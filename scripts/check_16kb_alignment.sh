#!/usr/bin/env bash
# check_16kb_alignment.sh — assert every native .so has 16 KB-aligned LOAD
# segments, as required by Google Play for apps targeting Android 15+ (API 35).
#
# Google Play rejects (or warns and will eventually reject) app bundles whose
# native libraries are not 16 KB page-size compatible. A library is compatible
# when every PT_LOAD program header is aligned to at least 0x4000 (16384) bytes.
#
# Usage: scripts/check_16kb_alignment.sh <dir-containing-.so-files>
# Exit:  0 = all libraries compliant, 1 = at least one non-compliant (or no .so)

set -euo pipefail

DIR="${1:-app/src/main/jniLibs}"
MIN_ALIGN=16384  # 0x4000

# Pick an available ELF reader (llvm-readelf preferred; GNU readelf works too).
READELF=""
for cand in llvm-readelf readelf; do
  if command -v "$cand" >/dev/null 2>&1; then READELF="$cand"; break; fi
done
if [[ -z "$READELF" ]]; then
  echo "ERROR: no readelf/llvm-readelf found on PATH" >&2
  exit 1
fi
echo ">>> using $READELF"

mapfile -t SO_FILES < <(find "$DIR" -type f -name '*.so' | sort)
if [[ ${#SO_FILES[@]} -eq 0 ]]; then
  echo "ERROR: no .so files found under '$DIR'" >&2
  exit 1
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
    echo "  ✗ $so — min LOAD align ${worst_hex} ($worst_dec) < 16384 — NOT 16 KB compatible"
    fail=1
  else
    echo "  ✓ $so — min LOAD align ${worst_hex} ($worst_dec)"
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
echo "OK: all ${#SO_FILES[@]} native libraries are 16 KB page-size compatible."
