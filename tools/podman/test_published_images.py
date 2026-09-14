import hashlib
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from script_test_support import (
    ROOT,
    PODMAN_TEST,
    CODEQL_WORKFLOW,
)
CI_IMAGE_WORKFLOW = ROOT / ".github" / "workflows" / "publish-ci-images.yml"

ACCEPTANCE_RUNNER_CONTAINERFILE = ROOT / "tools" / "podman" / "Containerfile.acceptance-runner"

CODEQL_CONTAINERFILE = ROOT / "tools" / "podman" / "Containerfile.codeql-kotlin"

CI_IMAGE_PINS = ROOT / "tools" / "ci-images.env"

CI_IMAGE_KEY = ROOT / "tools" / "ci-image-key"

CI_IMAGE_VERIFIER = ROOT / "tools" / "verify-ci-image"
CI_IMAGE_PUBLISHER = ROOT / "tools" / "publish-ci-images"

DOCKERIGNORE = ROOT / ".dockerignore"


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
        publisher = CI_IMAGE_PUBLISHER.read_text(encoding="utf-8")
        self.assertIn("cosign sign --yes", publisher)
        self.assertIn("Refusing to overwrite published image tag", publisher)
        self.assertIn("group: publish-ci-images\n  cancel-in-progress: false", source)
        self.assertIn("if: github.ref == 'refs/heads/main'", source)
        self.assertLess(source.index("sigstore/cosign-installer@"), source.index("run: tools/publish-ci-images"))
        self.assertEqual(source.count("actions/attest@"), 6)
        self.assertNotIn("actions/attest-build-provenance@", source)
        self.assertNotIn("actions/attest-sbom@", source)
        self.assertEqual(source.count("sbom-path:"), 3)
        self.assertIn("Reject forbidden image content", publisher)

    def test_sbom_generation_and_attestations_run_only_for_new_images(self):
        source = CI_IMAGE_WORKFLOW.read_text(encoding="utf-8")
        for name in ("runner", "emulator", "codeql"):
            self.assertEqual(source.count(f"if: steps.publish.outputs.{name}_published == 'true'"), 3)
        for step in source.split("      - "):
            if "actions/attest@" in step or "anchore/sbom-action@" in step:
                self.assertRegex(step, r"if: steps\.publish\.outputs\.(runner|emulator|codeql)_published == 'true'")
                image = next(name for name in ("runner", "emulator", "codeql") if f"outputs.{name}_published" in step)
                self.assertRegex(step, rf"steps\.publish\.outputs\.{image}_(ref|digest)")


class CiImageKeyTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.repo = Path(self.temporary.name)
        (self.repo / "tools").mkdir()
        shutil.copy2(CI_IMAGE_KEY, self.repo / "tools/ci-image-key")
        self.inputs = {}
        for kind in ("acceptance", "codeql-kotlin"):
            self.inputs[kind] = subprocess.check_output([str(CI_IMAGE_KEY), kind, "--list-inputs"], text=True).splitlines()
            for name in self.inputs[kind]:
                target = self.repo / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(ROOT / name, target)

    def keys(self):
        return {
            kind: subprocess.check_output([str(self.repo / "tools/ci-image-key"), kind], text=True).strip()
            for kind in self.inputs
        }

    def test_machine_readable_list_preserves_the_original_key_algorithm(self):
        for kind, key in self.keys().items():
            original = "".join(
                f"{hashlib.sha256((ROOT / name).read_bytes()).hexdigest()}  {name}\n"
                for name in self.inputs[kind]
            )
            self.assertEqual(key, hashlib.sha256(original.encode()).hexdigest()[:20])
            self.assertEqual(len(self.inputs[kind]), len(set(self.inputs[kind])))

    def test_each_declared_input_changes_only_the_appropriate_keys(self):
        before = self.keys()
        for name in set().union(*self.inputs.values()):
            with self.subTest(input=name):
                path = self.repo / name
                original = path.read_bytes()
                path.write_bytes(original + b"\n# changed input\n")
                after = self.keys()
                path.write_bytes(original)
                for kind in self.inputs:
                    self.assertEqual(before[kind] != after[kind], name in self.inputs[kind])

    def test_application_dependencies_and_gradle_wrapper_do_not_change_keys(self):
        before = self.keys()
        for name in (
            "android/app/gradle.lockfile", "gradle/verification-metadata.xml",
            "gradle/wrapper/gradle-wrapper.properties", "gradle/wrapper/gradle-wrapper.jar",
            "gradlew", "gradlew.bat", "watchapp/package-lock.json", "docs/package-lock.json",
        ):
            with self.subTest(input=name):
                path = self.repo / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("changed application dependency\n")
                self.assertEqual(self.keys(), before)

    def test_publication_triggers_match_declared_inputs_and_explicit_orchestration(self):
        source = CI_IMAGE_WORKFLOW.read_text(encoding="utf-8")
        paths = source.split("    paths:\n", 1)[1].split("  workflow_dispatch:", 1)[0]
        actual = {line.strip().removeprefix("- ") for line in paths.splitlines() if line.strip()}
        orchestration = {
            ".github/workflows/publish-ci-images.yml", "tools/ci-image-key",
            "tools/publish-ci-images", "tools/resolve-ci-image", "tools/verify-ci-image",
            "tools/podman-test", "tools/podman/release-metadata.sh",
            "tools/podman/test_published_images.py", "tools/podman/test_ci_image_publication.py",
        }
        self.assertEqual(actual, set().union(*self.inputs.values()) | orchestration)

    def test_unknown_options_and_missing_inputs_fail(self):
        for arguments in (["acceptance", "--typo"], ["unknown", "--list-inputs"], []):
            with self.subTest(arguments=arguments):
                result = subprocess.run([str(CI_IMAGE_KEY), *arguments], capture_output=True, text=True)
                self.assertEqual(result.returncode, 2)
        (self.repo / self.inputs["acceptance"][0]).unlink()
        result = subprocess.run([str(self.repo / "tools/ci-image-key"), "acceptance"], capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Missing image input", result.stderr)
