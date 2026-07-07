# BirdoVPN Android — Google Play launch plan

**Package:** `app.birdo.vpn`  ·  **Model:** login-only (subscriptions bought on
birdo.app; no in-app purchase)  ·  **Status as of this plan:** code is
Play-compliant and CI-gated; remaining work is **the organization developer
account (see §0 — VPN apps cannot ship from personal accounts) + Console setup**
(owner-only).

This is the sequenced *plan*. For the mechanical Console/CI wiring steps see the
companion doc **`docs/PLAY-STORE-SETUP.md`** — this plan references it and adds
the timeline, the compliance decisions, the Data-safety answers, and the review
gotchas that actually gate a VPN app.

---

## 0. TL;DR — the critical path

> ### ✅ 2026-07-06: ORGANIZATION account acquired — the one hard gate is cleared
> Google's **Play Console Requirements** policy requires VPN apps (anything using
> the `VpnService` class) to ship from an **organization** developer account, not
> a personal one (enforced against us 3 Jul 2026). **That org account now exists**,
> which removes the only blocker with real lead time — and organization accounts
> are **exempt from the 12-tester / 14-day closed-testing rule**, so there is no
> tester clock either.
>
> **Remaining owner path (all Play Console, ~a few days of clicks + a review):**
> 1. **App on the org account.** If `app.birdo.vpn` was ever uploaded under the old
>    personal account, use the Play Console **app-transfer** flow to move it to the
>    org account (free app, no in-app purchases → simple, ~1–2 days). **NEVER
>    delete + recreate** — the package name is reserved forever once uploaded, so
>    deleting would burn `app.birdo.vpn` permanently. If it was never uploaded,
>    just **create** it fresh under the org account.
> 2. **Enrol in Play App Signing** (Console offers this on first release). Your
>    `birdo-release.jks` becomes the *upload* key — no build change. **Then copy
>    the Play App Signing SHA-256 into `birdo-web/public/.well-known/assetlinks.json`
>    and redeploy birdo.app** (see §5.7 — App Links need the Play cert, not the
>    upload cert).
> 3. **Console setup** — Store listing (phone screenshots are **captured, valid,
>    and ready** in `store-assets/screenshot-0*.png` — just upload them; a
>    **PlayReviewer** anonymous account with an active plan already exists on prod
>    for the App-access field), Data-safety (§4b), Content rating (§4c),
>    App access (§3).
> 4. **Wire CI on the org account** — Play Console → API access → grant the service
>    account **Release manager**; set repo secret `PLAY_SERVICE_ACCOUNT_JSON` +
>    variable `PLAY_UPLOAD_ENABLED=true`. (First AAB upload is manual; CI takes over
>    after.)
> 5. **Upload the AAB → Internal → submit for review → promote to production**
>    (staged rollout). The current release version is **1.3.40** (bumped past the
>    already-tagged 1.3.39 so the versionCode is fresh).

The code, CI, and compliance are ready and re-verified this pass (§1). The launch
clock is now just **Console setup + Google's VPN review** — no account lead time,
no tester clock.

Realistic timeline: **app transfer/create** (~1–2 days if transferring) →
**listing + data-safety + review submission** (owner, hours) → **production
review** (1–7 days, VPN-strict).

> **Screenshots (resolved this pass):** the old `store-assets/` phone screenshots
> were corrupt (UTF-16 text-encoding accident) and unusable. They have been
> **re-captured** from the running app on the emulator — all five are valid
> 1080×2400 PNGs, committed and ready to upload. This required a new debug-only,
> opt-in `-PallowScreenshots=true` build flag (`ALLOW_SCREENSHOTS`) that skips
> `FLAG_SECURE` for capture; a **release build always keeps `FLAG_SECURE`**
> (double-gated by `BuildConfig.DEBUG`), so screenshots stay blocked for real
> users. Everything else (policy gating, manifest, data-safety-vs-code, 16 KB
> gate, signing, account deletion, privacy page) was verified in place.

---

## 1. What is already done (code side — no action needed)

The app was already hardened across previous work; verified this pass:

| Area | State |
|---|---|
| **Payments policy** | ✅ Play build (`IS_PLAY_BUILD=true`) compiles out **all** external-purchase steering — no buy buttons, no web-billing links, no "buy on birdo.app" copy. Premium tiers show as an informational feature comparison. (Sideload APK keeps the links; it's not on Play.) |
| **Permissions** | ✅ `QUERY_ALL_PACKAGES` already replaced with a targeted `<queries>` (launcher intent) for the split-tunnel app picker. No sensitive-permission over-ask. |
| **Foreground service** | ✅ `specialUse` FGS with a written `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` justification + `SUPPORTS_ALWAYS_ON`. `BIND_VPN_SERVICE` declared. |
| **Data at rest** | ✅ Auth tokens in `EncryptedSharedPreferences` (Keystore-backed, with corruption recovery). |
| **Transport** | ✅ Cleartext blocked (`networkSecurityConfig`), SPKI cert-pinning (OkHttp + manifest pin-set, ISRG backup pin, expiry 2027-06-01). |
| **Native integrity** | ✅ SHA-256 hash-gate on wg-go/xray/rosenpass-jni; signing-cert fingerprint runtime tamper check. |
| **Crash reporting** | ✅ Sentry with `isSendDefaultPii = false` + `beforeSend` scrubber; no analytics or ad SDKs. |
| **Self-update** | ✅ None — the app does **not** download/sideload APKs (that would be a Device-and-Network-Abuse violation). Updates come through Play. |
| **Account deletion** | ✅ In-app account deletion exists (Play requirement for apps with accounts) + web URL `birdo.app/delete-account`. |
| **Release engineering** | ✅ AGP 8.11, R8 minify + resource shrink, `debugSymbolLevel=FULL`, ABI filter arm64-v8a + x86_64, signed AAB in CI, Sigstore provenance, versionCode from `version.properties`. |

## 2. What this change set added

**Play compliance + build (PR #146):**
1. **`IS_PLAY_BUILD` build flag** (`app/build.gradle.kts`) — `-PplayBuild=true`
   bakes `BuildConfig.IS_PLAY_BUILD=true`. Gated in `SubscriptionScreen.kt`,
   `ProfileScreen.kt`, and `BirdoNavGraph.kt` to remove every purchase-steering
   surface from the Play build while leaving the sideload APK unchanged.
2. **CI build split** (`.github/workflows/android.yml`) — APK built default
   (sideload, keeps links); **AAB built with `-PplayBuild=true`** (Play,
   compliant). Same keystore, same native libs.
3. **16 KB page-size compliance** — Rust JNI lib sets the alignment linker flag
   (`native/rosenpass-jni/.cargo/config.toml`); a CI gate
   (`scripts/check_16kb_alignment.sh`) fails the build before signing if **any**
   `.so` inside the built AAB (`base/lib/**` — our libs *and* every AAR-merged
   lib: wg-go, xray, Sentry, glance, datastore) is not 16 KB-aligned. Required
   for API-35 targets.

**Fixes from the adversarial re-audit (same PR / follow-up):**
4. **Biometric app-lock actually works now** — it was a no-op (the lock state
   never gated the composition, so cancelling the prompt revealed the full UI).
   Now a full-screen lock covers the app until authentication succeeds, and it
   re-locks when the app is backgrounded.
5. **Profile "Manage on web" link** — was the one purchase-steering link not
   gated by `IS_PLAY_BUILD`; now hidden in the Play build.
6. **Always-on VPN toggle disabled** (`SUPPORTS_ALWAYS_ON=false`) — the service
   can't yet self-establish a tunnel on a headless/boot start, so leaving the
   toggle on let a user enable lockdown and lose connectivity after every
   reboot. Disabled = fails safe. Proper headless reconnect is a tracked
   follow-up (see §8).
7. **Sentry scrubber** now also redacts exception/stack-trace messages (the
   real crash-path vector), not just the top-level event message.

---

## 3. Owner setup checklist (Play Console) — do these in parallel with §4

Mechanical steps are in **`docs/PLAY-STORE-SETUP.md` Part A/B**. Summary + the
bits that trip people up:

- [ ] **Create/verify the developer account** ($25 one-time). Identity (and, for
      orgs, D-U-N-S) verification has **lead time — start today.**
- [ ] **Create the app**: *Birdo VPN*, `app.birdo.vpn`, Free, category **Tools**.
- [ ] **Enrol in Play App Signing** — your `birdo-release.jks` becomes the
      *upload* key; no build change.
- [ ] **First AAB upload is manual** (the API can't create the app). Download the
      `.aab` from a GitHub release (or run the workflow), upload to **Internal
      testing** by hand. CI takes over after that.
- [ ] **Service-account JSON** → repo secret `PLAY_SERVICE_ACCOUNT_JSON`; set
      variable `PLAY_UPLOAD_ENABLED=true` to arm the auto-upload job.
- [ ] **App access** — Google reviews **behind your login**. Create a reviewer
      account on birdo.app **with an active plan** and put the email/password in
      *App access → All functionality → Instructions*. **Without this the review
      fails** (they can't get past your auth). Include a note: *"Subscriptions
      are purchased on our website; this app is login-only. Test credentials
      below have an active plan."*

---

## 4. Store listing + declarations (concrete answers)

### 4a. Assets — already generated in `store-assets/`
- [ ] **App icon** 512×512 → `store-assets/app-icon-512.png`
- [ ] **Feature graphic** 1024×500 → `store-assets/feature-graphic-1024x500.png`
- [ ] **Phone screenshots** (≥ 2, up to 8) → `store-assets/screenshot-0*.png`
- [ ] **Short description** (≤ 80 chars) and **full description** (≤ 4000). Draft
      below.
- [ ] **What's new** → `distribution/whatsnew/whatsnew-en-US` (≤ 500 chars).

> Suggested short description: *"Fast, private WireGuard® VPN with post-quantum
> encryption and a kill switch."*

### 4b. Data safety form — declare exactly this (matches the code)

The app has **no ads/analytics SDKs**; Sentry runs with PII off + a scrubber.
Declare:

| Data type | Collected? | Shared? | Purpose | Notes |
|---|---|---|---|---|
| **Email address** | Yes | No | Account management | Only for account login/identity |
| **Device/other IDs** (device id + name) | Yes | No | App functionality | Device-management / multi-device limits |
| **Crash logs / diagnostics** | Yes | No | Crash prevention / diagnostics | Sentry, PII-scrubbed |
| **App activity / browsing** | **No** | No | — | Zero-logs VPN — no traffic/activity logging |
| **Location** | **No** | No | — | Not collected |
| **Payment info** | **No** (in app) | No | — | Purchases happen on birdo.app, not in the app |

- **Encryption in transit:** Yes. **User can request deletion:** Yes
  (`birdo.app/delete-account`). **Committed to Play Families policy:** N/A (adults).
- Answer the extra **VPN questions** honestly: it *is* a VPN, it routes traffic
  to provide the core service (not to collect data).

### 4c. Content rating
- Fill the questionnaire as a **Tools/Utility**. A VPN that provides unfiltered
  internet access typically lands **Teen/PEGI 12** (unrestricted web access
  question). Answer truthfully — no in-app user-generated content, no ads.

### 4d. Other declarations
- [ ] **Privacy policy URL:** `https://birdo.app/privacy`
- [ ] **Target audience:** adults (18+); do **not** opt into Families.
- [ ] **Ads:** declare **No ads**.
- [ ] **Government apps / financial features:** No.

---

## 5. VPN-specific review gotchas (read before submitting)

Google reviews VPN apps more strictly than average. The known rejection causes,
and where we stand:

1. **Must use the Android `VpnService` API** and declare it — ✅ we do
   (`BIND_VPN_SERVICE`, `specialUse` FGS). Apps that tunnel without `VpnService`
   get pulled.
2. **No steering to external payment** — ✅ removed in the Play build (§2).
3. **Data safety must match runtime behaviour** — ✅ §4b matches the code. A
   mismatch (e.g. an SDK that phones home) is the top cause of enforcement.
4. **Reviewer must reach full functionality** — provide the test account (§3).
   A VPN behind a login with no test creds = automatic fail.
5. **Foreground-service justification** — ✅ written into the manifest property.
6. **16 KB pages** — ✅ CI-gated (§2).
7. **Deep-link App Links** — ✅ **DONE (2026-07-07)**. `birdo.app/.well-known/
   assetlinks.json` is live with the real **Play App Signing** cert SHA-256
   (plus the upload-key cert for locally-built APKs) in both statements
   (birdo-web PR #186, deployed + verified). Certs documented in
   `docs/PLAY-APP-SIGNING.md`.

---

## 6. Release + rollout process (after setup)

Automated once `PLAY_UPLOAD_ENABLED=true` (see SETUP.md Part C):

1. Bump `version.properties` (Play rejects a re-used `versionCode`), commit.
2. Tag `android-vX.Y.Z` → push. CI builds/signs the AAB (`-PplayBuild=true`),
   runs the 16 KB gate, uploads to the **internal** track, and drafts a GitHub
   release with the sideload APK.
3. Promote via Actions → *Run workflow* → `play_track = closed / production`.
4. For production, use a **staged rollout** (`status: inProgress` + `userFraction`
   e.g. 0.1 → 0.5 → 1.0) so a bad build can be halted.

**Recommended track ladder (organization account):** internal (you) → a short
closed/open sanity round if you want one (no 12-tester/14-day mandate for org
accounts) → production (staged).

---

## 7. Phase 2 (optional, later) — in-app purchase via Play Billing

Only if you later want to sell subscriptions **inside** the Play app (Play takes
15% on subs after year one; you keep 100% today by selling on the web). The
groundwork exists but is parked:

- Client: PR **#116** (`BillingManager`/`BillingViewModel`, `billing-ktx`) —
  currently conflicting; would need a rebase.
- Backend: PR **#21** (`/payments/google-play/verify` + RTDN) — currently
  **closed**; would need to be reopened/rebuilt and deployed.
- Owner: create Play Console subscription products (`operative`/`sovereign` ×
  monthly/yearly base plans), a service account, RTDN Pub/Sub topic, and run
  license-tester purchase tests on a device.

Flipping `IS_PLAY_BUILD` back to showing purchase UI would then route to Play
Billing instead of the web. **Not needed for launch** — launch login-only first.

---

## 8. Risk register

| Risk | Likelihood | Mitigation |
|---|---|---|
| VPN apps = org-account-only (Play Console Requirements) | **Certain — enforced 3 Jul 2026** | D-U-N-S → org account → **transfer** the app (§0). Never delete the app: the package name would be burned forever |
| D-U-N-S / org verification lead time | Medium | Start today; UK Ltds often already have a D-U-N-S — check the D&B lookup first |
| Data-safety mismatch flagged | Low | §4b matches code; keep it in sync if SDKs change |
| Review can't get past login | Medium | Provide reviewer account with an active plan (§3) |
| 16 KB gate flags prebuilt Go libs | Low | Bump wg-go/xray to current builds (CI will name the file) |
| In-app **voucher** redemption seen as alt-payment | Low | Vouchers are gift-style codes; if Google objects, gate redemption behind `IS_PLAY_BUILD` too |
| Cert-pin expiry 2027-06-01 | Low (far off) | `lint` warns near expiry; ship an update before |

---

*Companion: `docs/PLAY-STORE-SETUP.md` (mechanical setup + CI wiring).*
