#!/usr/bin/env python3
"""Vendor or check botiverse/slock's runtime Raft capabilities and recommended runtimes.

Source: <slock>/packages/shared/src/runtimeCatalog.ts. Since slock #16178 `RUNTIME_RAFT_CAPABILITIES` is
derived, not a literal: per runtime id, the record of `RUNTIME_RAFT_CAPABILITIES_BY_HOST` on the
runtime's default host (`RUNTIME_DEFAULT_CAPABILITY_HOST`, "legacy" when absent), cut to the picker
flags midTurnSteering, subagents, compactionReported. This script does the same derivation. Plus
`RECOMMENDED_RUNTIME_IDS`. The runtime pickers
read them for the Recommended and Limited badges and the capability hint under the field (web
utils/runtimeCapabilityHint.ts, slock #15551 / #15599, #triage:#534). Copy:

    compose/shared/src/commonTest/resources/contract-cases/runtime-raft-capabilities/
        runtime-raft-capabilities.json   {"recommendedRuntimeIds": [...], "runtimeRaftCapabilities": {id: {...}}}
        SOURCE_SHA                       the slock commit it was read from

The app's table (RuntimeRaftCapabilities.kt, `RuntimeRaftCapabilityTable`) must equal the copy and
carry the same SHA: RuntimeRaftCapabilitiesContractTest checks both.

    SLOCK_SOURCE_REPO=<slock> python3 scripts/sync_slock_runtime_capabilities.py --ref origin/staging   # re-vendor
    python3 scripts/sync_slock_runtime_capabilities.py --check [--ref <ref>]                           # exit 1 on drift

--check compares the parsed values only; SOURCE_SHA is reported, not compared (slock moves on every
merge, the table does not). If slock moves or reshapes either symbol the parser fails loudly
instead of returning an empty table. The daily drift job (.github/workflows/contract-cases-drift.yml)
runs --check against slock `staging`.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

SOURCE = "packages/shared/src/runtimeCatalog.ts"
ROOT = Path(__file__).resolve().parent.parent
DEST = ROOT / "compose/shared/src/commonTest/resources/contract-cases/runtime-raft-capabilities"
FIXTURE = DEST / "runtime-raft-capabilities.json"
SHA_FILE = DEST / "SOURCE_SHA"
FLAGS = ("midTurnSteering", "subagents", "compactionReported")

BY_HOST = "RUNTIME_RAFT_CAPABILITIES_BY_HOST"
DEFAULT_HOST = "RUNTIME_DEFAULT_CAPABILITY_HOST"
FALLBACK_HOST = "legacy"  # getRuntimeDefaultCapabilityHost's answer for a runtime missing from DEFAULT_HOST
RECOMMENDED = re.compile(r"export const RECOMMENDED_RUNTIME_IDS\b[^=]*=\s*\[(?P<body>[^\]]*)\]")


def fail(message: str) -> "NoReturn":  # type: ignore[name-defined]
    print(f"sync_slock_runtime_capabilities: {message}", file=sys.stderr)
    sys.exit(2)


def git(repo: Path, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True)


def read_source(repo: Path, ref: str | None) -> tuple[str, str]:
    if ref:
        sha = git(repo, "rev-parse", f"{ref}^{{commit}}")
        if sha.returncode != 0:
            fail(f"cannot resolve {ref} in {repo}")
        shown = git(repo, "show", f"{ref}:{SOURCE}")
        if shown.returncode != 0:
            fail(f"{SOURCE} is not in slock {ref}")
        return sha.stdout.decode().strip(), shown.stdout.decode()
    head = git(repo, "rev-parse", "HEAD").stdout.decode().strip() or "worktree"
    path = repo / SOURCE
    if not path.exists():
        fail(f"{path} does not exist")
    return head, path.read_text()


class _Literal:
    """A small reader for the TypeScript object literals of runtimeCatalog.ts: string, boolean and
    number values, nested objects, identifier or quoted keys, comments. Anything else (spreads,
    computed keys, calls) fails loudly, so a reshape upstream cannot silently empty the table."""

    def __init__(self, text: str, start: int):
        self.text, self.i = text, start

    def _skip(self) -> None:
        t = self.text
        while self.i < len(t):
            if t[self.i].isspace():
                self.i += 1
            elif t.startswith("//", self.i):
                end = t.find("\n", self.i)
                self.i = len(t) if end < 0 else end + 1
            elif t.startswith("/*", self.i):
                end = t.find("*/", self.i + 2)
                self.i = len(t) if end < 0 else end + 2
            else:
                return

    def value(self):
        self._skip()
        t, c = self.text, self.text[self.i]
        if c == "{":
            return self.obj()
        if c in "\"'":
            end = t.index(c, self.i + 1)
            raw = t[self.i + 1:end]
            self.i = end + 1
            return raw
        m = re.compile(r"true|false|-?\d+(?:\.\d+)?").match(t, self.i)
        if not m:
            fail(f"unsupported value near {t[self.i:self.i + 40]!r}")
        self.i = m.end()
        word = m.group(0)
        return word == "true" if word in ("true", "false") else float(word)

    def obj(self) -> dict:
        t = self.text
        assert t[self.i] == "{"
        self.i += 1
        out: dict = {}
        while True:
            self._skip()
            if t[self.i] == "}":
                self.i += 1
                return out
            m = re.compile(r'"([^"]+)"|\'([^\']+)\'|([A-Za-z_$][\w$-]*)').match(t, self.i)
            if not m:
                fail(f"unsupported key near {t[self.i:self.i + 40]!r}")
            key = m.group(1) or m.group(2) or m.group(3)
            self.i = m.end()
            self._skip()
            if t[self.i] != ":":
                fail(f"expected ':' after {key!r}")
            self.i += 1
            out[key] = self.value()
            self._skip()
            if t[self.i] == ",":
                self.i += 1


def _const_object(source: str, name: str) -> dict:
    m = re.search(rf"export const {name}\b[^=]*=\s*\{{", source)
    if not m:
        fail(f"{name} not found; update the parser")
    return _Literal(source, m.end() - 1).obj()


def parse(source: str) -> dict:
    by_host = _const_object(source, BY_HOST)
    default_host = _const_object(source, DEFAULT_HOST)
    capabilities: dict[str, dict[str, bool]] = {}
    for runtime_id, hosts in by_host.items():
        if not isinstance(hosts, dict):
            fail(f"{BY_HOST}.{runtime_id} is not an object")
        default = default_host.get(runtime_id, FALLBACK_HOST)
        record = hosts.get(default)
        if not isinstance(record, dict):
            fail(f"{runtime_id}: no record for its default host {default!r}")
        flags = {name: record.get(name) for name in FLAGS}
        if not all(isinstance(value, bool) for value in flags.values()):
            fail(f"{runtime_id}: expected boolean flags {FLAGS}, got {flags}")
        capabilities[runtime_id] = flags
    if not capabilities:
        fail(f"{BY_HOST} is empty")
    recommended = RECOMMENDED.search(source)
    if not recommended:
        fail("RECOMMENDED_RUNTIME_IDS not found; update the RECOMMENDED pattern")
    ids = re.findall(r'"([^"]+)"', recommended.group("body"))
    if not ids:
        fail("RECOMMENDED_RUNTIME_IDS is empty")
    return {"recommendedRuntimeIds": ids, "runtimeRaftCapabilities": capabilities}


def render(value: dict) -> str:
    return json.dumps(value, indent=2) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--ref")
    parser.add_argument("--source-repo")
    args = parser.parse_args()
    repo_arg = args.source_repo or os.environ.get("SLOCK_SOURCE_REPO") or os.environ.get("SLOCK_REACT_REPO_DIR")
    if not repo_arg:
        fail("set SLOCK_SOURCE_REPO or pass --source-repo")
    sha, source = read_source(Path(repo_arg), args.ref)
    upstream = parse(source)
    pinned = SHA_FILE.read_text().strip() if SHA_FILE.exists() else "none"
    if args.check:
        vendored = json.loads(FIXTURE.read_text()) if FIXTURE.exists() else None
        if vendored != upstream:
            print(f"runtime-raft-capabilities: DRIFT. vendored from {pinned}, slock {sha} differs:")
            print(render(upstream))
            print("Re-vendor with --ref, update RuntimeRaftCapabilityTable (and SLOCK_SHA) to match.")
            return 1
        print(f"runtime-raft-capabilities: matches slock {sha} (vendored from {pinned})")
        return 0
    DEST.mkdir(parents=True, exist_ok=True)
    FIXTURE.write_text(render(upstream))
    SHA_FILE.write_text(sha + "\n")
    print(f"runtime-raft-capabilities: vendored from slock {sha}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
