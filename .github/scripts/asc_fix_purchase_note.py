#!/usr/bin/env python3
"""Correct the sentence in the App Review notes that described the OLD flow.

APPLE 2.1(b), 2026-09-29. The notes told the reviewer:

    "while signed out the button reads 'Sign in to subscribe' and raises the
     sign-in sheet first, because the server binds a purchase to an account
     before granting entitlement."

and then STOPPED. They never said what happens after sign-in. Before #449 the
answer was "nothing" - purchase() returned at the guard and ContentView moved
the user to the Connect tab - so a reviewer who followed these notes exactly
signed in, saw no payment sheet, and reported the app broken. They were right.

#449 makes the purchase resume by itself. These notes must now say so, or the
next reviewer stops at the same sentence and draws the same conclusion.

MODE=plan prints the before/after; MODE=apply PATCHes. Idempotent: if the new
sentence is already there it does nothing.
"""
import base64
import os
import sys
import time

import jwt
import requests

API = "https://api.appstoreconnect.apple.com/v1"
BUNDLE = os.environ.get("BUNDLE_ID", "app.birdo.vpn")
MODE = os.environ.get("MODE", "plan")

# Matched on the distinctive tail so both wordings in the notes are caught.
OLD_TAILS = (
    "because the server binds a purchase to an account before granting entitlement.",
    "because the server binds a purchase to an account first.",
)
ADDITION = (
    " Signing in then CONTINUES THE PURCHASE AUTOMATICALLY: Apple's payment "
    "sheet appears on its own and you do not need to tap Subscribe again."
)


def token() -> str:
    now = int(time.time())
    return jwt.encode(
        {"iss": os.environ["ASC_ISSUER_ID"], "iat": now, "exp": now + 19 * 60,
         "aud": "appstoreconnect-v1"},
        base64.b64decode(os.environ["ASC_KEY_B64"]).decode(),
        algorithm="ES256",
        headers={"kid": os.environ["ASC_KEY_ID"], "typ": "JWT"},
    )


def main() -> int:
    s = requests.Session()
    s.headers["Authorization"] = f"Bearer {token()}"

    apps = s.get(f"{API}/apps", params={"filter[bundleId]": BUNDLE}, timeout=60).json()["data"]
    if not apps:
        sys.exit(f"no app for bundle {BUNDLE}")
    app_id = apps[0]["id"]

    versions = s.get(f"{API}/apps/{app_id}/appStoreVersions",
                     params={"limit": 20}, timeout=60).json()["data"]
    touched = 0
    for v in versions:
        a = v["attributes"]
        # Only versions still editable can have their notes changed at all.
        if a.get("appStoreState") in ("READY_FOR_SALE", "REPLACED_WITH_NEW_VERSION"):
            continue
        r = s.get(f"{API}/appStoreVersions/{v['id']}/appStoreReviewDetail", timeout=60)
        if r.status_code >= 400:
            print(f"  [{a.get('versionString')} {a.get('platform')}] no review detail")
            continue
        detail = r.json()["data"]
        notes = detail["attributes"].get("notes") or ""
        label = f"{a.get('versionString')} {a.get('platform')} {a.get('appStoreState')}"

        if ADDITION.strip() in notes:
            print(f"  [{label}] already corrected - nothing to do")
            continue
        hits = [t for t in OLD_TAILS if t in notes]
        if not hits:
            print(f"  [{label}] the old sentence is not present - NOT editing blindly")
            continue

        new = notes
        for t in hits:
            new = new.replace(t, t + ADDITION)
        print(f"  [{label}] {len(notes)} -> {len(new)} chars, {len(hits)} sentence(s) corrected")
        if len(new) > 4000:
            print("    SKIP: would exceed ASC's 4000-char notes limit")
            continue
        if MODE != "apply":
            print("    (plan only)")
            continue
        pr = s.patch(f"{API}/appStoreReviewDetails/{detail['id']}",
                     json={"data": {"type": "appStoreReviewDetails", "id": detail["id"],
                                    "attributes": {"notes": new}}}, timeout=60)
        if pr.status_code >= 400:
            print(f"    PATCH FAILED {pr.status_code}: {pr.text[:300]}")
            continue
        print("    PATCHED")
        touched += 1
    print(f"\nversions corrected: {touched}  (mode={MODE})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
