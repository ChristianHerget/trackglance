import re
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
PODMAN_TEST = ROOT / "tools" / "podman-test"
DEVICE_LIB = ROOT / "tools" / "podman" / "device-lib.sh"
WEB_CONTAINERFILE = ROOT / "tools" / "podman" / "Containerfile.web"
EMULATOR_ENTRYPOINT = ROOT / "tools" / "podman" / "android-emulator-entrypoint.sh"
PUBLISH_RELEASE_WORKFLOW = ROOT / ".github" / "workflows" / "publish-release.yml"
CODEQL_WORKFLOW = ROOT / ".github" / "workflows" / "codeql.yml"
ACTION_MAJORS = {
    "actions/checkout": 7,
    "actions/configure-pages": 6,
    "actions/upload-pages-artifact": 5,
    "actions/deploy-pages": 5,
    "actions/dependency-review-action": 5,
    "github/codeql-action/init": 4,
    "github/codeql-action/analyze": 4,
    "crazy-max/ghaction-virustotal": 5,
    "actions/upload-artifact": 7,
    "dependabot/fetch-metadata": 3,
    "actions/create-github-app-token": 3,
}



def action_declarations(source):
    """Read block-style uses declarations, ignoring comments and block scalar text.

    This deliberately covers the repository's workflow layout, not arbitrary YAML.
    actionlint separately validates workflow syntax.
    """
    block_indent = None
    for line in source.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        indent = len(line) - len(line.lstrip())
        if block_indent is not None:
            if indent > block_indent:
                continue
            block_indent = None
        if re.match(r"^\s*(?:-\s+)?[\w-]+:\s*[|>][+-]?[1-9]?\s*(?:#.*)?$", line):
            block_indent = indent + (2 if line.lstrip().startswith("- ") else 0)
        declaration = re.match(r"^\s*(?:-\s+)?uses:\s*(.*?)\s*$", line)
        if declaration:
            yield declaration.group(1)



def checked_action_pins(source, action):
    """Require full SHA pins and stable release comments in the approved major.

    Comments declare a release; offline checks cannot verify the action's runtime
    or establish that the SHA belongs to the declared release.
    """
    pins = []
    for declaration in action_declarations(source):
        identity = re.split(r"[@\s#]", declaration.lstrip("\"'"), maxsplit=1)[0]
        if identity != action:
            continue
        match = re.fullmatch(
            rf"{re.escape(action)}@([0-9a-fA-F]{{40}})\s+#\s+"
            rf"(v{ACTION_MAJORS[action]}\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*))",
            declaration,
        )
        if match is None:
            raise AssertionError(f"Invalid pin for {action}: {declaration}")
        pins.append(match.groups())
    if not pins:
        raise AssertionError(f"Missing required action: {action}")
    return pins



def checked_codeql_pins(source):
    declarations = [
        value for value in action_declarations(source)
        if value.lstrip("\"'").startswith("github/codeql-action/")
    ]
    if len(declarations) != 4:
        raise AssertionError("Expected exactly four CodeQL steps")
    pins = []
    for step in ("init", "analyze"):
        step_pins = checked_action_pins(source, f"github/codeql-action/{step}")
        if len(step_pins) != 2:
            raise AssertionError(f"Expected two CodeQL {step} steps")
        pins.extend(step_pins)
    if len(set(pins)) != 1:
        raise AssertionError("All four CodeQL steps must share the same SHA and version")
