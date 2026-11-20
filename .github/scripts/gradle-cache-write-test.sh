#!/usr/bin/env bash
# Self-test for .github/scripts/gradle-cache-write.sh (MR-2554), run by Lint
# Check, and for its wiring into every workflow that runs setup-gradle.
# Builds throwaway git repositories; gh and timeout are stubs, so no case
# needs a token or the network.
#
#   not main / dispatch   pull request: no job saves; a dispatch on main: all
#   push, code only       a Kotlin change, healthy month entries: no job saves
#                         (the ~880 MB of transforms entries per push)
#   push, Gradle input    build scripts, gradle/, gradle.properties, gradlew,
#                         the workflow, the cache scripts: every job saves
#   push, not diffable    several commits, a new branch, no HEAD^1: all save
#   lineage               no entry for a job this month: it saves; its newest
#                         is below the floor (the 0.9 MB #656 merge-run home):
#                         it saves; the others do not
#   errors                the cache list fails or answers nonsense, no
#                         repository, no month prefix: the job saves, exit 0
#   reads                 every gh call is bounded by `timeout -k 10 30` and
#                         asks for this month's key, on main, newest first
#   wiring                every setup-gradle step in every workflow is
#                         cache-disabled, cache-read-only: true, or gated on
#                         needs.changes.outputs.gradle_cache_write_<its job>;
#                         `changes` publishes each gate from this script,
#                         which is run for exactly the gated jobs
set -euo pipefail

here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
script="$here/gradle-cache-write.sh"
workflows="$here/../workflows"
workflow="$workflows/android-ci.yml"
[ -f "$script" ] || { echo "::error::script missing: $script"; exit 1; }
[ -f "$workflow" ] || { echo "::error::workflow missing: $workflow"; exit 1; }

work="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/gradle-cache-write-selftest.XXXXXX")"
trap 'rm -rf "$work"' EXIT

fail=0
check() { # check <description> <command...>
  local d="$1"
  shift
  if "$@"; then echo "ok    $d"; else echo "::error::gradle-cache-write self-test: $d"; fail=1; fi
}

# -- stubs ---------------------------------------------------------------------
# gh api: answers the cache list from the case's fixture cache.<job>; logs
# every call. Anything but the expected list call is an error (exit 97).
stubs="$work/bin"
mkdir -p "$stubs"
cat >"$stubs/gh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$GCW_CASE/calls.log"
[ "${1:-}" = api ] && [ "${2:-}" = -X ] && [ "${3:-}" = GET ] &&
  [ "${4:-}" = "repos/o/r/actions/caches" ] || { echo "gh stub: unexpected call: $*" >&2; exit 97; }
shift 4
key="" ref="" sort="" dir="" per="" filter=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -f) case "$2" in
          key=*) key="${2#key=}" ;; ref=*) ref="${2#ref=}" ;; sort=*) sort="${2#sort=}" ;;
          direction=*) dir="${2#direction=}" ;; per_page=*) per="${2#per_page=}" ;;
          *) echo "gh stub: unexpected field $2" >&2; exit 97 ;;
        esac; shift 2 ;;
    --jq) filter="$2"; shift 2 ;;
    *) echo "gh stub: unexpected argument $1" >&2; exit 97 ;;
  esac
done
[ "$ref" = refs/heads/main ] && [ "$sort" = created_at ] && [ "$dir" = desc ] && [ "$per" = 1 ] ||
  { echo "gh stub: not main's newest entry: ref=$ref sort=$sort direction=$dir per_page=$per" >&2; exit 97; }
case "$key" in
  "${GCW_PREFIX}gradle-home-v2|Linux-X64|"*"[") job="${key#"${GCW_PREFIX}gradle-home-v2|Linux-X64|"}"; job="${job%[}" ;;
  *) echo "gh stub: unexpected key $key" >&2; exit 97 ;;
esac
fixture="$GCW_CASE/cache.$job"
[ -f "$fixture" ] || { echo "gh stub: no fixture for $job" >&2; exit 97; }
case "$(cat "$fixture")" in
  FAIL) echo "gh: HTTP 502" >&2; exit 1 ;;
esac
jq -r "$filter" "$fixture"
STUB
# timeout -k <s> <s> <command...>: logs the bound, then runs the command.
cat >"$stubs/timeout" <<'STUB'
#!/usr/bin/env bash
bound=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -k) bound="$bound-k $2 "; shift 2 ;;
    [0-9]*) bound="$bound$1"; shift; break ;;
    *) break ;;
  esac
done
printf '%s %s\n' "$bound" "${1:-}" >>"$GCW_CASE/timeouts.log"
exec "$@"
STUB
chmod +x "$stubs/gh" "$stubs/timeout"

# -- fixtures ------------------------------------------------------------------
prefix=2026-11-
entry() { # <job> <size>: the newest home entry of <job> in the month's lineage
  jq -n --arg k "${prefix}gradle-home-v2|Linux-X64|$1[5df42585ae8f8da228c604b8b4e8707c]-0123abcd" \
    --argjson s "$2" '{total_count: 1, actions_caches: [{key: $k, size_in_bytes: $s}]}'
}
no_entry() { echo '{"total_count": 0, "actions_caches": []}'; }

git_() { git -c core.autocrlf=false -c core.safecrlf=false -c core.longpaths=true "$@"; }
commit() { # <repo> <message>
  git_ -C "$1" add -A
  git_ -C "$1" -c user.name=selftest -c user.email=selftest@example.invalid \
    -c commit.gpgsign=false commit -q --allow-empty -m "$2"
}
case_dir=""
repo=""
before=""
new_case() { # <name> <path changed by the pushed commit>...: a repo, base + that commit
  case_dir="$work/case-$1"
  shift
  repo="$case_dir/repo"
  mkdir -p "$repo"
  git_ init -q "$repo"
  mkdir -p "$repo/app/src/main/kotlin" "$repo/shared" "$repo/gradle/wrapper" \
    "$repo/.github/workflows" "$repo/.github/scripts" "$repo/docs/gradle"
  for f in app/src/main/kotlin/A.kt build.gradle.kts settings.gradle.kts app/build.gradle.kts \
           shared/build.gradle.kts gradle.properties gradle/verification-metadata.xml \
           gradle/wrapper/gradle-wrapper.properties gradlew .github/workflows/android-ci.yml \
           .github/workflows/apple-ci.yml .github/scripts/gradle-cache-prefix.sh docs/gradle/notes.md; do
    echo base >"$repo/$f"
  done
  commit "$repo" base
  before=$(git_ -C "$repo" rev-parse HEAD)
  local p
  for p in "$@"; do mkdir -p "$(dirname "$repo/$p")"; echo change >>"$repo/$p"; done
  commit "$repo" change
  for j in lint test build; do entry "$j" 10000000 >"$case_dir/cache.$j"; done
}
rc=0
out=""
decide() { # <event> <ref> [before] [prefix]: runs the script in the case's repo for lint test build
  rc=0
  : >"$case_dir/output"
  out=$(cd "$repo" && PATH="$stubs:$PATH" GCW_CASE="$case_dir" GCW_PREFIX="$prefix" \
        GITHUB_OUTPUT="$case_dir/output" GITHUB_STEP_SUMMARY="$case_dir/summary" \
        GITHUB_REPOSITORY=o/r EVENT_NAME="$1" GIT_REF="$2" BEFORE="${3-$before}" PREFIX="${4-$prefix}" \
        bash "$script" lint test build 2>&1) || rc=$?
}
writes() { # <lint> <test> <build>: exit 0 and exactly these values
  [ "$rc" -eq 0 ] &&
    [ "$(cat "$case_dir/output")" = "$(printf 'lint=%s\ntest=%s\nbuild=%s' "$1" "$2" "$3")" ]
}
gh_calls() { if [ -f "$case_dir/calls.log" ]; then wc -l <"$case_dir/calls.log"; else echo 0; fi; }
no_gh() { [ "$(gh_calls)" -eq 0 ]; }
warned() { grep -q '^::warning::' <<<"$out"; }
all_reads_bounded() {
  local calls bounded
  calls=$(gh_calls)
  bounded=$(grep -cx -- '-k 10 30 gh' "$case_dir/timeouts.log" 2>/dev/null || true)
  [ "$calls" -gt 0 ] && [ "${bounded:-0}" -eq "$calls" ] && [ "$(wc -l <"$case_dir/timeouts.log")" -eq "$calls" ]
}

# -- not a push to main ----------------------------------------------------------
new_case pr app/src/main/kotlin/A.kt
decide pull_request refs/pull/1/merge
check "pull request: no job saves" writes false false false
check "pull request: no cache list read" no_gh

new_case dispatch app/src/main/kotlin/A.kt
decide workflow_dispatch refs/heads/main
check "dispatch on main: every job saves, as before" writes true true true
check "dispatch on main: no cache list read" no_gh

# -- a push to main that changes only code (the churn, MR-2554) --------------------
new_case code-only app/src/main/kotlin/A.kt docs/gradle/notes.md .github/workflows/apple-ci.yml
decide push refs/heads/main
check "push, code only, healthy month entries: no job saves" writes false false false
check "push, code only: one cache list read per job" eval '[ "$(gh_calls)" -eq 3 ]'
check "push, code only: every read is bounded by \`timeout -k 10 30\`" all_reads_bounded
check "push, code only: the summary names the entry each job restores" \
  grep -qF "read-only (no Gradle input changed; it restores ${prefix}gradle-home-v2|Linux-X64|build[" "$case_dir/summary"

# -- a push that changes a Gradle input ----------------------------------------------
n=0
for input in shared/build.gradle.kts settings.gradle.kts gradle.properties gradle/verification-metadata.xml \
             gradle/wrapper/gradle-wrapper.properties gradle/libs.versions.toml gradlew \
             buildSrc/src/main/kotlin/Conv.kt .github/workflows/android-ci.yml \
             .github/scripts/gradle-cache-prefix.sh; do
  n=$((n + 1))
  new_case "input-$n" app/src/main/kotlin/A.kt "$input"
  decide push refs/heads/main
  check "push changing $input: every job saves" writes true true true
  check "push changing $input: no cache list read" no_gh
done

# -- a push that cannot be diffed -------------------------------------------------------
new_case several-commits app/src/main/kotlin/A.kt
decide push refs/heads/main 0123456789abcdef0123456789abcdef01234567
check "push of more than one commit (before is not HEAD^1): every job saves" writes true true true
new_case new-branch app/src/main/kotlin/A.kt
decide push refs/heads/main 0000000000000000000000000000000000000000
check "push creating the branch (before is zeros): every job saves" writes true true true
new_case depth-1 app/src/main/kotlin/A.kt
shallow="$case_dir/shallow"
git_ clone -q --depth 1 "file://$repo" "$shallow"
repo="$shallow"
decide push refs/heads/main
check "push without HEAD^1 in the checkout: every job saves, exit 0" writes true true true

# -- the month's lineage -----------------------------------------------------------------
new_case broken-build app/src/main/kotlin/A.kt
entry build 905960 >"$case_dir/cache.build"
decide push refs/heads/main
check "push, code only, Build APK's newest entry 0.9 MB (the #656 merge run's): only it saves" writes false false true
new_case no-test-entry app/src/main/kotlin/A.kt
no_entry >"$case_dir/cache.test"
decide push refs/heads/main
check "push, code only, no entry for Unit Tests this month: only it saves" writes false true false
new_case at-floor app/src/main/kotlin/A.kt
entry lint 4000000 >"$case_dir/cache.lint"
entry test 3999999 >"$case_dir/cache.test"
decide push refs/heads/main
check "push, code only, entries at and just below the 4000000-byte floor: below saves" writes false true false

# -- errors: the job saves, the step never fails -----------------------------------------
new_case list-fails app/src/main/kotlin/A.kt
echo FAIL >"$case_dir/cache.lint"
decide push refs/heads/main
check "the cache list fails for Lint Check: it saves, the others do not, exit 0" writes true false false
check "the cache list fails: a warning" warned
new_case list-nonsense app/src/main/kotlin/A.kt
echo '{"total_count": 1, "actions_caches": [{"key": "k", "size_in_bytes": "big"}]}' >"$case_dir/cache.build"
decide push refs/heads/main
check "the cache list answers nonsense for Build APK: it saves" writes false false true
new_case no-prefix app/src/main/kotlin/A.kt
decide push refs/heads/main "$before" ""
check "no month prefix: every job saves, with a warning" eval 'writes true true true && warned'
new_case no-repo app/src/main/kotlin/A.kt
repo="$case_dir/not-a-repo"
mkdir -p "$repo"
rc=0
out=$(cd "$repo" && GIT_CEILING_DIRECTORIES="$case_dir" PATH="$stubs:$PATH" GCW_CASE="$case_dir" \
      GCW_PREFIX="$prefix" GITHUB_OUTPUT="$case_dir/output" GITHUB_REPOSITORY=o/r \
      EVENT_NAME=push GIT_REF=refs/heads/main BEFORE="$before" PREFIX="$prefix" \
      bash "$script" lint test build 2>&1) || rc=$?
check "no repository: every job saves, exit 0" writes true true true

# -- wiring: every workflow that runs setup-gradle -------------------------------------------
# Steps as the other self-tests read them: jobs at 2 spaces, steps as `      - `
# at 6, their inputs at 10. An independent count of every setup-gradle line
# must match, so a step this parser cannot read fails instead of passing.
gate_re='^\$\{\{ github\.ref != '"'"'refs/heads/main'"'"' \|\| needs\.changes\.outputs\.gradle_cache_write_([A-Za-z0-9_-]+) != '"'"'true'"'"' \}\}$'
declare -a files=()
for f in "$workflows"/*.yml "$workflows"/*.yaml; do [ -f "$f" ] && files+=("$f"); done
steps=$(awk '
  function flush_step() {
    if (in_step && gradle) print "step", file, job, (ro == "" ? "-" : ro), (dis == "" ? "-" : dis), needs
    in_step = 0; gradle = 0; ro = ""; dis = ""
  }
  FNR == 1 { flush_step(); in_jobs = 0; job = ""; file = FILENAME; sub(/.*\//, "", file) }
  /^[[:space:]]*#/ { next }
  /^jobs:[[:space:]]*$/ { flush_step(); in_jobs = 1; next }
  /^[^[:space:]]/ { flush_step(); in_jobs = 0; next }
  !in_jobs { next }
  /^  [A-Za-z0-9_-]+:[[:space:]]*$/ { flush_step(); job = $1; sub(/:$/, "", job); needs = 0; next }
  /^    [A-Za-z_-]+:/ { flush_step() }
  /^    needs:.*changes/ { needs = 1 }
  /^      - / { flush_step(); in_step = 1 }
  !in_step { next }
  /uses: gradle\/actions\/setup-gradle@/ { gradle = 1 }
  /^          cache-read-only:/ { ro = $0; sub(/^          cache-read-only:[[:space:]]*/, "", ro); sub(/[[:space:]]+$/, "", ro); gsub(/ /, "\001", ro) }
  /^          cache-disabled:/ { dis = $0; sub(/^          cache-disabled:[[:space:]]*/, "", dis); sub(/[[:space:]]+$/, "", dis) }
  END { flush_step() }' "${files[@]}")
grepped=$(grep -h -v '^[[:space:]]*#' "${files[@]}" | grep -cE "uses:[[:space:]]*[\"']?gradle/actions/setup-gradle@" || true)
parsed=$(grep -c '^step ' <<<"$steps" || true)
gated="" ungated="" wrong=""
while read -r _ file job ro dis needs; do
  ro="${ro//$'\001'/ }"
  if [ "$dis" = true ] || [ "$ro" = true ]; then continue; fi
  if [[ "$ro" =~ $gate_re ]]; then
    if [ "${BASH_REMATCH[1]}" = "$job" ] && [ "$file" = android-ci.yml ] && [ "$needs" = 1 ]; then
      gated="$gated $job"
    else
      wrong="$wrong $file:$job(gate ${BASH_REMATCH[1]})"
    fi
  else
    ungated="$ungated $file:$job"
  fi
done < <(grep '^step ' <<<"$steps")
gated="${gated# }"
check "all workflows: $parsed setup-gradle steps parsed, $grepped found by grep" eval '[ "$parsed" -ge 1 ] && [ "$parsed" = "$grepped" ]'
check "all workflows: no setup-gradle step saves on every main push${ungated:+ (ungated:$ungated)}" eval '[ -z "$ungated" ]'
check "all workflows: each gate names its own job, in android-ci.yml, which needs changes${wrong:+ (wrong:$wrong)}" eval '[ -z "$wrong" ]'
check "android-ci.yml: the gated jobs are lint, test and build (got: ${gated:-none})" \
  eval '[ "$(tr " " "\n" <<<"$gated" | sort | tr "\n" " ")" = "build lint test " ]'

# `changes` publishes each gate from the script's step, runs the script for
# exactly the gated jobs, and may read the cache list.
changes_job=$(awk '
  /^  [A-Za-z0-9_-]+:[[:space:]]*$/ { inc = ($1 == "changes:"); next }
  inc' "$workflow")
published_ok() {
  local j
  for j in $gated; do
    grep -qxF "      gradle_cache_write_$j: \${{ steps.gradle-write.outputs.$j }}" <<<"$changes_job" || return 1
  done
}
runs_for_gated() {
  local args
  args=$(sed -nE 's/^ +run: bash \.github\/scripts\/gradle-cache-write\.sh (.*)$/\1/p' <<<"$changes_job")
  [ -n "$args" ] && [ "$(tr " " "\n" <<<"$args" | sort | tr "\n" " ")" = "$(tr " " "\n" <<<"$gated" | sort | tr "\n" " ")" ]
}
check "android-ci.yml: \`changes\` publishes gradle_cache_write_<job> for each gated job" published_ok
check "android-ci.yml: \`changes\` runs gradle-cache-write.sh for exactly the gated jobs" runs_for_gated
check "android-ci.yml: the step is id gradle-write, reads the lineage, and \`changes\` has actions: read" eval '
  grep -qE "^      - id: gradle-write\$" <<<"$changes_job" &&
  grep -qF "PREFIX: \${{ steps.gradle-cache.outputs.gradle_cache_prefix }}" <<<"$changes_job" &&
  grep -qF "BEFORE: \${{ github.event.before }}" <<<"$changes_job" &&
  grep -qE "^      actions: read\$" <<<"$changes_job"'

if [ "$fail" -ne 0 ]; then
  echo "::error::gradle-cache-write self-test failed (above)"
  exit 1
fi
echo "gradle-cache-write self-test: all cases pass"
