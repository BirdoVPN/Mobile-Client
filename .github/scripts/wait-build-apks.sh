#!/usr/bin/env bash
# The emulator job's wait for the two APKs "Build APK" uploads (android-ci.yml,
# job `instrumented`, step "Wait for the APKs ..."; see the comment there).
# Writes app_id and test_id, the artifact ids to download, to $GITHUB_OUTPUT.
#
# Needs GITHUB_REPOSITORY, GITHUB_RUN_ID, GITHUB_RUN_ATTEMPT, GITHUB_OUTPUT,
# GH_TOKEN (actions: read), and BUILD_TIMEOUT_MIN = jobs.build.timeout-minutes
# (wait-build-apks-test.sh checks the workflow keeps the two equal).
# Self-test: .github/scripts/wait-build-apks-test.sh (gh, sleep, clock stubbed).
set -euo pipefail
run="repos/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID"
build_timeout_min="${BUILD_TIMEOUT_MIN:-}"
case "$build_timeout_min" in
  "" | *[!0-9]* | 0*)
    echo "::error::BUILD_TIMEOUT_MIN must be Build APK's timeout-minutes, a whole number of minutes (got '$build_timeout_min')"
    exit 1 ;;
esac
# Prints the id of the newest unexpired artifact named $1 that was
# uploaded at or after $2 (ISO 8601 UTC, so the strings compare).
# Newest by created_at (MR-2402): artifact ids do not grow with upload
# time (run 44390109179: the test APK, uploaded 4 s after the app APK,
# has the lower id). The id only breaks a tie within one second.
# 0: found; 1: none (yet); 2: the API could not be read.
apk_id() {
  local json id
  json=$(gh api "$run/artifacts?name=$1&per_page=100") || return 2
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
    echo "::error::the GitHub API failed 6 times in a row: this run's jobs or artifacts cannot be read"
    exit 1
  fi
}
missing=0
while :; do
  # "Build APK" as THIS attempt reports it.
  if ! job=$(gh api "$run/attempts/$GITHUB_RUN_ATTEMPT/jobs?per_page=100" \
       --jq '[.jobs[] | select(.name == "Build APK")]
             | if length == 1
               then .[0] | [.status, (.conclusion // "-"), (.started_at // "-")] | @tsv
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
  read -r status conclusion started <<< "$job"
  # The artifacts are read AFTER the job's state, so when that state
  # was "completed", every upload the job made is already listed.
  # Only once the job runs or has ended (MR-2402): before that (queued,
  # waiting, pending, requested), a re-run Build APK has uploaded
  # nothing in this attempt, and the start time it reports need not be
  # this attempt's, so an older attempt's APKs could pass the filter.
  case "$status" in
    in_progress | completed) check_artifacts=1 ;;
    *) check_artifacts=0 ;;
  esac
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
  if [ "$status" = completed ]; then
    echo "::error::Build APK ended ($conclusion) without uploading both APKs after its start ($started), so there is nothing to test. (If only this job was re-run more than 7 days later, its APKs have expired: re-run all jobs.)"
    exit 1
  fi
  if [ "$status" = in_progress ] && [ "$started" != "-" ]; then
    limit=$(( $(date -u -d "$started" +%s) + (build_timeout_min + 5) * 60 ))
    if [ "$(date -u +%s)" -ge "$limit" ]; then
      echo "::error::Build APK started at $started, is past its ${build_timeout_min}-minute timeout plus 5 minutes and still uploaded no APKs"
      exit 1
    fi
  fi
  sleep 10
done
