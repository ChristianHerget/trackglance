import hashlib
import io
import itertools
import json
import os
from pathlib import Path
import runpy
import shutil
import subprocess
import tempfile
import unittest
import urllib.error
from unittest.mock import MagicMock, patch

from script_test_support import ROOT


WORKFLOW = "ChristianHerget/trackglance/.github/workflows/publish-ci-images.yml@refs/heads/main"
NAMES = ("runner", "emulator", "codeql")
REPOSITORIES = {
    "runner": "ghcr.io/christianherget/trackglance-acceptance-runner",
    "emulator": "ghcr.io/christianherget/trackglance-acceptance-emulator",
    "codeql": "ghcr.io/christianherget/trackglance-codeql-kotlin",
}
DIGESTS = {name: "sha256:" + character * 64 for name, character in zip(NAMES, "abc")}
RESOLVER = runpy.run_path(str(ROOT / "tools/resolve-ci-image"))


class RegistryResolutionTest(unittest.TestCase):
    image = REPOSITORIES["runner"] + ":" + "a" * 20

    def response(self, body, headers=None):
        response = MagicMock()
        response.__enter__.return_value = response
        response.status = 200
        response.headers = headers or {}
        response.read.return_value = body
        return response

    def error(self, status, body):
        return urllib.error.HTTPError("https://ghcr.io/test", status, "registry error", {}, io.BytesIO(body))

    def resolve(self, *responses):
        with patch("urllib.request.urlopen", side_effect=responses) as request:
            result = RESOLVER["resolve"](self.image, "actor", "secret")
        return result, request

    def test_resolves_and_validates_the_digest_with_authenticated_bounded_requests(self):
        body = b'{"schemaVersion":2}'
        digest = "sha256:" + hashlib.sha256(body).hexdigest()
        result, request = self.resolve(
            self.response(b'{"token":"scoped-token"}'),
            self.response(body, {"Docker-Content-Digest": digest}),
        )
        self.assertEqual(result, REPOSITORIES["runner"] + "@" + digest)
        token_request, manifest_request = [call.args[0] for call in request.call_args_list]
        self.assertIn("scope=repository%3Achristianherget%2Ftrackglance-acceptance-runner%3Apull", token_request.full_url)
        self.assertTrue(token_request.get_header("Authorization").startswith("Basic "))
        self.assertEqual(manifest_request.get_header("Authorization"), "Bearer scoped-token")
        self.assertTrue(manifest_request.full_url.endswith("/manifests/" + "a" * 20))
        self.assertIn("application/vnd.oci.image.index.v1+json", manifest_request.get_header("Accept"))
        for call in request.call_args_list:
            self.assertEqual(call.kwargs["timeout"], 30)

    def test_only_structured_manifest_unknown_with_http_404_means_missing(self):
        result, _ = self.resolve(
            self.response(b'{"token":"token"}'),
            self.error(404, b'{"errors":[{"code":"MANIFEST_UNKNOWN"}]}'),
        )
        self.assertIsNone(result)
        for status, body in (
            (401, b'{"errors":[{"code":"UNAUTHORIZED"}]}'),
            (403, b'{"errors":[{"code":"DENIED"}]}'),
            (404, b'{"errors":[{"code":"NAME_UNKNOWN"}]}'),
            (404, b'{"errors":[]}'),
            (404, b'{"errors":[{"code":"MANIFEST_UNKNOWN"},{"code":"DENIED"}]}'),
            (404, b"not found"),
            (429, b"rate limited"),
            (500, b'{"errors":[{"code":"MANIFEST_UNKNOWN"}]}'),
            (503, b"unavailable"),
        ):
            with self.subTest(status=status, body=body), self.assertRaises((RESOLVER["RegistryError"], ValueError)):
                self.resolve(self.response(b'{"token":"token"}'), self.error(status, body))

    def test_authentication_errors_and_timeouts_never_mean_missing(self):
        for response in (
            self.error(401, b"unauthorized"),
            self.error(404, b'{"errors":[{"code":"MANIFEST_UNKNOWN"}]}'),
            self.response(b"{}"),
            self.response(b"bad json"),
            TimeoutError("timed out"),
            urllib.error.URLError("connection reset"),
        ):
            with self.subTest(response=response), self.assertRaises((RESOLVER["RegistryError"], ValueError)):
                self.resolve(response)
        for error in (TimeoutError("timed out"), urllib.error.URLError("connection reset")):
            with self.subTest(error=error), self.assertRaises(RESOLVER["RegistryError"]):
                self.resolve(self.response(b'{"token":"token"}'), error)

    def test_missing_malformed_or_mismatched_digest_stops_resolution(self):
        for digest in ("", "sha256:short", "sha256:" + "0" * 64):
            with self.subTest(digest=digest), self.assertRaises(RESOLVER["RegistryError"]):
                self.resolve(
                    self.response(b'{"token":"token"}'),
                    self.response(b"manifest", {"Docker-Content-Digest": digest}),
                )


# Each executable fake records its invocation. Registry state persists across
# separate publisher processes, allowing a real rerun of the shell orchestration.
FAKE_COMMAND = r'''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys

command = Path(sys.argv[0]).name
args = sys.argv[1:]
state_path = Path(os.environ["FAKE_STATE"])
state = json.loads(state_path.read_text())
with Path(os.environ["FAKE_LOG"]).open("a") as log:
    log.write(json.dumps([command, *args]) + "\n")

def save():
    state_path.write_text(json.dumps(state))

def name_for(reference):
    return next(name for name, repo in state["repositories"].items() if reference.startswith(repo))

if command == "resolve-ci-image":
    name = name_for(args[0])
    count = state.setdefault("resolutions", {}).get(name, 0) + 1
    state["resolutions"][name] = count
    save()
    if state.get("registry_error") == name and count >= state.get("registry_error_after", 1):
        print("Registry authentication/network/manifest failure", file=sys.stderr)
        sys.exit(1)
    if state.get("race") == name and count == 2:
        state["images"][name] = state["digests"][name]
        save()
    digest = state["images"][name]
    if digest is None:
        sys.exit(3)
    print(state["repositories"][name] + "@" + digest)
elif command == "gh":
    assert args[:2] == ["attestation", "verify"]
    assert "@sha256:" in args[2]
    assert args[args.index("--source-ref") + 1] == "refs/heads/main"
    assert args[args.index("--signer-workflow") + 1] == state["workflow"].split("@")[0]
    identity_flags = {"--cert-identity", "--cert-identity-regex", "--signer-workflow"}
    assert sum(arg.split("=", 1)[0] in identity_flags for arg in args) <= 1
    assert args[args.index("--repo") + 1] == "ChristianHerget/trackglance"
    predicate = args[args.index("--predicate-type") + 1]
    failure = state.get("verification_error")
    if failure in (predicate, "wrong workflow", "wrong branch", "attestation timeout"):
        print("Attestation rejected: " + failure, file=sys.stderr)
        sys.exit(1)
elif command == "cosign":
    if args[0] == "verify":
        assert "@sha256:" in args[1]
        assert args[args.index("--certificate-identity") + 1] == "https://github.com/" + state["workflow"]
        assert args[args.index("--certificate-oidc-issuer") + 1] == "https://token.actions.githubusercontent.com"
        if state.get("verification_error") == "signature":
            print("Missing or invalid signature", file=sys.stderr)
            sys.exit(1)
    else:
        assert args[:2] == ["sign", "--yes"]
elif command == "docker":
    if args[:2] == ["image", "history"]:
        print("COPY /private/locus.apk /opt/locus.apk" if state.get("forbidden") else "public toolchain")
    elif args[0] == "run":
        print("unexpected.apk" if state.get("bad_fixture") else "pebble-app-x86_64-debug.apk")
    elif args[0] == "push":
        name = name_for(args[1])
        assert state["images"][name] is None, "attempted overwrite"
        state["images"][name] = state["digests"][name]
        save()
    elif args[:2] == ["image", "inspect"]:
        name = name_for(args[-1])
        digest = "sha256:" + "f" * 64 if state.get("digest_mismatch") else state["digests"][name]
        print(state["repositories"][name] + "@" + digest)
    elif args[0] not in ("build", "tag"):
        raise AssertionError(args)
elif command == "podman-test":
    assert args in (["build-acceptance"], ["build-static"])
elif command != "sudo":
    raise AssertionError(command)
'''


class CiImagePublicationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.repo = self.base / "repo"
        (self.repo / "tools/podman").mkdir(parents=True)
        for name in ("ci-image-key", "publish-ci-images", "verify-ci-image"):
            shutil.copy2(ROOT / "tools" / name, self.repo / "tools" / name)
        for kind in ("acceptance", "codeql-kotlin"):
            inputs = subprocess.check_output([str(ROOT / "tools/ci-image-key"), kind, "--list-inputs"], text=True)
            for name in inputs.splitlines():
                target = self.repo / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(ROOT / name, target)
        (self.repo / "tools/podman/release-metadata.sh").write_text(
            'load_release_metadata() { RELEASE_VERSION=0.0.0; }\n'
        )
        self.bin = self.base / "bin"
        self.bin.mkdir()
        fake = self.bin / "fake"
        fake.write_text(FAKE_COMMAND)
        fake.chmod(0o755)
        for command in ("docker", "gh", "cosign", "sudo"):
            (self.bin / command).symlink_to(fake)
        for command in ("podman-test", "resolve-ci-image"):
            (self.repo / "tools" / command).symlink_to(fake)
        self.state_path = self.base / "state.json"
        self.log = self.base / "commands.jsonl"
        self.output = self.base / "output"
        self.state = {
            "repositories": REPOSITORIES, "digests": DIGESTS, "workflow": WORKFLOW,
            "images": dict(DIGESTS),
        }
        self.env = {
            **os.environ, "PATH": f"{self.bin}:{os.environ['PATH']}",
            "FAKE_STATE": str(self.state_path), "FAKE_LOG": str(self.log),
            "GITHUB_OUTPUT": str(self.output), "GITHUB_REF": "refs/heads/main",
            "GITHUB_SHA": "0" * 40, "GH_TOKEN": "fake-token", "USER": "runner",
            "ACCEPTANCE_RUNNER_REPOSITORY": REPOSITORIES["runner"],
            "ACCEPTANCE_EMULATOR_REPOSITORY": REPOSITORIES["emulator"],
            "CODEQL_KOTLIN_REPOSITORY": REPOSITORIES["codeql"],
        }

    def publish(self):
        self.state_path.write_text(json.dumps(self.state))
        self.log.write_text("")
        self.output.write_text("")
        result = subprocess.run(
            [str(self.repo / "tools/publish-ci-images")], cwd=self.repo,
            env=self.env, capture_output=True, text=True, timeout=30,
        )
        self.state = json.loads(self.state_path.read_text())
        self.events = [json.loads(line) for line in self.log.read_text().splitlines()]
        return result

    def assert_no_builds_or_writes(self):
        for event in self.events:
            self.assertIn(event[0], ("resolve-ci-image", "gh", "cosign"), event)
            if event[0] == "cosign":
                self.assertEqual(event[1], "verify", event)
        self.assertEqual(self.output.read_text(), "")

    def test_fake_gh_rejects_conflicting_attestation_identity_flags(self):
        verifier = self.repo / "tools/verify-ci-image"
        original = verifier.read_text()
        for flag in ("--cert-identity", "--cert-identity-regex"):
            with self.subTest(flag=flag):
                verifier.write_text(original.replace(
                    '--signer-workflow "${workflow%@*}"',
                    '--signer-workflow "${workflow%@*}" ' + flag + ' invalid',
                ))
                result = self.publish()
                self.assertNotEqual(result.returncode, 0)
                self.assert_no_builds_or_writes()
        verifier.write_text(original)

    def test_every_presence_combination_publishes_only_missing_images(self):
        for present in itertools.product((True, False), repeat=3):
            with self.subTest(present=present):
                self.state["images"] = {name: DIGESTS[name] if exists else None for name, exists in zip(NAMES, present)}
                result = self.publish()
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                missing = [name for name, exists in zip(NAMES, present) if not exists]
                expected_builds = []
                if "runner" in missing or "emulator" in missing:
                    expected_builds = [["podman-test", "build-acceptance"]]
                elif "codeql" in missing:
                    expected_builds = [["podman-test", "build-static"]]
                self.assertEqual([e for e in self.events if e[0] == "podman-test"], expected_builds)
                self.assertEqual(any(e[0] == "sudo" for e in self.events), "runner" in missing or "emulator" in missing)
                built = [e[e.index("--tag") + 1].split(":")[0] for e in self.events if e[:2] == ["docker", "build"]]
                self.assertEqual(built, [REPOSITORIES[name] for name in ("runner", "codeql") if name in missing])
                tagged = [e[-1].split(":")[0] for e in self.events if e[:2] == ["docker", "tag"]]
                self.assertEqual(tagged, [REPOSITORIES["emulator"]] if "emulator" in missing else [])
                pushes = [e[2].split(":")[0] for e in self.events if e[:2] == ["docker", "push"]]
                self.assertEqual(pushes, [REPOSITORIES[name] for name in missing])
                signatures = [e[3] for e in self.events if e[:2] == ["cosign", "sign"]]
                self.assertEqual(signatures, [REPOSITORIES[name] + "@" + DIGESTS[name] for name in missing])
                # Every tag was resolved before verification, and all existing images
                # were verified before KVM setup, builds, content checks, or writes.
                self.assertEqual([e[0] for e in self.events[:3]], ["resolve-ci-image"] * 3)
                first_build = next((i for i, e in enumerate(self.events) if e[0] in ("sudo", "podman-test", "docker")), len(self.events))
                verifications = [i for i, e in enumerate(self.events) if e[:2] == ["cosign", "verify"]]
                self.assertEqual(len(verifications), sum(present))
                self.assertTrue(all(i < first_build for i in verifications))
                output = dict(line.split("=", 1) for line in self.output.read_text().splitlines())
                summary = (self.repo / "build/ci-image-publication-summary.md").read_text()
                for name in NAMES:
                    self.assertEqual(output[name + "_ref"], REPOSITORIES[name] + "@" + DIGESTS[name])
                    self.assertEqual(output[name + "_published"], str(name in missing).lower())
                    self.assertIn("published" if name in missing else "reused", summary)
                if not missing:
                    self.assertFalse(any(e[0] in ("docker", "sudo", "podman-test") for e in self.events))

    def test_rerunning_successful_publication_reuses_every_digest(self):
        self.state["images"] = dict.fromkeys(NAMES)
        result = self.publish()
        self.assertEqual(result.returncode, 0, result.stderr)
        original = dict(self.state["images"])
        result = self.publish()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.state["images"], original)
        self.assertFalse(any(e[0] in ("docker", "sudo", "podman-test") or e[:2] == ["cosign", "sign"] for e in self.events))

    def test_invalid_or_incomplete_verification_stops_before_builds_with_recovery_guidance(self):
        self.state["images"]["runner"] = None
        for failure in (
            "signature", "https://slsa.dev/provenance/v1", "https://spdx.dev/Document",
            "wrong workflow", "wrong branch", "attestation timeout",
        ):
            with self.subTest(failure=failure):
                self.state["verification_error"] = failure
                result = self.publish()
                self.assertNotEqual(result.returncode, 0)
                self.assert_no_builds_or_writes()
                self.assertIn(DIGESTS["emulator"], result.stderr)
                self.assertIn("check:", result.stderr)
                self.assertIn("Recovery:", result.stderr)
                self.assertIn("Do not delete, overwrite, sign, or attest", result.stderr)

    def test_registry_failure_after_missing_images_stops_before_builds(self):
        self.state["images"] = dict.fromkeys(NAMES)
        self.state["registry_error"] = "codeql"
        result = self.publish()
        self.assertNotEqual(result.returncode, 0)
        self.assert_no_builds_or_writes()
        self.assertIn("Cannot establish registry state", result.stderr)

    def test_tag_created_during_build_is_not_overwritten_or_certified(self):
        self.state["images"] = dict.fromkeys(NAMES)
        self.state["race"] = "codeql"
        result = self.publish()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Refusing to overwrite", result.stderr)
        self.assertFalse(any(e[:2] in (["docker", "push"], ["cosign", "sign"]) for e in self.events))

    def test_registry_failure_during_recheck_stops_before_any_push(self):
        self.state["images"] = dict.fromkeys(NAMES)
        self.state["registry_error"] = "codeql"
        self.state["registry_error_after"] = 2
        result = self.publish()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Cannot establish registry state", result.stderr)
        self.assertFalse(any(e[:2] in (["docker", "push"], ["cosign", "sign"]) for e in self.events))

    def test_forbidden_content_or_unexpected_fixture_stops_before_any_push(self):
        for failure in ("forbidden", "bad_fixture"):
            with self.subTest(failure=failure):
                self.state["images"] = dict.fromkeys(NAMES)
                self.state["forbidden"] = failure == "forbidden"
                self.state["bad_fixture"] = failure == "bad_fixture"
                result = self.publish()
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(any(e[:2] in (["docker", "push"], ["cosign", "sign"]) for e in self.events))

    def test_pushed_digest_mismatch_is_not_signed(self):
        self.state["images"]["codeql"] = None
        self.state["digest_mismatch"] = True
        result = self.publish()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Pushed digest could not be confirmed", result.stderr)
        self.assertFalse(any(e[:2] == ["cosign", "sign"] for e in self.events))

    def test_non_main_publication_stops_before_external_commands(self):
        self.env["GITHUB_REF"] = "refs/heads/feature"
        result = self.publish()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.events, [])


if __name__ == "__main__":
    unittest.main()
