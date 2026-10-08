"""Check CI evidence and bind a main commit to a completed test run."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
from datetime import datetime, timezone
from zipfile import BadZipFile, ZipFile


ROOT = Path(__file__).resolve().parent.parent
COMPONENT_JOBS = {
    "static": "Android, Pebble, protocol, and helpers",
    "documentation": "Committed documentation",
    "android": "Android instrumentation",
    "emery": "Watch acceptance (emery)",
    "gabbro": "Watch acceptance (gabbro)",
}
REQUIRED_JOBS = set(COMPONENT_JOBS.values()) | {"Hosted full-stack acceptance"}
CERTIFICATION_JOB = "Main commit certification"
PREDICATE_TYPE = "https://github.com/ChristianHerget/trackglance/ci-certification/v1"
INPUT_PATHS = (
    ".github/workflows/ci.yml",
    "tools/ci_certification.py",
    "tools/ci-evidence",
    "tools/podman-test",
    "tools/podman/versions.env",
    "tools/podman/Containerfile.build",
    "tools/ci-images.env",
    "tools/locus-test-apk.properties",
    "tools/download-locus-apk",
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradle.properties",
    "pyproject.toml",
    "android/app/build.gradle.kts",
    "android/app/gradle.lockfile",
    "watchapp/package.json",
    "watchapp/package-lock.json",
    "docs/package.json",
    "docs/package-lock.json",
    "docs/requirements.txt",
)
INVALIDATION_PREFIXES = (".github/workflows/", "tools/", "android/gradle/", "gradle/",
                         "android/app/src/androidTest/", "watchapp/test/")
INVALIDATION_EXACT = {
    "tools/podman-test", "tools/ci_certification.py", "tools/release-certification",
    "tools/ci-images.env", "tools/locus-test-apk.properties", "tools/download-locus-apk",
    "tools/verify-ci-image", "android/build.gradle.kts", "android/settings.gradle.kts",
    "android/app/build.gradle.kts", "watchapp/package.json", "watchapp/package-lock.json",
    "docs/requirements.txt", "docs/requirements.lock", "docs/package.json",
    "docs/package-lock.json", "build.gradle.kts", "settings.gradle.kts",
    "gradle.properties", "pyproject.toml", "android/app/gradle.lockfile",
}
MAX_EVIDENCE_BYTES = 64 * 1024


class EvidenceError(ValueError):
    pass


def command(*args: str, binary: bool = False) -> str | bytes:
    completed = subprocess.run(args, cwd=ROOT, capture_output=True, text=not binary, check=False)
    if completed.returncode:
        detail = completed.stderr.decode(errors="replace") if binary else completed.stderr
        raise EvidenceError(f"{' '.join(args[:3])} failed: {detail.strip()[:220]}")
    return completed.stdout


def git(*args: str) -> str:
    return str(command("git", *args)).strip()


def timestamp(value: str) -> datetime:
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (AttributeError, ValueError) as error:
        raise EvidenceError(f"invalid timestamp: {value!r}") from error


def require(condition: bool, message: str) -> None:
    if not condition:
        raise EvidenceError(message)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def input_fingerprints() -> dict[str, str]:
    return {path: sha256(ROOT / path) for path in INPUT_PATHS}


def fixture_sha256() -> str:
    for line in (ROOT / "tools/locus-test-apk.properties").read_text().splitlines():
        if line.startswith("LOCUS_APK_SHA256="):
            value = line.partition("=")[2]
            require(len(value) == 64, "invalid fixture fingerprint")
            return value
    raise EvidenceError("missing fixture fingerprint")


def runner_inputs() -> dict[str, str]:
    image_os = os.environ.get("ImageOS", "")
    image_version = os.environ.get("ImageVersion", "")
    require(bool(image_os and image_version), "missing hosted runner image identity")
    docker = str(command("docker", "version", "--format", "{{.Server.Version}}")).strip()
    require(bool(docker), "missing Docker server version")
    return {"image_os": image_os, "image_version": image_version, "docker_version": docker}


def current_checkout() -> tuple[str, str]:
    commit = git("rev-parse", "HEAD")
    tree = git("rev-parse", "HEAD^{tree}")
    require(commit == os.environ.get("GITHUB_SHA"), "checkout differs from GITHUB_SHA")
    return commit, tree


def github_event() -> dict:
    event_path = os.environ.get("GITHUB_EVENT_PATH")
    return json.loads(Path(event_path).read_text()) if event_path else {}


def capture(component: str, fixture_path: str | None) -> None:
    require(component in COMPONENT_JOBS, f"unknown CI component: {component}")
    commit, tree = current_checkout()
    event = github_event().get("pull_request", {})
    pr = {
        "number": event.get("number"),
        "base_sha": event.get("base", {}).get("sha"),
        "head_sha": event.get("head", {}).get("sha"),
        "head_repo": event.get("head", {}).get("repo", {}).get("full_name"),
    } if os.environ.get("GITHUB_EVENT_NAME") == "pull_request" else None
    fixture = None
    if component in {"android", "emery", "gabbro"}:
        require(bool(fixture_path), "acceptance evidence needs the downloaded fixture")
        fixture = sha256(Path(fixture_path))
        require(fixture == fixture_sha256(), "downloaded fixture differs from its pin")
    record = {
        "schema": 1,
        "repository": os.environ.get("GITHUB_REPOSITORY"),
        "run_id": int(os.environ["GITHUB_RUN_ID"]),
        "attempt": int(os.environ["GITHUB_RUN_ATTEMPT"]),
        "event": os.environ.get("GITHUB_EVENT_NAME"),
        "component": component,
        "checkout_sha": commit,
        "checkout_tree": tree,
        "github_sha": os.environ.get("GITHUB_SHA"),
        "pr": pr,
        "workflow_blob": git("rev-parse", "HEAD:.github/workflows/ci.yml"),
        "inputs": input_fingerprints(),
        "fixture_sha256": fixture,
        "watch_passes": os.environ.get("WATCH_PASSES", "1"),
        "acceptance_provisioning": os.environ.get("ACCEPTANCE_PROVISIONING", "published"),
        "runner": runner_inputs(),
        "finalized": False,
    }
    target = ROOT / "build/ci-evidence" / f"{component}.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n")


def finalize(component: str) -> None:
    require(component in COMPONENT_JOBS, f"unknown CI component: {component}")
    target = ROOT / "build/ci-evidence" / f"{component}.json"
    record = json.loads(target.read_text())
    commit, tree = current_checkout()
    require(record.get("component") == component and record.get("checkout_sha") == commit
            and record.get("checkout_tree") == tree and not record.get("finalized"),
            "post-test evidence does not match the checkout")
    changed = git("diff", "HEAD", "--name-only", "--").splitlines()
    record["tracked_changes"] = changed
    record["observed_lock_sha256"] = sha256(ROOT / "watchapp/package-lock.json")
    record["finalized"] = True
    target.write_text(json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n")


class GitHub:
    def __init__(self, repository: str):
        self.repository = repository
        self.base = f"repos/{repository}"

    def json(self, endpoint: str):
        return json.loads(str(command("gh", "api", "--method", "GET", f"{self.base}/{endpoint}")))

    def pages(self, endpoint: str, key: str) -> list[dict]:
        items: list[dict] = []
        for page in range(1, 11):
            separator = "&" if "?" in endpoint else "?"
            payload = self.json(f"{endpoint}{separator}per_page=100&page={page}")
            batch = payload.get(key, []) if isinstance(payload, dict) else payload
            require(isinstance(batch, list), f"invalid {key} API response")
            items.extend(batch)
            total = payload.get("total_count") if isinstance(payload, dict) else None
            if (total is not None and len(items) >= total) or len(batch) < 100:
                return items
        raise EvidenceError(f"{key} API response exceeds ten pages")

    def artifact(self, artifact_id: int) -> bytes:
        return bytes(command("gh", "api", "--method", "GET",
                             f"{self.base}/actions/artifacts/{artifact_id}/zip", binary=True))


def attempt_jobs(gh: GitHub, run_id: int, attempt: int) -> dict[str, dict]:
    jobs = gh.pages(f"actions/runs/{run_id}/attempts/{attempt}/jobs", "jobs")
    names = [job.get("name") for job in jobs]
    require(len(names) == len(set(names)), "duplicate job names in run attempt")
    return {job["name"]: job for job in jobs}


def successful_checks(jobs: dict[str, dict]) -> dict[str, dict]:
    missing = REQUIRED_JOBS - jobs.keys()
    require(not missing, f"missing required jobs: {', '.join(sorted(missing))}")
    for name in REQUIRED_JOBS:
        job = jobs[name]
        require(job.get("status") == "completed" and job.get("conclusion") == "success",
                f"required job {name} concluded {job.get('conclusion')}")
    return {name: jobs[name] for name in sorted(REQUIRED_JOBS)}


def artifact_record(gh: GitHub, artifacts: list[dict], component: str, run: dict,
                    job: dict) -> dict:
    name = f"ci-evidence-{component}-{run['id']}-{run['run_attempt']}"
    matches = [artifact for artifact in artifacts if artifact.get("name") == name]
    require(len(matches) == 1, f"missing or duplicate {component} evidence")
    artifact = matches[0]
    require(not artifact.get("expired"), f"expired {component} evidence")
    require(artifact.get("workflow_run", {}).get("id") == run["id"],
            f"{component} evidence belongs to another run")
    require(artifact.get("workflow_run", {}).get("head_sha") == run["head_sha"],
            f"{component} evidence head differs")
    require(timestamp(job["started_at"]) <= timestamp(artifact["created_at"]) <= timestamp(job["completed_at"]),
            f"{component} evidence is outside the latest job attempt")
    archive = gh.artifact(artifact["id"])
    require(len(archive) <= MAX_EVIDENCE_BYTES, f"oversized {component} evidence")
    digest = "sha256:" + hashlib.sha256(archive).hexdigest()
    require(artifact.get("digest") == digest, f"{component} evidence digest differs")
    try:
        with ZipFile(io.BytesIO(archive)) as zip_file:
            require(zip_file.namelist() == [f"{component}.json"], f"invalid {component} evidence archive")
            info = zip_file.getinfo(f"{component}.json")
            require(info.file_size <= MAX_EVIDENCE_BYTES, f"oversized {component} evidence file")
            return json.loads(zip_file.read(info))
    except (OSError, ValueError, KeyError, BadZipFile) as error:
        raise EvidenceError(f"invalid {component} evidence: {error}") from error


def changed_policy_paths() -> list[str]:
    parents = git("rev-list", "--parents", "-n", "1", "HEAD").split()
    require(len(parents) == 2, "main commit does not have one parent")
    changed = git("diff", "--name-only", parents[1], parents[0]).splitlines()
    return [path for path in changed if path in INVALIDATION_EXACT
            or path.startswith(INVALIDATION_PREFIXES)
            or (path.startswith("docs/requirements") and path.endswith((".txt", ".lock")))]


def merge_tree(base: str, head: str) -> str:
    for commit in (base, head):
        if subprocess.run(["git", "cat-file", "-e", f"{commit}^{{commit}}"],
                          cwd=ROOT, capture_output=True, check=False).returncode:
            command("git", "fetch", "--quiet", "--no-tags", "origin", commit)
    return git("merge-tree", "--write-tree", base, head).splitlines()[0]


def source_candidate(gh: GitHub, commit: str) -> tuple[dict, dict]:
    pulls = gh.pages(f"commits/{commit}/pulls", "pulls")
    matching = [pr for pr in pulls if pr.get("merged_at") and pr.get("merge_commit_sha") == commit
                and pr.get("base", {}).get("ref") == "main"
                and pr.get("head", {}).get("repo", {}).get("full_name") == gh.repository]
    require(len(matching) == 1, "no unique merged same-repository PR for main commit")
    pr = matching[0]
    head = pr["head"]["sha"]
    runs = gh.pages(f"actions/workflows/ci.yml/runs?event=pull_request&head_sha={head}", "workflow_runs")
    matching_runs = [run for run in runs if run.get("event") == "pull_request"
                     and run.get("head_sha") == head
                     and run.get("head_repository", {}).get("full_name") == gh.repository
                     and run.get("head_branch") == pr["head"]["ref"]
                     and str(run.get("path", "")).startswith(".github/workflows/ci.yml")
                     and timestamp(run["created_at"]) <= timestamp(pr["merged_at"])]
    require(bool(matching_runs), "no matching PR CI run")
    run = max(matching_runs, key=lambda item: (timestamp(item["created_at"]), item["id"]))
    return pr, run


def assess(gh: GitHub, commit: str, *, now: datetime | None = None) -> dict:
    require(commit == git("rev-parse", "HEAD"), "main checkout differs from commit")
    invalidated = changed_policy_paths()
    require(not invalidated, f"policy or toolchain changed: {', '.join(invalidated[:5])}")
    pr, run = source_candidate(gh, commit)
    require(run.get("status") == "completed" and run.get("conclusion") == "success",
            f"newest PR run concluded {run.get('conclusion')}")
    attempt = run.get("run_attempt")
    require(isinstance(attempt, int) and attempt > 0, "PR run has no valid attempt")
    jobs = successful_checks(attempt_jobs(gh, run["id"], attempt))
    require(all(jobs[name].get("run_attempt") == attempt for name in REQUIRED_JOBS),
            "latest PR attempt is partial")
    completed = max(timestamp(job["completed_at"]) for job in jobs.values())
    age = ((now or datetime.now(timezone.utc)) - completed).total_seconds()
    require(0 <= age <= 24 * 60 * 60, "PR evidence is older than 24 hours")
    current_tree = git("rev-parse", "HEAD^{tree}")
    require(merge_tree(pr["base"]["sha"], pr["head"]["sha"]) == current_tree,
            "PR synthetic merge tree differs from main")
    artifacts = gh.pages(f"actions/runs/{run['id']}/artifacts", "artifacts")
    expected_inputs = input_fingerprints()
    expected_blob = git("rev-parse", "HEAD:.github/workflows/ci.yml")
    expected_runner = runner_inputs()
    checkout_shas = set()
    observed_locks = set()
    check_links = {}
    for component, name in COMPONENT_JOBS.items():
        job = jobs[name]
        evidence = artifact_record(gh, artifacts, component, run, job)
        require(evidence.get("schema") == 1 and evidence.get("repository") == gh.repository
                and evidence.get("finalized") is True,
                f"{component} evidence schema/repository/finalization differs")
        require(evidence.get("run_id") == run["id"] and evidence.get("attempt") == attempt
                and evidence.get("event") == "pull_request" and evidence.get("component") == component,
                f"{component} evidence run identity differs")
        expected_pr = {"number": pr["number"], "base_sha": pr["base"]["sha"],
                       "head_sha": pr["head"]["sha"], "head_repo": gh.repository}
        require(evidence.get("pr") == expected_pr, f"{component} evidence PR identity differs")
        require(evidence.get("checkout_sha") == evidence.get("github_sha")
                and evidence.get("checkout_tree") == current_tree,
                f"{component} tested checkout differs from main tree")
        checkout_shas.add(evidence["checkout_sha"])
        require(evidence.get("workflow_blob") == expected_blob and evidence.get("inputs") == expected_inputs,
                f"{component} workflow or input fingerprint differs")
        require(evidence.get("runner") == expected_runner,
                f"{component} hosted runner or Docker version differs")
        require(evidence.get("watch_passes") == "1"
                and evidence.get("acceptance_provisioning") == "published",
                f"{component} effective acceptance settings differ")
        expected_fixture = fixture_sha256() if component in {"android", "emery", "gabbro"} else None
        require(evidence.get("fixture_sha256") == expected_fixture,
                f"{component} fixture fingerprint differs")
        changes = evidence.get("tracked_changes")
        require(isinstance(changes, list) and set(changes) <= {"watchapp/package-lock.json"},
                f"{component} changed other tracked test inputs")
        observed_lock = evidence.get("observed_lock_sha256")
        require(isinstance(observed_lock, str) and len(observed_lock) == 64,
                f"{component} lacks observed dependency fingerprint")
        if not changes:
            require(observed_lock == expected_inputs["watchapp/package-lock.json"],
                    f"{component} dependency lock differs without a tracked change")
        observed_locks.add(observed_lock)
        check_links[name] = job.get("html_url", "")
    require(len(checkout_shas) == 1, "PR jobs tested different merge commits")
    require(len(observed_locks - {expected_inputs["watchapp/package-lock.json"]}) <= 1,
            "PR jobs used different post-build dependency locks")
    check_links["Hosted full-stack acceptance"] = jobs["Hosted full-stack acceptance"].get("html_url", "")
    return {"run_id": run["id"], "attempt": attempt, "run_url": run.get("html_url", ""),
            "pr_number": pr["number"], "head_sha": pr["head"]["sha"],
            "checkout_sha": checkout_shas.pop(), "checkout_tree": current_tree,
            "completed_at": completed.isoformat(), "checks": check_links,
            "observed_dependency_locks": sorted(observed_locks)}


def output(**values: object) -> None:
    if path := os.environ.get("GITHUB_OUTPUT"):
        with open(path, "a", encoding="utf-8") as stream:
            for key, value in values.items():
                stream.write(f"{key}={str(value).replace(chr(10), ' ')[:500]}\n")


def decide() -> None:
    mode, reason, source = "full", "non-main event", None
    if os.environ.get("GITHUB_EVENT_NAME") == "push" and os.environ.get("GITHUB_REF") == "refs/heads/main":
        try:
            commit, _ = current_checkout()
            source = assess(GitHub(os.environ["GITHUB_REPOSITORY"]), commit)
            mode, reason = "reuse", "verified matching PR evidence"
        except Exception as error:
            reason = str(error).replace("\n", " ")[:400]
    print(f"Main CI decision: {mode}: {reason}", flush=True)
    output(mode=mode, reason=reason,
           source_run_id=source["run_id"] if source else "",
           source_attempt=source["attempt"] if source else "")


def certify() -> None:
    require(os.environ.get("GITHUB_EVENT_NAME") == "push"
            and os.environ.get("GITHUB_REF") == "refs/heads/main", "certification requires main push")
    commit, tree = current_checkout()
    needs = json.loads(os.environ["CI_NEEDS_JSON"])
    require(needs["plan"]["result"] == "success", "CI planning job failed")
    mode = needs["plan"]["outputs"]["mode"]
    component_needs = ("static", "documentation", "acceptance-android",
                       "acceptance-watch", "acceptance-hosted")
    expected = "skipped" if mode == "reuse" else "success"
    require(mode in {"reuse", "full"}, "unknown CI decision")
    for name in component_needs:
        require(needs[name]["result"] == expected,
                f"main {name} concluded {needs[name]['result']}, expected {expected}")
    gh = GitHub(os.environ["GITHUB_REPOSITORY"])
    source = None
    checks = {}
    main_jobs = attempt_jobs(gh, int(os.environ["GITHUB_RUN_ID"]),
                             int(os.environ["GITHUB_RUN_ATTEMPT"]))
    if mode == "reuse":
        source = assess(gh, commit)
        require(str(source["run_id"]) == needs["plan"]["outputs"].get("source_run_id")
                and str(source["attempt"]) == needs["plan"]["outputs"].get("source_attempt"),
                "PR evidence changed after main planning")
        checks = source["checks"]
    else:
        jobs = successful_checks(main_jobs)
        checks = {name: job.get("html_url", "") for name, job in jobs.items()}
    certified_at = datetime.now(timezone.utc)
    main_run = gh.json(f"actions/runs/{os.environ['GITHUB_RUN_ID']}")
    elapsed = (certified_at - timestamp(main_run["run_started_at"])).total_seconds()
    require(elapsed >= 0, "main run start is after certification")
    completed_job_seconds = sum(
        (timestamp(job["completed_at"]) - timestamp(job["started_at"])).total_seconds()
        for job in main_jobs.values() if job.get("status") == "completed"
        and job.get("started_at") and job.get("completed_at")
    )
    record = {
        "schema": 1, "repository": gh.repository, "commit": commit, "tree": tree,
        "run_id": int(os.environ["GITHUB_RUN_ID"]),
        "attempt": int(os.environ["GITHUB_RUN_ATTEMPT"]),
        "workflow_blob": git("rev-parse", "HEAD:.github/workflows/ci.yml"),
        "inputs": input_fingerprints(), "fixture_sha256": fixture_sha256(),
        "runner": runner_inputs(), "effective_config": {"watch_passes": "1", "acceptance_provisioning": "published"},
        "decision": mode, "fallback_reason": needs["plan"]["outputs"].get("reason", ""),
        "source": source, "checks": checks,
        "certified_at": certified_at.isoformat(),
        "metrics": {"main_elapsed_seconds": round(elapsed),
                    "completed_job_runner_seconds": round(completed_job_seconds),
                    "repeated_components": 0 if mode == "reuse" else len(COMPONENT_JOBS)},
    }
    target = ROOT / "build/ci-certification.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as stream:
            stream.write(f"## Main commit certification\n\n- Commit: `{commit}`\n"
                         f"- Decision: **{mode}**\n- Reason: {record['fallback_reason']}\n"
                         f"- Main time to certification: {round(elapsed)}s\n"
                         f"- Completed runner time before certification: {round(completed_job_seconds)}s\n"
                         f"- Repeated test components: {record['metrics']['repeated_components']}\n")
            if source:
                stream.write(f"- Source: [PR run {source['run_id']} attempt {source['attempt']}]"
                             f"({source['run_url']})\n")
            for name, url in checks.items():
                stream.write(f"  - [{name}]({url})\n")
    output(decision=mode, source_run_id=source["run_id"] if source else "",
           source_run_url=source["run_url"] if source else "")
    print(f"Certified main {commit} using {mode} evidence.")


def verify_record_attestation(path: Path, repository: str, commit: str) -> None:
    result = str(command(
        "gh", "attestation", "verify", str(path), "--repo", repository,
        "--signer-workflow", f"{repository}/.github/workflows/ci.yml",
        "--source-ref", "refs/heads/main", "--source-digest", commit,
        "--deny-self-hosted-runners", "--predicate-type", PREDICATE_TYPE,
        "--format", "json",
    ))
    attestations = json.loads(result)
    expected = json.loads(path.read_text())
    require(any(item.get("verificationResult", {}).get("statement", {}).get("predicate") == expected
                for item in attestations), "certification attestation predicate differs")


def verify_main_record(gh: GitHub, commit: str, run: dict) -> dict:
    attempt = run.get("run_attempt")
    require(isinstance(attempt, int) and attempt > 0, "main run has no valid attempt")
    jobs = attempt_jobs(gh, run["id"], attempt)
    plan = jobs.get("Select main CI evidence")
    require(plan is not None and plan.get("status") == "completed"
            and plan.get("conclusion") == "success" and plan.get("run_attempt") == attempt,
            "latest main attempt lacks successful evidence planning")
    cert = jobs.get(CERTIFICATION_JOB)
    require(cert is not None and cert.get("status") == "completed"
            and cert.get("conclusion") == "success" and cert.get("run_attempt") == attempt,
            "latest main attempt lacks successful certification")
    artifacts = gh.pages(f"actions/runs/{run['id']}/artifacts", "artifacts")
    name = f"main-certification-{run['id']}-{attempt}"
    matches = [artifact for artifact in artifacts if artifact.get("name") == name]
    require(len(matches) == 1 and not matches[0].get("expired"), "missing main certification record")
    artifact = matches[0]
    require(artifact.get("workflow_run", {}).get("id") == run["id"]
            and artifact.get("workflow_run", {}).get("head_sha") == commit,
            "main certification artifact origin differs")
    require(timestamp(cert["started_at"]) <= timestamp(artifact["created_at"]) <= timestamp(cert["completed_at"]),
            "main certification record is outside latest attempt")
    archive = gh.artifact(artifact["id"])
    require(len(archive) <= MAX_EVIDENCE_BYTES
            and artifact.get("digest") == "sha256:" + hashlib.sha256(archive).hexdigest(),
            "main certification record digest differs")
    try:
        with ZipFile(io.BytesIO(archive)) as zip_file:
            require(zip_file.namelist() == ["ci-certification.json"], "invalid main certification archive")
            require(zip_file.getinfo("ci-certification.json").file_size <= MAX_EVIDENCE_BYTES,
                    "oversized main certification record")
            payload = zip_file.read("ci-certification.json")
    except (OSError, BadZipFile, KeyError) as error:
        raise EvidenceError(f"invalid main certification archive: {error}") from error
    require(len(payload) <= MAX_EVIDENCE_BYTES, "oversized main certification record")
    record = json.loads(payload)
    require(record.get("schema") == 1 and record.get("repository") == gh.repository
            and record.get("commit") == commit and record.get("run_id") == run["id"]
            and record.get("attempt") == attempt and record.get("tree") == git("rev-parse", f"{commit}^{{tree}}"),
            "main certification record identity differs")
    require(record.get("workflow_blob") == git("rev-parse", f"{commit}:.github/workflows/ci.yml")
            and record.get("inputs") == input_fingerprints()
            and record.get("fixture_sha256") == fixture_sha256()
            and record.get("effective_config") == {"watch_passes": "1", "acceptance_provisioning": "published"},
            "main certification workflow or inputs differ from release commit")
    with tempfile.TemporaryDirectory() as temporary:
        path = Path(temporary) / "ci-certification.json"
        path.write_bytes(payload)
        verify_record_attestation(path, gh.repository, commit)
    if record.get("decision") == "full":
        successful_checks(jobs)
        require(record.get("source") is None, "full certification names a PR source")
    elif record.get("decision") == "reuse":
        source = record.get("source")
        require(isinstance(source, dict), "reuse record lacks PR source")
        source_run = gh.json(f"actions/runs/{source['run_id']}")
        require(source_run.get("run_attempt") == source.get("attempt")
                and source_run.get("status") == "completed"
                and source_run.get("conclusion") == "success"
                and source_run.get("event") == "pull_request",
                "PR source has a newer or unsuccessful attempt")
        pr, newest = source_candidate(gh, commit)
        require(pr["number"] == source.get("pr_number") and newest["id"] == source["run_id"],
                "newer PR CI run superseded certification source")
        source_jobs = successful_checks(attempt_jobs(gh, source["run_id"], source["attempt"]))
        require(all(job.get("run_attempt") == source["attempt"] for job in source_jobs.values()),
                "PR source latest attempt is partial")
    else:
        raise EvidenceError("unknown main certification decision")
    return record


def main() -> None:
    parser = argparse.ArgumentParser()
    subcommands = parser.add_subparsers(dest="action", required=True)
    capture_parser = subcommands.add_parser("capture")
    capture_parser.add_argument("component", choices=COMPONENT_JOBS)
    capture_parser.add_argument("--fixture-path")
    finalize_parser = subcommands.add_parser("finalize")
    finalize_parser.add_argument("component", choices=COMPONENT_JOBS)
    subcommands.add_parser("decide")
    subcommands.add_parser("certify")
    args = parser.parse_args()
    try:
        if args.action == "capture":
            capture(args.component, args.fixture_path)
        elif args.action == "finalize":
            finalize(args.component)
        elif args.action == "decide":
            decide()
        else:
            certify()
    except (EvidenceError, KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise SystemExit(f"CI certification failed: {error}") from error


if __name__ == "__main__":
    main()
