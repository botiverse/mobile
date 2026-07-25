#!/usr/bin/env python3
"""Incident-born static source constraints (explicit CI gate, NOT unit tests).

Per the test-discipline announcement (#all 2026-07-25, artin-directed): static
constraints live here as an explicit gate with a self-test, instead of being
dressed up as source-grep "contract tests" inside test directories.

Each constraint records the incident it was born from. Behavior remains proven
by real behavior tests (e.g. ThreadForwardFlowTest drift scenarios); this gate
only pins the structural faces that a refactor could silently reintroduce.

Exit codes: 0 = all constraints hold; 1 = violation (fail-closed, including
missing files); 2 = usage error.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

THREAD_PAGE = "compose/shared/src/commonMain/kotlin/build/raft/app/ThreadPage.kt"
SLOCK_MARKDOWN = "compose/shared/src/commonMain/kotlin/build/raft/app/markdown/SlockMarkdown.kt"
VENDOR_PARAGRAPH = (
    "third_party/kuikly-compose-markdown/src/commonMain/kotlin/"
    "com/tencent/kuiklybase/markdown/compose/elements/MarkdownParagraph.kt"
)
BRIDGE_AUTH = "compose/shared/src/commonMain/kotlin/build/raft/app/data/BridgeAuth.kt"
ANDROID_ACTIVITY = "compose/androidApp/src/main/java/build/raft/app/shared/SlockKuiklyActivity.kt"

# Settings notification actions the shared side dispatches; each MUST have an
# explicit Android capability handler (incident: task #361 — Android silently
# lacked getNotificationPermissionStatus, permission banner lied forever).
NOTIFICATION_ACTIONS = (
    "getNotificationPermissionStatus",
    "requestNotificationPermission",
    "openNotificationSettings",
    "sendTestNotification",
)


class Violation(Exception):
    pass


def check_forward_row_identity(thread_page: str) -> None:
    """Incident: task #803 / feedback #152 (P0 wrong-recipient forward).

    The picker row's onClick once re-read ``targets[index]`` at click time
    while the displayed identity came from render time; a directory refresh
    between the two frames forwarded to the wrong destination. The structural
    face: click handlers must consume the render-time captured object, never
    an index into the mutable list. (Behavioral proof: ThreadForwardFlowTest.)
    """
    if "onSelectTarget(targets[index])" in thread_page:
        raise Violation(
            "task #803 regression: forward picker onClick re-reads targets[index] "
            "at click time; capture the render-time row object instead"
        )
    if "onSelectTarget(rowTarget)" not in thread_page:
        raise Violation(
            "task #803 anchor missing: forward picker rows must pass the "
            "render-time captured rowTarget to onSelectTarget"
        )


def check_no_layout_direction_flip(sources: dict[str, str]) -> None:
    """Incident: task #800 (right-aligned own messages).

    Alignment is expressed through Alignment.End parameters only. Flipping
    LayoutDirection would mirror BiDi text rendering, gesture anchors and
    popover placement — none of which are RTL-audited in the Kuikly hosts.
    """
    pattern = re.compile(r"LocalLayoutDirection\s+provides\s+LayoutDirection\.Rtl")
    for name, text in sources.items():
        if pattern.search(text):
            raise Violation(
                f"task #800 regression: {name} flips LayoutDirection for alignment; "
                "use Alignment.End parameterization instead"
            )


def check_notification_action_handlers(bridge: str, activity: str) -> None:
    """Incident: task #361 (permission banner permanently wrong on Android)."""
    for action in NOTIFICATION_ACTIONS:
        if f'"{action}"' not in bridge:
            raise Violation(
                f"task #361 drift: shared BridgeAuth no longer dispatches {action}; "
                "update NOTIFICATION_ACTIONS in this gate to match reality"
            )
        if f'"{action}" ->' not in activity:
            raise Violation(
                f"task #361 regression: Android capability switch lacks a handler "
                f"for {action}; unknown actions silently break the settings UI"
            )


def run(root: Path) -> None:
    def read(rel: str) -> str:
        path = root / rel
        if not path.is_file():
            raise Violation(f"constraint source missing (fail-closed): {rel}")
        return path.read_text(encoding="utf-8").replace("\r\n", "\n")

    thread_page = read(THREAD_PAGE)
    check_forward_row_identity(thread_page)
    check_no_layout_direction_flip(
        {
            THREAD_PAGE: thread_page,
            SLOCK_MARKDOWN: read(SLOCK_MARKDOWN),
            VENDOR_PARAGRAPH: read(VENDOR_PARAGRAPH),
        }
    )
    check_notification_action_handlers(read(BRIDGE_AUTH), read(ANDROID_ACTIVITY))


def self_test() -> None:
    """Every checker must reject its violation shape and accept the good shape."""
    good_row = "x { val rowTarget = targets[index]\n onClick = { onSelectTarget(rowTarget) } }"
    bad_row_reread = good_row + "\n onClick = { onSelectTarget(targets[index]) }"
    bad_row_anchorless = "nothing here"
    check_forward_row_identity(good_row)
    for bad in (bad_row_reread, bad_row_anchorless):
        try:
            check_forward_row_identity(bad)
        except Violation:
            pass
        else:
            raise SystemExit("self-test: row-identity checker accepted a violation")

    check_no_layout_direction_flip({"f": "Arrangement.spacedBy(12.dp, Alignment.End)"})
    try:
        check_no_layout_direction_flip(
            {"f": "CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl)"}
        )
    except Violation:
        pass
    else:
        raise SystemExit("self-test: layout-direction checker accepted a violation")

    good_bridge = "".join(f'put("action", "{a}")\n' for a in NOTIFICATION_ACTIONS)
    good_activity = "".join(f'"{a}" -> handle()\n' for a in NOTIFICATION_ACTIONS)
    check_notification_action_handlers(good_bridge, good_activity)
    try:
        check_notification_action_handlers(good_bridge, good_activity.replace(
            '"sendTestNotification" ->', '"somethingElse" ->'
        ))
    except Violation:
        pass
    else:
        raise SystemExit("self-test: notification-handler checker accepted a violation")

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
    print("source constraints hold (tasks #800/#803/#361 structural faces)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
