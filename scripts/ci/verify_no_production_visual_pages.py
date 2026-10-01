#!/usr/bin/env python3
"""Enforce that no visual fixture @Page registrations live in production source sets (task #194).

Incident / origin:
Task #193 moved visual fixture @Pages out of production source sets in phases:
- Phase 1: ThreadMessageRowVisualHost (PR #189)
- Phase 2: ThreadPage.kt remaining visual pages (PR #192)
- Phase 3: SlockFilePreview, FloatingOverlayTouchGuard, SettingsHomePage (PR #2115, PR #2117, PR #2656)
- Phase 4 (this gate): Wire verifyNoProductionVisualPages into required CI so regressions
  cannot silently enter production source sets again.

Production source sets scanned:
- compose/shared/src/commonMain/kotlin
- compose/shared/src/androidMain/kotlin
- compose/androidApp/src/main/java

Patterns rejected in production:
- @Page\\s*\\(\\s*[A-Za-z0-9_]*_VISUAL_PAGE\\b
- @Page\\s*\\(\\s*"[^"]*_visual"

Exit codes:
0 = no violations found / self-test passed
1 = violations found or self-test failed
2 = usage error
"""

from __future__ import annotations

import os
import re
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

PRODUCTION_SOURCE_DIRS = (
    "compose/shared/src/commonMain/kotlin",
    "compose/shared/src/androidMain/kotlin",
    "compose/androidApp/src/main/java",
)

VISUAL_PAGE_PATTERNS = (
    re.compile(r"@Page\s*\(\s*[A-Za-z0-9_]*_VISUAL_PAGE\b"),
    re.compile(r'@Page\s*\(\s*"[^"]*_visual"'),
)


def find_violations_in_file(path: Path | str, text: str) -> list[str]:
    violations: list[str] = []
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if any(pattern.search(line) for pattern in VISUAL_PAGE_PATTERNS):
            violations.append(f"{path}:{index + 1}: {line.strip()}")
    return violations


def scan_production_sources(root: Path) -> list[str]:
    violations: list[str] = []
    for rel_dir in PRODUCTION_SOURCE_DIRS:
        target_dir = root / rel_dir
        if not target_dir.is_dir():
            raise FileNotFoundError(
                f"Production source directory is missing: {target_dir}"
            )
        for current_root, _, filenames in os.walk(target_dir):
            for filename in filenames:
                if filename.endswith(".kt") or filename.endswith(".java"):
                    full_path = Path(current_root) / filename
                    rel_path = full_path.relative_to(root).as_posix()
                    try:
                        content = full_path.read_text(encoding="utf-8", errors="ignore")
                    except OSError as err:
                        violations.append(f"{rel_path}:0: [ERROR reading file: {err}]")
                        continue
                    violations.extend(find_violations_in_file(rel_path, content))
    return violations


def self_test() -> int:
    """The gate must detect positive violations and pass on clean inputs."""
    clean_sample = """
package build.raft.app

import com.tencent.kuikly.core.annotations.Page

@Page("compose_settings_home", supportInLocal = true)
internal class ComposeSettingsHomePage : BasePager() {
    val pageConstant = "compose_home_page"
}
"""
    clean_violations = find_violations_in_file("TestClean.kt", clean_sample)
    if clean_violations:
        print(
            f"self-test: reported false positive violation on clean input: {clean_violations}",
            file=sys.stderr,
        )
        return 1

    # Violation 1: constant with _VISUAL_PAGE suffix
    violation_constant = """
@Page(MY_CUSTOM_VISUAL_PAGE, supportInLocal = true)
class TestPage
"""
    v1 = find_violations_in_file("TestV1.kt", violation_constant)
    if len(v1) != 1 or "MY_CUSTOM_VISUAL_PAGE" not in v1[0]:
        print(f"self-test: failed to catch _VISUAL_PAGE violation: {v1}", file=sys.stderr)
        return 1

    # Violation 2: literal string ending in _visual
    violation_literal = """
@Page("compose_something_visual", supportInLocal = true)
class TestPage2
"""
    v2 = find_violations_in_file("TestV2.kt", violation_literal)
    if len(v2) != 1 or "compose_something_visual" not in v2[0]:
        print(f"self-test: failed to catch literal _visual violation: {v2}", file=sys.stderr)
        return 1

    # Violation 3: spacing variation
    violation_spaced = """
    @Page  (   SOME_ROUTE_VISUAL_PAGE   )
"""
    v3 = find_violations_in_file("TestV3.kt", violation_spaced)
    if len(v3) != 1:
        print(f"self-test: failed to catch spaced _VISUAL_PAGE violation: {v3}", file=sys.stderr)
        return 1

    # Negative control: page name containing visual but not ending in _visual
    negative_sample = """
@Page("compose_visualize_graph")
@Page(VISUALIZATION_PAGE)
"""
    v_neg = find_violations_in_file("TestNeg.kt", negative_sample)
    if v_neg:
        print(f"self-test: reported false positive on non-visual page: {v_neg}", file=sys.stderr)
        return 1

    # Synthetic directory injection test: ensure each of the 3 production source dirs is actively scanned
    with tempfile.TemporaryDirectory() as tmp_dir:
        tmp_root = Path(tmp_dir)
        for rel_dir in PRODUCTION_SOURCE_DIRS:
            (tmp_root / rel_dir).mkdir(parents=True, exist_ok=True)

        # Baseline: empty tree has zero violations
        if scan_production_sources(tmp_root):
            print("self-test: synthetic empty tree had violations", file=sys.stderr)
            return 1

        # Violation injection in each directory
        for i, rel_dir in enumerate(PRODUCTION_SOURCE_DIRS):
            test_file = tmp_root / rel_dir / f"InjectedVisualPage{i}.kt"
            test_file.write_text(
                f'@Page("injected_dir_{i}_visual")\nclass InjectedPage{i}\n',
                encoding="utf-8",
            )
            injected_violations = scan_production_sources(tmp_root)
            if not any(f"injected_dir_{i}_visual" in v for v in injected_violations):
                print(
                    f"self-test: failed to detect injected violation in {rel_dir}: {injected_violations}",
                    file=sys.stderr,
                )
                return 1
            test_file.unlink()

        # Fail-closed test on missing directory
        os.rmdir(tmp_root / PRODUCTION_SOURCE_DIRS[0])
        try:
            scan_production_sources(tmp_root)
            print("self-test: missing production directory did not fail closed", file=sys.stderr)
            return 1
        except FileNotFoundError:
            pass

    # Real scan of repository production sources
    real_violations = scan_production_sources(REPO_ROOT)
    if real_violations:
        print(
            "self-test: tree scan found unexpected violations:\n"
            + "\n".join(real_violations),
            file=sys.stderr,
        )
        return 1

    print("verify_no_production_visual_pages self-test passed")
    return 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()

    try:
        violations = scan_production_sources(REPO_ROOT)
    except FileNotFoundError as err:
        print(f"ERROR: {err}", file=sys.stderr)
        return 1

    if violations:
        print(
            "Visual fixture @Page registrations must live under "
            "compose/shared/src/visualFixtures/kotlin:\n"
            + "\n".join(violations),
            file=sys.stderr,
        )
        return 1

    print("0 production visual @Page violations found")
    return 0


if __name__ == "__main__":
    sys.exit(main())
