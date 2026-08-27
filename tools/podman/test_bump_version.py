import pathlib
import re
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
        android_before = (self.root / "android/app/build.gradle.kts").read_text()
        code = int(re.search(r"versionCode = ([0-9]+)", android_before).group(1))
        current = re.search(r'versionName = "([0-9]+\.[0-9]+\.)([0-9]+)"', android_before)
        next_version = f"{current.group(1)}{int(current.group(2)) + 1}"
        result = self.run_tool(next_version)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(0, self.run_tool("--check", next_version).returncode)
        android = (self.root / "android/app/build.gradle.kts").read_text()
        self.assertIn(f"versionCode = {code + 1}", android)
        self.assertIn(f'versionName = "{next_version}"', android)
        self.assertNotIn(current.group(0), android)
        changelog = (self.root / "CHANGELOG.md").read_text()
        self.assertIn(f"## Unreleased\n\n## {next_version}", changelog)
        self.assertRegex(changelog, rf"## {re.escape(next_version)} - [0-9]{{4}}-[0-9]{{2}}-[0-9]{{2}}")
        self.assertLess(changelog.index("## Unreleased"), changelog.index(f"## {next_version}"))

    def test_rejects_changelog_without_unreleased_section(self):
        changelog = self.root / "CHANGELOG.md"
        changelog.write_text(changelog.read_text().replace("## Unreleased", "## Next"))
        android_before = (self.root / "android/app/build.gradle.kts").read_text()
        self.assertNotEqual(0, self.run_tool("9.9.8").returncode)
        self.assertEqual(android_before, (self.root / "android/app/build.gradle.kts").read_text())

    def test_rejects_mismatched_current_sources(self):
        package = self.root / "watchapp/package.json"
        package.write_text(
            re.sub(r'"version": "[0-9.]+"', '"version": "9.9.9"', package.read_text(), count=1)
        )
        self.assertNotEqual(0, self.run_tool("9.9.8").returncode)


if __name__ == "__main__":
    unittest.main()
