import subprocess
import unittest
from script_test_support import (
    ROOT,
    PODMAN_TEST,
    DEVICE_LIB,
)


E2E_STAGE = ROOT / "tools" / "podman" / "e2e-stage.sh"

RELEASE_METADATA = ROOT / "tools" / "podman" / "release-metadata.sh"

# These integration assertions cover UI/AppMessage orchestration that needs the full
# Android and watch environment. The warm suite executes those flows; device permissions,
# location setup, and cleanup have direct shell-fake tests in their focused modules.


class AcceptanceOrchestrationTest(unittest.TestCase):
    def test_headless_acceptance_build_does_not_repeat_the_static_suite(self):
        source = PODMAN_TEST.read_text(encoding="utf-8")
        build = source.split("build_acceptance_all() {", 1)[1].split("\n}", 1)[0]
        self.assertIn("build_project_inputs false", build)

    def test_acceptance_relaunches_locus_after_each_cold_boot(self):
        podman_test = PODMAN_TEST.read_text(encoding="utf-8")
        device_lib = DEVICE_LIB.read_text(encoding="utf-8")
        android_body = podman_test.split("run_android_tests() {", 1)[1].split("\n}", 1)[0]
        e2e_stage = E2E_STAGE.read_text(encoding="utf-8")
        launch = "foreground_locus"
        uninstall_bridge = "adb_device uninstall app.trackglance.bridge"
        self.assertIn(launch, android_body)
        self.assertIn(launch, e2e_stage)
        self.assertLess(android_body.index(launch), android_body.index("set_emulator_test_location"))
        self.assertIn("grant_locus_test_permissions", android_body)
        self.assertIn("grant_locus_test_permissions", e2e_stage)
        self.assertIn(uninstall_bridge, android_body)
        self.assertIn(uninstall_bridge, e2e_stage)
        self.assertGreaterEqual(podman_test.count(uninstall_bridge), 2)
        foreground = device_lib.split("foreground_locus() {", 1)[1].split("\n}", 1)[0]
        self.assertIn("topResumedActivity=", foreground)
        self.assertIn("LOCUS_FOREGROUND_SETTLE_SECONDS:-10", foreground)
        bootstrap = podman_test.split("bootstrap() {", 1)[1].split("\n}", 1)[0]
        self.assertLess(
            bootstrap.index("complete_locus_onboarding 90"),
            bootstrap.index("foreground_locus 30"),
        )

    def test_acceptance_uses_the_manifest_activity_class_not_the_application_id(self):
        podman_test = PODMAN_TEST.read_text(encoding="utf-8")
        e2e_stage = E2E_STAGE.read_text(encoding="utf-8")
        qualified = (
            "app.trackglance.bridge/"
            "io.github.christianherget.trackglance.bridge.MainActivity"
        )
        self.assertIn(qualified, podman_test)
        self.assertIn(qualified, e2e_stage)
        self.assertNotIn("app.trackglance.bridge/.MainActivity", e2e_stage)

    def test_e2e_sideloads_the_pbw_with_coreapps_private_selinux_label(self):
        e2e_stage = E2E_STAGE.read_text(encoding="utf-8")
        self.assertIn("push \"$pbw\" /data/local/tmp/trackglance.pbw", e2e_stage)
        self.assertIn(
            "run-as coredevices.coreapp \\\n  cp /data/local/tmp/trackglance.pbw cache/trackglance.pbw",
            e2e_stage,
        )
        self.assertIn(
            "file:///data/user/0/coredevices.coreapp/cache/trackglance.pbw",
            e2e_stage,
        )
        self.assertNotIn("/sdcard/Android/data/coredevices.coreapp/cache", e2e_stage)

    def test_e2e_polls_until_the_watch_settings_webview_is_rendered(self):
        e2e_stage = E2E_STAGE.read_text(encoding="utf-8")
        self.assertIn("open_trackglance_settings", e2e_stage)
        self.assertIn("general_deadline=$((SECONDS + 30))", e2e_stage)
        self.assertIn('tap_text "General settings" 30', e2e_stage)
        self.assertIn("grep -Fq 'resource-id=\"theme\"'", e2e_stage)

    def test_e2e_commits_general_edits_before_saving_the_overview(self):
        e2e_stage = E2E_STAGE.read_text(encoding="utf-8")
        branches = e2e_stage.split('if [[ "$PEBBLE_PLATFORM" == "emery" ]]', 1)[1]
        emery, gabbro = branches.split("\nelse\n", 1)
        self.assertLess(emery.index('tap_text "Done"'), emery.index('tap_text "Save"'))

        self.assertLess(gabbro.index('tap_text "Done"'), gabbro.index('tap_text "Save"'))

    def test_e2e_starts_recording_through_the_debug_only_locus_api_surface(self):
        e2e_stage = E2E_STAGE.read_text(encoding="utf-8")
        self.assertIn("--method acceptance-start-recording", e2e_stage)
        self.assertIn("recording_profile=$(cut -d'|' -f2", e2e_stage)
        self.assertNotIn("adb_device shell input tap 73 1992", e2e_stage)

    def test_emery_retries_the_streamed_heart_rate_during_locus_ingestion(self):
        e2e_stage = E2E_STAGE.read_text(encoding="utf-8")
        heart_rate = "relayctl heart-rate 123 --quality excellent"
        locus_foreground = "foreground_locus"
        self.assertIn(locus_foreground, e2e_stage)
        emery = e2e_stage.split('if [[ "$PEBBLE_PLATFORM" == "emery" ]]', 1)[1]
        step_flow = e2e_stage.split("run_step_acceptance() {", 1)[1].split("\n}", 1)[0]
        self.assertLess(
            step_flow.index(locus_foreground),
            step_flow.index("set_emulator_test_location"),
        )
        self.assertLess(emery.index("run_step_acceptance"), emery.index(heart_rate))
        self.assertIn("heart_rate_deadline=$((SECONDS + 20))", e2e_stage)
        self.assertIn("watch_heart_rate_deadline=$((SECONDS + 30))", e2e_stage)
        self.assertIn("heart_rate_deadline=$((SECONDS + 20))", e2e_stage)


    def test_emery_and_gabbro_exercise_deterministic_watch_steps(self):
        e2e_stage = E2E_STAGE.read_text(encoding="utf-8")
        step_flow = e2e_stage.split("run_step_acceptance() {", 1)[1].split("\n}", 1)[0]
        for expected in (
            "relayctl steps 1000",
            "wait_status watch_steps 0",
            "relayctl steps 1012",
            "wait_status watch_steps 12",
            "wait_status recording_state PAUSED",
            "relayctl steps 5",
            "wait_status watch_steps 17",
            "wait_status watch_steps NULL",
            'watch_screenshot "${PEBBLE_PLATFORM}-steps-unavailable"',
            'watch_screenshot "${PEBBLE_PLATFORM}-steps-recovered"',
        ):
            self.assertIn(expected, step_flow)
        branches = e2e_stage.split('if [[ "$PEBBLE_PLATFORM" == "emery" ]]', 1)[1]
        emery, gabbro = branches.split("\nelse\n", 1)
        self.assertIn("run_step_acceptance", emery)
        self.assertIn("run_step_acceptance", gabbro)

    def test_static_path_does_not_require_acceptance_inputs(self):
        source = PODMAN_TEST.read_text(encoding="utf-8")
        static_body = source.split("static_tests() {", 1)[1].split("\n}", 1)[0]
        self.assertIn("doctor_static", static_body)
        self.assertIn("static_image_exists || build_static_image", static_body)
        self.assertIn("run_static_container", static_body)
        for forbidden in ("require_images", "require_current_golden", "/dev/kvm", "LOCUS_INPUT_DIR"):
            self.assertNotIn(forbidden, static_body)

    def test_acceptance_suite_shares_warm_and_fresh_test_stages(self):
        source = PODMAN_TEST.read_text(encoding="utf-8")
        suite = source.split("acceptance_suite() {", 1)[1].split("\nclean() {", 1)[0]
        self.assertIn("build_acceptance_all", suite)
        self.assertIn("ACCEPTANCE_BOOTSTRAP_AUTOMATED=1 bootstrap", suite)
        self.assertIn('run_android_tests "$locus_dir"', suite)
        self.assertIn('e2e_tests "$locus_dir"', suite)
        self.assertIn("export LOCUS_APKS_DIR=$locus_dir", suite)
        self.assertIn("for ((pass=1; pass <= watch_passes; pass++))", suite)
        self.assertNotIn("run_android_tests \"$locus_dir\" || run_android_tests", suite)
        self.assertNotIn("e2e_tests \"$locus_dir\" || e2e_tests", suite)

    def test_acceptance_suite_rejects_more_than_two_watch_passes(self):
        result = subprocess.run(
            [
                "bash", str(PODMAN_TEST), "acceptance-suite",
                "--watch-passes", "3", "--locus-apks", "/tmp",
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--watch-passes must be 1 or 2", result.stdout + result.stderr)

    def test_acceptance_release_expectations_come_from_package_metadata(self):
        result = subprocess.run(
            [
                "bash", "-euo", "pipefail", "-c",
                'source "$1"; load_release_metadata "$2"; '
                'printf "%s|%s|%s|%s\\n" "$RELEASE_ANDROID_VERSION" '
                '"$RELEASE_ANDROID_CODE" "$RELEASE_WATCH_VERSION" "$RELEASE_PROTOCOL_VERSION"',
                "release-metadata", str(RELEASE_METADATA), str(ROOT),
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        android_version, android_code, watch_version, protocol_version = result.stdout.strip().split("|")
        self.assertEqual(android_version, watch_version)
        self.assertTrue(android_code.isdecimal())
        self.assertTrue(protocol_version.isdecimal())
        e2e_stage = E2E_STAGE.read_text(encoding="utf-8")
        for variable in (
            "RELEASE_ANDROID_VERSION", "RELEASE_ANDROID_CODE",
            "RELEASE_WATCH_VERSION", "RELEASE_PROTOCOL_VERSION",
        ):
            self.assertIn(f'"${variable}"', e2e_stage)

    def test_acceptance_doctor_reports_provisioning_without_burdening_static(self):
        source = PODMAN_TEST.read_text(encoding="utf-8")
        acceptance_body = source.split("doctor_acceptance() {", 1)[1].split("\n}", 1)[0]
        report_body = source.split("report_acceptance_provisioning() {", 1)[1].split("\n}", 1)[0]
        self.assertIn("report_acceptance_provisioning", acceptance_body)
        self.assertIn("$EMULATOR_IMAGE", report_body)
        self.assertIn("$WEB_IMAGE", report_body)
        self.assertIn("LOCUS_APKS_DIR", report_body)
        self.assertIn("$GOLDEN_VOLUME", report_body)

    def test_golden_marker_covers_every_material_bootstrap_input(self):
        source = PODMAN_TEST.read_text(encoding="utf-8")
        required = (
            "bootstrap_schema=",
            "emulator_config=",
            "system_image=",
            "core_apk=",
            "bridge=$RELEASE_VERSION",
            "locus_fixture_config=",
            "locus=",
        )
        for field in required:
            self.assertGreaterEqual(source.count(field), 2, field)
        bootstrap = source.split("bootstrap() {", 1)[1].split("\n}", 1)[0]
        self.assertLess(
            bootstrap.index("wait_nonempty_status locus_profiles"),
            bootstrap.index("> /golden/.trackglance-bootstrap"),
        )

    def test_bootstrap_closes_locus_and_waits_for_guest_shutdown_before_reuse(self):
        source = PODMAN_TEST.read_text(encoding="utf-8")
        bootstrap = source.split("bootstrap() {", 1)[1].split("\n}", 1)[0]
        force_stop = "adb_device shell am force-stop menion.android.locus"
        sync = "adb_device shell sync"
        marker = "> /golden/.trackglance-bootstrap"
        container_stop = "stop_active_android_gracefully"

        self.assertLess(bootstrap.index(force_stop), bootstrap.index(sync))
        self.assertLess(bootstrap.index(sync), bootstrap.index(marker))
        self.assertLess(bootstrap.index(marker), bootstrap.index(container_stop))
        self.assertNotIn("adb_device emu kill", bootstrap)

        stop_helper = source.split("stop_active_android_gracefully() {", 1)[1].split(
            "\n}", 1
        )[0]
        self.assertIn('stop --time 60 "$ACTIVE_ANDROID_CONTAINER"', stop_helper)
        self.assertIn("{{.State.Status}}", stop_helper)
