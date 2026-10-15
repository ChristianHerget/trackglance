"""Pass only verified startup identity to the behavior phase, without executing state."""

import json
from pathlib import Path
import sys


def main():
    operation, filename, platform, *values = sys.argv[1:]
    path = Path(filename)
    if operation == "write":
        pid, profile = values
        if not pid.isdigit() or not profile:
            raise ValueError("Missing Android identity or recording profile")
        path.write_text(json.dumps({"platform": platform, "pid": pid, "profile": profile}))
    elif operation == "read":
        state = json.loads(path.read_text())
        if state["platform"] != platform or not state["pid"].isdigit() or not state["profile"]:
            raise ValueError("Startup identity does not match behavior")
        for field in ("pid", "profile"):
            sys.stdout.buffer.write(state[field].encode() + b"\0")
    else:
        raise ValueError(f"Unknown phase-state operation: {operation}")


if __name__ == "__main__":
    main()
