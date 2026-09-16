# Birdo VPN — Native Rosenpass Module

This directory contains the **post-quantum WireGuard PSK exchange** native
module for the Birdo Android client.

## Why native?

[Rosenpass](https://rosenpass.eu) inspired this design: a Rust-implemented
post-quantum secure WireGuard preshared-key exchange. Our current
production build uses **ML-KEM-1024 (FIPS 203 / formerly CRYSTALS-Kyber)**
exclusively — the NIST-standardised lattice KEM. Earlier prototypes also
linked Classic McEliece for hybrid evaluation; that dependency has been
removed (see `Cargo.toml` — only `ml-kem` remains). There is no
battle-tested pure-JVM implementation of ML-KEM, so we ship RustCrypto's
pure-Rust [`ml-kem`](https://github.com/RustCrypto/KEMs) as a JNI library
compiled per Android ABI. Up to 1.4.29 this was PQClean's reference C through
`pqcrypto-mlkem`; the swap is byte-for-byte compatible (the KAT in
`rosenpass-jni/src/handshake.rs` proves it against the server's own fixture)
and the reasons are in the decision record on the `ml-kem` line of
[`rosenpass-jni/Cargo.toml`](rosenpass-jni/Cargo.toml).

## Layout

```
native/
├── README.md              ← you are here
├── ROADMAP.md             ← what's done / what's left, audit checklist
├── build.ps1              ← Windows build entry point
├── build.sh               ← Linux/macOS build entry point
└── rosenpass-jni/         ← the Rust crate
    ├── Cargo.toml
    └── src/
        ├── lib.rs         ← JNI exports (#[no_mangle] extern "system" fn …)
        ├── errors.rs      ← exception conversion
        └── handshake.rs   ← KEM + protocol body
```

## Building locally

### 1. One-time setup

```pwsh
# Install Rust
winget install Rustlang.Rust.MSVC      # or use rustup-init.exe

# Install Android cross-compilation helper
cargo install cargo-ndk

# Add Android targets to your toolchain.
# ONE PER SHIPPED ABI. app/build.gradle.kts abiFilters ships four --
# arm64-v8a, armeabi-v7a, x86_64, x86 -- and build.ps1 / build.sh pass all four
# to cargo-ndk, so i686-linux-android is required to build what CI builds.
# A missing target does NOT fail the Rust build: it silently yields one fewer
# librosenpass_jni.so, which only surfaces much later in
# scripts/verify_android_release_apk.py.
rustup target add `
    aarch64-linux-android `
    armv7-linux-androideabi `
    x86_64-linux-android `
    i686-linux-android

# Set NDK path (auto-detected on Windows from %LOCALAPPDATA%\Android\Sdk\ndk)
$env:ANDROID_NDK_HOME = "$env:LOCALAPPDATA\Android\Sdk\ndk\26.3.11579264"
```

### 2. Build the .so files

```pwsh
pwsh native/build.ps1            # release, all four ABIs (0.34-0.45 MB each)
pwsh native/build.ps1 -Profile debug
```

Output is written directly to `app/src/main/jniLibs/<abi>/librosenpass_jni.so`,
which Gradle picks up automatically on the next `:app:assembleRelease` /
`:app:bundleRelease`.

These files keep their symbol table -- `native/rosenpass-jni/Cargo.toml` sets
`strip = "debuginfo"` so that AGP's `debugSymbolLevel = "FULL"` can bundle the
symbols into the AAB for Play Console native crash symbolication. AGP strips
them again on the way into the APK, so the packaged library is smaller than the
one written here (0.45 MB -> 0.32 MB on arm64-v8a). That is why the
`NATIVE_HASH_*` integrity constants are computed from AGP's
`strip<Variant>DebugSymbols` output rather than from these files or from the
merge stage -- see the comment on `stripTask` in `app/build.gradle.kts`.

### 3. Verify

```kotlin
// Anywhere in app code
Log.i("Rosenpass", RosenpassNative.getNativeVersion())
// → "rosenpass-jni 0.1.0 (aarch64, release)"
```

If you see `<not loaded>`, the .so wasn't packaged — either you skipped step 2
or your build variant doesn't include native libs.

## ISA baseline — soft Keccak, and the gates that keep it so

**Decision (2026-09-16, superseding the 1.4.25 post-mortem decision):**
`librosenpass_jni.so` links RustCrypto's pure-Rust `ml-kem` on every ABI, built
with `--cfg keccak_backend="soft"` so the shipped library carries no
optional-extension opcode. The decision record — why the KEM changed, what it
did and did not fix, and what every gate is for — is the comment block on the
`ml-kem` line in [`rosenpass-jni/Cargo.toml`](rosenpass-jni/Cargo.toml). The
short version, in the order the facts actually go:

- **The history.** Every Android build from 1.3.25 to 1.4.25 crashed with
  `SIGILL` on any arm64 device without FEAT_SHA3 (Snapdragon 6xx/7xx/888,
  Helio G8x/G9x). `pqcrypto-mlkem`'s default `neon` feature compiled PQClean's
  AArch64 path, which reaches 64 SHA3-extension opcodes behind a literal
  `if true`. Fixed in #353 by pinning the portable CLEAN C.
- **Why the KEM changed anyway.** PQClean upstream was archived read-only on
  2026-08-04 (PQClean/PQClean#604), so no further security patch will ever land
  on that C, and RustSec filed three unmaintained advisories on 2026-06-04:
  RUSTSEC-2026-0161 (`pqcrypto-mlkem`), -0162 (`pqcrypto-traits`), -0163
  (`pqcrypto-internals`). RUSTSEC-2026-0161's own remediation text says
  "Users should migrate to the `ml-kem` crate."
- **What the swap did NOT do.** It did not remove the opcodes. `ml-kem 0.3.2 →
  sha3 0.11 → keccak 0.2.2` ships `src/backends/aarch64_sha3.rs`, adapted from
  the same XKCP/K12 `KeccakP-1600-ARMv8Asha3.S` PQClean vendored, and a DEFAULT
  aarch64 build contains the same four mnemonics: 64 instructions, `eor3`=10,
  `rax1`=5, `xar`=24, `bcax`=25. What it added is the **gate PQClean's aarch64
  path lacked** — `keccak/src/lib.rs:81-91` dispatches on
  `cpufeatures`' `getauxval(AT_HWCAP) & (HWCAP_SHA3|HWCAP_SHA512)`.
- **How the shipped library still gets to zero.**
  [`rosenpass-jni/.cargo/config.toml`](rosenpass-jni/.cargo/config.toml) sets
  `--cfg keccak_backend="soft"` for all four ABIs, which takes the early-return
  arm in `Keccak::with_backend()`; the fast backend is then unreferenced and
  `lto = "fat"` + `--gc-sections` drop it at link time. It lives in that file
  and nowhere else because `app/build.gradle.kts` declares it a
  `buildRustLibs` input — the same cfg in a workflow `RUSTFLAGS` would not
  invalidate the Gradle task and could ship a stale `UP-TO-DATE` `.so`.
- **The one known way to re-arm the crash.** `RUSTFLAGS='-C target-feature=+sha3'`
  (or any `-C target-cpu` above the fleet floor): `cpufeatures`' internal
  `__unless_target_features!` macro elides the HWCAP check entirely and returns
  a constant `true` when the feature is statically enabled. `android.yml` builds
  that configuration on purpose and asserts the ISA gate **fails** on it.
- **Cost:** the FEAT_SHA3 Keccak speedup on devices that have the extension.
  ML-KEM keygen runs once per connect, next to a network round trip. No
  physical-device timing exists for either implementation; the QEMU TCG numbers
  from the 1.4.25 post-mortem (keypair ~0.33 ms CLEAN vs ~0.21 ms AArch64+SHA3)
  are emulated and relative only.

Seven controls keep it this way; every one fails the build, none is advisory:

| Control | What it proves | Where it runs |
|---|---|---|
| [`scripts/check_pq_features.sh`](../scripts/check_pq_features.sh) | `pqcrypto-*` is absent from every native crate's graph on every shipped target; `ml-kem` resolves to exactly the pinned version with exactly `{alloc, getrandom, zeroize}` outside dev-dependencies and `hazmat` only inside them; `keccak` and `cpufeatures` are present on aarch64; and the soft-Keccak `--cfg` — which `cargo tree` cannot see, because it is a cfg and not a feature — is in `.cargo/config.toml` and nowhere else. Catches feature unification and a dropped build flag before anything is built. | `android.yml` build + release jobs, before `cargo ndk` |
| [`scripts/check_pq_docs.sh`](../scripts/check_pq_docs.sh) | No file in the repo still claims the post-migration build is CLEAN-only, that the C/asm path is "removed entirely", or that the crash class is "structurally impossible". Three of those were true of `pqcrypto-mlkem`; one was never true of anything. A per-line `PQ-DOCS-OK` marker exempts text that quotes a claim in order to refute it. | `android.yml` build job |
| `cargo test` / `clippy` / `fmt` for all three native crates | The KAT against the server's own `@noble` fixture, the PQClean install-base guard, the 3168-byte store-length assertion, the implicit-rejection and re-key behaviours, and the `nativeImplName` assertion all still hold — with a minimum test count, so deleting a `#[cfg(test)]` module reports 0 tests and FAILS instead of silently passing. | `android.yml` build job |
| [`scripts/check_pq_rng_panics.sh`](../scripts/check_pq_rng_panics.sh) | No production path generates a keypair through `DecapsulationKey::generate()`, which is `generate_from_rng(&mut UnwrapErr(SysRng))` (crypto-common 0.2.2 `src/generate.rs:42`) under a literal `# Panics` doc. Both native crates set `panic = "abort"`, so that is a process abort mid-connect with no error for Kotlin or Swift to read — the same defect `pqcrypto-internals` had (`getrandom::fill(buf).expect("RNG Failed")`). The migration MOVED that panic before this gate existed; `try_generate()` removes it. `encapsulate()` is explicitly out of scope and the script says why. | `android.yml` build + release jobs |
| [`scripts/check_pq_ios_wiring.sh`](../scripts/check_pq_ios_wiring.sh) | Every production `birdo_pq_*` export declared in `birdo_pq_ios.h` is CALLED from a non-comment line of Swift under `iosApp/`. `birdo_pq_stored_key_usable` shipped written, declared, exported and symbol-checked in all three Apple slices — and called from nowhere, because every other control looks at the LIBRARY and none asked whether the app used it. Runs on ubuntu in `android.yml` because `ios.yml` fires only on `android-v*` tags, and a gate that cannot run on a PR does not gate the PR. | `android.yml` build + release jobs, `ios.yml` test job |
| [`scripts/check_no_sha3_ext.sh`](../scripts/check_no_sha3_ext.sh) | Every shipped `.so` disassembled (arm64/x86_64/x86) or attribute-checked (armeabi-v7a); any optional-extension opcode outside a runtime guard the script can verify per site fails. STRICT by default; only `libwg-go.so` and `libxray.so` (Go, `internal/cpu` dispatch) are allowlisted. | `native/build.sh` / `build.ps1` on their own output (so `:app:buildRustLibs` runs it), `android.yml` on the PR debug APK, the release APK and the Play AAB |
| [`scripts/tests/check_no_sha3_ext_test.sh`](../scripts/tests/check_no_sha3_ext_test.sh) | The gate bites: fails on a hand-assembled `eor3`/`rax1`/`xar`/`bcax`, an inline `ldadd`, an AVX2/BMI2/AES-NI x86_64 object, a v8-attributed armeabi-v7a object, a 32-bit `popcnt`; passes a stripped compiler-rt-shaped outlined-atomics helper and the sha2 crate's pinned SHA-NI count. Fixtures in [`scripts/testdata/isa-gate/`](../scripts/testdata/isa-gate/). | `android.yml` build job, before the gate is trusted |

Attribution for the next incident: `nativeCpuFeatures()` returns
`getauxval(AT_HWCAP/AT_HWCAP2)` and `nativeImplName()` returns
`"mlkem1024-rustcrypto"` (it was `"mlkem1024-clean"` up to 1.4.29, which is how
a crash report is attributed to one implementation or the other — see
`pq_impl_name_matches_linked_crate`). The iOS twin exports the same value as
`birdo_pq_impl_name()`, which `BirdoPQManager.implementationName` reads and
logs at first use — iOS has no crash reporter yet, so it is not attached to
crash metadata the way Android's is; it is the value one would attach. `CpuFeatures.kt` logs one line at library load and tags
Sentry (`cpu.sha3=true|false` etc., `birdo.pq.impl`, `birdo.abi`). The triage
runbook is in [`docs/SENTRY-SETUP.md` § 7a](../docs/SENTRY-SETUP.md).

Running the gate locally needs a disassembler that knows every Android ELF
machine: the NDK's `llvm-objdump` (found through `ANDROID_NDK_HOME`) or
`rustup component add llvm-tools --toolchain 1.96.0` (the channel
`rust-toolchain.toml` pins; the gate's own error message names it, read from
that file). Without one, `build.sh`/`build.ps1` print a
loud warning and CI — where the tool is mandatory (`ROSENPASS_ISA_GATE_REQUIRED=1`)
— fails.

## CI integration

[`.github/workflows/android.yml`](../.github/workflows/android.yml) installs
Rust + cargo-ndk before the Gradle build and invokes `native/build.sh release`
so every signed AAB contains the native module for all four ABIs. This adds
~3 minutes to the CI run.

## Graceful degradation

The Kotlin loader [`RosenpassNative`](../app/src/main/java/app/birdo/vpn/service/RosenpassNative.kt)
catches `UnsatisfiedLinkError` and exposes `RosenpassNative.isLoaded`.
[`RosenpassManager`](../app/src/main/java/app/birdo/vpn/service/RosenpassManager.kt)
checks this flag and falls back to the existing **server-provided PSK** path
when the native lib isn't present. **This means local debug builds without
the Rust toolchain still work** — you just don't get bilateral PQ until you
run `native/build.ps1` once.

## Security model

See [`ROADMAP.md`](./ROADMAP.md) §"Threat model and audit checklist".
