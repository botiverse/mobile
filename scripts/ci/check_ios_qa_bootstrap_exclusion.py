#!/usr/bin/env python3
"""Prove the iOS QA session bootstrap never ships outside the Debug configuration.

source  (any host, every PR): RAFT_QA_SESSION_BOOTSTRAP is set only in the iosApp target's Debug
        build configuration; no other build configuration, xcconfig or script defines it; every
        Swift reference to the QA bootstrap code sits inside `#if RAFT_QA_SESSION_BOOTSTRAP`.
binary  <path.app|path.ipa-extracted-dir|file> (release build, macOS): none of the QA bootstrap
        markers (Swift-only strings) occur anywhere in the built bundle.

Contract: docs/qa-acceptance/session-bootstrap.md.
"""

from __future__ import annotations

import pathlib
import re
import sys

FLAG = "RAFT_QA_SESSION_BOOTSTRAP"
ROOT = pathlib.Path(__file__).resolve().parents[2]
IOS_ROOT = ROOT / "compose" / "iosApp"
PBXPROJ = IOS_ROOT / "iosApp.xcodeproj" / "project.pbxproj"
APP_TARGET_DEBUG_ID = "7555FFA6242A565B00829871"
GATED_FILES = ("RaftIosQaSessionBootstrapContract.swift", "RaftIosQaSessionBootstrapHost.swift")
# Strings only the gated Swift files contain (the Kotlin bridge deliberately has none of them).
BINARY_MARKERS = (
    b"raft-ios-qa-session-bootstrap",
    b"qaBootstrapRequestId",
    b"qaBootstrapEnvelopeDigest",
    b"qa-bootstrap/inbox",
    b"RaftIosQaSessionBootstrapHost",
    b"RaftIosQaSessionBootstrapContract",
)
SWIFT_REFERENCE = re.compile(r"RaftIosQaSessionBootstrap\w*")


def fail(message: str) -> None:
    print(f"ios-qa-bootstrap-exclusion: FAIL {message}", file=sys.stderr)
    sys.exit(1)


def build_configurations(text: str) -> dict[str, tuple[str, str]]:
    blocks: dict[str, tuple[str, str]] = {}
    pattern = re.compile(
        r"\t\t([0-9A-F]{24}) /\* (\w+) \*/ = \{\n\t\t\tisa = XCBuildConfiguration;(.*?)\n\t\t\};",
        re.S,
    )
    for match in pattern.finditer(text):
        blocks[match.group(1)] = (match.group(2), match.group(3))
    return blocks


def check_source() -> None:
    text = PBXPROJ.read_text(encoding="utf-8")
    configurations = build_configurations(text)
    if APP_TARGET_DEBUG_ID not in configurations:
        fail("iosApp Debug build configuration not found in project.pbxproj")
    for identifier, (name, body) in configurations.items():
        mentions = FLAG in body
        if identifier == APP_TARGET_DEBUG_ID:
            if name != "Debug" or not mentions:
                fail("iosApp Debug configuration must define RAFT_QA_SESSION_BOOTSTRAP")
            continue
        if mentions:
            fail(f"build configuration {identifier} ({name}) must not define {FLAG}")
    if text.count(FLAG) != 1:
        fail(f"{FLAG} must appear exactly once in project.pbxproj (iosApp Debug)")

    allowed_mentions = {
        PBXPROJ,
        pathlib.Path(__file__).resolve(),
        IOS_ROOT / "scripts" / "test_qa_session_bootstrap_contract.sh",
    }
    for path in list(IOS_ROOT.rglob("*.xcconfig")) + list(IOS_ROOT.rglob("*.sh")) + list((ROOT / ".github").rglob("*.yml")):
        if "Pods" in path.parts or path.resolve() in allowed_mentions:
            continue
        if FLAG in path.read_text(encoding="utf-8", errors="replace"):
            fail(f"{path.relative_to(ROOT)} must not define {FLAG}")

    swift_files = [p for p in (IOS_ROOT / "iosApp").rglob("*.swift") if "Pods" not in p.parts]
    for path in swift_files:
        lines = path.read_text(encoding="utf-8").splitlines()
        if path.name in GATED_FILES:
            code = [line.strip() for line in lines if line.strip() and not line.strip().startswith("//")]
            if not code or code[0] != f"#if {FLAG}" or code[-1] != "#endif":
                fail(f"{path.relative_to(ROOT)} must be wrapped whole in #if {FLAG} ... #endif")
            continue
        stack: list[bool] = []
        for number, line in enumerate(lines, start=1):
            stripped = line.strip()
            if stripped.startswith("#if"):
                stack.append(stripped == f"#if {FLAG}")
            elif stripped.startswith("#endif") and stack:
                stack.pop()
            elif stripped.startswith(("#else", "#elseif")) and stack:
                stack[-1] = False
            if SWIFT_REFERENCE.search(line) and not stripped.startswith("//") and not any(stack):
                fail(f"{path.relative_to(ROOT)}:{number} references the QA bootstrap outside #if {FLAG}")
    for name in GATED_FILES:
        if not (IOS_ROOT / "iosApp" / name).is_file():
            fail(f"compose/iosApp/iosApp/{name} is missing")
    print("ios-qa-bootstrap-exclusion: source PASS (flag only in iosApp Debug; Swift references gated)")


def check_binary(target: pathlib.Path) -> None:
    if not target.exists():
        fail(f"{target} does not exist")
    files = [target] if target.is_file() else [p for p in target.rglob("*") if p.is_file() and not p.is_symlink()]
    for path in files:
        data = path.read_bytes()
        for marker in BINARY_MARKERS:
            if marker in data:
                fail(f"{path} contains QA bootstrap marker {marker.decode()!r}; a QA/Debug build was packaged")
    print(f"ios-qa-bootstrap-exclusion: binary PASS ({len(files)} files under {target} carry no QA bootstrap marker)")


def main(argv: list[str]) -> None:
    if len(argv) == 2 and argv[1] == "source":
        check_source()
    elif len(argv) == 3 and argv[1] == "binary":
        check_binary(pathlib.Path(argv[2]))
    else:
        fail("usage: check_ios_qa_bootstrap_exclusion.py source | binary <app-dir-or-file>")


if __name__ == "__main__":
    main(sys.argv)
