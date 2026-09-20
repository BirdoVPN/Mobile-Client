#!/usr/bin/env python3
"""What GB price points Apple ACTUALLY offers for each subscription.

OPEN-WORK A7 says the yearly prices "probably do not exist on Apple's price
grid" and states the rule: round DOWN, never up. Nobody had checked, because the
grid is only visible in App Store Connect — so the item sat open on a guess.

It is readable over the API, so it can be checked rather than assumed.

The direction of the rounding is the whole point. birdo-web/lib/plans.ts is the
single source for advertised prices (GBP 3.99 / 9.99 monthly, 38 / 99 yearly)
and its own header says charging above the advertised price is a UK CPRs 2008
problem. So for each subscription this prints the nearest GB tier AT OR BELOW
the advertised figure, and flags the cheapest tier above it as forbidden rather
than as an option.

Read-only. Makes no changes of any kind.
"""

import base64
import os
import sys
import time

import jwt
import requests

API = "https://api.appstoreconnect.apple.com/v1"

# From birdo-web/lib/plans.ts — the single source for advertised prices.
ADVERTISED = {
    "app.birdo.vpn.operative.monthly": 3.99,
    "app.birdo.vpn.operative.yearly": 38.0,
    "app.birdo.vpn.sovereign.monthly": 9.99,
    "app.birdo.vpn.sovereign.yearly": 99.0,
}
TERRITORY = "GBR"


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
    findings = 0

    for app in apps:
        app_id = app["id"]
        try:
            groups = get(f"apps/{app_id}/subscriptionGroups", **{"limit": 20})["data"]
        except requests.HTTPError as e:
            print(f"  subscriptionGroups unreadable for {app_id}: {e}")
            continue

        for g in groups:
            try:
                subs = get(
                    f"subscriptionGroups/{g['id']}/subscriptions",
                    **{"limit": 50, "fields[subscriptions]": "productId,name,state"},
                )["data"]
            except requests.HTTPError as e:
                print(f"  subscriptions unreadable: {e}")
                continue

            for sub in subs:
                pid = sub["attributes"].get("productId")
                if pid not in ADVERTISED:
                    continue
                want = ADVERTISED[pid]
                print("")
                print(f"=== {pid}   advertised GBP {want:.2f}")

                try:
                    pts = get(
                        f"subscriptions/{sub['id']}/pricePoints",
                        **{
                            "filter[territory]": TERRITORY,
                            "limit": 200,
                            "fields[subscriptionPricePoints]": "customerPrice,proceeds",
                        },
                    )["data"]
                except requests.HTTPError as e:
                    print(f"    pricePoints unreadable: {e}")
                    continue

                prices = sorted(
                    {float(p["attributes"]["customerPrice"]) for p in pts if p.get("attributes")}
                )
                if not prices:
                    print("    Apple returned NO GB price points — cannot advise")
                    continue

                exact = [p for p in prices if abs(p - want) < 0.005]
                below = [p for p in prices if p <= want + 0.005]
                above = [p for p in prices if p > want + 0.005]

                if exact:
                    print(f"    EXACT TIER EXISTS: GBP {exact[0]:.2f} — select it. No change needed.")
                else:
                    findings += 1
                    print(f"    NO EXACT TIER. Apple has no GBP {want:.2f} price point.")
                    if below:
                        print(f"    -> SELECT GBP {below[-1]:.2f}  (nearest at or BELOW the advertised price)")
                    else:
                        print("    -> NOTHING AT OR BELOW the advertised price.")
                        print("       Do NOT round up. Change the advertised price in")
                        print("       birdo-web/lib/plans.ts instead, then re-run this.")
                    if above:
                        print(f"    FORBIDDEN: GBP {above[0]:.2f} is the nearest tier above — selecting it")
                        print("       would charge more than the advertised price (UK CPRs 2008).")

                near = [p for p in prices if want - 6 <= p <= want + 6]
                print(f"    nearby GB tiers: {', '.join(f'{p:.2f}' for p in near[:14]) or '(none within 6)'}")

    print("")
    print(f"subscriptions whose advertised price has NO exact Apple tier: {findings}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
