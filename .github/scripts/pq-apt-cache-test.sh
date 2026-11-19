#!/usr/bin/env bash
# ============================================================================
# Self-test for .github/scripts/pq-apt-cache.sh (the QEMU job's .deb archive
# cache, MR-2347), run by that job before it stages anything. apt reuses a
# cached archive on its name and size alone, so the stage step's hash check is
# the whole of the protection of apt's archive directory; prove it refuses
# what it must, with fake archives in temp directories (no apt, no root):
#
#   stage    an archive matching the plan        -> staged, byte-identical
#            same size, different bytes           -> refused (the case apt's
#                                                    own size check accepts)
#            a symlink to a matching archive      -> refused
#            planned but not in the cache         -> left for apt
#            in the cache but not in the plan     -> ignored
#            no restored directory / no plan      -> stages nothing, exit 0
#   parse    the SHA256 print-uris line           -> plan line, with its pool path
#            a URI with no clean pool path        -> plan line, path "-"
#            MD5Sum / SHA512 / no hash            -> exit 1 (never trusted)
#            a file name with a slash or ..       -> exit 1
#            no lines at all                      -> exit 1
#   fetch    (MR-2438; curl is a stub, no network)
#            an archive already restored intact   -> not downloaded again
#            a missing or altered archive         -> downloaded, kept only when
#                                                    it hashes to the plan
#            a slow mirror / one serving other
#            bytes                                -> dropped for the rest of the run
#            a mirror lacking the file (HTTP 4xx) -> next mirror, not dropped
#            no pool path / no mirror has it      -> left for apt, exit 0
#            no budget / no plan                  -> nothing downloaded, exit 0
#   collect  every planned archive present        -> complete=true
#            one missing or altered               -> complete=false
#            an archive apt holds but the plan
#            does not name (an older version)     -> not collected
#   packages the list read from the action        -> plan refuses a bad name
# ============================================================================
set -euo pipefail

here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cache_sh="$here/pq-apt-cache.sh"
[ -f "$cache_sh" ] || { echo "::error::script missing: $cache_sh"; exit 1; }

work="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/pq-apt-cache-selftest.XXXXXX")"
trap 'rm -rf "$work"' EXIT

fail=0
check() { # check <description> <command...>
  local d="$1"
  shift
  if "$@"; then
    echo "ok    $d"
  else
    echo "::error::pq-apt-cache self-test: $d"
    fail=1
  fi
}
sha() { sha256sum "$1" | cut -d' ' -f1; }

restored="$work/restored"
dest="$work/archives"
mkdir -p "$restored" "$dest"
printf 'good archive bytes\n' >"$restored/good_1.0_amd64.deb"
printf 'what the index says\n' >"$work/expected-bad"
printf 'what the cache says\n' >"$restored/bad_2%3a1.0_amd64.deb" # same length, other bytes
printf 'linked archive\n' >"$work/link-target"
ln -s "$work/link-target" "$restored/link_1.0_amd64.deb"
printf 'not planned\n' >"$restored/extra_1.0_amd64.deb"

# Plan lines as `parse` writes them: name, size, SHA-256, pool path.
plan="$work/plan.txt"
{
  echo "good_1.0_amd64.deb 19 $(sha "$restored/good_1.0_amd64.deb") pool/main/g/good/good_1.0_amd64.deb"
  echo "bad_2%3a1.0_amd64.deb 20 $(sha "$work/expected-bad") pool/main/b/bad/bad_1.0_amd64.deb"
  echo "link_1.0_amd64.deb 15 $(sha "$work/link-target") -"
  echo "absent_1.0_amd64.deb 7 $(printf 'absent\n' | sha256sum | cut -d' ' -f1) pool/main/a/absent/absent_1.0_amd64.deb"
} >"$plan"

run() { APT_PLAN="$plan" APT_ARCHIVES="$dest" RUNNER_TEMP="$work" GITHUB_OUTPUT="$work/output" bash "$cache_sh" "$@"; }

# -- stage ---------------------------------------------------------------------
log=$(run stage "$restored")
echo "$log"
check "the matching archive is staged" test -f "$dest/good_1.0_amd64.deb"
check "the staged archive is byte-identical" cmp -s "$restored/good_1.0_amd64.deb" "$dest/good_1.0_amd64.deb"
check "same size, other bytes: refused" test ! -e "$dest/bad_2%3a1.0_amd64.deb"
check "a symlink: refused" test ! -e "$dest/link_1.0_amd64.deb"
check "an archive the plan does not name: ignored" test ! -e "$dest/extra_1.0_amd64.deb"
check "no verification copy left behind" test -z "$(find "$dest" -name '.birdo-verify.*')"
check "the summary counts 1 staged, 1 rejected, 2 not in the cache" grep -qF "1 verified and staged, 1 rejected, 2 not in the cache" <<<"$log"
check "no restored directory: exit 0, nothing staged" run stage "$work/does-not-exist"
check "no plan: exit 0, nothing staged" env APT_PLAN="$work/no-plan" APT_ARCHIVES="$dest" RUNNER_TEMP="$work" bash "$cache_sh" stage "$restored"

# -- parse ---------------------------------------------------------------------
h=$(printf 'x' | sha256sum | cut -d' ' -f1)
good_line="'mirror+file:/etc/apt/apt-mirrors.txt/pool/universe/q/qemu/qemu-user_8.2.2+ds-0ubuntu1.10_amd64.deb' qemu-user_1%3a8.2.2+ds-0ubuntu1.10_amd64.deb 104544 SHA256:$h"
parsed=$(run parse <<<"$good_line")
check "parse: a SHA256 line becomes a plan line (epoch's %3a kept, pool path from the URI)" test "$parsed" = "qemu-user_1%3a8.2.2+ds-0ubuntu1.10_amd64.deb 104544 $h pool/universe/q/qemu/qemu-user_8.2.2+ds-0ubuntu1.10_amd64.deb"
check "parse: an http URI's pool path" test "$(run parse <<<"'http://security.ubuntu.com/ubuntu/pool/main/g/gcc/x_1_amd64.deb' x_1_amd64.deb 5 SHA256:$h")" = "x_1_amd64.deb 5 $h pool/main/g/gcc/x_1_amd64.deb"
check "parse: no pool in the URI -> path '-' (cached, never fetched)" test "$(run parse <<<"'http://x/a.deb' a_1_amd64.deb 5 SHA256:$h")" = "a_1_amd64.deb 5 $h -"
check "parse: a pool path with .. -> path '-'" test "$(run parse <<<"'http://x/pool/main/../../etc/a.deb' a_1_amd64.deb 5 SHA256:$h")" = "a_1_amd64.deb 5 $h -"
check "parse: a pool path with // -> path '-'" test "$(run parse <<<"'http://x/pool/main//a.deb' a_1_amd64.deb 5 SHA256:$h")" = "a_1_amd64.deb 5 $h -"
refuse() { ! run parse <<<"$1" >/dev/null 2>&1; }
check "parse: MD5Sum refused" refuse "'http://x/a.deb' a_1_amd64.deb 5 MD5Sum:0123456789abcdef0123456789abcdef"
check "parse: SHA512 refused" refuse "'http://x/a.deb' a_1_amd64.deb 5 SHA512:$h$h"
check "parse: no hash refused" refuse "'http://x/a.deb' a_1_amd64.deb 5 "
check "parse: a short SHA256 refused" refuse "'http://x/a.deb' a_1_amd64.deb 5 SHA256:${h:0:63}"
check "parse: a path refused" refuse "'http://x/a.deb' ../a_1_amd64.deb 5 SHA256:$h"
check "parse: a slash refused" refuse "'http://x/a.deb' sub/a_1_amd64.deb 5 SHA256:$h"
check "parse: a leading dot refused" refuse "'http://x/a.deb' .._amd64.deb 5 SHA256:$h"
check "parse: one bad line fails the whole parse" refuse "$good_line"$'\n'"'http://x/b.deb' b_1_amd64.deb 5 MD5Sum:0123456789abcdef0123456789abcdef"
check "parse: no lines refused" refuse ""

# -- fetch ---------------------------------------------------------------------
# curl is a stub that serves by host: slow.test times out (curl exit 28, a
# partial file written), evil.test serves other bytes, gone.test answers 404
# (exit 22), good.test serves $work/pool. Every call's arguments are logged,
# one call per line.
fstubs="$work/fetch-stubs"
pool_dir="$work/pool"
mkdir -p "$fstubs" "$pool_dir/pool/main/b" "$pool_dir/pool/main/c" "$pool_dir/pool/main/a"
cat >"$fstubs/curl" <<'STUB'
#!/usr/bin/env bash
echo "$*" >>"$CURL_LOG"
out= url=
while [ $# -gt 0 ]; do
  case "$1" in
    -o) out=$2; shift 2 ;;
    --max-redirs | --proto | --proto-redir | --connect-timeout | --max-time | --speed-limit | --speed-time) shift 2 ;;
    -*) shift ;;
    *) url=$1; shift ;;
  esac
done
case "$url" in
  http://slow.test/*) printf 'part' >"$out"; exit 28 ;;
  http://evil.test/*) printf 'tampered bytes\n' >"$out" ;;
  http://gone.test/*) exit 22 ;;
  http://good.test/ubuntu/*)
    src="$POOL/${url#http://good.test/ubuntu/}"
    [ -f "$src" ] || exit 22
    cat "$src" >"$out" ;;
  *) exit 6 ;;
esac
STUB
chmod +x "$fstubs/curl"
printf 'archive a\n' >"$pool_dir/pool/main/a/a_1_amd64.deb"
printf 'archive b\n' >"$pool_dir/pool/main/b/b_1_amd64.deb"
printf 'archive c\n' >"$pool_dir/pool/main/c/c_1_amd64.deb"
fdir="$work/fetched"
mkdir -p "$fdir"
cp "$pool_dir/pool/main/a/a_1_amd64.deb" "$fdir/" # restored intact
printf 'archive C\n' >"$fdir/c_1_amd64.deb"       # restored, other bytes
fplan="$work/fetch-plan.txt"
{
  echo "a_1_amd64.deb 10 $(sha "$pool_dir/pool/main/a/a_1_amd64.deb") pool/main/a/a_1_amd64.deb"
  echo "b_1_amd64.deb 10 $(sha "$pool_dir/pool/main/b/b_1_amd64.deb") pool/main/b/b_1_amd64.deb"
  echo "c_1_amd64.deb 10 $(sha "$pool_dir/pool/main/c/c_1_amd64.deb") pool/main/c/c_1_amd64.deb"
  echo "d_1_amd64.deb 10 $(printf 'archive d\n' | sha256sum | cut -d' ' -f1) -"
  echo "e_1_amd64.deb 10 $(printf 'archive e\n' | sha256sum | cut -d' ' -f1) pool/main/e/e_1_amd64.deb"
} >"$fplan"
# The image's list: slow first, a line that is not an http(s) mirror, a 404
# mirror. Fallbacks: the evil one, the good one, slow again (asked once only).
printf 'http://slow.test/ubuntu/\tpriority:1\nfile:/not/a/mirror\tpriority:2\nhttp://gone.test/ubuntu\tpriority:3\n' >"$work/apt-mirrors.txt"
fetch_run() { # fetch_run [env...]: fetch into $fdir; its log on stdout
  : >"$work/curl-calls"
  env PATH="$fstubs:$PATH" CURL_LOG="$work/curl-calls" POOL="$pool_dir" APT_PLAN="$fplan" RUNNER_TEMP="$work" \
    APT_MIRRORS_FILE="$work/apt-mirrors.txt" PQ_APT_FALLBACK_MIRRORS="http://evil.test/ubuntu http://good.test/ubuntu/ http://slow.test/ubuntu" \
    "$@" bash "$cache_sh" fetch "$fdir" 2>&1
}
calls() { grep -cF "$1" "$work/curl-calls" || true; }
log=$(fetch_run)
echo "$log"
check "fetch: an archive restored intact is not downloaded again" test "$(calls /a_1_amd64.deb)" -eq 0
check "fetch: a missing archive is downloaded, byte-identical" cmp -s "$pool_dir/pool/main/b/b_1_amd64.deb" "$fdir/b_1_amd64.deb"
check "fetch: a restored archive with other bytes is replaced by a verified one" cmp -s "$pool_dir/pool/main/c/c_1_amd64.deb" "$fdir/c_1_amd64.deb"
check "fetch: the bytes the evil mirror served are not kept" test "$(sha "$fdir/b_1_amd64.deb")" = "$(sha "$pool_dir/pool/main/b/b_1_amd64.deb")"
check "fetch: a slow mirror is asked once, then dropped" test "$(calls http://slow.test/)" -eq 1
check "fetch: a mirror serving other bytes is asked once, then dropped" test "$(calls http://evil.test/)" -eq 1
check "fetch: a mirror lacking a file is not dropped (asked for b, c and e)" test "$(calls http://gone.test/)" -eq 3
check "fetch: the image list's non-http line is never asked" test "$(calls file:)" -eq 0
check "fetch: the trailing slash is dropped (one slash before pool/)" test "$(calls //pool/)" -eq 0
check "fetch: no pool path -> nothing asked, left for apt" test "$(calls /d_1_amd64.deb)" -eq 0 -a ! -e "$fdir/d_1_amd64.deb"
check "fetch: no mirror has it -> left for apt" test ! -e "$fdir/e_1_amd64.deb"
check "fetch: no partial or temporary file left behind" test -z "$(find "$fdir" -name '.birdo-fetch.*')"
check "fetch: the summary counts 1 restored, 2 fetched, 2 left for apt" grep -qF "1 already restored, 2 fetched and verified, 2 left for apt" <<<"$log"
check "fetch: every request carries the speed floor and http(s) only" test "$(grep -cF -- '--proto =http,https --proto-redir =http,https' "$work/curl-calls")" -eq "$(wc -l <"$work/curl-calls")" -a "$(grep -cF -- '--speed-limit 307200 --speed-time 10' "$work/curl-calls")" -eq "$(wc -l <"$work/curl-calls")"
rm -f "$fdir/b_1_amd64.deb" "$fdir/c_1_amd64.deb"
check "fetch: no budget -> exit 0, nothing downloaded" fetch_run PQ_APT_FETCH_BUDGET=0
check "fetch: no budget -> no mirror asked" test ! -s "$work/curl-calls" -a ! -e "$fdir/b_1_amd64.deb"
check "fetch: no plan -> exit 0" fetch_run APT_PLAN="$work/no-plan"

# -- collect -------------------------------------------------------------------
cplan="$work/collect-plan.txt"
held="$work/held"
mkdir -p "$held"
printf 'one\n' >"$held/one_1_amd64.deb"
printf 'two\n' >"$held/two_1_amd64.deb"
printf 'old\n' >"$held/one_0_amd64.deb" # an older version apt still holds
{
  echo "one_1_amd64.deb 4 $(sha "$held/one_1_amd64.deb")"
  echo "two_1_amd64.deb 4 $(sha "$held/two_1_amd64.deb")"
} >"$cplan"
collect() { APT_PLAN="$cplan" APT_ARCHIVES="$held" RUNNER_TEMP="$work" GITHUB_OUTPUT="$work/collect-output" bash "$cache_sh" collect "$work/out"; }
: >"$work/collect-output"
collect >/dev/null
check "collect: all present -> complete=true" grep -qxF "complete=true" "$work/collect-output"
check "collect: the archives are in the cache directory" cmp -s "$held/two_1_amd64.deb" "$work/out/two_1_amd64.deb"
check "collect: only the planned archives (the entry never grows)" test "$(find "$work/out" -type f | wc -l)" -eq 2
printf 'TWO\n' >"$held/two_1_amd64.deb"
: >"$work/collect-output"
collect >/dev/null
check "collect: an altered archive -> complete=false" grep -qxF "complete=false" "$work/collect-output"
check "collect: the altered archive is not kept" test ! -e "$work/out/two_1_amd64.deb"
rm -f "$held/two_1_amd64.deb"
: >"$work/collect-output"
collect >/dev/null
check "collect: a missing archive -> complete=false" grep -qxF "complete=false" "$work/collect-output"

# -- plan: the package list ------------------------------------------------------
# plan reads the action's list before it touches apt; a name that is not a
# package name turns the cache off (key empty) instead of reaching apt-get.
# sudo and apt-get are stubs here that only record a call: none of these cases
# may get as far as apt.
stubs="$work/stubs"
mkdir -p "$stubs"
for s in sudo apt-get; do
  printf '#!/usr/bin/env bash\necho "%s $*" >>"%s"\nexit 1\n' "$s" "$work/apt-calls" >"$stubs/$s"
  chmod +x "$stubs/$s"
done
plan_with() { # plan_with <action file>: plan's stdout+stderr; outputs in $work/plan-output
  : >"$work/plan-output"
  : >"$work/apt-calls"
  PATH="$stubs:$PATH" APT_PLAN="$work/p.txt" RUNNER_TEMP="$work" GITHUB_OUTPUT="$work/plan-output" PQ_ACTION="$1" bash "$cache_sh" plan 2>&1
}
fake_action="$work/action.yml"
printf '%s\n' '      run: |' \
  '        sudo timeout -k 30 600 apt-get install -y --no-install-recommends \' \
  '          qemu-user gcc-aarch64-linux-gnu \' \
  '          "$(evil)" || {' '          exit 1' '        }' >"$fake_action"
log=$(plan_with "$fake_action")
check "plan: a bad package name -> key empty, exit 0" grep -qxF "key=" "$work/plan-output"
check "plan: a bad package name is named as the reason" grep -qF "is not a package name" <<<"$log"
check "plan: a bad package name never reaches sudo or apt-get" test ! -s "$work/apt-calls"
log=$(plan_with "$work/missing.yml")
check "plan: no action file -> key empty, exit 0" grep -qxF "key=" "$work/plan-output"
check "plan: no action file never reaches sudo or apt-get" test ! -s "$work/apt-calls"
printf '%s\n' '      run: |' '        sudo apt-get install -y qemu-user' >"$fake_action"
log=$(plan_with "$fake_action")
check "plan: the install line changed shape -> key empty, exit 0" grep -qxF "key=" "$work/plan-output"
check "plan: a changed install line never reaches sudo or apt-get" test ! -s "$work/apt-calls"
# A failed (stubbed) apt-get update: cache off, exit 0, nothing planned.
printf '%s\n' '        sudo apt-get install -y --no-install-recommends \' '          qemu-user' >"$fake_action"
rm -f "$work/p.txt"
log=$(plan_with "$fake_action")
check "plan: a failed apt-get update -> key empty, exit 0, no plan" test -n "$(grep -xF 'key=' "$work/plan-output")" -a ! -e "$work/p.txt"
check "plan: the update is the bounded one (sudo timeout ... apt-get update)" grep -qE '^sudo timeout -k [0-9]+ [0-9]+ apt-get update$' "$work/apt-calls"
# The reader stops at the error handler's `||`, never taking `{` or `exit`.
printf '%s\n' '        sudo apt-get install -y --no-install-recommends \' '          a b \' '          c || {' '          exit 1' >"$fake_action"
check "packages: words up to the '||' and no further" test "$(PQ_ACTION="$fake_action" RUNNER_TEMP="$work" bash "$cache_sh" packages | tr '\n' ' ')" = "a b c "
# The real action, through the script's own reader: its list must parse, or
# every run would silently go uncached.
real=()
while IFS= read -r p; do real+=("$p"); done < <(PQ_ACTION="$here/../actions/pq-cpu-proof/action.yml" RUNNER_TEMP="$work" bash "$cache_sh" packages)
bad_names=0
for p in "${real[@]}"; do [[ $p =~ ^[a-z0-9][a-z0-9.+-]*$ ]] || bad_names=1; done
has_qemu=0
for p in "${real[@]}"; do [ "$p" = qemu-user ] && has_qemu=1; done
check "the real action's list parses (${#real[@]} packages: ${real[*]})" test "${#real[@]}" -ge 4 -a "$bad_names" -eq 0 -a "$has_qemu" -eq 1

if [ "$fail" -ne 0 ]; then
  echo "::error::pq-apt-cache-test.sh: at least one case failed - see above"
  exit 1
fi
echo "pq-apt-cache self-test: all cases passed"
