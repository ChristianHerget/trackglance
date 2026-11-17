import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from script_test_support import DEVICE_LIB, ROOT


STAGE = ROOT / "tools/podman/e2e-stage.sh"


def function_source(name):
    source = STAGE.read_text()
    return name + "() {" + source.split(name + "() {", 1)[1].split("\n}\n", 1)[0] + "\n}\n"


class WatchRecordingTest(unittest.TestCase):
    def run_stop(self, scenario="ready", platform="gabbro", branch=False):
        with tempfile.TemporaryDirectory() as directory:
            trace = Path(directory) / "trace"
            ticks = Path(directory) / "ticks"
            ticks.write_text("0")
            script = r'''
source "$DEVICE_LIB"
recording=RECORDING
command=PAUSE_RESUME
result=OK
presses=0
restored=false
trace() { printf '%s\n' "$*" >> "$TRACE"; }
sleep() { trace "sleep:$1"; SECONDS=$((SECONDS + 1)); }
behavior_checkpoint() { trace "checkpoint:$1"; }
adb_device() {
  trace "adb:$*"
  if [[ "$*" == 'shell input keyevent KEYCODE_WAKEUP' ]]; then closed=0; fi
}
adb_device_timeout() {
  shift
  case "$*" in
    'shell pidof menion.android.locus')
      if [[ "$SCENARIO" == missing-process ]]; then return 1; fi
      if [[ "$SCENARIO" == restarted && -f "$TICKS.ready" ]]; then echo 555; else echo 444; fi ;;
    'shell dumpsys activity activities')
      tick=$(cat "$TICKS")
      echo $((tick + 1)) > "$TICKS"
      if [[ "$SCENARIO" == blocked || "$tick" -lt 2 ]]; then
        echo 'topResumedActivity= app.trackglance.bridge/MainActivity'
      else
        trace foreground-ready
        touch "$TICKS.ready"
        echo 'topResumedActivity= menion.android.locus/MainActivityMap'
      fi ;;
  esac
}
status_value() {
  case "$1" in
    recording_state) echo "$recording" ;;
    last_command) echo "$command" ;;
    last_command_result) echo "$result" ;;
    watch_heart_rate|locus_heart_rate) [[ "$PEBBLE_PLATFORM" != emery ]] || echo 123 ;;
    supervision_sources) [[ "$PEBBLE_PLATFORM" == emery ]] && echo 'HEART_RATE|STEPS' || echo STEPS ;;
    supervision_phase) if (( closed )); then echo CLOSED; else echo ACTIVE; fi ;;
    watch_app_open) if (( closed )); then echo false; else echo true; fi ;;
  esac
}
wait_status_real=$(declare -f wait_status)
eval "${wait_status_real/wait_status ()/original_wait_status ()}"
wait_status() {
  trace "wait:$1:$2"
  if [[ "$1" == supervision_phase && "$2" == ACTIVE ]]; then closed=0; fi
  original_wait_status "$@"
}
watch_button() {
  trace "button:$1"
  if [[ "$1" == back ]]; then closed=1; fi
  if [[ "$restored" != true ]]; then
    recording=RECORDING
    [[ "$command" != ADD_WAYPOINT ]] || result=OK
    return
  fi
  presses=$((presses + 1))
  if (( presses == 4 )); then
    command=STOP_SAVE
    [[ "$SCENARIO" != missing-command ]] || command=PAUSE_RESUME
    [[ "$SCENARIO" != failed-command ]] || result=FAILED
    [[ "$SCENARIO" == still-recording || "$result" == FAILED ]] || recording=STOPPED
  fi
}
closed=0
'''
            script += function_source("stop_and_save_recording")
            # Record dispatch separately from readiness; the production helper must wait first.
            script += r'''
behavior_checkpoint() {
  trace "checkpoint:$1"
  if [[ "$1" == stop-save-command ]]; then restored=true; fi
}
'''
            if branch:
                script += function_source("screen_off_supervision_recovery")
                script += r'''
bridge_activity=bridge/MainActivity
run_step_acceptance() { recording=PAUSED; }
tap_text() { :; }
watch_screenshot() { trace "screenshot:$1"; }
relayctl() { :; }
grep() { [[ "$*" == *'Send watch heart rate'* ]] && return 1; command grep "$@"; }
wait_nonempty_status() { echo Cycling; }
'''
                body = STAGE.read_text().rsplit('if [[ "$PEBBLE_PLATFORM" == "emery" ]]; then', 1)[1]
                body = body.split("\n# Reject a system restart", 1)[0]
                # The Emery waypoint assertions need an independently successful prior command.
                script += r'''
wait_status() {
  trace "wait:$1:$2"
  if [[ "$1" == supervision_phase && "$2" == ACTIVE ]]; then closed=0; fi
  if [[ "$1" == last_command && "$2" == ADD_WAYPOINT ]]; then command=ADD_WAYPOINT; fi
  original_wait_status "$@"
}
'''
                script += 'if [[ "$PEBBLE_PLATFORM" == "emery" ]]; then' + body
            else:
                script += "stop_and_save_recording\n"
            completed = subprocess.run(
                ["bash", "-euo", "pipefail", "-c", script],
                env={**os.environ, "DEVICE_LIB": str(DEVICE_LIB), "TRACE": str(trace),
                     "TICKS": str(ticks), "SCENARIO": scenario, "PEBBLE_PLATFORM": platform},
                capture_output=True, text=True, timeout=5,
            )
            return completed, trace.read_text().splitlines()

    def test_both_platforms_complete_screen_off_supervision_before_foreground_stop(self):
        for platform in ("emery", "gabbro"):
            with self.subTest(platform=platform):
                completed, trace = self.run_stop(platform=platform, branch=True)
                self.assertEqual(completed.returncode, 0, completed.stderr)
                sleep = trace.index("sleep:40")
                foreground = trace.index("checkpoint:stop-save-foreground")
                self.assertLess(sleep, foreground)
                self.assertIn("adb:shell input keyevent KEYCODE_WAKEUP", trace[sleep:foreground])
                self.assertIn("wait:supervision_phase:ACTIVE", trace[sleep:foreground])
                self.assertIn("checkpoint:stop-save-complete", trace)

    def test_delayed_foreground_readiness_precedes_watch_command_and_all_assertions(self):
        completed, trace = self.run_stop()
        self.assertEqual(completed.returncode, 0, completed.stderr)
        dispatch = trace.index("checkpoint:stop-save-command")
        self.assertIn("foreground-ready", trace[:dispatch])
        self.assertIn("wait:recording_state:RECORDING", trace[:dispatch])
        self.assertEqual([v for v in trace[dispatch:] if v.startswith("button:")],
                         ["button:select", "button:down", "button:select", "button:select"])
        for assertion in ("wait:last_command:STOP_SAVE", "wait:last_command_result:OK",
                          "wait:recording_state:STOPPED"):
            self.assertIn(assertion, trace[dispatch:])

    def test_failed_foreground_or_restarted_locus_never_sends_stop(self):
        for scenario in ("blocked", "missing-process", "restarted"):
            with self.subTest(scenario=scenario):
                completed, trace = self.run_stop(scenario)
                self.assertNotEqual(completed.returncode, 0)
                self.assertNotIn("checkpoint:stop-save-command", trace)
                self.assertFalse(any(v.startswith("button:") for v in trace))

    def test_failed_missing_or_unapplied_command_is_never_accepted_or_retried(self):
        for scenario in ("failed-command", "missing-command", "still-recording"):
            with self.subTest(scenario=scenario):
                completed, trace = self.run_stop(scenario)
                self.assertNotEqual(completed.returncode, 0)
                self.assertNotIn("checkpoint:stop-save-complete", trace)
                self.assertEqual(sum(v == "button:select" for v in trace), 3)


class WatchFailureDiagnosticsTest(unittest.TestCase):
    def run_diagnostics(self, logcat, status="Row: 0 last_command=STOP_SAVE, last_command_result=FAILED"):
        with tempfile.TemporaryDirectory() as directory:
            status_file = Path(directory) / "status"
            logcat_file = Path(directory) / "logcat"
            status_file.write_text(status)
            logcat_file.write_text(logcat)
            return subprocess.run(
                ["bash", "-euo", "pipefail", "-c", function_source("print_failure_diagnostics") +
                 '\nprint_failure_diagnostics "$1" "$2"', "test", str(status_file), str(logcat_file)],
                env={**os.environ, "PEBBLE_PLATFORM": "gabbro"},
                capture_output=True, text=True, timeout=5,
            )

    def test_failure_summary_identifies_locus_crash_and_omits_other_app_stacks(self):
        result = self.run_diagnostics("""11-17 15:22:37 E AndroidRuntime: FATAL EXCEPTION: main
11-17 15:22:37 E AndroidRuntime: Process: menion.android.locus, PID: 6196
11-17 15:22:37 E AndroidRuntime: java.lang.IllegalStateException: StartScreen
11-17 15:22:37 E AndroidRuntime:     at locus.Frame
11-17 15:22:38 E AndroidRuntime: FATAL EXCEPTION: main
11-17 15:22:38 E AndroidRuntime: Process: other.app, PID: 123
11-17 15:22:38 E AndroidRuntime: unrelated private details
""")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("last_command_result=FAILED", result.stderr)
        self.assertIn("IllegalStateException: StartScreen", result.stderr)
        self.assertNotIn("unrelated private details", result.stderr)

    def test_diagnostics_remain_bounded_for_large_status_and_stack(self):
        result = self.run_diagnostics(
            "x E AndroidRuntime: FATAL EXCEPTION: main\n"
            "x E AndroidRuntime: Process: menion.android.locus, PID: 6196\n" +
            ("x E AndroidRuntime: " + "x" * 1000 + "\n") * 1000,
            "s" * 20000,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertLess(len(result.stderr), 20000)

    def test_unavailable_status_and_absent_locus_crash_are_explicit(self):
        result = self.run_diagnostics("", status="")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Bridge status was unavailable", result.stderr)
        self.assertIn("No Locus Java crash found", result.stderr)

    def test_diagnostic_collection_preserves_the_original_exit_status(self):
        for status, collection_failure, expected in ((23, False, 23), (23, True, 23), (0, True, 74)):
            with self.subTest(status=status, collection_failure=collection_failure):
                with tempfile.TemporaryDirectory() as directory:
                    script = function_source("print_failure_diagnostics")
                    script += function_source("capture_artifacts").replace('/artifacts/', '${ARTIFACTS}/')
                    script += r'''
watch_screenshot() { :; }
android_screenshot() { :; }
dump_ui() { :; }
cp() { :; }
adb_device_timeout() {
  if [[ "$2" == logcat ]]; then
    [[ "$FAIL_COLLECTION" != true ]]
  else
    echo 'Row: 0 last_command=STOP_SAVE, last_command_result=FAILED'
  fi
}
trap capture_artifacts EXIT
exit "$STATUS"
'''
                    result = subprocess.run(
                        ["bash", "-euo", "pipefail", "-c", script],
                        env={**os.environ, "ARTIFACTS": directory, "PEBBLE_PLATFORM": "gabbro",
                             "STATUS": str(status), "STATUS_URI": "content://test/status",
                             "FAIL_COLLECTION": str(collection_failure).lower()},
                        capture_output=True, text=True, timeout=5,
                    )
                    self.assertEqual(result.returncode, expected, result.stderr)
                    self.assertIn("last_command_result=FAILED", result.stderr)
