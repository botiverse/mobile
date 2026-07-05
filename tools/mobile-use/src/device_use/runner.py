from __future__ import annotations

import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping, Sequence


class DeviceUseError(RuntimeError):
    """Raised when a host/device command cannot complete."""


@dataclass(frozen=True)
class CommandResult:
    argv: list[str]
    returncode: int
    stdout: str
    stderr: str
    stdout_bytes: bytes = b""

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
    return _run_command(argv, cwd=cwd, env=env, check=check, timeout=timeout, binary=False)


def run_command_bytes(
    argv: Sequence[str | Path],
    *,
    cwd: str | Path | None = None,
    env: Mapping[str, str] | None = None,
    check: bool = True,
    timeout: float | None = None,
) -> CommandResult:
    return _run_command(argv, cwd=cwd, env=env, check=check, timeout=timeout, binary=True)


def _run_command(
    argv: Sequence[str | Path],
    *,
    cwd: str | Path | None,
    env: Mapping[str, str] | None,
    check: bool,
    timeout: float | None,
    binary: bool,
) -> CommandResult:
    str_argv = [str(value) for value in argv]
    try:
        result = subprocess.run(
            str_argv,
            cwd=str(cwd) if cwd is not None else None,
            env=dict(env) if env is not None else None,
            text=not binary,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
        )
        returncode = result.returncode
        if binary:
            stdout_bytes = result.stdout or b""
            stdout = stdout_bytes.decode("utf-8", errors="replace")
            stderr = (result.stderr or b"").decode("utf-8", errors="replace")
        else:
            stdout = result.stdout or ""
            stderr = result.stderr or ""
            stdout_bytes = stdout.encode("utf-8", errors="replace")
    except subprocess.TimeoutExpired as exc:
        stdout = stringify_timeout_output(exc.stdout)
        stderr = stringify_timeout_output(exc.stderr)
        stdout_bytes = stdout.encode("utf-8", errors="replace")
        returncode = 124
    except FileNotFoundError as exc:
        raise DeviceUseError(f"executable not found: {str_argv[0]}") from exc
    wrapped = CommandResult(
        argv=str_argv,
        returncode=returncode,
        stdout=stdout,
        stderr=stderr,
        stdout_bytes=stdout_bytes,
    )
    if check and wrapped.returncode != 0:
        detail = (wrapped.stdout + wrapped.stderr).strip()
        raise DeviceUseError(f"{wrapped.command} failed ({wrapped.returncode}): {detail}")
    return wrapped


def stringify_timeout_output(value: str | bytes | None) -> str:
    if value is None:
        return ""
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    return value
