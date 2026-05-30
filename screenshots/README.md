# Birdo VPN — Play Store Screenshot Capture

Automated capture of every Play-Store-required screenshot using the existing
Pixel 7 (API 35) emulator + production-backend test account.

## Test account (do NOT use for real traffic)

```
owner@birdo.app : EeimFNSzZ4GwXGB4ECvBAbvS.
```

> This account is owned by the operator and exists purely for marketing
> screenshots. It has a paid subscription so all premium screens render.

## Prereqs (already installed on this machine)

- Android SDK platform-tools (adb): `C:\platform-tools-latest-windows\platform-tools\adb.exe`
- Android emulator: `C:\Android\Sdk\emulator\emulator.exe`
- AVD: `Pixel_7_API_35` (already created)
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (built 2026-04-24)

## Run

```powershell
cd w:\vpn\birdo-client-mobile
pwsh -ExecutionPolicy Bypass -File scripts/capture-screenshots.ps1
```

Output lands in `screenshots/play/phone/01..NN.png` (sized 1080×1920, ready
for Play Console upload).

For tablet shots:

```powershell
pwsh -ExecutionPolicy Bypass -File scripts/capture-screenshots.ps1 -Form tablet
```

## What gets captured

| # | File                | Screen                                    |
|---|---------------------|-------------------------------------------|
| 1 | `01_connect_off.png`| Home — disconnected, big Connect button   |
| 2 | `02_connect_on.png` | Home — connected (or connecting state)    |
| 3 | `03_servers.png`    | Server picker with live ping + flags      |
| 4 | `04_settings.png`   | Settings — kill switch / split tunnel     |
| 5 | `05_profile.png`    | Account screen (passkey + sub status)     |
| 6 | `06_multihop.png`   | Multi-hop entry/exit picker               |

Each PNG is captured at native AVD resolution then resized + center-cropped
to the Play-Store-mandated 1080×1920 (phone) or 1920×1200 (10" tablet).
