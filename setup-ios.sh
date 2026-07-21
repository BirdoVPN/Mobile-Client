#!/bin/bash
set -euo pipefail

# ─── Birdo VPN iOS - Mac Setup & Run Script ─────────────────────
# Run from: birdo-client-mobile/
# Usage: chmod +x setup-ios.sh && ./setup-ios.sh

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${GREEN}═══════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  Birdo VPN iOS - Setup & Build                   ${NC}"
echo -e "${GREEN}═══════════════════════════════════════════════════${NC}"

# ── Step 1: Check prerequisites ─────────────────────────────────
echo -e "\n${YELLOW}[1/6] Checking prerequisites...${NC}"

if ! command -v xcodebuild &>/dev/null; then
  echo -e "${RED}[FAIL] Xcode not found. Install from the Mac App Store.${NC}"
  exit 1
fi
echo "[OK] Xcode $(xcodebuild -version | head -1 | awk '{print $2}')"

if ! command -v java &>/dev/null; then
  echo -e "${RED}[FAIL] Java not found. Install with: brew install openjdk@17${NC}"
  exit 1
fi
echo "[OK] Java $(java -version 2>&1 | head -1)"

if ! command -v brew &>/dev/null; then
  echo -e "${YELLOW} [WARN] Homebrew not found — installing XcodeGen via direct download${NC}"
else
  echo "[OK] Homebrew"
fi

# ── Step 2: Install XcodeGen (generates .xcodeproj from project.yml) ──
echo -e "\n${YELLOW}[2/6] Ensuring XcodeGen is installed...${NC}"
if ! command -v xcodegen &>/dev/null; then
  if command -v brew &>/dev/null; then
    brew install xcodegen
  else
    echo -e "${RED}[FAIL] XcodeGen not found. Install Homebrew first, then: brew install xcodegen${NC}"
    exit 1
  fi
fi
echo "[OK] XcodeGen $(xcodegen version 2>/dev/null || echo 'installed')"

# ── Step 3: Build KMP shared framework ──────────────────────────
echo -e "\n${YELLOW}[3/6] Building KMP shared framework for iOS Simulator...${NC}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Detect architecture
ARCH=$(uname -m)
if [ "$ARCH" = "arm64" ]; then
  GRADLE_TARGET=":shared:linkDebugFrameworkIosSimulatorArm64"
  FRAMEWORK_DIR="shared/build/bin/iosSimulatorArm64/debugFramework"
else
  GRADLE_TARGET=":shared:linkDebugFrameworkIosX64"
  FRAMEWORK_DIR="shared/build/bin/iosX64/debugFramework"
fi

echo "  Architecture: $ARCH → $GRADLE_TARGET"

if [ ! -f "gradlew" ]; then
  echo -e "${RED}[FAIL] No gradlew found. Run this from the birdo-client-mobile/ directory.${NC}"
  exit 1
fi

chmod +x gradlew
./gradlew $GRADLE_TARGET --no-daemon

if [ ! -d "$FRAMEWORK_DIR/BirdoShared.framework" ]; then
  echo -e "${RED}[FAIL] Framework build failed — BirdoShared.framework not found at $FRAMEWORK_DIR${NC}"
  exit 1
fi
echo -e " ${GREEN}[OK] BirdoShared.framework built${NC}"

# ── Step 4: Build the BirdoPQNative XCFramework ──────────────────
# Gitignored build artifact (Rust ML-KEM-1024). project.yml links
# Vendor/BirdoPQNative.xcframework, so the Xcode build fails without it.
echo -e "\n${YELLOW}[4/8] Building BirdoPQNative.xcframework...${NC}"
"$SCRIPT_DIR/scripts/build-birdo-pq-xcframework.sh" >/dev/null
echo -e " ${GREEN}[OK] BirdoPQNative.xcframework built${NC}"

# ── Step 5: Build libwg-go.a (WireGuard Go tunnel runtime) ───────
# WireGuardKit's Package.swift declares .linkedLibrary("wg-go"), but SwiftPM
# cannot run the Makefile that produces it — without this the PacketTunnel
# extension fails to link with: ld: library 'wg-go' not found.
# NOTE: the upstream Makefile maps GOOS only for macosx/iphoneos, so the
# simulator needs GOOS_iphonesimulator=ios passed explicitly.
echo -e "\n${YELLOW}[5/8] Building libwg-go.a (iOS Simulator)...${NC}"
if ! command -v go &>/dev/null; then
  echo -e "${RED}[FAIL] Go not found (required by WireGuardKitGo). Install with: brew install go${NC}"
  exit 1
fi
WG_LIB_DIR="$SCRIPT_DIR/iosApp/.build-wg/iphonesimulator"
mkdir -p "$WG_LIB_DIR"
( cd "$SCRIPT_DIR/iosApp/Vendor/wireguard-apple/Sources/WireGuardKitGo" && \
  make ARCHS=arm64 PLATFORM_NAME=iphonesimulator GOOS_iphonesimulator=ios \
       SDKROOT="$(xcrun --sdk iphonesimulator --show-sdk-path)" \
       CONFIGURATION_BUILD_DIR="$WG_LIB_DIR" \
       CONFIGURATION_TEMP_DIR=/tmp/birdo-wggo-sim >/dev/null )
echo -e " ${GREEN}[OK] libwg-go.a -> $WG_LIB_DIR${NC}"

# ── Step 6: Generate Xcode project ──────────────────────────────
echo -e "\n${YELLOW}[6/8] Generating Xcode project from project.yml...${NC}"
cd iosApp
xcodegen generate
echo -e " ${GREEN}[OK] BirdoVPN.xcodeproj created${NC}"

# ── Step 5: Find a simulator ────────────────────────────────────
echo -e "\n${YELLOW}[7/8] Finding iOS Simulator...${NC}"

# Prefer iPhone 15/16/17 models
SIMULATOR_ID=$(xcrun simctl list devices available -j | python3 -c "
import json, sys, re
data = json.load(sys.stdin)
# Prefer: booted iPhone 15-17, then any available iPhone 15-17, then any iPhone
preferred = re.compile(r'iPhone 1[567]')
for runtime, devices in sorted(data['devices'].items(), reverse=True):
    if 'iOS' in runtime:
        for d in devices:
            if preferred.search(d['name']) and d['state'] == 'Booted':
                print(d['udid']); sys.exit(0)
for runtime, devices in sorted(data['devices'].items(), reverse=True):
    if 'iOS' in runtime:
        for d in devices:
            if preferred.search(d['name']):
                print(d['udid']); sys.exit(0)
for runtime, devices in sorted(data['devices'].items(), reverse=True):
    if 'iOS' in runtime:
        for d in devices:
            if 'iPhone' in d['name']:
                print(d['udid']); sys.exit(0)
" 2>/dev/null || true)

if [ -z "$SIMULATOR_ID" ]; then
  echo -e "${YELLOW}  No iPhone simulator found. Creating one...${NC}"
  RUNTIME=$(xcrun simctl list runtimes -j | python3 -c "
import json, sys
data = json.load(sys.stdin)
for r in reversed(data['runtimes']):
    if r['isAvailable'] and 'iOS' in r['name']:
        print(r['identifier']); sys.exit(0)
")
  # Try iPhone 16 Pro first, fall back to iPhone 15 Pro
  SIMULATOR_ID=$(xcrun simctl create "iPhone 16 Pro" "com.apple.CoreSimulator.SimDeviceType.iPhone-16-Pro" "$RUNTIME" 2>/dev/null || \
    xcrun simctl create "iPhone 15 Pro" "com.apple.CoreSimulator.SimDeviceType.iPhone-15-Pro" "$RUNTIME")
  echo "  Created simulator: $SIMULATOR_ID"
fi

SIM_NAME=$(xcrun simctl list devices -j | python3 -c "
import json, sys
data = json.load(sys.stdin)
for devices in data['devices'].values():
    for d in devices:
        if d['udid'] == '$SIMULATOR_ID':
            print(d['name']); sys.exit(0)
print('Unknown')
" 2>/dev/null || echo "Unknown")

echo -e "[OK] Using simulator: $SIM_NAME ($SIMULATOR_ID)"

# ── Step 6: Build and Run ────────────────────────────────────────
echo -e "\n${YELLOW}[8/8] Building and launching on simulator...${NC}"

# Boot simulator if needed
xcrun simctl boot "$SIMULATOR_ID" 2>/dev/null || true
open -a Simulator

# ARCHS/EXCLUDED_ARCHS: the KMP framework above is built ONLY for
# iosSimulatorArm64, so an x86_64 simulator slice cannot link.
# LIBRARY_SEARCH_PATHS: where step 5 put libwg-go.a (resolves -lwg-go).
xcodebuild \
  -project BirdoVPN.xcodeproj \
  -scheme BirdoVPN \
  -destination "id=$SIMULATOR_ID" \
  -configuration Debug \
  ARCHS=arm64 \
  EXCLUDED_ARCHS=x86_64 \
  LIBRARY_SEARCH_PATHS="$WG_LIB_DIR" \
  CODE_SIGN_IDENTITY="" \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGNING_ALLOWED=NO \
  build 2>&1 | tail -5

echo ""
echo -e "${GREEN}═══════════════════════════════════════════════════${NC}"
echo -e "${GREEN} [OK] Build complete! ${NC}"
echo -e "${GREEN}═══════════════════════════════════════════════════${NC}"
echo ""
echo "To run in simulator:"
echo "  open iosApp/BirdoVPN.xcodeproj"
echo "  Select an iPhone simulator and press ⌘R"
echo ""
echo "Or install the built app:"
echo "  xcrun simctl install $SIMULATOR_ID build/Debug-iphonesimulator/BirdoVPN.app"
echo "  xcrun simctl launch $SIMULATOR_ID app.birdo.vpn"
