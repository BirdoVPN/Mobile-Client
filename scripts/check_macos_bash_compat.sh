#!/usr/bin/env bash
#
# Assert that every script a macOS workflow runs is bash 3.2-clean.
#
# WHY THIS EXISTS. Apple has shipped bash 3.2 (2007) as /bin/bash since 2019
# and never will ship a newer one (GPLv3). On GitHub's macOS runners `bash` on
# PATH resolves to that 3.2, while on ubuntu-latest it is bash 5. So a script
# that is exercised on every PR by android.yml (ubuntu) can still be broken on
# the ONE place it matters for iOS: ios.yml, which only runs on an android-v*
# tag push and workflow_dispatch.
#
# That is exactly what happened on android-v1.4.29 (2026-09-17):
#
#   ./scripts/check_pq_ios_wiring.sh: line 61: mapfile: command not found
#   ##[error]Process completed with exit code 127.
#
# `mapfile` is bash 4. The gate had passed on every PR since #409 added it and
# failed on the first tag — the iOS build for a public release, with the .ipa
# attach and the TestFlight upload behind it. A 4.x-only builtin in a script
# that macOS runs is a release-day failure by construction, and nothing read
# the scripts for it. This does.
#
# What it checks: every `scripts/*.sh` referenced from a workflow that has a
# macos-* runner (ios.yml, macos.yml today — found by reading the workflows,
# not a hard-coded list), for the bash 4+ constructs that 3.2 rejects at
# parse or run time:
#
#   mapfile / readarray                 (4.0) -> while IFS= read -r l; do arr+=("$l"); done
#   declare -A / local -A               (4.0) -> parallel indexed arrays or case
#   ${var,,} ${var^^} ${var,} ${var^}   (4.0) -> tr '[:upper:]' '[:lower:]'
#   |&                                  (4.0) -> 2>&1 |
#   ;;&  case fallthrough               (4.0) -> restructure the case
#   coproc                              (4.0)
#   ${var@Q} and other @ transforms     (4.4)
#   ${arr[-1]} negative subscripts      (4.3) -> ${arr[${#arr[@]}-1]}
#
# Comment lines are stripped first, so a header may DISCUSS these words.
#
# Usage:  scripts/check_macos_bash_compat.sh      (repo root, no network)

set -uo pipefail

fails=0
checked=0

# Workflows that run anything on macOS.
mac_workflows=$(grep -lE 'runs-on:[[:space:]]*(\[[^]]*)?["'"'"']?macos-' .github/workflows/*.yml 2>/dev/null || true)
if [ -z "$mac_workflows" ]; then
    echo "::error::check_macos_bash_compat: found no workflow with a macos-* runner. A check that finds nothing is not a pass." >&2
    exit 1
fi

# Every scripts/*.sh those workflows invoke (bash ./scripts/x.sh, scripts/x.sh, ./scripts/x.sh).
# shellcheck disable=SC2086  # $mac_workflows is a whitespace-separated file list
scripts=$(grep -hoE '(\./)?scripts/[A-Za-z0-9_./-]+\.sh' $mac_workflows | sed -E 's#^\./##' | sort -u)
if [ -z "$scripts" ]; then
    echo "::error::check_macos_bash_compat: the macOS workflows reference no scripts/*.sh — the grep is broken, not the tree." >&2
    exit 1
fi

# Parallel arrays: the construct's regex and its human name for the report.
patterns=(
    '(^|[^A-Za-z0-9_])(mapfile|readarray)([^A-Za-z0-9_]|$)'
    '(^|[^A-Za-z0-9_])(declare|local|typeset)[[:space:]]+-[a-zA-Z]*A'
    '\$\{[A-Za-z_][A-Za-z0-9_]*(\[[^]]*\])?(,,|\^\^|,|\^)\}'
    '\|&'
    ';;&'
    '(^|[^A-Za-z0-9_])coproc([^A-Za-z0-9_]|$)'
    '\$\{[A-Za-z_][A-Za-z0-9_]*@[A-Za-z]\}'
    '\$\{[A-Za-z_][A-Za-z0-9_]*\[-[0-9]+\]\}'
)
labels=(
    'mapfile/readarray (bash 4.0)'
    'an associative array, declare -A (bash 4.0)'
    'a case-modification expansion ${x,,} / ${x^^} (bash 4.0)'
    'the |& pipe (bash 4.0)'
    'case fallthrough ;;& (bash 4.0)'
    'coproc (bash 4.0)'
    'an @ parameter transform (bash 4.4)'
    'a negative array subscript (bash 4.3)'
)

for script in $scripts; do
    if [ ! -f "$script" ]; then
        echo "::error::check_macos_bash_compat: $script is invoked by a macOS workflow but does not exist" >&2
        fails=$((fails + 1))
        continue
    fi
    checked=$((checked + 1))
    # Strip comment lines (leading whitespace then #) so headers may name the constructs.
    code=$(grep -vE '^[[:space:]]*#' "$script" | tr -d '\r')
    i=0
    while [ "$i" -lt "${#patterns[@]}" ]; do
        pattern=${patterns[$i]}
        label=${labels[$i]}
        i=$((i + 1))
        hits=$(printf '%s\n' "$code" | grep -nE -- "$pattern" || true)
        if [ -n "$hits" ]; then
            echo "::error file=$script::check_macos_bash_compat: $script uses $label, which /bin/bash 3.2 on macOS runners does not have:" >&2
            printf '%s\n' "$hits" | sed 's/^/    /' >&2
            fails=$((fails + 1))
        fi
    done
done

if [ "$checked" -eq 0 ]; then
    echo "::error::check_macos_bash_compat: checked 0 scripts" >&2
    exit 1
fi
if [ "$fails" -ne 0 ]; then
    echo "check_macos_bash_compat: FAILED ($fails) across $checked script(s) — see above; the fix is to rewrite for bash 3.2, not to pin a newer bash on the runner" >&2
    exit 1
fi
echo "check_macos_bash_compat: $checked script(s) invoked from macOS workflows are bash 3.2-clean: $(echo "$scripts" | tr '\n' ' ')"
