#!/usr/bin/env python3
"""Vendor botiverse/slock's shared unread contract cases into the app's test resources.

botiverse/slock keeps the unread/thread rules both clients must agree on as plain JSON cases under
`packages/shared/contract-cases/unread/` (README.md, case.schema.json, cases/*.json; task
#proj-mobile:#70). The app runs a pinned copy in its unit tests
(UnreadContractCasesTest, :shared:testDebugUnitTest):

    compose/shared/src/commonTest/resources/contract-cases/unread/
        SOURCE_SHA            the slock commit the copy was taken from
        README.md, case.schema.json, cases/*.json   byte-exact copies

Usage (SLOCK_SOURCE_REPO, or the SLOCK_REACT_REPO_DIR alias, points at a botiverse/slock checkout):

    python3 scripts/sync_slock_contract_cases.py                  # copy from the checkout's work tree
    python3 scripts/sync_slock_contract_cases.py --ref origin/staging   # copy from a commit instead
    python3 scripts/sync_slock_contract_cases.py --check [--ref ...]    # exit 1 if the copy drifted

`--check` compares file names and bytes only; SOURCE_SHA is reported, not compared (slock moves on
every merge, the cases do not). The daily drift job (.github/workflows/contract-cases-drift.yml)
runs `--check` against slock `staging`.
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

SOURCE_SUBDIR = "packages/shared/contract-cases/unread"
REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_DEST = REPO_ROOT / "compose/shared/src/commonTest/resources/contract-cases/unread"
SHA_FILE = "SOURCE_SHA"


def fail(message: str) -> "NoReturn":  # type: ignore[name-defined]
    print(f"sync_slock_contract_cases: {message}", file=sys.stderr)
    sys.exit(2)


def git(repo: Path, *args: str, binary: bool = False):
    result = subprocess.run(
        ["git", "-C", str(repo), *args],
        check=False,
        capture_output=True,
    )
    if result.returncode != 0:
        fail(f"git {' '.join(args)} failed: {result.stderr.decode(errors='replace').strip()}")
    return result.stdout if binary else result.stdout.decode().strip()


def source_repo(arg: str | None) -> Path:
    raw = arg or os.environ.get("SLOCK_SOURCE_REPO") or os.environ.get("SLOCK_REACT_REPO_DIR")
    if not raw:
        fail("set SLOCK_SOURCE_REPO (or pass --source-repo) to a botiverse/slock checkout")
    repo = Path(raw).expanduser().resolve()
    if not (repo / "packages/shared").is_dir():
        fail(f"{repo} is not a botiverse/slock checkout (no packages/shared)")
    return repo


def read_source(repo: Path, ref: str | None) -> tuple[str, dict[str, bytes]]:
    """(commit sha, {relative path: bytes}) of the upstream cases directory."""
    if ref is None:
        sha = git(repo, "rev-parse", "HEAD")
        dirty = git(repo, "status", "--porcelain", "--", SOURCE_SUBDIR)
        if dirty:
            fail(f"{SOURCE_SUBDIR} has uncommitted changes in {repo}; commit them or pass --ref")
        root = repo / SOURCE_SUBDIR
        if not root.is_dir():
            fail(f"{root} does not exist at {sha}")
        files = {
            path.relative_to(root).as_posix(): path.read_bytes()
            for path in sorted(root.rglob("*"))
            if path.is_file()
        }
        return sha, files
    sha = git(repo, "rev-parse", "--verify", f"{ref}^{{commit}}")
    names = git(repo, "ls-tree", "-r", "--name-only", sha, "--", SOURCE_SUBDIR).splitlines()
    if not names:
        fail(f"{SOURCE_SUBDIR} does not exist at {ref} ({sha})")
    files = {
        name[len(SOURCE_SUBDIR) + 1 :]: git(repo, "show", f"{sha}:{name}", binary=True)
        for name in sorted(names)
    }
    return sha, files


def read_vendored(dest: Path) -> dict[str, bytes]:
    if not dest.is_dir():
        return {}
    return {
        path.relative_to(dest).as_posix(): path.read_bytes()
        for path in sorted(dest.rglob("*"))
        if path.is_file() and path.relative_to(dest).as_posix() != SHA_FILE
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="exit 1 if the vendored copy differs from the source")
    parser.add_argument("--ref", help="read the cases at this slock commit/ref instead of the work tree")
    parser.add_argument("--source-repo", help="botiverse/slock checkout (default: $SLOCK_SOURCE_REPO)")
    parser.add_argument("--dest", type=Path, default=DEFAULT_DEST, help=argparse.SUPPRESS)
    args = parser.parse_args()

    repo = source_repo(args.source_repo)
    sha, upstream = read_source(repo, args.ref)
    if not upstream.get("case.schema.json") or not any(name.startswith("cases/") for name in upstream):
        fail(f"{SOURCE_SUBDIR} at {sha} has no case.schema.json or no cases/")
    dest: Path = args.dest
    vendored = read_vendored(dest)
    sha_path = dest / SHA_FILE
    vendored_sha = sha_path.read_text().strip() if sha_path.is_file() else "(none)"

    added = sorted(set(upstream) - set(vendored))
    removed = sorted(set(vendored) - set(upstream))
    changed = sorted(name for name in set(upstream) & set(vendored) if upstream[name] != vendored[name])

    if args.check:
        if not (added or removed or changed):
            print(f"Unread contract cases match botiverse/slock@{sha} ({len(upstream)} files; vendored from {vendored_sha}).")
            return 0
        print(
            f"Unread contract cases drifted: vendored from botiverse/slock@{vendored_sha}, "
            f"source is botiverse/slock@{sha}.",
            file=sys.stderr,
        )
        for label, names in (("new upstream", added), ("removed upstream", removed), ("changed", changed)):
            for name in names:
                print(f"  {label}: {name}", file=sys.stderr)
        print(
            "Re-vendor with: SLOCK_SOURCE_REPO=<slock checkout> python3 scripts/sync_slock_contract_cases.py "
            "[--ref origin/staging], then run UnreadContractCasesTest.",
            file=sys.stderr,
        )
        return 1

    if dest.exists():
        shutil.rmtree(dest)
    for name, data in upstream.items():
        target = dest / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    sha_path.write_text(sha + "\n")
    print(
        f"Vendored {len(upstream)} files from botiverse/slock@{sha} into {dest.relative_to(REPO_ROOT)} "
        f"(+{len(added)} -{len(removed)} ~{len(changed)})."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
