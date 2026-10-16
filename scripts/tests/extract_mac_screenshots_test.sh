#!/usr/bin/env bash
#
# Self-test for scripts/extract_mac_screenshots.py (MR-1797), run by the
# required "Lint Check" job. That script decides whether macos.yml's
# screenshots-macos job is green, and the job runs only on a manual dispatch,
# so a check that cannot fail would otherwise stay unnoticed until a store
# submission went out with one screenshot. Each case is a fixture shaped like
# `xcresulttool export attachments` output.
#
# Usage: extract_mac_screenshots_test.sh [path/to/extract_mac_screenshots.py]
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="${1:-$HERE/../extract_mac_screenshots.py}"
fail=0
t="$(mktemp -d)"
trap 'rm -rf "$t"' EXIT

# fixture DIR NAME... : a manifest naming each capture the way XCUITest does
# ("<name>_<n>_<UUID>.png"), one PNG-named file per capture, plus a UI
# hierarchy attachment the script must ignore.
fixture() {
    local dir="$1"; shift
    mkdir -p "$dir"
    local entries="" i=0 name
    for name in "$@"; do
        printf 'png %s\n' "$name" > "$dir/att-$i.png"
        entries="$entries{\"suggestedHumanReadableName\": \"${name}_0_00000000-0000-0000-0000-00000000000$i.png\", \"exportedFileName\": \"att-$i.png\"},"
        i=$((i + 1))
    done
    printf 'tree\n' > "$dir/hierarchy.txt"
    entries="$entries{\"suggestedHumanReadableName\": \"App UI hierarchy\", \"exportedFileName\": \"hierarchy.txt\"}"
    printf '[{"testIdentifier": "ScreenshotTests/testCaptureAppStoreScreenshots()", "attachments": [%s]}]\n' "$entries" > "$dir/manifest.json"
}

# case NAME WANT-EXIT EXPECTED-FILE-COUNT ATT-DIR
runs=0
case_() {
    local name="$1" want="$2" files="$3" att="$4" got=0
    runs=$((runs + 1))
    local out="$t/out-$runs"
    mkdir -p "$out"
    python3 "$SCRIPT" "$att" "$out" > "$t/log" 2>&1 || got=$?
    local n
    n=$(find "$out" -name 'mac-*.png' | wc -l | tr -d ' ')
    if [ "$got" = "$want" ] && [ "$n" = "$files" ]; then
        echo "  ok: $name (exit $got, $n file(s))"
    else
        echo "::error::extract_mac_screenshots: $name: exit $got (want $want), $n file(s) (want $files)"
        sed 's/^/    /' "$t/log"
        fail=1
    fi
}

fixture "$t/full" 00-launch 01-connect 02-servers 03-settings
case_ "all four store screenshots" 0 4 "$t/full"

fixture "$t/warm" 00-launch 00-already-signed-in 01-connect 02-servers 03-settings
case_ "a warm keychain adds 00-already-signed-in" 0 5 "$t/warm"

# The case the old 'n == 0' check passed: the server row was never found.
fixture "$t/no-servers" 00-launch 01-connect 03-settings
case_ "02-servers missing" 1 3 "$t/no-servers"

fixture "$t/launch-only" 00-launch
case_ "only 00-launch" 1 1 "$t/launch-only"

fixture "$t/stuck" 00-launch 99-stuck
case_ "stuck at sign-in (99-stuck)" 1 2 "$t/stuck"

fixture "$t/stuck-late" 00-launch 01-connect 02-servers 03-settings 99-stuck
case_ "a 99-stuck capture fails even beside the full set" 1 5 "$t/stuck-late"

fixture "$t/none"
case_ "no named capture" 1 0 "$t/none"

mkdir -p "$t/no-manifest"
case_ "no manifest.json" 1 0 "$t/no-manifest"

# The missing names are spelled out, so the job log says which screen failed.
python3 "$SCRIPT" "$t/no-servers" "$t/out-names" > "$t/log" 2>&1 || true
if grep -q "missing store screenshot(s): 02-servers" "$t/log"; then
    echo "  ok: the error names the missing capture"
else
    echo "::error::extract_mac_screenshots: the error does not name 02-servers"
    sed 's/^/    /' "$t/log"
    fail=1
fi

if [ "$fail" -ne 0 ]; then
    exit 1
fi
echo "extract_mac_screenshots: all cases passed"
