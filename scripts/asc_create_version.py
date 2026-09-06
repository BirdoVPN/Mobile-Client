#!/usr/bin/env python3
"""
Create an App Store version record, and optionally attach a build to it.

WHY THIS EXISTS

Uploading a build and creating a version are two different operations in App
Store Connect, and only the first was ever automated here. `altool --upload-app`
puts a binary in TestFlight; it does not create the version record the App Store
tab shows, and nothing else does either.

That gap cost a full review round. macOS 1.4.27 was built, signed and uploaded
successfully, while the App Store tab still read 1.4.23 — so there was no version
to attach the build to, no version to submit, and no way to tell from CI that
anything was missing. Every signal was green.

Uses the same three secrets `altool` already uses:
  APPSTORE_API_KEY_BASE64  APPSTORE_API_KEY_ID  APPSTORE_API_ISSUER_ID

Prints no key material. The .p8 is written to a 0600 temp file only for the
duration of one openssl signature and removed immediately.

Usage:
  asc_create_version.py --platform MAC_OS --version 1.4.27 \
      [--whats-new "..."] [--build 10427] [--dry-run]

Exit codes: 0 created or already present, 1 failed.
"""
import argparse, base64, io, json, os, subprocess, sys, tempfile, time
import urllib.error, urllib.request

API = "https://api.appstoreconnect.apple.com/v1/"


def b64u(b: bytes) -> bytes:
    return base64.urlsafe_b64encode(b).rstrip(b"=")


def _der_to_raw(der: bytes) -> bytes:
    """ES256 wants a raw 64-byte r||s; openssl emits DER. Convert."""
    if der[0] != 0x30:
        raise ValueError("not a DER sequence")
    i = 2 if der[1] < 0x80 else 2 + (der[1] & 0x7F)
    out = []
    for _ in range(2):
        if der[i] != 0x02:
            raise ValueError("expected DER integer")
        ln = der[i + 1]
        v = der[i + 2:i + 2 + ln].lstrip(b"\x00")
        out.append(v.rjust(32, b"\x00"))
        i += 2 + ln
    return out[0] + out[1]


def make_token(key_pem: str, key_id: str, issuer: str) -> str:
    now = int(time.time())
    signing_input = (
        b64u(json.dumps({"alg": "ES256", "kid": key_id, "typ": "JWT"}).encode())
        + b"."
        + b64u(json.dumps({"iss": issuer, "iat": now, "exp": now + 900,
                           "aud": "appstoreconnect-v1"}).encode())
    )
    fd, path = tempfile.mkstemp()
    try:
        os.close(fd)
        os.chmod(path, 0o600)
        io.open(path, "w", encoding="utf-8").write(key_pem)
        der = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", path],
            input=signing_input, capture_output=True, check=True,
        ).stdout
    finally:
        # This temp file held the .p8 PRIVATE KEY, so a failed removal is not a
        # tidy-up nuisance — it leaves key material on the runner's disk. Do not
        # swallow it: warn loudly with the path so it is visible in the job log
        # and can be cleaned up. Still non-fatal, because the signature already
        # succeeded and aborting here would fail a job that did its work.
        try:
            os.remove(path)
        except OSError as e:
            print(f"::warning::could not remove the temporary key file {path}: {e}. "
                  f"Private key material may remain on this runner.")
    return (signing_input + b"." + b64u(_der_to_raw(der))).decode()


def api(tok, path, method="GET", payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    headers = {"Authorization": "Bearer " + tok}
    if data:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(API + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        try:
            return {"_http": e.code, "_err": json.load(e)}
        except Exception:
            return {"_http": e.code, "_err": {"raw": str(e.reason)}}


def fail(msg, obj=None):
    print(f"::error::{msg}")
    if obj is not None:
        for e in (obj.get("_err", {}).get("errors") or [obj.get("_err", obj)]):
            print(f"  {e.get('title', '')}: {e.get('detail', e)}")
    sys.exit(1)


def audit_account(tok, bundle_id):
    """Print every app on the account and every version record on each.

    WHY: the App Store Connect web UI caches aggressively, and a version that was
    RENAMED after a rejection (see the EDITABLE note in main) can keep showing its
    old number in a stale tab for a long time. When the dashboard and the API
    disagree, this is the tie-breaker -- it reads the account itself, so it also
    catches the other explanation, which is that the platform you are looking at
    lives on a DIFFERENT app record entirely.

    Prints identifiers and states only. No key material, no user data.
    """
    print("\n" + "=" * 68)
    print("ACCOUNT AUDIT - what App Store Connect actually holds right now")
    print("=" * 68)

    allapps = api(tok, "apps?limit=100")
    if "_http" in allapps:
        print("::warning::could not list apps for the audit")
        return
    rows = allapps.get("data", [])
    print("\napps on this account: %d" % len(rows))
    for a in rows:
        at = a["attributes"]
        mark = "  <- targeted by this run" if at.get("bundleId") == bundle_id else ""
        print("  %-12s %-30s %s%s" % (a["id"], at.get("bundleId", "?"),
                                      at.get("name", "?"), mark))

    for a in rows:
        at = a["attributes"]
        print("\n--- %s (%s) ---" % (at.get("name", "?"), at.get("bundleId", "?")))
        vers = api(tok, "apps/%s/appStoreVersions?limit=50" % a["id"])
        if "_http" in vers:
            print("    could not list versions")
            continue
        data = vers.get("data", [])
        if not data:
            print("    (no version records at all)")
            continue
        for v in data:
            va = v["attributes"]
            # appStoreState is the legacy field and appVersionState the current
            # one; they are not both populated across API versions, and reading
            # only one of them is how you get a confidently wrong answer.
            state = va.get("appStoreState") or va.get("appVersionState") or "?"
            b = api(tok, "appStoreVersions/%s/build" % v["id"])
            bv = "no build attached"
            if "_http" not in b and b.get("data"):
                bv = "build " + str((b["data"].get("attributes") or {}).get("version", "?"))
            print("    %-8s %-10s %-26s %s" % (va.get("platform", "?"),
                                               va.get("versionString", "?"), state, bv))
    print("\n" + "=" * 68)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bundle-id", default="app.birdo.vpn")
    ap.add_argument("--platform", required=True, choices=["MAC_OS", "IOS"])
    ap.add_argument("--version", required=True)
    ap.add_argument("--whats-new", default="")
    ap.add_argument("--build", default="", help="CFBundleVersion to attach, e.g. 10427")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--audit", action="store_true",
                    help="dump every app and version record on the account")
    args = ap.parse_args()

    key_b64 = os.environ.get("APPSTORE_API_KEY_BASE64", "")
    key_id = os.environ.get("APPSTORE_API_KEY_ID", "")
    issuer = os.environ.get("APPSTORE_API_ISSUER_ID", "")
    missing = [n for n, v in (
        ("APPSTORE_API_KEY_BASE64", key_b64),
        ("APPSTORE_API_KEY_ID", key_id),
        ("APPSTORE_API_ISSUER_ID", issuer)) if not v]
    if missing:
        fail("missing secrets: " + ", ".join(missing))

    try:
        key_pem = base64.b64decode(key_b64).decode()
    except Exception:
        fail("APPSTORE_API_KEY_BASE64 did not decode as base64")
    if "PRIVATE KEY" not in key_pem:
        fail("decoded APPSTORE_API_KEY_BASE64 is not a PEM private key")

    tok = make_token(key_pem, key_id, issuer)

    apps = api(tok, f"apps?filter[bundleId]={args.bundle_id}")
    if "_http" in apps:
        fail("authentication or lookup failed — check the three secrets", apps)
    if not apps.get("data"):
        fail(f"no app found for bundle id {args.bundle_id}")
    app_id = apps["data"][0]["id"]
    print(f"app: {apps['data'][0]['attributes'].get('name')} ({app_id})")

    if args.dry_run or args.audit:
        audit_account(tok, args.bundle_id)

    existing = api(tok, f"apps/{app_id}/appStoreVersions"
                        f"?filter[platform]={args.platform}&limit=20")
    if "_http" in existing:
        fail("could not list existing versions", existing)

    # App Store Connect permits at most ONE version in an editable state and
    # refuses to create another while one exists:
    #   "You cannot create a new version of the App in the current state."
    # A REJECTED version IS editable, so the correct move after a rejection is to
    # RENAME that version and resubmit it, not to add a second one. Getting this
    # wrong leaves the App Store tab apparently stuck on the rejected version
    # with no way forward -- which is exactly how this was found.
    EDITABLE = {
        "PREPARE_FOR_SUBMISSION", "DEVELOPER_REJECTED", "REJECTED",
        "METADATA_REJECTED", "INVALID_BINARY", "WAITING_FOR_EXPORT_COMPLIANCE",
    }

    print(f"existing {args.platform} versions:")
    version_id = None
    editable_id = editable_str = editable_state = None
    for v in existing.get("data", []):
        a = v["attributes"]
        vs, st = a.get("versionString"), a.get("appStoreState")
        marker = "  <- target" if vs == args.version else (
            "  <- editable" if st in EDITABLE else "")
        print(f"  {vs:12} {st}{marker}")
        if vs == args.version:
            version_id = v["id"]
        elif st in EDITABLE and editable_id is None:
            editable_id, editable_str, editable_state = v["id"], vs, st

    if version_id:
        print(f"\nversion {args.version} already exists ({version_id})")
    elif args.dry_run:
        if editable_id:
            print(f"\nDRY RUN: would RENAME {editable_str} ({editable_state}) "
                  f"-> {args.version}")
        else:
            print(f"\nDRY RUN: would create {args.platform} version {args.version}")
        return
    elif editable_id:
        # Reuse rather than create. Renaming keeps the review history, the
        # Resolution Center thread and the existing metadata attached, which is
        # what Apple expects for a resubmission after a rejection.
        print(f"\nan editable version already exists: {editable_str} "
              f"({editable_state}). Apple allows only one, so RENAMING it to "
              f"{args.version} rather than creating a second.")
        res = api(tok, f"appStoreVersions/{editable_id}", method="PATCH", payload={
            "data": {"type": "appStoreVersions", "id": editable_id,
                     "attributes": {"versionString": args.version}}})
        if "_http" in res:
            fail(f"could not rename {editable_str} to {args.version}", res)
        version_id = editable_id
        print(f"RENAMED {editable_str} -> {args.version} (id {version_id}, "
              f"state {res['data']['attributes'].get('appStoreState')})")
    else:
        print(f"\ncreating {args.platform} version {args.version} ...")
        res = api(tok, "appStoreVersions", method="POST", payload={
            "data": {
                "type": "appStoreVersions",
                "attributes": {"platform": args.platform,
                               "versionString": args.version},
                "relationships": {"app": {"data": {"type": "apps", "id": app_id}}},
            }})
        if "_http" in res:
            fail(f"could not create version {args.version}", res)
        version_id = res["data"]["id"]
        print(f"CREATED {args.version} (id {version_id}, "
              f"state {res['data']['attributes'].get('appStoreState')})")

    if args.whats_new:
        locs = api(tok, f"appStoreVersions/{version_id}/appStoreVersionLocalizations")
        for loc in locs.get("data", []):
            r = api(tok, f"appStoreVersionLocalizations/{loc['id']}", method="PATCH",
                    payload={"data": {"type": "appStoreVersionLocalizations",
                                      "id": loc["id"],
                                      "attributes": {"whatsNew": args.whats_new}}})
            state = "ok" if "_http" not in r else f"FAILED ({r['_http']})"
            print(f"what's-new [{loc['attributes'].get('locale')}]: {state}")

    if args.build:
        builds = api(tok, f"builds?filter[app]={app_id}"
                          f"&filter[version]={args.build}&limit=5")
        data = builds.get("data") or []
        if not data:
            # Not fatal: processing can lag well behind the upload, and the
            # version record is the thing that was missing. Say so plainly
            # rather than failing a job that achieved its main purpose.
            print(f"::warning::build {args.build} not found yet — it is probably "
                  f"still processing. Attach it in App Store Connect once it "
                  f"appears under TestFlight.")
        else:
            bid = data[0]["id"]
            r = api(tok, f"appStoreVersions/{version_id}/relationships/build",
                    method="PATCH",
                    payload={"data": {"type": "builds", "id": bid}})
            if "_http" in r:
                print(f"::warning::could not attach build {args.build}: "
                      f"{json.dumps(r.get('_err'))[:200]}")
            else:
                print(f"attached build {args.build} ({bid})")

    print("\nSTILL MANUAL, and neither can be scripted — the API returns 409 for both:")
    print("  * a Review Screenshot on each In-App Purchase")
    print("  * attaching those IAPs to this version")
    print("  See _local/apple/macos-iap-submission-clickpath.md")


if __name__ == "__main__":
    main()
