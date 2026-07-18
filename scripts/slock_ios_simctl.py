#!/usr/bin/env python3
"""Small simctl/XCUITest helper for Slock iOS simulator control.

simctl covers simulator lifecycle, install, launch, and screenshots. UI input
is executed inside the checked-in XCUITest target so this helper never sends
host-global mouse or keyboard events and never foregrounds Simulator.app.
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
IOS_DIR = REPO_ROOT / "compose" / "iosApp"
DEFAULT_OUT = Path(os.environ.get("TMPDIR", "/tmp")) / "slock-ios-automation" / datetime.now().strftime("%Y%m%d-%H%M%S")
DEFAULT_BUNDLE_ID = "build.raft.app"
DEFAULT_SCHEME = "iosApp"
DEFAULT_WORKSPACE = IOS_DIR / "iosApp.xcworkspace"
DEFAULT_INTERACTION_SCHEME = "iosAppUITests"
DEFAULT_DERIVED_DATA = Path(os.environ.get("IOS_USE_DERIVED_DATA", "/tmp/ios-use-derived-data"))
MOBILE_USE_SRC = REPO_ROOT / "tools" / "mobile-use" / "src"


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
    parser = argparse.ArgumentParser(description="simctl helper for Slock iOS simulator automation.")
    parser.add_argument("--simctl", default=os.environ.get("SIMCTL", "xcrun simctl"), help="simctl command; default: xcrun simctl")
    parser.add_argument("--device", default=os.environ.get("IOS_SIMULATOR_UDID", "booted"), help="UDID or booted")
    parser.add_argument("--pretty", action="store_true", help="Pretty-print JSON output")
    sub = parser.add_subparsers(dest="command")

    devices = sub.add_parser("devices", help="List available iOS simulators")
    devices.set_defaults(func=cmd_devices)

    boot = sub.add_parser("boot", help="Boot a simulator by UDID")
    boot.add_argument("udid")
    boot.set_defaults(func=cmd_boot)

    build = sub.add_parser("build-app", aliases=["build_app"], help="Build the iOS app for a simulator destination")
    build.add_argument("--workspace", default=str(DEFAULT_WORKSPACE))
    build.add_argument("--scheme", default=DEFAULT_SCHEME)
    build.add_argument("--destination", default="generic/platform=iOS Simulator")
    build.add_argument("--configuration", default="Debug")
    build.add_argument("--derived-data-path", default=str(DEFAULT_OUT / "DerivedData"))
    build.set_defaults(func=cmd_build_app)

    install = sub.add_parser("install-app", aliases=["install_app"], help="Install an .app bundle on the simulator")
    install.add_argument("app_path")
    install.set_defaults(func=cmd_install_app)

    launch = sub.add_parser("start-app", aliases=["start_app"], help="Launch the Slock iOS app")
    launch.add_argument("--bundle-id", default=DEFAULT_BUNDLE_ID)
    launch.add_argument("--wait", type=float, default=2.0)
    launch.set_defaults(func=cmd_start_app)

    stop = sub.add_parser("stop-app", aliases=["stop_app"], help="Terminate the Slock iOS app")
    stop.add_argument("--bundle-id", default=DEFAULT_BUNDLE_ID)
    stop.set_defaults(func=cmd_stop_app)

    state = sub.add_parser("get-app-state", aliases=["get_app_state"], help="Capture screenshot and basic simulator app info")
    add_out_args(state)
    state.add_argument("--bundle-id", default=DEFAULT_BUNDLE_ID)
    state.set_defaults(func=cmd_get_app_state)

    screenshot = sub.add_parser("screenshot", help="Capture current simulator screen")
    add_out_args(screenshot)
    screenshot.set_defaults(func=cmd_screenshot)

    tap = sub.add_parser("tap", help="Tap simulator coordinates through XCUITest")
    tap.add_argument("x", type=float)
    tap.add_argument("y", type=float)
    tap.add_argument("--coordinate-space", choices=("window", "normalized", "global"), default="window")
    add_xcuitest_args(tap)
    tap.set_defaults(func=cmd_tap)

    swipe = sub.add_parser("swipe", help="Swipe between simulator coordinates through XCUITest")
    swipe.add_argument("x1", type=float)
    swipe.add_argument("y1", type=float)
    swipe.add_argument("x2", type=float)
    swipe.add_argument("y2", type=float)
    swipe.add_argument("--coordinate-space", choices=("window", "normalized", "global"), default="window")
    add_xcuitest_args(swipe)
    swipe.set_defaults(func=cmd_swipe)

    open_url = sub.add_parser("open-url", aliases=["open_url"], help="Open a URL on the simulator")
    open_url.add_argument("url")
    open_url.set_defaults(func=cmd_open_url)

    return parser


def add_out_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--out", default=str(DEFAULT_OUT), help=f"Artifact directory. Default: {DEFAULT_OUT}")
    parser.add_argument("--prefix", default="state", help="Artifact filename prefix")


def add_xcuitest_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--workspace", default=str(DEFAULT_WORKSPACE), help="Xcode workspace containing the UI test target")
    parser.add_argument("--interaction-scheme", default=DEFAULT_INTERACTION_SCHEME, help="XCUITest scheme")
    parser.add_argument("--configuration", default="Debug")
    parser.add_argument("--derived-data-path", default=str(DEFAULT_DERIVED_DATA))
    parser.add_argument("--bundle-id", default=DEFAULT_BUNDLE_ID)


def cmd_devices(args: argparse.Namespace) -> dict[str, Any]:
    result = run_simctl(args, ["list", "devices", "available", "--json"], device=False)
    payload = json.loads(result.stdout)
    devices: list[dict[str, Any]] = []
    for runtime, entries in payload.get("devices", {}).items():
        if ".iOS-" not in runtime and "iOS" not in runtime:
            continue
        for entry in entries:
            devices.append(
                {
                    "name": entry.get("name"),
                    "udid": entry.get("udid"),
                    "state": entry.get("state"),
                    "runtime": runtime,
                }
            )
    return {"devices": devices}


def cmd_boot(args: argparse.Namespace) -> dict[str, Any]:
    result = run_simctl(args, ["boot", args.udid], device=False, check=False)
    if result.returncode != 0 and "Unable to boot device in current state: Booted" not in result.stderr:
        raise CliError(f"simctl boot failed ({result.returncode}): {result.stdout}{result.stderr}".strip())
    return {"device": args.udid, "stdout": result.stdout.strip(), "stderr": result.stderr.strip()}


def cmd_build_app(args: argparse.Namespace) -> dict[str, Any]:
    command = [
        "xcodebuild",
        "-workspace",
        args.workspace,
        "-scheme",
        args.scheme,
        "-configuration",
        args.configuration,
        "-destination",
        args.destination,
        "-derivedDataPath",
        args.derived_data_path,
        "CODE_SIGNING_ALLOWED=NO",
        "build",
    ]
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode != 0:
        raise CliError(f"xcodebuild failed ({result.returncode}): {result.stdout[-4000:]}{result.stderr[-4000:]}")
    products = Path(args.derived_data_path) / "Build" / "Products"
    candidates = list(products.glob(f"{args.configuration}-iphonesimulator/*.app"))
    return {
        "workspace": args.workspace,
        "scheme": args.scheme,
        "derived_data_path": args.derived_data_path,
        "app_candidates": [str(path) for path in candidates],
    }


def cmd_install_app(args: argparse.Namespace) -> dict[str, Any]:
    result = run_simctl(args, ["install", args.device, args.app_path], device=False)
    return {"device": args.device, "app_path": args.app_path, "stdout": result.stdout.strip()}


def cmd_start_app(args: argparse.Namespace) -> dict[str, Any]:
    result = run_simctl(args, ["launch", args.device, args.bundle_id], device=False)
    if args.wait > 0:
        time.sleep(args.wait)
    return {"device": args.device, "bundle_id": args.bundle_id, "stdout": result.stdout.strip()}


def cmd_stop_app(args: argparse.Namespace) -> dict[str, Any]:
    result = run_simctl(args, ["terminate", args.device, args.bundle_id], device=False, check=False)
    return {"device": args.device, "bundle_id": args.bundle_id, "stdout": result.stdout.strip(), "stderr": result.stderr.strip()}


def cmd_get_app_state(args: argparse.Namespace) -> dict[str, Any]:
    screenshot = cmd_screenshot(args)
    appinfo = run_simctl(args, ["appinfo", args.device, args.bundle_id], device=False, check=False)
    return {
        "device": args.device,
        "screenshot_path": screenshot["path"],
        "appinfo": appinfo.stdout.strip() or appinfo.stderr.strip(),
    }


def cmd_screenshot(args: argparse.Namespace) -> dict[str, Any]:
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    path = out_dir / f"{args.prefix}-{timestamp}.png"
    run_simctl(args, ["io", args.device, "screenshot", str(path)], device=False)
    if not path.exists() or path.stat().st_size == 0:
        raise CliError(f"simctl screenshot did not produce a file: {path}")
    return {"device": args.device, "path": str(path)}


def cmd_tap(args: argparse.Namespace) -> dict[str, Any]:
    coordinate_space = xcuitest_coordinate_space(args.coordinate_space)
    action = {"type": "tap", "x": args.x, "y": args.y, "coordinateSpace": coordinate_space}
    result = run_xcuitest_actions(args, [action])
    return {**result, "x": args.x, "y": args.y, "coordinate_space": args.coordinate_space, "input_backend": "XCUITest"}


def cmd_swipe(args: argparse.Namespace) -> dict[str, Any]:
    coordinate_space = xcuitest_coordinate_space(args.coordinate_space)
    action = {
        "type": "swipe",
        "x": args.x1,
        "y": args.y1,
        "x2": args.x2,
        "y2": args.y2,
        "coordinateSpace": coordinate_space,
    }
    result = run_xcuitest_actions(args, [action])
    return {
        **result,
        "coordinate_space": args.coordinate_space,
        "start": [args.x1, args.y1],
        "end": [args.x2, args.y2],
        "input_backend": "XCUITest",
    }


def cmd_open_url(args: argparse.Namespace) -> dict[str, Any]:
    result = run_simctl(args, ["openurl", args.device, args.url], device=False)
    return {"device": args.device, "url": args.url, "stdout": result.stdout.strip()}


def xcuitest_coordinate_space(coordinate_space: str) -> str:
    if coordinate_space == "global":
        raise CliError(
            "global coordinates address the macOS desktop and are intentionally unsupported. "
            "Use --coordinate-space window (app points) or normalized (0.0 to 1.0); UI input runs only inside XCUITest."
        )
    return "points" if coordinate_space == "window" else "normalized"


def run_xcuitest_actions(args: argparse.Namespace, actions: list[dict[str, Any]]) -> dict[str, Any]:
    if str(MOBILE_USE_SRC) not in sys.path:
        sys.path.insert(0, str(MOBILE_USE_SRC))
    try:
        from ios_use.config import IosConfig
        from ios_use.simctl import IosSimulator, IosUseError
    except ImportError as exc:
        raise CliError(f"unable to load the checked-in ios-use XCUITest harness: {exc}") from exc

    device = resolve_xcuitest_device(args)
    config = IosConfig.from_defaults(
        device=device,
        bundle=args.bundle_id,
        workspace=args.workspace,
        configuration=args.configuration,
        derived_data=args.derived_data_path,
    )
    try:
        return IosSimulator(config).interact(
            actions,
            workspace=Path(args.workspace).expanduser(),
            interaction_scheme=args.interaction_scheme,
            configuration=args.configuration,
            derived_data=Path(args.derived_data_path).expanduser(),
            bundle=args.bundle_id,
            device=device,
        )
    except IosUseError as exc:
        raise CliError(f"XCUITest input failed: {exc}") from exc


def resolve_xcuitest_device(args: argparse.Namespace) -> str:
    if args.device != "booted":
        return args.device
    result = run_simctl(args, ["list", "devices", "booted", "--json"], device=False)
    payload = json.loads(result.stdout)
    booted = [
        entry.get("udid")
        for entries in payload.get("devices", {}).values()
        for entry in entries
        if entry.get("state") == "Booted" and entry.get("isAvailable", True) and entry.get("udid")
    ]
    if len(booted) != 1:
        raise CliError(
            f"XCUITest input requires one concrete booted simulator UDID; found {len(booted)}. "
            "Pass --device <UDID>."
        )
    return str(booted[0])


def run_simctl(
    args: argparse.Namespace,
    simctl_args: list[str],
    *,
    device: bool = True,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    command = args.simctl.split()
    if device:
        command.append(args.device)
    command.extend(simctl_args)
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if check and result.returncode != 0:
        raise CliError(f"{' '.join(command)} failed ({result.returncode}): {result.stdout}{result.stderr}".strip())
    return result


def print_json(payload: dict[str, Any], *, pretty: bool) -> None:
    print(json.dumps(payload, indent=2 if pretty else None, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    sys.exit(main())
