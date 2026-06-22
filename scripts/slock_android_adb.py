#!/usr/bin/env python3
"""Small adb helper for Slock Android/KMP profiler audits.

The script intentionally has no third-party dependencies. Commands print JSON
so agents can chain state capture, taps, scrolls, logcat, and profiler pulls.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PACKAGE = "ai.slock.android"
DEFAULT_COMPONENT = "ai.slock.android/.MainActivity"
DEFAULT_OUT = REPO_ROOT / "artifacts" / "android-adb" / datetime.now().strftime("%Y%m%d-%H%M%S")
KEY_ALIASES = {
    "BACK": "BACK",
    "HOME": "HOME",
    "ENTER": "ENTER",
    "MENU": "MENU",
    "TAB": "TAB",
    "WAKEUP": "WAKEUP",
    "POWER": "POWER",
    "DPAD_UP": "DPAD_UP",
    "DPAD_DOWN": "DPAD_DOWN",
    "DPAD_LEFT": "DPAD_LEFT",
    "DPAD_RIGHT": "DPAD_RIGHT",
}


class CliError(RuntimeError):
    pass


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    if not hasattr(args, "func"):
        parser.print_help()
        return 2

    try:
        result = args.func(args)
    except CliError as exc:
        print_json({"ok": False, "error": str(exc)}, pretty=args.pretty)
        return 1
    print_json({"ok": True, **result}, pretty=args.pretty)
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="adb helper for Slock Android UI, log, and Kuikly profiler capture.",
    )
    parser.add_argument("--adb", default=os.environ.get("ADB", "adb"), help="adb executable path")
    parser.add_argument(
        "--device",
        default=os.environ.get("ANDROID_SERIAL"),
        help="adb serial. Defaults to ANDROID_SERIAL, then the first online device.",
    )
    parser.add_argument("--pretty", action="store_true", help="Pretty-print JSON output")
    sub = parser.add_subparsers(dest="command")

    devices = sub.add_parser("devices", help="List online adb devices")
    devices.set_defaults(func=cmd_devices)

    start_app = sub.add_parser("start-app", aliases=["start_app"], help="Launch the Slock Android app")
    start_app.add_argument("--component", default=DEFAULT_COMPONENT)
    start_app.set_defaults(func=cmd_start_app)

    state = sub.add_parser(
        "get-app-state",
        aliases=["get_app_state"],
        help="Capture screenshot and uiautomator XML",
    )
    add_out_args(state)
    state.set_defaults(func=cmd_get_app_state)

    click = sub.add_parser("click", help="Tap screen coordinates")
    click.add_argument("x", type=int)
    click.add_argument("y", type=int)
    click.set_defaults(func=cmd_click)

    key = sub.add_parser("press-key", aliases=["press_key"], help="Send an Android keyevent")
    key.add_argument("key", help="Key name or numeric key code, for example BACK, HOME, ENTER, 66")
    key.set_defaults(func=cmd_press_key)

    scroll = sub.add_parser("scroll", help="Swipe by coordinates or direction")
    scroll.add_argument("--x1", type=int)
    scroll.add_argument("--y1", type=int)
    scroll.add_argument("--x2", type=int)
    scroll.add_argument("--y2", type=int)
    scroll.add_argument("--direction", choices=("up", "down", "left", "right"))
    scroll.add_argument("--duration-ms", type=int, default=350)
    scroll.set_defaults(func=cmd_scroll)

    logcat = sub.add_parser(
        "capture-logcat",
        aliases=["capture_logcat"],
        help="Dump or stream logcat to an artifact file",
    )
    add_out_args(logcat)
    logcat.add_argument("--clear-first", action="store_true", help="Clear logcat before capture")
    logcat.add_argument("--duration-seconds", type=float, default=0.0, help="Stream for N seconds; default dumps current log")
    logcat.add_argument("--filter-regex", help="Also write a filtered log file matching this regex")
    logcat.set_defaults(func=cmd_capture_logcat)

    profiler = sub.add_parser(
        "pull-profiler",
        aliases=["pull_profiler"],
        help="Pull Kuikly profiler files via run-as",
    )
    add_out_args(profiler)
    profiler.add_argument("--package", default=DEFAULT_PACKAGE)
    profiler.add_argument("--allow-missing", action="store_true")
    profiler.set_defaults(func=cmd_pull_profiler)

    return parser


def add_out_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--out", default=str(DEFAULT_OUT), help=f"Artifact directory. Default: {DEFAULT_OUT}")
    parser.add_argument("--prefix", default="state", help="Artifact filename prefix")


def cmd_devices(args: argparse.Namespace) -> dict[str, Any]:
    lines = run_adb(args, ["devices"]).stdout.splitlines()
    devices = []
    for line in lines[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])
    return {"devices": devices}


def cmd_start_app(args: argparse.Namespace) -> dict[str, Any]:
    serial = ensure_device(args)
    result = run_adb(args, ["-s", serial, "shell", "am", "start", "-W", "-n", args.component])
    return {"device": serial, "component": args.component, "stdout": result.stdout.strip()}


def cmd_get_app_state(args: argparse.Namespace) -> dict[str, Any]:
    serial = ensure_device(args)
    out = ensure_out(args.out)
    prefix = safe_prefix(args.prefix)
    timestamp = now_id()
    base = f"{prefix}-{timestamp}"
    screenshot = out / f"{base}.png"
    uixml = out / f"{base}.xml"
    dump_log = out / f"{base}-uiautomator.txt"

    png = run_adb(args, ["-s", serial, "exec-out", "screencap", "-p"], binary=True)
    screenshot.write_bytes(png.stdout_bytes)
    dump = run_adb(args, ["-s", serial, "shell", "uiautomator", "dump", "/sdcard/window.xml"])
    dump_log.write_text(dump.stdout + dump.stderr, encoding="utf-8")
    xml = run_adb(args, ["-s", serial, "shell", "cat", "/sdcard/window.xml"]).stdout
    uixml.write_text(xml, encoding="utf-8")

    return {
        "device": serial,
        "out_dir": str(out),
        "screenshot_path": str(screenshot),
        "uixml_path": str(uixml),
        "uiautomator_log_path": str(dump_log),
    }


def cmd_click(args: argparse.Namespace) -> dict[str, Any]:
    serial = ensure_device(args)
    run_adb(args, ["-s", serial, "shell", "input", "tap", str(args.x), str(args.y)])
    return {"device": serial, "x": args.x, "y": args.y}


def cmd_press_key(args: argparse.Namespace) -> dict[str, Any]:
    serial = ensure_device(args)
    key = args.key.upper()
    key = KEY_ALIASES.get(key, args.key)
    run_adb(args, ["-s", serial, "shell", "input", "keyevent", key])
    return {"device": serial, "key": key}


def cmd_scroll(args: argparse.Namespace) -> dict[str, Any]:
    serial = ensure_device(args)
    if args.direction:
        x1, y1, x2, y2 = direction_to_swipe(args, serial, args.direction)
    else:
        required = (args.x1, args.y1, args.x2, args.y2)
        if any(value is None for value in required):
            raise CliError("scroll requires either --direction or --x1 --y1 --x2 --y2")
        x1, y1, x2, y2 = args.x1, args.y1, args.x2, args.y2
    run_adb(
        args,
        [
            "-s",
            serial,
            "shell",
            "input",
            "swipe",
            str(x1),
            str(y1),
            str(x2),
            str(y2),
            str(args.duration_ms),
        ],
    )
    return {"device": serial, "x1": x1, "y1": y1, "x2": x2, "y2": y2, "duration_ms": args.duration_ms}


def cmd_capture_logcat(args: argparse.Namespace) -> dict[str, Any]:
    serial = ensure_device(args)
    out = ensure_out(args.out)
    prefix = safe_prefix(args.prefix)
    timestamp = now_id()
    path = out / f"{prefix}-{timestamp}-logcat.txt"
    filtered_path = out / f"{prefix}-{timestamp}-logcat-filtered.txt" if args.filter_regex else None
    if args.clear_first:
        run_adb(args, ["-s", serial, "logcat", "-c"])
    if args.duration_seconds > 0:
        text = run_with_timeout(
            [args.adb, "-s", serial, "logcat", "-v", "time"],
            timeout=args.duration_seconds,
        )
    else:
        text = run_adb(args, ["-s", serial, "logcat", "-d", "-v", "time"]).stdout
    path.write_text(text, encoding="utf-8", errors="replace")
    result: dict[str, Any] = {"device": serial, "logcat_path": str(path), "bytes": path.stat().st_size}
    if filtered_path:
        regex = re.compile(args.filter_regex, re.IGNORECASE)
        filtered = "\n".join(line for line in text.splitlines() if regex.search(line))
        filtered_path.write_text(filtered + ("\n" if filtered else ""), encoding="utf-8")
        result["filtered_logcat_path"] = str(filtered_path)
        result["filtered_lines"] = len(filtered.splitlines()) if filtered else 0
    return result


def cmd_pull_profiler(args: argparse.Namespace) -> dict[str, Any]:
    serial = ensure_device(args)
    out = ensure_out(args.out)
    pulled: list[dict[str, str]] = []
    missing: list[str] = []
    for remote, local_name in (
        ("cache/KuiklyProfiler/profiler_report.json", "profiler_report.json"),
        ("cache/KuiklyProfiler/profiler_frames.jsonl", "profiler_frames.jsonl"),
        ("files/KuiklyProfiler/profiler_report.json", "files_profiler_report.json"),
        ("files/KuiklyProfiler/profiler_frames.jsonl", "files_profiler_frames.jsonl"),
    ):
        dest = out / local_name
        result = run_adb(
            args,
            ["-s", serial, "shell", "run-as", args.package, "cat", remote],
            check=False,
            binary=True,
        )
        if result.returncode == 0 and result.stdout_bytes:
            dest.write_bytes(result.stdout_bytes)
            pulled.append({"remote": remote, "path": str(dest)})
        else:
            missing.append(remote)
    if not pulled and not args.allow_missing:
        raise CliError(f"No profiler files found for {args.package}; missing={missing}")
    return {"device": serial, "package": args.package, "out_dir": str(out), "pulled": pulled, "missing": missing}


def ensure_device(args: argparse.Namespace) -> str:
    if args.device:
        return args.device
    devices = cmd_devices(args)["devices"]
    if not devices:
        raise CliError("No online adb devices. Start an emulator/device or pass --device.")
    return devices[0]


def ensure_out(value: str) -> Path:
    path = Path(value).expanduser().resolve()
    path.mkdir(parents=True, exist_ok=True)
    return path


def safe_prefix(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", value.strip() or "state")


def now_id() -> str:
    return datetime.now().strftime("%Y%m%d-%H%M%S")


def direction_to_swipe(args: argparse.Namespace, serial: str, direction: str) -> tuple[int, int, int, int]:
    width, height = screen_size(args, serial)
    mid_x = width // 2
    mid_y = height // 2
    left = width // 4
    right = width - left
    top = max(120, height // 4)
    bottom = height - top
    if direction == "up":
        return mid_x, bottom, mid_x, top
    if direction == "down":
        return mid_x, top, mid_x, bottom
    if direction == "left":
        return right, mid_y, left, mid_y
    return left, mid_y, right, mid_y


def screen_size(args: argparse.Namespace, serial: str) -> tuple[int, int]:
    result = run_adb(args, ["-s", serial, "shell", "wm", "size"]).stdout
    match = re.search(r"Physical size:\s*(\d+)x(\d+)", result)
    if not match:
        raise CliError(f"Unable to parse screen size from: {result.strip()}")
    return int(match.group(1)), int(match.group(2))


class CommandResult:
    def __init__(self, returncode: int, stdout: str, stderr: str, stdout_bytes: bytes):
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr
        self.stdout_bytes = stdout_bytes


def run_adb(
    args: argparse.Namespace,
    adb_args: list[str],
    *,
    check: bool = True,
    binary: bool = False,
) -> CommandResult:
    try:
        result = subprocess.run(
            [args.adb, *adb_args],
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=not binary,
        )
    except FileNotFoundError as exc:
        raise CliError(f"adb not found: {args.adb}") from exc
    if binary:
        stdout_bytes = result.stdout or b""
        stdout = stdout_bytes.decode("utf-8", errors="replace")
        stderr = (result.stderr or b"").decode("utf-8", errors="replace")
    else:
        stdout = result.stdout or ""
        stderr = result.stderr or ""
        stdout_bytes = stdout.encode("utf-8", errors="replace")
    if check and result.returncode != 0:
        raise CliError(f"adb {' '.join(adb_args)} failed ({result.returncode}): {stdout}{stderr}")
    return CommandResult(result.returncode, stdout, stderr, stdout_bytes)


def run_with_timeout(command: list[str], timeout: float) -> str:
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    try:
        stdout, _ = process.communicate(timeout=timeout)
        return stdout or ""
    except subprocess.TimeoutExpired:
        process.terminate()
        try:
            stdout, _ = process.communicate(timeout=2)
        except subprocess.TimeoutExpired:
            process.kill()
            stdout, _ = process.communicate()
        return stdout or ""


def print_json(value: dict[str, Any], *, pretty: bool = False) -> None:
    if pretty:
        print(json.dumps(value, indent=2, ensure_ascii=False))
    else:
        print(json.dumps(value, separators=(",", ":"), ensure_ascii=False))


if __name__ == "__main__":
    sys.exit(main())
