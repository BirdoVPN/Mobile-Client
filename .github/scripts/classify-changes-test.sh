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
list() { # name, newline-separated paths, want-android, want-jvm, want-apple, want-swift
    check "$1" "$(printf '%s' "$2" | bash "$C" | tr '\n' ' ')" "android=$3 jvm=$4 apple=$5 swift=$6 "
}

#                                                                                           android jvm  apple swift
list "docs, markdown and LICENSE only"         $'docs/a.md\nREADME.md\nLICENSE\n'           false false false false
list "markdown in a code directory"            $'native/README.md\n'                        false false false false
list "empty list fails open"                   ''                                           true  true  true  true
list "iOS only: JVM tests still read Swift"    $'iosApp/iosApp/Views/HomeView.swift\n'      false true  true  true
list "setup-ios.sh only"                       $'setup-ios.sh\n'                            false true  false false
list "Kotlin"                                  $'app/src/main/java/app/birdo/vpn/A.kt\n'    true  true  false false
list "a workflow"                              $'.github/workflows/ios.yml\n'               true  true  false false
list "fdroid metadata (read by JVM tests)"     $'fdroid/metadata/app.birdo.vpn.yml\n'       true  true  false false
list "both sides of a rename into docs/"       $'app/src/main/java/Foo.kt\ndocs/Foo.md\n'  true  true  false false

# apple-ci.yml and codeql-swift.yml (MR-2060): the paths that were their
# workflow-level `paths:` filters. A skipped Apple job counts as passing, so
# every one of these has to say true.
list "the KMP shared module (unlinked, MR-1085)" $'shared/src/commonMain/kotlin/A.kt\n'     true  true  false false
list "root build.gradle.kts"                   $'build.gradle.kts\n'                        true  true  false false
list "the Gradle version catalog"              $'gradle/libs.versions.toml\n'               true  true  false false
list "version.properties"                      $'version.properties\n'                      true  true  true  false
list "BirdoPQ for iOS"                         $'native/birdo-pq-ios/src/lib.rs\n'          true  true  true  false
list "the vendored contract"                   $'contract/vpn-protocol.schema.json\n'       true  true  true  false
list "XcodeGen spec"                           $'iosApp/project.yml\n'                      false true  true  true
list "the Apple Go pin"                        $'iosApp/.go-version\n'                      false true  true  true
list "vendored Go, not Swift"                  $'iosApp/Vendor/wireguard-apple/Sources/WireGuardKitGo/go.mod\n' false true true false
list "markdown under iosApp/"                  $'iosApp/README.md\n'                        false false true  false
list "apple-ci.yml itself"                     $'.github/workflows/apple-ci.yml\n'          true  true  true  false
list "codeql-swift.yml itself"                 $'.github/workflows/codeql-swift.yml\n'      true  true  false true
list "the Apple toolchain action"              $'.github/actions/apple-toolchain/action.yml\n' true true true true
list "this classifier"                         $'.github/scripts/classify-changes.sh\n'     true  true  true  true
list "the suite assertion"                     $'scripts/check_xctest_suites_ran.sh\n'      true  true  true  false

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
    "$(cd "$t" && bash "$C" --git-range HEAD^1 HEAD | tr '\n' ' ')" "android=true jvm=true apple=false swift=false "
# Evidence that the flag is what saves it: rename detection alone lists one path.
renamed="$(git -C "$t" diff --name-only HEAD^1 HEAD)"
echo "  (with rename detection git lists only: $renamed)"

printf 'more\n' >> "$t/docs/Foo.md"
git -C "$t" commit -qam "docs edit"
check "docs-only commit (via --git-range)" \
    "$(cd "$t" && bash "$C" --git-range HEAD^1 HEAD | tr '\n' ' ')" "android=false jvm=false apple=false swift=false "

# The same hazard for the Apple jobs: Swift moved into docs/ deletes Swift, so
# both Apple workflows must still run.
mkdir -p "$t/iosApp/iosApp"
printf 'struct A {}\n' > "$t/iosApp/iosApp/A.swift"
git -C "$t" add -A
git -C "$t" commit -qm "add swift"
git -C "$t" mv iosApp/iosApp/A.swift docs/A.md
git -C "$t" commit -qm "move swift into docs"
check "git mv iosApp/iosApp/A.swift docs/A.md (via --git-range)" \
    "$(cd "$t" && bash "$C" --git-range HEAD^1 HEAD | tr '\n' ' ')" "android=false jvm=true apple=true swift=true "

if [ "$fail" -ne 0 ]; then
    echo "::error::classify-changes-test FAILED"
    exit 1
fi
echo "classify-changes-test: all cases pass"
