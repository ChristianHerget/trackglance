"""Enable protected auto-merge only for verified Dependabot minor/patch updates."""

import json
import os
from pathlib import Path
import re
import subprocess


REPOSITORY = "ChristianHerget/trackglance"
ALLOWED_UPDATES = {"version-update:semver-minor", "version-update:semver-patch"}


def trusted_pr(pr):
    base = pr.get("base") or {}
    head = pr.get("head") or {}
    return (
        pr.get("user", {}).get("login") == "dependabot[bot]"
        and base.get("ref") == "main"
        and (base.get("repo") or {}).get("full_name") == REPOSITORY
        and (head.get("repo") or {}).get("full_name") == REPOSITORY
    )


def manual_reason(pr, outcome, metadata):
    if pr.get("draft") is not False:
        return "the PR is a draft or its draft status is unknown"
    if outcome != "success":
        return "Dependabot commit metadata could not be verified"
    try:
        dependencies = json.loads(metadata)
    except (TypeError, ValueError):
        return "dependency metadata is missing or invalid"
    if not isinstance(dependencies, list) or not dependencies:
        return "dependency metadata is empty or invalid"
    if any(
        not isinstance(dependency, dict)
        or not isinstance(dependency.get("dependencyName"), str)
        or not dependency["dependencyName"].strip()
        or not isinstance(dependency.get("updateType"), str)
        or dependency.get("updateType") not in ALLOWED_UPDATES
        for dependency in dependencies
    ):
        return "an update is major, unknown, or missing dependency metadata"
    return None


def gh(*arguments):
    result = subprocess.run(
        ["gh", *arguments], capture_output=True, text=True, check=True, timeout=60,
    )
    return result.stdout


def report(message):
    print(message, flush=True)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with Path(summary).open("a") as output:
            output.write(message + "\n")


def main():
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
    pr = event.get("pull_request", {})
    number = event.get("number")
    expected_head = pr.get("head", {}).get("sha", "")
    if (
        os.environ.get("GITHUB_EVENT_NAME") != "pull_request_target"
        or os.environ.get("GITHUB_REPOSITORY") != REPOSITORY
        or not trusted_pr(pr)
        or type(number) is not int or number <= 0
        or not isinstance(expected_head, str)
        or not re.fullmatch(r"[0-9a-f]{40}", expected_head)
    ):
        report("No merge request changed: this event is outside the Dependabot policy.")
        return

    current = json.loads(gh("api", f"repos/{REPOSITORY}/pulls/{number}"))
    if current.get("state") != "open" or not trusted_pr(current):
        report(f"No merge request changed: PR #{number} is closed or outside the policy.")
        return
    if current.get("head", {}).get("sha") != expected_head:
        report(f"No merge request changed: PR #{number} has a newer head; its update will be checked separately.")
        return

    reason = manual_reason(
        current, os.environ.get("DEPENDABOT_METADATA_OUTCOME"),
        os.environ.get("DEPENDABOT_UPDATED_DEPENDENCIES", ""),
    )
    arguments = ("pr", "merge", str(number), "--repo", REPOSITORY)
    if reason:
        if current.get("auto_merge") is not None:
            gh(*arguments, "--disable-auto")
        report(f"PR #{number} requires a manual merge because {reason}. Auto-merge is disabled.")
        return

    gh(*arguments, "--auto", "--squash", "--match-head-commit", expected_head)
    report(f"PR #{number} is eligible for squash auto-merge. Required checks and branch protections still apply.")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(f"GitHub auto-merge operation failed: {error.stderr.strip()}") from error
    except subprocess.TimeoutExpired as error:
        raise SystemExit("GitHub auto-merge operation timed out; no operation will be retried automatically.") from error
