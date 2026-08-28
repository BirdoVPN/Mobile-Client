#!/usr/bin/env python3
"""Attach the app versions and the subscriptions to App Store Connect review submissions.

DELIBERATELY STOPS SHORT OF SUBMITTING. It creates a submission where one does
not exist and adds the items to it. Pressing Submit stays a human decision,
because that is the irreversible, outward-facing step.

WHY THIS EXISTS
---------------
The review notes tell Apple that "four auto-renewing subscriptions ... are
submitted with this version". Read back from the API, all four are
READY_TO_SUBMIT, which means NOT attached to any submission. Submitting the
version without them makes that sentence false to a reviewer who has already
rejected the app twice.

An earlier attempt recorded that the API 409s when attaching subscriptions. That
may have been a duplicate-submission conflict rather than a hard limitation, so
this prints the exact status and body of every call instead of concluding from a
single failure.

MODE:
  plan   - print what WOULD be created and attached. Changes nothing.
  attach - create submissions where needed and add the items. Does NOT submit.
"""

import base64
import os
import sys
import time

import jwt
import requests

API = "https://api.appstoreconnect.apple.com/v1"
PLATFORMS = ("IOS", "MAC_OS")

EDITABLE = {
    "PREPARE_FOR_SUBMISSION",
    "DEVELOPER_REJECTED",
    "REJECTED",
    "METADATA_REJECTED",
    "INVALID_BINARY",
    "READY_FOR_REVIEW",
}

# A submission in one of these is finished and must not be reused.
CLOSED = {"COMPLETE", "CANCELING", "CANCELED"}


def token() -> str:
    now = int(time.time())
    return jwt.encode(
        {
            "iss": os.environ["ASC_ISSUER_ID"],
            "iat": now,
            "exp": now + 19 * 60,
            "aud": "appstoreconnect-v1",
        },
        base64.b64decode(os.environ["ASC_KEY_B64"]).decode(),
        algorithm="ES256",
        headers={"kid": os.environ["ASC_KEY_ID"], "typ": "JWT"},
    )


class Asc:
    def __init__(self):
        self.s = requests.Session()
        self.s.headers["Authorization"] = f"Bearer {token()}"

    def get(self, path, **params):
        r = self.s.get(f"{API}/{path}", params=params, timeout=60)
        r.raise_for_status()
        return r.json()

    def post(self, path, payload, label=""):
        r = self.s.post(f"{API}/{path}", json=payload, timeout=60)
        print(f"      POST {path} {label} -> {r.status_code}")
        if r.status_code >= 400:
            print(f"        {r.text[:700]}")
            return None
        return r.json()


def item_links(item):
    return [
        f"{k}={v['data'].get('id')}"
        for k, v in (item.get("relationships") or {}).items()
        if v.get("data")
    ]


def linked_ids(items):
    out = set()
    for it in items:
        for _, rel in (it.get("relationships") or {}).items():
            d = rel.get("data")
            if d and d.get("id"):
                out.add(d["id"])
    return out


def main() -> int:
    mode = os.environ.get("MODE", "plan")
    asc = Asc()

    apps = asc.get("apps", **{"limit": 20, "fields[apps]": "name,bundleId"})["data"]
    app = apps[0]
    app_id = app["id"]
    print(f"app: {app['attributes']['bundleId']} id={app_id}   mode={mode}")

    # --- the four subscriptions -------------------------------------------
    subs = []
    for g in asc.get(f"apps/{app_id}/subscriptionGroups", **{"limit": 20})["data"]:
        for s in asc.get(
            f"subscriptionGroups/{g['id']}/subscriptions",
            **{"limit": 50, "fields[subscriptions]": "productId,state"},
        )["data"]:
            subs.append(s)
    print("")
    print("subscriptions:")
    for s in subs:
        a = s["attributes"]
        print(f"  {a['productId']:<40} {a['state']}  id={s['id']}")

    # --- existing submissions (no fields filter - that is what 400'd) ------
    print("")
    print("existing review submissions:")
    try:
        existing = asc.get(f"apps/{app_id}/reviewSubmissions", **{"limit": 50})["data"]
    except requests.HTTPError as e:
        print(f"  unreadable: {e}")
        existing = []
    open_by_platform = {}
    for rs in existing:
        a = rs["attributes"]
        print(
            f"  {rs['id']} platform={a.get('platform')} state={a.get('state')} "
            f"submitted={a.get('submitted')}"
        )
        if a.get("state") not in CLOSED:
            open_by_platform.setdefault(a.get("platform"), rs)
    if not existing:
        print("  (none)")

    # --- versions ----------------------------------------------------------
    versions = asc.get(
        f"apps/{app_id}/appStoreVersions",
        **{"limit": 30, "fields[appStoreVersions]": "versionString,appStoreState,platform"},
    )["data"]
    by_platform = {}
    for v in versions:
        a = v["attributes"]
        if a["platform"] in PLATFORMS and a["appStoreState"] in EDITABLE:
            by_platform.setdefault(a["platform"], v)

    print("")
    print("plan:")
    for p in PLATFORMS:
        v = by_platform.get(p)
        if not v:
            print(f"  {p}: no editable version - SKIP")
            continue
        a = v["attributes"]
        rs = open_by_platform.get(p)
        print(f"  {p}: version {a['versionString']} ({a['appStoreState']}) id={v['id']}")
        print(f"       submission: {'reuse ' + rs['id'] if rs else 'CREATE new'}")

    if mode != "attach":
        print("")
        print("plan-only run - nothing was changed.")
        return 0

    # --- act ---------------------------------------------------------------
    subs_done = False
    for p in PLATFORMS:
        v = by_platform.get(p)
        if not v:
            continue
        print("")
        print(f"=== {p} ===")
        rs = open_by_platform.get(p)
        if not rs:
            created = asc.post(
                "reviewSubmissions",
                {
                    "data": {
                        "type": "reviewSubmissions",
                        "attributes": {"platform": p},
                        "relationships": {"app": {"data": {"type": "apps", "id": app_id}}},
                    }
                },
                label="(create submission)",
            )
            if not created:
                print("    could not create a submission for this platform - skipping")
                continue
            rs = created["data"]
        rs_id = rs["id"]
        print(f"    submission {rs_id}")

        try:
            items = asc.get(f"reviewSubmissions/{rs_id}/items", **{"limit": 50})["data"]
        except requests.HTTPError as e:
            print(f"    items unreadable: {e}")
            items = []
        have = linked_ids(items)
        print(f"    already attached: {len(items)} item(s)")

        if v["id"] in have:
            print("    version already attached")
        else:
            asc.post(
                "reviewSubmissionItems",
                {
                    "data": {
                        "type": "reviewSubmissionItems",
                        "relationships": {
                            "reviewSubmission": {
                                "data": {"type": "reviewSubmissions", "id": rs_id}
                            },
                            "appStoreVersion": {
                                "data": {"type": "appStoreVersions", "id": v["id"]}
                            },
                        },
                    }
                },
                label=f"(version {v['attributes']['versionString']} {p})",
            )

        # Subscriptions belong to the app rather than to a platform, so attach
        # them once. If Apple requires them per-platform it will say so, and the
        # status and body are printed either way rather than assumed.
        if not subs_done:
            ok = False
            for s in subs:
                if s["id"] in have:
                    print(f"    {s['attributes']['productId']} already attached")
                    ok = True
                    continue
                r = asc.post(
                    "reviewSubmissionItems",
                    {
                        "data": {
                            "type": "reviewSubmissionItems",
                            "relationships": {
                                "reviewSubmission": {
                                    "data": {"type": "reviewSubmissions", "id": rs_id}
                                },
                                "subscription": {
                                    "data": {"type": "subscriptions", "id": s["id"]}
                                },
                            },
                        }
                    },
                    label=f"({s['attributes']['productId']})",
                )
                ok = ok or bool(r)
            subs_done = ok

    # --- final state -------------------------------------------------------
    print("")
    print("=== FINAL STATE (nothing has been SUBMITTED) ===")
    for rs in asc.get(f"apps/{app_id}/reviewSubmissions", **{"limit": 50})["data"]:
        a = rs["attributes"]
        if a.get("state") in CLOSED:
            continue
        print(
            f"  submission {rs['id']} platform={a.get('platform')} "
            f"state={a.get('state')} submitted={a.get('submitted')}"
        )
        try:
            for it in asc.get(f"reviewSubmissions/{rs['id']}/items", **{"limit": 50})["data"]:
                print(f"    item {' '.join(item_links(it))}")
        except requests.HTTPError as e:
            print(f"    items unreadable: {e}")
    print("")
    print("Press Submit in App Store Connect when you are happy with the above.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
