#!/usr/bin/env python3
"""Incident-born static source constraint (explicit CI gate, NOT a unit test).

Per the test-discipline announcement (#all 2026-07-25, artin-directed): static
constraints live here as an explicit gate with a self-test, instead of being
dressed up as source-grep "contract tests" inside test directories.

Only one constraint remains: check_no_conditional_interaction_state, born from
the 1.15.0 release crash on launch. The other "forbid a future pattern" guards
were deleted as redundant; behavior is proven by behavior tests.

Exit codes: 0 = the constraint holds; 1 = violation (fail-closed, including
missing source roots); 2 = usage error.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]


class Violation(Exception):
    pass


CONDITIONAL_STATE_ROOTS = (
    "compose/designsystem/src/commonMain/kotlin",
    "compose/shared/src/commonMain/kotlin",
)
CONDITIONAL_INTERACTION_STATE = re.compile(
    r"(&&|\|\||\?:)\s*\(?\s*!?\s*"
    r"(raftIs(Pressed|Hovered|Focused)\s*\(|[\w.\[\]()]*\bcollectIs\w+AsState\s*\()"
)


def check_no_conditional_interaction_state(sources: dict[str, str]) -> None:
    """Incident: 1.15.0 (11500007) release crash on launch (CMP 1.12.2 / Kotlin 2.4 / R8).

    `ClassCastException: RaftButtonKt$$ExternalSyntheticLambda9 cannot be cast to
    MutableState` in `collectIsPressedAsState` <- `raftIsPressed` <- `RaftButtonBox`.
    `raftIsPressed/Hovered/Focused` and `collectIs*AsState` `remember` state; placed on the
    right of `&&` / `||` / `?:` they run conditionally, so the remembered slots change when
    `enabled` flips and a slot reads back the wrong object. Hoist the call to a local first.
    """
    for name, text in sources.items():
        match = CONDITIONAL_INTERACTION_STATE.search(text)
        if match:
            line = text.count("\n", 0, match.start()) + 1
            raise Violation(
                f"conditional composable state: {name}:{line} calls "
                f"{match.group(2).strip()}...) behind a short-circuit operator; hoist it "
                "to an unconditional `val isPressed = raftIsPressed(source)` first"
            )


def _conditional_state_sources(root: Path) -> dict[str, str]:
    sources: dict[str, str] = {}
    for rel in CONDITIONAL_STATE_ROOTS:
        base = root / rel
        if not base.is_dir():
            raise Violation(f"conditional composable state: missing source root {rel}")
        for path in sorted(base.rglob("*.kt")):
            sources[str(path.relative_to(root))] = path.read_text(encoding="utf-8")
    if not any("raftIsPressed(" in text for text in sources.values()):
        raise Violation("conditional composable state: scan never reached raftIsPressed")
    return sources


def run(root: Path) -> None:
    check_no_conditional_interaction_state(_conditional_state_sources(root))


def self_test() -> None:
    """The checker must reject every violation shape and accept the good shape."""
    check_no_conditional_interaction_state(
        {"f": "val isPressed = raftIsPressed(source)\nval pressed = active && (isPressed || isHovered)"}
    )
    for bad in (
        "val pressed = active && (raftIsPressed(source) || raftIsHovered(source))",
        "val p = raftIsPressed(source) || raftIsHovered(source)",
        "focused += link.enabled &&\n    raftIsFocused(sources[i])",
        "val p = enabled && interactionSource.collectIsPressedAsState().value",
        "val f = enabled && !raftIsFocused(source)",
    ):
        try:
            check_no_conditional_interaction_state({"f": bad})
        except Violation:
            pass
        else:
            raise SystemExit(f"self-test: conditional-state checker accepted {bad!r}")


    print("check_source_constraints self-test passed")


def main(argv: list[str]) -> int:
    if len(argv) == 2 and argv[1] == "--self-test":
        self_test()
        return 0
    if len(argv) != 1:
        print(__doc__, file=sys.stderr)
        return 2
    try:
        run(REPO_ROOT)
    except Violation as violation:
        print(f"source-constraint violation: {violation}", file=sys.stderr)
        return 1
    print("source constraints hold (1.15.0 conditional interaction state)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
