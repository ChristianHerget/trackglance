import runpy
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from script_test_support import ROOT


class SyntheticReportsTest(unittest.TestCase):
    def setUp(self):
        self.ns = runpy.run_path(str(ROOT / "tools/podman/assert-instrumentation-results.py"))
        self.main = self.ns["main"]

    def validate(self, cases, expected=None):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            body = "".join(
                f'<testcase classname="{cls}" name="{name}">{extra}</testcase>'
                for cls, name, extra in cases
            )
            (path / "report.xml").write_text(f'<testsuite tests="{len(cases)}">{body}</testsuite>')
            with mock.patch.dict(self.main.__globals__, {
                "synthetic_cases": lambda source: expected or {("example.Test", "works")},
            }), mock.patch.object(sys, "argv", ["report", directory, "--synthetic"]):
                self.main()

    def test_expected_synthetic_case_must_execute(self):
        self.validate([("example.Test", "works", "")])
        with self.assertRaisesRegex(ValueError, "coverage differs"):
            self.validate([("example.Test", "other", "")])

    def test_duplicate_and_real_locus_results_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.validate([("example.Test", "works", "")] * 2)
        with self.assertRaisesRegex(ValueError, "real Locus"):
            self.validate([("example.LocusEndToEndTest", "works", "")])

    def test_child_skip_failure_or_error_cannot_be_hidden_by_suite_counters(self):
        for child in ("<skipped/>", "<failure/>", "<error/>"):
            with self.subTest(child=child), self.assertRaisesRegex(ValueError, "did not complete"):
                self.validate([("example.Test", "works", child)])

    def test_expected_cases_come_from_test_methods_and_exclude_real_locus(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / "ExampleTest.kt").write_text(
                "package example\nclass ExampleTest {\n @Test\n fun works() {}\n}\n"
            )
            (path / "LocusEndToEndTest.kt").write_text(
                "package example\nclass LocusEndToEndTest {\n @Test\n fun realLocus() {}\n}\n"
            )
            self.assertEqual(self.ns["synthetic_cases"](path), {("example.ExampleTest", "works")})
