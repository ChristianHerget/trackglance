import pathlib
import shutil
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]


class BumpVersionTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name)
        for relative in (
            "tools/bump-version",
            "android/app/build.gradle.kts",
            "watchapp/package.json",
            "watchapp/package-lock.json",
            "watchapp/src/c/main.c",
            "watchapp/src/pkjs/index.js",
            "docs/sphinx/conf.py",
            "protocol/README.md",
            "RELEASE.md",
            "appstore/listing.json",
            "CHANGELOG.md",
            "docs/end-to-end-testing.md",
            "docs/sphinx/limitations.rst",
        ):
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative, target)

    def tearDown(self):
        self.temporary.cleanup()

    def run_tool(self, *arguments):
        return subprocess.run(
            [str(self.root / "tools/bump-version"), "--root", str(self.root), *arguments],
            text=True,
            capture_output=True,
        )

    def test_bumps_all_release_sources_and_android_code(self):
        self.assertIn("versionCode = 14", (self.root / "android/app/build.gradle.kts").read_text())
        result = self.run_tool("0.2.4")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(0, self.run_tool("--check", "0.2.4").returncode)
        android = (self.root / "android/app/build.gradle.kts").read_text()
        self.assertIn("versionCode = 15", android)
        self.assertIn('versionName = "0.2.4"', android)
        self.assertNotIn("0.2.3", (self.root / "protocol/README.md").read_text())
        self.assertIn("## 0.2.4 - Unreleased", (self.root / "CHANGELOG.md").read_text())

    def test_rejects_mismatched_current_sources(self):
        package = self.root / "watchapp/package.json"
        package.write_text(package.read_text().replace('"version": "0.2.3"', '"version": "9.9.9"', 1))
        self.assertNotEqual(0, self.run_tool("0.2.4").returncode)


if __name__ == "__main__":
    unittest.main()
