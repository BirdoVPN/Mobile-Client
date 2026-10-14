# Verifying Birdo VPN Mobile Downloads

All release APKs and AABs are signed with [Sigstore](https://www.sigstore.dev/) using
keyless signing from GitHub Actions. This lets you cryptographically verify that a
download was built from this repository's source code -- not tampered with.

## Quick Verify

```bash
# 1. Install cosign (https://docs.sigstore.dev/cosign/system_config/installation/)
#    Windows:  winget install sigstore.cosign
#    macOS:    brew install cosign
#    Linux:    go install github.com/sigstore/cosign/v2/cmd/cosign@latest

# 2. Download the APK AND its .sigstore bundle from the GitHub Release

# 3. Verify
cosign verify-blob \
  --bundle BirdoVPN-release.apk.sigstore \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp "github.com/BirdoVPN/" \
  BirdoVPN-release.apk
```

If valid, you'll see:
```
Verified OK
```

## Verify Checksums

Each release includes a `SHA256SUMS.txt` that is itself Sigstore-signed:

```bash
# 1. Verify the checksums file is authentic
cosign verify-blob \
  --bundle SHA256SUMS.txt.sigstore \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp "github.com/BirdoVPN/" \
  SHA256SUMS.txt

# 2. Verify file checksums
# Linux/macOS:
sha256sum -c SHA256SUMS.txt

# PowerShell:
Get-Content SHA256SUMS.txt | ForEach-Object {
    $parts = $_ -split '  '
    $expected = $parts[0]
    $file = $parts[1]
    $actual = (Get-FileHash -Path $file -Algorithm SHA256).Hash.ToLower()
    if ($actual -eq $expected) {
        Write-Host "OK: $file" -ForegroundColor Green
    } else {
        Write-Host "MISMATCH: $file" -ForegroundColor Red
    }
}
```

## Source of the Stealth Mode engine (Android)

The Android app ships Xray-core as `libxray.so`: the unmodified Xray-core
program (MPL-2.0), which links Go modules under the GPL-3.0 (sagernet/sing,
sing-shadowsocks), the LGPL-3.0 (juju/ratelimit), the MPL-2.0 (xtls/reality)
and permissive licences. Every Android release publishes the program's complete
corresponding source as `xray-core-<xray version>-corresponding-source.tar.gz`
(about 117 MB), never as a draft, at the release tagged
`xray-source-android-v<app version>`:

    https://github.com/BirdoVPN/Mobile-Client/releases/download/xray-source-android-v<app version>/xray-core-<xray version>-corresponding-source.tar.gz

(the app's licence notices give this URL; the `android-v<app version>` release
carries the same file). Its SHA-256 is in that release's `SHA256SUMS.txt`, so it
is covered by the signatures above. It contains exactly:

- `Xray-core-<xray version>.tar.gz`: `git archive` of the pinned Xray-core
  commit, whose git tree is `XRAY_SOURCE_TREE` in
  `scripts/provision_xray_engines.sh`;
- `go-modules/<module>@<version>.zip`: the proxy.golang.org zip of EVERY Go
  module the four shipped engines link (GPL-3.0 section 1 covers the whole
  combined program, not only its copyleft parts), byte for byte;
- `provision_xray_engines.sh`: the script that fetched the two upstream engines
  (arm64-v8a, x86_64) and built the other two (armeabi-v7a, x86);
- `README.txt`: every file's SHA-256, Go checksum and licence; how each engine
  was made (the upstream XTLS asset and the Go version XTLS built it with, or
  the Go toolchain, Android NDK revision and exact `go build` command BirdoVPN
  used); and where the Go toolchain's source is.

Not in it: the Go toolchain. The Go standard library and runtime (BSD-3-Clause)
are linked into every engine; their source is the Go source distribution of the
version the README names, `https://go.dev/dl/<go version>.src.tar.gz`.

It is built by `scripts/xray_corresponding_source.py`, which refuses unless the
engines link exactly the modules and versions in `scripts/xray-go-modules.txt`,
those versions are what Xray-core's `go.mod` requires at the pinned commit,
every zip matches its pinned SHA-256 and its Go checksum equals Xray-core's
`go.sum` and the engines' build info, the upstream engines carry the pinned
commit's stamp and Go version, and BirdoVPN's engines were built with the pinned
Go (`XRAY_GO_VERSION`). The output is deterministic (fixed tar headers, no
timestamps): given the same four engines and the build record
`provision_xray_engines.sh` wrote, it is byte-identical, and pull-request CI
builds it twice to prove that. To check a release without its build record,
compare the unpacked files instead: each one's SHA-256 is in its `README.txt`.

## What Does This Prove?

| Guarantee | How |
|-----------|-----|
| **Built from this repo** | Fulcio certificate contains the GitHub repo URL and workflow |
| **Not tampered with** | Cosign verifies the cryptographic signature matches the file |
| **Publicly auditable** | Every signing event is recorded in [Rekor](https://search.sigstore.dev/) |
| **No trust in us needed** | You verify against Sigstore's public infrastructure, not our keys |

## Inspect the Certificate

To see exactly which commit and workflow produced an APK:

```bash
cosign verify-blob \
  --bundle BirdoVPN-release.apk.sigstore \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp "github.com/BirdoVPN/" \
  --output-certificate cert.pem \
  BirdoVPN-release.apk

openssl x509 -in cert.pem -noout -text | grep -A1 "Subject Alternative Name"
```

This shows the GitHub Actions identity that signed the artifact, including the
repository, workflow, and ref.

## Lookup in Rekor

Every signing event is recorded in Sigstore's public transparency log:

1. Go to [https://search.sigstore.dev/](https://search.sigstore.dev/)
2. Search by the artifact hash or the email/identity from the certificate
3. You'll see the full log entry including timestamp, signature, and certificate

## Google Play vs GitHub APK

If you installed from **Google Play**, Google re-signs the app with their own key
(App Signing by Google Play). The Sigstore verification only applies to APKs
downloaded directly from GitHub Releases.

To verify a Google Play install is authentic, check the signing certificate in
Android Settings > Apps > Birdo VPN > Advanced > App details.
