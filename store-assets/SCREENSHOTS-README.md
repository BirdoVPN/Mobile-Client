# Store screenshots

**Re-captured 2026-10-21** (MR-1181, MR-037), both sets, on the Android emulator
(AVD `Pixel_7_API_35`, API 35) from a debug build of `main` with
`-PallowScreenshots=true`. A debug build talks to production (`API_BASE_URL` is
set in `defaultConfig`) and draws no debug label or HUD. The Play set is
1440x3120 (`adb shell wm size 1440x3120` + `wm density 560`: the same dp layout
as the device the 2026-09-20 set came from); the F-Droid set is the AVD's native
1080x2400. System UI demo mode fixed the status bar (10:00, full battery, Wi-Fi,
no notifications). `StoreScreenshotsTest` pins both sets and their sizes.

Account: ONE fresh anonymous free account, created in-app for this capture.
The "Save your account number" dialog was dismissed without a screenshot or a
host-side UI dump (the button was found by a dump grepped and deleted on the
device), no frame is taken from Profile (it shows the number's last group), and
the account was deleted in-app (Profile → Delete Account) straight afterwards.
The emulator ran with `-read-only`, so its install and sign-in were discarded
on exit. The current UI never names the account elsewhere: the top bar says
"Anonymous account".

The frames this replaced showed an account identifier in the top bar (the Play
home screen a staff e-mail address, two F-Droid frames 12 digits of an anonymous
account number, treated as burned under MR-037). `StoreScreenshotsTest` refuses
them by SHA-256, so they cannot be published again.

## Why this could not be done before

`ALLOW_SCREENSHOTS` and the frame-timing HUD were **mutually exclusive**. The
capture bypass is gated on `BuildConfig.DEBUG && BuildConfig.ALLOW_SCREENSHOTS`
— deliberately, so it can never ship — but `BuildConfig.DEBUG` also switched
`GlobePerf.ENABLED` on. So every build that could take a screenshot drew a debug
overlay across the Home screen, and every build without the overlay set
FLAG_SECURE and refused to be captured at all. Store screenshots were impossible
from any configuration.

`GlobePerf.ENABLED` now excludes screenshot builds, which is the only reason
these exist.

## How to re-capture

```bash
ANDROID_HOME=<sdk> ./gradlew :app:installDebug -PallowScreenshots=true
```

Then drive the app and `adb exec-out screencap -p > out.png`. Note that a fresh
git worktree does **not** carry `app/src/main/jniLibs/` — it is untracked build
output — and without it the app refuses to connect at all
(`quantum/connect_refused_pq_engine_unavailable`), so there is no Protected
screen to photograph. Copy it from the main checkout first.

## The set

Play: four. `play_listing.py` accepts 2–8. `screenshot-05-split-tunneling` was
dropped on 2026-09-20 and stays out of the Play set; the F-Droid set keeps its
split-tunnelling frame (Settings → Split Tunneling → Manage Apps).

| File | Shows |
|---|---|
| `screenshot-01-login.png` | Email / Anonymous / SSO tabs |
| `screenshot-02-home-protected.png` | Protected, the arc drawn to São Paulo, live timer and transfer counters |
| `screenshot-03-servers.png` | "10 of 10 servers", search, All / Favorites / High-Speed / Port Forwarding |
| `screenshot-04-security.png` | Settings: Appearance, then Kill Switch and Quantum Protection both on |

F-Droid (`fdroid/metadata/app.birdo.vpn/en-US/phoneScreenshots/`), 1080x2400:

| File | Shows |
|---|---|
| `01.png` | Email / Anonymous / SSO tabs |
| `02.png` | Protected, the arc drawn to São Paulo |
| `03.png` | The server picker, "10 of 10 servers" |
| `04.png` | Settings: Appearance, Kill Switch and Quantum Protection on |
| `05.png` | Split Tunneling: the app list |

---

## Previous notes

## How they were captured (for future re-capture)

The app sets `FLAG_SECURE` in `MainActivity`, which blanks all screenshots/screen
recording. A DEBUG-only, opt-in build flag disables it **for capture only**:

```bash
# 1. Build a debug APK with screenshots allowed (release ALWAYS keeps FLAG_SECURE
#    — the bypass is double-gated by BuildConfig.DEBUG, see MainActivity).
./gradlew :app:assembleDebug -PallowScreenshots=true

# 2. Boot the emulator (an AVD already exists), install, and use a real login.
$ANDROID_HOME/emulator/emulator -avd Pixel_7_API_35 -no-window -gpu swiftshader_indirect &
adb install -r -g app/build/outputs/apk/debug/app-debug.apk

#    Test login: use a throwaway test account with an active plan (log in on
#    the app's "Anonymous ID" tab). Never use a real customer account, and
#    REDACT the account ID from the top bar before committing screenshots --
#    the app shows it on the home/servers screens.
#    NOTE when automating: the 24-digit ID field auto-formats, so scripted input
#    must force cursor-to-end before each digit or it scrambles.

# 3. Capture (binary-safe — never pipe a PNG through PowerShell `>`):
adb exec-out screencap -p > store-assets/screenshot-XX.png
```

Tablet variants (optional): `python store-assets/generate_tablet_screenshots.py`.

## History: the second pipeline, and the credential leak it carried

Until 2026-09-13 this repo also had `scripts/capture-screenshots.ps1` (an
emulator/uiautomator driver) writing to `screenshots/play/phone/*.png`, a
directory nothing consumed — `scripts/play_listing.py` uploads ONLY the
`store-assets/screenshot-0*.png` set documented above. Both were deleted (an
archive is kept off-repo); this file is the single screenshot procedure.

**Credential-leak record — keep this paragraph.** `screenshots/README.md`
committed a plaintext password for `owner@birdo.app` (role OWNER, 2FA
disabled at the time) to this PUBLIC repository from `ce1f059` (2026-05-04)
until #314 removed it on 2026-08-21. The credential was rotated and every
session revoked; the audit trail showed 4 failed logins from one IP over
three months and no unfamiliar device registrations. Deleting the line did
not un-leak it — the history is public and already cloned; rotation was the
fix. The twelve screenshots captured from that account were reviewed pixel by
pixel on 2026-09-13 before deletion: none showed an email, account ID or
other identifier (three were emulator home-screen captures, not the app).
Rules that follow from it: screenshots come from a throwaway test account
with an active plan (see above), never a real or staff account; secrets are
never in this repository, and the OWNER account must have 2FA on.
