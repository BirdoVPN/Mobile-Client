#!/usr/bin/env python3
"""Self-test for scripts/verify_android_release_apk.py's manifest component check (MR-2379).

On every real artifact the check passes, so a check that could never fail would
look the same. These synthetic archives (an APK whose manifest string pool is
UTF-16, an AAB whose protobuf manifest is UTF-8) prove it fails when the Play
Billing log uploader is back, and when the manifest cannot be read at all.
Run: python3 scripts/tests/verify_android_release_apk_test.py
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import os
import sys
import tempfile
import unittest
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_spec = importlib.util.spec_from_file_location(
    "verify_android_release_apk", os.path.join(ROOT, "scripts", "verify_android_release_apk.py")
)
verify = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(verify)

UPLOADER = verify.REMOVED_MANIFEST_COMPONENTS[0]
DISCOVERY = "com.google.android.datatransport.runtime.backends.TransportBackendDiscovery"


def _manifest(names: list[str], utf16: bool) -> bytes:
    # A stand-in for the string pool: an even-length header, then each string
    # length-prefixed, the way both formats keep it.
    out = bytearray(b"\x03\x00\x08\x00")
    for name in names:
        data = name.encode("utf-16-le") if utf16 else name.encode("utf-8")
        out += len(name).to_bytes(2, "little") + data + (b"\x00\x00" if utf16 else b"\x00")
        if len(out) % 2:
            out += b"\x00"
    return bytes(out)


def _artifact(path: str, components: list[str], aab: bool) -> None:
    lib_prefix = "base/lib/" if aab else "lib/"
    manifest_entry = "base/manifest/AndroidManifest.xml" if aab else "AndroidManifest.xml"
    with zipfile.ZipFile(path, "w") as z:
        z.writestr(manifest_entry, _manifest(components, utf16=not aab))
        for name in verify.required_native_libs(lib_prefix):
            z.writestr(name, b"\x7fELF")


def _run(path: str) -> tuple[int, str]:
    err = io.StringIO()
    argv = sys.argv
    sys.argv = ["verify_android_release_apk.py", "--native-libs-only", path]
    try:
        with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
            verify.main()
        return 0, err.getvalue()
    except SystemExit as e:
        return int(e.code or 0), err.getvalue()
    finally:
        sys.argv = argv


class ManifestComponentCheck(unittest.TestCase):
    def setUp(self) -> None:
        self.dir = tempfile.TemporaryDirectory()

    def tearDown(self) -> None:
        self.dir.cleanup()

    def _check(self, components: list[str], aab: bool) -> tuple[int, str]:
        path = os.path.join(self.dir.name, "a.aab" if aab else "a.apk")
        _artifact(path, components, aab)
        return _run(path)

    def test_passes_without_the_uploader(self) -> None:
        for aab in (False, True):
            code, err = self._check([verify.OWN_VPN_SERVICE, DISCOVERY], aab)
            self.assertEqual(0, code, err)

    def test_fails_when_either_uploader_component_is_back(self) -> None:
        for aab in (False, True):
            for component in verify.REMOVED_MANIFEST_COMPONENTS:
                code, err = self._check([verify.OWN_VPN_SERVICE, component], aab)
                self.assertEqual(1, code, f"aab={aab} {component}")
                self.assertIn("MR-2379", err)

    def test_fails_when_the_manifest_cannot_be_read(self) -> None:
        # Without the app's own service in view, an absent uploader proves nothing.
        code, err = self._check([UPLOADER.replace(".", "/")], aab=False)
        self.assertEqual(1, code)
        self.assertIn(verify.OWN_VPN_SERVICE, err)


if __name__ == "__main__":
    unittest.main()
