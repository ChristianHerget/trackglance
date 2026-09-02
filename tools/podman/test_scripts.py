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
ANDROID_FRAME = ROOT / "tools" / "podman" / "android_frame.py"
MANUAL_STAGE = ROOT / "tools" / "podman" / "manual-stage.sh"
MANUAL_DASHBOARD = ROOT / "tools" / "podman" / "manual-dashboard" / "App.tsx"
MANUAL_VITE_PATCH = ROOT / "tools" / "podman" / "manual-dashboard" / "vite-lab-api.patch"
EMULATOR_PATH_PATCH = ROOT / "tools" / "podman" / "patches" / "android-emulator-dest-path.patch"
EMULATOR_ENTRYPOINT = ROOT / "tools" / "podman" / "android-emulator-entrypoint.sh"
EMULATOR_CONSOLE = ROOT / "tools" / "podman" / "emulator-console.py"
E2E_STAGE = ROOT / "tools" / "podman" / "e2e-stage.sh"
RELEASE_METADATA = ROOT / "tools" / "podman" / "release-metadata.sh"
RELEASE_MANIFEST_POLICY = ROOT / "tools" / "podman" / "check_release_manifest.py"
RELEASE_WORKFLOW = ROOT / ".github" / "workflows" / "release.yml"
PUBLISH_RELEASE_WORKFLOW = ROOT / ".github" / "workflows" / "publish-release.yml"
RELEASE_CANDIDATE_VERIFIER = ROOT / "tools" / "verify-release-candidate"
RELEASE_ASSET_VERIFIER = ROOT / "tools" / "verify-release-assets"
RELEASE_ARTIFACT_SCRIPT = ROOT / "tools" / "podman" / "release-artifacts.sh"
CI_WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"
CODEQL_WORKFLOW = ROOT / ".github" / "workflows" / "codeql.yml"
DEPENDENCY_REVIEW_WORKFLOW = ROOT / ".github" / "workflows" / "dependency-review.yml"
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
    def test_private_key_is_always_removed_before_public_processing(self):
        source = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        cleanup = source.index("- name: Remove private signing key")
        stage = source.index("- name: Stage public assets")
        submission = source.index("- name: Submit APK and PBW to VirusTotal")
        self.assertIn("if: always()", source[cleanup:stage])
        self.assertLess(cleanup, stage)
        self.assertLess(stage, submission)

    def test_pages_actions_use_the_node24_compatible_major_versions(self):
        source = PUBLISH_RELEASE_WORKFLOW.read_text(encoding="utf-8")
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

    def test_virustotal_submission_is_pinned_protected_and_rate_limited(self):
        source = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        build_draft = source.split("  build-draft:", 1)[1]
        submission = build_draft.split(
            "- name: Submit APK and PBW to VirusTotal", 1
        )[1].split("- name: Validate VirusTotal reports", 1)[0]

        self.assertIn("environment: release", build_draft)
        self.assertIn(
            "crazy-max/ghaction-virustotal@"
            "936d8c5c00afe97d3d9a1af26d017cfdf26800a2 # v5.0.0",
            submission,
        )
        self.assertIn("vt_api_key: ${{ secrets.VIRUSTOTAL_API_KEY }}", submission)
        self.assertEqual(submission.count("./build/release-assets/*.apk"), 1)
        self.assertEqual(submission.count("./build/release-assets/*.pbw"), 1)
        self.assertIn("request_rate: 4", submission)
        self.assertIn("update_release_body: false", submission)

    def test_virustotal_failure_blocks_draft_creation(self):
        source = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        build_draft = source.split("  build-draft:", 1)[1].split("\n  deploy-pages:", 1)[0]
        private_key_removal = build_draft.index("- name: Remove private signing key")
        submission = build_draft.index("- name: Submit APK and PBW to VirusTotal")
        validation = build_draft.index(
            "- name: Validate VirusTotal reports and add submission badges"
        )
        draft = build_draft.index("- name: Create or refresh draft release")

        self.assertLess(private_key_removal, submission)
        self.assertLess(submission, validation)
        self.assertLess(validation, draft)
        self.assertIn(
            "VIRUSTOTAL_ANALYSIS: ${{ steps.virustotal.outputs.analysis }}",
            build_draft,
        )
        self.assertIn(
            'tools/append-virustotal-badges "${GITHUB_REF_NAME#v}" '
            "build/release-notes.md",
            build_draft,
        )
        self.assertNotIn("continue-on-error", build_draft)
        self.assertIn("if: always()", build_draft)

    def test_draft_build_is_artifact_only_attested_and_checks_tag_twice(self):
        source = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        self.assertEqual(source.count('tools/release-preflight "${GITHUB_REF_NAME}"'), 2)
        self.assertIn("tools/release-certification", source)
        self.assertIn("tools/podman-test release-artifacts --published", source)
        self.assertEqual(source.count("actions/attest@"), 7)
        self.assertEqual(source.count("sbom-path:"), 4)
        self.assertIn("id: assets", source)
        for output in ("android_cdx", "android_spdx", "watch_cdx", "watch_spdx"):
            self.assertIn(f"sbom-path: ${{{{ steps.assets.outputs.{output} }}}}", source)
        self.assertNotIn("sbom-path: build/release-assets/", source)
        self.assertIn(
            'tools/verify-release-assets build/release-assets "$version"', source
        )
        self.assertNotIn("actions/attest-build-provenance@", source)
        for forbidden in ("podman-test static", "podman-test documentation", "acceptance-suite"):
            self.assertNotIn(forbidden, source)
        self.assertGreater(
            source.index("- name: Create or refresh draft release"),
            source.index("- name: Record release timings"),
        )

    def test_publication_reverifies_after_pages_review(self):
        source = PUBLISH_RELEASE_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("workflow_dispatch:", source)
        self.assertNotIn("inputs:", source)
        self.assertEqual(source.count("tools/verify-release-candidate"), 2)
        self.assertIn("environment: release", source)
        publish = source.split("  publish:", 1)[1]
        self.assertLess(
            publish.index("tools/verify-release-candidate"), publish.index("--draft=false")
        )

        verifier = RELEASE_CANDIDATE_VERIFIER.read_text(encoding="utf-8")
        self.assertEqual(verifier.count("gh attestation verify"), 2)
        self.assertIn('tools/verify-release-assets "$candidate" "$version"', verifier)
        asset_verifier = RELEASE_ASSET_VERIFIER.read_text(encoding="utf-8")
        self.assertIn("sha256sum --check --strict SHA256SUMS", asset_verifier)
        self.assertIn("--deny-self-hosted-runners", verifier)
        self.assertIn("--source-digest", verifier)
        self.assertIn("https://cyclonedx.org/bom", verifier)
        self.assertIn("https://spdx.dev/Document/v2.3", verifier)
        self.assertIn("statement.predicate == $expected[0]", verifier)

    def test_release_sboms_are_runtime_only_validated_and_generated_without_general_tests(self):
        source = RELEASE_ARTIFACT_SCRIPT.read_text(encoding="utf-8")
        self.assertIn(":android:app:cyclonedxDirectBom", source)
        self.assertIn("releaseRuntimeClasspath", (ROOT / "android/app/build.gradle.kts").read_text())
        self.assertIn("tools/release-sbom watch", source)
        self.assertIn("tools/release-sbom verify-pair", source)
        self.assertIn("cyclonedx validate", source)
        self.assertNotIn("jq ", source)
        for forbidden in (
            "testDebugUnitTest",
            "assembleDebugAndroidTest",
            "npm test",
            "acceptance-suite",
        ):
            self.assertNotIn(forbidden, source)

    def test_release_notes_receive_complete_github_release_history(self):
        source = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn('gh api --paginate --slurp "repos/$GITHUB_REPOSITORY/releases?per_page=100"', source)
        self.assertIn("--releases-json build/releases.json", source)


class ContinuousIntegrationWorkflowTest(unittest.TestCase):
    def test_validation_uses_protected_pull_requests_and_certifies_main_pushes(self):
        ci = CI_WORKFLOW.read_text(encoding="utf-8")
        ci_events = ci.split("permissions:\n", 1)[0]
        self.assertIn("  push:\n    branches: [main]", ci_events)
        self.assertIn("  pull_request:\n    branches: [main]", ci_events)
        self.assertIn("  workflow_dispatch:", ci_events)
        self.assertNotIn("if: github.event_name", ci)
        self.assertIn("ci-${{ github.event_name }}-${{ github.workflow }}-${{ github.ref }}", ci)

        codeql = CODEQL_WORKFLOW.read_text(encoding="utf-8")
        codeql_events = codeql.split("permissions:\n", 1)[0]
        self.assertNotIn("  push:", codeql_events)
        self.assertIn("  pull_request:\n    branches: [main]", codeql_events)
        self.assertIn("  schedule:", codeql_events)
        self.assertIn("  workflow_dispatch:", codeql_events)

    def test_dependency_review_is_one_pull_request_only_node24_check(self):
        source = DEPENDENCY_REVIEW_WORKFLOW.read_text(encoding="utf-8")
        expected_action = (
            "actions/dependency-review-action@"
            "a1d282b36b6f3519aa1f3fc636f609c47dddb294 # v5.0.0"
        )
        permissions = source.split("permissions:\n", 1)[1].split("\njobs:\n", 1)[0]
        job = source.split("  review:\n", 1)[1]

        self.assertIn("  pull_request:\n    branches: [main]", source)
        for unwanted_event in ("push:", "schedule:", "workflow_dispatch:"):
            self.assertNotIn(unwanted_event, source)
        self.assertEqual(permissions.strip(), "contents: read")
        self.assertNotIn("write", permissions)
        self.assertEqual(source.count("uses:"), 1)
        self.assertEqual(source.count(expected_action), 1)
        self.assertIn("name: Dependency review", job)
        self.assertIn("fail-on-severity: high", job)
        self.assertIn("fail-on-scopes: runtime, development, unknown", job)

    def test_codeql_actions_use_one_reviewed_v4_full_sha(self):
        source = CODEQL_WORKFLOW.read_text(encoding="utf-8")
        expected_pin = (
            "cdf488f595d80d6e07e03d4674febd5ab45fa938 # v4.37.9"
        )
        action_lines = [
            line.strip()
            for line in source.splitlines()
            if "uses: github/codeql-action/" in line
        ]

        self.assertEqual(len(action_lines), 4)
        self.assertEqual(
            action_lines.count(
                f"uses: github/codeql-action/init@{expected_pin}"
            ),
            2,
        )
        self.assertEqual(
            action_lines.count(
                f"uses: github/codeql-action/analyze@{expected_pin}"
            ),
            2,
        )
        self.assertIn(
            "language: [c-cpp, javascript-typescript, python, actions]",
            source,
        )
        self.assertIn("languages: java-kotlin", source)
        self.assertIn("build-mode: none", source)
        self.assertIn("build-mode: manual", source)
        self.assertEqual(source.count("queries: security-extended"), 2)
        for category in (
            "/language:${{ matrix.language }}",
            "/language:java-kotlin",
        ):
            self.assertIn(f"category: {category}", source)

    def test_every_pull_request_runs_one_hosted_acceptance_pass(self):
        source = CI_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("branches: [main]", source)
        self.assertNotIn("tags: ['v*']", source)
        self.assertIn("  acceptance-hosted:\n    name: Hosted full-stack acceptance", source)
        self.assertNotIn("if: github.event_name", source)
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
        run_suite = source.split("run_suite() {", 1)[1].split("\n          }", 1)[0]
        self.assertIn('sudo setfacl -m "u:${USER}:rw" /dev/kvm', run_suite)
        self.assertIn("test -w /dev/kvm", run_suite)


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

    def test_local_signature_verification_uses_a_digest_pinned_cosign_fallback(self):
        source = CI_IMAGE_VERIFIER.read_text(encoding="utf-8")
        self.assertRegex(
            source,
            r"ghcr\.io/sigstore/cosign/cosign@sha256:[a-f0-9]{64}",
        )
        self.assertIn('if command -v cosign', source)
        self.assertIn('"$engine" run --rm "$cosign_image"', source)

    def test_acceptance_runner_embeds_only_the_public_pebble_app_fixture(self):
        source = ACCEPTANCE_RUNNER_CONTAINERFILE.read_text(encoding="utf-8")
        self.assertIn("COPY build/podman/images/pebble-app-x86_64-debug.apk", source)
        for forbidden in ("locus.apk", "trackglance-bridge", ".pbw", ".p12", ".keystore"):
            self.assertNotIn(forbidden, source.lower())

    def test_docker_cleanup_uses_the_published_runner_when_generator_is_absent(self):
        source = PODMAN_TEST.read_text(encoding="utf-8")
        clean = source.split("clean() {", 1)[1].split("\n}\n\nmain()", 1)[0]
        self.assertIn('acceptance_image_exists "$ACCEPTANCE_RUNNER_IMAGE"', clean)
        self.assertIn('cleanup_image=$ACCEPTANCE_RUNNER_IMAGE', clean)
        self.assertIn('docker run --rm --volume "$BUILD_ROOT:/target" "$cleanup_image"', clean)

    def test_cleanup_does_not_mask_a_failed_artifact_deletion(self):
        source = PODMAN_TEST.read_text(encoding="utf-8")
        clean = source.split("clean() {", 1)[1].split("\n}\n\nmain()", 1)[0]
        self.assertIn('find "$BUILD_ROOT" -depth -delete || cleanup_status=$?', clean)
        self.assertIn('return "$cleanup_status"', clean)

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
        self.assertEqual(source.count("actions/attest@"), 6)
        self.assertNotIn("actions/attest-build-provenance@", source)
        self.assertNotIn("actions/attest-sbom@", source)
        self.assertEqual(source.count("sbom-path:"), 3)
        self.assertIn("Reject forbidden image content", source)


class DeviceReadinessTest(unittest.TestCase):
    def test_tap_text_targets_the_visible_part_of_a_clipped_control(self):
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            fixture = temporary / "window.xml"
            adb_log = temporary / "adb.log"
            fixture.write_text(
                textwrap.dedent(
                    """\
                    <hierarchy>
                      <node bounds="[0,0][1080,2400]">
                        <node class="android.widget.ScrollView" scrollable="true"
                              bounds="[53,116][1027,2305]">
                          <node bounds="[53,116][1027,1525]">
                            <node clickable="true" bounds="[423,2261][658,2387]">
                              <node text="Finished" bounds="[465,2297][605,2305]" />
                            </node>
                          </node>
                        </node>
                      </node>
                    </hierarchy>
                    """
                ),
                encoding="utf-8",
            )
            environment = {
                **os.environ,
                "ADB_LOG": str(adb_log),
                "DEVICE_LIB": str(DEVICE_LIB),
                "UI_FIXTURE": str(fixture),
            }
            result = subprocess.run(
                [
                    "bash",
                    "-euo",
                    "pipefail",
                    "-c",
                    textwrap.dedent(
                        """\
                        source "$DEVICE_LIB"
                        dump_ui() { cp "$UI_FIXTURE" /tmp/trackglance-window.xml; }
                        adb_device() { printf '%s\\n' "$*" > "$ADB_LOG"; }
                        tap_text Finished 2
                        """
                    ),
                ],
                env=environment,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(adb_log.read_text().strip(), "shell input tap 540 2283")

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
        podman_test = PODMAN_TEST.read_text(encoding="utf-8")
        android = podman_test.split("run_android_tests() {", 1)[1].split(
            "\nrun_e2e_platform() {", 1
        )[0]
        self.assertIn("/run/trackglance/emulator-console-auth-token", entrypoint)
        self.assertIn('socket.create_connection(("127.0.0.1", 5556)', helper)
        self.assertIn("geo fix", helper)
        self.assertIn(
            'install -m 600 "$EMULATOR_CONSOLE_TOKEN" /root/.emulator_console_auth_token',
            android,
        )
        self.assertLess(
            android.index("/root/.emulator_console_auth_token"),
            android.index("connectedDebugAndroidTest"),
        )
        self.assertIn('rm -rf "$results"', android)
        self.assertIn("gradle_status=$?", android)
        self.assertLess(android.index('rm -rf "$results"'), android.index("connectedDebugAndroidTest"))
        self.assertLess(
            android.index("connectedDebugAndroidTest"),
            android.index("assert-instrumentation-results.py"),
        )

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
    def test_large_emulator_downloads_resume_and_retry_transport_failures(self):
        source = PODMAN_TEST.read_text(encoding="utf-8")
        generator = source.split("build_emulator_image() {", 1)[1].split(
            "\n}", 1
        )[0]

        self.assertEqual(generator.count("--retry-all-errors"), 2)
        self.assertEqual(generator.count("--continue-at -"), 2)
        system_checksum = 'echo "$ANDROID_SYSTEM_IMAGE_SHA256  $system_zip"'
        system_download = 'curl --fail --location'
        self.assertLess(
            generator.index(system_checksum),
            generator.index(system_download, generator.index(system_checksum)),
        )

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
        self.assertLess(
            e2e_stage.index('tap_text "Apps"'),
            e2e_stage.index('tap_text "TrackGlance"'),
        )

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

    def test_release_manifest_policy_reads_the_compiled_apk_without_pipefail(self):
        source = PODMAN_TEST.read_text(encoding="utf-8")
        release_body = source.split("release_check() {", 1)[1].split("\n}", 1)[0]
        self.assertIn('aapt2 dump badging "$release_apk" > "$badging_file"', release_body)
        self.assertIn(
            'aapt2 dump xmltree --file AndroidManifest.xml "$release_apk" > "$manifest_file"',
            release_body,
        )
        self.assertIn("python3 tools/podman/check_release_manifest.py", release_body)
        self.assertIn("--debug-manifest android/app/src/debug/AndroidManifest.xml", release_body)
        self.assertIn('--target-sdk "$expected_target"', release_body)
        self.assertIn('rm -f "$badging_file" "$manifest_file"', release_body)
        self.assertNotIn('aapt2 dump badging "$release_apk" |', release_body)
        self.assertTrue(RELEASE_MANIFEST_POLICY.is_file())

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


class ManualLabHarnessTest(unittest.TestCase):
    def run_manual_parse(self, *arguments: str):
        return subprocess.run(
            ["bash", str(PODMAN_TEST), "manual", *arguments],
            capture_output=True,
            text=True,
            check=False,
        )

    def test_manual_requires_one_supported_platform_before_host_preflight(self):
        missing = self.run_manual_parse("--locus-apks", "/tmp")
        self.assertNotEqual(missing.returncode, 0)
        self.assertIn("manual requires --platform emery or --platform gabbro", missing.stderr)

        unsupported = self.run_manual_parse("--platform", "chalk", "--locus-apks", "/tmp")
        self.assertNotEqual(unsupported.returncode, 0)
        self.assertIn("manual requires --platform emery or --platform gabbro", unsupported.stderr)

        duplicate = self.run_manual_parse(
            "--platform", "emery", "--platform", "gabbro", "--locus-apks", "/tmp"
        )
        self.assertNotEqual(duplicate.returncode, 0)
        self.assertIn("--platform may be specified only once", duplicate.stderr)

    def test_manual_is_the_only_runtime_that_publishes_the_dashboard(self):
        source = PODMAN_TEST.read_text(encoding="utf-8")
        manual = source.split("manual_lab() {", 1)[1].split("\nstatic_tests() {", 1)[0]
        android = source.split("run_android_tests() {", 1)[1].split("\nrun_e2e_platform() {", 1)[0]
        e2e = source.split("run_e2e_platform() {", 1)[1].split("\ne2e_tests() {", 1)[0]

        self.assertIn('create_test_pod "$ACTIVE_POD" true', manual)
        self.assertIn("TRACKGLANCE_WEB_MODE=manual", source)
        self.assertNotIn("start_manual_web", android)
        self.assertNotIn("start_manual_web", e2e)
        self.assertIn("--publish 127.0.0.1:5173:5173", source)

    def test_manual_clones_and_protects_the_golden_state(self):
        source = PODMAN_TEST.read_text(encoding="utf-8")
        manual = source.split("manual_lab() {", 1)[1].split("\nstatic_tests() {", 1)[0]
        clone = source.split("clone_volume() {", 1)[1].split("\ngolden_marker() {", 1)[0]
        cleanup = source.split("cleanup_active() {", 1)[1].split("\ntrap cleanup_active", 1)[0]

        self.assertIn('MANUAL_GOLDEN_MARKER=$(golden_marker)', manual)
        self.assertIn('clone_volume "$GOLDEN_VOLUME" "$ACTIVE_DATA_VOLUME"', manual)
        self.assertIn('--volume "$source:/source:ro"', clone)
        self.assertIn('"$ACTIVE_DATA_VOLUME" "$ACTIVE_WATCH_VOLUME" "$ACTIVE_RUNTIME_VOLUME"', cleanup)
        self.assertIn('current_golden_marker=$(golden_marker', cleanup)

    def test_manual_web_image_is_versioned_and_proxies_only_an_internal_api(self):
        containerfile = WEB_CONTAINERFILE.read_text(encoding="utf-8")
        proxy_patch = MANUAL_VITE_PATCH.read_text(encoding="utf-8")
        frame_server = ANDROID_FRAME.read_text(encoding="utf-8")

        self.assertIn('LABEL io.trackglance.manual-lab="1"', containerfile)
        self.assertIn("git -C /opt/aemu apply --check", containerfile)
        self.assertIn("npm run build --prefix /opt/aemu/js/example", containerfile)
        self.assertIn("'/lab-api'", proxy_patch)
        self.assertIn("http://127.0.0.1:8081", proxy_patch)
        self.assertNotIn("0.0.0.0:8081", proxy_patch)
        self.assertIn('default="127.0.0.1"', frame_server)
        self.assertIn("width=540", frame_server)
        self.assertIn("getScreenshot", frame_server)
        self.assertIn("getDisplayConfigurations", frame_server)

    def test_dashboard_has_official_keyboard_mappings_and_ignores_text_editing(self):
        dashboard = MANUAL_DASHBOARD.read_text(encoding="utf-8")
        for mapping in (
            "q: 'back'", "ArrowLeft: 'back'", "w: 'up'", "ArrowUp: 'up'",
            "s: 'select'", "ArrowRight: 'select'", "x: 'down'", "ArrowDown: 'down'",
        ):
            self.assertIn(mapping, dashboard)
        self.assertIn("event.repeat", dashboard)
        self.assertIn("target?.isContentEditable", dashboard)
        self.assertIn("['INPUT', 'SELECT', 'TEXTAREA']", dashboard)
        self.assertIn("<canvas", dashboard)

    def test_manual_setup_installs_current_artifacts_and_finishes_stopped(self):
        stage = MANUAL_STAGE.read_text(encoding="utf-8")
        self.assertIn("install -r \"$bridge_apk\"", stage)
        installation = stage.split('install -r "$bridge_apk"', 1)[1]
        self.assertLess(
            installation.index("am force-stop menion.android.locus"),
            installation.index("adb_device shell am force-stop app.trackglance.bridge"),
        )
        self.assertLess(
            installation.index("am force-stop menion.android.locus"),
            installation.index("foreground_locus"),
        )
        self.assertIn("cache/trackglance.pbw", stage)
        self.assertIn("ADD_QEMU_WATCH", stage)
        self.assertNotIn("monkey -p coredevices.coreapp", stage)
        self.assertIn("complete_coreapp_onboarding", stage)
        self.assertLess(stage.rindex("foreground_locus"), stage.rindex("wait_status recording_state STOPPED"))
        self.assertIn('> "$ready_file"', stage)


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
