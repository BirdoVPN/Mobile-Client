# Play Store phone screenshots — MUST be re-captured before listing

**Status: the 5 phone screenshots + 3 extra captures that used to live here were
corrupted** (they had been written with a UTF-16 text encoding — magic bytes
`ff fe` instead of the PNG `89 50 4e 47…` — so they were not valid images and
Play would reject them). The corruption was already in the committed files and is
**not losslessly recoverable** (the binary was mangled by a text round-trip), so
they were removed. The icon (`app-icon-512.png`), feature graphic
(`feature-graphic-1024x500.png`), brand mark and YouTube banner are intact.

## What Play needs (owner step — requires the running app)

Google Play requires **at least 2** (up to 8) **phone screenshots**, each:
- PNG or JPEG, **320–3840 px** on each side, ~9:16 portrait (e.g. 1080×2340),
- showing the **actual app** (a VPN behind a login — screenshots of real screens).

Re-capture these five screens (the previous set):

| File | Screen |
|---|---|
| `screenshot-01-login.png` | Login |
| `screenshot-02-home-disconnected.png` | Home (disconnected) |
| `screenshot-03-servers.png` | Server list |
| `screenshot-04-settings.png` | Settings |
| `screenshot-05-split-tunneling.png` | Split tunnelling |

## How to capture (either way produces valid PNGs)

**Android Studio emulator** (no physical device needed): run a Pixel-class AVD,
install the debug build, navigate to each screen, then from a terminal:

```bash
adb exec-out screencap -p > store-assets/screenshot-01-login.png
```

(`exec-out` — NOT `adb shell screencap -p > file`, whose CRLF translation on
some shells corrupts the PNG. On Windows PowerShell never pipe binary through
`>`/`Out-File` — that is exactly what corrupted the originals; use
`adb exec-out ... > file` from Git Bash, or `adb pull` a file written on-device.)

Verify each is a real PNG before committing:

```bash
python -c "from PIL import Image;import glob;[print(f,Image.open(f).size) for f in glob.glob('store-assets/screenshot-*.png')]"
```

Optionally regenerate the tablet variants afterwards:
`python store-assets/generate_tablet_screenshots.py`.

Then upload them in the Play Console listing (Store listing → Phone screenshots).
