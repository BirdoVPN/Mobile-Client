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
         rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android

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
    $abis = @("arm64-v8a", "armeabi-v7a", "x86_64")

    Write-Host ">>> cargo ndk -t $($abis -join ' -t ') build $profileFlag" -ForegroundColor Cyan
    & cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o $jniLibsDir build $profileFlag
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
