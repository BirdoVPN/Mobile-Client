#!/usr/bin/env bash
#
# Fail the build if a PRODUCTION BirdoPQ C-ABI export has no Swift call site.
#
# WHY THIS GATE EXISTS
#
# `birdo_pq_stored_key_usable` was written in Rust, declared in
# birdo_pq_ios.h, asserted present by `c_header_declares_every_export`,
# verified as a global symbol in all three Apple slices by ios.yml -- and
# called from nowhere. `iosApp/` was not touched at all. Every existing check
# looked at the LIBRARY; none looked at whether the app used it.
#
# What that cost: `ml-kem` enforces FIPS 203 section 7.3 on load, so a stored
# ML-KEM key that the old PQClean binding accepted is now a hard rejection.
# Android self-heals (RosenpassManager discards the sealed pair and re-keys).
# Without the Swift call, iOS would have thrown `quantumHandshakeFailed` on
# every connect, on every attempt, forever, on any install with such a key --
# nothing but a logout clears the Keychain item.
#
# An export nobody calls is not a mitigation. This gate is how "the iOS half
# is wired" stops being a sentence in a PR body and becomes a build failure.
#
# THE RULE
#
#   Every `birdo_pq_*` function declared in native/birdo-pq-ios/include/
#   birdo_pq_ios.h must be CALLED at least once from a .swift file under
#   iosApp/ -- the name followed by "(", on a line that is not a comment --
#   unless it is listed in TEST_ONLY below.
#
# Usage: scripts/check_pq_ios_wiring.sh      (from anywhere in the repo)
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

HEADER="native/birdo-pq-ios/include/birdo_pq_ios.h"
[ -f "$HEADER" ] || { echo "::error::check_pq_ios_wiring: $HEADER not found" >&2; exit 1; }

# Exports that deliberately have no Swift caller. Each needs a reason, and the
# reason has to survive review -- this list is the only way to pass without a
# call site, so a bare addition to it is the thing to argue with.
#
#   birdo_pq_test_encapsulate  server-side round trip, exercised by
#                              `cargo test` in the crate. Production Swift must
#                              never encapsulate.
#   birdo_pq_public_key_len    the four *_len() accessors duplicate the
#   birdo_pq_secret_key_len    BIRDO_PQ_*_LEN macros, which Swift imports
#   birdo_pq_ciphertext_len    directly as constants. They exist for callers
#   birdo_pq_psk_len           that link the library without the header.
TEST_ONLY=(
    birdo_pq_test_encapsulate
    birdo_pq_public_key_len
    birdo_pq_secret_key_len
    birdo_pq_ciphertext_len
    birdo_pq_psk_len
)

# Declarations look like `int32_t birdo_pq_x(` or `const char* birdo_pq_y(`,
# possibly after a return type on the same line. Take the identifier that is
# immediately followed by "(".
# NOT `mapfile`: it is a bash 4 builtin, and this script runs under macOS's
# /bin/bash 3.2 in ios.yml (android-v1.4.29's iOS build died on it with
# `mapfile: command not found` while every ubuntu PR run had passed).
# scripts/check_macos_bash_compat.sh now guards every script macOS runs.
EXPORTS=()
while IFS= read -r line; do
    [ -n "$line" ] && EXPORTS+=("$line")
done < <(grep -oE '\bbirdo_pq_[a-z0-9_]+[[:space:]]*\(' "$HEADER" \
    | sed -E 's/[[:space:]]*\($//' | sort -u)

if [ "${#EXPORTS[@]}" -eq 0 ]; then
    echo "::error::check_pq_ios_wiring: parsed 0 exports out of $HEADER. A check that found nothing is not a pass -- the declaration format changed and this parser did not." >&2
    exit 1
fi

# Sanity floor: the header has had at least these many exports since 2026-09.
# Guards against a parser that silently matches one line.
if [ "${#EXPORTS[@]}" -lt 8 ]; then
    echo "::error::check_pq_ios_wiring: parsed only ${#EXPORTS[@]} exports out of $HEADER (expected at least 8). The parser is probably broken." >&2
    printf '    %s\n' "${EXPORTS[@]}" >&2
    exit 1
fi

MISSING=()
CHECKED=0
for sym in "${EXPORTS[@]}"; do
    skip=0
    for allowed in "${TEST_ONLY[@]}"; do
        [ "$sym" = "$allowed" ] && skip=1 && break
    done
    [ "$skip" -eq 1 ] && continue
    CHECKED=$((CHECKED + 1))
    # A CALL, not a mention. The symbol must be followed by "(" and must not be
    # on a comment line -- the first cut of this gate matched the name inside
    # BirdoPQManager's own doc comment and passed a build in which the call had
    # been renamed away. A gate satisfied by prose is the defect it is meant to
    # catch, one level up.
    calls=$(grep -rn --include='*.swift' -E "(^|[^A-Za-z0-9_])${sym}[[:space:]]*\(" iosApp/ 2>/dev/null | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(//|\*|/\*)' || true)
    if [ -z "$calls" ]; then
        MISSING+=("$sym")
    fi
done

if [ "${#MISSING[@]}" -ne 0 ]; then
    echo "::error::check_pq_ios_wiring: these BirdoPQ exports are declared, built and shipped, but no Swift file under iosApp/ calls them. An export nobody calls is not a mitigation." >&2
    printf '    %s\n' "${MISSING[@]}" >&2
    echo "::error::Either call it from iosApp/, or add it to TEST_ONLY in $0 with a reason." >&2
    exit 1
fi

echo "check_pq_ios_wiring: ${#EXPORTS[@]} export(s) in $HEADER, $CHECKED production export(s), all called from iosApp/"
