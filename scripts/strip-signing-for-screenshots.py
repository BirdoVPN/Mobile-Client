#!/usr/bin/env python3
"""Rewrite iosApp/project.yml for a screenshot run.

App Store screenshots have to be captured by driving the real app with XCUITest:
`simctl` cannot tap or type, and the app gates on a consent modal and then on a
session held in the keychain, so nothing outside the app can seed a login.

The shipping project pins a signing identity, a provisioning profile and an
entitlements file per target and per SDK. Those settings beat anything passed to
xcodebuild on the command line, which is why overriding them there does not
work — the attempts failed with "requires a provisioning profile" and then
"profile doesn't include signing certificate". Rewriting project.yml before
`xcodegen generate` is the only place the decision can actually be made.

Three different transforms are needed, which is why this is not a blanket strip:

  * The APP keeps an entitlements file, but a screenshot-only one. Removing its
    entitlements entirely broke sign-in — KeychainService writes with
    kSecAttrAccessGroup and kSecUseDataProtectionKeychain, and the
    data-protection keychain refuses an app carrying no application-identifier
    ("Could not securely store your session. Please try again.").

  * The EXTENSION loses its entitlements outright. They demand a provisioning
    profile, and the packet tunnel cannot load in an ad-hoc build regardless.

  * The UI TEST RUNNER must get NO entitlements and must be signed. It pins
    CODE_SIGNING_ALLOWED to "NO", which left it unsigned and macOS SIGKILLed it
    ("Test crashed with signal kill before establishing connection"). Note it
    must not inherit the app's entitlements either: passing
    CODE_SIGN_ENTITLEMENTS on the xcodebuild command line applied them to every
    target, giving the runner an application-identifier that did not match its
    own bundle id, and launchd then refused to spawn it ("Runningboard has
    returned error 5 ... Launchd job spawn failed").

Usage:  strip-signing-for-screenshots.py <path/to/project.yml>
Writes the file in place; the caller is responsible for restoring it.
"""
import re
import sys

SCREENSHOT_ENTITLEMENTS = "iosApp/BirdoVPN-Screenshots.entitlements"

# Settings that either demand a provisioning profile or pin an identity a
# screenshot build cannot use.
DROP = ("PROVISIONING_PROFILE_SPECIFIER", "CODE_SIGN_IDENTITY", "CODE_SIGNING_ALLOWED")
DROP_PATTERN = re.compile(r"^(\s*)(?:%s)(?:\[[^\]]+\])?\s*:" % "|".join(DROP))

# Entitlements are redirected rather than dropped, but only for the app: the
# app's files live under iosApp/, the extension's under PacketTunnel/.
ENTITLEMENTS_PATTERN = re.compile(
    r"^(\s*)(CODE_SIGN_ENTITLEMENTS(?:\[[^\]]+\])?)\s*:\s*(\S+)\s*$"
)


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2

    path = sys.argv[1]
    with open(path, "r", encoding="utf-8") as handle:
        lines = handle.read().split("\n")

    out = []
    redirected = dropped = 0

    for line in lines:
        entitlements = ENTITLEMENTS_PATTERN.match(line)
        if entitlements:
            indent, key, value = entitlements.groups()
            if value.startswith("iosApp/"):
                out.append("%s%s: %s" % (indent, key, SCREENSHOT_ENTITLEMENTS))
                redirected += 1
            else:
                out.append("%s# [screenshot-run] %s" % (indent, line.lstrip()))
                dropped += 1
            continue

        drop = DROP_PATTERN.match(line)
        if drop:
            out.append("%s# [screenshot-run] %s" % (drop.group(1), line.lstrip()))
            dropped += 1
            continue

        out.append(line)

    if not redirected:
        print("ERROR: no app entitlements setting matched — has project.yml changed shape?",
              file=sys.stderr)
        return 1

    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(out))

    print("redirected %d app entitlements to %s, commented out %d other settings"
          % (redirected, SCREENSHOT_ENTITLEMENTS, dropped))
    return 0


if __name__ == "__main__":
    sys.exit(main())
