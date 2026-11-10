#!/usr/bin/env bash
# Test for require-tag-on-main.sh using throwaway local repos.
# Run: bash .github/scripts/require-tag-on-main-test.sh   (exit 0 = all passed)
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="$here/require-tag-on-main.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null
export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@example.invalid GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@example.invalid
fail=0

git init -q --bare -b main "$work/origin.git"
git clone -q "$work/origin.git" "$work/clone" 2>/dev/null
cd "$work/clone"
git checkout -q -b main
git -c commit.gpgsign=false commit -q --allow-empty -m "main 1"
git -c commit.gpgsign=false commit -q --allow-empty -m "main 2"
on_main="$(git rev-parse HEAD)"
git push -q origin main
git checkout -q -b side
git -c commit.gpgsign=false commit -q --allow-empty -m "side only"
off_main="$(git rev-parse HEAD)"
git push -q origin side
git -c commit.gpgsign=false tag -a -m "release: draft" v9.9.9-ann "$on_main"

expect() { # name want(0|1) cmd...
  local name="$1" want="$2" rc=0
  shift 2
  "$@" >"$work/out" 2>&1 || rc=$?
  if [ "$rc" -ne "$want" ]; then
    echo "FAIL: $name (exit $rc, wanted $want)"; sed 's/^/    /' "$work/out"; fail=1
  else
    echo "ok:   $name"
  fi
}

expect "commit on main passes" 0 env GITHUB_SHA="$on_main" bash "$script"
expect "side-branch commit fails" 1 env GITHUB_SHA="$off_main" bash "$script"
expect "annotated tag object on main passes" 0 bash "$script" v9.9.9-ann
expect "argument beats GITHUB_SHA" 1 env GITHUB_SHA="$on_main" bash "$script" "$off_main"
expect "no commit given fails" 1 env -u GITHUB_SHA bash "$script"
expect "unknown commit fails" 1 env GITHUB_SHA=0000000000000000000000000000000000000000 bash "$script"

# origin/main missing and unfetchable: must fail closed.
git remote set-url origin "$work/does-not-exist.git"
git update-ref -d refs/remotes/origin/main
expect "origin/main unavailable fails" 1 env GITHUB_SHA="$on_main" bash "$script"

# A shallow clone of a tag checkout (fetch-depth 1 style) still resolves main.
git remote set-url origin "$work/origin.git"
git clone -q --depth 1 --branch main "file://$work/origin.git" "$work/shallow" 2>/dev/null
cd "$work/shallow"
expect "shallow checkout of main passes" 0 env GITHUB_SHA="$on_main" bash "$script"

[ "$fail" -eq 0 ] && echo "all passed"
exit "$fail"
