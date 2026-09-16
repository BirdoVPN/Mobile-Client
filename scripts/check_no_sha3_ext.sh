#!/usr/bin/env bash
#
# ISA-baseline gate for every shipped native library.
#
# Assert that no shipped native library can execute a CPU instruction the
# device's ABI does not guarantee -- unless that instruction sits behind a
# runtime CPU-feature check this script can SEE.
#
# The file name is historical: it started as a FEAT_SHA3-only check after the
# 1.4.25 crash and is now the single ISA-baseline gate for all four ABIs. Every
# caller (android.yml PR build, android.yml release APK + AAB, native/build.sh,
# native/build.ps1 via :app:buildRustLibs) runs THIS file, so there is exactly
# one rule table to reason about. scripts/tests/check_no_sha3_ext_test.sh runs
# it against hand-assembled fixtures and must stay green.
#
# WHY THIS GATE EXISTS
#
# HISTORY (1.3.25 .. 1.4.25): pqcrypto-mlkem's default features include `neon`.
# With it on, build.rs compiles PQClean's AArch64 ML-KEM and flips the FFI
# bindings from PQCLEAN_MLKEM1024_CLEAN_* to PQCLEAN_MLKEM1024_AARCH64_*, which
# reaches pqclean/common/keccak2x/feat.S -- 64 SHA3-extension instructions
# (eor3 x10, rax1 x5, xar x24, bcax x25, all in one 24-round loop), no `.arch`
# guard, and NO RUNTIME CPU DETECTION. The choice is compile-time only.
#
# TODAY (1.4.30+): the KEM is RustCrypto ml-kem, and pqcrypto is gone. That did
# NOT remove the mnemonics: ml-kem -> sha3 0.11 -> keccak 0.2.2 ships
# src/backends/aarch64_sha3.rs, adapted from the same XKCP/K12 source PQClean
# vendored, and a DEFAULT aarch64 build contains the same 64 instructions with
# the same split. Two things keep this gate green rather than one:
#
#   1. keccak dispatches at RUNTIME on cpufeatures' getauxval(AT_HWCAP) &
#      (HWCAP_SHA3|HWCAP_SHA512) -- the check PQClean's aarch64 path did not
#      have; and
#   2. native/rosenpass-jni/.cargo/config.toml pins --cfg keccak_backend="soft"
#      on every shipped ABI, which makes the fast backend unreferenced so the
#      linker drops it entirely.
#
# (2) is what this gate measures, because this gate judges PRESENCE. (1) is
# what makes (2) a safety margin rather than the only thing standing between
# the fleet and a SIGILL. The known way to defeat both at once is
# RUSTFLAGS='-C target-feature=+sha3', which additionally makes cpufeatures'
# __unless_target_features! elide the HWCAP check and return a constant true;
# android.yml builds exactly that and asserts THIS SCRIPT FAILS on it.
#
# FEAT_SHA3 is OPTIONAL in ARMv8.2-A. On an arm64 device without it the first
# `eor3` raises SIGILL and the process dies. That crash shipped in every Android
# build from 1.3.25 to 1.4.25 and was reported from a Galaxy A70 (Snapdragon 675
# / Kryo 460 -- ARMv8.2-A, no FEAT_SHA3), a Redmi Note 8 (Snapdragon 665), a
# Xiaomi 11T Pro (Snapdragon 888 -- all eight cores lack it) and Helio G8x/G9x
# devices. It fires inside nativeGenerateKeypair, so with PQ default-ON the user
# cannot connect at all.
#
# The 2026-09 fix was `default-features = false` in
# native/rosenpass-jni/Cargo.toml (#353); the current configuration is the
# soft-Keccak cfg described above. This script exists so that any route back to
# an ungated optional-extension opcode -- a re-enabled feature, a transitive
# dependency, a dropped cfg, a stray RUSTFLAGS -- fails the build instead of
# shipping a crash to every mid-range Android device.
# scripts/check_pq_features.sh asserts the dependency and cfg shape one layer
# earlier, before anything is compiled.
#
# Verified on real aarch64 before this gate was written: with the fix, the LINKED
# library contains 0 SHA3-ext instructions, 0 PQCLEAN_*_AARCH64_* symbols and 0
# keccakx2 symbols (the linker drops the unreferenced feat.o). So zero is the
# correct threshold, not a hopeful one.
#
# WHY IT IS NOT SHA3-ONLY ANY MORE
#
# The mechanism was never specific to SHA3: it is "a compile-time ISA choice
# with no runtime dispatch". An ARMv8.0 Kryo 260 would SIGILL just as surely on
# an ungated LSE atomic or an SQRDMLAH, and the x86 side of the same crate has an
# `avx2` default feature that is only safe because upstream happens to wrap it
# in is_x86_feature_detected!. So the arm64 rule now covers every optional
# extension class the decoder can name, x86/x86_64 cover VEX/BMI/AES-NI/PCLMUL
# on top of wide vectors, and armeabi-v7a is checked through its build
# attributes. An ABI directory with no rule is a FAILURE, not a skip.
#
# WHY THE TRUST MODEL IS "STRICT UNLESS ALLOWLISTED"
#
# The first version listed librosenpass_jni.so as the one STRICT library and let
# everything else pass on a substring heuristic ("does the binary mention
# internal/cpu"). That is the wrong default: a renamed or second first-party .so
# would silently fall into the lenient bucket. Now every library is STRICT
# unless it is in GO_RUNTIME_LIBS, and even a STRICT library may carry an
# extended instruction when the gate can verify the runtime check PER SITE
# (the compiler-rt outlined-atomics helper shape -- LSE_GATED_OK in the arm64
# class table).
#
# Usage: scripts/check_no_sha3_ext.sh <dir-containing-abi-subdirs>
#
# Environment:
#   OBJDUMP    override the disassembler (tests use it to prove "no tool" fails)
#   READOBJ    override the ELF attribute reader for armeabi-v7a
set -euo pipefail

LIB_ROOT="${1:?usage: check_no_sha3_ext.sh <lib-root>}"

if [ ! -d "$LIB_ROOT" ]; then
    echo "::error::check_no_sha3_ext: '$LIB_ROOT' is not a directory" >&2
    exit 1
fi

# ── Tool discovery ──────────────────────────────────────────────────────────
#
# A missing tool must FAIL, never skip. A gate that silently passes because its
# tool was absent is worse than no gate -- it reports green over an unchecked
# binary, which is the exact failure mode this repo keeps paying for.
#
# Search order: explicit override, the NDK (its llvm-objdump knows every
# Android ELF machine), rustup's llvm-tools component (`rustup component add
# llvm-tools` -- what native/build.sh recommends for a machine without an NDK),
# then whatever llvm/binutils is on PATH.
find_in_ndk() {
    # $1 = tool base name. -name '<tool>*' so a Windows dev machine
    # (llvm-objdump.exe) can run this gate locally too.
    [ -n "${ANDROID_NDK_HOME:-}" ] || return 1
    find "$ANDROID_NDK_HOME" \( -name "$1" -o -name "$1.exe" \) -type f 2>/dev/null | head -1
}

find_in_rustup() {
    command -v rustc >/dev/null 2>&1 || return 1
    local sysroot
    sysroot=$(rustc --print sysroot 2>/dev/null) || return 1
    find "$sysroot/lib/rustlib" -path '*/bin/*' \( -name "$1" -o -name "$1.exe" \) -type f 2>/dev/null | head -1
}

OBJDUMP="${OBJDUMP:-}"
if [ -z "$OBJDUMP" ]; then
    OBJDUMP=$(find_in_ndk llvm-objdump || true)
fi
if [ -z "$OBJDUMP" ]; then
    OBJDUMP=$(find_in_rustup llvm-objdump || true)
fi
if [ -z "$OBJDUMP" ]; then
    for c in llvm-objdump aarch64-linux-gnu-objdump objdump; do
        if command -v "$c" >/dev/null 2>&1; then OBJDUMP="$c"; break; fi
    done
fi
if [ -z "$OBJDUMP" ] || ! command -v "$OBJDUMP" >/dev/null 2>&1; then
    # Name the exact command, with the toolchain this repo pins. A missing
    # disassembler is a FAILURE and not a skip -- a gate that cannot look at
    # the library must never report clean -- but the person who hits it is
    # usually a reviewer running scripts/tests/check_no_sha3_ext_test.sh
    # locally, where 15 of 16 fixtures fail with this one line and the fix is
    # a single rustup command. The channel is read from rust-toolchain.toml so
    # this hint cannot go stale when the pin moves.
    _pinned_channel=$(sed -n 's/^[[:space:]]*channel[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' \
        "$(dirname -- "${BASH_SOURCE[0]}")/../rust-toolchain.toml" 2>/dev/null | head -1)
    _hint="rustup component add llvm-tools"
    [ -n "$_pinned_channel" ] && _hint="$_hint --toolchain $_pinned_channel"
    echo "::error::check_no_sha3_ext: no capable objdump found (tried \$OBJDUMP, \$ANDROID_NDK_HOME, rustup llvm-tools, llvm-objdump, aarch64-linux-gnu-objdump, objdump). Install the NDK, or run: $_hint" >&2
    exit 1
fi

# -d: executable sections only -- an opcode that is never decoded cannot raise
# SIGILL. Both llvm-objdump (measured: Ubuntu 24.04 llvm-objdump 18.1.3 with no
# --mattr decodes all 64 SHA3 sites of the 1.4.25 binary) and GNU objdump (same
# 64 on Ubuntu 24.04 aarch64-linux-gnu-objdump) decode every extension they
# know by default; what neither knows shows up as <unknown>/.inst and is
# classified UNDECODED, which fails a STRICT library rather than hiding a newer
# opcode.
OBJDUMP_ARGS=(-d)
echo "check_no_sha3_ext: using $OBJDUMP"

# armeabi-v7a is judged on .ARM.attributes, so it needs an ELF attribute
# reader rather than a disassembler. llvm-readobj / llvm-readelf print the
# numeric tag values we compare against; GNU readelf prints descriptions and is
# mapped through a small table below. Resolved lazily: an artifact with no v7
# slice must not fail for want of a tool it would never use.
READOBJ="${READOBJ:-}"
find_readobj() {
    [ -n "$READOBJ" ] && { printf '%s' "$READOBJ"; return 0; }
    local r
    r=$(find_in_ndk llvm-readobj || true); [ -n "$r" ] && { printf '%s' "$r"; return 0; }
    r=$(find_in_ndk llvm-readelf || true); [ -n "$r" ] && { printf '%s' "$r"; return 0; }
    r=$(find_in_rustup llvm-readobj || true); [ -n "$r" ] && { printf '%s' "$r"; return 0; }
    for c in llvm-readobj llvm-readelf arm-linux-gnueabihf-readelf readelf; do
        if command -v "$c" >/dev/null 2>&1; then printf '%s' "$c"; return 0; fi
    done
    return 1
}

# ── Library policy ──────────────────────────────────────────────────────────
#
# STRICT   the default. An extended instruction is a failure unless this script
#          can verify its runtime guard at the site (see the per-class rules).
#
# GATED    Go binaries only. Go's internal/cpu reads HWCAP/CPUID at process
#          start and every accelerated path branches on those flags, so a Go
#          binary legitimately CONTAINS thousands of instructions it will never
#          execute on a CPU lacking the feature (libxray.so: 7163 LSE sites,
#          every one behind runtime.arm64HasATOMICS; 64 SHA3 sites behind a
#          useSHA3 flag Go 1.25 sets to false on Android). Per-site proof for a
#          Go binary is out of scope; the policy accepts the instructions ONLY
#          if the binary carries positive evidence of internal/cpu. No evidence
#          means FAIL.
#
# The allowlist is by exact file name and is deliberately short. Add a name
# here only for a Go binary; a first-party or C/C++/Rust library never belongs
# here because nothing in it dispatches on CPU features.
GO_RUNTIME_LIBS=("libwg-go.so" "libxray.so")

is_go_runtime_lib() {
    local base s
    base=$(basename "$1")
    for s in "${GO_RUNTIME_LIBS[@]}"; do
        [ "$base" = "$s" ] && return 0
    done
    return 1
}

# Positive evidence that a Go binary performs runtime CPU feature detection.
# internal/cpu is what populates ARM64.HasSHA3 / X86.HasAVX2 from AT_HWCAP and
# CPUID, and its symbol names survive in the pclntab even in a stripped .so.
# `grep -a` rather than `strings`: grep is everywhere, strings is binutils.
has_runtime_cpu_detection() {
    grep -aqE 'internal/cpu|runtime/internal/cpu|ARM64\.Has|X86\.Has|ARM\.Has' "$1" 2>/dev/null
}

# ── Disassembly normaliser ──────────────────────────────────────────────────
#
# llvm-objdump and GNU objdump lay a line out differently (tab vs. space
# between mnemonic and operands, hex bytes padded differently, GNU wraps long
# x86 encodings onto byte-only continuation lines). One pass turns either into
# FUNCTION <tab> ADDRESS <tab> MNEMONIC <tab> OPERANDS <tab> RAW so that the
# per-ABI classifiers below never parse objdump output themselves.
normalize_dis() {
    awk '
    function strip(s) { gsub(/^[[:space:]]+|[[:space:]]+$/, "", s); return s }
    BEGIN { f = "<no symbol>" }
    /^[0-9a-f]+ <.*>:$/ { f = $0; sub(/^[0-9a-f]+ </, "", f); sub(/>:$/, "", f); next }
    /^[[:space:]]*[0-9a-f]+:/ {
        raw = $0
        addr = raw; sub(/^[[:space:]]*/, "", addr); sub(/:.*$/, "", addr)
        rest = raw; sub(/^[[:space:]]*[0-9a-f]+:[[:space:]]*/, "", rest)
        # Encoded bytes: one 8-hex word (AArch64) or a run of "xx " pairs (x86).
        if (rest ~ /^[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]([[:space:]]|$)/)      sub(/^[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][[:space:]]*/, "", rest)
        else if (rest ~ /^([0-9a-f][0-9a-f] )*[0-9a-f][0-9a-f]([[:space:]]|$)/) sub(/^([0-9a-f][0-9a-f] )*[0-9a-f][0-9a-f][[:space:]]*/, "", rest)
        rest = strip(rest)
        if (rest == "") next                     # GNU byte-only continuation line
        m = rest; sub(/[[:space:]].*$/, "", m)
        ops = rest; sub(/^[^[:space:]]+[[:space:]]*/, "", ops)
        print f "\t" addr "\t" m "\t" ops "\t" raw
    }' "$1"
}

# ── arm64-v8a rule ──────────────────────────────────────────────────────────
#
# One awk pass over the disassembly classifies every instruction that is not in
# the ARMv8.0-A baseline every arm64 Android device is guaranteed to implement.
# The output is one line per hit: CLASS <tab> MNEMONIC <tab> FUNCTION <tab>
# ADDRESS. Classes ending in _OK are allowed and only counted; everything else
# is a failure for a STRICT library.
#
# Class table (Arm ARM feature name -> mnemonics). Word-anchored on the
# mnemonic field, so `xar` cannot match `xaflag` and `stg` cannot match `stgp`
# by accident -- each is listed on its own.
#
#   SHA3      FEAT_SHA3    eor3 rax1 xar bcax           -- the 1.4.25 crash
#   SHA512    FEAT_SHA512  sha512h sha512h2 sha512su0 sha512su1
#   SM3SM4    FEAT_SM3/4   sm3* sm4e sm4ekey
#   LSE       FEAT_LSE     ldadd* ldclr* ldeor* ldset* ld[su]max* ld[su]min*
#                          swp* cas* and the st* no-result aliases
#   RDM       FEAT_RDM     sqrdmlah sqrdmlsh
#   DOTPROD   FEAT_DotProd sdot udot
#   FHM       FEAT_FHM     fmlal fmlal2 fmlsl fmlsl2
#   FP16      FEAT_FP16    any f* arithmetic on .4h/.8h vectors or h registers.
#                          fcvtl/fcvtn/fcvt/fcvtxn are EXCLUDED: half-precision
#                          conversion is ARMv8.0 (FP16 storage), only arithmetic
#                          is the optional extension. Validated with zero hits
#                          on the 1.4.28 arm64 librosenpass_jni.so, libsentry.so
#                          and libwg.so.
#   LRCPC     FEAT_LRCPC/2 ldapr ldaprb ldaprh ldapur* stlur*
#   CRC32     FEAT_CRC32   crc32b/h/w/x crc32cb/h/w/x (optional in v8.0)
#   CRYPTO    FEAT_AES/SHA1/SHA256  aese aesd aesmc aesimc sha1* sha256*
#   PMULL     FEAT_PMULL   pmull/pmull2 with a .1q destination ONLY -- the
#                          .8b/.16b polynomial multiply is base Advanced SIMD.
#   SVE       FEAT_SVE     any z<n>.<t> operand, ptrue/pfalse/whilel*/rdvl/addvl
#   BF16I8MM  FEAT_BF16/I8MM  bfdot bfmmla bfmlal[bt] bfcvt* smmla ummla
#                          usmmla usdot sudot
#   JSCVT     FEAT_JSCVT   fjcvtzs
#   FCMA      FEAT_FCMA    fcmla fcadd
#   MTE       FEAT_MTE     stg st2g stzg stz2g stgp ldg ldgm stgm stzgm irg
#                          addg subg gmi subp subps cmpp
#   FLAGM     FEAT_FlagM/2 cfinv rmif setf8 setf16 axflag xaflag
#   PAUTH     FEAT_PAuth   the NON-hint forms: pacia/autia with explicit
#                          registers, retaa/retab, braa/blraa, ldraa/ldrab, pacga
#   TME       FEAT_TME     tstart tcommit tcancel ttest
#   MOPS      FEAT_MOPS    cpy* set[gpme]*
#   LS64      FEAT_LS64    ld64b st64b st64bv st64bv0
#   SB        FEAT_SB      sb
#   HBC       FEAT_HBC     bc.<cond>
#   UNDECODED anything the disassembler could not decode inside an executable
#             section. An opcode newer than the decoder looks exactly like this,
#             so it is a failure for a STRICT library, not noise.
#
#   HINT_OK   (allowed) paciasp autiasp paciaz autiaz pacia1716 autia1716
#             pacibsp autibsp pacibz autibz pacib1716 autib1716 xpaclri bti.
#             These live in the HINT space and execute as NOP on a core without
#             PAuth/BTI -- they cannot SIGILL. The NDK emits them for every
#             build (1.4.28: 103 bti + 89 PAuth hints in librosenpass_jni.so).
#
#   LSE_GATED_OK (allowed) an LSE instruction whose site has the compiler-rt
#             outlined-atomics helper shape. compiler-rt/lib/builtins/aarch64/
#             lse.S expands every __aarch64_<op><size>_<order> helper as
#                 adrp x16, __aarch64_have_lse_atomics
#                 ldrb w16, [x16, :lo12:__aarch64_have_lse_atomics]
#                 cbz  w16, 1f
#                 <the LSE instruction>
#                 ret
#             1:  <ldaxr/stlxr loop>
#             and __aarch64_have_lse_atomics is set from getauxval(AT_HWCAP)
#             & HWCAP_ATOMICS at load. The check is per SITE: the two
#             preceding instructions must be `ldrb w16, [x16, ...]` and
#             `cbz w16, ...` and the next must be `ret`. A library that keeps
#             its symbols is also accepted when the site is inside a function
#             named __aarch64_<op>. Shipped libraries are stripped by AGP, so
#             the structural check is the one that matters (libsentry.so in
#             1.4.28 carries 9 such helpers and no symbol names for them).
#             Rust's own aarch64-linux-android codegen has outline-atomics OFF
#             (ldaxr/stlxr inline), so librosenpass_jni.so is expected to have
#             zero of either kind.
#
#   MTE_LIBUNWIND_OK (allowed, at most ONE per library) `stg xN, [xN]`
#             immediately followed by `add xN, xN, #0x10`. That is the
#             stack-untagging loop in NDK libunwind's
#             DwarfInstructions::stepWithDwarf, reached only when the CIE for
#             the frame carries a "G" augmentation (mteTaggedFrame). No CIE in
#             any shipped .so has one (measured on 1.4.25/1.4.27/1.4.28: only
#             "zR" and "zPLR"), so the instruction is present but unreachable.
#             Compiling anything with -fsanitize=memtag-stack would make it
#             live -- and would also add more stg sites, which then fail.
arm64_classify() {
    awk -F'\t' '
    function emit(c, m, f, a) { print c "\t" m "\t" f "\t" a }
    BEGIN { pending = ""; pending_mte = "" }
    {
        f = $1; addr = $2; m = $3; ops = $4; line = $5

        # A pending LSE hit is resolved by THIS instruction (must be ret).
        if (pending != "") {
            split(pending, p, SUBSEP)
            gated = (p[4] == "1" && m == "ret") ? 1 : 0
            emit(gated ? "LSE_GATED_OK" : "LSE", p[1], p[2], p[3])
            pending = ""
        }
        if (pending_mte != "") {
            split(pending_mte, p, SUBSEP)
            ok = (m == "add" && ops ~ ("^" p[4] ", " p[4] ", #0x10$")) ? 1 : 0
            emit(ok ? "MTE_LIBUNWIND_OK" : "MTE", p[1], p[2], p[3])
            pending_mte = ""
        }

        c = ""
        if (line ~ /<unknown>/ || m ~ /^\.(inst|word|long|short|byte)$/) c = "UNDECODED"
        else if (m ~ /^(eor3|rax1|xar|bcax)$/) c = "SHA3"
        else if (m ~ /^(sha512h|sha512h2|sha512su0|sha512su1)$/) c = "SHA512"
        else if (m ~ /^(sm3[a-z0-9]*|sm4e|sm4ekey)$/) c = "SM3SM4"
        else if (m ~ /^(ldadd|ldclr|ldeor|ldset|ldsmax|ldsmin|ldumax|ldumin|swp|cas|stadd|stclr|steor|stset|stsmax|stsmin|stumax|stumin)[a-z0-9]*$/) c = "LSE"
        else if (m ~ /^(sqrdmlah|sqrdmlsh)$/) c = "RDM"
        else if (m ~ /^(sdot|udot)$/) c = "DOTPROD"
        else if (m ~ /^(fmlal|fmlal2|fmlsl|fmlsl2)$/) c = "FHM"
        else if (m ~ /^f[a-z0-9]+$/ && m !~ /^(fcvtl|fcvtl2|fcvtn|fcvtn2|fcvt|fcvtxn|fcvtxn2)$/ && (ops ~ /v[0-9]+\.[48]h/ || ops ~ /(^|[^a-z0-9])h[0-9]+($|[^a-z0-9])/)) c = "FP16"
        else if (m ~ /^(ldapr|ldaprb|ldaprh|ldapur[a-z]*|stlur[bh]?)$/) c = "LRCPC"
        else if (m ~ /^crc32c?[bhwx]$/) c = "CRC32"
        else if (m ~ /^(aese|aesd|aesmc|aesimc|sha1c|sha1h|sha1m|sha1p|sha1su0|sha1su1|sha256h|sha256h2|sha256su0|sha256su1)$/) c = "CRYPTO"
        else if (m ~ /^pmull2?$/ && ops ~ /\.1q/) c = "PMULL"
        else if (ops ~ /(^|[^a-z0-9])z[0-9]+\.[bhsdq]/ || m ~ /^(ptrue|pfalse|whilel[a-z]|rdvl|addvl)$/) c = "SVE"
        else if (m ~ /^(bfdot|bfmmla|bfmlalb|bfmlalt|bfcvt|bfcvtn|bfcvtn2|smmla|ummla|usmmla|usdot|sudot)$/) c = "BF16I8MM"
        else if (m ~ /^fjcvtzs$/) c = "JSCVT"
        else if (m ~ /^(fcmla|fcadd)$/) c = "FCMA"
        else if (m ~ /^(stg|st2g|stzg|stz2g|stgp|ldg|ldgm|stgm|stzgm|irg|addg|subg|gmi|subp|subps|cmpp)$/) c = "MTE"
        else if (m ~ /^(cfinv|rmif|setf8|setf16|axflag|xaflag)$/) c = "FLAGM"
        else if (m ~ /^(pacia|pacib|pacda|pacdb|autia|autib|autda|autdb|paciza|pacizb|pacdza|pacdzb|autiza|autizb|autdza|autdzb|xpaci|xpacd|retaa|retab|braa|brab|braaz|brabz|blraa|blrab|blraaz|blrabz|eretaa|eretab|ldraa|ldrab|pacga)$/) c = "PAUTH"
        else if (m ~ /^(tstart|tcommit|tcancel|ttest)$/) c = "TME"
        else if (m ~ /^(cpy[a-z]*|set[gpme][a-z]*)$/ && m !~ /^setf(8|16)$/) c = "MOPS"
        else if (m ~ /^(ld64b|st64b|st64bv|st64bv0)$/) c = "LS64"
        else if (m == "sb") c = "SB"
        else if (m ~ /^bc\./) c = "HBC"
        else if (m ~ /^(paciasp|autiasp|paciaz|autiaz|pacia1716|autia1716|pacibsp|autibsp|pacibz|autibz|pacib1716|autib1716|xpaclri|bti)$/) c = "HINT_OK"

        if (c == "LSE") {
            # Defer: gated iff prev2 = ldrb w16,[x16,...], prev1 = cbz w16,..., next = ret,
            # or the enclosing symbol is a compiler-rt outlined helper.
            shape = (p2m == "ldrb" && p2o ~ /^w16, \[x16/ && p1m == "cbz" && p1o ~ /^w16, /) ? "1" : "0"
            if (f ~ /^__aarch64_(ldadd|ldclr|ldeor|ldset|swp|cas)[0-9]+_(relax|acq|rel|acq_rel|sync)$/) shape = "1"
            pending = m SUBSEP f SUBSEP addr SUBSEP shape
        } else if (c == "MTE" && m == "stg" && ops ~ /^x[0-9]+, \[x[0-9]+\]$/) {
            reg = ops; sub(/,.*$/, "", reg)
            base = ops; sub(/^.*\[/, "", base); sub(/\]$/, "", base)
            if (reg == base) pending_mte = m SUBSEP f SUBSEP addr SUBSEP reg
            else emit(c, m, f, addr)
        } else if (c != "") {
            emit(c, m, f, addr)
        }
        p2m = p1m; p2o = p1o; p1m = m; p1o = ops
    }
    END {
        if (pending != "")     { split(pending, p, SUBSEP);     emit("LSE", p[1], p[2], p[3]) }
        if (pending_mte != "") { split(pending_mte, p, SUBSEP); emit("MTE", p[1], p[2], p[3]) }
    }' "$1"
}

# ── x86 / x86_64 rule ───────────────────────────────────────────────────────
#
# Android x86_64 ABI baseline: SSE4.2 + POPCNT (developer.android.com/ndk/
# guides/abis). Android x86 ABI baseline: SSSE3 -- no SSE4.x, no POPCNT.
#
#   AVX_WIDE  any %ymm/%zmm operand: AVX, AVX2, AVX-512
#   VEX       a v-prefixed mnemonic on %xmm (VEX-encoded 128-bit AVX) -- AVX
#             is a CPUID bit, and VEX encodings #UD on a CPU without it even at
#             128 bits. The handful of base mnemonics that start with v
#             (verr/verw/vm*) are excluded by name.
#   BMI       BMI1/BMI2 + LZCNT: andn bextr blsi blsmsk blsr bzhi mulx pdep
#             pext rorx sarx shlx shrx lzcnt (AT&T suffix allowed). tzcnt is NOT
#             listed: its encoding is `rep bsf`, which a non-BMI CPU executes
#             as plain bsf, and LLVM emits it for cttz only when the operand is
#             known non-zero, where the two agree (1.4.28: 18 sites on x86_64,
#             9 on x86, all safe). lzcnt has no such fallback (`rep bsr` gives a
#             different answer) and stays forbidden.
#   AESNI     aesenc aesenclast aesdec aesdeclast aesimc aeskeygenassist
#   PCLMUL    pclmul*
#   POPCNT    popcnt -- x86 (32-bit) only; part of the x86_64 baseline
#   SHANI     sha1rnds4 sha1nexte sha1msg1/2 sha256rnds2 sha256msg1/2 --
#             see SHANI_ALLOWED_COUNT below
#   UNDECODED as for arm64
#   CPUID_OK  (counted only) the SHA-NI allowance requires at least one.
x86_classify() {
    local abi="$1"
    awk -F'\t' -v abi="$abi" '
    {
        f = $1; addr = $2; m = $3; ops = $4; line = $5
        c = ""
        if (line ~ /<unknown>/ || m ~ /^\.(byte|word|long)$/ || m == "(bad)") c = "UNDECODED"
        else if (ops ~ /%[yz]mm[0-9]+/) c = "AVX_WIDE"
        else if (m ~ /^v[a-z0-9]+$/ && m !~ /^(verr|verw|vmcall|vmlaunch|vmresume|vmxoff|vmptrld|vmptrst|vmclear|vmread|vmwrite|vmxon|vmfunc)$/) c = "VEX"
        else if (m ~ /^(andn|bextr|blsi|blsmsk|blsr|bzhi|mulx|pdep|pext|rorx|sarx|shlx|shrx|lzcnt)[bwlq]?$/) c = "BMI"
        else if (m ~ /^(aesenc|aesenclast|aesdec|aesdeclast|aesimc|aeskeygenassist)$/) c = "AESNI"
        else if (m ~ /^pclmul[a-z]*$/) c = "PCLMUL"
        else if (m ~ /^(sha1rnds4|sha1nexte|sha1msg1|sha1msg2|sha256rnds2|sha256msg1|sha256msg2)$/) c = "SHANI"
        else if (abi == "x86" && m ~ /^popcnt[bwlq]?$/) c = "POPCNT"
        else if (m == "cpuid") c = "CPUID_OK"
        if (c != "") print c "\t" m "\t" f "\t" addr
    }' "$2"
}

# SHA-NI in librosenpass_jni.so is the `sha2` crate's x86 backend: 34 sites
# (32 sha256rnds2 + sha256msg1 + sha256msg2) inside one digest_blocks function
# that the crate reaches only through cpufeatures' cached CPUID check. Measured
# identical on 1.4.25 and 1.4.28, on both x86_64 and x86. The shipped .so is
# stripped, so the function cannot be named; the count is pinned instead. A
# DIFFERENT count means the sha2 crate (or its dispatch) changed and the gate
# must be re-audited before the number is touched -- do not "fix" a failure
# here by editing the constant. The library must also contain at least one
# cpuid instruction, or there is nothing for the dispatch to have consulted.
SHANI_ALLOWED_LIB="librosenpass_jni.so"
SHANI_ALLOWED_COUNT=34

# ── armeabi-v7a rule ────────────────────────────────────────────────────────
#
# Stripped v7 libraries have no $a/$t mapping symbols, so a disassembler cannot
# tell ARM from Thumb from data and mnemonic counts are unreliable. The build
# attributes (.ARM.attributes, aeabi) are what the toolchain itself recorded
# about the ISA it targeted, and the linker merges them across every object, so
# one C file compiled with -march=armv8-a shows up at the library level.
#
# Rule (numeric aeabi values, Addenda to the ABI for the Arm Architecture):
#   Tag_CPU_arch (6)           <= 10   (v7; 14 = v8-A)
#   Tag_FP_arch (10)           in {0,1,2,3,4}  (up to VFPv3 / VFPv3-D16; 5 = VFPv4)
#   Tag_Advanced_SIMD_arch (12) in {0,1}  (NEONv1; 2 = NEONv1+FMA, 3 = NEON for v8)
#   Tag_DIV_use (44)           != 2    (2 = "Allowed": explicit sdiv/udiv)
#   Tag_FP_HP_extension (36)   == 0    (half-precision conversions)
#   Tag_MPextension_use (42)   == 0
#   Tag_Virtualization_use (68) == 0
#   Tag_MVE_arch (48)          == 0
# which is exactly what every 1.4.28 armeabi-v7a library ships (ARM v7 /
# Thumb-2 / VFPv3 / NEONv1, no DIV_use, no v8 tags). Applied to EVERY v7
# library, Go included: the attributes describe what the C toolchain emitted,
# and Go's own arm backend targets GOARM=7 with VFPv3.
v7_attribute_violations() {
    # Prints one line per violated tag; prints nothing when clean. Input: the
    # raw output of llvm-readobj --arch-specific / llvm-readelf -A / readelf -A.
    awk '
    function judge(tag, val) {
        if (tag == 6  && val > 10)                 print "Tag_CPU_arch=" val " (only v7 or below is allowed; 14 = v8-A)"
        if (tag == 10 && val > 4)                  print "Tag_FP_arch=" val " (VFPv3/VFPv3-D16 max; 5 = VFPv4, 7 = FP for v8)"
        if (tag == 12 && val > 1)                  print "Tag_Advanced_SIMD_arch=" val " (NEONv1 max; 2 = NEON+FMA, 3 = NEON for v8)"
        if (tag == 44 && val == 2)                 print "Tag_DIV_use=2 (sdiv/udiv explicitly allowed)"
        if (tag == 36 && val != 0)                 print "Tag_FP_HP_extension=" val
        if (tag == 42 && val != 0)                 print "Tag_MPextension_use=" val
        if (tag == 68 && val != 0)                 print "Tag_Virtualization_use=" val
        if (tag == 48 && val != 0)                 print "Tag_MVE_arch=" val
        seen[tag] = 1
    }
    # GNU readelf prints descriptions only; map the ones we judge.
    function gnu(name, desc,   v) {
        v = -1
        if (name == "Tag_CPU_arch") {
            if (desc == "v7") v = 10; else if (desc ~ /^v[4-6]/) v = 6; else if (desc ~ /^v8/) v = 14; else if (desc ~ /^v9/) v = 22
        } else if (name == "Tag_FP_arch") {
            if (desc == "No") v = 0; else if (desc == "VFPv1") v = 1; else if (desc == "VFPv2") v = 2; else if (desc == "VFPv3") v = 3; else if (desc == "VFPv3-D16") v = 4; else if (desc ~ /^VFPv4/) v = 5; else if (desc ~ /ARMv8/) v = 7
        } else if (name == "Tag_Advanced_SIMD_arch") {
            if (desc == "No") v = 0; else if (desc == "NEONv1") v = 1; else if (desc ~ /Fused MAC/) v = 2; else if (desc ~ /ARMv8/) v = 3
        } else if (name == "Tag_DIV_use") {
            if (desc ~ /^Allowed in v7-A/) v = 2; else if (desc ~ /^Not allowed/) v = 1; else v = 0
        } else if (name == "Tag_FP_HP_extension" || name == "Tag_MPextension_use" || name == "Tag_Virtualization_use" || name == "Tag_MVE_arch") {
            v = (desc ~ /^(No|Not Allowed|Not Permitted|None)/) ? 0 : 1
        } else return
        if (v < 0) { print name ": unrecognised description \"" desc "\" -- cannot judge, failing closed"; return }
        t = (name == "Tag_CPU_arch") ? 6 : (name == "Tag_FP_arch") ? 10 : (name == "Tag_Advanced_SIMD_arch") ? 12 : (name == "Tag_DIV_use") ? 44 : (name == "Tag_FP_HP_extension") ? 36 : (name == "Tag_MPextension_use") ? 42 : (name == "Tag_Virtualization_use") ? 68 : 48
        judge(t, v)
    }
    /^[[:space:]]*Tag: [0-9]+$/        { tag = $2 + 0; val = ""; next }              # llvm
    /^[[:space:]]*Value: [0-9]+$/      { if (tag != "") { judge(tag, $2 + 0); tag = "" } ; next }
    /^[[:space:]]*Tag_[A-Za-z_]+: /    { name = $1; sub(/:$/, "", name); desc = $0; sub(/^[[:space:]]*Tag_[A-Za-z_]+: /, "", desc); gsub(/^"|"$/, "", desc); gnu(name, desc); next }
    END { if (!(6 in seen)) print "Tag_CPU_arch missing -- no .ARM.attributes to judge, failing closed" }
    ' "$1"
}

# ── Walk every ABI directory present ────────────────────────────────────────
DIS_DIR="$(mktemp -d)"
trap 'rm -rf "$DIS_DIR"' EXIT

mapfile -t ABI_DIRS < <(find "$LIB_ROOT" -mindepth 1 -maxdepth 1 -type d | sort)

if [ "${#ABI_DIRS[@]}" -eq 0 ]; then
    echo "::error::check_no_sha3_ext: no ABI directories under '$LIB_ROOT' — nothing was checked" >&2
    exit 1
fi

FAILED=0
CHECKED=0

# Disassemble $1 into $2. Returns non-zero (and prints a ::error) when the tool
# failed or produced no instruction lines -- both are FAILURES, never skips.
#
# WRITTEN TO A FILE AND CHECKED, NOT PIPED: `hits=$(objdump -d | grep -c)` sees
# an EMPTY stream when objdump fails to run at all, and an empty stream counts
# zero -- which is this script's PASS value. objdump's exit status is captured
# on its own line, and the output must contain instruction lines.
disassemble() {
    local so="$1" dis="$2" rc=0
    "$OBJDUMP" "${OBJDUMP_ARGS[@]}" "$so" > "$dis" 2> "$dis.err" || rc=$?
    if [ "$rc" -ne 0 ]; then
        echo "::error::check_no_sha3_ext: $OBJDUMP exited $rc on $so — the library was NOT disassembled and therefore NOT checked. Most likely the disassembler does not understand this ABI's ELF machine (a single-target binutils objdump handed a foreign object). Install the NDK's llvm-objdump (\$ANDROID_NDK_HOME) or 'rustup component add llvm-tools'." >&2
        head -3 "$dis.err" >&2 || true
        return 1
    fi
    if ! grep -qE '^[[:space:]]*[0-9a-fA-F]+:' "$dis"; then
        echo "::error::check_no_sha3_ext: $OBJDUMP produced no disassembly for $so (exit 0, $(wc -l < "$dis") line(s) of output). Nothing was checked, so this is a failure, not a pass." >&2
        return 1
    fi
    return 0
}

# Summarise a hits file (CLASS\tMNEMONIC\tFUNCTION\tADDR) as "CLASS mnemonic=N ...".
summarise_hits() {
    awk -F'\t' '{ k[$1 "\t" $2]++ } END { for (x in k) { split(x, a, "\t"); print a[1] " " a[2] "=" k[x] } }' "${1:--}" | sort
}

for abi_dir in "${ABI_DIRS[@]}"; do
    abi=$(basename "$abi_dir")

    case "$abi" in
        arm64-v8a|x86_64|x86|armeabi-v7a) ;;
        *)
            echo "::error::check_no_sha3_ext: no ISA rule for ABI '$abi'. A new ABI must be given a rule in this script before it ships — an unrecognised directory is not a pass." >&2
            FAILED=1
            continue ;;
    esac

    mapfile -t LIBS < <(find "$abi_dir" -name '*.so' -type f | sort)
    if [ "${#LIBS[@]}" -eq 0 ]; then
        echo "::error::check_no_sha3_ext: '$abi' directory contains no .so files" >&2
        FAILED=1
        continue
    fi

    for so in "${LIBS[@]}"; do
        base=$(basename "$so")
        dis="$DIS_DIR/$abi-$base.txt"
        hits="$DIS_DIR/$abi-$base.hits"

        # ── armeabi-v7a: attributes, not mnemonics ──────────────────────
        if [ "$abi" = "armeabi-v7a" ]; then
            if ! READOBJ_BIN=$(find_readobj); then
                echo "::error::check_no_sha3_ext: armeabi-v7a present but no ELF attribute reader found (tried \$READOBJ, \$ANDROID_NDK_HOME llvm-readobj/llvm-readelf, rustup llvm-tools, llvm-readobj, llvm-readelf, readelf). The v7 slice was NOT checked." >&2
                FAILED=1
                continue
            fi
            attrs="$DIS_DIR/$abi-$base.attrs"
            rc=0
            case "$(basename "$READOBJ_BIN")" in
                llvm-readobj*) "$READOBJ_BIN" --arch-specific "$so" > "$attrs" 2> "$attrs.err" || rc=$? ;;
                *)             "$READOBJ_BIN" -A "$so" > "$attrs" 2> "$attrs.err" || rc=$? ;;
            esac
            CHECKED=$((CHECKED + 1))
            if [ "$rc" -ne 0 ]; then
                echo "::error::check_no_sha3_ext: $READOBJ_BIN exited $rc on $so — attributes NOT read, library NOT checked." >&2
                head -3 "$attrs.err" >&2 || true
                FAILED=1
                continue
            fi
            v7_attribute_violations "$attrs" > "$hits"
            if [ -s "$hits" ]; then
                echo "::error::$so targets an ISA beyond the armeabi-v7a baseline (ARM v7 / Thumb-2 / VFPv3 / NEONv1). A 32-bit library is not disassembled (stripped v7 objects carry no ARM/Thumb mapping symbols), so the build attributes are the evidence, and they say this object was compiled for something the ABI does not guarantee:" >&2
                sed 's/^/    /' "$hits" >&2
                FAILED=1
            else
                echo "  ok: $abi/$base - .ARM.attributes within the armeabi-v7a baseline (using $(basename "$READOBJ_BIN"))"
            fi
            continue
        fi

        # ── arm64-v8a / x86_64 / x86: disassemble and classify ───────────
        CHECKED=$((CHECKED + 1))
        if ! disassemble "$so" "$dis"; then
            FAILED=1
            continue
        fi

        norm="$DIS_DIR/$abi-$base.norm"
        normalize_dis "$dis" > "$norm"
        if [ ! -s "$norm" ]; then
            echo "::error::check_no_sha3_ext: no instruction could be parsed from the disassembly of $so — the normaliser does not understand this objdump's output format. Nothing was checked." >&2
            FAILED=1
            continue
        fi
        case "$abi" in
            arm64-v8a) arm64_classify "$norm" > "$hits" ;;
            x86_64|x86) x86_classify "$abi" "$norm" > "$hits" ;;
        esac

        # Allowed classes are counted for the log and then removed.
        allowed_summary=$(awk -F'\t' '$1 ~ /_OK$/' "$hits" | summarise_hits | tr '\n' ' ')
        bad="$DIS_DIR/$abi-$base.bad"
        awk -F'\t' '$1 !~ /_OK$/' "$hits" > "$bad"

        # MTE_LIBUNWIND_OK is a one-per-library allowance, not a class.
        mte_ok=$(awk -F'\t' '$1 == "MTE_LIBUNWIND_OK"' "$hits" | wc -l | tr -d ' ')
        if [ "$mte_ok" -gt 1 ]; then
            awk -F'\t' '$1 == "MTE_LIBUNWIND_OK" { $1 = "MTE"; print $1 "\t" $2 "\t" $3 "\t" $4 }' "$hits" >> "$bad"
        fi

        # SHA-NI: exact pinned count in the one library that legitimately has it,
        # and only when the binary consults cpuid at all.
        if [ "$abi" = "x86_64" ] || [ "$abi" = "x86" ]; then
            shani=$(awk -F'\t' '$1 == "SHANI"' "$hits" | wc -l | tr -d ' ')
            cpuid=$(awk -F'\t' '$1 == "CPUID_OK"' "$hits" | wc -l | tr -d ' ')
            if [ "$shani" -gt 0 ]; then
                if [ "$base" = "$SHANI_ALLOWED_LIB" ] && [ "$shani" -eq "$SHANI_ALLOWED_COUNT" ] && [ "$cpuid" -ge 1 ]; then
                    awk -F'\t' '$1 != "SHANI"' "$bad" > "$bad.tmp" && mv "$bad.tmp" "$bad"
                    allowed_summary="$allowed_summary SHANI(cpuid-dispatched,sha2-crate)=$shani"
                elif [ "$base" = "$SHANI_ALLOWED_LIB" ]; then
                    echo "::error::$so carries $shani SHA-NI instruction(s) with $cpuid cpuid site(s); the gate pins exactly $SHANI_ALLOWED_COUNT (the sha2 crate's cpufeatures-dispatched digest_blocks) and at least one cpuid. The count changed, which means the sha2 crate or its dispatch changed. Re-audit that the SHA-NI path is still reached only behind a CPUID check, THEN update SHANI_ALLOWED_COUNT in this script -- do not bump the number to make the build green." >&2
                fi
            fi
        fi

        if [ ! -s "$bad" ]; then
            echo "  ok: $abi/$base - no ungated extended-ISA instructions${allowed_summary:+ (allowed: $allowed_summary)}"
            continue
        fi

        nbad=$(wc -l < "$bad" | tr -d ' ')
        summary=$(summarise_hits "$bad" | tr '\n' ' ')
        if is_go_runtime_lib "$so"; then
            if has_runtime_cpu_detection "$so"; then
                echo "  ok: $abi/$base - $nbad extended-ISA instruction(s) [$summary] but the binary is a Go runtime binary (GO_RUNTIME_LIBS) carrying internal/cpu feature detection, so they are reached only where the feature is present"
            else
                echo "::error::$so contains $nbad extended-ISA instruction(s) [$summary] and NO evidence of Go runtime CPU-feature detection. Either it gained an ungated SIMD path, or it is no longer a Go binary and its GO_RUNTIME_LIBS entry is stale. Both need a human." >&2
                head -5 "$bad" | sed 's/^/    /' >&2
                FAILED=1
            fi
        else
            echo "::error::$so contains $nbad ungated extended-ISA instruction(s) [$summary]. This library is STRICT: nothing in it dispatches on CPU features, so an emitted instruction is an executed one and it WILL SIGILL on any $abi device without the feature. First sites (class, mnemonic, function, address):" >&2
            head -8 "$bad" | awk -F'\t' '{ printf "    %-10s %-12s in %s @ 0x%s\n", $1, $2, $3, $4 }' >&2
            FAILED=1
        fi
    done
done

if [ "$FAILED" -ne 0 ]; then
    echo "::error::check_no_sha3_ext FAILED. If the offender is librosenpass_jni.so on arm64-v8a with eor3/rax1/xar/bcax, the most likely cause is that --cfg keccak_backend=\"soft\" was dropped from native/rosenpass-jni/.cargo/config.toml, or that RUSTFLAGS enabled the sha3 target feature (which ALSO elides cpufeatures' HWCAP check -- that is the dangerous case, not the noisy one). scripts/check_pq_features.sh names the cfg problem; see native/README.md § ISA baseline." >&2
    exit 1
fi

if [ "$CHECKED" -eq 0 ]; then
    echo "::error::check_no_sha3_ext: nothing was actually checked under '$LIB_ROOT'." >&2
    exit 1
fi

echo "check_no_sha3_ext: ${#ABI_DIRS[@]} ABI(s), $CHECKED library(ies) checked and clean"
