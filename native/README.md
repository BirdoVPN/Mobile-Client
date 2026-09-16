# Birdo VPN — Native Rosenpass Module

This directory contains the **post-quantum WireGuard PSK exchange** native
module for the Birdo Android client.

## Why native?

[Rosenpass](https://rosenpass.eu) inspired this design: a Rust-implemented
post-quantum secure WireGuard preshared-key exchange. Our current
production build uses **ML-KEM-1024 (FIPS 203 / formerly CRYSTALS-Kyber)**
exclusively — the NIST-standardised lattice KEM. Earlier prototypes also
linked Classic McEliece for hybrid evaluation; that dependency has been
removed (see `Cargo.toml` — only `pqcrypto-mlkem` remains). There is no
battle-tested pure-JVM implementation of ML-KEM, so we ship PQClean's
reference C through `pqcrypto-mlkem` as a JNI library compiled per
Android ABI.

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

## ISA baseline — portable CLEAN only, and the gates that keep it so

**Decision (2026-09-16):** `librosenpass_jni.so` ships PQClean's portable
CLEAN C implementation of ML-KEM-1024 on every ABI. No optimised backend, no
runtime dispatch. The decision record — why, the measured cost, and what it
would take to revisit — is the comment block on the `pqcrypto-mlkem` line in
[`rosenpass-jni/Cargo.toml`](rosenpass-jni/Cargo.toml). The short version:

- Every Android build from 1.3.25 to 1.4.25 crashed with `SIGILL` on any arm64
  device without FEAT_SHA3 (Snapdragon 6xx/7xx/888, Helio G8x/G9x). The crate's
  default `neon` feature compiled PQClean's AArch64 path, which reaches 64
  SHA3-extension opcodes behind a literal `if true`. Fixed in #353 by
  `default-features = false, features = ["std"]`.
- CLEAN is the reference implementation the optimised ones are validated
  against; under QEMU all three produced bit-identical keypairs on every core
  model tried. One code path, one thing to audit.
- Cost, measured under QEMU TCG on an x86 host — **emulated, relative numbers
  only, no physical-device timing exists**: keypair ~0.33 ms CLEAN vs ~0.21 ms
  AArch64+SHA3; keypair+enc+dec ~1.25 ms vs ~0.68 ms. Keygen runs once per
  connect, next to a network round trip.

Three controls keep it this way; every one fails the build, none is advisory:

| Control | What it proves | Where it runs |
|---|---|---|
| [`scripts/check_pq_features.sh`](../scripts/check_pq_features.sh) | `cargo tree -e features -i pqcrypto-mlkem` resolves to exactly `{std}` for every native crate and every target, and every dependency line under `native/**/Cargo.toml` says `default-features = false`. Catches Cargo feature unification before anything is built. | `android.yml` build + release jobs, before `cargo ndk` |
| [`scripts/check_no_sha3_ext.sh`](../scripts/check_no_sha3_ext.sh) | Every shipped `.so` disassembled (arm64/x86_64/x86) or attribute-checked (armeabi-v7a); any optional-extension opcode outside a runtime guard the script can verify per site fails. STRICT by default; only `libwg-go.so` and `libxray.so` (Go, `internal/cpu` dispatch) are allowlisted. | `native/build.sh` / `build.ps1` on their own output (so `:app:buildRustLibs` runs it), `android.yml` on the PR debug APK, the release APK and the Play AAB |
| [`scripts/tests/check_no_sha3_ext_test.sh`](../scripts/tests/check_no_sha3_ext_test.sh) | The gate bites: fails on a hand-assembled `eor3`/`rax1`/`xar`/`bcax`, an inline `ldadd`, an AVX2/BMI2/AES-NI x86_64 object, a v8-attributed armeabi-v7a object, a 32-bit `popcnt`; passes a stripped compiler-rt-shaped outlined-atomics helper and the sha2 crate's pinned SHA-NI count. Fixtures in [`scripts/testdata/isa-gate/`](../scripts/testdata/isa-gate/). | `android.yml` build job, before the gate is trusted |

Attribution for the next incident: `nativeCpuFeatures()` returns
`getauxval(AT_HWCAP/AT_HWCAP2)` and `nativeImplName()` returns
`"mlkem1024-clean"`; `CpuFeatures.kt` logs one line at library load and tags
Sentry (`cpu.sha3=true|false` etc., `birdo.pq.impl`, `birdo.abi`). The triage
runbook is in [`docs/SENTRY-SETUP.md` § 7a](../docs/SENTRY-SETUP.md).

Running the gate locally needs a disassembler that knows every Android ELF
machine: the NDK's `llvm-objdump` (found through `ANDROID_NDK_HOME`) or
`rustup component add llvm-tools`. Without one, `build.sh`/`build.ps1` print a
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
