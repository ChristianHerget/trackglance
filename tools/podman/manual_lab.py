#!/usr/bin/env python3
"""Local-only HTTP controller for the disposable emulator lab."""

from __future__ import annotations

import argparse
import json
import re
import socket
import struct
import subprocess
import threading
import time
import urllib.parse
import zlib
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


CAPTURE_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}\Z")
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
QUALITIES = {"off-wrist", "worst", "poor", "acceptable", "good", "excellent"}
BUTTONS = {"back", "up", "select", "down"}
MAX_REQUEST_BYTES = 4096


class LabError(Exception):
    """A request error that is safe to return to the local dashboard."""


def png_chunk(kind: bytes, payload: bytes) -> bytes:
    checksum = zlib.crc32(kind)
    checksum = zlib.crc32(payload, checksum)
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", checksum & 0xFFFFFFFF)


def ppm_to_png(data: bytes) -> bytes:
    """Convert the binary RGB PPM emitted by QEMU screendump to PNG."""

    position = 0

    def token() -> bytes:
        nonlocal position
        while position < len(data):
            if data[position : position + 1] == b"#":
                newline = data.find(b"\n", position)
                if newline < 0:
                    raise LabError("invalid PPM comment")
                position = newline + 1
            elif data[position : position + 1].isspace():
                position += 1
            else:
                break
        start = position
        while position < len(data) and not data[position : position + 1].isspace():
            position += 1
        if start == position:
            raise LabError("incomplete PPM header")
        return data[start:position]

    if token() != b"P6":
        raise LabError("QEMU screendump is not a binary RGB PPM")
    try:
        width = int(token())
        height = int(token())
        maximum = int(token())
    except ValueError as error:
        raise LabError("invalid PPM dimensions") from error
    if not 1 <= width <= 4096 or not 1 <= height <= 4096 or maximum != 255:
        raise LabError("unsupported PPM dimensions or color depth")
    if position >= len(data) or not data[position : position + 1].isspace():
        raise LabError("PPM header is not separated from pixel data")
    position += 1
    pixels = data[position:]
    expected = width * height * 3
    if len(pixels) != expected:
        raise LabError(f"invalid PPM pixel length: expected {expected}, found {len(pixels)}")
    rows = b"".join(b"\0" + pixels[offset : offset + width * 3] for offset in range(0, expected, width * 3))
    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return PNG_SIGNATURE + png_chunk(b"IHDR", header) + png_chunk(b"IDAT", zlib.compress(rows, 6)) + png_chunk(b"IEND", b"")


def validate_capture_name(value: object) -> str:
    if not isinstance(value, str) or not CAPTURE_NAME.fullmatch(value):
        raise LabError("name must be 1-64 ASCII letters, digits, dots, underscores, or hyphens")
    return value


class LabController:
    def __init__(self, artifacts: Path, platform: str, relay_socket: Path, adb_serial: str) -> None:
        self.artifacts = artifacts.resolve()
        self.platform = platform
        self.relay_socket = relay_socket
        self.adb_serial = adb_serial
        self.capture_lock = threading.Lock()
        self.status_lock = threading.Lock()
        self.cached_status: tuple[float, dict[str, object]] | None = None
        self.artifacts.mkdir(parents=True, exist_ok=True)

    def relay(self, request: dict[str, object]) -> dict[str, object]:
        try:
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
                client.settimeout(5)
                client.connect(str(self.relay_socket))
                client.sendall((json.dumps(request, separators=(",", ":")) + "\n").encode())
                response = json.loads(client.makefile(encoding="utf-8").readline())
        except (OSError, json.JSONDecodeError) as error:
            raise LabError(f"Pebble relay is unavailable: {error}") from error
        if not response.get("ok"):
            raise LabError(str(response.get("error", "Pebble relay rejected the request")))
        return response

    def adb(self, *arguments: str, timeout: int = 5) -> bytes:
        try:
            result = subprocess.run(
                ["adb", "-s", self.adb_serial, *arguments],
                check=True,
                capture_output=True,
                timeout=timeout,
            )
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError) as error:
            raise LabError(f"Android emulator command failed: {type(error).__name__}") from error
        return result.stdout

    def bridge_status(self, field: str) -> str | None:
        output = self.adb(
            "shell",
            "content",
            "query",
            "--uri",
            "content://app.trackglance.bridge.debug-status/status",
            "--projection",
            field,
        ).decode(errors="replace")
        prefix = f"Row: 0 {field}="
        for line in output.splitlines():
            if line.startswith(prefix):
                return line.removeprefix(prefix).strip()
        return None

    def status(self) -> dict[str, object]:
        with self.status_lock:
            now = time.monotonic()
            if self.cached_status and now - self.cached_status[0] < 1:
                return self.cached_status[1]
            status: dict[str, object] = {"platform": self.platform}
            try:
                status["android_ready"] = self.adb("shell", "getprop", "sys.boot_completed").strip() == b"1"
                for field in ("locus_available", "recording_state", "watch_connected", "watch_app_open"):
                    status[field] = self.bridge_status(field)
            except LabError as error:
                status.update(android_ready=False, android_error=str(error))
            try:
                relay = self.relay({"command": "status"})
                status["phone_connected"] = bool(relay.get("phone_connected"))
                status["qemu_connected"] = bool(relay.get("qemu_connected"))
            except LabError as error:
                status.update(phone_connected=False, qemu_connected=False, pebble_error=str(error))
            status["ready"] = bool(
                status.get("android_ready")
                and status.get("locus_available") == "true"
                and status.get("recording_state") == "STOPPED"
                and status.get("watch_connected") == "true"
                and status.get("watch_app_open") == "true"
                and status.get("phone_connected")
                and status.get("qemu_connected")
            )
            self.cached_status = (now, status)
            return status

    def button(self, value: object) -> None:
        if value not in BUTTONS:
            raise LabError("button must be back, up, select, or down")
        self.relay({"command": "button", "button": value, "duration_ms": 100})

    def heart_rate(self, bpm_value: object, quality_value: object) -> None:
        if isinstance(bpm_value, bool):
            raise LabError("heart rate must be an integer between 0 and 255")
        try:
            bpm = int(bpm_value)
        except (TypeError, ValueError) as error:
            raise LabError("heart rate must be an integer between 0 and 255") from error
        if bpm_value != bpm and not (isinstance(bpm_value, str) and bpm_value == str(bpm)):
            raise LabError("heart rate must be an integer between 0 and 255")
        if not 0 <= bpm <= 255:
            raise LabError("heart rate must be an integer between 0 and 255")
        if quality_value not in QUALITIES:
            raise LabError("unknown heart-rate quality")
        self.relay({"command": "heart_rate", "bpm": bpm, "quality": quality_value})

    def steps(self, count_value: object) -> None:
        if isinstance(count_value, bool):
            raise LabError("steps must be an integer between 0 and 2147483647")
        try:
            count = int(count_value)
        except (TypeError, ValueError) as error:
            raise LabError("steps must be an integer between 0 and 2147483647") from error
        if count_value != count and not (isinstance(count_value, str) and count_value == str(count)):
            raise LabError("steps must be an integer between 0 and 2147483647")
        if not 0 <= count <= 0x7FFFFFFF:
            raise LabError("steps must be an integer between 0 and 2147483647")
        self.relay({"command": "steps", "count": count})

    def pebble_png(self) -> bytes:
        with self.capture_lock:
            temporary = self.artifacts / f".manual-pebble-{threading.get_ident()}.ppm"
            try:
                with socket.create_connection(("127.0.0.1", 12348), timeout=3) as monitor:
                    monitor.sendall(f"screendump /artifacts/{temporary.name}\n".encode())
                deadline = time.monotonic() + 3
                while time.monotonic() < deadline:
                    if temporary.is_file() and temporary.stat().st_size > 16:
                        return ppm_to_png(temporary.read_bytes())
                    time.sleep(0.05)
                raise LabError("Pebble screendump did not finish")
            except OSError as error:
                raise LabError(f"Pebble QEMU monitor is unavailable: {error}") from error
            finally:
                temporary.unlink(missing_ok=True)

    def capture(self, kind: str, name_value: object) -> str:
        name = validate_capture_name(name_value)
        if kind not in {"android", "pebble"}:
            raise LabError("capture kind must be android or pebble")
        filename = f"{name}-{kind}.png"
        destination = self.artifacts / filename
        if destination.exists():
            raise FileExistsError(filename)
        payload = self.adb("exec-out", "screencap", "-p", timeout=30) if kind == "android" else self.pebble_png()
        if not payload.startswith(PNG_SIGNATURE):
            raise LabError(f"{kind} capture is not a PNG")
        temporary = self.artifacts / f".{filename}.partial"
        try:
            with temporary.open("xb") as output:
                output.write(payload)
            temporary.replace(destination)
        finally:
            temporary.unlink(missing_ok=True)
        return filename

    def download(self, filename: str) -> bytes:
        if Path(filename).name != filename or not filename.endswith(("-android.png", "-pebble.png")):
            raise LabError("invalid capture path")
        path = self.artifacts / filename
        if not path.is_file():
            raise FileNotFoundError(filename)
        payload = path.read_bytes()
        if not payload.startswith(PNG_SIGNATURE):
            raise LabError("capture is not a PNG")
        return payload


class LabHandler(BaseHTTPRequestHandler):
    server: "LabServer"

    def log_message(self, _format: str, *_arguments: object) -> None:
        return

    def json_response(self, status: HTTPStatus, value: dict[str, object]) -> None:
        payload = json.dumps(value, sort_keys=True).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def error_response(self, status: HTTPStatus, error: Exception | str) -> None:
        self.json_response(status, {"ok": False, "error": str(error)})

    def request_json(self) -> dict[str, object]:
        if self.headers.get_content_type() != "application/json":
            raise LabError("request body must be application/json")
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError as error:
            raise LabError("invalid Content-Length") from error
        if not 1 <= length <= MAX_REQUEST_BYTES:
            raise LabError(f"request body must be between 1 and {MAX_REQUEST_BYTES} bytes")
        try:
            value = json.loads(self.rfile.read(length))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise LabError("request body must be valid JSON") from error
        if not isinstance(value, dict):
            raise LabError("request body must be a JSON object")
        return value

    def do_GET(self) -> None:  # noqa: N802
        path = urllib.parse.urlparse(self.path).path
        try:
            if path == "/lab-api/status":
                self.json_response(HTTPStatus.OK, {"ok": True, **self.server.controller.status()})
            elif path == "/lab-api/pebble-frame.png":
                payload = self.server.controller.pebble_png()
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", "image/png")
                self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
            elif path.startswith("/lab-api/captures/"):
                filename = urllib.parse.unquote(path.removeprefix("/lab-api/captures/"))
                payload = self.server.controller.download(filename)
                self.send_response(HTTPStatus.OK)
                self.send_header("Content-Type", "image/png")
                self.send_header("Content-Disposition", f'attachment; filename="{filename}"')
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
            else:
                self.error_response(HTTPStatus.NOT_FOUND, "unknown lab endpoint")
        except FileNotFoundError as error:
            self.error_response(HTTPStatus.NOT_FOUND, error)
        except LabError as error:
            self.error_response(HTTPStatus.SERVICE_UNAVAILABLE, error)

    def do_POST(self) -> None:  # noqa: N802
        path = urllib.parse.urlparse(self.path).path
        try:
            request = self.request_json()
            if path == "/lab-api/button":
                self.server.controller.button(request.get("button"))
                response: dict[str, object] = {"ok": True}
            elif path == "/lab-api/heart-rate":
                self.server.controller.heart_rate(request.get("bpm"), request.get("quality"))
                response = {"ok": True}
            elif path == "/lab-api/steps":
                self.server.controller.steps(request.get("count"))
                response = {"ok": True}
            elif path.startswith("/lab-api/capture/"):
                kind = path.removeprefix("/lab-api/capture/")
                filename = self.server.controller.capture(kind, request.get("name"))
                response = {"ok": True, "filename": filename, "download_url": f"/lab-api/captures/{filename}"}
            else:
                self.error_response(HTTPStatus.NOT_FOUND, "unknown lab endpoint")
                return
            self.json_response(HTTPStatus.OK, response)
        except FileExistsError as error:
            self.error_response(HTTPStatus.CONFLICT, f"capture already exists: {error}")
        except LabError as error:
            self.error_response(HTTPStatus.BAD_REQUEST, error)


class LabServer(ThreadingHTTPServer):
    def __init__(self, address: tuple[str, int], controller: LabController) -> None:
        super().__init__(address, LabHandler)
        self.controller = controller


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifacts", type=Path, default=Path("/artifacts"))
    parser.add_argument("--platform", choices=("emery", "gabbro"), required=True)
    parser.add_argument("--relay-socket", type=Path, default=Path("/run/trackglance/relay.sock"))
    parser.add_argument("--adb-serial", default="127.0.0.1:5555")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8081)
    arguments = parser.parse_args()
    controller = LabController(arguments.artifacts, arguments.platform, arguments.relay_socket, arguments.adb_serial)
    server = LabServer((arguments.host, arguments.port), controller)
    server.serve_forever()


if __name__ == "__main__":
    main()
