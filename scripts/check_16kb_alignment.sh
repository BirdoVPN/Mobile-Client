#!/usr/bin/env bash
# check_16kb_alignment.sh — assert every 64-bit native .so has 16 KB-aligned
# LOAD segments, as required by Google Play for apps targeting Android 15+
# (API 35).
#
# Google Play rejects (or warns and will eventually reject) app bundles whose
# native libraries are not 16 KB page-size compatible. A library is compatible
# when every PT_LOAD program header is aligned to at least 0x4000 (16384) bytes.
#
# WHY 32-BIT ABIs ARE EXCLUDED
#
# The 16 KB page size is a property of 64-bit Android only. Google's requirement
# is scoped to 64-bit devices; 32-bit Android runs 4 KB pages and always will, so
# there is nothing for a 32-bit library to be compatible with, and the toolchains
# reflect that:
#
#   * the NDK's lld emits max-page-size 16384 for arm64/x86_64 and 4096 for
#     armeabi-v7a/x86;
#   * Go does the same — an android/arm or android/386 build lands at 0x1000;
#   * the upstream wireguard-android AAR ships libwg-go.so at 0x4000 for its two
#     64-bit ABIs and 0x1000 for its two 32-bit ones.
#
# So a flat "every .so" rule does not find a real defect on 32-bit; it just makes
# shipping armeabi-v7a or x86 impossible, by failing on prebuilt dependencies
# nobody in this repo can rebuild. The scope below is the requirement's actual
# scope, not a waiver of it — and a 64-bit .so still fails, loudly, as before.
#
# Usage: scripts/check_16kb_alignment.sh <dir-containing-.so-files>
# Exit:  0 = all 64-bit libraries compliant, 1 = at least one non-compliant

set -euo pipefail

DIR="${1:-app/src/main/jniLibs}"
MIN_ALIGN=16384  # 0x4000

# Pick an available ELF reader (llvm-readelf preferred; GNU readelf works too).
#
# $ANDROID_NDK_HOME is searched FIRST, the way scripts/check_no_sha3_ext.sh
# already does. Neither MSYS/Git-Bash nor a stock Windows install carries a
# readelf, so without this the gate is unrunnable on a developer machine and
# only ever fails in CI -- exactly the situation the sibling gate calls out.
# The `-name 'llvm-readelf*'` form matches llvm-readelf.exe as well.
READELF=""
if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
  cand=$(find "$ANDROID_NDK_HOME" \( -name 'llvm-readelf' -o -name 'llvm-readelf.exe' \) -type f 2>/dev/null | head -1 || true)
  [[ -n "$cand" ]] && READELF="$cand"
fi
if [[ -z "$READELF" ]]; then
  for cand in llvm-readelf readelf; do
    if command -v "$cand" >/dev/null 2>&1; then READELF="$cand"; break; fi
  done
fi
if [[ -z "$READELF" ]]; then
  echo "ERROR: no readelf/llvm-readelf found on PATH" >&2
  exit 1
fi
echo ">>> using $READELF"

# 64-bit ABIs only — see the header. Matched on the path component so this works
# whether $DIR is a jniLibs tree or an unpacked AAB's base/lib.
mapfile -t SO_FILES < <(find "$DIR" -type f -name '*.so' \
  \( -path '*/arm64-v8a/*' -o -path '*/x86_64/*' \) | sort)
mapfile -t SKIPPED < <(find "$DIR" -type f -name '*.so' \
  ! \( -path '*/arm64-v8a/*' -o -path '*/x86_64/*' \) | sort)

if [[ ${#SO_FILES[@]} -eq 0 ]]; then
  # Never "pass by finding nothing": if the tree held only 32-bit libraries the
  # 64-bit ones are missing, which is a packaging failure, not a clean run.
  echo "ERROR: no 64-bit (.arm64-v8a/x86_64) .so files found under '$DIR'" >&2
  if [[ ${#SKIPPED[@]} -gt 0 ]]; then
    echo "       (${#SKIPPED[@]} 32-bit .so file(s) were present — the 64-bit ABIs did not get packaged)" >&2
  fi
  exit 1
fi

if [[ ${#SKIPPED[@]} -gt 0 ]]; then
  echo ">>> skipping ${#SKIPPED[@]} 32-bit library(ies) — 16 KB pages are a 64-bit-only requirement"
fi

fail=0
for so in "${SO_FILES[@]}"; do
  # Collect the alignment (last column) of every LOAD program header.
  # readelf -lW prints e.g.:  LOAD 0x0 0x0 0x0 0x1234 0x1234 R E 0x10000
  worst_dec=-1
  worst_hex=""
  while read -r align; do
    [[ -z "$align" ]] && continue
    # align is hex like 0x10000; normalise to decimal.
    dec=$(( align ))
    if [[ $worst_dec -eq -1 || $dec -lt $worst_dec ]]; then
      worst_dec=$dec
      worst_hex=$align
    fi
  done < <("$READELF" -lW "$so" 2>/dev/null | awk '$1 == "LOAD" { print $NF }')

  if [[ $worst_dec -eq -1 ]]; then
    echo "  ?? $so — no LOAD segments found (unexpected)"
    fail=1
    continue
  fi

  if [[ $worst_dec -lt $MIN_ALIGN ]]; then
    echo "[FAIL] $so — min LOAD align ${worst_hex} ($worst_dec) < 16384 — NOT 16 KB compatible"
    fail=1
  else
    echo "[OK] $so — min LOAD align ${worst_hex} ($worst_dec)"
  fi
done

if [[ $fail -ne 0 ]]; then
  echo ""
  echo "FAIL: one or more native libraries are not 16 KB page-size compatible." >&2
  echo "Rebuild the offending library with a 16 KB max-page-size (NDK r27+, or" >&2
  echo "linker flag -Wl,-z,max-page-size=16384), or update the prebuilt binary." >&2
  exit 1
fi

echo ""
echo "OK: all ${#SO_FILES[@]} 64-bit native libraries are 16 KB page-size compatible."
