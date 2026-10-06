#!/usr/bin/env bash
#
# Decide which android-ci.yml jobs a pull request needs, from its changed paths.
# Prints two lines for $GITHUB_OUTPUT:
#   android=<true|false>   Android build, lint, CodeQL java-kotlin, native gates
#   jvm=<true|false>       JVM unit tests
#
# Usage:
#   classify-changes.sh --git-range <from> <to>   diff two commits (what CI runs)
#   classify-changes.sh < paths.txt               classify a list, one path per line
#
# FAILS OPEN. Anything this cannot classify -- a failed diff, an empty list --
# prints true for both: a broken classifier may cost minutes, never coverage.
#
# Only docs/, *.md and LICENSE are inert for the JVM tests, because those tests
# read far more than Kotlin: Swift sources, fdroid/, store-assets/,
# distribution/, contract/ and the workflows themselves. iosApp/ (and
# setup-ios.sh) is additionally inert for the Android build, lint and CodeQL.
#
# RENAMES: the diff runs with --no-renames. With git's default rename
# detection, `git mv app/src/Foo.kt docs/Foo.md` lists only docs/Foo.md -- a
# "docs-only" change that deletes Kotlin, which would skip two required checks
# and merge green. Without rename detection both paths are listed.
# .github/scripts/classify-changes-test.sh asserts exactly that case.
set -euo pipefail

if [ "${1:-}" = "--git-range" ]; then
    if [ "$#" -ne 3 ]; then
        echo "usage: classify-changes.sh --git-range <from> <to>" >&2
        exit 2
    fi
    if ! files=$(git diff --no-renames --name-only "$2" "$3"); then
        echo "::warning::classify-changes: could not diff $2..$3; running every job" >&2
        files=""
    fi
else
    files=$(cat)
fi

android=false
jvm=false
n=0
while IFS= read -r f; do
    [ -n "$f" ] || continue
    n=$((n + 1))
    case "$f" in
        docs/*|*.md|LICENSE) continue ;;
    esac
    jvm=true
    case "$f" in
        iosApp/*|setup-ios.sh) ;;
        *) android=true ;;
    esac
done <<< "$files"

if [ "$n" -eq 0 ]; then
    android=true
    jvm=true
fi
echo "android=$android"
echo "jvm=$jvm"
