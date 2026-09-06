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

## CI integration

[`.github/workflows/android.yml`](../.github/workflows/android.yml) installs
Rust + cargo-ndk before the Gradle build and invokes `native/build.sh release`
so every signed AAB contains the native module for all three ABIs. This adds
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
