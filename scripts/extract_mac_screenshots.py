#!/usr/bin/env python3
"""Copy the Mac App Store screenshots out of exported XCUITest attachments.

Usage: extract_mac_screenshots.py ATTACHMENTS_DIR OUT_DIR

ATTACHMENTS_DIR is what `xcrun xcresulttool export attachments` wrote: a
manifest.json plus one file per attachment. Every named capture
(iosApp/BirdoVPNUITests/ScreenshotTests.swift `capture(...)`) is copied to
OUT_DIR as mac-<name>.png.

MR-1797: this decides whether the screenshots-macos job (macos.yml) passed, so
it must be able to fail. It exits 1 when there is no manifest, when any of the
four store captures (REQUIRED) is missing, or when the test captured 99-stuck
(it never signed in). Exiting 1 only on ZERO captures let a run that stopped
after 00-launch go green with one useless screenshot. Copying happens before
the verdict, so a failed run still uploads whatever it did capture.

scripts/tests/extract_mac_screenshots_test.sh runs this on fixtures in the
Android CI "Lint Check" job.
"""
import json
import os
import shutil
import sys

# The four store screenshots, in capture order. `00-already-signed-in` (a warm
# keychain) is kept when present but is not one of them: 00-launch is
# captured unconditionally first.
REQUIRED = ("00-launch", "01-connect", "02-servers", "03-settings")

# Only the test's own captures; XCUITest also attaches UI hierarchies,
# synthesized events and a screen recording.
PREFIXES = ("00-", "01-", "02-", "03-", "99-")


def main(argv):
    if len(argv) != 3:
        print("usage: extract_mac_screenshots.py ATTACHMENTS_DIR OUT_DIR", file=sys.stderr)
        return 2
    att, out = argv[1], argv[2]
    man = os.path.join(att, "manifest.json")
    if not os.path.exists(man):
        print("::error::no manifest.json in the exported attachments — the test attached nothing")
        return 1
    os.makedirs(out, exist_ok=True)
    with open(man, encoding="utf-8") as f:
        entries = json.load(f)
    extracted = []
    for entry in entries:
        for a in entry.get("attachments", []):
            human = a.get("suggestedHumanReadableName", "")
            if not human.startswith(PREFIXES):
                continue
            name = human.split("_")[0]
            src = os.path.join(att, a.get("exportedFileName", ""))
            if os.path.isfile(src):
                shutil.copy(src, os.path.join(out, "mac-%s.png" % name))
                extracted.append(name)
    print("extracted %d screenshots: %s" % (len(extracted), ", ".join(extracted) or "(none)"))

    failed = False
    missing = [name for name in REQUIRED if name not in extracted]
    if missing:
        print("::error::missing store screenshot(s): %s — the UI test did not reach every screen"
              % ", ".join(missing))
        failed = True
    stuck = [name for name in extracted if name.startswith("99-")]
    if stuck:
        print("::error::the UI test captured %s: it never signed in (see mac-%s.png)"
              % (", ".join(stuck), stuck[0]))
        failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
