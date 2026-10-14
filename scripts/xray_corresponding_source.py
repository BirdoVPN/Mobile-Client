#!/usr/bin/env python3
"""Build the Xray corresponding-source release asset, and refuse on any mismatch.

Owner decision L1 (2026-10-14, the Android twin of Desktop D8): every Android
release carries the exact source of the copyleft code it ships in libxray.so,
as a release asset with a SHA-256. libxray.so is the Xray-core program
(MPL-2.0), and it links github.com/sagernet/sing and sing-shadowsocks
(GPL-3.0-or-later), github.com/juju/ratelimit (LGPL-3.0 with a linking
exception) and github.com/xtls/reality (MPL-2.0). GPL-3.0 section 6 lets the
object code be conveyed with the Corresponding Source offered "from a
designated place"; this asset, attached to the release, is that place, and the
in-app notices (app/src/main/assets/third_party_notices.txt) point to it first.

The asset, xray-core-<XRAY_VERSION>-corresponding-source.tar.gz, holds:
  * Xray-core-<XRAY_VERSION>.tar.gz — GitHub's archive of the pinned tag, byte
    for byte;
  * go-modules/<module>@<version>.zip — every GPL, LGPL and MPL Go module the
    binaries link, byte for byte from proxy.golang.org;
  * provision_xray_engines.sh — the script that fetches two ABIs and builds the
    other two from that source (the build instructions);
  * README.txt — what each file is, its SHA-256, and which binaries it builds.

Nothing is written unless all of this holds (exit 1 otherwise):
  * the tag archive matches XRAY_SOURCE_ARCHIVE_SHA256 and was made from XRAY_COMMIT
    (both pinned in provision_xray_engines.sh);
  * every module in scripts/xray-go-modules.txt is at the version Xray-core's
    own go.mod requires at that tag;
  * with --binaries: every binary's Go build info lists exactly the table's
    modules at exactly its versions — so an Xray bump cannot ship until the
    table, and with it the licences and the mirrored source, follow;
  * each copyleft module zip matches its pinned SHA-256, and its Go checksum
    (dirhash h1:) equals the line in the tag's go.sum (and in the binaries).

The output is deterministic: fixed tar headers, sorted members, a gzip header
with no name and no timestamp. The same pins give the same bytes on every run.

Usage:
  python3 scripts/xray_corresponding_source.py --out DIR [--binaries libxray.so ...]
"""

from __future__ import annotations

import argparse
import base64
import gzip
import hashlib
import io
import os
import re
import subprocess
import sys
import tarfile
import time
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROVISION = ROOT / "scripts" / "provision_xray_engines.sh"
TABLE = ROOT / "scripts" / "xray-go-modules.txt"
COPYLEFT = re.compile(r"\b(?:L?GPL|MPL)-")


def fail(message: str) -> None:
    print(f"::error::{message}", file=sys.stderr)
    sys.exit(1)


def pins() -> dict[str, str]:
    text = PROVISION.read_text(encoding="utf-8")
    found = dict(re.findall(r'(?m)^(XRAY_[A-Z0-9_]+)="([^"]*)"', text))
    for key in ("XRAY_VERSION", "XRAY_COMMIT", "XRAY_SOURCE_ARCHIVE_SHA256",
                "XRAY_ANDROID_ARM64_SHA256", "XRAY_ANDROID_AMD64_SHA256"):
        if not found.get(key):
            fail(f"{PROVISION.name} has no {key} pin")
    return found


def table() -> dict[str, tuple[str, str, str | None]]:
    """module -> (version, licence, zip sha256 or None)."""
    rows: dict[str, tuple[str, str, str | None]] = {}
    for n, line in enumerate(TABLE.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        cols = re.split(r"\s{2,}", line.strip())
        if len(cols) not in (3, 4):
            fail(f"{TABLE.name}:{n}: expected module, version, licence[, zip sha256]")
        module, version, licence = cols[:3]
        sha = cols[3] if len(cols) == 4 else None
        if module in rows:
            fail(f"{TABLE.name}:{n}: {module} is listed twice")
        if COPYLEFT.search(licence) and not (sha and re.fullmatch(r"[0-9a-f]{64}", sha)):
            fail(f"{TABLE.name}:{n}: {module} is {licence} but has no zip SHA-256 — its source must be mirrored")
        if sha and not COPYLEFT.search(licence):
            fail(f"{TABLE.name}:{n}: {module} has a zip SHA-256 but a permissive licence; only copyleft source is mirrored")
        rows[module] = (version, licence, sha)
    if len(rows) < 10:
        fail(f"{TABLE.name} lists only {len(rows)} modules; the table scan is broken")
    return rows


def fetch(url: str) -> bytes:
    last: Exception | None = None
    for attempt in range(4):
        try:
            with urllib.request.urlopen(url, timeout=60) as response:  # noqa: S310 (https only, fixed hosts)
                return response.read()
        except Exception as error:  # network blips: retry, then fail loudly
            last = error
            time.sleep(2 * (attempt + 1))
    fail(f"could not download {url}: {last}")
    raise AssertionError  # unreachable


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def proxy_escape(path: str) -> str:
    """Go module proxy case-encoding: each upper-case letter becomes '!' + lower."""
    return re.sub(r"[A-Z]", lambda m: "!" + m.group(0).lower(), path)


def dirhash_h1(zip_bytes: bytes) -> str:
    """golang.org/x/mod/sumdb/dirhash.HashZip with Hash1 — what go.sum records."""
    summary = hashlib.sha256()
    with zipfile.ZipFile(io.BytesIO(zip_bytes)) as z:
        for name in sorted(z.namelist()):
            if "\n" in name:
                fail(f"module zip entry with a newline in its name: {name!r}")
            summary.update(f"{sha256(z.read(name))}  {name}\n".encode())
    return "h1:" + base64.b64encode(summary.digest()).decode()


def go_mod_requires(text: str) -> dict[str, str]:
    requires: dict[str, str] = {}
    block = False
    for raw in text.splitlines():
        line = raw.split("//", 1)[0].strip()
        if line.startswith("require ("):
            block = True
            continue
        if block and line == ")":
            block = False
            continue
        if block and line:
            module, version = line.split()[:2]
            requires[module] = version
        elif line.startswith("require "):
            module, version = line.split()[1:3]
            requires[module] = version
        elif line.startswith("replace "):
            fail(f"Xray-core's go.mod replaces a module ({line}); review the mirrored source by hand")
    return requires


def go_sum(text: str) -> dict[tuple[str, str], str]:
    sums: dict[tuple[str, str], str] = {}
    for line in text.splitlines():
        parts = line.split()
        if len(parts) == 3 and not parts[1].endswith("/go.mod"):
            sums[(parts[0], parts[1])] = parts[2]
    return sums


def build_info(binary: Path) -> tuple[dict[str, tuple[str, str]], str]:
    """`go version -m`: module -> (version, h1), and the Go version that built it."""
    go = os.environ.get("GO", "go")
    try:
        out = subprocess.run([go, "version", "-m", str(binary)], check=True,
                             capture_output=True, text=True).stdout
    except (OSError, subprocess.CalledProcessError) as error:
        fail(f"`go version -m {binary}` failed: {error}")
    deps: dict[str, tuple[str, str]] = {}
    toolchain = ""
    for n, line in enumerate(out.splitlines()):
        cols = line.split("\t")
        if n == 0:
            toolchain = line.rsplit(" ", 1)[-1]
        elif len(cols) >= 3 and cols[1] == "dep":
            deps[cols[2]] = (cols[3], cols[4] if len(cols) > 4 else "")
        elif len(cols) >= 2 and cols[1] == "=>":
            fail(f"{binary} was built with a replaced module ({line.strip()}); its source is not the proxy's")
    if len(deps) < 10:
        fail(f"{binary}: `go version -m` listed only {len(deps)} modules — not a Go binary with build info?")
    return deps, toolchain


def deterministic_tar_gz(members: list[tuple[str, bytes, int]]) -> bytes:
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode="w", format=tarfile.USTAR_FORMAT) as tar:
        for name, data, mode in sorted(members):
            info = tarfile.TarInfo(name)
            info.size = len(data)
            info.mode = mode
            info.mtime = 0
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            tar.addfile(info, io.BytesIO(data))
    out = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=out, compresslevel=9, mtime=0) as gz:
        gz.write(raw.getvalue())
    return out.getvalue()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--out", required=True, type=Path, help="directory to write the asset into")
    parser.add_argument("--binaries", nargs="*", type=Path, default=[],
                        help="the provisioned libxray.so files, checked against the table")
    args = parser.parse_args()

    pin = pins()
    version, commit = pin["XRAY_VERSION"], pin["XRAY_COMMIT"]
    modules = table()

    # 1. The binaries link exactly the table.
    linked_h1: dict[str, str] = {}
    for binary in args.binaries:
        deps, toolchain = build_info(binary)
        missing = sorted(set(deps) - set(modules))
        if missing:
            fail(f"{binary} links modules {TABLE.name} does not list (review their licences, add them): {missing}")
        unlinked = sorted(set(modules) - set(deps))
        if unlinked:
            fail(f"{TABLE.name} lists modules {binary} does not link (remove them): {unlinked}")
        for module, (got, h1) in deps.items():
            want = modules[module][0]
            if got != want:
                fail(f"{binary} links {module} {got}, {TABLE.name} pins {want}: the engine moved without its source")
            if linked_h1.setdefault(module, h1) != h1:
                fail(f"the binaries disagree on the checksum of {module} {got}")
        print(f"  {binary}: {len(deps)} modules match {TABLE.name} ({toolchain})")

    # 2. The tag archive is the pinned tag at the pinned commit.
    archive_name = f"Xray-core-{version}.tar.gz"
    archive = fetch(f"https://github.com/XTLS/Xray-core/archive/refs/tags/{version}.tar.gz")
    if sha256(archive) != pin["XRAY_SOURCE_ARCHIVE_SHA256"]:
        fail(f"the {version} tag archive is {sha256(archive)}, XRAY_SOURCE_ARCHIVE_SHA256 pins {pin['XRAY_SOURCE_ARCHIVE_SHA256']}")
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as tar:
        if tar.pax_headers.get("comment") != commit:
            fail(f"the {version} tag archive was made from {tar.pax_headers.get('comment')}, not {commit}")
        top = tar.getnames()[0].split("/")[0]
        go_mod_member = tar.extractfile(f"{top}/go.mod")
        go_sum_member = tar.extractfile(f"{top}/go.sum")
        if go_mod_member is None or go_sum_member is None:
            fail(f"the {version} tag archive has no go.mod / go.sum")
        requires = go_mod_requires(go_mod_member.read().decode())
        sums = go_sum(go_sum_member.read().decode())

    # 3. The table is Xray's go.mod at that tag.
    for module, (want, _, _) in modules.items():
        if requires.get(module) != want:
            fail(f"{TABLE.name} pins {module} {want}, Xray-core {version}'s go.mod requires {requires.get(module)}")

    # 4. The copyleft module zips, verified three ways.
    members: list[tuple[str, bytes, int]] = []
    rows = []
    for module, (mod_version, licence, want_sha) in sorted(modules.items()):
        if want_sha is None:
            continue
        url = f"https://proxy.golang.org/{proxy_escape(module)}/@v/{proxy_escape(mod_version)}.zip"
        data = fetch(url)
        if sha256(data) != want_sha:
            fail(f"{module}@{mod_version}.zip is {sha256(data)}, {TABLE.name} pins {want_sha}")
        h1 = dirhash_h1(data)
        if sums.get((module, mod_version)) != h1:
            fail(f"{module}@{mod_version}: zip checksum {h1}, Xray-core's go.sum says {sums.get((module, mod_version))}")
        if module in linked_h1 and linked_h1[module] != h1:
            fail(f"{module}@{mod_version}: zip checksum {h1}, the shipped binaries were built from {linked_h1[module]}")
        path = f"go-modules/{module}@{mod_version}.zip"
        members.append((path, data, 0o644))
        rows.append((path, want_sha, licence, h1))
        print(f"  {path}: sha256 {want_sha} {h1} ({licence})")

    script = PROVISION.read_bytes().replace(b"\r\n", b"\n")
    members.append((archive_name, archive, 0o644))
    members.append(("provision_xray_engines.sh", script, 0o755))

    permissive = [f"    {m} {v} ({lic})" for m, (v, lic, s) in sorted(modules.items()) if s is None]
    readme = "\n".join([
        "BirdoVPN for Android: complete corresponding source of libxray.so",
        "",
        f"libxray.so is the Xray-core program {version} (commit {commit}),",
        "unmodified. It is MPL-2.0, and it links the GPL-3.0, LGPL-3.0 and MPL-2.0",
        "Go modules below. This archive is their complete source, at exactly the",
        "versions the shipped program links.",
        "",
        "Which binary is which:",
        f"  arm64-v8a  the upstream release asset Xray-android-arm64-v8a.zip (sha256 {pin['XRAY_ANDROID_ARM64_SHA256']})",
        f"  x86_64     the upstream release asset Xray-android-amd64.zip (sha256 {pin['XRAY_ANDROID_AMD64_SHA256']})",
        "  armeabi-v7a, x86",
        "             built from the source below by provision_xray_engines.sh (in this",
        "             archive; the `go build` command and flags are in build_from_source)",
        "",
        "Contents (every file byte for byte as published upstream):",
        f"  {archive_name}",
        f"    sha256 {pin['XRAY_SOURCE_ARCHIVE_SHA256']}",
        f"    https://github.com/XTLS/Xray-core/archive/refs/tags/{version}.tar.gz",
        "    Xray-core, MPL-2.0. Its go.mod and go.sum name every module below.",
    ] + [line for path, digest, licence, h1 in rows for line in (
        f"  {path}",
        f"    sha256 {digest}",
        f"    Go checksum {h1} (equal to Xray-core's go.sum)",
        f"    {licence}; from https://proxy.golang.org",
    )] + [
        "  provision_xray_engines.sh",
        "    the build script, from https://github.com/BirdoVPN/Mobile-Client",
        "",
        "To build: unpack the Xray-core archive and run `go build ./main` in it.",
        "Go downloads each module and verifies it against go.sum; for the modules",
        "above, what it verifies is these zips (the same Go checksum).",
        "",
        "The other modules the program links are under permissive licences, and",
        "their source is on proxy.golang.org at the versions in Xray-core's go.sum:",
    ] + permissive + [
        "",
        "The Go toolchain (BSD-3-Clause) is not part of this archive: https://go.dev/dl/",
        "",
    ])
    members.append(("README.txt", readme.encode(), 0o644))

    prefix = f"xray-core-{version}-corresponding-source"
    asset = deterministic_tar_gz([(f"{prefix}/{name}", data, mode) for name, data, mode in members])
    args.out.mkdir(parents=True, exist_ok=True)
    target = args.out / f"{prefix}.tar.gz"
    target.write_bytes(asset)
    print(f"{target.name}  sha256 {sha256(asset)}  {len(asset)} bytes")


if __name__ == "__main__":
    main()
