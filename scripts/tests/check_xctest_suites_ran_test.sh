#!/usr/bin/env bash
#
# Self-test for scripts/check_xctest_suites_ran.sh, run by the required
# "Lint Check" job. The assertion gates the Mac App Store upload (macos.yml)
# and Apple CI, and only runs on macOS runners, so its cases run here, on
# every pull request: a gate that cannot fail is not a gate.
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
GATE="$HERE/../check_xctest_suites_ran.sh"
fail=0
t="$(mktemp -d)"
trap 'rm -rf "$t"' EXIT

expect() { # name, want-exit, want-text, args...
    local name=$1 want=$2 text=$3
    shift 3
    local out status=0
    out=$(bash "$GATE" "$@" 2>&1) || status=$?
    if [ "$status" -ne "$want" ]; then
        echo "::error::check_xctest_suites_ran: $name: exit $status, want $want. Output:"
        printf '%s\n' "$out" | sed 's/^/    /'
        fail=1
    elif ! printf '%s\n' "$out" | grep -qF -- "$text"; then
        echo "::error::check_xctest_suites_ran: $name: output lacks '$text'. Output:"
        printf '%s\n' "$out" | sed 's/^/    /'
        fail=1
    else
        echo "  ok: $name"
    fi
}

# A bundle shaped like iosApp/BirdoVPNTests: two suites in one file, a final
# and an attributed one, a helper that is not a suite, and a commented-out one.
mkdir -p "$t/Tests"
cat > "$t/Tests/AlphaTests.swift" <<'EOF'
import XCTest
final class AlphaTests: XCTestCase {
    func testA() {}
}
class BetaTests : XCTestCase, SomeHelping {
    func testB() {}
}
// class RetiredTests: XCTestCase {}
struct NotASuite {}
EOF
cat > "$t/Tests/GammaTests.swift" <<'EOF'
import XCTest
@MainActor
final class GammaTests: XCTestCase {
    func testC() {}
}
EOF

suite_line() { printf "Test Suite '%s' started at 2026-10-15 14:00:23.113.\n" "$1"; }
{ suite_line AlphaTests; suite_line BetaTests; suite_line GammaTests; } > "$t/all.log"
{ suite_line AlphaTests; suite_line GammaTests; } > "$t/missing-beta.log"
: > "$t/empty.log"
mkdir -p "$t/NoSuites"
printf 'struct Nothing {}\n' > "$t/NoSuites/Nothing.swift"

expect "every declared suite reported"          0 "all 3 suites"                    "$t/all.log" "$t/Tests"
expect "a declared suite did not run"           1 "BetaTests never reported"        "$t/missing-beta.log" "$t/Tests"
expect "a commented-out suite is not expected"  0 "AlphaTests BetaTests GammaTests" "$t/all.log" "$t/Tests"
expect "an empty log fails"                     1 "missing or empty"                "$t/empty.log" "$t/Tests"
expect "a missing log fails"                    1 "missing or empty"                "$t/nope.log" "$t/Tests"
expect "no suites found is a broken scan"       1 "found no XCTestCase class"       "$t/all.log" "$t/NoSuites"
expect "a wrong directory is a broken scan"     1 "found no XCTestCase class"       "$t/all.log" "$t/NoSuchDir"
expect "usage"                                  2 "usage:"                          "$t/all.log"

# The real bundle, cross-checked against the file names: every
# iosApp/BirdoVPNTests/<Name>Tests.swift declares a suite <Name>Tests, so a log
# holding exactly those suites must pass, and the same log missing any one of
# them must fail. A scan that missed a suite would pass the second half.
REPO="$(cd -- "$HERE/../.." && pwd)"
names=""
for f in "$REPO"/iosApp/BirdoVPNTests/*Tests.swift; do
    names="$names $(basename "$f" .swift)"
done
n=0
for s in $names; do n=$((n + 1)); done
[ "$n" -gt 0 ] || { echo "::error::no iosApp/BirdoVPNTests/*Tests.swift found"; exit 1; }
# Files may declare more than one suite (StoreCatalogTests.swift also holds
# StorePurchaseGateTests); those come from a deliberately cruder scan.
extra=$(grep -h 'XCTestCase' "$REPO"/iosApp/BirdoVPNTests/*.swift | grep -v '^[[:space:]]*//' \
    | grep -oE 'class [A-Za-z0-9_]+' | sed 's/^class //' || true)
for s in $names $extra; do suite_line "$s"; done > "$t/real.log"
expect "the real bundle: $n files, every suite reported" 0 "suites declared in" \
    "$t/real.log" "$REPO/iosApp/BirdoVPNTests"
for s in $names; do
    grep -vF "Test Suite '$s'" "$t/real.log" > "$t/real-minus.log"
    expect "the real bundle without $s" 1 "$s never reported" "$t/real-minus.log" "$REPO/iosApp/BirdoVPNTests"
done

if [ "$fail" -ne 0 ]; then
    echo "::error::check_xctest_suites_ran_test FAILED"
    exit 1
fi
echo "check_xctest_suites_ran_test: all cases pass"
