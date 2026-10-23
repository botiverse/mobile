#!/usr/bin/env python3
"""Fail unless an xcodebuild test log shows the named XCTest classes actually ran.

xcodebuild exits 0 when `-only-testing:` names a class that does not exist: it prints
"Executed 0 tests" and "** TEST SUCCEEDED **". test.yml ran a step like that for months
(SlockImageLoaderTests / SlockMediaAudioSessionTests were deleted in #3192). This turns that
into a failure.

usage: assert_xctest_ran.py LOG CLASS [CLASS...]
       assert_xctest_ran.py --self-test
"""
import re
import sys
import tempfile
from pathlib import Path

SUITE = re.compile(r"^Test Suite '([^']+)' (passed|failed) at ", re.M)
CASE = re.compile(r"^Test Case '-\[(?:[\w.]+\.)?(\w+) \w+\]' (passed|failed|skipped) ", re.M)


def check(log: str, classes: list[str]) -> list[str]:
    problems = []
    ran: dict[str, int] = {}
    for match in CASE.finditer(log):
        ran[match.group(1)] = ran.get(match.group(1), 0) + 1
    finished = {m.group(1) for m in SUITE.finditer(log)}
    if not ran:
        problems.append("no XCTest case ran at all")
    for name in classes:
        if ran.get(name, 0) == 0:
            problems.append(f"{name}: 0 test cases ran (class missing, renamed or filtered out?)")
        elif name not in finished:
            problems.append(f"{name}: test suite never finished")
    return problems


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    log = Path(argv[0]).read_text(errors="replace")
    problems = check(log, argv[1:])
    total = len(CASE.findall(log))
    if problems:
        print(f"XCTest run check FAILED ({total} cases in log):", file=sys.stderr)
        for problem in problems:
            print(f"  {problem}", file=sys.stderr)
        return 1
    print(f"XCTest run check: {total} cases ran across {len(argv) - 1} required classes")
    return 0


SAMPLE = """\
Test Suite 'Selected tests' started at 2026-10-23 06:32:11.993.
Test Suite 'RaftCmpBridgeSelectorTests' started at 2026-10-23 06:34:59.440.
Test Case '-[RaftCmpBridgeSelectorTests testBridgeCallSelectorIsPinned]' started.
Test Case '-[RaftCmpBridgeSelectorTests testBridgeCallSelectorIsPinned]' passed (0.001 seconds).
Test Suite 'RaftCmpBridgeSelectorTests' passed at 2026-10-23 06:34:59.446.
Test Suite 'RaftIosTabBarItemMappingTests' started at 2026-10-23 06:34:59.446.
Test Case '-[iosAppTests.RaftIosTabBarItemMappingTests testBadge]' started.
Test Case '-[iosAppTests.RaftIosTabBarItemMappingTests testBadge]' failed (0.002 seconds).
Test Suite 'RaftIosTabBarItemMappingTests' failed at 2026-10-23 06:35:02.667.
\t Executed 2 tests, with 1 failure (0 unexpected) in 0.003 (0.005) seconds
"""

EMPTY = """\
Test Suite 'Selected tests' started at 2026-10-23 06:37:53.440.
Test Suite 'Selected tests' passed at 2026-10-23 06:37:53.450.
\t Executed 0 tests, with 0 failures (0 unexpected) in 0.000 (0.002) seconds
** TEST SUCCEEDED **
"""


def self_test() -> int:
    assert check(SAMPLE, ["RaftCmpBridgeSelectorTests", "RaftIosTabBarItemMappingTests"]) == []
    missing = check(SAMPLE, ["RaftCmpBridgeSelectorTests", "SlockImageLoaderTests"])
    assert missing == ["SlockImageLoaderTests: 0 test cases ran (class missing, renamed or filtered out?)"], missing
    empty = check(EMPTY, ["SlockImageLoaderTests"])
    assert empty[0] == "no XCTest case ran at all" and len(empty) == 2, empty
    unfinished = SAMPLE.replace("Test Suite 'RaftCmpBridgeSelectorTests' passed", "Test Suite 'Other' passed")
    assert check(unfinished, ["RaftCmpBridgeSelectorTests"]) == ["RaftCmpBridgeSelectorTests: test suite never finished"]
    with tempfile.NamedTemporaryFile("w", suffix=".log", delete=False) as handle:
        handle.write(EMPTY)
    assert main([handle.name, "SlockImageLoaderTests"]) == 1
    Path(handle.name).unlink()
    print("assert_xctest_ran self-test: PASS")
    return 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test())
    sys.exit(main(sys.argv[1:]))
