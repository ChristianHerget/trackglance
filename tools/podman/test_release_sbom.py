import json
import pathlib
import subprocess
import tempfile
import unittest
import zipfile


ROOT = pathlib.Path(__file__).resolve().parents[2]
RELEASE_SBOM = ROOT / "tools" / "release-sbom"
TIMESTAMP = "2026-09-02T08:00:00+02:00"


class ReleaseSbomTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name)
        self.artifact = self.root / "artifact.bin"
        self.artifact.write_bytes(b"signed release artifact")

    def tearDown(self):
        self.temporary.cleanup()

    def run_tool(self, *arguments):
        return subprocess.run(
            [str(RELEASE_SBOM), *map(str, arguments)],
            capture_output=True,
            text=True,
            check=False,
        )

    def test_normalizes_android_root_and_preserves_dependency_graph(self):
        source = self.root / "gradle.json"
        source.write_text(
            json.dumps(
                {
                    "bomFormat": "CycloneDX",
                    "specVersion": "1.6",
                    "metadata": {"component": {"bom-ref": "old", "name": "app"}},
                    "components": [
                        {
                            "type": "library",
                            "bom-ref": "pkg:maven/example/runtime@1.0",
                            "name": "runtime",
                            "version": "1.0",
                        }
                    ],
                    "dependencies": [
                        {"ref": "old", "dependsOn": ["pkg:maven/example/runtime@1.0"]},
                        {"ref": "pkg:maven/example/runtime@1.0", "dependsOn": []},
                    ],
                }
            ),
            encoding="utf-8",
        )
        output = self.root / "android.cdx.json"
        result = self.run_tool(
            "android",
            "--version",
            "1.2.3",
            "--artifact",
            self.artifact,
            "--timestamp",
            TIMESTAMP,
            "--input",
            source,
            "--output",
            output,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        bom = json.loads(output.read_text())
        self.assertEqual(bom["metadata"]["component"]["name"], "trackglance-bridge")
        self.assertEqual(bom["metadata"]["component"]["version"], "1.2.3")
        root = next(item for item in bom["dependencies"] if item["ref"].startswith("trackglance"))
        self.assertEqual(root["dependsOn"], ["pkg:maven/example/runtime@1.0"])

    def test_watch_has_no_third_party_runtime_components(self):
        package = self.root / "package.json"
        package.write_text(
            json.dumps({"name": "trackglance-watch", "version": "1.2.3", "dependencies": {}}),
            encoding="utf-8",
        )
        pbw = self.root / "watch.pbw"
        with zipfile.ZipFile(pbw, "w") as archive:
            archive.writestr(
                "appinfo.json",
                json.dumps({"targetPlatforms": ["emery", "gabbro"], "sdkVersion": "3"}),
            )
        output = self.root / "watch.cdx.json"
        arguments = (
            "watch",
            "--version",
            "1.2.3",
            "--artifact",
            pbw,
            "--timestamp",
            TIMESTAMP,
            "--package",
            package,
            "--pebble-sdk-version",
            "4.33.1",
            "--output",
            output,
        )
        first = self.run_tool(*arguments)
        self.assertEqual(first.returncode, 0, first.stderr)
        original = output.read_bytes()
        self.assertEqual(self.run_tool(*arguments).returncode, 0)
        self.assertEqual(output.read_bytes(), original)
        bom = json.loads(original)
        self.assertEqual(bom["components"], [])
        self.assertEqual(bom["dependencies"][0]["dependsOn"], [])
        properties = {
            item["name"]: item["value"] for item in bom["metadata"]["component"]["properties"]
        }
        self.assertEqual(properties["io.trackglance.pebble.sdkCompatibility"], "3")
        self.assertEqual(properties["io.trackglance.pebble.buildSdk"], "4.33.1")

    def test_watch_spdx_is_generated_directly_from_pbw_metadata(self):
        package = self.root / "package.json"
        package.write_text(
            json.dumps({"version": "1.2.3", "dependencies": {}}), encoding="utf-8"
        )
        pbw = self.root / "watch.pbw"
        with zipfile.ZipFile(pbw, "w") as archive:
            archive.writestr(
                "appinfo.json",
                json.dumps({"targetPlatforms": ["emery", "gabbro"], "sdkVersion": "3"}),
            )
        output = self.root / "watch.spdx.json"
        result = self.run_tool(
            "watch-spdx", "--version", "1.2.3", "--artifact", pbw,
            "--timestamp", TIMESTAMP, "--package", package,
            "--pebble-sdk-version", "4.33.1", "--output", output,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        document = json.loads(output.read_text())
        self.assertEqual(document["packages"][0]["name"], "trackglance-watch")
        self.assertIn("Pebble SDK 4.33.1", document["packages"][0]["comment"])
        self.assertIn("not shipped", document["packages"][0]["comment"])


if __name__ == "__main__":
    unittest.main()
