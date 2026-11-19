#!/usr/bin/env python3
"""Vendor or check botiverse/slock's runtime Raft capabilities and recommended runtimes.

Source: <slock>/packages/shared/src/runtimeCatalog.ts, `RUNTIME_RAFT_CAPABILITIES` (per runtime id:
midTurnSteering, subagents, compactionReported) and `RECOMMENDED_RUNTIME_IDS`. The runtime pickers
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

TABLE = re.compile(r"export const RUNTIME_RAFT_CAPABILITIES\b[^=]*=\s*\{(?P<body>.*?)\n\};", re.S)
ROW = re.compile(r'^\s*(?:"(?P<quoted>[^"]+)"|(?P<bare>[A-Za-z_][\w-]*))\s*:\s*\{(?P<flags>[^}]*)\},?\s*$')
FLAG = re.compile(r"(\w+)\s*:\s*(true|false)")
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


def parse(source: str) -> dict:
    table = TABLE.search(source)
    if not table:
        fail("RUNTIME_RAFT_CAPABILITIES not found; update the TABLE pattern")
    capabilities: dict[str, dict[str, bool]] = {}
    for line in table.group("body").splitlines():
        if not line.strip() or line.strip().startswith("//"):
            continue
        row = ROW.match(line)
        if not row:
            fail(f"unreadable RUNTIME_RAFT_CAPABILITIES row: {line.strip()!r}")
        runtime_id = row.group("quoted") or row.group("bare")
        flags = {name: value == "true" for name, value in FLAG.findall(row.group("flags"))}
        if set(flags) != set(FLAGS):
            fail(f"{runtime_id}: expected flags {FLAGS}, got {sorted(flags)}")
        capabilities[runtime_id] = {name: flags[name] for name in FLAGS}
    if not capabilities:
        fail("RUNTIME_RAFT_CAPABILITIES is empty")
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
