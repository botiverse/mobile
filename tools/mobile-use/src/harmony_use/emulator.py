from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from .config import HarmonyConfig
from .runner import CommandResult, HarmonyUseError, run_command


@dataclass
class HarmonyEmulator:
    config: HarmonyConfig

    def command(self, args: list[str], *, check: bool = True) -> CommandResult:
        if not self.config.emulator.exists():
            raise HarmonyUseError(f"DevEco Emulator not found: {self.config.emulator}")
        return run_command([self.config.emulator, *args], env=self.config.env(), check=check)

    def list(self, *, details: bool = False) -> CommandResult:
        args = ["-list"]
        if details:
            args.append("-details")
        return self.command(args, check=False)

    def start(
        self,
        name: str,
        *,
        instance_path: str | Path | None = None,
        image_root: str | Path | None = None,
        bootmode: str | None = None,
        hdc_port: int | None = None,
    ) -> CommandResult:
        args = ["-start", name]
        if instance_path:
            args.extend(["-instancePath", str(instance_path)])
        if image_root:
            args.extend(["-imageRoot", str(image_root)])
        if bootmode:
            args.extend(["-bootmode", bootmode])
        if hdc_port is not None:
            args.extend(["-hdcPort", str(hdc_port)])
        return self.command(args, check=False)

    def stop(self, name: str) -> CommandResult:
        return self.command(["-stop", name], check=False)

    def license(self, *, accept: bool = False) -> CommandResult:
        args = ["-license"]
        if accept:
            args.append("accept")
        return self.command(args, check=False)

    def collect_logs(self, name: str, *, log_path: str | Path) -> CommandResult:
        return self.command(["-logZip", name, "-logPath", str(log_path)], check=False)
