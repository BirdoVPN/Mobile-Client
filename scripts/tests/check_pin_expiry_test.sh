#!/usr/bin/env bash
#
# Self-test for scripts/check_pin_expiry.sh, run by the required "Lint Check"
# job: the check is date-driven, so without fixed dates nobody would see it
# fail until the window opened on main (MR-1669).
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
CHECK="$HERE/../check_pin_expiry.sh"
fail=0
t="$(mktemp -d)"
trap 'rm -rf "$t"' EXIT

config() { # file, expiration attribute value ('' for none)
    {
        echo '<network-security-config><domain-config>'
        echo '<!-- `expiration` below: the date Android stops enforcing -->'
        if [ -n "$2" ]; then
            echo "<pin-set expiration=\"$2\"><pin digest=\"SHA-256\">AAAA</pin></pin-set>"
        else
            echo '<pin-set><pin digest="SHA-256">AAAA</pin></pin-set>'
        fi
        echo '</domain-config></network-security-config>'
    } > "$1"
}

expect() { # name, today, want-exit, want-text, config
    local out status=0
    out=$(PIN_EXPIRY_TODAY="$2" bash "$CHECK" "$5" 2>&1) || status=$?
    if [ "$status" -ne "$3" ]; then
        echo "::error::check_pin_expiry: $1: exit $status, want $3. Output:"
        printf '%s\n' "$out" | sed 's/^/    /'
        fail=1
    elif ! printf '%s\n' "$out" | grep -qF -- "$4"; then
        echo "::error::check_pin_expiry: $1: output lacks '$4'. Output:"
        printf '%s\n' "$out" | sed 's/^/    /'
        fail=1
    elif printf '%s\n' "$out" | grep -qi 'fail to connect'; then
        echo "::error::check_pin_expiry: $1: says the app fails to connect; Android stops enforcing the pin-set instead"
        fail=1
    elif printf '%s\n' "$out" | grep -qi 'unpinned'; then
        echo "::error::check_pin_expiry: $1: says connections go unpinned; the release builds' OkHttp CertificatePinner has no expiry"
        fail=1
    else
        echo "  ok: $1"
    fi
}

config "$t/nsc.xml" 2027-06-01
expect "229 days out: OK"                 2026-10-15 0 "Pin expiry OK: 229 days"      "$t/nsc.xml"
expect "90 days out: still OK"            2027-03-03 0 "Pin expiry OK: 90 days"       "$t/nsc.xml"
expect "89 days out: warns"               2027-03-04 0 "::warning"                    "$t/nsc.xml"
expect "the warning names both layers"    2027-03-04 0 "OkHttp CertificatePinner keeps pinning API calls" "$t/nsc.xml"
expect "30 days out: warns, not yet fail" 2027-05-02 0 "::warning"                    "$t/nsc.xml"
expect "29 days out: FAILS"               2027-05-03 1 "expires in 29 days"           "$t/nsc.xml"
expect "the failure names what happens"   2027-05-03 1 "stops enforcing the network_security_config pin-set" "$t/nsc.xml"
expect "and what does not stop"           2027-05-03 1 "has no expiry and keeps pinning API calls in release builds" "$t/nsc.xml"
expect "expiry day: FAILS"                2027-06-01 1 "expires in 0 days"            "$t/nsc.xml"
expect "expired: FAILS, still connects"   2027-06-02 1 "connections do not fail"      "$t/nsc.xml"
expect "expired: API calls stay pinned"   2027-06-02 1 "keeps pinning API calls"      "$t/nsc.xml"

config "$t/none.xml" ''
expect "no expiration at all"             2026-10-15 1 "No pin-set expiration found"  "$t/none.xml"
config "$t/bad.xml" 'someday'
expect "an expiration that is no date"    2026-10-15 1 "is not a date"                "$t/bad.xml"
expect "a missing file"                   2026-10-15 1 "not found"                    "$t/missing.xml"

if [ "$fail" -ne 0 ]; then
    echo "::error::check_pin_expiry_test FAILED"
    exit 1
fi
echo "check_pin_expiry_test: all cases pass"
