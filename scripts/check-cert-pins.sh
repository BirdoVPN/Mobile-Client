#!/usr/bin/env bash
#
# check-cert-pins.sh — the mechanism behind the "kept in sync" contract.
#
# Certificate pins are duplicated across several source files in this repo, in
# three different languages. Until now the only thing keeping them equal was a
# doc comment asserting that they were. This script is the enforcement.
#
# It runs THREE independent checks:
#
#   1. VENDOR    third_party/cert-pins.json is byte-identical to the SSOT in
#                birdo-shared/cert-pins.json (skipped when birdo-shared is not
#                checked out alongside, e.g. on CI runners).
#   2. DIVERGENCE  every pin file in this repo declares EXACTLY the pin set the
#                SSOT lists for the host it pins. Extra pin, missing pin, or
#                typo => failure, naming the file and the offending hash.
#   3. LIVENESS  for every pinned host, the LIVE certificate chain is fetched
#                and must contain at least one pinned SPKI. This is per-host:
#                a host whose pins have all gone stale fails even if other
#                hosts are fine.
#
# Check 3 is the one the old cert-pin-watchdog.yml got wrong. It read only
# cert_pin.rs, ignored the DoH hosts entirely, and failed only when NONE of the
# pins across the whole file matched — so a DoH provider that had migrated CA
# (as cloudflare-dns.com actually had, silently, for months) passed cleanly.
#
# Exit 0 = all checks pass. Exit 1 = a real divergence or a dead pin set.
#
# Usage:
#   scripts/check-cert-pins.sh              # all checks
#   scripts/check-cert-pins.sh --offline    # skip check 3 (no network)

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SSOT="$REPO_ROOT/third_party/cert-pins.json"
UPSTREAM="$REPO_ROOT/../birdo-shared/cert-pins.json"

OFFLINE=0
[ "${1:-}" = "--offline" ] && OFFLINE=1

FAILED=0
fail() { echo "FAIL  $*" >&2; FAILED=1; }
ok()   { echo "ok    $*"; }
info() { echo "      $*"; }

command -v python3 >/dev/null 2>&1 && PY=python3 || PY=python

if [ ! -f "$SSOT" ]; then
  echo "FAIL  vendored SSOT missing: $SSOT" >&2
  exit 1
fi

echo "=== 1. vendored SSOT vs birdo-shared ==="
if [ -f "$UPSTREAM" ]; then
  if "$PY" - "$SSOT" "$UPSTREAM" <<'PYEOF'
import json, sys
a = json.load(open(sys.argv[1])); b = json.load(open(sys.argv[2]))
sys.exit(0 if a == b else 1)
PYEOF
  then ok "third_party/cert-pins.json matches birdo-shared/cert-pins.json"
  else fail "third_party/cert-pins.json has DRIFTED from birdo-shared/cert-pins.json — re-vendor it"
  fi
else
  info "skipped — birdo-shared not checked out alongside this repo"
fi

echo
echo "=== 2. pin files vs SSOT (divergence) ==="
"$PY" - "$SSOT" "$REPO_ROOT" <<'PYEOF'
import json, re, sys, os

ssot_path, root = sys.argv[1], sys.argv[2]
ssot = json.load(open(ssot_path))

B64 = r'[A-Za-z0-9+/]{42,44}='

def strip_line_comments(text, markers=('//',)):
    """Drop comment tails so a retired pin left in a comment cannot satisfy
    the check — the exact hole that lets a 'fixed' pin quietly stay dead."""
    out = []
    for line in text.splitlines():
        for m in markers:
            i = line.find(m)
            if i != -1:
                line = line[:i]
        out.append(line)
    return "\n".join(out)

def strip_xml_comments(text):
    return re.sub(r'<!--.*?-->', '', text, flags=re.S)

def section(text, start_re, end_re):
    m = re.search(start_re, text, re.M)
    if not m:
        return None
    rest = text[m.end():]
    e = re.search(end_re, rest, re.M)
    return rest[:e.start()] if e else None

# Each extractor returns {host: set(pins)} from one file, or None if the file's
# structure could not be parsed (which is itself a failure — a parser that has
# silently stopped matching is indistinguishable from a file with no pins).
def rust_api(text):
    s = section(strip_line_comments(text),
                r'PINNED_SPKI_SHA256\s*:\s*&\[&str\]\s*=\s*&\[', r'^\];')
    return None if s is None else {"birdo.app": set(re.findall(B64, s))}

def rust_doh(text):
    s = section(strip_line_comments(text),
                r'DOH_PROVIDERS\s*:\s*&\[DoHProvider\]\s*=\s*&\[', r'^\];')
    if s is None:
        return None
    out = {}
    for host, body in re.findall(r'host:\s*"([^"]+)".*?pins:\s*&\[(.*?)\]', s, re.S):
        out[host] = set(re.findall(B64, body))
    return out or None

def kotlin_api(text):
    # Kotlin writes OkHttp pins as "sha256/<hash>". Match that prefix explicitly:
    # a bare B64 findall would swallow the '/' of 'sha256/' into the hash.
    s = section(strip_line_comments(text), r'val pins\s*=\s*arrayOf\(', r'^\s*\)')
    if s is None:
        return None
    return {"birdo.app": set(re.findall(r'"sha256/(' + B64 + r')"', s))}

def kotlin_doh(text):
    s = section(strip_line_comments(text),
                r'CertificatePinner\.Builder\(\)', r'\.build\(\)')
    if s is None:
        return None
    out = {}
    for host, pin in re.findall(r'\.add\(\s*"([^"]+)"\s*,\s*"sha256/(' + B64 + r')"', s):
        out.setdefault(host, set()).add(pin)
    return out or None

def swift_api(text):
    s = section(strip_line_comments(text),
                r'static let pins\s*:\s*Set<String>\s*=\s*\[', r'^\s*\]')
    return None if s is None else {"birdo.app": set(re.findall(B64, s))}

def android_xml(text):
    t = strip_xml_comments(text)
    out = {}
    for block in re.findall(r'<domain-config>(.*?)</domain-config>', t, re.S):
        hosts = re.findall(r'<domain[^>]*>([^<]+)</domain>', block)
        pins = set(re.findall(r'<pin[^>]*>\s*(' + B64 + r')\s*</pin>', block))
        for h in hosts:
            out.setdefault(h.strip(), set()).update(pins)
    return out or None

EXTRACTORS = [
    ("src-tauri/src/api/cert_pin.rs",                                       rust_api),
    ("src-tauri/src/vpn/doh.rs",                                            rust_doh),
    ("app/src/main/java/app/birdo/vpn/di/NetworkModule.kt",                 kotlin_api),
    ("app/src/main/java/app/birdo/vpn/data/network/DohResolver.kt",         kotlin_doh),
    ("app/src/main/res/xml/network_security_config.xml",                    android_xml),
    ("iosApp/iosApp/Services/APIClient.swift",                              swift_api),
    ("iosApp/PacketTunnel/PacketTunnelProvider.swift",                      swift_api),
]

expected = {h: {p["hash"] for p in v["pins"]} for h, v in ssot["hosts"].items()}
retired  = {r["hash"] for r in ssot.get("_removed", [])}

seen_any = False
failed = False
for rel, fn in EXTRACTORS:
    path = os.path.join(root, rel)
    if not os.path.exists(path):
        continue
    seen_any = True
    got = fn(open(path, encoding="utf-8").read())
    if got is None:
        print(f"FAIL  {rel}: could not parse the pin declaration - "
              f"the extractor in check-cert-pins.sh needs updating "
              f"(a silently-unparsed file must never pass)")
        failed = True
        continue
    for host, pins in got.items():
        want = expected.get(host)
        if want is None:
            print(f"FAIL  {rel}: pins host '{host}' which the SSOT does not declare")
            failed = True
            continue
        missing, extra = want - pins, pins - want
        if not missing and not extra:
            print(f"ok    {rel} [{host}] {len(pins)} pins match SSOT")
            continue
        failed = True
        for p in sorted(missing):
            print(f"FAIL  {rel} [{host}] MISSING pin {p}")
        for p in sorted(extra):
            note = " (retired in SSOT._removed — delete it here)" if p in retired else ""
            print(f"FAIL  {rel} [{host}] UNKNOWN pin {p}{note}")

if not seen_any:
    print("FAIL  no known pin files found in this repo — is the extractor list stale?")
    failed = True

# ---------------------------------------------------------------------------
# SWEEP: find pin files nobody registered above.
#
# The extractor list is hand-maintained, which is the same weakness that
# produced the drift in the first place: a new file carrying pins is simply
# never looked at, and the check reports green. This sweep walks the repo for
# any file containing a hash the SSOT knows about and fails on any that no
# extractor reads. A pin site the checker cannot see is a pin site that rots.
#
# It is how iosApp/PacketTunnel/PacketTunnelProvider.swift was found: a second,
# FAIL-CLOSED pinning delegate carrying its own stale copy of the pin set.
# ---------------------------------------------------------------------------
known_hashes = set()
for _pins in expected.values():
    known_hashes |= _pins
known_hashes |= retired

covered = {os.path.normpath(rel) for rel, _ in EXTRACTORS}
covered.add(os.path.normpath("third_party/cert-pins.json"))
covered.add(os.path.normpath("scripts/check-cert-pins.sh"))

SKIP_DIRS = {".git", "build", "target", "node_modules", "dist", ".gradle",
             "DerivedData", "Pods", ".idea", "vendor", "third_party"}
TEXT_EXT = {".rs", ".kt", ".kts", ".java", ".swift", ".xml", ".json", ".m",
            ".mm", ".h", ".c", ".cpp", ".ts", ".js", ".py", ".sh", ".yml",
            ".yaml", ".toml", ".md", ".plist", ".gradle", ".properties"}

unregistered = []
for dirpath, dirnames, filenames in os.walk(root):
    dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
    for fn in filenames:
        if os.path.splitext(fn)[1].lower() not in TEXT_EXT:
            continue
        full = os.path.join(dirpath, fn)
        rel = os.path.normpath(os.path.relpath(full, root))
        if rel in covered:
            continue
        try:
            body = open(full, encoding="utf-8", errors="ignore").read()
        except OSError:
            continue
        hits = sorted(h for h in known_hashes if h in body)
        if hits:
            unregistered.append((rel, hits))

for rel, hits in unregistered:
    print("FAIL  " + rel + ": contains " + str(len(hits)) + " known certificate "
          "pin(s), but no extractor in check-cert-pins.sh reads this file, so "
          "its pins are never checked against the SSOT. Add an extractor.")
    for h in hits[:3]:
        print("        " + h)
    failed = True

if not unregistered:
    print("ok    sweep: no unregistered pin-bearing files (" +
          str(len(covered) - 2) + " pin files known to the checker)")

sys.exit(1 if failed else 0)
PYEOF
[ $? -ne 0 ] && FAILED=1

if [ "$OFFLINE" -eq 1 ]; then
  echo
  echo "=== 3. live chain liveness — SKIPPED (--offline) ==="
  exit $FAILED
fi

echo
echo "=== 3. live chain liveness (per host) ==="

# Hosts to dial, and the SNI/connect address to reach them by. The DoH hosts
# are dialled by a pinned IP because a hostile or captive local resolver can
# point the name anywhere — exactly what happened on the machine this script
# was written on, where cloudflare-dns.com resolved to an ISP landing page.
connect_addr() {
  case "$1" in
    birdo.app)          echo "api.birdo.app:443" ;;
    cloudflare-dns.com) echo "1.1.1.1:443" ;;
    dns.google)         echo "8.8.8.8:443" ;;
    dns.quad9.net)      echo "9.9.9.9:443" ;;
    *)                  echo "$1:443" ;;
  esac
}
sni_for() { [ "$1" = "birdo.app" ] && echo "api.birdo.app" || echo "$1"; }

HOSTS=$("$PY" -c "import json,sys;print(' '.join(json.load(open(sys.argv[1]))['hosts']))" "$SSOT")

for host in $HOSTS; do
  addr=$(connect_addr "$host"); sni=$(sni_for "$host")
  chain=$(echo | openssl s_client -connect "$addr" -servername "$sni" -showcerts 2>/dev/null)
  if ! echo "$chain" | grep -q "BEGIN CERTIFICATE"; then
    fail "$host: could not retrieve a certificate chain from $addr"
    continue
  fi

  tmp=$(mktemp -d)
  echo "$chain" | awk -v d="$tmp" '/-----BEGIN CERTIFICATE-----/{n++} n{print > (d "/c" n ".pem")}'
  live=""
  for f in "$tmp"/c*.pem; do
    [ -f "$f" ] || continue
    h=$(openssl x509 -in "$f" -pubkey -noout 2>/dev/null \
          | openssl pkey -pubin -outform DER 2>/dev/null \
          | openssl dgst -sha256 -binary 2>/dev/null | openssl base64)
    [ -n "$h" ] && live="$live$h"$'\n'
  done
  rm -rf "$tmp"

  pinned=$("$PY" -c "
import json,sys
d=json.load(open(sys.argv[1]))
print('\n'.join(p['hash'] for p in d['hosts'][sys.argv[2]]['pins']))" "$SSOT" "$host")

  match=""
  while IFS= read -r h; do
    [ -z "$h" ] && continue
    if printf '%s\n' "$pinned" | grep -qxF "$h"; then match="$h"; break; fi
  done <<< "$live"

  if [ -n "$match" ]; then
    ok "$host: live chain satisfies pin $match"
  else
    fail "$host: NO pinned SPKI is present in the live chain — clients pinning this host CANNOT CONNECT."
    info "  live chain:  $(echo "$live" | tr '\n' ' ')"
    info "  pinned:      $(echo "$pinned" | tr '\n' ' ')"
    info "  Fix by ADDING the new chain pin to birdo-shared/cert-pins.json and every"
    info "  pin file, shipping it, and only then removing the old one."
  fi

  # Leaf expiry is informational: chain-SPKI pins survive a leaf renewal.
  if [ "$host" = "birdo.app" ]; then
    na=$(echo "$chain" | openssl x509 -noout -enddate 2>/dev/null | sed 's/notAfter=//')
    if [ -n "$na" ]; then
      days=$(( ( $(date -u -d "$na" +%s) - $(date -u +%s) ) / 86400 ))
      info "  leaf expires $na ($days days) — FYI only, pins are on the CA chain"
      declared=$("$PY" -c "
import json,sys;print(json.load(open(sys.argv[1]))['hosts']['birdo.app'].get('leaf_expires',''))" "$SSOT")
      actual=$(date -u -d "$na" +%Y-%m-%d)
      if [ -n "$declared" ] && [ "$declared" != "$actual" ]; then
        fail "birdo.app: SSOT declares leaf_expires=$declared but the live leaf expires $actual"
      fi
    fi
  fi
done

echo
if [ "$FAILED" -eq 0 ]; then echo "ALL CERT-PIN CHECKS PASSED"; else echo "CERT-PIN CHECKS FAILED" >&2; fi
exit $FAILED
