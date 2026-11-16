"""Read exact-commit workflow results for release qualification."""

import json
import subprocess
import time


class CheckError(ValueError):
    pass


class GitHub:
    def __init__(self, repository):
        self.repository = repository

    def json(self, resource):
        try:
            result = subprocess.run(
                ["gh", "api", f"repos/{self.repository}/{resource}"],
                capture_output=True, text=True, check=False, timeout=30,
            )
        except subprocess.TimeoutExpired as error:
            raise CheckError("GitHub API lookup timed out") from error
        if result.returncode:
            raise CheckError(result.stderr.strip()[:300])
        return json.loads(result.stdout)

    def pages(self, resource, key):
        values = []
        separator = "&" if "?" in resource else "?"
        for page in range(1, 101):
            batch = self.json(f"{resource}{separator}per_page=100&page={page}")[key]
            values.extend(batch)
            if len(batch) < 100:
                return values
        raise CheckError("workflow lookup exceeded pagination limit")


def latest_run(gh, workflow, commit, events):
    runs = gh.pages(f"actions/workflows/{workflow}/runs?head_sha={commit}", "workflow_runs")
    candidates = [
        run for run in runs
        if run.get("head_sha") == commit
        and run.get("head_branch") == "main"
        and run.get("event") in events
        and run.get("path") == f".github/workflows/{workflow}"
        and (run.get("repository") or {}).get("full_name") == gh.repository
        and (run.get("head_repository") or {}).get("full_name") == gh.repository
    ]
    return max(candidates, key=lambda run: run["id"]) if candidates else None


def require_run(gh, run, required, timeout=14400):
    deadline = time.monotonic() + timeout
    while True:
        current = gh.json(f"actions/runs/{run['id']}")
        for key in ("id", "head_sha", "head_branch", "event", "path", "run_attempt"):
            if current.get(key) != run.get(key):
                raise CheckError(f"workflow identity or attempt changed: {key}")
        if current.get("status") == "completed":
            if current.get("conclusion") != "success":
                raise CheckError(f"workflow {run['id']} concluded {current.get('conclusion')}")
            jobs = gh.pages(
                f"actions/runs/{run['id']}/attempts/{run['run_attempt']}/jobs", "jobs"
            )
            by_name = {job["name"]: job for job in jobs}
            for name in required:
                matches = [job for job in jobs if job["name"] == name]
                if len(matches) != 1 or by_name[name].get("conclusion") != "success":
                    raise CheckError(f"required job did not pass exactly once: {name}")
            return current
        if time.monotonic() >= deadline:
            raise CheckError("workflow qualification deadline exceeded")
        time.sleep(15)


SYNTHETIC_JOBS = {
    "Android synthetic runtime", "Watch synthetic runtime", "Helpers and policy",
    "Committed documentation", "Synthetic CI",
}
ACCEPTANCE_JOBS = {
    "Android instrumentation", "Watch acceptance (emery)",
    "Watch acceptance (gabbro)", "Hosted full-stack acceptance",
}
