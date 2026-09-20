#!/usr/bin/env python3
"""Report what App Store Connect ACTUALLY holds for subscriptions.

The review notes assert that four auto-renewing subscriptions are submitted with
the version. Nothing in the version metadata proves that, and it has been taken
on trust twice. Attaching subscriptions to a submission is not possible through
the API (it 409s), but READING is — so the claim can be checked rather than
assumed.

Read-only. Makes no changes of any kind.
"""

import base64
import os
import sys
import time

import jwt
import requests

API = "https://api.appstoreconnect.apple.com/v1"


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


def main() -> int:
    s = requests.Session()
    s.headers["Authorization"] = f"Bearer {token()}"

    def get(path, **params):
        r = s.get(f"{API}/{path}", params=params, timeout=60)
        r.raise_for_status()
        return r.json()

    apps = get("apps", **{"limit": 20, "fields[apps]": "name,bundleId"})["data"]
    for app in apps:
        app_id = app["id"]
        print("")
        print(f"=== {app['attributes']['name']} ({app['attributes']['bundleId']}) id={app_id} ===")

        print("  -- subscription groups --")
        try:
            groups = get(f"apps/{app_id}/subscriptionGroups", **{"limit": 20})["data"]
        except requests.HTTPError as e:
            print(f"    unreadable: {e}")
            groups = []
        for g in groups:
            print(f"    group '{g['attributes'].get('referenceName')}' id={g['id']}")
            try:
                subs = get(
                    f"subscriptionGroups/{g['id']}/subscriptions",
                    **{"limit": 50, "fields[subscriptions]": "productId,name,state"},
                )["data"]
            except requests.HTTPError as e:
                print(f"      unreadable: {e}")
                continue
            for sub in subs:
                a = sub["attributes"]
                print(f"      {str(a.get('productId')):<40} state={a.get('state')}")

        print("  -- review submissions (what is ACTUALLY attached) --")
        # `submittedDate`, not `submitted`. Apple 400s the WHOLE request on an
        # unknown member of fields[reviewSubmissions], so the typo did not
        # degrade the field — it made the entire query unreadable, which is the
        # one query OPEN-WORK A4 tells the owner to verify the macOS attach
        # with ("the macOS submission must list MORE THAN ONE item; do not
        # trust the UI").
        failed = None
        try:
            rss = get(
                f"apps/{app_id}/reviewSubmissions",
                **{"limit": 10, "fields[reviewSubmissions]": "state,platform,submittedDate"},
            )["data"]
        except requests.HTTPError as e:
            failed = e
            rss = []
        # "(none)" after a failed request is a lie in the reassuring direction:
        # it reads as "no submissions exist" when it means "I could not ask".
        # That is how the typo survived — the output looked like an answer.
        if failed is not None:
            print(f"    !! COULD NOT READ the submissions: {failed}")
            print("       This says NOTHING about what is attached. Do not read it as 'none'.")
        elif not rss:
            print("    (none) — the query succeeded and returned no submissions")
        for rs in rss:
            a = rs["attributes"]
            print(
                f"    submission {rs['id']} platform={a.get('platform')} "
                f"state={a.get('state')} submitted={a.get('submittedDate')}"
            )
            try:
                items = get(f"reviewSubmissions/{rs['id']}/items", **{"limit": 50})["data"]
            except requests.HTTPError as e:
                print(f"      items unreadable: {e}")
                continue
            if not items:
                print("      NO ITEMS ATTACHED")
            for it in items:
                linked = [
                    f"{k}={v['data'].get('id')}"
                    for k, v in (it.get("relationships") or {}).items()
                    if v.get("data")
                ]
                print(f"      item {it['id']}: {' '.join(linked) or '(nothing linked)'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
