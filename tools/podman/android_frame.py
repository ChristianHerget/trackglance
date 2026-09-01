#!/usr/bin/env python3
"""Serve scaled Android emulator screenshots inside the private test pod."""

from __future__ import annotations

import argparse
import sys
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import grpc
from google.protobuf.empty_pb2 import Empty


PROTO_DIR = Path("/opt/aemu/gateway/src/videobridge_gateway/proto")
sys.path.insert(0, str(PROTO_DIR))

import emulator_controller_pb2 as emulator  # noqa: E402
import emulator_controller_pb2_grpc as emulator_grpc  # noqa: E402


PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def discovery_port(path: Path) -> int:
    for line in path.read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator and key == "grpc.port":
            return int(value)
    raise RuntimeError(f"grpc.port is missing from {path}")


class FrameHandler(BaseHTTPRequestHandler):
    server: "FrameServer"

    def log_message(self, _format: str, *_arguments: object) -> None:
        return

    def do_GET(self) -> None:  # noqa: N802
        if self.path.partition("?")[0] != "/frame.png":
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        try:
            image = self.server.client.getScreenshot(
                emulator.ImageFormat(
                    format=emulator.ImageFormat.PNG,
                    width=540,
                    height=1200,
                ),
                timeout=5,
            ).image
            if not image.startswith(PNG_SIGNATURE):
                raise RuntimeError("emulator screenshot is not a PNG")
            native_width, native_height = self.server.native_size()
        except (grpc.RpcError, RuntimeError) as error:
            self.send_error(HTTPStatus.SERVICE_UNAVAILABLE, str(error))
            return
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", "image/png")
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Android-Width", str(native_width))
        self.send_header("X-Android-Height", str(native_height))
        self.send_header("Content-Length", str(len(image)))
        self.end_headers()
        self.wfile.write(image)


class FrameServer(ThreadingHTTPServer):
    def __init__(self, address: tuple[str, int], grpc_port: int) -> None:
        super().__init__(address, FrameHandler)
        channel = grpc.insecure_channel(f"127.0.0.1:{grpc_port}")
        self.client = emulator_grpc.EmulatorControllerStub(channel)
        self._native_size: tuple[int, int] | None = None

    def native_size(self) -> tuple[int, int]:
        if self._native_size is None:
            displays = self.client.getDisplayConfigurations(Empty(), timeout=5).displays
            if not displays:
                raise RuntimeError("emulator has no active display")
            self._native_size = (displays[0].width, displays[0].height)
        return self._native_size


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--discovery-file",
        type=Path,
        default=Path("/run/trackglance/android-discovery.ini"),
    )
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8082)
    arguments = parser.parse_args()
    FrameServer(
        (arguments.host, arguments.port),
        discovery_port(arguments.discovery_file),
    ).serve_forever()


if __name__ == "__main__":
    main()
