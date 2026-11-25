#!/usr/bin/env python3
"""Incident-born static source constraint (explicit CI gate, NOT a unit test).

Per the test-discipline announcement (#all 2026-07-25, artin-directed): static
constraints live here as an explicit gate with a self-test, instead of being
dressed up as source-grep "contract tests" inside test directories.

Seven constraints remain: check_no_conditional_interaction_state, born from the
1.15.0 release crash on launch, check_native_apple_authentication_services
(task #82: Swift-side Sign in with Apple, which no Android or JVM test covers),
check_no_production_run_blocking (#3823: a main-thread runBlocking on the
auth-session lock deadlocked the app; a hang no unit test can catch reliably),
check_native_safe_test_names (#3845: a comma in a backtick test name broke
the iOS test compile on main, which PR CI does not build),
check_no_strong_invocation_object_return (1.17.1: an NSInvocation object return
read into a strong variable crashed iOS 27.2 at launch; simulators don't crash),
check_no_top_level_or_object_snapshot_state (Hands 25b91174 / #264: a
Compose snapshot state initialized in `<clinit>` off the main thread crashed
Android cold start with `SnapshotKt.readError`), and check_date_time_di_invariants
(dm:@artin:#2 Phase 2A: prevents UI files from bypassing RaftTimeContext DI via
TimeZone.currentSystemDefault/toLocalDateTime, reading LocalRaftPageBaseTimeContextProvider
directly, slicing raw UTC ISO dates, or introducing new `.staticContext()` call sites).
The other "forbid a future pattern" guards were deleted as redundant; behavior
is proven by behavior tests.

Exit codes: 0 = the constraints hold; 1 = violation (fail-closed, including
missing source roots); 2 = usage error.
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]


class Violation(Exception):
    pass


IOS_MOBILE_OAUTH_HOST = "compose/iosApp/iosApp/RaftIosMobileOAuthHost.swift"
IOS_MOBILE_OAUTH_SHARED = (
    "compose/shared-data/src/commonMain/kotlin/build/raft/app/data/IosMobileOAuth.kt"
)
AUTH_DATA_SOURCE = "compose/shared-data/src/commonMain/kotlin/build/raft/app/data/AuthDataSource.kt"
IOS_ENTITLEMENTS = "compose/iosApp/iosApp/iosApp.entitlements"
IOS_XCODE_PROJECT = "compose/iosApp/iosApp.xcodeproj/project.pbxproj"


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


IOS_OBJC_ROOT = "compose/iosApp"
INVOCATION_RETURN = re.compile(r"getReturnValue:\s*&\s*(\w+)")
# Pointers to plain C data are not reference counted.
C_DATA_POINTER = re.compile(r"\b(void|char|u?int\w*|float|double|CGFloat|NSInteger|NSUInteger|BOOL)\s*\*")


def check_no_strong_invocation_object_return(sources: dict[str, str]) -> None:
    """Incident: 1.17.1 (11701001) crash on launch on iOS 27.2 real devices.

    `RaftIosReservedRegionsForKind` read `-[NSInvocation getReturnValue:]` into a `__strong
    NSArray *`. getReturnValue: copies the pointer without retaining it, so ARC released an
    object it never retained and the autorelease pool crashed releasing it again
    (`objc_release` in `objc_autoreleasePoolPop`, none of our frames on the stack). Read an
    object return into an `__unsafe_unretained` variable first, then assign it.
    """
    for name, text in sources.items():
        for match in INVOCATION_RETURN.finditer(text):
            var = match.group(1)
            declarations = list(
                re.finditer(rf"^[ \t]*([^;\n]*?)\b{re.escape(var)}\b\s*(=|;)", text[: match.start()], re.M)
            )
            if not declarations:
                continue
            declared = declarations[-1].group(1)
            is_object = "*" in declared or re.search(r"\bid\b", declared)
            if not is_object or "__unsafe_unretained" in declared or C_DATA_POINTER.search(declared):
                continue
            line = text.count("\n", 0, match.start()) + 1
            raise Violation(
                f"strong NSInvocation object return: {name}:{line} reads getReturnValue: into "
                f"`{declared.strip()} {var}`; read it into an `__unsafe_unretained` variable first"
            )


def _ios_objc_sources(root: Path) -> dict[str, str]:
    base = root / IOS_OBJC_ROOT
    if not base.is_dir():
        raise Violation(f"strong NSInvocation object return: missing source root {IOS_OBJC_ROOT}")
    sources = {
        str(path.relative_to(root)): path.read_text(encoding="utf-8")
        for pattern in ("*.m", "*.mm")
        for path in sorted(base.rglob(pattern))
    }
    if not any("getReturnValue:" in text for text in sources.values()):
        raise Violation("strong NSInvocation object return: scan never reached getReturnValue:")
    return sources


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


# #3823: `runBlocking { authStore.readSession() }` on the main thread parked it forever.
# kotlinx Mutex hands the lock to the next queued waiter and resumes it on that waiter's
# dispatcher; when the waiter is a Main coroutine, the blocked main thread can never run it,
# so the lock is never released. Any runBlocking on a thread that also runs coroutines is the
# same trap, so production source sets may not call it except at the reviewed sites below.
RUN_BLOCKING_SCAN_ROOT = "compose"
# Gradle build logic (buildSrc) runs at build time, not in the app.
RUN_BLOCKING_EXCLUDED_PREFIXES = ("compose/buildSrc/",)
RUN_BLOCKING_CALL = re.compile(r"\brunBlocking\b\s*(?:<[^>]*>\s*)?[({]")

# (file, text of the calling line, reason). Each entry must match exactly one call site, so a
# second runBlocking in an allowlisted file is still a violation, and a stale entry fails too.
RUN_BLOCKING_ALLOWLIST = (
    (
        "compose/androidApp/src/main/java/build/raft/app/RaftApplication.kt",
        "runBlocking {",
        "Application.onCreate before the main looper runs any message: no Main coroutine can be "
        "queued on the auth-session lock yet (see the comment at the call site)",
    ),
    (
        "compose/shared-data/src/jvmCommonMain/kotlin/build/raft/app/data/realtime/SessionRealtimeRepository.kt",
        "runBlocking {",
        "Socket.IO auth-refresh provider; runs only on the dedicated slock-realtime-auth-refresh thread",
    ),
    (
        "compose/androidApp/src/main/java/build/raft/app/shared/RaftReleaseNotesDocumentCache.kt",
        "runBlocking {",
        "release-notes download; runs on WebView's shouldInterceptRequest thread or Dispatchers.IO, "
        "never main, and takes no app lock",
    ),
    (
        "compose/shared-data/src/commonMain/kotlin/build/raft/app/data/RaftSqlDatabase.kt",
        "runBlocking {",
        "inside SQLDelight's thread-confined transaction callback, entered via runOffStoreDispatcher; "
        "the block is synchronous SQL",
    ),
    (
        "compose/shared-data/src/commonMain/kotlin/build/raft/app/resources/RaftStringDesc.kt",
        "runBlocking { getString(environment, stringRes) }",
        "Compose resources string lookup, as CMP's own stringResource does; cached catalog read, "
        "no app lock",
    ),
    (
        "compose/shared-data/src/commonMain/kotlin/build/raft/app/resources/RaftStringDesc.kt",
        "runBlocking { getString(environment, stringRes, *resolvedArgs.toTypedArray()) }",
        "Compose resources formatted string lookup, as CMP's own stringResource does; cached "
        "catalog read, no app lock",
    ),
    (
        "compose/shared-data/src/commonMain/kotlin/build/raft/app/resources/RaftStringDesc.kt",
        "runBlocking { Res.readBytes(",
        "bundled plural-forms file read once per language, the same synchronous resource read as the "
        "string lookups above; no app lock",
    ),
)


def _is_production_kotlin(rel: str) -> bool:
    # Only */src trees are scanned, so Gradle build/ outputs never reach here (and the package
    # path build/raft/... must not be mistaken for one).
    if not rel.endswith(".kt"):
        return False
    if any(rel.startswith(prefix) for prefix in RUN_BLOCKING_EXCLUDED_PREFIXES):
        return False
    parts = rel.split("/")
    if "src" not in parts:
        return False
    source_set = parts[parts.index("src") + 1] if parts.index("src") + 1 < len(parts) else ""
    return "test" not in source_set.lower()


def _kotlin_code_only(text: str) -> str:
    """[text] with comments and string/char literal contents blanked (newlines kept).

    A small Kotlin lexer, so `"*/*"`, `"https://x"` or a raw string holding `/*` is not
    mistaken for a comment. Handles "..." strings with escapes, raw triple-quoted strings,
    `${...}` templates (their code is kept and scanned), char literals, line comments and
    nested block comments (Kotlin allows nesting).
    """
    out = list(text)
    n = len(text)

    def blank(start: int, stop: int) -> None:
        for k in range(start, min(stop, n)):
            if out[k] != "\n":
                out[k] = " "

    # Each frame: [mode, open-brace depth]; mode is "code", "str" or "raw".
    stack: list[list] = [["code", 0]]
    i = 0
    while i < n:
        frame = stack[-1]
        mode = frame[0]
        if mode == "code":
            if text.startswith("//", i):
                stop = text.find("\n", i)
                stop = n if stop < 0 else stop
                blank(i, stop)
                i = stop
            elif text.startswith("/*", i):
                depth = 0
                j = i
                while j < n:
                    if text.startswith("/*", j):
                        depth += 1
                        j += 2
                    elif text.startswith("*/", j):
                        depth -= 1
                        j += 2
                        if depth == 0:
                            break
                    else:
                        j += 1
                blank(i, j)
                i = j
            elif text.startswith('"""', i):
                stack.append(["raw", 0])
                i += 3
            elif text[i] == '"':
                stack.append(["str", 0])
                i += 1
            elif text[i] == "'":
                j = i + 1
                if j < n and text[j] == "\\":
                    j += 2
                    while j < n and text[j] not in "'\n":
                        j += 1
                else:
                    j += 1
                if j < n and text[j] == "'":
                    blank(i + 1, j)
                    i = j + 1
                else:
                    i += 1  # not a char literal (stray quote); move on
            elif text[i] == "{":
                frame[1] += 1
                i += 1
            elif text[i] == "}":
                if frame[1] == 0 and len(stack) > 1:
                    stack.pop()  # end of a ${...} template
                else:
                    frame[1] -= 1
                i += 1
            else:
                i += 1
        elif mode == "str":
            if text[i] == "\\":
                blank(i, i + 2)
                i += 2
            elif text[i] == '"':
                stack.pop()
                i += 1
            elif text.startswith("${", i):
                stack.append(["code", 0])
                i += 2
            elif text[i] == "\n":
                stack.pop()  # unterminated; recover at the line end
                i += 1
            else:
                blank(i, i + 1)
                i += 1
        else:  # raw string
            if text.startswith('"""', i):
                j = i + 3
                while j < n and text[j] == '"':
                    j += 1
                stack.pop()
                i = j
            elif text.startswith("${", i):
                stack.append(["code", 0])
                i += 2
            else:
                blank(i, i + 1)
                i += 1
    return "".join(out)


def _run_blocking_call_sites(text: str) -> list[tuple[int, str]]:
    # Matched over the whole code-only text, not per line: `runBlocking` with its `{` / `(` on
    # the next line is still a call. Reported at the line holding `runBlocking`.
    sites: list[tuple[int, str]] = []
    raw_lines = text.split("\n")
    code = _kotlin_code_only(text)
    code_lines = code.split("\n")
    for match in RUN_BLOCKING_CALL.finditer(code):
        number = code.count("\n", 0, match.start()) + 1
        if code_lines[number - 1].lstrip().startswith("import "):
            continue
        sites.append((number, raw_lines[number - 1].strip()))
    return sites


def check_no_production_run_blocking(
    sources: dict[str, str],
    allowlist: tuple[tuple[str, str, str], ...] = RUN_BLOCKING_ALLOWLIST,
) -> None:
    """Incident #3823 (ANR, 1.15.4): runBlocking on the main thread waiting on the auth lock."""
    for path, _pattern, reason in allowlist:
        if not reason.strip():
            raise Violation(f"runBlocking allowlist entry for {path} has no reason")
    matched = [0] * len(allowlist)
    for name in sorted(sources):
        for line, code in _run_blocking_call_sites(sources[name]):
            hits = [
                index
                for index, (path, pattern, _reason) in enumerate(allowlist)
                if path == name and pattern in code
            ]
            if not hits:
                raise Violation(
                    f"runBlocking in production code: {name}:{line} `{code}`. Use a coroutine "
                    "scope (lifecycleScope / viewModelScope / a provided scope) or suspend through "
                    "to the caller; runBlocking on the main thread can deadlock on the "
                    "auth-session lock, see #3823. A read of the session from synchronous host "
                    "code can use KeyValueAuthSessionStore.readCommittedSessionSnapshot(). Only if "
                    "this provably never runs on a thread that runs coroutines, add it to "
                    "RUN_BLOCKING_ALLOWLIST in scripts/ci/check_source_constraints.py with the reason."
                )
            for index in hits:
                matched[index] += 1
    for (path, pattern, _reason), count in zip(allowlist, matched):
        if count != 1:
            raise Violation(
                f"runBlocking allowlist entry {path} `{pattern}` matches {count} call sites; "
                "each entry must match exactly one (remove a stale entry, or make it specific)"
            )


def _production_kotlin_sources(root: Path) -> dict[str, str]:
    base = root / RUN_BLOCKING_SCAN_ROOT
    if not base.is_dir():
        raise Violation(f"runBlocking scan: missing source root {RUN_BLOCKING_SCAN_ROOT}")
    sources: dict[str, str] = {}
    for module_src in sorted(base.glob("*/src")) + sorted(base.glob("*/*/src")):
        for path in sorted(module_src.rglob("*.kt")):
            rel = str(path.relative_to(root))
            if _is_production_kotlin(rel):
                sources[rel] = path.read_text(encoding="utf-8").replace("\r\n", "\n")
    if not any("/src/commonMain/" in name for name in sources) or not any(
        "/androidApp/src/main/" in name for name in sources
    ):
        raise Violation("runBlocking scan never reached commonMain and androidApp/src/main")
    return sources


NATIVE_TEST_SOURCE_SETS = ("commonTest", "iosTest", "nativeTest", "appleTest")
BACKTICK_FUNCTION_NAME = re.compile(r"\bfun\s+`([^`\n]+)`")
# Kotlin/Native rejects these in identifiers ("Name contains illegal characters"); the JVM
# accepts some of them (a comma), so a JVM-only PR CI run passes and main's iOS compile fails.
NATIVE_ILLEGAL_NAME_CHARACTERS = frozenset(".,;:/\\[]<>()\"")


def check_native_safe_test_names(sources: dict[str, str]) -> None:
    """#3845: a backtick test name the iOS compiler rejects breaks main, not the PR."""
    for name in sorted(sources):
        for match in BACKTICK_FUNCTION_NAME.finditer(sources[name]):
            bad = sorted(set(match.group(1)) & NATIVE_ILLEGAL_NAME_CHARACTERS)
            if bad:
                line = sources[name].count("\n", 0, match.start()) + 1
                raise Violation(
                    f"test name with characters Kotlin/Native rejects ({' '.join(bad)}): "
                    f"{name}:{line} `{match.group(1)}`. Shared tests also compile for iOS, "
                    "which PR CI does not build; reword the name without them."
                )


def _native_test_sources(root: Path) -> dict[str, str]:
    sources: dict[str, str] = {}
    for source_set in NATIVE_TEST_SOURCE_SETS:
        for base in sorted((root / "compose").glob(f"*/src/{source_set}")):
            for path in sorted(base.rglob("*.kt")):
                sources[str(path.relative_to(root))] = path.read_text(encoding="utf-8")
    if not any("/src/commonTest/" in name for name in sources):
        raise Violation("test-name scan never reached a commonTest source set")
    return sources


# task #256: a null agent-status resolution means Unknown, but the label-facing
# resolver substitutes a placeholder built with Presence.Offline, whose colour maps
# to PresenceOffline. Feeding that to a dot makes an unknown agent look offline.
# The defect reappeared three times in one file with three different spellings
# (elvis to the offline colour, constructing a grey-equivalent state, and reading
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


TOP_LEVEL_SNAPSHOT_STATE_ROOTS = (
    "compose/core/src/commonMain/kotlin",
    "compose/designsystem/src/commonMain/kotlin",
    "compose/shared-data/src/commonMain/kotlin",
    "compose/shared/src/commonMain/kotlin",
    "compose/ui-cmp/src/commonMain/kotlin",
)
SNAPSHOT_STATE_CALL = re.compile(
    r"\b(?:mutable(?:State|StateMap|StateList|StateSet|IntState|LongState|FloatState|DoubleState)Of|"
    r"derivedStateOf|SnapshotState(?:List|Map|Set))\b"
)
# Only strictly verified Main-thread-only UI token / theme objects may keep process-wide snapshot
# state (for non-@Composable style getters like RaftType.*). Every entry must pin one exact line
# and state why off-main-thread first initialization cannot occur.
ALLOWED_TOP_LEVEL_OR_OBJECT_SNAPSHOT_STATES: tuple[tuple[str, str, str], ...] = (
    (
        "compose/shared/src/commonMain/kotlin/build/raft/app/RaftDesignTokens.kt",
        "var scale by mutableStateOf(DesignSystemScale.Compact)",
        "Main-only UI typography scale read by non-@Composable RaftType getters; RaftAppTypeScale is a dedicated object touched only from composition",
    ),
    (
        "compose/shared/src/commonMain/kotlin/build/raft/app/RaftTextRendering.kt",
        "var localeList: LocaleList by mutableStateOf(raftUiTextLocaleList(lastAppLocale) { lastSystemTags })",
        "Main-only UI text-rendering locale read by non-@Composable TextStyle.withRaftTextRendering; RaftUiTextLocaleState is a dedicated object touched only from composition/lifecycle",
    ),
    (
        "compose/designsystem/src/commonMain/kotlin/build/raft/designsystem/components/RaftTextFieldState.kt",
        "var trace: RaftTextInputTrace? by mutableStateOf(null)",
        "Main-only design-system text input diagnostics hook touched only from BasicTextField composition and Settings diagnostics toggle",
    ),
    (
        "compose/designsystem/src/commonMain/kotlin/build/raft/designsystem/theme/RaftScale.kt",
        "var current: RaftPointerInput by mutableStateOf(raftPlatformInitialPointerInput())",
        "Main-only design-system pointer input tracker touched only from RaftTheme composition, pointer input, and iOS main-thread launch hook",
    ),
)
_DECL_START_RE = re.compile(
    r"^(?:@[A-Za-z0-9_.]+(?:\([^)]*\))?\s+)*"
    r"(?:(?:public|internal|private|protected|actual|expect|data|open|abstract|final|sealed|value|inline|noinline|crossinline|reified|annotation|enum|const|override|suspend|operator|infix|tailrec|external)\s+)*"
    r"(companion\s+object|(?!object\s*:)object|class|interface|fun)\b(?:\s+([A-Za-z0-9_]+))?"
)
_NEW_MEMBER_START_RE = re.compile(
    r"^(?:@[A-Za-z0-9_.]+(?:\([^)]*\))?\s+)*"
    r"(?:(?:public|internal|private|protected|actual|expect|data|open|abstract|final|sealed|value|inline|annotation|enum|const|override|suspend|operator|infix|tailrec|external|lateinit)\s+)*"
    r"(?:val|var|fun|class|interface|companion\s+object|(?!object\s*:)object|init|constructor)\b"
)
_REMEMBER_CALL_RE = re.compile(r"\bremember(?:Saveable)?\s*(?:\(|\{)")
_GETTER_RE = re.compile(r"\bget\s*\(\s*\)\s*(?:=|\{)")
_LAZY_PROP_RE = re.compile(r"\bby\s+lazy\b.*\{")
_INIT_BLOCK_RE = re.compile(r"^init\b.*\{")
_ANON_OBJECT_RE = re.compile(r"^object\s*:")


def _top_level_or_object_snapshot_state_matches(text: str) -> list[tuple[int, str, str]]:
    """Return `(line_no, scope_label, raw_line)` for snapshot-state factories initialized at top
    level or directly inside an `object` / `companion object` body (including `by lazy { ... }`
    and `object` `init { ... }`, but not inside a `class`, `fun`, custom `get()`, or `remember`).
    """
    code_lines = _kotlin_code_only(text).splitlines()
    raw_lines = text.splitlines()
    stack: list[tuple[str, str, int]] = []
    depth = 0
    pending_decl: tuple[str, str] | None = None
    expr_fun_depth: int | None = None
    getter_depth: int | None = None
    hits: list[tuple[int, str, str]] = []

    for idx, (code_line, raw_line) in enumerate(zip(code_lines, raw_lines), 1):
        stripped = code_line.strip()
        is_fun_decl_line = False
        is_getter_line = False
        same_line_object_scope: str | None = None

        if stripped and not stripped.startswith("import ") and not stripped.startswith("package "):
            if expr_fun_depth is not None and (
                depth < expr_fun_depth
                or (
                    depth == expr_fun_depth
                    and _NEW_MEMBER_START_RE.match(stripped)
                    and not _ANON_OBJECT_RE.match(stripped)
                )
            ):
                expr_fun_depth = None
            if getter_depth is not None and (
                depth < getter_depth
                or (
                    depth == getter_depth
                    and _NEW_MEMBER_START_RE.match(stripped)
                    and not _GETTER_RE.match(stripped)
                )
            ):
                getter_depth = None

            pre_in_top_level = depth == 0
            pre_in_object_body = (
                bool(stack)
                and stack[-1][0] in ("object", "companion object", "lazy", "init")
                and stack[-1][2] == depth
            )
            if pre_in_top_level:
                pre_scope_label = "top-level"
            elif pre_in_object_body:
                pre_scope_label = (
                    stack[-1][1]
                    if stack[-1][0] in ("lazy", "init")
                    else f"{stack[-1][0]} {stack[-1][1]}".strip()
                )
            else:
                pre_scope_label = ""

            decl = _DECL_START_RE.match(stripped)
            if decl:
                kind = re.sub(r"\s+", " ", decl.group(1))
                name = decl.group(2) or ""
                is_fun_decl_line = kind == "fun"
                if "{" in stripped:
                    if kind in ("object", "companion object"):
                        same_line_object_scope = f"{kind} {name}".strip()
                    stack.append((kind, name, depth + 1))
                    pending_decl = None
                elif kind == "fun" and "=" in stripped:
                    pending_decl = None
                    expr_fun_depth = depth
                elif kind in ("object", "companion object", "class", "interface", "fun"):
                    pending_decl = (kind, name)
            else:
                if pending_decl is not None:
                    if pending_decl[0] == "fun" and "=" in stripped:
                        is_fun_decl_line = True
                        if "{" in stripped:
                            stack.append(("fun", pending_decl[1], depth + 1))
                        else:
                            expr_fun_depth = depth
                        pending_decl = None
                    elif re.match(
                        r"^(?:(?:public|internal|private|protected|override|const|lateinit)\s+)*(?:val|var|init|constructor)\b",
                        stripped,
                    ):
                        pending_decl = None
                    elif "{" in stripped:
                        if pending_decl[0] in ("object", "companion object"):
                            same_line_object_scope = f"{pending_decl[0]} {pending_decl[1]}".strip()
                        stack.append((pending_decl[0], pending_decl[1], depth + 1))
                        pending_decl = None

                if _GETTER_RE.search(stripped):
                    is_getter_line = True
                    if "{" in stripped:
                        stack.append(("getter", "", depth + 1))
                    elif "=" in stripped:
                        getter_depth = depth
                elif (pre_in_top_level or pre_in_object_body) and _LAZY_PROP_RE.search(stripped):
                    owner = "top-level lazy" if pre_in_top_level else f"{pre_scope_label} lazy".strip()
                    same_line_object_scope = owner
                    stack.append(("lazy", owner, depth + 1))
                elif pre_in_object_body and _INIT_BLOCK_RE.match(stripped):
                    owner = f"{pre_scope_label} init".strip()
                    same_line_object_scope = owner
                    stack.append(("init", owner, depth + 1))

            if (
                not is_fun_decl_line
                and not is_getter_line
                and expr_fun_depth is None
                and getter_depth is None
                and SNAPSHOT_STATE_CALL.search(stripped)
                and not _REMEMBER_CALL_RE.search(stripped)
                and not _GETTER_RE.search(stripped)
            ):
                if same_line_object_scope is not None and not re.search(r"\bfun\b", stripped):
                    hits.append((idx, same_line_object_scope, raw_line.strip()))
                elif pre_in_top_level or pre_in_object_body:
                    hits.append((idx, pre_scope_label, raw_line.strip()))

        depth += code_line.count("{") - code_line.count("}")
        if depth < 0:
            depth = 0
        while stack and stack[-1][2] > depth:
            stack.pop()
        if expr_fun_depth is not None and depth < expr_fun_depth:
            expr_fun_depth = None
        if getter_depth is not None and depth < getter_depth:
            getter_depth = None

    return hits


def check_no_top_level_or_object_snapshot_state(
    sources: dict[str, str],
    allowlist: tuple[tuple[str, str, str], ...] = ALLOWED_TOP_LEVEL_OR_OBJECT_SNAPSHOT_STATES,
) -> None:
    """Incident: Hands 25b91174 / PR #5293 / #proj-mobile:#264 cold-start `SnapshotKt.readError`.

    A Compose snapshot state (`mutableStateOf` / `mutableStateMapOf` / `mutable*StateOf`) created at
    top level or inside an `object` / `companion object` is initialized lazily in `<clinit>`. If a
    background thread triggers `<clinit>` while the main thread Recomposer holds an open
    `MutableSnapshot`, reading that state on the main thread crashes with `SnapshotKt.readError`.
    Use `MutableStateFlow` + `@Composable collectAsState()` (or a non-snapshot `State` for
    constants) instead.
    """
    allowed_by_file: dict[str, list[tuple[str, str]]] = {}
    matched_entries: set[tuple[str, str]] = set()
    for path, snippet, reason in allowlist:
        if not reason.strip():
            raise Violation(f"top-level snapshot-state allowlist entry for {path} is missing a reason")
        allowed_by_file.setdefault(path, []).append((snippet, reason))

    for name in sorted(sources):
        hits = _top_level_or_object_snapshot_state_matches(sources[name])
        entries = allowed_by_file.get(name, [])
        for line_no, scope, raw_line in hits:
            matching = [snippet for snippet, _ in entries if snippet in raw_line]
            if len(matching) == 1:
                key = (name, matching[0])
                if key in matched_entries:
                    raise Violation(
                        f"top-level/object snapshot state allowlist matched more than once in "
                        f"{name}:{line_no} ({scope}): {raw_line}"
                    )
                matched_entries.add(key)
            else:
                raise Violation(
                    f"top-level/object Compose snapshot state: {name}:{line_no} ({scope}) "
                    f"initializes `{raw_line}`. Back process-wide state with MutableStateFlow + "
                    "`@Composable collectAsState()` (or a non-snapshot State for constants) to "
                    "prevent cold-start SnapshotKt.readError crashes (Hands 25b91174 / #264)."
                )

    for path, snippet, _ in allowlist:
        if (path, snippet) not in matched_entries:
            raise Violation(
                f"stale top-level/object snapshot-state allowlist entry: {path} ({snippet!r}) "
                "no longer matches any violation; remove it from ALLOWED_TOP_LEVEL_OR_OBJECT_SNAPSHOT_STATES"
            )


def _top_level_snapshot_state_sources(root: Path) -> dict[str, str]:
    sources: dict[str, str] = {}
    for rel in TOP_LEVEL_SNAPSHOT_STATE_ROOTS:
        base = root / rel
        if not base.is_dir():
            raise Violation(f"top-level/object snapshot state: missing source root {rel}")
        for path in sorted(base.rglob("*.kt")):
            sources[str(path.relative_to(root))] = path.read_text(encoding="utf-8")
    if not any("RaftDisplayPreferenceRuntime" in text for text in sources.values()):
        raise Violation("top-level/object snapshot state: scan never reached RaftDisplayPreferenceRuntime")
    return sources


DATE_TIME_DI_SCAN_ROOT = "compose/shared/src/commonMain/kotlin"
DATE_TIME_DI_UI_PREFIX = "compose/shared/src/commonMain/kotlin/build/raft/app/"
DATE_TIME_DI_NON_UI_PREFIXES = (
    "compose/shared/src/commonMain/kotlin/build/raft/app/data/",
)
DATE_TIME_DI_LIVE_CONTEXT_FILE = (
    "compose/shared/src/commonMain/kotlin/build/raft/app/RaftLiveTimeContext.kt"
)
SYSTEM_ZONE_OR_LOCAL_DATETIME_RE = re.compile(
    r"\bTimeZone\.currentSystemDefault\s*\(|\.\s*toLocalDateTime\s*\("
)
DIRECT_PAGE_BASE_PROVIDER_READ_RE = re.compile(
    r"\bLocalRaftPageBaseTimeContextProvider\s*\.\s*current\b"
)
RAW_ISO_DATE_SLICE_RE = re.compile(
    r"\.\s*(?:substringBefore\s*\(\s*(?:'T'|\"T\")\s*\)|"
    r"take\s*\(\s*10\s*\)\s*\??\.\s*split\s*\(\s*(?:'-'|\"-\")\s*\))"
)
STATIC_TIME_CONTEXT_CALL_RE = re.compile(r"\.\s*staticContext\s*\(")

# Exact remaining `.staticContext()` call counts pending Phase 2C retirement (dm:@artin:#2).
# Adding a new caller fails CI; removing callers in Phase 2C must shrink/remove entries here.
ALLOWED_STATIC_TIME_CONTEXT_SITES: tuple[tuple[str, int, str], ...] = (
    (
        "compose/shared/src/commonMain/kotlin/build/raft/app/assistant/AssistantPage.kt",
        1,
        "Phase 2C: AssistantPage fallback timeContext pending live provider migration",
    ),
    (
        "compose/shared/src/commonMain/kotlin/build/raft/app/settings/SettingsHomePage.kt",
        1,
        "Phase 2C: SettingsHomePage threadTimeContext fallback pending staticContext retirement",
    ),
    (
        "compose/shared/src/commonMain/kotlin/build/raft/app/thread/ThreadPage.kt",
        9,
        "Phase 2C: ThreadPage staticContext fallbacks pending Phase 2C retirement",
    ),
    (
        "compose/shared/src/commonMain/kotlin/build/raft/app/thread/ThreadRouteBannerSubtrees.kt",
        1,
        "Phase 2C: ThreadRouteBannerSubtrees staticContext pending Phase 2C retirement",
    ),
    (
        "compose/shared/src/commonMain/kotlin/build/raft/app/thread/ThreadRouteDialogs.kt",
        1,
        "Phase 2C: ThreadRouteDialogs staticContext fallback pending Phase 2C retirement",
    ),
    (
        "compose/shared/src/commonMain/kotlin/build/raft/app/thread/ThreadRouteEventHandler.kt",
        1,
        "Phase 2C: ThreadRouteEventHandler staticContext pending Phase 2C retirement",
    ),
    (
        "compose/shared/src/commonMain/kotlin/build/raft/app/thread/ThreadRouteEventHandlerActions.kt",
        1,
        "Phase 2C: ThreadRouteEventHandlerActions staticContext pending Phase 2C retirement",
    ),
    (
        "compose/shared/src/commonMain/kotlin/build/raft/app/thread/ThreadTabContent.kt",
        1,
        "Phase 2C: ThreadTabContent staticContext pending Phase 2C retirement",
    ),
)


def _is_date_time_di_ui_file(rel: str) -> bool:
    return rel.startswith(DATE_TIME_DI_UI_PREFIX) and not any(
        rel.startswith(prefix) for prefix in DATE_TIME_DI_NON_UI_PREFIXES
    )


def check_date_time_di_invariants(
    sources: dict[str, str],
    static_context_allowlist: tuple[tuple[str, int, str], ...] = ALLOWED_STATIC_TIME_CONTEXT_SITES,
) -> None:
    """Incident: dm:@artin:#2 Date/Time DI & formatting regressions (raw ISO leaks, UTC date
    slicing, ignored 12h/24h preference, and bypassed RaftTimeContext DI).

    Enforces four invariants across `compose/shared/src/commonMain`:
    1. UI files must not call `TimeZone.currentSystemDefault()` or `.toLocalDateTime(` directly
       (format through `RaftTimeContext` / `currentRaftEffectiveDateTimeContext()` instead).
    2. Only `RaftLiveTimeContext.kt` (`currentRaftTimeContextProvider`) may read
       `LocalRaftPageBaseTimeContextProvider.current`.
    3. UI files must not slice raw UTC ISO dates via `.substringBefore('T')` or `.take(10).split('-')`.
    4. `.staticContext()` calls are pinned to the exact remaining Phase 2C allowlist so no new
       dayless contexts can be introduced.
    """
    expected_static_counts: dict[str, int] = {}
    for path, count, reason in static_context_allowlist:
        if count <= 0:
            raise Violation(f"date/time DI staticContext allowlist entry for {path} must have count > 0")
        if not reason.strip():
            raise Violation(f"date/time DI staticContext allowlist entry for {path} is missing a reason")
        if path in expected_static_counts:
            raise Violation(f"duplicate date/time DI staticContext allowlist entry for {path}")
        expected_static_counts[path] = count

    observed_static_counts: dict[str, int] = {}
    live_base_reads_in_owner = 0

    for name in sorted(sources):
        text = sources[name]
        code = _kotlin_code_only(text)
        is_ui = _is_date_time_di_ui_file(name)

        if is_ui:
            for match in SYSTEM_ZONE_OR_LOCAL_DATETIME_RE.finditer(code):
                line = code.count("\n", 0, match.start()) + 1
                raise Violation(
                    f"date/time DI violation: {name}:{line} calls `{match.group(0).strip()}` in UI code; "
                    "format dates/times through RaftTimeContext (currentRaftDateTimeContext / "
                    "currentRaftEffectiveDateTimeContext) so local timezone and 12h/24h preferences apply."
                )

            for match in RAW_ISO_DATE_SLICE_RE.finditer(text):
                # _kotlin_code_only preserves 1:1 indices and blanks comments and string/char interiors.
                # Require the leading '.' and method name to remain live code (not inside a comment/string).
                if code[match.start()] != ".":
                    continue
                line = text.count("\n", 0, match.start()) + 1
                raise Violation(
                    f"date/time DI violation: {name}:{line} slices raw UTC ISO date with "
                    f"`{match.group(0).strip()}`; format local dates through RaftTimeContext instead."
                )

        for match in DIRECT_PAGE_BASE_PROVIDER_READ_RE.finditer(code):
            line = code.count("\n", 0, match.start()) + 1
            if name == DATE_TIME_DI_LIVE_CONTEXT_FILE:
                live_base_reads_in_owner += 1
            else:
                raise Violation(
                    f"date/time DI violation: {name}:{line} reads LocalRaftPageBaseTimeContextProvider.current "
                    "directly; use currentRaftTimeContextProvider / currentRaftDateTimeContext / "
                    "currentRaftTimeContext instead."
                )

        static_matches = list(STATIC_TIME_CONTEXT_CALL_RE.finditer(code))
        if static_matches:
            observed_static_counts[name] = len(static_matches)
            if name not in expected_static_counts:
                first_line = code.count("\n", 0, static_matches[0].start()) + 1
                raise Violation(
                    f"date/time DI violation: {name}:{first_line} introduces a new `.staticContext()` call; "
                    "use `timeContextProvider.dateContext` or `currentRaftDateTimeContext` instead."
                )
            if len(static_matches) > expected_static_counts[name]:
                last_line = code.count("\n", 0, static_matches[-1].start()) + 1
                raise Violation(
                    f"date/time DI violation: {name}:{last_line} has {len(static_matches)} `.staticContext()` "
                    f"calls (allowlist pins {expected_static_counts[name]} pending Phase 2C)."
                )

    if DATE_TIME_DI_LIVE_CONTEXT_FILE in sources and live_base_reads_in_owner != 1:
        raise Violation(
            f"date/time DI violation: expected exactly 1 LocalRaftPageBaseTimeContextProvider.current "
            f"read in {DATE_TIME_DI_LIVE_CONTEXT_FILE}, found {live_base_reads_in_owner}"
        )

    for path, expected in expected_static_counts.items():
        actual = observed_static_counts.get(path, 0)
        if actual != expected:
            raise Violation(
                f"stale date/time DI staticContext allowlist entry: {path} expected {expected} "
                f"call site(s), found {actual}; update ALLOWED_STATIC_TIME_CONTEXT_SITES"
            )


def _date_time_di_sources(root: Path) -> dict[str, str]:
    base = root / DATE_TIME_DI_SCAN_ROOT
    if not base.is_dir():
        raise Violation(f"date/time DI scan: missing source root {DATE_TIME_DI_SCAN_ROOT}")
    sources = {
        str(path.relative_to(root)): path.read_text(encoding="utf-8").replace("\r\n", "\n")
        for path in sorted(base.rglob("*.kt"))
    }
    if DATE_TIME_DI_LIVE_CONTEXT_FILE not in sources:
        raise Violation(f"date/time DI scan never reached {DATE_TIME_DI_LIVE_CONTEXT_FILE}")
    return sources


PREFIX_GATED_REFUSAL_RE = re.compile(r"\bchannelConversionServerWords\b")


def check_server_refusal_surface_invariants(sources: dict[str, str]) -> None:
    """Enforce #proj-mobile:#319 PR1 refusal-surface invariant on production Kotlin sources:
    Never reintroduce the prefix-gated `channelConversionServerWords` helper; route refusals
    through `resolveServerRefusal`.
    """
    for name, text in sorted(sources.items()):
        code = _kotlin_code_only(text)
        for match in PREFIX_GATED_REFUSAL_RE.finditer(code):
            line = code.count("\n", 0, match.start()) + 1
            raise Violation(
                f"server-refusal invariant violation (#proj-mobile:#319): {name}:{line} uses removed "
                f"prefix-gated helper `{match.group(0)}`; route through `resolveServerRefusal` instead."
            )


DESKTOP_WRY_GATEWAY_FILE = (
    "compose/shared/src/desktopMain/kotlin/build/raft/app/RaftDesktopWryGateway.kt"
)
DESKTOP_WRY_DIRECT_REF_RE = re.compile(
    r"\b(?:WryWebViewPanel|io\.github\.kdroidfilter\.webview\.wry)\b"
)
# WryWebViewPanel's command methods bounce back onto the AWT EDT via SwingUtilities.invokeLater
# before calling `_dispatch_sync_f` onto macOS Thread 0, and its query/eval methods block the
# caller directly. Even inside RaftDesktopWryGateway.kt, call only top-level UniFFI functions
# on the off-EDT worker thread, never these methods on `panel`.
DESKTOP_WRY_PANEL_BLOCKING_CALL_RE = re.compile(
    r"\bpanel\s*\??\.\s*(?:loadUrl|loadHtml|goBack|goForward|reload|stopLoading|"
    r"requestWebViewFocus|openDevTools|closeDevTools|clearCookiesForUrl|clearAllCookies|"
    r"setCookie|evaluateJavaScript|isLoading|getTitle|canGoBack|canGoForward|"
    r"drainIpcMessages|getCurrentUrl)\s*\("
)


def check_desktop_wry_off_edt_gateway(sources: dict[str, str]) -> None:
    """Incident: #wg-mobile-sync:bf308ade macOS Compose Desktop Chinese IME & live-resize freeze.

    macOS Thread 0 synchronously waits on the AWT EDT during IME (`firstRectForCharacterRange`)
    and window live-resize. Calling `WryWebViewPanel` or `io.github.kdroidfilter.webview.wry.*`
    from the AWT EDT triggers `_dispatch_sync_f` onto macOS Thread 0 -> instant AB-BA deadlock.
    All production Wry references must stay confined to `RaftDesktopWryGateway.kt`, which routes
    blocking native calls onto a dedicated off-EDT worker and strips `WryWebViewPanel`'s built-in
    EDT-blocking mouse focus listener.
    """
    if DESKTOP_WRY_GATEWAY_FILE not in sources:
        raise Violation(
            f"desktop Wry off-EDT gateway missing (fail-closed): {DESKTOP_WRY_GATEWAY_FILE}"
        )
    for name, text in sorted(sources.items()):
        code = _kotlin_code_only(text)
        if name == DESKTOP_WRY_GATEWAY_FILE:
            for match in DESKTOP_WRY_PANEL_BLOCKING_CALL_RE.finditer(code):
                line = code.count("\n", 0, match.start()) + 1
                raise Violation(
                    f"desktop Wry EDT-deadlock violation: {name}:{line} calls `{match.group(0).strip()}` "
                    "on WryWebViewPanel (which runs or bounces `_dispatch_sync_f` onto AWT EDT); "
                    "invoke the top-level UniFFI function on RaftDesktopWryWorker instead."
                )
            if "replaceBlockingFocusMouseListener" not in code:
                raise Violation(
                    f"desktop Wry EDT-deadlock violation: {name} must strip WryWebViewPanel's "
                    "EDT-blocking mouse focus listener via replaceBlockingFocusMouseListener"
                )
            continue
        match = DESKTOP_WRY_DIRECT_REF_RE.search(code)
        if match:
            line = code.count("\n", 0, match.start()) + 1
            raise Violation(
                f"desktop Wry off-EDT gateway violation: {name}:{line} references "
                f"`{match.group(0)}` outside {DESKTOP_WRY_GATEWAY_FILE}; route all Wry native "
                "calls through RaftDesktopWryGateway / RaftDesktopWryBridge."
            )


FRESHNESS_LIVE_CATCH_UP_DECL_FILE = (
    "compose/shared/src/commonMain/kotlin/build/raft/app/thread/RaftLiveCatchUp.kt"
)
FRESHNESS_GATE_DECL_FILE = (
    "compose/shared-data/src/commonMain/kotlin/build/raft/app/data/RaftFreshnessSignal.kt"
)
# Ratchet allowlist of legacy page-owned RaftLiveCatchUpEffect sites (#wg-mobile-feedback:#274).
# Shrink as Phases 1-3 migrate each page into store-owned / sync/changes freshness; never add new sites.
FRESHNESS_LIVE_CATCH_UP_ALLOWLIST: tuple[tuple[str, int, str], ...] = (
    (
        "compose/shared/src/commonMain/kotlin/build/raft/app/artifact/ArtifactsHomePage.kt",
        1,
        "#274 Phase 3: move into ArtifactsHomeStore",
    ),
    (
        "compose/shared/src/commonMain/kotlin/build/raft/app/settings/SettingsHomePage.kt",
        1,
        "#274 Phase 3: move Tasks home catch-up into SharedTasksStore",
    ),
    (
        "compose/shared/src/commonMain/kotlin/build/raft/app/settings/SettingsHomeShellNavigation.kt",
        1,
        "#274 Phase 2: fold shell reconnect catch-up into SharedForegroundReconciler",
    ),
    (
        "compose/shared/src/commonMain/kotlin/build/raft/app/thread/ThreadPageTabs.kt",
        1,
        "#274 Phase 3: move channel Tasks tab catch-up into SharedTasksStore",
    ),
    (
        "compose/shared/src/commonMain/kotlin/build/raft/app/thread/ThreadViewportAnchors.kt",
        1,
        "#274 Phase 2: move active conversation catch-up into SharedThreadStore",
    ),
)
# Ratchet allowlist of legacy RaftFreshnessGate sites not yet passing trackOutcome = true (#273/#274).
FRESHNESS_GATE_UNTRACKED_ALLOWLIST: tuple[tuple[str, int, str], ...] = (
    (
        "compose/shared/src/commonMain/kotlin/build/raft/app/thread/RaftLiveCatchUp.kt",
        1,
        "#274 legacy page helper (to be deleted after Phases 1-3)",
    ),
    (
        "compose/shared/src/commonMain/kotlin/build/raft/app/channel/ChannelAnnouncementHost.kt",
        1,
        "#274 legacy announcement host gate (migrate to trackOutcome = true)",
    ),
    (
        "compose/shared-data/src/commonMain/kotlin/build/raft/app/data/SharedAppLinkPreviewStore.kt",
        1,
        "#274 legacy link-preview gate (migrate to trackOutcome = true)",
    ),
)

FRESHNESS_LIVE_CATCH_UP_CALL_RE = re.compile(r"\bRaftLiveCatchUpEffect\s*\(")
FRESHNESS_GATE_CALL_RE = re.compile(r"\bRaftFreshnessGate\s*\(")
FRESHNESS_TRACK_OUTCOME_TRUE_RE = re.compile(r"\btrackOutcome\s*=\s*true\b")


def _extract_balanced_parens(code: str, open_paren_index: int) -> str:
    """Return the argument slice inside balanced parentheses starting at open_paren_index."""
    depth = 0
    for idx in range(open_paren_index, len(code)):
        ch = code[idx]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return code[open_paren_index + 1 : idx]
    return code[open_paren_index + 1 :]


def _validate_ratchet_allowlist(
    allowlist: tuple[tuple[str, int, str], ...],
    label: str,
) -> dict[str, int]:
    expected: dict[str, int] = {}
    for rel, count, reason in allowlist:
        if not reason.strip():
            raise Violation(f"{label} allowlist entry for {rel} has an empty reason")
        if count <= 0:
            raise Violation(f"{label} allowlist entry for {rel} must have count > 0, got {count}")
        if rel in expected:
            raise Violation(f"{label} allowlist has duplicate path {rel}")
        expected[rel] = count
    return expected


def check_freshness_by_default_invariants(
    sources: dict[str, str],
    live_catch_up_allowlist: tuple[tuple[str, int, str], ...] = FRESHNESS_LIVE_CATCH_UP_ALLOWLIST,
    untracked_gate_allowlist: tuple[tuple[str, int, str], ...] = FRESHNESS_GATE_UNTRACKED_ALLOWLIST,
) -> None:
    """Incident: #wg-mobile-feedback:#273/#274 (Freshness by Default).

    1. No new page-owned `RaftLiveCatchUpEffect(...)` call sites in production Kotlin: resources
       must be observed from their single per-server store and refreshed via `sync/changes` or
       store-owned freshness coordinators. Remaining legacy sites are pinned in a ratchet allowlist.
    2. Every new `RaftFreshnessGate(...)` construction in production Kotlin must set
       `trackOutcome = true` so a failed foreground read never consumes the 10 s coalescing window
       before `NetworkRestored` / `Reconnect` arrives. Existing 3 legacy sites are pinned in a
       ratchet allowlist.
    """
    expected_catch_up = _validate_ratchet_allowlist(
        live_catch_up_allowlist, "freshness RaftLiveCatchUpEffect"
    )
    expected_untracked_gate = _validate_ratchet_allowlist(
        untracked_gate_allowlist, "freshness untracked RaftFreshnessGate"
    )
    actual_catch_up: dict[str, int] = {}
    actual_untracked_gate: dict[str, int] = {}

    for name, text in sorted(sources.items()):
        code = _kotlin_code_only(text)

        if name != FRESHNESS_LIVE_CATCH_UP_DECL_FILE:
            matches = list(FRESHNESS_LIVE_CATCH_UP_CALL_RE.finditer(code))
            if matches:
                actual_catch_up[name] = len(matches)
                allowed = expected_catch_up.get(name, 0)
                if len(matches) > allowed:
                    line = code.count("\n", 0, matches[allowed].start()) + 1
                    raise Violation(
                        f"freshness-by-default violation (#wg-mobile-feedback:#274): {name}:{line} "
                        f"calls RaftLiveCatchUpEffect ({len(matches)} > allowed {allowed}); "
                        "do not add page-owned catch-up effects — observe the single store and "
                        "refresh via sync/changes or a store-owned coordinator."
                    )

        if name != FRESHNESS_GATE_DECL_FILE:
            untracked_lines: list[int] = []
            for match in FRESHNESS_GATE_CALL_RE.finditer(code):
                # Skip declaration `class RaftFreshnessGate(` if present in synthetic tests.
                prefix = code[max(0, match.start() - 24) : match.start()]
                if re.search(r"\bclass\s+$", prefix):
                    continue
                args_text = _extract_balanced_parens(code, match.end() - 1)
                if not FRESHNESS_TRACK_OUTCOME_TRUE_RE.search(args_text):
                    untracked_lines.append(code.count("\n", 0, match.start()) + 1)
            if untracked_lines:
                actual_untracked_gate[name] = len(untracked_lines)
                allowed = expected_untracked_gate.get(name, 0)
                if len(untracked_lines) > allowed:
                    line = untracked_lines[allowed]
                    raise Violation(
                        f"freshness-by-default violation (#wg-mobile-feedback:#274): {name}:{line} "
                        f"constructs RaftFreshnessGate without `trackOutcome = true` "
                        f"({len(untracked_lines)} > allowed {allowed}); failed catch-ups must not "
                        "consume the 10 s coalescing window before NetworkRestored/Reconnect."
                    )

    for rel, allowed in sorted(expected_catch_up.items()):
        actual = actual_catch_up.get(rel, 0)
        if actual < allowed:
            raise Violation(
                f"freshness-by-default ratchet (#wg-mobile-feedback:#274): {rel} has {actual} "
                f"RaftLiveCatchUpEffect call(s) (< allowlisted {allowed}); tighten "
                "FRESHNESS_LIVE_CATCH_UP_ALLOWLIST in scripts/ci/check_source_constraints.py."
            )

    for rel, allowed in sorted(expected_untracked_gate.items()):
        actual = actual_untracked_gate.get(rel, 0)
        if actual < allowed:
            raise Violation(
                f"freshness-by-default ratchet (#wg-mobile-feedback:#274): {rel} has {actual} "
                f"untracked RaftFreshnessGate call(s) (< allowlisted {allowed}); tighten "
                "FRESHNESS_GATE_UNTRACKED_ALLOWLIST in scripts/ci/check_source_constraints.py."
            )


def run(root: Path) -> None:
    def read(rel: str) -> str:
        path = root / rel
        if not path.is_file():
            raise Violation(f"constraint source missing (fail-closed): {rel}")
        return path.read_text(encoding="utf-8").replace("\r\n", "\n")

    check_no_conditional_interaction_state(_conditional_state_sources(root))
    check_no_strong_invocation_object_return(_ios_objc_sources(root))
    check_native_apple_authentication_services(
        host=read(IOS_MOBILE_OAUTH_HOST),
        shared=read(IOS_MOBILE_OAUTH_SHARED),
        auth_data_source=read(AUTH_DATA_SOURCE),
        entitlements=read(IOS_ENTITLEMENTS),
        xcode_project=read(IOS_XCODE_PROJECT),
    )
    prod_kotlin = _production_kotlin_sources(root)
    check_no_production_run_blocking(prod_kotlin)
    check_server_refusal_surface_invariants(prod_kotlin)
    check_desktop_wry_off_edt_gateway(prod_kotlin)
    check_freshness_by_default_invariants(prod_kotlin)
    check_native_safe_test_names(_native_test_sources(root))
    check_no_top_level_or_object_snapshot_state(_top_level_snapshot_state_sources(root))
    check_date_time_di_invariants(_date_time_di_sources(root))


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

    if not _is_production_kotlin("compose/shared/src/commonMain/kotlin/A.kt") or not _is_production_kotlin(
        "compose/androidApp/src/main/java/A.kt"
    ):
        raise SystemExit("self-test: runBlocking scan skipped a production source set")
    for test_path in (
        "compose/shared/src/commonTest/kotlin/A.kt",
        "compose/androidApp/src/test/java/A.kt",
        "compose/shared/src/androidUnitTest/kotlin/A.kt",
        "compose/buildSrc/src/main/kotlin/A.kt",
    ):
        if _is_production_kotlin(test_path):
            raise SystemExit(f"self-test: runBlocking scan included {test_path}")
    allow = (("a.kt", "runBlocking { allowed() }", "dedicated background thread"),)
    good_run_blocking = {
        "a.kt": "import kotlinx.coroutines.runBlocking\nval x = runBlocking { allowed() }\n",
        "b.kt": (
            "// runBlocking { readSession() } deadlocked (#3823)\n"
            "/**\n * Never call runBlocking { } here.\n */\n"
            "/* runBlocking(Dispatchers.IO) { } */ val y = 1\n"
            "val z = \"no runBlocking call\"\n"
            # Comments nest in Kotlin: the first */ does not end this one.
            "/* outer /* inner */ runBlocking { still a comment } */ val n = 1\n"
            "val raw = \"\"\"\n  runBlocking { in a raw string } /* not a comment\n\"\"\"\n"
            "val esc = \"quote \\\" runBlocking { still in the string }\"\n"
        ),
    }
    check_no_production_run_blocking(good_run_blocking, allow)
    for bad_sources, bad_allow in (
        ({**good_run_blocking, "c.kt": "val s = runBlocking { store.readSession() }"}, allow),
        ({**good_run_blocking, "c.kt": "runBlocking(Dispatchers.IO) {\n    work()\n}"}, allow),
        ({**good_run_blocking, "c.kt": "val v = runBlocking<Int> { 1 }"}, allow),
        # #3824 review (sindresorhus): string literals that look like comment delimiters.
        ({**good_run_blocking, "c.kt": 'val types = arrayOf("*/*")\nval s = runBlocking { x() }\n'}, allow),
        ({**good_run_blocking, "c.kt": 'val types = arrayOf("image/*")\nfun f() {}\nval s = runBlocking { x() }\n'}, allow),
        ({**good_run_blocking, "c.kt": 'val u = "https://x"; val s = runBlocking { x() }\n'}, allow),
        ({**good_run_blocking, "c.kt": 'val r = """ /* """\nval s = runBlocking { x() }\n'}, allow),
        ({**good_run_blocking, "c.kt": "/* a /* b */ c */ val s = runBlocking { x() }\n"}, allow),
        ({**good_run_blocking, "c.kt": "val q = '\"'\nval s = runBlocking { x() }\n"}, allow),
        ({**good_run_blocking, "c.kt": "val q = '\\''; val s = runBlocking { x() }\n"}, allow),
        ({**good_run_blocking, "c.kt": "val s = runBlocking\n{\n    x()\n}\n"}, allow),
        ({**good_run_blocking, "c.kt": "val s = runBlocking<Int>\n    (Dispatchers.IO) { 1 }\n"}, allow),
        ({**good_run_blocking, "c.kt": 'val t = "${runBlocking { x() }}"\n'}, allow),
        # Allowlisted file, second call site: still a violation.
        ({"a.kt": good_run_blocking["a.kt"] + "val w = runBlocking { other() }\n"}, allow),
        # Same pattern twice in the allowlisted file: the entry no longer pins one site.
        ({"a.kt": good_run_blocking["a.kt"] + "val w = runBlocking { allowed() }\n"}, allow),
        # Stale entry: the allowlisted call is gone.
        ({"a.kt": "val x = 1\n"}, allow),
        # An entry without a reason.
        (good_run_blocking, (("a.kt", "runBlocking { allowed() }", " "),)),
    ):
        try:
            check_no_production_run_blocking(bad_sources, bad_allow)
        except Violation:
            pass
        else:
            raise SystemExit(f"self-test: runBlocking checker accepted {bad_sources!r}")

    check_native_safe_test_names(
        {"t": "@Test\nfun `a muted row's pill - stays put`() {}\nfun plain() {}"}
    )
    for bad in (
        "fun `arrives nothing, even when it drops`() {}",
        "@Test fun `reads v1.2`() {}",
        "fun `a: b`() {}",
    ):
        try:
            check_native_safe_test_names({"t": bad})
        except Violation:
            pass
        else:
            raise SystemExit(f"self-test: test-name checker accepted {bad!r}")

    check_no_strong_invocation_object_return(
        {
            "f": "NSUInteger raw = 0;\n[i getReturnValue:&raw];\n"
            "CGRect frame = CGRectNull;\n[i getReturnValue:&frame];\n"
            "__unsafe_unretained NSArray * _Nullable unretained = nil;\n[i getReturnValue:&unretained];\n"
            "void *bytes = NULL;\n[i getReturnValue:&bytes];\n"
        }
    )
    for bad in (
        "NSArray * _Nullable regions = nil;\n[invocation getReturnValue:&regions];",
        "id result;\n[invocation getReturnValue:&result];",
        "__strong NSString *s = nil;\n[inv getReturnValue: &s];",
    ):
        try:
            check_no_strong_invocation_object_return({"f": bad})
        except Violation:
            pass
        else:
            raise SystemExit(f"self-test: 1.17.1 NSInvocation checker accepted {bad!r}")

    snap_allow = (("a.kt", "var allowed by mutableStateOf(0)", "Main-only test"),)
    good_snap = {
        "a.kt": "object A {\n    var allowed by mutableStateOf(0)\n}\n",
        "b.kt": (
            "@Stable class ViewHolder {\n    var x by mutableStateOf(1)\n    val m = mutableStateMapOf<String, Int>()\n}\n"
            "data class PlainDto(val id: String)\n"
            "fun makeState() = mutableStateOf(0)\n"
            "suspend fun makeAsyncState() = mutableStateOf(0)\n"
            "fun makeMultilineState() =\n    mutableStateOf(0)\n"
            "fun makeParamState(\n    x: Int,\n) = mutableStateOf(x)\n"
            "fun makeHolder() =\n    object : Any() { var x by mutableStateOf(0) }\n"
            "fun makeInline() { val s = mutableStateOf(0) }\n"
            "object Outer : Base {\n    class Inner {\n        var y by mutableIntStateOf(0)\n    }\n"
            "    fun helper() {\n        val local = mutableStateOf(true)\n    }\n"
            "    override fun factory() = mutableStateOf(0)\n"
            "    val fresh get() = mutableStateOf(0)\n}\n"
            "@Composable fun C() { val s = remember { mutableStateOf(0) } }\n"
            "// var commented by mutableStateOf(0)\n"
            'val text = "var str by mutableStateOf(0)"\n'
        ),
    }
    check_no_top_level_or_object_snapshot_state(good_snap, snap_allow)
    for bad_sources, bad_allow in (
        ({**good_snap, "c.kt": "private val Empty = mutableStateOf(0)\n"}, snap_allow),
        ({**good_snap, "c.kt": "internal object Bad {\n    var x by mutableStateOf(false)\n}\n"}, snap_allow),
        ({**good_snap, "c.kt": "class Host {\n    companion object {\n        private val m = mutableStateMapOf<String, Int>()\n    }\n}\n"}, snap_allow),
        ({**good_snap, "c.kt": "class Host {\n    companion object { var x by mutableStateOf(0) }\n}\n"}, snap_allow),
        ({**good_snap, "c.kt": "object Bad { val x by lazy { mutableStateOf(0) } }\n"}, snap_allow),
        ({**good_snap, "c.kt": "object Bad {\n    var rememberedCount by mutableStateOf(0)\n}\n"}, snap_allow),
        ({**good_snap, "c.kt": "object Bad {\n    lateinit var s: Any\n    init {\n        s = mutableStateOf(0)\n    }\n}\n"}, snap_allow),
        ({**good_snap, "c.kt": "object Bad {\n    var n by\n        mutableIntStateOf(0)\n}\n"}, snap_allow),
        ({**good_snap, "c.kt": "object Bad\n{\n    val items = mutableStateListOf<String>()\n}\n"}, snap_allow),
        ({**good_snap, "c.kt": "data class Dto(val a: Int)\nval lazyBad by lazy {\n    mutableStateOf(0)\n}\n"}, snap_allow),
        ({**good_snap, "c.kt": "object Bad {\n    val set = mutableStateSetOf<String>()\n}\n"}, snap_allow),
        ({**good_snap, "c.kt": "object Bad {\n    val list = SnapshotStateList<String>()\n}\n"}, snap_allow),
        ({**good_snap, "c.kt": "object Bad {\n    val derived = derivedStateOf { 1 }\n}\n"}, snap_allow),
        ({"a.kt": good_snap["a.kt"] + "object A2 {\n    var allowed by mutableStateOf(0)\n}\n"}, snap_allow),
        ({"a.kt": "object A { val x = 1 }\n"}, snap_allow),
        (good_snap, (("a.kt", "var allowed by mutableStateOf(0)", " "),)),
    ):
        try:
            check_no_top_level_or_object_snapshot_state(bad_sources, bad_allow)
        except Violation:
            pass
        else:
            raise SystemExit(f"self-test: top-level snapshot-state checker accepted {bad_sources!r}")

    dt_ui = "compose/shared/src/commonMain/kotlin/build/raft/app/settings/SamplePage.kt"
    dt_data = "compose/shared/src/commonMain/kotlin/build/raft/app/data/SampleProtocol.kt"
    dt_allow = ((dt_ui, 1, "Phase 2C: test allowlist"),)
    good_dt = {
        DATE_TIME_DI_LIVE_CONTEXT_FILE: (
            "val p = LocalRaftTimeContextProvider.current ?: LocalRaftPageBaseTimeContextProvider.current\n"
        ),
        dt_ui: (
            "// TimeZone.currentSystemDefault() and x.toLocalDateTime(z) in comments are fine\n"
            'val note = "iso.substringBefore(\'T\') and iso.take(10).split(\'-\')"\n'
            "val ctx = caps.staticContext()\n"
        ),
        dt_data: (
            "val z = TimeZone.currentSystemDefault()\n"
            "val local = instant.toLocalDateTime(z)\n"
        ),
    }
    check_date_time_di_invariants(good_dt, dt_allow)
    for bad_sources, bad_allow in (
        ({**good_dt, dt_ui: good_dt[dt_ui] + "val z = TimeZone.currentSystemDefault()\n"}, dt_allow),
        ({**good_dt, dt_ui: good_dt[dt_ui] + "val d = instant.toLocalDateTime(z)\n"}, dt_allow),
        ({**good_dt, dt_ui: good_dt[dt_ui] + "val p = LocalRaftPageBaseTimeContextProvider.current\n"}, dt_allow),
        ({**good_dt, dt_data: good_dt[dt_data] + "val p = LocalRaftPageBaseTimeContextProvider.current\n"}, dt_allow),
        ({**good_dt, dt_ui: good_dt[dt_ui] + "val d = iso.substringBefore('T')\n"}, dt_allow),
        ({**good_dt, dt_ui: good_dt[dt_ui] + 'val d = iso.substringBefore("T")\n'}, dt_allow),
        ({**good_dt, dt_ui: good_dt[dt_ui] + "val d = iso?.substringBefore('T')\n"}, dt_allow),
        ({**good_dt, dt_ui: good_dt[dt_ui] + "val p = iso.take(10).split('-')\n"}, dt_allow),
        ({**good_dt, dt_ui: good_dt[dt_ui] + 'val p = iso.take(10).split("-")\n'}, dt_allow),
        ({**good_dt, dt_ui: good_dt[dt_ui] + "val p = iso?.take(10)?.split('-')\n"}, dt_allow),
        ({**good_dt, dt_ui: good_dt[dt_ui] + 'val p = iso?.take(10)?.split("-")\n'}, dt_allow),
        ({**good_dt, dt_ui: good_dt[dt_ui] + "val extra = caps.staticContext()\n"}, dt_allow),
        ({**good_dt, dt_data: good_dt[dt_data] + "val c = caps.staticContext()\n"}, dt_allow),
        ({**good_dt, "compose/shared/src/commonMain/kotlin/build/raft/app/Other.kt": "val c = x.staticContext()\n"}, dt_allow),
        ({**good_dt, DATE_TIME_DI_LIVE_CONTEXT_FILE: "val p = LocalRaftTimeContextProvider.current\n"}, dt_allow),
        (
            {
                **good_dt,
                DATE_TIME_DI_LIVE_CONTEXT_FILE: (
                    good_dt[DATE_TIME_DI_LIVE_CONTEXT_FILE]
                    + "val p2 = LocalRaftPageBaseTimeContextProvider.current\n"
                ),
            },
            dt_allow,
        ),
        ({**good_dt, dt_ui: "val ok = 1\n"}, dt_allow),
        (good_dt, ((dt_ui, 1, " "),)),
        (good_dt, ((dt_ui, 0, "zero count"),)),
        (good_dt, ((dt_ui, -1, "negative count"),)),
        (good_dt, ((dt_ui, 1, "first"), (dt_ui, 1, "duplicate path"))),
    ):
        try:
            check_date_time_di_invariants(bad_sources, bad_allow)
        except Violation:
            pass
        else:
            raise SystemExit(f"self-test: date/time DI checker accepted {bad_sources!r}")

    good_refusal = {
        "a.kt": (
            "// channelConversionServerWords in comments is fine\n"
            'val note = "channelConversionServerWords"\n'
            'val ok = code?.startsWith("channel_conversion_") == true\n'
        ),
    }
    check_server_refusal_surface_invariants(good_refusal)
    for bad_refusal in (
        {"a.kt": "val msg = channelConversionServerWords(code, err, true)\n"},
        {"a.kt": "internal fun channelConversionServerWords(): String? = null\n"},
    ):
        try:
            check_server_refusal_surface_invariants(bad_refusal)
        except Violation:
            pass
        else:
            raise SystemExit(f"self-test: server-refusal checker accepted {bad_refusal!r}")

    good_wry = {
        DESKTOP_WRY_GATEWAY_FILE: (
            "import io.github.kdroidfilter.webview.wry.WryWebViewPanel\n"
            "val panel = WryWebViewPanel(initialUrl = \"about:blank\")\n"
            "replaceBlockingFocusMouseListener(panel) {}\n"
            "panel.addNavigateListener { true }\n"
            "io.github.kdroidfilter.webview.wry.loadUrl(id, url)\n"
        ),
        "compose/shared/src/desktopMain/kotlin/build/raft/app/Other.kt": (
            "// WryWebViewPanel and io.github.kdroidfilter.webview.wry in comments are fine\n"
            "val bridge: RaftDesktopWryBridge = gateway\n"
        ),
    }
    check_desktop_wry_off_edt_gateway(good_wry)
    for bad_wry in (
        {"other.kt": "val x = 1\n"},
        {**good_wry, "other.kt": "import io.github.kdroidfilter.webview.wry.WryWebViewPanel\n"},
        {**good_wry, "other.kt": "val p = WryWebViewPanel(\"about:blank\")\n"},
        {**good_wry, "other.kt": "io.github.kdroidfilter.webview.wry.setBounds(1u, 0, 0, 1, 1)\n"},
        {**good_wry, DESKTOP_WRY_GATEWAY_FILE: good_wry[DESKTOP_WRY_GATEWAY_FILE] + "panel.loadUrl(\"x\")\n"},
        {**good_wry, DESKTOP_WRY_GATEWAY_FILE: good_wry[DESKTOP_WRY_GATEWAY_FILE] + "panel.evaluateJavaScript(\"1\") {}\n"},
        {**good_wry, DESKTOP_WRY_GATEWAY_FILE: good_wry[DESKTOP_WRY_GATEWAY_FILE] + "panel.requestWebViewFocus()\n"},
        {**good_wry, DESKTOP_WRY_GATEWAY_FILE: good_wry[DESKTOP_WRY_GATEWAY_FILE].replace("replaceBlockingFocusMouseListener", "noop")},
    ):
        try:
            check_desktop_wry_off_edt_gateway(bad_wry)
        except Violation:
            pass
        else:
            raise SystemExit(f"self-test: desktop Wry off-EDT gateway checker accepted {bad_wry!r}")

    fresh_legacy_page = "compose/shared/src/commonMain/kotlin/build/raft/app/LegacyPage.kt"
    fresh_legacy_gate = "compose/shared-data/src/commonMain/kotlin/build/raft/app/data/LegacyStore.kt"
    fresh_good_store = "compose/shared-data/src/commonMain/kotlin/build/raft/app/data/NewStore.kt"
    good_fresh = {
        FRESHNESS_LIVE_CATCH_UP_DECL_FILE: (
            "internal fun RaftLiveCatchUpEffect() { val g = RaftFreshnessGate(trackOutcome = true) }\n"
        ),
        FRESHNESS_GATE_DECL_FILE: "class RaftFreshnessGate(val trackOutcome: Boolean = false)\n",
        fresh_legacy_page: "fun P() { RaftLiveCatchUpEffect(serverId = id) {} }\n",
        fresh_legacy_gate: "val g = RaftFreshnessGate(nowMillis = clock)\n",
        fresh_good_store: (
            "// RaftLiveCatchUpEffect( in comments is ignored\n"
            "val g = RaftFreshnessGate(\n"
            "    nowMillis = clock,\n"
            "    trackOutcome = true,\n"
            ")\n"
        ),
    }
    fresh_cu_allow = ((fresh_legacy_page, 1, "legacy"),)
    fresh_gate_allow = ((fresh_legacy_gate, 1, "legacy"),)
    check_freshness_by_default_invariants(good_fresh, fresh_cu_allow, fresh_gate_allow)
    for bad_fresh, bad_cu, bad_gt in (
        # New RaftLiveCatchUpEffect outside allowlist
        ({**good_fresh, "new.kt": "fun N() { RaftLiveCatchUpEffect(id) {} }\n"}, fresh_cu_allow, fresh_gate_allow),
        # Second call in allowlisted file
        ({**good_fresh, fresh_legacy_page: good_fresh[fresh_legacy_page] * 2}, fresh_cu_allow, fresh_gate_allow),
        # Stale RaftLiveCatchUpEffect allowlist entry (must ratchet down)
        ({**good_fresh, fresh_legacy_page: "fun P() {}\n"}, fresh_cu_allow, fresh_gate_allow),
        # New RaftFreshnessGate without trackOutcome = true
        ({**good_fresh, "new_store.kt": "val g = RaftFreshnessGate(nowMillis = clock)\n"}, fresh_cu_allow, fresh_gate_allow),
        # New RaftFreshnessGate with trackOutcome = false
        ({**good_fresh, "new_store.kt": "val g = RaftFreshnessGate(trackOutcome = false)\n"}, fresh_cu_allow, fresh_gate_allow),
        # Stale RaftFreshnessGate allowlist entry (must ratchet down)
        ({**good_fresh, fresh_legacy_gate: "val g = RaftFreshnessGate(trackOutcome = true)\n"}, fresh_cu_allow, fresh_gate_allow),
    ):
        try:
            check_freshness_by_default_invariants(bad_fresh, bad_cu, bad_gt)
        except Violation:
            pass
        else:
            raise SystemExit(f"self-test: freshness-by-default checker accepted {bad_fresh!r}")

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
        "source constraints hold (1.15.0 conditional interaction state, task #82 native Apple, "
        "#3823 no production runBlocking, #3845 Native-safe test names, "
        "1.17.1 NSInvocation object return, #264 no top-level/object snapshot state, "
        "dm:@artin:#2 date/time DI invariants, #proj-mobile:#319 server refusal invariants, "
        "#wg-mobile-sync:bf308ade desktop Wry off-EDT gateway, "
        "#wg-mobile-feedback:#274 freshness by default)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
