import subprocess
import unittest
from script_test_support import PODMAN_TEST


class AcceptanceComponentsTest(unittest.TestCase):
    def run_suite(self, *options, failure=''):
        script = r'''
source "$1"
shift
check_plan() { printf 'plan:%s\n' "$@"; }
check_stage() { :; }
check_result() { :; }
parse_locus_apk_options() { LOCUS_INPUT_DIR=/private; }
doctor_acceptance() { echo warm; }
report_acceptance_resources() { :; }
assert_no_active_acceptance_runtime() { :; }
run_android_tests() { echo android; [[ "$FAILURE" != android ]]; }
e2e_tests() { echo "watch:$2:$CHECK_PASS"; [[ "$FAILURE" != watch ]]; }
clean() { echo clean; }
build_acceptance_all() { echo build; }
prepare_published_acceptance_images() { echo published; }
bootstrap() { echo bootstrap; }
stage_acceptance_diagnostics() { echo diagnostics; }
acceptance_suite "$@"
'''
        import os
        return subprocess.run(['bash', '-euo', 'pipefail', '-c', script, 'test', str(PODMAN_TEST), *options],
                              env={**os.environ, 'FAILURE': failure}, capture_output=True, text=True, timeout=10)

    def test_all_component_values_and_default_select_only_expected_plans(self):
        for component in (None, 'all', 'android', 'emery', 'gabbro'):
            for passes in ('1', '2'):
                with self.subTest(component=component, passes=passes):
                    options = [] if component is None else ['--component', component]
                    result = self.run_suite(*options, '--watch-passes', passes)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    expected = []
                    if component in (None, 'all', 'android'):
                        expected.append('plan:android-instrumentation')
                    for n in range(1, int(passes) + 1):
                        for watch in ('emery', 'gabbro'):
                            if component in (None, 'all', watch):
                                expected.append(f'plan:{watch}-acceptance-pass-{n}')
                    self.assertEqual([s for s in result.stdout.splitlines() if s.startswith('plan:')], expected)
                    self.assertEqual(result.stdout.splitlines().count('android'), int(component in (None, 'all', 'android')))
                    watch_calls = [s for s in result.stdout.splitlines() if s.startswith('watch:')]
                    self.assertEqual(watch_calls, [] if component == 'android' else
                                     [f'watch:{component or "all"}:{n}' for n in range(1, int(passes) + 1)])

    def test_invalid_options_fail_before_provisioning(self):
        for options in (('--component',), ('--component', 'round'), ('--fresh', '--published'),
                        ('--published', '--fresh'), ('--watch-passes', '0'), ('--watch-passes', '3')):
            with self.subTest(options=options):
                result = self.run_suite(*options)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn('warm', result.stdout)
                self.assertNotIn('bootstrap', result.stdout)

    def test_failed_platform_never_triggers_clean_retry_or_second_soak_pass(self):
        for component in ('emery', 'gabbro', 'all'):
            result = self.run_suite('--component', component, '--watch-passes', '2', failure='watch')
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(result.stdout.count('watch:'), 1)
            self.assertNotIn('clean', result.stdout)
            self.assertIn('diagnostics', result.stdout)

    def test_android_failure_prevents_watch_stages(self):
        result = self.run_suite(failure='android')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('watch:', result.stdout)

    def test_provisioning_modes_share_selected_stages_and_cleanup(self):
        for option, expected in (('--fresh', 'build'), ('--published', 'published')):
            result = self.run_suite(option, '--cleanup', '--component', 'gabbro')
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn(expected, result.stdout)
            self.assertEqual(result.stdout.splitlines().count('clean'), 2)
            self.assertIn('watch:gabbro:1', result.stdout)
            self.assertNotIn('\nandroid\n', result.stdout)

    def test_watch_entry_point_runs_each_selected_platform_once(self):
        source = PODMAN_TEST.read_text()
        body = source.split("e2e_tests() {", 1)[1].split("\n}", 1)[0]
        for component, expected in (("all", ["emery", "gabbro"]), ("emery", ["emery"]), ("gabbro", ["gabbro"])):
            script = """
require_images() { :; }
require_current_golden() { :; }
ensure_cache_volumes() { :; }
check_stage() { :; }
new_run() { RUN_ARTIFACTS=/artifacts; }
cp() { :; }
run_e2e_platform() { echo "$1"; }
PROJECT_DIR=/workspace
e2e_tests() {
""" + body + "\n}\ne2e_tests /private \"$1\""
            result = subprocess.run(["bash", "-euo", "pipefail", "-c", script, "test", component],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.splitlines(), expected)
