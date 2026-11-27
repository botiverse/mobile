package build.raft.app

import build.raft.app.data.RaftClientConfigAppHosts
import build.raft.app.markdown.raftAppLinkAsciiHost
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Mobile In-App Browser Phase 2 state, pure routing/layout rules, and process-wide Option B
 * multi-page Raft App tab pool coordinator (#proj-mobile:#398, #proj-workspace:#412).
 *
 * Invariants:
 * - Only installed Raft Apps open in Raft's authenticated in-app browser stack page; ordinary
 *   external http(s) links route directly to Chrome Custom Tabs / SFSafariViewController.
 * - Up to [RAFT_APP_BROWSER_MAX_TABS] (5) Raft App pages may remain open simultaneously; opening
 *   a 6th evicts the oldest suspended tab (never the currently visible foreground tab).
 * - Up to [RAFT_APP_BROWSER_LIVE_POOL_SIZE] (2) live WebViews stay warm in LRU across suspend and
 *   resume; older suspended tabs hibernate (retaining URL, title, and App metadata) and reload
 *   cleanly on resume.
 * - Logout, account switch, server switch, or environment switch immediately clears all tabs and
 *   evicts every warm WebView. Memory pressure hibernates background live tabs while preserving
 *   the active foreground page.
 */

internal const val RAFT_APP_BROWSER_MAX_TABS: Int = 5
internal const val RAFT_APP_BROWSER_LIVE_POOL_SIZE: Int = 2
internal const val RAFT_APP_BROWSER_KEEP_ALIVE_PREFIX: String = "raft-app-tab:"
internal const val RAFT_APP_BROWSER_ENTRY_OWNER_PREFIX: String = "raft-app-tab-owner:"
internal const val RAFT_FLOATING_PILL_DEFAULT_Y_FRACTION: Float = 0.52f

/** Which storage partition a mobile browser launch uses (#proj-workspace:#397 / #412). */
internal sealed interface RaftMobileWebPanelLaunchResolution {
    data class OpenInPanel(
        val storagePartition: RaftWebViewStoragePartition,
        val isRaftApp: Boolean,
    ) : RaftMobileWebPanelLaunchResolution

    /**
     * Fail-closed handoff to the system browser when third-party storage isolation is unavailable
     * (Android pre-Chromium 119 without `MULTI_PROFILE`). Never falls back to [RaftWebViewStoragePartition.Default].
     */
    data object FailClosedToSystemBrowser : RaftMobileWebPanelLaunchResolution
}

/**
 * Resolves the WebView storage partition for a launch:
 * - Own-host ephemeral (`useEphemeralStore == true`) -> [RaftWebViewStoragePartition.Ephemeral].
 * - Installed Raft App (`isRaftAppEntry == true`) -> [RaftWebViewStoragePartition.Default].
 * - Cross-origin outbound child page or third-party fallback -> [RaftWebViewStoragePartition.IsolatedThirdParty]
 *   when [supportsIsolatedThirdPartyProfile] is true, else [RaftMobileWebPanelLaunchResolution.FailClosedToSystemBrowser].
 */
internal fun resolveRaftMobileWebPanelStorage(
    isRaftAppEntry: Boolean,
    useEphemeralStore: Boolean,
    supportsIsolatedThirdPartyProfile: Boolean,
): RaftMobileWebPanelLaunchResolution =
    when {
        useEphemeralStore ->
            RaftMobileWebPanelLaunchResolution.OpenInPanel(
                storagePartition = RaftWebViewStoragePartition.Ephemeral,
                isRaftApp = false,
            )
        isRaftAppEntry ->
            RaftMobileWebPanelLaunchResolution.OpenInPanel(
                storagePartition = RaftWebViewStoragePartition.Default,
                isRaftApp = true,
            )
        supportsIsolatedThirdPartyProfile ->
            RaftMobileWebPanelLaunchResolution.OpenInPanel(
                storagePartition = RaftWebViewStoragePartition.IsolatedThirdParty,
                isRaftApp = false,
            )
        else -> RaftMobileWebPanelLaunchResolution.FailClosedToSystemBrowser
    }

/**
 * Formats a site's domain for domain-only outbound child pages, switcher rows, and fallback titles:
 * - Full host minus a single leading `www.` (only when another label follows it).
 * - Internationalized domain names rendered in ASCII punycode (`xn--`) via [raftAppLinkAsciiHost].
 * - Preserves non-default ports (drops `:80` for `http` and `:443` for `https`).
 */
internal fun raftMobileWebPanelDisplayHost(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return ""
    val schemeEnd = trimmed.indexOf("://")
    val scheme = if (schemeEnd > 0) trimmed.substring(0, schemeEnd).lowercase() else ""
    val afterScheme = if (schemeEnd >= 0) trimmed.substring(schemeEnd + 3) else trimmed
    val authorityEnd = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val rawAuthority = (if (authorityEnd < 0) afterScheme else afterScheme.substring(0, authorityEnd)).trim()
    if (rawAuthority.isEmpty()) return ""
    val hostPort = rawAuthority.substringAfterLast('@').trim()
    if (hostPort.isEmpty()) return ""
    val hostPart: String
    val portPart: Int?
    if (hostPort.startsWith('[')) {
        val close = hostPort.indexOf(']')
        if (close <= 1) return ""
        hostPart = hostPort.substring(0, close + 1).lowercase()
        val remainder = hostPort.substring(close + 1)
        portPart = if (remainder.startsWith(':')) remainder.drop(1).toIntOrNull() else null
    } else {
        val colon = hostPort.lastIndexOf(':')
        if (colon > 0 && hostPort.indexOf(':') == colon) {
            hostPart = hostPort.substring(0, colon)
            portPart = hostPort.substring(colon + 1).toIntOrNull()
        } else {
            hostPart = hostPort
            portPart = null
        }
    }
    val asciiHost =
        raftAppLinkAsciiHost(hostPart)
            .lowercase()
            .trimEnd('.')
    if (asciiHost.isEmpty()) return ""
    val withoutWww =
        if (asciiHost.startsWith("www.") && asciiHost.indexOf('.', startIndex = 4) > 0) {
            asciiHost.removePrefix("www.")
        } else {
            asciiHost
        }
    val showPort =
        portPart != null &&
            portPart in 1..65535 &&
            !((scheme == "https" && portPart == 443) || (scheme == "http" && portPart == 80))
    return if (showPort) "$withoutWww:$portPart" else withoutWww
}

/** Defensive fail-closed action when isolated third-party storage is unavailable on pre-119 Android. */
internal enum class RaftMobileWebPanelFailClosedAction {
    None,
    HandoffToSystemBrowser,
    CloseSilently,
}

/**
 * Decides whether a [RaftMobileWebPanelLaunchResolution.FailClosedToSystemBrowser] resolution may
 * hand off to the external system browser. An already-closing panel or one that was previously
 * authorized as an installed Raft App must NEVER launch the external system browser (e.g. during
 * tear-down recomposition after its entry token is released).
 */
internal fun resolveRaftMobileWebPanelFailClosedAction(
    storageResolution: RaftMobileWebPanelLaunchResolution,
    effectiveLaunchUrl: String?,
    closing: Boolean,
    wasAuthorizedAppEntry: Boolean,
): RaftMobileWebPanelFailClosedAction {
    if (storageResolution != RaftMobileWebPanelLaunchResolution.FailClosedToSystemBrowser) {
        return RaftMobileWebPanelFailClosedAction.None
    }
    if (closing) {
        return RaftMobileWebPanelFailClosedAction.None
    }
    if (wasAuthorizedAppEntry) {
        return RaftMobileWebPanelFailClosedAction.CloseSilently
    }
    return if (!effectiveLaunchUrl.isNullOrBlank()) {
        RaftMobileWebPanelFailClosedAction.HandoffToSystemBrowser
    } else {
        RaftMobileWebPanelFailClosedAction.None
    }
}

/**
 * Whether [RaftMobileWebPanelBrowserBody] should show its immediate centered loading indicator
 * over the initial canvas before the document visually commits. Never covers error/sign-in UI or
 * warm keep-alive pages that already finished loading.
 */
internal fun shouldShowRaftMobileWebPanelLoadingPlaceholder(
    hasLaunchUrl: Boolean,
    hasVisualCommit: Boolean,
    hasLoadError: Boolean,
    isSignInResolving: Boolean,
    hasDeclinedSignIn: Boolean,
    isLoading: Boolean,
    progress: Int,
): Boolean =
    hasLaunchUrl &&
        !hasVisualCommit &&
        !hasLoadError &&
        !isSignInResolving &&
        !hasDeclinedSignIn &&
        isLoading &&
        progress < 25

/** Actions offered in the in-app browser's `⋮` overflow menu. */
internal enum class RaftMobileWebPanelMenuAction {
    Reload,
    MinimizeToFloat,
    ForwardToRaft,
    Share,
    CopyLink,
    OpenInBrowser,
    ClosePage,
}

/**
 * Overflow menu items by page mode:
 * - Installed Raft App page: Reload, (optional) Float window, Forward to Raft, Share, Copy link,
 *   Open in browser, Close page.
 * - Cross-origin unauthenticated child page: Reload, Forward to Raft, Share, Copy link, Open in browser.
 */
internal fun raftMobileWebPanelMenuActions(
    isRaftApp: Boolean,
    isOutboundChild: Boolean = false,
    canMinimizeToFloat: Boolean = false,
): List<RaftMobileWebPanelMenuAction> =
    buildList {
        add(RaftMobileWebPanelMenuAction.Reload)
        if (isRaftApp && !isOutboundChild && canMinimizeToFloat) {
            add(RaftMobileWebPanelMenuAction.MinimizeToFloat)
        }
        add(RaftMobileWebPanelMenuAction.ForwardToRaft)
        add(RaftMobileWebPanelMenuAction.Share)
        add(RaftMobileWebPanelMenuAction.CopyLink)
        add(RaftMobileWebPanelMenuAction.OpenInBrowser)
        if (isRaftApp && !isOutboundChild) {
            add(RaftMobileWebPanelMenuAction.ClosePage)
        }
    }

/**
 * Whether an `http`/`https` navigation inside an open installed Raft App crosses outside
 * [scopeOrigin] and must be pushed as a clean unauthenticated domain-only child page (or switched
 * to another installed Raft App if the target origin is itself installed), rather than loading
 * inside the App's authenticated `Default`-partition WebView (`raft-design #1417` Row 12).
 *
 * Mirrors Desktop `appTabLeaves` parity (`webPreviewPage.ts`):
 * - Server redirects ([isRedirect] == true) NEVER route out — they belong to the App's own
 *   OAuth / SSO round trip (e.g. `stamp.build/auth/github/start` -> 302 -> `github.com/...`).
 * - Only routes out when the currently committed document ([committedDocumentUrl]) is on
 *   [scopeOrigin]. If nothing has committed yet or the tab is already temporarily off
 *   [scopeOrigin] (e.g. mid-OAuth on `github.com`), every follow-up navigation stays in the same
 *   WebView and cookie jar until the round trip returns to [scopeOrigin].
 * - Also returns `false` for same-origin navigations, non-http(s) schemes (handled by external
 *   scheme confirmation), App Store 3.1.1 restricted purchase URLs (handled by billing blocker),
 *   and Raft's own hosts (handled by OAuth sign-in catch / Raft host router).
 */
internal fun raftAppShouldRouteCrossOriginOutbound(
    candidateUrl: String,
    scopeOrigin: String?,
    activeWebBaseUrl: String?,
    activeApiBaseUrl: String? = null,
    appStoreBillingRules: Boolean = raftPlatformFollowsAppStoreBillingRules(),
    appHosts: Set<String> = RaftClientConfigAppHosts.current,
    isRedirect: Boolean = false,
    committedDocumentUrl: String? = null,
): Boolean {
    if (isRedirect) return false
    val cleanScope = scopeOrigin?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val committedOrigin = committedDocumentUrl?.trim()?.takeIf { it.isNotEmpty() }?.let(::raftAppEntryOriginOf)
    if (committedOrigin != cleanScope) return false
    val trimmed = candidateUrl.trim()
    if (trimmed.isEmpty()) return false
    val candidateOrigin = raftAppEntryOriginOf(trimmed) ?: return false
    if (candidateOrigin == cleanScope) return false
    if (raftInAppBrowserShouldBlockUrl(trimmed, activeWebBaseUrl, appStoreBillingRules = appStoreBillingRules, appHosts = appHosts)) {
        return false
    }
    if (raftInAppBrowserIsRaftHost(trimmed, activeWebBaseUrl, activeApiBaseUrl, appHosts)) {
        return false
    }
    if (raftAppIsInTabAuthHandoffUrl(trimmed, cleanScope)) {
        return false
    }
    return true
}

private val RaftAppOAuthCallbackQueryKeys =
    listOf("redirect_uri", "return_to", "callback_url", "post_logout_redirect_uri")

private val RaftAppGitHubInstallPathRegex =
    Regex("^/apps/[A-Za-z0-9-]+/installations/(?:new|select_target)/?$", RegexOption.IGNORE_CASE)

/**
 * Whether a non-redirect cross-origin navigation from an installed Raft App on [scopeOrigin] is an
 * explicit third-party OAuth / IdP App-install handoff that must stay inside the App's own WebView
 * (preserving its cookie jar and session across the callback round-trip, even on pre-Chromium-119
 * Android devices without `MULTI_PROFILE` where outbound child pages would otherwise hand off to
 * the system browser).
 *
 * Matches ONLY:
 * 1. Standard OAuth/OIDC authorization URLs carrying `client_id` or `response_type` together with a
 *    callback parameter (`redirect_uri`, `return_to`, `callback_url`, `post_logout_redirect_uri`)
 *    whose origin strictly matches [scopeOrigin]; OR
 * 2. GitHub's OAuth authorize (`/login/oauth/authorize`) and GitHub App installation endpoints
 *    (`/apps/<slug>/installations/new`, `/apps/<slug>/installations/select_target`) over HTTPS.
 *
 * Ordinary cross-origin links (e.g. viewing a GitHub pull request or external docs) return `false`
 * and continue routing into an isolated outbound child page.
 */
internal fun raftAppIsInTabAuthHandoffUrl(
    candidateUrl: String,
    scopeOrigin: String,
): Boolean {
    val parsed = raftBrowserUrlOf(candidateUrl)?.takeIf { it.userInfo == null } ?: return false
    val scopeParsed = raftBrowserUrlOf(scopeOrigin)?.takeIf { it.userInfo == null } ?: return false
    val secureTarget = parsed.scheme == "https"
    val loopbackHttpAllowed = scopeParsed.scheme == "http" && parsed.scheme == "http" && parsed.host in RaftBrowserDebugLoopbackHosts
    if (!secureTarget && !loopbackHttpAllowed) return false

    if (secureTarget && parsed.host == "github.com" && parsed.port == null) {
        val normalizedPath = parsed.path.trimEnd('/').lowercase()
        if (normalizedPath == "/login/oauth/authorize" || RaftAppGitHubInstallPathRegex.matches(parsed.path)) {
            return true
        }
    }

    val query = RaftBrowserQuery.parse(parsed.query) ?: return false
    val hasOAuthStartParam =
        !query.first("client_id").isNullOrBlank() || !query.first("response_type").isNullOrBlank()
    if (!hasOAuthStartParam) return false
    return RaftAppOAuthCallbackQueryKeys.any { key ->
        query.all(key).any { callback ->
            raftAppEntryOriginOf(callback) == scopeOrigin
        }
    }
}

/**
 * Whether an open installed Raft App tab is currently displaying a document on its own
 * [scopeOrigin]. When `false` (for example during an in-tab third-party OAuth round trip such as
 * `github.com`), the top bar hides App branding (`Stamp · Signed in as ...`) and shows only the
 * live host domain to prevent origin spoofing.
 */
internal fun raftAppIsOnScopeOrigin(
    currentUrl: String?,
    scopeOrigin: String?,
): Boolean {
    val cleanScope = scopeOrigin?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val cleanUrl = currentUrl?.trim().orEmpty()
    if (cleanUrl.isEmpty()) return true
    return raftAppEntryOriginOf(cleanUrl) == cleanScope
}

/** Action taken when closing the sign-in-declined error overlay. */
internal enum class RaftMobileWebPanelDeclinedCloseAction {
    DismissNotice,
    ClosePage,
}

/**
 * Closing the sign-in-declined overlay dismisses the notice only when a real document has already
 * committed underneath; otherwise (e.g. a deep link redirected to `/api/oauth/authorize` before
 * any page committed), closing exits the browser page cleanly instead of exposing a blank white
 * WebView.
 */
internal fun resolveRaftMobileWebPanelDeclinedCloseAction(
    hasCommittedDocument: Boolean,
): RaftMobileWebPanelDeclinedCloseAction =
    if (hasCommittedDocument) {
        RaftMobileWebPanelDeclinedCloseAction.DismissNotice
    } else {
        RaftMobileWebPanelDeclinedCloseAction.ClosePage
    }

/**
 * Resolves a fresh launch URL to load via `controller.loadUrl(...)` on Reload when no document has
 * committed yet or the active URL is blank / `about:blank` (where `controller.reload()` would only
 * reload `about:blank`). Returns `null` when normal `controller.reload()` should be used.
 */
internal fun resolveRaftMobileWebPanelReloadTargetUrl(
    currentUrl: String,
    effectiveLaunchUrl: String?,
    hasCommittedDocument: Boolean,
): String? {
    val launch = effectiveLaunchUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val current = currentUrl.trim()
    val needsFreshLoad =
        !hasCommittedDocument ||
            current.isEmpty() ||
            current.equals("about:blank", ignoreCase = true)
    return if (needsFreshLoad) launch else null
}

/** System Back / top-bar ← resolution while an in-app browser page is visible. */
internal enum class RaftAppBrowserBackAction {
    WebGoBack,
    PopOutboundChild,
    CloseAppPage,
}

/**
 * Resolves system Back / top-bar ← inside an in-app browser stack page:
 * - When the WebView has back history (`canGoBack == true`): go back within WebView history.
 * - On an outbound cross-origin child page at its first history entry: pop back to the parent App.
 * - On an installed Raft App page at its first history entry: close cleanly (no auto-floating pill;
 *   minimizing to the right-edge floating pill is an explicit opt-in action in the `⋮` menu).
 */
internal fun resolveRaftAppBrowserBackAction(
    canGoBack: Boolean,
    isOutboundChild: Boolean,
): RaftAppBrowserBackAction =
    when {
        canGoBack -> RaftAppBrowserBackAction.WebGoBack
        isOutboundChild -> RaftAppBrowserBackAction.PopOutboundChild
        else -> RaftAppBrowserBackAction.CloseAppPage
    }

/** Physical screen edge where the magnetic floating browser tab pill docks. */
internal enum class RaftFloatingPillEdge {
    Left,
    Right,
}

/** Clamped physical `[minX, maxX]` horizontal range (in px) for the floating tab pill. */
internal data class RaftFloatingPillHorizontalRange(
    val minX: Float,
    val maxX: Float,
)

private fun Float.finiteNonNegativeOrZero(): Float = if (isFinite()) coerceAtLeast(0f) else 0f

/**
 * Resolves the clamped physical `[minX, maxX]` horizontal range (in px) for the floating tab pill
 * within `[leftSafeAreaPx, viewportWidthPx - rightSafeAreaPx - pillWidthPx]`.
 */
internal fun resolveRaftFloatingPillHorizontalRange(
    viewportWidthPx: Float,
    pillWidthPx: Float,
    leftSafeAreaPx: Float = 0f,
    rightSafeAreaPx: Float = 0f,
): RaftFloatingPillHorizontalRange {
    val safeViewport = viewportWidthPx.finiteNonNegativeOrZero()
    val safePillWidth = pillWidthPx.finiteNonNegativeOrZero()
    val safeLeft = leftSafeAreaPx.finiteNonNegativeOrZero()
    val safeRight = rightSafeAreaPx.finiteNonNegativeOrZero()
    val maxPossibleLeft = (safeViewport - safePillWidth).coerceAtLeast(0f)
    val minX = safeLeft.coerceAtMost(maxPossibleLeft)
    val maxX = (safeViewport - safeRight - safePillWidth).coerceAtLeast(minX)
    return RaftFloatingPillHorizontalRange(minX = minX, maxX = maxX)
}

/** Resolves the physical left offset (in px) where the floating tab pill docks for [edge]. */
internal fun resolveRaftFloatingPillDockedOffsetXPx(
    edge: RaftFloatingPillEdge,
    viewportWidthPx: Float,
    pillWidthPx: Float,
    leftSafeAreaPx: Float = 0f,
    rightSafeAreaPx: Float = 0f,
): Float {
    val range =
        resolveRaftFloatingPillHorizontalRange(
            viewportWidthPx = viewportWidthPx,
            pillWidthPx = pillWidthPx,
            leftSafeAreaPx = leftSafeAreaPx,
            rightSafeAreaPx = rightSafeAreaPx,
        )
    return when (edge) {
        RaftFloatingPillEdge.Left -> range.minX
        RaftFloatingPillEdge.Right -> range.maxX
    }
}

/** Clamps a dragged physical left offset [offsetXPx] within the safe horizontal pill range. */
internal fun clampRaftFloatingPillOffsetXPx(
    offsetXPx: Float,
    viewportWidthPx: Float,
    pillWidthPx: Float,
    leftSafeAreaPx: Float = 0f,
    rightSafeAreaPx: Float = 0f,
    fallbackEdge: RaftFloatingPillEdge = RaftFloatingPillEdge.Right,
): Float {
    val range =
        resolveRaftFloatingPillHorizontalRange(
            viewportWidthPx = viewportWidthPx,
            pillWidthPx = pillWidthPx,
            leftSafeAreaPx = leftSafeAreaPx,
            rightSafeAreaPx = rightSafeAreaPx,
        )
    if (!offsetXPx.isFinite()) {
        return when (fallbackEdge) {
            RaftFloatingPillEdge.Left -> range.minX
            RaftFloatingPillEdge.Right -> range.maxX
        }
    }
    return offsetXPx.coerceIn(range.minX, range.maxX)
}

/**
 * Resolves which physical screen edge ([RaftFloatingPillEdge.Left] or [RaftFloatingPillEdge.Right])
 * the floating tab pill should magnetically dock to when released at [offsetXPx].
 *
 * Compares the clamped pill position against the midpoint of its dockable horizontal span so the
 * pill snaps to whichever edge is closer; exact midpoint ties, narrow viewports with zero span, and
 * non-finite inputs preserve [currentEdge].
 */
internal fun resolveRaftFloatingPillSnapEdge(
    offsetXPx: Float,
    viewportWidthPx: Float,
    pillWidthPx: Float,
    leftSafeAreaPx: Float = 0f,
    rightSafeAreaPx: Float = 0f,
    currentEdge: RaftFloatingPillEdge = RaftFloatingPillEdge.Right,
): RaftFloatingPillEdge {
    if (!offsetXPx.isFinite() || !viewportWidthPx.isFinite() || !pillWidthPx.isFinite()) {
        return currentEdge
    }
    val range =
        resolveRaftFloatingPillHorizontalRange(
            viewportWidthPx = viewportWidthPx,
            pillWidthPx = pillWidthPx,
            leftSafeAreaPx = leftSafeAreaPx,
            rightSafeAreaPx = rightSafeAreaPx,
        )
    if (range.maxX <= range.minX) return currentEdge
    val clampedX = offsetXPx.coerceIn(range.minX, range.maxX)
    val midpoint = (range.minX + range.maxX) * 0.5f
    return when {
        clampedX < midpoint -> RaftFloatingPillEdge.Left
        clampedX > midpoint -> RaftFloatingPillEdge.Right
        else -> currentEdge
    }
}

/**
 * Resolves the clamped vertical top offset (in px) for the edge-docked floating tab pill within
 * safe viewport bounds `[topBoundPx, max(topBoundPx, viewportHeightPx - bottomBoundPx - pillHeightPx)]`.
 */
internal fun resolveRaftFloatingPillOffsetYPx(
    viewportHeightPx: Float,
    pillHeightPx: Float,
    topBoundPx: Float,
    bottomBoundPx: Float,
    yFraction: Float,
): Float {
    val safeTop = topBoundPx.finiteNonNegativeOrZero()
    val safeViewport = viewportHeightPx.finiteNonNegativeOrZero()
    val safeBottom = bottomBoundPx.finiteNonNegativeOrZero()
    val safePillHeight = pillHeightPx.finiteNonNegativeOrZero()
    val safeMax = (safeViewport - safeBottom - safePillHeight).coerceAtLeast(safeTop)
    val span = (safeMax - safeTop).coerceAtLeast(0f)
    val safeFraction = if (yFraction.isFinite()) yFraction.coerceIn(0f, 1f) else RAFT_FLOATING_PILL_DEFAULT_Y_FRACTION
    return safeTop + span * safeFraction
}

/** Converts a dragged vertical offset [offsetYPx] back into a clamped `[0f, 1f]` fraction. */
internal fun clampRaftFloatingPillYFraction(
    offsetYPx: Float,
    viewportHeightPx: Float,
    pillHeightPx: Float,
    topBoundPx: Float,
    bottomBoundPx: Float,
): Float {
    val safeTop = topBoundPx.finiteNonNegativeOrZero()
    val safeViewport = viewportHeightPx.finiteNonNegativeOrZero()
    val safeBottom = bottomBoundPx.finiteNonNegativeOrZero()
    val safePillHeight = pillHeightPx.finiteNonNegativeOrZero()
    val safeMax = (safeViewport - safeBottom - safePillHeight).coerceAtLeast(safeTop)
    val span = safeMax - safeTop
    if (!offsetYPx.isFinite() || span <= 1f) return RAFT_FLOATING_PILL_DEFAULT_Y_FRACTION
    return ((offsetYPx - safeTop) / span).coerceIn(0f, 1f)
}

/** One open installed Raft App page tracked in the Option B multi-page pool. */
internal data class RaftAppBrowserTab(
    val tabId: Long,
    val launchUrl: String,
    /**
     * URL configured as the WebView's initial `src`: stays stable while [isLive] is true so
     * re-attaching a parked warm WebView never triggers a reload, and snapshots [currentUrl] upon
     * hibernation so resuming a hibernated tab reloads right where the user left off.
     */
    val loadedBaseUrl: String = launchUrl,
    val currentUrl: String = launchUrl,
    val pageTitle: String? = null,
    val appEntryToken: String,
    val scopeOrigin: String,
    val appName: String? = null,
    val appLogoUrl: String? = null,
    val preferredServerId: String? = null,
    val accountId: String? = null,
    val serverId: String? = null,
    val environmentKey: String? = null,
    val isLive: Boolean = true,
    val lastUsedSeq: Long = 0L,
) {
    /** Native WebView keep-alive key (shared by Android & iOS 2-slot LRU keep-alive pools). */
    val keepAliveKey: String
        get() = "$RAFT_APP_BROWSER_KEEP_ALIVE_PREFIX$tabId"

    /** Stable [RaftAppEntryRegistry] owner key held across suspend/resume and hibernation reloads. */
    val entryOwnerId: String
        get() = "$RAFT_APP_BROWSER_ENTRY_OWNER_PREFIX$tabId"

    /** Current best URL to reload if the tab resumes after hibernation. */
    val resumeUrl: String
        get() = currentUrl.trim().ifBlank { launchUrl }

    /** Formatted ASCII host (`www.` stripped, IDN punycode). */
    val displayHost: String
        get() = raftMobileWebPanelDisplayHost(resumeUrl)

    /** App display name (falls back to formatted host). */
    val displayName: String
        get() = appName?.trim()?.takeIf { it.isNotEmpty() } ?: displayHost

    /** Page title for switcher rows when meaningful, otherwise [displayName]. */
    val switcherPrimaryLabel: String
        get() =
            pageTitle
                ?.trim()
                ?.takeIf { title ->
                    title.isNotEmpty() &&
                        !title.equals(resumeUrl, ignoreCase = true) &&
                        !title.startsWith("http://", ignoreCase = true) &&
                        !title.startsWith("https://", ignoreCase = true)
                }
                ?: displayName
}

/** Immutable snapshot of the Option B multi-page floating tab pill state. */
internal data class RaftAppBrowserTabsState(
    /** Open Raft App tabs ordered most-recently-used first (capped at [RAFT_APP_BROWSER_MAX_TABS]). */
    val tabs: List<RaftAppBrowserTab> = emptyList(),
    /** Currently visible foreground App tab id, or `null` when all open tabs are suspended. */
    val activeTabId: Long? = null,
    /** Persisted vertical dock fraction `[0f, 1f]` of the edge-docked floating tab pill. */
    val pillYFraction: Float = RAFT_FLOATING_PILL_DEFAULT_Y_FRACTION,
    /** Persisted physical horizontal dock edge of the floating tab pill (defaults to [RaftFloatingPillEdge.Right]). */
    val pillEdge: RaftFloatingPillEdge = RaftFloatingPillEdge.Right,
) {
    val activeTab: RaftAppBrowserTab?
        get() = activeTabId?.let { id -> tabs.firstOrNull { it.tabId == id } }

    val suspendedTabs: List<RaftAppBrowserTab>
        get() = if (activeTabId == null) tabs else tabs.filter { it.tabId != activeTabId }

    /** The edge-docked floating pill shows across Home & Chat whenever ≥1 tab is open and none is foreground. */
    val showFloatingPill: Boolean
        get() = activeTabId == null && tabs.isNotEmpty()

    val liveCount: Int
        get() = tabs.count { it.isLive }
}

/** Result returned when opening or activating an installed Raft App tab. */
internal data class RaftAppBrowserOpenResult(
    val tab: RaftAppBrowserTab,
    val reusedExisting: Boolean,
    val evictedTab: RaftAppBrowserTab? = null,
    val hibernatedTabs: List<RaftAppBrowserTab> = emptyList(),
)

/**
 * Process-wide Option B multi-page Raft App tab coordinator (#proj-mobile:#398 / #proj-workspace:#412).
 *
 * Uses [MutableStateFlow] (never top-level Compose snapshot state) so background threads such as
 * sign-out storage clearing or Android/iOS memory trim hooks can safely update or clear it.
 */
internal class RaftMobileWebPanelCoordinator(
    private val maxTabs: Int = RAFT_APP_BROWSER_MAX_TABS,
    private val livePoolSize: Int = RAFT_APP_BROWSER_LIVE_POOL_SIZE,
    private val releaseEntryToken: (token: String, owner: String) -> Unit = { token, owner ->
        RaftAppEntryRegistry.process.release(token, owner)
    },
    private val evictKeepAlive: (keepAliveKey: String) -> Unit = ::raftWebViewKeepAliveEvict,
) {
    init {
        require(maxTabs >= 1) { "maxTabs must be >= 1" }
        require(livePoolSize in 1..maxTabs) { "livePoolSize must be in 1..maxTabs" }
    }

    private val lock = SynchronizedObject()
    private var nextTabId = 0L
    private var lruSeq = 0L
    private val _state = MutableStateFlow(RaftAppBrowserTabsState())
    val state: StateFlow<RaftAppBrowserTabsState> = _state.asStateFlow()

    /**
     * Opens an installed Raft App page (or activates an existing tab with the exact same URL &
     * scope origin), enforcing the [maxTabs] cap (evicting oldest suspended tab) and the
     * [livePoolSize] LRU warm WebView cap (hibernating oldest non-active live tabs).
     */
    fun openOrActivateApp(
        launchUrl: String,
        appEntryToken: String,
        request: RaftAppEntryRequest,
        accountId: String? = null,
        serverId: String? = null,
        environmentKey: String? = null,
    ): RaftAppBrowserOpenResult {
        val cleanUrl = launchUrl.trim()
        val cleanServerId = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: request.preferredServerId?.trim()?.takeIf { it.isNotEmpty() }
        var redundantToken: String? = null
        var evicted: RaftAppBrowserTab? = null
        var hibernated: List<RaftAppBrowserTab> = emptyList()
        val result: RaftAppBrowserOpenResult =
            synchronized(lock) {
                val current = _state.value
                val existing =
                    current.tabs.firstOrNull { tab ->
                        tab.scopeOrigin == request.scopeOrigin &&
                            (tab.currentUrl == cleanUrl || tab.launchUrl == cleanUrl)
                    }
                lruSeq += 1L
                val nowSeq = lruSeq
                if (existing != null) {
                    if (appEntryToken != existing.appEntryToken) {
                        redundantToken = appEntryToken
                    }
                    val promoted =
                        existing.copy(
                            appName = request.appName?.trim()?.takeIf { it.isNotEmpty() } ?: existing.appName,
                            appLogoUrl = request.appLogoUrl?.trim()?.takeIf { it.isNotEmpty() } ?: existing.appLogoUrl,
                            preferredServerId = existing.preferredServerId ?: cleanServerId,
                            accountId = existing.accountId ?: accountId,
                            serverId = existing.serverId ?: cleanServerId,
                            environmentKey = existing.environmentKey ?: environmentKey,
                            isLive = true,
                            lastUsedSeq = nowSeq,
                        )
                    val remaining = current.tabs.filterNot { it.tabId == existing.tabId }
                    val (rebalanced, newlyHibernated) = rebalanceLiveLru(listOf(promoted) + remaining, activeTabId = promoted.tabId)
                    hibernated = newlyHibernated
                    _state.value = current.copy(tabs = rebalanced, activeTabId = promoted.tabId)
                    val finalTab = rebalanced.first { it.tabId == promoted.tabId }
                    RaftAppBrowserOpenResult(
                        tab = finalTab,
                        reusedExisting = true,
                        evictedTab = null,
                        hibernatedTabs = newlyHibernated,
                    )
                } else {
                    val working = current.tabs.toMutableList()
                    if (working.size >= maxTabs) {
                        val candidateToEvict =
                            working
                                .filter { it.tabId != current.activeTabId }
                                .minByOrNull { it.lastUsedSeq }
                                ?: working.minByOrNull { it.lastUsedSeq }
                        if (candidateToEvict != null) {
                            working.removeAll { it.tabId == candidateToEvict.tabId }
                            evicted = candidateToEvict
                        }
                    }
                    nextTabId += 1L
                    val created =
                        RaftAppBrowserTab(
                            tabId = nextTabId,
                            launchUrl = cleanUrl,
                            currentUrl = cleanUrl,
                            pageTitle = null,
                            appEntryToken = appEntryToken,
                            scopeOrigin = request.scopeOrigin,
                            appName = request.appName?.trim()?.takeIf { it.isNotEmpty() },
                            appLogoUrl = request.appLogoUrl?.trim()?.takeIf { it.isNotEmpty() },
                            preferredServerId = cleanServerId,
                            accountId = accountId,
                            serverId = cleanServerId,
                            environmentKey = environmentKey,
                            isLive = true,
                            lastUsedSeq = nowSeq,
                        )
                    val (rebalanced, newlyHibernated) = rebalanceLiveLru(listOf(created) + working, activeTabId = created.tabId)
                    hibernated = newlyHibernated
                    _state.value = current.copy(tabs = rebalanced, activeTabId = created.tabId)
                    val finalTab = rebalanced.first { it.tabId == created.tabId }
                    RaftAppBrowserOpenResult(
                        tab = finalTab,
                        reusedExisting = false,
                        evictedTab = evicted,
                        hibernatedTabs = newlyHibernated,
                    )
                }
            }
        redundantToken?.let { token -> releaseEntryToken(token, "") }
        evicted?.let(::releaseTabResources)
        hibernated.forEach { tab -> evictKeepAlive(tab.keepAliveKey) }
        return result
    }

    /** Activates an existing open tab (resuming from floating pill or switching via `[N]` sheet). */
    fun activateTab(tabId: Long): RaftAppBrowserTab? {
        var hibernated: List<RaftAppBrowserTab> = emptyList()
        val activated =
            synchronized(lock) {
                val current = _state.value
                val target = current.tabs.firstOrNull { it.tabId == tabId } ?: return@synchronized null
                lruSeq += 1L
                val promoted = target.copy(isLive = true, lastUsedSeq = lruSeq)
                val remaining = current.tabs.filterNot { it.tabId == tabId }
                val (rebalanced, newlyHibernated) = rebalanceLiveLru(listOf(promoted) + remaining, activeTabId = tabId)
                hibernated = newlyHibernated
                _state.value = current.copy(tabs = rebalanced, activeTabId = tabId)
                rebalanced.first { it.tabId == tabId }
            }
        hibernated.forEach { tab -> evictKeepAlive(tab.keepAliveKey) }
        return activated
    }

    /** Suspends [tabId] into the right-edge floating pill without destroying its live WebView. */
    fun suspendTab(tabId: Long): Boolean =
        synchronized(lock) {
            val current = _state.value
            if (current.activeTabId != tabId) return@synchronized false
            _state.value = current.copy(activeTabId = null)
            true
        }

    /** Suspends whichever tab is currently in the foreground into the right-edge floating pill. */
    fun suspendActive(): Boolean =
        synchronized(lock) {
            val current = _state.value
            if (current.activeTabId == null) return@synchronized false
            _state.value = current.copy(activeTabId = null)
            true
        }

    fun tabById(tabId: Long): RaftAppBrowserTab? =
        synchronized(lock) {
            _state.value.tabs.firstOrNull { it.tabId == tabId }
        }

    /**
     * Returns `true` iff [keepAliveKey] belongs to an open tab that is still in the live LRU pool.
     * Used by Android/iOS `onRelease` hooks so explicitly closed or hibernated tabs are destroyed
     * immediately instead of being re-parked.
     */
    fun isKeepAliveEligible(keepAliveKey: String): Boolean =
        synchronized(lock) {
            _state.value.tabs.any { it.keepAliveKey == keepAliveKey && it.isLive }
        }

    fun updateTabNavigation(
        tabId: Long,
        currentUrl: String,
        pageTitle: String? = null,
    ) {
        val cleanUrl = currentUrl.trim()
        val cleanTitle = pageTitle?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanUrl.isEmpty() && cleanTitle == null) return
        synchronized(lock) {
            val current = _state.value
            val index = current.tabs.indexOfFirst { it.tabId == tabId }
            if (index < 0) return@synchronized
            val tab = current.tabs[index]
            val nextUrl = cleanUrl.ifEmpty { tab.currentUrl }
            val nextTitle = cleanTitle ?: tab.pageTitle
            if (tab.currentUrl == nextUrl && tab.pageTitle == nextTitle) return@synchronized
            val updated = current.tabs.toMutableList()
            updated[index] = tab.copy(currentUrl = nextUrl, pageTitle = nextTitle)
            _state.value = current.copy(tabs = updated)
        }
    }

    /** Closes [tabId], destroying its keep-alive WebView and releasing its entry token. */
    fun closeTab(tabId: Long): Boolean {
        val removed =
            synchronized(lock) {
                val current = _state.value
                val target = current.tabs.firstOrNull { it.tabId == tabId } ?: return@synchronized null
                val nextTabs = current.tabs.filterNot { it.tabId == tabId }
                val nextActive = if (current.activeTabId == tabId) null else current.activeTabId
                _state.value = current.copy(tabs = nextTabs, activeTabId = nextActive)
                target
            } ?: return false
        releaseTabResources(removed)
        return true
    }

    /** Closes all open tabs, evicting all parked WebViews and releasing all entry tokens. */
    fun closeAll() {
        val removed =
            synchronized(lock) {
                val current = _state.value
                if (current.tabs.isEmpty() && current.activeTabId == null) return@synchronized emptyList()
                _state.value = current.copy(tabs = emptyList(), activeTabId = null)
                current.tabs
            }
        removed.forEach(::releaseTabResources)
    }

    /** Alias of [closeAll] used by logout / storage-clear cleanup hooks. */
    fun clearAll() = closeAll()

    /**
     * Hibernates all background (suspended) live tabs on OS memory pressure (`onTrimMemory` /
     * `UIApplicationDidReceiveMemoryWarningNotification`) while keeping the currently visible
     * foreground tab untouched. Hibernated tabs keep their URL/title/App metadata and reload on resume.
     */
    fun hibernateBackgroundTabs(): List<RaftAppBrowserTab> {
        val hibernated =
            synchronized(lock) {
                val current = _state.value
                val toHibernate = current.tabs.filter { it.isLive && it.tabId != current.activeTabId }
                if (toHibernate.isEmpty()) return@synchronized emptyList()
                val hibernateIds = toHibernate.mapTo(HashSet()) { it.tabId }
                val nextTabs =
                    current.tabs.map { tab ->
                        if (tab.tabId in hibernateIds) {
                            tab.copy(isLive = false, loadedBaseUrl = tab.resumeUrl)
                        } else {
                            tab
                        }
                    }
                _state.value = current.copy(tabs = nextTabs)
                toHibernate
            }
        hibernated.forEach { tab -> evictKeepAlive(tab.keepAliveKey) }
        return hibernated
    }

    /** Marks a non-foreground tab hibernated if its WebView renderer died while parked. */
    fun onTabContentProcessGone(tabId: Long) {
        val hibernated =
            synchronized(lock) {
                val current = _state.value
                if (current.activeTabId == tabId) return@synchronized null
                val target = current.tabs.firstOrNull { it.tabId == tabId && it.isLive } ?: return@synchronized null
                _state.value =
                    current.copy(
                        tabs =
                            current.tabs.map { tab ->
                                if (tab.tabId == tabId) {
                                    tab.copy(isLive = false, loadedBaseUrl = tab.resumeUrl)
                                } else {
                                    tab
                                }
                            },
                    )
                target
            }
        hibernated?.let { evictKeepAlive(it.keepAliveKey) }
    }

    /**
     * Synchronizes coordinator state when the platform WebView pool drops a parked tab (e.g.
     * renderer process gone, load error while parked, or light/dark theme switch), so the switcher
     * marks it Hibernated and its next resume loads [RaftAppBrowserTab.resumeUrl].
     */
    fun onTabKeepAliveDropped(keepAliveKey: String) {
        val cleanKey = keepAliveKey.trim()
        if (cleanKey.isEmpty()) return
        synchronized(lock) {
            val current = _state.value
            val target =
                current.tabs.firstOrNull { it.keepAliveKey == cleanKey && it.tabId != current.activeTabId && it.isLive }
                    ?: return@synchronized
            _state.value =
                current.copy(
                    tabs =
                        current.tabs.map { tab ->
                            if (tab.tabId == target.tabId) {
                                tab.copy(isLive = false, loadedBaseUrl = tab.resumeUrl)
                            } else {
                                tab
                            }
                        },
                )
        }
    }

    /**
     * Clears every open tab on sign-out, account switch, server switch, or environment switch
     * so no App session or floating pill ever crosses account/server boundaries.
     */
    fun onSessionContextChanged(
        accountId: String?,
        serverId: String?,
        environmentKey: String?,
    ) {
        val cleanAccount = accountId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanServer = serverId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanEnv = environmentKey?.trim()?.takeIf { it.isNotEmpty() }
        val removed =
            synchronized(lock) {
                val current = _state.value
                if (current.tabs.isEmpty()) return@synchronized emptyList()
                val shouldClear =
                    current.tabs.any { tab ->
                        val accountMismatch = tab.accountId != null && tab.accountId != cleanAccount
                        val signedOut = tab.accountId != null && cleanAccount == null
                        val serverMismatch =
                            tab.serverId != null &&
                                cleanServer != null &&
                                tab.serverId != cleanServer
                        val envMismatch =
                            tab.environmentKey != null &&
                                cleanEnv != null &&
                                tab.environmentKey != cleanEnv
                        accountMismatch || signedOut || serverMismatch || envMismatch
                    }
                if (shouldClear) {
                    _state.value = current.copy(tabs = emptyList(), activeTabId = null)
                    return@synchronized current.tabs
                }
                val needsBackfill =
                    current.tabs.any { tab ->
                        (tab.accountId == null && cleanAccount != null) ||
                            (tab.serverId == null && cleanServer != null) ||
                            (tab.environmentKey == null && cleanEnv != null)
                    }
                if (needsBackfill) {
                    _state.value =
                        current.copy(
                            tabs =
                                current.tabs.map { tab ->
                                    tab.copy(
                                        accountId = tab.accountId ?: cleanAccount,
                                        serverId = tab.serverId ?: cleanServer,
                                        environmentKey = tab.environmentKey ?: cleanEnv,
                                    )
                                },
                        )
                }
                emptyList()
            }
        removed.forEach(::releaseTabResources)
    }

    /** Closes any open App tab whose origin or App name was dropped from rail places (#16960). */
    fun closeByDroppedRailApp(origin: String?, appName: String?) {
        val cleanOrigin = origin?.trim()?.takeIf { it.isNotEmpty() }
        val cleanName = appName?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanOrigin == null && cleanName == null) return
        val removed =
            synchronized(lock) {
                val current = _state.value
                val matching =
                    current.tabs.filter { tab ->
                        (cleanOrigin != null && tab.scopeOrigin == cleanOrigin) ||
                            (cleanName != null && tab.appName == cleanName)
                    }
                if (matching.isEmpty()) return@synchronized emptyList()
                val removeIds = matching.mapTo(HashSet()) { it.tabId }
                val nextTabs = current.tabs.filterNot { it.tabId in removeIds }
                val nextActive = current.activeTabId?.takeUnless { it in removeIds }
                _state.value = current.copy(tabs = nextTabs, activeTabId = nextActive)
                matching
            }
        removed.forEach(::releaseTabResources)
    }

    /** Persists the user's vertical dock fraction for the magnetic floating pill. */
    fun setPillYFraction(yFraction: Float) {
        val clamped = if (yFraction.isFinite()) yFraction.coerceIn(0f, 1f) else RAFT_FLOATING_PILL_DEFAULT_Y_FRACTION
        synchronized(lock) {
            val current = _state.value
            if (current.pillYFraction == clamped) return@synchronized
            _state.value = current.copy(pillYFraction = clamped)
        }
    }

    /** Persists the user's physical horizontal dock edge for the magnetic floating pill. */
    fun setPillEdge(edge: RaftFloatingPillEdge) {
        synchronized(lock) {
            val current = _state.value
            if (current.pillEdge == edge) return@synchronized
            _state.value = current.copy(pillEdge = edge)
        }
    }

    /** Atomically persists both the docked edge and vertical fraction when a floating pill drag settles. */
    fun setPillDockPosition(
        edge: RaftFloatingPillEdge,
        yFraction: Float,
    ) {
        val clampedY = if (yFraction.isFinite()) yFraction.coerceIn(0f, 1f) else RAFT_FLOATING_PILL_DEFAULT_Y_FRACTION
        synchronized(lock) {
            val current = _state.value
            if (current.pillEdge == edge && current.pillYFraction == clampedY) return@synchronized
            _state.value = current.copy(pillEdge = edge, pillYFraction = clampedY)
        }
    }

    private fun rebalanceLiveLru(
        orderedTabs: List<RaftAppBrowserTab>,
        activeTabId: Long?,
    ): Pair<List<RaftAppBrowserTab>, List<RaftAppBrowserTab>> {
        val mruSorted = orderedTabs.sortedByDescending { it.lastUsedSeq }
        val keepLiveIds = LinkedHashSet<Long>(livePoolSize)
        if (activeTabId != null && mruSorted.any { it.tabId == activeTabId }) {
            keepLiveIds += activeTabId
        }
        for (tab in mruSorted) {
            if (keepLiveIds.size >= livePoolSize) break
            if (tab.isLive) keepLiveIds += tab.tabId
        }
        val newlyHibernated = mutableListOf<RaftAppBrowserTab>()
        val updated =
            mruSorted.map { tab ->
                val shouldStayLive = tab.tabId in keepLiveIds
                if (tab.isLive && !shouldStayLive) {
                    val hibernatedTab = tab.copy(isLive = false, loadedBaseUrl = tab.resumeUrl)
                    newlyHibernated += hibernatedTab
                    hibernatedTab
                } else if (!tab.isLive && shouldStayLive) {
                    tab.copy(isLive = true)
                } else {
                    tab
                }
            }
        return updated to newlyHibernated
    }

    private fun releaseTabResources(tab: RaftAppBrowserTab) {
        releaseEntryToken(tab.appEntryToken, tab.entryOwnerId)
        releaseEntryToken(tab.appEntryToken, "")
        evictKeepAlive(tab.keepAliveKey)
    }

    companion object {
        val process: RaftMobileWebPanelCoordinator = RaftMobileWebPanelCoordinator()
    }
}
