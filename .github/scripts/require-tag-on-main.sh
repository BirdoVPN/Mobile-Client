#!/usr/bin/env bash
# Release gate (register A-H09 / MR-2264 part b, A-M72): a release tag may only
# build, sign and publish when the tagged commit is already on main.
#
# Usage: require-tag-on-main.sh [commit]        (default: $GITHUB_SHA)
#
# Runs as the FIRST job of a release workflow, before anything signs or uploads.
# Needs a checkout with full history (actions/checkout fetch-depth: 0), which
# also brings refs/remotes/origin/main. If origin/main is still missing it is
# fetched here (the repo is public, so no credentials are needed). Any doubt
# (no commit, no origin/main, not an ancestor) fails: a gate that cannot tell
# must not pass.
#
# For a tag push GITHUB_SHA is the commit the tag points at (an annotated tag
# is peeled by GitHub), and `^{commit}` peels a tag object for local use.
#
# This stops a tag pushed on an unmerged or side-branch commit by mistake. It
# is not a control against someone who can push to main; that is the
# repository ruleset (tag protection) and the `release` environment reviewer,
# which are owner settings.
set -euo pipefail

target="${1:-${GITHUB_SHA:-}}"
if [ -z "$target" ]; then
  echo "::error::require-tag-on-main: no commit given and GITHUB_SHA is unset; refusing."
  exit 1
fi
if ! commit="$(git rev-parse --verify -q "${target}^{commit}")"; then
  echo "::error::require-tag-on-main: $target is not a commit in this checkout; refusing."
  exit 1
fi

if ! git rev-parse --verify -q refs/remotes/origin/main >/dev/null; then
  git fetch --no-tags origin main:refs/remotes/origin/main >/dev/null 2>&1 || true
fi
if ! git rev-parse --verify -q refs/remotes/origin/main >/dev/null; then
  echo "::error::require-tag-on-main: origin/main is not available; cannot prove $commit is on main. Refusing to release."
  exit 1
fi

# A shallow checkout can answer "not an ancestor" for a commit that is on main.
if [ "$(git rev-parse --is-shallow-repository)" = "true" ]; then
  git fetch --no-tags --unshallow origin main:refs/remotes/origin/main >/dev/null 2>&1 || true
fi

if ! git merge-base --is-ancestor "$commit" refs/remotes/origin/main; then
  echo "::error::$commit (${GITHUB_REF:-the release ref}) is not an ancestor of origin/main. Only a commit that has been merged to main can be released. Merge it, then tag the merged commit. Nothing was signed or uploaded."
  exit 1
fi
echo "$commit is on origin/main ($(git rev-parse --short refs/remotes/origin/main)); release may proceed."
