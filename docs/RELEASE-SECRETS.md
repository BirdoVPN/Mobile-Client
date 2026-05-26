# Mobile Release Secrets

> Production secrets required by the GitHub Actions workflows in
> `.github/workflows/android.yml` and `.github/workflows/ios.yml`. Do **not**
> commit any value listed below to the repository.

---

## Android — APK release (no Play Store)

Birdo VPN for Android is distributed as a **signed APK from GitHub Releases**
linked from the website. There is no Google Play Store / AAB upload pipeline.

### One-time setup

1. **Generate a release keystore** (only once for the lifetime of the app —
   losing it forces every user to uninstall before updating):

   ```bash
   keytool -genkeypair -v \
     -keystore birdo-release.jks \
     -alias birdo-vpn \
     -keyalg RSA -keysize 4096 \
     -validity 25000 \
     -storetype JKS
   ```

2. **Upload to Azure Key Vault** under the secret name
   `android-keystore-base64` (and the four companion secrets — see
   `android.yml` header). The release job retrieves them via OIDC; **no plain
   GitHub secrets are needed for signing**.

### GitHub variables (Settings → Secrets and variables → Variables)

| Variable                | Source                                                 |
| ----------------------- | ------------------------------------------------------ |
| `AZURE_KEY_VAULT_NAME`  | Azure Key Vault name, e.g. `birdovpn-keyvault`.        |

> The package name (`app.birdo.vpn`) is hard-coded in `android.yml` and
> `app/build.gradle.kts`.

---

## iOS — TestFlight upload

The workflow `release-ios` job codesigns the `.ipa` and pushes it to App Store
Connect / TestFlight on every `ios-v*` tag.

### One-time setup

1. **Apple Distribution certificate** (Xcode → Settings → Accounts → "Manage
   Certificates" → "+" → **Apple Distribution**). Export as `.p12` with a
   strong password.

2. **Provisioning Profile**: Apple Developer → Certificates, Identifiers &
   Profiles → Profiles → "+" → **App Store** distribution → bundle ID
   `app.birdo.vpn` → select the certificate → Download `.mobileprovision`.

3. **App Store Connect API key**: App Store Connect → Users and Access → Keys →
   "+" → grant "App Manager" role. Download the `.p8` (one-time only). Note
   the **Key ID** and **Issuer ID** shown on the page.

### Encoding for GitHub secrets

```bash
# Distribution cert (.p12)
base64 -i Certificates.p12 -o cert.b64

# Provisioning profile
base64 -i BirdoVPN_AppStore.mobileprovision -o prof.b64

# App Store Connect API .p8
base64 -i AuthKey_ABC123XYZ.p8 -o api.b64
```

### GitHub secrets

| Secret name                    | Value                                  |
| ------------------------------ | -------------------------------------- |
| `APPLE_DIST_CERT_BASE64`       | Contents of `cert.b64`                 |
| `APPLE_DIST_CERT_PASSWORD`     | Password used when exporting the .p12  |
| `APPLE_PROVISIONING_PROFILE`   | Contents of `prof.b64`                 |
| `APPLE_KEYCHAIN_PASSWORD`      | Random ≥32-char string (any value)     |
| `APPSTORE_API_KEY_ID`          | e.g. `ABC123XYZ`                       |
| `APPSTORE_API_ISSUER_ID`       | UUID from App Store Connect            |
| `APPSTORE_API_KEY_BASE64`      | Contents of `api.b64`                  |

### GitHub variables

| Variable           | Value                              |
| ------------------ | ---------------------------------- |
| `APPLE_TEAM_ID`    | 10-character team ID (Membership)  |
| `APPLE_BUNDLE_ID`  | `app.birdo.vpn`                    |

### Renewal calendar

| Item                         | Lifetime | Action on expiry                                           |
| ---------------------------- | -------- | ---------------------------------------------------------- |
| Apple Distribution cert      | 1 year   | Re-export `.p12`, update `APPLE_DIST_CERT_BASE64`          |
| Provisioning profile         | 1 year   | Regenerate, update `APPLE_PROVISIONING_PROFILE`            |
| App Store Connect API key    | No expiry; revoke on staff change |
| Android keystore             | 25k days | **DO NOT EXPIRE** — keystore loss = fork the app           |

---

## Linux — GPG release signing

The desktop workflow `build-linux.yml` produces `.asc` detached signatures for
`.deb`, `.AppImage`, and `SHA256SUMS.txt` alongside the existing Sigstore
bundles.

### One-time setup

```bash
# Generate a long-lived RSA-4096 release key (no expiry)
gpg --full-generate-key
# Choose: (1) RSA and RSA, 4096 bits, 0 = no expiry
# Real name: BirdoVPN Release Signing Key
# Email:     releases@birdo.app

# Export the long key ID
KEYID=$(gpg --list-secret-keys --keyid-format long releases@birdo.app \
        | awk '/^sec/{print $2}' | cut -d'/' -f2)
echo "$KEYID"

# Export the private key (ASCII-armoured) for GitHub secret
gpg --armor --export-secret-keys "$KEYID" > linux-release-private.asc

# Export the public key for publication on https://birdo.app/.well-known/
gpg --armor --export "$KEYID" > linux-release-public.asc
```

### GitHub secrets

| Secret name                | Value                                              |
| -------------------------- | -------------------------------------------------- |
| `LINUX_GPG_PRIVATE_KEY`    | Contents of `linux-release-private.asc`            |
| `LINUX_GPG_PASSPHRASE`     | The passphrase you set during `--full-generate-key` |
| `LINUX_GPG_KEY_ID`         | Long key ID from `$KEYID` above                    |

### Public-key publication

Copy `linux-release-public.asc` to `birdo-web/public/.well-known/birdo-release.asc`
and ship it in the next web release. Users can verify a download with:

```bash
curl -O https://birdo.app/.well-known/birdo-release.asc
gpg --import birdo-release.asc
gpg --verify birdo-vpn_1.2.0_amd64.deb.asc birdo-vpn_1.2.0_amd64.deb
```
