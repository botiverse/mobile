#!/usr/bin/env python3
"""Static contract: the mermaid consumer stays confined to one shared adapter.

Incident-born gate (task #373 / PR #2497). Under Kuikly the mermaid renderer
was copied per platform (RaftMermaidDiagramBlock.{android,ios}.kt) and one
plane silently drifted. The Compose Multiplatform switch replaced those
actuals with a single commonMain implementation: RaftMermaidDiagramBlock.kt
(parse + layout) and RaftMermaidCanvas.kt (Compose Canvas drawing). This gate
keeps that shape:

Assertions:
  1. The commonMain adapter files exist (an empty scope never passes).
  2. No per-platform mermaid actual (RaftMermaidDiagramBlock.<plane>.kt or
     RaftMermaidCanvas.<plane>.kt) reappears in any source set — one
     implementation, so planes cannot drift again.
  3. Production (non-test) Kotlin sources import `build.raft.mermaid` only from
     the adapter files listed in ADAPTER_FILES. Test source sets may import it
     to exercise the adapter.

Usage:
  check_mermaid_plane_confinement.py            enforce against the repo
  check_mermaid_plane_confinement.py --self-test  run synthetic mutation arms
"""

from __future__ import annotations

import re
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

ADAPTER_DIR = Path("compose/shared/src/commonMain/kotlin/build/raft/app/markdown")
ADAPTER_FILES = (
    ADAPTER_DIR / "RaftMermaidDiagramBlock.kt",
    ADAPTER_DIR / "RaftMermaidCanvas.kt",
)
SOURCE_ROOTS = (Path("compose"),)

IMPORT_RE = re.compile(r"^\s*import\s+(build\.raft\.mermaid(?:\.[A-Za-z0-9_]+)*)")
PLANE_ACTUAL_RE = re.compile(r"^(RaftMermaidDiagramBlock|RaftMermaidCanvas)\.[A-Za-z0-9]+\.kt$")
TEST_SOURCE_SET_RE = re.compile(r"(Test|test)$")


def _fail(header: str, details: list[str]) -> int:
    print("mermaid plane confinement contract: FAIL")
    print(f"  {header}")
    for line in details:
        print(f"  {line}")
    return 1


def _pass() -> int:
    print("mermaid plane confinement contract: PASS")
    return 0


def _source_set(rel: Path) -> str | None:
    parts = rel.parts
    if "src" not in parts:
        return None
    index = parts.index("src")
    return parts[index + 1] if index + 1 < len(parts) else None


def _iter_kotlin(base: Path):
    for root in SOURCE_ROOTS:
        top = base / root
        if not top.is_dir():
            continue
        for kt in sorted(top.rglob("*.kt")):
            rel = kt.relative_to(base)
            if "src" not in rel.parts:
                continue
            outer = rel.parts[: rel.parts.index("src")]
            if "build" in outer or ".gradle" in outer:
                continue
            yield rel, kt


def enforce(base: Path) -> int:
    violations: list[str] = []
    for adapter in ADAPTER_FILES:
        if not (base / adapter).is_file():
            violations.append(f"required commonMain adapter missing: {adapter}")

    for rel, kt in _iter_kotlin(base):
        if PLANE_ACTUAL_RE.match(rel.name):
            violations.append(
                f"{rel}: per-platform mermaid actual reintroduced; keep the single "
                f"commonMain adapter ({ADAPTER_DIR})"
            )
        source_set = _source_set(rel)
        if source_set is not None and TEST_SOURCE_SET_RE.search(source_set):
            continue
        if rel in ADAPTER_FILES:
            continue
        text = kt.read_text(encoding="utf-8", errors="replace")
        for lineno, line in enumerate(text.splitlines(), start=1):
            m = IMPORT_RE.match(line)
            if m:
                violations.append(
                    f"{rel}:{lineno}: import {m.group(1)} outside the mermaid adapter "
                    f"(route through RaftMermaidDiagramBlock instead)"
                )

    if violations:
        return _fail("right-cause confinement broken:", violations)
    return _pass()


CLEAN_BLOCK = """package build.raft.app.markdown

import build.raft.mermaid.core.MermaidParser

internal fun RaftMermaidDiagramBlock(code: String, fallback: () -> Unit) {
    MermaidParser.parse(code)
}
"""

CLEAN_CANVAS = """package build.raft.app.markdown

import build.raft.mermaid.layout.LayoutScene

internal fun RaftMermaidCanvas(scene: LayoutScene) = Unit
"""

CLEAN_CONSUMER = """package build.raft.app.markdown

/** Mentions of build.raft.mermaid in kdoc are allowed. */
internal fun renderFence(code: String) = RaftMermaidDiagramBlock(code) {}
"""

CLEAN_TEST = """package build.raft.app.markdown

import build.raft.mermaid.core.MermaidParser
import kotlin.test.Test

class MermaidAdapterTest {
    @Test
    fun parses() { MermaidParser.parse("graph TD; A-->B") }
}
"""


def _write(base: Path, rel: Path | str, text: str) -> Path:
    path = base / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return path


def _write_clean_tree(base: Path) -> None:
    _write(base, ADAPTER_FILES[0], CLEAN_BLOCK)
    _write(base, ADAPTER_FILES[1], CLEAN_CANVAS)
    _write(base, ADAPTER_DIR / "RaftMarkdown.kt", CLEAN_CONSUMER)
    _write(
        base,
        "compose/shared/src/androidUnitTest/kotlin/build/raft/app/markdown/MermaidAdapterTest.kt",
        CLEAN_TEST,
    )


def self_test() -> int:
    arms = 0

    def expect(base: Path, label: str, should_pass: bool) -> int:
        nonlocal arms
        arms += 1
        if (enforce(base) == 0) != should_pass:
            verdict = "pass" if should_pass else "be caught"
            print(f"self-test FAIL: {label} must {verdict}", file=sys.stderr)
            return 1
        return 0

    with tempfile.TemporaryDirectory() as td:
        empty = Path(td) / "empty"
        _write(empty, "compose/shared/src/commonMain/kotlin/X.kt", "package x\n")
        if expect(empty, "missing adapter", False):
            return 1

        base = Path(td) / "repo"
        _write_clean_tree(base)
        if expect(base, "clean tree", True):
            return 1

        actual = _write(
            base,
            "compose/shared/src/iosMain/kotlin/build/raft/app/markdown/RaftMermaidDiagramBlock.ios.kt",
            "package build.raft.app.markdown\n",
        )
        if expect(base, "per-platform actual reintroduced", False):
            return 1
        actual.unlink()

        consumer = base / ADAPTER_DIR / "RaftMarkdown.kt"
        consumer.write_text(
            CLEAN_CONSUMER.replace(
                "/**", "import build.raft.mermaid.core.MermaidParser\n\n/**", 1
            ),
            encoding="utf-8",
        )
        if expect(base, "commonMain mermaid import outside adapter", False):
            return 1
        consumer.write_text(CLEAN_CONSUMER, encoding="utf-8")

        leak = _write(
            base,
            "compose/androidApp/src/main/java/build/raft/app/Leak.kt",
            "package build.raft.app\nimport build.raft.mermaid.layout.LayoutScene\n",
        )
        if expect(base, "host mermaid import", False):
            return 1
        leak.unlink()

        if expect(base, "restored clean tree", True):
            return 1

    print(f"mermaid plane confinement contract self-test: PASS ({arms} arms)")
    return 0


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return self_test()
    return enforce(REPO_ROOT)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
