# Play Store phone screenshots

**Status: captured (2026-07-07), valid 1080×2400 PNGs, ready to upload.**

| File | Screen |
|---|---|
| `screenshot-01-login.png` | Login ("Welcome Back" / sovereign network) |
| `screenshot-02-home-disconnected.png` | Home — globe, selected server, Connect button |
| `screenshot-03-servers.png` | Server list ("Choose a server", 7 locations) |
| `screenshot-04-settings.png` | Settings (Appearance / Security / Connection) |
| `screenshot-05-split-tunneling.png` | Split-tunnelling app picker |

Upload these in the Play Console (Store listing → Phone screenshots). Play needs
2–8; all five here qualify (PNG, 1080×2340-ish portrait).

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

#    Reviewer/test login: create it with _local/artifacts/make-reviewer.js
#    (anonymous account + active plan; log in on the app's "Anonymous ID" tab).
#    NOTE when automating: the 24-digit ID field auto-formats, so scripted input
#    must force cursor-to-end before each digit or it scrambles.

# 3. Capture (binary-safe — never pipe a PNG through PowerShell `>`):
adb exec-out screencap -p > store-assets/screenshot-XX.png
```

Tablet variants (optional): `python store-assets/generate_tablet_screenshots.py`.
