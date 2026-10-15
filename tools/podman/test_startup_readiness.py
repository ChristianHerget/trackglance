import os
import subprocess
import unittest
from script_test_support import ROOT, PODMAN_TEST


class StartupReadinessTest(unittest.TestCase):
    def test_runner_adb_server_is_separate_from_emulator_server(self):
        script = r'''
unset ANDROID_ADB_SERVER_PORT ADB_SERVER_SOCKET
source "$DEVICE_LIB"
adb() { test "$ANDROID_ADB_SERVER_PORT" == 5037 && test "$ADB_SERVER_SOCKET" == tcp:localhost:5037; }
timeout() { shift; "$@"; }
adb_device version
adb_device_timeout 5 version
'''
        result = subprocess.run(["bash", "-euo", "pipefail", "-c", script],
                                env={**os.environ, "DEVICE_LIB": str(ROOT / "tools/podman/device-lib.sh")},
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_explicit_runner_adb_port_is_preserved_for_client_and_ddmlib(self):
        result = self.run_device("unused", '''
test "$ANDROID_ADB_SERVER_PORT" == 5037
ANDROID_ADB_SERVER_PORT=5040 ADB_SERVER_SOCKET=tcp:localhost:5040
source "$DEVICE_LIB"
test "$ANDROID_ADB_SERVER_PORT" == 5040
test "$ADB_SERVER_SOCKET" == tcp:localhost:5040
''')
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_emulator_server_port_is_isolated_for_both_engines(self):
        script = r'''
source "$PODMAN_TEST"
trap - EXIT
ACCEPTANCE_ENGINE=$ENGINE
ACTIVE_POD=trackglance-test
acceptance_security_args() { :; }
register_active_container() { :; }
acceptance_engine() {
  [[ "$*" == *'--env ANDROID_ADB_SERVER_PORT=5038'* ]]
  [[ "$*" == *'--env ADB_SERVER_SOCKET=tcp:localhost:5038'* ]]
}
start_android trackglance-test-android data runtime
'''
        for engine in ("docker", "podman"):
            result = subprocess.run(["bash", "-euo", "pipefail", "-c", script],
                                    env={**os.environ, "PODMAN_TEST": str(PODMAN_TEST), "ENGINE": engine},
                                    capture_output=True, text=True, timeout=5)
            self.assertEqual(result.returncode, 0, result.stderr)

    def run_device(self, scenario, command):
        script = r'''
source "$DEVICE_LIB"
tick=0
sleep() { tick=$((tick + 1)); SECONDS=$((SECONDS + 1)); }
timeout() { shift; "$@"; }
adb() { :; }
adb_device() { :; }
adb_device_timeout() {
  shift
  case "$*" in
    get-state) if (( tick >= 1 )); then echo device; else echo offline; fi ;;
    'shell getprop sys.boot_completed') echo 1 ;;
    *'test -d /sdcard'*) [[ "$SCENARIO" != missing-storage && "$tick" -ge 2 ]] ;;
    'shell ip -4 route show table all')
      echo '10.0.2.0/24 dev wlan0 proto kernel scope link src 10.0.2.16 table 1016'
      if [[ "$SCENARIO" != missing-route && "$tick" -ge 1 ]]; then
        echo 'default via 10.0.2.2 dev wlan0 table 1016'
      fi ;;
    'shell dumpsys connectivity') echo 'last route installation failed' ;;
  esac
}
set_emulator_test_location() { echo configured; }
relayctl() {
  if [[ "$SCENARIO" == delayed-relay && "$tick" -lt 2 ]]; then return 1; fi
  echo '{"session_id":0,"phone_connected":false,"qemu_connected":false}'
}
eval "$COMMAND"
'''
        return subprocess.run(["bash", "-euo", "pipefail", "-c", script],
                              env={**os.environ, "DEVICE_LIB": str(ROOT / "tools/podman/device-lib.sh"),
                                   "SCENARIO": scenario, "COMMAND": command},
                              capture_output=True, text=True, timeout=5)

    def test_boot_completed_waits_for_writable_storage(self):
        result = self.run_device("delayed-storage", 'wait_for_android 4; test "$tick" == 2')
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_new_runner_registers_transport_and_checks_actual_state(self):
        result = self.run_device("unused", 'connect_to_android 3; test "$tick" == 1')
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_missing_storage_fails_without_initializing_location(self):
        result = self.run_device("missing-storage", 'wait_for_android 3')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("boot/storage readiness expired", result.stderr)
        self.assertNotIn("configured", result.stdout)

    def test_network_waits_for_route_and_relay_control(self):
        result = self.run_device("delayed-relay", 'wait_for_relay_network 4; test "$tick" == 2')
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_missing_route_has_bounded_failure_and_last_diagnostics(self):
        result = self.run_device("missing-route", 'wait_for_relay_network 3')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Relay network readiness expired", result.stderr)
        self.assertIn("10.0.2.16", result.stderr)
        self.assertIn("last route installation failed", result.stderr)

    def test_partial_and_malformed_ui_dumps_cannot_supply_readiness(self):
        for scenario in ("partial", "malformed"):
            script = r'''
source "$DEVICE_LIB"
adb_device_timeout() {
  if [[ "$2" == pull ]]; then
    if [[ "$SCENARIO" == partial ]]; then
      echo '<hierarchy><node resource-id="generalOpen"/></hierarchy>' > /tmp/trackglance-window.xml
      return 1
    fi
    echo '<hierarchy><node resource-id="generalOpen"' > /tmp/trackglance-window.xml
  fi
}
if dump_ui; then exit 42; fi
test ! -e /tmp/trackglance-window.xml
'''
            result = subprocess.run(["bash", "-euo", "pipefail", "-c", script],
                                    env={**os.environ, "DEVICE_LIB": str(ROOT / "tools/podman/device-lib.sh"),
                                         "SCENARIO": scenario}, capture_output=True, text=True, timeout=5)
            self.assertEqual(result.returncode, 0, result.stderr)
