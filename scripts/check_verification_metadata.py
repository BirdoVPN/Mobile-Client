#!/usr/bin/env python3
"""Checksum guard for gradle/verification-metadata.xml (MR-1794).

gradle-verification-metadata.yml rewrites the file from fresh downloads and
fails a pull request whose committed copy is not byte for byte that write.
That proves the file is COMPLETE, but a regenerated file is also exactly how a
substituted artifact would arrive: the repository serves different bytes, the
writer records them, and the PR commits them. So this compares a candidate
file with the base branch's and fails on the two changes that are never a
routine dependency update:

  * a CHANGED checksum for a coordinate the base already lists
    (group:name:version and file name), printed as
    ::error::CHANGED CHECKSUM g:n:v file
  * any change to <configuration> (verify-metadata, verify-signatures, the
    trusted artifacts, keys), printed as ::error::CONFIGURATION CHANGED

Added and removed coordinates (a version bump) are listed, not failed.

Usage:
  check_verification_metadata.py --base BASE.xml --file CANDIDATE.xml [--label TEXT]

A missing BASE.xml (the branch that introduces the file) is reported and
passes: there is nothing to compare against yet.
Self-test: python3 scripts/tests/check_verification_metadata_test.py
"""
from __future__ import annotations

import argparse
import difflib
import os
import sys
import xml.etree.ElementTree as ET

NS = "{https://schema.gradle.org/dependency-verification}"
CHECKSUM_KINDS = ("md5", "sha1", "sha256", "sha512")

Key = tuple[str, str, str, str]


def _local(tag: str) -> str:
    return tag[len(NS):] if tag.startswith(NS) else tag


def load(path: str) -> tuple[str, dict[Key, frozenset[tuple[str, str]]]]:
    """(canonical <configuration>, {(group, name, version, file): checksums})."""
    root = ET.parse(path).getroot()
    if _local(root.tag) != "verification-metadata":
        raise ValueError(f"{path}: root element is <{_local(root.tag)}>, not <verification-metadata>")

    config = root.find(f"{NS}configuration")
    config_text = "" if config is None else ET.canonicalize(ET.tostring(config, encoding="unicode"), strip_text=True)

    entries: dict[Key, frozenset[tuple[str, str]]] = {}
    components = root.find(f"{NS}components")
    for component in ([] if components is None else components):
        if _local(component.tag) != "component":
            continue
        gnv = (component.get("group", ""), component.get("name", ""), component.get("version", ""))
        for artifact in component:
            if _local(artifact.tag) != "artifact":
                continue
            sums = set()
            for checksum in artifact:
                kind = _local(checksum.tag)
                if kind not in CHECKSUM_KINDS:
                    continue
                sums.add((kind, checksum.get("value", "")))
                for also in checksum:
                    if _local(also.tag) == "also-trust":
                        sums.add((kind, also.get("value", "")))
            entries[gnv + (artifact.get("name", ""),)] = frozenset(sums)
    return config_text, entries


def _fmt(sums: frozenset[tuple[str, str]]) -> str:
    return ", ".join(f"{kind}={value}" for kind, value in sorted(sums)) or "(none)"


def compare(base_path: str, file_path: str, label: str) -> list[str]:
    """The ::error:: lines for file_path against base_path (empty = pass)."""
    base_config, base = load(base_path)
    config, entries = load(file_path)
    errors: list[str] = []

    if config != base_config:
        diff = "\n".join(difflib.unified_diff(
            base_config.replace("><", ">\n<").splitlines(),
            config.replace("><", ">\n<").splitlines(),
            "base", label, lineterm=""))
        print(diff)
        errors.append(f"::error::CONFIGURATION CHANGED in {label}: <configuration> differs from the base branch's "
                      "(verify-metadata, verify-signatures, trusted artifacts or keys). That is a change to the "
                      "control itself, never part of a dependency update.")

    for key in sorted(set(base) & set(entries)):
        if base[key] != entries[key]:
            group, name, version, filename = key
            errors.append(f"::error::CHANGED CHECKSUM {group}:{name}:{version} {filename} in {label} "
                          f"(base: {_fmt(base[key])}; now: {_fmt(entries[key])}). The same coordinate now has "
                          "different bytes: investigate the repository, do not commit this.")

    added = sorted({k[:3] for k in entries} - {k[:3] for k in base})
    removed = sorted({k[:3] for k in base} - {k[:3] for k in entries})
    print(f"{label}: {len(entries)} files checked against the base's {len(base)}; "
          f"{len(added)} component version(s) added, {len(removed)} removed.")
    for gnv in added:
        print("  + " + ":".join(gnv))
    for gnv in removed:
        print("  - " + ":".join(gnv))
    return errors


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base", required=True, help="the base branch's verification-metadata.xml")
    parser.add_argument("--file", required=True, help="the candidate verification-metadata.xml")
    parser.add_argument("--label", default=None, help="name for the candidate in messages")
    args = parser.parse_args(argv)
    label = args.label or args.file

    if not os.path.isfile(args.file):
        print(f"::error::{args.file} does not exist")
        return 1
    if not os.path.isfile(args.base):
        print(f"{label}: the base branch has no verification-metadata.xml yet (this change introduces it); "
              "nothing to compare against.")
        return 0

    errors = compare(args.base, args.file, label)
    for line in errors:
        print(line)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
