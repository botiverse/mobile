#!/usr/bin/env python3
"""Refresh the pinned list of server enum values the app maps (ServerEnumContractTest).

Every enum-like value the server can send and the app turns into a typed value (channel types,
Activity row kinds, notification kinds, message sender/types, task statuses, runtime provider
kinds, runtime auth runtimes, pinned kinds, agent activity states, object kinds, socket event
names) is read straight from a botiverse/slock commit and written to

    compose/shared/src/commonTest/resources/contract-cases/server-enums/
        server-enums.json   {"sourceSha", "domains": {name: {"source", "values"}}}
        SOURCE_SHA          the slock commit the values were read from

`ServerEnumContractTest` (androidUnitTest) then checks that every value maps to a non-null app
value or to an explicit, logged fallback. A new server value therefore fails CI on the next
refresh until the app handles it (or the test records why it is skipped on purpose).

Usage (SLOCK_SOURCE_REPO, or the SLOCK_REACT_REPO_DIR alias, points at a botiverse/slock checkout):

    git -C "$SLOCK_SOURCE_REPO" fetch origin
    python3 scripts/refresh_server_enum_fixture.py --ref origin/staging         # rewrite the fixture
    python3 scripts/refresh_server_enum_fixture.py --ref origin/staging --check # exit 1 on drift

Only `origin/staging` (or an exact commit) is read, never the work tree. Each domain names the
file and symbol it comes from; if slock moves a symbol, the extractor fails loudly instead of
returning an empty list. Fix the `DOMAINS` entry and run again.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEST = REPO_ROOT / "compose/shared/src/commonTest/resources/contract-cases/server-enums"
FIXTURE = DEST / "server-enums.json"
SHA_FILE = DEST / "SOURCE_SHA"

LITERAL = re.compile(r'"([^"\\]+)"')

# name -> (extractor, file, anchor regex[, extra])
#   union        the string literals on the anchor line (a TS union / const array on one line)
#   enum         the literals inside `enum: [...]` on the anchor line (drizzle column)
#   first_kind   the literals of the first `kind:` line after each anchor (one arm per block)
#   block_kinds  the literals of every `kind:` line in the block that starts at the anchor
#   interface_keys  the top-level keys of an `interface X { a: ...; b: ... }` block
#   socket_events   every `.emit("x"` in server sources and every `*_EVENT = "x"` in shared/server
DOMAINS: dict[str, tuple] = {
    "channel_types": ("enum", "packages/server/src/db/schema.ts", r'^\s*type: text\("type", \{ enum: \["channel"'),
    "inbox_kinds": (
        "first_kind",
        "packages/server/src/services/channelService.ts",
        [r"^export type InboxItem =", r"^export type ThreadInboxItem =", r"^export type TaskConversationInboxItem ="],
        ("packages/shared/src/activityObjectsContract.ts", [r"^export interface ObjectInboxItem"]),
    ),
    "inbox_channel_types": (
        "union",
        "packages/server/src/services/channelService.ts",
        r'^\s*channelType: "channel" \| "private" \| "joint" \| "dm";',
    ),
    "message_sender_types": ("enum", "packages/server/src/db/schema.ts", r'^\s*senderType: text\("sender_type", \{ enum:'),
    "message_types": ("enum", "packages/server/src/db/schema.ts", r'^\s*messageType: text\("message_type", \{ enum:'),
    "task_statuses": ("union", "packages/shared/src/index.ts", r"^export const TASK_STATUSES = \["),
    "notification_surface_kinds": (
        "union",
        "packages/server/src/services/notifications/types.ts",
        r"^export type NotificationSurfaceKind =",
    ),
    "runtime_provider_kinds_claude": ("block_kinds", "packages/shared/src/index.ts", r"^export type ClaudeRuntimeProviderConfig ="),
    "runtime_provider_kinds_pi": ("block_kinds", "packages/shared/src/index.ts", r"^export type PiRuntimeProviderConfig ="),
    "runtime_provider_kinds_builtin": ("block_kinds", "packages/shared/src/index.ts", r"^export type BuiltInRuntimeProviderConfig ="),
    "runtime_auth_runtimes": ("union", "packages/shared/src/runtimeAuth.ts", r"^export const RUNTIME_AUTH_RUNTIMES = \["),
    "sidebar_pinned_kinds": ("first_kind", "packages/server/src/services/serverService.ts", [r"^export interface SidebarPinnedRef"]),
    "agent_activity_states": ("union", "packages/shared/src/agentActivitySubscriptions.ts", r"^export type AgentActivityCoarseState ="),
    "object_kinds": ("interface_keys", "packages/shared/src/webObjectsContract.ts", r"^export interface ObjectSummaryKinds \{"),
    "socket_events": ("socket_events",),
}


def fail(message: str) -> "NoReturn":  # type: ignore[name-defined]
    print(f"refresh_server_enum_fixture: {message}", file=sys.stderr)
    sys.exit(2)


def git(repo: Path, *args: str) -> str:
    result = subprocess.run(["git", "-C", str(repo), *args], check=False, capture_output=True, text=True)
    if result.returncode != 0:
        fail(f"git {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def show(repo: Path, ref: str, path: str) -> list[str]:
    return git(repo, "show", f"{ref}:{path}").splitlines()


def find(lines: list[str], anchor: str, path: str) -> int:
    pattern = re.compile(anchor)
    for index, line in enumerate(lines):
        if pattern.search(line):
            return index
    fail(f"{path}: anchor not found: {anchor}")


def block(lines: list[str], start: int) -> list[str]:
    out = [lines[start]]
    for line in lines[start + 1 :]:
        if line.startswith(("export ", "/**", "//")):
            break
        out.append(line)
    return out


def extract(repo: Path, ref: str, name: str, spec: tuple) -> tuple[str, list[str]]:
    kind = spec[0]
    if kind == "socket_events":
        return extract_socket_events(repo, ref)
    path = spec[1]
    lines = show(repo, ref, path)
    if kind == "union":
        index = find(lines, spec[2], path)
        line = lines[index].split("=", 1)[1] if "=" in lines[index] else lines[index].split(":", 1)[1]
        values = LITERAL.findall(line.split(" as ", 1)[0])
        source = f"{path}:{index + 1}"
    elif kind == "enum":
        index = find(lines, spec[2], path)
        match = re.search(r"enum:\s*\[([^\]]*)\]", lines[index])
        if not match:
            fail(f"{path}:{index + 1}: no enum list")
        values = LITERAL.findall(match.group(1))
        source = f"{path}:{index + 1}"
    elif kind == "first_kind":
        values, sources = [], []
        groups = [(path, lines, spec[2])]
        if len(spec) > 3:
            extra_path, extra_anchors = spec[3]
            groups.append((extra_path, show(repo, ref, extra_path), extra_anchors))
        for group_path, group_lines, anchors in groups:
            for anchor in anchors:
                start = find(group_lines, anchor, group_path)
                kind_lines = [(start + offset, line) for offset, line in enumerate(block(group_lines, start)) if re.match(r"^\s*kind:", line)]
                if not kind_lines:
                    fail(f"{group_path}:{start + 1}: no kind field in block")
                line_no, line = kind_lines[0]
                values += LITERAL.findall(line)
                sources.append(f"{group_path}:{line_no + 1}")
        source = ", ".join(sources)
    elif kind == "block_kinds":
        start = find(lines, spec[2], path)
        values = []
        for line in block(lines, start):
            for match in re.finditer(r'\bkind:\s*((?:"[^"]+"\s*\|?\s*)+)', line):
                values += LITERAL.findall(match.group(1))
        source = f"{path}:{start + 1}"
    elif kind == "interface_keys":
        start = find(lines, spec[2], path)
        values = []
        for line in block(lines, start)[1:]:
            if line.startswith("}"):
                break
            match = re.match(r"^  ([A-Za-z_][A-Za-z0-9_]*)\??:", line)
            if match:
                values.append(match.group(1))
        source = f"{path}:{start + 1}"
    else:
        fail(f"unknown extractor {kind} for {name}")
    values = list(dict.fromkeys(values))
    if not values:
        fail(f"{name}: no values extracted from {source}")
    return source, values


SOCKET_EVENT_NAME = re.compile(r"^[a-z][a-z0-9_]*(?:[:.\-][a-z0-9_\-]+)+$|^(?:heartbeat|connection|finish)$")


def extract_socket_events(repo: Path, ref: str) -> tuple[str, list[str]]:
    emits = git(
        repo, "grep", "-hoE", r'\.emit\(\s*"[a-zA-Z_:.\-]+"', ref, "--", "packages/server/src/*.ts", ":!*.test.ts"
    )
    constant = r'_EVENT\s*=\s*"[^"]+"'
    shared_constants = git(repo, "grep", "-hoE", constant, ref, "--", "packages/shared/src", ":!*.test.ts")
    # Server `*_EVENT` constants also name telemetry events (`server.foo.bar`); socket events there
    # always carry a `:` (`notification:push`).
    server_constants = [
        line
        for line in git(repo, "grep", "-hoE", constant, ref, "--", "packages/server/src", ":!*.test.ts").splitlines()
        if ":" in (LITERAL.findall(line) or [""])[-1]
    ]
    values = set()
    for line in emits.splitlines() + shared_constants.splitlines() + server_constants:
        found = LITERAL.findall(line)
        if found and SOCKET_EVENT_NAME.match(found[-1]):
            values.add(found[-1])
    if not values:
        fail("socket_events: nothing extracted")
    # A lower bound: an event emitted through a variable that is not a `*_EVENT` constant is not seen.
    return 'packages/server/src `.emit("…")` + packages/shared/src `*_EVENT` + packages/server/src `*_EVENT` with a ":"', sorted(values)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--ref", default="origin/staging", help="slock commit-ish to read (default origin/staging)")
    parser.add_argument("--check", action="store_true", help="exit 1 when the fixture's values differ")
    args = parser.parse_args()
    repo_env = os.environ.get("SLOCK_SOURCE_REPO") or os.environ.get("SLOCK_REACT_REPO_DIR")
    if not repo_env:
        fail("set SLOCK_SOURCE_REPO to a botiverse/slock checkout")
    repo = Path(repo_env)
    sha = git(repo, "rev-parse", f"{args.ref}^{{commit}}").strip()
    domains = {}
    for name, spec in DOMAINS.items():
        source, values = extract(repo, sha, name, spec)
        domains[name] = {"source": source, "values": values}
    fixture = {
        "source": "botiverse/slock",
        "sourceSha": sha,
        "generatedBy": "scripts/refresh_server_enum_fixture.py",
        "domains": domains,
    }
    if args.check:
        current = json.loads(FIXTURE.read_text())
        drift = [
            name
            for name in sorted(set(domains) | set(current.get("domains", {})))
            if domains.get(name, {}).get("values") != current.get("domains", {}).get(name, {}).get("values")
        ]
        if drift:
            print(f"server-enums drifted from slock {sha}: {', '.join(drift)}")
            sys.exit(1)
        print(f"server-enums match slock {sha}")
        return
    DEST.mkdir(parents=True, exist_ok=True)
    FIXTURE.write_text(json.dumps(fixture, indent=2) + "\n")
    SHA_FILE.write_text(sha + "\n")
    print(f"wrote {FIXTURE.relative_to(REPO_ROOT)} from slock {sha}")


if __name__ == "__main__":
    main()
