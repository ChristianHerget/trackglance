#!/usr/bin/env python3
import hashlib
import pathlib
import struct
import sys


def png_dimensions(path: pathlib.Path) -> tuple[int, int]:
    data = path.read_bytes()
    if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        raise ValueError(f"{path} is not a valid PNG")
    return struct.unpack(">II", data[16:24])


output_dir = pathlib.Path(sys.argv[1])
paths = [output_dir / "bridge_app_light.png", output_dir / "bridge_app_dark.png"]
for path in paths:
    if not path.is_file() or path.stat().st_size < 10_000:
        raise ValueError(f"missing or implausibly small Android Bridge screenshot: {path}")

dimensions = [png_dimensions(path) for path in paths]
if dimensions[0] != dimensions[1]:
    raise ValueError(f"light/dark screenshot dimensions differ: {dimensions}")
if dimensions[0][0] < 720 or dimensions[0][1] < 1280:
    raise ValueError(f"Android Bridge screenshots are too small: {dimensions[0]}")

digests = [hashlib.sha256(path.read_bytes()).digest() for path in paths]
if digests[0] == digests[1]:
    raise ValueError("light and dark Android Bridge screenshots are identical")

print(f"Verified Android Bridge screenshots at {dimensions[0][0]}x{dimensions[0][1]}.")
