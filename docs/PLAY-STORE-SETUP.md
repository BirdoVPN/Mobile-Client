# Google Play Store — setup & automated release

Birdo Android (`app.birdo.vpn`) sells subscriptions **through Google Play
Billing** in the Play build (`com.android.billingclient:billing`, wired in
`billing/PlayBillingManager.kt`). The `-PplayBuild=true` AAB has a real in-app
purchase path and steers nowhere else, per Play's Payments policy. The
**sideload APK** and the **F-Droid** build cannot use Play Billing at all (the
Store will not sell to an app it did not install), so those keep the birdo.app
web-billing links, which is allowed because they are not distributed through
Play. `app/build.gradle.kts` (the `isPlayBuild` note) is the authoritative
description; external LINK-OUT steering is a separate flag (`playExternalOffers`).

The CI (`.github/workflows/android.yml`) already builds a **signed AAB** on
`android-v*` tags. The `play-upload` job publishes that AAB to Google Play — but
it stays **dormant** until you complete the one-time setup below and flip a
repository variable.

---

## Part A — One-time setup (owner; cannot be automated)

1. **Create a Google Play Developer account** — https://play.google.com/console
   ($25 one-time). Complete identity/D-U-N-S verification (can take a few days).

2. **Create the app** in Play Console: app name *Birdo VPN*, package
   `app.birdo.vpn`, type App, Free (subscriptions handled on web).

3. **Enrol in Play App Signing** (recommended). Google holds the *app signing*
   key; your existing `birdo-release.jks` becomes the **upload key** — no build
   changes needed (the CI already signs the AAB with it).

4. **First upload must be manual.** Download the latest `.aab` from a GitHub
   Release (or run the workflow once to produce it), and upload it to the
   **Internal testing** track by hand. The Play API cannot create the app or do
   the very first upload; after this, CI takes over.

5. **Complete the console-only declarations** (required before any public
   release; none of these can be scripted):
   - **Store listing**: short + full description, app icon, feature graphic,
     phone screenshots. (Marketing creative exists in `birdo-web/public/banners/`.)
   - **Privacy policy URL**: `https://birdo.app/privacy`
   - **Data safety** form: declare data collected (account email, crash logs),
     encryption in transit, no selling of data, no activity logs.
   - **Content rating** questionnaire.
   - **Target audience** (adults), **app category** (Tools), **contact details**.
   - **App access**: provide a **test login** (Google reviews behind your auth) —
     create a reviewer account on birdo.app with an active plan and put the
     credentials in *App access* instructions.
   - **VPN / sensitive permissions**: justify `BIND_VPN_SERVICE` + the
     `specialUse` foreground service (already declared in `AndroidManifest.xml`).

6. **Create the Play Developer API service account** (this is the CI credential):
   - Play Console -> *Setup -> API access* -> link/create a Google Cloud project.
   - Create a **service account** in Google Cloud, then in Play Console grant it
     access with the **Release manager** role (or a custom role with *Releases*
     for the relevant tracks).
   - Download the service-account **JSON key**.

---

## Part B — Wire CI (you give me one file; I/you set two values)

In the **birdo-client-mobile** repo -> Settings -> Secrets and variables -> Actions:

- **Secret** `PLAY_SERVICE_ACCOUNT_JSON` = the full contents of the
  service-account JSON from step A.6.
  - CLI: `gh secret set PLAY_SERVICE_ACCOUNT_JSON < play-sa.json` (never echoes it).
- **Variable** `PLAY_UPLOAD_ENABLED` = `true`
  - CLI: `gh variable set PLAY_UPLOAD_ENABLED --body true`

That's the entire switch. Until `PLAY_UPLOAD_ENABLED=true`, the `play-upload`
job is skipped and nothing reaches Play.

---

## Part C — Releasing (automated thereafter)

- **Internal track (default):** push a version tag ->
  ```
  git tag android-v1.0.0 && git push origin android-v1.0.0
  ```
  CI builds + signs the AAB, then uploads it to the **internal** track
  (`status: completed`, so internal testers get it immediately). It also drafts
  a GitHub Release with the sideload APK.

- **Promote to another track:** Actions -> *Mobile CI (Android + iOS)* ->
  **Run workflow** -> pick `play_track` = `alpha` / `beta` / `production`.
  (For production, consider a **staged rollout** — set `status: inProgress` +
  a `userFraction` in the `play-upload` step instead of `completed`.)

- **Release notes** come from `distribution/whatsnew/whatsnew-en-US` — edit per
  release (≤ 500 chars). Add `whatsnew-<lang>` files for more locales.

### Version bumping
`versionCode`/`versionName` come from `version.properties`. Bump it before
tagging (or wire an auto-increment later). Play rejects a re-used `versionCode`.

---

## Notes / hardening
- `r0adkll/upload-google-play` is SHA-pinned at `v1.1.5` (`android.yml`), like
  every other action in the workflow.
- **The Play build sells through Play Billing.** The AAB is built with
  `-PplayBuild=true` -> `BuildConfig.IS_PLAY_BUILD=true`, which routes the
  Subscription screen at Play Billing and removes the external-purchase links
  (the "Manage on web" plan buttons, the toolbar "Web" action, the "purchased
  on birdo.app" copy). The direct **sideload APK** (`assembleRelease`, default
  `false`) keeps the web-billing links — it is not distributed through Play so
  it is not bound by Play policy. Full sequenced plan: `docs/PLAY-LAUNCH-PLAN.md`.
- **16 KB page-size compliance is gated in CI** (`scripts/check_16kb_alignment.sh`)
  — the build fails before signing if any shipped `.so` is not 16 KB-aligned, as
  Google Play requires for API-35 targets. The Rust JNI lib sets the alignment
  linker flag in `native/rosenpass-jni/.cargo/config.toml`.
- Cert pins (`network_security_config.xml`) expire **2027-06-01** — the `lint`
  job already warns as that approaches.
