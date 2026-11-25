import os
import subprocess
import tempfile
import unittest
from pathlib import Path

from script_test_support import ROOT


class SyntheticPreflightTest(unittest.TestCase):
    def run_android(self, preflight_status=0, runtime_status=0):
        with tempfile.TemporaryDirectory() as directory:
            calls = Path(directory) / "calls"
            script = r'''
                source "$SYNTHETIC_SCRIPT"
                record() { printf '%s\n' "$*" >> "$CALLS"; }
                prepare_synthetic_tools() { record tools; }
                check_stage() { record "stage $*"; }
                run_static_container() {
                  record "preflight $*"
                  if (( PREFLIGHT_STATUS != 0 )); then
                    echo 'Dependency requires compileSdk 37.1; current SDK is 37.0' >&2
                  fi
                  return "$PREFLIGHT_STATUS"
                }
                select_acceptance_engine() { record engine; }
                ensure_cache_volumes() { record caches; }
                acceptance_image_exists() { record image; }
                new_run() { RUN_ID=test; RUN_ARTIFACTS=/unused; record run; }
                acceptance_engine() { record "engine $*"; }
                create_test_pod() { record pod; }
                start_android() { record emulator; }
                runner_in_pod() { record runtime; return "$RUNTIME_STATUS"; }
                synthetic_android
            '''
            result = subprocess.run(
                ["bash", "-euo", "pipefail", "-c", script],
                env={
                    **os.environ,
                    "SYNTHETIC_SCRIPT": str(ROOT / "tools/podman/synthetic.sh"),
                    "CALLS": str(calls),
                    "PREFLIGHT_STATUS": str(preflight_status),
                    "RUNTIME_STATUS": str(runtime_status),
                    "CI_PUBLISHED_TOOLS": "false",
                    "EMULATOR_IMAGE": "fake-emulator",
                },
                capture_output=True,
                text=True,
                check=False,
            )
            return result, calls.read_text()

    def test_incompatible_dependencies_preserve_failure_without_provisioning(self):
        result, calls = self.run_android(preflight_status=47)
        self.assertEqual(result.returncode, 47, result.stderr)
        self.assertIn("requires compileSdk 37.1", result.stderr)
        self.assertIn("stage android-dependency-compatibility", calls)
        for forbidden in ("stage android-provisioning", "engine", "caches", "pod", "emulator", "runtime"):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, calls.splitlines())

    def test_compatible_dependencies_check_every_variant_before_starting_emulator(self):
        result, calls = self.run_android()
        self.assertEqual(result.returncode, 0, result.stderr)
        for variant in ("Debug", "DebugAndroidTest", "Release"):
            with self.subTest(variant=variant):
                self.assertIn(f":android:app:check{variant}AarMetadata", calls)
        self.assertIn("tools/podman/check-gradle.py", calls)
        self.assertEqual(calls.count("preflight "), 1)
        self.assertLess(calls.index("preflight "), calls.index("stage android-provisioning"))
        self.assertLess(calls.splitlines().index("emulator"), calls.splitlines().index("runtime"))

    def test_successful_preflight_does_not_hide_runtime_failure(self):
        result, calls = self.run_android(runtime_status=53)
        self.assertEqual(result.returncode, 53, result.stderr)
        self.assertIn("runtime\n", calls)
