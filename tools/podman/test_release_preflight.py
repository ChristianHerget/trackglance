import pathlib
import shutil
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]


class ReleasePreflightTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        base = pathlib.Path(self.temporary.name)
        self.repo = base / "work"
        self.remote = base / "remote.git"
        (self.repo / "tools").mkdir(parents=True)
        shutil.copy2(ROOT / "tools/release-preflight", self.repo / "tools/release-preflight")
        files = {
            "android/app/build.gradle.kts": 'versionName = "0.2.2"\n',
            "watchapp/package.json": '{"version":"0.2.2"}\n',
            "watchapp/package-lock.json": '{"version":"0.2.2"}\n',
            "watchapp/src/c/main.c": '#define RELEASE_VERSION "0.2.2"\n',
            "watchapp/src/pkjs/index.js": "  var RELEASE = '0.2.2';\n",
            "docs/sphinx/conf.py": "version = '0.2.2'\n",
            "protocol/README.md": "Protocol v4 is retained for release 0.2.2.\n",
        }
        for name, content in files.items():
            path = self.repo / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        self.git("init", "-b", "main")
        self.git("config", "user.name", "Test")
        self.git("config", "user.email", "test@example.invalid")
        self.git("add", ".")
        self.git("commit", "-m", "release")
        subprocess.run(["git", "init", "--bare", str(self.remote)], check=True, capture_output=True)
        self.git("remote", "add", "origin", str(self.remote))
        self.git("push", "-u", "origin", "main")

    def tearDown(self):
        self.temporary.cleanup()

    def git(self, *arguments):
        return subprocess.run(["git", *arguments], cwd=self.repo, check=True, capture_output=True, text=True)

    def preflight(self, tag):
        return subprocess.run(
            [str(self.repo / "tools/release-preflight"), tag],
            cwd=self.repo, capture_output=True, text=True,
        )

    def test_rejects_malformed_tag(self):
        self.assertNotEqual(0, self.preflight("0.2.2").returncode)

    def test_accepts_matching_versions_with_sentence_punctuation(self):
        self.git("tag", "v0.2.2")
        self.assertEqual(0, self.preflight("v0.2.2").returncode)

    def test_rejects_version_mismatch(self):
        self.git("tag", "v0.2.3")
        self.assertNotEqual(0, self.preflight("v0.2.3").returncode)

    def test_rejects_tag_commit_not_on_main(self):
        self.git("checkout", "-b", "side")
        (self.repo / "side").write_text("side")
        self.git("add", "side")
        self.git("commit", "-m", "side")
        self.git("tag", "v0.2.2")
        self.assertNotEqual(0, self.preflight("v0.2.2").returncode)


if __name__ == "__main__":
    unittest.main()
