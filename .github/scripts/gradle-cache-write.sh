#!/usr/bin/env bash
#
# Which Gradle jobs save main's Gradle cache in this run (MR-2554). Appends
#   <job>=true|false
# for each job named on the command line to $GITHUB_OUTPUT (stdout when that
# is unset). android-ci.yml's `changes` job publishes them as
# gradle_cache_write_<job>, and each of those jobs' setup-gradle step is
# cache-read-only unless its value is 'true' (the self-test checks the
# wiring).
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
# job saves only if
#   1. the push changed a Gradle input (build scripts, gradle/, gradle.properties,
#      the wrapper, this workflow's or these scripts' cache rules), or the
#      push cannot be diffed (more than one commit, a new branch, no HEAD^1);
#   2. or this month's lineage (GRADLE_BUILD_ACTION_CACHE_KEY_PREFIX, MR-2536)
#      has no Gradle User Home entry for that job on main yet;
#   3. or its newest one is smaller than min_home_bytes: a build that failed
#      before resolving anything saved it (2026-11-20, the #656 merge run:
#      Build APK hit the plugin-marker flake cold and saved a 0.9 MB home with
#      an empty dependencies entry; its re-run could not replace the key).
# Otherwise the job restores the month's entry and saves nothing. A first
# push of a month always saves, through 2.
#
# NEVER FAILS, and errs towards saving: when the commit, the push or the
# cache list cannot be read, the job saves, as it did before MR-2554.
#
# Usage: gradle-cache-write.sh <job>...   (run in the `changes` checkout,
#   fetch-depth 2)
# Env: EVENT_NAME, GIT_REF (github.ref), BEFORE (github.event.before),
#   PREFIX (the month's lineage, gradle-cache-prefix.sh), GITHUB_REPOSITORY,
#   GH_TOKEN (actions: read).
# Self-test: .github/scripts/gradle-cache-write-test.sh
set -u

# A healthy Gradle User Home entry of these jobs is 9-12 MB (2026-11); one
# saved by a build that failed at configuration was 0.9 MB.
min_home_bytes=4000000
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

out="${GITHUB_OUTPUT:-/dev/stdout}"
summary="${GITHUB_STEP_SUMMARY:-/dev/null}"
event="${EVENT_NAME:-}"
ref="${GIT_REF:-}"
before="${BEFORE:-}"
prefix="${PREFIX:-}"

emit() { # <job> <true|false> <reason>
  echo "$1=$2" >> "$out"
  echo "Gradle cache, $1: $([ "$2" = true ] && echo saves || echo read-only) ($3)"
  echo "- \`$1\`: $([ "$2" = true ] && echo saves || echo read-only) ($3)" >> "$summary"
}
all() { # <true|false> <reason>: the same for every job
  local job
  for job in $jobs; do emit "$job" "$1" "$2"; done
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

# 1. Did this push change a Gradle input?
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
inputs=$(grep -E -f <(printf '%s\n' "$gradle_inputs") <<<"$changed" || true)
if [ -n "$inputs" ]; then
  all true "the push changed a Gradle input: $(head -3 <<<"$inputs" | tr '\n' ' ' | sed 's/ $//')$([ "$(wc -l <<<"$inputs")" -gt 3 ] && echo ' ...')"
  exit 0
fi

# 2 and 3. Per job: the newest Gradle User Home entry of this month's lineage
# on main.
for job in $jobs; do
  rc=0
  newest=$(timeout -k 10 30 gh api -X GET "repos/${GITHUB_REPOSITORY:-}/actions/caches" \
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
      emit "$job" true "no ${prefix}gradle-home entry for it on main yet" ;;
    '' | *[!0-9]*)
      echo "::warning::gradle-cache-write.sh: unexpected cache list answer for '$job': $(head -1 <<<"$newest")"
      emit "$job" true "the cache list answer is not understood" ;;
    *)
      if [ "$size" -lt "$min_home_bytes" ]; then
        emit "$job" true "its newest entry is $size bytes, below $min_home_bytes: a build that failed early saved it ($key)"
      else
        emit "$job" false "no Gradle input changed; it restores $key ($size bytes)"
      fi ;;
  esac
done
exit 0
