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

    def test_kotlin_codeql_nightly_is_checksum_pinned_and_scoped(self):
        source = CODEQL_WORKFLOW.read_text(encoding="utf-8")
        standard_job = source.split("  analyze:\n", 1)[1].split("\n  analyze-kotlin:", 1)[0]
        kotlin_job = source.split("  analyze-kotlin:\n", 1)[1]
        nightly_url = (
            "https://github.com/dsp-testing/codeql-cli-nightlies/releases/download/"
            "codeql-bundle-20260914/codeql-bundle-linux64.tar.gz"
        )
        nightly_sha256 = (
            "0e368291ce2fa5cc28017293a56ae902b29c52fe42ea71bfbc3abce4bd6b6b34"
        )

        self.assertNotIn("tools: nightly", source)
        self.assertNotIn("CODEQL_NIGHTLY", standard_job)
        self.assertNotIn("tools:", standard_job)
        self.assertEqual(kotlin_job.count(nightly_url), 1)
        self.assertEqual(kotlin_job.count(nightly_sha256), 1)
        self.assertIn("curl --fail --location --proto '=https' --tlsv1.2 --retry 4", kotlin_job)
        self.assertIn("sha256sum --check --strict", kotlin_job)
        self.assertIn("tools: ${{ runner.temp }}/codeql-bundle-linux64.tar.gz", kotlin_job)

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
        checked_action_pins(source, "actions/upload-artifact")
        self.assertIn("path: build/check-logs", source)
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
        self.assertNotIn("build/acceptance-timings.txt", source)
        run_suite = source.split("run_suite() {", 1)[1].split("\n          }", 1)[0]
        self.assertIn('sudo setfacl -m "u:${USER}:rw" /dev/kvm', run_suite)
        self.assertIn("test -w /dev/kvm", run_suite)


class AcceptanceComponentsPolicyTest(unittest.TestCase):
    def test_android_precedes_parallel_watches_and_aggregate_requires_all(self):
        source = CI_WORKFLOW.read_text()
        android = source.split("  acceptance-android:", 1)[1].split("  acceptance-watch:", 1)[0]
        watch = source.split("  acceptance-watch:", 1)[1].split("  acceptance-hosted:", 1)[0]
        aggregate = source.split("  acceptance-hosted:", 1)[1]
        self.assertIn("ACCEPTANCE_COMPONENT: android", android)
        self.assertIn("needs: acceptance-android", watch)
        self.assertIn("fail-fast: false", watch)
        self.assertIn("component: [emery, gabbro]", watch)
        self.assertNotIn("max-parallel: 1", watch)
        self.assertIn("needs: [acceptance-android, acceptance-watch]", aggregate)
        self.assertIn("if: always()", aggregate)
        self.assertIn('test "$ANDROID_RESULT" = success', aggregate)
        self.assertIn('test "$WATCH_RESULT" = success', aggregate)
        self.assertEqual(source.count("name: Hosted full-stack acceptance"), 1)
        for job in (android, watch):
            self.assertIn("acceptance-${{ env.ACCEPTANCE_COMPONENT }}-diagnostics-", job)
            self.assertIn("retention-days: 7", job)
            self.assertIn("if: failure()", job)
            self.assertIn(' --component "$ACCEPTANCE_COMPONENT"', job)
            self.assertIn('"$GITHUB_STEP_SUMMARY"', job)
