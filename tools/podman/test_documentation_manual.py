import json
import pathlib
import re
import struct
import unittest
import zlib


ROOT = pathlib.Path(__file__).resolve().parents[2]
SPHINX = ROOT / "docs" / "sphinx"


def png_dimensions(path: pathlib.Path) -> tuple[int, int]:
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        raise ValueError(f"not a PNG: {path}")
    return struct.unpack(">II", data[16:24])


def png_first_rgb(path: pathlib.Path) -> tuple[int, int, int]:
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"not a PNG: {path}")
    offset = 8
    image_data = bytearray()
    bit_depth = color_type = None
    while offset < len(data):
        length = struct.unpack(">I", data[offset : offset + 4])[0]
        chunk_type = data[offset + 4 : offset + 8]
        chunk = data[offset + 8 : offset + 8 + length]
        offset += 12 + length
        if chunk_type == b"IHDR":
            bit_depth, color_type = chunk[8:10]
        elif chunk_type == b"IDAT":
            image_data.extend(chunk)
        elif chunk_type == b"IEND":
            break
    if bit_depth != 8 or color_type not in {2, 6}:
        raise ValueError(f"unsupported PNG format in {path}")
    scanlines = zlib.decompress(image_data)
    if not scanlines or scanlines[0] not in range(5):
        raise ValueError(f"invalid first PNG scanline in {path}")
    return tuple(scanlines[1:4])


def contrast_ratio(first: str, second: str) -> float:
    def luminance(color: str) -> float:
        channels = [int(color[offset : offset + 2], 16) / 255 for offset in (1, 3, 5)]
        linear = [channel / 12.92 if channel <= 0.04045 else ((channel + 0.055) / 1.055) ** 2.4 for channel in channels]
        return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]

    lighter, darker = sorted((luminance(first), luminance(second)), reverse=True)
    return (lighter + 0.05) / (darker + 0.05)


class DocumentationManualTest(unittest.TestCase):
    def test_local_only_privacy_promise_and_boundaries_are_public_and_consistent(self):
        listing = json.loads((ROOT / "appstore" / "listing.json").read_text(encoding="utf-8"))
        surfaces = {
            "README": (ROOT / "README.md").read_text(encoding="utf-8"),
            "Features": (SPHINX / "features.rst").read_text(encoding="utf-8"),
            "Privacy": (SPHINX / "legal.rst").read_text(encoding="utf-8"),
            "App store": listing["description"],
        }

        for name, content in surfaces.items():
            normalized = re.sub(r"\s+", " ", content.lower())
            with self.subTest(surface=name):
                for promise in (
                    "local-only by design",
                    "trackglance server",
                    "account",
                    "analytics",
                    "hosted crash reporting",
                    "network permission",
                    "locus map",
                    "pebble app",
                    "user runtime data",
                ):
                    self.assertIn(promise, normalized)

        privacy = re.sub(r"\s+", " ", surfaces["Privacy"])
        for boundary in (
            "does not collect or transmit user data to a TrackGlance service",
            "at most 20 entries",
            "excluded from Android backup and device transfer",
            "locally and builds the settings screen as an offline page",
            "not the full profile catalog or recorded track",
            "own storage, synchronization, network, and privacy behavior",
            "external browser",
            "VirusTotal",
        ):
            self.assertIn(boundary, privacy)

    def test_navigation_starts_with_features_and_uses_one_user_guide(self):
        index = (SPHINX / "index.rst").read_text(encoding="utf-8")

        self.assertLess(index.index("   features"), index.index("   getting-started"))
        self.assertLess(index.index("   getting-started"), index.index("   user-guide"))
        self.assertFalse((SPHINX / "watchapp-options.rst").exists())
        self.assertFalse((SPHINX / "watch-settings.rst").exists())
        all_rst = "\n".join(
            path.read_text(encoding="utf-8") for path in SPHINX.glob("*.rst")
        )
        self.assertNotIn("watchapp-options", all_rst)
        self.assertNotIn(":doc:`watch-settings`", all_rst)

    def test_user_guide_sections_and_images_follow_the_manual_structure(self):
        guide = (SPHINX / "user-guide.rst").read_text(encoding="utf-8")
        sections = (
            "On-watch operation",
            "Activity pages",
            "Recording controls",
            "Heart rate",
            "Watch Settings",
            "General settings",
            "Editing an activity",
            "Save, cancel, and reset",
        )
        offsets = [guide.index(section) for section in sections]

        self.assertEqual(offsets, sorted(offsets))
        self.assertIn("screenshot_emery_dashboard.png", guide)
        self.assertNotIn("screenshot_emery_profiles.png", guide)
        self.assertEqual(guide.count(":width: 360px"), 3)
        for required in (
            "1 through 60",
            "defaults to 5 seconds",
            "Heart rate unavailable",
            "Pebble Round 2 does not offer watch-to-Locus forwarding",
        ):
            self.assertIn(required, guide)

    def test_general_settings_screenshot_is_generated_at_phone_resolution(self):
        screenshot = SPHINX / "_static" / "watch_settings_general.png"

        self.assertGreater(screenshot.stat().st_size, 10_000)
        self.assertEqual(png_dimensions(screenshot), (780, 1688))

    def test_features_finish_with_the_complete_watch_matrix(self):
        features = (SPHINX / "features.rst").read_text(encoding="utf-8")

        self.assertEqual(features.rsplit("\nFeature matrix\n", 1)[1].count("   * - "), 7)
        for feature in (
            "Dashboard and activity pages",
            "Pause, resume, and stop & save",
            "Quick waypoints",
            "Dictated waypoints",
            "Display heart rate supplied by Locus",
            "Forward watch heart rate to Locus",
        ):
            self.assertIn(feature, features)

    def test_manual_uses_consistent_light_phone_screenshots_and_public_watch_names(self):
        all_rst = "\n".join(
            path.read_text(encoding="utf-8") for path in SPHINX.glob("*.rst")
        )
        renderer = (ROOT / "docs" / "render_watch_settings_screenshots.js").read_text(
            encoding="utf-8"
        )

        self.assertEqual(all_rst.count("bridge_app_light.png"), 2)
        self.assertNotIn("bridge_app_dark.png", all_rst)
        self.assertNotIn("Emery", all_rst)
        self.assertNotIn("Gabbro", all_rst)
        self.assertIn("colorScheme: 'light'", renderer)
        self.assertNotIn("colorScheme: 'dark'", renderer)
        for filename in (
            "bridge_app_light.png",
            "watch_settings_overview.png",
            "watch_settings_general.png",
            "watch_settings_profile.png",
        ):
            with self.subTest(filename=filename):
                self.assertEqual(png_first_rgb(SPHINX / "_static" / filename), (244, 251, 246))

    def test_table_theme_overrides_cover_rows_captions_and_caption_permalinks(self):
        css = (SPHINX / "_static" / "custom.css").read_text(encoding="utf-8")

        self.assertIn("table.docutils:not(.field-list) thead tr th", css)
        self.assertIn("tbody tr:nth-child(odd) td", css)
        self.assertIn("tbody tr:nth-child(even) td", css)
        self.assertIn("table.docutils:not(.field-list) td a:visited", css)
        self.assertIn("table.docutils caption", css)
        self.assertIn("figure figcaption", css)
        self.assertIn("caption .headerlink:visited", css)
        self.assertIn("table-layout: fixed", css)
        self.assertIn("white-space: normal", css)
        self.assertIn("width: 100% !important", css)

        token_values: dict[str, list[str]] = {}
        for name, value in re.findall(
            r"--tg-(background|surface|text|primary):\s*(#[0-9a-fA-F]{6})", css
        ):
            token_values.setdefault(name, []).append(value)
        self.assertTrue(all(len(values) == 2 for values in token_values.values()))
        for index, theme in enumerate(("light", "dark")):
            with self.subTest(theme=theme):
                background = token_values["background"][index]
                self.assertGreaterEqual(contrast_ratio(token_values["text"][index], background), 4.5)
                self.assertGreaterEqual(contrast_ratio(token_values["primary"][index], background), 4.5)
                self.assertGreaterEqual(
                    contrast_ratio(token_values["text"][index], token_values["surface"][index]),
                    4.5,
                )

    def test_screenshot_policy_checklist_and_release_versions_stay_aligned(self):
        style_guide = (ROOT / "docs" / "style-guide.md").read_text(encoding="utf-8")
        development = (ROOT / "docs" / "development.md").read_text(encoding="utf-8")
        self.assertIn("Published phone-side screenshots use the light application theme", style_guide)
        for requirement in (
            "Getting Started",
            "Features",
            "Android Bridge Settings",
            "User Guide",
            "light and dark browser themes",
            "desktop and 390px mobile widths",
            "captions plus their permalinks",
            "size and aspect ratio",
            "overflow or clip",
        ):
            self.assertIn(requirement, development)

        sources = (
            (ROOT / "android" / "app" / "build.gradle.kts", r'versionName = "([^"]+)"'),
            (ROOT / "watchapp" / "package.json", r'"version": "([^"]+)"'),
            (ROOT / "watchapp" / "src" / "pkjs" / "index.js", r"RELEASE = '([^']+)'"),
            (SPHINX / "conf.py", r"release = '([^']+)'"),
        )
        versions = []
        for path, pattern in sources:
            match = re.search(pattern, path.read_text(encoding="utf-8"))
            self.assertIsNotNone(match, path)
            versions.append(match.group(1))
        self.assertEqual(len(set(versions)), 1, versions)


if __name__ == "__main__":
    unittest.main()
