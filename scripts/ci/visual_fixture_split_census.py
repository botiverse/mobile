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
import subprocess
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
    # Receiver handling: the optional receiver group is GREEDY, which is what makes
    # `private fun RaftThreadMessageItem.toMemberProfile()` register
    # `toMemberProfile` rather than the receiver. The trailing (?!\s*\.) is
    # belt-and-braces only - mutation testing shows removing it changes nothing,
    # while making the receiver group lazy or deleting it does break the contract,
    # and self_test() catches both of those.
    #
    # Recorded because I got this wrong once: the phantom private->internal
    # transitions seen during task #189 came from a throwaway analysis script that
    # anchored the NAME directly (letting the optional receiver be skipped), not
    # from this pattern.
    r"(?:<[^>]*>\s*)?(?:[\w.<>, ?]+\.)?(?P<name>\w+)(?!\s*\.)\b",
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

    # Failure shape 1: a plain extension declaration must be read as the FUNCTION
    # name, never the receiver. Misreading this invented phantom private->internal
    # transitions three separate times during task #189.
    plain_extension = "private fun RaftThreadMessageItem.toMemberProfile() = Unit\n"
    names = declarations(plain_extension)
    if "toMemberProfile" not in names:
        print("self-test: extension declaration did not register its function name", file=sys.stderr)
        return 1
    if "RaftThreadMessageItem" in names:
        print(
            "self-test: extension receiver was registered as a declaration name",
            file=sys.stderr,
        )
        return 1

    # Failure shape 2: same, with a generic receiver - the form that first hid
    # five extension functions from the split analysis.
    generic_extension = "private fun <T> List<T>.firstVisualOrNull() = null\n"
    names = declarations(generic_extension)
    if "firstVisualOrNull" not in names:
        print("self-test: generic extension did not register its function name", file=sys.stderr)
        return 1
    if "List" in names:
        print("self-test: generic receiver was registered as a declaration name", file=sys.stderr)
        return 1

    # Failure shape 3: a visual-only EXTENSION left behind must be RED, and GREEN
    # once migrated. The left-behind check is the one the compiler cannot make.
    production_left_behind_extension = production_clean + (
        "internal fun RaftThreadScreenPayload.withVisualOnlyTweak() = this\n"
    )
    moved_using_extension = moved + "\nfun more() { payload.withVisualOnlyTweak() }\n"
    stuck = classify(production_left_behind_extension, moved_using_extension)
    if "withVisualOnlyTweak" not in stuck.left_behind:
        print(
            "self-test: census accepted a visual-only extension left in production",
            file=sys.stderr,
        )
        return 1
    freed = classify(production_clean, moved_using_extension)
    if "withVisualOnlyTweak" in freed.left_behind:
        print(
            "self-test: census still reported a migrated extension as left behind",
            file=sys.stderr,
        )
        return 1

    # Failure shape 4: a visual-only constant left declared in production while the
    # moved data declares it too. This slipped through once: the census printed it
    # under the informational `unreachable` list and still returned clean, and the
    # duplicate was only caught at compile time by review.
    dup_production = production_clean + "private const val VISUAL_ONLY_CONST = 3\n"
    dup_moved = moved + "\ninternal const val VISUAL_ONLY_CONST = 3\n"
    dup = classify(dup_production, dup_moved)
    if "VISUAL_ONLY_CONST" not in dup.duplicated:
        print(
            "self-test: census accepted a symbol declared in both production and moved data",
            file=sys.stderr,
        )
        return 1
    freed_dup = classify(production_clean, dup_moved)
    if freed_dup.duplicated:
        print(
            f"self-test: census reported a duplicate after production removal: {freed_dup.duplicated}",
            file=sys.stderr,
        )
        return 1

    # Failure shape 5: prose whose subject moved out. Declaration-level checks are
    # blind to it, and the block silently re-binds to the next declaration.
    detached = "/** Leading messages of the inline-selection fixture. */\n\nfun unrelated() = Unit\n"
    if not orphaned_visual_prose(detached):
        print("self-test: a detached fixture doc block was not detected", file=sys.stderr)
        return 1
    attached = "/** Leading messages of the inline-selection fixture. */\ninternal fun stillHere() = Unit\n"
    if orphaned_visual_prose(attached):
        print(
            "self-test: a fixture doc block still attached to its declaration was flagged",
            file=sys.stderr,
        )
        return 1
    unrelated_doc = "/** Resolves the composer mention scope. */\n\nfun other() = Unit\n"
    if orphaned_visual_prose(unrelated_doc):
        print("self-test: ordinary production prose was flagged as orphaned", file=sys.stderr)
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
    duplicated: list[str]


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
    # Declared in BOTH the production file and a moved file. This is a hard
    # failure and deliberately separate from `unreachable`: that one compares a
    # declaration against a *usage* and false-positives on identifier reuse, while
    # this compares declaration against declaration, so it cannot. Under flag=true
    # both land in commonMain and conflict outright; under flag=false the moved
    # copy joins commonTest alongside the production one in the same package.
    moved_decls = declarations(moved_text)
    prod_decls = declarations(production)
    duplicated = sorted(set(moved_decls) & set(prod_decls))
    return Census(left_behind, unreachable, widened, duplicated)


# Prose that only makes sense next to a moved declaration. A declaration-level
# census cannot see an orphaned KDoc block: the doc survives, its subject leaves,
# and the block silently re-binds to whatever production function follows it.
# That happened once here - a fixture KDoc ended up documenting
# threadComposerMentionScopeChannelId.
VISUAL_ONLY_PROSE = (
    "inline-selection fixture",
    "capture case",
    "visual fixture",
    "compact target group",
)


def orphaned_visual_prose(production: str) -> list[tuple[int, str]]:
    """Doc blocks about visual fixtures that no longer sit against a declaration.

    Content alone is not the signal: production code legitimately explains why it
    serves fixtures (ThreadReactionAtlasProvider does), and a comment directly
    above a visual @Page that has not moved yet is fine too. The failure is
    STRUCTURAL - a block whose subject moved out is left separated from the next
    code by a blank line, so it silently re-binds to whatever follows.
    """
    lines = production.split("\n")
    hits: list[tuple[int, str]] = []
    index = 0
    while index < len(lines):
        stripped = lines[index].strip()
        if stripped.startswith("/**"):
            start = index
            while index < len(lines) and "*/" not in lines[index]:
                index += 1
            block = "\n".join(lines[start : index + 1]).lower()
            after = index + 1
            blanks = 0
            while after < len(lines) and lines[after].strip() == "":
                blanks += 1
                after += 1
            if blanks and any(needle in block for needle in VISUAL_ONLY_PROSE):
                hits.append((start + 1, lines[start].strip()[:100]))
        index += 1
    return hits


def base_production(rev: str = "origin/main") -> str | None:
    """The pre-split production file, for the migrated-symbol census.

    Returns None when the base cannot be read (shallow clone, detached CI
    checkout). The migrated census is then reported as unavailable rather than
    guessed at - a partial list would read like a complete one.
    """
    rel = PRODUCTION.relative_to(ROOT).as_posix()
    try:
        done = subprocess.run(
            ["git", "show", f"{rev}:{rel}"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError:
        return None
    return done.stdout if done.returncode == 0 and done.stdout else None


def migrated_symbols(base: str, production: str, moved_text: str) -> list[tuple[str, str]]:
    """Symbols declared in the pre-split production file and now declared in the moved files."""
    out: list[tuple[str, str]] = []
    base_decls = declarations(base)
    now_decls = declarations(production)
    for name, vis in sorted(declarations(moved_text).items()):
        if name in base_decls and name not in now_decls:
            out.append((name, base_decls[name]))
    return out


def main() -> int:
    production = PRODUCTION.read_text(encoding="utf-8")
    moved_text = "\n".join(p.read_text(encoding="utf-8") for p in MOVED)
    result = classify(production, moved_text)
    left_behind, unreachable, widened = result.left_behind, result.unreachable, result.widened
    duplicated = result.duplicated

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
    base_for_split = base_production()
    base_decls = declarations(base_for_split) if base_for_split else {}
    now_decls = declarations(production)

    if base_for_split is None:
        print("== internal production symbols the visual files depend on ==")
        print("   (base revision unavailable, so widened-by-this-change cannot be split out)")
        for n, remaining in widened:
            print(f"   {n:<52} production-uses={remaining}")
    else:
        widened_here = [(n, r) for n, r in widened if base_decls.get(n) == "private"]
        pre_existing = [(n, r) for n, r in widened if base_decls.get(n) == "internal"]
        print("== A. widened by this change (private -> internal), still used by production ==")
        for n, remaining in widened_here:
            print(f"   {n:<52} production-uses={remaining}")
        print(f"   A total={len(widened_here)}")
        print()
        # Types that had to widen only because a widened signature exposes them.
        # They carry no direct textual use in the moved files, which is exactly why
        # they do not appear in the dependency scan above.
        cascade = sorted(
            n
            for n, vis in now_decls.items()
            if vis == "internal"
            and base_decls.get(n) == "private"
            and not occurrences(moved_text, n)
        )
        print("== B. widened only as a consequence of a widened signature ==")
        for n in cascade:
            print(f"   {n:<52} visual-uses=0")
        print(f"   B total={len(cascade)}")
        print()
        print("== pre-existing internal production symbols the visual files use ==")
        print("   (not widened by this change; listed so A is not over-read)")
        for n, remaining in pre_existing:
            print(f"   {n:<52} production-uses={remaining}")
        print(f"   pre-existing total={len(pre_existing)}")
    print()
    base = base_production()
    if base is None:
        print("== symbols fully migrated out of the production file ==")
        print("   UNAVAILABLE: could not read the base revision of the production file")
    else:
        migrated = migrated_symbols(base, production, moved_text)
        print("== symbols fully migrated out of the production file ==")
        print("   (declared in the base production file, now declared only in the moved files)")
        for name, vis in migrated:
            print(f"   {name:<62} was {vis}")
        print(f"   migrated total={len(migrated)}")
    print()
    prose = orphaned_visual_prose(production)
    print("== visual-only prose left in the production file ==")
    print("   (hard failure: an orphaned doc block re-binds to the next declaration)")
    for number, text in prose:
        print(f"   ORPHAN-DOC  {PRODUCTION.name}:{number}: {text}")
    print()
    print("== symbols declared in BOTH the production file and a moved file ==")
    print("   (hard failure: duplicate declaration in the same package)")
    for n in duplicated:
        print(f"   DUPLICATED  {n}")
    print()
    print(
        f"total widened={len(widened)} left-behind={len(left_behind)} "
        f"unreachable={len(unreachable)} duplicated={len(duplicated)} "
        f"orphan-doc={len(prose)}"
    )

    if left_behind or duplicated or prose:
        if left_behind:
            print(
                "\nFAIL: visual-only symbols are still declared in the production file",
                file=sys.stderr,
            )
        if duplicated:
            print(
                "\nFAIL: symbols are declared in both the production file and a moved file",
                file=sys.stderr,
            )
        if prose:
            print(
                "\nFAIL: visual-only prose is still in the production file",
                file=sys.stderr,
            )
        return 1
    print("census clean")
    return 0


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        raise SystemExit(self_test())
    raise SystemExit(main())
