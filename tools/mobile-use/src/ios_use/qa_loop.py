"""Persistent QaAcceptanceLoopTests client used by ios-use interactions.

A one-shot XCTest process (IosUseInteractionTests) can see Kuikly Home nodes
and still fail to deliver taps. The in-repo path that does tap Kuikly is this
file protocol: one xcodebuild test keeps XCUIApplication alive and serves
cmd-<n>.json -> res-<n>.json. Sequential ios-use invocations reuse that loop.
"""

from __future__ import annotations

import fcntl
import json
import os
import signal
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator


QA_LOOP_TEST = "iosAppUITests/QaAcceptanceLoopTests"
DEFAULT_IDLE_TIMEOUT = "1800"
READY_WAIT_SECONDS = 600.0
ACTION_WAIT_SECONDS = 90.0


def loop_root(derived_data: Path, device: str) -> Path:
    return Path(derived_data) / "ios-use-qa-loop" / device


def io_dir_for(root: Path) -> Path:
    return root / "io"


def pid_path_for(root: Path) -> Path:
    return root / "loop.pid"


def log_path_for(root: Path) -> Path:
    return root / "loop.log"


def lock_path_for(root: Path) -> Path:
    return root / "lock"


def pid_alive(pid: int) -> bool:
    if pid <= 0:
        return False
    try:
        os.kill(pid, 0)
    except OSError:
        return False
    return True


def read_pid(path: Path) -> int | None:
    try:
        raw = path.read_text(encoding="utf-8").strip()
    except FileNotFoundError:
        return None
    if not raw.isdigit():
        return None
    return int(raw)


def next_seq(io_dir: Path) -> int:
    highest = 0
    if io_dir.exists():
        for path in io_dir.glob("cmd-*.json"):
            suffix = path.name[len("cmd-") : -len(".json")]
            if suffix.isdigit():
                highest = max(highest, int(suffix))
    return highest + 1


def loop_sources_newer(workspace: Path, ready: Path) -> bool:
    swift = workspace.parent / "iosAppUITests" / "QaAcceptanceLoopTests.swift"
    try:
        return swift.stat().st_mtime > ready.stat().st_mtime
    except FileNotFoundError:
        return False


@contextmanager
def exclusive_lock(path: Path) -> Iterator[None]:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a+") as handle:
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def map_ios_use_action(action: dict[str, Any]) -> list[dict[str, Any]]:
    """Map one ios-use action onto one or more QA-loop protocol actions."""
    payload = {key: value for key, value in action.items() if key != "waitAfter"}
    action_type = payload.get("type")
    mapped: list[dict[str, Any]]
    if action_type == "swipe" and (payload.get("x") is not None or payload.get("y") is not None):
        mapped = [
            {
                "type": "drag",
                "x": payload.get("x"),
                "y": payload.get("y"),
                "x2": payload.get("x2"),
                "y2": payload.get("y2"),
                "holdSeconds": 0.05,
                **(
                    {"coordinateSpace": payload["coordinateSpace"]}
                    if payload.get("coordinateSpace")
                    else {}
                ),
            }
        ]
    else:
        mapped = [payload]
    wait_after = action.get("waitAfter")
    if isinstance(wait_after, (int, float)) and wait_after > 0:
        mapped.append({"type": "wait", "seconds": float(wait_after)})
    return mapped


def send_action(io_dir: Path, action: dict[str, Any], *, timeout: float = ACTION_WAIT_SECONDS) -> Any:
    io_dir.mkdir(parents=True, exist_ok=True)
    seq = next_seq(io_dir)
    cmd = io_dir / f"cmd-{seq}.json"
    res = io_dir / f"res-{seq}.json"
    tmp = Path(str(cmd) + ".tmp")
    tmp.write_text(json.dumps(action, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    tmp.replace(cmd)
    deadline = time.time() + timeout
    while time.time() < deadline:
        if res.exists():
            try:
                out = json.loads(res.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                time.sleep(0.05)
                continue
            if not out.get("ok"):
                raise _error(f"ios harness: {out.get('error') or 'unknown error'}")
            return out.get("data")
        time.sleep(0.15)
    raise _error(f"ios harness timeout on {action.get('type')} (step {seq})")


def stop_pid(pid: int, *, wait_seconds: float = 5.0) -> None:
    if not pid_alive(pid):
        return
    try:
        os.kill(pid, signal.SIGTERM)
    except OSError:
        return
    deadline = time.time() + wait_seconds
    while time.time() < deadline and pid_alive(pid):
        time.sleep(0.1)
    if pid_alive(pid):
        try:
            os.kill(pid, signal.SIGKILL)
        except OSError:
            return


def wait_ready(io_dir: Path, *, pid: int, log_path: Path, timeout: float = READY_WAIT_SECONDS) -> None:
    ready = io_dir / "ready.json"
    deadline = time.time() + timeout
    while time.time() < deadline:
        if ready.exists():
            return
        if not pid_alive(pid):
            tail = _log_tail(log_path)
            raise _error(f"QA loop process {pid} exited before ready.json{tail}")
        time.sleep(0.5)
    raise _error(f"QA loop never became ready (no ready.json after {int(timeout)}s){_log_tail(log_path)}")


def _error(message: str) -> Exception:
    from .simctl import IosUseError

    return IosUseError(message)


def _log_tail(log_path: Path, lines: int = 40) -> str:
    try:
        text = log_path.read_text(encoding="utf-8", errors="replace")
    except FileNotFoundError:
        return ""
    tail = "\n".join(text.strip().splitlines()[-lines:])
    return f"\n{tail}" if tail else ""
