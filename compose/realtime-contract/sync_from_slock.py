#!/usr/bin/env python3
"""Keep compose/realtime-contract/realtime_contract.json in step with botiverse/slock.

realtime_contract.json is the app's single source for Socket.IO event names and handshake error
strings; generate_realtime_contract.py turns it into the Kotlin (and Swift) constants. This script
fills the slock-derived parts of the JSON and checks the hand-kept parts against slock:

Generated from slock (``--write`` rewrites them, ``--check`` fails when they differ):

- ``handshakeErrors``: every ``connect_error`` message the socket auth middleware can send. The
  exported ``SOCKET_*_ERROR`` constants of ``packages/shared/src/socketHandshakeErrors.ts`` plus the
  ``next(new Error("..."))`` literals of ``packages/server/src/socket/index.ts`` (four of the six are
  still literals there). Keys: the constant name without ``SOCKET_`` / ``_ERROR``, else the message,
  in camelCase.
- ``slockSharedEvents``: every ``export const *_EVENT = "<event>"`` of ``packages/shared/src``.

Checked (``--check`` fails):

- every ``slockSharedEvents`` name is known to the app: a ``socketEvents`` value, a literal in
  ``RealtimeSocketEvents.kt``, or an ``unconsumedSlockEvents`` entry (hand-kept, with the reason);
- every ``unconsumedSlockEvents`` entry is still a slock shared event the app does not consume;
- every ``serverWireEvents`` name the app subscribes to still appears in slock's server or shared
  sources (a renamed or removed server event would otherwise silently never arrive), unless it is
  listed in ``acknowledgedUnemittedServerWireEvents`` with the reason it is kept.

Usage (SLOCK_SOURCE_REPO, or the SLOCK_REACT_REPO_DIR alias, points at a botiverse/slock checkout):

    python3 compose/realtime-contract/sync_from_slock.py --write    # update the JSON, then run
    python3 compose/realtime-contract/generate_realtime_contract.py # to regenerate the constants
    python3 compose/realtime-contract/sync_from_slock.py --check    # CI (realtime-contract-drift.yml)
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
CONTRACT = HERE / "realtime_contract.json"
SOCKET_EVENTS_KT = HERE.parent / "core/src/commonMain/kotlin/build/raft/app/data/realtime/RealtimeSocketEvents.kt"

HANDSHAKE_ERRORS_TS = "packages/shared/src/socketHandshakeErrors.ts"
SOCKET_INDEX_TS = "packages/server/src/socket/index.ts"
SHARED_SRC = "packages/shared/src"
SERVER_SRC = "packages/server/src"

EVENT_NAME = re.compile(r"^[a-z][a-z0-9_-]*(?:[:.][a-z0-9_-]+)+$")
SHARED_EVENT_CONST = re.compile(r'export\s+const\s+([A-Z][A-Z0-9_]*_EVENT)\s*=\s*"([^"]+)"')
HANDSHAKE_CONST = re.compile(r'export\s+const\s+(SOCKET_[A-Z0-9_]+_ERROR)\s*=\s*"([^"]+)"')
NEXT_ERROR_LITERAL = re.compile(r'next\(\s*new\s+Error\(\s*"([^"]+)"\s*\)\s*\)')
NEXT_ERROR_CONST = re.compile(r'next\(\s*new\s+Error\(\s*([A-Z][A-Z0-9_]+)\s*\)\s*\)')
KOTLIN_LITERAL = re.compile(r'const\s+val\s+\w+\s*=\s*"([^"]+)"')


def fail(message: str) -> "NoReturn":  # type: ignore[name-defined]
    print(f"sync_from_slock: {message}", file=sys.stderr)
    sys.exit(2)


def source_repo(arg: str | None) -> Path:
    raw = arg or os.environ.get("SLOCK_SOURCE_REPO") or os.environ.get("SLOCK_REACT_REPO_DIR")
    if not raw:
        fail("set SLOCK_SOURCE_REPO (or pass --source-repo) to a botiverse/slock checkout")
    repo = Path(raw).expanduser().resolve()
    for sub in (SHARED_SRC, SERVER_SRC):
        if not (repo / sub).is_dir():
            fail(f"{repo} is not a botiverse/slock checkout (no {sub})")
    return repo


def ts_sources(root: Path) -> list[Path]:
    return sorted(
        path
        for path in root.rglob("*.ts")
        if path.is_file() and not path.name.endswith((".test.ts", ".d.ts")) and "node_modules" not in path.parts
    )


def camel(words: list[str]) -> str:
    cleaned = [w for w in (re.sub(r"[^A-Za-z0-9]", "", word) for word in words) if w]
    if not cleaned:
        fail(f"cannot derive a key from {words!r}")
    return cleaned[0].lower() + "".join(w[:1].upper() + w[1:].lower() for w in cleaned[1:])


def handshake_errors(repo: Path) -> dict[str, str]:
    """{key: message} in a stable order: shared constants first (by name), then the literals."""
    shared_text = (repo / HANDSHAKE_ERRORS_TS).read_text()
    constants = dict(HANDSHAKE_CONST.findall(shared_text))
    if not constants:
        fail(f"no SOCKET_*_ERROR constants in {HANDSHAKE_ERRORS_TS}")
    index_text = (repo / SOCKET_INDEX_TS).read_text()
    used_constants = set(NEXT_ERROR_CONST.findall(index_text))
    unknown = sorted(name for name in used_constants if name.startswith("SOCKET_") and name not in constants)
    if unknown:
        fail(f"{SOCKET_INDEX_TS} rejects with constants this script cannot resolve: {', '.join(unknown)}")
    errors: dict[str, str] = {}
    for name in sorted(constants):
        key = camel(name.removeprefix("SOCKET_").removesuffix("_ERROR").split("_"))
        errors[key] = constants[name]
    for message in NEXT_ERROR_LITERAL.findall(index_text):
        if message in errors.values():
            continue
        errors[camel(message.split())] = message
    return errors


def shared_events(repo: Path) -> dict[str, str]:
    events: dict[str, str] = {}
    for path in ts_sources(repo / SHARED_SRC):
        for name, value in SHARED_EVENT_CONST.findall(path.read_text()):
            if not EVENT_NAME.match(value):
                continue
            previous = events.get(name)
            if previous is not None and previous != value:
                fail(f"{name} is declared twice in {SHARED_SRC} with different values ({previous!r}, {value!r})")
            events[name] = value
    if not events:
        fail(f"no *_EVENT constants found under {SHARED_SRC}")
    return dict(sorted(events.items(), key=lambda item: (item[1], item[0])))


def slock_source_text(repo: Path) -> str:
    """slock's server and shared sources (tests excluded), concatenated, for literal lookups."""
    return "\n".join(path.read_text() for sub in (SERVER_SRC, SHARED_SRC) for path in ts_sources(repo / sub))


def appears_in_slock(name: str, text: str) -> bool:
    return any(f"{quote}{name}{quote}" in text for quote in ('"', "'", "`"))


def app_known_events(contract: dict) -> set[str]:
    known = set(contract["socketEvents"].values())
    if SOCKET_EVENTS_KT.exists():
        known.update(KOTLIN_LITERAL.findall(SOCKET_EVENTS_KT.read_text()))
    return known


def check(contract: dict, repo: Path) -> list[str]:
    problems: list[str] = []
    expected_errors = handshake_errors(repo)
    if contract.get("handshakeErrors") != expected_errors:
        problems.append(
            "handshakeErrors differ from slock "
            f"(contract {contract.get('handshakeErrors')!r}, slock {expected_errors!r}); run --write"
        )
    expected_shared = shared_events(repo)
    if contract.get("slockSharedEvents") != expected_shared:
        added = sorted(set(expected_shared.items()) - set((contract.get("slockSharedEvents") or {}).items()))
        removed = sorted(set((contract.get("slockSharedEvents") or {}).items()) - set(expected_shared.items()))
        problems.append(f"slockSharedEvents differ from slock (new: {added}, gone: {removed}); run --write")
    known = app_known_events(contract)
    unconsumed = contract.get("unconsumedSlockEvents") or {}
    for name, value in expected_shared.items():
        if value not in known and value not in unconsumed:
            problems.append(
                f"slock shared event {value!r} ({name}) is neither consumed by the app (socketEvents) nor listed in "
                "unconsumedSlockEvents with a reason; decide which"
            )
    shared_values = set(expected_shared.values())
    for value, reason in unconsumed.items():
        if value in known:
            problems.append(f"unconsumedSlockEvents lists {value!r}, which the app now consumes; remove it")
        if value not in shared_values:
            problems.append(f"unconsumedSlockEvents lists {value!r}, which is no longer a slock shared event; remove it")
        if not str(reason).strip():
            problems.append(f"unconsumedSlockEvents {value!r} has no reason")
    text = slock_source_text(repo)
    acknowledged = contract.get("acknowledgedUnemittedServerWireEvents") or {}
    for key in contract["serverWireEvents"]:
        name = contract["socketEvents"][key]
        emitted = name in shared_values or appears_in_slock(name, text)
        if emitted and key in acknowledged:
            problems.append(f"acknowledgedUnemittedServerWireEvents lists {key} ({name!r}), which slock sends again; remove it")
        if not emitted and key not in acknowledged:
            problems.append(
                f"serverWireEvents {key} ({name!r}) no longer appears anywhere in slock {SERVER_SRC} or {SHARED_SRC}: "
                "the server stopped sending it or renamed it (fix the name, drop it, or acknowledge it with a reason)"
            )
    for key in acknowledged:
        if key not in contract["serverWireEvents"]:
            problems.append(f"acknowledgedUnemittedServerWireEvents lists {key}, which is not in serverWireEvents; remove it")
    return problems


def write(contract: dict, repo: Path) -> dict:
    updated = dict(contract)
    updated["handshakeErrors"] = handshake_errors(repo)
    updated["slockSharedEvents"] = shared_events(repo)
    updated.setdefault("unconsumedSlockEvents", {})
    return updated


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true", help="exit 1 when the contract drifted from slock")
    mode.add_argument("--write", action="store_true", help="rewrite the slock-derived sections of the contract")
    parser.add_argument("--source-repo", help="botiverse/slock checkout (default: $SLOCK_SOURCE_REPO)")
    args = parser.parse_args()
    repo = source_repo(args.source_repo)
    contract = json.loads(CONTRACT.read_text())
    if args.write:
        updated = write(contract, repo)
        CONTRACT.write_text(json.dumps(updated, indent=2, ensure_ascii=False) + "\n")
        problems = check(updated, repo)
        for problem in problems:
            print(f"warning: {problem}", file=sys.stderr)
        print(f"sync_from_slock: wrote {CONTRACT.relative_to(HERE.parent.parent)}")
        return
    problems = check(contract, repo)
    if problems:
        print("realtime_contract.json drifted from botiverse/slock:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        sys.exit(1)
    print("sync_from_slock: realtime_contract.json matches botiverse/slock")


if __name__ == "__main__":
    main()
