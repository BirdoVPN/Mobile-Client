#!/usr/bin/env bash
# Build libwg-go.a (the wireguard-go tunnel) from the vendored WireGuardKit.
#
# WHY THIS EXISTS
# SwiftPM emits `-lwg-go` for WireGuardKitGo but cannot produce the archive —
# upstream's README says so outright. Upstream's prescription is an Xcode
# "External Build System" (legacy) target running this Makefile. That machinery
# failed here with Xcode's opaque
#   "Internal inconsistency error: never received target ended message"
# and swallows make's output, which makes it undebuggable — and iOS only ever
# builds in CI (the dev box is Windows). So we invoke the same Makefile
# directly, passing the environment variables Xcode would have passed, and get
# a readable log.
#
# Usage:  build-wireguard-go.sh <iphoneos|iphonesimulator> <output-dir>
#
# Output: <output-dir>/libwg-go.a  (+ wireguard-go-version.h)
# Feed <output-dir> to xcodebuild as LIBWG_GO_DIR; project.yml puts it on
# PacketTunnel's LIBRARY_SEARCH_PATHS.

set -euo pipefail

PLATFORM="${1:?usage: build-wireguard-go.sh <iphoneos|iphonesimulator> <output-dir>}"
OUT_DIR="${2:?usage: build-wireguard-go.sh <iphoneos|iphonesimulator> <output-dir>}"

case "$PLATFORM" in
  iphoneos)
    # Device: the only slice we ship.
    ARCHS="arm64"
    MIN_FLAG="miphoneos-version-min"
    MIN_ENV="IPHONEOS_DEPLOYMENT_TARGET"
    ;;
  iphonesimulator)
    # Simulator: needed because the unit-test job builds the app, which embeds
    # the extension, which links -lwg-go. arm64 only — the vendored Makefile
    # gates GOOS_iphonesimulator on an arm64 host (CI is Apple Silicon).
    ARCHS="arm64"
    MIN_FLAG="mios-simulator-version-min"
    MIN_ENV="IPHONEOS_DEPLOYMENT_TARGET"
    ;;
  *)
    echo "ERROR: platform must be iphoneos or iphonesimulator (got '$PLATFORM')" >&2
    exit 2
    ;;
esac

command -v go >/dev/null 2>&1 || {
  echo "ERROR: go not on PATH. macOS runners cache Go but do not export it" >&2
  echo "       (actions/runner-images#13675) — use actions/setup-go." >&2
  exit 1
}

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GO_DIR="$REPO_ROOT/iosApp/Vendor/wireguard-apple/Sources/WireGuardKitGo"
[ -f "$GO_DIR/Makefile" ] || { echo "ERROR: no Makefile at $GO_DIR" >&2; exit 1; }

mkdir -p "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"

SDKROOT="$(xcrun --sdk "$PLATFORM" --show-sdk-path)"
DEPLOYMENT_TARGET="${IPHONEOS_DEPLOYMENT_TARGET:-17.0}"

echo "==> wireguard-go: platform=$PLATFORM archs=$ARCHS min=$DEPLOYMENT_TARGET"
echo "    go:     $(go version)"
echo "    sdk:    $SDKROOT"
echo "    out:    $OUT_DIR"

# The Makefile reads these exactly as Xcode sets them. PLATFORM_NAME selects
# GOOS; CFLAGS_PREFIX is assembled from DEPLOYMENT_TARGET_CLANG_* + SDKROOT.
make -C "$GO_DIR" build \
  ARCHS="$ARCHS" \
  PLATFORM_NAME="$PLATFORM" \
  SDKROOT="$SDKROOT" \
  CONFIGURATION_BUILD_DIR="$OUT_DIR" \
  DESTDIR="$OUT_DIR" \
  DEPLOYMENT_TARGET_CLANG_FLAG_NAME="$MIN_FLAG" \
  DEPLOYMENT_TARGET_CLANG_ENV_NAME="$MIN_ENV" \
  "$MIN_ENV=$DEPLOYMENT_TARGET"

LIB="$OUT_DIR/libwg-go.a"
[ -f "$LIB" ] || { echo "ERROR: make succeeded but $LIB is missing" >&2; exit 1; }

echo "==> built $LIB"
ls -la "$LIB"
# Prove the slice matches the platform we asked for — a device archive silently
# linked into a simulator build fails late and confusingly.
lipo -info "$LIB" || true
