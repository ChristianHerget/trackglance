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
timeout() { [[ "$1" != --kill-after=* ]] || shift; shift; "$@"; }
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
wifi_reconnections=0
sleep() { tick=$((tick + 1)); SECONDS=$((SECONDS + 1)); }
timeout() { [[ "$1" != --kill-after=* ]] || shift; shift; "$@"; }
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
      if [[ "$SCENARIO" == recovery-* ]]; then
        if (( wifi_reconnections > 0 )) && [[ "$SCENARIO" != recovery-no-route ]]; then
          echo 'default via 10.0.2.2 dev wlan0 table 1016'
        fi
      elif [[ "$SCENARIO" != missing-route && "$SCENARIO" != other-gateway && "$tick" -ge 1 ]]; then
        echo 'default via 10.0.2.2 dev wlan0 table 1016'
      fi ;;
    'logcat -d -s netd:E')
      if [[ "$SCENARIO" == recovery-* ]]; then
        echo '11-17 21:30:24 E netd    : Error adding route 0.0.0.0/0 -> 10.0.2.2 wlan0 to table 1016: Network is unreachable'
      elif [[ "$SCENARIO" == other-gateway ]]; then
        echo 'E netd: Error adding route 0.0.0.0/0 -> 10.0.3.2 wlan0 to table 1016: Network is unreachable'
      fi ;;
    'shell cmd wifi set-wifi-enabled disabled')
      echo wifi-disabled
      [[ "$SCENARIO" != recovery-disable-failed ]] ;;
    'shell cmd wifi set-wifi-enabled enabled')
      echo wifi-enabled
      wifi_reconnections=$((wifi_reconnections + 1)) ;;
    'shell dumpsys connectivity') echo 'last route installation failed' ;;
  esac
}
set_emulator_test_location() { echo configured; }
relayctl() {
  [[ "$SCENARIO" != recovery-no-relay ]] || return 1
  if [[ "$SCENARIO" == delayed-relay && "$tick" -lt 2 ]]; then return 1; fi
  echo '{"session_id":0,"phone_connected":false,"qemu_connected":false}'
}
grep() {
  if [[ "$SCENARIO" == recovery-deadline && "$*" == *'Error adding route'* ]]; then
    SECONDS=$((SECONDS + 10))
  fi
  command grep "$@"
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

    def test_recorded_netd_gateway_failure_reconnects_wifi_then_requires_live_routes(self):
        result = self.run_device("recovery-ready", 'wait_for_relay_network 4; test "$wifi_reconnections" == 1')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines(), ["wifi-disabled", "wifi-enabled"])
        self.assertIn("Reconnecting emulator Wi-Fi once", result.stderr)

    def test_healthy_missing_and_unrelated_gateway_failures_never_reset_wifi(self):
        for scenario in ("delayed-relay", "missing-route", "other-gateway"):
            with self.subTest(scenario=scenario):
                result = self.run_device(scenario, 'wait_for_relay_network 4')
                self.assertNotIn("wifi-", result.stdout)
                self.assertNotIn("Reconnecting emulator Wi-Fi", result.stderr)

    def test_recovery_without_routes_or_relay_still_fails_after_one_reconnection(self):
        for scenario in ("recovery-no-route", "recovery-no-relay"):
            with self.subTest(scenario=scenario):
                result = self.run_device(scenario, 'wait_for_relay_network 4')
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stdout.splitlines(), ["wifi-disabled", "wifi-enabled"])
                self.assertIn("Relay network readiness expired", result.stderr)

    def test_failed_wifi_command_does_not_retry_or_claim_readiness(self):
        result = self.run_device("recovery-disable-failed", 'wait_for_relay_network 4')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout.splitlines(), ["wifi-disabled"])
        self.assertIn("Relay network readiness expired", result.stderr)

    def test_gateway_diagnostics_cannot_start_recovery_after_the_original_deadline(self):
        result = self.run_device("recovery-deadline", 'wait_for_relay_network 4')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("wifi-", result.stdout)
        self.assertNotIn("Reconnecting emulator Wi-Fi", result.stderr)

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


class GoldenLocationTest(unittest.TestCase):
    def test_golden_provisioning_disables_gnss_before_installing_or_launching_apps(self):
        source = PODMAN_TEST.read_text()
        bootstrap = source[source.index("bootstrap() {"):source.index("new_run() {")]
        self.assertIn("wait_for_android 180 disabled", bootstrap)
        self.assertLess(bootstrap.index("wait_for_android 180 disabled"),
                        bootstrap.index("install-multiple"))
        self.assertNotIn("set_emulator_test_location", bootstrap)
