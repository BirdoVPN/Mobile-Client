# Birdo VPN Mobile Client

The universal mobile client for [Birdo VPN](https://birdo.app) -- built once with **Kotlin Multiplatform**, running natively on **Android** and **iOS**.

| Platform | Min Version | Framework | Store |
|----------|-------------|-----------|-------|
| Android  | 10 (API 29) | Kotlin + Jetpack Compose | [Google Play](https://play.google.com/store/apps/details?id=app.birdo.vpn) |
| iOS      | 17.0        | KMP + SwiftUI            | App Store |

> **Looking for Windows, macOS, or Linux?** See the [Desktop Client](https://github.com/BirdoVPN/Desktop-Client).

---

## Architecture

```
birdo-client-mobile/
  shared/            Kotlin Multiplatform shared module (Android + iOS)
  app/               Android app (Jetpack Compose UI)
  iosApp/            iOS app (SwiftUI + PacketTunnel Network Extension)
  store-assets/      Play Store and App Store screenshots
```

### Shared Module (`shared/`)

The KMP shared module contains all platform-agnostic logic:

- WireGuard tunnel management and key exchange
- API client (authentication, server list, session management)
- Connection state machine
- Certificate pinning and transport security
- Encryption utilities (ChaCha20-Poly1305 via WireGuard)

Compiled to:
- **Android:** Kotlin/JVM library linked directly into the app module
- **iOS:** Native `BirdoShared.framework` (arm64) consumed by the SwiftUI app

### Android App (`app/`)

- **UI:** Jetpack Compose with Material 3
- **DI:** Dagger Hilt
- **VPN:** Android `VpnService` API with kernel WireGuard
- **Security:** Android Keystore for credential storage, biometric authentication
- **Distribution:** Google Play (AAB) + direct APK from GitHub Releases

### iOS App (`iosApp/`)

- **UI:** SwiftUI (Swift 6.0)
- **VPN:** Network Extension PacketTunnel provider
- **Security:** iOS Keychain for credential storage, Face ID / Touch ID
- **Build:** XcodeGen (`project.yml`) generates the Xcode project
- **Distribution:** App Store + TestFlight

---

## Features

- **WireGuard Protocol** -- ChaCha20-Poly1305 encryption with Curve25519 + Post-Quantum key exchange
- **Kill Switch** -- Blocks all traffic if the tunnel drops
- **Split Tunneling** -- Per-app VPN routing (Android)
- **Auto-reconnect** -- the tunnel re-establishes itself after network changes
  while the app's service is running. (Android's system "Always-on VPN" toggle
  is deliberately NOT offered: the service cannot yet self-establish a tunnel
  from a headless boot start, so lockdown would strand users after every
  reboot — `SUPPORTS_ALWAYS_ON=false` in the manifest, with the TODO to flip
  it once headless reconnect exists.)
- **Biometric Lock** -- Fingerprint / Face ID app lock
- **Quick Settings Tile** -- Toggle VPN from the notification shade (Android)
- **Home Screen Widgets** -- Glanceable status with one-tap connect (iOS)
- **On-Demand Connect** -- Rules-based activation on specific networks (iOS)
- **iCloud Sync** -- Sync settings across Apple devices (iOS)
- **Stealth Mode** -- XRAY Reality obfuscation to bypass DPI
- **Multi-Hop** -- Route through multiple servers for extra anonymity

---

## Building

### Prerequisites

- JDK 17 (Temurin recommended)
- Android Studio Ladybug or later
- Xcode 16+ (for iOS builds)
- [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`)

### Android

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires signing config)
./gradlew assembleRelease

# Run tests
./gradlew testDebugUnitTest
```

### iOS

```bash
# Build KMP shared framework for iOS
./gradlew :shared:linkReleaseFrameworkIosArm64

# Generate Xcode project
cd iosApp && xcodegen generate

# Build (unsigned)
xcodebuild build \
  -project BirdoVPN.xcodeproj \
  -scheme BirdoVPN \
  -destination 'generic/platform=iOS' \
  -configuration Release \
  CODE_SIGN_IDENTITY="-" \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGNING_ALLOWED=NO
```

### Environment Setup

```bash
cp local.properties.example local.properties
# Edit local.properties with your Android SDK path
```

---

## Verifying Downloads

Every release APK and AAB is signed with [Sigstore](https://www.sigstore.dev/) using keyless signing from GitHub Actions. See [docs/VERIFICATION.md](docs/VERIFICATION.md) for instructions.

```bash
cosign verify-blob \
  --bundle BirdoVPN-release.apk.sigstore \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp "github.com/BirdoVPN/" \
  BirdoVPN-release.apk
```

---

## CI/CD

| Workflow | Trigger | Platforms |
|----------|---------|-----------|
| [Android CI](.github/workflows/android.yml) | Push to main/develop, PRs | Lint, test, build APK + AAB, Sigstore sign |
| [iOS CI](.github/workflows/ios.yml) | Push to main/develop, PRs | Build KMP shared framework, build iOS app, simulator tests |

All workflows use pinned action SHAs, minimal permissions, and Sigstore cosign for artifact signing.

---

## Tech Stack

| Component | Technology |
|-----------|------------|
| Shared Logic | Kotlin Multiplatform 2.1 |
| Android UI | Jetpack Compose + Material 3 |
| iOS UI | SwiftUI (Swift 6.0) |
| VPN Protocol | WireGuard (kernel on Android, Network Extension on iOS) |
| Encryption | ChaCha20-Poly1305 |
| Key Exchange | Curve25519 + Post-Quantum (Rosenpass) |
| DI (Android) | Dagger Hilt 2.53 |
| Build (Android) | Gradle 8.7 + AGP 8.7.3 |
| Build (iOS) | XcodeGen + Xcode 16 |
| Serialization | kotlinx.serialization |
| Code Signing | Sigstore (keyless) + Android Keystore |

---

## Project Structure

```
.github/workflows/
  android.yml          Android CI: lint, test, build, sign
  ios.yml              iOS CI: shared framework, build, test
app/
  src/main/            Android app source (Kotlin + Compose)
  build.gradle.kts     Android app build config
shared/
  src/commonMain/      KMP shared code (both platforms)
  src/androidMain/     Android-specific implementations
  src/iosMain/         iOS-specific implementations
  build.gradle.kts     KMP module build config
iosApp/
  iosApp/              SwiftUI app source
  PacketTunnel/        WireGuard Network Extension
  project.yml          XcodeGen project spec
store-assets/          Store screenshots and graphics
docs/
  CODE_SIGNING.md      How artifacts are signed
  VERIFICATION.md      How to verify downloads
  store-listing.md     Google Play / App Store listing
build.gradle.kts       Root Gradle config
settings.gradle.kts    Module declarations
version.properties     Centralized version (1.1.0)
```

---

## Security

- All network traffic encrypted with WireGuard (ChaCha20-Poly1305)
- Certificate pinning on all API connections
- Credentials stored in Android Keystore / iOS Keychain
- No activity logs, no DNS logs, no traffic inspection
- Release artifacts signed with Sigstore for provenance verification
- CI uses pinned action SHAs and minimal permissions
- ProGuard/R8 obfuscation on release builds (Android)

For vulnerability reports, email **security@birdo.app**.

---

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).

---

**[birdo.app](https://birdo.app)** -- A secure and independent VPN
