#!/usr/bin/env bash
#
# Assert the PQ KEM dependency of every native crate is EXACTLY what was
# reviewed: RustCrypto `ml-kem`, exact-pinned, with exactly the features that
# were argued for -- and that `pqcrypto-*` is gone from every graph.
#
# WHY A DEPENDENCY CHECK WHEN THERE IS ALREADY A DISASSEMBLY GATE
#
# scripts/check_no_sha3_ext.sh catches the SYMPTOM (an ungated extension opcode
# in a shipped .so) after a full cargo-ndk cross-compile. This catches the
# MECHANISM before anything is built, in seconds, with no NDK.
#
# WHAT CHANGED IN 2026-09, AND WHY THIS SCRIPT WAS REWRITTEN RATHER THAN DELETED
#
# Until 1.4.29 the KEM was `pqcrypto-mlkem` (PQClean CLEAN C) and this script
# asserted its resolved feature set was exactly {std} -- because `neon` is what
# compiled PQClean's AArch64 ML-KEM behind a literal `if true` and shipped the
# 1.4.25 SIGILL. The KEM is now `ml-kem`, so the old assertion could not pass;
# and its old failure mode was the right one to keep: it treated "the crate is
# not in the graph at all" as a FAILURE, on the principle that a check which
# found nothing is not a pass. That principle survives here. Every assertion
# below fails loudly when the thing it is looking for is missing.
#
# The other reason it had to be rewritten rather than retuned: the Keccak
# backend selector is a `--cfg`, not a Cargo feature, so `cargo tree` cannot
# see it at all. Layer 3 greps for it directly.
#
# WHAT THIS ASSERTS
#
#   1. `pqcrypto-mlkem`, `pqcrypto-traits` and `pqcrypto-internals` are ABSENT
#      from every native crate's graph, on every shipped target. They are
#      unmaintained by advisory (RUSTSEC-2026-0161/-0162/-0163, 2026-06-04) and
#      PQClean upstream has been archived read-only since 2026-08-04.
#   2. `ml-kem` resolves to EXACTLY the pinned version, with EXACTLY the
#      expected features outside dev-dependencies -- and `hazmat` ONLY inside
#      them, because `encapsulate_deterministic` must never be reachable from a
#      shipped build.
#   3. `keccak` and `cpufeatures` are present on aarch64. That is not a
#      formality: `cpufeatures` IS the runtime FEAT_SHA3 gate that PQClean's
#      aarch64 path lacked, and a graph without it would mean the Keccak
#      dispatch had been replaced by something unexamined.
#   4. The soft-Keccak `--cfg` lives in native/rosenpass-jni/.cargo/config.toml
#      and NOWHERE else. app/build.gradle.kts declares that file as a
#      buildRustLibs input; the same cfg set in a workflow `RUSTFLAGS` or a
#      shell env var would not invalidate the Gradle task and could ship a
#      stale UP-TO-DATE .so built without it.
#   5. Every `ml-kem` dependency line under native/**/Cargo.toml is the exact
#      pin, in inline form, with `default-features = false` -- so the whole
#      contract is readable on one line and a twin crate cannot drift.
#
# Usage: scripts/check_pq_features.sh          (from anywhere in the repo)
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# The exact pin. ml-kem is pre-1.0 with a changelog that permits MSRV bumps in
# patch releases, and its FIPS 203 input validations did not exist before April
# 2026. Every bump is a crypto change: re-run the KAT (`kat_vs_noble`), the
# install-base guard (`stored_pqclean_dk_loads_and_derives_same_psk`) and the
# ISA gate before touching this.
ML_KEM_VERSION="0.3.2"
EXPECTED_FEATURES="alloc getrandom zeroize"
DEV_ONLY_FEATURE="hazmat"
FORBIDDEN_CRATES=(pqcrypto-mlkem pqcrypto-traits pqcrypto-internals)
KECCAK_CFG='keccak_backend="soft"'
CARGO_CONFIG="native/rosenpass-jni/.cargo/config.toml"

# crate directory -> the targets whose resolution to check. Cargo resolves
# features per target (target-specific dependency tables can add features), so
# every shipped target is asked, not just the host.
CRATES=(
    "native/rosenpass-jni|aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android"
    "native/birdo-pq-server|aarch64-linux-android x86_64-unknown-linux-gnu aarch64-unknown-linux-gnu"
    "native/birdo-pq-ios|aarch64-apple-ios"
)

if ! command -v cargo >/dev/null 2>&1; then
    echo "::error::check_pq_features: cargo not on PATH -- the dependency graph was NOT checked. This is a failure, not a skip." >&2
    exit 1
fi

FAILED=0

# --locked: Cargo.lock is part of what ships; a lockfile that would be
# rewritten by any query here is itself a finding.
tree() {
    cargo tree --locked --prefix none "$@" 2>&1
}

# ── Layer 1: the resolved graph, per crate and per target ──────────────────
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
        if ! graph=$(tree -e no-dev --target "$target" --manifest-path "$manifest"); then
            echo "::error::check_pq_features: cargo tree failed for $crate ($target):" >&2
            printf '%s\n' "$graph" | sed 's/^/    /' >&2
            FAILED=1
            continue
        fi

        # 1a. No pqcrypto anywhere, including dev-dependencies (checked
        #     separately below with the dev edges on).
        for dead in "${FORBIDDEN_CRATES[@]}"; do
            if printf '%s\n' "$graph" | grep -qE "^${dead} v"; then
                echo "::error::$crate ($target): $dead is back in the dependency graph. It is unmaintained by advisory (RUSTSEC-2026-0161/-0162/-0163) and PQClean upstream is archived read-only. The KEM is ml-kem; see the decision record in native/rosenpass-jni/Cargo.toml." >&2
                FAILED=1
            fi
        done

        # 1b. ml-kem present, at exactly the pinned version.
        resolved=$(printf '%s\n' "$graph" | sed -nE 's/^ml-kem v([0-9][^ ]*).*$/\1/p' | sort -u)
        if [ -z "$resolved" ]; then
            echo "::error::$crate ($target): ml-kem is not in the dependency graph at all. Either the KEM was replaced (update this script AND the decision record) or the query is broken; a check that found nothing is not a pass." >&2
            FAILED=1
            continue
        fi
        if [ "$resolved" != "$ML_KEM_VERSION" ]; then
            echo "::error::$crate ($target): ml-kem resolves to $resolved, expected exactly $ML_KEM_VERSION. ml-kem is pre-1.0 and permits MSRV bumps in patch releases; every bump is a crypto change and must re-run the KAT and the ISA gate, not just CI." >&2
            FAILED=1
        fi

        # 1c. Exactly the expected features, outside dev-dependencies.
        if ! feat_out=$(tree -e features,no-dev -i ml-kem --target "$target" --manifest-path "$manifest"); then
            echo "::error::check_pq_features: cargo tree -e features failed for $crate ($target)" >&2
            FAILED=1
            continue
        fi
        features=$(printf '%s\n' "$feat_out" | sed -nE 's/^ml-kem feature "([^"]+)".*$/\1/p' | sort -u | tr '\n' ' ' | sed 's/ $//')
        if [ "$features" = "$EXPECTED_FEATURES" ]; then
            echo "  ok: $crate ($target): ml-kem v$resolved, features = {$features}"
        else
            echo "::error::$crate ($target): ml-kem resolves to features {$features}, expected exactly {$EXPECTED_FEATURES}. 'zeroize' is NOT a default (ml-kem's default is [\"alloc\"] only) and without it DecapsulationKey's ZeroizeOnDrop is cfg'd out and key material is never wiped. '$DEV_ONLY_FEATURE' must never be enabled outside dev-dependencies. Whoever enabled what is shown below:" >&2
            printf '%s\n' "$feat_out" | sed 's/^/    /' >&2
            FAILED=1
        fi

        # 1d. hazmat must be reachable in TEST builds (the KAT needs
        #     encapsulate_deterministic to reproduce the server's ciphertext)
        #     and unreachable otherwise. Both directions, or the assertion is
        #     half a check.
        if printf '%s\n' "$feat_out" | grep -qE "^ml-kem feature \"$DEV_ONLY_FEATURE\""; then
            echo "::error::$crate ($target): ml-kem feature '$DEV_ONLY_FEATURE' is enabled in a NON-dev build. encapsulate_deterministic must not be reachable from a shipped library." >&2
            FAILED=1
        fi
        if dev_out=$(tree -e features -i ml-kem --target "$target" --manifest-path "$manifest"); then
            if ! printf '%s\n' "$dev_out" | grep -qE "^ml-kem feature \"$DEV_ONLY_FEATURE\""; then
                echo "::error::$crate ($target): ml-kem feature '$DEV_ONLY_FEATURE' is not enabled for test builds either, so kat_vs_noble cannot reproduce the server's ciphertext. Add it to [dev-dependencies]." >&2
                FAILED=1
            fi
        fi

        # 1e. On aarch64, the Keccak runtime gate must be in the graph. This is
        #     the thing PQClean's aarch64 path did not have.
        case "$target" in
        aarch64-*)
            for needed in keccak cpufeatures; do
                if ! printf '%s\n' "$graph" | grep -qE "^${needed} v"; then
                    echo "::error::$crate ($target): $needed is missing from the aarch64 graph. cpufeatures is the getauxval(AT_HWCAP)/sysctl runtime check that gates keccak's FEAT_SHA3 backend; a graph without it means the Keccak dispatch has been replaced by something nobody reviewed." >&2
                    FAILED=1
                fi
            done
            ;;
        esac
    done
done

# ── Layer 2: every dependency line says what it means ──────────────────────
#
# Inline-table form only. A `[dependencies.ml-kem]` table is rejected rather
# than parsed: the point of this layer is that a reviewer can see the whole
# contract on one line.
mapfile -t MANIFESTS < <(find native -name Cargo.toml -not -path '*/target/*' | sort)
if [ "${#MANIFESTS[@]}" -eq 0 ]; then
    echo "::error::check_pq_features: no Cargo.toml under native/ -- nothing to guard" >&2
    exit 1
fi
for m in "${MANIFESTS[@]}"; do
    for dead in "${FORBIDDEN_CRATES[@]}"; do
        if grep -qE "^[[:space:]]*${dead}[[:space:]]*=|^\[.*dependencies\.${dead}\]" "$m"; then
            echo "::error::$m still declares $dead. Remove it; the KEM is ml-kem." >&2
            FAILED=1
        fi
    done
    if grep -qE '^\[.*dependencies\.ml-kem\]' "$m"; then
        echo "::error::$m declares ml-kem as a dependency table; use the inline form so the guard (and the reviewer) can read the whole contract on one line." >&2
        FAILED=1
    fi
    found=0
    while IFS= read -r line; do
        [ -n "$line" ] || continue
        found=1
        ok=1
        printf '%s' "$line" | grep -qE "version[[:space:]]*=[[:space:]]*\"=${ML_KEM_VERSION}\"" || ok=0
        printf '%s' "$line" | grep -qE 'default-features[[:space:]]*=[[:space:]]*false' || ok=0
        if [ "$ok" -eq 1 ]; then
            echo "  ok: $m: $line"
        else
            echo "::error::$m: ml-kem dependency line must pin version = \"=${ML_KEM_VERSION}\" exactly AND set default-features = false: $line" >&2
            FAILED=1
        fi
    done < <(grep -E '^[[:space:]]*ml-kem[[:space:]]*=' "$m" || true)
    if [ "$found" -eq 0 ]; then
        echo "::error::$m declares no ml-kem dependency. Every native crate is a twin of the other two; a twin left on a different KEM is exactly how drift survives." >&2
        FAILED=1
    fi
done

# ── Layer 3: the Keccak backend cfg, which features cannot see ─────────────
#
# `--cfg keccak_backend="soft"` is a RUSTFLAGS cfg, not a Cargo feature, so
# nothing above can observe it. It must live in .cargo/config.toml (a declared
# buildRustLibs input) and nowhere else, or Gradle can ship a stale .so.
if [ ! -f "$CARGO_CONFIG" ]; then
    echo "::error::check_pq_features: $CARGO_CONFIG is missing; that is where the soft-Keccak cfg and the 16 KB page alignment live." >&2
    FAILED=1
else
    android_targets=$(grep -cE '^\[target\.[a-z0-9_]+-linux-android(eabi)?\]' "$CARGO_CONFIG" || true)
    # Count only real rustflags lines -- the file's own explanatory comment
    # names the cfg too, and a comment is not a build flag.
    cfg_sites=$(grep -cE "^rustflags[[:space:]]*=.*$KECCAK_CFG" "$CARGO_CONFIG" || true)
    if [ "$android_targets" -eq 0 ] || [ "$cfg_sites" -ne "$android_targets" ]; then
        echo "::error::$CARGO_CONFIG: found $cfg_sites occurrence(s) of --cfg $KECCAK_CFG for $android_targets Android target section(s). EVERY shipped ABI must set it, or that ABI links keccak's aarch64 FEAT_SHA3 backend (64 instructions: eor3/rax1/xar/bcax) and scripts/check_no_sha3_ext.sh fails the build." >&2
        FAILED=1
    else
        echo "  ok: $CARGO_CONFIG: --cfg $KECCAK_CFG set for all $android_targets Android targets"
    fi
fi

# Anywhere else is a trap, not a belt-and-braces: a cfg outside the declared
# Gradle input can go stale silently.
#
# Comment lines are exempt -- several files explain the flag, and explaining it
# is the opposite of the problem. What is forbidden is SETTING it: a line that
# is not a comment and carries the cfg value.
STRAY=$(grep -rnF "$KECCAK_CFG" --include='*.yml' --include='*.yaml' --include='*.sh' --include='*.ps1' --include='*.kts' --include='*.gradle' --include='*.toml' . 2>/dev/null     | grep -vE '^\./(scripts/check_pq_features\.sh|native/rosenpass-jni/\.cargo/config\.toml):'     | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(#|//)' || true)
if [ -n "$STRAY" ]; then
    echo "::error::the soft-Keccak cfg also appears outside $CARGO_CONFIG, which app/build.gradle.kts does NOT treat as a buildRustLibs input. A cfg set there can ship a stale UP-TO-DATE .so built without it:" >&2
    printf '%s\n' "$STRAY" | sed 's/^/    /' >&2
    FAILED=1
fi

# -- Layer 4: RUSTFLAGS is not allowed to exist ----------------------------
#
# `RUSTFLAGS` as an ENVIRONMENT VARIABLE **REPLACES** `target.<triple>.rustflags`
# from .cargo/config.toml -- it does not append to it. So a single stray
# `export RUSTFLAGS=...` anywhere in a build path silently drops BOTH flags
# this repo relies on, in one step:
#
#   --cfg keccak_backend="soft"            the shipped .so links keccak's
#                                          aarch64 FEAT_SHA3 backend (64
#                                          instructions), the 1.4.25 crash
#                                          class. check_no_sha3_ext.sh on the
#                                          extracted APK libs catches this.
#
#   -C link-arg=-Wl,-z,max-page-size=16384 the shipped .so loses 16 KB page
#                                          alignment. Google Play REQUIRES it
#                                          for apps targeting API 35+, so this
#                                          one is a store rejection rather
#                                          than a crash.
#                                          verify-16kb-alignment catches it.
#
# Both downstream gates exist and both run on the extracted libraries, so this
# layer is not the only thing between a stray export and a bad artifact. It is
# here because the downstream failures are far from the cause -- one reads as
# a SIGILL risk, the other as a Play upload refusal -- and because the
# ALIGNMENT half was named only in an android.yml comment, sitting on the one
# step that sets RUSTFLAGS on purpose, which is the last place someone adding
# a new one would look.
#
# The rule: no build path may assign RUSTFLAGS unless the line carries
# RUSTFLAGS-NEGATIVE-CASE-OK. The two sites that do are deliberate negative
# tests that build a library specifically to prove a gate rejects it; neither
# ships anything.
RUSTFLAGS_MARKER="RUSTFLAGS-NEGATIVE-CASE-OK"
RUSTFLAGS_HITS=$(grep -rnE '(^|[^A-Za-z0-9_])RUSTFLAGS[[:space:]]*[:=]' \
    --include='*.yml' --include='*.yaml' --include='*.sh' --include='*.ps1' \
    --include='*.kts' --include='*.gradle' --include='*.toml' . 2>/dev/null \
    | grep -vE '^[.]/scripts/check_pq_features[.]sh:' \
    | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(#|//)' \
    | grep -vF "$RUSTFLAGS_MARKER" || true)
if [ -n "$RUSTFLAGS_HITS" ]; then
    echo "::error::RUSTFLAGS is assigned in a build path. As an env var it REPLACES .cargo/config.toml's target rustflags, dropping BOTH --cfg $KECCAK_CFG (FEAT_SHA3 opcodes ship again) AND -C link-arg=-Wl,-z,max-page-size=16384 (16 KB page alignment, which Google Play requires for API 35+). If the site is a deliberate negative test, put $RUSTFLAGS_MARKER on the line:" >&2
    printf '%s\n' "$RUSTFLAGS_HITS" | sed 's/^/    /' >&2
    FAILED=1
else
    echo "  ok: no unmarked RUSTFLAGS assignment in any build path"
fi

if [ "$FAILED" -ne 0 ]; then
    echo "::error::check_pq_features FAILED. See native/README.md § ISA baseline and the decision record in native/rosenpass-jni/Cargo.toml for what the KEM configuration is and why." >&2
    exit 1
fi
echo "check_pq_features: ml-kem v$ML_KEM_VERSION {$EXPECTED_FEATURES} in every native crate and target; no pqcrypto-*; soft-Keccak cfg only in $CARGO_CONFIG"
