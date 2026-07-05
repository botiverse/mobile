from __future__ import annotations

import shlex
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

from .config import HarmonyConfig
from .runner import CommandResult, run_command


@dataclass
class RemoteHost:
    host: str
    config: HarmonyConfig
    ssh_bin: str = "ssh"

    def run(self, command: Sequence[str] | str, *, check: bool = False) -> CommandResult:
        remote_command = command if isinstance(command, str) else shell_join(command)
        return run_command([self.ssh_bin, self.host, remote_command], check=check)

    def run_with_deveco_env(self, command: Sequence[str] | str, *, check: bool = False) -> CommandResult:
        remote_command = command if isinstance(command, str) else shell_join(command)
        return self.run(f"{remote_env_prefix(self.config)} {remote_command}", check=check)

    def hdc_devices(self) -> CommandResult:
        return self.run_with_deveco_env([str(self.config.hdc), "list", "targets"], check=False)

    def hdc_connect(self, endpoint: str) -> CommandResult:
        return self.run_with_deveco_env([str(self.config.hdc), "tconn", endpoint], check=False)


def shell_join(values: Sequence[str | Path]) -> str:
    return " ".join(shlex.quote(str(value)) for value in values)


def remote_env_prefix(config: HarmonyConfig) -> str:
    path_entries = [
        config.sdk_home,
        config.java_home / "bin",
        config.deveco_home / "tools" / "node" / "bin",
        config.deveco_home / "tools" / "ohpm" / "bin",
        config.deveco_home / "tools" / "hvigor" / "bin",
        config.sdk_home / "default" / "openharmony" / "toolchains",
    ]
    return " ".join(
        [
            f"DEVECO_SDK_HOME={shlex.quote(str(config.sdk_home))}",
            "PATH=" + shlex.quote(":".join(str(path) for path in path_entries)) + ":$PATH",
            f"HDC_BIN={shlex.quote(str(config.hdc))}",
        ]
    )
