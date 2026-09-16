#!/usr/bin/env bash
# Build BirdoPQNative.xcframework from birdo-pq-ios for the three iOS slices
# the BirdoVPN app ships against:
#
#   1. iOS device      (aarch64-apple-ios)
#   2. iOS simulator   (aarch64-apple-ios-sim)
#   3. iOS simulator   (x86_64-apple-ios)            ← Intel-Mac CI runners
#
# Output: BirdoPQNative.xcframework at the path passed as $1, default
# `iosApp/Vendor/BirdoPQNative.xcframework`.
#
# REQUIREMENTS:
#   - macOS host with Xcode + cargo + the three rustup targets installed.
#   - Run from the repo root, OR cd into native/birdo-pq-ios first.
#
# This script is a no-op on non-macOS hosts because Xcode + xcodebuild are
# both required by `xcodebuild -create-xcframework`. Cargo cross-compiles
# fine from Linux/Windows but the final XCFramework wrapping does not.

set -euo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "ERROR: BirdoPQNative XCFramework can only be built on macOS." >&2
    echo "       (xcodebuild -create-xcframework is Apple-only.)" >&2
    exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CRATE_DIR="${SCRIPT_DIR}/../native/birdo-pq-ios"
OUT_DIR="${1:-${SCRIPT_DIR}/../iosApp/Vendor/BirdoPQNative.xcframework}"

if [[ ! -d "${CRATE_DIR}" ]]; then
    echo "ERROR: cannot find crate at ${CRATE_DIR}" >&2
    exit 1
fi

# Make sure the rustup targets are present. (Idempotent.)
#
# The two darwin targets are for the macOS app. NOT Mac Catalyst
# (*-apple-ios-macabi): Go has no Catalyst target, so libwg-go.a cannot be built
# for it, which rules Catalyst out for this app entirely. A native macOS build
# uses GOOS=darwin, which the WireGuardKitGo Makefile already supports.
rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios \
                  aarch64-apple-darwin x86_64-apple-darwin

cd "${CRATE_DIR}"

# Build the static archive for every target.
cargo build --release --target aarch64-apple-ios       --lib
cargo build --release --target aarch64-apple-ios-sim   --lib
cargo build --release --target x86_64-apple-ios        --lib
cargo build --release --target aarch64-apple-darwin    --lib
cargo build --release --target x86_64-apple-darwin     --lib

# -- ISA census for every Apple aarch64 slice -------------------------------
#
# Android has had scripts/check_no_sha3_ext.sh since the 1.4.25 SIGILL. The
# Apple side had NOTHING: ios.yml just called this script, so the Apple arm64
# slices could carry the same Keccak FEAT_SHA3 code with no build-time check at
# all, and there was no iOS equivalent of nativeImplName to attribute a crash
# with either. (birdo_pq_impl_name() closes the second half of that gap.)
#
# The policy here is the same as the Android gate's, resolved for Apple:
#
#   * ml-kem -> sha3 0.11 -> keccak 0.2.2 ships backends/aarch64_sha3.rs. On
#     Apple targets it is gated at runtime by cpufeatures-0.3.1/src/aarch64.rs,
#     which calls sysctlbyname("hw.optional.armv8_2_sha3") -- NOT getauxval, so
#     the Apple slices need their own evidence rather than inheriting Android's.
#   * So: FEAT_SHA3 instructions are permitted ONLY when that sysctl name is
#     still present in the archive. Building with -C target-feature=+sha3 makes
#     cpufeatures' __unless_target_features! macro elide the check entirely and
#     return a constant true -- at which point the opcodes execute
#     unconditionally AND the string disappears. That is exactly the shape of
#     the crash 1.4.25 shipped on Android, and it is what this census catches.
#   * Zero instructions is also fine, and is what an Apple build with the
#     soft-Keccak cfg would produce. Both outcomes pass; only "opcodes with no
#     check" fails.
#
# Note the asymmetry with Android, which is deliberate: Android pins
# --cfg keccak_backend="soft" in native/rosenpass-jni/.cargo/config.toml and
# ships zero, because the Android fleet genuinely contains ARMv8.2 cores without
# FEAT_SHA3. Every Apple device the app supports has it (A12 / M1 and later), so
# the Apple slices keep the fast path and rely on the runtime gate -- which this
# census is here to prove is actually present.
SHA3_MNEMONICS='^(eor3|rax1|xar|bcax)$'
CPUFEATURES_SYSCTL='hw.optional.armv8_2_sha3'

# Every check below captures its tool's output into a variable FIRST and greps
# the variable afterwards. `set -o pipefail` is on, and both otool and nm exit
# non-zero on a Rust staticlib (empty archive members produce "no symbols"),
# which in a `tool | grep -q` pipeline silently turns "found it" into "failed".
# The first version of this census did exactly that and reported a symbol as
# missing when it was present.
#
# Each check also proves its own tooling worked before it is allowed to pass.
# A census that decodes nothing, or a symbol scan that reads no symbols, is a
# FAILURE -- not a clean bill of health. That is the whole lesson of
# scripts/check_pq_features.sh: a check that found nothing is not a pass.

isa_census() {
    archive="$1"
    label="$2"
    if [[ ! -f "${archive}" ]]; then
        echo "ERROR: ISA census: ${archive} does not exist" >&2
        exit 1
    fi

    # otool -tvV prints "<address><tab><mnemonic><tab><operands>", so the
    # mnemonic is the SECOND field, not the first. Getting that wrong is how
    # the first version of this census reported 0 for every slice and passed.
    # Matching $1 against a hex address also skips otool's archive-member and
    # section headers, and works whether it separates with tabs or spaces.
    dis="$(otool -tvV "${archive}" 2>&1 || true)"
    mnemonics="$(printf '%s\n' "${dis}" | awk '$1 ~ /^[0-9a-f]+$/ { print $2 }')"
    total="$(printf '%s\n' "${mnemonics}" | grep -cE '^[a-z][a-z0-9._]*$' || true)"
    if [[ "${total}" -lt 1000 ]]; then
        echo "ERROR: ISA census: otool decoded only ${total} instruction(s) out of ${label}." >&2
        echo "       A ML-KEM-1024 implementation is tens of thousands of instructions, so this" >&2
        echo "       census examined nothing and must not be reported as clean. First lines of" >&2
        echo "       otool output:" >&2
        printf '%s\n' "${dis}" | head -5 | sed 's/^/       /' >&2
        exit 1
    fi

    count="$(printf '%s\n' "${mnemonics}" | grep -cE "${SHA3_MNEMONICS}" || true)"
    if [[ "${count}" -eq 0 ]]; then
        echo "  ok: ${label}: 0 FEAT_SHA3 instructions (of ${total} decoded)"
        return 0
    fi

    strs="$(strings -a "${archive}" 2>&1 || true)"
    if printf '%s\n' "${strs}" | grep -qF "${CPUFEATURES_SYSCTL}"; then
        echo "  ok: ${label}: ${count} FEAT_SHA3 instruction(s) of ${total} decoded, gated on sysctlbyname"
        return 0
    fi
    echo "ERROR: ${label} contains ${count} FEAT_SHA3 instruction(s) (eor3/rax1/xar/bcax) but NO reference to" >&2
    echo "       a sysctlbyname on ${CPUFEATURES_SYSCTL}, so nothing checks the CPU before executing them." >&2
    echo "       The usual cause is RUSTFLAGS enabling the sha3 target feature (or -C target-cpu above the" >&2
    echo "       fleet floor), which makes cpufeatures elide its runtime check and return a constant true." >&2
    echo "       That is the 1.4.25 SIGILL shape. Do not silence this by widening the rule." >&2
    exit 1
}

echo "ISA census (Apple aarch64 slices):"
isa_census "target/aarch64-apple-ios/release/libbirdo_pq_ios.a"     "aarch64-apple-ios"
isa_census "target/aarch64-apple-ios-sim/release/libbirdo_pq_ios.a" "aarch64-apple-ios-sim"
isa_census "target/aarch64-apple-darwin/release/libbirdo_pq_ios.a"  "aarch64-apple-darwin"

# birdo_pq_impl_name must be in every slice, or an Apple crash report cannot say
# which KEM was running -- the exact gap that made the 1.4.25 attribution rest
# on a human reading docs. Mach-O prefixes C symbols with an underscore.
for slice in aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios \
             aarch64-apple-darwin x86_64-apple-darwin; do
    syms="$(nm -g "target/${slice}/release/libbirdo_pq_ios.a" 2>&1 || true)"
    found="$(printf '%s\n' "${syms}" | grep -c '_birdo_pq_' || true)"
    if [[ "${found}" -eq 0 ]]; then
        echo "ERROR: read no birdo_pq_* symbols at all out of ${slice}, not even birdo_pq_derive_psk," >&2
        echo "       which the app links against and therefore must be there. The SYMBOL CHECK is" >&2
        echo "       broken, not the export. First lines of nm output:" >&2
        printf '%s\n' "${syms}" | head -5 | sed 's/^/       /' >&2
        exit 1
    fi
    if ! printf '%s\n' "${syms}" | grep -q '_birdo_pq_impl_name'; then
        echo "ERROR: ${slice} exports ${found} birdo_pq_* symbol(s) but not birdo_pq_impl_name." >&2
        exit 1
    fi
done
echo "  ok: birdo_pq_impl_name exported by all five slices"

# Lipo the simulator slices into a single fat archive (xcframework wants
# one archive per platform-variant).
SIM_FAT_DIR="$(mktemp -d)"
SIM_FAT="${SIM_FAT_DIR}/libbirdo_pq_ios.a"
lipo -create \
    "target/aarch64-apple-ios-sim/release/libbirdo_pq_ios.a" \
    "target/x86_64-apple-ios/release/libbirdo_pq_ios.a" \
    -output "${SIM_FAT}"

# macOS ships universal: Apple Silicon and Intel Macs both run the Mac App Store
# build, and an xcframework takes ONE archive per platform-variant — so the two
# darwin arches are lipo'd together rather than declared as separate slices,
# exactly as the simulator pair above.
MAC_FAT_DIR="$(mktemp -d)"
MAC_FAT="${MAC_FAT_DIR}/libbirdo_pq_ios.a"
lipo -create \
    "target/aarch64-apple-darwin/release/libbirdo_pq_ios.a" \
    "target/x86_64-apple-darwin/release/libbirdo_pq_ios.a" \
    -output "${MAC_FAT}"

# Stage headers per slice.
HEADERS_DIR="$(mktemp -d)"
mkdir -p "${HEADERS_DIR}/Headers"
cp "include/birdo_pq_ios.h" "${HEADERS_DIR}/Headers/"
cp "include/module.modulemap" "${HEADERS_DIR}/Headers/"

# Wipe any previous output so xcodebuild won't refuse to overwrite.
rm -rf "${OUT_DIR}"

xcodebuild -create-xcframework \
    -library "target/aarch64-apple-ios/release/libbirdo_pq_ios.a" \
    -headers "${HEADERS_DIR}/Headers" \
    -library "${SIM_FAT}" \
    -headers "${HEADERS_DIR}/Headers" \
    -library "${MAC_FAT}" \
    -headers "${HEADERS_DIR}/Headers" \
    -output "${OUT_DIR}"

echo "[OK] Built: ${OUT_DIR}"
echo "   Slices: arm64-iOS, arm64+x86_64-iOS-sim, arm64+x86_64-macOS"
echo "   Add the .xcframework to the iosApp Xcode target ('Embed & Sign')."
