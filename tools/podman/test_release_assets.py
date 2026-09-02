import hashlib
import pathlib
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
VERIFY_RELEASE_ASSETS = ROOT / "tools" / "verify-release-assets"
VERSION = "0.2.8"
ASSETS = (
    f"trackglance-bridge-{VERSION}.apk",
    f"trackglance-bridge-{VERSION}.cdx.json",
    f"trackglance-bridge-{VERSION}.spdx.json",
    f"trackglance-docs-{VERSION}.tar.gz",
    "trackglance-release-certificate.pem",
    "trackglance-release-certificate.sha256",
    f"trackglance-watch-{VERSION}.cdx.json",
    f"trackglance-watch-{VERSION}.pbw",
    f"trackglance-watch-{VERSION}.spdx.json",
)


class VerifyReleaseAssetsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.candidate = pathlib.Path(self.temporary.name)
        for name in ASSETS:
            (self.candidate / name).write_bytes(f"contents of {name}\n".encode())
        self.write_checksums()

    def tearDown(self):
        self.temporary.cleanup()

    def write_checksums(self):
        lines = []
        for name in ASSETS:
            digest = hashlib.sha256((self.candidate / name).read_bytes()).hexdigest()
            lines.append(f"{digest}  ./{name}\n")
        (self.candidate / "SHA256SUMS").write_text("".join(lines), encoding="utf-8")

    def run_verifier(self, version=VERSION):
        return subprocess.run(
            (str(VERIFY_RELEASE_ASSETS), str(self.candidate), version),
            capture_output=True,
            text=True,
            check=False,
        )

    def test_accepts_exact_nonempty_candidate_with_complete_checksums(self):
        result = self.run_verifier()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_rejects_missing_and_unexpected_assets(self):
        (self.candidate / ASSETS[0]).unlink()
        missing = self.run_verifier()
        self.assertNotEqual(missing.returncode, 0)
        self.assertIn("missing or unexpected", missing.stderr)

        (self.candidate / ASSETS[0]).write_text("restored", encoding="utf-8")
        (self.candidate / "unexpected.txt").write_text("unexpected", encoding="utf-8")
        unexpected = self.run_verifier()
        self.assertNotEqual(unexpected.returncode, 0)
        self.assertIn("missing or unexpected", unexpected.stderr)

    def test_rejects_invalid_version(self):
        result = self.run_verifier("v0.2.8")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("MAJOR.MINOR.PATCH", result.stderr)

    def test_rejects_corrupted_asset(self):
        (self.candidate / ASSETS[0]).write_text("corrupted", encoding="utf-8")
        result = self.run_verifier()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("FAILED", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
