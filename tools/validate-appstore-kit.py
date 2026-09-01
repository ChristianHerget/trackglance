#!/usr/bin/env python3
import json
import pathlib
import struct


ROOT = pathlib.Path(__file__).resolve().parents[1]
LISTING = ROOT / "appstore/listing.json"
EXPECTED_LANGUAGES = {"en_US", "fr_FR", "de_DE", "es_ES", "it_IT", "pt_PT", "zh_CN", "zh_TW"}
EXPECTED_ICONS = {
    "48x48": "artwork/pebble_appstore_icon_48x48.png",
    "144x144": "artwork/pebble_appstore_icon_144x144.png",
}
EXPECTED_BANNER_SIZE = (720, 320)


def png_size(path):
    with path.open("rb") as stream:
        header = stream.read(24)
    if header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
        raise ValueError(f"not a PNG: {path}")
    return struct.unpack(">II", header[16:24])


def main():
    listing = json.loads(LISTING.read_text(encoding="utf-8"))
    required = {"name", "version", "category", "summary", "description", "sourceUrl", "supportUrl", "releaseUrl", "documentationUrl", "platforms", "languages", "icons", "banner", "screenshots", "releaseNotes"}
    missing = required - listing.keys()
    if missing:
        raise ValueError(f"missing listing fields: {sorted(missing)}")
    package_version = json.loads((ROOT / "watchapp/package.json").read_text())["version"]
    if listing["version"] != package_version:
        raise ValueError("listing and package versions differ")
    if len(listing["summary"]) > 140 or len(listing["description"]) > 1600:
        raise ValueError("listing copy exceeds portal limits")
    if set(listing["platforms"]) != {"emery", "gabbro"}:
        raise ValueError("listing must cover both supported watches")
    if set(listing["languages"]) != EXPECTED_LANGUAGES:
        raise ValueError("listing language set is incomplete")
    if listing["icons"] != EXPECTED_ICONS:
        raise ValueError("listing must reference the 48x48 and 144x144 app-store icons")
    for dimensions, relative in listing["icons"].items():
        expected = tuple(map(int, dimensions.split("x")))
        if png_size(ROOT / relative) != expected:
            raise ValueError(f"wrong dimensions for {relative}")
    if png_size(ROOT / listing["banner"]) != EXPECTED_BANNER_SIZE:
        raise ValueError(f"wrong dimensions for {listing['banner']}")
    for platform, relative in listing["screenshots"].items():
        if not (ROOT / relative).is_file():
            raise ValueError(f"missing {platform} screenshot: {relative}")
        png_size(ROOT / relative)
    print("Pebble app-store submission kit is complete")


if __name__ == "__main__":
    main()
