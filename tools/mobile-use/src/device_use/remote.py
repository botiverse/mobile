from __future__ import annotations

import shlex
from dataclasses import dataclass
from pathlib import Path
from typing import Sequence

from .runner import CommandResult, run_command


@dataclass
class RemoteHost:
    host: str
    ssh_bin: str = "ssh"

    def run(self, command: Sequence[str | Path] | str, *, check: bool = False) -> CommandResult:
        remote_command = command if isinstance(command, str) else shell_join(command)
        return run_command([self.ssh_bin, self.host, remote_command], check=check)


def shell_join(values: Sequence[str | Path]) -> str:
    return " ".join(shlex.quote(str(value)) for value in values)
