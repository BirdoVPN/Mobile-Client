#!/usr/bin/env bash
#
# Record the baseline profile that ships in the release APK/AAB.
#
# READ THIS FIRST IF YOU ARE ABOUT TO EDIT THE PROFILE BY HAND: do not. The file
# at app/src/release/generated/baselineProfiles/baseline-prof.txt is a RECORDING
# of the classes and methods a real cold start touches. Android AOT-compiles and
# pre-pages exactly what it lists, so entries that are not on the real startup
# path do not merely fail to help -- they spend the startup budget on the wrong
# code and make launches slower. Nothing in the build or in CI can detect a
# fabricated profile; that is why issue #358 exists and why two hand-written
# attempts were rejected.
#
# USAGE
#   scripts/generate-baseline-profile.sh                # Gradle Managed Device (default)
#   scripts/generate-baseline-profile.sh --connected    # a physical phone over adb
#
# WHICH ONE TO USE
#   Managed device is the default and is what CI runs
#   (.github/workflows/baseline-profile.yml). It boots a Pixel 6 / API 34 /
#   google_apis x86_64 emulator, records, and tears it down, so the result is
#   reproducible by anyone. It needs hardware virtualisation: KVM on Linux,
#   WHPX or Hyper-V on Windows, HVF on an Intel Mac. Apple Silicon cannot run
#   the x86_64 image at usable speed -- use --connected there.
#
#   --connected uses whatever single device adb sees. Use it when you want the
#   profile recorded on real hardware. The device MUST be rooted or a userdebug
#   build: recording pulls the ART profile out of /data/misc/profiles, which
#   needs `adb root`. A stock retail phone will fail at that last step.
#
# WHAT TO DO WITH THE RESULT
#   Commit the changed files under app/src/release/generated/baselineProfiles/.
#   Say in the commit or PR body WHICH device it was recorded on. A profile whose
#   provenance is not written down is indistinguishable from a guess six months
#   later.
set -euo pipefail

cd "$(dirname "$0")/.."

CONNECTED=0
for arg in "$@"; do
  case "$arg" in
    --connected) CONNECTED=1 ;;
    -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

GRADLE=./gradlew
[ -x "$GRADLE" ] || chmod +x "$GRADLE"

if [ "$CONNECTED" = "1" ]; then
  command -v adb >/dev/null 2>&1 || {
    echo "adb is not on PATH; --connected cannot work." >&2; exit 1; }

  # Fail on zero or several devices HERE, not after a ten-minute build. With two
  # devices attached the run picks one without saying which, and the recorded
  # profile silently belongs to a device you did not choose.
  device_count=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
  if [ "$device_count" != "1" ]; then
    echo "Expected exactly one attached device, found $device_count." >&2
    adb devices >&2
    exit 1
  fi

  model=$(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')
  release=$(adb shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')
  echo "Recording on connected device: $model (Android $release)"
  echo "Put this in the commit message -- provenance is the point."

  # `adb root` is what the recording actually needs. Checking it up front turns
  # a confusing failure at the end of the run into a clear one at the start.
  if ! adb root >/dev/null 2>&1 || [ "$(adb shell id -u | tr -d '\r')" != "0" ]; then
    echo "adb root failed: this device cannot expose /data/misc/profiles, so no profile can be recorded on it. Use a userdebug/rooted device, or drop --connected and use the managed device." >&2
    exit 1
  fi

  "$GRADLE" :app:generateReleaseBaselineProfile \
    -Pandroidx.baselineprofile.useconnecteddevices=true \
    -Pandroidx.baselineprofile.skipgeneration=false
else
  echo "Recording on the Gradle Managed Device 'profileGenDevice' (Pixel 6, API 34, google_apis, x86_64)."
  echo "First run downloads the system image; expect it to take a while."
  "$GRADLE" :app:generateReleaseBaselineProfile
fi

profile=app/src/release/generated/baselineProfiles/baseline-prof.txt
if [ ! -s "$profile" ]; then
  echo "Generation reported success but $profile is missing or empty. Do NOT hand-write one." >&2
  exit 1
fi

echo
echo "Recorded $(wc -l < "$profile") lines into $profile"
echo "Review with: git diff --stat -- app/src/release/generated/baselineProfiles/"
