#!/usr/bin/env bash
# ============================================================================
# Self-test for .github/scripts/wait-build-apks.sh (the emulator job's wait for
# the two APKs "Build APK" uploads; MR-1410, MR-2382, MR-2402), run by that job
# before it waits. gh, sleep and the clock are stubs, so no case needs a token,
# the network or real time. A re-run's race cannot be staged on demand in CI,
# so each case replays what the API reports during one:
#
#   attempt 1       Build APK builds, then uploads both      -> their ids
#                   the jobs are read for THIS attempt        -> attempts/<n>/jobs
#   re-run all      the previous attempt's APKs are listed
#                   while the new Build APK runs              -> not taken; the new ids
#                   ... while it is queued, waiting, pending
#                   or requested with a start time older than
#                   those APKs                                -> not taken (MR-2402)
#   re-run failed   Build APK passed and was not re-run: it
#                   keeps its first start time                -> its APKs, at once
#   two uploads     the newer one has the LOWER id            -> the newer (MR-2402)
#                   both in the same second                   -> the higher id
#   expired         the only APK has expired                  -> never taken
#   gate failure    Build APK failed after uploading both     -> their ids
#   no APKs         Build APK ended without them              -> exit 1
#   deadline        running past BUILD_TIMEOUT_MIN + 5 min    -> exit 1, not earlier
#   API             5 failed reads, then a good one           -> their ids
#                   6 failed reads in a row (jobs/artifacts)  -> exit 1
#   not listed      no single job named "Build APK", 30 polls -> exit 1
#   in step         BUILD_TIMEOUT_MIN = jobs.build.timeout-minutes, and the
#                   wait ends before the emulator job's own timeout (MR-2382)
# ============================================================================
set -euo pipefail

here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
wait_sh="$here/wait-build-apks.sh"
workflow="$here/../workflows/android-ci.yml"
[ -f "$wait_sh" ] || { echo "::error::script missing: $wait_sh"; exit 1; }
[ -f "$workflow" ] || { echo "::error::workflow missing: $workflow"; exit 1; }

work="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/wait-build-apks-selftest.XXXXXX")"
trap 'rm -rf "$work"' EXIT

fail=0
case_fail=0
check() { # check <description> <command...>
  local d="$1"
  shift
  if "$@"; then
    echo "ok    $d"
  else
    echo "::error::wait-build-apks self-test: $d"
    fail=1
    case_fail=1
  fi
}

# -- stubs ---------------------------------------------------------------------
# gh api: answers from the case's fixtures. Each read of the attempt's jobs is
# one poll; a read answers with the newest fixture <kind>.<k>, k <= that poll.
# A fixture holding FAIL is a failed read. Artifact reads are filtered by
# ?name= as the API does. Every endpoint read is logged.
stubs="$work/bin"
mkdir -p "$stubs"
cat >"$stubs/gh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
scen="$WBA_CASE"
[ "${1:-}" = api ] || { echo "gh stub: only 'gh api' is expected, got: $*" >&2; exit 97; }
endpoint="$2"
shift 2
filter=""
if [ "$#" -ge 2 ] && [ "$1" = --jq ]; then filter="$2"; shift 2; fi
[ "$#" -eq 0 ] || { echo "gh stub: unexpected arguments: $*" >&2; exit 97; }
printf '%s\n' "$endpoint" >>"$scen/calls.log"
poll=$(cat "$scen/polls" 2>/dev/null || echo 0)
case "$endpoint" in
  "repos/o/r/actions/runs/42/attempts/"*"/jobs?per_page=100")
    poll=$((poll + 1)); echo "$poll" >"$scen/polls"; kind=jobs ;;
  "repos/o/r/actions/runs/42/artifacts?name="*"&per_page=100")
    kind=artifacts; name="${endpoint#*name=}"; name="${name%%&*}" ;;
  *) echo "gh stub: unexpected endpoint $endpoint" >&2; exit 97 ;;
esac
file=""
for ((k = poll; k >= 0; k--)); do
  if [ -f "$scen/$kind.$k" ]; then file="$scen/$kind.$k"; break; fi
done
[ -n "$file" ] || { echo "gh stub: no $kind fixture at poll $poll" >&2; exit 97; }
if [ "$(cat "$file")" = FAIL ]; then echo "gh: HTTP 502" >&2; exit 1; fi
if [ "$kind" = artifacts ]; then
  json=$(jq --arg n "$name" '.artifacts |= map(select(.name == $n)) | .total_count = (.artifacts | length)' "$file")
else
  json=$(cat "$file")
fi
if [ -n "$filter" ]; then jq -r "$filter" <<<"$json"; else printf '%s\n' "$json"; fi
STUB
# sleep advances the fake clock; past 3 h of fake time the wait is looping
# forever, and the stub ends it (exit 3).
cat >"$stubs/sleep" <<'STUB'
#!/usr/bin/env bash
now=$(( $(cat "$WBA_CASE/clock") + $1 ))
echo "$now" >"$WBA_CASE/clock"
[ "$now" -lt "$WBA_RUNAWAY" ] || { echo "sleep stub: still waiting after 3 h of fake time" >&2; exit 3; }
STUB
# `date -u +%s` is the fake clock; anything else is the real date.
cat >"$stubs/date" <<'STUB'
#!/usr/bin/env bash
if [ "$*" = "-u +%s" ]; then cat "$WBA_CASE/clock"; else exec "$WBA_REAL_DATE" "$@"; fi
STUB
chmod +x "$stubs/gh" "$stubs/sleep" "$stubs/date"
real_date="$(command -v date)"
epoch() { "$real_date" -u -d "$1" +%s; }

# -- fixtures ------------------------------------------------------------------
jobs_json() { # <status> <conclusion|null> <started_at|null>: this attempt's jobs
  jq -n --arg s "$1" --arg c "$2" --arg t "$3" '{total_count: 2, jobs: [
    {name: "Lint Check", status: "completed", conclusion: "success", started_at: "2026-11-19T09:59:00Z"},
    {name: "Build APK", status: $s,
     conclusion: (if $c == "null" then null else $c end),
     started_at: (if $t == "null" then null else $t end)}]}'
}
art() { # <id> <name> <created_at> [expired]
  jq -cn --argjson id "$1" --arg n "$2" --arg t "$3" --argjson e "${4:-false}" \
    '{id: $id, name: $n, created_at: $t, expired: $e}'
}
arts_json() { # <art>...: this run's artifact list, every attempt's
  if [ "$#" -eq 0 ]; then echo '{"total_count": 0, "artifacts": []}'; return; fi
  printf '%s\n' "$@" | jq -s '{total_count: length, artifacts: .}'
}
# The first attempt's Build APK started at 10:00 and uploaded at 10:05.
old_app=$(art 900 debug-apk 2026-11-19T10:05:00Z)
old_test=$(art 901 debug-android-test-apk 2026-11-19T10:05:01Z)

case_dir=""
new_case() { # <name> <fake time now>
  case_dir="$work/case-$1"
  case_fail=0
  mkdir -p "$case_dir"
  epoch "$2" >"$case_dir/clock"
}
fixture() { # <jobs|artifacts> <from poll> <content>
  printf '%s\n' "$3" >"$case_dir/$1.$2"
}
rc=0
out=""
run_wait() { # <attempt> [BUILD_TIMEOUT_MIN]
  rc=0
  out=$(PATH="$stubs:$PATH" WBA_CASE="$case_dir" WBA_REAL_DATE="$real_date" \
        WBA_RUNAWAY=$(( $(cat "$case_dir/clock") + 3 * 3600 )) \
        GITHUB_REPOSITORY=o/r GITHUB_RUN_ID=42 GITHUB_RUN_ATTEMPT="$1" \
        GITHUB_OUTPUT="$case_dir/output" BUILD_TIMEOUT_MIN="${2:-40}" \
        bash "$wait_sh" 2>&1) || rc=$?
}
took() { # <app_id> <test_id>: exit 0 and exactly these ids written
  [ "$rc" -eq 0 ] && [ "$(cat "$case_dir/output" 2>/dev/null)" = "$(printf 'app_id=%s\ntest_id=%s' "$1" "$2")" ]
}
failed_with() { # <text>: exit 1, the text in an ::error:: line, no ids written
  local errors
  errors=$(grep -F '::error::' <<<"$out" || true)
  [ "$rc" -eq 1 ] && [ ! -s "$case_dir/output" ] && grep -qF -- "$1" <<<"$errors"
}
polls() { cat "$case_dir/polls" 2>/dev/null || echo 0; }
no_artifact_read_in_first() { # <n>: the first n API reads are all of the jobs
  [ "$(sed -n "1,$1p" "$case_dir/calls.log" | grep -c '/jobs?' || true)" -eq "$1" ]
}
clock_is() { [ "$(cat "$case_dir/clock")" -eq "$(epoch "$1")" ]; }
show() { [ "$case_fail" -eq 0 ] || printf '%s\n' "$out" | sed 's/^/      | /'; }

# -- attempt 1 -----------------------------------------------------------------
new_case attempt1 2026-11-19T10:02:00Z
fixture jobs 1 "$(jobs_json in_progress null 2026-11-19T10:00:00Z)"
fixture artifacts 1 "$(arts_json)"
fixture artifacts 3 "$(arts_json "$old_app" "$old_test")"
run_wait 1
check "attempt 1: waits while Build APK builds, then takes both APKs it uploaded" took 900 901
check "attempt 1: polled until the upload (3 reads of the jobs)" [ "$(polls)" -eq 3 ]
check "attempt 1: the jobs are read for this run attempt" \
  grep -qx 'repos/o/r/actions/runs/42/attempts/1/jobs?per_page=100' "$case_dir/calls.log"
show

# -- re-run all: the previous attempt's APKs are still listed --------------------
new_case rerun-all 2026-11-19T11:02:00Z
fixture jobs 1 "$(jobs_json in_progress null 2026-11-19T11:00:30Z)"
fixture artifacts 1 "$(arts_json "$old_app" "$old_test")"
fixture artifacts 3 "$(arts_json "$old_app" "$old_test" \
  "$(art 950 debug-apk 2026-11-19T11:06:00Z)" "$(art 951 debug-android-test-apk 2026-11-19T11:06:01Z)")"
run_wait 2
check "re-run all: the previous attempt's APKs are not taken; this attempt's are" took 950 951
check "re-run all: the jobs are read for attempt 2" \
  grep -qx 'repos/o/r/actions/runs/42/attempts/2/jobs?per_page=100' "$case_dir/calls.log"
show

# Before the re-run Build APK starts, its start time is not this attempt's:
# whatever it is (here the first attempt's, older than the old APKs), the
# artifacts are not read until the job runs (MR-2402).
for status in queued waiting pending requested; do
  new_case "rerun-$status" 2026-11-19T11:00:10Z
  fixture jobs 1 "$(jobs_json "$status" null 2026-11-19T10:00:00Z)"
  fixture jobs 4 "$(jobs_json in_progress null 2026-11-19T11:01:00Z)"
  fixture artifacts 1 "$(arts_json "$old_app" "$old_test")"
  fixture artifacts 6 "$(arts_json "$old_app" "$old_test" \
    "$(art 950 debug-apk 2026-11-19T11:06:00Z)" "$(art 951 debug-android-test-apk 2026-11-19T11:06:01Z)")"
  run_wait 2
  check "re-run all, Build APK $status with an older start time: the old APKs are not taken" took 950 951
  check "re-run all, Build APK $status: no artifact read before it runs" no_artifact_read_in_first 3
  show
done

new_case rerun-queued-nostart 2026-11-19T11:00:10Z
fixture jobs 1 "$(jobs_json queued null null)"
fixture jobs 3 "$(jobs_json in_progress null 2026-11-19T11:01:00Z)"
fixture artifacts 1 "$(arts_json "$old_app" "$old_test")"
fixture artifacts 5 "$(arts_json "$old_app" "$old_test" \
  "$(art 950 debug-apk 2026-11-19T11:06:00Z)" "$(art 951 debug-android-test-apk 2026-11-19T11:06:01Z)")"
run_wait 2
check "re-run all, Build APK queued with no start time: the old APKs are not taken" took 950 951
show

# -- re-run failed jobs: Build APK passed and is not re-run ---------------------
# The new attempt lists it with its first start time (run 44384671633,
# attempts 2 and 4), and the APKs it uploaded then are the ones to test.
new_case rerun-failed 2026-11-19T11:02:00Z
fixture jobs 1 "$(jobs_json completed success 2026-11-19T10:00:00Z)"
fixture artifacts 1 "$(arts_json "$old_app" "$old_test")"
run_wait 2
check "re-run failed jobs, Build APK not re-run: its own APKs, at the first poll" took 900 901
check "re-run failed jobs: one poll" [ "$(polls)" -eq 1 ]
show

# -- the newest upload by time, not by id (MR-2402) ------------------------------
# Artifact ids do not grow with upload time: in run 44390109179 the test APK
# was uploaded 4 s after the app APK and has the lower id.
new_case newest 2026-11-19T10:08:00Z
fixture jobs 1 "$(jobs_json in_progress null 2026-11-19T10:00:00Z)"
fixture artifacts 1 "$(arts_json "$(art 500 debug-apk 2026-11-19T10:05:00Z)" \
  "$(art 400 debug-apk 2026-11-19T10:07:00Z)" "$(art 401 debug-android-test-apk 2026-11-19T10:07:01Z)")"
run_wait 1
check "two uploads: the newer one is taken although its id is lower" took 400 401
show

new_case same-second 2026-11-19T10:08:00Z
fixture jobs 1 "$(jobs_json in_progress null 2026-11-19T10:00:00Z)"
fixture artifacts 1 "$(arts_json "$(art 600 debug-apk 2026-11-19T10:07:00Z)" \
  "$(art 700 debug-apk 2026-11-19T10:07:00Z)" "$(art 701 debug-android-test-apk 2026-11-19T10:07:01Z)")"
run_wait 1
check "two uploads in the same second: the higher id" took 700 701
show

# -- what the wait never takes, and how it ends ---------------------------------
new_case expired 2026-11-19T10:06:00Z
fixture jobs 1 "$(jobs_json in_progress null 2026-11-19T10:00:00Z)"
fixture jobs 3 "$(jobs_json completed success 2026-11-19T10:00:00Z)"
fixture artifacts 1 "$(arts_json "$(art 900 debug-apk 2026-11-19T10:05:00Z true)" "$old_test")"
run_wait 1
check "an expired APK is never taken: the wait ends in an error" failed_with 'Build APK ended (success) without uploading both APKs'
show

new_case gate-failure 2026-11-19T10:06:00Z
fixture jobs 1 "$(jobs_json completed failure 2026-11-19T10:00:00Z)"
fixture artifacts 1 "$(arts_json "$old_app" "$old_test")"
run_wait 1
check "Build APK failed after uploading both (its ISA gate): those APKs are tested" took 900 901
show

new_case no-apks 2026-11-19T10:02:00Z
fixture jobs 1 "$(jobs_json in_progress null 2026-11-19T10:00:00Z)"
fixture jobs 2 "$(jobs_json completed failure 2026-11-19T10:00:00Z)"
fixture artifacts 1 "$(arts_json)"
run_wait 1
check "Build APK ended without its APKs: exit 1" failed_with 'Build APK ended (failure) without uploading both APKs'
show

# The deadline is Build APK's start + BUILD_TIMEOUT_MIN + 5 minutes, and the
# wait does not end before it.
new_case deadline 2026-11-19T10:44:30Z
fixture jobs 1 "$(jobs_json in_progress null 2026-11-19T10:00:00Z)"
fixture artifacts 1 "$(arts_json)"
run_wait 1 40
check "deadline: running past its 40-minute timeout plus 5 with no APKs is exit 1" \
  failed_with 'past its 40-minute timeout plus 5 minutes'
check "deadline: not before 10:45:00 (start + 45 min)" clock_is 2026-11-19T10:45:00Z
show

new_case deadline-env 2026-11-19T10:44:30Z
fixture jobs 1 "$(jobs_json in_progress null 2026-11-19T10:00:00Z)"
fixture artifacts 1 "$(arts_json)"
run_wait 1 50
check "deadline: follows BUILD_TIMEOUT_MIN (50: exit 1 at 10:55:00, not 10:45:00)" clock_is 2026-11-19T10:55:00Z
check "deadline: BUILD_TIMEOUT_MIN 50 is named in the error" failed_with 'past its 50-minute timeout plus 5 minutes'
show

for bad in "" 0 abc 4O; do
  new_case "timeout-${bad:-empty}" 2026-11-19T10:02:00Z
  fixture jobs 1 "$(jobs_json in_progress null 2026-11-19T10:00:00Z)"
  fixture artifacts 1 "$(arts_json "$old_app" "$old_test")"
  rc=0
  out=$(PATH="$stubs:$PATH" WBA_CASE="$case_dir" WBA_REAL_DATE="$real_date" WBA_RUNAWAY=0 \
        GITHUB_REPOSITORY=o/r GITHUB_RUN_ID=42 GITHUB_RUN_ATTEMPT=1 \
        GITHUB_OUTPUT="$case_dir/output" BUILD_TIMEOUT_MIN="$bad" bash "$wait_sh" 2>&1) || rc=$?
  check "BUILD_TIMEOUT_MIN '${bad}' is refused before any poll" failed_with 'BUILD_TIMEOUT_MIN'
  check "BUILD_TIMEOUT_MIN '${bad}': no API read" [ "$(polls)" -eq 0 ]
  show
done

# -- the API ---------------------------------------------------------------------
new_case api-recovers 2026-11-19T10:06:00Z
for k in 1 2 3 4 5; do fixture jobs "$k" FAIL; done
fixture jobs 6 "$(jobs_json in_progress null 2026-11-19T10:00:00Z)"
fixture artifacts 0 "$(arts_json "$old_app" "$old_test")"
run_wait 1
check "API: 5 failed reads, then a good one: the APKs" took 900 901
show

new_case api-jobs-down 2026-11-19T10:06:00Z
fixture jobs 1 FAIL
run_wait 1
check "API: 6 failed reads of the jobs in a row: exit 1" failed_with 'failed 6 times in a row'
check "API: ... after exactly 6 reads" [ "$(polls)" -eq 6 ]
show

new_case api-artifacts-down 2026-11-19T10:06:00Z
fixture jobs 1 "$(jobs_json in_progress null 2026-11-19T10:00:00Z)"
fixture artifacts 1 FAIL
run_wait 1
check "API: 6 failed reads of the artifacts in a row: exit 1" failed_with 'failed 6 times in a row'
show

new_case not-listed 2026-11-19T10:06:00Z
fixture jobs 1 '{"total_count": 1, "jobs": [{"name": "Lint Check", "status": "in_progress", "conclusion": null, "started_at": "2026-11-19T10:00:00Z"}]}'
run_wait 1
check "no job named \"Build APK\" in 30 reads: exit 1" failed_with 'lists no single job named "Build APK"'
check "not listed: ... after exactly 30 reads" [ "$(polls)" -eq 30 ]
show

# -- the workflow keeps the two timeouts in step (MR-2382) ------------------------
job_timeout() { # <job id>: its job-level timeout-minutes in android-ci.yml
  awk -v job="  $1:" '
    $0 == job { in_job = 1; next }
    in_job && /^  [A-Za-z0-9_-]+:/ { exit }
    in_job && /^    timeout-minutes:/ { print $2; exit }' "$workflow"
}
build_timeout=$(job_timeout build)
emulator_timeout=$(job_timeout instrumented)
wait_timeout=$(sed -nE "s/^ +BUILD_TIMEOUT_MIN: *'?([0-9]+)'?.*/\1/p" "$workflow")
echo "      Build APK timeout-minutes: ${build_timeout:-?}; BUILD_TIMEOUT_MIN: ${wait_timeout:-?}; emulator job timeout-minutes: ${emulator_timeout:-?}"
set_once() { [ "$(grep -cE '^ +BUILD_TIMEOUT_MIN:' "$workflow" || true)" -eq 1 ]; }
same_timeout() { [ -n "$build_timeout" ] && [ "$build_timeout" = "$wait_timeout" ]; }
wait_inside_job() {
  [ -n "$wait_timeout" ] && [ -n "$emulator_timeout" ] && [ $((wait_timeout + 5)) -lt "$emulator_timeout" ]
}
check "android-ci.yml: BUILD_TIMEOUT_MIN is set once" set_once
check "android-ci.yml: BUILD_TIMEOUT_MIN = jobs.build.timeout-minutes" same_timeout
check "android-ci.yml: the wait (Build APK timeout + 5) ends inside the emulator job's timeout-minutes" wait_inside_job

if [ "$fail" -ne 0 ]; then
  echo "::error::wait-build-apks self-test failed (above)"
  exit 1
fi
echo "wait-build-apks self-test: all cases pass"
