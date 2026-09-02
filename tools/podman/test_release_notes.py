import json
import pathlib
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
RELEASE_NOTES = ROOT / "tools" / "release-notes"


class ReleaseNotesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name)
        (self.root / "CHANGELOG.md").write_text(
            """# Changelog

## Unreleased

## 0.2.8 - 2026-09-02

- Eight.

## 0.2.7 - 2026-08-28

- Seven.

## 0.2.6 - 2026-08-27

- Six.
""",
            encoding="utf-8",
        )

    def tearDown(self):
        self.temporary.cleanup()

    def run_tool(self, releases=None, version="0.2.8"):
        command = [str(RELEASE_NOTES), version, "--root", str(self.root)]
        if releases is not None:
            path = self.root / "releases.json"
            path.write_text(json.dumps(releases), encoding="utf-8")
            command.extend(("--releases-json", str(path)))
        return subprocess.run(command, capture_output=True, text=True, check=False)

    def test_keeps_single_section_compatibility_without_release_metadata(self):
        result = self.run_tool()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "- Eight.")

    def test_includes_unpublished_predecessors_after_latest_public_release(self):
        releases = [
            {"tag_name": "v0.2.8", "draft": True, "prerelease": False},
            {"tag_name": "v0.2.6", "draft": False, "prerelease": False},
            {"tag_name": "v0.2.7", "draft": False, "prerelease": True},
        ]
        result = self.run_tool(releases)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("## 0.2.8\n\n- Eight.", result.stdout)
        self.assertIn("## 0.2.7\n\n- Seven.", result.stdout)
        self.assertNotIn("0.2.6", result.stdout)
        self.assertLess(result.stdout.index("0.2.8"), result.stdout.index("0.2.7"))

    def test_selects_semantically_greatest_published_predecessor(self):
        releases = [
            [
                {"tag_name": "v0.2.6", "draft": False, "prerelease": False},
                {"tag_name": "not-semver", "draft": False, "prerelease": False},
            ],
            [{"tag_name": "v0.2.5", "draft": False, "prerelease": False}],
        ]
        result = self.run_tool(releases)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            result.stdout.strip(),
            "## 0.2.8\n\n- Eight.\n\n## 0.2.7\n\n- Seven.",
        )

    def test_rejects_invalid_metadata_and_missing_target(self):
        metadata = self.root / "bad.json"
        metadata.write_text("{}", encoding="utf-8")
        invalid = subprocess.run(
            [
                str(RELEASE_NOTES),
                "0.2.8",
                "--root",
                str(self.root),
                "--releases-json",
                str(metadata),
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertNotEqual(invalid.returncode, 0)
        self.assertNotEqual(self.run_tool([], version="0.2.9").returncode, 0)


if __name__ == "__main__":
    unittest.main()
