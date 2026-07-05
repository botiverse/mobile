from __future__ import annotations

import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping, Sequence


class HarmonyUseError(RuntimeError):
    """Raised when a host/device command cannot complete."""


@dataclass(frozen=True)
class CommandResult:
    argv: list[str]
    returncode: int
    stdout: str
    stderr: str

    @property
    def command(self) -> str:
        return " ".join(self.argv)

    def as_dict(self) -> dict[str, object]:
        return {
            "command": self.command,
            "returncode": self.returncode,
            "stdout": self.stdout.strip(),
            "stderr": self.stderr.strip(),
        }


def run_command(
    argv: Sequence[str | Path],
    *,
    cwd: str | Path | None = None,
    env: Mapping[str, str] | None = None,
    check: bool = True,
    timeout: float | None = None,
) -> CommandResult:
    str_argv = [str(value) for value in argv]
    try:
        result = subprocess.run(
            str_argv,
            cwd=str(cwd) if cwd is not None else None,
            env=dict(env) if env is not None else None,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
        )
        stdout = result.stdout
        stderr = result.stderr
        returncode = result.returncode
    except subprocess.TimeoutExpired as exc:
        stdout = stringify_timeout_output(exc.stdout)
        stderr = stringify_timeout_output(exc.stderr)
        returncode = 124
    wrapped = CommandResult(
        argv=str_argv,
        returncode=returncode,
        stdout=stdout,
        stderr=stderr,
    )
    if check and wrapped.returncode != 0:
        detail = (wrapped.stdout + wrapped.stderr).strip()
        raise HarmonyUseError(f"{wrapped.command} failed ({wrapped.returncode}): {detail}")
    return wrapped


def stringify_timeout_output(value: str | bytes | None) -> str:
    if value is None:
        return ""
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    return value
