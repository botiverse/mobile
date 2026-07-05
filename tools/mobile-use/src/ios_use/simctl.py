from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path
from typing import Any

from device_use.runner import CommandResult, DeviceUseError, run_command

from .config import IosConfig


class IosUseError(DeviceUseError):
    """Raised when an iOS host/simulator command cannot complete."""


class IosSimulator:
    def __init__(self, config: IosConfig):
        self.config = config

    def xcrun(self, args: list[str], *, check: bool = True, timeout: float | None = None) -> CommandResult:
        try:
            return run_command([self.config.xcrun, *args], check=check, timeout=timeout)
        except DeviceUseError as exc:
            raise IosUseError(str(exc)) from exc

    def xcodebuild(self, args: list[str], *, cwd: Path | None = None, check: bool = True, timeout: float | None = None) -> CommandResult:
        try:
            return run_command([self.config.xcodebuild, *args], cwd=cwd, check=check, timeout=timeout)
        except DeviceUseError as exc:
            raise IosUseError(str(exc)) from exc

    def devices(self) -> dict[str, Any]:
        result = self.xcrun(["simctl", "list", "devices", "--json"])
        return json.loads(result.stdout)

    def ensure_device(self) -> str:
        if self.config.device:
            return self.config.device
        devices = self.devices().get("devices", {})
        for runtime_devices in devices.values():
            for device in runtime_devices:
                if device.get("state") == "Booted" and device.get("isAvailable", True):
                    return str(device["udid"])
        for runtime_devices in devices.values():
            for device in runtime_devices:
                if device.get("isAvailable", True):
                    return str(device["udid"])
        raise IosUseError("no available iOS simulator found; pass --device or create one in Simulator")

    def boot(self, device: str | None = None) -> CommandResult:
        return self.xcrun(["simctl", "boot", device or self.ensure_device()], check=False)

    def shutdown(self, device: str | None = None) -> CommandResult:
        return self.xcrun(["simctl", "shutdown", device or self.ensure_device()], check=False)

    def install(self, app: Path, *, device: str | None = None) -> CommandResult:
        if not app.exists():
            raise IosUseError(f"app bundle not found: {app}")
        return self.xcrun(["simctl", "install", device or self.ensure_device(), str(app)])

    def launch(self, *, bundle: str | None = None, device: str | None = None, args: list[str] | None = None) -> CommandResult:
        argv = ["simctl", "launch", device or self.ensure_device(), bundle or self.config.bundle]
        if args:
            argv.extend(args)
        return self.xcrun(argv)

    def terminate(self, *, bundle: str | None = None, device: str | None = None) -> CommandResult:
        return self.xcrun(["simctl", "terminate", device or self.ensure_device(), bundle or self.config.bundle], check=False)

    def open_url(self, url: str, *, device: str | None = None) -> CommandResult:
        return self.xcrun(["simctl", "openurl", device or self.ensure_device(), url])

    def screenshot(self, *, out: Path, prefix: str = "state", device: str | None = None) -> dict[str, Any]:
        out.mkdir(parents=True, exist_ok=True)
        path = out / f"{safe_prefix(prefix)}-{now_id()}.png"
        result = self.xcrun(["simctl", "io", device or self.ensure_device(), "screenshot", str(path)])
        return {"device": device or self.ensure_device(), "path": str(path), "result": result.as_dict()}

    def capture_log(
        self,
        *,
        out: Path,
        prefix: str = "state",
        device: str | None = None,
        predicate: str | None = None,
        duration: float = 10.0,
    ) -> dict[str, Any]:
        out.mkdir(parents=True, exist_ok=True)
        path = out / f"{safe_prefix(prefix)}-{now_id()}-ios-log.txt"
        argv = ["simctl", "spawn", device or self.ensure_device(), "log", "stream", "--style", "compact"]
        if predicate:
            argv.extend(["--predicate", predicate])
        result = self.xcrun(argv, check=False, timeout=duration)
        text = result.stdout + result.stderr
        path.write_text(text, encoding="utf-8", errors="replace")
        return {"device": device or self.ensure_device(), "path": str(path), "bytes": path.stat().st_size}

    def build(
        self,
        *,
        workspace: Path | None = None,
        scheme: str | None = None,
        configuration: str | None = None,
        sdk: str | None = None,
        derived_data: Path | None = None,
    ) -> CommandResult:
        resolved_workspace = workspace or self.config.workspace
        if resolved_workspace is None:
            raise IosUseError("build requires --workspace or IOS_USE_WORKSPACE")
        argv = [
            "-workspace",
            str(resolved_workspace),
            "-scheme",
            scheme or self.config.scheme,
            "-configuration",
            configuration or self.config.configuration,
            "-sdk",
            sdk or self.config.sdk,
            "-derivedDataPath",
            str(derived_data or self.config.derived_data),
            "build",
        ]
        return self.xcodebuild(argv)

    def default_app_path(self, *, derived_data: Path | None = None, configuration: str | None = None, sdk: str | None = None) -> Path:
        sdk_name = sdk or self.config.sdk
        platform = "iphonesimulator" if "simulator" in sdk_name else "iphoneos"
        return (derived_data or self.config.derived_data) / "Build" / "Products" / f"{configuration or self.config.configuration}-{platform}" / "iosApp.app"


def safe_prefix(value: str) -> str:
    return "".join(ch if ch.isalnum() or ch in "._-" else "_" for ch in (value.strip() or "state"))


def now_id() -> str:
    return datetime.now().strftime("%Y%m%d-%H%M%S")
