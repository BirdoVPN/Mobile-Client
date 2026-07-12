# iOS release — how it works and what it deliberately does NOT do

## Current state (owner decision, 2026-07-12)

iOS is **fully built and released alongside Android, but not published to any
store**. Every `android-v*` tag:

1. builds the KMP shared framework for iOS,
2. builds `BirdoPQNative.xcframework` from the Rust ML-KEM crate
   (`native/birdo-pq-ios`, cached by source hash — it is NOT committed),
3. builds the app + PacketTunnel extension **unsigned**
   (`CODE_SIGNING_ALLOWED=NO`),
4. packages `BirdoVPN-iOS-v<version>-unsigned.ipa`, and
5. attaches it to the **same draft GitHub Release** as the Android artifacts —
   one "Publish" click ships both platforms.

Versioning is single-source: `version.properties` stamps
`CFBundleShortVersionString` and `CFBundleVersion`
(= `major*10000 + minor*100 + patch`, identical to Android's versionCode).

## What the unsigned IPA can and cannot do

- It proves the app assembles end-to-end and is the re-signable artifact shape.
- It **cannot run a VPN tunnel on anyone's device**. Packet-tunnel
  (NetworkExtension) entitlements require a **paid Apple Developer Program
  membership** — free/sideload signing (AltStore etc.) does not grant them.
  This is an Apple platform rule, not a build defect.

## TestFlight / App Store — dormant by design

The signing + TestFlight job (`release-ios` in ios.yml) is hard-disabled with
`if: ${{ false }}`. Repo secrets `APPLE_*` / `APPSTORE_*` exist (created
2026-04-17) but are **presumed stale** — do not trust them without
re-verifying. Nothing in CI can publish to Apple while the `if: false` stands.

## The day the owner wants the App Store (checklist)

1. Apple Developer Program membership active (£79/$99 per year).
2. In the developer portal: bundle IDs `app.birdo.vpn` and
   `app.birdo.vpn.tunnel`, BOTH with the **Network Extensions**
   (packet-tunnel-provider) capability and a shared **App Group**.
3. Regenerate the distribution cert + provisioning profiles; replace the
   `APPLE_*` secrets; set repo variables `APPLE_TEAM_ID` and `APPLE_BUNDLE_ID`.
4. Prefix the keychain-access-group / App Group in both `.entitlements` files
   with the real TEAMID (the unsigned build tolerates the unprefixed group;
   a signed device build will not).
5. Remove `if: ${{ false }}` from the `release-ios` job.
6. **Billing is an OWNER decision**: payments are website-only (Polar). An
   App Store listing that links out to external purchase has its own Apple
   rules (regional external-purchase-link entitlements) — do not wire any
   link-out or IAP without revisiting that decision. The app today only
   *displays* the plan and opens birdo.app.

## Guardrails that must stay green

- `BirdoVPNTests/APIContractTests.swift` pins the wire protocol (endpoint
  shapes, exact `ConnectRequest` field names). This is the Android
  "@Serializable lost, CI green, broken at runtime" lesson, applied to iOS.
- Attestation: iOS sends `integrityToken: nil` (no Play Integrity on iOS).
  If the backend attestation policy is ever set to ENFORCE, iOS clients will
  be rejected until App Attest is implemented — coordinate before flipping
  that policy.
- Stealth (Xray/XTLS) does not exist on iOS. The Settings toggle is shown
  disabled; `stealthMode: false` is hard-sent. Do not "enable" the toggle
  without porting the engine.
