#!/usr/bin/env bash
#
# Assert the RESOLVED Cargo feature set of pqcrypto-mlkem in every native crate
# is exactly {std} -- no `neon`, no `avx2`, no `default`.
#
# WHY A FEATURE CHECK WHEN THERE IS ALREADY A DISASSEMBLY GATE
#
# scripts/check_no_sha3_ext.sh catches the SYMPTOM (an ungated eor3 in the
# linked .so) after a full cargo-ndk cross-compile. This catches the MECHANISM
# before anything is built: pqcrypto-mlkem's `neon` feature is what compiles
# PQClean's AArch64 ML-KEM behind a literal `if true`, and Cargo feature
# unification means ANY crate in the graph that depends on pqcrypto-mlkem with
# default features turns it back on for everyone -- the line in our Cargo.toml
# would still read `default-features = false` and still be lying. `cargo tree
# -e features -i pqcrypto-mlkem` prints the features that are actually enabled
# after unification and who enabled them, which is the only view that cannot be
# fooled that way. It runs in seconds and needs no NDK.
#
# Two layers, deliberately:
#   1. the resolved feature set per crate and target (the truth), and
#   2. a textual guard that every `pqcrypto-mlkem` dependency line under
#      native/**/Cargo.toml says `default-features = false` (the intent) -- so
#      the crate that is not built or locked today (birdo-pq-server) cannot
#      quietly become the twin that re-enables the feature tomorrow.
#
# 1.4.25 shipped with `pqcrypto-mlkem = "0.1"` (defaults on) and crashed with
# SIGILL on every arm64 device without FEAT_SHA3. Fixed in #353; this script
# is one of the controls that keep it fixed.
#
# Usage: scripts/check_pq_features.sh          (from anywhere in the repo)
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

EXPECTED_FEATURES="std"

# crate directory -> the targets whose resolution to check. Cargo resolves
# features per target (target-specific dependency tables can add features), so
# every shipped target is asked, not just the host.
CRATES=(
    "native/rosenpass-jni|aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android"
    "native/birdo-pq-server|aarch64-linux-android x86_64-unknown-linux-gnu aarch64-unknown-linux-gnu"
    "native/birdo-pq-ios|aarch64-apple-ios"
)

if ! command -v cargo >/dev/null 2>&1; then
    echo "::error::check_pq_features: cargo not on PATH -- the feature set was NOT checked. This is a failure, not a skip." >&2
    exit 1
fi

FAILED=0

# ── Layer 1: resolved features ──────────────────────────────────────────────
for entry in "${CRATES[@]}"; do
    crate="${entry%%|*}"
    targets="${entry#*|}"
    manifest="$crate/Cargo.toml"
    if [ ! -f "$manifest" ]; then
        echo "::error::check_pq_features: $manifest not found (the crate table in this script is stale)" >&2
        FAILED=1
        continue
    fi
    for target in $targets; do
        # --locked: Cargo.lock is part of what ships; a lockfile that would be
        # rewritten by this query is itself a finding.
        out=$(cargo tree --locked -e features -i pqcrypto-mlkem --target "$target" \
                  --manifest-path "$manifest" --prefix none 2>&1) || {
            echo "::error::check_pq_features: cargo tree failed for $crate ($target):" >&2
            printf '%s\n' "$out" | sed 's/^/    /' >&2
            FAILED=1
            continue
        }
        # Lines look like: pqcrypto-mlkem feature "std"   (possibly with a
        # trailing "(*)" de-dup marker). Collect the distinct feature names.
        features=$(printf '%s\n' "$out" | sed -nE 's/^pqcrypto-mlkem feature "([^"]+)".*$/\1/p' | sort -u | tr '\n' ' ' | sed 's/ $//')
        if ! printf '%s\n' "$out" | grep -qE '^pqcrypto-mlkem v'; then
            echo "::error::check_pq_features: $crate ($target): pqcrypto-mlkem is not in the dependency graph at all. Either the KEM was removed (update this script) or the query is broken; a check that found nothing is not a pass." >&2
            FAILED=1
            continue
        fi
        if [ "$features" = "$EXPECTED_FEATURES" ]; then
            echo "  ok: $crate ($target): pqcrypto-mlkem features = {$features}"
        else
            echo "::error::$crate ($target): pqcrypto-mlkem resolves to features {$features}, expected exactly {$EXPECTED_FEATURES}. 'neon' compiles PQClean's AArch64 ML-KEM with 64 FEAT_SHA3 instructions and NO runtime CPU check (SIGILL on every arm64 device without SHA3 -- the 1.4.25 crash); 'avx2' is only safe because upstream happens to wrap it in is_x86_feature_detected!. Whoever enables it is shown below:" >&2
            printf '%s\n' "$out" | sed 's/^/    /' >&2
            FAILED=1
        fi
    done
done

# ── Layer 2: every dependency line says what it means ──────────────────────
#
# Inline-table form only (`pqcrypto-mlkem = { version = ..., default-features
# = false, ... }`). A `[dependencies.pqcrypto-mlkem]` table is rejected rather
# than parsed: the point of this layer is that a reviewer can see the whole
# contract on one line.
mapfile -t MANIFESTS < <(find native -name Cargo.toml -not -path '*/target/*' | sort)
if [ "${#MANIFESTS[@]}" -eq 0 ]; then
    echo "::error::check_pq_features: no Cargo.toml under native/ -- nothing to guard" >&2
    exit 1
fi
for m in "${MANIFESTS[@]}"; do
    if grep -qE '^\[.*dependencies\.pqcrypto-mlkem\]' "$m"; then
        echo "::error::$m declares pqcrypto-mlkem as a dependency table; use the inline form with default-features = false so the guard (and the reviewer) can read it on one line." >&2
        FAILED=1
    fi
    while IFS= read -r line; do
        [ -n "$line" ] || continue
        if printf '%s' "$line" | grep -qE 'default-features[[:space:]]*=[[:space:]]*false'; then
            echo "  ok: $m: $line"
        else
            echo "::error::$m: pqcrypto-mlkem dependency line lacks 'default-features = false': $line" >&2
            FAILED=1
        fi
    done < <(grep -E '^[[:space:]]*pqcrypto-mlkem[[:space:]]*=' "$m" || true)
done

if [ "$FAILED" -ne 0 ]; then
    echo "::error::check_pq_features FAILED. See native/README.md § ISA baseline for why CLEAN-only is deliberate and what re-enabling an optimised path would require." >&2
    exit 1
fi
echo "check_pq_features: pqcrypto-mlkem resolves to {$EXPECTED_FEATURES} in every native crate and target"
