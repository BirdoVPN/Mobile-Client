#!/usr/bin/env python3
"""Pick an available iPhone simulator UDID for `xcodebuild test`.

WHY: ios.yml used to hard-code `-destination 'platform=iOS Simulator,name=iPhone 16'`.
When the macos-26 image dropped that device, xcodebuild failed with "Unable to
find a device matching the provided destination specifier" — and, because the
test step piped to xcpretty without `set -o pipefail`, the job still reported
SUCCESS while running zero tests. Resolving the device at run time removes the
name from the contract entirely; the pipefail fix in ios.yml removes the
masking.

Reads `xcrun simctl list devices available --json` on stdin and prints ONE udid
(nothing at all if there is no usable device, which the caller treats as fatal).
Prefers the newest iOS runtime, and within it the last-listed iPhone, so the
choice tracks the image rather than a model name.
"""
import json
import re
import sys


def runtime_sort_key(runtime: str):
    """`com.apple.CoreSimulator.SimRuntime.iOS-26-4` -> (26, 4)."""
    numbers = [int(n) for n in re.findall(r"\d+", runtime.rsplit(".", 1)[-1])]
    return numbers or [0]


def main() -> int:
    devices = json.load(sys.stdin).get("devices", {})
    ios_runtimes = sorted(
        (r for r in devices if "iOS" in r and "watchOS" not in r and "tvOS" not in r),
        key=runtime_sort_key,
    )
    for runtime in reversed(ios_runtimes):
        iphones = [
            d for d in devices[runtime]
            # `--json` on an `available` listing already filters, but older
            # simctl builds still emit isAvailable, so honour it when present.
            if d.get("isAvailable", True) and d.get("name", "").startswith("iPhone")
        ]
        if iphones:
            chosen = iphones[-1]
            print(chosen["udid"])
            print(f"picked {chosen['name']} on {runtime}", file=sys.stderr)
            return 0
    print("no available iPhone simulator", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
