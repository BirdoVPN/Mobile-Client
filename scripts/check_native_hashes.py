#!/usr/bin/env python3
"""Assert the baked NATIVE_HASH_* constants describe the .so files that SHIP.

Why this exists
---------------
NativeLibraryVerifier hashes the engine .so files it finds in
ApplicationInfo.nativeLibraryDir and compares them with three constants baked
into BuildConfig at build time.  Every way that comparison can be wrong is
silent: `decide()` falls back to the APK-signature check, the build stays green,
CI stays green, and the app reports itself healthy while the engine-integrity
control does nothing.  Two such failures have already shipped:

  1. The hashes were written through `android.defaultConfig.buildConfigField`
     from a task `doFirst`, far too late, so every release shipped `""`.
  2. The hashes were computed from `merge<Variant>NativeLibs`.  AGP runs
     `strip<Variant>DebugSymbols` afterwards and it is the STRIPPED bytes that
     packaging puts in the APK.  Measured on this repo, NDK 29.0.13846066:
     librosenpass_jni.so goes 469480 -> 325280 bytes across that step (it is
     built with `strip = "debuginfo"` to keep the symbol table for
     debugSymbolLevel=FULL, and AGP strips it again), and libxray.so changes
     hash at the same size.  Only libwg-go.so was unaffected.

Neither was visible from the build log.  This script closes both by recomputing
the hashes from the artifacts on disk and refusing to agree with BuildConfig
unless they match exactly.

It is deliberately strict about ABSENCE too: a constant that is missing, blank,
or malformed fails, and an ABI present on disk but absent from the constant
fails.  "Nothing to check" must never read as "check passed".

Usage:  python3 scripts/check_native_hashes.py [--app-dir app]
Exit:   0 all good, 1 a mismatch or a missing artifact.
"""

import argparse
import hashlib
import os
import re
import sys
import zipfile

# BuildConfig constant -> the `lib<name>.so` it describes.
ENGINES = {
    "NATIVE_HASH_WG_GO": "libwg-go.so",
    "NATIVE_HASH_XRAY": "libxray.so",
    "NATIVE_HASH_ROSENPASS_JNI": "librosenpass_jni.so",
}

CONST_RE = re.compile(
    r'public\s+static\s+final\s+String\s+(NATIVE_HASH_[A-Z0-9_]+)\s*=\s*"([^"]*)"\s*;'
)
ENTRY_RE = re.compile(r"^([A-Za-z0-9_\-]+)=([0-9a-f]{64})$")


def fail(msg):
    print("FAIL: %s" % msg, file=sys.stderr)
    return 1


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def find_build_config(app_dir):
    root = os.path.join(app_dir, "build", "generated", "source", "buildConfig", "release")
    for dirpath, _dirnames, filenames in os.walk(root):
        if "BuildConfig.java" in filenames:
            return os.path.join(dirpath, "BuildConfig.java")
    return None


def find_stripped_lib_dir(app_dir):
    """<app>/build/intermediates/stripped_native_libs/release/<task>/out/lib"""
    root = os.path.join(app_dir, "build", "intermediates", "stripped_native_libs", "release")
    for dirpath, dirnames, _f in os.walk(root):
        if os.path.basename(dirpath) == "lib" and os.path.basename(os.path.dirname(dirpath)) == "out":
            del dirnames  # keep linters quiet; we want this exact directory
            return dirpath
    return None


def find_release_apk(app_dir):
    out = os.path.join(app_dir, "build", "outputs", "apk", "release")
    if not os.path.isdir(out):
        return None
    apks = sorted(f for f in os.listdir(out) if f.endswith(".apk"))
    return os.path.join(out, apks[0]) if apks else None


def parse_constants(build_config_path):
    with open(build_config_path, "r", encoding="utf-8") as fh:
        source = fh.read()
    return {m.group(1): m.group(2) for m in CONST_RE.finditer(source)}


def parse_entries(const_name, raw):
    """"abi=hash;abi=hash" -> {abi: hash}, or (None, error)."""
    entries = {}
    for part in raw.split(";"):
        part = part.strip()
        if not part:
            continue
        m = ENTRY_RE.match(part)
        if not m:
            return None, "%s has a malformed entry %r; expected <abi>=<64 hex chars>" % (
                const_name,
                part,
            )
        entries[m.group(1)] = m.group(2)
    if not entries:
        return None, "%s parsed to zero abi=hash entries" % const_name
    return entries, None


def actual_from_stripped(lib_dir, so_name):
    """{abi: sha256} for every ABI directory that contains so_name."""
    found = {}
    for abi in sorted(os.listdir(lib_dir)):
        candidate = os.path.join(lib_dir, abi, so_name)
        if os.path.isfile(candidate):
            found[abi] = sha256_file(candidate)
    return found


def actual_from_apk(apk_path, so_name):
    found = {}
    with zipfile.ZipFile(apk_path) as zf:
        for name in zf.namelist():
            parts = name.split("/")
            if len(parts) == 3 and parts[0] == "lib" and parts[2] == so_name:
                found[parts[1]] = sha256_bytes(zf.read(name))
    return found


def compare(source_label, const_name, expected, actual, strict_coverage):
    """`expected` is what BuildConfig claims, `actual` what is on disk."""
    problems = []
    if not actual:
        return ["%s: no %s found under %s" % (const_name, ENGINES[const_name], source_label)]
    for abi, actual_hash in sorted(actual.items()):
        claimed = expected.get(abi)
        if claimed is None:
            if strict_coverage:
                problems.append(
                    "%s: %s ships for ABI %s (%s) but the constant carries no entry for it, "
                    "so NativeLibraryVerifier finds no registered hash on that device and "
                    "degrades to signature-only."
                    % (const_name, ENGINES[const_name], abi, source_label)
                )
            continue
        if claimed != actual_hash:
            problems.append(
                "%s: ABI %s baked %s but the %s artifact hashes to %s. The baked value cannot "
                "match on device -- most likely the hash was taken from merge<Variant>NativeLibs "
                "instead of strip<Variant>DebugSymbols."
                % (const_name, abi, claimed, source_label, actual_hash)
            )
    return problems


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--app-dir", default="app")
    args = ap.parse_args()
    app_dir = args.app_dir

    build_config = find_build_config(app_dir)
    if build_config is None:
        return fail(
            "no release BuildConfig.java under %s/build/generated/source/buildConfig/release. "
            "Run a task that generates it (e.g. :app:generateReleaseBuildConfig) first." % app_dir
        )
    print("BuildConfig: %s" % build_config)

    constants = parse_constants(build_config)
    problems = []
    parsed = {}
    for const_name in sorted(ENGINES):
        raw = constants.get(const_name)
        if raw is None:
            problems.append("%s is absent from the generated BuildConfig entirely." % const_name)
            continue
        if not raw.strip():
            problems.append(
                "%s is BLANK. NativeLibraryVerifier takes its no-registered-hash branch and the "
                "engine-integrity check degrades to the signature check." % const_name
            )
            continue
        entries, err = parse_entries(const_name, raw)
        if err:
            problems.append(err)
            continue
        parsed[const_name] = entries

    if problems:
        for p in problems:
            print("FAIL: %s" % p, file=sys.stderr)
        return 1

    # An APK is the ground truth when one has been built; otherwise the strip
    # stage, which is what packaging copies from.
    apk = find_release_apk(app_dir)
    stripped = find_stripped_lib_dir(app_dir)
    if apk is None and stripped is None:
        return fail(
            "neither a release APK nor stripped_native_libs was found under %s/build. There is "
            "nothing to check the baked hashes against, and an unchecked hash is exactly the "
            "failure this script exists to catch." % app_dir
        )

    checked = 0
    if stripped is not None:
        print("Stripped libs: %s" % stripped)
        for const_name, so_name in sorted(ENGINES.items()):
            actual = actual_from_stripped(stripped, so_name)
            # Not strict on coverage here: the strip stage still carries ABIs
            # that `abiFilters` drops before packaging, so an ABI present here
            # and absent from the constant is not necessarily shipped.
            problems += compare("stripped", const_name, parsed[const_name], actual, False)
            checked += len(actual)

    if apk is not None:
        print("Release APK: %s" % apk)
        for const_name, so_name in sorted(ENGINES.items()):
            actual = actual_from_apk(apk, so_name)
            # Strict here: whatever is inside the APK is what a device runs.
            problems += compare("APK", const_name, parsed[const_name], actual, True)
            checked += len(actual)

    if problems:
        for p in problems:
            print("FAIL: %s" % p, file=sys.stderr)
        return 1

    if checked == 0:
        return fail("no engine .so files were located, so nothing was actually verified.")

    print("OK: %d shipped .so files match their baked NATIVE_HASH_* entries." % checked)
    return 0


if __name__ == "__main__":
    sys.exit(main())
