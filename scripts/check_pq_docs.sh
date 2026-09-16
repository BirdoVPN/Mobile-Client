#!/usr/bin/env bash
#
# Fail the build if any file in this repo still makes a claim about the PQ KEM
# that measurement has falsified.
#
# WHY A DOCS GATE IS A BUILD GATE
#
# The 1.4.25 SIGILL was attributed to an implementation because a human read
# docs/SENTRY-SETUP.md. When the docs are wrong, the next incident is
# mis-attributed, and the fix goes into the wrong place. Three of the claims
# below were true of `pqcrypto-mlkem` and are false of `ml-kem`; one of them was
# never true of anything.
#
# THE CLAIMS, AND WHY EACH IS FALSE
#
#   "structurally impossible" / "removes the C/asm path entirely"
#       The premise the migration was requested on. It does not hold.
#       ml-kem 0.3.2 -> sha3 0.11 -> keccak 0.2.2 ships
#       src/backends/aarch64_sha3.rs, and a DEFAULT aarch64 build contains the
#       same four ARMv8.2 FEAT_SHA3 mnemonics PQClean's keccak2x/feat.S did:
#       64 instructions, eor3=10, rax1=5, xar=24, bcax=25. What changed is that
#       keccak gates them at runtime on cpufeatures' getauxval(AT_HWCAP) &
#       (HWCAP_SHA3|HWCAP_SHA512), which PQClean's aarch64 path did not -- the
#       estate's own note recorded that gate as a literal `if true`. The crash
#       class is closed SEMANTICALLY, by a check, not STRUCTURALLY, by absence.
#
#   "no FEAT_SHA3 opcodes ship"
#       True of the shipped librosenpass_jni.so ONLY because
#       native/rosenpass-jni/.cargo/config.toml pins
#       --cfg keccak_backend="soft", which makes the fast backend unreferenced
#       so the linker drops it. That is a build-flag fact with a specific
#       failure mode (RUSTFLAGS='-C target-feature=+sha3' silently elides the
#       HWCAP check via cpufeatures' __unless_target_features!), not a property
#       of the crate. Stating it without the flag is how the flag gets dropped.
#
#   "CLEAN-only"
#       PQClean is gone. Nothing in this repo is CLEAN C any more.
#
# HOW TO SAY ONE OF THESE THINGS ANYWAY
#
# Put `PQ-DOCS-OK` on the same line. That is for text that QUOTES a false claim
# in order to refute it, or that describes the pre-1.4.30 history accurately.
# It is a per-site exception, like LSE_GATED_OK in check_no_sha3_ext.sh: it
# makes the exception visible in review instead of silently widening the rule.
#
# Usage: scripts/check_pq_docs.sh          (from anywhere in the repo)
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

EXEMPT_MARKER="PQ-DOCS-OK"

# Each entry: <regex>|<why it is false>
FORBIDDEN=(
    "structurally impossible|ml-kem's aarch64 build carries the same 64 FEAT_SHA3 instructions PQClean's feat.S did; the crash class is closed by a runtime HWCAP check, not by absence of opcodes."
    "removes the C/asm path entirely|The C build step is gone, but keccak's aarch64_sha3 backend is machine-specific code reached through the same four mnemonics."
    "no FEAT_SHA3 opcodes ship|Only true because .cargo/config.toml pins --cfg keccak_backend=\"soft\". Say the flag, or the flag gets dropped."
    "ships no FEAT_SHA3|Same: that is a property of the build flags, not of the crate."
    "CLEAN-only|PQClean is no longer a dependency of anything in this repo."
    "CLEAN only|PQClean is no longer a dependency of anything in this repo."
)

# Search the whole tree except build output, VCS metadata and vendored deps.
# `git ls-files` would miss a file added but not staged, which is exactly when
# a wrong claim is easiest to land.
PRUNE=(-name .git -o -name target -o -name build -o -name node_modules -o -name .gradle -o -name .idea)

FAILED=0
for entry in "${FORBIDDEN[@]}"; do
    pattern="${entry%%|*}"
    why="${entry#*|}"
    # -I: skip binaries. The exemption marker must be on the SAME line.
    hits=$(find . \( "${PRUNE[@]}" \) -prune -o -type f -print0 \
        | xargs -0 grep -InH -- "$pattern" 2>/dev/null \
        | grep -v -- "$EXEMPT_MARKER" \
        | grep -v '^\./scripts/check_pq_docs\.sh:' || true)
    if [ -n "$hits" ]; then
        echo "::error::check_pq_docs: \"$pattern\" is still claimed in this repo. $why" >&2
        printf '%s\n' "$hits" | sed 's/^/    /' >&2
        FAILED=1
    fi
done

if [ "$FAILED" -ne 0 ]; then
    echo "::error::check_pq_docs FAILED. Fix the text, or -- if the line quotes the claim in order to refute it -- put $EXEMPT_MARKER on that line. See native/README.md § ISA baseline." >&2
    exit 1
fi
echo "check_pq_docs: no falsified PQ claims in the tree (${#FORBIDDEN[@]} patterns checked)"
