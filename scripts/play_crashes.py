#!/usr/bin/env python3
"""Fetch REAL crash stack traces from the Play Developer Reporting API.

Why this exists: Play rejected 1.4.28 for "crashes after opening" and the repo
had no way to see a single stack trace. Every previous Android crash hunt here
guessed from the changelog; the one before this guessed SIGILL and was wrong,
because the SIGILL root cause (#353) is an ancestor of 1.4.27, 1.4.28 AND
1.4.29.

It asks for the crash, it does not infer it.

NOTE ON SCOPES: this API needs `playdeveloperreporting`, which is NOT the
`androidpublisher` scope the listing script uses. If the service account has
not been granted it, the failure is explicit and says what to do rather than
printing an empty list that reads like "no crashes".
"""
import json
import os
import sys
from datetime import datetime, timedelta, timezone

import requests
from google.oauth2 import service_account
from google.auth.transport.requests import Request

PACKAGE = os.environ.get("PLAY_PACKAGE", "app.birdo.vpn")
BASE = f"https://playdeveloperreporting.googleapis.com/v1beta1/apps/{PACKAGE}"
SCOPE = "https://www.googleapis.com/auth/playdeveloperreporting"
VERSION_CODES = [c.strip() for c in os.environ.get("VERSION_CODES", "10428").split(",") if c.strip()]
DAYS = int(os.environ.get("DAYS", "28"))


def token() -> str:
    raw = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", "").strip()
    if not raw:
        sys.exit("PLAY_SERVICE_ACCOUNT_JSON is not set")
    creds = service_account.Credentials.from_service_account_info(
        json.loads(raw), scopes=[SCOPE]
    )
    creds.refresh(Request())
    return creds.token


def post(sess, path, body):
    r = sess.post(f"{BASE}{path}", json=body, timeout=60)
    if r.status_code == 403:
        print(f"\n  !! 403 on {path}")
        print("     The service account lacks the playdeveloperreporting scope, or the")
        print("     Play Developer Reporting API is not enabled on its GCP project.")
        print("     Fix: Play Console -> Users and permissions -> the service account ->")
        print("     grant 'View app information and download bulk reports', AND enable")
        print("     'Google Play Developer Reporting API' in the SA's Cloud project.")
        print(f"     body: {r.text[:400]}")
        return None
    if r.status_code >= 400:
        print(f"  !! HTTP {r.status_code} on {path}: {r.text[:500]}")
        return None
    return r.json()


def main() -> int:
    end = datetime.now(timezone.utc)
    start = end - timedelta(days=DAYS)
    interval = {
        "startTime": {"year": start.year, "month": start.month, "day": start.day,
                      "hours": 0, "timeZone": {"id": "UTC"}},
        "endTime": {"year": end.year, "month": end.month, "day": end.day,
                    "hours": 0, "timeZone": {"id": "UTC"}},
    }

    sess = requests.Session()
    sess.headers["Authorization"] = f"Bearer {token()}"

    vc_filter = " OR ".join(f"versionCode = {c}" for c in VERSION_CODES)
    filt = f"({vc_filter})"
    print(f"app={PACKAGE}  versionCodes={','.join(VERSION_CODES)}  last {DAYS}d")

    issues = post(sess, "/errorIssues:search", {
        "interval": interval, "filter": filt, "pageSize": 25,
        "orderBy": "distinctUsers desc",
    })
    if issues is None:
        return 2

    rows = issues.get("errorIssues") or []
    if not rows:
        # An EMPTY result and a FAILED request must never look the same.
        print("\n  The query SUCCEEDED and returned no crash issues for these version codes.")
        print("  That is a real answer, not an error - but note Play's vitals only cover")
        print("  users who opted into sharing diagnostics.")
        return 0

    print(f"\n{len(rows)} crash issue(s), worst first:\n")
    for i, it in enumerate(rows, 1):
        m = it.get("metrics") or {}
        print(f"=== [{i}] {it.get('type','?')}  {it.get('cause','')}")
        print(f"    location : {it.get('location','?')}")
        print(f"    users    : {m.get('distinctUsers','?')}   events: {m.get('errorReportCount','?')}")
        print(f"    firstOs  : {it.get('firstOsVersion',{}).get('apiLevel','?')}"
              f"  lastOs: {it.get('lastOsVersion',{}).get('apiLevel','?')}")
        print(f"    issueId  : {it.get('name','?')}")
        name = it.get("name")
        if not name:
            continue
        reps = post(sess, "/errorReports:search", {
            "interval": interval, "filter": f'issueId = "{name.split("/")[-1]}"', "pageSize": 2,
        })
        for rep in (reps or {}).get("errorReports", [])[:1]:
            txt = rep.get("reportText") or ""
            print("    ---- stack trace ----")
            for line in txt.splitlines()[:45]:
                print("    " + line)
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
