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

The KMP shared module holds the cross-platform **wire types and pure helpers**
— not the tunnel:

- `model/Models.kt` — the serialised API request/response types
- `util/{FlagUtils,FormatUtils,InputValidator}.kt` — formatting and validation
- `Platform.kt` (+ `androidMain` / `appleMain` actuals) — clock and IP-literal
  validation

Tunnel management, the API client, the connection state machine and certificate
pinning live in each platform's own source tree (`app/`, `iosApp/`), not here.

Compiled to:
- **Android:** Kotlin/JVM library linked directly into the app module
- **iOS/macOS:** `BirdoShared.framework` is still produced and linked by
  `project.yml`, but **no Swift file imports it today** — the Apple side of the
  module is unused. Tracked as cleanup; do not rely on it.

### Android App (`app/`)

- **UI:** Jetpack Compose with Material 3
- **DI:** Dagger Hilt
- **VPN:** Android `VpnService` API with wireguard-go (`com.wireguard.android:tunnel`)
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
- **Kill Switch** -- if the tunnel drops unexpectedly, the app blocks traffic
  until it reconnects. Android: protection applies while BirdoVPN's VPN
  service is running. iOS/macOS: if reconnecting keeps failing, the app stops
  blocking.
- **Split Tunneling** -- Per-app VPN routing (Android)
- **Auto-reconnect** -- the tunnel re-establishes itself after network changes
  while the app's service is running. (Android's system "Always-on VPN" toggle
  is deliberately NOT offered: the service cannot yet self-establish a tunnel
  from a headless boot start, so lockdown would strand users after every
  reboot — `SUPPORTS_ALWAYS_ON=false` in the manifest, with the TODO to flip
  it once headless reconnect exists.)
- **Biometric Lock** -- Fingerprint / Face ID app lock
- **Quick Settings Tile** -- Toggle VPN from the notification shade (Android)
- **Home Screen Widget** -- Glanceable status with one-tap connect (Android,
  Glance)
- **Stealth Mode** -- XRAY Reality obfuscation to bypass DPI (Android)
- **Multi-Hop** -- your traffic enters one server and leaves from another, so
  sites see the exit server's address. It is not onion routing: the entry
  server can see your IP address and the destinations you connect to.
- **Crash reporting** -- Sentry, Android only, OFF unless the user turns it on
  (first screen or Settings). When on it sends crash and error reports:
  crashes, and errors when an app feature such as connecting or the kill
  switch fails, with the app and OS version and device model. No sessions, no
  account details, IP address or browsing data. The iOS/macOS app has no
  crash-reporting SDK.

---

## Building

### Prerequisites

- JDK 17 (Temurin recommended)
- Android Studio Ladybug or later
- Xcode 26+ (for iOS builds — App Store Connect requires the iOS 26 SDK)
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
| [Android CI](.github/workflows/android-ci.yml) | PRs (any base branch), push to `main` | Lint, unit tests, CodeQL, debug APK + R8, native gates |
| [Android Release](.github/workflows/android.yml) | Push to `main` (dry run), `android-v*` tags, manual dispatch | Signed APK + AAB, Sigstore + GPG, draft release, Play upload |
| [Apple CI](.github/workflows/apple-ci.yml) | PRs and `main` pushes touching `iosApp/`, `shared/`, `native/birdo-pq-ios/` | iOS simulator tests, unsigned macOS build |
| [iOS Release](.github/workflows/ios.yml) | `android-v*` tags, manual dispatch | Unsigned .ipa on the release, TestFlight upload |
| [macOS Release](.github/workflows/macos.yml) | `mac-v*` tags, manual dispatch | Mac App Store upload, UI screenshots |

All workflows use pinned action SHAs, minimal permissions, and Sigstore cosign for artifact signing.

---

## Tech Stack

| Component | Technology |
|-----------|------------|
| Shared Logic | Kotlin Multiplatform 2.4.10 |
| Android UI | Jetpack Compose + Material 3 |
| iOS UI | SwiftUI (Swift 6.0) |
| VPN Protocol | WireGuard (wireguard-go on Android, Network Extension on iOS) |
| Encryption | ChaCha20-Poly1305 |
| Key Exchange | Curve25519 + Post-Quantum (BirdoPQ v1, ML-KEM-1024) |
| DI (Android) | Dagger Hilt 2.60.1 |
| Build (Android) | Gradle 9.7.1 + AGP 9.4.0 |
| Build (iOS) | XcodeGen + Xcode 26+ |
| Serialization | kotlinx.serialization |
| Code Signing | Sigstore (keyless) + Android Keystore |

---

## Project Structure

```
.github/workflows/
  android-ci.yml       Android CI: lint, test, CodeQL, debug APK, R8, native gates
  android.yml          Android release: signed APK + AAB, draft release, Play upload
  apple-ci.yml         Apple CI: iOS simulator tests, macOS build
  ios.yml              iOS release: .ipa, TestFlight
app/
  src/main/            Android app source (Kotlin + Compose)
  build.gradle.kts     Android app build config
shared/
  src/commonMain/      KMP shared code (both platforms)
  src/androidMain/     Android-specific implementations
  src/appleMain/       Apple-specific implementations (iOS + macOS)
  build.gradle.kts     KMP module build config
iosApp/
  iosApp/              SwiftUI app source
  PacketTunnel/        WireGuard Network Extension
  project.yml          XcodeGen project spec
store-assets/          Store screenshots and graphics
docs/
  CODE_SIGNING.md      How artifacts are signed
  VERIFICATION.md      How to verify downloads
build.gradle.kts       Root Gradle config
settings.gradle.kts    Module declarations
version.properties     Centralized version (1.4.29)
```

---

## Security

- All network traffic encrypted with WireGuard (ChaCha20-Poly1305)
- Certificate pinning on all API connections
- Credentials stored in Android Keystore / iOS Keychain
- No activity logs: the VPN servers do not record the sites you visit, your
  DNS queries or your traffic. While you are connected the account system
  keeps a live session record, deleted when you disconnect and left out of our
  nightly backups. Our daily encrypted copy of the database files (kept 7 days,
  used for point-in-time recovery) can contain it as it stood at that moment
  -- see [birdo.app/privacy](https://birdo.app/privacy)
- No advertising or analytics SDKs; optional crash reporting (Android, off
  unless you turn it on)
- Release artifacts signed with Sigstore for provenance verification
- CI uses pinned action SHAs and minimal permissions
- ProGuard/R8 obfuscation on release builds (Android)

For vulnerability reports, email **security@birdo.app**.

---

## License

This project is licensed under [Creative Commons Attribution-NonCommercial 4.0
International (CC BY-NC 4.0)](LICENSE). That makes the apps source-available,
not open source: CC BY-NC is not an OSI-approved licence. The server code is
private.

---

**[birdo.app](https://birdo.app)** -- A secure and independent VPN
