#!/usr/bin/env bash
#
# Self-test for scripts/check_no_sha3_ext.sh, the ISA-baseline gate.
#
# A gate is only worth having if it is known to bite. This runs the gate
# against the hand-assembled fixtures in scripts/testdata/isa-gate (see
# build-fixtures.sh there for how they are made) and asserts, per case, BOTH
# the exit status and the text of the verdict. Every case is one of the
# mutations named in the SIGILL hardening plan:
#
#   sha3           the 1.4.25 crash: eor3/rax1/xar/bcax -> FAIL, naming each
#                  opcode and the function
#   lse-outlined   LSE only inside compiler-rt-shaped, HWCAP-gated helpers,
#                  stripped -> PASS (per-site verification, not a name match)
#   lse-inline     the same opcodes with no guard (+lse rustflags) -> FAIL
#   libfoo         the sha3 fixture under a non-allowlisted name, with and
#                  without the Go "internal/cpu" marker -> FAIL both times
#                  (STRICT is the default; the heuristic rescues nothing but
#                  the two Go binaries)
#   libxray        the sha3 fixture under an allowlisted Go name -> FAIL
#                  without the marker, PASS with it
#   v7 / v8-attrs  armeabi-v7a judged on .ARM.attributes -> PASS / FAIL
#   avx2           ymm + BMI2 + AES-NI + PCLMUL on x86_64 -> FAIL
#   shani34        the sha2 crate's pinned SHA-NI count -> PASS as
#                  librosenpass_jni.so, FAIL under any other name
#   popcnt         POPCNT on 32-bit x86 -> FAIL
#   no-tool        OBJDUMP pointing nowhere -> FAIL (never skip-as-pass)
#   unknown-abi    a directory the rule table does not know -> FAIL
#
# The test needs a disassembler the gate can find (NDK, rustup llvm-tools,
# llvm-objdump or binutils). If there is none, the PASS cases fail and this
# script fails with the gate's own "no capable objdump" message -- a missing
# toolchain is a test failure here, never a skip.
#
# Usage: scripts/tests/check_no_sha3_ext_test.sh
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
GATE="$HERE/../check_no_sha3_ext.sh"
FIX="$HERE/../testdata/isa-gate"

[ -f "$GATE" ] || { echo "FAIL: gate script not found at $GATE" >&2; exit 1; }
[ -d "$FIX" ]  || { echo "FAIL: fixtures not found at $FIX" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

PASSED=0
FAILED=0

# stage <case> <abi> <fixture.so> <published name>
stage() {
    mkdir -p "$WORK/$1/$2"
    cp "$FIX/$3" "$WORK/$1/$2/$4"
}

# expect <case> <expected rc> <required substrings...>
# Runs the gate on $WORK/<case>, captures stdout+stderr, checks the exit code
# and that every required substring appears in the output.
expect() {
    local name="$1" want="$2"; shift 2
    local out rc=0
    out=$(bash "$GATE" "$WORK/$name" 2>&1) || rc=$?
    local ok=1
    if [ "$rc" -ne "$want" ]; then
        echo "FAIL [$name]: expected exit $want, got $rc" >&2
        ok=0
    fi
    local s
    for s in "$@"; do
        if ! printf '%s' "$out" | grep -qF -- "$s"; then
            echo "FAIL [$name]: output does not mention '$s'" >&2
            ok=0
        fi
    done
    if [ "$ok" -eq 1 ]; then
        echo "  ok  [$name] exit $rc"
        PASSED=$((PASSED + 1))
    else
        printf '%s\n' "$out" | sed 's/^/      | /' >&2
        FAILED=$((FAILED + 1))
    fi
}

# ── arm64-v8a ───────────────────────────────────────────────────────────────
stage sha3 arm64-v8a arm64-sha3.so librosenpass_jni.so
expect sha3 1 "eor3" "rax1" "xar" "bcax" "f1600x2_fixture" "STRICT" "SIGILL"

stage lse-outlined arm64-v8a arm64-lse-outlined.so librosenpass_jni.so
expect lse-outlined 0 "LSE_GATED_OK ldaddal=1" "LSE_GATED_OK casal=1" "checked and clean"

stage lse-inline arm64-v8a arm64-lse-inline.so librosenpass_jni.so
expect lse-inline 1 "LSE ldadd=1" "LSE stadd=1" "atomic_inc_inline_fixture"

# H3: STRICT by default. A non-allowlisted name gets no heuristic, marker or not.
stage libfoo arm64-v8a arm64-sha3.so libfoo.so
expect libfoo 1 "libfoo.so contains 4 ungated" "eor3"
stage libfoo-marker arm64-v8a arm64-sha3.so libfoo.so
printf 'internal/cpu' >> "$WORK/libfoo-marker/arm64-v8a/libfoo.so"
expect libfoo-marker 1 "libfoo.so contains 4 ungated" "eor3"

# The allowlisted Go names DO get the heuristic -- and only with evidence.
stage libxray-nomarker arm64-v8a arm64-sha3.so libxray.so
expect libxray-nomarker 1 "NO evidence of Go runtime CPU-feature detection"
stage libxray-marker arm64-v8a arm64-sha3.so libxray.so
printf 'internal/cpu' >> "$WORK/libxray-marker/arm64-v8a/libxray.so"
expect libxray-marker 0 "Go runtime binary (GO_RUNTIME_LIBS)" "checked and clean"

# ── armeabi-v7a (attributes, not mnemonics) ─────────────────────────────────
stage v7 armeabi-v7a armv7-neon.so librosenpass_jni.so
expect v7 0 ".ARM.attributes within the armeabi-v7a baseline"

stage v8-attrs armeabi-v7a armv7-v8-attrs.so librosenpass_jni.so
expect v8-attrs 1 "Tag_CPU_arch=14" "Tag_Advanced_SIMD_arch=3" "beyond the armeabi-v7a baseline"

# ── x86_64 / x86 ────────────────────────────────────────────────────────────
stage avx2 x86_64 x86_64-avx2.so librosenpass_jni.so
expect avx2 1 "vpxor" "pdepq" "pextq" "aesenc" "pclmulqdq" "vzeroupper" "avx2_fixture"

stage shani34 x86_64 x86_64-shani34.so librosenpass_jni.so
expect shani34 0 "SHANI(cpuid-dispatched,sha2-crate)=34" "checked and clean"

stage shani34-other x86_64 x86_64-shani34.so libother.so
expect shani34-other 1 "libother.so contains 34 ungated" "sha256rnds2"

stage popcnt x86 x86-popcnt.so librosenpass_jni.so
expect popcnt 1 "POPCNT popcntl=1" "popcnt_fixture"

# ── fail-closed plumbing ────────────────────────────────────────────────────
stage no-tool arm64-v8a arm64-lse-outlined.so librosenpass_jni.so
out=$(OBJDUMP=/nonexistent/objdump bash "$GATE" "$WORK/no-tool" 2>&1) && rc=0 || rc=$?
if [ "$rc" -ne 0 ] && printf '%s' "$out" | grep -qF "no capable objdump"; then
    echo "  ok  [no-tool] exit $rc"; PASSED=$((PASSED + 1))
else
    echo "FAIL [no-tool]: a missing disassembler must fail the gate, got exit $rc" >&2
    printf '%s\n' "$out" | sed 's/^/      | /' >&2; FAILED=$((FAILED + 1))
fi

stage unknown-abi mips64 arm64-sha3.so librosenpass_jni.so
expect unknown-abi 1 "no ISA rule for ABI 'mips64'"

mkdir -p "$WORK/empty-abi/arm64-v8a"
expect empty-abi 1 "contains no .so files"

echo
echo "check_no_sha3_ext_test: $PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ]
