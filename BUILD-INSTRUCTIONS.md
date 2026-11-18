# Rebuilding Birdo VPN Mobile (Android, iOS, macOS App Store)

This page tells a third party how to rebuild the Mobile client from a release tag
and compare the result with what we ship. Every pin is copied from a file in this
repository, named next to it; the files win if this page drifts. No secret is needed.

To check a download against our published checksums and Sigstore bundles, see
[`docs/VERIFICATION.md`](docs/VERIFICATION.md). This page is the other direction.

## What "reproducible" means here, honestly

We do **not** claim bit-for-bit reproducible release artefacts today. What you can do:

- Rebuild with the same toolchains and locked dependencies CI uses (pinned below).
- Compare the **unsigned payloads** (Android: everything except the signature block;
  iOS/macOS: the unsigned build products).
- Check the native engines against the hashes in the repository.

The reasons a byte-for-byte match is not expected are in
[Known non-determinism](#known-non-determinism). The Android native-library
hashes are compiled into the app and checked by CI (see
[Where CI already records hashes](#where-ci-already-records-hashes)).

## Pinned inputs

| Input | Pin | Source of truth |
|---|---|---|
| Rust | `1.96.0`, components `rustfmt` + `clippy`; Android targets `aarch64-linux-android`, `armv7-linux-androideabi`, `x86_64-linux-android`, `i686-linux-android` | `rust-toolchain.toml` |
| Rust crates | exactly each crate's `Cargo.lock`, built `--locked` | `native/rosenpass-jni/Cargo.lock`, `native/birdo-pq-ios/Cargo.lock`, `native/birdo-pq-server/Cargo.lock` |
| cargo-ndk | `4.1.2`, binary SHA-256 `dc148bebbfcd7d5355e9858530d5a69c20ce69cf3268c54b9f63c60cb4c9a966` | `.github/actions/install-cargo-ndk/action.yml` |
| JDK | 17, Temurin | `JAVA_VERSION` / `JAVA_DISTRIBUTION` in `.github/workflows/android.yml`; `JavaVersion.VERSION_17` in `app/build.gradle.kts` |
| Gradle | 9.8.2, distribution SHA-256 `21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607` | `gradle/wrapper/gradle-wrapper.properties` (use `./gradlew`, never a system Gradle) |
| Android Gradle Plugin | 9.4.1 (`com.android.application`, `com.android.library`); `com.android.kotlin.multiplatform.library` 9.5.0 | `build.gradle.kts` |
| Kotlin | 2.4.10 (multiplatform, compose, serialization plugins); KSP 2.3.12; Hilt 2.60.1; baselineprofile 1.5.0 | `build.gradle.kts` |
| Gradle dependencies | locked, `LockMode.STRICT`, `lockAllConfigurations()` | `app/gradle.lockfile`, `dependencyLocking {}` in `app/build.gradle.kts` |
| Gradle dependency bytes | SHA-256 of every artifact, POM and Gradle module file that Gradle resolves for the CI and release tasks, checked strictly by those builds; the exceptions are listed under "Where CI already records hashes" below | `gradle/verification-metadata.xml`, written by `.github/workflows/gradle-verification-metadata.yml` |
| SDK levels | `compileSdk 37` (minor 0), `targetSdk 36`, `minSdk 29` | `app/build.gradle.kts` |
| ABIs | `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` | `abiFilters` in `app/build.gradle.kts` |
| Android NDK | **not pinned on `main` yet**: the build uses the runner image's `ANDROID_NDK_HOME`. Open PR #601 (MR-1793) pins it in `native/ndk-version.txt`; once merged, use the revision named there | `.github/actions/android-native-libs/action.yml`, PR #601 |
| Go (Android Xray engines) | `go1.25.14` (official source SHA-256 `9e83f44f5fc297378861b4e16cc6aa114be8add7993fb3ceb2c512380aa4d582`) | `go-version` in `.github/actions/android-native-libs/action.yml`; `XRAY_GO_VERSION*` in `scripts/provision_xray_engines.sh` |
| Xray-core (Android) | `v26.2.6`, commit `12ee51e4bb1d02ece4ef4b7114efa2bcdc130995`, tree `d64b24307450e1cbfadf8c7a2d0e7f840c83b5c6`; arm64-v8a zip SHA-256 `952ccbb2275eace52e644a349358de293278eefc32b139d795b067edcb6a2f07`, x86_64 zip SHA-256 `f11f5bb71ebb2fe4127670404fbd0c21d93f62e453870fda65ad6effe7a6131e`; the two 32-bit ABIs are built from the pinned commit | `scripts/provision_xray_engines.sh` |
| Go (iOS/macOS WireGuard) | `1.27.2` | `iosApp/.go-version` |
| Xcode | `26.6` (iOS 26 SDK required) | `.github/actions/apple-toolchain/action.yml` |
| XcodeGen | `2.46.0`, SHA-256 `4d9e34b62172d645eed6457cac13fc222569974098ef4ee9c3368bedf0196806` | `.github/actions/apple-toolchain/action.yml` |
| Apple deployment target | iOS 17.0 (`IPHONEOS_DEPLOYMENT_TARGET` in `ios.yml`) | `.github/workflows/ios.yml`, `iosApp/project.yml` |
| wireguard-apple | vendored in-tree | `iosApp/Vendor/wireguard-apple` |
| Runners | Android: `ubuntu-24.04`; iOS and macOS: `macos-26` | `runs-on` in the workflows |
| Third-party GitHub Actions | each pinned by commit SHA (`scripts/check-action-pins.sh` enforces comments match) | `uses:` lines |

No container images are used by the Mobile build workflows, so there is no image
digest to pin. The Android SDK packages and build-tools on the runner image are not
pinned in this repository (see gaps).

The version number comes from `version.properties` (`major*10000 + minor*100 + patch`
is the Android `versionCode` and the iOS build number).

## Android

### Prerequisites

Linux x86-64 (CI uses Ubuntu 24.04), JDK 17 (Temurin), the Android SDK with platform
37, an Android NDK (see the NDK row above), Python 3, and Rust via rustup (inside the
checkout `rustup toolchain install` installs 1.96.0 from `rust-toolchain.toml`).
Go 1.25.14 is needed for the Xray engines.

### Steps

```bash
git clone https://github.com/BirdoVPN/Mobile-Client.git && cd Mobile-Client
git checkout android-vX.Y.Z

# 1. Native payload, all four ABIs. Same steps as .github/actions/android-native-libs:
rustup toolchain install
cargo install --locked --version 4.1.2 cargo-ndk       # CI installs the checked upstream binary instead
export ANDROID_NDK_HOME=/path/to/ndk
ROSENPASS_ISA_GATE_REQUIRED=1 ./gradlew :app:buildRustLibs   # librosenpass_jni.so
bash scripts/provision_xray_engines.sh                        # libxray.so (needs Go go1.25.14)

# 2. Pre-build assertions the release runs:
bash scripts/check_pq_features.sh && bash scripts/check_pq_docs.sh \
  && bash scripts/check_pq_rng_panics.sh && bash scripts/check_pq_ios_wiring.sh

# 3. The two artefacts. SENTRY_DSN is required by the build (see below).
export BIRDO_ALLOW_MISSING_SENTRY_DSN=1
./gradlew assembleRelease                      # APK  -> GitHub release, F-Droid repo
./gradlew bundleRelease -PplayBuild=true       # AAB  -> Google Play
```

Outputs: `app/build/outputs/apk/release/` and `app/build/outputs/bundle/release/`.
Without the `RELEASE_STORE_*` variables the `release` signing config stays empty, so
Gradle produces an **unsigned** release APK (`app-release-unsigned.apk`) and an
unsigned AAB, which is what you compare.

The two artefacts differ on purpose: `-PplayBuild=true` flips
`BuildConfig.IS_PLAY_BUILD`, so the AAB is a second, different R8 run (different
constant folding and reachability). The APK is never the AAB's bytes.

One APK serves three channels, built once by `android.yml`: the GitHub release, and the
self-hosted F-Droid repository, which `fdroid.yml` assembles from the **same release
APKs**. There is no separate F-Droid-server build, so there is no F-Droid recipe to
rebuild. Google Play receives the AAB and re-signs what it serves with the Play
App Signing key.

### Build inputs you cannot match

- `SENTRY_DSN`: our CI reads it from a secret and compiles it into `BuildConfig`. The
  build refuses to run without it; set `BIRDO_ALLOW_MISSING_SENTRY_DSN=1` (the
  documented source-build opt-out in `app/build.gradle.kts`) and expect that string to
  differ.
- The release signing key (Azure Key Vault in CI, `docs/CODE_SIGNING.md`). Never needed
  to rebuild.

### Comparing with the shipped APK

1. Download the release APK, `SHA256SUMS.txt` and their `.sigstore` bundles
   (`docs/VERIFICATION.md`). Those hashes are of the **signed** files.
2. APKs are zip files plus a v2/v3 signature block. Compare content, not the
   signature: `apksigner verify --print-certs` on ours, then diff the two
   archives file by file, for example
   `diffoscope ours.apk yours.apk` (it ignores nothing, so expect the signing
   metadata entries `META-INF/*` to differ) or, with the signature stripped,
   `unzip -q` both and `diff -r`. The deterministic parts are `classes*.dex`,
   `lib/*/*.so`, `assets/` (including the committed baseline profile) and
   `resources.arsc`.
3. For the AAB, unpack both (`bundletool`, or `unzip`) and compare `base/` the same way.
   Google Play's re-signed, split APKs are not comparable to your build at all; compare
   the AAB.

### Where CI already records hashes

- `scripts/check_native_hashes.py` (run by `android.yml`): the `NATIVE_HASH_*`
  constants compiled into the app must equal the SHA-256 of the shipped `.so` files.
- `scripts/verify_android_release_apk.py`: ABI and engine set of the release APK.
- `scripts/provision_xray_engines.sh`: SHA-256 of the downloaded Xray zips; commit-SHA
  assertion for the Xray source.
- `scripts/xray_corresponding_source.py`: builds the Xray corresponding-source asset
  (every Go module, the Go toolchain source, the exact `go build` command per engine).
  The asset is in each release's `SHA256SUMS.txt`.
- `app/gradle.lockfile`: dependency versions only.
- `gradle/verification-metadata.xml` (MR-1794): the SHA-256 of every artifact, POM
  and Gradle module file Gradle resolves for the CI and release tasks. Gradle refuses
  one it does not list, in CI and on your machine. Each coordinate comes from exactly
  one repository (Google's Maven or Maven Central; `settings.gradle.kts` says why the
  Gradle Plugin Portal is not listed). `.github/workflows/gradle-verification-metadata.yml`
  writes it from fresh downloads on a cold cache (Linux with every CI and release
  task, then Windows for its own `aapt2` and Kotlin/Native compiler; no workflow
  builds the Android app on macOS, so a Gradle build on a Mac first adds those osx
  entries, see the workflow header). On a pull request it fails when the committed
  file differs from that fresh write, when a checksum changed for a version the base
  branch already lists, and when `<configuration>` changed at all. After a dependency
  change, take that run's `verification-metadata` artifact, review the diff (new
  entries only for the versions that changed) and commit it; or run
  `gh workflow run gradle-verification-metadata.yml --ref <branch>`. Locally,
  `./gradlew --write-verification-metadata sha256 help <the tasks you run>` adds
  what is missing. Never `--dependency-verification off`.

  **Not covered** by the file:
  - `baseline-profile.yml`, which records on a Gradle Managed Device with
    `--dependency-verification lenient` (it reports a checksum it cannot find instead
    of failing) and a read-only Gradle cache. Its local equivalent,
    `scripts/generate-baseline-profile.sh`, runs Gradle with the default strict
    verification and is expected to stop on the managed-device and test-platform
    artifacts the file does not list; run its Gradle command with
    `--dependency-verification lenient` there too. Only the recorded profile text
    comes out of either, and a pull request reviews it.
  - sources and javadoc jars: trusted by rule (an IDE fetches them; no build ships
    them).
  - anything downloaded outside Gradle's dependency resolution: Robolectric's
    `android-all-instrumented` jars, which Robolectric fetches itself when the unit
    tests run; Android SDK and NDK packages (`sdkmanager`); and managed-device system
    images.

## iOS

CI: `.github/workflows/ios.yml`. The release workflow produces a signed TestFlight/App
Store IPA, and also an **unsigned `.ipa`** attached to GitHub releases with
`SHA256SUMS-ios.txt` (Sigstore-signed). The unsigned build is what you can reproduce.

Prerequisites: macOS with Xcode 26.6, XcodeGen 2.46.0 (SHA in the table), Go 1.27.2,
Rust 1.96.0 with the Apple targets (the script adds them).

```bash
git clone https://github.com/BirdoVPN/Mobile-Client.git && cd Mobile-Client
git checkout android-vX.Y.Z          # iOS builds from the same android-v* tag (ios.yml)
rustup toolchain install
bash scripts/build-birdo-pq-xcframework.sh        # BirdoPQNative.xcframework (cargo --locked, 5 Apple targets)

# libwg-go.a for the device (Go 1.27.2):
WG_LIB_DIR="$PWD/iosApp/.build-wg/iphoneos"; mkdir -p "$WG_LIB_DIR"
( cd iosApp/Vendor/wireguard-apple/Sources/WireGuardKitGo && \
  make ARCHS=arm64 PLATFORM_NAME=iphoneos \
    DEPLOYMENT_TARGET_CLANG_FLAG_NAME=mios-version-min \
    DEPLOYMENT_TARGET_CLANG_ENV_NAME=IPHONEOS_DEPLOYMENT_TARGET \
    IPHONEOS_DEPLOYMENT_TARGET=17.0 \
    SDKROOT="$(xcrun --sdk iphoneos --show-sdk-path)" \
    CONFIGURATION_BUILD_DIR="$WG_LIB_DIR" CONFIGURATION_TEMP_DIR="$(mktemp -d)" )

# version.properties -> BIRDO_MARKETING_VERSION / BIRDO_PROJECT_VERSION (see ios.yml "Derive iOS version")
cd iosApp && xcodegen generate
xcodebuild build -project BirdoVPN.xcodeproj -scheme BirdoVPN \
  -destination 'generic/platform=iOS' -configuration Release SYMROOT="$PWD/build" \
  MARKETING_VERSION="$BIRDO_MARKETING_VERSION" CURRENT_PROJECT_VERSION="$BIRDO_PROJECT_VERSION" \
  ARCHS=arm64 LIBRARY_SEARCH_PATHS="$WG_LIB_DIR" \
  CODE_SIGN_IDENTITY="-" CODE_SIGNING_REQUIRED=NO CODE_SIGNING_ALLOWED=NO ONLY_ACTIVE_ARCH=NO
```

The App Store IPA is the same sources archived (`xcodebuild archive`, manual signing)
and exported with an Apple Distribution certificate and provisioning profiles, then
re-signed and processed by Apple. Its bytes are Apple's, not ours.

## macOS (Mac App Store)

CI: `.github/workflows/macos.yml`. The macOS app is the same SwiftUI source as iOS
(this is the Mac App Store artefact; the Tauri Desktop client is not allowed in the
sandbox). The steps are the iOS ones with these changes (copy the exact lines from
`macos.yml`):

- `libwg-go.a`: `make ARCHS="arm64 x86_64" PLATFORM_NAME=macosx`, with
  `DEPLOYMENT_TARGET_CLANG_FLAG_NAME=mmacosx-version-min` and
  `DEPLOYMENT_TARGET_CLANG_ENV_NAME=MACOSX_DEPLOYMENT_TARGET`, into
  `iosApp/.build-wg/macosx`.
- `xcodebuild build ... -destination 'generic/platform=macOS' CODE_SIGNING_ALLOWED=NO`
  (the unsigned build `macos.yml` makes to verify the source compiles).
- The upload build (`mac-v*` tag) is `xcodebuild archive` with manual signing and
  App Store export.

## What is and is not reproducible

| Part | Reproducible by a third party | Why not |
|---|---|---|
| Rust crates (`librosenpass_jni.so`, `BirdoPQNative`) | Expected, subject to the paths and toolchain notes below. See the Mac measurement in the pull request. | Embedded paths, linker versions |
| `libxray.so` arm64-v8a, x86_64 | Yes at the input level: the upstream zip hash is pinned | Upstream's own build |
| `libxray.so` armeabi-v7a, x86 | Built from the pinned commit with the NDK clang and Go 1.25.14 | The NDK is not pinned on `main` until #601; Go embeds build paths |
| Android APK, AAB | Content after removing the signature, modulo the sources of non-determinism below | R8, resource ordering, `SENTRY_DSN`, signature |
| Play-served APKs | No | Play App Signing |
| iOS / macOS App Store binary | No | Apple signs and re-processes it |
| Unsigned iOS `.ipa` on GitHub releases | Closest. Needs the same Xcode and runner image | Embedded paths, Xcode and SDK build number |

## Known non-determinism

- **Signatures.** Play App Signing, Apple signing and our release key all change
  the bytes. Compare unsigned payloads.
- **`SENTRY_DSN`** compiled into `BuildConfig` (a secret in CI).
- **Embedded paths and timestamps.** Rust and Go embed build paths unless told not to.
  Zip entry ordering and timestamps in an APK/AAB have not been
  checked by us for the release pipeline.
- **R8.** Output depends on exact inputs, including `-PplayBuild`, and the Android SDK
  `android.jar` on the runner.
- **Toolchain drift we do not pin.** The NDK until #601 merges, the Android SDK
  build-tools, and the macOS runner image's build number and SDK build.
- **Apple.** Xcode embeds UUIDs (Mach-O `LC_UUID`) and DWARF paths, and App Store
  processing re-encrypts and re-signs; there is no bitcode (removed in Xcode 14).
- **Vendored xray.** Android arm64/x86_64 engines are the upstream release binary, so
  they are as reproducible as upstream's own build.

## Expected outputs

- Android: `SHA256SUMS.txt` (+ `.sigstore`) on each `android-v*` GitHub release,
  covering the APK, the AAB and the Xray corresponding-source asset. These are
  hashes of the **signed** files.
- iOS: `SHA256SUMS-ios.txt` (+ `.sigstore`) beside the unsigned `.ipa`.
- Hashes of the unsigned payloads, and of the native libraries on their own, are
  **not published** yet. Proposed in the pull request that added this page.

## Verification of this page

The Rust crate below was rebuilt on a Mac; see the result.

### Verification result

Rust crate `native/birdo-pq-ios` (the iOS and macOS post-quantum engine) built on macOS 26.5.2 (arm64,
Apple M3, Xcode 26.6) with rustc 1.96.0, `cargo build --locked --release --lib` for the host target
`aarch64-apple-darwin`, `target/` removed before each build. Static library `libbirdo_pq_ios.a`:

| Build | Directory | SHA-256 |
|---|---|---|
| 1 | A | `56f9cacb4efa6a85bccdeb6e83b1098f021eee6394a8ddf0a663e4acadd2f452` |
| 2 | A | `56f9cacb4efa6a85bccdeb6e83b1098f021eee6394a8ddf0a663e4acadd2f452` |
| 3 | B (a different path, copied tree) | `56f9cacb4efa6a85bccdeb6e83b1098f021eee6394a8ddf0a663e4acadd2f452` |

All identical. This is one crate for one host target on one machine. The Android `.so`, the
other Apple targets (iOS device, simulator, x86_64), the Go `libwg-go.a`, any Xcode or Gradle
output were **not** rebuilt for this page.

## Gaps we know about

- No published unsigned-payload hashes.
- The Android NDK is not pinned until #601 lands; the SDK build-tools and runner
  images are never pinned.
- No `SOURCE_DATE_EPOCH` or path remapping in the Rust or Go builds.
- No Gradle build was run for this page: Gradle is not run on the machine that
  produced it, so the Android APK/AAB paths are documented from `android.yml`, not
  re-executed.
- The iOS and macOS steps were not rebuilt end to end here; only the Rust
  `BirdoPQNative` crate was.
