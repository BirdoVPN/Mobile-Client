# iOS Testing on a Mac (No iPhone Required)

You don't need an iPhone to verify the iOS build. CI already proves it compiles, links,
and that unit tests pass on `macos-14` with Xcode 16. Locally on your Mac you can go
further: run the app in the iOS Simulator, debug it, profile it, and even side-load
it onto a friend's iPhone for free with a personal Apple ID.

This is the no-iPhone happy path.

---

## TL;DR

```bash
cd birdo-client-mobile
chmod +x setup-ios.sh && ./setup-ios.sh
# Opens Xcode. Cmd-R → app runs in iPhone 15 Pro simulator.
```

`setup-ios.sh` already does steps 1–4 for you. The rest of this guide is what to do
when something goes wrong, or when you want to side-load to a real iPhone.

---

## 0. One-time Mac setup

```bash
# 1. Xcode 16 (Mac App Store).
xcode-select --install

# 2. Homebrew tools used by the iOS build:
brew install xcodegen
brew install --cask temurin@17     # Java 17 for Gradle
```

Confirm Xcode picked the iOS 17 SDK:

```bash
xcodebuild -showsdks | grep iphone
# expect:  iphoneos17.x  /  iphonesimulator17.x
```

---

## 1. Build the shared KMP framework

The Kotlin Multiplatform `shared` module produces a per-arch `BirdoShared.framework`
that the Xcode project links against. Pick the target that matches your Mac:

```bash
cd birdo-client-mobile

# Apple Silicon (M1/M2/M3/M4):
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64

# Intel Mac:
./gradlew :shared:linkDebugFrameworkIosX64
```

Output:
- ARM Mac -> `shared/build/bin/iosSimulatorArm64/debugFramework/BirdoShared.framework`
- Intel  -> `shared/build/bin/iosX64/debugFramework/BirdoShared.framework`

For a real-device or release build:

```bash
./gradlew :shared:linkReleaseFrameworkIosArm64
# → shared/build/bin/iosArm64/releaseFramework/BirdoShared.framework
```

The Xcode project's `FRAMEWORK_SEARCH_PATHS` (in `iosApp/project.yml`) already
points at both locations.

---

## 2. Generate the Xcode project

The repo doesn't check in `.xcodeproj` — it's regenerated from `iosApp/project.yml`
by XcodeGen, the same way CI does it.

```bash
cd iosApp
xcodegen generate
open BirdoVPN.xcodeproj
```

This produces two targets:
- `BirdoVPN` — the host app (`app.birdo.vpn`)
- `PacketTunnel` — the Network Extension (`app.birdo.vpn.tunnel`)

Both depend on the `WireGuardKit` Swift package (auto-fetched by Xcode on first
open — give it a minute).

---

## 3. Run in the iOS Simulator

In Xcode:

1. Scheme picker (top-left) -> **BirdoVPN**.
2. Destination picker -> **iPhone 15 Pro** (or any 17.x simulator).
3. Hit **▶ Run** (`⌘R`).

First launch builds WireGuardKit (~1–2 min). Subsequent launches are seconds.

### What the simulator can and can't do

| Feature                          | Simulator | Real device |
|----------------------------------|:---------:|:-----------:|
| UI, navigation, animations       |         |           |
| Login, register, server list     |         |           |
| 3D globe / Compose UI rendering  |         |           |
| **Establish a real VPN tunnel**  |         |           |
| Network Extension entitlement    |         |           |
| Push notifications, biometrics   | partial   |           |

The Simulator can't create a `utun` interface, so the **Connect** button surfaces
"VPN unavailable in simulator". Everything else — login flow, server picker, theme,
animations, settings — works exactly like a device.

---

## 4. Run the iOS unit tests locally

Same suite that CI runs:

```bash
cd birdo-client-mobile
./gradlew :shared:iosSimulatorArm64Test
```

Or from Xcode: **Product ▸ Test** (`⌘U`) on the iOS scheme.

---

## 5. Side-load onto a real iPhone (free, 7-day signing)

If you want to test the actual VPN tunnel on a physical device, you don't need a
paid Apple Developer account — a free Apple ID gives you a 7-day provisioning
profile, plenty for testing.

1. Borrow an iPhone (yours, a friend's, anyone with iOS 17+).
2. On the iPhone: **Settings ▸ Privacy & Security ▸ Developer Mode** -> enable.
3. Plug it into your Mac via USB. Trust the computer when prompted.
4. In Xcode: **Settings ▸ Accounts ▸ +** -> sign in with any Apple ID.
5. Select the **BirdoVPN** target ▸ **Signing & Capabilities**:
   - Team: pick your Apple ID (Personal Team).
   - Bundle Identifier: change to something unique like `dev.<yourname>.birdo`.
   - Repeat for **PacketTunnel** with `dev.<yourname>.birdo.tunnel`.
6. Destination picker -> choose the connected iPhone.
7. **▶ Run**.

First launch on the iPhone:
- iOS will refuse to launch the unsigned app. Go to **Settings ▸ General ▸ VPN &
  Device Management ▸ \<your Apple ID\>** and tap **Trust**.
- The app will then ask permission to add a VPN configuration. Accept.

The build expires after 7 days — re-run from Xcode to refresh it.

> **Why this works without paying $99/yr:** Apple permits free personal-team signing
> with a 7-day expiry and 3-app limit. Personal teams *can* use the
> `packet-tunnel-provider` capability in development. The build won't be distributable,
> but it will run.

---

## 6. TestFlight (paid Apple Developer Program)

Required only when you want strangers to install it. Cost: $99/yr.

1. Generate distribution cert + provisioning profile in App Store Connect for
   `app.birdo.vpn` and `app.birdo.vpn.tunnel`.
2. Add these GitHub Actions secrets to `BirdoVPN/Mobile-Client`:
   - `APPLE_DIST_CERT_BASE64` — base64 of `.p12`
   - `APPLE_DIST_CERT_PASSWORD`
   - `APPLE_PROVISIONING_PROFILE` — base64 of `.mobileprovision`
   - `APPLE_KEYCHAIN_PASSWORD` — any random string
   - `APPSTORE_API_KEY_ID`
   - `APPSTORE_API_ISSUER_ID`
   - `APPSTORE_API_KEY_BASE64`
3. Add repo variables:
   - `APPLE_TEAM_ID`
   - `APPLE_BUNDLE_ID` = `app.birdo.vpn`
4. Cut a tag: `git tag ios-v1.0.0 && git push origin ios-v1.0.0`
5. The `release-ios` workflow signs and uploads to TestFlight.

---

## 7. Troubleshooting

| Symptom | Fix |
| --- | --- |
| `No such module 'BirdoShared'` | Re-run step 1 (the framework wasn't built for your arch). |
| `WireGuardKit fails to resolve` | Xcode ▸ **File ▸ Packages ▸ Reset Package Caches**. |
| `Provisioning profile doesn't include packet-tunnel-provider` (paid team) | Enable the capability in App Store Connect for `app.birdo.vpn.tunnel`. |
| Crashes immediately in simulator | Kotlin/Native exception — wrong arch. Delete `shared/build/bin/` and rebuild. |
| Simulator boots forever | `xcrun simctl shutdown all && xcrun simctl erase all`, retry. |
| `xcodegen: command not found` | `brew install xcodegen`. |
| `JAVA_HOME not set` | `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` and add to `~/.zshrc`. |

---

## Quick reference

```bash
# Full clean rebuild + open in Xcode
cd birdo-client-mobile
./gradlew :shared:clean
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
cd iosApp && xcodegen generate && open BirdoVPN.xcodeproj
# Then ⌘R in Xcode
```

That covers ~95% of the iOS testing surface from a Mac alone. The remaining 5% (real
VPN tunnel) needs a physical iPhone — borrow one for 10 minutes and use free signing.
