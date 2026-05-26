#!/usr/bin/env bash
# Cross-compiles the rosenpass-jni Rust crate for all Android ABIs and
# copies the resulting .so files into app/src/main/jniLibs/<abi>/.
#
# Requires:
#   - Rust >= 1.75 (rustup)
#   - cargo-ndk: cargo install cargo-ndk
#   - Android NDK r26+ via $ANDROID_NDK_HOME
#   - Rust Android targets:
#       rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
#
# Usage: native/build.sh [release|debug]      (default: release)

set -euo pipefail

PROFILE="${1:-release}"
case "$PROFILE" in
  release|debug) ;;
  *) echo "ERROR: profile must be 'release' or 'debug', got '$PROFILE'" >&2; exit 1 ;;
esac

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &> /dev/null && pwd)"
ROOT="$(dirname "$SCRIPT_DIR")"
CRATE_DIR="$ROOT/native/rosenpass-jni"
JNI_LIBS_DIR="$ROOT/app/src/main/jniLibs"

echo ">>> rosenpass-jni native build ($PROFILE)"

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  if [[ -d "$HOME/Android/Sdk/ndk" ]]; then
    LATEST=$(ls -1 "$HOME/Android/Sdk/ndk" | sort -V | tail -n1 || true)
    if [[ -n "$LATEST" ]]; then
      export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/$LATEST"
      echo "    auto-detected ANDROID_NDK_HOME=$ANDROID_NDK_HOME"
    fi
  fi
fi
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  echo "ERROR: ANDROID_NDK_HOME is not set and no NDK found under \$HOME/Android/Sdk/ndk" >&2
  exit 1
fi
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"

cd "$CRATE_DIR"

PROFILE_FLAG=""
[[ "$PROFILE" == "release" ]] && PROFILE_FLAG="--release"

echo ">>> cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 build $PROFILE_FLAG"
cargo ndk \
  -t arm64-v8a \
  -t armeabi-v7a \
  -t x86_64 \
  -o "$JNI_LIBS_DIR" \
  build $PROFILE_FLAG

echo ">>> built .so files:"
find "$JNI_LIBS_DIR" -name "librosenpass_jni.so" -exec ls -lh {} \;
