import json
import struct
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from manual_lab import (  # noqa: E402
    LabController,
    LabError,
    LabServer,
    PNG_SIGNATURE,
    ppm_to_png,
    validate_capture_name,
)


def png_dimensions(payload: bytes) -> tuple[int, int]:
    if not payload.startswith(PNG_SIGNATURE) or payload[12:16] != b"IHDR":
        raise AssertionError("not a PNG")
    return struct.unpack(">II", payload[16:24])


def png_pixels(payload: bytes) -> bytes:
    position = len(PNG_SIGNATURE)
    compressed = bytearray()
    while position < len(payload):
        length = struct.unpack(">I", payload[position : position + 4])[0]
        kind = payload[position + 4 : position + 8]
        data = payload[position + 8 : position + 8 + length]
        position += 12 + length
        if kind == b"IDAT":
            compressed.extend(data)
        if kind == b"IEND":
            break
    return zlib.decompress(compressed)


class PpmConversionTest(unittest.TestCase):
    def test_binary_ppm_becomes_rgb_png_without_losing_whitespace_pixels(self):
        pixels = b"\x20\x09\x0a\xff\x00\x80"
        png = ppm_to_png(b"P6\n# qemu\n2 1\n255\n" + pixels)

        self.assertEqual(png_dimensions(png), (2, 1))
        self.assertEqual(png_pixels(png), b"\0" + pixels)

    def test_invalid_or_truncated_ppm_is_rejected(self):
        for payload in (b"P3\n1 1\n255\n0 0 0", b"P6\n1 1\n255\n\0\0"):
            with self.subTest(payload=payload), self.assertRaises(LabError):
                ppm_to_png(payload)


class CaptureValidationTest(unittest.TestCase):
    def test_capture_names_are_short_flat_ascii_slugs(self):
        for accepted in ("smoke", "Emery-01", "menu.after_steps"):
            self.assertEqual(validate_capture_name(accepted), accepted)
        for rejected in ("", "../manual", "/tmp/file", "space name", "x" * 65, "ümlaut"):
            with self.subTest(name=rejected), self.assertRaises(LabError):
                validate_capture_name(rejected)

    def test_capture_refuses_to_overwrite_an_existing_png(self):
        with tempfile.TemporaryDirectory() as directory:
            controller = LabController(Path(directory), "emery", Path("/missing"), "missing")
            existing = Path(directory) / "smoke-android.png"
            existing.write_bytes(PNG_SIGNATURE)
            with self.assertRaises(FileExistsError):
                controller.capture("android", "smoke")
            self.assertEqual(existing.read_bytes(), PNG_SIGNATURE)


class RelayInputValidationTest(unittest.TestCase):
    def controller(self):
        controller = LabController(Path(tempfile.gettempdir()), "emery", Path("/missing"), "missing")
        requests = []
        controller.relay = requests.append
        return controller, requests

    def test_relay_inputs_use_existing_wire_limits(self):
        controller, requests = self.controller()
        controller.button("select")
        controller.heart_rate(255, "excellent")
        controller.steps(0x7FFFFFFF)
        self.assertEqual(requests[0], {"command": "button", "button": "select", "duration_ms": 100})
        self.assertEqual(requests[1], {"command": "heart_rate", "bpm": 255, "quality": "excellent"})
        self.assertEqual(requests[2], {"command": "steps", "count": 0x7FFFFFFF})

    def test_invalid_relay_inputs_never_reach_the_socket(self):
        controller, requests = self.controller()
        invalid = (
            lambda: controller.button("power"),
            lambda: controller.heart_rate(256, "excellent"),
            lambda: controller.heart_rate(60, "unknown"),
            lambda: controller.steps(-1),
            lambda: controller.steps(0x80000000),
        )
        for call in invalid:
            with self.subTest(call=call), self.assertRaises(LabError):
                call()
        self.assertEqual(requests, [])


class FakeController:
    def status(self):
        return {"ready": True, "platform": "emery"}

    def button(self, value):
        if value != "up":
            raise LabError("bad button")

    def heart_rate(self, _bpm, _quality):
        return None

    def steps(self, _count):
        return None

    def pebble_png(self):
        return PNG_SIGNATURE

    def capture(self, _kind, _name):
        return "smoke-pebble.png"

    def download(self, _name):
        return PNG_SIGNATURE


class HttpApiTest(unittest.TestCase):
    def setUp(self):
        self.server = LabServer(("127.0.0.1", 0), FakeController())
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def request(self, path, body=None, content_type="application/json"):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.base + path, data=data, headers={"Content-Type": content_type})
        return urllib.request.urlopen(request, timeout=2)

    def test_status_and_named_capture_download_are_local_http_resources(self):
        with self.request("/lab-api/status") as response:
            self.assertTrue(json.load(response)["ready"])
        with self.request("/lab-api/capture/pebble", {"name": "smoke"}) as response:
            result = json.load(response)
        self.assertEqual(result["download_url"], "/lab-api/captures/smoke-pebble.png")
        with self.request(result["download_url"]) as response:
            self.assertEqual(response.headers.get_content_type(), "image/png")
            self.assertIn("attachment", response.headers["Content-Disposition"])

    def test_api_rejects_invalid_content_and_unknown_buttons(self):
        with self.assertRaises(urllib.error.HTTPError) as invalid_content:
            self.request("/lab-api/button", {"button": "up"}, "text/plain")
        self.assertEqual(invalid_content.exception.code, 400)
        invalid_content.exception.close()

        with self.assertRaises(urllib.error.HTTPError) as invalid_button:
            self.request("/lab-api/button", {"button": "power"})
        self.assertEqual(invalid_button.exception.code, 400)
        invalid_button.exception.close()


if __name__ == "__main__":
    unittest.main()
