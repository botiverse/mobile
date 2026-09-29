from __future__ import annotations

import json
import re
from datetime import datetime
from pathlib import Path
from typing import Any

from device_use.runner import CommandResult, DeviceUseError, run_command, run_command_bytes

from .config import (
    DEFAULT_DIAGNOSTICS_ACTION,
    DEFAULT_DIAGNOSTICS_COMPONENT,
    AndroidConfig,
)


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


class AndroidUseError(DeviceUseError):
    """Raised when an Android adb operation cannot complete."""


class AndroidDevice:
    def __init__(self, config: AndroidConfig):
        self.config = config

    def adb(
        self,
        args: list[str],
        *,
        device: bool = True,
        check: bool = True,
        timeout: float | None = None,
        binary: bool = False,
    ) -> CommandResult:
        argv = [self.config.adb]
        if device:
            argv.extend(["-s", self.ensure_device()])
        argv.extend(args)
        try:
            if binary:
                return run_command_bytes(argv, check=check, timeout=timeout)
            return run_command(argv, check=check, timeout=timeout)
        except DeviceUseError as exc:
            raise AndroidUseError(str(exc)) from exc

    def devices(self) -> list[str]:
        result = self.adb(["devices"], device=False)
        devices: list[str] = []
        for line in result.stdout.splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device":
                devices.append(parts[0])
        return devices

    def ensure_device(self) -> str:
        if self.config.device:
            return self.config.device
        devices = self.devices()
        if not devices:
            raise AndroidUseError("no online adb devices; start an emulator/device or pass --device")
        return devices[0]

    def stop(self, package: str | None = None) -> CommandResult:
        return self.adb(["shell", "am", "force-stop", package or self.config.package], check=False)

    def launch(self, component: str | None = None, *, wait: bool = True) -> CommandResult:
        args = ["shell", "am", "start"]
        if wait:
            args.append("-W")
        args.extend(["-n", component or self.config.main_component])
        return self.adb(args)

    def install(self, apk: Path, *, reinstall: bool = True) -> CommandResult:
        if not apk.exists():
            raise AndroidUseError(f"APK not found: {apk}")
        args = ["install"]
        if reinstall:
            args.append("-r")
        args.append(str(apk))
        return self.adb(args)

    def start_intent(
        self,
        *,
        action: str | None = None,
        component: str | None = None,
        categories: list[str] | None = None,
        extras: list[tuple[str, str, str]] | None = None,
        wait: bool = True,
    ) -> CommandResult:
        am_args = ["-W"] if wait else []
        if action:
            am_args.extend(["-a", action])
        if component:
            am_args.extend(["-n", component])
        if not action and not component:
            raise AndroidUseError("start-intent requires action and/or component")
        for category in categories or []:
            am_args.extend(["-c", category])
        for flag, key, value in extras or []:
            am_args.extend([flag, key, value])
        return self.adb(["shell", "am", "start", *am_args], check=False)

    def open_page(
        self,
        page_name: str,
        *,
        page_data: dict[str, Any] | None = None,
        component: str | None = None,
        suppress_soft_keyboard: bool = True,
        wait: bool = True,
    ) -> CommandResult:
        payload = dict(page_data or {})
        payload.setdefault("pageName", page_name)
        extras = [
            ("--es", "pageName", page_name),
            ("--es", "pageData", json.dumps(payload, separators=(",", ":"), ensure_ascii=False)),
        ]
        if suppress_soft_keyboard:
            extras.append(("--ez", "slockSuppressSoftKeyboard", "true"))
        return self.start_intent(component=component or self.config.kuikly_component, extras=extras, wait=wait)

    def open_diagnostics(
        self,
        *,
        component: str = DEFAULT_DIAGNOSTICS_COMPONENT,
        action: str = DEFAULT_DIAGNOSTICS_ACTION,
        auto_close_ms: int = 0,
        wait: bool = True,
    ) -> CommandResult:
        extras: list[tuple[str, str, str]] = []
        if auto_close_ms > 0:
            extras.append(("--el", "auto_close_ms", str(auto_close_ms)))
        return self.start_intent(action=action, component=component, extras=extras, wait=wait)

    def screenshot(self, *, out: Path, prefix: str = "state") -> dict[str, Any]:
        out.mkdir(parents=True, exist_ok=True)
        local = out / f"{safe_prefix(prefix)}-{now_id()}.png"
        png = self.adb(["exec-out", "screencap", "-p"], binary=True)
        local.write_bytes(png.stdout_bytes)
        return {"device": self.ensure_device(), "path": str(local)}

    def dump_layout(self, *, out: Path, prefix: str = "state") -> dict[str, Any]:
        out.mkdir(parents=True, exist_ok=True)
        base = out / f"{safe_prefix(prefix)}-{now_id()}"
        xml = base.with_suffix(".xml")
        log = out / f"{base.name}-uiautomator.txt"
        dump = self.adb(["shell", "uiautomator", "dump", "/sdcard/window.xml"])
        log.write_text(dump.stdout + dump.stderr, encoding="utf-8")
        contents = self.adb(["shell", "cat", "/sdcard/window.xml"]).stdout
        xml.write_text(contents, encoding="utf-8")
        return {"device": self.ensure_device(), "path": str(xml), "uiautomator_log_path": str(log)}

    def state(self, *, out: Path, prefix: str = "state") -> dict[str, Any]:
        return {
            "screenshot": self.screenshot(out=out, prefix=prefix),
            "layout": self.dump_layout(out=out, prefix=prefix),
        }

    def tap(self, x: int, y: int) -> CommandResult:
        return self.adb(["shell", "input", "tap", str(x), str(y)])

    def key(self, key_name: str) -> CommandResult:
        key = KEY_ALIASES.get(key_name.upper(), key_name)
        return self.adb(["shell", "input", "keyevent", key])

    def swipe(self, *, x1: int, y1: int, x2: int, y2: int, duration_ms: int = 350) -> CommandResult:
        return self.adb(["shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(duration_ms)])

    def direction_to_swipe(self, direction: str) -> tuple[int, int, int, int]:
        return direction_to_swipe(direction, *self.screen_size())

    def screen_size(self) -> tuple[int, int]:
        result = self.adb(["shell", "wm", "size"]).stdout
        match = re.search(r"Physical size:\s*(\d+)x(\d+)", result)
        if not match:
            raise AndroidUseError(f"unable to parse screen size from: {result.strip()}")
        return int(match.group(1)), int(match.group(2))

    def capture_logcat(
        self,
        *,
        out: Path,
        prefix: str = "state",
        clear_first: bool = False,
        duration_seconds: float = 0.0,
        filter_regex: str | None = None,
    ) -> dict[str, Any]:
        out.mkdir(parents=True, exist_ok=True)
        path = out / f"{safe_prefix(prefix)}-{now_id()}-logcat.txt"
        if clear_first:
            self.adb(["logcat", "-c"], check=False)
        if duration_seconds > 0:
            result = self.adb(["logcat", "-v", "time"], timeout=duration_seconds, check=False)
        else:
            result = self.adb(["logcat", "-d", "-v", "time"])
        text = result.stdout
        path.write_text(text, encoding="utf-8", errors="replace")
        payload: dict[str, Any] = {"device": self.ensure_device(), "path": str(path), "bytes": path.stat().st_size}
        if filter_regex:
            filtered_path = out / f"{safe_prefix(prefix)}-{now_id()}-logcat-filtered.txt"
            regex = re.compile(filter_regex, re.IGNORECASE)
            filtered = "\n".join(line for line in text.splitlines() if regex.search(line))
            filtered_path.write_text(filtered + ("\n" if filtered else ""), encoding="utf-8")
            payload["filtered_path"] = str(filtered_path)
            payload["filtered_lines"] = len(filtered.splitlines()) if filtered else 0
        return payload


def direction_to_swipe(direction: str, width: int = 1080, height: int = 2400) -> tuple[int, int, int, int]:
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
    if direction == "right":
        return left, mid_y, right, mid_y
    raise AndroidUseError(f"unsupported direction: {direction}")


def safe_prefix(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", value.strip() or "state")


def now_id() -> str:
    return datetime.now().strftime("%Y%m%d-%H%M%S")
