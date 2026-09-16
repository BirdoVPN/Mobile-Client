<#
 .SYNOPSIS
   Cross-compiles the rosenpass-jni Rust crate for all Android ABIs and
   copies the resulting .so files into app/src/main/jniLibs/<abi>/.

 .DESCRIPTION
   Requires:
     - Rust >= 1.75 (rustup toolchain)
     - cargo-ndk (`cargo install cargo-ndk`)
     - Android NDK r26 or newer (path via $env:ANDROID_NDK_HOME)
     - Rust Android targets:
         rustup target add aarch64-linux-android armv7-linux-androideabi `
                           x86_64-linux-android i686-linux-android

 .PARAMETER Profile
   "release" (default) or "debug".

 .EXAMPLE
   pwsh native/build.ps1
   pwsh native/build.ps1 -Profile debug
#>
[CmdletBinding()]
param(
    [ValidateSet("release", "debug")]
    [string]$Profile = "release"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$crateDir = Join-Path $root "native\rosenpass-jni"
$jniLibsDir = Join-Path $root "app\src\main\jniLibs"

Write-Host ">>> rosenpass-jni native build ($Profile)" -ForegroundColor Cyan

if (-not $env:ANDROID_NDK_HOME) {
    # Fall back to common SDK location
    $candidate = Join-Path $env:LOCALAPPDATA "Android\Sdk\ndk"
    if (Test-Path $candidate) {
        $latest = Get-ChildItem $candidate | Sort-Object Name -Descending | Select-Object -First 1
        if ($latest) {
            $env:ANDROID_NDK_HOME = $latest.FullName
            Write-Host "    auto-detected ANDROID_NDK_HOME=$($env:ANDROID_NDK_HOME)" -ForegroundColor DarkGray
        }
    }
}
if (-not $env:ANDROID_NDK_HOME) {
    throw "ANDROID_NDK_HOME is not set and no NDK found under %LOCALAPPDATA%\Android\Sdk\ndk"
}

# cargo-ndk forwards to cc, which needs to find the NDK toolchain
$env:ANDROID_NDK_ROOT = $env:ANDROID_NDK_HOME

Push-Location $crateDir
try {
    $profileFlag = if ($Profile -eq "release") { "--release" } else { "" }
    # All four live Android ABIs.
    $abis = @("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    Write-Host ">>> cargo ndk -t $($abis -join ' -t ') build $profileFlag" -ForegroundColor Cyan
    & cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -t x86 -o $jniLibsDir build $profileFlag
    if ($LASTEXITCODE -ne 0) {
        throw "cargo ndk build failed (exit $LASTEXITCODE)"
    }

    Write-Host ">>> built .so files:" -ForegroundColor Green
    Get-ChildItem -Recurse -Filter "librosenpass_jni.so" $jniLibsDir | ForEach-Object {
        $size = [math]::Round($_.Length / 1MB, 2)
        Write-Host "    $($_.FullName)  ${size} MB" -ForegroundColor DarkGray
    }
}
finally {
    Pop-Location
}

# -- ISA-baseline gate on what was just built --------------------------------
#
# The same bash script CI runs on the packaged APK/AAB (scripts/
# check_no_sha3_ext.sh), run here on the jniLibs output so a re-enabled
# pqcrypto-mlkem `neon`/`avx2` feature (the 1.4.25 SIGILL) fails on the
# developer's machine. It needs bash (Git for Windows ships one) and a
# disassembler: the NDK's llvm-objdump.exe (via ANDROID_NDK_HOME) or
# `rustup component add llvm-tools`. Missing either is a LOUD warning locally
# and a hard failure when ROSENPASS_ISA_GATE_REQUIRED=1 (android.yml).
$gate = Join-Path $root "scripts\check_no_sha3_ext.sh"
if (-not (Test-Path $gate)) {
    throw "$gate is missing -- the ISA-baseline gate cannot run"
}
$bash = Get-Command bash -ErrorAction SilentlyContinue
$gateRc = 1
if ($bash) {
    Write-Host ">>> ISA-baseline gate: $gate $jniLibsDir" -ForegroundColor Cyan
    # Forward slashes: Git Bash resolves them on Windows; a backslash path is
    # mangled by MSYS path conversion.
    & $bash.Source ($gate.Replace('\', '/')) ($jniLibsDir.Replace('\', '/'))
    $gateRc = $LASTEXITCODE
} else {
    Write-Warning "bash not found on PATH -- the ISA-baseline gate could not run"
}
if ($gateRc -ne 0) {
    if ($env:ROSENPASS_ISA_GATE_REQUIRED -eq "1") {
        throw "ISA-baseline gate failed (exit $gateRc) and ROSENPASS_ISA_GATE_REQUIRED=1"
    }
    Write-Warning "!!! scripts/check_no_sha3_ext.sh did NOT pass (exit $gateRc)."
    Write-Warning "!!! If it says no disassembler was found: rustup component add llvm-tools -- CI will run this gate and fail."
    Write-Warning "!!! If it names an instruction, the .so you just built WILL SIGILL on devices without that CPU feature. Do not ship it."
}
