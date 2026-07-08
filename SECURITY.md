# Security Policy

## Reporting a Vulnerability

If you discover a security vulnerability in BirdoVPN's mobile clients, **please do
not open a public GitHub issue**. Instead, report it privately so we can fix it
before disclosure.

### Preferred channel

Use GitHub's private vulnerability reporting:

> Repository -> **Security** tab -> **Report a vulnerability**

### Alternate channel

Email: **security@birdo.app** (PGP key available on request).

Please include:

- A description of the vulnerability and its potential impact.
- Steps to reproduce, ideally with a minimal proof-of-concept.
- Affected version(s) / commit hash(es).
- Whether you are willing to be credited in the public disclosure.

### What to expect

| Stage                | Target time            |
| -------------------- | ---------------------- |
| Acknowledgement      | Within 48 hours        |
| Initial assessment   | Within 7 days          |
| Patch development    | Severity-dependent     |
| Public disclosure    | After patch is shipped |

We follow a 90-day coordinated-disclosure window by default, and may request an
extension for complex issues. We will keep you informed throughout.

## Scope

In scope:

- Source code under this repository (Android `app/`, iOS `iosApp/`, KMP
  `shared/`).
- The CI/CD workflows under `.github/workflows/`.
- Build/release tooling that ships in shipped artifacts.

Out of scope (please report to the appropriate vendor):

- Vulnerabilities in third-party dependencies (report upstream first; we will
  bump versions promptly once a patch is released).
- Backend / infrastructure (separate repository).
- Issues requiring physical access to an unlocked device.
- Self-reported social-engineering scenarios.

## Hardening Practices

- All release secrets (Android keystore, Apple .p12, App Store Connect API
  keys) are stored in GitHub Secrets / Azure Key Vault — never in the
  repository.
- All push and pull-request events run **CodeQL** (Kotlin + Swift) and
  **Gitleaks** (full history weekly).
- All shipped releases are **signed** end-to-end:
  - Android APK/AAB: V1+V2+V3+V4 schemes via the keystore retrieved from
    Azure Key Vault using OIDC (no long-lived credentials in CI).
  - Android artifact attestation via **Sigstore** (Fulcio OIDC).
  - iOS: Apple Distribution certificate codesigning + notarization,
    uploaded to TestFlight via App Store Connect API.
- TLS to Birdo backends is **certificate-pinned** on both platforms; pin
  expiry is enforced as a CI gate.
- Tunnel secrets (WireGuard private key, PSK) are stored in
  `EncryptedSharedPreferences` (Android) / Keychain with
  `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` + the App Group access
  group (iOS), and zeroed/deleted as soon as the wg-go runtime consumes
  them.

Thank you for helping keep BirdoVPN users safe.
