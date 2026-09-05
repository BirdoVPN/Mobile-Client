#!/usr/bin/env bash
#
# Assert no shipped arm64 native library contains ARMv8.2 SHA3-extension
# instructions (eor3 / rax1 / xar / bcax).
#
# WHY THIS GATE EXISTS
#
# pqcrypto-mlkem's default features include `neon`. With it on, build.rs compiles
# PQClean's AArch64 ML-KEM and flips the FFI bindings from
# PQCLEAN_MLKEM1024_CLEAN_* to PQCLEAN_MLKEM1024_AARCH64_*, which reaches
# pqclean/common/keccak2x/feat.S -- 64 SHA3-extension instructions, no `.arch`
# guard, and NO RUNTIME CPU DETECTION. The choice is compile-time only.
#
# FEAT_SHA3 is OPTIONAL in ARMv8.2-A. On an arm64 device without it the first
# `eor3` raises SIGILL and the process dies. That crash shipped in 1.4.25 and was
# reported from a Galaxy A70 (Snapdragon 675 / Kryo 460 -- ARMv8.2-A, no
# FEAT_SHA3). It fires inside nativeGenerateKeypair, so with PQ default-ON the
# user cannot connect at all.
#
# The fix is `default-features = false` in native/rosenpass-jni/Cargo.toml. This
# script exists so that re-enabling `neon` -- directly, or by a transitive
# dependency turning it back on -- fails the build instead of shipping a crash to
# every mid-range Android device.
#
# Verified on real aarch64 before this gate was written: with the fix, the LINKED
# library contains 0 SHA3-ext instructions, 0 PQCLEAN_*_AARCH64_* symbols and 0
# keccakx2 symbols (the linker drops the unreferenced feat.o). So zero is the
# correct threshold, not a hopeful one.
#
# Usage: scripts/check_no_sha3_ext.sh <dir-containing-abi-subdirs>
set -euo pipefail

LIB_ROOT="${1:?usage: check_no_sha3_ext.sh <lib-root>}"

if [ ! -d "$LIB_ROOT" ]; then
    echo "::error::check_no_sha3_ext: '$LIB_ROOT' is not a directory" >&2
    exit 1
fi

# Find a disassembler that understands aarch64. The NDK's llvm-objdump handles
# every target, so prefer it; fall back to a cross binutils objdump.
#
# A missing disassembler must FAIL, never skip. A gate that silently passes
# because its tool was absent is worse than no gate -- it reports green over an
# unchecked binary, which is the exact failure mode this repo keeps paying for.
OBJDUMP=""
if [ -n "${ANDROID_NDK_HOME:-}" ]; then
    cand=$(find "$ANDROID_NDK_HOME" -name 'llvm-objdump' -type f 2>/dev/null | head -1 || true)
    [ -n "$cand" ] && OBJDUMP="$cand"
fi
if [ -z "$OBJDUMP" ]; then
    for c in llvm-objdump aarch64-linux-gnu-objdump objdump; do
        if command -v "$c" >/dev/null 2>&1; then OBJDUMP="$c"; break; fi
    done
fi
if [ -z "$OBJDUMP" ]; then
    echo "::error::check_no_sha3_ext: no aarch64-capable objdump found (tried \$ANDROID_NDK_HOME, llvm-objdump, aarch64-linux-gnu-objdump, objdump)" >&2
    exit 1
fi
echo "check_no_sha3_ext: using $OBJDUMP"

# Only arm64 matters. The x86/x86_64 emulator ABIs cannot execute these opcodes,
# and armv7 predates the extension entirely.
mapfile -t LIBS < <(find "$LIB_ROOT" -type d -name 'arm64-v8a' -exec find {} -name '*.so' -type f \; 2>/dev/null | sort)

if [ "${#LIBS[@]}" -eq 0 ]; then
    echo "::error::check_no_sha3_ext: no arm64-v8a .so files under '$LIB_ROOT' — nothing was checked" >&2
    exit 1
fi

FAILED=0
for so in "${LIBS[@]}"; do
    # -d disassembles executable sections only, which is what matters: an opcode
    # that is never decoded cannot raise SIGILL.
    hits=$("$OBJDUMP" -d "$so" 2>/dev/null \
        | grep -coE '[[:space:]](eor3|rax1|xar|bcax)[[:space:]]' || true)
    hits="${hits:-0}"

    if [ "$hits" -ne 0 ]; then
        echo "::error::$so contains $hits ARMv8.2 SHA3-extension instruction(s) — this WILL SIGILL on any arm64 device without FEAT_SHA3"
        "$OBJDUMP" -d "$so" 2>/dev/null \
            | grep -nE '[[:space:]](eor3|rax1|xar|bcax)[[:space:]]' | head -5 >&2 || true
        FAILED=1
    else
        echo "  ok: $(basename "$so") — no SHA3-extension instructions"
    fi
done

if [ "$FAILED" -ne 0 ]; then
    echo "::error::check_no_sha3_ext FAILED. Most likely cause: a dependency re-enabled pqcrypto-mlkem's 'neon' feature. Restoring it needs runtime dispatch on getauxval(AT_HWCAP) & HWCAP_SHA3, which the crate does not provide." >&2
    exit 1
fi

echo "check_no_sha3_ext: ${#LIBS[@]} arm64 library(ies) clean"
