#!/usr/bin/env bash
#
# Assert that no shipped native library can execute a CPU instruction the
# device's ABI does not guarantee.
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
# WHY IT IS NO LONGER ARM64-ONLY
#
# The gate used to look at arm64-v8a and ignore every other directory. That was
# safe only while arm64-v8a and x86_64 were the shipped set AND nothing on the
# x86 side had an ungated wide-vector path. Both halves of that changed:
#
#   * armeabi-v7a and x86 are now shipped too, so an arm64-only walk silently
#     skips half the binaries in the bundle;
#   * the SAME crate carries an `avx2` default feature guarded by
#     `target_arch == "x86_64" && avx2_enabled`, and AVX2 is NOT in the Android
#     x86_64 ABI baseline (that is SSE4.2 + POPCNT). The only reason it is not a
#     second SIGILL is that pqcrypto-mlkem happens to wrap the AVX2 call in
#     `std::is_x86_feature_detected!("avx2")` while wrapping the AArch64 call in
#     `if true`. One line of upstream inconsistency is not a control.
#
# So the rule is now per-ABI, and an ABI directory with no rule is a FAILURE, not
# a skip. A gate that quietly passes over an unrecognised directory is how the
# next ABI ships unchecked.
#
# Usage: scripts/check_no_sha3_ext.sh <dir-containing-abi-subdirs>
set -euo pipefail

LIB_ROOT="${1:?usage: check_no_sha3_ext.sh <lib-root>}"

if [ ! -d "$LIB_ROOT" ]; then
    echo "::error::check_no_sha3_ext: '$LIB_ROOT' is not a directory" >&2
    exit 1
fi

# Find a disassembler that understands every Android target. The NDK's
# llvm-objdump does; fall back to whatever binutils is around.
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
    echo "::error::check_no_sha3_ext: no capable objdump found (tried \$ANDROID_NDK_HOME, llvm-objdump, aarch64-linux-gnu-objdump, objdump)" >&2
    exit 1
fi
echo "check_no_sha3_ext: using $OBJDUMP"

# ── Per-ABI rules ───────────────────────────────────────────────────────────
#
# For each shipped ABI: an extended regex matching instructions the ABI's
# guaranteed baseline does NOT include, or the empty string for "no forbidden
# instruction class, and here is why".
#
#   arm64-v8a    FEAT_SHA3 (eor3/rax1/xar/bcax) is OPTIONAL in ARMv8.2-A. This
#                is the one that actually shipped a crash.
#
#   x86_64       The Android x86_64 ABI guarantees SSE4.2 and POPCNT, nothing
#                wider. Any 256-/512-bit register operand means AVX/AVX2 or
#                AVX-512, none of which is guaranteed -- notably on older
#                emulator host CPUs and on hypervisors that mask AVX.
#
#   x86          The Android x86 ABI guarantees only up to SSE3, so the same
#                256-/512-bit test applies and is if anything more generous than
#                the ABI deserves.
#
#   armeabi-v7a  DELIBERATELY EMPTY, and this is a judgement, not an oversight.
#                The SHA3-extension opcodes have no A32/T32 encoding at all, so
#                the arm64 rule is not merely inapplicable, it is unrepresentable.
#                The analogous optional feature would be NEON (Advanced SIMD),
#                formally optional in ARMv7-A -- but the NDK has emitted it by
#                default for armeabi-v7a since r21, so its own clang puts NEON
#                into our C dependencies (measured on this ABI: 2 instructions in
#                librosenpass_jni.so, 4 in libxray.so). Every Android 10 armv7
#                device runs NEON code all day. A rule banning it would fail this
#                build over something no user's phone can trip on, and a gate
#                that cries wolf gets deleted. If a genuinely optional ARMv7
#                extension ever matters here, add it -- do not add NEON.
#
# An ABI directory that is not in this table fails the run.
abi_rule() {
    case "$1" in
        arm64-v8a)   printf '%s' '[[:space:]](eor3|rax1|xar|bcax)[[:space:]]' ;;
        x86_64|x86)  printf '%s' '%[yz]mm[0-9]+' ;;
        armeabi-v7a) printf '%s' '' ;;
        *)           return 1 ;;
    esac
}

abi_rule_name() {
    case "$1" in
        arm64-v8a)   printf '%s' 'ARMv8.2 SHA3-extension' ;;
        x86_64|x86)  printf '%s' 'AVX/AVX2/AVX-512 (256- or 512-bit vector)' ;;
        *)           printf '%s' 'extended-ISA' ;;
    esac
}

# WHY THIS IS NOT A FLAT "ZERO INSTRUCTIONS" CHECK
#
# The first version of this gate asserted zero SHA3-extension instructions in
# every shipped arm64 library. That threshold was measured against
# librosenpass_jni.so, where it is exactly right: Rust/PQClean picks its
# implementation at COMPILE time, so an emitted eor3 is an executed eor3.
#
# It is wrong for a Go library. Go internal/cpu reads HWCAP (or CPUID) at process
# start and every SIMD-accelerated path branches on those flags, so a Go binary
# legitimately CONTAINS instructions it will never execute on a CPU lacking the
# feature. libxray.so carries 64 SHA3-ext instructions on arm64 and ~21k AVX
# operands on x86_64, and has shipped for months without a single Xray frame in a
# SIGILL report -- the production crash that prompted this gate died in ML-KEM, on
# devices that were running libxray.so quite happily.
#
# So the policy is per-library, and neither half is an exemption list:
#
#   STRICT  libraries WE build, where no runtime dispatch exists. Zero
#           instructions, no evidence accepted, no exceptions.
#
#   GATED   any other library. Instructions are permitted ONLY if the binary
#           carries positive evidence of runtime CPU detection. No evidence
#           means FAIL, so a third-party .so that starts emitting an ungated
#           extended path still breaks the build.
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
# internal/cpu is what populates ARM64.HasSHA3 / X86.HasAVX2 from AT_HWCAP and
# CPUID, and its symbol names survive in the pclntab even in a stripped .so.
has_runtime_cpu_detection() {
    # `grep -a` rather than `strings`: strings needs binutils, which is not
    # guaranteed on every runner or dev machine, and a MISSING tool here would
    # silently answer "no evidence" and fail the build for a reason that has
    # nothing to do with the binary. grep is everywhere and reads the file
    # directly. Go stores these symbol names contiguously in the pclntab, so a
    # plain byte-run match is sound.
    grep -aqE 'internal/cpu|runtime/internal/cpu|ARM64\.Has|X86\.Has|ARM\.Has' "$1" 2>/dev/null
}

# ── Walk every ABI directory present ────────────────────────────────────────
mapfile -t ABI_DIRS < <(find "$LIB_ROOT" -mindepth 1 -maxdepth 1 -type d | sort)

if [ "${#ABI_DIRS[@]}" -eq 0 ]; then
    echo "::error::check_no_sha3_ext: no ABI directories under '$LIB_ROOT' — nothing was checked" >&2
    exit 1
fi

FAILED=0
CHECKED=0

for abi_dir in "${ABI_DIRS[@]}"; do
    abi=$(basename "$abi_dir")

    if ! rule=$(abi_rule "$abi"); then
        echo "::error::check_no_sha3_ext: no ISA rule for ABI '$abi'. A new ABI must be given a rule (or an explicit, reasoned empty one) in this script before it ships — an unrecognised directory is not a pass." >&2
        FAILED=1
        continue
    fi

    mapfile -t LIBS < <(find "$abi_dir" -name '*.so' -type f | sort)
    if [ "${#LIBS[@]}" -eq 0 ]; then
        echo "::error::check_no_sha3_ext: '$abi' directory contains no .so files" >&2
        FAILED=1
        continue
    fi

    if [ -z "$rule" ]; then
        echo "  -- $abi: no forbidden instruction class for this ABI (see the rule table); ${#LIBS[@]} library(ies) not disassembled"
        continue
    fi

    rule_name=$(abi_rule_name "$abi")
    for so in "${LIBS[@]}"; do
        # -d disassembles executable sections only, which is what matters: an
        # opcode that is never decoded cannot raise SIGILL.
        hits=$("$OBJDUMP" -d "$so" 2>/dev/null | grep -coE "$rule" || true)
        hits="${hits:-0}"
        CHECKED=$((CHECKED + 1))

        if [ "$hits" -eq 0 ]; then
            echo "  ok: $abi/$(basename "$so") - no $rule_name instructions"
        elif is_strict "$so"; then
            echo "::error::$so contains $hits $rule_name instruction(s). This library is built by us and has NO runtime dispatch, so an emitted instruction is an executed one - it WILL SIGILL on any $abi device without the feature."
            "$OBJDUMP" -d "$so" 2>/dev/null | grep -nE "$rule" | head -5 >&2 || true
            FAILED=1
        elif has_runtime_cpu_detection "$so"; then
            echo "  ok: $abi/$(basename "$so") - $hits $rule_name instruction(s), but the binary carries runtime CPU-feature detection, so they are reached only where the feature is present"
        else
            echo "::error::$so contains $hits $rule_name instruction(s) and NO evidence of runtime CPU-feature detection. Either it gained an ungated SIMD path, or it is no longer a Go binary and this gate assumption about it is stale. Both need a human."
            "$OBJDUMP" -d "$so" 2>/dev/null | grep -nE "$rule" | head -5 >&2 || true
            FAILED=1
        fi
    done
done

if [ "$FAILED" -ne 0 ]; then
    echo "::error::check_no_sha3_ext FAILED. Most likely cause: a dependency re-enabled pqcrypto-mlkem's 'neon' (or 'avx2') feature. Restoring the arm64 one needs runtime dispatch on getauxval(AT_HWCAP) & HWCAP_SHA3, which the crate does not provide -- it hardcodes 'if true'." >&2
    exit 1
fi

if [ "$CHECKED" -eq 0 ]; then
    echo "::error::check_no_sha3_ext: every ABI present has an empty rule — nothing was actually disassembled. arm64-v8a is missing from '$LIB_ROOT'." >&2
    exit 1
fi

echo "check_no_sha3_ext: ${#ABI_DIRS[@]} ABI(s), $CHECKED library(ies) disassembled and clean"
