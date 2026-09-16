#!/usr/bin/env bash
#
# Every fingerprint in .gitleaksignore must be pinned to a commit that is
# ALREADY ON main.
#
# WHY
#
# A gitleaks fingerprint is `<commit>:<file>:<rule>:<line>`. This repository
# merges SQUASH ONLY -- `gh api repos/BirdoVPN/Mobile-Client` reports
# allow_merge_commit=false, allow_rebase_merge=false, allow_squash_merge=true --
# so every commit on a PR branch is discarded and replaced by one NEW commit
# with a NEW sha when the PR lands.
#
# A fingerprint pinned to a branch commit therefore matches on the PR (green)
# and stops matching the instant the PR is squashed. .github/workflows/
# secret-scan.yml runs on push to main with fetch-depth: 0, so the finding
# re-fires and the job goes red ON MAIN -- after review, after approval, on the
# branch nobody is watching, and attached to a commit whose author has moved on.
#
# This has already happened here: .gitleaksignore carried the same
# BirdoPQManager.swift:generic-api-key:52 finding twice, under two different
# shas, once per commit that touched the file.
#
# WHAT TO DO INSTEAD
#
# For anything that will keep matching -- a committed test fixture, a constant
# that merely looks key-shaped -- use a path or regex allowlist in
# .gitleaks.toml. Those are sha-independent: they survive the squash, they
# survive a rebase, and they survive the line moving.
#
# .gitleaksignore is then only for findings on history that is already immutable.
#
# Usage: scripts/check_gitleaksignore.sh        (from anywhere in the repo)
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

IGNORE_FILE=".gitleaksignore"
if [ ! -f "$IGNORE_FILE" ]; then
    echo "check_gitleaksignore: no $IGNORE_FILE, nothing to check"
    exit 0
fi

# The base to measure against. In CI on a PR the checkout is the merge commit
# and refs/remotes/origin/main may not exist, so fetch it explicitly. Locally
# origin/main is normally already there.
BASE=""
if git rev-parse --verify --quiet refs/remotes/origin/main >/dev/null; then
    BASE="refs/remotes/origin/main"
elif git fetch --quiet origin main 2>/dev/null; then
    BASE="FETCH_HEAD"
elif git rev-parse --verify --quiet refs/heads/main >/dev/null; then
    BASE="refs/heads/main"
fi

if [ -z "$BASE" ]; then
    echo "::error::check_gitleaksignore: cannot resolve main to check ancestry against. Refusing to pass -- a check that cannot look is not a pass." >&2
    exit 1
fi

FAILED=0
COUNT=0
LINENO_=0
while IFS= read -r line || [ -n "$line" ]; do
    LINENO_=$((LINENO_ + 1))
    # Strip a trailing CR so a CRLF checkout does not produce a sha with an
    # invisible character glued to it.
    line="${line%$'\r'}"
    case "$line" in
        ''|'#'*) continue ;;
    esac

    sha="${line%%:*}"
    if [ "$sha" = "$line" ]; then
        echo "::error::check_gitleaksignore: $IGNORE_FILE:$LINENO_ is not a <commit>:<file>:<rule>:<line> fingerprint: $line" >&2
        FAILED=1
        continue
    fi

    COUNT=$((COUNT + 1))

    if ! git cat-file -e "${sha}^{commit}" 2>/dev/null; then
        echo "::error::check_gitleaksignore: $IGNORE_FILE:$LINENO_ names commit $sha, which does not exist in this checkout. If the scan runs with fetch-depth: 0 and the commit is still unknown, it was rewritten -- the fingerprint is dead and the finding will re-fire." >&2
        FAILED=1
        continue
    fi

    if ! git merge-base --is-ancestor "$sha" "$BASE" 2>/dev/null; then
        echo "::error::check_gitleaksignore: $IGNORE_FILE:$LINENO_ is pinned to $sha, which is NOT an ancestor of main. This repo squash-merges, so that commit will be replaced by a new sha and this fingerprint will stop matching AT MERGE -- Secret Scan then goes red on main, not on this PR. Use a path or regex allowlist in .gitleaks.toml instead." >&2
        FAILED=1
    fi
done < "$IGNORE_FILE"

if [ "$FAILED" -ne 0 ]; then
    exit 1
fi
echo "check_gitleaksignore: $COUNT fingerprint(s), all pinned to commits already on main ($BASE)"
