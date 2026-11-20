#!/usr/bin/env bash
# Self-test for .github/scripts/gradle-cache-prefix.sh (MR-2536), run by Lint
# Check, and for its wiring in android-ci.yml. Builds throwaway git
# repositories with fixed committer dates; no network.
#
#   push            the pushed commit's committer month, in UTC
#   other timezone  2026-11-30T20:30-05:00 is 2026-12 in UTC, not 2026-11
#   pull request    the base tip's month (HEAD^1), not the merge commit's
#   unreadable      no HEAD^1 (depth 1) or no repository: this UTC month,
#                   a warning, exit 0
#   wiring          `changes` publishes the output, and every job with a
#                   setup-gradle step sets GRADLE_BUILD_ACTION_CACHE_KEY_PREFIX
#                   from it
set -euo pipefail

here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
script="$here/gradle-cache-prefix.sh"
workflow="$here/../workflows/android-ci.yml"
[ -f "$script" ] || { echo "::error::script missing: $script"; exit 1; }
[ -f "$workflow" ] || { echo "::error::workflow missing: $workflow"; exit 1; }

work="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/gradle-cache-prefix-selftest.XXXXXX")"
trap 'rm -rf "$work"' EXIT

fail=0
check() { # check <description> <command...>
  local d="$1"
  shift
  if "$@"; then echo "ok    $d"; else echo "::error::gradle-cache-prefix self-test: $d"; fail=1; fi
}

commit() { # commit <repo> <committer date> <message>
  GIT_AUTHOR_DATE="$2" GIT_COMMITTER_DATE="$2" \
    git -C "$1" -c user.name=selftest -c user.email=selftest@example.invalid \
    -c commit.gpgsign=false commit -q --allow-empty -m "$3"
}

repo="$work/repo"
git init -q "$repo"
git -C "$repo" checkout -q -b main
commit "$repo" 2026-11-30T23:59:59Z base
git -C "$repo" checkout -q -b feature
commit "$repo" 2026-12-01T00:00:01Z change
git -C "$repo" checkout -q main
# The pull request's merge commit: first parent the base tip (2026-11).
GIT_COMMITTER_DATE=2026-12-01T00:00:05Z git -C "$repo" -c user.name=selftest \
  -c user.email=selftest@example.invalid -c commit.gpgsign=false \
  merge -q --no-ff -m merge feature

rc=0
out=""
prefix_of() { # prefix_of <dir> <event>: runs the script there, as CI does
  rm -f "$work/output"
  rc=0
  out=$(cd "$1" && TZ=America/New_York GITHUB_OUTPUT="$work/output" bash "$script" "$2" 2>&1) || rc=$?
}
wrote() { [ "$rc" -eq 0 ] && [ "$(cat "$work/output")" = "gradle_cache_prefix=$1" ]; }
warned() { grep -q '^::warning::' <<<"$out"; }

prefix_of "$repo" pull_request
check "pull request: the base tip's month (2026-11-), not the merge commit's (2026-12)" wrote 2026-11-
check "pull request: no warning" eval '! warned'

prefix_of "$repo" push
check "push: the pushed commit's month (here the merge commit, 2026-12-)" wrote 2026-12-

git -C "$repo" checkout -q main~1
prefix_of "$repo" workflow_dispatch
check "dispatch: the checked-out commit's month (2026-11-)" wrote 2026-11-

tz="$work/tz"
git init -q "$tz"
commit "$tz" 2026-11-30T20:30:00-05:00 late-evening-in-new-york
prefix_of "$tz" push
check "committer timezone: 2026-11-30T20:30-05:00 is 2026-12 in UTC" wrote 2026-12-

before=$(date -u +%Y-%m)
prefix_of "$tz" pull_request
after=$(date -u +%Y-%m)
check "unreadable (no HEAD^1): this UTC month, exit 0" eval 'wrote "$before-" || wrote "$after-"'
check "unreadable (no HEAD^1): a warning" warned

mkdir -p "$work/not-a-repo"
before=$(date -u +%Y-%m)
GIT_CEILING_DIRECTORIES="$work" prefix_of "$work/not-a-repo" push
after=$(date -u +%Y-%m)
check "no repository: this UTC month, exit 0" eval 'wrote "$before-" || wrote "$after-"'

# -- wiring in android-ci.yml ----------------------------------------------------
# Every job with a setup-gradle step restores (and on main saves) the lineage
# `changes` names; a job without it would restore and grow an unbounded one.
wire="GRADLE_BUILD_ACTION_CACHE_KEY_PREFIX: \${{ needs.changes.outputs.gradle_cache_prefix }}"
unwired=$(awk -v wire="$wire" '
  function flush() { if (job != "" && gradle && !(has_wire && has_needs)) print job }
  /^  [A-Za-z0-9_-]+:[[:space:]]*$/ { flush(); job = $1; gradle = 0; has_wire = 0; has_needs = 0; next }
  /uses: gradle\/actions\/setup-gradle@/ { gradle = 1 }
  index($0, "      " wire) == 1 { has_wire = 1 }
  /^    needs:.*changes/ { has_needs = 1 }
  END { flush() }' "$workflow")
gradle_jobs=$(grep -c 'uses: gradle/actions/setup-gradle@' "$workflow" || true)
check "android-ci.yml: $gradle_jobs setup-gradle steps, all in jobs that set the lineage from \`changes\`${unwired:+ (missing: $unwired)}" \
  eval '[ "$gradle_jobs" -ge 1 ] && [ -z "$unwired" ]'
check "android-ci.yml: \`changes\` publishes gradle_cache_prefix from this script" eval '
  grep -qF "gradle_cache_prefix: \${{ steps.gradle-cache.outputs.gradle_cache_prefix }}" "$workflow" &&
  grep -qF "bash .github/scripts/gradle-cache-prefix.sh" "$workflow"'

if [ "$fail" -ne 0 ]; then
  echo "::error::gradle-cache-prefix self-test failed (above)"
  exit 1
fi
echo "gradle-cache-prefix self-test: all cases pass"
