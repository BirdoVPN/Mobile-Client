#!/usr/bin/env python3
"""Publish the Google Play store listing for app.birdo.vpn from repo assets.

Everything the Play Developer API exposes for a store listing is driven from
files committed in this repo, so the listing is reviewable + reproducible:

  store-assets/listing/en-US/title.txt              -> listing title
  store-assets/listing/en-US/short-description.txt  -> short description (<=80)
  store-assets/listing/en-US/full-description.txt   -> full description (<=4000)
  store-assets/app-icon-512.png                     -> icon (512x512)
  store-assets/feature-graphic-1024x500.png         -> feature graphic
  store-assets/screenshot-0*.png                    -> phone screenshots (2..8)

Modes (exactly one required):
  --probe    Read-only sanity check: verifies the service account can access
             the app on the (organisation) Play account, prints current track
             releases + whether an en-US listing exists. Creates a throwaway
             edit and deletes it; commits nothing.
  --dry-run  Stages listing text + images + contact details into a new edit,
             runs server-side validation, then DELETES the edit. Nothing is
             committed. Use to preview API acceptance.
  --apply    Stages, validates and COMMITS the edit. The listing goes live in
             the Play Console (the app itself still ships via the AAB flow).
             Commit uses changesNotSentForReview=true — same as the AAB upload
             job — because Play refuses auto-review submission for this app;
             the final "submit for review" click stays in the Console.

Auth: service-account JSON in env PLAY_SERVICE_ACCOUNT_JSON (never printed).
The questionnaires (Data safety, Content rating) and App access credentials
have NO public API and must be completed in the Play Console UI.
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import sys
import time
from pathlib import Path

try:
    from google.oauth2 import service_account
    from google.auth.transport.requests import AuthorizedSession
except ImportError:  # pragma: no cover
    sys.exit("missing deps: python3 -m pip install google-auth requests")

PACKAGE = "app.birdo.vpn"
BASE = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}"
UPLOAD_BASE = (
    f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PACKAGE}"
)
LANG = "en-US"
REPO_ROOT = Path(__file__).resolve().parent.parent
ASSETS = REPO_ROOT / "store-assets"
LISTING_DIR = ASSETS / "listing" / LANG

CONTACT = {
    "contactEmail": "support@birdo.app",
    "contactWebsite": "https://birdo.app",
    "defaultLanguage": LANG,
}

# Play limits (validated locally before any network call).
LIMITS = {"title": 30, "short": 80, "full": 4000}


def die(msg: str) -> "NoReturn":  # noqa: F821 - py3.9 runner compat
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


def read_listing_files() -> dict:
    def read(name: str) -> str:
        p = LISTING_DIR / name
        if not p.exists():
            die(f"missing listing file: {p}")
        return p.read_text(encoding="utf-8").strip()

    title = read("title.txt")
    short = read("short-description.txt")
    full = read("full-description.txt")
    for key, text in (("title", title), ("short", short), ("full", full)):
        if len(text) > LIMITS[key]:
            die(f"{key} is {len(text)} chars (limit {LIMITS[key]})")
        if not text:
            die(f"{key} is empty")
    return {"language": LANG, "title": title, "shortDescription": short, "fullDescription": full}


def collect_images() -> dict:
    icon = ASSETS / "app-icon-512.png"
    feature = ASSETS / "feature-graphic-1024x500.png"
    shots = sorted(glob.glob(str(ASSETS / "screenshot-0*.png")))
    if not icon.exists():
        die(f"missing {icon}")
    if not feature.exists():
        die(f"missing {feature}")
    if not 2 <= len(shots) <= 8:
        die(f"need 2..8 phone screenshots, found {len(shots)}")
    for shot in shots:
        if os.path.getsize(shot) > 8 * 1024 * 1024:
            die(f"screenshot over 8 MiB Play limit: {shot}")
    return {"icon": [str(icon)], "featureGraphic": [str(feature)], "phoneScreenshots": shots}


def session() -> AuthorizedSession:
    raw = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON")
    if not raw:
        die("PLAY_SERVICE_ACCOUNT_JSON env var is not set")
    try:
        info = json.loads(raw)
    except json.JSONDecodeError:
        die("PLAY_SERVICE_ACCOUNT_JSON is not valid JSON")
    creds = service_account.Credentials.from_service_account_info(
        info, scopes=["https://www.googleapis.com/auth/androidpublisher"]
    )
    return AuthorizedSession(creds)


def request_with_retry(fn, what: str, attempts: int = 3):
    """Call fn() -> Response, retrying transient Google 5xx/429 errors.

    The androidpublisher API intermittently returns 500 'Internal error
    encountered.' on image uploads; a blind fail there aborts the whole
    publish over a blip.
    """
    delays = [0, 2, 5]
    last = None
    for i in range(attempts):
        if delays[i]:
            time.sleep(delays[i])
        last = fn()
        if last.status_code < 500 and last.status_code != 429:
            return last
        print(f"transient HTTP {last.status_code} on {what} (attempt {i + 1}/{attempts})")
    return last


def check(resp, what: str):
    if resp.status_code >= 400:
        # Surface the STRUCTURED error messages rather than the raw body:
        # GitHub Actions masks any log line containing a secret-matching
        # substring, and raw Google error bodies have been masked wholesale in
        # practice — leaving "HTTP 400: ***" which is undebuggable. The
        # message/reason fields are plain English and survive masking.
        details = []
        try:
            err = resp.json().get("error", {})
            if err.get("message"):
                details.append(f"message: {err['message']}")
            for e in err.get("errors", []):
                reason = e.get("reason", "?")
                msg = e.get("message", "")
                details.append(f"- [{reason}] {msg}")
        except Exception:  # noqa: BLE001 - non-JSON body
            details.append(resp.text[:500])
        detail_text = "\n".join(details) or "(no error detail)"
        die(f"{what} failed: HTTP {resp.status_code}\n{detail_text}")
    return resp.json() if resp.content else {}


def open_edit(s: AuthorizedSession) -> str:
    edit = check(s.post(f"{BASE}/edits", json={}), "create edit")
    return edit["id"]


def delete_edit(s: AuthorizedSession, edit_id: str) -> None:
    # Best-effort cleanup; an abandoned edit also just expires server-side.
    s.delete(f"{BASE}/edits/{edit_id}")


def probe(s: AuthorizedSession) -> None:
    edit_id = open_edit(s)
    try:
        tracks = check(s.get(f"{BASE}/edits/{edit_id}/tracks"), "list tracks")
        print(f"service account OK for {PACKAGE}; tracks:")
        for t in tracks.get("tracks", []):
            for rel in t.get("releases", []):
                print(
                    f"  {t['track']:<12} {rel.get('status', '?'):<10} "
                    f"versionCodes={rel.get('versionCodes', [])}"
                )
        listing = s.get(f"{BASE}/edits/{edit_id}/listings/{LANG}")
        if listing.status_code == 404:
            print(f"{LANG} listing: NOT SET yet (apply will create it)")
        else:
            data = check_response_passthrough(listing)
            print(f"{LANG} listing: present (title={data.get('title')!r})")
    finally:
        delete_edit(s, edit_id)


def check_response_passthrough(resp):
    if resp.status_code >= 400:
        die(f"read listing failed: HTTP {resp.status_code}: {resp.text[:500]}")
    return resp.json()


def stage(s: AuthorizedSession, edit_id: str, listing: dict, images: dict) -> None:
    check(
        s.put(f"{BASE}/edits/{edit_id}/listings/{LANG}", json=listing),
        "update listing text",
    )
    print(f"listing text staged ({LANG})")
    check(
        s.patch(f"{BASE}/edits/{edit_id}/details", json=CONTACT),
        "update contact details",
    )
    print("contact details staged")
    for image_type, paths in images.items():
        check(
            s.delete(f"{BASE}/edits/{edit_id}/listings/{LANG}/{image_type}"),
            f"clear {image_type}",
        )
        for path in paths:
            with open(path, "rb") as fh:
                data = fh.read()
            check(
                request_with_retry(
                    lambda: s.post(
                        f"{UPLOAD_BASE}/edits/{edit_id}/listings/{LANG}/{image_type}"
                        "?uploadType=media",
                        data=data,
                        headers={"Content-Type": "image/png"},
                    ),
                    f"upload {Path(path).name}",
                ),
                f"upload {image_type} {Path(path).name}",
            )
            print(f"uploaded {image_type}: {Path(path).name}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--probe", action="store_true")
    group.add_argument("--dry-run", action="store_true")
    group.add_argument("--apply", action="store_true")
    args = parser.parse_args()

    s = session()
    if args.probe:
        probe(s)
        return

    listing = read_listing_files()
    images = collect_images()
    edit_id = open_edit(s)
    committed = False
    try:
        stage(s, edit_id, listing, images)
        # NOTE: the separate :validate endpoint is UNUSABLE for this app — it
        # always fails with "Changes cannot be sent for review automatically"
        # and (unlike :commit) does not accept the changesNotSentForReview
        # parameter that suppresses exactly that condition. Every staging call
        # above is individually validated by the API, and the guarded commit
        # is the real end-to-end check, so validate adds nothing here.
        if args.apply:
            check(
                request_with_retry(
                    lambda: s.post(
                        f"{BASE}/edits/{edit_id}:commit?changesNotSentForReview=true"
                    ),
                    "commit",
                ),
                "commit edit",
            )
            committed = True
            print("COMMITTED: listing is now staged in the Play Console "
                  "(submit for review from the Console when declarations are done)")
        else:
            print("dry-run complete: all content staged + accepted by the API; "
                  "edit will be discarded (apply commits it)")
    finally:
        if not committed:
            delete_edit(s, edit_id)


if __name__ == "__main__":
    main()
