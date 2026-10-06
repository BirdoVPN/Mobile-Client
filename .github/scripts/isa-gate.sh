#!/usr/bin/env bash
#
# Run the ISA-baseline gate, scripts/check_no_sha3_ext.sh, over one or more
# native-library roots: ONE invocation per (ABI, library), in parallel.
#
# WHY THIS EXISTS
#
# The gate disassembles every .so it is given, one after the other. Over a
# debug APK that is 36 libraries and about 8 minutes, almost all of it the three
# disassembled libxray.so slices (arm64 ~90 s, x86_64 ~160 s, x86 ~180 s on a
# hosted runner). The release job paid it twice (Play AAB, then sideload APK),
# serially, on a 4-core machine that sat 3/4 idle.
#
# The gate judges every library on its own: the rule table, the Go-runtime
# exemption, the SHA-NI allowance and the MTE/LSE shapes are all per library,
# and nothing it decides about one library depends on another library or on
# another ABI. So running the SAME script with the SAME rule table and the SAME
# disassembler once per library is the same verdict, in the time of the slowest
# library rather than the sum of all of them. The gate itself is unchanged.
#
# WHAT THIS WRAPPER MUST NOT LOSE, AND HOW IT KEEPS IT
#
#   * Every library is checked. Each invocation must print the gate's own
#     "1 ABI(s), 1 library(ies) checked and clean" line; an exit 0 without it
#     is a failure. The number of clean invocations must equal the number of
#     .so files found.
#   * An ABI directory the gate has no rule for, or one with no .so in it, is
#     still handed to the gate, so the gate's OWN error fires. An unknown or
#     empty directory is not a pass here any more than it is there.
#   * A root with no ABI directory at all fails, as it does in the gate.
#   * Every invocation's full output is printed, so every ::error:: the gate
#     emits is still an annotation on the run.
#
# Usage: .github/scripts/isa-gate.sh <lib-root> [<lib-root> ...]
#   A <lib-root> holds <abi>/ directories: an unzipped APK's lib/, an AAB's
#   base/lib/, or app/src/main/jniLibs.
# Env:
#   ANDROID_NDK_HOME  the NDK whose llvm-objdump / llvm-readobj the gate prefers
#   ISA_GATE_JOBS     parallel invocations (default: nproc)
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
GATE="$HERE/../../scripts/check_no_sha3_ext.sh"
if [ ! -f "$GATE" ]; then
    echo "::error::isa-gate: $GATE is missing -- nothing can be checked" >&2
    exit 1
fi
if [ "$#" -lt 1 ]; then
    echo "::error::usage: isa-gate.sh <lib-root> [<lib-root> ...]" >&2
    exit 1
fi

# Same fallback the workflows use when the image names only the versioned NDK.
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}"
export ANDROID_NDK_HOME

# The gate's FIRST choice of tools is the NDK's (find_in_ndk). Resolve them
# once here and hand them down, rather than have each of ~36 invocations walk
# the whole NDK tree with find. When there is no NDK they stay empty and the
# gate falls back through its own list exactly as before.
find_ndk_tool() {
    [ -n "${ANDROID_NDK_HOME:-}" ] || return 0
    find "$ANDROID_NDK_HOME" \( -name "$1" -o -name "$1.exe" \) -type f 2>/dev/null | head -1 || true
}
OBJDUMP="${OBJDUMP:-$(find_ndk_tool llvm-objdump)}"
READOBJ="${READOBJ:-$(find_ndk_tool llvm-readobj)}"
export GATE OBJDUMP READOBJ

WORK="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/isa-gate.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
UNITS="$WORK/units.tsv"
: > "$UNITS"

n=0      # invocations
libs=0   # .so files found
for root in "$@"; do
    if [ ! -d "$root" ]; then
        echo "::error::isa-gate: '$root' is not a directory -- nothing was checked" >&2
        exit 1
    fi
    abis=0
    while IFS= read -r -d '' abi_dir; do
        abis=$((abis + 1))
        abi="$(basename "$abi_dir")"
        found=0
        while IFS= read -r -d '' so; do
            found=$((found + 1))
            libs=$((libs + 1))
            n=$((n + 1))
            mkdir -p "$WORK/$n/$abi"
            # A hard link is the same bytes; copy only across filesystems.
            ln "$so" "$WORK/$n/$abi/" 2>/dev/null || cp "$so" "$WORK/$n/$abi/"
            printf '%s\t%s\t%s\n' "$n" "$root" "$abi/$(basename "$so")" >> "$UNITS"
        done < <(find "$abi_dir" -name '*.so' -type f -print0 | sort -z)
        if [ "$found" -eq 0 ]; then
            # Hand the gate the empty directory so that ITS error fires.
            n=$((n + 1))
            mkdir -p "$WORK/$n/$abi"
            printf '%s\t%s\t%s\n' "$n" "$root" "$abi/ (no .so files)" >> "$UNITS"
        fi
    done < <(find "$root" -mindepth 1 -maxdepth 1 -type d -print0 | sort -z)
    if [ "$abis" -eq 0 ]; then
        echo "::error::isa-gate: no ABI directories under '$root' -- nothing was checked" >&2
        exit 1
    fi
done

JOBS="${ISA_GATE_JOBS:-$(nproc 2>/dev/null || echo 2)}"
echo "isa-gate: $libs librar(y/ies) in $# root(s); $n gate invocation(s), $JOBS at a time"
echo "isa-gate: objdump=${OBJDUMP:-<gate resolves>} readobj=${READOBJ:-<gate resolves>}"

# Each invocation records its own exit code; xargs is never asked to judge.
# shellcheck disable=SC2016  # the quoted script is expanded by the child bash, on purpose
for i in $(seq 1 "$n"); do printf '%s\0' "$WORK/$i"; done \
    | xargs -0 -n 1 -P "$JOBS" bash -c \
        'rc=0; bash "$GATE" "$1" > "$1.log" 2>&1 || rc=$?; printf "%s\n" "$rc" > "$1.rc"' _

failed=0
clean=0
while IFS=$'\t' read -r i root what; do
    rc="$(cat "$WORK/$i.rc" 2>/dev/null || echo 'never ran')"
    echo "--- $root :: $what (exit $rc)"
    cat "$WORK/$i.log" 2>/dev/null || true
    if [ "$rc" != "0" ]; then
        failed=$((failed + 1))
        continue
    fi
    if ! grep -qxF 'check_no_sha3_ext: 1 ABI(s), 1 library(ies) checked and clean' "$WORK/$i.log"; then
        echo "::error::isa-gate: $root :: $what exited 0 without the gate's own 'checked and clean' line -- treated as NOT checked" >&2
        failed=$((failed + 1))
        continue
    fi
    clean=$((clean + 1))
done < "$UNITS"

if [ "$failed" -ne 0 ]; then
    echo "::error::isa-gate: $failed of $n gate invocation(s) failed -- see the ::error:: lines above, each from scripts/check_no_sha3_ext.sh" >&2
    exit 1
fi
if [ "$clean" -ne "$libs" ] || [ "$clean" -eq 0 ]; then
    echo "::error::isa-gate: $clean clean invocation(s) for $libs librar(y/ies) found -- every library must be checked exactly once" >&2
    exit 1
fi
echo "isa-gate: all $clean librar(y/ies) checked and clean, each by its own run of scripts/check_no_sha3_ext.sh (${SECONDS}s)"
