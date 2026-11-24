#!/usr/bin/env bash
# Self-test for .github/scripts/gradle-cache-write.sh (MR-2554), run by Lint
# Check, and for its wiring into every workflow that runs setup-gradle.
# Builds throwaway git repositories; gh and timeout are stubs (the comparison
# is answered from the case's own repository), so no case needs a token or
# the network.
#
#   not main / dispatch   pull request: no job saves; a dispatch on main: all
#   push, code only       a Kotlin change, healthy month entries whose runs
#                         succeeded: no job saves (the ~880 MB of transforms
#                         entries per push); one comparison and one run
#                         listing for the commit the entries share
#   push, Gradle input    build scripts, gradle/, gradle.properties, gradlew,
#                         the workflow, the cache scripts: every job saves
#   push, not diffable    several commits, a new branch, no HEAD^1: all save
#   lineage               no entry for a job this month: it saves; its newest
#                         is below the floor (the 0.9 MB #656 merge-run home):
#                         it saves; the others do not
#   since the entry       a Gradle input changed after the entry's commit but
#                         not in this push (the cancelled 9.9.0 wrapper bump
#                         d8ca194, then the cargo-only 592faca), also as a
#                         rename: it saves; Kotlin-only commits since: it does
#                         not; entries of different commits are judged apart;
#                         a commit not behind HEAD, 300+ files, a key with no
#                         commit: it saves
#   the entry's run       the job failed in any attempt (#656's Build APK
#                         failed, then passed on re-run), was cancelled, has
#                         not finished, failed in a second run at that commit,
#                         or no run is found: it saves; another job's failure
#                         does not count
#   errors                the cache list, the comparison, the run list or the
#                         job list fails or answers nonsense, no repository,
#                         no month prefix: the job saves, exit 0
#   reads                 every gh call is bounded by `timeout -k 10 30`; the
#                         cache list asks for this month's key, on main, newest
#                         first; the job list asks for every attempt
#   wiring                every setup-gradle step in every workflow is
#                         cache-disabled, cache-read-only: true, or gated on
#                         needs.changes.outputs.gradle_cache_write_<its job>
#                         == 'false' (so an empty answer saves); `changes`
#                         publishes each gate from this script, which is run
#                         for exactly the gated jobs; the script's check names
#                         are those jobs' names
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
# gh api -X GET <endpoint> [-f k=v]... --jq <filter>: answers from the case's
# fixtures (the comparison from the case's repository, the script's working
# directory); logs every call. Any other call, or an endpoint asked with other
# fields, is an error (exit 97).
stubs="$work/bin"
mkdir -p "$stubs"
cat >"$stubs/gh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$GCW_CASE/calls.log"
[ "${1:-}" = api ] && [ "${2:-}" = -X ] && [ "${3:-}" = GET ] || { echo "gh stub: unexpected call: $*" >&2; exit 97; }
endpoint="${4:-}"
shift 4
declare -A f=()
filter=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -f) f["${2%%=*}"]="${2#*=}"; shift 2 ;;
    --jq) filter="$2"; shift 2 ;;
    *) echo "gh stub: unexpected argument $1" >&2; exit 97 ;;
  esac
done
[ -n "$filter" ] || { echo "gh stub: no --jq" >&2; exit 97; }
fields() { # exactly these -f fields
  local got want
  got=$(for k in "${!f[@]}"; do echo "$k"; done | sort | tr '\n' ' ')
  want=$(printf '%s\n' "$@" | sed '/^$/d' | sort | tr '\n' ' ')
  [ "$got" = "$want" ] || { echo "gh stub: $endpoint asked with fields '$got', expected '$want'" >&2; exit 97; }
}
answer() { jq -r "$filter" "$1"; }
case "$endpoint" in
  repos/o/r/actions/caches)
    fields key ref sort direction per_page
    [ "${f[ref]}" = refs/heads/main ] && [ "${f[sort]}" = created_at ] && [ "${f[direction]}" = desc ] && [ "${f[per_page]}" = 1 ] ||
      { echo "gh stub: not main's newest entry: ref=${f[ref]} sort=${f[sort]} direction=${f[direction]} per_page=${f[per_page]}" >&2; exit 97; }
    key="${f[key]}"
    case "$key" in
      "${GCW_PREFIX}gradle-home-v2|Linux-X64|"*"[") job="${key#"${GCW_PREFIX}gradle-home-v2|Linux-X64|"}"; job="${job%[}" ;;
      *) echo "gh stub: unexpected key $key" >&2; exit 97 ;;
    esac
    fixture="$GCW_CASE/cache.$job"
    [ -f "$fixture" ] || { echo "gh stub: no fixture for $job" >&2; exit 97; }
    case "$(cat "$fixture")" in
      FAIL) echo "gh: HTTP 502" >&2; exit 1 ;;
    esac
    answer "$fixture" ;;
  repos/o/r/compare/*...*)
    fields
    if [ -f "$GCW_CASE/compare.fail" ]; then echo "gh: HTTP 502" >&2; exit 1; fi
    if [ -f "$GCW_CASE/compare.json" ]; then answer "$GCW_CASE/compare.json"; exit 0; fi
    spec="${endpoint#repos/o/r/compare/}"
    base="${spec%%...*}"
    head="${spec#*...}"
    if ! git cat-file -e "$base^{commit}" 2>/dev/null || ! git cat-file -e "$head^{commit}" 2>/dev/null; then
      echo "gh: Not Found (HTTP 404)" >&2; exit 1
    fi
    if [ "$base" = "$head" ]; then status=identical
    elif git merge-base --is-ancestor "$base" "$head"; then status=ahead
    elif git merge-base --is-ancestor "$head" "$base"; then status=behind
    else status=diverged; fi
    # Like GitHub: the files from the merge base to head, renames detected.
    git diff -M --name-status "$(git merge-base "$base" "$head")" "$head" |
      jq -R -s --arg s "$status" '{status: $s, files: [split("\n")[] | select(length > 0) | split("\t")
        | if (.[0] | startswith("R")) then {status: "renamed", previous_filename: .[1], filename: .[2]}
          else {status: .[0], filename: .[1]} end]}' >"$GCW_CASE/compare.answer"
    answer "$GCW_CASE/compare.answer" ;;
  repos/o/r/actions/workflows/android-ci.yml/runs)
    fields head_sha branch per_page
    [ "${f[branch]}" = main ] || { echo "gh stub: runs not on main: ${f[branch]}" >&2; exit 97; }
    if [ -f "$GCW_CASE/runs.fail" ]; then echo "gh: HTTP 502" >&2; exit 1; fi
    fixture="$GCW_CASE/runs.${f[head_sha]}"
    [ -f "$fixture" ] || { fixture="$GCW_CASE/runs.none"; echo '{"total_count": 0, "workflow_runs": []}' >"$fixture"; }
    answer "$fixture" ;;
  repos/o/r/actions/runs/*/jobs)
    fields filter per_page
    [ "${f[filter]}" = all ] || { echo "gh stub: jobs of one attempt only (filter=${f[filter]})" >&2; exit 97; }
    if [ -f "$GCW_CASE/jobs.fail" ]; then echo "gh: HTTP 502" >&2; exit 1; fi
    id="${endpoint#repos/o/r/actions/runs/}"
    id="${id%/jobs}"
    [ -f "$GCW_CASE/jobs.$id" ] || { echo "gh stub: no jobs fixture for run $id" >&2; exit 97; }
    answer "$GCW_CASE/jobs.$id" ;;
  *) echo "gh stub: unexpected endpoint $endpoint" >&2; exit 97 ;;
esac
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
entry_sha=""
entry() { # <job> <size> [<commit>]: the newest home entry of <job> in the month's lineage, saved from <commit>
  jq -n --arg k "${prefix}gradle-home-v2|Linux-X64|$1[5df42585ae8f8da228c604b8b4e8707c]-${3:-$entry_sha}" \
    --argjson s "$2" '{total_count: 1, actions_caches: [{key: $k, size_in_bytes: $s}]}'
}
no_entry() { echo '{"total_count": 0, "actions_caches": []}'; }
runs_at() { # <commit> <run id>...: the android-ci runs on main at <commit>
  local sha="$1"
  shift
  printf '%s\n' "$@" | jq -R -s '[split("\n")[] | select(length > 0) | {id: tonumber}]
    | {total_count: length, workflow_runs: .}' >"$case_dir/runs.$sha"
}
jobs_of() { # <run id> "<conclusion>|<check name>"...: its jobs over all attempts; conclusion null = unfinished
  local id="$1"
  shift
  printf '%s\n' "$@" | jq -R -s '[split("\n")[] | select(length > 0) | split("|")
    | {name: .[1], status: (if .[0] == "null" then "in_progress" else "completed" end),
       conclusion: (if .[0] == "null" then null else .[0] end)}] | {total_count: length, jobs: .}' >"$case_dir/jobs.$id"
}
good_run() { # <commit> <run id>: a run at <commit> in which the three jobs succeeded (another job failed)
  runs_at "$1" "$2"
  jobs_of "$2" "success|Detect changes" "success|Lint Check" "success|Unit Tests" "success|Build APK" \
    "failure|Instrumented Tests"
}

git_() { git -c core.autocrlf=false -c core.safecrlf=false -c core.longpaths=true "$@"; }
commit() { # <repo> <message>
  git_ -C "$1" add -A
  git_ -C "$1" -c user.name=selftest -c user.email=selftest@example.invalid \
    -c commit.gpgsign=false commit -q --allow-empty -m "$2"
}
case_dir=""
repo=""
before=""
new_case() { # <name> <path changed by the pushed commit>...: a repo, base + that commit; month
  # entries for every job saved from base, by a run in which they succeeded
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
  entry_sha="$before"
  local p
  for p in "$@"; do mkdir -p "$(dirname "$repo/$p")"; echo change >>"$repo/$p"; done
  commit "$repo" change
  for j in lint test build; do entry "$j" 10000000 >"$case_dir/cache.$j"; done
  good_run "$entry_sha" 101
}
then_push() { # <path>...: one more commit on top; it is the push, the old HEAD is `before`
  before=$(git_ -C "$repo" rev-parse HEAD)
  local p
  for p in "$@"; do mkdir -p "$(dirname "$repo/$p")"; echo "change $RANDOM" >>"$repo/$p"; done
  commit "$repo" "push"
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
gh_calls() { # [<endpoint pattern>]: how many gh calls (matching it)
  if [ -f "$case_dir/calls.log" ]; then grep -c -- "${1:-}" "$case_dir/calls.log" || true; else echo 0; fi
}
no_gh() { [ "$(gh_calls)" -eq 0 ]; }
warned() { grep -q '^::warning::' <<<"$out"; }
said() { grep -qF -- "$1" "$case_dir/summary"; }
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
check "push, code only: one cache list read per job" eval '[ "$(gh_calls /actions/caches)" -eq 3 ]'
check "push, code only: the three entries' shared commit is compared once, with HEAD" \
  eval '[ "$(gh_calls "/compare/$entry_sha\.\.\.$(git_ -C "$repo" rev-parse HEAD)")" -eq 1 ] && [ "$(gh_calls /compare/)" -eq 1 ]'
check "push, code only: its runs and their jobs are listed once" \
  eval '[ "$(gh_calls /workflows/android-ci.yml/runs)" -eq 1 ] && [ "$(gh_calls /actions/runs/101/jobs)" -eq 1 ]'
check "push, code only: every read is bounded by \`timeout -k 10 30\`" all_reads_bounded
check "push, code only: the summary names the entry each job restores" \
  said "it restores ${prefix}gradle-home-v2|Linux-X64|build["

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

# -- what changed since the entry's commit (M1: not only HEAD^1..HEAD) --------------------
# 2026-11-17: d8ca194 (gradle-wrapper 9.8.2 -> 9.9.0) was a pending run cancelled
# when 592faca (a cargo bump) queued; 592faca's own diff touches no Gradle input.
new_case superseded gradle/wrapper/gradle-wrapper.properties
then_push native/rosenpass-jni/Cargo.toml
decide push refs/heads/main
check "a Gradle-input commit whose run saved nothing, then a cargo-only push: every job saves" writes true true true
check "the cancelled wrapper bump: the summary names the input and the entry's commit" \
  said "a Gradle input changed since its commit ${entry_sha:0:8}: gradle/wrapper/gradle-wrapper.properties"
new_case renamed
git_ -C "$repo" mv gradle/verification-metadata.xml docs/verification-metadata.xml
commit "$repo" rename
then_push app/src/main/kotlin/A.kt
decide push refs/heads/main
check "a Gradle input renamed away since the entry's commit (GitHub lists it as renamed): every job saves" writes true true true
new_case kotlin-since app/src/main/kotlin/A.kt
then_push app/src/main/kotlin/A.kt docs/gradle/notes.md
decide push refs/heads/main
check "two Kotlin-only commits since the entries' commit: no job saves" writes false false false
new_case apart gradle.properties
mid=$(git_ -C "$repo" rev-parse HEAD)
then_push app/src/main/kotlin/A.kt
entry test 10000000 "$mid" >"$case_dir/cache.test"
entry build 10000000 "$mid" >"$case_dir/cache.build"
good_run "$mid" 102
decide push refs/heads/main
check "gradle.properties changed after Lint Check's entry but before the others': only Lint Check saves" writes true false false
check "entries of two commits: two comparisons" eval '[ "$(gh_calls /compare/)" -eq 2 ]'
new_case diverged app/src/main/kotlin/A.kt
side=$(git_ -C "$repo" -c user.name=selftest -c user.email=selftest@example.invalid \
  commit-tree "$entry_sha^{tree}" -p "$entry_sha" -m side)
for j in lint test build; do entry "$j" 10000000 "$side" >"$case_dir/cache.$j"; done
good_run "$side" 103
decide push refs/heads/main
check "the entries' commit is not behind HEAD (diverged): every job saves" writes true true true
new_case cap app/src/main/kotlin/A.kt
jq -n '{status: "ahead", files: [range(300) | {filename: "docs/f\(.).md"}]}' >"$case_dir/compare.json"
decide push refs/heads/main
check "300 files changed since the entries' commit (the comparison's limit): every job saves" writes true true true
new_case below-cap app/src/main/kotlin/A.kt
jq -n '{status: "ahead", files: [range(299) | {filename: "docs/f\(.).md"}]}' >"$case_dir/compare.json"
decide push refs/heads/main
check "299 files, none a Gradle input: no job saves" writes false false false
new_case short-key app/src/main/kotlin/A.kt
entry lint 10000000 0123abcd >"$case_dir/cache.lint"
decide push refs/heads/main
check "Lint Check's key ends in no full commit: only it saves, with a warning" eval 'writes true false false && warned'

# -- the run that saved the entry (M1: a failed run's entry over the floor) -------------------
new_case failed-attempt app/src/main/kotlin/A.kt
jobs_of 101 "success|Lint Check" "success|Unit Tests" "failure|Build APK" "success|Build APK"
decide push refs/heads/main
check "Build APK failed at the entries' commit, then passed on re-run (#656's merge run): only it saves" writes false false true
check "the failed attempt: the summary says why" said "'Build APK' at its commit ${entry_sha:0:8} did not only succeed (failure)"
new_case cancelled-unfinished app/src/main/kotlin/A.kt
jobs_of 101 "cancelled|Lint Check" "null|Unit Tests" "success|Build APK"
decide push refs/heads/main
check "Lint Check cancelled and Unit Tests unfinished at the entries' commit: they save" writes true true false
new_case second-run app/src/main/kotlin/A.kt
runs_at "$entry_sha" 101 104
jobs_of 104 "failure|Lint Check" "success|Unit Tests" "success|Build APK"
decide push refs/heads/main
check "a second run at the entries' commit (a dispatch) failed Lint Check: only it saves" writes true false false
new_case no-run app/src/main/kotlin/A.kt
rm -f "$case_dir/runs.$entry_sha"
decide push refs/heads/main
check "no android-ci run on main at the entries' commit: every job saves" writes true true true
new_case job-missing app/src/main/kotlin/A.kt
jobs_of 101 "success|Lint Check" "success|Unit Tests"
decide push refs/heads/main
check "no Build APK job in the run at the entries' commit: only it saves" writes false false true

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
for what in compare runs jobs; do
  new_case "$what-fails" app/src/main/kotlin/A.kt
  : >"$case_dir/$what.fail"
  decide push refs/heads/main
  check "the $what read fails: every job saves, with a warning, exit 0" eval 'writes true true true && warned'
done
new_case runs-nonsense app/src/main/kotlin/A.kt
echo '{"total_count": 1, "workflow_runs": [{"id": "x1"}]}' >"$case_dir/runs.$entry_sha"
decide push refs/heads/main
check "the run list answers nonsense: every job saves" writes true true true
new_case jobs-truncated app/src/main/kotlin/A.kt
jq '.total_count = 150' "$case_dir/jobs.101" >"$case_dir/jobs.tmp" && mv "$case_dir/jobs.tmp" "$case_dir/jobs.101"
decide push refs/heads/main
check "the run has more jobs than one page lists: every job saves" writes true true true
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
gate_re='^\$\{\{ github\.ref != '"'"'refs/heads/main'"'"' \|\| needs\.changes\.outputs\.gradle_cache_write_([A-Za-z0-9_-]+) (==|!=) '"'"'([a-z]*)'"'"' \}\}$'
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
gated="" ungated="" wrong="" sense=""
while read -r _ file job ro dis needs; do
  ro="${ro//$'\001'/ }"
  if [ "$dis" = true ] || [ "$ro" = true ]; then continue; fi
  if [[ "$ro" =~ $gate_re ]]; then
    if [ "${BASH_REMATCH[1]}" = "$job" ] && [ "$file" = android-ci.yml ] && [ "$needs" = 1 ]; then
      gated="$gated $job"
    else
      wrong="$wrong $file:$job(gate ${BASH_REMATCH[1]})"
    fi
    # Read-only only on an explicit 'false': an empty output (the step was
    # skipped or died before emit) must save, as the script promises.
    if [ "${BASH_REMATCH[2]} '${BASH_REMATCH[3]}'" != "== 'false'" ]; then
      sense="$sense $job(${BASH_REMATCH[2]} '${BASH_REMATCH[3]}')"
    fi
  else
    ungated="$ungated $file:$job"
  fi
done < <(grep '^step ' <<<"$steps")
gated="${gated# }"
check "all workflows: $parsed setup-gradle steps parsed, $grepped found by grep" eval '[ "$parsed" -ge 1 ] && [ "$parsed" = "$grepped" ]'
check "all workflows: no setup-gradle step saves on every main push${ungated:+ (ungated:$ungated)}" eval '[ -z "$ungated" ]'
check "all workflows: each gate names its own job, in android-ci.yml, which needs changes${wrong:+ (wrong:$wrong)}" eval '[ -z "$wrong" ]'
check "android-ci.yml: each gate is read-only only on == 'false', so an empty answer saves${sense:+ (not:$sense)}" \
  eval '[ -n "$gated" ] && [ -z "$sense" ]'
check "android-ci.yml: the gated jobs are lint, test and build (got: ${gated:-none})" \
  eval '[ "$(tr " " "\n" <<<"$gated" | sort | tr "\n" " ")" = "build lint test " ]'

# The script judges each gated job's runs by its check name: it must be the
# job's name: in android-ci.yml.
names_ok() {
  local j want got
  for j in $gated; do
    want=$(awk -v j="  $j:" '$0 == j { inj = 1; next } inj && /^  [^ ]/ { inj = 0 } inj && /^    name: / { sub(/^    name: /, ""); print; exit }' "$workflow")
    got=$(sed -nE "s/^ +$j\) echo \"([^\"]+)\" ;;\$/\1/p" "$script")
    [ -n "$want" ] && [ "$want" = "$got" ] || { echo "  $j: android-ci.yml '$want', script '$got'"; return 1; }
  done
}
check "the script's check name of each gated job is its name in android-ci.yml" names_ok

# `changes` publishes each gate from the script's step, runs the script for
# exactly the gated jobs, and may read the caches, the comparison and the runs.
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
check "android-ci.yml: the step is id gradle-write, reads the lineage, and \`changes\` has contents: read and actions: read" eval '
  grep -qE "^      - id: gradle-write\$" <<<"$changes_job" &&
  grep -qF "PREFIX: \${{ steps.gradle-cache.outputs.gradle_cache_prefix }}" <<<"$changes_job" &&
  grep -qF "BEFORE: \${{ github.event.before }}" <<<"$changes_job" &&
  grep -qE "^      contents: read\$" <<<"$changes_job" &&
  grep -qE "^      actions: read\$" <<<"$changes_job"'

if [ "$fail" -ne 0 ]; then
  echo "::error::gradle-cache-write self-test failed (above)"
  exit 1
fi
echo "gradle-cache-write self-test: all cases pass"
