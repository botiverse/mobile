#!/usr/bin/env python3
"""Print what failed in an xcodebuild test log: each failed assertion and each failed case.

xcodebuild ends a red run with only "Failing tests:" and the case names; the assertion text
("XCTAssertEqual failed: ...", "Asynchronous wait failed: Exceeded timeout of ...") is thousands
of lines earlier, so `tail` of the log never shows why a case failed (nightly 44789067976).
Always exits 0: it only prints, the caller decides the step's result.

usage: print_xctest_failures.py LOG
       print_xctest_failures.py --self-test
"""
import re
import sys

ASSERTION = re.compile(r"^.*: error: -\[[\w.]+ \w+\] : .*$", re.M)
FAILED_CASE = re.compile(r"^Test Case '-\[[\w.]+ \w+\]' failed \(.*$", re.M)
LIMIT = 80


def failures(log: str) -> list[str]:
    lines = [m.group(0) for m in ASSERTION.finditer(log)] + [m.group(0) for m in FAILED_CASE.finditer(log)]
    return lines[:LIMIT]


def self_test() -> int:
    sample = (
        "Test Case '-[iosAppTests.ATests testOk]' passed (0.001 seconds).\n"
        "/w/ATests.swift:97: error: -[iosAppTests.ATests testWait] : Asynchronous wait failed: "
        "Exceeded timeout of 1 seconds, with unfulfilled expectations: \"sink\".\n"
        "Test Case '-[iosAppTests.ATests testWait]' failed (1.012 seconds).\n"
        "ld: error: something unrelated\n"
    )
    found = failures(sample)
    assert len(found) == 2, found
    assert "Asynchronous wait failed" in found[0], found
    assert found[1].startswith("Test Case '-[iosAppTests.ATests testWait]' failed"), found
    assert failures("Test Case '-[a.B c]' passed (0.1 seconds).\n") == []
    print("print_xctest_failures self-test ok")
    return 0


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 0
    if argv[1] == "--self-test":
        return self_test()
    try:
        with open(argv[1], errors="replace") as handle:
            found = failures(handle.read())
    except OSError as error:
        print(f"print_xctest_failures: cannot read log: {error}")
        return 0
    print("=== XCTest failures ===")
    print("\n".join(found) if found else "(no XCTest assertion or failed case found in the log)")
    print("=== end of XCTest failures; last lines of the log follow ===")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
