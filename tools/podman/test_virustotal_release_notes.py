import os
import pathlib
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
APPENDER = ROOT / "tools" / "append-virustotal-badges"
VERSION = "0.2.5"
APK = f"trackglance-bridge-{VERSION}.apk"
PBW = f"trackglance-watch-{VERSION}.pbw"
APK_URL = "https://www.virustotal.com/gui/file-analysis/apk_analysis-123/detection"
PBW_URL = "https://www.virustotal.com/gui/file-analysis/pbw_analysis-456/detection"


class VirusTotalReleaseNotesTest(unittest.TestCase):
    def run_appender(self, analysis: str):
        temporary = tempfile.TemporaryDirectory()
        notes = pathlib.Path(temporary.name) / "release-notes.md"
        original = "Release notes\n"
        notes.write_text(original, encoding="utf-8")
        result = subprocess.run(
            [str(APPENDER), VERSION, str(notes)],
            env={**os.environ, "VIRUSTOTAL_ANALYSIS": analysis},
            capture_output=True,
            text=True,
            check=False,
        )
        content = notes.read_text(encoding="utf-8")
        temporary.cleanup()
        return result, content, original

    def test_reversed_results_generate_two_stable_submission_badges(self):
        result, notes, _ = self.run_appender(
            f"build/release-assets/{PBW}={PBW_URL},"
            f"build/release-assets/{APK}={APK_URL}"
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("[![VirusTotal: APK submitted]", notes)
        self.assertIn(f"]({APK_URL})", notes)
        self.assertIn("[![VirusTotal: PBW submitted]", notes)
        self.assertIn(f"]({PBW_URL})", notes)
        self.assertLess(notes.index("APK submitted"), notes.index("PBW submitted"))
        self.assertNotIn("clean", notes.lower())

    def test_missing_report_does_not_modify_release_notes(self):
        result, notes, original = self.run_appender(
            f"build/release-assets/{APK}={APK_URL}"
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("missing VirusTotal report", result.stderr)
        self.assertEqual(notes, original)

    def test_duplicate_report_does_not_modify_release_notes(self):
        result, notes, original = self.run_appender(
            f"build/release-assets/{APK}={APK_URL},"
            f"other/{APK}={APK_URL},build/release-assets/{PBW}={PBW_URL}"
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("duplicate VirusTotal report", result.stderr)
        self.assertEqual(notes, original)

    def test_unexpected_artifact_does_not_modify_release_notes(self):
        result, notes, original = self.run_appender(
            f"build/release-assets/{APK}={APK_URL},"
            f"build/release-assets/{PBW}={PBW_URL},"
            f"build/release-assets/SHA256SUMS={APK_URL}"
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unexpected VirusTotal artifact", result.stderr)
        self.assertEqual(notes, original)

    def test_malformed_urls_do_not_modify_release_notes(self):
        malformed = (
            "http://www.virustotal.com/gui/file-analysis/id/detection",
            "https://example.com/gui/file-analysis/id/detection",
            "https://www.virustotal.com/gui/file-analysis/id",
            "https://www.virustotal.com/gui/file-analysis/id/detection?verdict=clean",
        )
        for url in malformed:
            with self.subTest(url=url):
                result, notes, original = self.run_appender(
                    f"build/release-assets/{APK}={url},"
                    f"build/release-assets/{PBW}={PBW_URL}"
                )
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("malformed VirusTotal analysis URL", result.stderr)
                self.assertEqual(notes, original)


if __name__ == "__main__":
    unittest.main()
