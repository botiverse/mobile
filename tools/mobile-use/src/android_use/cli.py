from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from device_use.remote import RemoteHost
from device_use.runner import DeviceUseError

from .adb import AndroidDevice, AndroidUseError
from .config import DEFAULT_DIAGNOSTICS_ACTION, DEFAULT_DIAGNOSTICS_COMPONENT, AndroidConfig
from .logcat import analyze_logcat_file


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if not hasattr(args, "func"):
        parser.print_help()
        return 2
    config = AndroidConfig.from_defaults(
        adb=args.adb,
        device=args.device,
        package=args.package,
        main_component=args.main_component,
        kuikly_component=args.kuikly_component,
    )
    try:
        payload = args.func(args, config)
    except (AndroidUseError, DeviceUseError) as exc:
        print_payload({"ok": False, "error": str(exc)}, pretty=args.pretty)
        return 1
    print_payload({"ok": True, **payload}, pretty=args.pretty)
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="android-use", description="Android adb automation CLI for Raft/Slock.")
    parser.add_argument("--adb", default=None, help="adb executable path")
    parser.add_argument("--device", default=None, help="adb serial")
    parser.add_argument("--package", default=None, help="Android package name")
    parser.add_argument("--main-component", default=None)
    parser.add_argument("--kuikly-component", default=None)
    parser.add_argument("--pretty", action="store_true", help="Pretty-print JSON")
    sub = parser.add_subparsers(dest="command")

    add(sub, "doctor", cmd_doctor, "Inspect adb setup")
    add(sub, "devices", cmd_devices, "List connected adb devices")

    remote_run = add(sub, "remote-run", cmd_remote_run, "Run a command on a remote host through ssh")
    remote_run.add_argument("host")
    remote_run.add_argument("command", nargs=argparse.REMAINDER)

    remote_devices = add(sub, "remote-devices", cmd_remote_devices, "List adb devices on a remote host")
    remote_devices.add_argument("host")

    install = add(sub, "install", cmd_install, "Install an APK")
    install.add_argument("apk")
    install.add_argument("--no-reinstall", action="store_true")

    launch = add(sub, "launch", cmd_launch, "Launch the app main activity", aliases=["start-app", "start_app"])
    launch.add_argument("--component", default=None)
    launch.add_argument("--wait", action=argparse.BooleanOptionalAction, default=True)

    stop = add(sub, "stop", cmd_stop, "Force-stop a package")
    stop.add_argument("--package", default=None)

    run_app = add(sub, "run-app", cmd_run_app, "Install APK, stop, and launch")
    run_app.add_argument("apk")
    run_app.add_argument("--component", default=None)

    intent = add(sub, "start-intent", cmd_start_intent, "Run adb shell am start", aliases=["start_intent"])
    intent.add_argument("--action", "-a")
    intent.add_argument("--component", "-n")
    intent.add_argument("--category", action="append", default=[])
    intent.add_argument("--string-extra", action="append", default=[], metavar="KEY=VALUE")
    intent.add_argument("--bool-extra", action="append", default=[], metavar="KEY=BOOL")
    intent.add_argument("--long-extra", action="append", default=[], metavar="KEY=LONG")
    intent.add_argument("--int-extra", action="append", default=[], metavar="KEY=INT")
    intent.add_argument("--wait", action=argparse.BooleanOptionalAction, default=True)

    page = add(sub, "open-page", cmd_open_page, "Open a Kuikly page", aliases=["open_page"])
    page.add_argument("page_name")
    page.add_argument("--page-data", default="{}")
    page.add_argument("--component", default=None)
    page.add_argument("--suppress-soft-keyboard", action=argparse.BooleanOptionalAction, default=True)
    page.add_argument("--wait", action=argparse.BooleanOptionalAction, default=True)

    diagnostics = add(sub, "open-diagnostics", cmd_open_diagnostics, "Open diagnostics deep link", aliases=["open_diagnostics"])
    diagnostics.add_argument("--component", default=DEFAULT_DIAGNOSTICS_COMPONENT)
    diagnostics.add_argument("--action", default=DEFAULT_DIAGNOSTICS_ACTION)
    diagnostics.add_argument("--auto-close-ms", type=int, default=0)
    diagnostics.add_argument("--wait", action=argparse.BooleanOptionalAction, default=True)

    state = add(sub, "state", cmd_state, "Capture screenshot and uiautomator XML", aliases=["get-app-state", "get_app_state"])
    add_artifact_args(state)

    screenshot = add(sub, "screenshot", cmd_screenshot, "Capture screenshot")
    add_artifact_args(screenshot)

    layout = add(sub, "dump-layout", cmd_dump_layout, "Dump uiautomator XML")
    add_artifact_args(layout)

    tap = add(sub, "tap", cmd_tap, "Tap coordinates", aliases=["click"])
    tap.add_argument("x", type=int)
    tap.add_argument("y", type=int)

    key = add(sub, "key", cmd_key, "Send Android keyevent", aliases=["press-key", "press_key"])
    key.add_argument("key_name")

    swipe = add(sub, "swipe", cmd_swipe, "Swipe coordinates or direction", aliases=["scroll"])
    swipe.add_argument("--direction", choices=["up", "down", "left", "right"])
    swipe.add_argument("--x1", type=int)
    swipe.add_argument("--y1", type=int)
    swipe.add_argument("--x2", type=int)
    swipe.add_argument("--y2", type=int)
    swipe.add_argument("--duration-ms", type=int, default=350)

    logcat = add(sub, "logcat", cmd_logcat, "Capture logcat", aliases=["capture-logcat", "capture_logcat"])
    add_artifact_args(logcat)
    logcat.add_argument("--clear-first", action="store_true")
    logcat.add_argument("--duration", type=float, default=0.0)
    logcat.add_argument("--duration-seconds", type=float, dest="duration")
    logcat.add_argument("--filter-regex", default=None)

    analyze = add(sub, "analyze-log", cmd_analyze_log, "Analyze an exported logcat file")
    analyze.add_argument("path")
    analyze.add_argument("--trim-last-start", action="store_true", help="Analyze only from the last detected app launch")
    analyze.add_argument("--trim-out", default=None, help="Write the last-start log window to this path")
    return parser


def add(
    sub: argparse._SubParsersAction[argparse.ArgumentParser],
    name: str,
    func: Any,
    help_text: str,
    *,
    aliases: list[str] | None = None,
) -> argparse.ArgumentParser:
    parser = sub.add_parser(name, aliases=aliases or [], help=help_text)
    parser.add_argument("--pretty", action="store_true", help=argparse.SUPPRESS)
    parser.set_defaults(func=func)
    return parser


def add_artifact_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--out", default="/tmp/android-use")
    parser.add_argument("--prefix", default="state")


def cmd_doctor(args: argparse.Namespace, config: AndroidConfig) -> dict[str, object]:
    return {"tools": config.tool_status()}


def cmd_devices(args: argparse.Namespace, config: AndroidConfig) -> dict[str, object]:
    return {"devices": AndroidDevice(config).devices()}


def cmd_remote_run(args: argparse.Namespace, config: AndroidConfig) -> dict[str, object]:
    command = list(args.command)
    if command and command[0] == "--":
        command = command[1:]
    if not command:
        raise AndroidUseError("remote-run requires a command after the host, usually after --")
    result = RemoteHost(args.host).run(command)
    return {"host": args.host, "result": result.as_dict()}


def cmd_remote_devices(args: argparse.Namespace, config: AndroidConfig) -> dict[str, object]:
    result = RemoteHost(args.host).run([config.adb, "devices"])
    return {"host": args.host, "result": result.as_dict()}


def cmd_install(args: argparse.Namespace, config: AndroidConfig) -> dict[str, object]:
    result = AndroidDevice(config).install(Path(args.apk).expanduser(), reinstall=not args.no_reinstall)
    return {"result": result.as_dict()}


def cmd_launch(args: argparse.Namespace, config: AndroidConfig) -> dict[str, object]:
    result = AndroidDevice(config).launch(component=args.component, wait=args.wait)
    return {"component": args.component or config.main_component, "result": result.as_dict()}


def cmd_stop(args: argparse.Namespace, config: AndroidConfig) -> dict[str, object]:
    result = AndroidDevice(config).stop(package=args.package)
    return {"package": args.package or config.package, "result": result.as_dict()}


def cmd_run_app(args: argparse.Namespace, config: AndroidConfig) -> dict[str, object]:
    device = AndroidDevice(config)
    install = device.install(Path(args.apk).expanduser()).as_dict()
    stop = device.stop().as_dict()
    launch = device.launch(component=args.component).as_dict()
    return {"install": install, "stop": stop, "launch": launch}


def cmd_start_intent(args: argparse.Namespace, config: AndroidConfig) -> dict[str, object]:
    extras = collect_extras(args)
    result = AndroidDevice(config).start_intent(
        action=args.action,
        component=args.component,
        categories=args.category,
        extras=extras,
        wait=args.wait,
    )
    return {"result": result.as_dict(), "extras": extras}


def cmd_open_page(args: argparse.Namespace, config: AndroidConfig) -> dict[str, object]:
    result = AndroidDevice(config).open_page(
        args.page_name,
        page_data=parse_json_object(args.page_data, "--page-data"),
        component=args.component,
        suppress_soft_keyboard=args.suppress_soft_keyboard,
        wait=args.wait,
    )
    return {"page_name": args.page_name, "result": result.as_dict()}


def cmd_open_diagnostics(args: argparse.Namespace, config: AndroidConfig) -> dict[str, object]:
    result = AndroidDevice(config).open_diagnostics(
        component=args.component,
        action=args.action,
        auto_close_ms=args.auto_close_ms,
        wait=args.wait,
    )
    return {"result": result.as_dict()}


def cmd_state(args: argparse.Namespace, config: AndroidConfig) -> dict[str, object]:
    return AndroidDevice(config).state(out=Path(args.out).expanduser(), prefix=args.prefix)


def cmd_screenshot(args: argparse.Namespace, config: AndroidConfig) -> dict[str, object]:
    return AndroidDevice(config).screenshot(out=Path(args.out).expanduser(), prefix=args.prefix)


def cmd_dump_layout(args: argparse.Namespace, config: AndroidConfig) -> dict[str, object]:
    return AndroidDevice(config).dump_layout(out=Path(args.out).expanduser(), prefix=args.prefix)


def cmd_tap(args: argparse.Namespace, config: AndroidConfig) -> dict[str, object]:
    result = AndroidDevice(config).tap(args.x, args.y)
    return {"x": args.x, "y": args.y, "result": result.as_dict()}


def cmd_key(args: argparse.Namespace, config: AndroidConfig) -> dict[str, object]:
    result = AndroidDevice(config).key(args.key_name)
    return {"key": args.key_name, "result": result.as_dict()}


def cmd_swipe(args: argparse.Namespace, config: AndroidConfig) -> dict[str, object]:
    device = AndroidDevice(config)
    if args.direction:
        x1, y1, x2, y2 = device.direction_to_swipe(args.direction)
    else:
        values = [args.x1, args.y1, args.x2, args.y2]
        if any(value is None for value in values):
            raise AndroidUseError("swipe requires --direction or all of --x1 --y1 --x2 --y2")
        x1, y1, x2, y2 = int(args.x1), int(args.y1), int(args.x2), int(args.y2)
    result = device.swipe(x1=x1, y1=y1, x2=x2, y2=y2, duration_ms=args.duration_ms)
    return {"x1": x1, "y1": y1, "x2": x2, "y2": y2, "duration_ms": args.duration_ms, "result": result.as_dict()}


def cmd_logcat(args: argparse.Namespace, config: AndroidConfig) -> dict[str, object]:
    return AndroidDevice(config).capture_logcat(
        out=Path(args.out).expanduser(),
        prefix=args.prefix,
        clear_first=args.clear_first,
        duration_seconds=args.duration,
        filter_regex=args.filter_regex,
    )


def cmd_analyze_log(args: argparse.Namespace, config: AndroidConfig) -> dict[str, object]:
    return analyze_logcat_file(
        Path(args.path).expanduser(),
        package=config.package,
        trim_last_start=args.trim_last_start,
        trim_out=Path(args.trim_out).expanduser() if args.trim_out else None,
    )


def collect_extras(args: argparse.Namespace) -> list[tuple[str, str, str]]:
    extras: list[tuple[str, str, str]] = []
    add_extra_args(extras, "--es", args.string_extra, str)
    add_extra_args(extras, "--ez", args.bool_extra, normalize_bool)
    add_extra_args(extras, "--el", args.long_extra, normalize_int)
    add_extra_args(extras, "--ei", args.int_extra, normalize_int)
    return extras


def add_extra_args(extras: list[tuple[str, str, str]], flag: str, values: list[str], normalizer: Any) -> None:
    for raw in values:
        key, value = split_key_value(raw)
        extras.append((flag, key, str(normalizer(value))))


def split_key_value(raw: str) -> tuple[str, str]:
    if "=" not in raw:
        raise AndroidUseError(f"expected KEY=VALUE, got: {raw}")
    key, value = raw.split("=", 1)
    if not key.strip():
        raise AndroidUseError(f"extra key is blank in: {raw}")
    return key.strip(), value


def normalize_bool(value: str) -> str:
    normalized = value.strip().lower()
    if normalized in ("1", "true", "t", "yes", "y", "on"):
        return "true"
    if normalized in ("0", "false", "f", "no", "n", "off"):
        return "false"
    raise AndroidUseError(f"expected boolean value, got: {value}")


def normalize_int(value: str) -> int:
    try:
        return int(value.strip(), 10)
    except ValueError as exc:
        raise AndroidUseError(f"expected integer value, got: {value}") from exc


def parse_json_object(value: str, label: str) -> dict[str, Any]:
    try:
        parsed = json.loads(value)
    except json.JSONDecodeError as exc:
        raise AndroidUseError(f"{label} must be a JSON object: {exc}") from exc
    if not isinstance(parsed, dict):
        raise AndroidUseError(f"{label} must be a JSON object")
    return parsed


def print_payload(payload: dict[str, object], *, pretty: bool) -> None:
    print(json.dumps(payload, ensure_ascii=False, sort_keys=True, indent=2 if pretty else None))
