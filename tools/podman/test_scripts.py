import os
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PODMAN_TEST = ROOT / "tools" / "podman-test"
DEVICE_LIB = ROOT / "tools" / "podman" / "device-lib.sh"
APK_VALIDATOR = ROOT / "tools" / "podman" / "validate-locus-apks.py"
GENERATOR_CONTAINERFILE = ROOT / "tools" / "podman" / "Containerfile.generator"
WEB_CONTAINERFILE = ROOT / "tools" / "podman" / "Containerfile.web"
EMULATOR_PATH_PATCH = ROOT / "tools" / "podman" / "patches" / "android-emulator-dest-path.patch"
EMULATOR_ENTRYPOINT = ROOT / "tools" / "podman" / "android-emulator-entrypoint.sh"
EMULATOR_CONSOLE = ROOT / "tools" / "podman" / "emulator-console.py"
E2E_STAGE = ROOT / "tools" / "podman" / "e2e-stage.sh"


class DeviceReadinessTest(unittest.TestCase):
    def test_tap_text_initializes_its_timeout_before_deadline_expansion(self):
        environment = {**os.environ, "DEVICE_LIB": str(DEVICE_LIB)}
        script = textwrap.dedent(
            """\
            source "$DEVICE_LIB"
            dump_ui() { :; }
            python3() { printf '10 20\n'; }
            adb_device() { test "$*" = "shell input tap 10 20"; }
            tap_text Start 1
            """
        )
        result = subprocess.run(
            ["bash", "-euo", "pipefail", "-c", script],
            env=environment,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_wait_for_android_retries_a_failed_initial_connect(self):
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            state = temporary / "adb-state"
            (temporary / "timeout").write_text("#!/bin/sh\nshift\nexec \"$@\"\n", encoding="utf-8")
            (temporary / "sleep").write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            (temporary / "adb").write_text(
                textwrap.dedent(
                    """\
                    #!/bin/sh
                    if [ "$1" = connect ]; then
                      count=0
                      [ ! -f "$ADB_STATE.count" ] || count=$(cat "$ADB_STATE.count")
                      count=$((count + 1))
                      printf '%s\n' "$count" > "$ADB_STATE.count"
                      if [ "$count" -eq 1 ]; then
                        echo 'failed to connect' >&2
                        exit 0
                      fi
                      : > "$ADB_STATE"
                      exit 0
                    fi
                    if [ "$1" = -s ] && [ "$3" = shell ] && [ "$4" = getprop ]; then
                      [ -f "$ADB_STATE" ] || exit 1
                      printf '1\r\n'
                      exit 0
                    fi
                    if [ "$1" = -s ] && [ "$3" = shell ] && [ "$4" = settings ]; then
                      exit 0
                    fi
                    if [ "$1" = -s ] && [ "$3" = shell ] && { [ "$4" = appops ] || [ "$4" = cmd ]; }; then
                      exit 0
                    fi
                    exit 1
                    """
                ),
                encoding="utf-8",
            )
            for executable in ("timeout", "sleep", "adb"):
                (temporary / executable).chmod(0o755)
            environment = {
                **os.environ,
                "ADB_STATE": str(state),
                "PATH": f"{temporary}:{os.environ['PATH']}",
                "DEVICE_LIB": str(DEVICE_LIB),
            }
            result = subprocess.run(
                [
                    "bash", "-euo", "pipefail", "-c",
                    'source "$DEVICE_LIB"; set_emulator_test_location() { :; }; wait_for_android 2',
                ],
                env=environment,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((temporary / "adb-state.count").read_text().strip(), "2")

    def test_wait_for_android_sets_wartburg_through_the_emulator_console(self):
        source = DEVICE_LIB.read_text(encoding="utf-8")
        self.assertIn("EMULATOR_TEST_LATITUDE=50.9662", source)
        self.assertIn("EMULATOR_TEST_LONGITUDE=10.3065", source)
        self.assertIn("emulator-console.py", source)
        self.assertIn("providers remove-test-provider gps", source)
        readiness = source.split("wait_for_android() {", 1)[1].split("\n}", 1)[0]
        self.assertIn("set_emulator_test_location", readiness)

    def test_emulator_console_token_stays_in_the_private_runtime_volume(self):
        entrypoint = EMULATOR_ENTRYPOINT.read_text(encoding="utf-8")
        helper = EMULATOR_CONSOLE.read_text(encoding="utf-8")
        self.assertIn("/run/locuspebble/emulator-console-auth-token", entrypoint)
        self.assertIn('socket.create_connection(("127.0.0.1", 5556)', helper)
        self.assertIn("geo fix", helper)

    def test_locus_acceptance_permissions_include_the_device_idle_allowlist(self):
        source = DEVICE_LIB.read_text(encoding="utf-8")
        permissions = source.split("grant_locus_test_permissions() {", 1)[1].split("\n}", 1)[0]
        self.assertIn("dumpsys deviceidle whitelist +menion.android.locus", permissions)
        self.assertIn("android.permission.ACCESS_BACKGROUND_LOCATION", permissions)
        self.assertIn("android.permission.READ_EXTERNAL_STORAGE", permissions)
        self.assertIn("android.permission.WRITE_EXTERNAL_STORAGE", permissions)

    def test_coreapp_onboarding_grants_only_its_notification_listener(self):
        source = DEVICE_LIB.read_text(encoding="utf-8")
        permissions = source.split("grant_coreapp_test_permissions() {", 1)[1].split("\n}", 1)[0]
        self.assertIn("cmd notification allow_listener", permissions)
        self.assertIn("LibPebbleNotificationListener", permissions)


class CleanupScopeTest(unittest.TestCase):
    def test_clean_revalidates_every_pod_and_volume_prefix(self):
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            log = temporary / "removed"
            environment = {
                **os.environ,
                "PODMAN_TEST_SCRIPT": str(PODMAN_TEST),
                "PODMAN_REMOVAL_LOG": str(log),
                "EMPTY_BUILD_ROOT": str(temporary / "absent"),
            }
            script = textwrap.dedent(
                """\
                source "$PODMAN_TEST_SCRIPT"
                BUILD_ROOT=$EMPTY_BUILD_ROOT
                podman() {
                  if [[ "$1 $2" == "pod ps" ]]; then
                    printf '%s\n' locuspebble-owned backup-locuspebble-old
                  elif [[ "$1 $2" == "pod rm" ]]; then
                    printf 'pod:%s\n' "$4" >> "$PODMAN_REMOVAL_LOG"
                  elif [[ "$1 $2" == "volume ls" ]]; then
                    printf '%s\n' locuspebble-cache backup-locuspebble-cache
                  elif [[ "$1 $2" == "volume rm" ]]; then
                    printf 'volume:%s\n' "$4" >> "$PODMAN_REMOVAL_LOG"
                  elif [[ "$1 $2" == "image exists" || "$1 $2" == "network exists" ]]; then
                    return 1
                  fi
                }
                clean
                """
            )
            result = subprocess.run(
                ["bash", "-euo", "pipefail", "-c", script],
                env=environment,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(
                log.read_text(encoding="utf-8").splitlines(),
                ["pod:locuspebble-owned", "volume:locuspebble-cache"],
            )

    def test_docker_clean_uses_the_generator_to_remove_root_owned_outputs_first(self):
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            build_root = temporary / "build" / "podman"
            build_root.mkdir(parents=True)
            (build_root / "generated").mkdir()
            (build_root / "generated" / "output").write_text("generated", encoding="utf-8")
            log = temporary / "docker-log"
            environment = {
                **os.environ,
                "PODMAN_TEST_SCRIPT": str(PODMAN_TEST),
                "TEST_PROJECT_DIR": str(temporary),
                "DOCKER_LOG": str(log),
                "ACCEPTANCE_CONTAINER_ENGINE": "docker",
            }
            script = textwrap.dedent(
                """\
                source "$PODMAN_TEST_SCRIPT"
                PROJECT_DIR=$TEST_PROJECT_DIR
                BUILD_ROOT=$PROJECT_DIR/build/podman
                docker() {
                  if [[ "$1 $2" == "container ls" || "$1 $2" == "volume ls" ]]; then
                    return 0
                  elif [[ "$1 $2" == "image inspect" ]]; then
                    [[ "$3" == "$GENERATOR_IMAGE" ]]
                  elif [[ "$1" == run ]]; then
                    printf 'run-cleaner\n' >> "$DOCKER_LOG"
                    find "$BUILD_ROOT" -mindepth 1 -delete
                  elif [[ "$1 $2" == "image rm" ]]; then
                    printf 'remove-image\n' >> "$DOCKER_LOG"
                  elif [[ "$1 $2" == "network inspect" ]]; then
                    return 1
                  fi
                }
                clean
                """
            )
            result = subprocess.run(
                ["bash", "-euo", "pipefail", "-c", script],
                env=environment,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertFalse(build_root.exists())
            self.assertEqual(
                log.read_text(encoding="utf-8").splitlines(),
                ["run-cleaner", "remove-image"],
            )


class StaticPreflightTest(unittest.TestCase):
    def test_headless_acceptance_build_does_not_repeat_the_static_suite(self):
        source = PODMAN_TEST.read_text(encoding="utf-8")
        build = source.split("build_acceptance_all() {", 1)[1].split("\n}", 1)[0]
        self.assertIn("build_project_inputs false", build)

    def test_hosted_probe_uses_the_acceptance_emulator_and_cleans_ephemeral_state(self):
        source = PODMAN_TEST.read_text(encoding="utf-8")
        probe = source.split("docker_emulator_probe() {", 1)[1].split("\n}", 1)[0]
        self.assertIn('"$EMULATOR_IMAGE"', probe)
        self.assertIn("--device /dev/kvm", probe)
        self.assertIn("sys.boot_completed", probe)
        self.assertIn("cleanup_emulator_probe", probe)
        self.assertNotIn("LOCUS_INPUT_DIR", probe)
        self.assertNotIn("GOLDEN_VOLUME", probe)

    def test_emulator_handles_the_fallback_discovery_path(self):
        entrypoint = EMULATOR_ENTRYPOINT.read_text(encoding="utf-8")
        self.assertIn("/root/.android/avd/running", entrypoint)
        self.assertIn('path=$avd_home/MediumPhone.avd', entrypoint)
        self.assertIn("-no-metrics", entrypoint)
        self.assertNotIn("-no-snapshot-save", entrypoint)

    def test_pinned_emulator_generator_applies_its_dest_path_compatibility_patch(self):
        containerfile = GENERATOR_CONTAINERFILE.read_text(encoding="utf-8")
        patch = EMULATOR_PATH_PATCH.read_text(encoding="utf-8")
        self.assertIn("git -C /opt/aemu apply --check", containerfile)
        self.assertIn('Path(args.dest) / "sys_img"', patch)
        self.assertIn("Path(args.dest)", patch)

    def test_webrtc_image_generates_the_javascript_protocol_module(self):
        containerfile = WEB_CONTAINERFILE.read_text(encoding="utf-8")
        self.assertIn("protobuf-compiler", containerfile)
        self.assertIn("libprotobuf-dev", containerfile)
        self.assertIn("--js_out=import_style=commonjs,binary:/opt/aemu/js/src/proto", containerfile)
        self.assertIn("emulator_controller.proto", containerfile)
        self.assertIn("ws://127.0.0.1:8080", containerfile)
        self.assertIn("http://127.0.0.1:8080", containerfile)

    def test_acceptance_relaunches_locus_after_each_cold_boot(self):
        podman_test = PODMAN_TEST.read_text(encoding="utf-8")
        device_lib = DEVICE_LIB.read_text(encoding="utf-8")
        android_body = podman_test.split("run_android_tests() {", 1)[1].split("\n}", 1)[0]
        e2e_stage = E2E_STAGE.read_text(encoding="utf-8")
        launch = "foreground_locus"
        uninstall_bridge = "adb_device uninstall app.locuspebble.bridge"
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
            "app.locuspebble.bridge/"
            "io.github.christianherget.locuspebble.bridge.MainActivity"
        )
        self.assertIn(qualified, podman_test)
        self.assertIn(qualified, e2e_stage)
        self.assertNotIn("app.locuspebble.bridge/.MainActivity", e2e_stage)

    def test_e2e_sideloads_the_pbw_with_coreapps_private_selinux_label(self):
        e2e_stage = E2E_STAGE.read_text(encoding="utf-8")
        self.assertIn("push \"$pbw\" /data/local/tmp/locuspebble.pbw", e2e_stage)
        self.assertIn(
            "run-as coredevices.coreapp \\\n  cp /data/local/tmp/locuspebble.pbw cache/locuspebble.pbw",
            e2e_stage,
        )
        self.assertIn(
            "file:///data/user/0/coredevices.coreapp/cache/locuspebble.pbw",
            e2e_stage,
        )
        self.assertNotIn("/sdcard/Android/data/coredevices.coreapp/cache", e2e_stage)

    def test_e2e_dismisses_a_stale_coreapp_onboarding_gate(self):
        e2e_stage = E2E_STAGE.read_text(encoding="utf-8")
        device_lib = DEVICE_LIB.read_text(encoding="utf-8")
        self.assertIn("complete_coreapp_onboarding 90", e2e_stage)
        self.assertIn("Connect a Pebble", device_lib)
        self.assertIn("Get Started", device_lib)
        self.assertIn("tap_text Finished", device_lib)
        self.assertGreaterEqual(e2e_stage.count("pebble://navbar/apps"), 2)
        self.assertLess(
            e2e_stage.index("complete_coreapp_onboarding 90"),
            e2e_stage.index('tap_text "LocusPebble"'),
        )

    def test_e2e_polls_until_the_watch_settings_webview_is_rendered(self):
        e2e_stage = E2E_STAGE.read_text(encoding="utf-8")
        self.assertIn("settings_deadline=$((SECONDS + 30))", e2e_stage)
        self.assertIn("grep -Fq 'text=\"THEME\"'", e2e_stage)

    def test_emery_retries_the_streamed_heart_rate_during_locus_ingestion(self):
        e2e_stage = E2E_STAGE.read_text(encoding="utf-8")
        recording = 'wait_status recording_state RECORDING 30'
        heart_rate = "relayctl heart-rate 123 --quality excellent"
        locus_foreground = "foreground_locus"
        self.assertIn(locus_foreground, e2e_stage)
        emery = e2e_stage.split('if [[ "$PEBBLE_PLATFORM" == "emery" ]]', 1)[1]
        self.assertLess(emery.index(locus_foreground), emery.index('watch_button select'))
        foregrounded = emery[emery.index(locus_foreground):]
        self.assertLess(
            foregrounded.index("set_emulator_test_location"),
            foregrounded.index('watch_button select'),
        )
        self.assertLess(emery.index(recording), emery.index(heart_rate))
        self.assertIn("heart_rate_deadline=$((SECONDS + 20))", e2e_stage)
        self.assertGreaterEqual(
            e2e_stage.count("relayctl heart-rate 123 --quality excellent"),
            3,
        )
        self.assertLess(
            e2e_stage.index('tap_text "Apps"'),
            e2e_stage.index('tap_text "LocusPebble"'),
        )

    def test_static_path_does_not_require_acceptance_inputs(self):
        source = PODMAN_TEST.read_text(encoding="utf-8")
        static_body = source.split("static_tests() {", 1)[1].split("\n}", 1)[0]
        self.assertIn("doctor_static", static_body)
        self.assertIn("static_image_exists || build_static_image", static_body)
        self.assertIn("run_static_container", static_body)
        for forbidden in ("require_images", "require_current_golden", "/dev/kvm", "LOCUS_INPUT_DIR"):
            self.assertNotIn(forbidden, static_body)

    def test_static_path_keeps_development_dependencies_in_a_container(self):
        source = PODMAN_TEST.read_text(encoding="utf-8")
        doctor_body = source.split("doctor_static() {", 1)[1].split("\n}", 1)[0]
        static_body = source.split("static_tests() {", 1)[1].split("\n}", 1)[0]
        self.assertIn("select_static_engine", doctor_body)
        self.assertNotIn("command -v java", doctor_body)
        self.assertNotIn("command -v pebble", doctor_body)
        self.assertIn("docker", source.split("select_static_engine() {", 1)[1].split("\n}", 1)[0])
        self.assertLess(
            static_body.index("npm ci --prefix watchapp"),
            static_body.index("npm test --prefix watchapp"),
        )

    def test_doctor_requires_an_explicit_scope(self):
        result = subprocess.run(
            ["bash", str(PODMAN_TEST), "doctor"],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("doctor requires static or acceptance", result.stderr)

    def test_image_refresh_is_explicit(self):
        source = PODMAN_TEST.read_text(encoding="utf-8")
        self.assertIn("PULL_POLICY=missing", source)
        self.assertIn("PULL_POLICY=always", source)
        self.assertNotIn("podman build --pull=always", source)

    def test_release_metadata_checks_do_not_trip_pipefail(self):
        source = PODMAN_TEST.read_text(encoding="utf-8")
        release_body = source.split("release_check() {", 1)[1].split("\n}", 1)[0]
        self.assertIn('badging=$(aapt2 dump badging "$release_apk")', release_body)
        self.assertIn('manifest=$(aapt2 dump xmltree', release_body)
        self.assertNotIn('aapt2 dump badging "$release_apk" |', release_body)

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
            "bridge=0.1.9",
            "locus_fixture_config=",
            "locus=",
        )
        for field in required:
            self.assertGreaterEqual(source.count(field), 2, field)
        bootstrap = source.split("bootstrap() {", 1)[1].split("\n}", 1)[0]
        self.assertLess(
            bootstrap.index("wait_nonempty_status locus_profiles"),
            bootstrap.index("> /golden/.locuspebble-bootstrap"),
        )


class PrivateApkFingerprintTest(unittest.TestCase):
    def fingerprint(self, directory: Path) -> str:
        result = subprocess.run(
            ["python3", str(APK_VALIDATOR), "--fingerprint-only", str(directory)],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout.strip()

    def test_fingerprint_is_location_independent_and_content_sensitive(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first = root / "first"
            second = root / "second"
            first.mkdir()
            second.mkdir()
            for target in (first, second):
                (target / "base.apk").write_bytes(b"base")
                (target / "split.apk").write_bytes(b"split")
            original = self.fingerprint(first)
            self.assertRegex(original, r"^[0-9a-f]{64}$")
            self.assertEqual(original, self.fingerprint(second))
            (second / "split.apk").write_bytes(b"changed")
            self.assertNotEqual(original, self.fingerprint(second))


if __name__ == "__main__":
    unittest.main()
