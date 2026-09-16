#!/usr/bin/env bash
#
# Fail the build if any PRODUCTION path in the three native crates generates an
# ML-KEM keypair through an API that PANICS when the system RNG fails.
#
# WHY THIS GATE EXISTS
#
# The pqcrypto-mlkem -> ml-kem migration was sold, in part, as removing "an FFI
# panic on RNG failure". That was not true of the first cut of the migration:
#
#   OLD  pqcrypto-internals 0.2.11 src/lib.rs:21
#          getrandom::fill(buf).expect("RNG Failed")
#        doc-commented "may panic over FFI boundary if rng failed".
#
#   NEW  DecapsulationKey::generate() resolves to crypto-common 0.2.2
#        src/generate.rs:42
#          fn generate() { Self::generate_from_rng(&mut UnwrapErr(SysRng)) }
#        under a literal "/// # Panics This method will panic in the event the
#        system's ambient RNG experiences an internal failure."
#
# Both native crates set panic = "abort", so either way it is a process abort:
# no Kotlin exception, no BirdoPqStatus for Swift to read, just a native crash
# in the middle of a connect. The panic had been MOVED, not removed. It is the
# same shape as every other claim in this migration that was checked rather
# than assumed -- so it gets a gate rather than a promise.
#
# THE RULE
#
#   `DecapsulationKey::generate()` must not appear in production code.
#   Use `DecapsulationKey::try_generate()` and map the error to the crate's own
#   error channel (JniErr::Crypto / BirdoPqStatus::Internal).
#
# WHAT THIS DOES *NOT* COVER, SAID OUT LOUD
#
# `Encapsulate::encapsulate()` has the identical ambient-RNG unwrap and is NOT
# checked here, because `kem` 0.3.0 declares no `TryEncapsulate` and the only
# fallible door is `encapsulate_deterministic`, which lives behind ml-kem's
# `hazmat` feature that check_pq_features.sh keeps out of the non-dev
# dependency set. Every call site is server-side or test-only
# (nativeEncapsulateForServer, birdo_pq_test_encapsulate, birdo-pq-server);
# no shipped client path reaches one. If `kem` ever grows a fallible
# encapsulate, add it to FORBIDDEN below.
#
# HOW "PRODUCTION" IS DECIDED
#
# Each file is truncated at its first top-level `#[cfg(test)]`, which in all
# six files is the last item. A test module may call the panicking form freely
# -- a test process that aborts because the RNG died is not a shipped defect.
#
# Usage: scripts/check_pq_rng_panics.sh      (from anywhere in the repo)
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Each entry: <literal>|<what to call instead>
FORBIDDEN=(
    "DecapsulationKey::generate()|DecapsulationKey::try_generate(), mapped to the crate's error type"
    "DecapsulationKey::<MlKem1024>::generate()|DecapsulationKey::try_generate(), mapped to the crate's error type"
)

mapfile -t SOURCES < <(find native -type d -name target -prune -o -type f -name '*.rs' -print | sort)
if [ "${#SOURCES[@]}" -eq 0 ]; then
    echo "::error::check_pq_rng_panics: found no Rust sources under native/ -- a check that found nothing is not a pass." >&2
    exit 1
fi

FAILED=0
SCANNED=0
for src in "${SOURCES[@]}"; do
    # Everything from the first top-level #[cfg(test)] onward is test code.
    prod=$(awk '/^#\[cfg\(test\)\]/ { exit } { print }' "$src")
    [ -n "$prod" ] || continue
    SCANNED=$((SCANNED + 1))
    for entry in "${FORBIDDEN[@]}"; do
        pattern="${entry%%|*}"
        advice="${entry#*|}"
        hits=$(printf '%s\n' "$prod" | grep -Fn -- "$pattern" || true)
        if [ -n "$hits" ]; then
            echo "::error::check_pq_rng_panics: $src calls $pattern in production code. It panics when the system RNG fails, and panic = \"abort\" turns that into a process abort with no error for the caller to handle. Use $advice." >&2
            printf '%s\n' "$hits" | sed "s|^|    $src:|" >&2
            FAILED=1
        fi
    done
done

if [ "$FAILED" -ne 0 ]; then
    exit 1
fi
echo "check_pq_rng_panics: $SCANNED production Rust file(s) scanned, no panicking ambient-RNG keygen"
