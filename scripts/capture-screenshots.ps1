<#
.SYNOPSIS
  Drive the Birdo VPN Android app through every Play Store screen and
  capture screenshots. Uses uiautomator dump to find UI elements by text
  rather than blind coordinate taps.
#>
[CmdletBinding()]
param(
    [ValidateSet('phone','tablet')] [string]$Form = 'phone',
    [string]$Avd,
    [string]$Account = 'owner@birdo.app',
    [string]$Password = $(if ($env:BIRDO_TEST_PASSWORD) { $env:BIRDO_TEST_PASSWORD } else { 'CHANGE_ME' }),
    [switch]$SkipBoot,
    [switch]$SkipInstall,
    [switch]$KeepEmulator
)

$ErrorActionPreference = 'Stop'
$AdbExe      = 'C:\platform-tools-latest-windows\platform-tools\adb.exe'
$EmulatorExe = 'C:\Android\Sdk\emulator\emulator.exe'

# adb writes progress to stderr — wrap to keep PS happy
function Adb-Quiet {
    param([Parameter(ValueFromRemainingArguments)]$ArgList)
    $oldEAP = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $AdbExe @ArgList 2>&1 | Out-Null
    } finally { $ErrorActionPreference = $oldEAP }
}

if (-not (Test-Path $AdbExe))      { throw "adb not found at $AdbExe" }
if (-not (Test-Path $EmulatorExe)) { throw "emulator not found at $EmulatorExe" }

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$Root      = Resolve-Path (Join-Path $ScriptDir '..')
$Apk       = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'
$OutDir    = Join-Path $Root "screenshots\play\$Form"
$RawDir    = Join-Path $OutDir '_raw'
New-Item -ItemType Directory -Force -Path $OutDir, $RawDir | Out-Null

switch ($Form) {
    'phone'  { $TargetW = 1080; $TargetH = 1920; if (-not $Avd) { $Avd = 'Pixel_7_API_35' } }
    'tablet' { $TargetW = 1920; $TargetH = 1080; if (-not $Avd) { $Avd = 'Pixel_Tablet_API_35' } }
}
Write-Host "[run] Form=$Form  AVD=$Avd  target=${TargetW}x${TargetH}" -ForegroundColor Cyan

function Adb-Tap   { param($x,$y)        & $AdbExe shell input tap $x $y | Out-Null; Start-Sleep -Milliseconds 500 }
function Adb-Text  { param($t)           & $AdbExe shell input text "'$t'" | Out-Null; Start-Sleep -Milliseconds 350 }
function Adb-Key   { param($k)           & $AdbExe shell input keyevent $k | Out-Null; Start-Sleep -Milliseconds 350 }
function Adb-Sleep { param($ms = 1500)   Start-Sleep -Milliseconds $ms }

function Get-UiDump {
    cmd /c "`"$AdbExe`" shell uiautomator dump /sdcard/window.xml >nul 2>&1"
    $localXml = Join-Path $env:TEMP "birdo_uidump.xml"
    if (Test-Path $localXml) { Remove-Item $localXml -Force }
    cmd /c "`"$AdbExe`" pull /sdcard/window.xml `"$localXml`" >nul 2>&1"
    if (-not (Test-Path $localXml)) { return $null }
    return [xml](Get-Content $localXml -Raw)
}

# Walk every <node>; return list of @{text, desc, cls, bounds=@(l,t,r,b), centerX, centerY}
function Get-UiNodes {
    param([xml]$Xml)
    $nodes = @()
    if ($null -eq $Xml) { return $nodes }
    $stack = New-Object System.Collections.Stack
    $stack.Push($Xml.DocumentElement)
    while ($stack.Count -gt 0) {
        $n = $stack.Pop()
        if ($n.LocalName -eq 'node') {
            $b = $n.bounds
            if ($b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
                $l = [int]$Matches[1]; $t = [int]$Matches[2]
                $r = [int]$Matches[3]; $bot = [int]$Matches[4]
                $nodes += [pscustomobject]@{
                    Text=$n.text; Desc=$n.'content-desc'; Cls=$n.class;
                    Pkg=$n.package; Resource=$n.'resource-id';
                    L=$l; T=$t; R=$r; B=$bot;
                    CX=[int](($l+$r)/2); CY=[int](($t+$bot)/2)
                }
            }
        }
        if ($n.HasChildNodes) {
            foreach ($c in $n.ChildNodes) { $stack.Push($c) }
        }
    }
    return $nodes
}

function Tap-Text {
    param([string]$Pattern, [switch]$Optional, [int]$Timeout=8)
    $deadline = (Get-Date).AddSeconds($Timeout)
    while ((Get-Date) -lt $deadline) {
        $xml = Get-UiDump
        $nodes = Get-UiNodes $xml
        $hit = $nodes | Where-Object {
            ($_.Text -and $_.Text -match $Pattern) -or
            ($_.Desc -and $_.Desc -match $Pattern)
        } | Select-Object -First 1
        if ($hit) {
            Write-Host "    tap: '$($hit.Text)' ($($hit.CX),$($hit.CY))" -ForegroundColor DarkGray
            Adb-Tap $hit.CX $hit.CY
            return $true
        }
        Start-Sleep -Milliseconds 800
    }
    if ($Optional) { Write-Host "    skip (no match for '$Pattern')" -ForegroundColor DarkYellow; return $false }
    throw "Could not find UI element matching '$Pattern' within $Timeout s"
}

function Tap-Class-Index {
    param([string]$ClassRegex, [int]$Index = 0)
    $xml = Get-UiDump
    $nodes = Get-UiNodes $xml
    $matches = @($nodes | Where-Object { $_.Cls -match $ClassRegex })
    if ($matches.Count -le $Index) { throw "No class[$Index] for $ClassRegex (have $($matches.Count))" }
    $h = $matches[$Index]
    Write-Host "    tap class[$Index]: $($h.Cls) ($($h.CX),$($h.CY))" -ForegroundColor DarkGray
    Adb-Tap $h.CX $h.CY
}

function Capture {
    param([string]$Name)
    $rawPath = Join-Path $RawDir "$Name.png"
    $devPath = "/sdcard/birdo_shot.png"
    cmd /c "`"$AdbExe`" shell screencap -p $devPath >nul 2>&1"
    if (Test-Path $rawPath) { Remove-Item $rawPath -Force }
    cmd /c "`"$AdbExe`" pull $devPath `"$rawPath`" >nul 2>&1"
    cmd /c "`"$AdbExe`" shell rm $devPath >nul 2>&1"
    if (-not (Test-Path $rawPath) -or (Get-Item $rawPath).Length -lt 1000) {
        throw "Screenshot $Name failed"
    }
    Write-Host "  [shot] $Name" -ForegroundColor Green
    return $rawPath
}

function Resize-PlayStore {
    param([string]$InPath, [string]$OutPath, [int]$TargetW, [int]$TargetH)
    Add-Type -AssemblyName System.Drawing
    $img = [System.Drawing.Image]::FromFile($InPath)
    try {
        $srcRatio = $img.Width / $img.Height
        $dstRatio = $TargetW / $TargetH
        if ($srcRatio -gt $dstRatio) {
            $cropH = $img.Height; $cropW = [int]($cropH * $dstRatio)
            $cropX = [int](($img.Width - $cropW) / 2); $cropY = 0
        } else {
            $cropW = $img.Width;  $cropH = [int]($cropW / $dstRatio)
            $cropX = 0; $cropY = [int](($img.Height - $cropH) / 2)
        }
        $bmp = New-Object System.Drawing.Bitmap $TargetW, $TargetH
        $g   = [System.Drawing.Graphics]::FromImage($bmp)
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $srcRect = New-Object System.Drawing.Rectangle $cropX, $cropY, $cropW, $cropH
        $dstRect = New-Object System.Drawing.Rectangle 0, 0, $TargetW, $TargetH
        $g.DrawImage($img, $dstRect, $srcRect, [System.Drawing.GraphicsUnit]::Pixel)
        $g.Dispose()
        $bmp.Save($OutPath, [System.Drawing.Imaging.ImageFormat]::Png)
        $bmp.Dispose()
    } finally { $img.Dispose() }
}

# -- 1. Boot --
function Wait-Boot {
    Write-Host "  waiting for emulator boot..." -NoNewline
    $oldEAP = $ErrorActionPreference; $ErrorActionPreference = 'SilentlyContinue'
    try {
        for ($i = 0; $i -lt 180; $i++) {
            $b = & $AdbExe shell getprop sys.boot_completed 2>$null
            $b = ($b | Out-String).Trim()
            if ($b -eq '1') { Write-Host " done" -ForegroundColor Green; return }
            Start-Sleep -Seconds 2; Write-Host '.' -NoNewline
        }
    } finally { $ErrorActionPreference = $oldEAP }
    throw "boot timeout"
}

if (-not $SkipBoot) {
    $running = (& $AdbExe devices) -match 'emulator-\d+\s+device'
    if (-not $running) {
        Write-Host "[run] Booting AVD $Avd..." -ForegroundColor Cyan
        Start-Process -FilePath $EmulatorExe -ArgumentList @('-avd',$Avd,'-no-snapshot-load','-no-boot-anim','-gpu','host') -WindowStyle Hidden
        Start-Sleep -Seconds 5
    }
    Wait-Boot
    Adb-Sleep 1500
    & $AdbExe shell input keyevent 82 | Out-Null
    & $AdbExe shell settings put global sysui_demo_allowed 1 | Out-Null
    & $AdbExe shell am broadcast -a com.android.systemui.demo -e command enter | Out-Null
    & $AdbExe shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941 | Out-Null
    & $AdbExe shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false | Out-Null
    & $AdbExe shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 | Out-Null
    & $AdbExe shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false | Out-Null
}

# -- 2. Install --
# Debug builds use applicationIdSuffix=".debug" → real package is app.birdo.vpn.debug.
$pkg = if ($Apk -match 'debug') { 'app.birdo.vpn.debug' } else { 'app.birdo.vpn' }
if (-not $SkipInstall) {
    if (-not (Test-Path $Apk)) { throw "APK not found at $Apk" }
    Write-Host "[run] Installing APK..." -ForegroundColor Cyan
    & $AdbExe install -r -t -g $Apk | Out-Null
    foreach ($perm in @('android.permission.POST_NOTIFICATIONS','android.permission.ACCESS_FINE_LOCATION')) {
        & $AdbExe shell pm grant $pkg $perm 2>$null | Out-Null
    }
}

# -- 3. Launch fresh --
Write-Host "[run] Launching app..." -ForegroundColor Cyan
& $AdbExe shell am force-stop $pkg | Out-Null
& $AdbExe shell pm clear $pkg | Out-Null
& $AdbExe shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 | Out-Null
Adb-Sleep 4500

# Dismiss notification permission dialog if it appeared
Tap-Text -Pattern '^Allow$' -Optional -Timeout 4 | Out-Null
Adb-Sleep 1200

# Consent screen — exact text match (avoid matching the warning line)
Write-Host "[run] Accept consent..."
Tap-Text -Pattern '^I Agree' -Timeout 8 | Out-Null
Adb-Sleep 2500

# -- Capture login screen BEFORE entering credentials --
Write-Host "[run] Capturing login screen..." -ForegroundColor Cyan
$loginShot = Capture '00_login'

# Login (Compose has no EditText - find input field below the
# labelled "Email" / "Password" TextViews exposed by uiautomator)
Write-Host "[run] Login..."
$xml = Get-UiDump
$nodes = Get-UiNodes $xml
$emailLabel = $nodes | Where-Object { $_.Text -eq 'Email' } | Select-Object -First 1
$pwLabel    = $nodes | Where-Object { $_.Text -eq 'Password' } | Select-Object -First 1
$submit     = $nodes | Where-Object { $_.Text -match 'Initialize Uplink' } | Select-Object -First 1
if (-not $emailLabel -or -not $pwLabel -or -not $submit) {
    throw "Login screen not detected (missing Email/Password/Initialize)"
}
$emailY = $emailLabel.B + 70   # ~70px below label = inside input
$pwY    = $pwLabel.B    + 70
# Use submit button center X (form is centered) — works for phone (1080) and tablet (2560).
$inputX = $submit.CX
Write-Host "    email tap ($inputX,$emailY)  password tap ($inputX,$pwY)  submit ($($submit.CX),$($submit.CY))"

Adb-Tap $inputX $emailY; Adb-Sleep 800
Adb-Text $Account; Adb-Sleep 600
# Use TAB to advance to password field — more reliable than re-tapping when IME
# may have shifted layout (esp. on tablets where Y of password label moves).
Adb-Key 61  # KEYCODE_TAB
Adb-Sleep 600
Adb-Text $Password; Adb-Sleep 500
# Dismiss IME so the submit button isn't covered by the keyboard
Adb-Key 4    # BACK closes IME but stays in app
Adb-Sleep 1200
# Re-resolve submit button bounds now that layout has reflowed
$xml2 = Get-UiDump
$nodes2 = Get-UiNodes $xml2
$submit2 = $nodes2 | Where-Object { $_.Text -match 'Initialize Uplink' } | Select-Object -First 1
if (-not $submit2) { $submit2 = $submit }
Write-Host "    submit (after IME) ($($submit2.CX),$($submit2.CY))"
Adb-Tap $submit2.CX $submit2.CY
Adb-Sleep 9000

# -- 4. Capture (8 distinct Play Store screens) ─────────────────────────────────
# Detect screen dimensions so swipe distances scale for both phone and tablet.
$szRaw = (& $AdbExe shell wm size 2>$null) | Out-String
if ($szRaw -match '(\d+)x(\d+)') { $scrW=[int]$Matches[1]; $scrH=[int]$Matches[2] }
else                               { $scrW=1080; $scrH=2400 }
$midX = [int]($scrW / 2)
Write-Host "  [dim] screen ${scrW}x${scrH}" -ForegroundColor DarkGray

function Adb-Swipe {
    param([int]$X1,[int]$Y1,[int]$X2,[int]$Y2,[int]$Ms=700)
    cmd /c "`"$AdbExe`" shell input swipe $X1 $Y1 $X2 $Y2 $Ms >nul 2>&1"
    Start-Sleep -Milliseconds 900
}

$captures = @()
$captures += $loginShot   # 00_login — captured before credentials were entered

# ── 01: Hero — Home disconnected (globe · power button · server row) ─────────────
$captures += Capture '01_home'

# ── 02: Server browser — tap the server selection row at the bottom of home ──────
# The row shows text like "DE Frankfurt 1" or a city name; we match broadly.
Tap-Text -Pattern 'Frankfurt|London|Amsterdam|New York|Tokyo|\d% load|DE |GB |NL |US ' -Optional -Timeout 4 | Out-Null
Adb-Sleep 2800
$captures += Capture '02_server_list'
Adb-Key 4; Adb-Sleep 1500       # back to Home

# ── 03: Settings top — Kill Switch · Auto-Connect · Notifications ─────────────────
Tap-Text -Pattern '^Settings$' -Optional -Timeout 4 | Out-Null
Adb-Sleep 2200
$captures += Capture '03_settings_security'

# ── 04: Settings scrolled — Split Tunneling · Multi-Hop · Advanced ───────────────
# Swipe up (scroll list down) to reveal the sections below the fold.
$sfY = [int]($scrH * 0.72); $stY = [int]($scrH * 0.22)
Adb-Swipe $midX $sfY $midX $stY 700
Adb-Sleep 1400
$captures += Capture '04_settings_features'

# ── 05: VPN Settings sub-screen (DNS · WireGuard · Local Network) ─────────────────
# Scroll once more to expose the "VPN Settings" list item, then tap it.
# ("VPN PROTOCOL" visible above is only a section header; "VPN Settings" is the item below it.)
Adb-Swipe $midX $sfY $midX ([int]($scrH * 0.45)) 600
Adb-Sleep 1200
$t5 = Tap-Text -Pattern '^VPN Settings$' -Optional -Timeout 4
if (-not $t5) { $t5 = Tap-Text -Pattern 'VPN Settings|Protocol.*DNS|DNS.*port' -Optional -Timeout 3 }
Adb-Sleep 2400
$captures += Capture '05_vpn_settings'

# ── 06: Profile screen (plan badge · subscription · account) ─────────────────────
Adb-Key 4; Adb-Sleep 700        # back from VPN Settings screen
Adb-Key 4; Adb-Sleep 700        # back from main Settings to nav root
Tap-Text -Pattern '^Profile$' -Optional -Timeout 4 | Out-Null
Adb-Sleep 2200
$captures += Capture '06_profile'

# ── 07: Subscription / plan comparison ───────────────────────────────────────────
Tap-Text -Pattern '^Subscription$|Manage billing|RECON plan|Upgrade for premium' -Optional -Timeout 4 | Out-Null
Adb-Sleep 2400
$captures += Capture '07_subscription'

# ── 08: Connecting state — power button tap from Home ────────────────────────────
# Navigate back to the Home tab, then find the circular power button.
# The power button has content-desc "Connect" (same string as the bottom-nav tab),
# so we distinguish it by Y-position: nav item is in the bottom ~16% of the screen.
Adb-Key 4; Adb-Sleep 700        # back from subscription to profile
Tap-Text -Pattern '^Connect$' -Optional -Timeout 4 | Out-Null   # bottom nav → Home
Adb-Sleep 1800
$xml8   = Get-UiDump
$nodes8 = Get-UiNodes $xml8
$navThresh = [int]($scrH * 0.84)
$pwr = $nodes8 | Where-Object { $_.Desc -eq 'Connect' -and $_.CY -lt $navThresh } | Select-Object -First 1
if (-not $pwr) { $pwr = [pscustomobject]@{ CX=$midX; CY=[int]($scrH*0.37) } }
Write-Host "  [tap] power-button ($($pwr.CX),$($pwr.CY))" -ForegroundColor DarkGray
Adb-Tap $pwr.CX $pwr.CY
Adb-Sleep 3200
# Capture now — the frame will show either the Android VPN-permission dialog (great!)
# or the Connecting… animation on the home screen.
$captures += Capture '08_connecting'

# -- 5. Resize --
Write-Host "[run] Resizing to ${TargetW}x${TargetH}..." -ForegroundColor Cyan
foreach ($raw in $captures) {
    $name = [IO.Path]::GetFileNameWithoutExtension($raw)
    $out  = Join-Path $OutDir "$name.png"
    Resize-PlayStore -InPath $raw -OutPath $out -TargetW $TargetW -TargetH $TargetH
    Write-Host "  [ok] $name" -ForegroundColor Green
}

if (-not $KeepEmulator -and -not $SkipBoot) {
    Write-Host "[run] Stopping emulator..."
    & $AdbExe emu kill | Out-Null
}

Write-Host "`n[DONE] Screenshots ready at $OutDir" -ForegroundColor Green
