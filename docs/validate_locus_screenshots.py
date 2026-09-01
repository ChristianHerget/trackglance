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
paths = [
    output_dir / "locus_menu_all_features.png",
    output_dir / "locus_all_features_add_ons.png",
    output_dir / "locus_trackglance_pin_to_map.png",
    output_dir / "locus_trackglance_map_button.png",
]
for path in paths:
    if not path.is_file() or path.stat().st_size < 50_000:
        raise ValueError(f"missing or implausibly small Locus setup screenshot: {path}")
    dimensions = png_dimensions(path)
    if dimensions != (1080, 2400):
        raise ValueError(f"Locus setup screenshot has unexpected dimensions: {path} {dimensions}")

digests = {hashlib.sha256(path.read_bytes()).digest() for path in paths}
if len(digests) != len(paths):
    raise ValueError("Locus setup screenshots must be distinct")

print(f"Verified {len(paths)} Locus setup screenshots at 1080x2400.")
