import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from script_test_support import ROOT


class SettingsNavigationTest(unittest.TestCase):
    def navigate(self, scenario):
        with tempfile.TemporaryDirectory() as directory:
            script = r'''
source "$1"
tick=0
page=bridge
opens=0
declare -A missed=()
sleep() { tick=$((tick + 1)); SECONDS=$((SECONDS + 1)); }
relayctl() {
  if [[ "$SCENARIO" == connection && "$tick" -lt 2 ]]; then
    echo '{"phone_connected":true,"qemu_connected":false,"session_id":1}'
  else
    echo '{"phone_connected":true,"qemu_connected":true,"session_id":2}'
  fi
}
status_value() { if [[ "$SCENARIO" == connection && "$tick" -lt 3 ]]; then echo false; else echo true; fi; }
adb_device_timeout() {
  shift
  if [[ "$*" == *'dumpsys activity'* ]]; then
    if [[ "$page" == bridge || ( "$SCENARIO" == drift && "$tick" == 2 ) ]]; then
      echo 'topResumedActivity=ActivityRecord{ app.trackglance.bridge/MainActivity}'
    else
      echo 'topResumedActivity=ActivityRecord{ coredevices.coreapp/MainActivity}'
    fi
  elif [[ "$*" == *'am start'* ]]; then
    opens=$((opens + 1)); page=apps
    if [[ "$SCENARIO" == onboarding && "$opens" == 1 ]]; then page=intro; fi
    echo "launch:$tick" >> "$CALLS"
  elif [[ "$*" == *'content query'* ]]; then
    echo 'Row: 0 watch_connected=true, recording_state=STOPPED'
  fi
}
dump_ui() {
  if [[ "$SCENARIO" == failure ]]; then
    echo '<hierarchy><node text="Unrelated control"/></hierarchy>' > /tmp/trackglance-window.xml
    return
  fi
  case "$page" in
    intro) echo '<node text="Get Started"/>' ;;
    choice) echo '<hierarchy><node text="I have a:"/><node text="Watch"/></hierarchy>' ;;
    finish) echo '<node text="Finished"/>' ;;
    apps) echo '<hierarchy><node text="Watch"/><node text="Settings"/><node text="Apps"/></hierarchy>' ;;
    list) echo '<hierarchy><node text="Settings"/><node text="TrackGlance"/></hierarchy>' ;;
    details) echo '<node text="Settings"/>' ;;
    settings) echo '<node resource-id="generalOpen"/>' ;;
  esac > /tmp/trackglance-window.xml
}
tap_text() {
  echo "tap:$1" >> "$CALLS"
  if [[ "$SCENARIO" == missing && "${missed[$1]:-0}" == 0 ]]; then
    missed[$1]=1; return 1
  fi
  case "$1" in
    'Get Started') page=choice ;;
    Watch) page=finish ;;
    Finished) page=apps ;;
    Apps) page=list ;;
    TrackGlance) page=details ;;
    Settings) page=settings ;;
  esac
}
open_trackglance_settings 10
if [[ "$SCENARIO" == reopen ]]; then
  page=bridge
  open_trackglance_settings 10
fi
'''
            trace = Path(directory) / 'trace'
            calls = Path(directory) / 'calls'
            result = subprocess.run(['bash', '-euo', 'pipefail', '-c', script, 'test',
                                     str(ROOT / 'tools/podman/settings-navigation.sh')],
                                    env={**os.environ, 'SETTINGS_TRACE': str(trace), 'CALLS': str(calls),
                                         'SCENARIO': scenario, 'STATUS_URI': 'content://test'},
                                    capture_output=True, text=True, timeout=10)
            return result, trace.read_text(), calls.read_text() if calls.exists() else ''

    def test_waits_for_relay_and_bridge_health_before_navigation(self):
        result, trace, calls = self.navigate('connection')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('qemu_connected":false', trace)
        self.assertIn('bridge=false', trace)
        self.assertIn('launch:3', calls)
        self.assertIn('settings-webview-passed', trace)

    def test_foreground_drift_relaunches_apps(self):
        result, trace, calls = self.navigate('drift')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls.count('launch:'), 2)
        self.assertIn('settings-webview-passed', trace)

    def test_missing_controls_do_not_abort_recovery(self):
        result, trace, _ = self.navigate('missing')
        self.assertEqual(result.returncode, 0, result.stderr)
        for control in ('Apps', 'TrackGlance', 'Settings'):
            self.assertIn('missing ' + control, trace)
        self.assertIn('settings-webview-passed', trace)

    def test_main_screen_watch_and_settings_controls_do_not_bypass_app_navigation(self):
        result, _, calls = self.navigate('normal')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([line for line in calls.splitlines() if line.startswith('tap:')],
                         ['tap:Apps', 'tap:TrackGlance', 'tap:Settings'])

    def test_onboarding_watch_choice_precedes_apps_navigation(self):
        result, trace, calls = self.navigate('onboarding')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([line for line in calls.splitlines() if line.startswith('tap:')],
                         ['tap:Get Started', 'tap:Watch', 'tap:Finished', 'tap:Apps',
                          'tap:TrackGlance', 'tap:Settings'])
        self.assertEqual(calls.count('launch:'), 2)
        self.assertIn('settings-webview-passed', trace)

    def test_settings_reopen_uses_same_marker_and_recovery(self):
        result, trace, calls = self.navigate('reopen')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls.count('tap:Settings'), 2)
        self.assertEqual(trace.count('settings-webview-passed'), 2)

    def test_expiry_includes_foreground_relay_bridge_ui_and_checkpoint_trace(self):
        result, trace, _ = self.navigate('failure')
        self.assertNotEqual(result.returncode, 0)
        for evidence in ('Foreground activity:', 'Relay status:', 'Bridge watch_connected:',
                         'recording_state=STOPPED', 'Unrelated control', 'apps-deep-link', 'expired'):
            self.assertIn(evidence, result.stderr)
        self.assertNotIn('settings-webview-passed', trace)
