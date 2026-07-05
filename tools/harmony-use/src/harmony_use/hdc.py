from __future__ import annotations

import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

from .config import DEFAULT_ABILITY, DEFAULT_BUNDLE, HarmonyConfig
from .runner import CommandResult, HarmonyUseError, run_command


@dataclass
class HarmonyDevice:
    config: HarmonyConfig

    def hdc(self, args: list[str], *, target: bool = True, check: bool = True, timeout: float | None = None) -> CommandResult:
        if not self.config.hdc.exists():
            raise HarmonyUseError(f"hdc not found: {self.config.hdc}")
        argv = [str(self.config.hdc)]
        if target:
            argv.extend(["-t", self.ensure_target()])
        argv.extend(args)
        return run_command(argv, env=self.config.env(), check=check, timeout=timeout)

    def devices(self) -> list[str]:
        result = self.hdc(["list", "targets"], target=False)
        return [
            line.strip().split()[0]
            for line in result.stdout.splitlines()
            if line.strip() and line.strip() != "[Empty]"
        ]

    def connect(self, endpoint: str) -> CommandResult:
        return self.hdc(["tconn", endpoint], target=False, check=False)

    def ensure_target(self) -> str:
        if self.config.target:
            return self.config.target
        targets = self.devices()
        if not targets:
            raise HarmonyUseError("no OHOS target connected; start an emulator/device or run `harmony-use connect HOST:PORT`")
        return targets[0]

    def stop(self, *, bundle: str = DEFAULT_BUNDLE) -> CommandResult:
        return self.hdc(["shell", f"aa force-stop {quote_shell(bundle)}"], check=False)

    def launch(self, *, bundle: str = DEFAULT_BUNDLE, ability: str = DEFAULT_ABILITY, wait: float = 1.0) -> CommandResult:
        result = self.hdc(["shell", f"aa start -a {quote_shell(ability)} -b {quote_shell(bundle)}"])
        if wait > 0:
            time.sleep(wait)
        return result

    def install(self, hap: Path) -> CommandResult:
        if not hap.exists():
            raise HarmonyUseError(f"HAP not found: {hap}")
        return self.hdc(["install", str(hap)])

    def tap(self, x: int, y: int) -> CommandResult:
        return self.hdc(["shell", f"uitest uiInput click {x} {y}"])

    def key(self, key_name: str) -> CommandResult:
        return self.hdc(["shell", f"uitest uiInput keyEvent {quote_shell(key_name)}"])

    def swipe(
        self,
        *,
        x1: int,
        y1: int,
        x2: int,
        y2: int,
        duration_ms: int = 350,
    ) -> CommandResult:
        return self.hdc(["shell", f"uitest uiInput swipe {x1} {y1} {x2} {y2} {duration_ms}"])

    def screenshot(self, *, out: Path, prefix: str = "state") -> dict[str, Any]:
        out.mkdir(parents=True, exist_ok=True)
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        local = out / f"{prefix}-{stamp}.png"
        remote = f"/data/local/tmp/harmony-use-{prefix}-{stamp}.png"
        command = f"snapshot_display -f {quote_shell(remote)}"
        result = self.hdc(["shell", command], check=False)
        if result.returncode != 0:
            command = f"snapshot_display -i 0 -f {quote_shell(remote)}"
            result = self.hdc(["shell", command], check=False)
        if result.returncode != 0:
            raise HarmonyUseError(f"screenshot failed: {(result.stdout + result.stderr).strip()}")
        self.hdc(["file", "recv", remote, str(local)])
        self.hdc(["shell", f"rm -f {quote_shell(remote)}"], check=False)
        if not local.exists() or local.stat().st_size == 0:
            raise HarmonyUseError(f"screenshot pull did not produce a file: {local}")
        return {"target": self.ensure_target(), "path": str(local), "command": command}

    def dump_layout(self, *, out: Path, prefix: str = "state") -> dict[str, Any]:
        out.mkdir(parents=True, exist_ok=True)
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        local = out / f"{prefix}-{stamp}-layout.json"
        remote = f"/data/local/tmp/harmony-use-{prefix}-{stamp}-layout.json"
        command = f"uitest dumpLayout -p {quote_shell(remote)}"
        result = self.hdc(["shell", command], check=False)
        if result.returncode != 0:
            raise HarmonyUseError(f"uitest dumpLayout failed: {(result.stdout + result.stderr).strip()}")
        self.hdc(["file", "recv", remote, str(local)])
        self.hdc(["shell", f"rm -f {quote_shell(remote)}"], check=False)
        return {"target": self.ensure_target(), "path": str(local), "command": command}


def quote_shell(value: str) -> str:
    return "'" + value.replace("'", "'\\''") + "'"


def direction_to_swipe(direction: str, *, width: int = 1080, height: int = 2400) -> tuple[int, int, int, int]:
    center_x = width // 2
    center_y = height // 2
    distance = min(width, height) // 2
    if direction == "up":
        return center_x, center_y + distance // 2, center_x, center_y - distance // 2
    if direction == "down":
        return center_x, center_y - distance // 2, center_x, center_y + distance // 2
    if direction == "left":
        return center_x + distance // 2, center_y, center_x - distance // 2, center_y
    if direction == "right":
        return center_x - distance // 2, center_y, center_x + distance // 2, center_y
    raise HarmonyUseError(f"unsupported direction: {direction}")
