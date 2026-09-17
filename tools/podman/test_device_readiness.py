import os
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path
from script_test_support import (
    ROOT,
    PODMAN_TEST,
    DEVICE_LIB,
    EMULATOR_ENTRYPOINT,
)
EMULATOR_CONSOLE = ROOT / "tools" / "podman" / "emulator-console.py"


class DeviceReadinessTest(unittest.TestCase):
    def run_device(self, body, fail_command="", api=32):
        with tempfile.TemporaryDirectory() as directory:
            token = Path(directory) / "token"
            token.write_text("private test token")
            script = r'''source "$DEVICE_LIB"
                EMULATOR_CONSOLE_TOKEN=$TEST_TOKEN
                exec 3>&1
                adb() {
                  { printf 'adb'; printf ' <%s>' "$@"; printf '\n'; } >&3
                  if [[ "$*" == "-s test:5555 shell getprop ro.build.version.sdk" ]]; then printf "%s\n" "$TEST_API"; fi
                  [[ "$*" != "$FAIL_COMMAND" ]]
                }
                python3() {
                  printf 'python3'; printf ' <%s>' "$@"; printf '\n'
                }
            ''' + body
            return subprocess.run(
                ["bash", "-euo", "pipefail", "-c", script],
                env={**os.environ, "DEVICE_LIB": str(DEVICE_LIB), "TEST_TOKEN": str(token),
                     "FAIL_COMMAND": fail_command, "ADB_SERIAL": "test:5555", "TEST_API": str(api)},
                capture_output=True, text=True, check=False,
            )

    def test_tap_text_targets_the_visible_part_of_a_clipped_control(self):
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            fixture = temporary / "window.xml"
            adb_log = temporary / "adb.log"
            fixture.write_text(
                textwrap.dedent(
                    """\
                    <hierarchy>
                      <node bounds="[0,0][1080,2400]">
                        <node class="android.widget.ScrollView" scrollable="true"
                              bounds="[53,116][1027,2305]">
                          <node bounds="[53,116][1027,1525]">
                            <node clickable="true" bounds="[423,2261][658,2387]">
                              <node text="Finished" bounds="[465,2297][605,2305]" />
                            </node>
                          </node>
                        </node>
                      </node>
                    </hierarchy>
                    """
                ),
                encoding="utf-8",
            )
            environment = {
                **os.environ,
                "ADB_LOG": str(adb_log),
                "DEVICE_LIB": str(DEVICE_LIB),
                "UI_FIXTURE": str(fixture),
            }
            result = subprocess.run(
                [
                    "bash",
                    "-euo",
                    "pipefail",
                    "-c",
                    textwrap.dedent(
                        """\
                        source "$DEVICE_LIB"
                        dump_ui() { cp "$UI_FIXTURE" /tmp/trackglance-window.xml; }
                        adb_device() { printf '%s\\n' "$*" > "$ADB_LOG"; }
                        tap_text Finished 2
                        """
                    ),
                ],
                env=environment,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(adb_log.read_text().strip(), "shell input tap 540 2283")

    def test_tap_text_initializes_its_timeout_before_deadline_expansion(self):
        environment = {**os.environ, "DEVICE_LIB": str(DEVICE_LIB)}
        script = textwrap.dedent(
            """\
            source "$DEVICE_LIB"
            dump_ui() { :; }
            python3() { printf '10 20\n'; }
            adb_device() { test "$*" = "shell input tap 10 20"; }
            tap_text Start 1
            """
        )
        result = subprocess.run(
            ["bash", "-euo", "pipefail", "-c", script],
            env=environment,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_wait_for_android_retries_a_failed_initial_connect(self):
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            state = temporary / "adb-state"
            (temporary / "timeout").write_text("#!/bin/sh\nshift\nexec \"$@\"\n", encoding="utf-8")
            (temporary / "sleep").write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            (temporary / "adb").write_text(
                textwrap.dedent(
                    """\
                    #!/bin/sh
                    if [ "$1" = connect ]; then
                      count=0
                      [ ! -f "$ADB_STATE.count" ] || count=$(cat "$ADB_STATE.count")
                      count=$((count + 1))
                      printf '%s\n' "$count" > "$ADB_STATE.count"
                      if [ "$count" -eq 1 ]; then
                        echo 'failed to connect' >&2
                        exit 0
                      fi
                      : > "$ADB_STATE"
                      exit 0
                    fi
                    if [ "$1" = -s ] && [ "$3" = shell ] && [ "$4" = getprop ]; then
                      [ -f "$ADB_STATE" ] || exit 1
                      printf '1\r\n'
                      exit 0
                    fi
                    if [ "$1" = -s ] && [ "$3" = shell ] && [ "$4" = settings ]; then
                      exit 0
                    fi
                    if [ "$1" = -s ] && [ "$3" = shell ] && { [ "$4" = appops ] || [ "$4" = cmd ]; }; then
                      exit 0
                    fi
                    exit 1
                    """
                ),
                encoding="utf-8",
            )
            for executable in ("timeout", "sleep", "adb"):
                (temporary / executable).chmod(0o755)
            environment = {
                **os.environ,
                "ADB_STATE": str(state),
                "PATH": f"{temporary}:{os.environ['PATH']}",
                "DEVICE_LIB": str(DEVICE_LIB),
            }
            result = subprocess.run(
                [
                    "bash", "-euo", "pipefail", "-c",
                    'source "$DEVICE_LIB"; set_emulator_test_location() { :; }; wait_for_android 2',
                ],
                env=environment,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((temporary / "adb-state.count").read_text().strip(), "2")

    def test_wait_for_android_sets_wartburg_through_the_emulator_console(self):
        result = self.run_device(r'''timeout() {
            if [[ "$*" == *sys.boot_completed* ]]; then printf '1
'; fi
        }
        wait_for_android 2
        ''')
        self.assertEqual(result.returncode, 0, result.stderr)
        commands = result.stdout.splitlines()
        self.assertEqual(commands[:5], [
            "adb <-s> <test:5555> <shell> <settings> <put> <global> <window_animation_scale> <0>",
            "adb <-s> <test:5555> <shell> <settings> <put> <global> <transition_animation_scale> <0>",
            "adb <-s> <test:5555> <shell> <settings> <put> <global> <animator_duration_scale> <0>",
            "adb <-s> <test:5555> <shell> <cmd> <location> <set-location-enabled> <true>",
            "adb <-s> <test:5555> <shell> <cmd> <location> <providers> <remove-test-provider> <gps>",
        ])
        self.assertRegex(commands[5], r"^python3 </workspace/tools/podman/emulator-console.py> <--token-file> <.+/token> <--latitude> <50.9662> <--longitude> <10.3065>$")

        ignored = self.run_device("set_emulator_test_location",
            "-s test:5555 shell cmd location providers remove-test-provider gps")
        self.assertEqual(ignored.returncode, 0, ignored.stderr)
        self.assertIn("python3", ignored.stdout)
        failed = self.run_device("set_emulator_test_location",
            "-s test:5555 shell cmd location set-location-enabled true")
        self.assertNotEqual(failed.returncode, 0, failed.stdout)
        self.assertNotIn("python3", failed.stdout)

    def test_emulator_console_token_stays_in_the_private_runtime_volume(self):
        entrypoint = EMULATOR_ENTRYPOINT.read_text(encoding="utf-8")
        helper = EMULATOR_CONSOLE.read_text(encoding="utf-8")
        podman_test = PODMAN_TEST.read_text(encoding="utf-8")
        android = podman_test.split("run_android_tests() {", 1)[1].split(
            "\nrun_e2e_platform() {", 1
        )[0]
        self.assertIn("/run/trackglance/emulator-console-auth-token", entrypoint)
        self.assertIn('socket.create_connection(("127.0.0.1", 5556)', helper)
        self.assertIn("geo fix", helper)
        self.assertIn(
            'install -m 600 "$EMULATOR_CONSOLE_TOKEN" /root/.emulator_console_auth_token',
            android,
        )
        self.assertLess(
            android.index("/root/.emulator_console_auth_token"),
            android.index("connectedDebugAndroidTest"),
        )
        self.assertIn('rm -rf "$results"', android)
        self.assertIn("gradle_status=$?", android)
        self.assertLess(android.index('rm -rf "$results"'), android.index("connectedDebugAndroidTest"))
        self.assertLess(
            android.index("connectedDebugAndroidTest"),
            android.index("assert-instrumentation-results.py"),
        )

    def test_locus_acceptance_permissions_include_the_device_idle_allowlist(self):
        result = self.run_device("grant_locus_test_permissions")
        self.assertEqual(result.returncode, 0, result.stderr)
        expected = ["ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION", "ACCESS_BACKGROUND_LOCATION",
                    "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE"]
        calls = [
            f"adb <-s> <test:5555> <shell> <pm> <grant> <menion.android.locus> <android.permission.{permission}>"
            for permission in expected
        ]
        calls.insert(3, "adb <-s> <test:5555> <shell> <getprop> <ro.build.version.sdk>")
        calls.append("adb <-s> <test:5555> <shell> <dumpsys> <deviceidle> <whitelist> <+menion.android.locus>")
        self.assertEqual(result.stdout.splitlines(), calls)
        # Every grant and the allowlist command must propagate failure.
        failed = self.run_device("grant_locus_test_permissions", "-s test:5555 shell dumpsys deviceidle whitelist +menion.android.locus")
        self.assertNotEqual(failed.returncode, 0, failed.stdout)
        for permission in expected:
            with self.subTest(permission=permission):
                failed = self.run_device("grant_locus_test_permissions",
                    f"-s test:5555 shell pm grant menion.android.locus android.permission.{permission}")
                self.assertNotEqual(failed.returncode, 0, failed.stdout)

    def test_api34_grants_notifications_without_obsolete_storage_permissions(self):
        result = self.run_device("grant_locus_test_permissions; grant_bridge_test_notifications", api=34)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("EXTERNAL_STORAGE", result.stdout)
        for package in ("menion.android.locus", "app.trackglance.bridge"):
            self.assertIn(f"<grant> <{package}> <android.permission.POST_NOTIFICATIONS>", result.stdout)

    def test_coreapp_onboarding_grants_location_and_its_notification_listener(self):
        result = self.run_device("grant_coreapp_test_permissions")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines(), [
            "adb <-s> <test:5555> <shell> <pm> <grant> <coredevices.coreapp> <android.permission.ACCESS_COARSE_LOCATION>",
            "adb <-s> <test:5555> <shell> <pm> <grant> <coredevices.coreapp> <android.permission.ACCESS_FINE_LOCATION>",
            "adb <-s> <test:5555> <shell> <pm> <grant> <coredevices.coreapp> <android.permission.ACCESS_BACKGROUND_LOCATION>",
            "adb <-s> <test:5555> <shell> <cmd> <notification> <allow_listener> <coredevices.coreapp/io.rebble.libpebblecommon.notification.LibPebbleNotificationListener>",
        ])
