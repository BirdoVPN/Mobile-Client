#!/usr/bin/env python3
"""Assert the release and Apple-CI gates are wired (MR-2102, MR-2060, MR-1802).

The release workflows run only on tags and dispatches, so a gate dropped from
one of them would first be noticed on release day, by its absence. This reads
the workflow files on every pull request (the required "Lint Check" job) and
fails if any of these stops holding:

  * every actions/setup-go step in the Apple workflows reads iosApp/.go-version,
    and that file holds one exact Go version (no `stable`, no `1.x`);
  * macos.yml's upload needs a unit-test job, as ios.yml's TestFlight upload
    needs test-ios, and both test jobs run the XCTest bundle and assert with
    scripts/check_xctest_suites_ran.sh that every suite in its sources ran;
  * a workflow that can read the App Store Connect key installs Python
    packages only with --require-hashes from a hashed requirements file;
  * fdroid.yml collects the same releases its guard admits: no drafts and no
    prereleases;
  * android.yml's draft release targets the built commit and is a prerelease
    on purpose (`prerelease: true`, not an expression);
  * apple-ci.yml and codeql-swift.yml have no workflow-level `paths:` filter (a
    required check that never starts blocks the merge); each job but `changes`
    needs `changes` and skips only when it positively said 'false';
  * the certificate pin expiry is checked by scripts/check_pin_expiry.sh, in
    both android-ci.yml (pull requests) and android.yml (the Play release), and
    no workflow parses the pin-set expiration itself or says the app will "fail
    to connect" (MR-1669: past the date Android stops enforcing the pin-set).

Plain text, standard library only: the files are this repo's own, and a
formatting change that breaks a pattern fails here loudly, not silently.

Usage: scripts/check_release_workflow_gates.py [repo-root]
"""
import os
import re
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
WF = os.path.join(ROOT, ".github", "workflows")
APPLE_WORKFLOWS = ["ios.yml", "macos.yml", "apple-ci.yml", "codeql-swift.yml"]
GO_PIN = "iosApp/.go-version"
ASC_KEY = "secrets.APPSTORE_API_KEY_BASE64"
PIN_SCRIPT = "scripts/check_pin_expiry.sh"
PIN_RUN = f"run: bash {PIN_SCRIPT} app/src/main/res/xml/network_security_config.xml"

failures = []


def fail(msg):
    failures.append(msg)


def read(path):
    try:
        with open(path, encoding="utf-8") as fh:
            return fh.read()
    except OSError as exc:
        fail(f"cannot read {path}: {exc}")
        return ""


def top_level(text, key):
    """The lines of a top-level YAML key's block (`key:` up to the next column-0 key)."""
    m = re.search(rf"^{re.escape(key)}:.*?(?=^[A-Za-z_]|\Z)", text, re.M | re.S)
    return m.group(0) if m else ""


def jobs_of(text):
    """{job id: block text} for the jobs: map (job ids sit at two spaces)."""
    body = top_level(text, "jobs")
    out = {}
    for m in re.finditer(r"^  ([A-Za-z0-9_-]+):\s*$(.*?)(?=^  [A-Za-z0-9_#-]|\Z)", body, re.M | re.S):
        out[m.group(1)] = m.group(2)
    return out


def needs_of(block):
    m = re.search(r"^    needs:\s*(\[[^\]]*\]|\S+)", block, re.M)
    if not m:
        return []
    return [n.strip() for n in m.group(1).strip("[]").split(",") if n.strip()]


# -- Go pinned to one exact version --------------------------------------------
pin = read(os.path.join(ROOT, GO_PIN)).strip()
if not re.fullmatch(r"\d+\.\d+\.\d+", pin):
    fail(f"{GO_PIN} must hold one exact Go version (e.g. 1.27.2), found {pin!r}")
for name in APPLE_WORKFLOWS:
    text = read(os.path.join(WF, name))
    steps = re.findall(r"uses: actions/setup-go@[^\n]*\n((?:[ \t]+[^\n]*\n)*)", text)
    if not steps:
        fail(f"{name}: no actions/setup-go step found (the libwg-go build needs Go)")
    for i, step in enumerate(steps, 1):
        with_block = step.split("- ")[0]
        if f"go-version-file: {GO_PIN}" not in with_block or re.search(r"\bgo-version:", with_block):
            fail(f"{name}: setup-go step {i} must read go-version-file: {GO_PIN} and nothing else")

# -- The Mac App Store upload needs the tests ----------------------------------
for name, upload, test in [("macos.yml", "release-macos", "unit-tests"), ("ios.yml", "release-ios", "test-ios")]:
    jobs = jobs_of(read(os.path.join(WF, name)))
    if upload not in jobs:
        fail(f"{name}: no {upload} job")
        continue
    if test not in needs_of(jobs[upload]):
        fail(f"{name}: {upload} must need {test}; it needs {needs_of(jobs[upload])}")
    block = jobs.get(test, "")
    if "xcodebuild test" not in block or "-only-testing:BirdoVPNTests" not in block:
        fail(f"{name}: {test} must run `xcodebuild test -only-testing:BirdoVPNTests`")
    if "continue-on-error" in block:
        fail(f"{name}: {test} must not be continue-on-error")
    if "scripts/check_xctest_suites_ran.sh" not in block:
        fail(f"{name}: {test} must assert the suites ran (scripts/check_xctest_suites_ran.sh)")

# -- No floating Python packages where the App Store Connect key is ------------
for name in sorted(os.listdir(WF)):
    if not name.endswith((".yml", ".yaml")):
        continue
    text = read(os.path.join(WF, name))
    if ASC_KEY not in text:
        continue
    for cmd in re.findall(r"pip3? install(?:[^\n]*\\\n)*[^\n]*", text):
        flat = cmd.replace("\\\n", " ")
        m = re.search(r"-r\s+(\S+)", flat)
        if "--require-hashes" not in flat or not m:
            fail(f"{name}: holds {ASC_KEY} but runs `{flat.strip()}` without --require-hashes -r <file>")
            continue
        req = read(os.path.join(ROOT, m.group(1)))
        for entry in re.split(r"(?<!\\)\n", req):
            entry = entry.strip()
            if not entry or entry.startswith("#"):
                continue
            if not re.match(r"[A-Za-z0-9._-]+==[^\s\\]+", entry) or "--hash=sha256:" not in entry:
                fail(f"{m.group(1)}: every entry needs name==version and --hash=sha256:, got {entry.splitlines()[0]!r}")

# -- F-Droid indexes what its guard admits -------------------------------------
fd = read(os.path.join(WF, "fdroid.yml"))
if "github.event.release.prerelease == false" not in fd:
    fail("fdroid.yml: the build job's guard must skip prereleases")
if not re.search(r"select\(\.isDraft==false and \.isPrerelease==false\)", fd):
    fail("fdroid.yml: 'Collect release APKs' must select isDraft==false and isPrerelease==false, like the guard")

# -- The Android draft release ---------------------------------------------------
draft = jobs_of(read(os.path.join(WF, "android.yml"))).get("draft-release", "")
if "target_commitish: ${{ github.sha }}" not in draft:
    fail("android.yml: draft-release must set target_commitish: ${{ github.sha }}")
if not re.search(r"^\s+prerelease: true\s*$", draft, re.M):
    fail("android.yml: draft-release must say `prerelease: true` (owner decision 2026-10-01), not an expression")

# -- One certificate pin expiry gate (MR-1669) -----------------------------------
# Two copies once drifted: android-ci.yml's moved to the script while the release
# pipeline's inline step kept failing only after expiry, saying the app "will
# fail to connect".
pin_gates = {}
for name in sorted(os.listdir(WF)):
    if not name.endswith((".yml", ".yaml")):
        continue
    text = read(os.path.join(WF, name))
    if re.search(r"fails? to connect", text, re.I):
        fail(f"{name}: says the app will fail to connect at pin expiry; past it Android stops enforcing the pin-set (MR-1669)")
    if "expiration=" in text:
        fail(f"{name}: parses the pin-set expiration itself; only {PIN_SCRIPT} may (MR-1669)")
    # A step is its `- name:` line plus every following line indented past
    # the dash (blank lines included).
    for m in re.finditer(r"^( *)- name: ([^\n]*pin expir[^\n]*)\n((?:\1  [^\n]*\n|[ \t]*\n)*)", text, re.I | re.M):
        if not re.search(rf"^\s+{re.escape(PIN_RUN)}\s*$", m.group(3), re.M):
            fail(f"{name}: step '{m.group(2).strip()}' must be exactly `{PIN_RUN}`")
        pin_gates[name] = pin_gates.get(name, 0) + 1
for name in ("android-ci.yml", "android.yml"):
    if not pin_gates.get(name):
        fail(f"{name}: no 'Check certificate pin expiry' step running {PIN_SCRIPT}")

# -- Apple CI and CodeQL Swift can be required checks ----------------------------
for name, output in [("apple-ci.yml", "apple"), ("codeql-swift.yml", "swift")]:
    text = read(os.path.join(WF, name))
    on_block = top_level(text, "on")
    if re.search(r"^\s+paths(-ignore)?:", on_block, re.M):
        fail(f"{name}: workflow-level paths filter under `on:`; move it into the `changes` job")
    jobs = jobs_of(text)
    if "changes" not in jobs or f"outputs.{output}" not in jobs["changes"]:
        fail(f"{name}: needs a `changes` job with output `{output}`")
    for job, block in jobs.items():
        if job == "changes":
            continue
        if "changes" not in needs_of(block):
            fail(f"{name}: job {job} must need `changes`")
        if f"needs.changes.outputs.{output} != 'false'" not in block or "!cancelled()" not in block:
            fail(f"{name}: job {job} must run unless changes said '{output}' is 'false' (fail open)")

if failures:
    for f in failures:
        print(f"::error::check_release_workflow_gates: {f}")
    sys.exit(1)
print("check_release_workflow_gates: Go pinned to", pin, "in", ", ".join(APPLE_WORKFLOWS) + ";",
      "uploads gated on tests; hashed pip where the ASC key is; F-Droid and draft-release consistent;",
      "one pin expiry gate in", ", ".join(sorted(pin_gates)) + ";",
      "Apple CI and CodeQL Swift start on every pull request")
