import pathlib
import struct
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SPHINX = ROOT / "docs" / "sphinx"


def png_dimensions(path: pathlib.Path) -> tuple[int, int]:
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        raise ValueError(f"not a PNG: {path}")
    return struct.unpack(">II", data[16:24])


class DocumentationManualTest(unittest.TestCase):
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

    def test_manual_uses_only_the_dark_bridge_image_and_public_watch_names(self):
        all_rst = "\n".join(
            path.read_text(encoding="utf-8") for path in SPHINX.glob("*.rst")
        )

        self.assertEqual(all_rst.count("bridge_app_dark.png"), 2)
        self.assertNotIn("bridge_app_light.png", all_rst)
        self.assertNotIn("Emery", all_rst)
        self.assertNotIn("Gabbro", all_rst)

    def test_table_theme_overrides_cover_headers_and_both_row_parities(self):
        css = (SPHINX / "_static" / "custom.css").read_text(encoding="utf-8")

        self.assertIn("table.docutils:not(.field-list) thead tr th", css)
        self.assertIn("tbody tr:nth-child(odd) td", css)
        self.assertIn("tbody tr:nth-child(even) td", css)
        self.assertIn("table.docutils:not(.field-list) td a:visited", css)


if __name__ == "__main__":
    unittest.main()
