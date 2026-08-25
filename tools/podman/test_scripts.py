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
RELEASE_METADATA = ROOT / "tools" / "podman" / "release-metadata.sh"
RELEASE_WORKFLOW = ROOT / ".github" / "workflows" / "release.yml"
CI_WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"
CODEQL_WORKFLOW = ROOT / ".github" / "workflows" / "codeql.yml"
CI_IMAGE_WORKFLOW = ROOT / ".github" / "workflows" / "publish-ci-images.yml"
DEPENDABOT = ROOT / ".github" / "dependabot.yml"
BUILD_CONTAINERFILE = ROOT / "tools" / "podman" / "Containerfile.build"
ACCEPTANCE_RUNNER_CONTAINERFILE = ROOT / "tools" / "podman" / "Containerfile.acceptance-runner"
CODEQL_CONTAINERFILE = ROOT / "tools" / "podman" / "Containerfile.codeql-kotlin"
CI_IMAGE_PINS = ROOT / "tools" / "ci-images.env"
CI_IMAGE_KEY = ROOT / "tools" / "ci-image-key"
CI_IMAGE_VERIFIER = ROOT / "tools" / "verify-ci-image"
DOCKERIGNORE = ROOT / ".dockerignore"
VERSIONS = ROOT / "tools" / "podman" / "versions.env"


class ReleaseWorkflowTest(unittest.TestCase):
    def test_public_signing_assets_are_checksummed_before_private_key_removal(self):
        source = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        stage = source.split("- name: Stage public assets and remove private key", 1)[1]
        certificate = stage.index("cp trackglance-release-certificate.pem build/release-assets/")
        fingerprint = stage.index("cp trackglance-release-certificate.sha256 build/release-assets/")
        checksums = stage.index("sha256sum ./* > SHA256SUMS")
        private_key_removal = stage.index("rm -f build/release-private/trackglance-release.p12")

        self.assertLess(certificate, checksums)
        self.assertLess(fingerprint, checksums)
        self.assertLess(checksums, private_key_removal)

    def test_pages_actions_use_the_node24_compatible_major_versions(self):
        source = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn(
            "actions/configure-pages@45bfe0192ca1faeb007ade9deae92b16b8254a0d # v6.0.0",
            source,
        )
        self.assertIn(
            "actions/upload-pages-artifact@fc324d3547104276b827a68afc52ff2a11cc49c9 # v5.0.0",
            source,
        )
        self.assertIn(
            "actions/deploy-pages@cd2ce8fcbc39b97be8ca5fce6e763baed58fa128 # v5.0.0",
            source,
        )


class ContinuousIntegrationWorkflowTest(unittest.TestCase):
    def test_every_pull_request_runs_one_hosted_acceptance_pass(self):
        source = CI_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("branches: [main]", source)
        self.assertIn("tags: ['v*']", source)
        self.assertIn("github.event_name == 'pull_request'", source)
        self.assertIn("WATCH_PASSES: ${{ inputs.watch_passes || '1' }}", source)
        self.assertIn("tools/podman-test acceptance-suite", source)
        self.assertIn('source) run_suite Source --fresh', source)
        self.assertIn('"$provisioning" --cleanup --watch-passes', source)

    def test_obsolete_probe_and_self_hosted_jobs_are_absent(self):
        source = CI_WORKFLOW.read_text(encoding="utf-8")
        for obsolete in (
            "run_acceptance_probe", "emulator-probe", "run_acceptance:",
            "self-hosted", "Protected KVM acceptance",
        ):
            self.assertNotIn(obsolete, source)

    def test_failure_artifact_is_short_lived_and_binary_free_by_construction(self):
        source = CI_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn(
            "actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7.0.1",
            source,
        )
        self.assertIn("path: build/acceptance-diagnostics", source)
        self.assertIn("retention-days: 7", source)
        self.assertNotIn("path: build/podman", source)

    def test_dependabot_tracks_github_actions_weekly(self):
        source = DEPENDABOT.read_text(encoding="utf-8")
        self.assertIn("package-ecosystem: github-actions", source)
        self.assertIn("interval: weekly", source)

    def test_actionlint_is_checksum_pinned_in_the_build_container(self):
        containerfile = BUILD_CONTAINERFILE.read_text(encoding="utf-8")
        versions = VERSIONS.read_text(encoding="utf-8")
        self.assertIn("actionlint_${ACTIONLINT_VERSION}_linux_amd64.tar.gz", containerfile)
        self.assertIn("ACTIONLINT_VERSION=1.7.12", versions)
        self.assertIn(
            "ACTIONLINT_X86_64_SHA256="
            "8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8",
            versions,
        )

    def test_node_is_checksum_pinned_in_the_build_container(self):
        containerfile = BUILD_CONTAINERFILE.read_text(encoding="utf-8")
        versions = VERSIONS.read_text(encoding="utf-8")
        self.assertIn("node-v${NODE_VERSION}-linux-x64.tar.xz", containerfile)
        self.assertIn("NODE_VERSION=22.23.2", versions)
        self.assertIn(
            "NODE_X86_64_SHA256="
            "d60acfe00a2932254bb0ad20e01b0d74397a0875595de719654b214f4b03f307",
            versions,
        )

    def test_manual_acceptance_can_compare_source_and_published_provisioning(self):
        source = CI_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("options: [source, published, compare]", source)
        self.assertIn("default: published", source)
        self.assertIn("inputs.acceptance_provisioning || 'published'", source)
        self.assertIn("run_suite Source --fresh", source)
        self.assertIn("run_suite Published --published", source)
        self.assertIn("build/acceptance-timings.txt", source)


class PublishedCiImageTest(unittest.TestCase):
    def test_image_set_uses_only_immutable_digest_pins(self):
        pins = CI_IMAGE_PINS.read_text(encoding="utf-8")
        self.assertIn("CI_IMAGE_SET_SCHEMA=1", pins)
        self.assertNotIn("=UNPUBLISHED", pins)
        image_lines = [line for line in pins.splitlines() if line.startswith(("ACCEPTANCE_", "CODEQL_"))]
        self.assertEqual(len(image_lines), 3)
        for line in image_lines:
            self.assertRegex(line, r"^[A-Z_]+_IMAGE=ghcr\.io/[a-z0-9/_.-]+@sha256:[a-f0-9]{64}$")
        source = PODMAN_TEST.read_text(encoding="utf-8")
        self.assertIn("require_published_ci_image_pins", source)
        self.assertIn("@sha256:[a-f0-9]{64}", source)
        self.assertIn('"$SCRIPT_DIR/verify-ci-image"', source)
        self.assertIn("build_project_inputs false false", source)

    def test_acceptance_runner_embeds_only_the_public_pebble_app_fixture(self):
        source = ACCEPTANCE_RUNNER_CONTAINERFILE.read_text(encoding="utf-8")
        self.assertIn("COPY build/podman/images/pebble-app-x86_64-debug.apk", source)
        for forbidden in ("locus.apk", "trackglance-bridge", ".pbw", ".p12", ".keystore"):
            self.assertNotIn(forbidden, source.lower())

    def test_docker_context_excludes_everything_except_public_build_inputs(self):
        source = DOCKERIGNORE.read_text(encoding="utf-8")
        self.assertTrue(source.startswith("**\n"))
        self.assertIn("!tools/podman/**", source)
        self.assertIn("!build/podman/images/pebble-app-x86_64-debug.apk", source)
        for forbidden in ("locus", "trackglance-bridge", "watchapp/build", ".plist"):
            self.assertNotIn(forbidden, source)

    def test_kotlin_codeql_toolchain_is_a_separate_image(self):
        source = CODEQL_CONTAINERFILE.read_text(encoding="utf-8")
        self.assertIn("TrackGlance Kotlin CodeQL toolchain", source)
        self.assertIn("command -v aapt2", source)
        self.assertNotIn("Containerfile.acceptance-runner", source)

    def test_kotlin_codeql_traces_a_manual_gradle_build_in_the_pinned_image(self):
        workflow = CODEQL_WORKFLOW.read_text(encoding="utf-8")
        codeql_pin = next(
            line.split("=", 1)[1]
            for line in CI_IMAGE_PINS.read_text(encoding="utf-8").splitlines()
            if line.startswith("CODEQL_KOTLIN_IMAGE=")
        )
        kotlin_job = workflow.split("  analyze-kotlin:", 1)[1]
        self.assertIn(f"image: {codeql_pin}", kotlin_job)
        self.assertIn("languages: java-kotlin", kotlin_job)
        self.assertIn("build-mode: manual", kotlin_job)
        self.assertIn(":android:app:assembleDebug", kotlin_job)
        self.assertIn("category: /language:java-kotlin", kotlin_job)

    def test_image_invalidation_keys_cover_pins_and_relevant_inputs(self):
        source = CI_IMAGE_KEY.read_text(encoding="utf-8")
        for required in (
            "tools/podman/versions.env",
            "tools/podman/Containerfile.emulator",
            "tools/podman/coreapp-x86_64.patch",
            "tools/podman/Containerfile.codeql-kotlin",
        ):
            self.assertIn(required, source)
        for kind in ("acceptance", "codeql-kotlin"):
            result = subprocess.run(
                [str(CI_IMAGE_KEY), kind], capture_output=True, text=True, check=False
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertRegex(result.stdout.strip(), r"^[a-f0-9]{20}$")

    def test_verifier_rejects_mutable_tags_before_external_tools_are_needed(self):
        result = subprocess.run(
            [
                str(CI_IMAGE_VERIFIER),
                "ghcr.io/christianherget/image:latest",
                "ChristianHerget/trackglance/.github/workflows/publish-ci-images.yml@refs/heads/main",
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("not an immutable GHCR", result.stderr)

    def test_publication_is_protected_signed_attested_and_least_privilege(self):
        source = CI_IMAGE_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("environment: ci-images", source)
        self.assertIn("packages: write", source)
        self.assertIn("id-token: write", source)
        self.assertIn("attestations: write", source)
        self.assertIn("permissions:\n  contents: read", source)
        self.assertIn("cosign sign --yes", source)
        self.assertIn("Refusing to overwrite published image tag", source)
        self.assertEqual(source.count("actions/attest-build-provenance@"), 3)
        self.assertEqual(source.count("actions/attest-sbom@"), 3)
        self.assertIn("Reject forbidden image content", source)


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
        self.assertIn("/run/trackglance/emulator-console-auth-token", entrypoint)
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
                    printf '%s\n' trackglance-owned backup-trackglance-old
                  elif [[ "$1 $2" == "pod rm" ]]; then
                    printf 'pod:%s\n' "$4" >> "$PODMAN_REMOVAL_LOG"
                  elif [[ "$1 $2" == "volume ls" ]]; then
                    printf '%s\n' trackglance-cache backup-trackglance-cache
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
                ["pod:trackglance-owned", "volume:trackglance-cache"],
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

    def test_acceptance_retries_external_settings_webview_loading(self):
        source = E2E_STAGE.read_text(encoding="utf-8")
        settings = source.split("settings_loaded=0", 1)[1].split(
            'cp /tmp/trackglance-window.xml', 1
        )[0]

        self.assertIn("for _ in 1 2 3", settings)
        self.assertIn('resource-id="generalOpen"', settings)
        self.assertIn("KEYCODE_BACK", settings)

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
            e2e_stage.index('tap_text "TrackGlance"'),
        )

    def test_e2e_polls_until_the_watch_settings_webview_is_rendered(self):
        e2e_stage = E2E_STAGE.read_text(encoding="utf-8")
        self.assertIn("settings_deadline=$((SECONDS + 30))", e2e_stage)
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
        self.assertIn("watch_heart_rate_deadline=$((SECONDS + 30))", e2e_stage)
        self.assertIn("heart_rate_deadline=$((SECONDS + 20))", e2e_stage)
        self.assertLess(
            e2e_stage.index('tap_text "Apps"'),
            e2e_stage.index('tap_text "TrackGlance"'),
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
        self.assertIn("actionlint .github/workflows/*.yml", static_body)

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
        self.assertIn("--watch-passes must be 1 or 2", result.stderr)

    def test_sanitized_diagnostics_allowlist_excludes_installable_binaries(self):
        source = PODMAN_TEST.read_text(encoding="utf-8")
        diagnostics = source.split("stage_acceptance_diagnostics() {", 1)[1].split(
            "\nreport_acceptance_resources() {", 1
        )[0]
        for allowed in ("*.log", "*.txt", "*.jsonl", "*.xml", "*.png", "*.ppm"):
            self.assertIn(allowed, diagnostics)
        for forbidden in ("*.apk", "*.pbw", "*.p12", "*.keystore"):
            self.assertIn(forbidden, diagnostics)

    def test_diagnostics_staging_copies_only_the_allowlist(self):
        with tempfile.TemporaryDirectory() as directory:
            environment = {**os.environ, "DIAGNOSTICS_TEST_ROOT": directory}
            script = textwrap.dedent(
                """\
                source "$PODMAN_TEST"
                PROJECT_DIR=$DIAGNOSTICS_TEST_ROOT
                BUILD_ROOT=$PROJECT_DIR/build/podman
                ACCEPTANCE_DIAGNOSTICS_ROOT=$PROJECT_DIR/build/acceptance-diagnostics
                mkdir -p "$BUILD_ROOT/run/results"
                printf 'result\n' > "$BUILD_ROOT/run/results/test.xml"
                printf 'log\n' > "$BUILD_ROOT/run/runtime.log"
                printf 'private\n' > "$BUILD_ROOT/run/locus.apk"
                printf 'watch\n' > "$BUILD_ROOT/run/watch.pbw"
                stage_acceptance_diagnostics
                test -f "$ACCEPTANCE_DIAGNOSTICS_ROOT/run/results/test.xml"
                test -f "$ACCEPTANCE_DIAGNOSTICS_ROOT/run/runtime.log"
                test ! -e "$ACCEPTANCE_DIAGNOSTICS_ROOT/run/locus.apk"
                test ! -e "$ACCEPTANCE_DIAGNOSTICS_ROOT/run/watch.pbw"
                """
            )
            result = subprocess.run(
                ["bash", "-euo", "pipefail", "-c", script],
                env={**environment, "PODMAN_TEST": str(PODMAN_TEST)},
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stderr)

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
