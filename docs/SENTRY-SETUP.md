# Sentry Setup — Birdo VPN for Android

> Closes issue **#357** ("Sentry is inert in every shipped build — `SENTRY_DSN`
> is never provided to CI").
>
> The SDK, the manual init, the PII scrubber and the ProGuard rules were all
> already in the repo. The one thing missing was the **value**: no `SENTRY_DSN`
> repo secret existed and no workflow set the env var, so every artifact CI ever
> produced compiled a blank DSN and every `Sentry.captureException` in the app
> was a no-op. Nothing failed — the build was green and the crash reporter
> simply did not exist.
>
> After this change a release build **refuses to run** without a usable DSN.
> **That means the release job will fail until you complete Step 3 below.**

---

## 0. What changed (so you know what you are configuring)

| Piece | File | State |
| --- | --- | --- |
| DSN resolution (`-PsentryDsn` > `local.properties` > env) | `app/build.gradle.kts` | wired |
| Release build **fails** without a usable DSN | `app/build.gradle.kts` → `:app:validateSentryDsn` | wired |
| CI passes the secret to the release job | `.github/workflows/android.yml` → job `release` | wired |
| Runtime init | `app/src/main/java/app/birdo/vpn/BirdoApp.kt` → `initSentry()` | wired |
| PII scrubbing / no performance data / no replay | same file | wired |
| Sentry calls survive R8 | `app/proguard-rules.pro` | verified |
| The DSN value itself | Sentry.io | **YOURS — Steps 1–3** |
| ProGuard mapping upload (optional, needs extra credentials) | — | **not wired, see Step 6** |

---

## 1. Create the Sentry organisation and project

If you already have a Birdo Sentry project, skip to Step 2.

1. Go to <https://sentry.io/signup/> and sign up (the free "Developer" plan is
   enough: 5k errors/month, 1 user).
2. When asked for a **data region**, choose **European Union (EU)**. Birdo is a
   UK company with EU customers and a privacy policy that promises no
   connection logs; keeping crash telemetry inside the EU keeps the transfer
   story simple. An EU project's DSN host contains `.de.sentry.io` — that is
   how you can tell later which region a DSN belongs to. *This cannot be
   changed after the org is created — you would have to make a new org.*
3. Organisation name: `birdo` (this becomes the org **slug**, used in URLs).
4. On the "Select a platform" screen choose **Android** (`android`, not
   "Android (Kotlin Multiplatform)" and not "React Native").
5. Project name: `birdo-vpn-android`. Assign it to a team (`#birdo` is fine).
6. Click **Create Project**. Sentry shows an onboarding page with a Gradle
   snippet — **ignore the snippet entirely.** This repo initialises Sentry by
   hand in `BirdoApp.kt` and deliberately does *not* use the Sentry Gradle
   plugin (it would auto-enable options this app must keep off, and it would
   force a dependency-lockfile regeneration). Copy only the **DSN**.

---

## 2. Find the DSN

The DSN is not a secret in the cryptographic sense — it is compiled into the
shipped APK and anyone can extract it — but it is write-only credentials for
your error quota, so it is stored as a repo secret rather than committed.

**Click path:** Sentry → **Settings** (gear, bottom left) → **Projects** →
`birdo-vpn-android` → **Client Keys (DSN)**.

The value looks like:

```
https://<32-hex-public-key>@o<org-id>.ingest.de.sentry.io/<7-digit-project-id>
```

Copy the whole line, including `https://`.

> The build validates this shape exactly:
> `^https://<publicKey>@<host>/<projectId>$`. A project **slug**, an **auth
> token** (`sntrys_…`), or a DSN with a trailing slash will be rejected by
> `:app:validateSentryDsn` with a clear message rather than silently producing
> another inert build.

---

## 3. Set the GitHub repo secret  ← **the actual fix for #357**

From a shell with `gh` authenticated:

```bash
gh secret set SENTRY_DSN --repo BirdoVPN/Mobile-Client
# paste the DSN when prompted, then press Enter and Ctrl-D (or Ctrl-Z, Enter on Windows)
```

Or non-interactively, avoiding shell history:

```bash
gh secret set SENTRY_DSN --repo BirdoVPN/Mobile-Client < dsn.txt && rm dsn.txt
```

Or in the web UI: **Settings → Secrets and variables → Actions → New repository
secret**, name `SENTRY_DSN`.

Verify it exists (this prints the name and update time, never the value):

```bash
gh secret list --repo BirdoVPN/Mobile-Client | grep SENTRY_DSN
```

**Nothing else in CI needs changing.** The `release` job in
`.github/workflows/android.yml` already declares:

```yaml
env:
  SENTRY_DSN: ${{ secrets.SENTRY_DSN }}
```

at **job** level, so `assembleRelease` and `bundleRelease` — and any gradle step
added to that job in future — inherit it.

### Which builds get a DSN

| Build | Gets a DSN? | Why |
| --- | --- | --- |
| Local `assembleDebug` | No | `initSentry()` returns early on `BuildConfig.DEBUG`; no secret needed to develop. |
| CI `build` job (debug APK) | No | Same. |
| CI `release` job — sideload APK (`assembleRelease`) | **Yes** | A sideload crash is still a crash you need to see. |
| CI `release` job — Play AAB (`bundleRelease -PplayBuild=true`) | **Yes** | Same DSN, same project. |
| F-Droid (`fdroid.yml`) | N/A | That workflow does not build; it re-signs and indexes the APK the `release` job published, so it inherits whatever that APK contains. |

Issue #357 asked whether sideload builds should carry a DSN at all. They do, on
purpose: sideload is the channel with no Play Console crash reporting behind it,
so it is the channel that needs Sentry *most*. If you later want to split them,
give the Play build its own DSN by passing `-PsentryDsn=…` to the `bundleRelease`
invocation only — the `-P` flag wins over the environment.

---

## 4. Local development

Nothing is required. If you *want* events from a local release build, add the
DSN to `local.properties` (gitignored, see `local.properties.example`):

```properties
SENTRY_DSN=https://…@o….ingest.de.sentry.io/…
```

To build a release **deliberately without** crash reporting — a reproducible
source build, or checking that minification still works on a machine with no
Sentry account:

```bash
./gradlew assembleRelease -PallowMissingSentryDsn=true
```

That prints a loud banner and must never be used for a shipped artifact. It is
never set in CI.

---

## 5. Verify it worked

### 5a. Verify the gate actually fails without a DSN (proves #357 is closed)

This is the important one — it proves the build can no longer silently produce
an inert artifact. Run it with the DSN temporarily hidden:

```bash
# from a shell where local.properties has no SENTRY_DSN line, or:
./gradlew :app:validateSentryDsn -PsentryDsn=" "
```

Expected: **`FAILURE: … No usable SENTRY_DSN — refusing to build a release
artifact that cannot report crashes.`** followed by the three ways to supply
one. If this *passes*, something is still feeding a DSN in — check
`local.properties` and the `SENTRY_DSN` environment variable.

And with one present:

```bash
./gradlew :app:validateSentryDsn -PsentryDsn=https://abc@o1.ingest.de.sentry.io/2
```

Expected: `Sentry: release build has a DSN — crash reporting is ARMED.`

### 5b. Verify the DSN reached the artifact

```bash
./gradlew assembleRelease
grep -c 'ingest\.\(de\.\)\?sentry\.io' app/build/generated/source/buildConfig/release/app/birdo/vpn/BuildConfig.java
```

Expected: `1`. Before this change the same grep returned `0` for every CI build.

### 5c. Verify a real event arrives

The app has exactly one `Sentry.captureException` (`TokenManager.kt`, on
Android Keystore corruption), which is not a path you can trigger on demand.
The honest test is a deliberate crash:

1. Install a **release** APK built with a DSN on a device
   (`./gradlew installRelease`, or sideload the CI artifact once the secret is
   set).
2. Temporarily add to `MainActivity.onCreate`:
   `throw IllegalStateException("sentry smoke test")`
3. Launch the app. It crashes.
4. Relaunch it — the Android SDK caches the event to disk and sends it on the
   **next** start, so a single crash-and-look shows nothing.
5. Sentry → **Issues** → the event appears within ~30s, tagged
   `release: app.birdo.vpn@<version>`, `environment: production`.
6. **Remove the throw.** Do not commit it.

While you are there, confirm the privacy posture on the event itself:

- **User** section shows no email and no IP address.
- No **screenshot**, no **view hierarchy**, no **replay** attachment.
- The **Performance/Traces** tab stays empty — tracing is off by design.
- Any hostname/IP inside the exception message shows as `[HOST]` / `[IP]`.

### 5d. Verify CI

Push to `main` (or re-run the `release` job). Its log contains the line
`Sentry: release build has a DSN — crash reporting is ARMED.` before the APK is
built. If the secret is missing the job fails at `:app:validateSentryDsn`, before
any signing happens.

---

## 6. Optional — de-obfuscated stack traces (ProGuard mapping upload)

**Not wired, and it cannot be wired with the `SENTRY_DSN` secret alone.** A DSN
grants event *ingestion* only; uploading a mapping file is a project-management
API call that needs an org-scoped **auth token** plus the org and project slugs.

Without the upload you still get: correct **line numbers** (kept by
`-keepattributes SourceFile,LineNumberTable` in `proguard-rules.pro`), the
message, and the breadcrumbs. What you lose is readable class/method names —
they appear obfuscated (`a.b.c`).

`app/build/outputs/mapping/release/mapping.txt` is already retained as a CI
artifact by the `release` job's **Upload Mapping File** step, so you can always
symbolicate after the fact: Sentry → Settings → Projects → `birdo-vpn-android` →
**ProGuard** → **Upload Mapping Files**.

To automate it later you would need:

1. Sentry → Settings → **Auth Tokens** → *Create New Token*, scopes
   `project:releases` and `org:read`. Then
   `gh secret set SENTRY_AUTH_TOKEN --repo BirdoVPN/Mobile-Client`.
2. Repo **variables** `SENTRY_ORG` (`birdo`) and `SENTRY_PROJECT`
   (`birdo-vpn-android`).
3. A step in the `release` job, after **Build Release APK and AAB**:

   ```yaml
   - name: Upload ProGuard mapping to Sentry (optional)
     if: ${{ secrets.SENTRY_AUTH_TOKEN != '' && vars.SENTRY_ORG != '' }}
     env:
       SENTRY_AUTH_TOKEN: ${{ secrets.SENTRY_AUTH_TOKEN }}
       SENTRY_ORG: ${{ vars.SENTRY_ORG }}
       SENTRY_PROJECT: ${{ vars.SENTRY_PROJECT }}
       SENTRY_URL: https://de.sentry.io/   # EU region; omit for US
     run: |
       npx --yes @sentry/cli@2 upload-proguard \
         --uuid "$(uuidgen)" \
         app/build/outputs/mapping/release/mapping.txt
   ```

   It is left out of this change on purpose: it pulls an unpinned tool at build
   time into the job that signs the release, which is a supply-chain trade this
   repo has not made anywhere else (every action in these workflows is
   SHA-pinned). Add it deliberately, with a pin, if the obfuscated names ever
   actually get in the way.

---

## 7. Privacy posture — what Sentry is and is not allowed to send

Birdo's privacy policy states no connection logs are kept. A crash reporter that
exports a destination host or a tunnel address contradicts that policy exactly
as a server-side log line would, and this estate has already had one incident of
that class (ufw logging customer destinations). So the configuration in
`BirdoApp.initSentry()` sets every relevant switch **explicitly**, including the
ones whose SDK default is already safe — a default is someone else's decision
and it can change in a version bump.

**Off:**

- `isSendDefaultPii` — no IP address, no device name, no username.
- `isAttachScreenshot`, `isAttachViewHierarchy` — never photograph or describe
  the UI.
- `isEnableUserInteractionTracing` / `isEnableUserInteractionBreadcrumbs` — no
  tap-by-tap trace of a privacy tool's UI.
- `sessionReplay.sessionSampleRate` / `onErrorSampleRate` = 0.0 — no screen
  recording, pinned rather than left to default.
- `tracesSampleRate` = 0.0 **and** `beforeSendTransaction` returns `null` —
  performance monitoring is off twice over. This matters: `beforeSend` (the
  scrubber) does **not** run for transactions, so span descriptions and
  transaction names would be an unscrubbed channel for request URLs sitting
  right next to a carefully scrubbed one.
- `isAttachServerName`, `isSendModules`.

**Scrubbed** (`beforeBreadcrumb` at capture time, `beforeSend` on the way out —
event message, every exception value, every breadcrumb message and string data
value, extras, tags):

UUIDs, 64-hex and 43-char-base64 key material (WireGuard/ML-KEM), URLs, IPv6,
IPv4, email addresses, and bare multi-label hostnames (our node names) — the
same pattern set as the desktop client's `redact.rs`, so the two clients agree.
`event.user` and `event.serverName` are nulled unconditionally.

**Known gap, accepted:** native (NDK) crashes are captured by `sentry-native`,
written to the outbox and uploaded on next start **without** passing through
`beforeSend`. Those payloads are register/stack/module data, not application
strings, so the scrubber has nothing to do there — but be aware that
`options.isEnableNdk` is a separate path from everything above. It is left on
because native crashes (the SIGILL class of bug) are the ones with no other
signal. Play Console's `debugSymbolLevel = "FULL"` symbols cover the same
ground for Play installs only, which is the other reason sideload builds get a
DSN.

**Kotlin-side failures still have no other signal.** `WgNative` catches bare
`Exception` and returns `-1`, and `-assumenosideeffects` strips `Log.e`/`Log.wtf`
from release. Sentry is therefore the *only* remaining channel on the data path.
That is why `io.sentry.**` must never be added to an `-assumenosideeffects`
rule, and why a release build now refuses to ship without a DSN.

---

## 8. Remaining human steps — checklist

- [ ] **Step 1** — create the Sentry org + Android project (EU region), *or*
      confirm the existing project is the one you want.
- [ ] **Step 2** — copy the DSN from **Settings → Projects → Client Keys (DSN)**.
- [ ] **Step 3** — `gh secret set SENTRY_DSN --repo BirdoVPN/Mobile-Client`.
      **Until this is done, the release job fails.**
- [ ] **Step 5d** — re-run the `release` job and confirm the "crash reporting is
      ARMED" line.
- [ ] **Step 5c** — one-off device smoke test with a deliberate throw; confirm
      the event lands and carries no IP, email or hostname.
- [ ] *(optional)* **Step 6** — mapping upload, if obfuscated names become a
      problem.
