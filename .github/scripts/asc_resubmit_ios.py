#!/usr/bin/env python3
"""Prepare the iOS review resubmission after a rejection, via the API.

Does, in order (MODE=plan prints what it WOULD do; MODE=apply does it):

  1. Find the iOS appStoreVersion in REJECTED / PREPARE_FOR_SUBMISSION /
     DEVELOPER_REJECTED state and PATCH its versionString to TARGET_VERSION.
     A rejected version is re-used, never re-created — creating a second
     version record for the same platform is what 409s.
  2. Find the processed build whose build number is TARGET_BUILD and attach it
     to that version (PATCH appStoreVersions/{id}/relationships/build).
  3. Find the open iOS reviewSubmission (UNRESOLVED_ISSUES) and PATCH its
     REJECTED version item `resolved: true`. The item ids are base64url of
     `submissionId|type|entityId` (type 6 = version), so we decode rather than
     guess which item is which.
  4. Report the four subscriptions' states. If any is READY_TO_SUBMIT (a
     rejection releases them), POST a subscriptionSubmission for it; a 409
     "no pending version" means it is already in flight and is NOT an error.

DELIBERATELY STOPS SHORT OF SUBMITTING. Pressing Submit is the irreversible,
outward-facing step and stays a human decision, exactly like asc_attach.py.
The reply to the Resolution Center thread has no API at all and must already
have been pasted by hand.

Env: ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_B64 (same as the sibling scripts),
     MODE=plan|apply, TARGET_VERSION (e.g. 1.4.26), TARGET_BUILD (e.g. 10426).
"""
import base64
import os
import sys
import time

import jwt
import requests

API = "https://api.appstoreconnect.apple.com/v1"
SUB_PRODUCTS = (
    "app.birdo.vpn.operative.monthly",
    "app.birdo.vpn.operative.yearly",
    "app.birdo.vpn.sovereign.monthly",
    "app.birdo.vpn.sovereign.yearly",
)
REUSABLE_STATES = {"REJECTED", "DEVELOPER_REJECTED", "PREPARE_FOR_SUBMISSION", "METADATA_REJECTED"}


def token() -> str:
    now = int(time.time())
    return jwt.encode(
        {"iss": os.environ["ASC_ISSUER_ID"], "iat": now, "exp": now + 19 * 60, "aud": "appstoreconnect-v1"},
        base64.b64decode(os.environ["ASC_KEY_B64"]).decode(),
        algorithm="ES256",
        headers={"kid": os.environ["ASC_KEY_ID"], "typ": "JWT"},
    )


class Asc:
    def __init__(self) -> None:
        self.s = requests.Session()
        self.s.headers["Authorization"] = f"Bearer {token()}"
        self.s.headers["Content-Type"] = "application/json"

    def get(self, path: str, **params):
        r = self.s.get(f"{API}/{path}", params=params, timeout=60)
        if r.status_code >= 400:
            raise requests.HTTPError(f"GET {path} -> {r.status_code}: {r.text[:400]}")
        return r.json()

    def patch(self, path: str, body: dict, label: str, apply: bool):
        if not apply:
            print(f"    PLAN  PATCH {path} {label}")
            return None
        r = self.s.patch(f"{API}/{path}", json=body, timeout=60)
        print(f"    PATCH {path} {label} -> {r.status_code}")
        if r.status_code >= 400:
            print(f"      {r.text[:400]}")
            return None
        return r.json() if r.text.strip() else {}

    def post(self, path: str, body: dict, label: str, apply: bool):
        if not apply:
            print(f"    PLAN  POST {path} {label}")
            return None
        r = self.s.post(f"{API}/{path}", json=body, timeout=60)
        print(f"    POST {path} {label} -> {r.status_code}")
        if r.status_code >= 400:
            print(f"      {r.text[:400]}")
            return None
        return r.json()


def decode_item_id(item_id: str):
    """reviewSubmissionItem ids are base64url of 'submissionId|type|entityId'."""
    pad = "=" * (-len(item_id) % 4)
    try:
        raw = base64.urlsafe_b64decode(item_id + pad).decode()
        parts = raw.split("|")
        return parts if len(parts) == 3 else None
    except Exception:
        return None


def main() -> int:
    mode = os.environ.get("MODE", "plan")
    apply = mode == "apply"
    target_version = os.environ["TARGET_VERSION"]
    target_build = os.environ["TARGET_BUILD"]
    asc = Asc()

    app = asc.get("apps", **{"limit": 20, "fields[apps]": "name,bundleId"})["data"][0]
    app_id = app["id"]
    print(f"app {app['attributes']['bundleId']} id={app_id}  mode={mode}  target={target_version} ({target_build})")

    # 1. the iOS version record ------------------------------------------------
    versions = asc.get(
        f"apps/{app_id}/appStoreVersions",
        **{"limit": 30, "fields[appStoreVersions]": "versionString,appStoreState,platform"},
    )["data"]
    ios = [v for v in versions if v["attributes"]["platform"] == "IOS"]
    for v in ios:
        a = v["attributes"]
        print(f"  iOS version {a['versionString']:<8} {a['appStoreState']:<24} id={v['id']}")
    reusable = [v for v in ios if v["attributes"]["appStoreState"] in REUSABLE_STATES]
    if not reusable:
        print("  no reusable iOS version record (REJECTED / PREPARE_FOR_SUBMISSION); refusing to create one")
        return 1
    ver = reusable[0]
    ver_id = ver["id"]
    if ver["attributes"]["versionString"] != target_version:
        asc.patch(
            f"appStoreVersions/{ver_id}",
            {"data": {"type": "appStoreVersions", "id": ver_id, "attributes": {"versionString": target_version}}},
            f"(versionString {ver['attributes']['versionString']} -> {target_version})",
            apply,
        )
    else:
        print(f"  versionString already {target_version}")

    # 2. the build ---------------------------------------------------------------
    builds = asc.get(
        "builds",
        **{
            "filter[app]": app_id,
            "filter[version]": target_build,
            "sort": "-uploadedDate",
            "limit": 10,
            "fields[builds]": "version,processingState,uploadedDate,expired",
            "include": "preReleaseVersion",
        },
    )
    cands = [b for b in builds["data"] if not b["attributes"].get("expired")]
    for b in cands:
        a = b["attributes"]
        print(f"  build {a['version']} {a['processingState']} uploaded={a['uploadedDate']} id={b['id']}")
    valid = [b for b in cands if b["attributes"]["processingState"] == "VALID"]
    if valid:
        asc.patch(
            f"appStoreVersions/{ver_id}/relationships/build",
            {"data": {"type": "builds", "id": valid[0]["id"]}},
            f"(attach build {target_build})",
            apply,
        )
    else:
        # Keep going so the submission and subscription state is still reported,
        # but nothing can be attached yet.
        print(f"  build {target_build} is not yet VALID (uploaded and processed) — run again later")

    # 3. the open iOS submission and its rejected version item -----------------
    subs_all = asc.get(f"apps/{app_id}/reviewSubmissions", **{"limit": 50})["data"]
    open_ios = [
        rs for rs in subs_all
        if rs["attributes"].get("platform") == "IOS"
        and rs["attributes"].get("state") not in {"COMPLETE", "CANCELING", "CANCELED"}
    ]
    for rs in open_ios:
        a = rs["attributes"]
        print(f"  iOS submission {rs['id']} state={a.get('state')} submitted={a.get('submitted')}")
    if not open_ios:
        print("  no open iOS review submission — nothing to resolve (asc_attach.py creates one)")
    else:
        rs_id = open_ios[0]["id"]
        items = asc.get(f"reviewSubmissions/{rs_id}/items", **{"limit": 50})["data"]
        for it in items:
            a = it["attributes"]
            dec = decode_item_id(it["id"])
            kind = {"6": "version", "18": "subscription", "19": "group"}.get(dec[1], dec[1]) if dec else "?"
            print(f"    item {it['id'][:16]}… type={kind} state={a.get('state')} resolved={a.get('resolved')}")
            if kind == "version" and a.get("state") == "REJECTED" and not a.get("resolved"):
                asc.patch(
                    f"reviewSubmissionItems/{it['id']}",
                    {"data": {"type": "reviewSubmissionItems", "id": it["id"], "attributes": {"resolved": True}}},
                    "(mark rejected version item resolved)",
                    apply,
                )

    # 4. subscriptions -----------------------------------------------------------
    print("  subscriptions:")
    for g in asc.get(f"apps/{app_id}/subscriptionGroups", **{"limit": 20})["data"]:
        for s in asc.get(
            f"subscriptionGroups/{g['id']}/subscriptions",
            **{"limit": 50, "fields[subscriptions]": "productId,state"},
        )["data"]:
            a = s["attributes"]
            print(f"    {a['productId']:<40} {a['state']}")
            if a["productId"] in SUB_PRODUCTS and a["state"] == "READY_TO_SUBMIT":
                asc.post(
                    "subscriptionSubmissions",
                    {"data": {"type": "subscriptionSubmissions", "relationships": {"subscription": {"data": {"type": "subscriptions", "id": s["id"]}}}}},
                    f"(submit {a['productId']}; a 409 'no pending version' means already in flight)",
                    apply,
                )

    print("")
    print("STOPPED SHORT OF SUBMITTING. Press Submit in App Store Connect once the Resolution Center reply is posted." if apply else "plan-only run — nothing was changed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
