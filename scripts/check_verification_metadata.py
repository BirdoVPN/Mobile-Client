#!/usr/bin/env python3
"""Checksum guard for gradle/verification-metadata.xml (MR-1794, MR-2435).

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

Added and removed coordinates (a version bump) are listed, not failed, and so
is a NEW file under a version the base already lists (another classifier, POM
or module file of the same coordinates): routine when a task starts resolving
it, worth a look when nothing else changed.

READ AS GRADLE READS IT (MR-2435). Gradle's DependencyVerificationsXmlReader
parses with SAX namespace processing OFF and matches element names exactly as
written, and its builder MERGES every copy of a component or artifact: a
second <artifact> for a file the first already lists adds its checksum as an
alternative Gradle accepts, like <also-trust>. So this reads raw names the
same way (expat, no namespace processing), merges each artifact's checksums
into one set before comparing, and fails on the shapes Gradle accepts but
never writes, which exist only to hide something from a reader:

  * ::error::DUPLICATE COMPONENT / DUPLICATE ARTIFACT: listed twice.
  * ::error::MORE THAN ONE <configuration> (Gradle applies each one, wherever
    it is) or MORE THAN ONE <components>.
  * ::error::FOREIGN NAMESPACE: an xmlns on any element but the root, a root
    namespace that is not Gradle's, or a prefixed name. Gradle still reads
    <configuration xmlns="urn:x"> as the configuration.
  * ::error::UNEXPECTED SHAPE: an element where Gradle never writes it, text
    where it writes none, or a comment, CDATA section or processing
    instruction inside the root. Gradle reads verify-metadata's text one SAX
    chunk at a time, so " true " or "tr&#117;e" reads as false where a
    whitespace-stripping reader sees "true"; the configuration's text is
    therefore compared exactly as written, chunk by chunk.
  * A DOCTYPE is refused outright (Gradle's reader refuses it too).

Usage:
  check_verification_metadata.py --base BASE.xml --file CANDIDATE.xml [--label TEXT]

A missing BASE.xml (the branch that introduces the file) is reported and
passes: there is nothing to compare against yet.
On a pull request the workflow runs this file as the BASE commit has it
(MR-2437), so a change here takes effect from the next pull request on.
Self-test: python3 scripts/tests/check_verification_metadata_test.py
"""
from __future__ import annotations

import argparse
import difflib
import os
import sys
import xml.parsers.expat
from xml.sax.saxutils import escape, quoteattr

NS_URI = "https://schema.gradle.org/dependency-verification"
XSI_URI = "http://www.w3.org/2001/XMLSchema-instance"
ROOT = "verification-metadata"
# The root's attributes as Gradle writes them; None = any value.
ROOT_ATTRS = {"xmlns": NS_URI, "xmlns:xsi": XSI_URI, "xsi:schemaLocation": None}
CHECKSUM_KINDS = ("md5", "sha1", "sha256", "sha512")

# Where Gradle writes each element it reads outside <configuration>. Inside
# <configuration> any other element may appear: that subtree is compared whole.
PARENTS = {
    "configuration": {ROOT},
    "components": {ROOT},
    "component": {"components"},
    "artifact": {"component"},
    **{kind: {"artifact"} for kind in CHECKSUM_KINDS},
    "also-trust": set(CHECKSUM_KINDS),
    "pgp": {"artifact"},
    "ignored-keys": {"artifact", "configuration"},
    "ignored-key": {"ignored-keys"},
}

Key = tuple[str, str, str, str]


class _Node:
    """An element inside <configuration>: its name, attributes and content in
    document order (child _Nodes, and each character-data chunk as expat
    delivered it)."""

    def __init__(self, name: str, attrs: dict[str, str]):
        self.name = name
        self.attrs = attrs
        self.content: list[_Node | str] = []


def _canonical(node: _Node) -> str:
    """Attribute order and the whitespace between elements do not matter;
    text inside an element is kept exactly, chunk boundaries included."""
    has_children = any(isinstance(item, _Node) for item in node.content)
    parts: list[str] = []
    after_text = False
    for item in node.content:
        if isinstance(item, _Node):
            parts.append(_canonical(item))
            after_text = False
        elif has_children and not item.strip():
            continue
        else:
            if after_text:
                parts.append("<!--split-->")
            parts.append(escape(item))
            after_text = True
    attrs = "".join(f" {k}={quoteattr(v)}" for k, v in sorted(node.attrs.items()))
    return f"<{node.name}{attrs}>{''.join(parts)}</{node.name}>"


class Metadata:
    """One verification-metadata.xml, read the way Gradle applies it.

    .config   canonical text of every <configuration>
    .entries  {(group, name, version, file): every checksum Gradle accepts}
    .problems ::error:: lines for the shapes Gradle never writes
    """

    def __init__(self, path: str, label: str):
        self.label = label
        self.problems: list[str] = []
        self.entries: dict[Key, set[tuple[str, str]]] = {}
        self._configs: list[_Node] = []
        self._open: list[_Node] = []  # open elements inside a <configuration>
        self._stack: list[str] = []
        self._counts: dict[str, int] = {}
        self._components: dict[tuple[str, str, str], int] = {}
        self._component: tuple[str, str, str] | None = None
        self._artifact: Key | None = None
        self._kind: str | None = None

        parser = xml.parsers.expat.ParserCreate()  # no namespace separator: raw names, as Gradle reads them
        parser.buffer_text = False  # one chunk per token, as SAX delivers them
        parser.StartDoctypeDeclHandler = self._doctype
        parser.StartElementHandler = self._start
        parser.EndElementHandler = self._end
        parser.CharacterDataHandler = self._text
        parser.CommentHandler = lambda _data: self._inside_root("a comment")
        parser.StartCdataSectionHandler = lambda: self._inside_root("a CDATA section")
        parser.ProcessingInstructionHandler = lambda target, _data: self._problem(
            "UNEXPECTED SHAPE", f"a processing instruction <?{target}?>, which Gradle never writes")
        self._parser = parser
        with open(path, "rb") as f:
            data = f.read()
        try:
            parser.Parse(data, True)
        except xml.parsers.expat.ExpatError as e:
            raise ValueError(f"{label}: not well-formed XML ({e})") from None

    @property
    def config(self) -> str:
        return "".join(_canonical(node) for node in self._configs)

    def _problem(self, kind: str, message: str) -> None:
        self.problems.append(f"::error::{kind} in {self.label} line {self._parser.CurrentLineNumber}: {message}")

    def _inside_root(self, what: str) -> None:
        if self._stack:
            self._problem("UNEXPECTED SHAPE", f"{what} inside <{ROOT}>, where Gradle never writes one")

    def _doctype(self, *_args) -> None:
        raise ValueError(f"{self.label} has a DOCTYPE: Gradle's reader refuses one and Gradle never writes one")

    def _start(self, name: str, attrs: dict[str, str]) -> None:
        parent = self._stack[-1] if self._stack else None
        self._counts[name] = self._counts.get(name, 0) + 1
        if parent is None:
            if name != ROOT:
                raise ValueError(f"{self.label}: root element is <{name}>, not <{ROOT}>")
            for attr, value in attrs.items():
                if attr not in ROOT_ATTRS:
                    self._problem("FOREIGN NAMESPACE" if attr.startswith("xmlns") or ":" in attr
                                  else "UNEXPECTED SHAPE", f"<{ROOT}> has the attribute {attr}")
                elif ROOT_ATTRS[attr] is not None and value != ROOT_ATTRS[attr]:
                    self._problem("FOREIGN NAMESPACE",
                                  f"<{ROOT}> {attr}={quoteattr(value)}, not {quoteattr(ROOT_ATTRS[attr])}")
            if "xmlns" not in attrs:
                self._problem("FOREIGN NAMESPACE", f"<{ROOT}> does not declare {NS_URI}")
        else:
            if ":" in name:
                self._problem("FOREIGN NAMESPACE", f"<{name}> has a namespace prefix; Gradle reads names as "
                              f"written, so this is not the <{name.split(':', 1)[1]}> it looks like")
            for attr in attrs:
                if attr == "xmlns" or attr.startswith("xmlns:"):
                    self._problem("FOREIGN NAMESPACE", f"<{name}> declares {attr}={quoteattr(attrs[attr])}; "
                                  "Gradle ignores namespaces and reads the element by its name alone")
                elif ":" in attr:
                    self._problem("FOREIGN NAMESPACE", f"<{name}> has the prefixed attribute {attr}")
            if name in PARENTS:
                if parent not in PARENTS[name]:
                    self._problem("UNEXPECTED SHAPE", f"<{name}> inside <{parent}>; Gradle writes it only inside "
                                  + " or ".join(f"<{p}>" for p in sorted(PARENTS[name])))
            elif not self._open:
                self._problem("UNEXPECTED SHAPE", f"<{name}> inside <{parent}>, outside <configuration>")

        if name in ("configuration", "components") and self._counts[name] > 1:
            self._problem(f"MORE THAN ONE <{name}>", f"a second <{name}>; Gradle applies every one, so a "
                          "comparison of the first alone would miss what the others do")

        if name == "configuration" or self._open:
            node = _Node(name, dict(attrs))
            if self._open:
                self._open[-1].content.append(node)
            else:
                self._configs.append(node)
            self._open.append(node)

        # What Gradle's reader does with the same element.
        if name == "component":
            gnv = (attrs.get("group", ""), attrs.get("name", ""), attrs.get("version", ""))
            if gnv in self._components:
                self._problem("DUPLICATE COMPONENT", f"{':'.join(gnv)} is already listed at line "
                              f"{self._components[gnv]}; Gradle merges the copies, so a checksum in "
                              "either one is accepted")
            else:
                self._components[gnv] = self._parser.CurrentLineNumber
            self._component = gnv
        elif name == "artifact" and self._component is not None:
            key = self._component + (attrs.get("name", ""),)
            if key in self.entries:
                self._problem("DUPLICATE ARTIFACT", f"{':'.join(key[:3])} {key[3]} is listed twice; Gradle "
                              "merges the copies, so a checksum in either one is accepted")
            self.entries.setdefault(key, set())
            self._artifact = key
            self._kind = None
        elif self._artifact is not None:
            sums = self.entries[self._artifact]
            if name == "also-trust" and self._kind is not None:
                sums.add((self._kind, attrs.get("value", "")))
            elif name in CHECKSUM_KINDS:
                self._kind = name
                sums.add((name, attrs.get("value", "")))
            elif name == "pgp":
                sums.add(("pgp", attrs.get("value", "")))
            elif name == "ignored-key":
                sums.add(("ignored-key", attrs.get("id", "")))
        self._stack.append(name)

    def _end(self, name: str) -> None:
        self._stack.pop()
        if self._open:
            self._open.pop()
        if name == "component":
            self._component = None
        elif name == "artifact":
            self._artifact = None
            self._kind = None

    def _text(self, data: str) -> None:
        if self._open:
            self._open[-1].content.append(data)
        elif data.strip() and self._stack:
            self._problem("UNEXPECTED SHAPE", f"text inside <{self._stack[-1]}>, where Gradle writes none")


def _fmt(sums: frozenset[tuple[str, str]]) -> str:
    return ", ".join(f"{kind}={value}" for kind, value in sorted(sums)) or "(none)"


def compare(base_path: str, file_path: str, label: str) -> list[str]:
    """The ::error:: lines for file_path against base_path (empty = pass)."""
    base_meta = Metadata(base_path, "the base branch's file")
    meta = Metadata(file_path, label)
    errors: list[str] = base_meta.problems + meta.problems
    base = {k: frozenset(v) for k, v in base_meta.entries.items()}
    entries = {k: frozenset(v) for k, v in meta.entries.items()}

    if meta.config != base_meta.config:
        diff = "\n".join(difflib.unified_diff(
            base_meta.config.replace("><", ">\n<").splitlines(),
            meta.config.replace("><", ">\n<").splitlines(),
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

    base_versions = {k[:3] for k in base}
    versions = {k[:3] for k in entries}
    added = sorted(versions - base_versions)
    removed = sorted(base_versions - versions)
    new_files = sorted(k for k in set(entries) - set(base) if k[:3] in base_versions)
    print(f"{label}: {len(entries)} files checked against the base's {len(base)}; "
          f"{len(added)} component version(s) added, {len(removed)} removed, "
          f"{len(new_files)} new file(s) under a version the base already lists.")
    for gnv in added:
        print("  + " + ":".join(gnv))
    for gnv in removed:
        print("  - " + ":".join(gnv))
    for key in new_files:
        print(f"  + {':'.join(key[:3])} {key[3]} (new file under an existing version)")
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

    try:
        errors = compare(args.base, args.file, label)
    except ValueError as e:
        print(f"::error::{e}")
        return 1
    for line in errors:
        print(line)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
