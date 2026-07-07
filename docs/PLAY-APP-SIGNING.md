# Google Play App Signing — `app.birdo.vpn`

**Status: ENROLLED / ACTIVE (auto-enabled).** Play App Signing turned on automatically
when the first AAB was uploaded to the Play Console. In **Play Console → App integrity →
App signing** (and on the "Protected with Play" page), *Protect app signing key → Releases
signed by Play* shows a green ✓. There is nothing to "enrol" — it is already done.

## The two keys

Play App Signing splits signing into two keys:

| Key | Held by | Signs | Certificate below |
|-----|---------|-------|-------------------|
| **App signing key** | Google (secure server, not accessible) | the APKs Google *delivers* to users | **App signing key certificate** |
| **Upload key** | us — `birdo-release.jks` (see `RELEASE-SECRETS.md`) | the AAB we *upload* to Play | **Upload key certificate** |

We sign the AAB with the **upload key**; Google verifies it, strips it, and re-signs the
delivered artifact with the **app signing key**. So the certificate that matters for anything
that must match the *installed* app (App Links, Credential Manager, API providers) is the
**app signing key certificate**, NOT the upload key.

## Certificate fingerprints (all public)

These are public certificates — they are published in `assetlinks.json` and shown to anyone in
the Play Console. Safe to keep in-repo. Read from Play Console → App integrity → App signing.

### App signing key certificate (Google's — use this for App Links / API providers)

```
SHA-256  05:9F:CD:5A:87:15:7F:29:3A:93:CD:00:B8:8D:8B:AD:30:97:8A:0A:46:41:AA:70:FF:04:88:83:41:E4:59:A0
SHA-1    E2:A9:08:16:B0:22:68:4F:B0:30:33:9E:78:30:5D:87:F5:7E:A7:2C
MD5      2A:A2:90:E7:74:EC:65:74:B6:DF:FA:E8:87:01:12:FC
```

### Upload key certificate (ours — `birdo-release.jks`)

```
SHA-256  F1:67:8A:E9:8A:19:96:54:B1:A7:E1:A2:1F:38:61:CC:6B:EB:74:90:B7:3F:08:68:5E:90:7A:55:A8:1F:2A:A9
SHA-1    82:AF:C7:EE:76:F9:15:E9:69:26:1F:78:9B:21:80:41:20:F6:04:0D
MD5      7B:6A:E9:3B:17:88:BF:EE:4B:4F:8C:D8:15:6A:82:CD
```

## Where these are used

1. **Digital Asset Links** — `birdo-web/public/.well-known/assetlinks.json` (served at
   `https://birdo.app/.well-known/assetlinks.json`). Both `handle_all_urls` (App Links
   auto-verification) and `get_login_creds` (Credential Manager) statements list **both**
   fingerprints: the **app signing** cert (so the Play-distributed app verifies) and the
   **upload** cert (so locally-built / internal-test APKs verify too). Wired in birdo-web
   PR #186.
2. **Signing allow-list** (defense-in-depth, optional) — `BIRDO_SIGNING_CERT_FINGERPRINT`
   (backend, added in PR #151). If enabled, set it to the **app signing** SHA-256 above.

## If the upload key is ever lost/compromised

Do **not** create a new app — the package name `app.birdo.vpn` is permanent. Use
**Play Console → App integrity → App signing → Request upload key reset**. Google keeps
signing delivered apps with the same (unchanged) app signing key, so existing installs keep
updating; only our upload cert changes. Update the **upload** cert line in `assetlinks.json`
afterward; the app-signing line never changes.

## Related

- `CODE_SIGNING.md` — Sigstore provenance layered on top of Android signing
- `RELEASE-SECRETS.md` — where `birdo-release.jks` / keystore secrets live
- `PLAY-LAUNCH-PLAN.md` — full Play launch checklist
