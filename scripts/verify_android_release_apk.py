#!/usr/bin/env python3
"""Verify Android release APK payload before publishing.

This catches the exact failure mode where a signed APK is produced but runtime
integrity constants or native VPN engines are missing, causing the client to
fail closed into the kill switch on first connect.
"""

from __future__ import annotations

import os
import re
import sys
import zipfile


REQUIRED_NATIVE_LIBS = (
    "lib/arm64-v8a/libwg-go.so",
    "lib/arm64-v8a/libxray.so",
    "lib/x86_64/libxray.so",
    "lib/arm64-v8a/librosenpass_jni.so",
    "lib/armeabi-v7a/librosenpass_jni.so",
    "lib/x86_64/librosenpass_jni.so",
)


def read_uleb128(data: bytes, offset: int) -> tuple[int, int]:
    result = 0
    shift = 0
    while True:
        byte = data[offset]
        offset += 1
        result |= (byte & 0x7F) << shift
        if byte & 0x80 == 0:
            return result, offset
        shift += 7


def dex_strings(data: bytes) -> list[str]:
    if len(data) < 0x40 or data[:3] != b"dex":
        return []

    string_count = int.from_bytes(data[0x38:0x3C], "little")
    string_ids_offset = int.from_bytes(data[0x3C:0x40], "little")
    strings: list[str] = []

    for index in range(string_count):
        item_offset = string_ids_offset + index * 4
        if item_offset + 4 > len(data):
            break
        data_offset = int.from_bytes(data[item_offset:item_offset + 4], "little")
        if data_offset >= len(data):
            continue
        _, value_offset = read_uleb128(data, data_offset)
        value_end = data.find(b"\x00", value_offset)
        if value_end < 0:
            continue
        try:
            strings.append(data[value_offset:value_end].decode("utf-8"))
        except UnicodeDecodeError:
            continue

    return strings


def normalize_fingerprint(value: str) -> str:
    compact = re.sub(r"[^0-9A-Fa-f]", "", value).upper()
    return ":".join(compact[index:index + 2] for index in range(0, len(compact), 2))


def compact_fingerprint(value: str) -> str:
    return re.sub(r"[^0-9A-Fa-f]", "", value).upper()


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    sys.exit(1)


def main() -> None:
    if len(sys.argv) != 2:
        fail("usage: verify_android_release_apk.py <release-apk>")

    apk_path = sys.argv[1]
    expected_fingerprint = os.environ.get("BIRDO_SIGNING_CERT_FINGERPRINT", "").strip()
    if not expected_fingerprint:
        fail("BIRDO_SIGNING_CERT_FINGERPRINT is not set")

    with zipfile.ZipFile(apk_path) as apk:
        names = set(apk.namelist())
        missing_libs = [name for name in REQUIRED_NATIVE_LIBS if name not in names]
        if missing_libs:
            fail("release APK is missing required native libraries: " + ", ".join(missing_libs))

        all_strings: list[str] = []
        for name in names:
            if name.startswith("classes") and name.endswith(".dex"):
                all_strings.extend(dex_strings(apk.read(name)))

    expected_variants = {
        expected_fingerprint,
        normalize_fingerprint(expected_fingerprint),
        compact_fingerprint(expected_fingerprint),
    }
    found_fingerprint = any(
        string_value in expected_variants or compact_fingerprint(string_value) in expected_variants
        for string_value in all_strings
    )
    if not found_fingerprint:
        fail("release APK does not contain the expected signing fingerprint constant")

    print("Release APK payload verification passed")


if __name__ == "__main__":
    main()