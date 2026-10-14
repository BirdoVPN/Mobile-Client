#!/usr/bin/env python3
"""Build the Xray corresponding-source release asset, and refuse on any mismatch.

Owner decision L1 (2026-10-14, the Android twin of Desktop D8): every Android
release carries the complete corresponding source of libxray.so as a release
asset with a SHA-256. libxray.so is the Xray-core program (MPL-2.0), and it
links github.com/sagernet/sing and sing-shadowsocks (GPL-3.0-or-later),
github.com/juju/ratelimit (LGPL-3.0 with a linking exception),
github.com/xtls/reality (MPL-2.0) and some 28 permissively licensed modules.
Under GPL-3.0 section 1 the Corresponding Source of the combined program is the
source of ALL of it, so every linked module is mirrored, not only the copyleft
ones. Section 6 lets the object code be conveyed with that source offered "from
a designated place"; this asset, published with the release, is that place, and
the in-app notices (app/src/main/assets/third_party_notices.txt) point to it.

The asset, xray-core-<XRAY_VERSION>-corresponding-source.tar.gz, holds:
  * Xray-core-<XRAY_VERSION>.tar.gz — `git archive` of XRAY_COMMIT, whose git
    tree must be XRAY_SOURCE_TREE (content-addressed: a pin on what the source
    IS, not on how GitHub happens to compress a tag), gzip'd deterministically;
  * go-modules/<module>@<version>.zip — EVERY Go module the shipped binaries
    link, byte for byte from proxy.golang.org;
  * go-toolchain/<go version>.src.tar.gz — the Go source distribution of each
    toolchain that built a shipped engine, byte for byte from go.dev: the Go
    standard library and runtime are linked into every engine;
  * provision_xray_engines.sh — the script that fetches two ABIs and builds the
    other two from that source;
  * README.txt — what each file is (SHA-256, Go checksum, licence) and how
    each shipped engine was made (upstream asset, or the Go toolchain, NDK
    revision and exact `go build` command, from provision_xray_engines.sh's
    build record).

Nothing is written unless all of this holds (exit 1 otherwise):
  * XRAY_COMMIT's tree is XRAY_SOURCE_TREE (both pinned in
    provision_xray_engines.sh);
  * every module in scripts/xray-go-modules.txt is at the version Xray-core's
    own go.mod requires at that commit;
  * every binary's Go build info lists exactly the table's modules at exactly
    its versions — so an Xray bump cannot ship until the table, and with it the
    licences and the mirrored source, follow;
  * each module zip matches its pinned SHA-256, and its Go checksum (dirhash
    h1:) equals the line in Xray-core's go.sum and in the binaries' build info;
  * the build record covers exactly the binaries given; each upstream binary
    was built with XRAY_UPSTREAM_GO_VERSION and carries XRAY_COMMIT's stamp
    (Xray's release build writes the abbreviated commit into core.build); each
    of ours was built with XRAY_GO_VERSION;
  * the Go sources mirrored are exactly the toolchains in the binaries' build
    info, each matching the SHA-256 go.dev publishes for it, as pinned in
    XRAY_GO_VERSION_SOURCE_SHA256 / XRAY_UPSTREAM_GO_SOURCE_SHA256.

The output is deterministic: fixed tar headers, sorted members, gzip headers
with no name and no timestamp. The same pins, binaries and build record give
the same bytes on every run.

Usage:
  python3 scripts/xray_corresponding_source.py --out DIR \\
      --binaries app/src/main/jniLibs/*/libxray.so \\
      --build-record "$RUNNER_TEMP/xray-provision/build-record.txt" [--cache DIR]

--cache keeps verified downloads (by SHA-256) and the Xray clone for a second run.
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
import tempfile
import time
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROVISION = ROOT / "scripts" / "provision_xray_engines.sh"
TABLE = ROOT / "scripts" / "xray-go-modules.txt"
COPYLEFT = re.compile(r"\b(?:L?GPL|MPL)-")
XRAY_REPO = "https://github.com/XTLS/Xray-core.git"
# The upstream release assets and the pins that name their SHA-256.
UPSTREAM_ASSETS = {
    "Xray-android-arm64-v8a.zip": "XRAY_ANDROID_ARM64_SHA256",
    "Xray-android-amd64.zip": "XRAY_ANDROID_AMD64_SHA256",
}


def fail(message: str) -> None:
    print(f"::error::{message}", file=sys.stderr)
    sys.exit(1)


def pins() -> dict[str, str]:
    text = PROVISION.read_text(encoding="utf-8")
    found = dict(re.findall(r'(?m)^(XRAY_[A-Z0-9_]+)="([^"]*)"', text))
    for key in ("XRAY_VERSION", "XRAY_COMMIT", "XRAY_SOURCE_TREE", "XRAY_GO_VERSION",
                "XRAY_UPSTREAM_GO_VERSION", "XRAY_GO_VERSION_SOURCE_SHA256",
                "XRAY_UPSTREAM_GO_SOURCE_SHA256", *UPSTREAM_ASSETS.values()):
        if not found.get(key):
            fail(f"{PROVISION.name} has no {key} pin")
    return found


def table() -> dict[str, tuple[str, str, str]]:
    """module -> (version, licence, zip sha256)."""
    rows: dict[str, tuple[str, str, str]] = {}
    for n, line in enumerate(TABLE.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        cols = re.split(r"\s{2,}", line.strip())
        if len(cols) != 4 or not re.fullmatch(r"[0-9a-f]{64}", cols[3]):
            fail(f"{TABLE.name}:{n}: expected module, version, licence, zip sha256; every linked module is mirrored")
        module, version, licence, sha = cols
        if module in rows:
            fail(f"{TABLE.name}:{n}: {module} is listed twice")
        rows[module] = (version, licence, sha)
    if len(rows) < 10:
        fail(f"{TABLE.name} lists only {len(rows)} modules; the table scan is broken")
    return rows


def build_record(path: Path) -> dict[str, list[str]]:
    """abi -> [kind, ...]: `fetched <asset> <sha256>` or `built <go> <ndk> <command>`."""
    if not path.is_file():
        fail(f"no build record at {path}: provision_xray_engines.sh writes it next to its work files")
    record: dict[str, list[str]] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        abi, *rest = line.split("\t")
        if abi in record:
            fail(f"the build record lists {abi} twice")
        if not ((rest[:1] == ["fetched"] and len(rest) == 3) or (rest[:1] == ["built"] and len(rest) == 4)):
            fail(f"unreadable build record line: {line!r}")
        record[abi] = rest
    return record


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def fetch(url: str, want_sha: str, into: Path) -> Path:
    """Download url to into/<want_sha>, streamed, and refuse any other bytes.

    A file already there with that SHA-256 (a --cache) is reused. Files, not
    memory: the module zips are ~120 MB and unpack to far more.
    """
    into.mkdir(parents=True, exist_ok=True)
    target = into / want_sha
    if target.is_file() and file_sha256(target) == want_sha:
        return target
    part = into / (want_sha + ".part")
    last: Exception | None = None
    for attempt in range(4):
        try:
            with urllib.request.urlopen(url, timeout=120) as response, open(part, "wb") as out:  # noqa: S310
                for chunk in iter(lambda: response.read(1 << 20), b""):
                    out.write(chunk)
            break
        except Exception as error:  # network blips: retry, then fail loudly
            last = error
            time.sleep(2 * (attempt + 1))
    else:
        fail(f"could not download {url}: {last}")
    got = file_sha256(part)
    if got != want_sha:
        part.unlink()
        fail(f"{url} is {got}, the pin says {want_sha}")
    os.replace(part, target)
    return target


def git(*args: str, cwd: Path | None = None) -> bytes:
    # Fixed line endings: `git archive` applies core.autocrlf, which would
    # otherwise make the archive depend on the machine.
    cmd = ["git", "-c", "core.autocrlf=false", "-c", "core.eol=lf", "-c", "advice.detachedHead=false", *args]
    try:
        return subprocess.run(cmd, cwd=cwd, check=True, capture_output=True).stdout
    except (OSError, subprocess.CalledProcessError) as error:
        fail(f"`{' '.join(cmd)}` failed: {getattr(error, 'stderr', b'').decode(errors='replace').strip() or error}")
        raise AssertionError  # unreachable


def xray_checkout(version: str, commit: str, tree: str, cache: Path | None, scratch: Path) -> Path:
    """A clone of the pinned commit, whose tree is the pinned tree."""
    clone = (cache or scratch) / f"Xray-core-{commit}"
    if not (clone / ".git").is_dir() or git("rev-parse", "HEAD", cwd=clone).decode().strip() != commit:
        if clone.exists():
            fail(f"{clone} exists but is not a clean clone of {commit}; remove it")
        git("clone", "--quiet", "--depth", "1", "--branch", version, XRAY_REPO, str(clone))
    got = git("rev-parse", "HEAD", cwd=clone).decode().strip()
    if got != commit:
        fail(f"Xray-core {version} resolves to {got}, not XRAY_COMMIT {commit}: the tag moved")
    got_tree = git("rev-parse", "HEAD^{tree}", cwd=clone).decode().strip()
    if got_tree != tree:
        fail(f"Xray-core {commit} has tree {got_tree}, XRAY_SOURCE_TREE pins {tree}")
    return clone


def deterministic_gzip(data: bytes) -> bytes:
    out = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=out, compresslevel=9, mtime=0) as gz:
        gz.write(data)
    return out.getvalue()


def proxy_escape(path: str) -> str:
    """Go module proxy case-encoding: each upper-case letter becomes '!' + lower."""
    return re.sub(r"[A-Z]", lambda m: "!" + m.group(0).lower(), path)


def dirhash_h1(zip_path: Path) -> str:
    """golang.org/x/mod/sumdb/dirhash.HashZip with Hash1 — what go.sum records."""
    summary = hashlib.sha256()
    with zipfile.ZipFile(zip_path) as z:
        for name in sorted(z.namelist()):
            if "\n" in name:
                fail(f"module zip entry with a newline in its name: {name!r}")
            digest = hashlib.sha256()
            with z.open(name) as member:
                for chunk in iter(lambda: member.read(1 << 20), b""):
                    digest.update(chunk)
            summary.update(f"{digest.hexdigest()}  {name}\n".encode())
    return "h1:" + base64.b64encode(summary.digest()).decode()


def go_mod(text: str) -> tuple[dict[str, str], str]:
    """The require lines (module -> version) and the `go` directive."""
    requires: dict[str, str] = {}
    go_version = ""
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
        elif line.startswith("go "):
            go_version = line.split()[1]
        elif line.startswith("replace "):
            fail(f"Xray-core's go.mod replaces a module ({line}); review the mirrored source by hand")
    return requires, go_version


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
        raise AssertionError  # unreachable
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
    if len(deps) < 10 or not toolchain.startswith("go"):
        fail(f"{binary}: `go version -m` listed only {len(deps)} modules; not a Go binary with build info?")
    return deps, toolchain


def write_deterministic_tar_gz(target: Path, members: list[tuple[str, bytes | Path, int]]) -> None:
    """Streamed to disk (the modules are ~120 MB), then moved into place.

    A member is (name, bytes or a file to stream, mode)."""
    part = target.with_name(target.name + ".part")
    with open(part, "wb") as raw, \
            gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=6, mtime=0) as gz, \
            tarfile.open(fileobj=gz, mode="w", format=tarfile.USTAR_FORMAT) as tar:
        for name, source, mode in sorted(members, key=lambda m: m[0]):
            info = tarfile.TarInfo(name)
            info.mode = mode
            info.mtime = 0
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            if isinstance(source, Path):
                info.size = source.stat().st_size
                with open(source, "rb") as f:
                    tar.addfile(info, f)
            else:
                info.size = len(source)
                tar.addfile(info, io.BytesIO(source))
    os.replace(part, target)


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--out", required=True, type=Path, help="directory to write the asset into")
    parser.add_argument("--binaries", required=True, nargs="+", type=Path,
                        help="the provisioned engines, app/src/main/jniLibs/<abi>/libxray.so")
    parser.add_argument("--build-record", required=True, type=Path,
                        help="provision_xray_engines.sh's build-record.txt for those engines")
    parser.add_argument("--cache", type=Path, help="keep verified downloads and the Xray clone here")
    args = parser.parse_args()

    pin = pins()
    version, commit = pin["XRAY_VERSION"], pin["XRAY_COMMIT"]
    modules = table()
    record = build_record(args.build_record)

    # 1. The binaries link exactly the table, and were made as the record says.
    linked_h1: dict[str, str] = {}
    abis: set[str] = set()
    toolchains: set[str] = set()
    for binary in args.binaries:
        deps, toolchain = build_info(binary)
        toolchains.add(toolchain)
        abi = binary.parent.name
        if abi in abis:
            fail(f"two binaries for {abi}")
        abis.add(abi)
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
        how = record.get(abi)
        if how is None:
            fail(f"the build record does not say how {abi} was made")
        if how[0] == "fetched":
            asset, asset_sha = how[1], how[2]
            if asset not in UPSTREAM_ASSETS or pin[UPSTREAM_ASSETS[asset]] != asset_sha:
                fail(f"{abi} came from {asset} {asset_sha}, which no pin in {PROVISION.name} names")
            if toolchain != pin["XRAY_UPSTREAM_GO_VERSION"]:
                fail(f"{binary} was built with {toolchain}, XRAY_UPSTREAM_GO_VERSION pins {pin['XRAY_UPSTREAM_GO_VERSION']}")
            if commit[:7].encode() not in binary.read_bytes():
                fail(f"{binary} does not carry Xray's stamp of commit {commit[:7]}: not a build of {commit}")
        else:
            if not (toolchain == how[1] == pin["XRAY_GO_VERSION"]):
                fail(f"{binary} was built with {toolchain}, the record says {how[1]}, XRAY_GO_VERSION pins {pin['XRAY_GO_VERSION']}")
        print(f"  {binary}: {len(deps)} modules match {TABLE.name}; {how[0]} with {toolchain}")
    if set(record) != abis:
        fail(f"the build record covers {sorted(record)}, the binaries are {sorted(abis)}")

    scratch_dir = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
    scratch = Path(scratch_dir.name)
    downloads = args.cache or scratch / "downloads"

    # 2. The source: the pinned commit, checked by its tree.
    clone = xray_checkout(version, commit, pin["XRAY_SOURCE_TREE"], args.cache, scratch)
    archive_name = f"Xray-core-{version}.tar.gz"
    archive = deterministic_gzip(git("archive", "--format=tar", f"--prefix=Xray-core-{version}/", commit, cwd=clone))
    requires, go_directive = go_mod(git("show", f"{commit}:go.mod", cwd=clone).decode())
    sums = go_sum(git("show", f"{commit}:go.sum", cwd=clone).decode())

    # 3. The table is Xray's go.mod at that commit.
    for module, (want, _, _) in modules.items():
        if requires.get(module) != want:
            fail(f"{TABLE.name} pins {module} {want}, Xray-core {version}'s go.mod requires {requires.get(module)}")

    # 4. Every module zip, verified three ways.
    members: list[tuple[str, bytes | Path, int]] = []
    rows = []
    total = 0
    for module, (mod_version, licence, want_sha) in sorted(modules.items()):
        url = f"https://proxy.golang.org/{proxy_escape(module)}/@v/{proxy_escape(mod_version)}.zip"
        data = fetch(url, want_sha, downloads)
        h1 = dirhash_h1(data)
        if sums.get((module, mod_version)) != h1:
            fail(f"{module}@{mod_version}: zip checksum {h1}, Xray-core's go.sum says {sums.get((module, mod_version))}")
        if linked_h1.get(module) != h1:
            fail(f"{module}@{mod_version}: zip checksum {h1}, the shipped binaries were built from {linked_h1.get(module)}")
        path = f"go-modules/{module}@{mod_version}.zip"
        members.append((path, data, 0o644))
        rows.append((path, want_sha, licence, h1))
        total += data.stat().st_size
        print(f"  {path}: {data.stat().st_size} bytes, {h1} ({licence})")
    print(f"  {len(rows)} modules, {total} bytes")

    # 5. The Go source of every toolchain that built a shipped engine: its
    # standard library and runtime are linked into the engine too.
    go_source_pins = {
        pin["XRAY_GO_VERSION"]: pin["XRAY_GO_VERSION_SOURCE_SHA256"],
        pin["XRAY_UPSTREAM_GO_VERSION"]: pin["XRAY_UPSTREAM_GO_SOURCE_SHA256"],
    }
    if pin["XRAY_GO_VERSION"] == pin["XRAY_UPSTREAM_GO_VERSION"] and \
            pin["XRAY_GO_VERSION_SOURCE_SHA256"] != pin["XRAY_UPSTREAM_GO_SOURCE_SHA256"]:
        fail(f"{PROVISION.name} pins two different source SHA-256s for {pin['XRAY_GO_VERSION']}")
    go_rows = []
    for toolchain in sorted(toolchains):
        want_sha = go_source_pins.get(toolchain)
        if want_sha is None:
            fail(f"an engine was built with {toolchain}, whose source no pin in {PROVISION.name} names")
        data = fetch(f"https://go.dev/dl/{toolchain}.src.tar.gz", want_sha, downloads)
        path = f"go-toolchain/{toolchain}.src.tar.gz"
        members.append((path, data, 0o644))
        go_rows.append((path, toolchain, want_sha))
        print(f"  {path}: {data.stat().st_size} bytes, sha256 {want_sha}")

    script = PROVISION.read_bytes().replace(b"\r\n", b"\n")
    members.append((archive_name, archive, 0o644))
    members.append(("provision_xray_engines.sh", script, 0o755))

    copyleft = sorted(f"{m} ({lic})" for m, (_, lic, _) in modules.items() if COPYLEFT.search(lic))
    made = []
    for abi in sorted(record):
        how = record[abi]
        if how[0] == "fetched":
            made += [
                f"  {abi}: the upstream release asset {how[1]} (sha256 {how[2]}),",
                f"    unmodified; built by XTLS's release workflow with {pin['XRAY_UPSTREAM_GO_VERSION']} (its",
                f"    build info) and stamped with commit {commit[:7]}.",
            ]
        else:
            made += [
                f"  {abi}: built by BirdoVPN from Xray-core-{version} above with {how[1]} and the",
                f"    Android NDK {how[2]}, in the unpacked source directory:",
                f"      {how[3]}",
            ]
    readme = "\n".join([
        "BirdoVPN for Android: complete corresponding source of libxray.so",
        "",
        f"libxray.so is the Xray-core program {version} (commit {commit}),",
        "unmodified. It is MPL-2.0, and it links the Go modules below, among them",
        "GPL-3.0, LGPL-3.0 and MPL-2.0 ones:",
    ] + [f"  {line}" for line in copyleft] + [
        "This archive is the source of the program, of EVERY Go module it links and",
        "of the Go toolchains (standard library and runtime) that built it, at exactly",
        "the versions the shipped engines link.",
        "",
        "How each shipped engine was made:",
    ] + made + [
        "",
        "Contents (the module zips and Go sources byte for byte as published upstream):",
        f"  {archive_name}",
        f"    sha256 {sha256(archive)}",
        f"    `git archive --prefix=Xray-core-{version}/ {commit}`, gzip'd without a",
        f"    timestamp; that commit's git tree is {pin['XRAY_SOURCE_TREE']}.",
        f"    From {XRAY_REPO}. Xray-core, MPL-2.0; its go.mod and go.sum name every",
        "    module below.",
    ] + [line for path, digest, licence, h1 in rows for line in (
        f"  {path}",
        f"    sha256 {digest}",
        f"    Go checksum {h1} (equal to Xray-core's go.sum and the engines' build info)",
        f"    {licence}; from https://proxy.golang.org",
    )] + [
    ] + [line for path, toolchain, digest in go_rows for line in (
        f"  {path}",
        f"    sha256 {digest} (as go.dev publishes it)",
        f"    https://go.dev/dl/{toolchain}.src.tar.gz; the Go standard library and",
        f"    runtime the engines built with {toolchain} link, BSD-3-Clause",
    )] + [
        "  provision_xray_engines.sh",
        "    the script that fetched and built the engines above, from",
        "    https://github.com/BirdoVPN/Mobile-Client",
        "",
        f"Xray-core's go.mod asks for go {go_directive}. The Android NDK supplies only the",
        "C toolchain and system libraries the 32-bit engines link against.",
        "",
        "To build: unpack the Xray-core archive and run the command above in it.",
        "Go downloads each module from proxy.golang.org and verifies it against",
        "go.sum; these zips are exactly what it downloads (the same Go checksums),",
        "kept here so the source stays available whatever happens upstream.",
        "",
    ])
    members.append(("README.txt", readme.encode(), 0o644))

    prefix = f"xray-core-{version}-corresponding-source"
    args.out.mkdir(parents=True, exist_ok=True)
    target = args.out / f"{prefix}.tar.gz"
    write_deterministic_tar_gz(target, [(f"{prefix}/{name}", source, mode) for name, source, mode in members])
    scratch_dir.cleanup()
    print(f"{target.name}  sha256 {file_sha256(target)}  {target.stat().st_size} bytes")


if __name__ == "__main__":
    main()
