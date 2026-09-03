from __future__ import annotations

import json
import os
import plistlib
import shutil
import subprocess
from datetime import datetime
from pathlib import Path
from typing import Any

from device_use.runner import CommandResult, DeviceUseError, run_command

from .config import IosConfig
from . import qa_loop


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

    def interact(
        self,
        actions: list[dict[str, Any]],
        *,
        workspace: Path | None = None,
        interaction_scheme: str = "iosAppUITests",
        configuration: str | None = None,
        derived_data: Path | None = None,
        bundle: str | None = None,
        device: str | None = None,
    ) -> dict[str, Any]:
        resolved_workspace = workspace or self.config.workspace
        if resolved_workspace is None:
            raise IosUseError("interact requires --workspace or IOS_USE_WORKSPACE")
        resolved_device = device or self.ensure_device()
        resolved_derived_data = derived_data or self.config.derived_data
        resolved_bundle = bundle or self.config.bundle
        session = self._ensure_qa_loop(
            workspace=resolved_workspace,
            interaction_scheme=interaction_scheme,
            configuration=configuration or self.config.configuration,
            derived_data=resolved_derived_data,
            bundle=resolved_bundle,
            device=resolved_device,
        )
        results: list[Any] = []
        with qa_loop.exclusive_lock(qa_loop.lock_path_for(session["root"])):
            for action in actions:
                for mapped in qa_loop.map_ios_use_action(action):
                    results.append(qa_loop.send_action(session["io_dir"], mapped))
        return {
            "device": resolved_device,
            "scheme": interaction_scheme,
            "bundle": resolved_bundle,
            "actions": actions,
            "harness": "QaAcceptanceLoopTests",
            "io_dir": str(session["io_dir"]),
            "loop_pid": session["pid"],
            "results": results,
        }

    def start_xcodebuild_background(
        self,
        args: list[str],
        *,
        log_path: Path,
        env: dict[str, str] | None = None,
    ) -> int:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        log_file = log_path.open("ab")
        proc = subprocess.Popen(
            [self.config.xcodebuild, *args],
            stdout=log_file,
            stderr=subprocess.STDOUT,
            start_new_session=True,
            env=env,
        )
        return proc.pid

    def _ensure_qa_loop(
        self,
        *,
        workspace: Path,
        interaction_scheme: str,
        configuration: str,
        derived_data: Path,
        bundle: str,
        device: str,
    ) -> dict[str, Any]:
        root = qa_loop.loop_root(derived_data, device)
        io_dir = qa_loop.io_dir_for(root)
        pid_path = qa_loop.pid_path_for(root)
        log_path = qa_loop.log_path_for(root)
        ready = io_dir / "ready.json"
        with qa_loop.exclusive_lock(qa_loop.lock_path_for(root)):
            existing = qa_loop.read_pid(pid_path)
            if (
                existing
                and qa_loop.pid_alive(existing)
                and ready.exists()
                and not qa_loop.loop_sources_newer(workspace, ready)
            ):
                return {"root": root, "io_dir": io_dir, "pid": existing}
            if existing:
                qa_loop.stop_pid(existing)
            if io_dir.exists():
                shutil.rmtree(io_dir)
            io_dir.mkdir(parents=True)
            if log_path.exists():
                log_path.unlink()
            self.xcodebuild(
                [
                    "-workspace",
                    str(workspace),
                    "-scheme",
                    interaction_scheme,
                    "-configuration",
                    configuration,
                    "-destination",
                    f"id={device}",
                    "-derivedDataPath",
                    str(derived_data),
                    "-quiet",
                    "build-for-testing",
                ]
            )
            # Same channel as .github/workflows/qa-acceptance-ios.yml: TEST_RUNNER_*
            # is forwarded into the XCUITest process. Patching only the first
            # xctestrun target used to inject env into iosAppTests and skip the
            # UI test, which XCTSkip'd in ~3s with no ready.json.
            runner_env = os.environ.copy()
            runner_env["TEST_RUNNER_QA_LOOP_IO_DIR"] = str(io_dir)
            runner_env["TEST_RUNNER_QA_LOOP_BUNDLE"] = bundle
            runner_env["TEST_RUNNER_QA_LOOP_IDLE_TIMEOUT"] = qa_loop.DEFAULT_IDLE_TIMEOUT
            pid = 0
            try:
                pid = self.start_xcodebuild_background(
                    [
                        "-workspace",
                        str(workspace),
                        "-scheme",
                        interaction_scheme,
                        "-configuration",
                        configuration,
                        "-destination",
                        f"id={device}",
                        "-derivedDataPath",
                        str(derived_data),
                        "-quiet",
                        f"-only-testing:{qa_loop.QA_LOOP_TEST}",
                        "test-without-building",
                    ],
                    log_path=log_path,
                    env=runner_env,
                )
                pid_path.write_text(str(pid), encoding="utf-8")
                qa_loop.wait_ready(io_dir, pid=pid, log_path=log_path)
            except Exception:
                if pid:
                    qa_loop.stop_pid(pid)
                raise
            return {"root": root, "io_dir": io_dir, "pid": pid}

    def _patched_xctestrun(
        self,
        env_vars: dict[str, str],
        *,
        derived_data: Path,
        interaction_scheme: str,
        bundle: str | None = None,
    ) -> Path:
        products = derived_data / "Build" / "Products"
        candidates = sorted(
            products.glob(f"{interaction_scheme}_*.xctestrun"),
            key=lambda path: path.stat().st_mtime,
            reverse=True,
        )
        if not candidates:
            raise IosUseError(f"xctestrun not found after build-for-testing in {products}")
        source = candidates[0]
        patched = products / f"ios-use-qa-loop-{now_id()}.xctestrun"
        shutil.copyfile(source, patched)
        with patched.open("rb") as stream:
            data = plistlib.load(stream)
        patched_any = False
        for key, target in data.items():
            if key == "__xctestrun_metadata__" or not isinstance(target, dict):
                continue
            if "EnvironmentVariables" not in target and "TestingEnvironmentVariables" not in target:
                continue
            patched_any = True
            for env_key in ("EnvironmentVariables", "TestingEnvironmentVariables"):
                target.setdefault(env_key, {})
                target[env_key].update(env_vars)
                if bundle:
                    target[env_key]["IOS_USE_BUNDLE"] = bundle
        if not patched_any:
            raise IosUseError(f"invalid xctestrun without test target entry: {patched}")
        with patched.open("wb") as stream:
            plistlib.dump(data, stream)
        return patched

    def default_app_path(self, *, derived_data: Path | None = None, configuration: str | None = None, sdk: str | None = None) -> Path:
        sdk_name = sdk or self.config.sdk
        platform = "iphonesimulator" if "simulator" in sdk_name else "iphoneos"
        return (derived_data or self.config.derived_data) / "Build" / "Products" / f"{configuration or self.config.configuration}-{platform}" / "iosApp.app"


def safe_prefix(value: str) -> str:
    return "".join(ch if ch.isalnum() or ch in "._-" else "_" for ch in (value.strip() or "state"))


def now_id() -> str:
    return datetime.now().strftime("%Y%m%d-%H%M%S")


def compact_result(result: CommandResult, *, tail_lines: int = 40) -> dict[str, object]:
    stdout_lines = result.stdout.strip().splitlines()
    stderr_lines = result.stderr.strip().splitlines()
    return {
        "command": result.command,
        "returncode": result.returncode,
        "stdout_tail": "\n".join(stdout_lines[-tail_lines:]),
        "stderr_tail": "\n".join(stderr_lines[-tail_lines:]),
    }
