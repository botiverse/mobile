from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from device_use.remote import RemoteHost
from device_use.runner import DeviceUseError

from .config import IosConfig
from .log import analyze_ios_log_file
from .simctl import IosSimulator, IosUseError


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if not hasattr(args, "func"):
        parser.print_help()
        return 2
    config = IosConfig.from_defaults(
        xcrun=args.xcrun,
        xcodebuild=args.xcodebuild,
        device=args.device,
        bundle=args.bundle,
        workspace=args.workspace,
        scheme=args.scheme,
        configuration=args.configuration,
        sdk=args.sdk,
        derived_data=args.derived_data,
    )
    try:
        payload = args.func(args, config)
    except (IosUseError, DeviceUseError) as exc:
        print_payload({"ok": False, "error": str(exc)}, pretty=args.pretty)
        return 1
    print_payload({"ok": True, **payload}, pretty=args.pretty)
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="ios-use", description="iOS simulator/device automation CLI for Raft/Slock.")
    parser.add_argument("--xcrun", default=None)
    parser.add_argument("--xcodebuild", default=None)
    parser.add_argument("--device", default=None, help="simctl device UDID; defaults to booted/first available simulator")
    parser.add_argument("--bundle", default=None, help="iOS bundle identifier")
    parser.add_argument("--workspace", default=None)
    parser.add_argument("--scheme", default=None)
    parser.add_argument("--configuration", default=None)
    parser.add_argument("--sdk", default=None)
    parser.add_argument("--derived-data", default=None)
    parser.add_argument("--pretty", action="store_true", help="Pretty-print JSON")
    parser.add_argument(
        "--driver",
        default=None,
        choices=["agent-device", "qa-loop"],
        help="UI driver for tap/tap-text/swipe/interact (default agent-device; qa-loop is the old XCTest file protocol)",
    )
    parser.add_argument("--agent-device-bin", default=None, help="agent-device executable; also AGENT_DEVICE_BIN")
    sub = parser.add_subparsers(dest="command")

    add(sub, "doctor", cmd_doctor, "Inspect Xcode/simctl setup")
    add(sub, "devices", cmd_devices, "List simulators through simctl")

    remote_run = add(sub, "remote-run", cmd_remote_run, "Run a command on a remote host through ssh")
    remote_run.add_argument("host")
    remote_run.add_argument("command", nargs=argparse.REMAINDER)

    remote_devices = add(sub, "remote-devices", cmd_remote_devices, "List iOS simulators on a remote host")
    remote_devices.add_argument("host")

    boot = add(sub, "boot", cmd_boot, "Boot a simulator")
    boot.add_argument("--device", default=None)

    shutdown = add(sub, "shutdown", cmd_shutdown, "Shutdown a simulator")
    shutdown.add_argument("--device", default=None)

    build = add(sub, "build", cmd_build, "Build the iOS app with xcodebuild")
    build.add_argument("--workspace", default=None)
    build.add_argument("--scheme", default=None)
    build.add_argument("--configuration", default=None)
    build.add_argument("--sdk", default=None)
    build.add_argument("--derived-data", default=None)

    install = add(sub, "install", cmd_install, "Install an .app bundle to a simulator")
    install.add_argument("--app", default=None, help="Defaults to derived-data Build/Products path")
    install.add_argument("--device", default=None)

    launch = add(sub, "launch", cmd_launch, "Launch the iOS app")
    launch.add_argument("--bundle", default=None)
    launch.add_argument("--device", default=None)
    launch.add_argument("args", nargs=argparse.REMAINDER)

    terminate = add(sub, "terminate", cmd_terminate, "Terminate the iOS app")
    terminate.add_argument("--bundle", default=None)
    terminate.add_argument("--device", default=None)

    run_app = add(sub, "run-app", cmd_run_app, "Build, install, and launch")
    run_app.add_argument("--app", default=None)

    open_url = add(sub, "open-url", cmd_open_url, "Open a URL on the simulator")
    open_url.add_argument("url")
    open_url.add_argument("--device", default=None)

    screenshot = add(sub, "screenshot", cmd_screenshot, "Capture simulator screenshot")
    add_artifact_args(screenshot)
    screenshot.add_argument("--device", default=None)

    interact = add(sub, "interact", cmd_interact, "Drive UI actions through agent-device (default) or --driver qa-loop")
    add_interaction_args(interact)
    interact.add_argument("--actions", default=None, help="JSON array of ios-use actions")
    interact.add_argument("--actions-file", default=None, help="Path to a JSON action array")

    tap_text = add(sub, "tap-text", cmd_tap_text, "Tap an element by accessibility label or visible text")
    add_interaction_args(tap_text)
    tap_text.add_argument("text")

    tap = add(sub, "tap", cmd_tap, "Tap normalized screen coordinates")
    add_interaction_args(tap)
    tap.add_argument("x", type=float, help="Normalized x coordinate, 0.0 to 1.0")
    tap.add_argument("y", type=float, help="Normalized y coordinate, 0.0 to 1.0")

    swipe = add(sub, "swipe", cmd_swipe, "Swipe in a direction")
    add_interaction_args(swipe)
    swipe.add_argument("--direction", required=True, choices=["up", "down", "left", "right"])

    log = add(sub, "log", cmd_log, "Capture simulator log stream for a duration")
    add_artifact_args(log)
    log.add_argument("--device", default=None)
    log.add_argument("--predicate", default=None)
    log.add_argument("--duration", type=float, default=10.0)

    analyze = add(sub, "analyze-log", cmd_analyze_log, "Analyze exported iOS log")
    analyze.add_argument("path")
    return parser


def add(sub: argparse._SubParsersAction[argparse.ArgumentParser], name: str, func: Any, help_text: str) -> argparse.ArgumentParser:
    parser = sub.add_parser(name, help=help_text)
    parser.add_argument("--pretty", action="store_true", help=argparse.SUPPRESS)
    parser.set_defaults(func=func)
    return parser


def add_artifact_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--out", default="/tmp/ios-use")
    parser.add_argument("--prefix", default="state")


def add_interaction_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--workspace", default=argparse.SUPPRESS)
    parser.add_argument("--interaction-scheme", default=argparse.SUPPRESS)
    parser.add_argument("--configuration", default=argparse.SUPPRESS)
    parser.add_argument("--derived-data", default=argparse.SUPPRESS)
    parser.add_argument("--bundle", default=argparse.SUPPRESS)
    parser.add_argument("--device", default=argparse.SUPPRESS)
    parser.add_argument("--driver", default=argparse.SUPPRESS, choices=["agent-device", "qa-loop"])
    parser.add_argument("--agent-device-bin", default=argparse.SUPPRESS)


def cmd_doctor(args: argparse.Namespace, config: IosConfig) -> dict[str, object]:
    from .agent_device import tool_status as agent_device_status

    tools = config.tool_status()
    explicit = getattr(args, "agent_device_bin", None)
    if explicit:
        tools["agent_device"] = agent_device_status(explicit)
    return {"tools": tools}


def cmd_devices(args: argparse.Namespace, config: IosConfig) -> dict[str, object]:
    return IosSimulator(config).devices()


def cmd_remote_run(args: argparse.Namespace, config: IosConfig) -> dict[str, object]:
    command = list(args.command)
    if command and command[0] == "--":
        command = command[1:]
    if not command:
        raise IosUseError("remote-run requires a command after the host, usually after --")
    result = RemoteHost(args.host).run(command)
    return {"host": args.host, "result": result.as_dict()}


def cmd_remote_devices(args: argparse.Namespace, config: IosConfig) -> dict[str, object]:
    result = RemoteHost(args.host).run([config.xcrun, "simctl", "list", "devices", "--json"])
    return {"host": args.host, "result": result.as_dict()}


def cmd_boot(args: argparse.Namespace, config: IosConfig) -> dict[str, object]:
    result = IosSimulator(config).boot(device=args.device)
    return {"device": args.device or config.device, "result": result.as_dict()}


def cmd_shutdown(args: argparse.Namespace, config: IosConfig) -> dict[str, object]:
    result = IosSimulator(config).shutdown(device=args.device)
    return {"device": args.device or config.device, "result": result.as_dict()}


def cmd_build(args: argparse.Namespace, config: IosConfig) -> dict[str, object]:
    result = IosSimulator(config).build(
        workspace=Path(args.workspace).expanduser() if args.workspace else None,
        scheme=args.scheme,
        configuration=args.configuration,
        sdk=args.sdk,
        derived_data=Path(args.derived_data).expanduser() if args.derived_data else None,
    )
    return {"result": result.as_dict()}


def cmd_install(args: argparse.Namespace, config: IosConfig) -> dict[str, object]:
    simulator = IosSimulator(config)
    app = Path(args.app).expanduser() if args.app else simulator.default_app_path()
    result = simulator.install(app, device=args.device)
    return {"app": str(app), "result": result.as_dict()}


def cmd_launch(args: argparse.Namespace, config: IosConfig) -> dict[str, object]:
    launch_args = list(args.args)
    if launch_args and launch_args[0] == "--":
        launch_args = launch_args[1:]
    result = IosSimulator(config).launch(bundle=args.bundle, device=args.device, args=launch_args)
    return {"bundle": args.bundle or config.bundle, "args": launch_args, "result": result.as_dict()}


def cmd_terminate(args: argparse.Namespace, config: IosConfig) -> dict[str, object]:
    result = IosSimulator(config).terminate(bundle=args.bundle, device=args.device)
    return {"bundle": args.bundle or config.bundle, "result": result.as_dict()}


def cmd_run_app(args: argparse.Namespace, config: IosConfig) -> dict[str, object]:
    simulator = IosSimulator(config)
    build = simulator.build().as_dict()
    app = Path(args.app).expanduser() if args.app else simulator.default_app_path()
    install = simulator.install(app).as_dict()
    launch = simulator.launch().as_dict()
    return {"build": build, "app": str(app), "install": install, "launch": launch}


def cmd_open_url(args: argparse.Namespace, config: IosConfig) -> dict[str, object]:
    result = IosSimulator(config).open_url(args.url, device=args.device)
    return {"url": args.url, "result": result.as_dict()}


def cmd_screenshot(args: argparse.Namespace, config: IosConfig) -> dict[str, object]:
    return IosSimulator(config).screenshot(out=Path(args.out).expanduser(), prefix=args.prefix, device=args.device)


def cmd_interact(args: argparse.Namespace, config: IosConfig) -> dict[str, object]:
    raw = read_actions(args.actions, args.actions_file)
    return run_interaction(args, config, raw)


def cmd_tap_text(args: argparse.Namespace, config: IosConfig) -> dict[str, object]:
    return run_interaction(args, config, [{"type": "tapText", "text": args.text}])


def cmd_tap(args: argparse.Namespace, config: IosConfig) -> dict[str, object]:
    if not (0.0 <= args.x <= 1.0 and 0.0 <= args.y <= 1.0):
        raise IosUseError("tap coordinates are normalized and must be between 0.0 and 1.0")
    return run_interaction(args, config, [{"type": "tap", "x": args.x, "y": args.y}])


def cmd_swipe(args: argparse.Namespace, config: IosConfig) -> dict[str, object]:
    return run_interaction(args, config, [{"type": "swipe", "direction": args.direction}])


def run_interaction(args: argparse.Namespace, config: IosConfig, actions: list[dict[str, Any]]) -> dict[str, object]:
    return IosSimulator(config).interact(
        actions,
        workspace=Path(args.workspace).expanduser() if getattr(args, "workspace", None) else None,
        interaction_scheme=getattr(args, "interaction_scheme", "iosAppUITests"),
        configuration=getattr(args, "configuration", None),
        derived_data=Path(args.derived_data).expanduser() if getattr(args, "derived_data", None) else None,
        bundle=getattr(args, "bundle", None),
        device=getattr(args, "device", None),
        driver=getattr(args, "driver", None),
        agent_device_bin=getattr(args, "agent_device_bin", None),
    )


def read_actions(raw: str | None, path: str | None) -> list[dict[str, Any]]:
    if raw and path:
        raise IosUseError("pass only one of --actions or --actions-file")
    if path:
        raw = Path(path).expanduser().read_text(encoding="utf-8")
    if not raw:
        raise IosUseError("interact requires --actions or --actions-file")
    try:
        actions = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise IosUseError(f"invalid actions JSON: {exc}") from exc
    if not isinstance(actions, list) or not all(isinstance(action, dict) for action in actions):
        raise IosUseError("actions JSON must be an array of objects")
    return actions


def cmd_log(args: argparse.Namespace, config: IosConfig) -> dict[str, object]:
    return IosSimulator(config).capture_log(
        out=Path(args.out).expanduser(),
        prefix=args.prefix,
        device=args.device,
        predicate=args.predicate,
        duration=args.duration,
    )


def cmd_analyze_log(args: argparse.Namespace, config: IosConfig) -> dict[str, object]:
    return analyze_ios_log_file(Path(args.path).expanduser())


def print_payload(payload: dict[str, object], *, pretty: bool) -> None:
    print(json.dumps(payload, ensure_ascii=False, sort_keys=True, indent=2 if pretty else None))
