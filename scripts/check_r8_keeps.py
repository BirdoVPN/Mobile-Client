#!/usr/bin/env python3
"""Assert, against the REAL minified DEX, that R8 did not break anything whose
failure would only show up at runtime on a user's device.

Why this exists
---------------
Three of this app's load-bearing contracts are invisible to R8 and invisible to
CI, and all three fail silently in production:

1. ``WgNative`` resolves the entire WireGuard data path by string —
   ``Class.forName("com.wireguard.android.backend.GoBackend")`` then
   ``getDeclaredMethod("wgTurnOn", ...)`` — and ``libwg-go.so`` binds those
   methods by static JNI name mangling. Nothing references them statically, and
   the tunnel AAR ships no consumer ProGuard rules, so one wrong keep removes
   them. When that happens ``init()`` swallows the exception, ``turnOn()``
   returns -1, the kill switch engages, and NOBODY CAN CONNECT — with no
   logcat line, because ``-assumenosideeffects`` strips every ``Log.*`` call
   from release builds.

2. ``RosenpassNative``'s five ``external fun`` are bound the same way. Losing
   them silently downgrades BirdoPQ to the server-provided PSK path.

3. kotlinx.serialization resolves serializers reflectively for every Retrofit
   response body (``getDeclaredField("Companion")`` ->
   ``getDeclaredMethod("serializer", ...)``). A missing keep compiles, passes
   the unit tests — which run on UNMINIFIED classes — and throws only when a
   real API response is parsed on a real device.

Everything here is checked against the shipped bytes, not against the build
configuration, so it stays true regardless of how the rules are written.

It also checks that ``app/src/main/baseline-prof.txt`` has not silently gone
stale: every rule must still match at least one class that survived R8.

Usage
-----
    python scripts/check_r8_keeps.py [ARTIFACT] [--mapping PATH]
                                     [--baseline-profile PATH]
                                     [--source-root DIR ...]
                                     [--no-baseline-profile]

ARTIFACT may be an .apk, an .aab, a directory containing ``*.dex``, or a single
``.dex``. When omitted it is auto-discovered from the usual AGP output
locations, preferring the packaged APK/AAB over intermediates.

Exit status is 0 only when every check passes.
"""

from __future__ import annotations

import argparse
import glob
import os
import re
import struct
import sys
import zipfile

ACC_NATIVE = 0x0100

# --- The contracts ---------------------------------------------------------

# GoBackend: resolved reflectively by WgNative, bound by JNI name mangling.
# wgGetConfig is deliberately NOT required: WgNative treats it as optional and
# degrades to "traffic stats disabled" when it is absent from the AAR.
WIREGUARD_CLASS = "Lcom/wireguard/android/backend/GoBackend;"
WIREGUARD_METHODS = ("wgTurnOn", "wgTurnOff", "wgGetSocketV4", "wgGetSocketV6")

# RosenpassNative: five @JvmStatic external fun, bound by JNI name mangling.
ROSENPASS_CLASS = "Lapp/birdo/vpn/service/RosenpassNative;"
ROSENPASS_NATIVE_METHODS = (
    "nativeVersion",
    "nativeGenerateKeypair",
    "nativeDeriveSharedPsk",
    "nativeEncapsulateForServer",
    "nativePskLength",
)

# scripts/verify_android_release_apk.py scans the DEX string pool for
# SIGNING_CERT_FINGERPRINT; NativeLibraryVerifier reads NATIVE_HASH_*.
BUILDCONFIG_CLASS = "Lapp/birdo/vpn/BuildConfig;"

DEFAULT_SOURCE_ROOTS = ("app/src/main", "shared/src")

DEFAULT_ARTIFACT_GLOBS = (
    "app/build/outputs/apk/release/*.apk",
    "app/build/outputs/bundle/release/*.aab",
    "app/build/intermediates/dex/release/minifyReleaseWithR8",
    "app/build/intermediates/dex/release",
)

DEFAULT_MAPPING = "app/build/outputs/mapping/release/mapping.txt"
# NOT where the shipped profile lives, and deliberately so.
#
# The profile that ships is RECORDED, by :app:generateReleaseBaselineProfile, to
# app/src/release/generated/baselineProfiles/baseline-prof.txt. The per-rule
# check below -- every rule must match a class that survived R8 -- is the right
# check for a HAND-WRITTEN profile and the wrong one for a recorded profile: a
# recording is taken against the nonMinifiedRelease build, so it legitimately
# names classes R8 later removes or inlines, and AGP drops those when it rewrites
# the profile through mapping.txt. Measured on the profile committed with #358:
# 16,547 of 22,696 entries still match a surviving class. Pointing this default
# at it would fail the build on 6,149 entries that are working exactly as
# designed.
#
# So this path stays as it is, and it is still load-bearing: app/src/main/
# baseline-prof.txt is precisely where a future hand-written guess would be
# dropped, and if one ever appears there this gate will hold it to the standard
# a hand-written profile deserves.
#
# What guards the RECORDED profile instead:
#   * app/src/test/.../BaselineProfileIntegrityTest.kt -- shape and scale, so a
#     hand-written file cannot masquerade as a recording;
#   * scripts/verify_android_release_apk.py -- that the compiled profile is
#     actually inside the shipped APK and AAB;
#   * .github/workflows/baseline-profile.yml -- that a fresh recording still
#     matches the committed one, monthly.
DEFAULT_BASELINE_PROFILE = "app/src/main/baseline-prof.txt"


# --- Minimal DEX reader ----------------------------------------------------


def _uleb128(data: bytes, offset: int) -> tuple[int, int]:
    result = 0
    shift = 0
    while True:
        byte = data[offset]
        offset += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, offset
        shift += 7


class Dex:
    """Just enough of the DEX format to answer 'did this survive R8?'."""

    def __init__(self, data: bytes) -> None:
        if len(data) < 0x70 or data[:3] != b"dex":
            raise ValueError("not a DEX file")
        self.data = data
        self._strings: dict[int, str] = {}
        self.string_ids_off = struct.unpack_from("<I", data, 0x3C)[0]
        self.type_ids_off = struct.unpack_from("<I", data, 0x44)[0]
        self.field_ids_off = struct.unpack_from("<I", data, 0x54)[0]
        self.method_ids_off = struct.unpack_from("<I", data, 0x5C)[0]
        self.class_defs_size = struct.unpack_from("<I", data, 0x60)[0]
        self.class_defs_off = struct.unpack_from("<I", data, 0x64)[0]

    def string(self, index: int) -> str:
        cached = self._strings.get(index)
        if cached is not None:
            return cached
        offset = struct.unpack_from("<I", self.data, self.string_ids_off + index * 4)[0]
        _, offset = _uleb128(self.data, offset)
        end = self.data.find(b"\x00", offset)
        # MUTF-8; surrogate handling is irrelevant for identifiers.
        value = self.data[offset:end].decode("utf-8", errors="replace")
        self._strings[index] = value
        return value

    def type_name(self, index: int) -> str:
        return self.string(struct.unpack_from("<I", self.data, self.type_ids_off + index * 4)[0])

    def method_name(self, index: int) -> str:
        return self.string(struct.unpack_from("<I", self.data, self.method_ids_off + index * 8 + 4)[0])

    def field_name(self, index: int) -> str:
        return self.string(struct.unpack_from("<I", self.data, self.field_ids_off + index * 8 + 4)[0])

    def field_type(self, index: int) -> str:
        return self.type_name(struct.unpack_from("<H", self.data, self.field_ids_off + index * 8 + 2)[0])

    def classes(self) -> dict[str, dict]:
        """descriptor -> {'methods': {name: access}, 'fields': {name: (access, type)}}."""
        out: dict[str, dict] = {}
        for i in range(self.class_defs_size):
            base = self.class_defs_off + i * 32
            class_idx = struct.unpack_from("<I", self.data, base)[0]
            class_data_off = struct.unpack_from("<I", self.data, base + 24)[0]
            descriptor = self.type_name(class_idx)
            entry = out.setdefault(descriptor, {"methods": {}, "fields": {}})
            if not class_data_off:
                continue
            offset = class_data_off
            static_fields, offset = _uleb128(self.data, offset)
            instance_fields, offset = _uleb128(self.data, offset)
            direct_methods, offset = _uleb128(self.data, offset)
            virtual_methods, offset = _uleb128(self.data, offset)
            # field_idx_diff / method_idx_diff each restart from 0 at the head
            # of their own list, which is why index is reset per list.
            for count in (static_fields, instance_fields):
                index = 0
                for _ in range(count):
                    diff, offset = _uleb128(self.data, offset)
                    access, offset = _uleb128(self.data, offset)
                    index += diff
                    entry["fields"][self.field_name(index)] = (access, self.field_type(index))
            for count in (direct_methods, virtual_methods):
                index = 0
                for _ in range(count):
                    diff, offset = _uleb128(self.data, offset)
                    access, offset = _uleb128(self.data, offset)
                    _code_off, offset = _uleb128(self.data, offset)
                    index += diff
                    name = self.method_name(index)
                    entry["methods"][name] = entry["methods"].get(name, 0) | access
        return out


def load_classes(artifact: str) -> dict[str, dict]:
    blobs: list[bytes] = []
    if os.path.isdir(artifact):
        for path in sorted(glob.glob(os.path.join(artifact, "**", "*.dex"), recursive=True)):
            with open(path, "rb") as handle:
                blobs.append(handle.read())
    elif artifact.endswith(".dex"):
        with open(artifact, "rb") as handle:
            blobs.append(handle.read())
    elif artifact.endswith((".apk", ".aab", ".zip")):
        with zipfile.ZipFile(artifact) as archive:
            for name in archive.namelist():
                # APK: classes*.dex at the root. AAB: base/dex/classes*.dex.
                if name.endswith(".dex") and os.path.basename(name).startswith("classes"):
                    blobs.append(archive.read(name))
    else:
        raise SystemExit(f"ERROR: don't know how to read '{artifact}'")

    if not blobs:
        raise SystemExit(f"ERROR: no DEX found in '{artifact}'")

    merged: dict[str, dict] = {}
    for blob in blobs:
        for descriptor, entry in Dex(blob).classes().items():
            target = merged.setdefault(descriptor, {"methods": {}, "fields": {}})
            target["methods"].update(entry["methods"])
            target["fields"].update(entry["fields"])
    return merged


def discover_artifact() -> str:
    for pattern in DEFAULT_ARTIFACT_GLOBS:
        if os.path.isdir(pattern):
            if glob.glob(os.path.join(pattern, "**", "*.dex"), recursive=True):
                return pattern
            continue
        matches = sorted(glob.glob(pattern))
        if matches:
            return matches[0]
    raise SystemExit(
        "ERROR: no release artifact found. Run ':app:minifyReleaseWithR8' (or "
        "'assembleRelease'/'bundleRelease') first, or pass the path explicitly."
    )


# --- @Serializable discovery ----------------------------------------------

PACKAGE_RE = re.compile(r"^\s*package\s+([\w.]+)", re.MULTILINE)
# Matches "@Serializable ... <modifiers> class|object Name". The annotation and
# the declaration may be separated by other annotations (@SerialName etc).
SERIALIZABLE_RE = re.compile(
    r"@Serializable\b(?P<between>(?:\s*@[\w.]+(?:\([^)]*\))?)*)\s*"
    r"(?P<modifiers>(?:(?:public|internal|private|data|value|sealed|abstract|open|inner|enum)\s+)*)"
    r"(?P<kind>class|object)\s+(?P<name>[A-Za-z_]\w*)"
)


def find_serializable_classes(roots: list[str]) -> list[tuple[str, str, str]]:
    """Return (fqcn, kind, modifiers) for every @Serializable declaration."""
    found: list[tuple[str, str, str]] = []
    for root in roots:
        if not os.path.isdir(root):
            continue
        for dirpath, _dirnames, filenames in os.walk(root):
            for filename in filenames:
                if not filename.endswith(".kt"):
                    continue
                path = os.path.join(dirpath, filename)
                with open(path, "r", encoding="utf-8") as handle:
                    text = handle.read()
                package_match = PACKAGE_RE.search(text)
                if not package_match:
                    continue
                package = package_match.group(1)
                for match in SERIALIZABLE_RE.finditer(text):
                    found.append(
                        (
                            f"{package}.{match.group('name')}",
                            match.group("kind"),
                            match.group("modifiers"),
                        )
                    )
    return found


def descriptor_of(fqcn: str) -> str:
    return "L" + fqcn.replace(".", "/") + ";"


# --- Baseline-profile freshness -------------------------------------------


def mapping_original_classes(mapping_path: str) -> set[str]:
    """Original (pre-obfuscation) names of every class R8 kept."""
    names: set[str] = set()
    with open(mapping_path, "r", encoding="utf-8") as handle:
        for line in handle:
            if not line or line[0] in " \t#":
                continue
            if " -> " in line and line.rstrip().endswith(":"):
                names.add(line.split(" -> ", 1)[0].strip())
    return names


def mapping_class_map(mapping_path: str) -> dict[str, str]:
    """Original fully-qualified name -> the name R8 gave it in the shipped DEX."""
    renames: dict[str, str] = {}
    with open(mapping_path, "r", encoding="utf-8") as handle:
        for line in handle:
            if not line or line[0] in " 	#":
                continue
            if " -> " in line and line.rstrip().endswith(":"):
                original, minified = line.rstrip()[:-1].split(" -> ", 1)
                renames[original.strip()] = minified.strip()
    return renames


def profile_rule_patterns(profile_path: str) -> list[tuple[int, str, str]]:
    """(line number, raw rule, class pattern in dotted form) for each rule."""
    rules: list[tuple[int, str, str]] = []
    with open(profile_path, "r", encoding="utf-8") as handle:
        for number, raw in enumerate(handle, start=1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            body = line.split("->", 1)[0]
            # Strip the leading HSP flags, then the leading 'L'.
            body = body.lstrip("HSP")
            if not body.startswith("L"):
                continue
            body = body[1:]
            if body.endswith(";"):
                body = body[:-1]
            rules.append((number, line, body.replace("/", ".")))
    return rules


def pattern_to_regex(pattern: str) -> re.Pattern:
    out = []
    index = 0
    while index < len(pattern):
        if pattern.startswith("**", index):
            out.append(".*")
            index += 2
        elif pattern[index] == "*":
            out.append("[^.]*")
            index += 1
        else:
            out.append(re.escape(pattern[index]))
            index += 1
    return re.compile("^" + "".join(out) + "([.$].*)?$")


# --- Checks ----------------------------------------------------------------


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", nargs="?", help="APK, AAB, .dex, or a directory of .dex")
    parser.add_argument("--mapping", default=DEFAULT_MAPPING)
    parser.add_argument("--baseline-profile", default=DEFAULT_BASELINE_PROFILE)
    parser.add_argument("--source-root", action="append", default=None)
    parser.add_argument(
        "--no-baseline-profile",
        action="store_true",
        help="skip the baseline-profile freshness check (needs mapping.txt)",
    )
    args = parser.parse_args()

    artifact = args.artifact or discover_artifact()
    print(f"Reading minified classes from: {artifact}")
    classes = load_classes(artifact)
    print(f"  {len(classes)} classes survived R8")

    failures: list[str] = []

    def require_class(descriptor: str, why: str) -> dict | None:
        entry = classes.get(descriptor)
        if entry is None:
            failures.append(f"{descriptor} was REMOVED by R8 — {why}")
            return None
        return entry

    # 1. WireGuard JNI ------------------------------------------------------
    entry = require_class(
        WIREGUARD_CLASS,
        "WgNative resolves it with Class.forName; without it the VPN cannot connect",
    )
    if entry is not None:
        for method in WIREGUARD_METHODS:
            if method not in entry["methods"]:
                failures.append(
                    f"{WIREGUARD_CLASS} has no method named '{method}' in the shipped DEX. "
                    "WgNative resolves it via getDeclaredMethod and libwg-go.so exports "
                    f"Java_com_wireguard_android_backend_GoBackend_{method}; the VPN "
                    "cannot connect. Restore the hard keep in app/proguard-rules.pro."
                )

    # 2. Rosenpass JNI ------------------------------------------------------
    entry = require_class(
        ROSENPASS_CLASS, "librosenpass_jni.so binds to it by JNI name mangling"
    )
    if entry is not None:
        for method in ROSENPASS_NATIVE_METHODS:
            access = entry["methods"].get(method)
            if access is None:
                failures.append(
                    f"{ROSENPASS_CLASS} has no method named '{method}' in the shipped DEX; "
                    "BirdoPQ would silently downgrade to the server-provided PSK."
                )
            elif not access & ACC_NATIVE:
                failures.append(
                    f"{ROSENPASS_CLASS}.{method} survived but is no longer ACC_NATIVE "
                    "— the JNI binding is broken."
                )

    # 3. BuildConfig --------------------------------------------------------
    require_class(
        BUILDCONFIG_CLASS,
        "verify_android_release_apk.py scans the DEX for SIGNING_CERT_FINGERPRINT "
        "and NativeLibraryVerifier reads NATIVE_HASH_*",
    )

    # 4. kotlinx.serialization ---------------------------------------------
    #
    # Every name is resolved through mapping.txt. Looking the ORIGINAL descriptor
    # up in the minified DEX only works while some rule happens to pin the name;
    # today that is the blanket $$serializer keep, whose includedescriptorclasses
    # makes every model class a seed (see seeds.txt). Remove that keep -- which
    # the comment in app/proguard-rules.pro explicitly invites -- and every model
    # is renamed, every name lookup misses, and a name-based check reports
    # "All R8 keep and baseline-profile checks passed" having verified NOTHING.
    # A renamed class and a deleted class are not the same thing; this check has
    # to be able to tell them apart, and it must refuse to pass vacuously.
    roots = args.source_root or list(DEFAULT_SOURCE_ROOTS)
    serializable = find_serializable_classes(roots)
    if not serializable:
        failures.append(
            "found no @Serializable declarations under " + ", ".join(roots) +
            " — the scanner is broken, so this check would pass vacuously"
        )

    renames: dict[str, str] = {}
    if not os.path.exists(args.mapping):
        failures.append(
            f"mapping.txt not found at {args.mapping}; without it a class R8 renamed is "
            "indistinguishable from one R8 deleted, and this check cannot run at all"
        )
    else:
        renames = mapping_class_map(args.mapping)

    checked = 0
    shipped = 0
    for fqcn, kind, modifiers in serializable:
        minified = renames.get(fqcn)
        if minified is None:
            # Genuinely absent from the build: R8 removed a model nothing
            # references. Only classes that shipped are worth asserting on.
            continue
        shipped += 1
        entry = classes.get(descriptor_of(minified))
        if entry is None:
            failures.append(
                f"{fqcn} is listed in mapping.txt as '{minified}' but no such class is in "
                "the shipped DEX. The artifact and the mapping are not from the same R8 "
                "run — pass --mapping for THIS artifact."
            )
            continue
        checked += 1
        is_enum = "enum" in modifiers
        is_object = kind == "object"
        if not is_enum and not is_object:
            serializer_minified = renames.get(fqcn + "$$serializer")
            if serializer_minified is None or descriptor_of(serializer_minified) not in classes:
                failures.append(
                    f"{fqcn} is @Serializable and shipped, but its generated "
                    f"{fqcn}$$serializer was removed by R8. Decoding it would throw "
                    "SerializationException at runtime."
                )
        if not is_object:
            companion = entry["fields"].get("Companion")
            if companion is None:
                failures.append(
                    f"{fqcn} is @Serializable but has no field named 'Companion' in the "
                    "shipped DEX. kotlinx.serialization reaches the serializer via "
                    "getDeclaredField(\"Companion\"), which Retrofit's converter does for "
                    "every response body — this throws at runtime."
                )
            else:
                # The second half of the lookup, previously unasserted. Having
                # found the Companion FIELD, kotlinx calls
                # getDeclaredMethod("serializer", ...) on the companion's CLASS.
                # Keeping the field but losing the method is a perfectly
                # buildable state that throws on the first response body.
                companion_entry = classes.get(companion[1])
                if companion_entry is None or "serializer" not in companion_entry["methods"]:
                    failures.append(
                        f"{fqcn}.Companion ({companion[1]}) has no method named 'serializer' "
                        "in the shipped DEX; kotlinx.serialization resolves it with "
                        "getDeclaredMethod(\"serializer\", ...) and would throw at runtime."
                    )

    if serializable and not checked:
        failures.append(
            f"0 of {len(serializable)} declared @Serializable classes were verified against "
            f"{artifact}. This check just passed vacuously, which is not evidence of "
            "anything — confirm the artifact and --mapping come from the same build."
        )
    print(
        f"  checked {checked} shipped @Serializable classes "
        f"({shipped} in mapping.txt, {len(serializable)} declared)"
    )

    # 5. Baseline-profile freshness ----------------------------------------
    #
    # The profile check runs when there is a profile to check. A repo that has
    # not adopted one yet must not fail this gate, but a profile that exists and
    # has gone stale must, and an EXPLICITLY named profile that is missing must
    # too -- otherwise a typo'd --baseline-profile path would pass silently.
    profile_named = args.baseline_profile != DEFAULT_BASELINE_PROFILE
    if not args.no_baseline_profile and not os.path.exists(args.baseline_profile) and not profile_named:
        print(f"  baseline profile: none at {args.baseline_profile} - skipped")
    elif not args.no_baseline_profile:
        if not os.path.exists(args.baseline_profile):
            failures.append(f"baseline profile not found at {args.baseline_profile}")
        elif not os.path.exists(args.mapping):
            failures.append(
                f"mapping.txt not found at {args.mapping}; pass --no-baseline-profile "
                "if the profile check is not wanted here"
            )
        else:
            kept = mapping_original_classes(args.mapping)
            rules = profile_rule_patterns(args.baseline_profile)
            if not rules:
                failures.append(f"{args.baseline_profile} contains no usable rules")
            unmatched = []
            for number, raw, pattern in rules:
                regex = pattern_to_regex(pattern)
                if not any(regex.match(name) for name in kept):
                    unmatched.append((number, raw))
            if unmatched:
                for number, raw in unmatched:
                    failures.append(
                        f"{args.baseline_profile}:{number} matches NOTHING that shipped: "
                        f"{raw}\n    A baseline-profile rule that matches nothing is dead "
                        "weight and decays silently. Fix the package name or delete it."
                    )
            print(f"  baseline profile: {len(rules) - len(unmatched)}/{len(rules)} rules still match")

    if failures:
        print("", file=sys.stderr)
        for failure in failures:
            print(f"ERROR: {failure}", file=sys.stderr)
        print(f"\n{len(failures)} R8 keep/profile check(s) FAILED", file=sys.stderr)
        return 1

    print("All R8 keep and baseline-profile checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
