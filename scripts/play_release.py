#!/usr/bin/env python3
"""Halt a bad production rollout, and stage a new release, via the Play API.

MODE=halt     - set the in-progress production release to `halted`. Users stop
                being served it immediately. Reversible: resume from the Console.
MODE=promote  - upload an AAB and put it on `production` as a DRAFT.
MODE=probe    - read-only; print the current tracks.

WHY A DRAFT AND NOT AUTO-SUBMIT. The release workflow's auto-submit was refused
by Google with "Changes cannot be sent for review automatically", which happens
while the app has Console changes pending (here: the Broken Functionality
rejection of 10428). android.yml deliberately refuses to retry with
`changesNotSentForReview: true`, and that refusal is right for an automated
release path - a committed-but-unsubmitted edit is a trap for the NEXT release.

This script is the manual, owner-invoked path, so it takes the opposite trade
DELIBERATELY and says so: it commits with changesNotSentForReview=true, which
leaves the release sitting in the Play Console as a draft for a human to press
Submit on. Nothing is sent for review by this script. That is the whole point -
Google is telling us a human has to submit, so we get the artifact all the way
to the Console and stop there.
"""
import io
import json
import os
import sys

from google.oauth2 import service_account
from google.auth.transport.requests import AuthorizedSession

PKG = os.environ.get("PLAY_PACKAGE", "app.birdo.vpn")
BASE = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PKG}"
UPLOAD = f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PKG}"
MODE = os.environ.get("MODE", "probe")
AAB = os.environ.get("AAB_PATH", "")
TRACK = os.environ.get("TRACK", "production")


def die(msg: str) -> None:
    print(f"ERROR: {msg}")
    sys.exit(1)


def session() -> AuthorizedSession:
    raw = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON", "").strip()
    if not raw:
        die("PLAY_SERVICE_ACCOUNT_JSON is not set")
    creds = service_account.Credentials.from_service_account_info(
        json.loads(raw), scopes=["https://www.googleapis.com/auth/androidpublisher"]
    )
    return AuthorizedSession(creds)


def show_tracks(s, edit_id=None):
    url = f"{BASE}/edits/{edit_id}/tracks" if edit_id else None
    if not url:
        return
    r = s.get(url, timeout=60)
    if r.status_code >= 400:
        print(f"  (could not read tracks: {r.status_code} {r.text[:200]})")
        return
    for t in r.json().get("tracks", []):
        for rel in t.get("releases", []):
            print(f"    {t['track']:<12} {rel.get('status'):<12} "
                  f"versionCodes={rel.get('versionCodes')} "
                  f"userFraction={rel.get('userFraction','-')}")


def main() -> int:
    s = session()
    r = s.post(f"{BASE}/edits", timeout=60)
    if r.status_code >= 400:
        die(f"could not open an edit: {r.status_code} {r.text[:300]}")
    edit = r.json()["id"]
    print(f"edit {edit} opened  (mode={MODE}, track={TRACK})")
    print("  BEFORE:")
    show_tracks(s, edit)

    try:
        if MODE == "probe":
            s.delete(f"{BASE}/edits/{edit}", timeout=60)
            print("  probe only - edit discarded, nothing changed")
            return 0

        if MODE == "halt":
            r = s.get(f"{BASE}/edits/{edit}/tracks/{TRACK}", timeout=60)
            if r.status_code >= 400:
                die(f"read {TRACK}: {r.status_code} {r.text[:300]}")
            track = r.json()
            changed = 0
            for rel in track.get("releases", []):
                if rel.get("status") == "inProgress":
                    print(f"  halting {rel.get('versionCodes')} "
                          f"(was inProgress at userFraction={rel.get('userFraction','-')})")
                    rel["status"] = "halted"
                    rel.pop("userFraction", None)
                    changed += 1
            if not changed:
                s.delete(f"{BASE}/edits/{edit}", timeout=60)
                print("  no inProgress release on this track - nothing to halt, edit discarded")
                return 0
            r = s.put(f"{BASE}/edits/{edit}/tracks/{TRACK}", json=track, timeout=60)
            if r.status_code >= 400:
                die(f"halt failed: {r.status_code} {r.text[:400]}")

        elif MODE == "promote":
            if not AAB or not os.path.isfile(AAB):
                die(f"AAB_PATH '{AAB}' is not a file")
            size = os.path.getsize(AAB)
            print(f"  uploading {os.path.basename(AAB)} ({size/1048576:.1f} MB)")
            with io.open(AAB, "rb") as fh:
                r = s.post(f"{UPLOAD}/edits/{edit}/bundles?uploadType=media",
                           data=fh,
                           headers={"Content-Type": "application/octet-stream"},
                           timeout=1800)
            if r.status_code >= 400:
                die(f"bundle upload failed: {r.status_code} {r.text[:400]}")
            vc = r.json().get("versionCode")
            print(f"  uploaded versionCode {vc}")

            notes = ""
            for p in ("distribution/whatsnew/whatsnew-en-US",):
                if os.path.isfile(p):
                    notes = io.open(p, encoding="utf-8").read().strip()[:490]
            body = {"releases": [{
                "versionCodes": [str(vc)],
                "status": "draft",
                "releaseNotes": ([{"language": "en-US", "text": notes}] if notes else []),
            }]}
            r = s.put(f"{BASE}/edits/{edit}/tracks/{TRACK}", json=body, timeout=120)
            if r.status_code >= 400:
                die(f"track update failed: {r.status_code} {r.text[:400]}")
            print(f"  {TRACK} set to DRAFT with versionCode {vc}")
        else:
            die(f"unknown MODE '{MODE}'")

        # changesNotSentForReview: Google refuses the automatic submit while the
        # app has pending Console changes. Committing without it 400s; committing
        # WITH it lands the change and leaves review to a human, which is exactly
        # what Google's own error message instructs.
        r = s.post(f"{BASE}/edits/{edit}:commit?changesNotSentForReview=true", timeout=300)
        if r.status_code >= 400:
            die(f"commit failed: {r.status_code} {r.text[:400]}")
        print("  committed (changesNotSentForReview=true - NOT sent for review)")
    except SystemExit:
        s.delete(f"{BASE}/edits/{edit}", timeout=60)
        raise

    e2 = s.post(f"{BASE}/edits", timeout=60).json().get("id")
    print("  AFTER:")
    show_tracks(s, e2)
    s.delete(f"{BASE}/edits/{e2}", timeout=60)
    print("\n  A human must now press Submit in the Play Console. Nothing here does that.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
