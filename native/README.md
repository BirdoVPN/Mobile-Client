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

# Add Android targets to your toolchain
rustup target add `
    aarch64-linux-android `
    armv7-linux-androideabi `
    x86_64-linux-android

# Set NDK path (auto-detected on Windows from %LOCALAPPDATA%\Android\Sdk\ndk)
$env:ANDROID_NDK_HOME = "$env:LOCALAPPDATA\Android\Sdk\ndk\26.3.11579264"
```

### 2. Build the .so files

```pwsh
pwsh native/build.ps1            # release (~3 MB per ABI, stripped)
pwsh native/build.ps1 -Profile debug
```

Output is written directly to `app/src/main/jniLibs/<abi>/librosenpass_jni.so`,
which Gradle picks up automatically on the next `:app:assembleRelease` /
`:app:bundleRelease`.

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
