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
import xml.etree.ElementTree as ET
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
AUTH_STRINGS = {
    "base": "compose/shared/src/commonMain/resources/MR/base/strings.xml",
    "zh-rCN": "compose/shared/src/commonMain/resources/MR/zh-rCN/strings.xml",
    "ja": "compose/shared/src/commonMain/resources/MR/ja/strings.xml",
}
AUTH_AUTHORITY_CHANGED_KEY = "auth_error_session_changed_retry_create_server"
AUTH_AUTHORITY_CHANGED_COPY = {
    "base": "Your session changed. Try creating the server again.",
    "zh-rCN": "您的会话已更改。请重新创建服务器。",
    "ja": "セッションが変更されました。もう一度サーバーを作成してください。",
}

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
    direct_index = re.compile(
        r"TargetSelected\(\s*(?:item\s*=\s*)?state\.items\[index\]"
    )
    captured_row = re.compile(
        r"TargetSelected\(\s*(?:item\s*=\s*)?rowTarget\b"
    )
    if direct_index.search(thread_page):
        raise Violation(
            "task #803 regression: forward picker onClick re-reads state.items[index] "
            "at click time; capture the render-time row object instead"
        )
    if not captured_row.search(thread_page):
        raise Violation(
            "task #803 anchor missing: forward picker rows must pass the "
            "render-time captured rowTarget to TargetSelected"
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


def check_auth_authority_changed_i18n(locale_xml: dict[str, str]) -> None:
    """Incident: task #161 (internal auth code leaked through server picker).

    Base is the runtime fallback. The two shipped non-English catalogs must carry
    the same key with reviewed actionable copy; missing/drifted values fail closed.
    """
    if set(locale_xml) != set(AUTH_AUTHORITY_CHANGED_COPY):
        raise Violation("task #161 i18n gate must receive base/zh-rCN/ja catalogs")
    for locale, expected in AUTH_AUTHORITY_CHANGED_COPY.items():
        try:
            root = ET.fromstring(locale_xml[locale])
        except ET.ParseError as error:
            raise Violation(f"task #161 {locale} strings XML is invalid: {error}") from error
        values = {
            element.attrib.get("name"): "".join(element.itertext())
            for element in root.findall("string")
        }
        actual = values.get(AUTH_AUTHORITY_CHANGED_KEY)
        if actual != expected:
            raise Violation(
                f"task #161 {locale} {AUTH_AUTHORITY_CHANGED_KEY} drift: "
                f"expected {expected!r}, got {actual!r}"
            )


def check_system_selectable_text_dialog(thread_page: str) -> None:
    """Incident: task #924 (system selection menu replaced by app chrome).

    The full-screen Select Text destination must expose the immutable message
    body through Kuikly's native ``SelectableText`` bridge. Reintroducing a
    ``SelectionContainer`` or business-owned copy/select controls suppresses
    platform actions such as Android PROCESS_TEXT and iOS Look Up/Translate.
    """
    match = re.search(
        r"private fun ThreadMessageTextSelectionDialog\(.*?"
        r"\n}\n\n@Composable\nprivate fun ThreadMessageMenuChrome",
        thread_page,
        flags=re.DOTALL,
    )
    if match is None:
        raise Violation(
            "task #924 anchor missing: ThreadMessageTextSelectionDialog must remain "
            "a distinct auditable surface"
        )
    dialog = match.group(0)
    required = {
        "val originalText = message.body": "bind the immutable message body",
        "LazyColumn(": "keep long text externally scrollable",
        "SelectableText(": "render through the native selectable-text bridge",
        "text = originalText": "pass original message bytes to the native surface",
        '.testTag("thread_message_selection_text")': (
            "project a stable native resource id for physical UIAutomator admission"
        ),
        "Modal(onWillDismiss = { onDismiss() })": "dispose the surface on dismissal",
    }
    for token, reason in required.items():
        if token not in dialog:
            raise Violation(f"task #924 regression: selection dialog must {reason}")

    forbidden = (
        "SelectionContainer",
        "rememberSelectionContainerState",
        "onCopyText",
        "createSelection",
        "getSelection",
        "TextField",
        "pointerInput",
        "thread_text_selection_",
        "SlockBrutalActionButton",
    )
    for token in forbidden:
        if token in dialog:
            raise Violation(
                f"task #924 regression: selection dialog contains {token}; "
                "system selection/menu ownership must not be replaced by app logic"
            )

    if "key(message.id, message.body)" not in thread_page:
        raise Violation(
            "task #924 lifecycle fence missing: selection dialog must be keyed by "
            "message identity and immutable body"
        )


def run(root: Path) -> None:
    def read(rel: str) -> str:
        path = root / rel
        if not path.is_file():
            raise Violation(f"constraint source missing (fail-closed): {rel}")
        return path.read_text(encoding="utf-8").replace("\r\n", "\n")

    thread_page = read(THREAD_PAGE)
    check_forward_row_identity(thread_page)
    check_system_selectable_text_dialog(thread_page)
    check_no_layout_direction_flip(
        {
            THREAD_PAGE: thread_page,
            SLOCK_MARKDOWN: read(SLOCK_MARKDOWN),
            VENDOR_PARAGRAPH: read(VENDOR_PARAGRAPH),
        }
    )
    check_notification_action_handlers(read(BRIDGE_AUTH), read(ANDROID_ACTIVITY))
    check_auth_authority_changed_i18n(
        {locale: read(path) for locale, path in AUTH_STRINGS.items()}
    )


def self_test() -> None:
    """Every checker must reject its violation shape and accept the good shape."""
    good_row = "x { val rowTarget = state.items[index]\n onClick = { TargetSelected(item = rowTarget, renderGeneration = g) } }"
    bad_row_reread = good_row + "\n onClick = { TargetSelected(item = state.items[index], renderGeneration = g) }"
    bad_row_anchorless = "nothing here"
    check_forward_row_identity(good_row)
    for bad in (bad_row_reread, bad_row_anchorless):
        try:
            check_forward_row_identity(bad)
        except Violation:
            pass
        else:
            raise SystemExit("self-test: row-identity checker accepted a violation")

    good_dialog = """
key(message.id, message.body) { Unit }
private fun ThreadMessageTextSelectionDialog(message: Message, onDismiss: () -> Unit) {
    val originalText = message.body
    Modal(onWillDismiss = { onDismiss() }) {
        LazyColumn(
            modifier = Modifier
        ) {
            item {
                SelectableText(
                    text = originalText,
                    modifier = Modifier.testTag("thread_message_selection_text")
                )
            }
        }
    }
}

@Composable
private fun ThreadMessageMenuChrome() = Unit
"""
    check_system_selectable_text_dialog(good_dialog)
    for bad in (
        good_dialog.replace("SelectableText(", "SelectionContainer("),
        good_dialog.replace('.testTag("thread_message_selection_text")', ""),
        good_dialog.replace(
            "item {",
            "SlockBrutalActionButton(); item {",
            1,
        ),
        good_dialog.replace("key(message.id, message.body)", "key(message.id)"),
    ):
        try:
            check_system_selectable_text_dialog(bad)
        except Violation:
            pass
        else:
            raise SystemExit("self-test: task #924 checker accepted a violation")

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

    good_auth_i18n = {
        locale: f'<resources><string name="{AUTH_AUTHORITY_CHANGED_KEY}">{value}</string></resources>'
        for locale, value in AUTH_AUTHORITY_CHANGED_COPY.items()
    }
    check_auth_authority_changed_i18n(good_auth_i18n)
    for bad in (
        {**good_auth_i18n, "ja": "<resources />"},
        {
            **good_auth_i18n,
            "zh-rCN": (
                f'<resources><string name="{AUTH_AUTHORITY_CHANGED_KEY}">wrong</string></resources>'
            ),
        },
    ):
        try:
            check_auth_authority_changed_i18n(bad)
        except Violation:
            pass
        else:
            raise SystemExit("self-test: task #161 i18n checker accepted a violation")

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
    print("source constraints hold (tasks #800/#803/#361/#161/#924 structural faces)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
