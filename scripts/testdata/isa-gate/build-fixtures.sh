#!/usr/bin/env bash
#
# Rebuild the hand-assembled fixtures scripts/tests/check_no_sha3_ext_test.sh
# runs the ISA gate against. The .so files next to this script are COMMITTED so
# the test needs no cross toolchain; run this only when a fixture changes.
#
# Toolchain: GNU binutils cross assemblers, as packaged on Ubuntu 24.04
# (binutils-aarch64-linux-gnu, binutils-arm-linux-gnueabihf, binutils). The
# fixtures are never executed -- they only need to disassemble and carry the
# right .ARM.attributes -- so no libc, no NDK and no linking against anything.
#
#   docker run --rm -v "$PWD:/w" ubuntu:24.04 bash -c \
#     'apt-get update -q && apt-get install -yq --no-install-recommends \
#        binutils binutils-aarch64-linux-gnu binutils-arm-linux-gnueabihf \
#      && /w/scripts/testdata/isa-gate/build-fixtures.sh'
#
# Fails loud on a missing tool: a fixture that silently did not rebuild is a
# test that silently tests the previous fixture.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

for t in aarch64-linux-gnu-as aarch64-linux-gnu-ld aarch64-linux-gnu-strip \
         arm-linux-gnueabihf-as arm-linux-gnueabihf-ld as ld; do
    command -v "$t" >/dev/null 2>&1 || { echo "ERROR: $t not found -- see the header of this script" >&2; exit 1; }
done

# Small pages and no separate code segment: keeps each fixture ~5 KB instead
# of 64 KB of alignment padding. Irrelevant to what the gate looks at.
LD=(-shared -z max-page-size=4096 -z noseparate-code)
A=aarch64-linux-gnu
V=arm-linux-gnueabihf
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT

# arm64: the four FEAT_SHA3 opcodes of the 1.4.25 crash.
$A-as -march=armv8.2-a+sha3 -o "$tmp/o" arm64_sha3.s;         $A-ld "${LD[@]}" -o arm64-sha3.so "$tmp/o"
# arm64: LSE only inside compiler-rt-shaped outlined helpers, STRIPPED so the
# gate has to recognise the shape rather than the symbol name.
$A-as -march=armv8.5-a -o "$tmp/o" arm64_lse_outlined.s;      $A-ld "${LD[@]}" -o arm64-lse-outlined.so "$tmp/o"; $A-strip --strip-all arm64-lse-outlined.so
# arm64: the same LSE opcodes inline, no guard (what +lse rustflags emit).
$A-as -march=armv8.1-a -o "$tmp/o" arm64_lse_inline.s;        $A-ld "${LD[@]}" -o arm64-lse-inline.so "$tmp/o"
# armeabi-v7a: identical NEON source, once with v7-A/NEONv1 attributes (what
# 1.4.28 ships) and once with -march=armv8-a (the H5 mutation).
$V-as -march=armv7-a -mfpu=neon -o "$tmp/o" armv7_neon.s;     $V-ld "${LD[@]}" -o armv7-neon.so "$tmp/o"
$V-as -march=armv8-a -mfpu=neon-fp-armv8 -o "$tmp/o" armv7_neon.s; $V-ld "${LD[@]}" -o armv7-v8-attrs.so "$tmp/o"
# x86_64: what pqcrypto-mlkem's avx2 feature emits; and the sha2 crate's
# exact SHA-NI count behind a cpuid.
as --64 -o "$tmp/o" x86_64_avx2.s;                            ld "${LD[@]}" -o x86_64-avx2.so "$tmp/o"
as --64 -o "$tmp/o" x86_64_shani34.s;                         ld "${LD[@]}" -o x86_64-shani34.so "$tmp/o"
# x86: POPCNT, outside the 32-bit baseline.
as --32 -o "$tmp/o" x86_popcnt.s;                             ld -m elf_i386 "${LD[@]}" -o x86-popcnt.so "$tmp/o"

ls -l ./*.so
