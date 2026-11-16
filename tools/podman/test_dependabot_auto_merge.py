import copy
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

from script_test_support import ROOT, checked_action_pins

sys.path.insert(0, str(ROOT / "tools"))
import dependabot_auto_merge as merge  # noqa: E402

HEAD = "a" * 40
WORKFLOW = ROOT / ".github/workflows/dependabot-auto-merge.yml"


def dependency(kind="minor", name="example:library"):
    return {"dependencyName": name, "updateType": f"version-update:semver-{kind}"}


class DependabotAutoMergeTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.pr = {
            "state": "open", "draft": False, "auto_merge": None,
            "user": {"login": "dependabot[bot]"},
            "base": {"ref": "main", "repo": {"full_name": merge.REPOSITORY}},
            "head": {"sha": HEAD, "repo": {"full_name": merge.REPOSITORY}},
        }
        self.event = {"number": 126, "pull_request": copy.deepcopy(self.pr)}
        self.commits = [{"sha": HEAD, "author": {"login": "dependabot[bot]"},
                         "committer": {"login": "web-flow"},
                         "commit": {"verification": {"verified": True, "reason": "valid"}}}]
        self.commit_error = None
        self.environment = {
            "GITHUB_EVENT_NAME": "pull_request_target",
            "GITHUB_REPOSITORY": merge.REPOSITORY,
            "GITHUB_EVENT_PATH": str(self.directory / "event.json"),
            "GITHUB_STEP_SUMMARY": str(self.directory / "summary.md"),
            "DEPENDABOT_METADATA_OUTCOME": "success",
            "DEPENDABOT_UPDATED_DEPENDENCIES": json.dumps([dependency()]),
        }

    def invoke(self, current=None):
        Path(self.environment["GITHUB_EVENT_PATH"]).write_text(json.dumps(self.event))

        def github(*arguments):
            if arguments[0] == "api":
                if arguments[1].endswith("/commits?per_page=2"):
                    if self.commit_error:
                        raise self.commit_error
                    return json.dumps(self.commits)
                return json.dumps(self.pr if current is None else current)
            return ""

        with mock.patch.dict(os.environ, self.environment, clear=True), \
             mock.patch.object(merge, "gh", side_effect=github) as gh, \
             mock.patch("sys.stdout", new_callable=io.StringIO):
            merge.main()
        return [call.args for call in gh.call_args_list]

    def test_minor_patch_and_mixed_nonmajor_groups_enable_protected_squash_merge(self):
        for dependencies in ([dependency()], [dependency("patch")],
                             [dependency(), dependency("patch", "other:library")]):
            with self.subTest(dependencies=dependencies):
                self.environment["DEPENDABOT_UPDATED_DEPENDENCIES"] = json.dumps(dependencies)
                calls = self.invoke()
                self.assertEqual(calls, [
                    ("api", f"repos/{merge.REPOSITORY}/pulls/126"),
                    ("api", f"repos/{merge.REPOSITORY}/pulls/126/commits?per_page=2"),
                    ("pr", "merge", "126", "--repo", merge.REPOSITORY,
                     "--auto", "--squash", "--match-head-commit", HEAD),
                ])
                self.assertIn("Required checks and branch protections still apply",
                              (self.directory / "summary.md").read_text())

    def test_any_major_or_unknown_update_disables_an_existing_auto_merge_request(self):
        self.pr["auto_merge"] = {"merge_method": "squash"}
        invalid = [
            [dependency("major")],
            [dependency(), dependency("major", "org.json:json")],
            [dependency(), dependency("unknown")],
            [dependency(), {"dependencyName": "unknown:library"}],
            [{"updateType": "version-update:semver-minor"}],
            [{"dependencyName": " ", "updateType": "version-update:semver-minor"}],
            [{"dependencyName": "example:library", "updateType": []}],
            [None], [], None, {},
        ]
        for metadata in [json.dumps(value) for value in invalid] + ["", "malformed JSON"]:
            with self.subTest(metadata=metadata):
                self.environment["DEPENDABOT_UPDATED_DEPENDENCIES"] = metadata
                calls = self.invoke()
                self.assertEqual(calls[-1],
                                 ("pr", "merge", "126", "--repo", merge.REPOSITORY, "--disable-auto"))
                self.assertEqual(len(calls), 2)

    def test_json_calendar_update_remains_manual(self):
        self.environment["DEPENDABOT_UPDATED_DEPENDENCIES"] = json.dumps([
            {**dependency("major", "org.json:json"), "prevVersion": "20260719", "newVersion": "20260814"},
        ])
        self.assertEqual(len(self.invoke()), 1)

    def test_unverified_commits_cannot_reuse_previous_metadata(self):
        self.pr["auto_merge"] = {"merge_method": "squash"}
        for outcome in ("failure", "skipped", "", "cancelled"):
            with self.subTest(outcome=outcome):
                self.environment["DEPENDABOT_METADATA_OUTCOME"] = outcome
                self.assertEqual(self.invoke()[-1][-1], "--disable-auto")

    def test_ineligible_pr_without_auto_merge_needs_no_mutation(self):
        self.environment["DEPENDABOT_UPDATED_DEPENDENCIES"] = "[]"
        self.assertEqual(len(self.invoke()), 1)

    def test_converting_to_draft_disables_pending_auto_merge(self):
        self.pr.update(draft=True, auto_merge={"merge_method": "squash"})
        self.assertEqual(self.invoke()[-1][-1], "--disable-auto")

    def test_changed_head_never_enables_merge_with_stale_metadata(self):
        current = copy.deepcopy(self.pr)
        current["head"]["sha"] = "b" * 40
        self.assertEqual(len(self.invoke(current)), 1)

    def test_closed_or_retargeted_pr_cannot_be_merged(self):
        for change in ({"state": "closed"}, {"base": {"ref": "release"}}):
            with self.subTest(change=change):
                current = {**self.pr, **change}
                self.assertEqual(len(self.invoke(current)), 1)

    def test_untrusted_events_never_call_github(self):
        original = copy.deepcopy(self.event)
        for change in (
            {"user": {"login": "human"}},
            {"head": {"sha": HEAD, "repo": {"full_name": "fork/trackglance"}}},
            {"head": {"sha": HEAD, "repo": None}},
            {"head": {"sha": "--admin", "repo": {"full_name": merge.REPOSITORY}}},
            {"base": {"ref": "other", "repo": {"full_name": merge.REPOSITORY}}},
        ):
            with self.subTest(change=change):
                self.event = {**original, "pull_request": {**self.pr, **change}}
                self.assertEqual(self.invoke(), [])
        self.event = original
        for variable, value in (("GITHUB_EVENT_NAME", "pull_request"),
                                ("GITHUB_REPOSITORY", "fork/trackglance")):
            with self.subTest(variable=variable):
                saved = self.environment[variable]
                self.environment[variable] = value
                self.assertEqual(self.invoke(), [])
                self.environment[variable] = saved

    def test_api_errors_do_not_attempt_a_merge(self):
        Path(self.environment["GITHUB_EVENT_PATH"]).write_text(json.dumps(self.event))
        with mock.patch.dict(os.environ, self.environment, clear=True), \
             mock.patch.object(merge, "gh", side_effect=RuntimeError("API unavailable")) as gh:
            with self.assertRaisesRegex(RuntimeError, "API unavailable"):
                merge.main()
        self.assertEqual(gh.call_count, 1)

    def test_human_commit_after_verified_bot_commit_disables_auto_merge(self):
        self.pr["auto_merge"] = {"merge_method": "squash"}
        first = copy.deepcopy(self.commits[0])
        first["sha"] = "b" * 40
        human = {**copy.deepcopy(self.commits[0]), "author": {"login": "human"}}
        self.commits = [first, human]
        # The upstream action still reports success and the first bot commit's
        # minor metadata. The local guard must inspect the rest of the PR.
        calls = self.invoke()
        self.assertEqual(calls[-1][-1], "--disable-auto")
        self.assertEqual(len(calls), 3)

    def test_additional_bot_commit_cannot_hide_a_major_update_from_first_commit_metadata(self):
        self.pr["auto_merge"] = {"merge_method": "squash"}
        first = copy.deepcopy(self.commits[0])
        first["sha"] = "b" * 40
        self.commits = [first, self.commits[0]]
        self.assertEqual(self.invoke()[-1][-1], "--disable-auto")

    def test_missing_stale_human_or_unverified_commit_requires_manual_merge(self):
        original = copy.deepcopy(self.commits[0])
        invalid = [[], None, [{}],
                   [{**original, "sha": "b" * 40}],
                   [{**original, "author": {"login": "human"}}],
                   [{**original, "committer": {"login": "human"}}],
                   [{**original, "commit": {"verification": {"verified": False, "reason": "unsigned"}}}],
                   [{**original, "commit": {"verification": {"verified": "true", "reason": "valid"}}}]]
        for commits in invalid:
            with self.subTest(commits=commits):
                self.commits = commits
                calls = self.invoke()
                self.assertEqual(len(calls), 2)
                self.assertFalse(any("--auto" in call for call in calls))

    def test_commit_lookup_failure_disables_a_pending_auto_merge_request(self):
        self.pr["auto_merge"] = {"merge_method": "squash"}
        for error in (subprocess.CalledProcessError(1, "gh", stderr="API unavailable"),
                      subprocess.TimeoutExpired("gh", 60)):
            with self.subTest(error=error):
                self.commit_error = error
                self.assertEqual(self.invoke()[-1][-1], "--disable-auto")


class DependabotWorkflowPolicyTest(unittest.TestCase):
    def test_privileged_workflow_executes_only_pinned_trusted_base_code(self):
        source = WORKFLOW.read_text()
        self.assertIn("pull_request_target:", source)
        self.assertIn("github.event.pull_request.user.login == 'dependabot[bot]'", source)
        self.assertIn("github.event.pull_request.head.repo.full_name == github.repository", source)
        self.assertIn("branches: [main]", source)
        self.assertIn("ref: ${{ github.event.pull_request.base.sha }}", source)
        self.assertIn("persist-credentials: false", source)
        self.assertNotIn("ref: ${{ github.event.pull_request.head", source)
        self.assertNotIn("skip-verification:", source)
        self.assertNotIn("skip-commit-verification:", source)
        for action in ("actions/checkout", "dependabot/fetch-metadata", "actions/create-github-app-token"):
            self.assertEqual(len(checked_action_pins(source, action)), 1)

    def test_missing_configuration_cannot_enable_merge_or_use_the_builtin_token_to_merge(self):
        source = WORKFLOW.read_text()
        self.assertIn("configured=false", source)
        self.assertIn("No merge request was enabled", source)
        self.assertEqual(source.count("if: steps.configuration.outputs.configured == 'true'"), 4)
        self.assertIn("GH_TOKEN: ${{ steps.app-token.outputs.token }}", source)
        self.assertIn("repositories: ${{ github.event.repository.name }}", source)
        self.assertIn("permission-workflows: write", source)
        self.assertIn("continue-on-error: true", source)
        self.assertIn("DEPENDABOT_METADATA_OUTCOME: ${{ steps.metadata.outcome }}", source)
        self.assertIn("converted_to_draft", source)
