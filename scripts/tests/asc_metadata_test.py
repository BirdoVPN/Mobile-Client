"""Tests for build_description() in .github/scripts/asc_metadata.py (MR-2259).

Run: python -I -m unittest scripts/tests/asc_metadata_test.py  (or python scripts/tests/asc_metadata_test.py)
jwt and requests are stubbed: only the pure description logic is under test.
"""
import importlib.util
import sys
import types
import unittest
from pathlib import Path

for name in ("jwt", "requests"):
    sys.modules.setdefault(name, types.ModuleType(name))

_path = Path(__file__).resolve().parents[2] / ".github" / "scripts" / "asc_metadata.py"
_spec = importlib.util.spec_from_file_location("asc_metadata", _path)
m = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(m)


class BuildDescription(unittest.TestCase):
    BASE = "Birdo VPN. A fast WireGuard VPN."

    def test_ios_gets_eula_and_note(self):
        out = m.build_description(self.BASE, "IOS")
        self.assertIn(m.EULA_MARKER, out)
        self.assertIn(m.KILL_SWITCH_NOTE.strip(), out)

    def test_idempotent(self):
        once = m.build_description(self.BASE, "IOS")
        self.assertEqual(m.build_description(once, "IOS"), once)

    def test_note_added_when_eula_already_present(self):
        with_eula = self.BASE + m.EULA_BLOCK
        out = m.build_description(with_eula, "IOS")
        self.assertTrue(out.startswith(with_eula.rstrip()))
        self.assertIn(m.KILL_SWITCH_NOTE.strip(), out)

    def test_macos_untouched_by_note(self):
        out = m.build_description(self.BASE, "MAC_OS")
        self.assertIn(m.EULA_MARKER, out)
        self.assertNotIn(m.KILL_SWITCH_MARKER, out)
        already = self.BASE + m.EULA_BLOCK
        self.assertEqual(m.build_description(already, "MAC_OS"), already)

    def test_curled_apostrophe_still_counts_as_present(self):
        once = m.build_description(self.BASE, "IOS").replace("carrier's", "carrier’s")
        self.assertEqual(m.build_description(once, "IOS"), once)

    def test_limit_eula_wins_over_note(self):
        room = m.DESCRIPTION_LIMIT - len(m.EULA_BLOCK)
        desc = "x" * (room - 10)  # EULA fits, EULA + note does not
        out = m.build_description(desc, "IOS")
        self.assertIn(m.EULA_MARKER, out)
        self.assertNotIn(m.KILL_SWITCH_MARKER, out)
        self.assertLessEqual(len(out), m.DESCRIPTION_LIMIT)

    def test_limit_nothing_fits(self):
        desc = "x" * m.DESCRIPTION_LIMIT
        self.assertEqual(m.build_description(desc, "IOS"), desc)

    def test_never_exceeds_limit(self):
        for n in range(m.DESCRIPTION_LIMIT - 1500, m.DESCRIPTION_LIMIT, 97):
            self.assertLessEqual(len(m.build_description("x" * n, "IOS")), max(n, m.DESCRIPTION_LIMIT))


if __name__ == "__main__":
    unittest.main()
