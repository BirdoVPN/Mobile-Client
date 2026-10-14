#!/usr/bin/env bash
#
# provision_xray_engines.sh — put a libxray.so into app/src/main/jniLibs/<abi>/
# for EVERY Android ABI the app ships.
#
# WHY THIS SCRIPT EXISTS
#
# libxray.so is not a shared library. It is the Xray-core `xray` executable,
# renamed so that AGP packages it and the platform extracts it into
# applicationInfo.nativeLibraryDir, which is one of the few directories an app
# may exec from on Android 10+ (see XrayManager.kt and the
# `useLegacyPackaging = true` in app/build.gradle.kts). It therefore has to be
# provisioned per-ABI just like any other native artifact.
#
# XTLS publishes exactly TWO Android binaries per release — verified against the
# GitHub releases API for the pinned tag, which carries 66 assets of which only
# Xray-android-arm64-v8a.zip and Xray-android-amd64.zip are Android:
#
#     ABI            upstream asset
#     arm64-v8a      Xray-android-arm64-v8a.zip
#     x86_64         Xray-android-amd64.zip
#     armeabi-v7a    -- none --
#     x86            -- none --
#
# Xray-linux-arm32-v7a.zip and Xray-linux-32.zip are NOT substitutes: those are
# GOOS=linux builds. Every Android binary above requests /system/bin/linker64 and
# links liblog.so/libdl.so/libc.so from bionic; the linux ones do not.
#
# So the 32-bit ABIs are built from source here, from the SAME pinned tag, with
# the same flags as XTLS's own release workflow. The Go toolchain refuses to
# internally link GOOS=android for anything but arm64 ("requires external (cgo)
# linking"), so the 32-bit builds go through the NDK's clang — which is what
# makes them bionic-linked rather than glibc-linked.
#
# SUPPLY CHAIN
#
#   * downloaded ABIs are pinned by SHA-256 of the release zip;
#   * built ABIs are pinned by Xray-core commit SHA (asserted after clone, so a
#     retagged v26.2.6 fails the build instead of shipping), and every Go module
#     that goes into them is verified against the upstream repo's own go.sum by
#     the toolchain.
#
# Both pins live HERE and only here.
#
# CORRESPONDING SOURCE (owner decision L1, 2026-10-14)
#
# libxray.so links GPL-3.0 and LGPL-3.0 Go modules, so every release carries
# the complete corresponding source of the program as an asset:
# scripts/xray_corresponding_source.py builds
# xray-core-<XRAY_VERSION>-corresponding-source.tar.gz from `git archive` of
# XRAY_COMMIT (whose tree must be XRAY_SOURCE_TREE), the proxy.golang.org zip
# of every module in scripts/xray-go-modules.txt (which must match what these
# binaries link) and the build record this script writes. A bump here
# therefore also needs: the new commit's tree id below, and that table
# re-reviewed (the script, run by PR CI and the release, says what moved).
#
# The build record ($WORK/build-record.txt) says how each engine was made: the
# upstream asset it came from, or the Go toolchain, NDK revision and exact
# `go build` command it was built with. The source asset's README carries it.
#
# Usage: scripts/provision_xray_engines.sh [abi ...]     (default: all four)
# Env:   ANDROID_NDK_HOME  required for the 32-bit builds.

set -euo pipefail

# ── Pins ────────────────────────────────────────────────────────────────────
XRAY_VERSION="v26.2.6"
XRAY_COMMIT="12ee51e4bb1d02ece4ef4b7114efa2bcdc130995"
XRAY_ANDROID_ARM64_SHA256="952ccbb2275eace52e644a349358de293278eefc32b139d795b067edcb6a2f07"
XRAY_ANDROID_AMD64_SHA256="f11f5bb71ebb2fe4127670404fbd0c21d93f62e453870fda65ad6effe7a6131e"
# Keep the SHA-256 pins' names long: gitleaks' jfrog-identity-token rule reads
# "xray", up to 20 characters, "=" and 64 hex characters as a JFrog Xray token.
# The git tree of XRAY_COMMIT: content-addressed, so the mirrored source is
# pinned by what it contains, not by how GitHub happens to compress it.
XRAY_SOURCE_TREE="d64b24307450e1cbfadf8c7a2d0e7f840c83b5c6"
# The Go toolchain the two 32-bit engines are built with. Exact, so the build
# is reproducible and the source asset can name it; must equal the go-version
# in .github/actions/android-native-libs/action.yml (the build refuses another).
XRAY_GO_VERSION="go1.25.14"
# What XTLS built the two upstream release binaries with (their build info).
XRAY_UPSTREAM_GO_VERSION="go1.25.7"
# The Go standard library and runtime are linked into every engine, so each
# toolchain's source distribution (https://go.dev/dl/<version>.src.tar.gz) is
# mirrored in the source asset too, pinned to the SHA-256 go.dev publishes
# (https://go.dev/dl/?mode=json&include=all). Bump with the versions above.
XRAY_GO_VERSION_SOURCE_SHA256="9e83f44f5fc297378861b4e16cc6aa114be8add7993fb3ceb2c512380aa4d582"
XRAY_UPSTREAM_GO_SOURCE_SHA256="178f2832820274b43e177d32f06a3ebb0129e427dd20a5e4c88df2c1763cf10a"

ALL_ABIS=(arm64-v8a armeabi-v7a x86_64 x86)
ABIS=("$@")
[ "${#ABIS[@]}" -eq 0 ] && ABIS=("${ALL_ABIS[@]}")

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &> /dev/null && pwd)"
ROOT="$(dirname "$SCRIPT_DIR")"
JNI_LIBS="$ROOT/app/src/main/jniLibs"
WORK="${XRAY_BUILD_DIR:-${RUNNER_TEMP:-${TMPDIR:-/tmp}}/xray-provision}"
mkdir -p "$WORK"
RECORD="$WORK/build-record.txt"
: > "$RECORD"

echo ">>> provisioning Xray $XRAY_VERSION for: ${ABIS[*]}"

# ── Downloaded ABIs ─────────────────────────────────────────────────────────
fetch_release() {
    local abi="$1" asset="$2" want="$3"
    local zip="$WORK/$asset" dir="$WORK/unz-$abi"
    echo "  [$abi] downloading $asset"
    curl -fsSL -o "$zip" \
        "https://github.com/XTLS/Xray-core/releases/download/${XRAY_VERSION}/${asset}"
    echo "${want}  ${zip}" | sha256sum -c -
    rm -rf "$dir"; mkdir -p "$dir"
    unzip -q -o "$zip" -d "$dir"
    local bin
    bin="$(find "$dir" -type f -name xray | head -n1)"
    [ -n "$bin" ] || { echo "::error::no 'xray' binary inside $asset" >&2; exit 1; }
    install -D -m 755 "$bin" "$JNI_LIBS/$abi/libxray.so"
    printf '%s\tfetched\t%s\t%s\n' "$abi" "$asset" "$want" >> "$RECORD"
}

# ── Built ABIs ──────────────────────────────────────────────────────────────
SRC="$WORK/Xray-core"
ensure_source() {
    [ -d "$SRC/.git" ] && return 0
    echo "  cloning Xray-core $XRAY_VERSION"
    rm -rf "$SRC"
    git -c advice.detachedHead=false clone --quiet --depth 1 --branch "$XRAY_VERSION" \
        https://github.com/XTLS/Xray-core.git "$SRC"
    local got
    got="$(git -C "$SRC" rev-parse HEAD)"
    # A tag is mutable. Without this, a retagged v26.2.6 would silently change
    # what we ship on two ABIs, which is exactly the guarantee the SHA-256 pin
    # gives the other two.
    if [ "$got" != "$XRAY_COMMIT" ]; then
        echo "::error::Xray-core $XRAY_VERSION resolves to $got, expected $XRAY_COMMIT — the tag moved. Do not build this." >&2
        exit 1
    fi
    echo "  commit $got verified"
}

ndk_cc() {
    # cargo-ndk and the Gradle build already require ANDROID_NDK_HOME, so this
    # adds no new prerequisite.
    local triple="$1"
    if [ -z "${ANDROID_NDK_HOME:-}" ]; then
        echo "::error::ANDROID_NDK_HOME is not set — needed to build the 32-bit Xray engines (Go cannot internally link GOOS=android outside arm64)" >&2
        exit 1
    fi
    local bin
    bin="$(find "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" -maxdepth 2 -type d -name bin | head -n1)"
    [ -n "$bin" ] || { echo "::error::no llvm prebuilt toolchain under $ANDROID_NDK_HOME" >&2; exit 1; }
    # minSdk is 29 (app/build.gradle.kts). Keep these in step.
    local cc="$bin/${triple}29-clang"
    [ -x "$cc" ] || cc="$cc.cmd"
    [ -x "$cc" ] || { echo "::error::NDK clang not found: ${bin}/${triple}29-clang" >&2; exit 1; }
    printf '%s' "$cc"
}

build_from_source() {
    local abi="$1" goarch="$2" goarm="$3" triple="$4"
    ensure_source
    local gover; gover="$(go env GOVERSION)"
    if [ "$gover" != "$XRAY_GO_VERSION" ]; then
        echo "::error::Go is $gover, the 32-bit Xray engines are pinned to $XRAY_GO_VERSION (the source asset names it)" >&2
        exit 1
    fi
    local cc; cc="$(ndk_cc "$triple")"
    local ndk_rev
    ndk_rev="$(sed -n 's/^Pkg\.Revision *= *//p' "$ANDROID_NDK_HOME/source.properties" 2>/dev/null | tr -d '\r' | head -n1)"
    # One array for the build AND the record, so what the source asset says
    # is what ran.
    local -a flags=(-trimpath -buildvcs=false "-gcflags=all=-l=4"
        "-ldflags=-X github.com/xtls/xray-core/core.build=${XRAY_VERSION} -s -w -buildid=")
    echo "  [$abi] building GOOS=android GOARCH=$goarch GOARM=$goarm with $gover, NDK ${ndk_rev:-unknown}"
    ( cd "$SRC" && \
      GOOS=android GOARCH="$goarch" GOARM="$goarm" CGO_ENABLED=1 CC="$cc" \
      go build -o "$WORK/xray-$abi" "${flags[@]}" ./main )
    install -D -m 755 "$WORK/xray-$abi" "$JNI_LIBS/$abi/libxray.so"
    local rel_cc="${cc#"$ANDROID_NDK_HOME"}"
    local command
    command="GOOS=android GOARCH=$goarch GOARM=$goarm CGO_ENABLED=1 CC=\$ANDROID_NDK_HOME/${rel_cc#/} go build -o libxray.so$(printf ' %q' "${flags[@]}") ./main"
    printf '%s\tbuilt\t%s\t%s\t%s\n' "$abi" "$gover" "${ndk_rev:-unknown}" "$command" >> "$RECORD"
}

for abi in "${ABIS[@]}"; do
    case "$abi" in
        arm64-v8a)   fetch_release arm64-v8a Xray-android-arm64-v8a.zip "$XRAY_ANDROID_ARM64_SHA256" ;;
        x86_64)      fetch_release x86_64    Xray-android-amd64.zip     "$XRAY_ANDROID_AMD64_SHA256" ;;
        armeabi-v7a) build_from_source armeabi-v7a arm 7 armv7a-linux-androideabi ;;
        x86)         build_from_source x86         386 "" i686-linux-android ;;
        *) echo "::error::unknown ABI '$abi' (known: ${ALL_ABIS[*]})" >&2; exit 1 ;;
    esac
done

# ── Post-conditions ─────────────────────────────────────────────────────────
# An engine for the wrong machine installs fine and then fails at exec time with
# nothing but a "no such file or directory" from the loader, so assert the ELF
# machine of what we just wrote rather than trusting the build matrix.
READELF=""
if [ -n "${ANDROID_NDK_HOME:-}" ]; then
    READELF="$(find "$ANDROID_NDK_HOME" -name 'llvm-readelf*' -type f 2>/dev/null | head -1 || true)"
fi
[ -n "$READELF" ] || for c in llvm-readelf readelf; do
    command -v "$c" >/dev/null 2>&1 && { READELF="$c"; break; }
done

declare -A WANT_MACHINE=(
    [arm64-v8a]="AArch64"
    [armeabi-v7a]="ARM"
    [x86_64]="X86-64"
    [x86]="Intel 80386"
)

echo ">>> provisioned engines:"
for abi in "${ABIS[@]}"; do
    so="$JNI_LIBS/$abi/libxray.so"
    [ -f "$so" ] || { echo "::error::$so was not produced" >&2; exit 1; }
    if [ -n "$READELF" ]; then
        machine="$("$READELF" -h "$so" 2>/dev/null | sed -n 's/^ *Machine: *//p' | head -1)"
        case "$machine" in
            *"${WANT_MACHINE[$abi]}"*) ;;
            *) echo "::error::$so is ELF machine '$machine', expected ${WANT_MACHINE[$abi]}" >&2; exit 1 ;;
        esac
        echo "    $abi  $(stat -c %s "$so" 2>/dev/null || wc -c < "$so") bytes  [$machine]"
    else
        echo "    $abi  $(stat -c %s "$so" 2>/dev/null || wc -c < "$so") bytes  [machine unchecked: no readelf]"
    fi
done
