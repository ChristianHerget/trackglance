import copy
import runpy
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from script_test_support import ROOT

sys.path.insert(0, str(ROOT / "tools"))
from github_checks import CheckError, GitHub, latest_run, require_run  # noqa: E402

COMMIT = "a" * 40
REPO = "owner/project"


def run(**changes):
    return {
        "id": 42, "head_sha": COMMIT, "head_branch": "main", "event": "push",
        "path": ".github/workflows/ci.yml", "repository": {"full_name": REPO},
        "head_repository": {"full_name": REPO}, "run_attempt": 1,
        "status": "completed", "conclusion": "success", "html_url": "https://example.invalid/42",
        **changes,
    }


class FakeGitHub:
    repository = REPO

    def __init__(self, runs=None, jobs=None):
        self.runs = runs or [run()]
        self.jobs = jobs if jobs is not None else [{"name": "required", "conclusion": "success"}]
        self.current = copy.deepcopy(self.runs[-1])

    def pages(self, resource, key):
        return self.jobs if key == "jobs" else self.runs

    def json(self, resource):
        if resource == "git/ref/heads/main":
            return {"object": {"sha": COMMIT}}
        return self.current


class ExactCommitChecksTest(unittest.TestCase):
    def test_latest_failed_run_is_selected_instead_of_older_success(self):
        gh = FakeGitHub([run(), run(id=43, conclusion="failure")])
        selected = latest_run(gh, "ci.yml", COMMIT, {"push"})
        self.assertEqual(selected["id"], 43)
        with self.assertRaisesRegex(CheckError, "failure"):
            require_run(gh, selected, {"required"})

    def test_wrong_commit_branch_event_repository_and_workflow_do_not_qualify(self):
        changes = [
            {"head_sha": "b" * 40}, {"head_branch": "feature"}, {"event": "pull_request"},
            {"repository": {"full_name": "other/repo"}},
            {"head_repository": {"full_name": "other/repo"}},
            {"head_repository": None},
            {"path": ".github/workflows/other.yml"},
        ]
        for change in changes:
            with self.subTest(change=change):
                self.assertIsNone(latest_run(FakeGitHub([run(**change)]), "ci.yml", COMMIT, {"push"}))

    def test_missing_duplicate_skipped_and_failed_jobs_block_qualification(self):
        for jobs in (
            [], [{"name": "different", "conclusion": "success"}],
            [{"name": "required", "conclusion": "skipped"}],
            [{"name": "required", "conclusion": "failure"}],
            [{"name": "required", "conclusion": "success"}] * 2,
        ):
            with self.subTest(jobs=jobs), self.assertRaises(CheckError):
                require_run(FakeGitHub(jobs=jobs), run(), {"required"})

    def test_new_attempt_and_identity_changes_block_qualification(self):
        for change in ({"run_attempt": 2}, {"head_sha": "b" * 40}, {"event": "pull_request"}):
            gh = FakeGitHub()
            gh.current.update(change)
            with self.subTest(change=change), self.assertRaisesRegex(CheckError, "identity or attempt"):
                require_run(gh, run(), {"required"})

    def test_pending_run_times_out_without_authorizing_a_release(self):
        gh = FakeGitHub()
        gh.current.update(status="in_progress", conclusion=None)
        with self.assertRaisesRegex(CheckError, "deadline"):
            require_run(gh, run(), {"required"}, timeout=0)

    def test_every_required_job_must_explicitly_succeed(self):
        self.assertEqual(require_run(FakeGitHub(), run(), {"required"})["id"], 42)

    def test_api_failure_is_not_an_empty_qualifying_result(self):
        with mock.patch("github_checks.subprocess.run") as command:
            command.return_value.returncode = 1
            command.return_value.stderr = "HTTP 503"
            with self.assertRaisesRegex(CheckError, "503"):
                GitHub(REPO).json("actions/runs/42")

    def test_api_timeout_cannot_authorize_a_release(self):
        with mock.patch("github_checks.subprocess.run", side_effect=subprocess.TimeoutExpired("gh", 30)):
            with self.assertRaisesRegex(CheckError, "timed out"):
                GitHub(REPO).json("actions/runs/42")


class ReleaseQualificationTest(unittest.TestCase):
    def setUp(self):
        self.namespace = runpy.run_path(str(ROOT / "tools/release-qualification"))
        self.main = self.namespace["main"]
        self.globals = self.main.__globals__

    def invoke(self, runs, probe=False):
        gh = FakeGitHub(runs)
        # Result validation is tested above; isolate release selection and outputs here.
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "outputs"
            with mock.patch.dict(self.globals, {
                "GitHub": lambda repo: gh, "git": lambda *args: COMMIT,
                "require_run": lambda *args: None,
            }), mock.patch.dict("os.environ", {
                "GITHUB_REPOSITORY": REPO, "GITHUB_OUTPUT": str(output),
                "RELEASE_QUALIFICATION_CREATION_TIMEOUT": "0",
            }), mock.patch.object(sys, "argv", ["qualification", "v1.2.3"] + (["--probe"] if probe else [])):
                self.main()
            return output.read_text()

    def test_probe_requests_automatic_acceptance_only_when_none_exists(self):
        self.assertIn("needs_acceptance=true", self.invoke([run()], probe=True))

    def test_exact_commit_manual_and_scheduled_acceptance_can_qualify(self):
        for event in ("workflow_dispatch", "schedule"):
            acceptance = run(id=43, event=event, path=".github/workflows/acceptance.yml")
            with self.subTest(event=event):
                self.assertIn("needs_acceptance=false", self.invoke([run(), acceptance]))

    def test_tag_without_main_ci_cannot_request_acceptance(self):
        with self.assertRaisesRegex(CheckError, "no synthetic main CI"):
            self.invoke([run(head_sha="b" * 40)], probe=True)
