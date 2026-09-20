# Store screenshots

**Re-captured 2026-09-20** on a Samsung SM-S938B (Android 16), 1440x3120, from a
debug build with `-PallowScreenshots=true`.

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

Four, not five. `play_listing.py` accepts 2–8, and four verified images beat
five where one is unchecked. `screenshot-05-split-tunneling` is gone: split
tunnelling is not reachable from Settings in the current build, and the security
panel below is a stronger asset anyway.

| File | Shows |
|---|---|
| `screenshot-01-login.png` | Email / Anonymous / SSO tabs |
| `screenshot-02-home-protected.png` | Connected, the arc drawn to the node, live transfer counters |
| `screenshot-03-servers.png` | "10 of 10 servers", search, All / Favorites / High-Speed / Port Forwarding |
| `screenshot-04-security.png` | Quantum Protection and Kill Switch both on |

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
