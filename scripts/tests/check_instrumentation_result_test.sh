#!/usr/bin/env bash
#
# Self-test for scripts/check_instrumentation_result.sh, run by the required
# "Lint Check" job. `am instrument` exits 0 on failures too, so this script is
# the only thing that can turn the emulator job red; its cases run on every
# pull request (MR-1410).
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
CHECK="$HERE/../check_instrumentation_result.sh"
fail=0
t="$(mktemp -d)"
trap 'rm -rf "$t"' EXIT

expect() { # name, want-exit, want-text, log, dir
    local out status=0
    out=$(bash "$CHECK" "$4" "$5" 2>&1) || status=$?
    if [ "$status" -ne "$2" ]; then
        echo "::error::check_instrumentation_result: $1: exit $status, want $2. Output:"
        printf '%s\n' "$out" | sed 's/^/    /'
        fail=1
    elif ! printf '%s\n' "$out" | grep -qF -- "$3"; then
        echo "::error::check_instrumentation_result: $1: output lacks '$3'. Output:"
        printf '%s\n' "$out" | sed 's/^/    /'
        fail=1
    else
        echo "  ok: $1"
    fi
}

# Three @Test methods, as in app/src/androidTest today, plus a decoy.
mkdir -p "$t/src/a"
cat > "$t/src/a/ThreeTest.kt" <<'EOF'
class ThreeTest {
    @Test
    fun one() {}
    @Test fun two() {}
    @Test()
    fun three() {}
    // A comment about @Testing is not a test.
    @TestOnly fun helper() {}
}
EOF
mkdir -p "$t/empty"
printf 'class Nothing\n' > "$t/empty/Nothing.kt"

status_block() { # test name, code
    printf 'INSTRUMENTATION_STATUS: class=app.birdo.vpn.perf.ThreeTest\r\n'
    printf 'INSTRUMENTATION_STATUS: test=%s\r\n' "$1"
    printf 'INSTRUMENTATION_STATUS_CODE: 1\r\n'
    printf 'INSTRUMENTATION_STATUS: class=app.birdo.vpn.perf.ThreeTest\r\n'
    printf 'INSTRUMENTATION_STATUS: test=%s\r\n' "$1"
    printf 'INSTRUMENTATION_STATUS_CODE: %s\r\n' "$2"
}
{ status_block one 0; status_block two 0; status_block three 0
  printf 'INSTRUMENTATION_RESULT: stream=\r\n\r\nTime: 4.2\r\n\r\nOK (3 tests)\r\n\r\n\r\nINSTRUMENTATION_CODE: -1\r\n'
} > "$t/pass.log"
{ status_block one 0; status_block two -2; status_block three 0
  printf 'INSTRUMENTATION_RESULT: stream=\r\n\r\nTime: 4.2\r\nThere was 1 failure:\r\n1) two(app.birdo.vpn.perf.ThreeTest)\r\n'
  printf 'java.lang.AssertionError: frames arrived but were labelled `off`\r\n\r\nFAILURES!!!\r\nTests run: 3,  Failures: 1\r\n\r\n\r\nINSTRUMENTATION_CODE: -1\r\n'
} > "$t/failed.log"
{ status_block one 0
  printf 'INSTRUMENTATION_RESULT: shortMsg=Process crashed.\r\nINSTRUMENTATION_CODE: 0\r\n'
} > "$t/crashed.log"
printf 'INSTRUMENTATION_FAILED: app.birdo.vpn.debug.test/androidx.test.runner.AndroidJUnitRunner\r\nINSTRUMENTATION_CODE: 0\r\n' > "$t/nostart.log"
{ status_block one 0; status_block two 0
  printf 'INSTRUMENTATION_RESULT: stream=\r\n\r\nTime: 2.0\r\n\r\nOK (2 tests)\r\n\r\n\r\nINSTRUMENTATION_CODE: -1\r\n'
} > "$t/fewer.log"
printf 'INSTRUMENTATION_RESULT: stream=\r\n\r\nTime: 0.0\r\n\r\nOK (0 tests)\r\n\r\n\r\nINSTRUMENTATION_CODE: -1\r\n' > "$t/zero.log"
: > "$t/empty.log"

expect "all three ran and passed (CRLF output)"  0 "all 3 instrumented tests ran and passed" "$t/pass.log"    "$t/src"
expect "the passing tests are listed"            0 "  three"                                 "$t/pass.log"    "$t/src"
expect "a failed test"                           1 "FAILURES!!!"                             "$t/failed.log"  "$t/src"
expect "the app process crashed"                 1 "Process crashed"                         "$t/crashed.log" "$t/src"
expect "the instrumentation never started"       1 "INSTRUMENTATION_FAILED"                  "$t/nostart.log" "$t/src"
expect "fewer tests ran than are declared"       1 "2 tests ran, but"                        "$t/fewer.log"   "$t/src"
expect "zero tests ran"                          1 "0 tests ran, but"                        "$t/zero.log"    "$t/src"
expect "no output at all"                        1 "missing or empty"                        "$t/empty.log"   "$t/src"
expect "no @Test found is a broken scan"         1 "found no @Test method"                   "$t/pass.log"    "$t/empty"

# The real suite: the count the job will compare against.
REPO="$(cd -- "$HERE/../.." && pwd)"
n=$(find "$REPO/app/src/androidTest" -type f \( -name '*.kt' -o -name '*.java' \) -exec cat {} + \
    | grep -cE '^[[:space:]]*@Test([[:space:](]|$)' || true)
echo "  (app/src/androidTest declares $n @Test methods)"
if [ "$n" -lt 1 ]; then
    echo "::error::check_instrumentation_result: app/src/androidTest declares no @Test method"
    fail=1
fi

if [ "$fail" -ne 0 ]; then
    echo "::error::check_instrumentation_result_test FAILED"
    exit 1
fi
echo "check_instrumentation_result_test: all cases pass"
