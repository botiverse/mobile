#!/usr/bin/env python3
"""Resolve Android visual cases from the canonical instrumentation source."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path


DEFAULT_SOURCE = Path(
    "compose/androidApp/src/androidTest/java/build/raft/app/visual/"
    "KmpVisualScreenshotCaptureTest.kt"
)
TEST_CLASS = "build.raft.app.visual.KmpVisualScreenshotCaptureTest"
CASE_ID_RE = re.compile(r'"([A-Za-z0-9][A-Za-z0-9._-]*\.[A-Za-z0-9._-]+)"')
CANONICAL_EXCLUDED_MARKER = "VISUAL_CANONICAL_EXCLUDED:"


def parse_csv(raw: str) -> list[str]:
    seen: set[str] = set()
    values: list[str] = []
    for item in raw.split(","):
        value = item.strip()
        if value and value not in seen:
            seen.add(value)
            values.append(value)
    return values


def enumerate_capture_tests(source: str) -> list[dict[str, object]]:
    chunks = re.split(r"\bfun\s+(\w+)\s*\(", source)
    entries: list[dict[str, object]] = []
    for index in range(1, len(chunks) - 1, 2):
        prefix = chunks[index - 1]
        method = chunks[index]
        body = chunks[index + 1]
        if re.search(r"@Test\s*$", prefix) is None:
            continue
        # @Ignore'd tests compile but never execute, so they must be removed
        # from the expected/sharding set at the source — otherwise a quarantined
        # case is still "expected" (24) while it never starts (23), tripping the
        # per-shard started-vs-expected harness guard. The annotation block for
        # this method is the prefix tail after the previous method's close brace.
        if "@Ignore" in prefix.rsplit("}", 1)[-1]:
            continue
        case_match = CASE_ID_RE.search(body)
        if case_match is None:
            raise ValueError(f"@Test method has no canonical case id: {method}")
        case_id = case_match.group(1)
        page_name_match = re.search(r'pageName\s*=\s*"([^"]+)"', body)
        fixture_match = re.search(r'\.put\("fixtureKey",\s*"([^"]+)"\)', body)
        revision_match = re.search(r'\.put\("fixtureRevision",\s*"([^"]+)"\)', body)
        goal_match = re.search(r'"renderedGoalSha256",\s*"([0-9a-f]{64})"', body)
        ready_match = re.search(r'readyToken\s*=\s*"([^"]+)"', body)
        page_name = page_name_match.group(1) if page_name_match else None
        entries.append(
            {
                "caseId": case_id,
                "class": TEST_CLASS,
                "method": method,
                "selector": f"{TEST_CLASS}#{method}",
                "sourceBodySha256": hashlib.sha256(body.encode("utf-8")).hexdigest(),
                "canonicalEnabled": CANONICAL_EXCLUDED_MARKER not in body,
                "testboxEligible": f"TESTBOX_ELIGIBLE: {case_id}" in body,
                "routePageName": page_name,
                "fixtureKey": fixture_match.group(1) if fixture_match else None,
                "fixtureRevision": revision_match.group(1) if revision_match else None,
                "renderedGoalSha256": goal_match.group(1) if goal_match else None,
                "readyToken": ready_match.group(1) if ready_match else None,
                "realProductRoute": page_name == "compose_settings_route_visual",
            }
        )
    case_ids = [str(entry["caseId"]) for entry in entries]
    methods = [str(entry["method"]) for entry in entries]
    if len(case_ids) != len(set(case_ids)):
        raise ValueError("canonical Android visual source contains duplicate case ids")
    if len(methods) != len(set(methods)):
        raise ValueError("canonical Android visual source contains duplicate test methods")
    return entries


def select_cases(entries: list[dict[str, object]], case_ids: list[str]) -> tuple[list[dict[str, object]], list[str]]:
    by_case = {str(entry["caseId"]): entry for entry in entries}
    return [by_case[case_id] for case_id in case_ids if case_id in by_case], [case_id for case_id in case_ids if case_id not in by_case]


def select_selectors(entries: list[dict[str, object]], selectors: list[str]) -> tuple[list[dict[str, object]], list[str]]:
    by_selector = {str(entry["selector"]): entry for entry in entries}
    return [by_selector[selector] for selector in selectors if selector in by_selector], [selector for selector in selectors if selector not in by_selector]


def shard_entries(entries: list[dict[str, object]], shard_index: int, shard_total: int) -> list[dict[str, object]]:
    ordered = sorted(entries, key=lambda entry: str(entry["selector"]))
    return [entry for index, entry in enumerate(ordered) if index % shard_total == shard_index]


def main() -> int:
    parser = argparse.ArgumentParser()
    source_group = parser.add_mutually_exclusive_group()
    source_group.add_argument("--cases", default=None, help="Comma-separated visual case ids")
    source_group.add_argument("--selectors", default=None, help="Comma-separated class#method selectors")
    source_group.add_argument("--all", action="store_true", help="Enumerate every canonical-enabled @Test capture")
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--format", choices=("filter", "json", "case-ids", "methods"), default="filter")
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--allow-missing", action="store_true")
    parser.add_argument("--require-testbox-eligible", action="store_true")
    parser.add_argument("--shard-index", type=int)
    parser.add_argument("--shard-total", type=int)
    args = parser.parse_args()

    try:
        entries = enumerate_capture_tests(args.source.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        print(f"Unable to enumerate Android visual captures: {exc}", file=sys.stderr)
        return 2

    missing: list[str] = []
    if args.all:
        payload = [entry for entry in entries if entry["canonicalEnabled"]]
    elif args.selectors is not None:
        payload, missing = select_selectors(entries, parse_csv(args.selectors))
    else:
        payload, missing = select_cases(entries, parse_csv(args.cases or ""))

    if missing and not args.allow_missing:
        print("No unique canonical Android visual capture for: " + ", ".join(missing), file=sys.stderr)
        return 2
    if (args.shard_index is None) != (args.shard_total is None):
        print("--shard-index and --shard-total must be provided together", file=sys.stderr)
        return 2
    if args.shard_index is not None and args.shard_total is not None:
        if not args.all or args.shard_total < 1 or not 0 <= args.shard_index < args.shard_total:
            print("canonical sharding requires --all and 0 <= index < total", file=sys.stderr)
            return 2
        payload = shard_entries(payload, args.shard_index, args.shard_total)
    ineligible = [str(entry["caseId"]) for entry in payload if not entry["testboxEligible"]]
    if ineligible and args.require_testbox_eligible:
        print("Android visual case is not explicitly TESTBOX_ELIGIBLE: " + ", ".join(ineligible), file=sys.stderr)
        return 3

    if args.format == "json":
        value = json.dumps(payload, indent=2, sort_keys=True)
    elif args.format == "case-ids":
        value = "\n".join(str(entry["caseId"]) for entry in payload)
    elif args.format == "methods":
        value = "\n".join(str(entry["method"]) for entry in payload)
    else:
        value = ",".join(str(entry["selector"]) for entry in payload)

    if args.github_output:
        if "\n" in value:
            print("GitHub output only supports the single-line filter format", file=sys.stderr)
            return 2
        with args.github_output.open("a", encoding="utf-8") as output:
            output.write(f"filter={value}\n")
    print(value)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
