#!/usr/bin/env python3
"""Gradle dependency verification stays ON (MR-2434).

gradle/verification-metadata.xml (MR-1794) makes every Gradle build refuse an
artifact, POM or module file whose SHA-256 it does not list. One line turns
that off, and before this check none of the required checks would notice:

  * gradle/verification-metadata.xml deleted (nothing to verify against);
  * org.gradle.dependency.verification set (lenient or off) in any
    .properties file, -Dorg.gradle.dependency.verification on a command line
    or in GRADLE_OPTS;
  * --dependency-verification (or its short form -F) on a Gradle command;
  * disableDependencyVerification() on a configuration, or the start
    parameter's dependencyVerificationMode set by a build or init script;
  * --write-verification-metadata (or -M) outside the workflow that writes
    the file: a writing build records what it downloads instead of checking.

Every tracked file is read except Markdown (prose; nothing runs it) and this
check's own two files. Whole-line comments are skipped ('#' in YAML, shell,
Python, TOML and extension-less files; '//' in Kotlin, Groovy and Java), so
the workflows can say which flags they avoid. A .properties file is read as
java.util.Properties reads it: continuation lines joined, escapes decoded.
Other Gradle properties, such as #646's systemProp.org.gradle.internal.
repository.* retry settings, are not touched.

ONE exception, matched to the exact line: baseline-profile.yml records the
monthly profile with --dependency-verification lenient, because it runs a
Gradle Managed Device that the writer workflow cannot run (see that file and
BUILD-INSTRUCTIONS.md). Its cache is read-only and nothing it builds ships.

Android CI's Lint Check (a required check) runs this file as the pull
request's BASE commit has it, as well as the pull request's own copy (MR-2437's
reasoning): a pull request cannot loosen the check it is judged by, and one
that tightens it is held to the tighter rule at once. A change to the allowed
line therefore lands in a pull request of its own first.

Usage: check_dependency_verification_on.py [--root DIR]   (DIR: the repository; default .)
Self-test: python3 scripts/tests/check_dependency_verification_on_test.py
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys

METADATA = "gradle/verification-metadata.xml"
WRITER = ".github/workflows/gradle-verification-metadata.yml"
OWN_FILES = {
    "scripts/check_dependency_verification_on.py",
    "scripts/tests/check_dependency_verification_on_test.py",
}

# (path, the line with surrounding whitespace removed): the only places
# --dependency-verification may appear, each at most once.
ALLOWED_MODE_LINES = {
    (".github/workflows/baseline-profile.yml",
     "./gradlew :app:generateReleaseBaselineProfile --no-daemon --dependency-verification lenient \\"),
}

PROPERTY = re.compile(r"org\.gradle\.dependency\.verification(?![\w.])")
MODE_FLAG = re.compile(r"--dependency-verification\b")
WRITE_FLAG = re.compile(r"--write-verification-metadata\b")
API = re.compile(r"disableDependencyVerification|[Dd]ependencyVerificationMode")
GRADLE_COMMAND = re.compile(r"(?:^|[\s(\"'])(?:\S*/)?gradlew?(?:\.bat)?[\"']?(?=\s|$)")
SHORT_FLAG = re.compile(r"(?<![^\s\"'])-[FM]")
SEGMENT_SPLIT = re.compile(r"\|\||&&|[|;&]")

HASH_COMMENTS = {".yml", ".yaml", ".sh", ".bash", ".py", ".toml", ".cfg", ".ini", ".conf", ""}
SLASH_COMMENTS = {".kts", ".gradle", ".kt", ".java", ".groovy"}


def tracked_files(root: str) -> list[str]:
    """Repository-relative paths: git's index in a checkout, a walk otherwise."""
    if os.path.exists(os.path.join(root, ".git")):
        out = subprocess.run(["git", "-C", root, "ls-files", "-z"], check=True, capture_output=True).stdout
        return [p for p in out.decode("utf-8", "surrogateescape").split("\0") if p]
    paths = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d != ".git"]
        for name in filenames:
            paths.append(os.path.relpath(os.path.join(dirpath, name), root).replace(os.sep, "/"))
    return sorted(paths)


def _properties_unescape(text: str) -> str:
    out, i = [], 0
    while i < len(text):
        c = text[i]
        if c == "\\" and i + 1 < len(text):
            nxt = text[i + 1]
            if nxt == "u" and re.fullmatch(r"[0-9a-fA-F]{4}", text[i + 2:i + 6]):
                out.append(chr(int(text[i + 2:i + 6], 16)))
                i += 6
                continue
            out.append({"t": "\t", "n": "\n", "r": "\r", "f": "\f"}.get(nxt, nxt))
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def properties_entries(text: str) -> list[tuple[int, str]]:
    """(first line number, decoded 'key=value' text) per entry, as
    java.util.Properties.load reads them."""
    lines = re.split(r"\r\n|\r|\n", text)
    entries, i = [], 0
    while i < len(lines):
        number, line = i + 1, lines[i].lstrip(" \t\f")
        i += 1
        if not line or line[0] in "#!":
            continue
        # An odd number of trailing backslashes continues the entry; the next
        # line's leading whitespace is dropped, and it is never a comment.
        while (len(line) - len(line.rstrip("\\"))) % 2 == 1:
            line = line[:-1]
            if i >= len(lines):
                break
            line += lines[i].lstrip(" \t\f")
            i += 1
        entries.append((number, _properties_unescape(line)))
    return entries


def _code_lines(path: str, text: str) -> list[tuple[int, str]]:
    """(line number, line) for every line that is not a whole-line comment."""
    if path.endswith(".properties"):
        return properties_entries(text)
    suffix = os.path.splitext(os.path.basename(path))[1].lower()
    marker = "#" if suffix in HASH_COMMENTS else "//" if suffix in SLASH_COMMENTS else None
    return [(n, line) for n, line in enumerate(text.splitlines(), 1)
            if marker is None or not line.lstrip().startswith(marker)]


def _logical_commands(lines: list[tuple[int, str]]) -> list[tuple[int, str]]:
    """Backslash-continued shell lines joined into one command line."""
    joined: list[tuple[int, str]] = []
    pending: tuple[int, str] | None = None
    for number, line in lines:
        if pending is not None:
            number, line = pending[0], pending[1] + " " + line.strip()
        stripped = line.rstrip()
        if stripped.endswith("\\"):
            pending = (number, stripped[:-1])
        else:
            pending = None
            joined.append((number, line))
    if pending is not None:
        joined.append(pending)
    return joined


def check(root: str) -> tuple[list[str], int]:
    """(::error:: lines, number of files read)."""
    errors: list[str] = []

    def error(path: str, number: int, message: str) -> None:
        errors.append(f"::error file={path},line={number}::{path}:{number} {message}. Dependency verification "
                      "(MR-1794) stays on in every build; see scripts/check_dependency_verification_on.py.")

    if not os.path.isfile(os.path.join(root, METADATA)):
        errors.append(f"::error file={METADATA}::{METADATA} is missing. Without it Gradle verifies nothing; "
                      "restore it (gradle-verification-metadata.yml writes it).")

    allowed_seen: set[tuple[str, str]] = set()
    read = 0
    for path in tracked_files(root):
        if path in OWN_FILES or path.lower().endswith(".md"):
            continue
        full = os.path.join(root, path)
        if not os.path.isfile(full):
            continue
        with open(full, "rb") as f:
            data = f.read()
        if b"\0" in data[:8192]:
            continue
        read += 1
        text = data.decode("latin-1" if path.endswith(".properties") else "utf-8", "replace")
        lines = _code_lines(path, text)
        for number, line in lines:
            if PROPERTY.search(line):
                error(path, number, "sets org.gradle.dependency.verification")
            if API.search(line):
                error(path, number, "turns dependency verification off from a script "
                      "(disableDependencyVerification / dependencyVerificationMode)")
            if WRITE_FLAG.search(line) and path != WRITER:
                error(path, number, f"passes --write-verification-metadata outside {WRITER}: a writing build "
                      "records what it downloads instead of checking it")
            if MODE_FLAG.search(line):
                entry = (path, line.strip())
                if entry in ALLOWED_MODE_LINES and entry not in allowed_seen:
                    allowed_seen.add(entry)
                else:
                    error(path, number, "passes --dependency-verification (only baseline-profile.yml's "
                          "recording line may, and only once)")
        for number, command in _logical_commands(lines):
            for segment in SEGMENT_SPLIT.split(command):
                if GRADLE_COMMAND.search(segment) and SHORT_FLAG.search(segment):
                    error(path, number, "passes -F (--dependency-verification) or -M "
                          "(--write-verification-metadata) to Gradle")
    return errors, read


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", default=".", help="the repository to check (default: .)")
    args = parser.parse_args(argv)
    errors, read = check(args.root)
    for line in errors:
        print(line)
    if errors:
        return 1
    print(f"ok: {METADATA} is present and none of {read} tracked files turns dependency verification off "
          "(baseline-profile.yml's recording line excepted).")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
