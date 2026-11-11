package build.raft.app

import build.raft.app.resources.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import build.raft.app.base.BridgeModule
import build.raft.app.base.Utils
import build.raft.app.data.KeyValueLinkOpenPreferenceStore
import build.raft.app.data.LinkOpenMode
import build.raft.app.data.PersistentLinkOpenPreferenceRepository
import build.raft.app.data.RaftClientConfigAppHosts
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.parseArtifactOpenAddress
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager

internal enum class RaftLinkOpenIntent {
    Ordinary,
    ForceExternal,

    /**
     * A page of Raft's own web the app has no screen for (an announcement's `/apps`, or Settings
     * of a server the account is not on). The link setting still decides where it opens, but "Default
     * browser" asks the host for a real browser (`browser: true`): Raft claims its own origin as
     * Android App Links / iOS Universal Links, so a plain VIEW of it can route straight back into
     * the app, which has nothing to show for it.
     */
    RaftWebPage,
}

internal enum class RaftLinkOpenTarget {
    InAppBrowser,
    ExternalBrowser,
    Rejected,

    /** Raft's own Artifact viewer ([RaftLinkNavigator]'s Artifact address); never a decision of [resolveRaftLinkOpenDecision]. */
    ArtifactViewer,
}

internal enum class RaftLinkRejectReason {
    Blank,
    Invalid,
    UnsupportedScheme,
    RouteUnavailable,
}

internal data class RaftLinkOpenDecision(
    val target: RaftLinkOpenTarget,
    val url: String = "",
    val rejectReason: RaftLinkRejectReason? = null,
)

internal enum class RaftInAppBrowserRouteScope {
    InheritShell,
    Unscoped,
}

internal fun resolveRaftLinkOpenDecision(
    rawUrl: String,
    mode: LinkOpenMode,
    intent: RaftLinkOpenIntent = RaftLinkOpenIntent.Ordinary,
): RaftLinkOpenDecision {
    val url = rawUrl.trim()
    if (url.isBlank()) return rejectedLink(RaftLinkRejectReason.Blank)
    if (url.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) {
        return rejectedLink(RaftLinkRejectReason.Invalid)
    }

    val scheme = url.raftAbsoluteSchemeOrNull()
        ?: return rejectedLink(RaftLinkRejectReason.Invalid)
    return when (scheme) {
        "http", "https" -> {
            if (!url.hasValidHttpAuthority(scheme)) {
                rejectedLink(RaftLinkRejectReason.Invalid)
            } else if (intent == RaftLinkOpenIntent.ForceExternal || mode == LinkOpenMode.SystemBrowser) {
                RaftLinkOpenDecision(RaftLinkOpenTarget.ExternalBrowser, url)
            } else {
                RaftLinkOpenDecision(RaftLinkOpenTarget.InAppBrowser, url)
            }
        }
        "mailto", "tel" -> {
            if (url.substringAfter(':', missingDelimiterValue = "").isBlank()) {
                rejectedLink(RaftLinkRejectReason.Invalid)
            } else {
                RaftLinkOpenDecision(RaftLinkOpenTarget.ExternalBrowser, url)
            }
        }
        else -> rejectedLink(RaftLinkRejectReason.UnsupportedScheme)
    }
}

private fun rejectedLink(reason: RaftLinkRejectReason): RaftLinkOpenDecision =
    RaftLinkOpenDecision(
        target = RaftLinkOpenTarget.Rejected,
        rejectReason = reason,
    )

private fun String.raftAbsoluteSchemeOrNull(): String? {
    val colonIndex = indexOf(':')
    if (colonIndex <= 0) return null
    val candidate = substring(0, colonIndex)
    if (!candidate.first().isLetter()) return null
    if (!candidate.drop(1).all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return null
    return candidate.lowercase()
}

private fun String.hasValidHttpAuthority(scheme: String): Boolean {
    val prefix = "$scheme://"
    if (!startsWith(prefix, ignoreCase = true)) return false
    val authority = substring(prefix.length).substringBefore('/').substringBefore('?').substringBefore('#')
    if (authority.isBlank() || '@' in authority) return false
    if (authority.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) return false
    if (authority.startsWith('[')) {
        val close = authority.indexOf(']')
        if (close <= 1) return false
        val literal = authority.substring(1, close)
        val literalIsValid =
            ':' in literal &&
                literal.all { char ->
                    char == ':' || char == '.' || char.isDigit() || char.lowercaseChar() in 'a'..'f'
                }
        if (!literalIsValid) return false
        val remainder = authority.substring(close + 1)
        return remainder.isEmpty() || remainder.validPortSuffix()
    }
    if ('[' in authority || ']' in authority || '\\' in authority) return false
    if (authority.count { it == ':' } > 1) return false
    val host = authority.substringBefore(':')
    val remainder = authority.removePrefix(host)
    return host.isNotBlank() && (remainder.isEmpty() || remainder.validPortSuffix())
}

private fun String.validPortSuffix(): Boolean =
    startsWith(':') && drop(1).toIntOrNull()?.let { it in 1..65535 } == true

internal fun raftInAppBrowserDisplayUrl(rawUrl: String): String {
    val url = rawUrl.trim()
    val queryIndex = url.indexOf('?').takeIf { it >= 0 }
    val fragmentIndex = url.indexOf('#').takeIf { it >= 0 }
    val end = listOfNotNull(queryIndex, fragmentIndex).minOrNull() ?: url.length
    return url.substring(0, end)
}

internal fun raftInAppBrowserPageData(rawUrl: String): JSONObject? {
    val decision = resolveRaftLinkOpenDecision(rawUrl, LinkOpenMode.InAppBrowser)
    if (decision.target != RaftLinkOpenTarget.InAppBrowser) return null
    return JSONObject()
        .put("pageName", RAFT_IN_APP_BROWSER_PAGE)
        .put(RAFT_IN_APP_BROWSER_URL_KEY, decision.url)
}

internal fun JSONObject.raftInAppBrowserUrlOrNull(): String? =
    raftInAppBrowserPageData(optString(RAFT_IN_APP_BROWSER_URL_KEY))
        ?.optString(RAFT_IN_APP_BROWSER_URL_KEY)
        ?.takeIf { it.isNotBlank() }

internal fun Pager.openRaftInAppBrowserPage(
    rawUrl: String,
    storeScopeOverride: RaftShellStoreScope? = null,
): Boolean {
    val pageData = raftInAppBrowserPageData(rawUrl) ?: return false
    return openRaftPage(
        pageName = RAFT_IN_APP_BROWSER_PAGE,
        pageData = pageData,
        storeScopeOverride = storeScopeOverride,
    )
}

internal fun Pager.openUnscopedRaftInAppBrowserPage(rawUrl: String): Boolean {
    val pageData = raftInAppBrowserPageData(rawUrl) ?: return false
    acquireModule<RouterModule>(RouterModule.MODULE_NAME)
        .openPage(RAFT_IN_APP_BROWSER_PAGE, pageData)
    return true
}

internal class RaftLinkNavigator(
    private val modeProvider: () -> LinkOpenMode,
    private val openInAppBrowser: (String) -> Boolean,
    private val openExternalBrowser: (String) -> Unit,
    private val onRejected: (RaftLinkRejectReason) -> Unit,
    /** [RaftLinkOpenIntent.RaftWebPage] in the default browser: a browser even for a Raft link. */
    private val openRaftWebPageInBrowser: (String) -> Unit = openExternalBrowser,
    /**
     * An ordinary link to an Artifact open over the Artifacts home ([parseArtifactOpenAddress]):
     * true when the in-app viewer opened it; false leaves it to the link setting as any link.
     */
    private val openArtifactAddress: (String) -> Boolean = { false },
) {
    fun open(
        rawUrl: String,
        intent: RaftLinkOpenIntent = RaftLinkOpenIntent.Ordinary,
    ): RaftLinkOpenDecision {
        val decision = resolveRaftLinkOpenDecision(rawUrl, modeProvider(), intent)
        // An Artifact's address opens Raft's own viewer, as an Artifact card does, whatever the setting.
        if (intent == RaftLinkOpenIntent.Ordinary && decision.target != RaftLinkOpenTarget.Rejected && openArtifactAddress(decision.url)) {
            return RaftLinkOpenDecision(RaftLinkOpenTarget.ArtifactViewer, decision.url)
        }
        return when (decision.target) {
            RaftLinkOpenTarget.InAppBrowser -> {
                if (openInAppBrowser(decision.url)) {
                    decision
                } else {
                    onRejected(RaftLinkRejectReason.RouteUnavailable)
                    rejectedLink(RaftLinkRejectReason.RouteUnavailable)
                }
            }
            RaftLinkOpenTarget.ExternalBrowser -> {
                if (intent == RaftLinkOpenIntent.RaftWebPage) {
                    openRaftWebPageInBrowser(decision.url)
                } else {
                    openExternalBrowser(decision.url)
                }
                decision
            }
            RaftLinkOpenTarget.Rejected -> {
                onRejected(requireNotNull(decision.rejectReason))
                decision
            }
            RaftLinkOpenTarget.ArtifactViewer -> decision
        }
    }
}

@Composable
internal fun rememberRaftLinkNavigator(
    pager: Pager,
    bridge: BridgeModule,
    routeScope: RaftInAppBrowserRouteScope = RaftInAppBrowserRouteScope.InheritShell,
): RaftLinkNavigator {
    // Lazy: every page has a navigator (ProvideRaftLinkOpening), few of them open a link.
    val preferenceRepository =
        remember {
            lazy {
                PersistentLinkOpenPreferenceRepository(
                    KeyValueLinkOpenPreferenceStore(raftSharedPreferenceStore())
                )
            }
        }
    val rejectedMessage = stringResource(Res.string.in_app_browser_unable_to_open_link)
    // An app-shell tab's page has no scope of its own: the viewer opens with the tab's ([LocalRaftArtifactOpenStoreScope]).
    val artifactStoreScope = LocalRaftArtifactOpenStoreScope.current
    return remember(pager, bridge, preferenceRepository, routeScope, rejectedMessage, artifactStoreScope) {
        RaftLinkNavigator(
            modeProvider = { effectiveLinkOpenMode(preferenceRepository.value.getLinkOpenModeSync()) },
            openInAppBrowser = { url ->
                when (routeScope) {
                    RaftInAppBrowserRouteScope.InheritShell -> pager.openRaftInAppBrowserPage(url)
                    RaftInAppBrowserRouteScope.Unscoped -> pager.openUnscopedRaftInAppBrowserPage(url)
                }
            },
            openExternalBrowser = { url -> bridge.openUrl(url) },
            onRejected = { reason ->
                bridge.logWarn("link_open_rejected reason=${reason.name.lowercase()}")
                bridge.toast(rejectedMessage)
            },
            openRaftWebPageInBrowser = { url -> bridge.openUrl(url, inBrowser = true) },
            openArtifactAddress = { url ->
                val target = parseArtifactOpenAddress(url, RaftClientConfigAppHosts.current)
                if (target == null) {
                    false
                } else {
                    RaftArtifactOpenTrace.tap("link", target.id)
                    pager.openRaftArtifactViewerPage(target, artifactStoreScope).also { opened ->
                        if (!opened) RaftArtifactOpenTrace.mark("browser_fallback", "reason=no_page_scope")
                    }
                }
            },
        )
    }
}

internal val LocalRaftLinkNavigator = staticCompositionLocalOf<RaftLinkNavigator?> { null }

/**
 * The one way a page opens a link (artin, Hands ff72869f): Compose's [LocalUriHandler] default starts its own
 * VIEW, so anything reading it opened differently from a message link. Every page sits under this (BasePager).
 */
@Composable
internal fun ProvideRaftLinkOpening(pager: Pager, content: @Composable () -> Unit) {
    val bridge = remember(pager) { Utils.bridgeModule(pager.pagerId) }
    val navigator = rememberRaftLinkNavigator(pager, bridge)
    val uriHandler =
        remember(navigator) {
            object : UriHandler {
                override fun openUri(uri: String) {
                    navigator.open(uri)
                }
            }
        }
    CompositionLocalProvider(
        LocalRaftLinkNavigator provides navigator,
        LocalUriHandler provides uriHandler,
        content = content,
    )
}

/** Opens in the system browser whatever the link setting is: where an account's sign-in lives. */
@Composable
internal fun rememberRaftSystemBrowserOpener(): (String) -> Unit {
    val navigator = LocalRaftLinkNavigator.current
    val uriHandler = LocalUriHandler.current
    return remember(navigator, uriHandler) {
        { url ->
            if (navigator != null) navigator.open(url, RaftLinkOpenIntent.ForceExternal) else uriHandler.openUri(url)
        }
    }
}

/**
 * The link mode in effect: the saved one where an in-app browser can show a page; the default
 * browser on desktop, which has no embedded web view yet (and no "Open web links" choice).
 */
internal fun effectiveLinkOpenMode(
    saved: LinkOpenMode,
    supportsInAppBrowser: Boolean = raftPlatformSupportsInAppBrowser(),
): LinkOpenMode = if (supportsInAppBrowser) saved else LinkOpenMode.SystemBrowser
