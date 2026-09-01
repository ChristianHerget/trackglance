import json
import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
REQUIRED = [
    "Android, Pebble, protocol, and helpers",
    "Committed documentation",
    "Hosted full-stack acceptance",
]


class ReleaseCertificationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        base = pathlib.Path(self.temporary.name)
        self.repo = base / "repo"
        self.bin = base / "bin"
        self.state = base / "state.json"
        (self.repo / "tools").mkdir(parents=True)
        self.bin.mkdir()
        shutil.copy2(ROOT / "tools/release-certification", self.repo / "tools/release-certification")
        subprocess.run(["git", "init", "-b", "main"], cwd=self.repo, check=True, capture_output=True)
        subprocess.run(["git", "config", "user.name", "Test"], cwd=self.repo, check=True)
        subprocess.run(["git", "config", "user.email", "test@example.invalid"], cwd=self.repo, check=True)
        (self.repo / "source").write_text("release\n")
        subprocess.run(["git", "add", "."], cwd=self.repo, check=True)
        subprocess.run(["git", "commit", "-m", "release"], cwd=self.repo, check=True, capture_output=True)
        subprocess.run(["git", "tag", "v1.0.0"], cwd=self.repo, check=True)
        self.commit = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=self.repo, check=True, capture_output=True, text=True
        ).stdout.strip()
        gh = self.bin / "gh"
        gh.write_text(
            """#!/usr/bin/env python3
import json, os, pathlib, sys
state_path = pathlib.Path(os.environ['FAKE_GH_STATE'])
state = json.loads(state_path.read_text())
endpoint = sys.argv[-1]
kind = 'runs' if '/workflows/ci.yml/runs?' in endpoint else ('jobs' if endpoint.endswith('/jobs?per_page=100') else 'run')
values = state[kind]
value = values.pop(0) if len(values) > 1 else values[0]
state_path.write_text(json.dumps(state))
print(json.dumps(value))
"""
        )
        gh.chmod(0o755)

    def tearDown(self):
        self.temporary.cleanup()

    def invoke(self, runs, run_states, jobs, creation="0.2", completion="0.2"):
        self.state.write_text(json.dumps({"runs": runs, "run": run_states, "jobs": jobs}))
        environment = os.environ.copy()
        environment.update({
            "PATH": f"{self.bin}:{environment['PATH']}",
            "FAKE_GH_STATE": str(self.state),
            "GH_REPOSITORY": "ChristianHerget/trackglance",
            "RELEASE_CERTIFICATION_CREATION_TIMEOUT": creation,
            "RELEASE_CERTIFICATION_COMPLETION_TIMEOUT": completion,
            "RELEASE_CERTIFICATION_POLL_INTERVAL": "0.01",
        })
        return subprocess.run(
            [str(self.repo / "tools/release-certification"), "v1.0.0"],
            cwd=self.repo, env=environment, capture_output=True, text=True,
        )

    def run_record(self, **overrides):
        record = {
            "id": 42, "event": "push", "head_branch": "main", "head_sha": self.commit,
            "status": "queued", "conclusion": None, "html_url": "https://example.invalid/run/42",
        }
        record.update(overrides)
        return record

    def successful_jobs(self):
        return {"jobs": [{"name": name, "conclusion": "success"} for name in REQUIRED]}

    def test_delayed_creation_and_pending_run_succeed(self):
        run = self.run_record()
        result = self.invoke(
            [{"workflow_runs": []}, {"workflow_runs": [run]}],
            [run, self.run_record(status="in_progress"), self.run_record(status="completed", conclusion="success")],
            [self.successful_jobs()],
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("run 42", result.stdout)

    def test_wrong_event_branch_and_sha_are_ignored_until_timeout(self):
        wrong = [
            self.run_record(event="pull_request"), self.run_record(head_branch="feature"),
            self.run_record(head_sha="0" * 40),
        ]
        result = self.invoke([{"workflow_runs": wrong}], [self.run_record()], [self.successful_jobs()], creation="0.03")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("no CI push run", result.stderr)

    def test_missing_or_skipped_job_fails(self):
        complete = self.run_record(status="completed", conclusion="success")
        missing = {"jobs": [{"name": name, "conclusion": "success"} for name in REQUIRED[:-1]]}
        result = self.invoke([{"workflow_runs": [complete]}], [complete], [missing])
        self.assertIn("missing required jobs", result.stderr)
        skipped = self.successful_jobs()
        skipped["jobs"][-1]["conclusion"] = "skipped"
        result = self.invoke([{"workflow_runs": [complete]}], [complete], [skipped])
        self.assertIn("=skipped", result.stderr)

    def test_failure_and_cancellation_fail_immediately(self):
        for conclusion in ("failure", "cancelled"):
            failed = self.run_record(status="completed", conclusion=conclusion)
            result = self.invoke([{"workflow_runs": [failed]}], [failed], [self.successful_jobs()])
            self.assertIn(f"concluded {conclusion}", result.stderr)

    def test_pending_run_times_out(self):
        pending = self.run_record(status="in_progress")
        result = self.invoke(
            [{"workflow_runs": [pending]}], [pending], [self.successful_jobs()], completion="0.03"
        )
        self.assertIn("did not complete", result.stderr)


if __name__ == "__main__":
    unittest.main()
