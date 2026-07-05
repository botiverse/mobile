from __future__ import annotations

from pathlib import Path
from typing import Mapping, Sequence

from device_use.runner import CommandResult, DeviceUseError, run_command as shared_run_command, stringify_timeout_output


class HarmonyUseError(DeviceUseError):
    """Raised when a Harmony/OHOS command cannot complete."""


def run_command(
    argv: Sequence[str | Path],
    *,
    cwd: str | Path | None = None,
    env: Mapping[str, str] | None = None,
    check: bool = True,
    timeout: float | None = None,
) -> CommandResult:
    try:
        return shared_run_command(argv, cwd=cwd, env=env, check=check, timeout=timeout)
    except DeviceUseError as exc:
        raise HarmonyUseError(str(exc)) from exc
