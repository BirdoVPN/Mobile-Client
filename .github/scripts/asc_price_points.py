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

PAGINATION IS NOT AN OPTIONAL DETAIL HERE, and the first version of this script
got it wrong in a way that produced a confident, plausible, wrong answer.
Apple's price grid runs to several hundred GB tiers and the API returns them 200
at a time, cheapest first. Reading a single page and calling max() on it does
not yield "the nearest tier below GBP 38" — it yields the top of page one, which
was GBP 24.90 for BOTH yearly products. Two different targets resolving to the
same suspiciously round figure, with "no nearby tiers", was the tell.

So this follows links.next to exhaustion, prints how many tiers it actually saw,
and — the part that matters — REFUSES to recommend anything when the highest
tier it fetched is still below the target. A truncated grid can only ever
produce a wrong "nearest below", so the script says the grid looks truncated
instead of answering. A check that cannot detect its own truncation is worse
than no check, because its output reads exactly like a real answer.

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

# Apple caps a page at 200. Following links.next is the only way to see the
# whole grid; this bound exists so a pathological response cannot loop forever.
MAX_PAGES = 40


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

    def get_all(path, **params):
        """Every page, following links.next. Returns (data, pages_read)."""
        out = []
        r = s.get(f"{API}/{path}", params=params, timeout=60)
        r.raise_for_status()
        body = r.json()
        pages = 1
        out.extend(body.get("data", []))
        while pages < MAX_PAGES:
            nxt = (body.get("links") or {}).get("next")
            if not nxt:
                break
            r = s.get(nxt, timeout=60)
            r.raise_for_status()
            body = r.json()
            pages += 1
            out.extend(body.get("data", []))
        return out, pages

    apps = get("apps", **{"limit": 20, "fields[apps]": "name,bundleId"})["data"]
    findings = 0
    truncated = 0

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
                    pts, pages = get_all(
                        f"subscriptions/{sub['id']}/pricePoints",
                        **{
                            "filter[territory]": TERRITORY,
                            "limit": 200,
                            "fields[subscriptionPricePoints]": "customerPrice,proceeds",
                        },
                    )
                except requests.HTTPError as e:
                    print(f"    pricePoints unreadable: {e}")
                    continue

                prices = sorted(
                    {float(p["attributes"]["customerPrice"]) for p in pts if p.get("attributes")}
                )
                if not prices:
                    print("    Apple returned NO GB price points — cannot advise")
                    continue

                print(
                    f"    grid: {len(prices)} distinct GB tiers over {pages} page(s), "
                    f"GBP {prices[0]:.2f} to {prices[-1]:.2f}"
                )

                exact = [p for p in prices if abs(p - want) < 0.005]
                below = [p for p in prices if p <= want + 0.005]
                above = [p for p in prices if p > want + 0.005]

                if exact:
                    print(f"    EXACT TIER EXISTS: GBP {exact[0]:.2f} — select it. No change needed.")
                elif not above:
                    # Nothing above the target means the fetch stopped before
                    # reaching it. "Nearest below" computed from a truncated
                    # grid is meaningless, so refuse rather than advise.
                    truncated += 1
                    print(f"    GRID LOOKS TRUNCATED — highest tier seen is GBP {prices[-1]:.2f},")
                    print(f"    which is below the advertised GBP {want:.2f}. Apple's grid goes")
                    print("    higher, so pagination stopped early. NOT ADVISING a tier from")
                    print(f"    this data. Pages read: {pages} (cap {MAX_PAGES}).")
                else:
                    findings += 1
                    print(f"    NO EXACT TIER. Apple has no GBP {want:.2f} price point.")
                    print(f"    -> SELECT GBP {below[-1]:.2f}  (nearest at or BELOW the advertised price)")
                    print(f"    FORBIDDEN: GBP {above[0]:.2f} is the nearest tier above — selecting it")
                    print("       would charge more than the advertised price (UK CPRs 2008).")

                near = [p for p in prices if want - 6 <= p <= want + 6]
                shown = ", ".join(f"{p:.2f}" for p in near[:14])
                more = f" (+{len(near) - 14} more)" if len(near) > 14 else ""
                print(f"    GB tiers within GBP 6 of the target: {shown or '(none)'}{more}")

    print("")
    print(f"subscriptions whose advertised price has NO exact Apple tier: {findings}")
    if truncated:
        print(f"subscriptions whose grid came back TRUNCATED (no advice given): {truncated}")
        print("A truncated grid is a script bug, not an Apple answer. Fix pagination")
        print("and re-run before acting on anything above.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
