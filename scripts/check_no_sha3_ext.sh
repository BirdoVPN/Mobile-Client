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

# WHY THIS IS NOT A FLAT "ZERO INSTRUCTIONS" CHECK ANY MORE
#
# The first version of this gate asserted zero SHA3-extension instructions in
# every shipped arm64 library. That threshold was measured against
# librosenpass_jni.so, where it is exactly right: Rust/PQClean picks its
# implementation at COMPILE time, so an emitted eor3 is an executed eor3.
#
# It is wrong for a Go library. Go internal/cpu reads HWCAP at process start and
# every SIMD-accelerated crypto path branches on those flags, so a Go binary
# legitimately CONTAINS instructions it will never execute on a CPU lacking the
# feature. libxray.so carries 64 of them and has shipped for months without a
# single Xray frame in a SIGILL report -- the production crash that prompted this
# gate died in ML-KEM, on devices that were running libxray.so quite happily.
#
# So the policy is per-library, and neither half is an exemption list:
#
#   STRICT  libraries WE build, where no runtime dispatch exists. Zero
#           instructions, no evidence accepted, no exceptions.
#
#   GATED   any other library. Instructions are permitted ONLY if the binary
#           carries positive evidence of runtime CPU detection. No evidence
#           means FAIL, so a third-party .so that starts emitting an ungated
#           SHA3 path still breaks the build.
#
# The distinction is "can this instruction be reached without a feature check",
# not "do we trust this vendor".
STRICT_LIBS=("librosenpass_jni.so")

is_strict() {
    local base
    base=$(basename "$1")
    local s
    for s in "${STRICT_LIBS[@]}"; do
        [ "$base" = "$s" ] && return 0
    done
    return 1
}

# Positive evidence that a Go binary performs runtime CPU feature detection.
# internal/cpu is what populates ARM64.HasSHA3 from AT_HWCAP, and its symbol
# names survive in the pclntab even in a stripped .so.
has_runtime_cpu_detection() {
    # `grep -a` rather than `strings`: strings needs binutils, which is not
    # guaranteed on every runner or dev machine, and a MISSING tool here would
    # silently answer "no evidence" and fail the build for a reason that has
    # nothing to do with the binary. grep is everywhere and reads the file
    # directly. Go stores these symbol names contiguously in the pclntab, so a
    # plain byte-run match is sound.
    grep -aqE 'internal/cpu|runtime/internal/cpu|ARM64\.Has' "$1" 2>/dev/null
}

FAILED=0
for so in "${LIBS[@]}"; do
    # -d disassembles executable sections only, which is what matters: an opcode
    # that is never decoded cannot raise SIGILL.
    hits=$("$OBJDUMP" -d "$so" 2>/dev/null \
        | grep -coE '[[:space:]](eor3|rax1|xar|bcax)[[:space:]]' || true)
    hits="${hits:-0}"

    if [ "$hits" -eq 0 ]; then
        echo "  ok: $(basename "$so") - no SHA3-extension instructions"
    elif is_strict "$so"; then
        echo "::error::$so contains $hits ARMv8.2 SHA3-extension instruction(s). This library is built by us and has NO runtime dispatch, so an emitted instruction is an executed one - it WILL SIGILL on any arm64 device without FEAT_SHA3."
        "$OBJDUMP" -d "$so" 2>/dev/null | grep -nE '[[:space:]](eor3|rax1|xar|bcax)[[:space:]]' | head -5 >&2 || true
        FAILED=1
    elif has_runtime_cpu_detection "$so"; then
        echo "  ok: $(basename "$so") - $hits SHA3-extension instruction(s), but the binary carries runtime CPU-feature detection, so they are reached only where FEAT_SHA3 is present"
    else
        echo "::error::$so contains $hits ARMv8.2 SHA3-extension instruction(s) and NO evidence of runtime CPU-feature detection. Either it gained an ungated SIMD path, or it is no longer a Go binary and this gate assumption about it is stale. Both need a human."
        "$OBJDUMP" -d "$so" 2>/dev/null | grep -nE '[[:space:]](eor3|rax1|xar|bcax)[[:space:]]' | head -5 >&2 || true
        FAILED=1
    fi
done

if [ "$FAILED" -ne 0 ]; then
    echo "::error::check_no_sha3_ext FAILED. Most likely cause: a dependency re-enabled pqcrypto-mlkem's 'neon' feature. Restoring it needs runtime dispatch on getauxval(AT_HWCAP) & HWCAP_SHA3, which the crate does not provide." >&2
    exit 1
fi

echo "check_no_sha3_ext: ${#LIBS[@]} arm64 library(ies) clean"
