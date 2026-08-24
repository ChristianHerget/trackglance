#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SOURCE_DIR="$SCRIPT_DIR/sphinx"
BUILD_DIR="${DOCS_BUILD_DIR:-$SOURCE_DIR/_build}"
VENV_DIR="${DOCS_VENV_DIR:-$PROJECT_DIR/build/docs-venv}"
REQUIREMENTS="$SCRIPT_DIR/requirements.txt"
PYTHON="${PYTHON:-python3}"

if ! command -v "$PYTHON" >/dev/null 2>&1; then
  echo "Error: '$PYTHON' is required to build the documentation." >&2
  exit 1
fi

if [[ ! -x "$VENV_DIR/bin/python" ]]; then
  "$PYTHON" -m venv "$VENV_DIR"
fi

REQUIREMENTS_HASH="$(
  "$PYTHON" - "$REQUIREMENTS" <<'PY'
import hashlib
import pathlib
import sys

print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())
PY
)"
REQUIREMENTS_STAMP="$VENV_DIR/.requirements.sha256"

if [[ ! -f "$REQUIREMENTS_STAMP" ]] || [[ "$(cat "$REQUIREMENTS_STAMP")" != "$REQUIREMENTS_HASH" ]] || \
    ! "$VENV_DIR/bin/python" -c 'import sphinx, sphinx_rtd_theme'; then
  "$VENV_DIR/bin/python" -m pip install --disable-pip-version-check --requirement "$REQUIREMENTS"
  printf '%s\n' "$REQUIREMENTS_HASH" > "$REQUIREMENTS_STAMP"
fi

"$VENV_DIR/bin/python" -m sphinx -W --keep-going -b html \
  -d "$BUILD_DIR/doctrees" "$SOURCE_DIR" "$BUILD_DIR/html"
