#!/usr/bin/env python3
"""Every case the web captures for comparison with the app has its Android capture.

scripts/ci/visual_paired_cases.txt lists those cases. It is written from slock's manifest
(packages/visual-testing/shared/sharedCases.json, `--manifest FILE --write`), and the visual-testing
workflow checks that it still matches (`--manifest FILE`). On a pull request only the list and
KmpVisualScreenshotCaptureTest are read: a capture deleted from under its web counterpart fails here,
instead of leaving the web half of the pair with nothing to compare with (#5339 dropped 31).

To stop comparing a case, change it in slock's manifest first.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from visual_android_case_filter import DEFAULT_SOURCE, enumerate_capture_tests  # noqa: E402

PAIRS = Path(__file__).with_name("visual_paired_cases.txt")


def compared(case: dict) -> bool:
    """A case the web captures and its manifest expects of the app: it names an Android capture or
    provider, or no provider at all (the default: every provider)."""
    skip = case.get("skip")
    if case.get("baselineStatus") == "pending" or skip is True or (isinstance(skip, dict) and skip.get("enabled") is not False):
        return False
    matrix = case.get("providerMatrix")
    return bool(case.get("androidCaseHint")) or not matrix or "android" in matrix


def manifest_pairs(manifest: dict) -> list[str]:
    return sorted(case["id"] for case in manifest["cases"] if compared(case))


def read_pairs() -> list[str]:
    return [line for line in PAIRS.read_text(encoding="utf-8").splitlines() if line and not line.startswith("#")]


def report(title: str, ids: list[str]) -> None:
    print(f"{title} ({len(ids)}):", file=sys.stderr)
    for case_id in ids:
        print(f"  {case_id}", file=sys.stderr)


def self_test() -> None:
    cases = [
        {"id": "b.default"},
        {"id": "a.hint", "providerMatrix": {"react": {}}, "androidCaseHint": "page"},
        {"id": "c.android", "providerMatrix": {"react": {}, "android": {}}},
        {"id": "d.web", "providerMatrix": {"react": {}}},
        {"id": "e.pending", "baselineStatus": "pending"},
        {"id": "f.skipped", "skip": {"reason": "external"}},
        {"id": "g.unskipped", "skip": {"enabled": False}},
    ]
    assert manifest_pairs({"cases": cases}) == ["a.hint", "b.default", "c.android", "g.unskipped"]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0

    failed = False
    if args.manifest:
        expected = manifest_pairs(json.loads(args.manifest.read_text(encoding="utf-8")))
        if args.write:
            header = "# Cases compared with the web. Written by check_visual_pairs.py --manifest sharedCases.json --write.\n"
            PAIRS.write_text(header + "".join(f"{case_id}\n" for case_id in expected), encoding="utf-8")
        elif expected != read_pairs():
            report("Compared in slock's manifest, not listed", sorted(set(expected) - set(read_pairs())))
            report("Listed, not compared in slock's manifest", sorted(set(read_pairs()) - set(expected)))
            print(f"Rewrite {PAIRS.name} with --write.", file=sys.stderr)
            failed = True
    captured = {str(entry["caseId"]) for entry in enumerate_capture_tests((ROOT / DEFAULT_SOURCE).read_text(encoding="utf-8"))}
    missing = [case_id for case_id in read_pairs() if case_id not in captured]
    if missing:
        report(f"Compared with the web, but not captured in {DEFAULT_SOURCE.name}", missing)
        failed = True
    if not failed:
        print(f"{len(read_pairs())} compared cases, all captured.")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
