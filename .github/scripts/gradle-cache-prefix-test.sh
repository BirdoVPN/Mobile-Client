#!/usr/bin/env bash
# Self-test for .github/scripts/gradle-cache-prefix.sh (MR-2536), run by Lint
# Check, and for its wiring into every workflow that runs setup-gradle.
# Builds throwaway git repositories with fixed committer dates; no network.
#
#   push            the pushed commit's committer month, in UTC
#   other timezone  2026-11-30T20:30-05:00 is 2026-12 in UTC, not 2026-11
#   pull request    the base tip's month (HEAD^1), not the merge commit's
#   unreadable      no HEAD^1 (depth 1) or no repository: this UTC month,
#                   a warning, exit 0
#   wiring          every setup-gradle step in every workflow (*.yml and
#                   *.yaml) restores the month's lineage
#                   (GRADLE_BUILD_ACTION_CACHE_KEY_PREFIX) or caches nothing
#                   (cache-disabled: true); `changes` publishes the output, and
#                   computes it before `classify`, so a failed classify cannot
#                   leave its fail-open jobs an empty prefix
#   wiring refuses  (MR-2557) a step the parser cannot read (4-space steps), an
#                   unwired *.yaml workflow, and a gradle-cache step with an
#                   `if:` that can skip it; a correctly wired *.yaml passes
set -euo pipefail

here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
script="$here/gradle-cache-prefix.sh"
workflows="$here/../workflows"
workflow="$workflows/android-ci.yml"
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
  # A POSIX TZ string, not America/New_York: without zoneinfo (Git for
  # Windows) a zone name silently means UTC, and the script's own TZ=UTC
  # would go untested.
  out=$(cd "$1" && TZ=EST+5 GITHUB_OUTPUT="$work/output" bash "$script" "$2" 2>&1) || rc=$?
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

# -- wiring: every workflow that caches Gradle ------------------------------------
# A setup-gradle step without the month's prefix restores (and, if it writes,
# grows) the unprefixed lineage, which no job saves any more: it runs cold
# once GitHub evicts that. So each setup-gradle step, in every workflow, is
#   job-wired   its job needs `changes` and sets, at job level,
#               GRADLE_BUILD_ACTION_CACHE_KEY_PREFIX from needs.changes
#               (android-ci.yml)
#   step-wired  it sets GRADLE_BUILD_ACTION_CACHE_KEY_PREFIX in its own env:
#               from steps.gradle-cache, and an earlier step of the same job
#               has id gradle-cache, runs this script and has no `if:` that
#               could skip it (MR-2557 N2; `!cancelled()`/`always()` are
#               fine) (baseline-profile.yml)
#   uncached    cache-disabled: true (gradle-verification-metadata.yml)
# The parser reads the layout these workflows use: jobs at 2 spaces, steps
# as `      - ` at 6. An independent count of every
# `uses: gradle/actions/setup-gradle@` line (any indentation, quoted or not)
# must match the steps it parsed, so a step it cannot read (a 4-space step
# list, say) fails the check instead of passing unseen (MR-2557 N1).
wiring_of() { # <workflows dir>: "unwired <file>:<job>" lines, "counts ...", "grepped <n>"
  local f
  local -a files=()
  for f in "$1"/*.yml "$1"/*.yaml; do [ -f "$f" ] && files+=("$f"); done
  if [ "${#files[@]}" -eq 0 ]; then echo "counts 0 0 0"; echo "grepped 0"; return; fi
  awk '
  function flush_step() {
    if (in_step && step_gradle) {
      n++; g_disabled[n] = step_disabled; g_wire[n] = step_wire; g_cache[n] = cache_seen
    }
    if (in_step && step_cache_id && step_runs_script && !step_cond) cache_seen = 1
    in_step = 0; step_gradle = 0; step_disabled = 0; step_wire = 0
    step_cache_id = 0; step_runs_script = 0; step_cond = 0
  }
  function flush_job(   i) {
    flush_step()
    for (i = 1; i <= n; i++) {
      total++
      if (g_disabled[i]) disabled++
      else if ((job_wire && job_needs) || (g_wire[i] && g_cache[i])) wired++
      else print "unwired " file ":" job
    }
    n = 0; job = ""; job_wire = 0; job_needs = 0; cache_seen = 0
  }
  FNR == 1 { flush_job(); in_jobs = 0; file = FILENAME; sub(/.*\//, "", file) }
  /^[[:space:]]*#/ { next }
  /^jobs:[[:space:]]*$/ { flush_job(); in_jobs = 1; next }
  /^[^[:space:]]/ { flush_job(); in_jobs = 0; next }
  !in_jobs { next }
  /^  [A-Za-z0-9_-]+:[[:space:]]*$/ { flush_job(); job = $1; sub(/:$/, "", job); next }
  /^    [A-Za-z_-]+:/ { flush_step() }
  /^    needs:.*changes/ { job_needs = 1 }
  index($0, "      GRADLE_BUILD_ACTION_CACHE_KEY_PREFIX: ${{ needs.changes.outputs.gradle_cache_prefix }}") == 1 { job_wire = 1 }
  /^      - / { flush_step(); in_step = 1 }
  !in_step { next }
  /uses: gradle\/actions\/setup-gradle@/ { step_gradle = 1 }
  /^          cache-disabled:[[:space:]]*true[[:space:]]*$/ { step_disabled = 1 }
  index($0, "          GRADLE_BUILD_ACTION_CACHE_KEY_PREFIX: ${{ steps.gradle-cache.outputs.gradle_cache_prefix }}") == 1 { step_wire = 1 }
  /^(      - |        )id: gradle-cache[[:space:]]*$/ { step_cache_id = 1 }
  /^(      - |        )if:/ { if ($0 !~ /!cancelled\(\)|always\(\)/) step_cond = 1 }
  index($0, "bash .github/scripts/gradle-cache-prefix.sh") { step_runs_script = 1 }
  END { flush_job(); printf "counts %d %d %d\n", total, wired, disabled }' "${files[@]}"
  echo "grepped $(grep -h -v '^[[:space:]]*#' "${files[@]}" |
    grep -cE "uses:[[:space:]]*[\"']?gradle/actions/setup-gradle@" || true)"
}
wiring_msg=""
wiring_ok() { # <workflows dir>: 0 when every setup-gradle step is wired or uncached; sets wiring_msg
  local wiring unwired steps="" wired="" uncached="" grepped
  wiring=$(wiring_of "$1")
  unwired=$(sed -n 's/^unwired //p' <<<"$wiring" | tr '\n' ' ' | sed 's/ $//')
  read -r _ steps wired uncached < <(grep '^counts ' <<<"$wiring") || true
  grepped=$(sed -n 's/^grepped //p' <<<"$wiring")
  wiring_msg="${steps:-?} setup-gradle steps, ${wired:-?} restore the month's lineage, ${uncached:-?} cache nothing${unwired:+ (missing the lineage: $unwired)}"
  if [ "${grepped:-x}" != "${steps:-y}" ]; then
    wiring_msg="$wiring_msg; but ${grepped:-?} lines use setup-gradle: a step the check cannot read (steps go at 6 spaces, \`      - \`, under a 2-space job)"
    return 1
  fi
  [ "$steps" -ge 1 ] && [ -z "$unwired" ] && [ "$wired" -ge 1 ]
}
wiring_rc=0
wiring_ok "$workflows" || wiring_rc=$?
check "all workflows: $wiring_msg" eval '[ "$wiring_rc" -eq 0 ]'

# The check against workflows it must refuse (MR-2557): each case is this
# repository's workflows plus one more.
synthetic() { # <case> <file name> <content>: a workflows dir; prints its path
  local d="$work/wf-$1"
  mkdir -p "$d"
  cp "$workflows"/*.y*ml "$d"/
  printf '%s\n' "$3" >"$d/$2"
  printf '%s\n' "$d"
}
refused() { # <dir>: the wiring check fails on it
  local rc=0
  wiring_ok "$1" || rc=$?
  [ "$rc" -ne 0 ]
}
accepted() { wiring_ok "$1"; }
step_wired_job() { # <indent of the step list> <if: line or empty>: a step-wired job's workflow
  local i="$1"
  printf '%s\n' "name: synthetic" "on: workflow_dispatch" "jobs:" "  gen:" \
    "    runs-on: ubuntu-24.04" "    steps:" \
    "$i- uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1" \
    "$i- id: gradle-cache"
  [ -z "$2" ] || printf '%s\n' "$i  $2"
  printf '%s\n' "$i  run: bash .github/scripts/gradle-cache-prefix.sh \"\$EVENT_NAME\"" \
    "$i- name: Set up Gradle" \
    "$i  uses: gradle/actions/setup-gradle@8da46b539cd8fe6ab27ca75128919a18d757604c" \
    "$i  env:" \
    "$i    GRADLE_BUILD_ACTION_CACHE_KEY_PREFIX: \${{ steps.gradle-cache.outputs.gradle_cache_prefix }}"
}
unwired_job() { # <indent of the step list>: setup-gradle with no prefix at all
  printf '%s\n' "name: synthetic" "on: workflow_dispatch" "jobs:" "  gen:" \
    "    runs-on: ubuntu-24.04" "    steps:" \
    "$1- uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1" \
    "$1- uses: gradle/actions/setup-gradle@8da46b539cd8fe6ab27ca75128919a18d757604c"
}
check "wiring refuses: a new workflow whose steps sit at 4 spaces (unwired)" \
  refused "$(synthetic n1-4space new.yml "$(unwired_job '    ')")"
check "wiring refuses: a new unwired *.yaml workflow" \
  refused "$(synthetic n1-yaml new.yaml "$(unwired_job '      ')")"
check "wiring refuses: a quoted \`uses: \"gradle/actions/setup-gradle@...\"\` it cannot parse" \
  refused "$(synthetic n1-quoted new.yml "$(unwired_job '      ' | sed 's|uses: gradle/\(.*\)$|uses: "gradle/\1"|')")"
check "wiring refuses: a step-wired job whose gradle-cache step has \`if: false\`" \
  refused "$(synthetic n2-if new.yml "$(step_wired_job '      ' 'if: false')")"
check "wiring accepts: a correctly step-wired *.yaml workflow (control)" \
  accepted "$(synthetic ok-yaml new.yaml "$(step_wired_job '      ' '')")"
check "wiring accepts: a step-wired job whose gradle-cache step is \`if: \${{ !cancelled() }}\` (control)" \
  accepted "$(synthetic ok-if-fail-open new.yml "$(step_wired_job '      ' 'if: ${{ !cancelled() }}')")"
check "android-ci.yml: \`changes\` publishes gradle_cache_prefix from this script" eval '
  grep -qF "gradle_cache_prefix: \${{ steps.gradle-cache.outputs.gradle_cache_prefix }}" "$workflow" &&
  grep -qF "bash .github/scripts/gradle-cache-prefix.sh" "$workflow"'

# The jobs that consume it run `if: !cancelled()`, so they run even when a
# step of `changes` failed. The lineage step must therefore not sit behind
# one that can fail (classify): only checkout may precede it, unless it is
# itself `if: !cancelled()` / always().
changes_steps=$(awk '
  /^[[:space:]]*#/ { next }
  /^  [A-Za-z0-9_-]+:[[:space:]]*$/ { if (s != "") print s; s = ""; inc = ($1 == "changes:"); next }
  !inc { next }
  /^    [A-Za-z_-]+:/ { if (s != "") print s; s = ""; next }
  /^      - / { if (s != "") print s; s = "step" }
  s == "" { next }
  /uses: actions\/checkout@/ { s = s " checkout" }
  /^(      - |        )id: gradle-cache[[:space:]]*$/ { s = s " gradle-cache" }
  /^(      - |        )if:/ { s = s (/!cancelled\(\)|always\(\)/ ? " fail-open" : " conditional") }
  END { if (s != "") print s }' "$workflow")
lineage_step_unconditional() {
  local line before=""
  while IFS= read -r line; do
    case " $line " in
      *" gradle-cache "*)
        case " $line " in
          *" fail-open "*) return 0 ;;
          *" conditional "*) return 1 ;;
        esac
        [ -z "$before" ] && return 0
        return 1
        ;;
      *" checkout "*) ;;
      *) before="$before x" ;;
    esac
  done <<<"$changes_steps"
  return 1
}
check "android-ci.yml: \`changes\` names the lineage before any step that can fail (only checkout precedes it)" \
  lineage_step_unconditional

if [ "$fail" -ne 0 ]; then
  echo "::error::gradle-cache-prefix self-test failed (above)"
  exit 1
fi
echo "gradle-cache-prefix self-test: all cases pass"
