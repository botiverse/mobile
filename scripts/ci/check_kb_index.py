#!/usr/bin/env python3
"""Admission gate for the knowledge-base index (docs/kb/README.md).

The index is prose, not a generated artifact, so nothing notices when it drifts.
Two directions can break, and they fail differently:

* **unindexed article** — a file exists under ``docs/kb/`` with no row. The
  article ships reachable only by direct link. New articles make this visible
  to their author, so it tends to be caught by the person who just added one.
* **dangling row** — a row points at a file that does not exist. This survives
  far longer: deleting an article is rare and nobody re-reads the table
  afterwards, so the row keeps asserting that something exists long after it
  stopped being true.

The checker therefore reports which side is broken rather than a bare mismatch.

This only verifies structure. It does not judge whether an article is accurate,
current, or safe to publish; ``verified_at`` and ``status`` are conventions the
reviewer enforces, not fields this script can validate.

Run ``--self-test`` to exercise mutation arms against small fixtures; without
the flag the checker reads the current repository and fails closed when the
index or an article is unreadable.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
from typing import Iterable


REPO_ROOT = Path(__file__).resolve().parents[2]
KB_DIR = "docs/kb"
INDEX_NAME = "README.md"

# `| [Title](file.md) | area | owner | date | status |`
LINK_RE = re.compile(r"\[[^\]]*\]\(([^)\s]+\.md)\)")
# Rows must carry five cells so owner/verified-at/status cannot be silently dropped.
ROW_RE = re.compile(r"^\|\s*\[[^\]]*\]\([^)]+\.md\)\s*\|")


def articles(kb_dir: Path) -> list[str]:
    return sorted(
        path.name
        for path in kb_dir.glob("*.md")
        if path.is_file() and path.name != INDEX_NAME
    )


def indexed(index_text: str) -> list[str]:
    return sorted(name for name in LINK_RE.findall(index_text))


def index_rows(index_text: str) -> list[str]:
    """Only table rows count; a prose sentence mentioning a .md file is not an entry."""
    return [line for line in index_text.splitlines() if ROW_RE.match(line)]


def check(kb_dir: Path, index_text: str) -> list[str]:
    """Return human-readable violations; empty means the index is consistent."""
    on_disk = articles(kb_dir)
    rows = index_rows(index_text)
    linked = indexed(index_text)

    violations: list[str] = []

    missing = [name for name in on_disk if name not in linked]
    for name in missing:
        violations.append(
            f"unindexed article: {KB_DIR}/{name} exists but has no row in {KB_DIR}/{INDEX_NAME}"
        )

    dangling = [name for name in linked if name not in on_disk]
    for name in dangling:
        violations.append(
            f"dangling row: {KB_DIR}/{INDEX_NAME} points at {name}, which does not exist"
        )

    # A link that is not a table row is invisible to readers scanning the table.
    if len(linked) != len(rows):
        violations.append(
            "malformed index: "
            f"{len(linked)} links but {len(rows)} table rows; "
            "each article needs one row of `| [Title](file.md) | area | owner | verified at | status |`"
        )

    duplicates = sorted({name for name in linked if linked.count(name) > 1})
    for name in duplicates:
        violations.append(f"duplicate row: {name} is indexed more than once")

    return violations


def render(violations: Iterable[str]) -> str:
    lines = list(violations)
    if not lines:
        return "kb index holds: every article is indexed and every row resolves"
    head = f"{len(lines)} knowledge-base index violation(s):"
    return "\n".join([head, *[f"  - {line}" for line in lines]])


def run_self_test() -> int:
    import tempfile

    cases: list[tuple[str, dict[str, str], str, list[str]]] = [
        (
            "consistent index",
            {"README.md": "| [A](a.md) | x | me | 2026-10-18 | current |", "a.md": "# A\n"},
            "",
            [],
        ),
        (
            "unindexed article",
            {"README.md": "| [A](a.md) | x | me | 2026-10-18 | current |", "a.md": "", "b.md": ""},
            "",
            ["unindexed article"],
        ),
        (
            "dangling row",
            {"README.md": "| [A](a.md) | x | me | d | current |\n| [B](b.md) | y | me | d | current |", "a.md": ""},
            "",
            ["dangling row"],
        ),
        (
            "duplicate row",
            {"README.md": "| [A](a.md) | x | me | d | current |\n| [A](a.md) | x | me | d | current |", "a.md": ""},
            "",
            ["duplicate row"],
        ),
        (
            "link outside a row",
            {"README.md": "See [A](a.md) somewhere in prose.", "a.md": ""},
            "",
            ["malformed index"],
        ),
    ]

    failures = 0
    for name, files, _, expected in cases:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for filename, body in files.items():
                (root / filename).write_text(body, encoding="utf-8")
            index_path = root / INDEX_NAME
            text = index_path.read_text(encoding="utf-8") if index_path.exists() else None
            if text is None:
                print(f"FAIL {name}: fixture has no {INDEX_NAME}")
                failures += 1
                continue
            found = check(root, text)
        got = [marker for marker in expected if any(marker in v for v in found)]
        ok = len(got) == len(expected) and (
            not expected or len(found) >= len(expected)
        )
        if not ok:
            print(f"FAIL {name}: expected {expected}, got {found}")
            failures += 1
        else:
            print(f"ok   {name}")

    if failures:
        print(f"self-test failed: {failures} case(s)")
        return 1
    print(f"self-test passed: {len(cases)} case(s)")
    return 0


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return run_self_test()

    kb_dir = REPO_ROOT / KB_DIR
    index_path = kb_dir / INDEX_NAME
    if not index_path.is_file():
        print(f"kb index holds: no {KB_DIR}/{INDEX_NAME} yet; nothing to check")
        return 0

    violations = check(kb_dir, index_path.read_text(encoding="utf-8"))
    print(render(violations))
    return 1 if violations else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
