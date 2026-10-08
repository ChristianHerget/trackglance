import hashlib
import io
import json
import pathlib
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from unittest import mock
from zipfile import ZipFile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import ci_certification as ci  # noqa: E402

REPO = "ChristianHerget/trackglance"
MAIN, HEAD, BASE, TREE, MERGE, BLOB = (letter * 40 for letter in "abcdef")
START = datetime(2026, 10, 1, 12, tzinfo=timezone.utc)


def date(minutes):
    return (START + timedelta(minutes=minutes)).isoformat()


def archive(component, record):
    data = io.BytesIO()
    with ZipFile(data, "w") as zip_file:
        zip_file.writestr(f"{component}.json", json.dumps(record))
    return data.getvalue()


class FakeGitHub:
    repository = REPO

    def __init__(self):
        self.pr = {"number": 87, "merged_at": date(70), "merge_commit_sha": MAIN,
                   "base": {"ref": "main", "sha": BASE},
                   "head": {"ref": "branch", "sha": HEAD, "repo": {"full_name": REPO}}}
        self.run = {"id": 99, "event": "pull_request", "head_sha": HEAD,
                    "head_branch": "branch", "head_repository": {"full_name": REPO},
                    "path": ".github/workflows/ci.yml", "created_at": date(0),
                    "status": "completed", "conclusion": "success", "run_attempt": 1,
                    "html_url": "https://example.invalid/runs/99"}
        self.runs = [self.run]
        self.jobs = [self.job(name) for name in ci.REQUIRED_JOBS]
        self.artifacts = []
        self.archives = {}
        for component in ci.COMPONENT_JOBS:
            self.set_record(component)

    @staticmethod
    def job(name):
        return {"name": name, "status": "completed", "conclusion": "success",
                "run_attempt": 1, "started_at": date(1), "completed_at": date(60),
                "html_url": "https://example.invalid/job"}

    def set_record(self, component, **changes):
        record = {"schema": 1, "repository": REPO, "run_id": 99, "attempt": 1,
                  "event": "pull_request", "component": component,
                  "checkout_sha": MERGE, "checkout_tree": TREE, "github_sha": MERGE,
                  "pr": {"number": 87, "base_sha": BASE, "head_sha": HEAD, "head_repo": REPO},
                  "workflow_blob": BLOB, "inputs": {"pin": "value", "watchapp/package-lock.json": "0" * 64},
                  "fixture_sha256": "fixture" if component in {"android", "emery", "gabbro"} else None,
                  "watch_passes": "1", "acceptance_provisioning": "published",
                  "runner": {"image_os": "ubuntu24", "image_version": "20261001.1", "docker_version": "28.0"},
                  "finalized": True, "tracked_changes": [], "observed_lock_sha256": "0" * 64}
        record.update(changes)
        name = f"ci-evidence-{component}-99-1"
        item = next((entry for entry in self.artifacts if entry["name"] == name), None)
        if item is None:
            item = {"id": len(self.artifacts) + 1, "name": name, "expired": False,
                    "created_at": date(30), "workflow_run": {"id": 99, "head_sha": HEAD}}
            self.artifacts.append(item)
        data = archive(component, record)
        self.archives[item["id"]] = data
        item["digest"] = "sha256:" + hashlib.sha256(data).hexdigest()

    def pages(self, endpoint, key):
        if endpoint.startswith("commits/"):
            return [self.pr]
        if endpoint.startswith("actions/workflows/"):
            return self.runs
        if endpoint.endswith("/jobs"):
            return self.jobs
        if endpoint.endswith("/artifacts"):
            return self.artifacts
        raise AssertionError(endpoint)

    def artifact(self, artifact_id):
        return self.archives[artifact_id]


class CaptureEvidenceTest(unittest.TestCase):
    def test_capture_and_finalize_record_actual_checkout_and_post_build_lock(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            event = {"pull_request": {"number": 87, "base": {"sha": BASE},
                                      "head": {"sha": HEAD, "repo": {"full_name": REPO}}}}
            event_path = root / "event.json"
            event_path.write_text(json.dumps(event))
            environment = {"GITHUB_SHA": MERGE, "GITHUB_EVENT_NAME": "pull_request",
                           "GITHUB_EVENT_PATH": str(event_path), "GITHUB_REPOSITORY": REPO,
                           "GITHUB_RUN_ID": "99", "GITHUB_RUN_ATTEMPT": "1"}
            fingerprints = {"watchapp/package-lock.json": "0" * 64}
            with mock.patch.object(ci, "ROOT", root), mock.patch.dict("os.environ", environment), \
                 mock.patch.object(ci, "current_checkout", return_value=(MERGE, TREE)), \
                 mock.patch.object(ci, "git", side_effect=lambda *args: (
                     BLOB if args[0] == "rev-parse" else "watchapp/package-lock.json")), \
                 mock.patch.object(ci, "input_fingerprints", return_value=fingerprints), \
                 mock.patch.object(ci, "runner_inputs", return_value={"image_version": "test"}), \
                 mock.patch.object(ci, "sha256", return_value="1" * 64):
                ci.capture("static", None)
                ci.finalize("static")
            record = json.loads((root / "build/ci-evidence/static.json").read_text())
            self.assertEqual(MERGE, record["checkout_sha"])
            self.assertEqual(BASE, record["pr"]["base_sha"])
            self.assertEqual(["watchapp/package-lock.json"], record["tracked_changes"])
            self.assertEqual("1" * 64, record["observed_lock_sha256"])
            self.assertTrue(record["finalized"])


class ReusePolicyTest(unittest.TestCase):
    def setUp(self):
        self.gh = FakeGitHub()
        patches = [
            mock.patch.object(ci, "changed_policy_paths", return_value=[]),
            mock.patch.object(ci, "merge_tree", return_value=TREE),
            mock.patch.object(ci, "input_fingerprints", return_value={
                "pin": "value", "watchapp/package-lock.json": "0" * 64}),
            mock.patch.object(ci, "fixture_sha256", return_value="fixture"),
            mock.patch.object(ci, "runner_inputs", return_value={
                "image_os": "ubuntu24", "image_version": "20261001.1", "docker_version": "28.0"}),
            mock.patch.object(ci, "git", side_effect=lambda *args: {
                ("rev-parse", "HEAD"): MAIN, ("rev-parse", "HEAD^{tree}"): TREE,
                ("rev-parse", "HEAD:.github/workflows/ci.yml"): BLOB}[args]),
        ]
        for patch in patches:
            patch.start()
            self.addCleanup(patch.stop)

    def assess(self, minutes=71):
        return ci.assess(self.gh, MAIN, now=START + timedelta(minutes=minutes))

    def test_valid_reuse_and_freshness(self):
        self.assertEqual(99, self.assess()["run_id"])
        with self.assertRaisesRegex(ci.EvidenceError, "older than 24 hours"):
            self.assess(minutes=60 + 24 * 60 + 1)

    def test_latest_run_and_attempt_must_be_complete(self):
        self.gh.runs.append(dict(self.gh.run, id=100, created_at=date(5), conclusion="failure"))
        with self.assertRaisesRegex(ci.EvidenceError, "newest PR run concluded failure"):
            self.assess()
        self.gh.runs.pop()
        self.gh.run["run_attempt"] = 2
        with self.assertRaisesRegex(ci.EvidenceError, "latest PR attempt is partial"):
            self.assess()

    def test_failed_cancelled_and_incomplete_checks_fall_back(self):
        target = self.gh.jobs[0]
        for status, conclusion in (("completed", "failure"), ("completed", "cancelled"),
                                   ("in_progress", None)):
            target.update(status=status, conclusion=conclusion)
            with self.subTest(status=status, conclusion=conclusion):
                with self.assertRaisesRegex(ci.EvidenceError, "required job"):
                    self.assess()
        target.update(status="completed", conclusion="success")

    def test_fork_skipped_job_and_policy_change_fall_back(self):
        self.gh.pr["head"]["repo"]["full_name"] = "fork/repo"
        with self.assertRaisesRegex(ci.EvidenceError, "same-repository PR"):
            self.assess()
        self.gh.pr["head"]["repo"]["full_name"] = REPO
        self.gh.jobs[-1]["conclusion"] = "skipped"
        with self.assertRaisesRegex(ci.EvidenceError, "concluded skipped"):
            self.assess()
        self.gh.jobs[-1]["conclusion"] = "success"
        with mock.patch.object(ci, "changed_policy_paths", return_value=[".github/workflows/ci.yml"]):
            with self.assertRaisesRegex(ci.EvidenceError, "policy or toolchain changed"):
                self.assess()

    def test_tree_workflow_fixture_and_runner_mismatches_fall_back(self):
        with mock.patch.object(ci, "merge_tree", return_value="wrong"):
            with self.assertRaisesRegex(ci.EvidenceError, "synthetic merge tree differs"):
                self.assess()
        for changes, reason in [
            ({"workflow_blob": "wrong"}, "workflow or input fingerprint differs"),
            ({"fixture_sha256": "wrong"}, "fixture fingerprint differs"),
            ({"runner": {}}, "hosted runner or Docker version differs"),
            ({"watch_passes": "2"}, "effective acceptance settings differ"),
        ]:
            self.gh.set_record("android", **changes)
            with self.assertRaisesRegex(ci.EvidenceError, reason):
                self.assess()

    def test_post_build_dependency_fingerprint_must_be_consistent(self):
        for component in ("static", "android", "emery", "gabbro"):
            self.gh.set_record(component, tracked_changes=["watchapp/package-lock.json"],
                               observed_lock_sha256="1" * 64)
        self.assertEqual(["0" * 64, "1" * 64],
                         self.assess()["observed_dependency_locks"])
        self.gh.set_record("gabbro", tracked_changes=["watchapp/package-lock.json"],
                           observed_lock_sha256="2" * 64)
        with self.assertRaisesRegex(ci.EvidenceError, "different post-build dependency locks"):
            self.assess()
        self.gh.set_record("gabbro", tracked_changes=["android/app/build.gradle.kts"],
                           observed_lock_sha256="1" * 64)
        with self.assertRaisesRegex(ci.EvidenceError, "changed other tracked test inputs"):
            self.assess()

    def test_tampered_and_missing_artifacts_fall_back(self):
        self.gh.artifacts[0]["digest"] = "sha256:" + "0" * 64
        with self.assertRaisesRegex(ci.EvidenceError, "digest differs"):
            self.assess()
        self.gh.artifacts.pop(0)
        with self.assertRaisesRegex(ci.EvidenceError, "missing or duplicate"):
            self.assess()


class MainRecordTest(unittest.TestCase):
    def setUp(self):
        self.record = {"schema": 1, "repository": REPO, "commit": MAIN, "tree": TREE,
                       "run_id": 42, "attempt": 1, "decision": "full", "source": None,
                       "workflow_blob": BLOB, "inputs": {"pin": "value"},
                       "fixture_sha256": "fixture",
                       "effective_config": {"watch_passes": "1", "acceptance_provisioning": "published"}}
        self.run = {"id": 42, "run_attempt": 1}
        self.jobs = {name: FakeGitHub.job(name) for name in ci.REQUIRED_JOBS}
        self.jobs["Select main CI evidence"] = FakeGitHub.job("Select main CI evidence")
        self.jobs[ci.CERTIFICATION_JOB] = FakeGitHub.job(ci.CERTIFICATION_JOB)
        self.gh = mock.Mock(repository=REPO)
        self.gh.pages.side_effect = self.pages
        self.gh.artifact.side_effect = lambda artifact_id: self.data
        self.gh.json.return_value = {"id": 99, "run_attempt": 1, "status": "completed",
                                     "conclusion": "success", "event": "pull_request"}
        self.reset_archive()
        self.git_patch = mock.patch.object(ci, "git", side_effect=lambda *args: {
            ("rev-parse", f"{MAIN}^{{tree}}"): TREE,
            ("rev-parse", f"{MAIN}:.github/workflows/ci.yml"): BLOB}[args])
        self.inputs_patch = mock.patch.object(ci, "input_fingerprints", return_value={"pin": "value"})
        self.fixture_patch = mock.patch.object(ci, "fixture_sha256", return_value="fixture")
        self.jobs_patch = mock.patch.object(ci, "attempt_jobs", return_value=self.jobs)
        self.sign_patch = mock.patch.object(ci, "verify_record_attestation")
        for patch in (self.git_patch, self.inputs_patch, self.fixture_patch,
                      self.jobs_patch, self.sign_patch):
            patch.start()
            self.addCleanup(patch.stop)

    def reset_archive(self):
        self.data = archive("ci-certification", self.record)
        self.artifact = {"id": 5, "name": "main-certification-42-1", "expired": False,
                         "created_at": date(30), "workflow_run": {"id": 42, "head_sha": MAIN},
                         "digest": "sha256:" + hashlib.sha256(self.data).hexdigest()}

    def pages(self, endpoint, key):
        if endpoint.endswith("/artifacts"):
            return [self.artifact]
        raise AssertionError(endpoint)

    def test_full_main_record_requires_signed_record_and_all_jobs(self):
        self.assertEqual("full", ci.verify_main_record(self.gh, MAIN, self.run)["decision"])
        self.jobs.pop("Committed documentation")
        with self.assertRaisesRegex(ci.EvidenceError, "missing required jobs"):
            ci.verify_main_record(self.gh, MAIN, self.run)

    def test_record_workflow_inputs_must_match_release_commit(self):
        self.record["workflow_blob"] = "wrong"
        self.reset_archive()
        with self.assertRaisesRegex(ci.EvidenceError, "workflow or inputs differ"):
            ci.verify_main_record(self.gh, MAIN, self.run)

    def test_unverified_attestation_is_rejected(self):
        with mock.patch.object(ci, "verify_record_attestation",
                               side_effect=ci.EvidenceError("invalid attestation")):
            with self.assertRaisesRegex(ci.EvidenceError, "invalid attestation"):
                ci.verify_main_record(self.gh, MAIN, self.run)

    def test_record_digest_and_latest_attempt_are_required(self):
        self.artifact["digest"] = "sha256:" + "0" * 64
        with self.assertRaisesRegex(ci.EvidenceError, "digest differs"):
            ci.verify_main_record(self.gh, MAIN, self.run)
        self.reset_archive()
        self.run["run_attempt"] = 2
        with self.assertRaisesRegex(ci.EvidenceError, "latest main attempt lacks successful evidence planning"):
            ci.verify_main_record(self.gh, MAIN, self.run)

    def test_reused_source_cannot_be_superseded(self):
        self.record["decision"] = "reuse"
        self.record["source"] = {"run_id": 99, "attempt": 1, "pr_number": 87}
        self.reset_archive()
        with mock.patch.object(ci, "source_candidate", return_value=(
                {"number": 87}, {"id": 99})):
            self.gh.json.return_value["run_attempt"] = 2
            with self.assertRaisesRegex(ci.EvidenceError, "newer or unsuccessful attempt"):
                ci.verify_main_record(self.gh, MAIN, self.run)


if __name__ == "__main__":
    unittest.main()
