import hashlib
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from script_test_support import ROOT


class DocumentationDependenciesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.bin = self.directory / "bin"
        self.bin.mkdir()
        self.venv = self.directory / "venv"
        self.calls = self.directory / "calls"
        self.ready = self.directory / "ready"
        self.requirements = ROOT / "docs/requirements.txt"
        self.digest = hashlib.sha256(self.requirements.read_bytes()).hexdigest()
        self.python = self.bin / "python"
        self.python.write_text('''#!/bin/bash
set -euo pipefail
printf 'python %s\\n' "$*" >> "$CALLS"
case "$1" in
  -)
    sha256sum "$2" | cut -d' ' -f1
    ;;
  -c)
    test -f "$READY"
    ;;
  -m)
    if [[ "$2" == venv ]]; then
      mkdir -p "$3/bin"
      cp "$0" "$3/bin/python"
    elif [[ "$2" == sphinx ]]; then
      test -f "$READY"
    else
      exit 91
    fi
    ;;
esac
''')
        self.python.chmod(0o755)
        installer = self.bin / "uv"
        installer.write_text('''#!/bin/bash
set -euo pipefail
printf 'uv %s\\n' "$*" >> "$CALLS"
if (( INSTALL_STATUS != 0 )); then
  exit "$INSTALL_STATUS"
fi
touch "$READY"
''')
        installer.chmod(0o755)

    def existing_environment(self, stamp, modules_present=True):
        (self.venv / "bin").mkdir(parents=True)
        shutil.copy2(self.python, self.venv / "bin/python")
        (self.venv / ".requirements.sha256").write_text(stamp + "\n")
        if modules_present:
            self.ready.touch()

    def build(self, install_status=0):
        return subprocess.run(
            ["bash", str(ROOT / "docs/build_html.sh")],
            env={
                **os.environ,
                "PATH": str(self.bin) + os.pathsep + os.environ["PATH"],
                "PYTHON": str(self.python),
                "DOCS_VENV_DIR": str(self.venv),
                "DOCS_BUILD_DIR": str(self.directory / "output"),
                "CALLS": str(self.calls),
                "READY": str(self.ready),
                "INSTALL_STATUS": str(install_status),
            },
            capture_output=True,
            text=True,
            check=False,
        )

    def assert_installed(self):
        self.assertIn(
            f"uv pip install --python {self.venv}/bin/python --requirement {self.requirements}",
            self.calls.read_text(),
        )
        self.assertEqual((self.venv / ".requirements.sha256").read_text().strip(), self.digest)
        self.assertIn("python -m sphinx -W --keep-going", self.calls.read_text())

    def test_fresh_environment_installs_pins_into_its_own_python(self):
        result = self.build()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_installed()

    def test_matching_healthy_environment_reuses_dependencies(self):
        self.existing_environment(self.digest)
        result = self.build(install_status=47)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("uv ", self.calls.read_text())
        self.assertIn("python -m sphinx -W --keep-going", self.calls.read_text())

    def test_changed_requirements_reinstall_dependencies(self):
        self.existing_environment("old")
        result = self.build()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_installed()

    def test_missing_modules_repair_a_matching_stamp(self):
        self.existing_environment(self.digest, modules_present=False)
        result = self.build()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_installed()

    def test_failed_installation_preserves_status_without_stamp_or_html_build(self):
        result = self.build(install_status=47)
        self.assertEqual(result.returncode, 47, result.stderr)
        self.assertFalse((self.venv / ".requirements.sha256").exists())
        self.assertNotIn("python -m sphinx", self.calls.read_text())
