#!/usr/bin/env bash
# The emulator job's wait for the two APKs "Build APK" uploads (android-ci.yml,
# job `instrumented`, step "Wait for the APKs ..."; see the comment there).
# Writes app_id and test_id, the artifact ids to download, to $GITHUB_OUTPUT.
set -euo pipefail
run="repos/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID"
build_timeout_min=40   # = jobs.build.timeout-minutes
# Prints the id of the newest unexpired artifact named $1 that was
# uploaded at or after $2 (ISO 8601 UTC, so the strings compare).
# 0: found; 1: none (yet); 2: the API could not be read.
apk_id() {
  local json id
  json=$(gh api "$run/artifacts?name=$1&per_page=100") || return 2
  id=$(jq -r --arg since "$2" '[.artifacts[]
         | select((.expired | not) and .created_at >= $since)]
         | max_by(.id) | .id // empty' <<< "$json") || return 2
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
  if [ "$started" != "-" ]; then
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
