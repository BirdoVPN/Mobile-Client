#!/usr/bin/env python3
"""Neutralise the signing configuration in iosApp/project.yml for a screenshot run.

App Store screenshots have to be captured by driving the real app with XCUITest:
`simctl` cannot tap or type, and the app gates on a consent modal and then on a
session held in the keychain, so nothing outside the app can seed a login.

The shipping project pins a signing identity, a provisioning profile and an
entitlements file per target and per SDK. Those settings beat anything passed to
xcodebuild on the command line, which is why overriding them there does not
work — the attempts failed with "requires a provisioning profile" and then
"profile doesn't include signing certificate". Rewriting project.yml before
`xcodegen generate` is the only place the decision can actually be made.

What the screenshot build gives up, deliberately:
  * no entitlements   -> the packet-tunnel extension will not load
  * no App Sandbox    -> irrelevant to rendering the UI

What it keeps: the keychain. Unlike iOS, a macOS app has keychain access with no
entitlement at all, so sign-in still works — and sign-in is the gate in front of
every screen worth capturing.

CODE_SIGNING_ALLOWED is stripped too. The UI-test target pins it to "NO", which
left the test runner unsigned and macOS SIGKILLed it on launch ("Test crashed
with signal kill before establishing connection").

Usage:  strip-signing-for-screenshots.py <path/to/project.yml>
Writes the file in place; the caller is responsible for restoring it.
"""
import re
import sys

SETTINGS = (
    "CODE_SIGN_ENTITLEMENTS",
    "PROVISIONING_PROFILE_SPECIFIER",
    "CODE_SIGN_IDENTITY",
    "CODE_SIGNING_ALLOWED",
)

# Matches both the plain key and its per-SDK conditional form, e.g.
# `CODE_SIGN_IDENTITY[sdk=macosx*]:`.
PATTERN = re.compile(
    r"^(\s*)(?:%s)(?:\[[^\]]+\])?\s*:" % "|".join(SETTINGS)
)


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2

    path = sys.argv[1]
    with open(path, "r", encoding="utf-8") as handle:
        lines = handle.read().split("\n")

    out, dropped = [], 0
    for line in lines:
        match = PATTERN.match(line)
        if match:
            out.append("%s# [screenshot-run] %s" % (match.group(1), line.lstrip()))
            dropped += 1
        else:
            out.append(line)

    if not dropped:
        print("ERROR: no signing settings matched — has project.yml changed shape?",
              file=sys.stderr)
        return 1

    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(out))

    print("commented out %d signing settings in %s" % (dropped, path))
    return 0


if __name__ == "__main__":
    sys.exit(main())
