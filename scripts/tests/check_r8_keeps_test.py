#!/usr/bin/env python3
"""Self-test for scripts/check_r8_keeps.py's worker check (MR-2129).

The R8 job runs the script against the real minified DEX, where today every
check passes, so a check that could never fail would look the same. These
fixtures prove each failure the worker check exists to catch: a synthetic DEX
built here, no Android tooling needed. Run: python3 scripts/tests/check_r8_keeps_test.py
"""
from __future__ import annotations

import importlib.util
import os
import struct
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_spec = importlib.util.spec_from_file_location("check_r8_keeps", os.path.join(ROOT, "scripts", "check_r8_keeps.py"))
r8 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(r8)

WORKER = "app.birdo.vpn.utils.ClearClipboardWorker"
WORKER_DESC = "Lapp/birdo/vpn/utils/ClearClipboardWorker;"
CONTEXT = "Landroid/content/Context;"
PARAMS = "Landroidx/work/WorkerParameters;"
PUBLIC = 0x0001
PRIVATE = 0x0002
CONSTRUCTOR = 0x10000


def _uleb(value: int) -> bytes:
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def build_dex(classes: dict[str, list[tuple[str, tuple[str, ...], int]]]) -> bytes:
    """A DEX holding `classes`: descriptor -> [(method name, parameter descriptors, access)].

    Only what check_r8_keeps.Dex reads: the string, type, proto and method ids,
    class defs and class data. No code, no checksum.
    """
    methods = [(cls, name, params, access) for cls, ms in classes.items() for name, params, access in ms]
    protos = sorted({params for _, _, params, _ in methods})
    types = sorted({"V"} | set(classes) | {t for p in protos for t in p})
    strings = sorted(set(types) | {name for _, name, _, _ in methods} | {"V" + "L" * len(p) for p in protos})
    s_idx = {value: i for i, value in enumerate(strings)}
    t_idx = {value: i for i, value in enumerate(types)}
    p_idx = {value: i for i, value in enumerate(protos)}

    header = 0x70
    string_ids = header
    type_ids = string_ids + 4 * len(strings)
    proto_ids = type_ids + 4 * len(types)
    method_ids = proto_ids + 12 * len(protos)
    class_defs = method_ids + 8 * len(methods)
    data_start = class_defs + 32 * len(classes)

    data = bytearray()

    def place(blob: bytes, align: int = 1) -> int:
        while (data_start + len(data)) % align:
            data.append(0)
        offset = data_start + len(data)
        data.extend(blob)
        return offset

    string_offs = [place(_uleb(len(v)) + v.encode() + b"\x00") for v in strings]
    params_offs = [
        place(struct.pack("<I", len(p)) + b"".join(struct.pack("<H", t_idx[t]) for t in p), 4) if p else 0
        for p in protos
    ]
    # class_data_item: no fields, every method in the direct list (the reader
    # treats both lists alike). A class's methods have consecutive ids, so the
    # first method_idx_diff is its id and each later one is 1.
    class_data_offs = []
    method_index = 0
    for ms in classes.values():
        blob = bytearray(_uleb(0) + _uleb(0) + _uleb(len(ms)) + _uleb(0))
        for i, (_, _, access) in enumerate(ms):
            blob += _uleb(method_index if i == 0 else 1) + _uleb(access) + _uleb(0)
            method_index += 1
        class_data_offs.append(place(bytes(blob)))

    out = bytearray(data_start)
    out[0:8] = b"dex\n035\x00"
    struct.pack_into("<II", out, 0x38, len(strings), string_ids)
    struct.pack_into("<II", out, 0x40, len(types), type_ids)
    struct.pack_into("<II", out, 0x48, len(protos), proto_ids)
    struct.pack_into("<II", out, 0x58, len(methods), method_ids)
    struct.pack_into("<II", out, 0x60, len(classes), class_defs)
    for i, off in enumerate(string_offs):
        struct.pack_into("<I", out, string_ids + 4 * i, off)
    for i, t in enumerate(types):
        struct.pack_into("<I", out, type_ids + 4 * i, s_idx[t])
    for i, p in enumerate(protos):
        struct.pack_into("<III", out, proto_ids + 12 * i, s_idx["V" + "L" * len(p)], t_idx["V"], params_offs[i])
    for i, (cls, name, params, _) in enumerate(methods):
        struct.pack_into("<HHI", out, method_ids + 8 * i, t_idx[cls], p_idx[params], s_idx[name])
    for i, cls in enumerate(classes):
        struct.pack_into("<I", out, class_defs + 32 * i, t_idx[cls])
        struct.pack_into("<I", out, class_defs + 32 * i + 24, class_data_offs[i])
    return bytes(out) + bytes(data)


def classes_of(spec: dict[str, list[tuple[str, tuple[str, ...], int]]]) -> dict[str, dict]:
    return r8.Dex(build_dex(spec)).classes()


GOOD_CTOR = ("<init>", (CONTEXT, PARAMS), PUBLIC | CONSTRUCTOR)
IDENTITY = {WORKER: WORKER, "androidx.work.WorkerParameters": "androidx.work.WorkerParameters"}


class WorkerCheckTest(unittest.TestCase):
    def failures_for(self, spec, renames=IDENTITY) -> list[str]:
        failures: list[str] = []
        r8.check_worker_constructors(classes_of(spec), dict(renames), [WORKER], failures)
        return failures

    def test_the_reader_records_each_constructor_with_its_parameters(self):
        entry = classes_of({WORKER_DESC: [("<init>", (), PRIVATE | CONSTRUCTOR), GOOD_CTOR, ("doWork", (), PUBLIC)]})[WORKER_DESC]
        self.assertEqual({(): PRIVATE | CONSTRUCTOR, (CONTEXT, PARAMS): PUBLIC | CONSTRUCTOR}, entry["ctors"])
        self.assertIn("doWork", entry["methods"])

    def test_a_worker_that_keeps_its_public_constructor_passes(self):
        self.assertEqual([], self.failures_for({WORKER_DESC: [GOOD_CTOR, ("doWork", (), PUBLIC)]}))

    def test_a_worker_without_the_context_and_parameters_constructor_fails(self):
        failures = self.failures_for({WORKER_DESC: [("<init>", (), PUBLIC | CONSTRUCTOR)]})
        self.assertEqual(1, len(failures))
        self.assertIn("no <init>(Context, WorkerParameters)", failures[0])

    def test_a_constructor_that_is_no_longer_public_fails(self):
        failures = self.failures_for({WORKER_DESC: [("<init>", (CONTEXT, PARAMS), PRIVATE | CONSTRUCTOR)]})
        self.assertEqual(1, len(failures))
        self.assertIn("not public", failures[0])

    def test_a_renamed_worker_fails(self):
        renamed = dict(IDENTITY, **{WORKER: "a.b"})
        failures = self.failures_for({"La/b;": [GOOD_CTOR]}, renamed)
        self.assertEqual(1, len(failures))
        self.assertIn("RENAMED", failures[0])

    def test_a_removed_worker_fails(self):
        failures = self.failures_for({"La/b;": []}, {"androidx.work.WorkerParameters": "androidx.work.WorkerParameters"})
        self.assertEqual(1, len(failures))
        self.assertIn("removed the worker", failures[0])

    def test_a_renamed_worker_parameters_is_followed_through_the_mapping(self):
        renamed = dict(IDENTITY, **{"androidx.work.WorkerParameters": "b.c"})
        spec = {WORKER_DESC: [("<init>", (CONTEXT, "Lb/c;"), PUBLIC | CONSTRUCTOR)]}
        self.assertEqual([], self.failures_for(spec, renamed))

    def test_the_source_scan_finds_the_clipboard_worker(self):
        # Otherwise the check above would pass vacuously on the real build.
        roots = [os.path.join(ROOT, root) for root in r8.DEFAULT_SOURCE_ROOTS]
        self.assertIn(WORKER, r8.find_worker_classes(roots))


if __name__ == "__main__":
    result = unittest.main(exit=False, verbosity=2).result
    sys.exit(0 if result.wasSuccessful() else 1)
