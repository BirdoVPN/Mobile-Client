#!/usr/bin/env bash
#
# Assert that every XCTestCase class in a test bundle's sources reported in an
# `xcodebuild test` log.
#
# A green xcodebuild proves the run did not FAIL; it does not prove a single
# test EXECUTED (ios.yml's test step once passed while running zero tests: a
# simulator that no longer existed, and no pipefail). The assertion that
# guarded this named five or six suites by hand while the bundle grew to 28,
# so a suite dropped from the bundle (a target membership lost in project.yml,
# say) went unnoticed as long as it was not one of the named few. The expected
# list is now read from the sources, so a new suite is covered the day it
# lands.
#
# Runs on macOS runners (apple-ci.yml, macos.yml): bash 3.2 only, which
# scripts/check_macos_bash_compat.sh enforces.
#
# Usage: scripts/check_xctest_suites_ran.sh <xcodebuild-log> <tests-dir>
#   e.g. scripts/check_xctest_suites_ran.sh "$RUNNER_TEMP/iostest.log" iosApp/BirdoVPNTests
set -euo pipefail

if [ "$#" -ne 2 ]; then
    echo "usage: check_xctest_suites_ran.sh <xcodebuild-log> <tests-dir>" >&2
    exit 2
fi
log=$1
dir=$2

if [ ! -s "$log" ]; then
    echo "::error::check_xctest_suites_ran: $log is missing or empty — the test run left no log"
    exit 1
fi

# Every `class Name: XCTestCase` (final, attributed or not), with // comments
# stripped first so a commented-out suite is not expected.
suites=$(cat "$dir"/*.swift 2>/dev/null \
    | sed -E 's#//.*$##' \
    | grep -oE 'class[[:space:]]+[A-Za-z_][A-Za-z0-9_]*[[:space:]]*:[[:space:]]*XCTestCase' \
    | sed -E 's/^class[[:space:]]+([A-Za-z_][A-Za-z0-9_]*).*/\1/' \
    | sort -u || true)
if [ -z "$suites" ]; then
    echo "::error::check_xctest_suites_ran: found no XCTestCase class in $dir/*.swift — the scan is broken, not the bundle"
    exit 1
fi

expected=0
missing=0
for suite in $suites; do
    expected=$((expected + 1))
    if ! grep -qF "Test Suite '$suite'" "$log"; then
        echo "::error::$suite never reported — it is declared in $dir but did not run"
        missing=$((missing + 1))
    fi
done

if [ "$missing" -ne 0 ]; then
    echo "check_xctest_suites_ran: $missing of $expected suites did not run"
    exit 1
fi
echo "check_xctest_suites_ran: all $expected suites declared in $dir reported:"
echo "$suites" | tr '\n' ' '
echo
