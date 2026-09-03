"""Drive ios-use interactions through the agent-device CLI.

Build/install/launch stay on simctl/xcodebuild. Inspect/act/verify for
tap/tap-text/swipe/text/key/wait goes through agent-device so callers no
longer need the QaAcceptanceLoopTests file protocol.

Product log verdicts stay outside this module.
"""

from __future__ import annotations

import os
import shutil
import time
from pathlib import Path
from typing import Any, Callable

from device_use.runner import run_command

from .simctl import IosUseError


DEFAULT_SESSION_PREFIX = "ios-use"
DRIVER_AGENT_DEVICE = "agent-device"
DRIVER_QA_LOOP = "qa-loop"
KNOWN_DRIVERS = (DRIVER_AGENT_DEVICE, DRIVER_QA_LOOP)


def resolve_binary(explicit: str | None = None) -> str:
    raw = explicit or os.environ.get("AGENT_DEVICE_BIN") or os.environ.get("IOS_USE_AGENT_DEVICE")
    if raw:
        path = Path(raw).expanduser()
        if path.exists():
            return str(path)
        found = shutil.which(raw)
        if found:
            return found
        raise IosUseError(f"agent-device binary not found: {raw}")
    found = shutil.which("agent-device")
    if found:
        return found
    raise IosUseError(
        "agent-device is not on PATH. Install with `npm install -g agent-device@0.20.10` "
        "or set AGENT_DEVICE_BIN. Fallback: ios-use --driver qa-loop"
    )


def tool_status(explicit: str | None = None) -> dict[str, object]:
    try:
        binary = resolve_binary(explicit)
    except IosUseError as exc:
        return {"path": None, "exists": False, "error": str(exc)}
    version = None
    try:
        result = run_command([binary, "--version"], check=False, timeout=15)
        version = (result.stdout or result.stderr).strip().splitlines()[-1] if result.stdout or result.stderr else None
    except Exception as exc:  # noqa: BLE001 — doctor must not crash
        return {"path": binary, "exists": True, "version": None, "error": str(exc)}
    return {"path": binary, "exists": True, "version": version}


def session_name(device: str) -> str:
    slug = "".join(ch if ch.isalnum() else "-" for ch in device)[:24].strip("-") or "device"
    return f"{DEFAULT_SESSION_PREFIX}-{slug}"


def selector_for_text(text: str, *, button_only: bool = False) -> str:
    escaped = text.replace("\\", "\\\\").replace('"', '\\"')
    if button_only:
        return f'role="button" label="{escaped}" || role="button" text="{escaped}"'
    return f'role="button" label="{escaped}" || label="{escaped}" || text="{escaped}"'


def map_action(
    action: dict[str, Any],
    *,
    screen_px: tuple[int, int] | None = None,
) -> list[list[str]]:
    """Map one ios-use action onto agent-device CLI argv lists (no binary)."""
    payload = {key: value for key, value in action.items() if key != "waitAfter"}
    action_type = str(payload.get("type") or "")
    mapped: list[list[str]]
    if action_type == "tapText":
        text = str(payload.get("text") or "")
        if not text:
            raise IosUseError("tapText requires text")
        mapped = [["press", selector_for_text(text), "--settle"]]
    elif action_type == "tap":
        x, y = payload.get("x"), payload.get("y")
        if x is None or y is None:
            raise IosUseError("tap requires x and y")
        px, py = _tap_pixels(float(x), float(y), payload.get("coordinateSpace"), screen_px)
        mapped = [["press", str(px), str(py), "--settle"]]
    elif action_type == "swipe":
        mapped = [_swipe_argv(payload, screen_px)]
    elif action_type in {"text", "type"}:
        value = str(payload.get("text") or payload.get("value") or "")
        mapped = [["type", value]]
    elif action_type == "key":
        mapped = [["press", f'label="{payload.get("text") or payload.get("name")}"', "--settle"]]
    elif action_type == "wait":
        seconds = float(payload.get("seconds") or payload.get("ms", 0) / 1000)
        mapped = [["__sleep", str(seconds)]]
    elif action_type == "launch":
        mapped = [["__open", "--relaunch"]]
    else:
        raise IosUseError(f"unsupported ios-use action for agent-device: {action_type or payload}")
    wait_after = action.get("waitAfter")
    if isinstance(wait_after, (int, float)) and wait_after > 0:
        mapped.append(["__sleep", str(float(wait_after))])
    return mapped


def _tap_pixels(
    x: float,
    y: float,
    coordinate_space: Any,
    screen_px: tuple[int, int] | None,
) -> tuple[int, int]:
    space = str(coordinate_space or "normalized")
    if space == "points":
        return int(round(x)), int(round(y))
    if space == "pixels":
        return int(round(x)), int(round(y))
    if not (0.0 <= x <= 1.0 and 0.0 <= y <= 1.0):
        raise IosUseError("normalized tap coordinates must be between 0.0 and 1.0")
    if not screen_px:
        raise IosUseError("normalized tap needs a screenshot size")
    width, height = screen_px
    return int(round(x * width)), int(round(y * height))


def _swipe_argv(payload: dict[str, Any], screen_px: tuple[int, int] | None) -> list[str]:
    if payload.get("x") is not None or payload.get("y") is not None:
        space = str(payload.get("coordinateSpace") or "points")
        x1, y1 = _tap_pixels(float(payload["x"]), float(payload["y"]), space, screen_px)
        x2, y2 = _tap_pixels(float(payload["x2"]), float(payload["y2"]), space, screen_px)
        return ["swipe", str(x1), str(y1), str(x2), str(y2)]
    direction = str(payload.get("direction") or "")
    if direction in {"left", "right", "left-edge", "right-edge"}:
        return ["gesture", "swipe", direction]
    if direction in {"up", "down"}:
        if not screen_px:
            raise IosUseError("vertical swipe needs a screenshot size")
        width, height = screen_px
        x = width // 2
        if direction == "up":
            return ["swipe", str(x), str(int(height * 0.8)), str(x), str(int(height * 0.2))]
        return ["swipe", str(x), str(int(height * 0.2)), str(x), str(int(height * 0.8))]
    raise IosUseError("swipe requires direction or x/y/x2/y2")


class AgentDeviceSession:
    def __init__(
        self,
        *,
        binary: str,
        bundle: str,
        device: str,
        session: str | None = None,
        runner: Callable[..., Any] = run_command,
    ):
        self.binary = binary
        self.bundle = bundle
        self.device = device
        self.session = session or session_name(device)
        self._run = runner
        self.opened = False

    def _common(self) -> list[str]:
        return ["--platform", "ios", "--device", self.device, "--session", self.session]

    def run(self, args: list[str], *, timeout: float = 90.0) -> str:
        if args and args[0] == "__sleep":
            time.sleep(float(args[1]))
            return "slept"
        argv = [self.binary, *args, *self._common()]
        result = self._run(argv, check=False, timeout=timeout)
        if result.returncode != 0:
            detail = (result.stderr or result.stdout).strip() or f"exit {result.returncode}"
            raise IosUseError(f"agent-device {' '.join(args)} failed: {detail}")
        return (result.stdout or "").strip()

    def ensure_open(self, *, relaunch: bool = False) -> str:
        args = ["open", self.bundle]
        if relaunch:
            args.append("--relaunch")
        output = self.run(args, timeout=180.0)
        self.opened = True
        return output

    def apply(self, action: dict[str, Any], *, screen_px: tuple[int, int] | None) -> list[str]:
        outputs: list[str] = []
        for argv in map_action(action, screen_px=screen_px):
            if argv[0] == "__open":
                outputs.append(self.ensure_open(relaunch="--relaunch" in argv))
                continue
            try:
                outputs.append(self.run(argv))
            except IosUseError as exc:
                if action.get("type") == "tapText" and "AMBIGUOUS_MATCH" in str(exc):
                    retry = ["press", selector_for_text(str(action.get("text") or ""), button_only=True), "--settle"]
                    outputs.append(self.run(retry))
                    continue
                raise
        return outputs
