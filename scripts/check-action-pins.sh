#!/usr/bin/env bash
#
# Assert that every SHA-pinned GitHub Action's version COMMENT tells the truth.
#
# WHY THIS EXISTS. Every action in this repo is pinned to a 40-hex commit SHA,
# which is correct — a floating tag on a job holding `contents: write` in a PUBLIC
# repo whose releases are sideload APKs is not acceptable. But a SHA is opaque to
# a human, so the trailing `# vX` comment is the ONLY readable handle on what
# actually executes.
#
# On 2026-09-10 two of twelve pins were lying:
#
#   actions/setup-java@dd06d9cb...          said "# v5"      was actually v6.0.0
#   softprops/action-gh-release@efb35369... said "# v2.4.0"  was actually v3.0.3
#
# setup-java's comment was wrong in FOURTEEN places. action-gh-release's had
# survived four consecutive Dependabot bumps — Dependabot rewrites the SHA and
# leaves the comment untouched, so this never self-corrects.
#
# That matters more here than it looks. A bad action ref fails as
# `startup_failure`: zero jobs, no logs, and the YAML still parses. A pin review
# is the control that catches it, and a pin review reads the comments.
#
# Resolves each SHA against the upstream tags API and fails on any mismatch.
# Read-only: no checkout of the upstream repo, no network writes.
#
# Usage:  scripts/check-action-pins.sh          (needs gh with a token)

set -uo pipefail

fails=0
checked=0

# Collect distinct (repo, sha, comment) triples across every workflow.
mapfile -t pins < <(
    grep -rhoE 'uses:[[:space:]]*[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+@[0-9a-f]{40}[[:space:]]*#[[:space:]]*[^[:space:]]+' \
        .github/workflows 2>/dev/null \
    | sed -E 's/uses:[[:space:]]*//; s/[[:space:]]*#[[:space:]]*/ /' \
    | sort -u
)

if [ "${#pins[@]}" -eq 0 ]; then
    echo "FAIL: found no SHA-pinned actions at all — has the pinning convention changed?"
    exit 1
fi

for entry in "${pins[@]}"; do
    ref=${entry%% *}
    comment=${entry##* }
    repo=${ref%@*}
    sha=${ref#*@}
    checked=$((checked + 1))

    tags=$(gh api "repos/${repo}/tags" --paginate \
             --jq ".[]|select(.commit.sha==\"${sha}\")|.name" 2>/dev/null | tr '\n' ' ')

    if [ -z "$tags" ]; then
        # Not every pin has to be a tagged release, but it must be a real commit.
        if gh api "repos/${repo}/commits/${sha}" --jq .sha >/dev/null 2>&1; then
            echo "  warn  ${repo}@${sha:0:8} (# ${comment}) is a real commit but matches no tag"
        else
            echo "  FAIL  ${repo}@${sha:0:8} is NOT a commit in that repository"
            fails=$((fails + 1))
        fi
        continue
    fi

    want=${comment#v}
    hit=0
    for t in $tags; do
        bare=${t#v}
        # "# v7" legitimately describes v7.0.1; "# v5" does not describe v6.0.0.
        if [ "$bare" = "$want" ] || [ "${bare#"$want".}" != "$bare" ]; then hit=1; break; fi
    done

    if [ "$hit" -eq 1 ]; then
        echo "  ok    ${repo} # ${comment}"
    else
        echo "  FAIL  ${repo} says '# ${comment}' but that SHA is ${tags% }"
        fails=$((fails + 1))
    fi
done

echo
echo "checked ${checked} pin(s)"
if [ "$fails" -eq 0 ]; then
    echo "ALL ACTION PIN COMMENTS ARE TRUTHFUL"
    exit 0
fi
echo "${fails} pin comment(s) do NOT match the pinned SHA"
echo "Fix the COMMENT to match the SHA. Do NOT change the SHA to match the comment,"
echo "and do NOT replace the pin with a floating tag."
exit 1
