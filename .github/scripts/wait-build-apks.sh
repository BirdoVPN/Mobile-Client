#!/usr/bin/env bash
# The emulator job's wait for the two APKs "Build APK" uploads (android-ci.yml,
# job `instrumented`, step "Wait for the APKs ..."; see the comment there).
# Writes app_id and test_id, the artifact ids to download, to $GITHUB_OUTPUT.
#
# Needs GITHUB_REPOSITORY, GITHUB_RUN_ID, GITHUB_RUN_ATTEMPT, GITHUB_OUTPUT,
# GH_TOKEN (actions: read), and BUILD_TIMEOUT_MIN = jobs.build.timeout-minutes
# (wait-build-apks-test.sh checks the workflow keeps the two equal).
# Self-test: .github/scripts/wait-build-apks-test.sh (gh, sleep, timeout and
# the clock stubbed).
set -euo pipefail
run="repos/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID"
build_timeout_min="${BUILD_TIMEOUT_MIN:-}"
case "$build_timeout_min" in
  "" | *[!0-9]* | 0*)
    echo "::error::BUILD_TIMEOUT_MIN must be Build APK's timeout-minutes, a whole number of minutes (got '$build_timeout_min')"
    exit 1 ;;
esac
# How long "Build APK" may take to start in this attempt, from this wait's
# start (MR-2512). A job that never leaves the queue (no runner) held the
# emulator job until its own timeout. A build that starts just inside this
# still has its full timeout plus 5 minutes, so the wait is at most
# queued_cap_min + BUILD_TIMEOUT_MIN + 5 minutes; wait-build-apks-test.sh
# fails unless that ends inside the emulator job's timeout-minutes.
# The cap stops applying once this attempt's Build APK has been seen
# running (MR-2557): a later read that regresses (queued, or no start, or an
# older one) is then bounded by that start's deadline, not cut by the cap.
queued_cap_min=20
# Every API read is bounded (MR-2512): a hung `gh api` held the job until
# its timeout. A read cut off counts as a failed one. -k 10 (MR-2557): a gh
# that ignores the TERM is killed 10 s later instead of hanging again.
gh_api() { timeout -k 10 60 gh api "$@"; }
# Prints the id of the newest unexpired artifact named $1 that was
# uploaded at or after $2 (ISO 8601 UTC, so the strings compare).
# Newest by created_at (MR-2402): artifact ids do not grow with upload
# time (run 44390109179: the test APK, uploaded 4 s after the app APK,
# has the lower id). The id only breaks a tie within one second.
# 0: found; 1: none (yet); 2: the API could not be read.
apk_id() {
  local json id
  json=$(gh_api "$run/artifacts?name=$1&per_page=100") || return 2
  id=$(jq -r --arg since "$2" '[.artifacts[]
         | select((.expired | not) and .created_at >= $since)]
         | max_by([.created_at, .id]) | .id // empty' <<< "$json") || return 2
  [ -n "$id" ] || return 1
  printf '%s\n' "$id"
}
errors=0
api_failed() {
  errors=$((errors + 1))
  if [ "$errors" -ge 6 ]; then
    echo "::error::the GitHub API failed 6 times in a row: this run's attempt, jobs or artifacts cannot be read"
    exit 1
  fi
}
# When this attempt started (MR-2512), read once. A "Build APK" counts as
# this attempt's only if it started then or later; see below.
while :; do
  if run_started=$(gh_api "$run/attempts/$GITHUB_RUN_ATTEMPT" --jq '.run_started_at // empty') &&
     [ -n "$run_started" ] && run_started_s=$(date -u -d "$run_started" +%s 2>/dev/null); then
    break
  fi
  api_failed; sleep 10
done
errors=0
wait_start_s=$(date -u +%s)
missing=0
# The start of this attempt's Build APK once seen in_progress (MR-2557).
seen_running=""
seen_running_s=0
while :; do
  # "Build APK" as THIS attempt reports it.
  if ! job=$(gh_api "$run/attempts/$GITHUB_RUN_ATTEMPT/jobs?per_page=100" \
       --jq '[.jobs[] | select(.name == "Build APK")]
             | if length == 1
               then .[0] | [.status, (.conclusion // "-"), (.started_at // "-"), (.completed_at // "-")] | @tsv
               else "missing" end'); then
    api_failed; sleep 10; continue
  fi
  if [ "$job" = missing ]; then
    missing=$((missing + 1))
    if [ "$missing" -ge 30 ]; then
      echo "::error::attempt $GITHUB_RUN_ATTEMPT lists no single job named \"Build APK\" (renamed?), so its APKs cannot be matched"
      exit 1
    fi
    sleep 10; continue
  fi
  read -r status conclusion started completed <<< "$job"
  # Whose "Build APK" this is (MR-2512):
  #   this     it started at or after this attempt did.
  #   carried  it is completed and ended before this attempt started, or in
  #            the same second (MR-2557: one ended 4 s before): "Re-run
  #            failed jobs" (or re-running one job) copies a job it does not
  #            re-run into the new attempt with the earlier attempt's times
  #            (run 44384671633, attempts 2 and 4). Its APKs are this
  #            commit's and are the ones to test, whatever its conclusion.
  #   none     anything else: not (yet) running in this attempt, or a start
  #            before this attempt's, whose uploads cannot be told from an
  #            earlier attempt's.
  # A plain started >= run_started would refuse every carried job, and the
  # emulator job of each "re-run failed jobs" would then fail.
  whose=none
  if [ "$started" != "-" ] && [ "$(date -u -d "$started" +%s)" -ge "$run_started_s" ]; then
    whose=this
  elif [ "$status" = completed ] && [ "$completed" != "-" ] &&
       [ "$(date -u -d "$completed" +%s)" -le "$run_started_s" ]; then
    # -le: a job that started in this attempt is `this` above, so one that
    # ended in the second this attempt started can only be an earlier one's.
    whose=carried
  fi
  if [ "$status" = in_progress ] && [ "$whose" = this ]; then
    seen_running="$started"
    seen_running_s=$(date -u -d "$started" +%s)
  fi
  # The artifacts are read AFTER the job's state, so when that state
  # was "completed", every upload the job made is already listed.
  # Only once the job runs or has ended (MR-2402): before that (queued,
  # waiting, pending, requested), a re-run Build APK has uploaded
  # nothing in this attempt, and the start time it reports need not be
  # this attempt's, so an older attempt's APKs could pass the filter.
  # And only for a job that is this attempt's or carried into it (MR-2512).
  case "$status" in
    in_progress | completed) check_artifacts=1 ;;
    *) check_artifacts=0 ;;
  esac
  [ "$whose" != none ] || check_artifacts=0
  if [ "$check_artifacts" -eq 1 ] && [ "$started" != "-" ]; then
    rc=0; app_id=$(apk_id debug-apk "$started") || rc=$?
    if [ "$rc" -eq 0 ]; then
      test_id=$(apk_id debug-android-test-apk "$started") || rc=$?
    fi
    if [ "$rc" -eq 0 ]; then
      echo "Both APKs are uploaded by the \"Build APK\" that started $started (attempt $GITHUB_RUN_ATTEMPT; artifacts $app_id and $test_id; waited ${SECONDS}s)"
      { echo "app_id=$app_id"; echo "test_id=$test_id"; } >> "$GITHUB_OUTPUT"
      break
    fi
    if [ "$rc" -eq 2 ]; then
      api_failed; sleep 10; continue
    fi
  fi
  errors=0
  if [ "$status" = completed ] && [ "$whose" = none ]; then
    echo "::error::Build APK is completed ($conclusion), but its times (started $started, completed $completed) fit neither attempt $GITHUB_RUN_ATTEMPT (started $run_started) nor an earlier attempt it was carried over from (completed before that), so its APKs cannot be matched"
    exit 1
  fi
  if [ "$status" = completed ]; then
    echo "::error::Build APK ended ($conclusion) without uploading both APKs after its start ($started), so there is nothing to test. (If only this job was re-run more than 7 days later, its APKs have expired: re-run all jobs.)"
    exit 1
  fi
  if [ -n "$seen_running" ]; then
    # Running in this attempt now, or seen running earlier and this read
    # regressed: the deadline of the start it was seen with (MR-2557).
    limit=$(( seen_running_s + (build_timeout_min + 5) * 60 ))
    if [ "$(date -u +%s)" -ge "$limit" ]; then
      echo "::error::Build APK started at $seen_running, is past its ${build_timeout_min}-minute timeout plus 5 minutes and still uploaded no APKs (it now reads $status, with start $started)"
      exit 1
    fi
  elif [ $(( $(date -u +%s) - wait_start_s )) -ge $(( queued_cap_min * 60 )) ]; then
    # Not seen running in this attempt: queued, waiting, pending, requested,
    # or in_progress with no start or one before this attempt's.
    echo "::error::Build APK has not started in attempt $GITHUB_RUN_ATTEMPT (which started $run_started) within ${queued_cap_min} minutes of this wait: it is $status, with start $started. Nothing it uploads can be matched to this attempt, so this job stops here rather than at its timeout"
    exit 1
  fi
  sleep 10
done
