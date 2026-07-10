#!/usr/bin/env python3
"""Small hdc helper for Slock OHOS device control.

The script intentionally has no third-party dependencies. Commands print JSON
so agents can chain target discovery, app launch, taps, swipes, layout dumps,
and screenshots in task reports.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DEVECO_HOME = Path("/Applications/DevEco-Studio.app/Contents")
DEFAULT_SDK_HOME = DEFAULT_DEVECO_HOME / "sdk"
DEFAULT_HDC = DEFAULT_SDK_HOME / "default" / "openharmony" / "toolchains" / "hdc"
DEFAULT_OUT = Path(os.environ.get("TMPDIR", "/tmp")) / "slock-ohos-automation" / datetime.now().strftime("%Y%m%d-%H%M%S")
DEFAULT_BUNDLE = "build.raft.mobile"
DEFAULT_MODULE = "entry"
DEFAULT_ABILITY = "EntryAbility"


class CliError(RuntimeError):
    pass


def main() -> int:
    parser = build_parser()
    argv = sys.argv[1:]
    pretty = "--pretty" in argv
    if pretty:
        argv = [value for value in argv if value != "--pretty"]
    args = parser.parse_args(argv)
    args.pretty = args.pretty or pretty
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
    parser = argparse.ArgumentParser(description="hdc helper for Slock OHOS UI automation.")
    parser.add_argument("--hdc", default=os.environ.get("HDC_BIN", str(DEFAULT_HDC)), help="hdc executable path")
    parser.add_argument("--target", default=os.environ.get("OHOS_SERIAL"), help="hdc target id; defaults to first connected target")
    parser.add_argument("--pretty", action="store_true", help="Pretty-print JSON output")
    sub = parser.add_subparsers(dest="command")

    devices = sub.add_parser("devices", help="List connected OHOS targets")
    devices.set_defaults(func=cmd_devices)

    start = sub.add_parser("start-app", aliases=["start_app"], help="Launch the Slock OHOS EntryAbility")
    start.add_argument("--bundle", default=DEFAULT_BUNDLE)
    start.add_argument("--ability", default=DEFAULT_ABILITY)
    start.add_argument("--wait", type=float, default=2.0)
    start.set_defaults(func=cmd_start_app)

    stop = sub.add_parser("stop-app", aliases=["stop_app"], help="Force-stop the Slock OHOS bundle")
    stop.add_argument("--bundle", default=DEFAULT_BUNDLE)
    stop.set_defaults(func=cmd_stop_app)

    state = sub.add_parser("get-app-state", aliases=["get_app_state"], help="Capture screenshot and optional UI layout")
    add_out_args(state)
    state.add_argument("--dump-layout", action=argparse.BooleanOptionalAction, default=True)
    state.set_defaults(func=cmd_get_app_state)

    screenshot = sub.add_parser("screenshot", help="Capture current device screen")
    add_out_args(screenshot)
    screenshot.set_defaults(func=cmd_screenshot)

    dump = sub.add_parser("dump-layout", aliases=["dump_layout"], help="Dump OHOS UI tree through uitest")
    add_out_args(dump)
    dump.set_defaults(func=cmd_dump_layout)

    click = sub.add_parser("click", help="Tap screen coordinates through uitest uiInput")
    click.add_argument("x", type=int)
    click.add_argument("y", type=int)
    click.set_defaults(func=cmd_click)

    swipe = sub.add_parser("swipe", help="Swipe by coordinates or direction through uitest uiInput")
    swipe.add_argument("--x1", type=int)
    swipe.add_argument("--y1", type=int)
    swipe.add_argument("--x2", type=int)
    swipe.add_argument("--y2", type=int)
    swipe.add_argument("--direction", choices=("up", "down", "left", "right"))
    swipe.add_argument("--duration-ms", type=int, default=350)
    swipe.set_defaults(func=cmd_swipe)

    key = sub.add_parser("press-key", aliases=["press_key"], help="Send a key event through uitest uiInput")
    key.add_argument("key", help="Key name, for example Back, Home, Enter")
    key.set_defaults(func=cmd_press_key)

    help_cmd = sub.add_parser("input-help", aliases=["input_help"], help="Print target uitest help output")
    help_cmd.set_defaults(func=cmd_input_help)

    return parser


def add_out_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--out", default=str(DEFAULT_OUT), help=f"Artifact directory. Default: {DEFAULT_OUT}")
    parser.add_argument("--prefix", default="state", help="Artifact filename prefix")


def cmd_devices(args: argparse.Namespace) -> dict[str, Any]:
    result = run_hdc(args, ["list", "targets"], target=False)
    targets = [line.strip() for line in result.stdout.splitlines() if line.strip() and line.strip() != "[Empty]"]
    return {"targets": targets, "raw": result.stdout.strip()}


def cmd_start_app(args: argparse.Namespace) -> dict[str, Any]:
    target = ensure_target(args)
    command = f"aa start -a {shell_quote(args.ability)} -b {shell_quote(args.bundle)}"
    result = run_hdc(args, ["shell", command])
    if args.wait > 0:
        time.sleep(args.wait)
    return {"target": target, "bundle": args.bundle, "ability": args.ability, "stdout": result.stdout.strip()}


def cmd_stop_app(args: argparse.Namespace) -> dict[str, Any]:
    target = ensure_target(args)
    result = run_hdc(args, ["shell", f"aa force-stop {shell_quote(args.bundle)}"], check=False)
    return {"target": target, "bundle": args.bundle, "stdout": result.stdout.strip(), "stderr": result.stderr.strip()}


def cmd_get_app_state(args: argparse.Namespace) -> dict[str, Any]:
    screenshot_result = cmd_screenshot(args)
    layout_result: dict[str, Any] | None = None
    if args.dump_layout:
        try:
            layout_result = cmd_dump_layout(args)
        except CliError as exc:
            layout_result = {"ok": False, "error": str(exc)}
    return {"target": screenshot_result["target"], "screenshot_path": screenshot_result["path"], "layout": layout_result}


def cmd_screenshot(args: argparse.Namespace) -> dict[str, Any]:
    target = ensure_target(args)
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    local_path = out_dir / f"{args.prefix}-{timestamp}.png"
    remote_path = f"/data/local/tmp/slock-{args.prefix}-{timestamp}.png"
    command = f"snapshot_display -f {shell_quote(remote_path)}"
    result = run_hdc(args, ["shell", command], check=False)
    used_command = command
    if result.returncode != 0:
        used_command = f"snapshot_display -i 0 -f {shell_quote(remote_path)}"
        result = run_hdc(args, ["shell", used_command], check=False)
    if result.returncode != 0:
        raise CliError(f"screenshot failed: {result.stdout}{result.stderr}".strip())
    run_hdc(args, ["file", "recv", remote_path, str(local_path)])
    run_hdc(args, ["shell", f"rm -f {shell_quote(remote_path)}"], check=False)
    if not local_path.exists() or local_path.stat().st_size == 0:
        raise CliError(f"screenshot pull did not produce a file: {local_path}")
    return {"target": target, "path": str(local_path), "command": used_command}


def cmd_dump_layout(args: argparse.Namespace) -> dict[str, Any]:
    target = ensure_target(args)
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    local_path = out_dir / f"{args.prefix}-{timestamp}-layout.json"
    remote_path = f"/data/local/tmp/slock-{args.prefix}-{timestamp}-layout.json"
    command = f"uitest dumpLayout -p {shell_quote(remote_path)}"
    result = run_hdc(args, ["shell", command], check=False)
    if result.returncode != 0:
        raise CliError(
            "uitest dumpLayout failed. Run `input-help` on this target to confirm supported UiTest commands: "
            f"{result.stdout}{result.stderr}".strip()
        )
    run_hdc(args, ["file", "recv", remote_path, str(local_path)])
    run_hdc(args, ["shell", f"rm -f {shell_quote(remote_path)}"], check=False)
    return {"target": target, "path": str(local_path), "command": command}


def cmd_click(args: argparse.Namespace) -> dict[str, Any]:
    target = ensure_target(args)
    command = f"uitest uiInput click {args.x} {args.y}"
    result = run_hdc(args, ["shell", command], check=False)
    if result.returncode != 0:
        raise CliError(f"click failed; confirm `uitest uiInput` support with input-help: {result.stdout}{result.stderr}".strip())
    return {"target": target, "x": args.x, "y": args.y, "command": command}


def cmd_swipe(args: argparse.Namespace) -> dict[str, Any]:
    target = ensure_target(args)
    x1, y1, x2, y2 = resolve_swipe(args)
    command = f"uitest uiInput swipe {x1} {y1} {x2} {y2} {args.duration_ms}"
    result = run_hdc(args, ["shell", command], check=False)
    if result.returncode != 0:
        raise CliError(f"swipe failed; confirm `uitest uiInput` support with input-help: {result.stdout}{result.stderr}".strip())
    return {"target": target, "x1": x1, "y1": y1, "x2": x2, "y2": y2, "duration_ms": args.duration_ms, "command": command}


def cmd_press_key(args: argparse.Namespace) -> dict[str, Any]:
    target = ensure_target(args)
    command = f"uitest uiInput keyEvent {shell_quote(args.key)}"
    result = run_hdc(args, ["shell", command], check=False)
    if result.returncode != 0:
        raise CliError(f"press-key failed; confirm supported key names with input-help: {result.stdout}{result.stderr}".strip())
    return {"target": target, "key": args.key, "command": command}


def cmd_input_help(args: argparse.Namespace) -> dict[str, Any]:
    target = ensure_target(args)
    result = run_hdc(args, ["shell", "uitest --help"], check=False)
    ui_input = run_hdc(args, ["shell", "uitest uiInput --help"], check=False)
    return {
        "target": target,
        "uitest_help": result.stdout.strip() or result.stderr.strip(),
        "ui_input_help": ui_input.stdout.strip() or ui_input.stderr.strip(),
    }


def resolve_swipe(args: argparse.Namespace) -> tuple[int, int, int, int]:
    if args.direction:
        # Conservative phone defaults; pass explicit coordinates for tablets/foldables.
        center_x = 540
        center_y = 1200
        distance = 520
        if args.direction == "up":
            return center_x, center_y + distance // 2, center_x, center_y - distance // 2
        if args.direction == "down":
            return center_x, center_y - distance // 2, center_x, center_y + distance // 2
        if args.direction == "left":
            return center_x + distance // 2, center_y, center_x - distance // 2, center_y
        return center_x - distance // 2, center_y, center_x + distance // 2, center_y
    values = (args.x1, args.y1, args.x2, args.y2)
    if any(value is None for value in values):
        raise CliError("swipe requires --direction or all of --x1 --y1 --x2 --y2")
    return int(args.x1), int(args.y1), int(args.x2), int(args.y2)


def ensure_target(args: argparse.Namespace) -> str:
    if args.target:
        return args.target
    result = run_hdc(args, ["list", "targets"], target=False)
    for line in result.stdout.splitlines():
        value = line.strip()
        if value and value != "[Empty]":
            args.target = value.split()[0]
            return args.target
    raise CliError("no OHOS target connected; start a device/emulator or pass --target")


def run_hdc(args: argparse.Namespace, hdc_args: list[str], *, target: bool = True, check: bool = True) -> subprocess.CompletedProcess[str]:
    hdc = Path(args.hdc)
    if not hdc.exists():
        raise CliError(f"hdc not found: {hdc}")
    command = [str(hdc)]
    if target:
        command.extend(["-t", ensure_target(args)])
    command.extend(hdc_args)
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if check and result.returncode != 0:
        raise CliError(f"hdc {' '.join(hdc_args)} failed ({result.returncode}): {result.stdout}{result.stderr}".strip())
    return result


def shell_quote(value: str) -> str:
    return "'" + value.replace("'", "'\\''") + "'"


def print_json(payload: dict[str, Any], *, pretty: bool) -> None:
    print(json.dumps(payload, indent=2 if pretty else None, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    sys.exit(main())
