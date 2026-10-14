import importlib.util
import os
import signal
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / "tools/podman/check-report.sh"


class ReportingTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "tools").symlink_to(ROOT / "tools", target_is_directory=True)

    def command(self, body):
        return ["bash", str(HELPER), str(self.root), "bash", "-euo", "pipefail", "-c",
                'source "$1"; trap \'check_result "$?"\' EXIT; check_stage work; ' + body,
                "fixture", str(HELPER)]

    def run_check(self, body, verbose=False, **environment):
        return subprocess.run(self.command(body), env={**os.environ,
                              "CHECK_VERBOSE": str(verbose).lower(), **environment},
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              timeout=15)

    def directory(self):
        return Path((self.root / "build/check-logs/latest").read_text().strip())

    def test_modes_preserve_order_logs_and_status(self):
        body = "printf 'first\\n'; check_stage second; printf 'second\\n'; exit 37"
        quiet = self.run_check(body)
        first = (self.directory() / "combined.log").read_bytes()
        verbose = self.run_check(body, verbose=True)
        self.assertEqual((quiet.returncode, verbose.returncode), (37, 37))
        self.assertEqual(first, (self.directory() / "combined.log").read_bytes())
        self.assertIn(b"[pass] work", quiet.stdout)
        self.assertIn(b"[FAIL 37] second", quiet.stdout)
        self.assertLess(first.index(b"first"), first.index(b"second"))

    def test_errexit_inside_functions_is_preserved(self):
        result = self.run_check("work() { false; echo should-not-run; }; work")
        self.assertEqual(result.returncode, 1)
        self.assertNotIn(b"should-not-run", (self.directory() / "combined.log").read_bytes())

    def test_nested_reporting_restores_outer_directory_for_failure_replay(self):
        result = self.run_check('''root=$(dirname "$(dirname "$(dirname "$CHECK_LOG_DIR")")")
bash "$1" "$root" bash -c 'echo nested-fixture' >/dev/null
echo outer-failure
exit 42''', verbose=True)
        self.assertEqual(result.returncode, 42, result.stdout)
        outer = Path(result.stdout.splitlines()[0].decode().removeprefix("Logs: "))
        self.assertEqual(self.directory(), outer)
        replay = subprocess.run([str(ROOT / "tools/check-diagnostics"), str(self.directory())],
                                capture_output=True, check=True)
        self.assertIn(b"outer-failure", replay.stdout)
        self.assertNotIn(b"nested-fixture", replay.stdout)

    def test_unstarted_work_is_in_console_and_manifest(self):
        result = self.run_check("check_plan later; exit 2")
        self.assertIn(b"[unstarted] later", result.stdout)
        self.assertIn("later\tunstarted", (self.directory() / "manifest.tsv").read_text())

    def test_small_failure_is_not_repeated_by_ci(self):
        self.run_check("echo specific-failure; exit 1")
        replay = subprocess.run([str(ROOT / "tools/check-diagnostics"), str(self.directory())],
                                capture_output=True, check=True)
        self.assertEqual(replay.stdout, b"")

    def test_report_then_platform_log_share_the_failure_budget(self):
        result = self.run_check('''mkdir -p "$CHECK_LOG_DIR/reports" "$CHECK_LOG_DIR/diagnostics"
printf 'test report detail\\n' > "$CHECK_LOG_DIR/reports/test.xml"
printf 'platform detail\\n' > "$CHECK_LOG_DIR/diagnostics/android.log"
check_diagnostic_candidates
echo failed-stage-detail
exit 3''')
        excerpt = result.stdout[result.stdout.index(b"--- "):]
        self.assertLessEqual(len(excerpt), 4096)
        self.assertLessEqual(len(excerpt.splitlines()), 60)
        self.assertLess(excerpt.index(b"failed-stage-detail"), excerpt.index(b"test report detail"))
        self.assertLess(excerpt.index(b"test report detail"), excerpt.index(b"platform detail"))

    def test_verbose_options_preserve_entry_point_order_and_dev_arguments(self):
        fixture = '''source "$1"; shift
trap - EXIT
export CHECK_REPORT_ACTIVE=1
check_stage() { :; }
check_plan() { :; }
static_tests() { echo static; }
documentation_tests() { echo documentation; }
release_check() { echo release; }
run_android_tests() { echo android; }
e2e_tests() { echo watch; }
acceptance_suite() { printf 'suite %s\\n' "$*"; }
development_command() { printf 'dev %s\\n' "$*"; }
parse_locus_apk_options() { LOCUS_INPUT_DIR=/fixture; }
main "$@"
'''
        for command in ("static", "documentation", "release-check", "android",
                        "acceptance", "e2e", "acceptance-suite", "all"):
            args = ["bash", "-euo", "pipefail", "-c", fixture, "fixture",
                    str(ROOT / "tools/podman-test"), command]
            quiet = subprocess.run(args, capture_output=True, check=True)
            verbose = subprocess.run([*args, "--verbose"], capture_output=True, check=True)
            self.assertEqual(quiet.stdout, verbose.stdout)
            if command == "all":
                self.assertEqual(quiet.stdout, b"static\nandroid\nwatch\n")
        dev = subprocess.run(["bash", "-euo", "pipefail", "-c", fixture, "fixture",
                              str(ROOT / "tools/podman-test"), "dev", "echo", "--verbose"],
                             capture_output=True, check=True)
        self.assertEqual(dev.stdout, b"dev echo --verbose\n")

    def test_counts_states_unknown_output_and_memory(self):
        result = self.run_check("""cat <<'OUTPUT'
> Task :one
> Task :two UP-TO-DATE
> Task :three FROM-CACHE
> Task :four SKIPPED
Ran 17 tests in 1.0s
Verified 8 instrumentation tests with no failures or skips.
warning: fixture
unfamiliar continuation
EMERY APP MEMORY USAGE
Total size of resources:        4474 bytes / 256.0KB
Total footprint in RAM:         54116 bytes / 128.0KB
Free RAM available (heap):      76956 bytes
GABBRO APP MEMORY USAGE
Total size of resources:        4475 bytes / 256.0KB
Total footprint in RAM:         54117 bytes / 128.0KB
Free RAM available (heap):      76957 bytes
OUTPUT""")
        self.assertEqual(result.returncode, 0, result.stdout)
        for expected in (b"warnings=1", b"python=17", b"instrumentation=8",
                         b"executed=1 up-to-date=1 cached=1 skipped=1",
                         b"EMERY resources=4474 RAM=54116 heap=76956B",
                         b"GABBRO resources=4475 RAM=54117 heap=76957B"):
            self.assertIn(expected, result.stdout)

    def test_multiline_and_sanitizer_failure_are_retained(self):
        result = self.run_check("printf 'unexpected failure\\n  context\\n\\nAddressSanitizer: crash\\n  #0 frame\\n'; exit 1")
        self.assertIn(b"unexpected failure\n  context\n\nAddressSanitizer: crash\n  #0 frame", result.stdout)

    def test_shared_byte_line_budget_oversized_lines_and_ci_deduplication(self):
        result = self.run_check("for i in {1..100}; do printf '%0100d\\n' \"$i\"; done; printf '%09000d\\n' 1; exit 1")
        excerpt = result.stdout[result.stdout.index(b"--- "):]
        self.assertLessEqual(len(excerpt), 4096)
        self.assertLessEqual(len(excerpt.splitlines()), 60)
        self.assertIn(b"truncated", excerpt)
        self.assertGreater((self.directory() / "combined.log").stat().st_size, 19000)
        replay = subprocess.run([str(ROOT / "tools/check-diagnostics"), str(self.directory())],
                                capture_output=True, check=True)
        self.assertEqual(replay.stdout, b"")

    def test_one_oversized_line_and_many_short_lines_are_bounded(self):
        for body in ("printf '%09000d\\n' 1; exit 1",
                     "for i in {1..500}; do echo x; done; exit 1"):
            result = self.run_check(body)
            excerpt = result.stdout[result.stdout.index(b"--- "):]
            self.assertLessEqual(len(excerpt), 4096)
            self.assertLessEqual(len(excerpt.splitlines()), 60)
            self.assertIn(b"truncated", excerpt)

    def test_log_flush_failure_is_an_infrastructure_failure(self):
        binary = self.root / "bin"
        binary.mkdir()
        sync = binary / "sync"
        sync.write_text('#!/bin/bash\nexit 8\n')
        sync.chmod(0o755)
        result = self.run_check("true", PATH=f"{binary}:{os.environ['PATH']}")
        self.assertEqual(result.returncode, 74)

    def test_timeout_status_and_heartbeat(self):
        result = self.run_check("timeout 0.3s sleep 10", CHECK_HEARTBEAT_SECONDS="0.05")
        self.assertEqual(result.returncode, 124)
        self.assertIn(b"[active] work", result.stdout)
        self.assertIn(b"FAIL 124", result.stdout)

    def test_external_timeout_stops_the_supervisor(self):
        result = subprocess.run(["timeout", "0.3s", *self.command("sleep 30")],
                                capture_output=True, timeout=5)
        self.assertEqual(result.returncode, 124)

    def test_signal_stops_command_and_capture(self):
        with subprocess.Popen(self.command('echo "$BASHPID" > "$CHECK_LOG_DIR/child"; sleep 30'),
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT) as process:
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                try:
                    child = int((self.directory() / "child").read_text())
                    break
                except FileNotFoundError:
                    time.sleep(0.02)
            else:
                process.kill()
                self.fail("child did not start")
            process.send_signal(signal.SIGTERM)
            output, _ = process.communicate(timeout=5)
            self.assertEqual(process.returncode, 143, output)
            with self.assertRaises(ProcessLookupError):
                os.kill(child, 0)

    def test_log_creation_and_write_failures_are_nonzero(self):
        (self.root / "build").write_text("not a directory")
        self.assertNotEqual(self.run_check("true").returncode, 0)
        (self.root / "build").unlink()
        result = self.run_check('ln -s /dev/full "$CHECK_LOG_DIR/02-broken.log"; check_stage broken; echo content')
        self.assertNotEqual(result.returncode, 0)

    def test_combined_log_write_failure_is_nonzero(self):
        binary = self.root / "bin"
        binary.mkdir()
        tee = binary / "tee"
        tee.write_text('#!/bin/bash\nexec /usr/bin/tee /dev/full "$@"\n')
        tee.chmod(0o755)
        result = self.run_check("echo retained", PATH=f"{binary}:{os.environ['PATH']}")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"retained", (self.directory() / "combined.log").read_bytes())
        failed = self.run_check("echo failed; exit 42", PATH=f"{binary}:{os.environ['PATH']}")
        self.assertEqual(failed.returncode, 42)


class GradleReportTest(unittest.TestCase):
    def test_report_staging_failure_preserves_an_original_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            for status in (0, 23):
                result = subprocess.run(["python3", str(ROOT / "tools/podman/check-gradle.py"),
                                         "bash", "-c", f'''mkdir -p build/reports
echo report > build/reports/fresh.xml
exit {status}'''], cwd=directory,
                                        env={**os.environ, "CHECK_LOG_DIR": "/dev/full"},
                                        capture_output=True)
                self.assertEqual(result.returncode, status or 74)

    def test_fresh_executed_reports_have_counts_and_are_retained(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            script = """mkdir -p android/app/build/test-results/testDebugUnitTest
echo '> Task :android:app:testDebugUnitTest FAILED'
echo '<testsuite tests="7" failures="1" errors="0" skipped="2"/>' > android/app/build/test-results/testDebugUnitTest/TEST-fixture.xml
exit 19
"""
            result = subprocess.run(["python3", str(ROOT / "tools/podman/check-gradle.py"),
                                     "bash", "-c", script], cwd=root,
                                    env={**os.environ, "CHECK_LOG_DIR": str(root / "logs")},
                                    capture_output=True)
            self.assertEqual(result.returncode, 19)
            self.assertIn(b"Tests: JVM tests=7 failures=1 errors=0 skipped=2", result.stdout)
            self.assertEqual(len(list((root / "logs/reports").rglob("TEST-*.xml"))), 1)

    def test_malformed_and_negative_counts_are_not_reported(self):
        spec = importlib.util.spec_from_file_location("gradle_report", ROOT / "tools/podman/check-gradle.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "TEST-fixture.xml"
            for content in ('<bad', '<testsuite tests="-1"/>', '<testsuite/>'):
                report.write_text(content)
                with self.assertRaises((ValueError, KeyError, module.ET.ParseError)):
                    module.totals([report])

    def test_stale_and_cached_reports_are_not_new_test_totals(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = root / "android/app/build/test-results/testDebugUnitTest/TEST-fixture.xml"
            report.parent.mkdir(parents=True)
            report.write_text('<testsuite tests="99" failures="0" errors="0" skipped="0"/>')
            for state in ("", " UP-TO-DATE", " FROM-CACHE", " SKIPPED"):
                result = subprocess.run(["python3", str(ROOT / "tools/podman/check-gradle.py"),
                                         "bash", "-c", f"echo '> Task :android:app:testDebugUnitTest{state}'"],
                                        cwd=root, capture_output=True, check=True)
                self.assertNotIn(b"Tests: JVM", result.stdout)


class AcceptanceRetentionTest(unittest.TestCase):
    def test_container_log_failures_are_reported_without_replacing_test_failures(self):
        for platform in ("android", "emery", "gabbro"):
            for original, capture in ((0, 0), (0, 9), (23, 0), (23, 9)):
                with self.subTest(platform=platform, original=original, capture=capture), \
                        tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    (root / "tools").symlink_to(ROOT / "tools", target_is_directory=True)
                    script = '''source "$1"
PROJECT_DIR=$2
BUILD_ROOT=$PROJECT_DIR/build/podman
platform=$3
test_status=$4
capture_status=$5
require_images() { :; }
require_current_golden() { :; }
ensure_cache_volumes() { :; }
clone_volume() { :; }
create_test_pod() { :; }
start_android() { :; }
acceptance_security_args() { :; }
active_network_args() { :; }
register_active_container() { :; }
runner_in_pod() { echo test-result; return "$test_status"; }
stop_active_pod() { :; }
cleanup_active() { :; }
acceptance_engine() {
  if [[ "$1" == logs ]]; then
    echo capture-detail
    return "$capture_status"
  fi
}
if [[ "$platform" == android ]]; then
  run_android_tests /fixture
else
  new_run e2e
  run_e2e_platform "$platform" "$RUN_ARTIFACTS"
fi
'''
                    result = subprocess.run(
                        ["bash", str(HELPER), directory, "bash", "-euo", "pipefail", "-c",
                         script, "fixture", str(ROOT / "tools/podman-test"), directory,
                         platform, str(original), str(capture)],
                        capture_output=True, timeout=15)
                    self.assertEqual(result.returncode, original or (74 if capture else 0),
                                     result.stdout + result.stderr)
                    logs = Path((root / "build/check-logs/latest").read_text().strip())
                    rows = [line.split("\t") for line in (logs / "manifest.tsv").read_text().splitlines()]
                    statuses = {row[1]: int(row[2]) for row in rows}
                    self.assertEqual(statuses[f"{platform}-diagnostics-and-cleanup"],
                                     74 if capture else 0)
                    test_stage = "android-instrumentation" if platform == "android" else f"{platform}-acceptance"
                    self.assertEqual(statuses[test_stage], original)
                    if capture and not original:
                        self.assertIn(f"[FAIL 74] {platform}-diagnostics-and-cleanup".encode(), result.stdout)
                        self.assertIn(b"capture-detail", result.stdout)

    def test_successful_cleanup_retains_current_diagnostics_before_removing_state(self):
        with tempfile.TemporaryDirectory() as directory:
            script = '''source "$1"
trap - EXIT
PROJECT_DIR=$2
BUILD_ROOT=$PROJECT_DIR/build/podman
CHECK_LOG_DIR=$PROJECT_DIR/build/check-logs/fixture
mkdir -p "$CHECK_LOG_DIR"
: > "$CHECK_LOG_DIR/runs.txt"
parse_locus_apk_options() { LOCUS_INPUT_DIR=/fixture; }
doctor_acceptance() { :; }
select_warm_acceptance_images() { :; }
run_android_tests() {
  new_run android
  echo current-log > "$RUN_ARTIFACTS/android.log"
}
e2e_tests() { :; }
assert_no_active_acceptance_runtime() { :; }
clean() { rm -rf "$BUILD_ROOT"; }
acceptance_suite --cleanup
test ! -d "$BUILD_ROOT"
test -n "$(find "$CHECK_LOG_DIR/diagnostics" -name android.log)"
'''
            result = subprocess.run(["bash", "-euo", "pipefail", "-c", script,
                                     "fixture", str(ROOT / "tools/podman-test"), directory],
                                    capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_diagnostic_retention_preserves_original_acceptance_verdicts(self):
        android = (ROOT / "tools/podman-test").read_text().split("run_android_tests() {", 1)[1]
        watch = (ROOT / "tools/podman/e2e-stage.sh").read_text()
        for source, ending in ((android, "\n    }"), (watch, "\n}")):
            body = "capture_artifacts() {" + source.split("capture_artifacts() {", 1)[1].split(ending, 1)[0] + "\n}"
            with tempfile.TemporaryDirectory() as directory:
                body = body.replace("/artifacts", directory)
                for original, capture, expected in ((0, 0, 0), (0, 9, 74), (23, 9, 23)):
                    script = f'''PEBBLE_PLATFORM=emery
STATUS_URI=fixture
android_screenshot() {{ :; }}
dump_ui() {{ :; }}
adb_device_timeout() {{ return {capture}; }}
cp() {{ return {capture}; }}
{body}
trap capture_artifacts EXIT
exit {original}
'''
                    result = subprocess.run(["bash", "-euo", "pipefail", "-c", script],
                                            capture_output=True)
                    self.assertEqual(result.returncode, expected, result.stderr)
