import unittest
from script_test_support import (
    ROOT,
    PUBLISH_RELEASE_WORKFLOW,
    checked_action_pins,
)
RELEASE_WORKFLOW = ROOT / ".github" / "workflows" / "release.yml"

RELEASE_CANDIDATE_VERIFIER = ROOT / "tools" / "verify-release-candidate"

RELEASE_ASSET_VERIFIER = ROOT / "tools" / "verify-release-assets"

RELEASE_ARTIFACT_SCRIPT = ROOT / "tools" / "podman" / "release-artifacts.sh"


class ReleaseWorkflowTest(unittest.TestCase):
    def test_private_key_is_always_removed_before_public_processing(self):
        source = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        cleanup = source.index("- name: Remove private signing key")
        stage = source.index("- name: Stage public assets")
        submission = source.index("- name: Submit APK and PBW to VirusTotal")
        self.assertIn("if: always()", source[cleanup:stage])
        self.assertLess(cleanup, stage)
        self.assertLess(stage, submission)

    def test_pages_actions_pin_stable_releases_in_approved_majors(self):
        source = PUBLISH_RELEASE_WORKFLOW.read_text(encoding="utf-8")
        for action in ("configure-pages", "upload-pages-artifact", "deploy-pages"):
            checked_action_pins(source, f"actions/{action}")

    def test_virustotal_submission_is_pinned_protected_and_rate_limited(self):
        source = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        build_draft = source.split("  build-draft:", 1)[1]
        submission = build_draft.split(
            "- name: Submit APK and PBW to VirusTotal", 1
        )[1].split("- name: Validate VirusTotal reports", 1)[0]

        self.assertIn("environment: release", build_draft)
        checked_action_pins(source, "crazy-max/ghaction-virustotal")
        checked_action_pins(submission, "crazy-max/ghaction-virustotal")
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
        self.assertIn("tools/release-qualification", source)
        self.assertIn("gh workflow run acceptance.yml --ref main", source)
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
