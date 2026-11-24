#!/usr/bin/env bash
#
# Which Gradle jobs save main's Gradle cache in this run (MR-2554). Appends
#   <job>=true|false
# for each job named on the command line to $GITHUB_OUTPUT (stdout when that
# is unset). android-ci.yml's `changes` job publishes them as
# gradle_cache_write_<job>, and each of those jobs' setup-gradle step is
# cache-read-only only when its value is 'false': an empty or missing value
# saves (the self-test checks the wiring).
#
# WHY. setup-gradle saves a job's Gradle User Home entry, and every extracted
# entry whose contents changed, on every main run it is allowed to write.
# The transforms entry (gradle-transforms-v2-<hash>, 234-404 MB per job) is
# one bundle of all transform outputs, and a Kotlin change alters a few of
# them, so each such main push saved ~880 MB of new transforms entries for
# Lint Check, Unit Tests and Build APK, while the dependencies did not move
# (e.g. 5dc1dc1c -> 3b55813f -> 27aecb03 -> e9952c6b on 2026-11-19/20). The
# repository's Actions cache sat at its 10 GB cap, evicting the Xray, Rosenpass
# and apt caches. What changed in those entries is the transforms of this
# commit's own code, which the next commit cannot reuse anyway.
#
# THE RULE. On a pull request: false (setup-gradle is read-only there by
# ref anyway). On a dispatch on main: true, as before. On a push to main, a
# job saves if
#   1. the push itself changed a Gradle input (build scripts, gradle/,
#      gradle.properties, the wrapper, this workflow's or these scripts' cache
#      rules), or the push cannot be diffed (more than one commit, a new
#      branch, no HEAD^1): every job saves, with no API call;
#   2. or this month's lineage (GRADLE_BUILD_ACTION_CACHE_KEY_PREFIX, MR-2536)
#      has no Gradle User Home entry for that job on main yet;
#   3. or its newest one is smaller than min_home_bytes (a cheap first test:
#      the #656 merge run's Build APK hit the plugin-marker flake cold and
#      saved a 0.9 MB home with an empty dependencies entry);
#   4. or a Gradle input changed between the commit that entry was saved from
#      (its key ends in -<sha>) and this push. The entry the job restores is
#      the one judged, not HEAD^1: a Gradle-input push whose own run saved
#      nothing (d8ca194, the 9.8.2 -> 9.9.0 wrapper bump, was a pending run
#      cancelled when 592faca, a cargo-only push, queued behind it on
#      2026-11-17) is caught by the next push, not at the month's end;
#   5. or that job, in any android-ci run on main at that commit, in any
#      attempt, did not conclude `success`: setup-gradle saves after a failed
#      build too, a home over the floor can hold a half-resolved dependencies
#      entry, and a re-run of the same commit cannot replace the key.
# Otherwise the job restores the month's entry and saves nothing. A first
# push of a month always saves, through 2.
#
# NEVER FAILS, and errs towards saving: when the commit, the push, the cache
# list, the comparison or the runs cannot be read, the job saves, as it did
# before MR-2554.
#
# Usage: gradle-cache-write.sh <job>...   (run in the `changes` checkout,
#   fetch-depth 2; the jobs are android-ci.yml job ids, see check_name)
# Env: EVENT_NAME, GIT_REF (github.ref), BEFORE (github.event.before),
#   PREFIX (the month's lineage, gradle-cache-prefix.sh), GITHUB_REPOSITORY,
#   GH_TOKEN (contents: read and actions: read).
# Self-test: .github/scripts/gradle-cache-write-test.sh
set -u

# A healthy Gradle User Home entry of these jobs is 9-12 MB (2026-11); one
# saved by a build that failed at configuration was 0.9 MB.
min_home_bytes=4000000
# The compare API lists at most 300 files for a whole comparison.
compare_file_cap=300
# The workflow whose runs save these entries.
workflow_file=android-ci.yml
# The paths whose change can change what a Gradle job downloads or
# transforms (POSIX ERE, one per line).
gradle_inputs='(^|/)[^/]*\.gradle(\.kts)?$
(^|/)gradle\.properties$
(^|/)[^/]*\.versions\.toml$
^gradle/
^gradlew(\.bat)?$
^(buildSrc|build-logic)/
^\.github/workflows/android-ci\.yml$
^\.github/scripts/gradle-cache-[^/]*$'

# The check name of each job id in android-ci.yml (the self-test checks them).
check_name() {
  case "$1" in
    lint) echo "Lint Check" ;;
    test) echo "Unit Tests" ;;
    build) echo "Build APK" ;;
    *) echo "" ;;
  esac
}

out="${GITHUB_OUTPUT:-/dev/stdout}"
summary="${GITHUB_STEP_SUMMARY:-/dev/null}"
event="${EVENT_NAME:-}"
ref="${GIT_REF:-}"
before="${BEFORE:-}"
prefix="${PREFIX:-}"
repo="${GITHUB_REPOSITORY:-}"

emit() { # <job> <true|false> <reason>
  echo "$1=$2" >> "$out"
  echo "Gradle cache, $1: $([ "$2" = true ] && echo saves || echo read-only) ($3)"
  echo "- \`$1\`: $([ "$2" = true ] && echo saves || echo read-only) ($3)" >> "$summary"
}
all() { # <true|false> <reason>: the same for every job
  local job
  for job in $jobs; do emit "$job" "$1" "$2"; done
}
first3() { # <lines>: the first three on one line, "..." when there are more
  echo "$(head -3 <<<"$1" | tr '\n' ' ' | sed 's/ $//')$([ "$(wc -l <<<"$1")" -gt 3 ] && echo ' ...')"
}
gradle_inputs_in() { # <paths, one per line>: those that are Gradle inputs
  grep -E -f <(printf '%s\n' "$gradle_inputs") <<<"$1" || true
}

jobs="$*"
if [ -z "$jobs" ]; then
  echo "::warning::gradle-cache-write.sh: no job named; nothing to decide"
  exit 0
fi
echo "### Gradle cache writes (MR-2554)" >> "$summary"

if [ "$ref" != refs/heads/main ]; then
  all false "not main: setup-gradle is read-only here"
  exit 0
fi
if [ "$event" != push ]; then
  all true "a $event on main"
  exit 0
fi
case "$prefix" in
  [0-9][0-9][0-9][0-9]-[01][0-9]-) ;;
  *)
    echo "::warning::gradle-cache-write.sh: the lineage prefix is '$prefix', not YYYY-MM-; every job saves"
    all true "no month lineage to check"
    exit 0 ;;
esac

# 1. Did this push change a Gradle input? (No API call.)
parent=$(git rev-parse -q --verify 'HEAD^1' 2>/dev/null) || parent=""
if [ -z "$parent" ] || [ "$before" != "$parent" ]; then
  echo "::notice::gradle-cache-write.sh: push from ${before:-?} is not one commit on HEAD^1 (${parent:-none}); every job saves"
  all true "the push is not one commit on HEAD^1, so its changes are not known"
  exit 0
fi
if ! changed=$(git diff --no-renames --name-only HEAD^1 HEAD 2>/dev/null); then
  echo "::warning::gradle-cache-write.sh: cannot diff HEAD^1..HEAD; every job saves"
  all true "HEAD^1..HEAD cannot be diffed"
  exit 0
fi
inputs=$(gradle_inputs_in "$changed")
if [ -n "$inputs" ]; then
  all true "the push changed a Gradle input: $(first3 "$inputs")"
  exit 0
fi
head_sha=$(git rev-parse -q --verify HEAD 2>/dev/null) || head_sha=""
why=""
listing=""

# 4. Sets `why` to the reason the changes between <sha> and this push make a
# job save; empty when they do not. One comparison per commit (the jobs'
# entries usually share one), so it runs in this shell, never in $(...).
declare -A since_memo=()
since_reason() { # <sha>
  local sha="$1" ans rc=0 status count names found inputs
  if [ -n "${since_memo[$sha]+set}" ]; then why="${since_memo[$sha]}"; return; fi
  ans=$(timeout -k 10 30 gh api -X GET "repos/$repo/compare/${sha}...${head_sha}" \
    --jq '"\(.status) \((.files // []) | length)", ((.files // [])[] | .filename, (.previous_filename // empty))' \
    2>/dev/null) || rc=$?
  ans="${ans//$'\r'/}"
  if [ "$rc" -ne 0 ]; then
    echo "::warning::gradle-cache-write.sh: cannot compare ${sha:0:12}...${head_sha:0:12} (exit $rc); it saves"
    found="the changes since its commit ${sha:0:8} cannot be read"
  else
    read -r status count <<<"$(head -1 <<<"$ans")"
    names=$(tail -n +2 <<<"$ans")
    case "$status" in
      ahead | identical)
        if ! [[ "$count" =~ ^[0-9]+$ ]]; then
          found="the comparison with its commit ${sha:0:8} is not understood"
        elif [ "$count" -ge "$compare_file_cap" ]; then
          found="$count files changed since its commit ${sha:0:8}, more than the comparison lists"
        else
          inputs=$(gradle_inputs_in "$names")
          if [ -n "$inputs" ]; then
            found="a Gradle input changed since its commit ${sha:0:8}: $(first3 "$inputs")"
          else
            found=""
          fi
        fi ;;
      *)
        found="its commit ${sha:0:8} is not behind this push (comparison: ${status:-no answer})" ;;
    esac
  fi
  since_memo[$sha]="$found"
  why="$found"
}

# 5. Sets `listing` to every conclusion, over all attempts, of every job of
# every android-ci run on main at <sha>, as "<conclusion> <check name>" lines,
# or to a line "!<problem>" when they cannot be read. One listing per commit,
# in this shell.
declare -A runs_memo=()
run_jobs() { # <sha>
  local sha="$1" ids id rc=0 lines="" page
  if [ -n "${runs_memo[$sha]+set}" ]; then listing="${runs_memo[$sha]}"; return; fi
  ids=$(timeout -k 10 30 gh api -X GET "repos/$repo/actions/workflows/$workflow_file/runs" \
    -f "head_sha=$sha" -f branch=main -f per_page=20 --jq '.workflow_runs[].id' 2>/dev/null) || rc=$?
  ids="${ids//$'\r'/}"
  if [ "$rc" -ne 0 ]; then
    lines="!the $workflow_file runs at its commit ${sha:0:8} cannot be listed"
  else
    for id in $ids; do
      case "$id" in '' | *[!0-9]*) lines="!the run list answer is not understood"; break ;; esac
      rc=0
      page=$(timeout -k 10 30 gh api -X GET "repos/$repo/actions/runs/$id/jobs" \
        -f filter=all -f per_page=100 \
        --jq '(if .total_count > (.jobs | length) then "!run '"$id"' has more jobs than one page" else empty end), (.jobs[] | "\(.conclusion // "unfinished") \(.name)")' \
        2>/dev/null) || rc=$?
      page="${page//$'\r'/}"
      if [ "$rc" -ne 0 ]; then
        lines="!the jobs of run $id cannot be listed"
        break
      fi
      lines="$lines${lines:+$'\n'}$page"
    done
  fi
  runs_memo[$sha]="$lines"
  listing="$lines"
}
run_reason() { # <sha> <job>: sets `why` to the reason its runs at <sha> make <job> save; empty when they do not
  local sha="$1" name problem concl bad
  why=""
  name=$(check_name "$2")
  if [ -z "$name" ]; then
    why="its check name is not known, so the run that saved it cannot be judged"
    return
  fi
  run_jobs "$sha"
  problem=$(grep -m1 '^!' <<<"$listing" || true)
  if [ -n "$problem" ]; then
    echo "::warning::gradle-cache-write.sh: ${problem#!}; '$2' saves"
    why="${problem#!}"
    return
  fi
  concl=$(awk -v n="$name" '{ c = $1; $1 = ""; sub(/^ /, ""); if ($0 == n) print c }' <<<"$listing")
  if [ -z "$concl" ]; then
    why="no '$name' job of a $workflow_file run on main at its commit ${sha:0:8} was found"
    return
  fi
  bad=$(grep -vx success <<<"$concl" | sort -u | tr '\n' ' ' | sed 's/ $//')
  if [ -n "$bad" ]; then
    why="'$name' at its commit ${sha:0:8} did not only succeed ($bad), so the entry may be incomplete"
  fi
}

# 2 to 5. Per job: the newest Gradle User Home entry of this month's lineage
# on main.
for job in $jobs; do
  rc=0
  newest=$(timeout -k 10 30 gh api -X GET "repos/${repo}/actions/caches" \
    -f "key=${prefix}gradle-home-v2|Linux-X64|${job}[" -f ref=refs/heads/main \
    -f sort=created_at -f direction=desc -f per_page=1 \
    --jq '.actions_caches[0] | if . == null then "none" else "\(.size_in_bytes) \(.key)" end' 2>/dev/null) || rc=$?
  if [ "$rc" -ne 0 ]; then
    echo "::warning::gradle-cache-write.sh: the cache list for '$job' cannot be read (exit $rc); it saves"
    emit "$job" true "the cache list cannot be read"
    continue
  fi
  read -r size key <<<"$newest"
  case "$size" in
    none)
      emit "$job" true "no ${prefix}gradle-home entry for it on main yet"
      continue ;;
    '' | *[!0-9]*)
      echo "::warning::gradle-cache-write.sh: unexpected cache list answer for '$job': $(head -1 <<<"$newest")"
      emit "$job" true "the cache list answer is not understood"
      continue ;;
  esac
  if [ "$size" -lt "$min_home_bytes" ]; then
    emit "$job" true "its newest entry is $size bytes, below $min_home_bytes: a build that failed early saved it ($key)"
    continue
  fi
  sha="${key##*]-}"
  if ! [[ "$sha" =~ ^[0-9a-f]{40}$ ]] || [ -z "$head_sha" ]; then
    echo "::warning::gradle-cache-write.sh: the entry '$key' names no commit, or HEAD is unknown; '$job' saves"
    emit "$job" true "the commit its newest entry was saved from is not known ($key)"
    continue
  fi
  since_reason "$sha"
  if [ -z "$why" ]; then run_reason "$sha" "$job"; fi
  if [ -n "$why" ]; then
    emit "$job" true "$why ($key)"
  else
    emit "$job" false "no Gradle input changed since the entry it restores, whose run succeeded; it restores $key ($size bytes)"
  fi
done
exit 0
