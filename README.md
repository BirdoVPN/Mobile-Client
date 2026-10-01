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
  until it reconnects. If reconnecting keeps failing, the app stops blocking
  (Android, iOS/macOS and Windows alike) and says so. Android: protection
  applies while BirdoVPN's VPN service is running; if a crash or Android
  closes the app, nothing is blocked until it starts again. For a block
  Android itself enforces, turn on Always-on VPN and "Block connections
  without VPN" in Android's VPN settings (supported, see below).
- **Split Tunneling** -- Per-app VPN routing (Android)
- **Auto-reconnect** -- the tunnel re-establishes itself after network changes
  and drops. Android notices a dead tunnel from wg-go's handshake and traffic
  counters: within about 20–30 s while traffic is flowing, about a minute and
  a half on an idle phone (its once-a-minute heartbeat is then the only
  traffic to go unanswered), and 10–20 s once no network is left under it.
  It shows "Reconnecting…", and waits for the network without spending
  retries while there is none; a captive portal is said as one. Before the
  first re-dial it asks the server once, around the tunnel, why the tunnel
  died: a connection the server ended on purpose (another device took the
  slot, a Disconnect from another device, the Free plan's data used up)
  stays down with the reason instead of being dialled back.
- **Always-on VPN (Android)** -- supported: `SUPPORTS_ALWAYS_ON=true`, pinned
  by `ApiLevel36ContractTest`. When Android starts the service at boot, on
  unlock or when the setting changes, the service puts its block up first and
  then reconnects on its own to the last server (`SystemStartPolicy`). An app
  update restores the session (`PackageReplacedReceiver`). After a crash the
  service is NOT restarted by Android (`START_NOT_STICKY`, live on API 35).
  Instead, a connection you left on comes back, block first, the next time
  BirdoVPN's process starts for any reason: you open the app, tap the widget
  or the Quick Settings tile, or a home-screen widget refresh starts it. That
  is not only after a crash: it also happens after a reboot without Always-on
  (launchers refresh their widgets at boot) and after a Force stop once the app
  is next opened. Disconnect in BirdoVPN first if you do not want the
  connection back. A tap on the tile or widget that starts the process joins
  that resume rather than cancelling it. With "Block connections without VPN"
  on, Android keeps blocking through the gap.
- **The app's own traffic** -- while connected, BirdoVPN's own requests (sign-in,
  the server list, the once-a-minute heartbeat, opted-in crash reports) go
  through the VPN tunnel like every other app's. They go around it, from your
  own IP address, only where they have to: before the first tunnel is up,
  behind the kill switch's block and while reconnecting; for the one heartbeat
  that asks the server why a dead tunnel died; and for an account deletion,
  whose answer would otherwise be lost with the tunnel the server ends. Its API
  requests then resolve names over DNS-over-HTTPS (Cloudflare). Exception:
  during a Stealth Mode session the app's own traffic stays outside the
  tunnel, as it did before, because Xray runs as a child process of the app
  and cannot be routed into the tunnel it carries.
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
| [Android CI](.github/workflows/android.yml) | Push to `main`, `android-v*` tags, PRs | Lint, test, build APK + AAB, Sigstore sign |
| [macOS CI](.github/workflows/macos.yml) | `mac-v*` tags, PRs touching `iosApp/`, `shared/`, `native/` | Build the macOS app, UI screenshots |
| [iOS CI](.github/workflows/ios.yml) | `android-v*` tags, manual dispatch | Build the iOS app + .ipa, simulator tests |

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
  android.yml          Android CI: lint, test, build, sign
  ios.yml              iOS CI: shared framework, build, test
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
