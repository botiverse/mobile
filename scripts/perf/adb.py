"""Small wrapper around `scripts/slock_android_adb.py` so perf tools can drive
the emulator without re-implementing the JSON output protocol.

This module is intentionally narrow: only the commands the perf tools use.
If you need more, prefer extending `slock_android_adb.py` upstream and calling
it via `subprocess.run` rather than duplicating behavior here.
"""

from __future__ import annotations

import argparse
import json
import os
import shlex
import subprocess
import sys
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[2]
ADB_HELPER = REPO_ROOT / "scripts" / "slock_android_adb.py"


def _run(cmd: list[str], *, check: bool = True) -> dict[str, Any]:
    """Invoke slock_android_adb.py and return the JSON payload."""
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if not proc.stdout.strip():
        raise RuntimeError(
            f"adb helper returned no output; stderr={proc.stderr!r}"
        )
    try:
        return json.loads(proc.stdout)
    except json.JSONDecodeError as exc:
        raise RuntimeError(
            f"adb helper returned non-JSON: {proc.stdout[:200]!r}; exc={exc}"
        ) from exc


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Direct passthrough to scripts/slock_android_adb.py")
    p.add_argument("--device", required=True, help="adb serial, e.g. emulator-5554")
    p.add_argument("--pretty", action="store_true", help="pretty-print JSON")
    sub = p.add_subparsers(dest="cmd", required=True)

    sub.add_parser("devices")
    start_app = sub.add_parser("start-app")
    start_app.add_argument("--component")
    sub.add_parser("get-app-state")
    sub.add_parser("click").add_argument("x", type=int).add_argument("y", type=int)
    sub.add_parser("scroll").add_argument("--direction")
    sub.add_parser("press-key").add_argument("key")
    sub.add_parser("capture-logcat")

    return p


def main() -> int:
    args = build_parser().parse_args()
    cmd = [sys.executable, str(ADB_HELPER), "--device", args.device]
    if args.pretty:
        cmd.append("--pretty")
    cmd.append(args.cmd.replace("-", "_"))
    if args.cmd == "start-app" and getattr(args, "component", None):
        cmd += ["--component", args.component]
    elif args.cmd == "click":
        cmd += [str(args.x), str(args.y)]
    elif args.cmd == "scroll" and getattr(args, "direction", None):
        cmd += ["--direction", args.direction]
    elif args.cmd == "press-key":
        cmd.append(args.key)
    print(json.dumps(_run(cmd), indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())