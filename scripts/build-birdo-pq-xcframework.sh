#!/usr/bin/env bash
# Build BirdoPQNative.xcframework from birdo-pq-ios for the three iOS slices
# the BirdoVPN app ships against:
#
#   1. iOS device      (aarch64-apple-ios)
#   2. iOS simulator   (aarch64-apple-ios-sim)
#   3. iOS simulator   (x86_64-apple-ios)            ← Intel-Mac CI runners
#
# Output: BirdoPQNative.xcframework at the path passed as $1, default
# `iosApp/Vendor/BirdoPQNative.xcframework`.
#
# REQUIREMENTS:
#   - macOS host with Xcode + cargo + the three rustup targets installed.
#   - Run from the repo root, OR cd into native/birdo-pq-ios first.
#
# This script is a no-op on non-macOS hosts because Xcode + xcodebuild are
# both required by `xcodebuild -create-xcframework`. Cargo cross-compiles
# fine from Linux/Windows but the final XCFramework wrapping does not.

set -euo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "ERROR: BirdoPQNative XCFramework can only be built on macOS." >&2
    echo "       (xcodebuild -create-xcframework is Apple-only.)" >&2
    exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CRATE_DIR="${SCRIPT_DIR}/../native/birdo-pq-ios"
OUT_DIR="${1:-${SCRIPT_DIR}/../iosApp/Vendor/BirdoPQNative.xcframework}"

if [[ ! -d "${CRATE_DIR}" ]]; then
    echo "ERROR: cannot find crate at ${CRATE_DIR}" >&2
    exit 1
fi

# Make sure the rustup targets are present. (Idempotent.)
rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios

cd "${CRATE_DIR}"

# Build the static archive for every target.
cargo build --release --target aarch64-apple-ios       --lib
cargo build --release --target aarch64-apple-ios-sim   --lib
cargo build --release --target x86_64-apple-ios        --lib

# Lipo the simulator slices into a single fat archive (xcframework wants
# one archive per platform-variant).
SIM_FAT_DIR="$(mktemp -d)"
SIM_FAT="${SIM_FAT_DIR}/libbirdo_pq_ios.a"
lipo -create \
    "target/aarch64-apple-ios-sim/release/libbirdo_pq_ios.a" \
    "target/x86_64-apple-ios/release/libbirdo_pq_ios.a" \
    -output "${SIM_FAT}"

# Stage headers per slice.
HEADERS_DIR="$(mktemp -d)"
mkdir -p "${HEADERS_DIR}/Headers"
cp "include/birdo_pq_ios.h" "${HEADERS_DIR}/Headers/"
cp "include/module.modulemap" "${HEADERS_DIR}/Headers/"

# Wipe any previous output so xcodebuild won't refuse to overwrite.
rm -rf "${OUT_DIR}"

xcodebuild -create-xcframework \
    -library "target/aarch64-apple-ios/release/libbirdo_pq_ios.a" \
    -headers "${HEADERS_DIR}/Headers" \
    -library "${SIM_FAT}" \
    -headers "${HEADERS_DIR}/Headers" \
    -output "${OUT_DIR}"

echo "✅ Built: ${OUT_DIR}"
echo "   Slices: arm64-iOS, arm64+x86_64-iOS-sim"
echo "   Add the .xcframework to the iosApp Xcode target ('Embed & Sign')."
