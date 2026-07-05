from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from .config import DEFAULT_ABILITY, DEFAULT_BUNDLE, DEFAULT_MODULE, DEFAULT_PRODUCT, HarmonyConfig
from .deveco import assemble_hap, default_hap_path, run_hvigor_sync, run_ohpm_install
from .emulator import HarmonyEmulator
from .hdc import HarmonyDevice, direction_to_swipe
from .hilog import analyze_hilog_file, capture_hilog
from .remote import RemoteHost
from .runner import HarmonyUseError


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if not hasattr(args, "func"):
        parser.print_help()
        return 2
    config = HarmonyConfig.from_defaults(
        deveco_home=args.deveco_home,
        sdk_home=args.sdk_home,
        project=args.project,
        target=args.target,
    )
    try:
        payload = args.func(args, config)
    except HarmonyUseError as exc:
        print_payload({"ok": False, "error": str(exc)}, pretty=args.pretty)
        return 1
    print_payload({"ok": True, **payload}, pretty=args.pretty)
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="harmony-use", description="HarmonyOS/OHOS device automation CLI.")
    parser.add_argument("--deveco-home", default=None, help="DevEco Studio Contents directory")
    parser.add_argument("--sdk-home", default=None, help="DevEco SDK directory")
    parser.add_argument("--project", default=None, help="OHOS app project directory")
    parser.add_argument("--target", default=None, help="HDC target id")
    parser.add_argument("--pretty", action="store_true", help="Pretty-print JSON")
    sub = parser.add_subparsers(dest="command")

    add(sub, "doctor", cmd_doctor, "Inspect local DevEco/HDC/Hvigor setup")
    add(sub, "env", cmd_env, "Print shell exports for DevEco tools")
    add(sub, "devices", cmd_devices, "List connected HDC targets")

    connect = add(sub, "connect", cmd_connect, "Connect a remote HDC target, e.g. 192.168.50.129:5555")
    connect.add_argument("endpoint")

    emulator_list = add(sub, "emulator-list", cmd_emulator_list, "List DevEco emulator instances")
    emulator_list.add_argument("--details", action="store_true")

    emulator_start = add(sub, "emulator-start", cmd_emulator_start, "Start a DevEco emulator instance")
    emulator_start.add_argument("name")
    emulator_start.add_argument("--instance-path", default=None)
    emulator_start.add_argument("--image-root", default=None)
    emulator_start.add_argument("--bootmode", default=None, choices=["coldboot", "coldboot_no_save", "reset", "reset_no_save", "snapshot"])
    emulator_start.add_argument("--hdc-port", type=int, default=None)

    emulator_stop = add(sub, "emulator-stop", cmd_emulator_stop, "Stop a DevEco emulator instance")
    emulator_stop.add_argument("name")

    emulator_license = add(sub, "emulator-license", cmd_emulator_license, "Show or accept DevEco emulator license")
    emulator_license.add_argument("--accept", action="store_true")

    remote_run = add(sub, "remote-run", cmd_remote_run, "Run a command on a remote host through ssh")
    remote_run.add_argument("host")
    remote_run.add_argument("command", nargs=argparse.REMAINDER, help="Command after --, e.g. -- hdc list targets")

    remote_devices = add(sub, "remote-devices", cmd_remote_devices, "List HDC targets from a remote host through ssh")
    remote_devices.add_argument("host")

    remote_connect = add(sub, "remote-connect", cmd_remote_connect, "Ask a remote host to connect an HDC target")
    remote_connect.add_argument("host")
    remote_connect.add_argument("endpoint")

    build = add(sub, "build-hap", cmd_build_hap, "Run ohpm install, hvigor sync, and assembleHap")
    add_build_args(build)

    install = add(sub, "install", cmd_install, "Install a signed HAP")
    install.add_argument("--hap", default=None, help="HAP path; defaults to entry-default-signed.hap under --project")
    install.add_argument("--bundle", default=DEFAULT_BUNDLE)

    launch = add(sub, "launch", cmd_launch, "Launch an ability")
    launch.add_argument("--bundle", default=DEFAULT_BUNDLE)
    launch.add_argument("--ability", default=DEFAULT_ABILITY)
    launch.add_argument("--wait", type=float, default=1.0)

    stop = add(sub, "stop", cmd_stop, "Force-stop a bundle")
    stop.add_argument("--bundle", default=DEFAULT_BUNDLE)

    run_app = add(sub, "run-app", cmd_run_app, "Build, install, and launch")
    add_build_args(run_app)
    run_app.add_argument("--bundle", default=DEFAULT_BUNDLE)
    run_app.add_argument("--ability", default=DEFAULT_ABILITY)

    state = add(sub, "state", cmd_state, "Capture screenshot and layout")
    add_artifact_args(state)

    screenshot = add(sub, "screenshot", cmd_screenshot, "Capture screenshot")
    add_artifact_args(screenshot)

    layout = add(sub, "dump-layout", cmd_dump_layout, "Dump UI layout JSON")
    add_artifact_args(layout)

    tap = add(sub, "tap", cmd_tap, "Tap coordinates")
    tap.add_argument("x", type=int)
    tap.add_argument("y", type=int)

    swipe = add(sub, "swipe", cmd_swipe, "Swipe coordinates or direction")
    swipe.add_argument("--direction", choices=["up", "down", "left", "right"])
    swipe.add_argument("--x1", type=int)
    swipe.add_argument("--y1", type=int)
    swipe.add_argument("--x2", type=int)
    swipe.add_argument("--y2", type=int)
    swipe.add_argument("--duration-ms", type=int, default=350)

    key = add(sub, "key", cmd_key, "Send a key event")
    key.add_argument("key_name")

    hilog = add(sub, "hilog", cmd_hilog, "Capture/filter HiLog")
    add_artifact_args(hilog)
    hilog.add_argument("--bundle", default=None)
    hilog.add_argument("--tag", default=None)
    hilog.add_argument("--contains", default=None)
    hilog.add_argument("--duration", type=float, default=10.0)

    analyze = add(sub, "analyze-log", cmd_analyze_log, "Analyze an exported HiLog file")
    analyze.add_argument("path")

    return parser


def add(sub: argparse._SubParsersAction[argparse.ArgumentParser], name: str, func: Any, help_text: str) -> argparse.ArgumentParser:
    parser = sub.add_parser(name, help=help_text)
    parser.add_argument("--pretty", action="store_true", help=argparse.SUPPRESS)
    parser.set_defaults(func=func)
    return parser


def add_build_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--module", default=DEFAULT_MODULE)
    parser.add_argument("--product", default=DEFAULT_PRODUCT)
    parser.add_argument("--skip-ohpm", action="store_true")
    parser.add_argument("--skip-sync", action="store_true")


def add_artifact_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--out", default="/tmp/harmony-use")
    parser.add_argument("--prefix", default="state")


def cmd_doctor(args: argparse.Namespace, config: HarmonyConfig) -> dict[str, object]:
    return {"tools": config.tool_status(), "project": str(config.project) if config.project else None}


def cmd_env(args: argparse.Namespace, config: HarmonyConfig) -> dict[str, object]:
    return {"exports": config.export_lines()}


def cmd_devices(args: argparse.Namespace, config: HarmonyConfig) -> dict[str, object]:
    return {"targets": HarmonyDevice(config).devices()}


def cmd_connect(args: argparse.Namespace, config: HarmonyConfig) -> dict[str, object]:
    result = HarmonyDevice(config).connect(args.endpoint)
    return {"endpoint": args.endpoint, "result": result.as_dict()}


def cmd_emulator_list(args: argparse.Namespace, config: HarmonyConfig) -> dict[str, object]:
    result = HarmonyEmulator(config).list(details=args.details)
    return {"result": result.as_dict()}


def cmd_emulator_start(args: argparse.Namespace, config: HarmonyConfig) -> dict[str, object]:
    result = HarmonyEmulator(config).start(
        args.name,
        instance_path=args.instance_path,
        image_root=args.image_root,
        bootmode=args.bootmode,
        hdc_port=args.hdc_port,
    )
    return {"name": args.name, "result": result.as_dict()}


def cmd_emulator_stop(args: argparse.Namespace, config: HarmonyConfig) -> dict[str, object]:
    result = HarmonyEmulator(config).stop(args.name)
    return {"name": args.name, "result": result.as_dict()}


def cmd_emulator_license(args: argparse.Namespace, config: HarmonyConfig) -> dict[str, object]:
    result = HarmonyEmulator(config).license(accept=args.accept)
    return {"accepted": args.accept, "result": result.as_dict()}


def cmd_remote_run(args: argparse.Namespace, config: HarmonyConfig) -> dict[str, object]:
    command = list(args.command)
    if command and command[0] == "--":
        command = command[1:]
    if not command:
        raise HarmonyUseError("remote-run requires a command after the host, usually after --")
    result = RemoteHost(args.host, config).run(command)
    return {"host": args.host, "result": result.as_dict()}


def cmd_remote_devices(args: argparse.Namespace, config: HarmonyConfig) -> dict[str, object]:
    result = RemoteHost(args.host, config).hdc_devices()
    return {"host": args.host, "result": result.as_dict()}


def cmd_remote_connect(args: argparse.Namespace, config: HarmonyConfig) -> dict[str, object]:
    result = RemoteHost(args.host, config).hdc_connect(args.endpoint)
    return {"host": args.host, "endpoint": args.endpoint, "result": result.as_dict()}


def cmd_build_hap(args: argparse.Namespace, config: HarmonyConfig) -> dict[str, object]:
    steps: list[dict[str, object]] = []
    if not args.skip_ohpm:
        steps.append({"step": "ohpm_install", **run_ohpm_install(config).as_dict()})
    if not args.skip_sync:
        steps.append({"step": "hvigor_sync", **run_hvigor_sync(config).as_dict()})
    steps.append({"step": "assemble_hap", **assemble_hap(config, module=args.module, product=args.product).as_dict()})
    signed = default_hap_path(config, module=args.module, product=args.product, signed=True)
    unsigned = default_hap_path(config, module=args.module, product=args.product, signed=False)
    return {
        "steps": steps,
        "signed_hap": str(signed),
        "signed_hap_exists": signed.exists(),
        "unsigned_hap": str(unsigned),
        "unsigned_hap_exists": unsigned.exists(),
    }


def cmd_install(args: argparse.Namespace, config: HarmonyConfig) -> dict[str, object]:
    device = HarmonyDevice(config)
    hap = Path(args.hap).expanduser() if args.hap else default_hap_path(config, signed=True)
    stop_result = device.stop(bundle=args.bundle)
    install_result = device.install(hap)
    return {"hap": str(hap), "stop": stop_result.as_dict(), "install": install_result.as_dict()}


def cmd_launch(args: argparse.Namespace, config: HarmonyConfig) -> dict[str, object]:
    result = HarmonyDevice(config).launch(bundle=args.bundle, ability=args.ability, wait=args.wait)
    return {"bundle": args.bundle, "ability": args.ability, "result": result.as_dict()}


def cmd_stop(args: argparse.Namespace, config: HarmonyConfig) -> dict[str, object]:
    result = HarmonyDevice(config).stop(bundle=args.bundle)
    return {"bundle": args.bundle, "result": result.as_dict()}


def cmd_run_app(args: argparse.Namespace, config: HarmonyConfig) -> dict[str, object]:
    build = cmd_build_hap(args, config)
    install = cmd_install(argparse.Namespace(hap=build["signed_hap"], bundle=args.bundle), config)
    launch = cmd_launch(argparse.Namespace(bundle=args.bundle, ability=args.ability, wait=1.0), config)
    return {"build": build, "install": install, "launch": launch}


def cmd_state(args: argparse.Namespace, config: HarmonyConfig) -> dict[str, object]:
    device = HarmonyDevice(config)
    out = Path(args.out).expanduser()
    screenshot = device.screenshot(out=out, prefix=args.prefix)
    try:
        layout: dict[str, object] = device.dump_layout(out=out, prefix=args.prefix)
    except HarmonyUseError as exc:
        layout = {"ok": False, "error": str(exc)}
    return {"screenshot": screenshot, "layout": layout}


def cmd_screenshot(args: argparse.Namespace, config: HarmonyConfig) -> dict[str, object]:
    return HarmonyDevice(config).screenshot(out=Path(args.out).expanduser(), prefix=args.prefix)


def cmd_dump_layout(args: argparse.Namespace, config: HarmonyConfig) -> dict[str, object]:
    return HarmonyDevice(config).dump_layout(out=Path(args.out).expanduser(), prefix=args.prefix)


def cmd_tap(args: argparse.Namespace, config: HarmonyConfig) -> dict[str, object]:
    return {"result": HarmonyDevice(config).tap(args.x, args.y).as_dict(), "x": args.x, "y": args.y}


def cmd_swipe(args: argparse.Namespace, config: HarmonyConfig) -> dict[str, object]:
    if args.direction:
        x1, y1, x2, y2 = direction_to_swipe(args.direction)
    else:
        values = [args.x1, args.y1, args.x2, args.y2]
        if any(value is None for value in values):
            raise HarmonyUseError("swipe requires --direction or all of --x1 --y1 --x2 --y2")
        x1, y1, x2, y2 = int(args.x1), int(args.y1), int(args.x2), int(args.y2)
    result = HarmonyDevice(config).swipe(x1=x1, y1=y1, x2=x2, y2=y2, duration_ms=args.duration_ms)
    return {"result": result.as_dict(), "x1": x1, "y1": y1, "x2": x2, "y2": y2, "duration_ms": args.duration_ms}


def cmd_key(args: argparse.Namespace, config: HarmonyConfig) -> dict[str, object]:
    return {"result": HarmonyDevice(config).key(args.key_name).as_dict(), "key": args.key_name}


def cmd_hilog(args: argparse.Namespace, config: HarmonyConfig) -> dict[str, object]:
    return capture_hilog(
        HarmonyDevice(config),
        out=Path(args.out).expanduser(),
        bundle=args.bundle,
        tag=args.tag,
        contains=args.contains,
        duration=args.duration,
    )


def cmd_analyze_log(args: argparse.Namespace, config: HarmonyConfig) -> dict[str, object]:
    return analyze_hilog_file(Path(args.path).expanduser())


def print_payload(payload: dict[str, object], *, pretty: bool) -> None:
    print(json.dumps(payload, ensure_ascii=False, sort_keys=True, indent=2 if pretty else None))
