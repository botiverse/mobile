#!/usr/bin/env python3
"""Vendor or check botiverse/slock's notification level fixture.

Source: <slock>/docs/contracts/fixtures/notification-levels.json (the cases resolveEffectiveLevel,
the server policy and every client agree on). Copy:
compose/shared/src/commonTest/resources/contract-cases/notification-levels/ with the slock commit in
SOURCE_SHA; NotificationLevelContractCasesTest runs it.

    SLOCK_SOURCE_REPO=<slock> python3 scripts/sync_slock_notification_levels.py --ref <ref>   # re-vendor
    python3 scripts/sync_slock_notification_levels.py --check [--ref <ref>]                  # exit 1 on drift

--check compares bytes only. A source without the file (a ref from before the notification contract
landed) is reported and passes, so the scheduled check against staging is not red before it merges.
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

FIXTURE = "docs/contracts/fixtures/notification-levels.json"
ROOT = Path(__file__).resolve().parent.parent
DEST = ROOT / "compose/shared/src/commonTest/resources/contract-cases/notification-levels"


def git(repo: Path, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True)


def read_source(repo: Path, ref: str | None) -> tuple[str, bytes | None]:
    if ref:
        sha = git(repo, "rev-parse", f"{ref}^{{commit}}")
        if sha.returncode != 0:
            sys.exit(f"cannot resolve {ref} in {repo}")
        shown = git(repo, "show", f"{ref}:{FIXTURE}")
        return sha.stdout.decode().strip(), shown.stdout if shown.returncode == 0 else None
    head = git(repo, "rev-parse", "HEAD").stdout.decode().strip() or "worktree"
    path = repo / FIXTURE
    return head, path.read_bytes() if path.exists() else None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--ref")
    parser.add_argument("--source-repo")
    args = parser.parse_args()
    repo_arg = args.source_repo or os.environ.get("SLOCK_SOURCE_REPO") or os.environ.get("SLOCK_REACT_REPO_DIR")
    if not repo_arg:
        sys.exit("set SLOCK_SOURCE_REPO or pass --source-repo")
    repo = Path(repo_arg)
    sha, source = read_source(repo, args.ref)
    vendored_path = DEST / "notification-levels.json"
    pinned = (DEST / "SOURCE_SHA").read_text().strip() if (DEST / "SOURCE_SHA").exists() else "none"
    if source is None:
        print(f"notification-levels: {FIXTURE} is not in slock {sha} yet; nothing to compare (vendored from {pinned})")
        return 0
    if args.check:
        vendored = vendored_path.read_bytes() if vendored_path.exists() else b""
        if vendored != source:
            print(f"notification-levels: DRIFT. vendored from {pinned}, slock {sha} differs. Re-vendor with --ref.")
            return 1
        print(f"notification-levels: matches slock {sha} (vendored from {pinned})")
        return 0
    DEST.mkdir(parents=True, exist_ok=True)
    vendored_path.write_bytes(source)
    (DEST / "SOURCE_SHA").write_text(sha + "\n")
    print(f"notification-levels: vendored from slock {sha}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
