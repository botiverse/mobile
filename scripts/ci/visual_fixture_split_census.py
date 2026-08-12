#!/usr/bin/env python3
"""Census + guard for the task #189 visual fixture split.

Two analysis mistakes were made while splitting the thread message-row visual
cluster out of ThreadPage.kt, and both were caught by the Kotlin compiler rather
than by review:

  1. A visual-only symbol was left behind because the closure that decided what
     to move only considered ``private`` declarations, so an ``internal``
     visual-only helper was invisible to it. Membership is about whether a
     symbol is visual-only, not about its visibility keyword.
  2. Private *extension* functions were never classified at all, because the
     regex that collected private symbols skipped extension receivers (to avoid
     reading ``List`` out of ``private fun <T> List<T>.x``) and thereby skipped
     the extension functions themselves.

This script re-derives the census from source so a reviewer can reproduce it,
and fails when either mistake recurs:

  LEFT-BEHIND  a declaration in the production file whose only references are
               from the moved visual files - it should have moved. This is the
               hard failure, because nothing else catches it: a left-behind
               visual-only symbol still compiles, it just keeps visual surface
               area (and sometimes widened visibility) in production for no
               reason.
  UNREACHABLE  reported for information only. The Kotlin compiler is the
               authority here and already fails the build, and this text-level
               scan produces false positives when a moved file happens to use
               the same identifier in another role (a named argument called
               `icon`, say).

Run: python3 scripts/ci/visual_fixture_split_census.py
"""
from __future__ import annotations

import re
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PRODUCTION = ROOT / "compose/shared/src/commonMain/kotlin/build/raft/app/ThreadPage.kt"
MOVED = (
    ROOT / "compose/shared/src/visualFixtures/kotlin/build/raft/app/ThreadMessageRowVisualHost.kt",
    ROOT / "compose/shared/src/visualFixtureData/kotlin/build/raft/app/ThreadMessageRowVisualFixtures.kt",
)

# Captures the declared NAME, including extension functions (receiver is group 1
# and deliberately discarded) so extensions are never silently skipped.
DECL = re.compile(
    r"^(?P<vis>private|internal)\s+(?:const\s+)?(?:suspend\s+)?(?:inline\s+)?"
    r"(?:fun|val|var|class|object|interface|enum class|data class|typealias)\s+"
    r"(?:<[^>]*>\s*)?(?:[\w.<>, ?]+\.)?(?P<name>\w+)\b",
    re.MULTILINE,
)


def declarations(text: str) -> dict[str, str]:
    found: dict[str, str] = {}
    for m in DECL.finditer(text):
        found.setdefault(m.group("name"), m.group("vis"))
    return found


def occurrences(text: str, name: str) -> int:
    return len(re.findall(r"\b%s\b" % re.escape(name), text))


def self_test() -> int:
    """The guard must reject a left-behind visual-only declaration and accept its absence.

    Without this, the census is exactly the kind of rule that quietly stops
    biting - which is how verifyNoProductionVisualPages accumulated nine
    violations while nobody noticed it never ran.
    """
    production_clean = """
internal fun ProductionThing() = Unit
private fun StaysPrivate() = Unit
internal fun SomeProductionCaller() {
    ProductionThing()
}
"""
    production_with_left_behind = production_clean + """
internal fun OnlyTheVisualFilesUseThis() = Unit
"""
    moved = """
fun render() {
    ProductionThing()
    OnlyTheVisualFilesUseThis()
}
"""
    clean = classify(production_clean, moved)
    if clean.left_behind:
        print(
            "self-test: census reported a left-behind symbol on clean input: "
            f"{clean.left_behind}",
            file=sys.stderr,
        )
        return 1
    if "ProductionThing" not in dict(clean.widened):
        print("self-test: census failed to report a real production dependency", file=sys.stderr)
        return 1

    dirty = classify(production_with_left_behind, moved)
    if "OnlyTheVisualFilesUseThis" not in dirty.left_behind:
        print(
            "self-test: census accepted a visual-only declaration left in production",
            file=sys.stderr,
        )
        return 1
    print("visual_fixture_split_census self-test passed")
    return 0


@dataclass
class Census:
    left_behind: list[str]
    unreachable: list[str]
    widened: list[tuple[str, int]]


def classify(production: str, moved_text: str) -> Census:
    left_behind: list[str] = []
    unreachable: list[str] = []
    widened: list[tuple[str, int]] = []
    for name, vis in sorted(declarations(production).items()):
        if not occurrences(moved_text, name):
            continue
        remaining = occurrences(production, name) - 1
        if vis == "private":
            unreachable.append(name)
        elif remaining == 0:
            left_behind.append(name)
        else:
            widened.append((name, remaining))
    return Census(left_behind, unreachable, widened)


def main() -> int:
    production = PRODUCTION.read_text(encoding="utf-8")
    moved_text = "\n".join(p.read_text(encoding="utf-8") for p in MOVED)
    result = classify(production, moved_text)
    left_behind, unreachable, widened = result.left_behind, result.unreachable, result.widened

    print("== visual-only symbols still declared in the production file ==")
    print("   (expect none; each should have moved with the cluster)")
    for n in left_behind:
        print(f"   LEFT-BEHIND  {n}")
    print()
    print("== private declarations the moved visual files appear to reference ==")
    print("   (informational: the compiler is authoritative; identifier reuse")
    print("    such as a named argument `icon` shows up here as a false positive)")
    for n in unreachable:
        print(f"   UNREACHABLE  {n}")
    print()
    print("== internal production symbols the visual files depend on ==")
    print("   (the widening census; each still has production call sites)")
    for n, remaining in widened:
        print(f"   {n:<52} production-uses={remaining}")
    print()
    print(f"total widened={len(widened)} left-behind={len(left_behind)} unreachable={len(unreachable)}")

    if left_behind:
        print(
            "\nFAIL: visual-only symbols are still declared in the production file",
            file=sys.stderr,
        )
        return 1
    print("census clean")
    return 0


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        raise SystemExit(self_test())
    raise SystemExit(main())
