# Birdo VPN — Play Store Screenshot Capture

Automated capture of every Play-Store-required screenshot using the existing
Pixel 7 (API 35) emulator + production-backend test account.

## Test account (do NOT use for real traffic)

The account e-mail is `owner@birdo.app`. **The password is NOT stored in this repo.**
`scripts/capture-screenshots.ps1` reads it from the environment:

```powershell
$env:BIRDO_TEST_PASSWORD = '<the password>'   # never commit this
.\scripts\capture-screenshots.ps1
```

> ⚠️ **2026-08-21 — a plaintext password used to live on this line.** This repository is
> PUBLIC and it had been in git history since `ce1f059` (v1.3.15), on an account whose role
> is `OWNER` with 2FA disabled. The credential was rotated on 2026-08-21 and every session
> revoked; the audit trail showed 4 failed logins from a single IP over three months and no
> unfamiliar device registrations, so it looks like exposure without exploitation.
>
> Deleting it from this file does NOT un-leak it — the history is public and already cloned.
> Rotation was the fix. Never put a credential here again; the script takes an env var.

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
