# Code Signing with Sigstore

## Overview

Birdo VPN Mobile Client uses [Sigstore](https://www.sigstore.dev/) for keyless code signing
of release artifacts. Every APK, AAB, and iOS build artifact produced by CI is signed with
`cosign sign-blob` using GitHub's OIDC identity token.

This produces a `.sigstore` bundle containing:

- A **Fulcio certificate** proving the artifact was built by GitHub Actions from this repo
- A **Rekor transparency log entry** -- a tamper-proof public record of the signing event

## How It Works

### Android

```
GitHub Actions triggers
  -> Gradle builds release APK + AAB
  -> Android Keystore signs the APK/AAB (required by Android OS)
  -> cosign sign-blob --yes --bundle <file>.sigstore <file>
    -> Fulcio issues short-lived cert (GitHub OIDC identity)
    -> Signature recorded in Rekor transparency log
    -> .sigstore bundle uploaded as CI artifact
```

Android APKs and AABs are signed with the upload keystore (`birdo-release.jks`),
which CI fetches from **Azure Key Vault over OIDC** (`azure/login` +
`az keyvault secret show` in `android.yml`) — it is NOT a GitHub secret. Play
App Signing re-signs the AAB with the app signing key on Google's side
(`docs/PLAY-APP-SIGNING.md`).
Sigstore is layered **on top** of Android signing to provide independent provenance
verification -- you can verify which repo and workflow produced the APK, not just
that it was signed with our key.

### iOS / macOS

Apple Developer Program enrolment is complete and both Apple trains are
signed: on every `android-v*` tag `ios.yml`'s `release-ios` job codesigns the
`.ipa` and uploads it to App Store Connect / TestFlight (live since
2026-07-31), and `macos.yml`'s `release-macos` job does the same for the Mac
App Store `.pkg` on `mac-v*` tags. The *unsigned* `.ipa` is still attached to
the GitHub release alongside, for sideload verification. Which identities and
profiles each job uses is in `docs/RELEASE-SECRETS.md`.

## Two Layers of Signing (Android)

| Layer | Purpose | Verifier |
|-------|---------|----------|
| **Android Keystore** | Required by Android OS to install APKs | Android OS verifies on install |
| **Sigstore** | Proves the APK was built from this repo's CI | Users verify with `cosign` |

The Android signing key proves the APK was signed by us. Sigstore proves it was
built from a specific commit in this repository by GitHub Actions -- not built locally
or tampered with after signing.

## Google Play vs Direct APK

- **Google Play:** Google re-signs the app with their own key (App Signing by Google Play).
  The Sigstore signature applies to the AAB uploaded to Play Console, not the final APK
  distributed by Google Play.
- **Direct APK (GitHub Releases):** The APK is signed with our Android Keystore AND
  has a Sigstore bundle. Users can verify both.

## Verifying Signatures

See [VERIFICATION.md](./VERIFICATION.md) for step-by-step instructions.

Quick verify:

```bash
cosign verify-blob \
  --bundle BirdoVPN-release.apk.sigstore \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp "github.com/BirdoVPN/" \
  BirdoVPN-release.apk
```

## CI Configuration

| Workflow | Artifacts Signed |
|----------|-----------------|
| `android.yml` (push to main) | Release APK + Release AAB + SHA256SUMS.txt |
| `ios.yml` | Unsigned `.ipa` + SHA256SUMS attached to the release with Sigstore bundles; a separately codesigned `.ipa` goes to TestFlight |

Both workflows use pinned action SHAs and minimal permissions.
The `id-token: write` permission is required for Sigstore's Fulcio OIDC flow.
