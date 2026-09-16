#!/usr/bin/env bash
# Cross-compiles the rosenpass-jni Rust crate for all Android ABIs and
# copies the resulting .so files into app/src/main/jniLibs/<abi>/.
#
# Requires:
#   - Rust >= 1.75 (rustup)
#   - cargo-ndk: cargo install cargo-ndk
#   - Android NDK r26+ via $ANDROID_NDK_HOME
#   - Rust Android targets:
#       rustup target add aarch64-linux-android armv7-linux-androideabi \
#                         x86_64-linux-android i686-linux-android
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

# All four live Android ABIs. cargo-ndk names its targets exactly as the
# jniLibs subdirectories are named, so the output lands where AGP expects it.
echo ">>> cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -t x86 build $PROFILE_FLAG"
cargo ndk \
  -t arm64-v8a \
  -t armeabi-v7a \
  -t x86_64 \
  -t x86 \
  -o "$JNI_LIBS_DIR" \
  build $PROFILE_FLAG

echo ">>> built .so files:"
find "$JNI_LIBS_DIR" -name "librosenpass_jni.so" -exec ls -lh {} \;

# ── ISA-baseline gate on what was just built ────────────────────────────────
#
# The same script CI runs on the packaged APK/AAB, run here on the jniLibs
# output so a Keccak/SHA-NI backend reaching an optional extension (the 1.4.25
# SIGILL) fails on the developer's machine, not two pushes later. It needs a
# disassembler that knows every Android ELF machine: the NDK's llvm-objdump
# (found through ANDROID_NDK_HOME, which this script already requires) or
# `rustup component add llvm-tools`. The script discovers both.
#
# ROSENPASS_ISA_GATE_REQUIRED=1 (set by android.yml) turns "no disassembler"
# into a hard failure; locally it is a loud warning, because a laptop without
# the tool should still be able to build -- but never silently.
GATE="$ROOT/scripts/check_no_sha3_ext.sh"
if [[ -f "$GATE" ]]; then
  echo ">>> ISA-baseline gate: $GATE $JNI_LIBS_DIR"
  gate_rc=0
  bash "$GATE" "$JNI_LIBS_DIR" || gate_rc=$?
  if [[ "$gate_rc" -ne 0 ]]; then
    if [[ "${ROSENPASS_ISA_GATE_REQUIRED:-0}" == "1" ]]; then
      echo "ERROR: ISA-baseline gate failed (exit $gate_rc) and ROSENPASS_ISA_GATE_REQUIRED=1" >&2
      exit "$gate_rc"
    fi
    echo "" >&2
    echo "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!" >&2
    echo "!!! WARNING: scripts/check_no_sha3_ext.sh did NOT pass (exit $gate_rc)." >&2
    echo "!!! If the message above says no disassembler was found, install one" >&2
    echo "!!! (rustup component add llvm-tools) -- CI will run this gate and fail." >&2
    echo "!!! If it names an instruction, the .so you just built WILL SIGILL on" >&2
    echo "!!! devices without that CPU feature. Do not ship it." >&2
    echo "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!" >&2
  fi
else
  echo "ERROR: $GATE is missing -- the ISA-baseline gate cannot run" >&2
  exit 1
fi
