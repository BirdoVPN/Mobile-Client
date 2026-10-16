#!/usr/bin/env bash
#
# Decide whether an `adb shell am instrument -w -r` run passed (MR-1410).
#
# `am instrument` exits 0 whether the tests passed, failed or never started,
# so its status says nothing. This reads its raw output instead and passes
# only when JUnit reported "OK (N tests)", N is exactly the number of @Test
# methods under the androidTest sources (a run that silently executed fewer,
# or none, is not a pass), and nothing failed, crashed or refused to start.
#
# Usage: scripts/check_instrumentation_result.sh <am-instrument-log> <androidTest-dir>
#   e.g. scripts/check_instrumentation_result.sh "$RUNNER_TEMP/instrument.log" app/src/androidTest
set -euo pipefail

if [ "$#" -ne 2 ]; then
    echo "usage: check_instrumentation_result.sh <am-instrument-log> <androidTest-dir>" >&2
    exit 2
fi
log=$1
dir=$2

if [ ! -s "$log" ]; then
    echo "::error::check_instrumentation_result: $log is missing or empty — am instrument printed nothing"
    exit 1
fi
# adb relays the device's line endings; strip CRs before matching.
out=$(tr -d '\r' < "$log")

declared=$(find "$dir" -type f \( -name '*.kt' -o -name '*.java' \) -exec cat {} + 2>/dev/null \
    | grep -cE '^[[:space:]]*@Test([[:space:](]|$)' || true)
if [ "${declared:-0}" -eq 0 ]; then
    echo "::error::check_instrumentation_result: found no @Test method under $dir — the scan is broken, not the suite"
    exit 1
fi

bad=0
for marker in 'FAILURES!!!' 'INSTRUMENTATION_FAILED' 'Process crashed' 'shortMsg='; do
    if printf '%s\n' "$out" | grep -qF -- "$marker"; then
        echo "::error::check_instrumentation_result: the run reported '$marker'"
        bad=1
    fi
done
if ! printf '%s\n' "$out" | grep -qx 'INSTRUMENTATION_CODE: -1'; then
    echo "::error::check_instrumentation_result: no 'INSTRUMENTATION_CODE: -1' — the instrumentation did not finish normally"
    bad=1
fi
ran=$(printf '%s\n' "$out" | sed -nE 's/^OK \(([0-9]+) tests?\)$/\1/p' | tail -1)
if [ -z "$ran" ]; then
    echo "::error::check_instrumentation_result: no 'OK (N tests)' line — JUnit did not report a passing run"
    bad=1
elif [ "$ran" -ne "$declared" ]; then
    echo "::error::check_instrumentation_result: $ran tests ran, but $dir declares $declared @Test methods"
    bad=1
fi

if [ "$bad" -ne 0 ]; then
    echo "--- tail of $log:"
    printf '%s\n' "$out" | tail -40
    exit 1
fi
echo "check_instrumentation_result: OK, all $declared instrumented tests ran and passed:"
printf '%s\n' "$out" | sed -nE 's/^INSTRUMENTATION_STATUS: test=(.*)$/  \1/p' | sort -u
