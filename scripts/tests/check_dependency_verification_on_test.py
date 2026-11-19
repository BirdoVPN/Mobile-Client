#!/usr/bin/env python3
"""Self-test for scripts/check_dependency_verification_on.py (MR-2434).

On a pull request that leaves verification alone the guard has nothing to
find, so a guard that could never fail would look the same. Each fixture turns
verification off one way Gradle accepts and must fail; the tree it starts from
(this repository's shapes: the baseline-profile line, the writer workflow,
#646's retry properties, comments that name the flags) must pass.
Run: python3 scripts/tests/check_dependency_verification_on_test.py
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import os
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
_spec = importlib.util.spec_from_file_location(
    "check_dependency_verification_on", os.path.join(ROOT, "scripts", "check_dependency_verification_on.py"))
guard = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(guard)

BASELINE_LINE = "./gradlew :app:generateReleaseBaselineProfile --no-daemon --dependency-verification lenient \\"

CLEAN = {
    "gradle/verification-metadata.xml": "<verification-metadata/>\n",
    "gradle.properties": (
        "# Never org.gradle.dependency.verification=off here.\n"
        "org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8\n"
        "org.gradle.dependency.verification.console=verbose\n"),
    ".github/workflows/android-ci.yml": (
        "jobs:\n  lint:\n    steps:\n"
        "      # Do not answer a failure with --dependency-verification off.\n"
        "      - run: ./gradlew lintDebug\n"
        "      - run: ./gradlew help | grep -F BUILD\n"
        "      - run: grep -F -- '-F' notes.txt\n"),
    ".github/workflows/baseline-profile.yml": (
        "jobs:\n  record:\n    steps:\n"
        "      # --dependency-verification lenient (MR-1794): see the step.\n"
        "      - run: |\n"
        f"          {BASELINE_LINE}\n"
        "            -Dorg.gradle.internal.repository.max.tentatives=8 \\\n"
        "            -Dorg.gradle.internal.repository.initial.backoff=2000\n"),
    ".github/workflows/gradle-verification-metadata.yml": (
        "jobs:\n  linux:\n    steps:\n"
        "      - run: ./gradlew --write-verification-metadata sha256 help lintDebug\n"),
    "app/build.gradle.kts": "// Never disableDependencyVerification() here.\nplugins { id(\"com.android.application\") }\n",
    "BUILD-INSTRUCTIONS.md": "Run with `--dependency-verification lenient` on a managed device; never `-F off`.\n",
}


class GuardTest(unittest.TestCase):
    def run_guard(self, changes: dict[str, str | None]) -> tuple[int, str]:
        files = dict(CLEAN)
        files.update(changes)
        with tempfile.TemporaryDirectory() as tmp:
            for path, content in files.items():
                if content is None:
                    continue
                full = os.path.join(tmp, path)
                os.makedirs(os.path.dirname(full), exist_ok=True)
                with open(full, "w", encoding="utf-8", newline="\n") as f:
                    f.write(content)
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                rc = guard.main(["--root", tmp])
            return rc, out.getvalue()

    def assert_fails(self, changes: dict[str, str | None], *needles: str) -> str:
        rc, out = self.run_guard(changes)
        self.assertEqual(rc, 1, out)
        for needle in needles:
            self.assertIn(needle, out)
        return out

    def test_clean_tree_passes(self):
        rc, out = self.run_guard({})
        self.assertEqual(rc, 0, out)
        self.assertIn("ok:", out)
        self.assertNotIn("::error", out)

    def test_retry_properties_from_646_pass(self):
        # Mobile #646 adds these to gradle.properties: other Gradle properties,
        # system properties included, are not this guard's business.
        rc, out = self.run_guard({"gradle.properties": CLEAN["gradle.properties"] + (
            "systemProp.org.gradle.internal.repository.max.tentatives=8\n"
            "systemProp.org.gradle.internal.repository.initial.backoff=2000\n")})
        self.assertEqual(rc, 0, out)

    def test_missing_metadata_fails(self):
        self.assert_fails({"gradle/verification-metadata.xml": None},
                          "::error file=gradle/verification-metadata.xml::gradle/verification-metadata.xml is missing")

    def test_property_in_gradle_properties_fails(self):
        for line in ("org.gradle.dependency.verification=lenient",
                     "org.gradle.dependency.verification = off",
                     "org.gradle.dependency.verification:off",
                     "systemProp.org.gradle.dependency.verification=off",
                     "  org.gradle.dependency\\.verification=off",
                     "org.gradle.dependency.verificatio\\u006e=off",
                     "org.gradle.dependency.verifi\\\n    cation=off",
                     "org.gradle.jvmargs=-Xmx2g \\\n#-Dorg.gradle.dependency.verification=off"):
            with self.subTest(line=line):
                self.assert_fails({"gradle.properties": CLEAN["gradle.properties"] + line + "\n"},
                                  "gradle.properties:4 sets org.gradle.dependency.verification")

    def test_property_in_any_gradle_properties_fails(self):
        self.assert_fails({"app/gradle.properties": "org.gradle.dependency.verification=off\n"},
                          "app/gradle.properties:1 sets org.gradle.dependency.verification")

    def test_property_value_is_never_printed(self):
        out = self.assert_fails({"gradle.properties": "org.gradle.dependency.verification=lenient-VALUE-MARKER\n"})
        self.assertNotIn("VALUE-MARKER", out)

    def test_commented_property_passes(self):
        rc, out = self.run_guard({"gradle.properties": "! org.gradle.dependency.verification=off\n"})
        self.assertEqual(rc, 0, out)

    def test_system_property_on_a_command_line_fails(self):
        self.assert_fails({".github/workflows/android-ci.yml": CLEAN[".github/workflows/android-ci.yml"]
                           + "    env:\n      GRADLE_OPTS: -Dorg.gradle.dependency.verification=off\n"},
                          "android-ci.yml:9 sets org.gradle.dependency.verification")

    def test_mode_flag_in_a_workflow_fails(self):
        for run in ("./gradlew lintDebug --dependency-verification off",
                    "./gradlew --dependency-verification=lenient testDebugUnitTest",
                    './gradlew --dependency-verification "$MODE" assembleDebug'):
            with self.subTest(run=run):
                self.assert_fails({".github/workflows/android-ci.yml": CLEAN[".github/workflows/android-ci.yml"]
                                   + f"      - run: {run}\n"},
                                  "android-ci.yml:8 passes --dependency-verification")

    def test_mode_flag_in_a_script_fails(self):
        self.assert_fails({"scripts/build.sh": "#!/bin/bash\nset -e\n./gradlew assembleRelease --dependency-verification off\n"},
                          "scripts/build.sh:3 passes --dependency-verification")

    def test_short_flags_on_a_gradle_command_fail(self):
        for run in ("./gradlew -F off lintDebug",
                    "./gradlew -Foff lintDebug",
                    "./gradlew lintDebug \\\n          -F lenient",
                    "gradle -M sha256 help",
                    "./gradlew.bat -F=off help"):
            with self.subTest(run=run):
                self.assert_fails({".github/workflows/android-ci.yml": CLEAN[".github/workflows/android-ci.yml"]
                                   + f"      - run: |\n          {run}\n"},
                                  "passes -F (--dependency-verification) or -M")

    def test_the_baseline_profile_line_passes_once_only(self):
        twice = CLEAN[".github/workflows/baseline-profile.yml"] + f"          {BASELINE_LINE}\n            help\n"
        self.assert_fails({".github/workflows/baseline-profile.yml": twice},
                          "baseline-profile.yml:9 passes --dependency-verification")

    def test_the_baseline_profile_line_with_another_mode_fails(self):
        off = CLEAN[".github/workflows/baseline-profile.yml"].replace("verification lenient", "verification off")
        self.assert_fails({".github/workflows/baseline-profile.yml": off},
                          "baseline-profile.yml:6 passes --dependency-verification")

    def test_the_baseline_profile_line_elsewhere_fails(self):
        self.assert_fails({".github/workflows/android-ci.yml": CLEAN[".github/workflows/android-ci.yml"]
                           + f"      - run: |\n          {BASELINE_LINE}\n            help\n"},
                          "android-ci.yml:9 passes --dependency-verification")

    def test_disable_in_a_build_script_fails(self):
        self.assert_fails({"app/build.gradle.kts": CLEAN["app/build.gradle.kts"]
                           + "configurations.all { resolutionStrategy.disableDependencyVerification() }\n"},
                          "app/build.gradle.kts:3 turns dependency verification off from a script")

    def test_start_parameter_in_an_init_script_fails(self):
        init = ("      - run: |\n          cat > init.gradle <<'GRADLE'\n"
                "          gradle.startParameter.dependencyVerificationMode = "
                "org.gradle.api.artifacts.verification.DependencyVerificationMode.OFF\n          GRADLE\n")
        self.assert_fails({".github/workflows/android-ci.yml": CLEAN[".github/workflows/android-ci.yml"] + init},
                          "android-ci.yml:10 turns dependency verification off from a script")

    def test_write_flag_outside_the_writer_fails(self):
        self.assert_fails({".github/workflows/android-ci.yml": CLEAN[".github/workflows/android-ci.yml"]
                           + "      - run: ./gradlew --write-verification-metadata sha256 lintDebug\n"},
                          "android-ci.yml:8 passes --write-verification-metadata outside")

    def test_markdown_is_prose(self):
        rc, out = self.run_guard({"docs/notes.md": "./gradlew -F off --dependency-verification off\n"})
        self.assertEqual(rc, 0, out)

    def test_every_finding_is_reported(self):
        out = self.assert_fails({
            "gradle/verification-metadata.xml": None,
            "gradle.properties": "org.gradle.dependency.verification=off\n",
            "app/build.gradle.kts": "configurations.all { resolutionStrategy.disableDependencyVerification() }\n",
        })
        self.assertEqual(out.count("::error"), 3, out)

    def test_this_repository_passes(self):
        # The real tree, read the way CI reads it.
        if not os.path.exists(os.path.join(ROOT, ".git")):
            self.skipTest("not a git checkout")
        errors, read = guard.check(ROOT)
        self.assertEqual(errors, [])
        self.assertGreater(read, 100)


if __name__ == "__main__":
    sys.exit(0 if unittest.main(exit=False, verbosity=1).result.wasSuccessful() else 1)
