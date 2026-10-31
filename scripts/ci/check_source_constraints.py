#!/usr/bin/env python3
"""Incident-born static source constraint (explicit CI gate, NOT a unit test).

Per the test-discipline announcement (#all 2026-07-25, artin-directed): static
constraints live here as an explicit gate with a self-test, instead of being
dressed up as source-grep "contract tests" inside test directories.

Five constraints remain: check_no_conditional_interaction_state, born from the
1.15.0 release crash on launch, check_native_apple_authentication_services
(task #82: Swift-side Sign in with Apple, which no Android or JVM test covers),
check_no_production_run_blocking (#3823: a main-thread runBlocking on the
auth-session lock deadlocked the app; a hang no unit test can catch reliably),
check_native_safe_test_names (#3845: a comma in a backtick test name broke
the iOS test compile on main, which PR CI does not build), and
check_no_strong_invocation_object_return (1.17.1: an NSInvocation object return
read into a strong variable crashed iOS 27.2 at launch; simulators don't crash).
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
    "compose/shared/src/commonMain/kotlin/build/raft/app/data/IosMobileOAuth.kt"
)
AUTH_DATA_SOURCE = "compose/shared/src/commonMain/kotlin/build/raft/app/data/AuthDataSource.kt"
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
        "compose/androidApp/src/main/java/build/raft/app/core/data/realtime/SessionRealtimeRepository.kt",
        "runBlocking {",
        "Socket.IO auth-refresh provider; runs only on the dedicated slock-realtime-auth-refresh thread",
    ),
    (
        "compose/desktopApp/src/main/kotlin/build/raft/app/desktop/realtime/SessionRealtimeRepository.kt",
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
        "compose/shared/src/commonMain/kotlin/build/raft/app/data/RaftSqlDatabase.kt",
        "runBlocking {",
        "inside SQLDelight's thread-confined transaction callback, entered via runOffStoreDispatcher; "
        "the block is synchronous SQL",
    ),
    (
        "compose/shared/src/commonMain/kotlin/build/raft/app/resources/RaftStringDesc.kt",
        "runBlocking { getString(environment, stringRes) }",
        "Compose resources string lookup, as CMP's own stringResource does; cached catalog read, "
        "no app lock",
    ),
    (
        "compose/shared/src/commonMain/kotlin/build/raft/app/resources/RaftStringDesc.kt",
        "runBlocking { getString(environment, stringRes, *resolvedArgs.toTypedArray()) }",
        "Compose resources formatted string lookup, as CMP's own stringResource does; cached "
        "catalog read, no app lock",
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
    check_no_production_run_blocking(_production_kotlin_sources(root))
    check_native_safe_test_names(_native_test_sources(root))


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
        "1.17.1 NSInvocation object return)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
