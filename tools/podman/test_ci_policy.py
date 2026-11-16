import re
import unittest
from script_test_support import (
    ROOT,
    CODEQL_WORKFLOW,
    action_declarations,
    checked_action_pins,
    checked_codeql_pins,
)
CI_WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"

DEPENDENCY_REVIEW_WORKFLOW = ROOT / ".github" / "workflows" / "dependency-review.yml"

DEPENDABOT = ROOT / ".github" / "dependabot.yml"

BUILD_CONTAINERFILE = ROOT / "tools" / "podman" / "Containerfile.build"

VERSIONS = ROOT / "tools" / "podman" / "versions.env"


class ContinuousIntegrationWorkflowTest(unittest.TestCase):
    def test_dependency_cache_never_rescans_generated_files_and_cannot_fail_tests(self):
        for path in (CI_WORKFLOW, CODEQL_WORKFLOW):
            source = path.read_text()
            cache = source.split("- uses: actions/cache@", 1)[1].split("\n      - ", 1)[0]
            self.assertNotIn("hashFiles", cache)
            self.assertIn("steps.dependency-key.outputs.value", cache)
            self.assertIn("continue-on-error: true", cache)
            self.assertLess(source.index("id: dependency-key"), source.index("- uses: actions/cache@"))

    def test_validation_runs_synthetic_checks_on_prs_and_main(self):
        ci = CI_WORKFLOW.read_text()
        self.assertIn("  push:\n    branches: [main]", ci)
        self.assertIn("  pull_request:\n    branches: [main]", ci)
        self.assertIn("  workflow_dispatch:", ci)
        self.assertIn("name: Synthetic CI", ci)
        self.assertNotIn("tools/ci-evidence", ci)
        self.assertNotIn("attestations: write", ci)
        self.assertNotIn("Main commit certification", ci)
        self.assertIn("cancel-in-progress: true", ci)

    def test_dependency_review_is_one_pull_request_only_pinned_v5_check(self):
        source = DEPENDENCY_REVIEW_WORKFLOW.read_text(encoding="utf-8")
        permissions = source.split("permissions:\n", 1)[1].split("\njobs:\n", 1)[0]
        job = source.split("  review:\n", 1)[1]

        self.assertIn("  pull_request:\n    branches: [main]", source)
        for unwanted_event in ("push:", "schedule:", "workflow_dispatch:"):
            self.assertNotIn(unwanted_event, source)
        self.assertEqual(permissions.strip(), "contents: read")
        self.assertNotIn("write", permissions)
        self.assertEqual(len(list(action_declarations(source))), 1)
        self.assertEqual(len(checked_action_pins(source, "actions/dependency-review-action")), 1)
        self.assertIn("name: Dependency review", job)
        self.assertIn("fail-on-severity: high", job)
        self.assertIn("fail-on-scopes: runtime, development, unknown", job)

    def test_codeql_actions_share_one_full_sha_and_stable_v4_release(self):
        source = CODEQL_WORKFLOW.read_text(encoding="utf-8")
        checked_codeql_pins(source)
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

    def test_codeql_uses_the_stable_actions_default_bundle(self):
        source = CODEQL_WORKFLOW.read_text(encoding="utf-8")
        self.assertNotIn("nightly", source.lower())
        self.assertNotIn("tools:", source)
        self.assertNotIn("codeql-bundle-linux64.tar.gz", source)

    def test_rollout_keeps_real_acceptance_until_synthetic_gate_is_enabled(self):
        source = CI_WORKFLOW.read_text()
        self.assertIn("vars.SYNTHETIC_CI_ENABLED != 'true'", source)
        self.assertIn("uses: ./.github/workflows/acceptance.yml", source)
        self.assertIn("needs: [synthetic, documentation]", source)
        self.assertIn('test "$SYNTHETIC_RESULT" = success', source)
        self.assertIn('test "$DOCUMENTATION_RESULT" = success', source)

    def test_obsolete_probe_and_self_hosted_jobs_are_absent(self):
        source = CI_WORKFLOW.read_text(encoding="utf-8")
        for obsolete in (
            "run_acceptance_probe", "emulator-probe", "run_acceptance:",
            "self-hosted", "Protected KVM acceptance",
        ):
            self.assertNotIn(obsolete, source)

    def test_failure_artifact_is_short_lived_and_binary_free_by_construction(self):
        source = CI_WORKFLOW.read_text(encoding="utf-8")
        checked_action_pins(source, "actions/upload-artifact")
        self.assertIn("path: build/check-logs", source)
        self.assertIn("retention-days: 7", source)
        self.assertNotIn("path: build/podman", source)

    def test_dependabot_tracks_github_actions_weekly(self):
        source = DEPENDABOT.read_text(encoding="utf-8")
        self.assertIn("package-ecosystem: github-actions", source)
        self.assertIn("interval: weekly", source)

    def test_dependabot_keeps_major_updates_outside_all_weekly_groups(self):
        source = DEPENDABOT.read_text(encoding="utf-8")
        areas = re.findall(r"  - package-ecosystem:.*?(?=  - package-ecosystem:|\Z)", source, re.S)
        self.assertEqual(len(areas), 4)
        for area in areas:
            with self.subTest(area=area.splitlines()[0]):
                self.assertIn('update-types: ["minor", "patch"]', area)
                self.assertIn("interval: weekly", area)
                self.assertIn("day: monday", area)
                self.assertIn('time: "06:00"', area)
                self.assertIn("timezone: Europe/Berlin", area)
                self.assertNotIn("ignore:", area)

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

    def test_full_acceptance_is_weekly_manual_and_reusable(self):
        source = (ROOT / ".github/workflows/acceptance.yml").read_text()
        events = source.split("permissions:", 1)[0]
        self.assertIn("workflow_dispatch:", events)
        self.assertIn("workflow_call:", events)
        self.assertIn("schedule:", events)
        self.assertNotIn("pull_request:", events)
        self.assertIn("options: [source, published, compare]", events)
        self.assertIn("run_suite Source --fresh", source)
        self.assertIn("run_suite Published --published", source)
        self.assertNotIn("ci-evidence", source)


class AcceptanceComponentsPolicyTest(unittest.TestCase):
    def test_full_acceptance_requires_android_and_both_watch_platforms(self):
        source = (ROOT / ".github/workflows/acceptance.yml").read_text()
        self.assertIn("needs: [acceptance-android]", source)
        self.assertIn("component: [emery, gabbro]", source)
        self.assertIn("fail-fast: false", source)
        self.assertIn("needs: [acceptance-android, acceptance-watch]", source)
        self.assertIn('test "$ANDROID_RESULT" = success', source)
        self.assertIn('test "$WATCH_RESULT" = success', source)
        self.assertIn("if: always()", source)
        for mode in ("source", "published"):
            for number in (1, 2):
                self.assertLess(
                    source.index(f"tools/ci-watch-phase startup {mode} {number}"),
                    source.index(f"tools/ci-watch-phase behavior {mode} {number}"),
                )
