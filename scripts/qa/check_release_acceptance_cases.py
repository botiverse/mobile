#!/usr/bin/env python3
"""Fail closed when the canonical Android release case set drifts.

This intentionally uses only the Python standard library.  It reads the few
top-level scalar/list fields that form the release admission contract; the
full YAML remains opaque input to Stamp and is not re-serialized here.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CASE_DIR = ROOT / "docs" / "qa-acceptance" / "cases"
MANIFEST = CASE_DIR / "release-1.7.0-android.json"


def top_level_scalar(source: str, key: str) -> str | None:
    match = re.search(rf"(?m)^{re.escape(key)}:\s*([^#\n]+?)\s*$", source)
    if not match:
        return None
    return match.group(1).strip().strip('"\'')


def top_level_inline_list(source: str, key: str) -> set[str]:
    match = re.search(rf"(?m)^{re.escape(key)}:\s*\[([^\n]*)\]\s*$", source)
    if not match:
        return set()
    return {part.strip().strip('"\'') for part in match.group(1).split(",") if part.strip()}


def indented_scalar(source: str, key: str, spaces: int = 2) -> str | None:
    indent = " " * spaces
    match = re.search(rf"(?m)^{re.escape(indent)}{re.escape(key)}:\s*([^#\n]+?)\s*$", source)
    if not match:
        return None
    return match.group(1).strip().strip('"\'')


def fail(errors: list[str], message: str) -> None:
    errors.append(message)


def main() -> int:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    canonical = set(manifest["canonical_case_ids"])
    automated = set(manifest["automated_case_ids"])
    manual_only = set(manifest["manual_only_case_ids"])
    errors: list[str] = []

    if len(manifest["canonical_case_ids"]) != 26 or len(canonical) != 26:
        fail(errors, "canonical_case_ids must contain exactly 26 unique IDs")
    if len(manifest["automated_case_ids"]) != 25 or len(automated) != 25:
        fail(errors, "automated_case_ids must contain exactly 25 unique IDs")
    if manual_only != {"server-create"}:
        fail(errors, "server-create must be the sole manual-only case")
    if automated & manual_only or automated | manual_only != canonical:
        fail(errors, "automated and manual-only sets must partition the canonical set")

    sources: dict[str, tuple[Path, str]] = {}
    for path in sorted(CASE_DIR.glob("*.yaml")):
        source = path.read_text(encoding="utf-8")
        case_id = top_level_scalar(source, "id")
        if not case_id:
            fail(errors, f"{path.name}: missing top-level id")
            continue
        if case_id in sources:
            fail(errors, f"duplicate case id {case_id}: {sources[case_id][0].name}, {path.name}")
            continue
        sources[case_id] = (path, source)

    actual = set(sources)
    for case_id in sorted(canonical - actual):
        fail(errors, f"missing canonical case source: {case_id}")
    for case_id in sorted(actual - canonical):
        fail(errors, f"unexpected top-level Android case source: {case_id}")

    for case_id in sorted(automated & actual):
        path, source = sources[case_id]
        if top_level_scalar(source, "auth_fixture") != "alpha-qa":
            fail(errors, f"{path.name}: automated case must use auth_fixture alpha-qa")
        if top_level_scalar(source, "launch_route") != "compose_slock_app_shell":
            fail(errors, f"{path.name}: automated case must launch compose_slock_app_shell")
        if "manual-only" in top_level_inline_list(source, "tags"):
            fail(errors, f"{path.name}: manual-only case cannot enter the automated set")

    if "server-create" in sources:
        path, source = sources["server-create"]
        if top_level_scalar(source, "auth_fixture") != "none":
            fail(errors, f"{path.name}: manual provisioning must keep auth_fixture none")
        if top_level_scalar(source, "launch_route") != "compose_slock_app_shell":
            fail(errors, f"{path.name}: manual provisioning launch route drifted")
        if "manual-only" not in top_level_inline_list(source, "tags"):
            fail(errors, f"{path.name}: missing manual-only tag")
        if indented_scalar(source, "scheduled") != "false":
            fail(errors, f"{path.name}: manual provisioning must keep scheduled false")

    if errors:
        print("release acceptance case contract FAILED", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("release acceptance case contract PASS: 26 canonical = 25 automated + 1 manual-only")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
