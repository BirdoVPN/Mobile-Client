#!/usr/bin/env python3
"""App Store Connect metadata for the two fields Apple's automated review rejects on.

Sets, for BOTH the iOS and macOS apps:

  * the Terms of Use (EULA) link in the App DESCRIPTION            (guideline 3.1.2)
  * the VPN data answers in App Review Information NOTES           (guideline 2.1.0)

MODE=read changes nothing and prints what each platform currently has. Run that
first — the point is to act on observed state rather than on an assumption about
what an earlier session did.

Deliberate design choices, because this writes to a live storefront:

  * Only versions in an EDITABLE state are touched. A version Apple is actively
    reviewing cannot be edited, and trying is how you get a confusing 409 rather
    than a clear message.
  * The description is READ, then the EULA block appended only if its marker is
    absent. Re-running is a no-op instead of stacking duplicate blocks.
  * Every write is preceded by printing the exact before/after length, so the
    run log is the audit trail.
"""

import base64
import os
import sys
import time

import jwt
import requests

API = "https://api.appstoreconnect.apple.com/v1"

# The marker makes the append idempotent. It is also human-visible text that
# belongs in the description anyway, so it costs the reader nothing.
EULA_MARKER = "Terms of Use (EULA):"

EULA_BLOCK = """

SUBSCRIPTION TERMS

Birdo VPN offers auto-renewing subscriptions:
- Operative (monthly or yearly)
- Sovereign (monthly or yearly)

Payment is charged to your Apple Account at confirmation of purchase. A
subscription renews automatically unless auto-renew is turned off at least 24
hours before the end of the current period; your account is charged for renewal
within 24 hours of the end of that period. You can manage your subscription and
turn off auto-renewal in your Apple Account settings after purchase.

Terms of Use (EULA): https://www.apple.com/legal/internet-services/itunes/dev/stdeula/
Privacy Policy: https://birdo.app/privacy
Birdo Terms of Service: https://birdo.app/terms
"""

# Audit 2026-09-29 (D-5 / B-15): the previous answers told App Review that
# account data was stored "on servers under our control in the United
# Kingdom" (it is hosted by Hetzner in Germany), that "No VPN usage data is
# shared ... with any third party" (Cloudflare resolves DNS by default and
# fronts the API), and described the collected data as far narrower than the
# account system's live connection record. Every sentence below follows
# AUDIT-2026-09-29/REMEDIATION-DECISIONS.md sections 1-3; change it only with
# that file.
VPN_ANSWERS = """
VPN FUNCTIONALITY - ANSWERS TO APP REVIEW'S QUESTIONS

Yes, the app has VPN features. Birdo VPN is a WireGuard client implemented as a
NetworkExtension packet-tunnel-provider.

1) What user information is the app collecting using the VPN?

Our VPN servers do not record the sites or addresses a user connects to, their
DNS queries, their traffic content, or a connection log. While a device is
connected, the VPN server holds that device's WireGuard public key and tunnel
IP address in memory.

The account system processes:
  - the account identifier: an email address, or an anonymous account number
  - the plan, and the devices the user adds
  - while a device is connected, a live connection record (account, server,
    device name, tunnel IP, connect time, last check-in). It is deleted when
    the user disconnects, or within 15 minutes of the last check-in, and is
    left out of our nightly backups. Our daily encrypted copy of the database
    files (kept 7 days, used for point-in-time recovery) can contain it as it
    stood at that moment
  - data-usage totals per billing period, to enforce the free plan's 10 GB
    allowance and fair use
  - App Store transactions, to grant the subscription that was bought
Every request the app makes to our API, including the periodic check-in,
arrives from the device's current IP address. The address is used transiently
for rate limiting (at most 15 minutes, or 24 hours for a security lockout) and
is not written to request logs. Sign-in security records keep only a salted
hash of the IP address, for 90 days.

2) For what purposes are you collecting this information?

Solely to operate the service: authenticating the account, applying plan and
device limits, enforcing the free plan's data allowance, and detecting abuse.
It is never used for advertising, profiling or behavioural analytics. The iOS
and macOS apps contain no advertising, analytics or crash-reporting SDK.

3) Will the data be shared with any third parties?

We do not sell user data or share it for advertising. Service providers
process it on our behalf to run the service: Hetzner hosts the account system,
Vultr hosts the VPN servers, and Cloudflare provides the API's network edge
and, unless the user sets their own DNS, resolves the DNS queries sent through
the VPN (it sees the VPN server's address, not the user's). Apple processes
in-app purchases; web subscriptions are sold by Polar.sh as merchant of record.

Birdo Networks Ltd operates the service on rented servers. The account
database is hosted by Hetzner in Germany (EU); encrypted backups are kept in
Cloudflare R2 object storage.

Privacy policy: https://birdo.app/privacy
"""

# Sentences from earlier versions of VPN_ANSWERS that are known to be false.
# apply mode never rewrites notes that already carry VPN answers (see
# handle()), so a store that still holds the old block would otherwise be
# reported as fine. These make that loud instead.
STALE_VPN_ANSWER_PHRASES = (
    "in the United Kingdom",
    "No VPN usage data is shared",
    "servers under our control",
    "never included in backups",
    # Second-pass #6: only the point-in-time-recovery bucket is documented as
    # EU-jurisdiction; the nightly-dump bucket's is unconfirmed.
    "Cloudflare R2 in the EU",
    "RAM-only",
    "zero-logs",
)

# App Store Connect refuses edits to a version the review team holds.
EDITABLE = {
    "PREPARE_FOR_SUBMISSION",
    "DEVELOPER_REJECTED",
    "REJECTED",
    "METADATA_REJECTED",
    "INVALID_BINARY",
    "READY_FOR_REVIEW",
}


def token() -> str:
    key_id = os.environ["ASC_KEY_ID"]
    issuer = os.environ["ASC_ISSUER_ID"]
    private_key = base64.b64decode(os.environ["ASC_KEY_B64"]).decode()
    now = int(time.time())
    return jwt.encode(
        {"iss": issuer, "iat": now, "exp": now + 19 * 60, "aud": "appstoreconnect-v1"},
        private_key,
        algorithm="ES256",
        headers={"kid": key_id, "typ": "JWT"},
    )


class Asc:
    def __init__(self) -> None:
        self.s = requests.Session()
        self.s.headers["Authorization"] = f"Bearer {token()}"

    def get(self, path, **params):
        r = self.s.get(f"{API}/{path}", params=params, timeout=60)
        r.raise_for_status()
        return r.json()

    def patch(self, path, payload):
        r = self.s.patch(f"{API}/{path}", json=payload, timeout=60)
        if r.status_code >= 400:
            print(f"    PATCH {path} -> {r.status_code}: {r.text[:600]}")
            r.raise_for_status()
        return r.json()


def platform_of(app_id: str, asc: Asc) -> set:
    vs = asc.get(f"apps/{app_id}/appStoreVersions", **{"limit": 20})
    return {v["attributes"]["platform"] for v in vs["data"]}


def handle(asc: Asc, app, want_platform: str, mode: str) -> None:
    app_id = app["id"]
    name = app["attributes"]["name"]
    bundle = app["attributes"]["bundleId"]
    print(f"\n=== {name}  ({bundle})  id={app_id} ===")

    versions = asc.get(
        f"apps/{app_id}/appStoreVersions",
        **{"limit": 20, "fields[appStoreVersions]": "versionString,appStoreState,platform"},
    )["data"]

    targets = [
        v
        for v in versions
        if v["attributes"]["platform"] == want_platform
        and v["attributes"]["appStoreState"] in EDITABLE
    ]
    for v in versions:
        a = v["attributes"]
        mark = "  <- editable" if v in targets else ""
        print(f"  version {a['versionString']:<10} {a['platform']:<10} {a['appStoreState']}{mark}")

    if not targets:
        print("  NO EDITABLE VERSION for this platform — nothing to do.")
        print("  (A version being actively reviewed cannot be edited.)")
        return

    for v in targets:
        vid = v["id"]
        vs = v["attributes"]["versionString"]

        # ---- 3.1.2 : description on the version localisation -------------
        locs = asc.get(
            f"appStoreVersions/{vid}/appStoreVersionLocalizations",
            **{"limit": 50, "fields[appStoreVersionLocalizations]": "locale,description"},
        )["data"]
        for loc in locs:
            locale = loc["attributes"]["locale"]
            desc = loc["attributes"].get("description") or ""
            has = EULA_MARKER in desc
            print(f"  [{vs}] description {locale}: {len(desc)} chars, EULA link present = {has}")
            if has or mode != "apply":
                continue
            new = desc.rstrip() + EULA_BLOCK
            if len(new) > 4000:
                print(f"    SKIP {locale}: would exceed the 4000-char description limit ({len(new)})")
                continue
            asc.patch(
                f"appStoreVersionLocalizations/{loc['id']}",
                {
                    "data": {
                        "type": "appStoreVersionLocalizations",
                        "id": loc["id"],
                        "attributes": {"description": new},
                    }
                },
            )
            print(f"    UPDATED {locale}: {len(desc)} -> {len(new)} chars")

        # ---- 2.1.0 : VPN answers in App Review Information ---------------
        try:
            detail = asc.get(f"appStoreVersions/{vid}/appStoreReviewDetail")["data"]
        except requests.HTTPError:
            print(f"  [{vs}] no appStoreReviewDetail — skipping review notes")
            continue

        notes = detail["attributes"].get("notes") or ""
        # Match on the QUESTIONS themselves, not on a heading. Prepared notes for
        # the two platforms used different headings ("VPN FUNCTIONALITY" vs
        # "VPN DATA QUESTIONS"), and keying on a heading reports a false negative
        # for one of them — which would then append a duplicate.
        has = any(
            m in notes
            for m in (
                "VPN FUNCTIONALITY",
                "VPN DATA QUESTIONS",
                "information is the app collecting",
                "information is collected using the VPN",
            )
        )
        print(f"  [{vs}] review notes: {len(notes)} chars, VPN answers present = {has}")
        stale = [phrase for phrase in STALE_VPN_ANSWER_PHRASES if phrase in notes]
        if stale:
            # Never rewritten automatically: the notes may have been edited by
            # hand since, and a blind replace could destroy that. The owner
            # replaces the old block with VPN_ANSWERS in App Store Connect.
            print(f"  [{vs}] !! STALE VPN ANSWERS in the review notes: {stale}")
            print(f"  [{vs}] !! Replace the old answers block with VPN_ANSWERS by hand in App Store Connect.")
        if mode == "read":
            print("  ----- CURRENT REVIEW NOTES -----")
            for line in notes.splitlines():
                print(f"  | {line}")
            print("  ----- END -----")
        if has or mode != "apply":
            continue
        new_notes = (notes.rstrip() + "\n" + VPN_ANSWERS).strip()
        if len(new_notes) > 4000:
            print(f"    SKIP notes: would exceed the 4000-char limit ({len(new_notes)})")
            continue
        asc.patch(
            f"appStoreReviewDetails/{detail['id']}",
            {
                "data": {
                    "type": "appStoreReviewDetails",
                    "id": detail["id"],
                    "attributes": {"notes": new_notes},
                }
            },
        )
        print(f"    UPDATED notes: {len(notes)} -> {len(new_notes)} chars")


def main() -> int:
    mode = os.environ.get("MODE", "read")
    which = os.environ.get("PLATFORMS", "both")
    print(f"mode={mode}  platforms={which}")
    if mode not in ("read", "apply"):
        print("MODE must be read or apply")
        return 2

    asc = Asc()
    apps = asc.get("apps", **{"limit": 50, "fields[apps]": "name,bundleId"})["data"]
    print(f"\n{len(apps)} app(s) visible to this API key:")
    for a in apps:
        print(f"  {a['attributes']['bundleId']:<32} {a['attributes']['name']}  id={a['id']}")

    wanted = []
    if which in ("both", "ios"):
        wanted.append("IOS")
    if which in ("both", "macos"):
        wanted.append("MAC_OS")

    for a in apps:
        plats = platform_of(a["id"], asc)
        for p in wanted:
            if p in plats:
                handle(asc, a, p, mode)

    print("\nDone." if mode == "apply" else "\nRead-only run — nothing was changed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
