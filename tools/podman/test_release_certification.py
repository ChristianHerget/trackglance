import os
import pathlib
import runpy
import sys
import unittest
from unittest import mock


ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools/release-certification"
sys.path.insert(0, str(ROOT / "tools"))
COMMIT = "a" * 40


class FakeGitHub:
    def __init__(self, run, states):
        self.run = run
        self.states = list(states)

    def pages(self, endpoint, key):
        assert key == "workflow_runs"
        return [self.run]

    def json(self, endpoint):
        assert endpoint == f"actions/runs/{self.run['id']}"
        return self.states.pop(0) if len(self.states) > 1 else self.states[0]


class ReleaseCertificationTest(unittest.TestCase):
    def setUp(self):
        self.namespace = runpy.run_path(str(SCRIPT), run_name="release_test")
        self.main = self.namespace["main"]
        self.globals = self.main.__globals__
        self.run = {"id": 42, "event": "push", "head_branch": "main", "head_sha": COMMIT,
                    "path": ".github/workflows/ci.yml", "status": "completed",
                    "conclusion": "success", "run_attempt": 1,
                    "html_url": "https://example.invalid/runs/42"}
        self.output = pathlib.Path(self.id().replace(".", "_"))
        self.addCleanup(self.output.unlink, missing_ok=True)

    def invoke(self, run=None, states=None, record=None, verify_error=None, creation="0.1", completion="0.1"):
        run = run or self.run
        states = states or [run]
        fake = FakeGitHub(run, states)
        record = record or {"decision": "reuse", "source": {
            "run_id": 7, "run_url": "https://example.invalid/runs/7"}}
        with mock.patch.dict(self.globals, {
            "run": lambda *args: COMMIT,
            "repository": lambda: "ChristianHerget/trackglance",
            "GitHub": lambda repo: fake,
            "verify_main_record": mock.Mock(side_effect=verify_error) if verify_error else mock.Mock(return_value=record),
        }), mock.patch.object(self.globals["sys"], "argv", [str(SCRIPT), "v1.0.0"]), \
             mock.patch.dict(os.environ, {
                 "GITHUB_OUTPUT": str(self.output),
                 "RELEASE_CERTIFICATION_CREATION_TIMEOUT": creation,
                 "RELEASE_CERTIFICATION_COMPLETION_TIMEOUT": completion,
                 "RELEASE_CERTIFICATION_POLL_INTERVAL": "0.001",
             }), mock.patch.object(self.globals["time"], "sleep", return_value=None):
            self.main()
        return self.output.read_text()

    def test_exact_main_certification_supplies_release_outputs(self):
        output = self.invoke()
        self.assertIn("run_id=42", output)
        self.assertIn("source_run_id=7", output)
        self.assertIn("decision=reuse", output)

    def test_wrong_event_branch_and_sha_are_ignored(self):
        for update in ({"event": "pull_request"}, {"head_branch": "feature"},
                       {"head_sha": "0" * 40}):
            with self.subTest(update=update), self.assertRaisesRegex(SystemExit, "no CI push run"):
                self.invoke(run=dict(self.run, **update), creation="0")

    def test_failed_or_cancelled_main_run_fails(self):
        for conclusion in ("failure", "cancelled"):
            with self.subTest(conclusion=conclusion), self.assertRaisesRegex(SystemExit, conclusion):
                self.invoke(states=[dict(self.run, conclusion=conclusion)])

    def test_missing_or_invalid_signed_record_fails(self):
        error = self.namespace["EvidenceError"]("missing main certification record")
        with self.assertRaisesRegex(SystemExit, "missing main certification record"):
            self.invoke(verify_error=error)

    def test_pending_main_run_times_out(self):
        pending = dict(self.run, status="in_progress", conclusion=None)
        with self.assertRaisesRegex(SystemExit, "did not complete"):
            self.invoke(states=[pending], completion="0")


if __name__ == "__main__":
    unittest.main()
