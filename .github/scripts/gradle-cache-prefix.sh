#!/usr/bin/env bash
#
# The Gradle cache lineage of a run (MR-2536). Appends
#   gradle_cache_prefix=<YYYY-MM>-
# to $GITHUB_OUTPUT (stdout when that is unset). android-ci.yml's `changes`
# job publishes it and every Gradle job there sets it as
# GRADLE_BUILD_ACTION_CACHE_KEY_PREFIX at job level; baseline-profile.yml
# runs this script in its own job and sets it on its setup-gradle step.
# Every setup-gradle step that caches must have it (the self-test checks).
#
# WHAT THE PREFIX REACHES. setup-gradle (v6.4.1) prefixes the key and the
# restore keys of ONE entry, the Gradle User Home entry
# (<prefix>gradle-home-v2|...). The extracted entries saved beside it
# (gradle-dependencies-v2-<hash>, gradle-transforms-v2-<hash>, the wrapper
# zips, generated and instrumented jars, toolchains, build cache, Kotlin and
# Groovy DSL) carry NO prefix. They are restored only by the exact keys a
# restored Gradle User Home entry lists, so they are bounded too: a new
# month's home starts empty and lists only what that month's builds
# downloaded. One that hashes the same in two months (the wrapper zip, small
# DSL and jar entries) is a single shared entry. The trap: listing or
# purging a month by key prefix (`gh cache list --key 2026-10-`) finds only
# the ~11 MB gradle-home entries, not the GB-sized dependencies and
# transforms entries; those go by GitHub's eviction once nothing reads them.
#
# WHY A NEW LINEAGE EVERY MONTH. Since MR-2511 (Mobile #654) the jobs that
# save main's Gradle cache never run setup-gradle's cleanup: on a warm build
# that cleanup deleted the POMs, module files and plugin markers Gradle reads
# from its descriptor cache, and the next restore fetched them all again
# (the kotlin.multiplatform plugin-marker flake). Without it, a saved cache
# keeps every dependency version a build ever downloaded and only grows. A
# new prefix matches none of the old Gradle User Home keys, so the month's
# first main run starts cold and saves a fresh, complete cache. The old
# month's home entries, and the extracted entries only they list, stop being
# restored once no run builds on an old-month base, and GitHub evicts them
# (7 days unused, or least recently used first at the repository's cache
# limit). No cleanup runs, so nothing is ever restored with files missing.
#
# WHICH MONTH. The committer date, in UTC, of the main commit the run builds
# on: the pushed, dispatched or scheduled commit (HEAD), or for a pull
# request the base tip, the first parent of the merge commit (so the checkout
# needs fetch-depth 2).
# A pull request then restores the lineage main saved for its base, and only
# main's first run of a month starts cold. The date of the run would leave
# every pull request cold from 00:00Z on the 1st until that main run saved.
#
# NEVER FAILS: if the commit cannot be read, the current UTC month, with a
# warning.
#
# Usage: gradle-cache-prefix.sh <event name>   (run in the checkout)
# Self-test: .github/scripts/gradle-cache-prefix-test.sh
set -u

event="${1:-}"
rev=HEAD
if [ "$event" = pull_request ]; then
  rev=HEAD^1
fi
month=$(TZ=UTC git show -s --format=%cd --date=format-local:%Y-%m "$rev" 2>/dev/null) || month=""
case "$month" in
  [0-9][0-9][0-9][0-9]-[01][0-9]) ;;
  *)
    month=$(date -u +%Y-%m)
    echo "::warning::gradle-cache-prefix.sh: cannot read the committer date of $rev ($event); the Gradle cache lineage is this month's, $month"
    ;;
esac
echo "Gradle cache lineage: ${month}- (from $rev, event '${event}')"
echo "gradle_cache_prefix=${month}-" >> "${GITHUB_OUTPUT:-/dev/stdout}"
