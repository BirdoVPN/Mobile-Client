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
        try:
            os.remove(path)
        except OSError:
            pass
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bundle-id", default="app.birdo.vpn")
    ap.add_argument("--platform", required=True, choices=["MAC_OS", "IOS"])
    ap.add_argument("--version", required=True)
    ap.add_argument("--whats-new", default="")
    ap.add_argument("--build", default="", help="CFBundleVersion to attach, e.g. 10427")
    ap.add_argument("--dry-run", action="store_true")
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

    existing = api(tok, f"apps/{app_id}/appStoreVersions"
                        f"?filter[platform]={args.platform}&limit=20")
    if "_http" in existing:
        fail("could not list existing versions", existing)

    print(f"existing {args.platform} versions:")
    version_id = None
    for v in existing.get("data", []):
        a = v["attributes"]
        marker = "  <- target" if a.get("versionString") == args.version else ""
        print(f"  {a.get('versionString'):12} {a.get('appStoreState')}{marker}")
        if a.get("versionString") == args.version:
            version_id = v["id"]

    if version_id:
        print(f"\nversion {args.version} already exists ({version_id})")
    elif args.dry_run:
        print(f"\nDRY RUN: would create {args.platform} version {args.version}")
        return
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
