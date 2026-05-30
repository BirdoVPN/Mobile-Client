<#
.SYNOPSIS
  Generate the static Play Console assets that don't come from the app:
  - icon-512.png   (mandatory, 512×512 hi-res launcher icon)
  - feature-1024x500.png (mandatory, store header banner)
  All written to birdo-client-mobile/screenshots/play/store-assets/.
#>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$Root      = Resolve-Path (Join-Path $ScriptDir '..')
$Out       = Join-Path $Root 'screenshots\play\store-assets'
New-Item -ItemType Directory -Force -Path $Out | Out-Null

# ── 1. Hi-res launcher icon (512×512) ───────────────────────────────────
# Up-sample the existing xxxhdpi launcher (192×192) into a square 512 PNG.
$src = Join-Path $Root 'app\src\main\res\mipmap-xxxhdpi\ic_launcher.png'
if (-not (Test-Path $src)) { throw "Source launcher icon not found at $src" }

$icon = [System.Drawing.Image]::FromFile($src)
try {
    $bmp = New-Object System.Drawing.Bitmap 512, 512
    $g   = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode      = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.InterpolationMode  = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.Clear([System.Drawing.Color]::Black)
    $g.DrawImage($icon, (New-Object System.Drawing.Rectangle 0, 0, 512, 512))
    $g.Dispose()
    $iconOut = Join-Path $Out 'icon-512.png'
    $bmp.Save($iconOut, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "[OK] icon-512.png -> $iconOut" -ForegroundColor Green
} finally { $icon.Dispose() }

# ── 2. Feature graphic (1024×500) — black bg, brand mark + tagline ─────
$bmp = New-Object System.Drawing.Bitmap 1024, 500
$g   = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode      = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
$g.InterpolationMode  = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.TextRenderingHint  = [System.Drawing.Text.TextRenderingHint]::ClearTypeGridFit
$g.Clear([System.Drawing.Color]::Black)

# Subtle radial-ish glow via two filled circles
$glowOuter = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(30, 150, 150, 255))
$g.FillEllipse($glowOuter, 200, -250, 700, 700)
$glowInner = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(20, 255, 255, 255))
$g.FillEllipse($glowInner, 350, -100, 400, 400)

# App icon left-center
$iconImg = [System.Drawing.Image]::FromFile($src)
try {
    $g.DrawImage($iconImg, (New-Object System.Drawing.Rectangle 80, 150, 200, 200))
} finally { $iconImg.Dispose() }

$titleFont   = New-Object System.Drawing.Font 'Segoe UI Semibold', 64, ([System.Drawing.FontStyle]::Bold)
$taglineFont = New-Object System.Drawing.Font 'Segoe UI', 26
$whiteBrush  = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)
$dimBrush    = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(180, 255, 255, 255))

$g.DrawString('Birdo VPN',                    $titleFont,   $whiteBrush, 320, 170)
$g.DrawString('Sovereign · Post-Quantum · Yours.', $taglineFont, $dimBrush,  324, 270)

$g.Dispose()
$featOut = Join-Path $Out 'feature-1024x500.png'
$bmp.Save($featOut, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Host "[OK] feature-1024x500.png -> $featOut" -ForegroundColor Green

Write-Host "`nDONE - Store assets generated at $Out" -ForegroundColor Green
