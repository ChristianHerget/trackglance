import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from script_test_support import PODMAN_TEST


class AcceptanceSessionTest(unittest.TestCase):
    def test_session_counters_cannot_be_shell_expressions(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "state.json"
            path.write_text(json.dumps({"started": "counter[$(touch unexpected-file)]"}))
            helper = PODMAN_TEST.parent / "acceptance-session-state"
            result = subprocess.run([str(helper), "read", str(path), "started"], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(result.stdout, "")

    def run_session(self, commands, failure=""):
        with tempfile.TemporaryDirectory() as directory:
            script = r'''
source "$PODMAN_TEST"
trap - EXIT
PROJECT_DIR=$TEMP
BUILD_ROOT=$TEMP/build/podman
CHECK_LOG_DIR=$TEMP/logs
mkdir -p "$CHECK_LOG_DIR"
SESSION=$TEMP/build/acceptance-sessions/test.json
BUILD_IMAGE=runner
EMULATOR_IMAGE=emulator
INPUTS=original
IDS='[{"Id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},{"Id":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}]'
git() { echo commit; }
select_acceptance_engine() { ACCEPTANCE_ENGINE=podman; }
select_warm_acceptance_images() { :; }
session_inputs() { echo "$INPUTS"; }
parse_locus_apk_options() { LOCUS_INPUT_DIR=/private; }
doctor_acceptance() { :; }
assert_no_active_acceptance_runtime() { :; }
require_images() { :; }
require_current_golden() { :; }
golden_marker() { echo fixture; }
clean() { echo provisioning-clean; }
build_acceptance_all() { echo source-images; }
prepare_published_acceptance_images() { echo published-images; }
bootstrap() { echo fresh-bootstrap; }
check_stage() { :; }
check_result() { :; }
acceptance_engine() {
  if [[ "$*" == image* ]]; then echo image-id; else echo "$IDS"; fi
}
start_e2e_platform() {
  ACTIVE_POD=trackglance-${RUN_ID}-$1
  ACTIVE_ANDROID_CONTAINER=$ACTIVE_POD-android
  ACTIVE_DATA_VOLUME=$ACTIVE_POD-android
  ACTIVE_WATCH_VOLUME=$ACTIVE_POD-watch
  ACTIVE_RUNTIME_VOLUME=$ACTIVE_POD-runtime
  echo "start:$1"
}
run_e2e_phase() { echo "phase:$3:$CHECK_PASS"; [[ "$3" != "$FAILURE" ]]; }
finish_e2e_platform() { echo cleanup; ACTIVE_POD=''; return "${3:-0}"; }
eval "$COMMANDS"
'''
            result = subprocess.run(["bash", "-euo", "pipefail", "-c", script],
                                    env={**os.environ, "PODMAN_TEST": str(PODMAN_TEST), "TEMP": directory,
                                         "COMMANDS": commands, "FAILURE": failure},
                                    capture_output=True, text=True, timeout=10)
            path = Path(directory) / "build/acceptance-sessions/test.json"
            state = json.loads(path.read_text()) if path.exists() else None
            return result, state

    def test_two_passes_require_startup_then_behavior_and_cleanup(self):
        result, state = self.run_session('''
acceptance_session prepare --session "$SESSION" --watch-passes 2
acceptance_session startup --session "$SESSION"
acceptance_session behavior --session "$SESSION"
acceptance_session startup --session "$SESSION" --pass 2
acceptance_session behavior --session "$SESSION" --pass 2
acceptance_session cleanup --session "$SESSION"
acceptance_session cleanup --session "$SESSION"
''')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(state["completed"], [1, 2])
        self.assertTrue(state["closed"])
        self.assertEqual(result.stdout.splitlines(), ["start:emery", "phase:startup:1", "phase:behavior:1", "cleanup",
                                                    "start:emery", "phase:startup:2", "phase:behavior:2", "cleanup"])

    def test_behavior_without_startup_fails(self):
        result, _ = self.run_session('''
acceptance_session prepare --session "$SESSION"
acceptance_session behavior --session "$SESSION"
''')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("No completed startup", result.stderr)
        self.assertNotIn("phase:behavior", result.stdout)

    def test_second_pass_requires_previous_behavior(self):
        result, _ = self.run_session('''
acceptance_session prepare --session "$SESSION" --watch-passes 2
acceptance_session startup --session "$SESSION" --pass 2
''')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Previous behavior", result.stderr)

    def test_changed_inputs_block_behavior(self):
        result, _ = self.run_session('''
acceptance_session prepare --session "$SESSION"
acceptance_session startup --session "$SESSION"
INPUTS=changed
acceptance_session behavior --session "$SESSION"
''')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("inputs changed", result.stderr)

    def test_replaced_containers_block_behavior_before_adopting_cleanup_targets(self):
        result, _ = self.run_session('''
acceptance_session prepare --session "$SESSION"
acceptance_session startup --session "$SESSION"
ACTIVE_POD=''
IDS='[{"Id":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}]'
acceptance_session behavior --session "$SESSION"
''')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("containers were replaced", result.stderr)
        self.assertNotIn("phase:behavior", result.stdout)

    def test_failed_startup_cannot_record_completion(self):
        result, state = self.run_session('''
acceptance_session prepare --session "$SESSION"
acceptance_session startup --session "$SESSION"
''', failure="startup")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(state.get("startup_complete", False))
        self.assertEqual(state["completed"], [])

    def test_failed_behavior_cannot_record_completion(self):
        result, state = self.run_session('''
acceptance_session prepare --session "$SESSION"
acceptance_session startup --session "$SESSION"
acceptance_session behavior --session "$SESSION"
''', failure="behavior")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("cleanup", result.stdout)
        self.assertEqual(state["completed"], [])

    def test_metadata_cannot_redirect_cleanup_to_another_volume(self):
        result, _ = self.run_session('''
acceptance_session prepare --session "$SESSION"
acceptance_session startup --session "$SESSION"
ACTIVE_POD=''
"$SCRIPT_DIR/acceptance-session-state" patch "$SESSION" runtime '{"pod":"unrelated"}'
export PYTHONOPTIMIZE=1
acceptance_session cleanup --session "$SESSION"
''')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("cleanup", result.stdout)

    def test_fresh_modes_provision_once_before_phase_handoff(self):
        for mode, expected in (("fresh", "source-images"), ("published", "published-images")):
            result, _ = self.run_session(f'''
acceptance_session prepare --session "$SESSION" --{mode}
acceptance_session startup --session "$SESSION"
acceptance_session behavior --session "$SESSION"
''')
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.count("fresh-bootstrap"), 1)
            self.assertEqual(result.stdout.count(expected), 1)

    def test_successful_startup_retains_runtime_in_exit_trap(self):
        result, _ = self.run_session('''
acceptance_session prepare --session "$SESSION"
acceptance_session startup --session "$SESSION"
cleanup_active() { echo "exit-cleanup:${ACTIVE_POD:-none}"; }
trap finish_checks EXIT
''')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("exit-cleanup:none", result.stdout)

    def test_cancellation_cleans_runtime_even_after_startup_completed(self):
        result, _ = self.run_session('''
acceptance_session prepare --session "$SESSION"
acceptance_session startup --session "$SESSION"
cleanup_active() { echo "exit-cleanup:$ACTIVE_POD"; }
trap finish_checks EXIT
exit 130
''')
        self.assertEqual(result.returncode, 130, result.stderr)
        self.assertIn("exit-cleanup:trackglance-", result.stdout)

    def test_replaced_image_is_rejected(self):
        result, _ = self.run_session('''
acceptance_session prepare --session "$SESSION"
acceptance_session startup --session "$SESSION"
ACTIVE_POD=''
acceptance_engine() { if [[ "$*" == image* ]]; then echo changed-image; else echo "$IDS"; fi; }
acceptance_session behavior --session "$SESSION"
''')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("images were replaced", result.stderr)
