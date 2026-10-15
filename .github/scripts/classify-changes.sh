#!/usr/bin/env bash
#
# Decide which pull-request jobs a change needs, from its changed paths.
# Prints four lines for $GITHUB_OUTPUT:
#   android=<true|false>   Android build, lint, CodeQL java-kotlin, native gates
#   jvm=<true|false>       JVM unit tests
#   apple=<true|false>     apple-ci.yml: iOS simulator tests, macOS build
#   swift=<true|false>     codeql-swift.yml: Analyze (swift)
#
# Usage:
#   classify-changes.sh --git-range <from> <to>   diff two commits (what CI runs)
#   classify-changes.sh < paths.txt               classify a list, one path per line
#
# FAILS OPEN. Anything this cannot classify -- a failed diff, an empty list --
# prints true for every line: a broken classifier may cost minutes, never
# coverage.
#
# Only docs/, *.md and LICENSE are inert for the JVM tests, because those tests
# read far more than Kotlin: Swift sources, fdroid/, store-assets/,
# distribution/, contract/ and the workflows themselves. iosApp/ (and
# setup-ios.sh) is additionally inert for the Android build, lint and CodeQL.
#
# apple and swift are the other way round: ALLOWLISTS, the paths that were the
# two workflows' `paths:` filters until those moved here (MR-2060), so the
# Apple jobs can be required checks without blocking every other pull request.
# This script is on both lists, so a change to it proves itself on the jobs it
# can skip.
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
apple=false
swift=false
n=0
while IFS= read -r f; do
    [ -n "$f" ] || continue
    n=$((n + 1))

    # apple-ci.yml. A case `*` also matches `/`, so `iosApp/*` is the whole
    # tree; the root-level Gradle files are matched only at the root.
    case "$f" in
        iosApp/*|shared/*|native/birdo-pq-ios/*|contract/*|gradle/*|\
        scripts/build-birdo-pq-xcframework.sh|scripts/check_xctest_suites_ran.sh|\
        gradle.properties|version.properties|rust-toolchain.toml|\
        .github/workflows/apple-ci.yml|.github/actions/apple-toolchain/*|\
        .github/scripts/pick-simulator.py|.github/scripts/classify-changes.sh)
            apple=true ;;
        */*) ;;
        *.gradle.kts) apple=true ;;
    esac

    # codeql-swift.yml: Swift sources at any depth under iosApp/, and the
    # scan's own configuration.
    case "$f" in
        iosApp/*.swift|iosApp/project.yml|iosApp/.go-version|\
        .github/workflows/codeql-swift.yml|.github/codeql/codeql-config-swift.yml|\
        .github/actions/apple-toolchain/*|.github/scripts/classify-changes.sh)
            swift=true ;;
    esac

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
    apple=true
    swift=true
fi
echo "android=$android"
echo "jvm=$jvm"
echo "apple=$apple"
echo "swift=$swift"
