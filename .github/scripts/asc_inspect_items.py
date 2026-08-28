#!/usr/bin/env python3
"""Print what each open review submission ACTUALLY contains, with the linked
resources resolved.

Read-only. Makes no changes.

WHY
---
An earlier run reported "6 items" on the iOS submission and "1 item" on macOS,
but printed no relationship names, so the six could not be identified. Whether
the four subscriptions are already attached to iOS is the entire question, and
it was being answered by inference.

It also separates two different 409s that were previously lumped together:

  STATE_ERROR.ENTITY_STATE_INVALID
      "reviewSubmission state does not allow adding more items"
      -> the submission really is frozen for additions

  ENTITY_ERROR.RELATIONSHIP.UNKNOWN
      -> the relationship name in the request body was not recognised, i.e. a
         malformed request, NOT a statement about state

The second is very likely my own payload being wrong rather than Apple refusing,
so this prints the relationship keys Apple actually returns for an existing
item — which is the authoritative list of what may be sent.
"""

import base64
import os
import sys
import time

import jwt
import requests

API = "https://api.appstoreconnect.apple.com/v1"
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


def main() -> int:
    s = requests.Session()
    s.headers["Authorization"] = f"Bearer {token()}"

    def get(path, **params):
        r = s.get(f"{API}/{path}", params=params, timeout=60)
        if r.status_code >= 400:
            print(f"    GET {path} -> {r.status_code}: {r.text[:300]}")
            return None
        return r.json()

    apps = get("apps", **{"limit": 20, "fields[apps]": "name,bundleId"})["data"]
    app_id = apps[0]["id"]
    print(f"app {apps[0]['attributes']['bundleId']} id={app_id}")

    # product id -> readable name, so item links can be named rather than guessed
    names = {}
    for g in get(f"apps/{app_id}/subscriptionGroups", **{"limit": 20})["data"]:
        for sub in get(
            f"subscriptionGroups/{g['id']}/subscriptions",
            **{"limit": 50, "fields[subscriptions]": "productId,state"},
        )["data"]:
            names[sub["id"]] = f"subscription {sub['attributes']['productId']}"
    for v in get(
        f"apps/{app_id}/appStoreVersions",
        **{"limit": 30, "fields[appStoreVersions]": "versionString,platform,appStoreState"},
    )["data"]:
        a = v["attributes"]
        names[v["id"]] = f"version {a['versionString']} {a['platform']} ({a['appStoreState']})"

    subs = get(f"apps/{app_id}/reviewSubmissions", **{"limit": 50})
    for rs in (subs or {}).get("data", []):
        a = rs["attributes"]
        if a.get("state") in CLOSED:
            continue
        print("")
        print(f"submission {rs['id']}  platform={a.get('platform')}  state={a.get('state')}")
        # Ask for the linked resources explicitly. Without include=, the
        # relationship data comes back empty, which is what made the previous
        # listing useless.
        items = get(
            f"reviewSubmissions/{rs['id']}/items",
            **{"limit": 50, "include": "appStoreVersion,subscription"},
        )
        if not items:
            continue
        for it in items.get("data", []):
            rels = it.get("relationships") or {}
            print(f"  item {it['id']}")
            print(f"    attributes: {it.get('attributes')}")
            for key, rel in rels.items():
                d = rel.get("data")
                if d:
                    rid = d.get("id")
                    print(f"    {key}: {rid}  -> {names.get(rid, '(unknown resource)')}")
            present = [k for k, v in rels.items() if v.get("data")]
            if not present:
                print(f"    relationship KEYS Apple returns: {sorted(rels.keys())}")
        for inc in items.get("included", []):
            print(f"  included: {inc['type']} {inc['id']} {inc.get('attributes', {})}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
