import hashlib
import http.server
import io
import runpy
import subprocess
import tempfile
import threading
import unittest
from collections import Counter
from email.message import Message
from pathlib import Path
from unittest.mock import Mock, patch


ROOT = Path(__file__).resolve().parents[2]
DOWNLOADER = ROOT / "tools" / "download-locus-apk"
VALIDATOR = ROOT / "tools" / "podman" / "validate-locus-apks.py"
APK_BYTES = b"PK\x03\x04" + b"fixture payload"
INTERRUPTED_APK_BYTES = b"PK\x03\x04" + b"fixture payload" * 1000


class FixtureHandler(http.server.BaseHTTPRequestHandler):
    request_counts = Counter()
    request_lock = threading.Lock()

    def do_GET(self):
        with self.request_lock:
            self.request_counts[self.path] += 1
            attempt = self.request_counts[self.path]
        if self.path == "/temporary.apk" and attempt == 1:
            body, content_type, status = b"unavailable", "text/plain", 503
        elif self.path == "/unavailable.apk":
            body, content_type, status = b"unavailable", "text/plain", 503
        elif self.path == "/interrupted.apk" and attempt == 1:
            body = INTERRUPTED_APK_BYTES[:9000]
            content_type, status = "application/vnd.android.package-archive", 200
        elif self.path == "/interrupted.apk":
            body = INTERRUPTED_APK_BYTES
            content_type, status = "application/vnd.android.package-archive", 200
        elif self.path in {"/fixture.apk", "/temporary.apk"}:
            body, content_type, status = APK_BYTES, "application/vnd.android.package-archive", 200
        elif self.path == "/corrupt.apk":
            body, content_type, status = APK_BYTES + b"changed", "application/octet-stream", 200
        elif self.path == "/warning.html":
            body, content_type, status = b"<!DOCTYPE html><html>not an APK</html>", "text/html", 200
        else:
            body, content_type, status = b"missing", "text/plain", 404
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        content_length = len(INTERRUPTED_APK_BYTES) if self.path == "/interrupted.apk" else len(body)
        self.send_header("Content-Length", str(content_length))
        self.end_headers()
        self.wfile.write(body)
        if self.path == "/interrupted.apk" and attempt == 1:
            self.close_connection = True

    def log_message(self, *_args):
        pass


class DownloaderTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), FixtureHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.thread.join()
        cls.server.server_close()

    def setUp(self):
        with FixtureHandler.request_lock:
            FixtureHandler.request_counts.clear()

    def run_download(self, path: str, checksum: str, output: Path):
        url = f"http://127.0.0.1:{self.server.server_port}{path}"
        return subprocess.run(
            [
                str(DOWNLOADER),
                "--url",
                url,
                "--sha256",
                checksum,
                "--output",
                str(output),
                "--timeout",
                "5",
            ],
            capture_output=True,
            text=True,
            check=False,
        )

    def test_downloads_a_matching_apk_atomically(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "locus.apk"
            checksum = hashlib.sha256(APK_BYTES).hexdigest()
            result = self.run_download("/fixture.apk", checksum, output)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(output.read_bytes(), APK_BYTES)

    def test_recovers_from_a_temporary_http_503(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "locus.apk"
            checksum = hashlib.sha256(APK_BYTES).hexdigest()
            result = self.run_download("/temporary.apk", checksum, output)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(FixtureHandler.request_counts["/temporary.apk"], 2)
            self.assertEqual(output.read_bytes(), APK_BYTES)

    def test_recovers_from_an_interrupted_transfer(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "locus.apk"
            checksum = hashlib.sha256(INTERRUPTED_APK_BYTES).hexdigest()
            result = self.run_download("/interrupted.apk", checksum, output)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(FixtureHandler.request_counts["/interrupted.apk"], 2)
            self.assertEqual(output.read_bytes(), INTERRUPTED_APK_BYTES)
            self.assertEqual(list(Path(directory).iterdir()), [output])

    def test_stops_after_three_http_503_responses_without_leaving_output(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "locus.apk"
            result = self.run_download("/unavailable.apk", "0" * 64, output)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("HTTP 503 after 3 attempts", result.stderr)
            self.assertEqual(FixtureHandler.request_counts["/unavailable.apk"], 3)
            self.assertEqual(list(Path(directory).iterdir()), [])

    def test_rejects_html_instead_of_an_apk(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "locus.apk"
            result = self.run_download("/warning.html", "0" * 64, output)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("HTML", result.stderr)
            self.assertEqual(FixtureHandler.request_counts["/warning.html"], 1)
            self.assertFalse(output.exists())

    def test_rejects_a_checksum_mismatch_without_leaving_output(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "locus.apk"
            result = self.run_download("/corrupt.apk", "0" * 64, output)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("SHA-256", result.stderr)
            self.assertEqual(FixtureHandler.request_counts["/corrupt.apk"], 1)
            self.assertFalse(output.exists())

    def test_rejects_an_http_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "locus.apk"
            result = self.run_download("/missing", "0" * 64, output)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("HTTP Error 404", result.stderr)
            self.assertEqual(FixtureHandler.request_counts["/missing"], 1)
            self.assertFalse(output.exists())


class DriveDownloadTest(unittest.TestCase):
    def setUp(self):
        self.downloader = runpy.run_path(str(DOWNLOADER))

    def test_shared_drive_urls_use_the_download_endpoint(self):
        for url in (
            "https://drive.google.com/file/d/shared-id/view?usp=sharing",
            "https://drive.google.com/open?id=shared-id",
            "https://docs.google.com/uc?id=shared-id&export=download",
        ):
            with self.subTest(url=url):
                self.assertEqual(
                    self.downloader["initial_url"](url),
                    "https://drive.google.com/uc?export=download&id=shared-id",
                )

    def response(self, body, content_type):
        response = io.BytesIO(body)
        response.headers = Message()
        response.headers["Content-Type"] = content_type
        response.headers["Content-Length"] = str(len(body))
        return response

    def form(self, action="https://drive.usercontent.google.com/download", omit=None):
        fields = {"id": "shared-id", "export": "download", "confirm": "t", "uuid": "token"}
        return (
            f'<html><form id="download-form" action="{action}">'
            + "".join(f'<input name="{key}" value="{value}">' for key, value in fields.items() if key != omit)
            + "</form></html>"
        ).encode()

    def test_confirmation_form_downloads_and_verifies_the_apk(self):
        download = self.downloader["download_once"]
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "locus.apk"
            with patch.dict(
                download.__globals__,
                request=Mock(
                    side_effect=[
                        self.response(self.form(), "text/html"),
                        self.response(APK_BYTES, "application/octet-stream"),
                    ]
                ),
            ):
                size, checksum = download(
                    "https://drive.google.com/file/d/shared-id/view", output, hashlib.sha256(APK_BYTES).hexdigest(), 5
                )
                calls = download.__globals__["request"].call_args_list
            self.assertEqual(
                calls[1].args[0],
                "https://drive.usercontent.google.com/download?id=shared-id&export=download&confirm=t&uuid=token",
            )
            self.assertEqual(size, len(APK_BYTES))
            self.assertEqual(checksum, hashlib.sha256(APK_BYTES).hexdigest())
            self.assertEqual(output.read_bytes(), APK_BYTES)
            self.assertEqual(list(Path(directory).iterdir()), [output])

    def test_invalid_confirmation_responses_leave_no_output(self):
        for warning, confirmed in (
            (self.form(action="https://example.com/download"), None),
            (self.form(omit="uuid"), None),
            (b"<html>no download form</html>", None),
            (self.form(), b"<html>access denied</html>"),
            (self.form(), b"not an APK"),
            (self.form(), APK_BYTES + b"corrupted"),
        ):
            with self.subTest(warning=warning, confirmed=confirmed), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / "locus.apk"
                responses = [self.response(warning, "text/html")]
                if confirmed is not None:
                    responses.append(self.response(confirmed, "application/octet-stream"))
                download = self.downloader["download_once"]
                with patch.dict(download.__globals__, request=Mock(side_effect=responses)):
                    with self.assertRaises(ValueError):
                        download(
                            "https://drive.google.com/file/d/shared-id/view",
                            output,
                            hashlib.sha256(APK_BYTES).hexdigest(),
                            5,
                        )
                self.assertEqual(list(Path(directory).iterdir()), [])


class PinnedMetadataTest(unittest.TestCase):
    def validate_metadata(self, field=None, value=None, package="menion.android.locus"):
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            apk_dir = temporary / "apks"
            apk_dir.mkdir()
            apk = apk_dir / "locus.apk"
            apk.write_bytes(APK_BYTES)
            aapt = temporary / "aapt"
            aapt.write_text(
                "#!/bin/sh\ncat <<'EOF'\n"
                f"package: name='{package}' versionCode='1215' versionName='4.35.0'\n"
                "sdkVersion:'24'\ntargetSdkVersion:'35'\nnative-code: 'x86_64'\nEOF\n",
                encoding="utf-8",
            )
            apksigner = temporary / "apksigner"
            apksigner.write_text(
                "#!/bin/sh\necho 'Signer #1 certificate SHA-256 digest: abcd'\n",
                encoding="utf-8",
            )
            aapt.chmod(0o755)
            apksigner.chmod(0o755)
            config = temporary / "fixture.properties"
            config.write_text(
                "\n".join(
                    (
                        f"LOCUS_APK_SHA256={hashlib.sha256(APK_BYTES).hexdigest()}",
                        f"LOCUS_APK_SIZE={len(APK_BYTES)}",
                        "LOCUS_APK_PACKAGE=menion.android.locus",
                        "LOCUS_APK_VERSION_NAME=4.35.0",
                        "LOCUS_APK_VERSION_CODE=1215",
                        "LOCUS_APK_ABI=x86_64",
                        "LOCUS_APK_CERT_SHA256=abcd",
                        "LOCUS_APK_MIN_SDK=24",
                        "LOCUS_APK_TARGET_SDK=35",
                    )
                ),
                encoding="utf-8",
            )
            if field is not None:
                lines = config.read_text().splitlines()
                config.write_text(
                    "\n".join(f"{field}={value}" if line.startswith(field + "=") else line for line in lines)
                )
            result = subprocess.run(
                [
                    "python3",
                    str(VALIDATOR),
                    "--aapt",
                    str(aapt),
                    "--apksigner",
                    str(apksigner),
                    "--fixture-config",
                    str(config),
                    str(apk_dir),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            return result

    def test_validator_accepts_matching_metadata(self):
        result = self.validate_metadata()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_validator_rejects_the_wrong_package(self):
        result = self.validate_metadata(package="wrong.package")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("package ID", result.stderr)

    def test_validator_rejects_each_mismatched_pin(self):
        for field, value, description in (
            ("SHA256", "0" * 64, "SHA-256"),
            ("SIZE", "999", "size"),
            ("PACKAGE", "wrong.package", "package"),
            ("VERSION_CODE", "999", "version code"),
            ("VERSION_NAME", "wrong", "version name"),
            ("MIN_SDK", "25", "minimum SDK"),
            ("TARGET_SDK", "36", "target SDK"),
            ("CERT_SHA256", "1234", "signing certificate"),
            ("ABI", "arm64-v8a", "native ABI"),
        ):
            with self.subTest(field=field):
                result = self.validate_metadata("LOCUS_APK_" + field, value)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("pinned " + description, result.stderr)


class PrivateApkFingerprintTest(unittest.TestCase):
    def fingerprint(self, directory: Path) -> str:
        result = subprocess.run(
            ["python3", str(VALIDATOR), "--fingerprint-only", str(directory)],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout.strip()

    def test_fingerprint_is_location_independent_and_content_sensitive(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first = root / "first"
            second = root / "second"
            first.mkdir()
            second.mkdir()
            for target in (first, second):
                (target / "base.apk").write_bytes(b"base")
                (target / "split.apk").write_bytes(b"split")
            original = self.fingerprint(first)
            self.assertRegex(original, r"^[0-9a-f]{64}$")
            self.assertEqual(original, self.fingerprint(second))
            (second / "split.apk").write_bytes(b"changed")
            self.assertNotEqual(original, self.fingerprint(second))


if __name__ == "__main__":
    unittest.main()
