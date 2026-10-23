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
import tempfile
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

THREAD_PAGE = "compose/shared/src/commonMain/kotlin/build/raft/app/ThreadPage.kt"
MEMBERS_PAGE = "compose/shared/src/commonMain/kotlin/build/raft/app/MembersPage.kt"
BASIC_WIDGET = "compose/shared/src/commonMain/kotlin/build/raft/app/BasicWidget.kt"
THREAD_COMPOSER_PAGE = (
    "compose/shared/src/commonMain/kotlin/build/raft/app/ThreadComposerPage.kt"
)
SHARED_TASKS_PAGE = "compose/shared/src/commonMain/kotlin/build/raft/app/SharedTasksPage.kt"
SHARED_INBOX_PANEL = "compose/shared/src/commonMain/kotlin/build/raft/app/SharedInboxPanel.kt"
SETTINGS_HOME_PAGE = "compose/shared/src/commonMain/kotlin/build/raft/app/SettingsHomePage.kt"
SETTINGS_FORM_PARTS = "compose/shared/src/commonMain/kotlin/build/raft/app/SettingsFormParts.kt"
BRUTAL_DROPDOWN = "compose/shared/src/commonMain/kotlin/build/raft/app/RaftBrutalDropdownMenu.kt"
FOREGROUND_BANNER = "compose/shared/src/commonMain/kotlin/build/raft/app/RaftForegroundBannerHost.kt"
GESTURE_OWNER = "compose/shared/src/commonMain/kotlin/build/raft/app/RaftGestureOwner.kt"
SLOCK_MARKDOWN = "compose/shared/src/commonMain/kotlin/build/raft/app/markdown/RaftMarkdown.kt"
VENDOR_PARAGRAPH = (
    "third_party/kuikly-compose-markdown/src/commonMain/kotlin/"
    "com/tencent/kuiklybase/markdown/compose/elements/MarkdownParagraph.kt"
)
BRIDGE_AUTH = "compose/shared/src/commonMain/kotlin/build/raft/app/data/BridgeAuth.kt"
SHARED_THREAD_STORE = "compose/shared/src/commonMain/kotlin/build/raft/app/SharedThreadStore.kt"
THREAD_API = "compose/shared/src/commonMain/kotlin/build/raft/app/data/ThreadApi.kt"
SHARED_TASKS_DATA_STORE = (
    "compose/shared/src/commonMain/kotlin/build/raft/app/data/SharedTasksDataStore.kt"
)
SHARED_TASKS_STORE = "compose/shared/src/commonMain/kotlin/build/raft/app/data/SharedTasksStore.kt"
TASKS_DATA_SOURCE = "compose/shared/src/commonMain/kotlin/build/raft/app/data/TasksDataSource.kt"
SLOCK_SHARED_STORE_PROVIDER = (
    "compose/shared/src/commonMain/kotlin/build/raft/app/data/RaftSharedStoreProvider.kt"
)
ANDROID_ACTIVITY = "compose/androidApp/src/main/java/build/raft/app/shared/RaftCmpActivity.kt"
VISUAL_FIXTURE_DATA = (
    "compose/shared/src/visualFixtureData/kotlin/build/raft/app/VisualFixtureData.kt"
)
COMPOSER_IME_CONNECTED_TEST = (
    "compose/androidApp/src/androidTest/java/build/raft/app/shared/"
    "RaftThreadImePaintConnectedTest.kt"
)
IOS_MOBILE_OAUTH_HOST = "compose/iosApp/iosApp/RaftIosMobileOAuthHost.swift"
IOS_MOBILE_OAUTH_SHARED = (
    "compose/shared/src/commonMain/kotlin/build/raft/app/data/IosMobileOAuth.kt"
)
AUTH_DATA_SOURCE = "compose/shared/src/commonMain/kotlin/build/raft/app/data/AuthDataSource.kt"
IOS_ENTITLEMENTS = "compose/iosApp/iosApp/iosApp.entitlements"
IOS_XCODE_PROJECT = "compose/iosApp/iosApp.xcodeproj/project.pbxproj"
AUTH_STRINGS = {
    "base": "compose/shared/src/commonMain/resources/MR/base/strings.xml",
    "zh-rCN": "compose/shared/src/commonMain/resources/MR/zh-rCN/strings.xml",
    "ja": "compose/shared/src/commonMain/resources/MR/ja/strings.xml",
}
AUTH_AUTHORITY_CHANGED_KEY = "auth_error_session_changed_retry_create_server"
AUTH_AUTHORITY_CHANGED_COPY = {
    "base": "Your session changed. Try creating the server again.",
    "zh-rCN": "你的会话已更改。请重新创建服务器。",
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


GESTURE_OWNER_REGIONS = (
    (
        BASIC_WIDGET,
        "internal fun Modifier.touchListenerWithConfiguration(",
        "private data class RaftTouchListenerGestureCallbacks(",
        "touch-listener",
    ),
    (BASIC_WIDGET, "internal fun Button(", "internal class Border(", "button"),
    (SHARED_TASKS_PAGE, "private fun SharedTaskCard(", "internal fun tasksStatusChipClickIdentity(", "tasks-card"),
    (SHARED_INBOX_PANEL, "internal fun Modifier.activityRowGestures(", "internal fun InboxItem.inboxItemOpenGestureKey(", "activity-row"),
    (SETTINGS_FORM_PARTS, "private fun SharedSettingsRow(", "private data class SharedSettingsRowGestureCallbacks(", "settings-row"),
    (SETTINGS_HOME_PAGE, "private fun SharedHomeSavedResultCard(", "private fun SharedHomeSavedUnsaveButton(", "home-saved-result"),
    (SETTINGS_HOME_PAGE, "private fun SharedHomeSearchEntityRow(", "internal data class SearchEntityPinTarget(", "home-search-entity"),
    (THREAD_PAGE, "private fun ThreadReactionChip(", "private data class ThreadReactionChipGestureCallbacks(", "thread-reaction-chip"),
    (THREAD_PAGE, "private fun ThreadMessageAvatar(", "private fun ThreadMessageStaticAvatar(", "thread-message-avatar"),
    (BRUTAL_DROPDOWN, "internal fun RaftBrutalDropdownMenuRow(", "private data class RaftBrutalDropdownRowGestureCallbacks(", "brutal-dropdown-row"),
    (FOREGROUND_BANNER, "internal fun RaftForegroundMessageBannerView(", "private data class ForegroundBannerGestureCallbacks(", "foreground-banner"),
)


def check_retained_pointer_gesture_owners(sources: dict[str, str]) -> None:
    """Incident: task #991 (equal Kuikly pointer elements retained old closures).

    These lazy/recycled or lifecycle-sensitive surfaces must route pointer handlers through a
    composition owner token plus a current callback slot. A raw pointerInput in any protected
    region can keep an old rememberUpdatedState holder, scope, anchor, or presentation state when
    Kuikly reuses an equal modifier element. The guarantee still applies on CMP: upstream
    pointerInput keeps its suspend block alive while its keys stay equal, and lazy rows are reused.
    """
    for path, start_token, end_token, surface in GESTURE_OWNER_REGIONS:
        source = sources.get(path)
        if source is None:
            raise Violation(f"task #991 source missing: {path}")
        start = source.find(start_token)
        end = source.find(end_token, start + len(start_token)) if start >= 0 else -1
        if start < 0 or end < 0:
            raise Violation(
                f"task #991 audit region missing for {surface}: {start_token!r}..{end_token!r}"
            )
        region = source[start:end]
        if ".pointerInput(" in region or " pointerInput(" in region:
            raise Violation(
                f"task #991 regression: {surface} uses raw pointerInput; "
                "route callbacks/state through RaftGestureOwner"
            )
        if ".callbacks" in region:
            raise Violation(
                f"task #991 regression: {surface} bypasses current-generation admission; "
                "read callbacks through currentCallbacksOrNull()"
            )
        required = (
            "rememberRaftGestureOwner(",
            f'surface = "{surface}"',
            ".raftGesturePointerInput(",
            ".beginGesture()",
            ".currentCallbacksOrNull()",
        )
        for token in required:
            if token not in region:
                raise Violation(
                    f"task #991 regression: {surface} missing retained-pointer contract {token!r}"
                )


def check_gesture_owner_generation_fence(source: str) -> None:
    required = (
        "internal val pointerInputOwnerToken: Any = Any()",
        "if (active) currentCallbacks = callbacks",
        "if (active) currentGestureToken = generationToken",
        "active && currentGestureToken === generationToken",
        "active = false",
    )
    for token in required:
        if token not in source:
            raise Violation(
                f"task #991 gesture owner lost opaque-token/active-generation fence {token!r}"
            )


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


def check_tasks_single_mutation_authority(
    shared_thread_store: str,
    thread_api: str,
    bridge_auth: str,
    shared_tasks_data_store: str,
    shared_tasks_store: str,
    tasks_data_source: str,
    slock_shared_store_provider: str,
) -> None:
    """Incident: task #17 (Thread retained a second task-status PATCH chain).

    Task status writes must enter through SharedTasksDataStore's owner/server/channel/task
    permit and the typed Tasks data source. Thread may render or dispatch a Tasks intent, but
    it must not regain its own data-source/API/network mutation method.
    """
    for label, source in (
        ("SharedThreadStore", shared_thread_store),
        ("ThreadApi", thread_api),
        ("SharedThreadNetworkApi", bridge_auth),
    ):
        if re.search(r"\b(?:suspend\s+fun|fun|override\s+suspend\s+fun)\s+updateTaskStatus\s*\(", source):
            raise Violation(
                f"task #17 regression: {label} reintroduced a private updateTaskStatus chain; "
                "route task writes through SharedTasksDataStore"
            )

    required = {
        "SharedTasksDataStore": (
            (shared_tasks_data_store, "suspend fun beginStatusMutation("),
            (shared_tasks_data_store, "suspend fun completeStatusMutation("),
            (shared_tasks_data_store, "suspend fun rollbackStatusMutation("),
            (shared_tasks_data_store, "currentServerIdProvider"),
            (shared_tasks_data_store, "ownerGeneration"),
        ),
        "SharedTasksStore": (
            (shared_tasks_store, "sharedStore.beginStatusMutation("),
            (shared_tasks_store, "sharedStore.completeStatusMutation("),
            (shared_tasks_store, "sharedStore.rollbackStatusMutation("),
        ),
        "TasksDataSource": (
            (tasks_data_source, '"tasks/${taskId.value}/status"'),
            (tasks_data_source, 'method = "PATCH"'),
        ),
        "SharedRealtimeDomainEventOwner": (
            (slock_shared_store_provider, "store.upsertTaskForCurrentOwner("),
            (slock_shared_store_provider, "store.removeTaskForCurrentOwner("),
            (slock_shared_store_provider, "reason=owner_admission_missing"),
        ),
    }
    for owner, tokens in required.items():
        for source, token in tokens:
            if token not in source:
                raise Violation(f"task #17 authority anchor missing from {owner}: {token!r}")


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
    body through ``RaftNativeSelectableText`` -- the CMP ``expect`` composable
    whose actuals host the platform text view (Android selectable ``TextView``
    via ``AndroidView``; iOS non-editable selectable ``UITextView`` via
    ``UIKitView``). Reintroducing a Compose ``SelectionContainer`` or
    business-owned copy/select controls suppresses platform actions such as
    Android PROCESS_TEXT and iOS Look Up/Translate/Share.
    """
    match = re.search(
        r"private fun ThreadMessageTextSelectionDialog\(.*?"
        # Anchor only — this marks where the dialog body ends. task #189 moved the
        # visual host into a gated source set, which required ThreadMessageMenuChrome
        # to become internal; the invariant below is about the dialog's contents, not
        # about that neighbour's visibility, so accept either keyword.
        r"\n}\n\n@Composable\n(?:private|internal) fun ThreadMessageMenuChrome",
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
        "RaftNativeSelectableText(": "render through the platform text view (RaftNativeSelectableText)",
        "text = originalText": "pass original message bytes to the native surface",
        '.testTag("thread_message_selection_text")': (
            "expose a stable resource id for physical UIAutomator admission"
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
        "RaftBrutalActionButton",
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


def check_thread_ime_tail_pixel_probe_scope(thread_page: str) -> None:
    """Incident: task #183 / VC1000134 (PixelCopy silently self-cancelled).

    The probe helper has behavior tests, but the production callsite can still
    regress by constructing it from the restartable LaunchedEffect receiver.
    Pin the composition-owned runner creation and the exact effect callsite.
    """
    code = re.sub(r"/\*.*?\*/", "", thread_page, flags=re.DOTALL)
    code = re.sub(r"//[^\n]*", "", code)
    remembered_runner = re.compile(
        r"val\s+coroutineScope\s*=\s*rememberCoroutineScope\(\)\s*"
        r"val\s+imeTailPixelProbeRunner\s*=\s*"
        r"remember\(threadInstanceKey\s*,\s*coroutineScope\)\s*\{\s*"
        r"ThreadImeTailPixelProbeRunner\(coroutineScope\)\s*\}",
        flags=re.DOTALL,
    )
    if remembered_runner.search(code) is None:
        raise Violation(
            "task #183 regression: IME-tail PixelCopy runner must be remembered "
            "from rememberCoroutineScope and threadInstanceKey"
        )

    effect_start = code.find(
        "LaunchedEffect(\n        threadInstanceKey,\n        composerKeyboardInsetDp,"
    )
    effect_end = code.find("\n    ThreadReactionAtlasProvider", effect_start)
    if effect_start < 0 or effect_end < 0:
        raise Violation(
            "task #183 anchor missing: composer inset LaunchedEffect must remain auditable"
        )
    effect = code[effect_start:effect_end]
    stable_call = re.compile(r"\bimeTailPixelProbeRunner\s*\.\s*launch\s*\(")
    if len(stable_call.findall(effect)) != 1:
        raise Violation(
            "task #183 regression: composer inset effect must schedule exactly one probe "
            "through the remembered imeTailPixelProbeRunner"
        )
    forbidden = (
        "ThreadImeTailPixelProbeRunner(",
        "currentCoroutineContext(",
        "CoroutineScope(coroutineContext",
        "this.launch",
    )
    for token in forbidden:
        if token in effect:
            raise Violation(
                f"task #183 regression: restartable composer effect contains {token}; "
                "probe work must remain owned by the remembered composition scope"
            )


def check_composer_retirement_uses_bounded_waits(connected_test: str) -> None:
    """Incident: task #76 (post-send focus/IME never reached its deadline).

    ``waitForIdleSync`` has no timeout. A live focus/IME/recomposition stream can
    therefore block the decisive retirement case before its bounded replacement
    wait runs. Other tests in the same class intentionally use idle waits, so pin
    only this product-contract method.
    """
    match = re.search(
        r"fun sendRetiresEditorSessionWithoutLosingFocusImeOrFirstComposition\(\) \{"
        r"(?P<body>.*?)\n\s*\}\n\n\s*@Test",
        connected_test,
        flags=re.DOTALL,
    )
    if match is None:
        raise Violation(
            "task #76 anchor missing: the composer session-retirement connected "
            "test must remain independently auditable"
        )
    body = match.group("body")
    required = (
        'waitUntil(RenderTimeoutMs, "initial editor")',
        'waitUntil(SendTimeoutMs, "same editor cleared after send")',
        'waitUntil(ImeTransitionTimeoutMs, "sent row and same-text first composition")',
        "SLOCK_COMPOSER_SESSION_RETIREMENT_PASS",
    )
    for token in required:
        if token not in body:
            raise Violation(
                f"task #76 bounded-wait anchor missing from retirement test: {token}"
            )
    if "waitForIdleSync(" in body:
        raise Violation(
            "task #76 regression: the decisive retirement test contains unbounded "
            "waitForIdleSync; use its existing timeout-bounded state polling"
        )


def check_composer_native_send_snapshot_wiring(
    basic_widget: str,
    composer_page: str,
) -> None:
    """Incident: task #127 (Send snapshot was wired to CoreTextField's outer Box).

    Under Kuikly the Send snapshot read the native editor, and was wired to the wrong view. On
    CMP the editing state lives in Compose, so the snapshot reader must be bound to the
    TextField's *live* editor state: the TextFieldState the editor edits (task #69), which holds
    an IME edit before the caller is told about it or recomposes, so a reader bound in an earlier
    composition still sees the latest text/composition even when IME commit and Send land in one
    frame, and is unbound when the field leaves composition. The bounded opaque-token timeout in the composer still applies; pure
    snapshot/gate tests alone cannot prove either production callsite is connected.
    """
    text_field = ""
    search_from = 0
    while True:
        text_field_start = basic_widget.find("internal fun TextField(", search_from)
        if text_field_start < 0:
            break
        text_field_end = basic_widget.find("\n}\n", text_field_start)
        candidate = basic_widget[text_field_start : text_field_end if text_field_end >= 0 else None]
        if "nativeEditingStateReader: RaftTextFieldNativeEditingStateReader?" in candidate:
            text_field = candidate
            break
        search_from = text_field_start + 1
    if not text_field:
        raise Violation("task #127 anchor missing: shared TextField region is not auditable")
    basic_required = (
        "val state = rememberRaftTextFieldState(value,",
        "state = state,",
        "nativeEditingStateReader.bind(nativeEditingStateBindingOwner)",
        "state = state.toRaftTextFieldValue().toRaftTextInputState()",
        "nativeEditingStateReader?.unbind(nativeEditingStateBindingOwner)",
    )
    for token in basic_required:
        if token not in text_field:
            raise Violation(
                f"task #127 regression: Send snapshot reader lost live-value wiring {token!r}"
            )
    if re.search(r"state\s*=\s*(value|currentValueForReader)\.toRaftTextInputState\(\)", text_field):
        raise Violation(
            "task #127 regression: Send snapshot reader captures the composition-time value; "
            "read the editor's TextFieldState instead"
        )

    composer_required = (
        "internal class ThreadComposerSendSnapshotGate",
        "if (pendingToken !== token) return false",
        "val pendingSendSnapshotToken = sendSnapshotGate.pendingToken",
        "delay(ThreadComposerNativeSnapshotTimeoutMillis)",
        'currentOnEvent("sendSnapshotUnavailable", "timeout", null, null)',
        "val requestToken = sendSnapshotGate.begin()",
        "if (!sendSnapshotGate.finish(requestToken)) return@readCurrentValue",
        'onEvent("sendSnapshotUnavailable", "native_editor_unavailable", null, null)',
    )
    for token in composer_required:
        if token not in composer_page:
            raise Violation(
                f"task #127 regression: native Send snapshot lost bounded fail-safe {token!r}"
            )


def check_native_apple_authentication_services(
    host: str,
    shared: str,
    auth_data_source: str,
    entitlements: str,
    xcode_project: str,
) -> None:
    """Incident: task #82 (Apple was incorrectly presented as browser OAuth).

    Apple must enter AuthenticationServices' native credential sheet, with the
    PKCE S256 challenge bound as the Apple nonce. Google/GitHub intentionally
    remain on ASWebAuthenticationSession. The entitlement and Xcode capability
    are part of the same structural contract; source alone cannot provision the
    Apple Developer App ID, but dropping either local declaration is always a
    regression.
    """
    native_host_tokens = {
        "ASAuthorizationAppleIDProvider().createRequest()": (
            "create a native Apple ID authorization request"
        ),
        "request.requestedScopes = [.email]": "request the verified email scope",
        "request.nonce = pkce.challenge": "bind Apple identity tokens to the PKCE challenge",
        "ASAuthorizationController(authorizationRequests: [request])": (
            "present through ASAuthorizationController"
        ),
        'result.presentation == "native_apple"': "require the shared native presentation contract",
        "result.authorizationUrl == nil": "reject browser URLs from the native Apple start contract",
        "authorization.credential as? ASAuthorizationAppleIDCredential": (
            "admit only Apple ID credentials"
        ),
        "RaftIosMobileOAuthBridge.shared.completeNativeApple(": (
            "complete native Apple credentials through the typed shared bridge"
        ),
    }
    for token, reason in native_host_tokens.items():
        if token not in host:
            raise Violation(f"task #82 regression: iOS host must {reason}")

    dispatch = re.search(
        r'if normalizedProvider == "apple" \{\s*'
        r'(?P<apple>RaftIosMobileOAuthBridge\.shared\.startNativeApple\(.*?)'
        r'\n\s*\} else \{\s*'
        r'(?P<browser>RaftIosMobileOAuthBridge\.shared\.startBrowser\(.*?)'
        r'\n\s*\}',
        host,
        flags=re.DOTALL,
    )
    if dispatch is None:
        raise Violation(
            "task #82 routing fence missing: Apple must call startNativeApple and "
            "the explicit non-Apple branch must call startBrowser"
        )
    if "startBrowser" in dispatch.group("apple") or "ASWebAuthenticationSession" in dispatch.group("apple"):
        raise Violation(
            "task #82 regression: the Apple dispatch branch must not enter the browser broker"
        )

    browser_allowlists = (
        ("iOS coordinator", shared, "IosBrowserOAuthProviders"),
        ("auth data source", auth_data_source, "BrowserMobileOAuthProviders"),
    )
    for label, source, variable in browser_allowlists:
        provider_match = re.search(
            rf"private val {variable}\s*=\s*setOf\((?P<providers>[^)]*)\)",
            source,
        )
        if provider_match is None:
            raise Violation(f"task #82 anchor missing: {label} browser-provider allowlist is absent")
        providers = set(re.findall(r'"([a-z]+)"', provider_match.group("providers")))
        if providers != {"google", "github"}:
            raise Violation(
                f"task #82 regression: the {label} browser broker must admit exactly "
                f"Google/GitHub, got {sorted(providers)}"
            )

    shared_tokens = {
        'presentation = "native_apple"': "return the native Apple presentation discriminator",
        "suspend fun startNativeApple(": "expose a native Apple start coordinator",
        "suspend fun completeNativeApple(": "expose native Apple credential completion",
        "normalizedProvider !in IosBrowserOAuthProviders": (
            "validate browser starts against the Google/GitHub-only allowlist"
        ),
        "pending.provider !in IosBrowserOAuthProviders || pending.requestToken != null": (
            "prevent custom-URL callbacks from consuming native Apple pending state"
        ),
    }
    for token, reason in shared_tokens.items():
        if token not in shared:
            raise Violation(f"task #82 regression: shared iOS coordinator must {reason}")

    try:
        plist = ET.fromstring(entitlements)
    except ET.ParseError as error:
        raise Violation(f"task #82 iOS entitlements plist is invalid: {error}") from error
    entitlement_dict = plist.find("dict")
    if entitlement_dict is None:
        raise Violation("task #82 iOS entitlements plist has no top-level dict")
    entitlement_children = list(entitlement_dict)
    apple_entitlement = None
    for index, element in enumerate(entitlement_children[:-1]):
        if element.tag == "key" and element.text == "com.apple.developer.applesignin":
            apple_entitlement = entitlement_children[index + 1]
            break
    if (
        apple_entitlement is None
        or apple_entitlement.tag != "array"
        or [value.text for value in apple_entitlement.findall("string")] != ["Default"]
    ):
        raise Violation(
            "task #82 regression: iOS entitlements must declare "
            "com.apple.developer.applesignin = [Default]"
        )

    if not re.search(
        r"com\.apple\.SignInWithApple\s*=\s*\{\s*enabled\s*=\s*1;\s*\};",
        xcode_project,
        flags=re.DOTALL,
    ):
        raise Violation(
            "task #82 regression: the iOS target must keep the Sign in with Apple "
            "SystemCapabilities entry enabled"
        )


# task #256: a null agent-status resolution means Unknown, but the label-facing
# resolver substitutes a placeholder built with Presence.Offline, whose colour maps
# to StatusNeutralGray. Feeding that to a dot makes an unknown agent look offline.
# The defect reappeared three times in one file with three different spellings
# (elvis to StatusNeutralGray, constructing a grey-equivalent state, and reading
# .color straight off the non-null resolver), so the contract in the resolver's
# KDoc is not enough on its own: a comment is guarded by the same thing it failed
# to guard, i.e. someone reading it.
#
# LIMITATION, stated on purpose: this is a NAME-based check. It catches the
# label-facing bindings as currently named. A new binding name for the same
# non-null resolution would slip past it; the durable fix is a return type that
# has no .color to read.
LABEL_FACING_STATUS_BINDINGS = (
    "statusDisplayState",
    "statusIndicatorState",
    "agentStatusIndicatorState",
    "agentStatusDisplayState",
)


def check_label_facing_resolver_has_no_production_callers(root: Path) -> None:
    """`resolveMembersAgentStatusIndicatorState` must not be called from production code.

    It never returns null: it substitutes unknownMembersAgentStatusIndicatorState(), whose
    presence is Offline and whose colour is therefore StatusNeutralGray -- pixel-identical
    to a real Offline dot for an agent whose status is merely not known yet (tasks #253,
    #256). Production dot *and* label sites both have safe alternatives, so a production
    caller is always a mistake; the resolver survives for tests that pin its behaviour.

    This replaces an earlier check that scanned MembersPage.kt for `RaftStatusIndicator(`
    argument lists. That check had two independent scope holes, and a real defect went
    through both: SettingsHomePage.kt:17360 painted a dot from this resolver via
    `RaftAvatarWithStatus(statusColor = ...)`. It was in a file the check never read, AND
    behind a composable the pattern never matched -- widening either one alone would still
    have missed it. Checking the resolver's callers instead is scope-free: it cannot be
    evaded by a new file, a new dot-painting composable, or a renamed local.
    """
    offenders = []
    scanned_production_files = 0
    definition_sites = []
    for path in sorted((root / "compose").rglob("*.kt")):
        # Directory components only, matched whole. Two earlier attempts were wrong in
        # opposite directions: enumerating source sets (commonTest, src/test) is a list
        # someone must remember to extend, and a substring test over path.parts matches
        # FILE names too -- "AppShellTabRouteState.kt" contains "rouTESTate" and
        # "SharedAuthGateStore.kt" contains "gaTESTore", so both production files were
        # skipped while the check still reported green (LiBai). The list version failed
        # loudly (a missed exclusion is a false positive); the substring version failed
        # silently, which is strictly worse. `endswith("Test")` covers every Kotlin test
        # source set by construction -- they are all named `<target>Test` -- so this is a
        # rule rather than a list; `== "test"` covers buildSrc/src/test.
        if any(part == "test" or part.endswith("Test") for part in path.parent.parts):
            continue
        text = path.read_text(encoding="utf-8").replace("\r\n", "\n")
        scanned_production_files += 1
        for line_number, line in enumerate(text.split("\n"), start=1):
            # `(` or `::` distinguishes a use from a mention: prose names this resolver
            # (MembersPage.kt's KDoc, and the comment at the fixed call site) and must not
            # be flagged. `::` catches a callable reference, which carries the value just
            # as well as a call does.
            if not re.search(
                r"(?<!OrNull)\bresolveMembersAgentStatusIndicatorState\s*\("
                r"|::\s*resolveMembersAgentStatusIndicatorState\b(?!OrNull)",
                line,
            ):
                continue
            if re.match(r"\s*internal fun resolveMembersAgentStatusIndicatorState\(", line):
                definition_sites.append(f"{path.relative_to(root)}:{line_number}")
                continue
            offenders.append(f"{path.relative_to(root)}:{line_number}")
    # Denominator first (LiBai, 2026-09-04): a "must be zero" check is vacuously green on an
    # empty scan, and a broken glob or an exclusion rule that swallowed commonMain prints
    # exactly what a clean tree prints. So before judging the offenders, prove the scan
    # reached the production code at all: it must have read files, and it must have passed
    # the resolver's own definition exactly once. Not pinned to a file count -- that rots.
    if scanned_production_files == 0:
        raise Violation(
            "check_label_facing_resolver_has_no_production_callers scanned zero production "
            "Kotlin files under compose/ -- the glob or the test-dir exclusion is wrong, so "
            "'no offenders' below would be meaningless."
        )
    if len(definition_sites) != 1:
        raise Violation(
            "check_label_facing_resolver_has_no_production_callers expected to pass exactly one "
            "definition of `internal fun resolveMembersAgentStatusIndicatorState(` in production "
            f"code and found {len(definition_sites)} ({', '.join(definition_sites) or 'none'}). "
            "If the resolver moved or was retired, update or retire this check; if it is still "
            "in commonMain, the scan is not reaching it and every result it reports is blind."
        )
    if offenders:
        raise Violation(
            "production code calls the label-facing resolveMembersAgentStatusIndicatorState "
            f"({', '.join(offenders)}). It is never null -- it falls back to a grey "
            "placeholder that impersonates Offline for a merely-unknown status (tasks #253, "
            "#256). For a dot, use resolveMembersAgentStatusIndicatorStateOrNull(...) and "
            "render inside `?.let` so an unresolved status paints nothing. For a label, "
            "build the unknown placeholder explicitly at the call site so the substitution "
            "is visible where it happens."
        )


def check_task_259_members_visual_fixtures(
    members_page: str, visual_fixture_data: str
) -> None:
    """task #259: assert MembersPage.kt literal machines and variants match VisualFixtureData.

    MembersPage.kt runs on production compose_member_detail in commonMain without
    a gated wrapper to inject ComputerSummary fixtures, so it carries literal machines.
    This constraint verifies:
    1. membersVisualFixtureMachines equals VisualFixtureData.Machines field for field.
    2. membersAgentDetailVisualVariants daemonVersion matches the referenced machine's
       daemonVersion (in-file self-consistency).
    3. Variant machineIds are restricted to {Machines.*} union {"computer-missing", None}.
    4. agent-daemon-only variant fields match VisualFixtureData.Agents.DaemonOnly verbatim.
    5. membersAgentDetailVisualSnapshot.summary daemonVersion matches the referenced machine's
       daemonVersion and its machineId is in allowed_machine_ids.
    """
    machines_block_match = re.search(
        r"object Machines \{(.*?)\n    \}", visual_fixture_data, re.DOTALL
    )
    if not machines_block_match:
        raise Violation("task #259: VisualFixtureData.Machines block not found")
    machines_block = machines_block_match.group(1)

    fixture_machines = {}
    obj_matches = re.finditer(
        r"object (\w+) \{(.*?)\n        \}", machines_block, re.DOTALL
    )
    for om in obj_matches:
        obj_body = om.group(2)
        m_id = re.search(r'const val ID: String = "([^"]+)"', obj_body).group(1)
        m_name = re.search(r'const val NAME: String = "([^"]+)"', obj_body).group(1)
        m_status = re.search(r'const val STATUS: String = "([^"]+)"', obj_body).group(1)
        m_is_comp = (
            re.search(r"const val IS_COMPUTER: Boolean = (true|false)", obj_body).group(1)
            == "true"
        )
        comp_ver_m = re.search(
            r'(?:const val|val) COMPUTER_VERSION: String\?? = (?:null|"([^"]+)")',
            obj_body,
        )
        m_comp_ver = comp_ver_m.group(1) if comp_ver_m else None
        daemon_ver_m = re.search(
            r'(?:const val|val) DAEMON_VERSION: String\?? = (?:null|"([^"]+)")',
            obj_body,
        )
        m_daemon_ver = daemon_ver_m.group(1) if daemon_ver_m else None

        fixture_machines[m_id] = {
            "name": m_name,
            "status": m_status,
            "is_computer": m_is_comp,
            "computer_version": m_comp_ver,
            "daemon_version": m_daemon_ver,
        }

    page_machines_block_m = re.search(
        r"private val membersVisualFixtureMachines: List<ComputerSummary> =.*?listOf\((.*?)\n    \)",
        members_page,
        re.DOTALL,
    )
    if not page_machines_block_m:
        raise Violation(
            "task #259: membersVisualFixtureMachines list not found in MembersPage.kt"
        )
    page_machines_block = page_machines_block_m.group(1)

    machine_call_regex = re.compile(
        r'membersVisualFixtureMachine\(\s*'
        r'"(?P<id>[^"]+)",\s*'
        r'"(?P<name>[^"]+)",\s*'
        r'ComputerStatus\.(?P<status>Online|Offline),\s*'
        r'isComputer\s*=\s*(?P<isComputer>true|false),\s*'
        r'computerVersion\s*=\s*(?P<computerVersion>"[^"]*"|null),\s*'
        r'daemonVersion\s*=\s*(?P<daemonVersion>"[^"]*"|null)'
        r'\s*\)'
    )

    page_machines = {}
    for m in machine_call_regex.finditer(page_machines_block):
        m_id = m.group("id")
        cv = m.group("computerVersion")
        dv = m.group("daemonVersion")
        page_machines[m_id] = {
            "name": m.group("name"),
            "status": m.group("status").lower(),
            "is_computer": m.group("isComputer") == "true",
            "computer_version": cv.strip('"') if cv != "null" else None,
            "daemon_version": dv.strip('"') if dv != "null" else None,
        }

    if len(page_machines) != len(fixture_machines):
        raise Violation(
            f"task #259: membersVisualFixtureMachines count ({len(page_machines)}) "
            f"!= VisualFixtureData.Machines count ({len(fixture_machines)})"
        )

    for m_id, f_data in fixture_machines.items():
        if m_id not in page_machines:
            raise Violation(
                f"task #259: machine {m_id} missing from membersVisualFixtureMachines"
            )
        p_data = page_machines[m_id]
        for field in ("name", "status", "is_computer", "computer_version", "daemon_version"):
            if p_data[field] != f_data[field]:
                raise Violation(
                    f"task #259: machine {m_id} field {field} mismatch: "
                    f"page has {p_data[field]!r}, fixture has {f_data[field]!r}"
                )

    variants_block_m = re.search(
        r"private val membersAgentDetailVisualVariants = listOf\((.*?)\n\)",
        members_page,
        re.DOTALL,
    )
    if not variants_block_m:
        raise Violation(
            "task #259: membersAgentDetailVisualVariants list not found in MembersPage.kt"
        )
    variants_block = variants_block_m.group(1)

    variant_call_regex = re.compile(
        r'MembersAgentDetailVisualVariant\(\s*'
        r'id\s*=\s*"(?P<id>[^"]+)",\s*'
        r'name\s*=\s*"(?P<name>[^"]+)",\s*'
        r'displayName\s*=\s*"(?P<displayName>[^"]+)",\s*'
        r'avatarLabel\s*=\s*"(?P<avatarLabel>[^"]+)",\s*'
        r'description\s*=\s*"(?P<description>[^"]+)",\s*'
        r'machineId\s*=\s*(?P<machineId>"[^"]*"|null),\s*'
        r'machineName\s*=\s*(?P<machineName>"[^"]*"|null),\s*'
        r'daemonVersion\s*=\s*(?P<daemonVersion>"[^"]*"|null),\s*'
        r'serverRole\s*=\s*"(?P<serverRole>[^"]+)"\s*'
        r'\)',
        re.DOTALL,
    )

    page_variants = {}
    allowed_machine_ids = set(page_machines.keys()) | {"computer-missing", None}

    for vm in variant_call_regex.finditer(variants_block):
        v_id = vm.group("id")
        mid = vm.group("machineId")
        m_id = mid.strip('"') if mid != "null" else None
        mname = vm.group("machineName")
        m_name = mname.strip('"') if mname != "null" else None
        dver = vm.group("daemonVersion")
        d_ver = dver.strip('"') if dver != "null" else None

        page_variants[v_id] = {
            "name": vm.group("name"),
            "displayName": vm.group("displayName"),
            "avatarLabel": vm.group("avatarLabel"),
            "description": vm.group("description"),
            "machineId": m_id,
            "machineName": m_name,
            "daemonVersion": d_ver,
            "serverRole": vm.group("serverRole"),
        }

        if m_id not in allowed_machine_ids:
            raise Violation(
                f"task #259: variant {v_id} references unallowed machineId {m_id!r} "
                "(must be in fixture machines or 'computer-missing' / null)"
            )

        if m_id in page_machines:
            expected_daemon_ver = page_machines[m_id]["daemon_version"]
            if d_ver != expected_daemon_ver:
                raise Violation(
                    f"task #259: variant {v_id} daemonVersion ({d_ver!r}) mismatches "
                    f"referenced machine {m_id} daemonVersion ({expected_daemon_ver!r})"
                )

    if "agent-daemon-only" not in page_variants:
        raise Violation(
            "task #259: variant agent-daemon-only missing from membersAgentDetailVisualVariants"
        )

    agents_block_m = re.search(
        r"object Agents \{(.*?)\n    \}", visual_fixture_data, re.DOTALL
    )
    if not agents_block_m:
        raise Violation("task #259: VisualFixtureData.Agents block not found")
    agents_block = agents_block_m.group(1)

    agent_do_block_m = re.search(
        r"object DaemonOnly \{(.*?)\n        \}", agents_block, re.DOTALL
    )
    if not agent_do_block_m:
        raise Violation("task #259: VisualFixtureData.Agents.DaemonOnly block not found")
    agent_do_block = agent_do_block_m.group(1)

    f_agent_name = re.search(r'const val NAME: String = "([^"]+)"', agent_do_block).group(1)
    f_agent_dname = re.search(
        r'const val DISPLAY_NAME: String = "([^"]+)"', agent_do_block
    ).group(1)
    f_agent_alabel = re.search(
        r'const val AVATAR_LABEL: String = "([^"]+)"', agent_do_block
    ).group(1)
    f_agent_desc = re.search(
        r'const val DESCRIPTION: String = "([^"]+)"', agent_do_block
    ).group(1)
    f_agent_role = re.search(
        r'const val SERVER_ROLE: String = "([^"]+)"', agent_do_block
    ).group(1)

    p_v = page_variants["agent-daemon-only"]
    if p_v["name"] != f_agent_name:
        raise Violation(
            f"task #259: agent-daemon-only name mismatch: {p_v['name']!r} != {f_agent_name!r}"
        )
    if p_v["displayName"] != f_agent_dname:
        raise Violation(
            f"task #259: agent-daemon-only displayName mismatch: {p_v['displayName']!r} != {f_agent_dname!r}"
        )
    if p_v["avatarLabel"] != f_agent_alabel:
        raise Violation(
            f"task #259: agent-daemon-only avatarLabel mismatch: {p_v['avatarLabel']!r} != {f_agent_alabel!r}"
        )
    if p_v["description"] != f_agent_desc:
        raise Violation(
            f"task #259: agent-daemon-only description mismatch: {p_v['description']!r} != {f_agent_desc!r}"
        )
    if p_v["serverRole"] != f_agent_role:
        raise Violation(
            f"task #259: agent-daemon-only serverRole mismatch: {p_v['serverRole']!r} != {f_agent_role!r}"
        )

    snapshot_summary_m = re.search(
        r"private val membersAgentDetailVisualSnapshot = AgentDetailSnapshot\(.*?"
        r"summary = AgentDetailSummary\((.*?)\n    \),",
        members_page,
        re.DOTALL,
    )
    if not snapshot_summary_m:
        raise Violation(
            "task #259: membersAgentDetailVisualSnapshot summary not found in MembersPage.kt"
        )
    snapshot_body = snapshot_summary_m.group(1)
    sm_id_m = re.search(r'machineId\s*=\s*(?P<machineId>"[^"]*"|null)', snapshot_body)
    sd_ver_m = re.search(r'daemonVersion\s*=\s*(?P<daemonVersion>"[^"]*"|null)', snapshot_body)
    if not sm_id_m or not sd_ver_m:
        raise Violation(
            "task #259: machineId or daemonVersion missing from membersAgentDetailVisualSnapshot.summary"
        )
    s_mid = sm_id_m.group("machineId")
    snapshot_machine_id = s_mid.strip('"') if s_mid != "null" else None
    s_dver = sd_ver_m.group("daemonVersion")
    snapshot_daemon_ver = s_dver.strip('"') if s_dver != "null" else None

    if snapshot_machine_id not in allowed_machine_ids:
        raise Violation(
            f"task #259: snapshot summary references unallowed machineId {snapshot_machine_id!r} "
            "(must be in fixture machines or 'computer-missing' / null)"
        )

    if snapshot_machine_id in page_machines:
        expected_daemon_ver = page_machines[snapshot_machine_id]["daemon_version"]
        if snapshot_daemon_ver != expected_daemon_ver:
            raise Violation(
                f"task #259: snapshot summary daemonVersion ({snapshot_daemon_ver!r}) mismatches "
                f"referenced machine {snapshot_machine_id} daemonVersion ({expected_daemon_ver!r})"
            )


SHARED_COMMON_TEST_ROOT = "compose/shared/src/commonTest/kotlin"
TEST_SESSION_STORE_CLASS_FIELD = re.compile(
    r"^ {4}(?:private |internal |protected )?(?:val|var|lateinit var) \w+(?:\s*:\s*[\w.<>?]+)?\s*=\s*InMemoryRaftAuthSessionStore\(",
    re.MULTILINE,
)


def check_test_session_stores_are_test_local(sources: dict[str, str]) -> None:
    """Task #1122: the test identity authority is memoised per session-store INSTANCE.

    Every data source built on one `InMemoryRaftAuthSessionStore` shares one
    generation sequence; that is the property the card wanted. It stays a
    property only while each test function creates its own store. A store held
    as a class field would let generations leak silently between test
    functions (and stay green), which is the per-instance defect shape moved up
    one level. Store instances must therefore be function-local in commonTest.
    """
    # Denominator: a prohibition over an empty (or store-free) input is vacuously true. The scan
    # must see commonTest files AND at least one function-local store, or the glob has drifted.
    if not sources:
        raise Violation("task #1122 checker saw no commonTest sources (glob drift?)")
    if not any("InMemoryRaftAuthSessionStore(" in text for text in sources.values()):
        raise Violation(
            "task #1122 checker saw no InMemoryRaftAuthSessionStore construction at all in commonTest "
            "(the constraint no longer observes what it constrains)"
        )
    for name, text in sorted(sources.items()):
        match = TEST_SESSION_STORE_CLASS_FIELD.search(text)
        if match:
            line = text.count("\n", 0, match.start()) + 1
            raise Violation(
                f"task #1122 regression: {name}:{line} holds an InMemoryRaftAuthSessionStore as a "
                "class field; create it inside the test function so the memoised identity "
                "authority stays test-local"
            )


def run(root: Path) -> None:
    def read(rel: str) -> str:
        path = root / rel
        if not path.is_file():
            raise Violation(f"constraint source missing (fail-closed): {rel}")
        return path.read_text(encoding="utf-8").replace("\r\n", "\n")

    thread_page = read(THREAD_PAGE)
    gesture_paths = {path for path, _, _, _ in GESTURE_OWNER_REGIONS}
    check_retained_pointer_gesture_owners(
        {path: thread_page if path == THREAD_PAGE else read(path) for path in gesture_paths}
    )
    check_gesture_owner_generation_fence(read(GESTURE_OWNER))
    check_forward_row_identity(thread_page)
    check_tasks_single_mutation_authority(
        shared_thread_store=read(SHARED_THREAD_STORE),
        thread_api=read(THREAD_API),
        bridge_auth=read(BRIDGE_AUTH),
        shared_tasks_data_store=read(SHARED_TASKS_DATA_STORE),
        shared_tasks_store=read(SHARED_TASKS_STORE),
        tasks_data_source=read(TASKS_DATA_SOURCE),
        slock_shared_store_provider=read(SLOCK_SHARED_STORE_PROVIDER),
    )
    check_label_facing_resolver_has_no_production_callers(root)
    check_system_selectable_text_dialog(thread_page)
    check_thread_ime_tail_pixel_probe_scope(thread_page)
    check_composer_retirement_uses_bounded_waits(read(COMPOSER_IME_CONNECTED_TEST))
    check_composer_native_send_snapshot_wiring(
        basic_widget=read(BASIC_WIDGET),
        composer_page=read(THREAD_COMPOSER_PAGE),
    )
    check_no_layout_direction_flip(
        {
            THREAD_PAGE: thread_page,
            SLOCK_MARKDOWN: read(SLOCK_MARKDOWN),
            VENDOR_PARAGRAPH: read(VENDOR_PARAGRAPH),
        }
    )
    check_no_conditional_interaction_state(_conditional_state_sources(root))
    check_notification_action_handlers(read(BRIDGE_AUTH), read(ANDROID_ACTIVITY))
    check_auth_authority_changed_i18n(
        {locale: read(path) for locale, path in AUTH_STRINGS.items()}
    )
    check_native_apple_authentication_services(
        host=read(IOS_MOBILE_OAUTH_HOST),
        shared=read(IOS_MOBILE_OAUTH_SHARED),
        auth_data_source=read(AUTH_DATA_SOURCE),
        entitlements=read(IOS_ENTITLEMENTS),
        xcode_project=read(IOS_XCODE_PROJECT),
    )
    check_task_259_members_visual_fixtures(
        members_page=read(MEMBERS_PAGE),
        visual_fixture_data=read(VISUAL_FIXTURE_DATA),
    )
    common_test_root = root / SHARED_COMMON_TEST_ROOT
    if not common_test_root.is_dir():
        raise Violation(f"constraint source missing (fail-closed): {SHARED_COMMON_TEST_ROOT}")
    check_test_session_stores_are_test_local(
        {
            str(path.relative_to(root)): path.read_text(encoding="utf-8").replace("\r\n", "\n")
            for path in sorted(common_test_root.rglob("*.kt"))
        }
    )


def self_test() -> None:
    """Every checker must reject its violation shape and accept the good shape."""
    # check_label_facing_resolver_has_no_production_callers scans a tree, so probe it on
    # miniature trees. The two denominator probes are the point (LiBai, 2026-09-04): an
    # empty scan and a scan that never reaches the definition must both be red, not the
    # same green a clean tree produces.
    definition = "internal fun resolveMembersAgentStatusIndicatorState(x: Int) = x\n"
    caller = "val s = resolveMembersAgentStatusIndicatorState(1)\n"
    def _tree(files: dict[str, str]):
        tmp = tempfile.TemporaryDirectory()
        root = Path(tmp.name)
        for rel, body in files.items():
            target = root / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(body, encoding="utf-8")
        return tmp, root
    good_files = {
        "compose/shared/src/commonMain/kotlin/a/Members.kt": definition,
        "compose/shared/src/commonTest/kotlin/a/MembersTest.kt": caller,
    }
    tmp, root = _tree(good_files)
    with tmp:
        check_label_facing_resolver_has_no_production_callers(root)
    for label, files, needle in (
        (
            "production caller",
            {**good_files, "compose/shared/src/commonMain/kotlin/a/Settings.kt": caller},
            "production code calls",
        ),
        ("empty scan", {}, "scanned zero production"),
        (
            "definition only under a test dir (scan reaches production but never the resolver)",
            {
                "compose/shared/src/commonMain/kotlin/a/Other.kt": "val unrelated = 1\n",
                "compose/shared/src/commonTest/kotlin/a/Members.kt": definition + caller,
            },
            "found 0",
        ),
        (
            "definition twice",
            {**good_files, "compose/shared/src/commonMain/kotlin/a/Copy.kt": definition},
            "found 2",
        ),
    ):
        tmp, root = _tree(files)
        with tmp:
            try:
                check_label_facing_resolver_has_no_production_callers(root)
            except Violation as violation:
                if needle not in str(violation):
                    raise AssertionError(f"{label}: wrong violation: {violation}")
            else:
                raise AssertionError(f"{label}: expected a Violation, got none")
    good_snapshot_basic_widget = """
@Composable
internal fun TextField(value: String = "", onValueChange: (String) -> Unit) {
    TextField(value = TextFieldValue(value), onValueChange = {})
}

@Composable
internal fun TextField(
    value: TextFieldValue,
    nativeEditingStateReader: RaftTextFieldNativeEditingStateReader? = null,
) {
    DisposableEffect(nativeEditingStateReader, nativeEditingStateBindingOwner) {
        onDispose { nativeEditingStateReader?.unbind(nativeEditingStateBindingOwner) }
    }
    val state = rememberRaftTextFieldState(value, { next -> currentOnValueChange(next) }, traceName = "TextField")
    if (nativeEditingStateReader != null) {
        SideEffect {
            nativeEditingStateReader.bind(nativeEditingStateBindingOwner) { callback ->
                callback(RaftTextFieldNativeEditorRead(state = state.toRaftTextFieldValue().toRaftTextInputState()))
            }
        }
    }
    RaftAppStateTextField(
        state = state,
    )
}
"""
    good_snapshot_composer_page = """
internal class ThreadComposerSendSnapshotGate {
    fun finish(token: Any): Boolean {
        if (pendingToken !== token) return false
        return true
    }
}
val pendingSendSnapshotToken = sendSnapshotGate.pendingToken
delay(ThreadComposerNativeSnapshotTimeoutMillis)
currentOnEvent("sendSnapshotUnavailable", "timeout", null, null)
val requestToken = sendSnapshotGate.begin()
if (!sendSnapshotGate.finish(requestToken)) return@readCurrentValue
onEvent("sendSnapshotUnavailable", "native_editor_unavailable", null, null)
"""
    check_composer_native_send_snapshot_wiring(
        good_snapshot_basic_widget,
        good_snapshot_composer_page,
    )
    for source_name, token in (
        ("basic", "val state = rememberRaftTextFieldState(value,"),
        ("basic", "state = state,"),
        ("basic", "state = state.toRaftTextFieldValue().toRaftTextInputState()"),
        ("basic", "nativeEditingStateReader.bind(nativeEditingStateBindingOwner)"),
        ("basic", "nativeEditingStateReader?.unbind(nativeEditingStateBindingOwner)"),
        ("composer", "if (pendingToken !== token) return false"),
        ("composer", "delay(ThreadComposerNativeSnapshotTimeoutMillis)"),
        ("composer", 'currentOnEvent("sendSnapshotUnavailable", "timeout", null, null)'),
        ("composer", "if (!sendSnapshotGate.finish(requestToken)) return@readCurrentValue"),
    ):
        bad_basic = good_snapshot_basic_widget
        bad_composer = good_snapshot_composer_page
        if source_name == "basic":
            bad_basic = bad_basic.replace(token, "removed", 1)
        else:
            bad_composer = bad_composer.replace(token, "removed", 1)
        try:
            check_composer_native_send_snapshot_wiring(bad_basic, bad_composer)
        except Violation:
            pass
        else:
            raise SystemExit(
                f"self-test: task #127 native snapshot checker accepted missing {token!r}"
            )

    try:
        check_composer_native_send_snapshot_wiring(
            good_snapshot_basic_widget.replace(
                "state = state.toRaftTextFieldValue().toRaftTextInputState()",
                "state = value.toRaftTextInputState()",
            ),
            good_snapshot_composer_page,
        )
    except Violation:
        pass
    else:
        raise SystemExit("self-test: task #127 checker accepted a composition-time value capture")

    good_gesture_sources: dict[str, str] = {}
    for path, start_token, end_token, surface in GESTURE_OWNER_REGIONS:
        good_gesture_sources[path] = (
            good_gesture_sources.get(path, "")
            + f"""
{start_token}
    val owner = rememberRaftGestureOwner(
        surface = "{surface}",
        semanticIdentity = "id",
        callbacks = Callbacks()
    )
    Modifier.raftGesturePointerInput(owner) {{ currentOwner ->
        val gesture = currentOwner.beginGesture()
        gesture.currentCallbacksOrNull()
    }}
{end_token}
"""
        )
    check_retained_pointer_gesture_owners(good_gesture_sources)
    for path, start_token, end_token, surface in GESTURE_OWNER_REGIONS:
        source = good_gesture_sources[path]
        start = source.index(start_token)
        end = source.index(end_token, start + len(start_token))
        pointer = source.index(".raftGesturePointerInput(", start, end)
        mutated = source[:pointer] + ".pointerInput(" + source[pointer + len(".raftGesturePointerInput("):]
        bad_sources = dict(good_gesture_sources)
        bad_sources[path] = mutated
        try:
            check_retained_pointer_gesture_owners(bad_sources)
        except Violation:
            pass
        else:
            raise SystemExit(
                f"self-test: task #991 gesture-owner checker accepted raw pointerInput in {surface}"
            )
    first_path = GESTURE_OWNER_REGIONS[0][0]
    for token, replacement in (
        (".beginGesture()", ".gestureStartRemoved()"),
        (".currentCallbacksOrNull()", ".currentCallbacksRemoved()"),
    ):
        bad_sources = dict(good_gesture_sources)
        bad_sources[first_path] = bad_sources[first_path].replace(token, replacement, 1)
        try:
            check_retained_pointer_gesture_owners(bad_sources)
        except Violation:
            pass
        else:
            raise SystemExit(
                f"self-test: task #991 gesture-owner checker accepted missing {token}"
            )
    direct_callbacks = dict(good_gesture_sources)
    direct_callbacks[first_path] = direct_callbacks[first_path].replace(
        "gesture.currentCallbacksOrNull()",
        "gesture.currentCallbacksOrNull()\n        gesture.callbacks",
        1,
    )
    try:
        check_retained_pointer_gesture_owners(direct_callbacks)
    except Violation:
        pass
    else:
        raise SystemExit(
            "self-test: task #991 gesture-owner checker accepted direct callback bypass"
        )

    good_gesture_owner = """
internal val pointerInputOwnerToken: Any = Any()
if (active) currentCallbacks = callbacks
if (active) currentGestureToken = generationToken
active && currentGestureToken === generationToken
active = false
"""
    check_gesture_owner_generation_fence(good_gesture_owner)
    for token in (
        "internal val pointerInputOwnerToken: Any = Any()",
        "if (active) currentCallbacks = callbacks",
        "if (active) currentGestureToken = generationToken",
        "active && currentGestureToken === generationToken",
        "active = false",
    ):
        try:
            check_gesture_owner_generation_fence(good_gesture_owner.replace(token, "removed", 1))
        except Violation:
            pass
        else:
            raise SystemExit(
                f"self-test: task #991 gesture-owner fence accepted missing {token!r}"
            )

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

    good_tasks_data_store = """
private val currentServerIdProvider: (suspend () -> String?)? = null
private var ownerGeneration: Long = 0L
suspend fun beginStatusMutation() = Unit
suspend fun completeStatusMutation() = Unit
suspend fun rollbackStatusMutation() = Unit
"""
    good_tasks_store = """
sharedStore.beginStatusMutation()
sharedStore.completeStatusMutation()
sharedStore.rollbackStatusMutation()
"""
    good_tasks_data_source = '''
request(path = "tasks/${taskId.value}/status", method = "PATCH")
'''
    good_shared_store_provider = """
store.upsertTaskForCurrentOwner()
store.removeTaskForCurrentOwner()
log("reason=owner_admission_missing")
"""
    good_task_authority_sources = (
        "thread renders only",
        "thread api reads only",
        "thread network reads only",
        good_tasks_data_store,
        good_tasks_store,
        good_tasks_data_source,
        good_shared_store_provider,
    )
    check_tasks_single_mutation_authority(*good_task_authority_sources)
    for source_index in range(3):
        bad = list(good_task_authority_sources)
        bad[source_index] += "\nsuspend fun updateTaskStatus(taskId: String) = Unit"
        try:
            check_tasks_single_mutation_authority(*bad)
        except Violation:
            pass
        else:
            raise SystemExit("self-test: task #17 checker accepted a private Thread mutation chain")
    for source_index, token in (
        (3, "suspend fun beginStatusMutation("),
        (3, "suspend fun completeStatusMutation("),
        (3, "suspend fun rollbackStatusMutation("),
        (3, "currentServerIdProvider"),
        (3, "ownerGeneration"),
        (4, "sharedStore.beginStatusMutation("),
        (4, "sharedStore.completeStatusMutation("),
        (4, "sharedStore.rollbackStatusMutation("),
        (5, '"tasks/${taskId.value}/status"'),
        (5, 'method = "PATCH"'),
        (6, "store.upsertTaskForCurrentOwner("),
        (6, "store.removeTaskForCurrentOwner("),
        (6, "reason=owner_admission_missing"),
    ):
        bad = list(good_task_authority_sources)
        bad[source_index] = bad[source_index].replace(token, "removed", 1)
        try:
            check_tasks_single_mutation_authority(*bad)
        except Violation:
            pass
        else:
            raise SystemExit(f"self-test: task #17 checker accepted missing {token!r}")

    good_pixel_probe_scope = """
val coroutineScope = rememberCoroutineScope()
val imeTailPixelProbeRunner =
    remember(threadInstanceKey, coroutineScope) {
        ThreadImeTailPixelProbeRunner(coroutineScope)
    }
LaunchedEffect(
        threadInstanceKey,
        composerKeyboardInsetDp,
) {
    imeTailPixelProbeRunner.launch(awaitSettlement = {}, runProbe = {})
}
    ThreadReactionAtlasProvider
"""
    check_thread_ime_tail_pixel_probe_scope(good_pixel_probe_scope)
    bad_pixel_probe_scopes = (
        good_pixel_probe_scope.replace(
            "imeTailPixelProbeRunner.launch(",
            "ThreadImeTailPixelProbeRunner(this).launch(",
        ),
        good_pixel_probe_scope.replace(
            "ThreadImeTailPixelProbeRunner(coroutineScope)",
            "ThreadImeTailPixelProbeRunner(this)",
        ),
        good_pixel_probe_scope.replace(
            "imeTailPixelProbeRunner.launch(",
            "this.launch(",
        ),
        good_pixel_probe_scope.replace(
            "imeTailPixelProbeRunner.launch(",
            "// imeTailPixelProbeRunner.launch(\nthis.launch(",
        ),
    )
    for bad in bad_pixel_probe_scopes:
        try:
            check_thread_ime_tail_pixel_probe_scope(bad)
        except Violation:
            pass
        else:
            raise SystemExit("self-test: task #183 probe-scope checker accepted a violation")

    good_dialog = """
key(message.id, message.body) { Unit }
private fun ThreadMessageTextSelectionDialog(message: Message, onDismiss: () -> Unit) {
    val originalText = message.body
    Modal(onWillDismiss = { onDismiss() }) {
        LazyColumn(
            modifier = Modifier
        ) {
            item {
                RaftNativeSelectableText(
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
    # task #189 relaxed the region-END anchor to accept `internal fun
    # ThreadMessageMenuChrome`, because the visual host moved into a gated source set
    # and has to call it. Prove that is only anchor compatibility, not a weakened
    # guard: the region must be found under BOTH modifiers, and every content
    # assertion must still bite under BOTH.
    good_dialog_internal_neighbour = good_dialog.replace(
        "private fun ThreadMessageMenuChrome",
        "internal fun ThreadMessageMenuChrome",
    )
    if good_dialog_internal_neighbour == good_dialog:
        raise SystemExit("self-test: task #924 anchor fixture did not vary the neighbour modifier")
    check_system_selectable_text_dialog(good_dialog_internal_neighbour)
    for variant_name, variant in (
        ("private neighbour", good_dialog),
        ("internal neighbour", good_dialog_internal_neighbour),
    ):
        for token in (
            "RaftNativeSelectableText(",
            "LazyColumn(",
            "val originalText = message.body",
            "text = originalText",
        ):
            broken = variant.replace(token, "removed", 1)
            if broken == variant:
                raise SystemExit(
                    f"self-test: task #924 fixture missing {token!r} under {variant_name}"
                )
            try:
                check_system_selectable_text_dialog(broken)
            except Violation:
                pass
            else:
                raise SystemExit(
                    f"self-test: task #924 checker accepted a broken {token!r} "
                    f"under {variant_name}"
                )
    for bad in (
        good_dialog.replace("RaftNativeSelectableText(", "SelectionContainer {\n Text("),
        good_dialog.replace("RaftNativeSelectableText(", "SelectableText("),
        good_dialog.replace("item {", "SelectionContainer { }\n            item {", 1),
        good_dialog.replace('.testTag("thread_message_selection_text")', ""),
        good_dialog.replace(
            "item {",
            "RaftBrutalActionButton(); item {",
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

    good_retirement_test = '''
fun sendRetiresEditorSessionWithoutLosingFocusImeOrFirstComposition() {
    waitUntil(RenderTimeoutMs, "initial editor") { true }
    waitUntil(SendTimeoutMs, "same editor cleared after send") { true }
    waitUntil(ImeTransitionTimeoutMs, "sent row and same-text first composition") { true }
    println("SLOCK_COMPOSER_SESSION_RETIREMENT_PASS")
}

@Test
fun anotherCase() = Unit
'''
    check_composer_retirement_uses_bounded_waits(good_retirement_test)
    try:
        check_composer_retirement_uses_bounded_waits(
            good_retirement_test.replace(
                'waitUntil(SendTimeoutMs, "same editor cleared after send") { true }',
                'waitForIdleSync()\n    waitUntil(SendTimeoutMs, "same editor cleared after send") { true }',
            )
        )
    except Violation:
        pass
    else:
        raise SystemExit("self-test: task #76 bounded-wait checker accepted a violation")

    check_no_layout_direction_flip({"f": "Arrangement.spacedBy(12.dp, Alignment.End)"})
    try:
        check_no_layout_direction_flip(
            {"f": "CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl)"}
        )
    except Violation:
        pass
    else:
        raise SystemExit("self-test: layout-direction checker accepted a violation")

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

    good_native_apple_host = '''
let request = ASAuthorizationAppleIDProvider().createRequest()
request.requestedScopes = [.email]
request.nonce = pkce.challenge
let controller = ASAuthorizationController(authorizationRequests: [request])
guard result.presentation == "native_apple", result.authorizationUrl == nil else { return }
guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential else { return }
RaftIosMobileOAuthBridge.shared.completeNativeApple(
    generation: generation,
    authorizationCode: authorizationCode,
    identityToken: identityToken
)
if normalizedProvider == "apple" {
    RaftIosMobileOAuthBridge.shared.startNativeApple(
        mode: mode
    )
} else {
    RaftIosMobileOAuthBridge.shared.startBrowser(
        provider: normalizedProvider
    )
}
'''
    good_native_apple_shared = '''
private val IosBrowserOAuthProviders = setOf("google", "github")
if (normalizedProvider !in IosBrowserOAuthProviders) return
if (pending.provider !in IosBrowserOAuthProviders || pending.requestToken != null) return
presentation = "native_apple"
suspend fun startNativeApple() = Unit
suspend fun completeNativeApple() = Unit
'''
    good_native_apple_auth_data_source = '''
private val BrowserMobileOAuthProviders = setOf("google", "github")
'''
    good_native_apple_entitlements = '''<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0"><dict>
<key>com.apple.developer.applesignin</key><array><string>Default</string></array>
</dict></plist>
'''
    good_native_apple_project = '''
com.apple.SignInWithApple = {
    enabled = 1;
};
'''
    check_native_apple_authentication_services(
        good_native_apple_host,
        good_native_apple_shared,
        good_native_apple_auth_data_source,
        good_native_apple_entitlements,
        good_native_apple_project,
    )
    bad_native_apple_shapes = (
        (
            good_native_apple_host.replace(
                "RaftIosMobileOAuthBridge.shared.startNativeApple(",
                "RaftIosMobileOAuthBridge.shared.startBrowser(",
                1,
            ),
            good_native_apple_shared,
            good_native_apple_auth_data_source,
            good_native_apple_entitlements,
            good_native_apple_project,
        ),
        (
            good_native_apple_host.replace("request.nonce = pkce.challenge", ""),
            good_native_apple_shared,
            good_native_apple_auth_data_source,
            good_native_apple_entitlements,
            good_native_apple_project,
        ),
        (
            good_native_apple_host,
            good_native_apple_shared.replace(
                'setOf("google", "github")',
                'setOf("google", "github", "apple")',
            ),
            good_native_apple_auth_data_source,
            good_native_apple_entitlements,
            good_native_apple_project,
        ),
        (
            good_native_apple_host,
            good_native_apple_shared,
            good_native_apple_auth_data_source.replace(
                'setOf("google", "github")',
                'setOf("google", "github", "apple")',
            ),
            good_native_apple_entitlements,
            good_native_apple_project,
        ),
        (
            good_native_apple_host,
            good_native_apple_shared,
            good_native_apple_auth_data_source,
            good_native_apple_entitlements.replace("Default", ""),
            good_native_apple_project,
        ),
        (
            good_native_apple_host,
            good_native_apple_shared,
            good_native_apple_auth_data_source,
            good_native_apple_entitlements,
            good_native_apple_project.replace("enabled = 1", "enabled = 0"),
        ),
    )
    for bad_host, bad_shared, bad_auth_data_source, bad_entitlements, bad_project in bad_native_apple_shapes:
        try:
            check_native_apple_authentication_services(
                bad_host,
                bad_shared,
                bad_auth_data_source,
                bad_entitlements,
                bad_project,
            )
        except Violation:
            pass
        else:
            raise SystemExit("self-test: task #82 native Apple checker accepted a violation")

    good_members_page = """
private val membersVisualFixtureMachines: List<ComputerSummary> =
    listOf(
        membersVisualFixtureMachine("computer-mbp", "Jiachengs-MacBook-Pro", ComputerStatus.Online, isComputer = true, computerVersion = "0.0.48", daemonVersion = "0.65.0"),
        membersVisualFixtureMachine("computer-studio", "Studio Test Rig", ComputerStatus.Offline, isComputer = true, computerVersion = "0.0.50", daemonVersion = "0.65.0"),
        membersVisualFixtureMachine("computer-long-name", "ci-visual-capture-runner-us-west-2b_node17_macbookpro_m4max_buildhost_2026", ComputerStatus.Online, isComputer = true, computerVersion = "0.0.48", daemonVersion = "0.65.0"),
        membersVisualFixtureMachine("computer-daemon-only", "build-box-bare-daemon", ComputerStatus.Online, isComputer = false, computerVersion = null, daemonVersion = "0.65.0")
    )
private val membersAgentDetailVisualVariants = listOf(
    MembersAgentDetailVisualVariant(
        id = "agent-computer-offline",
        name = "Computer-Offline-Probe",
        displayName = "Computer Offline Probe",
        avatarLabel = "C",
        description = "offline probe",
        machineId = "computer-studio",
        machineName = "Studio Test Rig",
        daemonVersion = "0.65.0",
        serverRole = "member"
    ),
    MembersAgentDetailVisualVariant(
        id = "agent-computer-missing",
        name = "Computer-Missing-Probe",
        displayName = "Computer Missing Probe",
        avatarLabel = "C",
        description = "missing probe",
        machineId = "computer-missing",
        machineName = null,
        daemonVersion = null,
        serverRole = "member"
    ),
    MembersAgentDetailVisualVariant(
        id = "agent-no-computer",
        name = "No-Computer-Probe",
        displayName = "No Computer Probe",
        avatarLabel = "N",
        description = "no computer probe",
        machineId = null,
        machineName = null,
        daemonVersion = null,
        serverRole = "member"
    ),
    MembersAgentDetailVisualVariant(
        id = "agent-long-machine",
        name = "Long-Machine-Name-Probe",
        displayName = "Long Machine Name Probe",
        avatarLabel = "L",
        description = "long machine probe",
        machineId = "computer-long-name",
        machineName = "ci-visual-capture-runner-us-west-2b_node17_macbookpro_m4max_buildhost_2026",
        daemonVersion = "0.65.0",
        serverRole = "admin"
    ),
    MembersAgentDetailVisualVariant(
        id = "agent-daemon-only",
        name = "Daemon-Only-Probe",
        displayName = "Daemon Only Probe",
        avatarLabel = "DO",
        description = "Bound to a bare daemon (not a managed Computer): web shows Connected · daemon vX, never a computer version.",
        machineId = "computer-daemon-only",
        machineName = "build-box-bare-daemon",
        daemonVersion = "0.65.0",
        serverRole = "member"
    )
)
private val membersAgentDetailVisualSnapshot = AgentDetailSnapshot(
    agentId = membersAgentDetailVisualMember.id,
    summary = AgentDetailSummary(
        id = membersAgentDetailVisualMember.id.value,
        name = "Product-UX-Designer",
        displayName = membersAgentDetailVisualMember.displayName,
        createdAt = "2026-06-18T00:00:00.000Z",
        status = "working",
        runtime = "codex",
        model = "gpt-5-codex",
        reasoning = "medium",
        executionMode = "byoc",
        machineId = "computer-mbp",
        machineName = "Jiachengs-MacBook-Pro",
        daemonVersion = "0.65.0",
        creatorId = "artin",
        creatorType = "user",
        creator = AgentCreatorSummary(
            type = "human",
            id = "artin",
            name = "artin",
            displayName = "artin",
            avatarUrl = "pixel:user"
        ),
    ),
    details = membersAgentDetailVisualMember,
    computers = membersVisualFixtureMachines,
)
"""
    good_fixture_data = """
    object Machines {
        object Primary {
            const val ID: String = "computer-mbp"
            const val NAME: String = "Jiachengs-MacBook-Pro"
            const val STATUS: String = "online"
            const val IS_COMPUTER: Boolean = true
            const val COMPUTER_VERSION: String = "0.0.48"
            const val DAEMON_VERSION: String = "0.65.0"
        }
        object Studio {
            const val ID: String = "computer-studio"
            const val NAME: String = "Studio Test Rig"
            const val STATUS: String = "offline"
            const val IS_COMPUTER: Boolean = true
            const val COMPUTER_VERSION: String = "0.0.50"
            const val DAEMON_VERSION: String = "0.65.0"
        }
        object LongName {
            const val ID: String = "computer-long-name"
            const val NAME: String = "ci-visual-capture-runner-us-west-2b_node17_macbookpro_m4max_buildhost_2026"
            const val STATUS: String = "online"
            const val IS_COMPUTER: Boolean = true
            const val COMPUTER_VERSION: String = "0.0.48"
            const val DAEMON_VERSION: String = "0.65.0"
        }
        object DaemonOnly {
            const val ID: String = "computer-daemon-only"
            const val NAME: String = "build-box-bare-daemon"
            const val STATUS: String = "online"
            const val IS_COMPUTER: Boolean = false
            val COMPUTER_VERSION: String? = null
            const val DAEMON_VERSION: String = "0.65.0"
        }
    }
    object Agents {
        object DaemonOnly {
            const val ID: String = "agent-daemon-only"
            const val NAME: String = "Daemon-Only-Probe"
            const val DISPLAY_NAME: String = "Daemon Only Probe"
            const val AVATAR_LABEL: String = "DO"
            const val DESCRIPTION: String = "Bound to a bare daemon (not a managed Computer): web shows Connected · daemon vX, never a computer version."
            const val SERVER_ROLE: String = "member"
        }
    }
"""
    check_task_259_members_visual_fixtures(good_members_page, good_fixture_data)

    bad_task_259_cases = (
        (
            good_members_page.replace('"0.65.0"', '"0.0.48"', 1),
            good_fixture_data,
            "machine field mismatch",
        ),
        (
            good_members_page.replace(
                'daemonVersion = "0.65.0",\n        serverRole = "admin"',
                'daemonVersion = "0.0.48",\n        serverRole = "admin"',
            ),
            good_fixture_data,
            "variant daemonVersion mismatches referenced machine (historic :1495 defect)",
        ),
        (
            good_members_page.replace('machineId = "computer-missing"', 'machineId = "computer-unlisted"'),
            good_fixture_data,
            "unallowed machineId outside whitelist",
        ),
        (
            good_members_page.replace('avatarLabel = "DO"', 'avatarLabel = "D"'),
            good_fixture_data,
            "agent-daemon-only avatarLabel mismatch",
        ),
        (
            good_members_page.replace('id = "agent-daemon-only"', 'id = "agent-daemon-missing"'),
            good_fixture_data,
            "agent-daemon-only variant missing",
        ),
        (
            good_members_page.replace(
                'daemonVersion = "0.65.0",\n        creatorId = "artin"',
                'daemonVersion = "0.0.48",\n        creatorId = "artin"',
            ),
            good_fixture_data,
            "snapshot summary daemonVersion mismatches referenced machine (historic :1561 defect)",
        ),
        (
            good_members_page.replace(
                'machineId = "computer-mbp",\n        machineName = "Jiachengs-MacBook-Pro",\n        daemonVersion = "0.65.0"',
                'machineId = "computer-unlisted",\n        machineName = "Unlisted",\n        daemonVersion = "0.65.0"',
            ),
            good_fixture_data,
            "snapshot summary unallowed machineId outside whitelist",
        ),
    )
    for bad_page, bad_fix, desc in bad_task_259_cases:
        try:
            check_task_259_members_visual_fixtures(bad_page, bad_fix)
        except Violation:
            pass
        else:
            raise SystemExit(f"self-test: task #259 checker accepted violation: {desc}")

    check_test_session_stores_are_test_local(
        {
            "f": "class T {\n    @Test\n    fun a() = runBlocking {\n        val store = InMemoryRaftAuthSessionStore(session())\n    }\n}\n",
            "g": "class U {\n    private fun make() = InMemoryRaftAuthSessionStore(session())\n}\n",
        }
    )
    for empty_input in ({}, {"f": "class T {\n    @Test\n    fun a() = Unit\n}\n"}):
        try:
            check_test_session_stores_are_test_local(empty_input)
        except Violation:
            pass
        else:
            raise SystemExit("self-test: task #1122 checker passed vacuously on input without any session store")
    for offending in (
        "class T {\n    private val store = InMemoryRaftAuthSessionStore(session())\n}\n",
        "class T {\n    val authStore: InMemoryRaftAuthSessionStore = InMemoryRaftAuthSessionStore(session())\n}\n",
        "class T {\n    lateinit var store = InMemoryRaftAuthSessionStore(session())\n}\n",
    ):
        try:
            check_test_session_stores_are_test_local({"f": offending})
        except Violation:
            pass
        else:
            raise SystemExit("self-test: task #1122 test-local session-store checker accepted a class field")

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
    print(
        "source constraints hold "
        "(tasks #17/#800/#803/#361/#161/#924/#82/#183/#76/#127/#991/#259/#1122 structural faces)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
