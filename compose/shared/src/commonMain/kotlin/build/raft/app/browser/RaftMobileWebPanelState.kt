package build.raft.app

import build.raft.app.data.RaftAvatarCacheSubject
import build.raft.app.markdown.raftAppLinkAsciiHost
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Unified bottom-rising mobile web panel state, pure rules, and process-wide single kept-alive page
 * coordinator (#proj-workspace:#397, Option A per Kirby's mobile web panel spec).
 */

/** Which storage partition a mobile web panel / in-app browser launch uses (#proj-workspace:#397). */
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
 * - Layer 2 Raft App entry (`isRaftAppEntry == true`) -> [RaftWebViewStoragePartition.Default].
 * - Layer 3 third-party ordinary link -> [RaftWebViewStoragePartition.IsolatedThirdParty] when
 *   [supportsIsolatedThirdPartyProfile] is true, else [RaftMobileWebPanelLaunchResolution.FailClosedToSystemBrowser].
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
 * Formats the Layer 3 third-party host label for the minimal bottom bar and keep-open chip:
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

/** Actions offered in the bottom bar's `⋮` overflow menu. */
internal enum class RaftMobileWebPanelMenuAction {
    Reload,
    ForwardToRaft,
    Share,
    CopyLink,
    OpenInBrowser,
}

/**
 * Overflow menu items by layer (#proj-mobile:#409: both Layer 2 Raft Apps and Layer 3 third-party
 * pages offer Open in browser so users can always hand off to their system browser).
 */
internal fun raftMobileWebPanelMenuActions(isRaftApp: Boolean): List<RaftMobileWebPanelMenuAction> =
    listOf(
        RaftMobileWebPanelMenuAction.Reload,
        RaftMobileWebPanelMenuAction.ForwardToRaft,
        RaftMobileWebPanelMenuAction.Share,
        RaftMobileWebPanelMenuAction.CopyLink,
        RaftMobileWebPanelMenuAction.OpenInBrowser,
    )

/** The panel's vertical detents (Option A: 88% default, 100% fullscreen, or collapsed keeping page alive). */
internal enum class RaftMobileWebPanelDetent {
    Default88,
    Fullscreen,
    Collapsed,
}

internal const val RAFT_MOBILE_WEB_PANEL_DEFAULT_HEIGHT_FRACTION: Float = 0.88f

/**
 * Computes the backdrop scrim alpha for the mobile web panel overlay.
 *
 * Tracks [slideProgress] cleanly from `0f` (hidden) to `1f` (fully expanded at both Default88 and
 * Fullscreen detents) so the opaque canvas backdrop completely masks the underlying conversation
 * header behind the 1-line source strip and top safe area.
 */
internal fun resolveRaftMobileWebPanelBackdropAlpha(slideProgress: Float): Float =
    slideProgress.coerceIn(0f, 1f)

/** Target detent after releasing a vertical drag on the panel handle/bar. */
internal fun resolveRaftMobileWebPanelDragTarget(
    current: RaftMobileWebPanelDetent,
    dragDeltaPx: Float,
    velocityYPxPerSec: Float,
    viewportHeightPx: Float,
): RaftMobileWebPanelDetent {
    val safeHeight = viewportHeightPx.coerceAtLeast(1f)
    val stepThresholdPx = (safeHeight * 0.08f).coerceIn(48f, 120f)
    val flingVelocityThreshold = 600f
    return when (current) {
        RaftMobileWebPanelDetent.Default88 ->
            when {
                dragDeltaPx <= -stepThresholdPx || velocityYPxPerSec <= -flingVelocityThreshold ->
                    RaftMobileWebPanelDetent.Fullscreen
                dragDeltaPx >= stepThresholdPx || velocityYPxPerSec >= flingVelocityThreshold ->
                    RaftMobileWebPanelDetent.Collapsed
                else -> RaftMobileWebPanelDetent.Default88
            }
        RaftMobileWebPanelDetent.Fullscreen ->
            if (dragDeltaPx >= stepThresholdPx || velocityYPxPerSec >= flingVelocityThreshold) {
                RaftMobileWebPanelDetent.Default88
            } else {
                RaftMobileWebPanelDetent.Fullscreen
            }
        RaftMobileWebPanelDetent.Collapsed -> RaftMobileWebPanelDetent.Collapsed
    }
}

/** Android system Back / edge-swipe resolution while the mobile web panel is hosted. */
internal enum class RaftMobileWebPanelBackAction {
    WebGoBack,
    CollapseKeepingAlive,
    Ignore,
}

/**
 * Resolves Android system Back / edge swipe for the mobile web panel:
 * - While expanded and the page has web history (`canGoBack == true`): go back in WebView.
 * - While expanded at the first history entry (`canGoBack == false`): collapse back to the
 *   conversation keeping the WebView alive.
 * - While collapsed: do not intercept Back.
 */
internal fun resolveRaftMobileWebPanelBackAction(
    detent: RaftMobileWebPanelDetent,
    canGoBack: Boolean,
): RaftMobileWebPanelBackAction =
    when (detent) {
        RaftMobileWebPanelDetent.Collapsed -> RaftMobileWebPanelBackAction.Ignore
        RaftMobileWebPanelDetent.Default88,
        RaftMobileWebPanelDetent.Fullscreen ->
            if (canGoBack) {
                RaftMobileWebPanelBackAction.WebGoBack
            } else {
                RaftMobileWebPanelBackAction.CollapseKeepingAlive
            }
    }

/** Where the panel was opened from, rendered in the dimmed 1-line top strip at the 88% detent. */
internal sealed interface RaftMobileWebPanelSource {
    data class Message(
        val messageId: String,
        val authorName: String,
        val authorAvatarKind: RaftAvatarKind,
        val authorAvatarWireValue: String? = null,
        val authorCacheSubject: RaftAvatarCacheSubject? = null,
        val previewText: String,
        val timestampLabel: String,
    ) : RaftMobileWebPanelSource

    data class Surface(
        val label: String,
    ) : RaftMobileWebPanelSource
}

/** The single active or collapsed kept-alive mobile web panel session across the process. */
internal data class RaftMobileWebPanelSession(
    val sessionId: Long,
    val ownerHostId: String,
    val launchUrl: String,
    val currentUrl: String,
    val appEntryToken: String?,
    val appName: String?,
    val appLogoUrl: String?,
    val isRaftApp: Boolean,
    val source: RaftMobileWebPanelSource?,
    val detent: RaftMobileWebPanelDetent,
    val accountId: String? = null,
    val environmentKey: String? = null,
) {
    val isExpanded: Boolean
        get() = detent != RaftMobileWebPanelDetent.Collapsed

    val isCollapsedKeptAlive: Boolean
        get() = detent == RaftMobileWebPanelDetent.Collapsed

    /** Label shown in the keep-open chip: App name for Layer 2, or dynamic formatted host for Layer 3. */
    val keepOpenLabel: String
        get() =
            if (isRaftApp && !appName.isNullOrBlank()) {
                appName
            } else {
                raftMobileWebPanelDisplayHost(currentUrl.ifBlank { launchUrl })
            }
}

/**
 * Process-wide single kept-alive web panel coordinator (#proj-workspace:#397).
 *
 * Uses [MutableStateFlow] (never top-level Compose snapshot state) so background threads such as
 * sign-out storage clearing can safely clear it without touching a Compose snapshot.
 *
 * Invariants:
 * - Strictly at most 1 kept-alive page exists at any time; opening any new link replaces the
 *   previous session and immediately releases any previous [RaftAppEntryRegistry] token.
 * - Collapsing keeps the session (and its live WebView) alive and surfaces the keep-open chip.
 * - Closing (`×`), replacing, account/environment switches, sign-out, or low-memory reclamation
 *   destroys the session, releases its entry token, and removes the keep-open chip.
 */
internal class RaftMobileWebPanelCoordinator(
    private val releaseEntryToken: (token: String, owner: String) -> Unit = { token, owner ->
        RaftAppEntryRegistry.process.release(token, owner)
    },
) {
    private val lock = SynchronizedObject()
    private var nextSessionId = 0L
    private val _session = MutableStateFlow<RaftMobileWebPanelSession?>(null)
    val session: StateFlow<RaftMobileWebPanelSession?> = _session.asStateFlow()

    fun open(
        ownerHostId: String,
        launchUrl: String,
        appEntryToken: String? = null,
        appName: String? = null,
        appLogoUrl: String? = null,
        isRaftApp: Boolean = appEntryToken != null,
        source: RaftMobileWebPanelSource? = null,
        accountId: String? = null,
        environmentKey: String? = null,
    ): RaftMobileWebPanelSession {
        var previous: RaftMobileWebPanelSession? = null
        val created =
            synchronized(lock) {
                previous = _session.value
                nextSessionId += 1L
                val next =
                    RaftMobileWebPanelSession(
                        sessionId = nextSessionId,
                        ownerHostId = ownerHostId,
                        launchUrl = launchUrl,
                        currentUrl = launchUrl,
                        appEntryToken = appEntryToken,
                        appName = appName?.trim()?.takeIf { it.isNotEmpty() },
                        appLogoUrl = appLogoUrl?.trim()?.takeIf { it.isNotEmpty() },
                        isRaftApp = isRaftApp,
                        source = source,
                        detent = RaftMobileWebPanelDetent.Default88,
                        accountId = accountId,
                        environmentKey = environmentKey,
                    )
                _session.value = next
                next
            }
        previous?.let(::releaseTokenIfPresent)
        return created
    }

    fun collapse(sessionId: Long): Boolean = setDetent(sessionId, RaftMobileWebPanelDetent.Collapsed)

    fun expand(
        sessionId: Long,
        detent: RaftMobileWebPanelDetent = RaftMobileWebPanelDetent.Default88,
    ): Boolean {
        val target = if (detent == RaftMobileWebPanelDetent.Collapsed) RaftMobileWebPanelDetent.Default88 else detent
        return setDetent(sessionId, target)
    }

    fun setDetent(sessionId: Long, detent: RaftMobileWebPanelDetent): Boolean {
        return synchronized(lock) {
            val current = _session.value ?: return@synchronized false
            if (current.sessionId != sessionId) return@synchronized false
            if (current.detent == detent) return@synchronized true
            _session.value = current.copy(detent = detent)
            true
        }
    }

    fun updateCurrentUrl(sessionId: Long, url: String) {
        val clean = url.trim()
        if (clean.isEmpty()) return
        synchronized(lock) {
            val current = _session.value ?: return@synchronized
            if (current.sessionId != sessionId || current.currentUrl == clean) return@synchronized
            _session.value = current.copy(currentUrl = clean)
        }
    }

    fun close(sessionId: Long): Boolean {
        val removed =
            synchronized(lock) {
                val current = _session.value ?: return@synchronized null
                if (current.sessionId != sessionId) return@synchronized null
                _session.value = null
                current
            }
        removed?.let(::releaseTokenIfPresent)
        return removed != null
    }

    fun onAccountOrEnvironmentChanged(accountId: String?, environmentKey: String?) {
        val removed =
            synchronized(lock) {
                val current = _session.value ?: return@synchronized null
                val accountChanged = current.accountId != null && current.accountId != accountId
                val signedOut = current.accountId != null && accountId == null
                val envChanged =
                    current.environmentKey != null &&
                        environmentKey != null &&
                        current.environmentKey != environmentKey
                if (accountChanged || signedOut || envChanged) {
                    _session.value = null
                    return@synchronized current
                }
                if ((current.accountId == null && accountId != null) ||
                    (current.environmentKey == null && environmentKey != null)
                ) {
                    _session.value =
                        current.copy(
                            accountId = current.accountId ?: accountId,
                            environmentKey = current.environmentKey ?: environmentKey,
                        )
                }
                null
            }
        removed?.let(::releaseTokenIfPresent)
    }

    fun onHostDisposed(ownerHostId: String) {
        val removed =
            synchronized(lock) {
                val current = _session.value ?: return@synchronized null
                if (current.ownerHostId != ownerHostId) return@synchronized null
                _session.value = null
                current
            }
        removed?.let(::releaseTokenIfPresent)
    }

    fun clearCollapsed() {
        val removed =
            synchronized(lock) {
                val current = _session.value ?: return@synchronized null
                if (!current.isCollapsedKeptAlive) return@synchronized null
                _session.value = null
                current
            }
        removed?.let(::releaseTokenIfPresent)
    }

    fun clearAll() {
        val removed =
            synchronized(lock) {
                val current = _session.value
                _session.value = null
                current
            }
        removed?.let(::releaseTokenIfPresent)
    }

    private fun releaseTokenIfPresent(session: RaftMobileWebPanelSession) {
        val token = session.appEntryToken ?: return
        releaseEntryToken(token, session.ownerHostId)
    }

    companion object {
        val process: RaftMobileWebPanelCoordinator = RaftMobileWebPanelCoordinator()
    }
}
