#!/usr/bin/env bash
#
# Self-test for .github/scripts/classify-changes.sh, run by the required
# "Lint Check" job: a classifier that wrongly says "docs only" skips required
# checks, and a skipped required check counts as passing.
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
C="$HERE/classify-changes.sh"
fail=0

check() { # name, got, want
    if [ "$2" = "$3" ]; then
        echo "  ok: $1"
    else
        echo "::error::classify-changes: $1: got '$2', want '$3'"
        fail=1
    fi
}
list() { # name, newline-separated paths, want-android, want-jvm
    check "$1" "$(printf '%s' "$2" | bash "$C" | tr '\n' ' ')" "android=$3 jvm=$4 "
}

list "docs, markdown and LICENSE only"         $'docs/a.md\nREADME.md\nLICENSE\n'           false false
list "markdown in a code directory"            $'native/README.md\n'                        false false
list "empty list fails open"                   ''                                           true  true
list "iOS only: JVM tests still read Swift"    $'iosApp/iosApp/Views/HomeView.swift\n'      false true
list "setup-ios.sh only"                       $'setup-ios.sh\n'                            false true
list "Kotlin"                                  $'app/src/main/java/app/birdo/vpn/A.kt\n'    true  true
list "a workflow"                              $'.github/workflows/ios.yml\n'               true  true
list "fdroid metadata (read by JVM tests)"     $'fdroid/metadata/app.birdo.vpn.yml\n'       true  true
list "both sides of a rename into docs/"       $'app/src/main/java/Foo.kt\ndocs/Foo.md\n'  true  true

# The case that matters, through the same --git-range path android-ci.yml runs:
# a real `git mv` of Kotlin into docs/ must NOT classify as docs-only.
t="$(mktemp -d)"
trap 'rm -rf "$t"' EXIT
git -C "$t" init -q
git -C "$t" config user.email "ci@example.invalid"
git -C "$t" config user.name "classify-changes test"
git -C "$t" config diff.renames true
git -C "$t" config core.autocrlf false
mkdir -p "$t/app/src" "$t/docs"
printf 'class Foo\n' > "$t/app/src/Foo.kt"
git -C "$t" add -A
git -C "$t" commit -qm base
git -C "$t" mv app/src/Foo.kt docs/Foo.md
git -C "$t" commit -qm "move into docs"
check "git mv app/src/Foo.kt docs/Foo.md (via --git-range)" \
    "$(cd "$t" && bash "$C" --git-range HEAD^1 HEAD | tr '\n' ' ')" "android=true jvm=true "
# Evidence that the flag is what saves it: rename detection alone lists one path.
renamed="$(git -C "$t" diff --name-only HEAD^1 HEAD)"
echo "  (with rename detection git lists only: $renamed)"

printf 'more\n' >> "$t/docs/Foo.md"
git -C "$t" commit -qam "docs edit"
check "docs-only commit (via --git-range)" \
    "$(cd "$t" && bash "$C" --git-range HEAD^1 HEAD | tr '\n' ' ')" "android=false jvm=false "

if [ "$fail" -ne 0 ]; then
    echo "::error::classify-changes-test FAILED"
    exit 1
fi
echo "classify-changes-test: all cases pass"
