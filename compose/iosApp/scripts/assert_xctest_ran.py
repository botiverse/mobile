#!/usr/bin/env python3
"""Fail unless an xcodebuild test run shows the named XCTest classes actually ran.

xcodebuild exits 0 when `-only-testing:` names a class that does not exist: it prints
"Executed 0 tests" and "** TEST SUCCEEDED **". test.yml ran a step like that for months
(SlockImageLoaderTests / SlockMediaAudioSessionTests were deleted in #3192). This turns that
into a failure.

The result bundle (`xcodebuild -resultBundlePath`) is what this reads: each class's cases and
their results, from `xcrun xcresulttool get test-results tests` (Xcode 16 and later; test.yml
runs Xcode 27). The log is read only when there is no bundle. In the log, the host app's stderr
(its os_log lines) shares one stream with XCTest's lines and is cut into it anywhere, not at line
ends. Run 44830456744 (attempt 3, all 161 tests passed) had "...hapticpatternlibrary.pTest Suite
'RaftIosNotificationRetractionTests' passed at ..." on one line, and a match at line start
reported "test suite never finished". The log check now finds XCTest's lines anywhere in a line,
but a line cut in two (the same log has some) still goes uncounted.

usage: assert_xctest_ran.py [--xcresult BUNDLE] LOG CLASS [CLASS...]
       assert_xctest_ran.py --self-test

BUNDLE is the .xcresult directory, or a file holding `xcresulttool get test-results tests` JSON.
When BUNDLE does not exist, LOG is checked instead (and the output says so). A BUNDLE that exists
but cannot be read fails the check.
"""
import contextlib
import io
import json
import re
import subprocess
import sys
import tempfile
from collections import Counter
from pathlib import Path

# XCTest's own lines, wherever they start in a line of the log (see above).
SUITE = re.compile(r"Test Suite '([^']+)' (passed|failed) at ")
CASE = re.compile(r"Test Case '-\[(?:[\w.]+\.)?(\w+) \w+\]' (passed|failed|skipped) ")

# How to run xcresulttool (the self-test runs a fake).
XCRESULTTOOL = ["xcrun", "xcresulttool"]
# A test case's result in the bundle once it ran to its end; "unknown", or none, means it did not.
FINISHED = {"Passed", "Failed", "Skipped", "Expected Failure"}


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


class BundleError(Exception):
    """The result bundle is there but cannot be read."""


def read_bundle(path: Path) -> dict:
    """The bundle's test tree: `xcresulttool get test-results tests` of an .xcresult directory, or
    that output saved to a file."""
    if path.is_dir():
        command = [*XCRESULTTOOL, "get", "test-results", "tests", "--path", str(path)]
        try:
            done = subprocess.run(command, capture_output=True, text=True, timeout=600)
        except (OSError, subprocess.SubprocessError) as error:
            raise BundleError(f"{' '.join(command)}: {error}") from error
        if done.returncode != 0:
            raise BundleError(f"{' '.join(command)} exited {done.returncode}: {done.stderr.strip()[-2000:]}")
        text = done.stdout
    else:
        text = path.read_text(errors="replace")
    try:
        tree = json.loads(text)
    except json.JSONDecodeError as error:
        raise BundleError(f"not xcresulttool JSON ({error}): {text[:200]!r}") from error
    if not isinstance(tree, dict):
        raise BundleError(f"not xcresulttool JSON: a {type(tree).__name__}, not an object")
    return tree


def bundle_cases(tree: dict) -> dict[str, list[str]]:
    """Each test class's case results ("" for a case without one). A class is a "Test Suite" node,
    named without its module; its cases are its "Test Case" nodes, whose own children (runs,
    failure messages, attachments) are not cases."""
    classes: dict[str, list[str]] = {}

    def walk(node: object, suite: str) -> None:
        if not isinstance(node, dict):
            return
        kind = node.get("nodeType")
        if kind == "Test Case":
            classes.setdefault(suite, []).append(str(node.get("result") or ""))
            return
        if kind == "Test Suite":
            suite = str(node.get("name", "")).rsplit(".", 1)[-1]
        for child in node.get("children") or []:
            walk(child, suite)

    for node in tree.get("testNodes") or []:
        walk(node, "")
    return classes


def node_types(tree: dict) -> list[str]:
    """Every nodeType in the tree, for the message when it has no test case."""
    seen = set()
    stack = list(tree.get("testNodes") or [])
    while stack:
        node = stack.pop()
        if isinstance(node, dict):
            seen.add(str(node.get("nodeType")))
            stack.extend(node.get("children") or [])
    return sorted(seen)


def check_bundle(tree: dict, classes: list[str]) -> list[str]:
    problems = []
    ran = bundle_cases(tree)
    if not any(ran.values()):
        problems.append(
            "no XCTest case ran at all (result bundle; node types: "
            f"{', '.join(node_types(tree)) or 'none'}; keys: {', '.join(sorted(tree)) or 'none'})"
        )
    for name in classes:
        results = ran.get(name, [])
        if not results:
            problems.append(f"{name}: 0 test cases ran (class missing, renamed or filtered out?)")
            continue
        unfinished = [result for result in results if result not in FINISHED]
        if unfinished:
            problems.append(
                f"{name}: test suite never finished ({len(unfinished)} of {len(results)} cases have no result)"
            )
    return problems


def outcomes(results: list[str]) -> str:
    counts = Counter(result.lower() or "no result" for result in results)
    return ", ".join(f"{count} {result}" for result, count in sorted(counts.items()))


def main(argv: list[str]) -> int:
    bundle = None
    if argv[:1] == ["--xcresult"] and len(argv) >= 2:
        bundle, argv = Path(argv[1]), argv[2:]
    if len(argv) < 2 or argv[0].startswith("--"):
        print(__doc__, file=sys.stderr)
        return 2
    log, classes = Path(argv[0]), argv[1:]
    details: list[str] = []
    if bundle is not None and bundle.exists():
        try:
            tree = read_bundle(bundle)
        except BundleError as error:
            print(f"XCTest run check FAILED: cannot read the result bundle {bundle}: {error}", file=sys.stderr)
            return 1
        ran = bundle_cases(tree)
        problems = check_bundle(tree, classes)
        total = sum(len(results) for results in ran.values())
        source = f"result bundle {bundle.name}"
        details = [f"  {name}: {outcomes(ran[name])}" for name in classes if ran.get(name)]
    else:
        if bundle is not None:
            print(f"XCTest run check: no result bundle at {bundle}; checking the log")
        text = log.read_text(errors="replace")
        problems = check(text, classes)
        total = len(CASE.findall(text))
        source = f"log {log.name}"
    if problems:
        print(f"XCTest run check FAILED ({total} cases in the {source}):", file=sys.stderr)
        for problem in problems:
            print(f"  {problem}", file=sys.stderr)
        return 1
    print(f"XCTest run check: {total} cases ran across {len(classes)} required classes ({source})")
    for line in details:
        print(line)
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

# Run 44830456744 attempt 3, xcodebuild-login-router-tests.log (lines trimmed at the right): the
# host app's os_log lines cut into XCTest's. One case line is cut in two, and the suite's "passed"
# line is glued to the end of an os_log line.
INTERLEAVED = """\
Test Suite 'RaftIosNotificationRetractionTests' started at 2026-11-21 01:23:27.622.
Test Case '-[iosAppTests.RaftIosNotificationRetractionTests testMalformedPayloadsAreRejected]' started.
Test Case '-[iosAppTests.RaftIosNotificationRetractionTests testMalformedPayloadsAreRejected]' passed (0.013 seconds).
Test Case '-[iosAppTests.RaftIosNotificationRetractionTests testMatcherNullThroughSeqRemovesWholeGroup]' started.
2026-11-21 01:23:27.726355+0000 iosApp[2444:248556] [UIKBFeedbackGenerator] <_UIKBFeedbackGenerator: 0x15a92a7c0>: \
Error creating CHHapticPattern: Error Domain=NSCocoaErrorDomain Code=260 "The file “hapticpatternlibrary.plist” \
couldn’t be opened because there is no such file."
Test Case '-[iosApp2026-11-21 01:23:27.727469+0000 iosApp[2444:248556] [hapi]        CHHapticPattern.mm:509   \
+[CHHapticPattern patternForKey:error:]: Failed to read pattern library data: Error Domain=NSCocoaErrorDomain Code=260
Tests.RaftIosNotificationRetractionTests testMatcherNullThroughSeqRemovesWholeGroup]' passed (0.001 seconds).
2026-11-21 01:23:28.706894+0000 iosApp[2444:248556] [UIKBFeedbackGenerator] <_UIKBFeedbackGenerator: 0x15a92a7c0>: \
Error creating CHHapticPattern: Error Domain=NSCocoaErrorDomain Code=260 "The file “hapticpatternlibrary.plist” \
couldn’t be opened because there is no such file." \
UserInfo={NSFilePath=/Library/Audio/Tunings/Generic/Haptics/Library/hapticpatternlibrary.p\
Test Suite 'RaftIosNotificationRetractionTests' passed at 2026-11-21 01:23:28.707.
\t Executed 24 tests, with 0 failures (0 unexpected) in 0.403 (1.085) seconds
"""

# `xcrun xcresulttool get test-results tests` output, shaped as its --schema gives it (Xcode 16 and
# later), trimmed; not yet captured from a CI bundle (the iOS job's check reads the real ones). An
# Objective-C class (cases without "()") and Swift ones; failed, skipped and expected-failure cases
# count as having run; a case's children are not cases.
XCRESULT_TESTS = """\
{
  "devices": [{"architecture": "arm64", "deviceName": "iPhone 17", "osVersion": "27.0", "platform": "iOS Simulator"}],
  "testPlanConfigurations": [{"configurationId": "1", "configurationName": "Test Scheme Action"}],
  "testNodes": [{
    "name": "iosAppTests", "nodeType": "Test Plan", "result": "Failed",
    "children": [{
      "name": "iosAppTests", "nodeType": "Unit test bundle", "result": "Failed",
      "children": [
        {"name": "RaftCmpBridgeSelectorTests", "nodeType": "Test Suite", "result": "Passed", "children": [
          {"name": "testBridgeCallSelectorIsPinned", "nodeIdentifier": "RaftCmpBridgeSelectorTests/testBridgeCallSelectorIsPinned",
           "nodeType": "Test Case", "result": "Passed", "duration": "0.001s"}
        ]},
        {"name": "RaftIosNotificationRetractionTests", "nodeType": "Test Suite", "result": "Passed", "children": [
          {"name": "testSyncPushCarriesBadge()", "nodeIdentifier": "RaftIosNotificationRetractionTests/testSyncPushCarriesBadge()",
           "nodeType": "Test Case", "result": "Passed", "duration": "0.011s"},
          {"name": "testWatermarkExpiresAfterThreeDays()", "nodeIdentifier": "RaftIosNotificationRetractionTests/testWatermarkExpiresAfterThreeDays()",
           "nodeType": "Test Case", "result": "Skipped", "duration": "0.001s"}
        ]},
        {"name": "RaftIosComposerPasteTests", "nodeType": "Test Suite", "result": "Failed", "children": [
          {"name": "testCommandVPastesPngBytesWithoutAUIImage()", "nodeIdentifier": "RaftIosComposerPasteTests/testCommandVPastesPngBytesWithoutAUIImage()",
           "nodeType": "Test Case", "result": "Failed", "duration": "16s", "children": [
            {"name": "RaftIosComposerPasteTests.swift:137: XCTAssertTrue failed - paste: has a target", "nodeType": "Failure Message"}
          ]},
          {"name": "testPlainFieldOffersAndPastesText()", "nodeIdentifier": "RaftIosComposerPasteTests/testPlainFieldOffersAndPastesText()",
           "nodeType": "Test Case", "result": "Expected Failure", "duration": "1.2s"}
        ]}
      ]
    }]
  }]
}
"""

# Stands in for `xcrun xcresulttool`: prints the tests.json kept in the bundle directory it is given.
FAKE_XCRESULTTOOL = """\
import pathlib, sys
args = sys.argv[1:]
if args[:4] != ["get", "test-results", "tests", "--path"] or len(args) != 5:
    sys.exit(f"unexpected arguments: {args}")
sys.stdout.write((pathlib.Path(args[4]) / "tests.json").read_text())
"""


def run_main(argv: list[str]) -> tuple[int, str]:
    """main(argv), and what it printed."""
    output = io.StringIO()
    with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
        code = main(argv)
    return code, output.getvalue()


def self_test() -> int:
    global XCRESULTTOOL
    # The log.
    assert check(SAMPLE, ["RaftCmpBridgeSelectorTests", "RaftIosTabBarItemMappingTests"]) == []
    missing = check(SAMPLE, ["RaftCmpBridgeSelectorTests", "SlockImageLoaderTests"])
    assert missing == ["SlockImageLoaderTests: 0 test cases ran (class missing, renamed or filtered out?)"], missing
    empty = check(EMPTY, ["SlockImageLoaderTests"])
    assert empty[0] == "no XCTest case ran at all" and len(empty) == 2, empty
    unfinished_log = SAMPLE.replace("Test Suite 'RaftCmpBridgeSelectorTests' passed", "Test Suite 'Other' passed")
    assert check(unfinished_log, ["RaftCmpBridgeSelectorTests"]) == ["RaftCmpBridgeSelectorTests: test suite never finished"]
    # The interleaved log (the fixture has the glued line attempt 3 had): the suite's "passed" line
    # is found; the case line cut in two is not counted, which is why the bundle comes first.
    assert not re.search(r"^Test Suite 'RaftIosNotificationRetractionTests' passed at ", INTERLEAVED, re.M)
    assert check(INTERLEAVED, ["RaftIosNotificationRetractionTests"]) == []
    assert [m.group(1) for m in CASE.finditer(INTERLEAVED)] == ["RaftIosNotificationRetractionTests"]

    # The bundle.
    tree = json.loads(XCRESULT_TESTS)
    cases = bundle_cases(tree)
    assert cases == {
        "RaftCmpBridgeSelectorTests": ["Passed"],
        "RaftIosNotificationRetractionTests": ["Passed", "Skipped"],
        "RaftIosComposerPasteTests": ["Failed", "Expected Failure"],
    }, cases
    assert check_bundle(tree, list(cases)) == []
    assert check_bundle(tree, ["SlockImageLoaderTests"]) == [
        "SlockImageLoaderTests: 0 test cases ran (class missing, renamed or filtered out?)"
    ]
    unknown = json.loads(XCRESULT_TESTS.replace('"result": "Skipped"', '"result": "unknown"'))
    assert check_bundle(unknown, ["RaftIosNotificationRetractionTests"]) == [
        "RaftIosNotificationRetractionTests: test suite never finished (1 of 2 cases have no result)"
    ]
    no_result = json.loads(XCRESULT_TESTS.replace('"result": "Expected Failure", ', ""))
    assert check_bundle(no_result, ["RaftIosComposerPasteTests"]) == [
        "RaftIosComposerPasteTests: test suite never finished (1 of 2 cases have no result)"
    ]
    nothing = check_bundle({"devices": [], "testNodes": [], "testPlanConfigurations": []}, ["SlockImageLoaderTests"])
    assert nothing[0].startswith("no XCTest case ran at all (result bundle; node types: none") and len(nothing) == 2, nothing
    module = {"testNodes": [{"name": "iosAppTests.RaftFooTests", "nodeType": "Test Suite", "children": [
        {"name": "testA()", "nodeType": "Test Case", "result": "Passed"}]}]}
    assert bundle_cases(module) == {"RaftFooTests": ["Passed"]}
    assert outcomes(["Passed", "Skipped", "Passed", ""]) == "1 no result, 2 passed, 1 skipped"

    with tempfile.TemporaryDirectory() as scratch:
        root = Path(scratch)
        logs = {}
        for name, text in {"empty": EMPTY, "unfinished": unfinished_log, "interleaved": INTERLEAVED}.items():
            logs[name] = root / f"{name}.log"
            logs[name].write_text(text)
        assert run_main([str(logs["empty"]), "SlockImageLoaderTests"])[0] == 1
        assert run_main([str(logs["unfinished"]), "RaftCmpBridgeSelectorTests"])[0] == 1
        # The bundle decides, whatever the log says.
        saved = root / "tests.json"
        saved.write_text(XCRESULT_TESTS)
        code, output = run_main(["--xcresult", str(saved), str(logs["unfinished"]), "RaftCmpBridgeSelectorTests"])
        assert code == 0 and "5 cases ran across 1 required classes (result bundle tests.json)" in output, output
        assert "  RaftCmpBridgeSelectorTests: 1 passed" in output, output
        code, output = run_main(["--xcresult", str(saved), str(logs["interleaved"]), "SlockImageLoaderTests"])
        assert code == 1 and "SlockImageLoaderTests: 0 test cases ran" in output, output
        # An .xcresult directory is read through xcresulttool.
        bundle = root / "Run.xcresult"
        bundle.mkdir()
        (bundle / "tests.json").write_text(XCRESULT_TESTS)
        broken = root / "Broken.xcresult"
        broken.mkdir()
        fake = root / "fake_xcresulttool.py"
        fake.write_text(FAKE_XCRESULTTOOL)
        real, XCRESULTTOOL = XCRESULTTOOL, [sys.executable, str(fake)]
        try:
            code, output = run_main(
                ["--xcresult", str(bundle), str(logs["empty"]), "RaftIosNotificationRetractionTests", "RaftIosComposerPasteTests"]
            )
            assert code == 0 and "(result bundle Run.xcresult)" in output, output
            assert "  RaftIosComposerPasteTests: 1 expected failure, 1 failed" in output, output
            # A bundle that is there but cannot be read fails; it does not fall back to the log.
            code, output = run_main(["--xcresult", str(broken), str(logs["interleaved"]), "RaftIosNotificationRetractionTests"])
            assert code == 1 and "cannot read the result bundle" in output, output
        finally:
            XCRESULTTOOL = real
        garbage = root / "garbage.json"
        garbage.write_text("Error: not a result bundle\n")
        code, output = run_main(["--xcresult", str(garbage), str(logs["interleaved"]), "RaftIosNotificationRetractionTests"])
        assert code == 1 and "not xcresulttool JSON" in output, output
        # No bundle at the path: the log is checked, and the output says so.
        code, output = run_main(
            ["--xcresult", str(root / "Missing.xcresult"), str(logs["interleaved"]), "RaftIosNotificationRetractionTests"]
        )
        assert code == 0 and "no result bundle at" in output and "(log interleaved.log)" in output, output
        assert run_main(["--xcresult", str(root / "Missing.xcresult"), str(logs["empty"]), "SlockImageLoaderTests"])[0] == 1
        assert run_main(["--xcresult"])[0] == 2
        assert run_main(["--xcresult", str(saved), str(logs["empty"])])[0] == 2
    print("assert_xctest_ran self-test: PASS")
    return 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test())
    sys.exit(main(sys.argv[1:]))
