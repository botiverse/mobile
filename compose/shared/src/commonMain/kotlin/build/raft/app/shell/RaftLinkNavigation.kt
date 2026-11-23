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
import build.raft.app.cmp.RaftCmpRouter
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.KeyValueLinkOpenPreferenceStore
import build.raft.app.data.LinkOpenMode
import build.raft.app.data.PersistentLinkOpenPreferenceRepository
import build.raft.app.data.RaftClientConfigAppHosts
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager

internal enum class RaftLinkOpenIntent {
    Ordinary,
    ForceExternal,

    /**
     * A page of Raft's own web the app has no screen for (an announcement's `/apps`, or Settings
     * of a server the account is not on), or one asked for as the web page rather than the app's
     * screen for it (an Artifact's web page: a card's Open in New Page, a viewer fallback,
     * [rememberRaftWebPageOpener]). Never the in-app Artifact viewer. The link setting still decides
     * where it opens, but "Default browser" asks the host for a real browser (`browser: true`): Raft
     * claims its own origin as Android App Links / iOS Universal Links, so a plain VIEW of it can
     * route straight back into the app, which is not what was asked for.
     */
    RaftWebPage,

    /**
     * A page of Raft's own web that needs a real browser whatever the link setting: one where the
     * person confirms with a passkey (WebAuthn, which the in-app web view lacks), as Settings ›
     * Account › Login & security's rows. Asked for as a browser (`browser: true`), so Raft's App
     * Links / Universal Links don't bring it back into the app ([rememberRaftPasskeyWebPageOpener]).
     */
    RaftWebPageInBrowser,
}

internal enum class RaftLinkOpenTarget {
    InAppBrowser,
    ExternalBrowser,
    Rejected,

    /** Raft's own Artifact viewer ([RaftLinkNavigator]'s Artifact address); never a decision of [resolveRaftLinkOpenDecision]. */
    ArtifactViewer,

    /** Raft's own server join page ([RaftLinkNavigator]'s join link); never a decision of [resolveRaftLinkOpenDecision]. */
    ServerJoinPage,

    /**
     * Another of the app's own screens for a Raft link ([RaftLinkNavigator]'s own-link opener, [RaftOwnLinkRoute]:
     * a conversation, a task, an invite), opened by the app shell; never a decision of [resolveRaftLinkOpenDecision].
     */
    InAppDestination,
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
            } else if (
                intent == RaftLinkOpenIntent.ForceExternal ||
                intent == RaftLinkOpenIntent.RaftWebPageInBrowser ||
                mode == LinkOpenMode.SystemBrowser
            ) {
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

internal const val RAFT_IN_APP_BROWSER_APP_ENTRY_KEY = "appEntry"

internal fun raftInAppBrowserPageData(
    rawUrl: String,
    replaceCurrentPage: Boolean = false,
    appEntryToken: String? = null,
): JSONObject? {
    val decision = resolveRaftLinkOpenDecision(rawUrl, LinkOpenMode.InAppBrowser)
    if (decision.target != RaftLinkOpenTarget.InAppBrowser) return null
    return JSONObject().apply {
        put("pageName", RAFT_IN_APP_BROWSER_PAGE)
        put(RAFT_IN_APP_BROWSER_URL_KEY, decision.url)
        if (replaceCurrentPage) put(build.raft.app.nav.RAFT_REPLACE_CURRENT_PAGE, true)
        appEntryToken?.trim()?.takeIf { it.isNotEmpty() }?.let { put(RAFT_IN_APP_BROWSER_APP_ENTRY_KEY, it) }
    }
}

internal fun JSONObject.raftInAppBrowserUrlOrNull(): String? =
    raftInAppBrowserPageData(optString(RAFT_IN_APP_BROWSER_URL_KEY))
        ?.optString(RAFT_IN_APP_BROWSER_URL_KEY)
        ?.takeIf { it.isNotBlank() }

internal fun JSONObject.raftInAppBrowserAppEntryTokenOrNull(): String? =
    optString(RAFT_IN_APP_BROWSER_APP_ENTRY_KEY).trim().takeIf { it.isNotEmpty() }

internal fun Pager.openRaftInAppBrowserPage(
    rawUrl: String,
    storeScopeOverride: RaftShellStoreScope? = null,
    replaceCurrentPage: Boolean = false,
    appEntryToken: String? = null,
): Boolean {
    val pageData =
        raftInAppBrowserPageData(
            rawUrl = rawUrl,
            replaceCurrentPage = replaceCurrentPage,
            appEntryToken = appEntryToken,
        ) ?: return false
    return openRaftPage(
        pageName = RAFT_IN_APP_BROWSER_PAGE,
        pageData = pageData,
        storeScopeOverride = storeScopeOverride,
    )
}

internal fun Pager.openUnscopedRaftInAppBrowserPage(
    rawUrl: String,
    replaceCurrentPage: Boolean = false,
    appEntryToken: String? = null,
): Boolean {
    val pageData =
        raftInAppBrowserPageData(
            rawUrl = rawUrl,
            replaceCurrentPage = replaceCurrentPage,
            appEntryToken = appEntryToken,
        ) ?: return false
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
     * An ordinary link to an Artifact ([raftArtifactLinkOpener]):
     * true when the in-app viewer opened it; false leaves it to the link setting as any link.
     */
    private val openArtifactAddress: (String) -> Boolean = { false },
    /**
     * An ordinary server join link ([raftServerJoinLinkOpener]): true when the app's join page takes
     * it; false leaves it to the link setting as any link.
     */
    private val openServerJoinLink: (String) -> Boolean = { false },
    /**
     * An ordinary link to another Raft page the app has a screen for ([raftOwnLinkShellOpener]: a
     * conversation, message, thread or task link, an external invite): true when the app shell takes it.
     */
    private val openOwnLink: (String) -> Boolean = { false },
    /**
     * Whether an ordinary link is a Raft page the app has no screen for ([raftOwnLinkIsWebPage]): it
     * opens as [RaftLinkOpenIntent.RaftWebPage], a real browser in "Default browser" mode.
     */
    private val isRaftWebPage: (String) -> Boolean = { false },
    /**
     * #proj-mobile:#204: opens a Raft own-origin web page in the authenticated in-app WebView
     * regardless of [LinkOpenMode]. Returning false falls through to the unauthenticated browser path.
     */
    private val openRaftWebPageInApp: (String) -> Boolean = { false },
    /**
     * iOS App Store Guideline 3.1.1: Raft billing, `/servers/<id>/upgrade`, own-host `?upgrade=...`,
     * and Stripe purchase URLs must never open inside an in-app WebView or `SFSafariViewController`.
     */
    private val isAppStoreRestrictedPurchaseUrl: (String) -> Boolean = { false },
    /**
     * Optional iOS App Store 3.1.1 handler for [isAppStoreRestrictedPurchaseUrl] (e.g. redirecting
     * Raft's own billing page to the native read-only Plan & Billing screen). Returning false blocks
     * the navigation without opening any browser or purchase sheet.
     */
    private val openAppStoreRestrictedPurchaseUrl: (String) -> Boolean = { false },
    /** Opens an App entry page with its single-use registry token (#proj-workspace:#356). */
    private val openInAppBrowserWithEntry: (url: String, appEntryToken: String) -> Boolean = { url, _ -> openInAppBrowser(url) },
    /** False on desktop, which has no in-app browser so entries stay ordinary opens. */
    private val supportsInAppBrowser: () -> Boolean = ::raftPlatformSupportsInAppBrowser,
    /** Raft's own hosts (env web + API + production + client config): never an App entry scope. */
    private val raftHostsProvider: () -> Collection<String> = { emptySet() },
    private val debugBuild: Boolean = RaftCmpRouter.isDebugBuild,
    private val appEntryRegistry: RaftAppEntryRegistry = RaftAppEntryRegistry.process,
) {
    /**
     * Opens an App entry (#proj-workspace:#356): checks iOS 3.1.1 first, then validates [request]'s
     * scope via [raftAppEntryScopeOrigin], mints a single-use token in [appEntryRegistry], and opens
     * in-app regardless of [LinkOpenMode]. An invalid scope or a platform without an in-app browser
     * falls back to ordinary [open].
     */
    fun openAppEntry(
        rawUrl: String,
        request: RaftAppEntryRequest,
    ): RaftLinkOpenDecision {
        val decision = resolveRaftLinkOpenDecision(rawUrl, LinkOpenMode.InAppBrowser)
        if (decision.target == RaftLinkOpenTarget.Rejected) {
            onRejected(requireNotNull(decision.rejectReason))
            return decision
        }
        if (isAppStoreRestrictedPurchaseUrl(decision.url)) {
            if (openAppStoreRestrictedPurchaseUrl(decision.url)) {
                return RaftLinkOpenDecision(RaftLinkOpenTarget.InAppDestination, decision.url)
            }
            onRejected(RaftLinkRejectReason.RouteUnavailable)
            return rejectedLink(RaftLinkRejectReason.RouteUnavailable)
        }
        if (!supportsInAppBrowser()) {
            return open(decision.url)
        }
        val scopeOrigin = raftAppEntryScopeOrigin(request.scopeOrigin, decision.url, raftHostsProvider(), debugBuild)
            ?: return open(decision.url)
        val token = appEntryRegistry.register(decision.url, request.copy(scopeOrigin = scopeOrigin))
        if (openInAppBrowserWithEntry(decision.url, token)) {
            return RaftLinkOpenDecision(RaftLinkOpenTarget.InAppBrowser, decision.url)
        }
        appEntryRegistry.release(token, owner = "")
        onRejected(RaftLinkRejectReason.RouteUnavailable)
        return rejectedLink(RaftLinkRejectReason.RouteUnavailable)
    }

    fun open(
        rawUrl: String,
        intent: RaftLinkOpenIntent = RaftLinkOpenIntent.Ordinary,
    ): RaftLinkOpenDecision {
        val decision = resolveRaftLinkOpenDecision(rawUrl, modeProvider(), intent)
        // App Store Guideline 3.1.1: intercept billing, /servers/*/upgrade, and Stripe URLs before
        // any in-app WebView or SFSafariViewController path can open them on iOS.
        if (decision.target != RaftLinkOpenTarget.Rejected && isAppStoreRestrictedPurchaseUrl(decision.url)) {
            if (openAppStoreRestrictedPurchaseUrl(decision.url)) {
                return RaftLinkOpenDecision(RaftLinkOpenTarget.InAppDestination, decision.url)
            }
            onRejected(RaftLinkRejectReason.RouteUnavailable)
            return rejectedLink(RaftLinkRejectReason.RouteUnavailable)
        }
        // An Artifact's address opens Raft's own viewer, as an Artifact card does, whatever the setting.
        if (intent == RaftLinkOpenIntent.Ordinary && decision.target != RaftLinkOpenTarget.Rejected && openArtifactAddress(decision.url)) {
            return RaftLinkOpenDecision(RaftLinkOpenTarget.ArtifactViewer, decision.url)
        }
        // A server join link opens the app's join page (the shell's, through the join link inbox).
        if (intent == RaftLinkOpenIntent.Ordinary && decision.target != RaftLinkOpenTarget.Rejected && openServerJoinLink(decision.url)) {
            return RaftLinkOpenDecision(RaftLinkOpenTarget.ServerJoinPage, decision.url)
        }
        // Any other Raft page the app has a screen for: that screen (the web opens a Raft page in Raft, not a tab).
        if (intent == RaftLinkOpenIntent.Ordinary && decision.target != RaftLinkOpenTarget.Rejected && openOwnLink(decision.url)) {
            return RaftLinkOpenDecision(RaftLinkOpenTarget.InAppDestination, decision.url)
        }
        // A Raft page it has none for: the web page, authenticated in-app when on the active environment.
        if (intent == RaftLinkOpenIntent.Ordinary && decision.target != RaftLinkOpenTarget.Rejected && isRaftWebPage(decision.url)) {
            return open(decision.url, RaftLinkOpenIntent.RaftWebPage)
        }
        // Active-environment Raft web pages open in the authenticated WebView regardless of LinkOpenMode;
        // ForceExternal and RaftWebPageInBrowser bypass this and always reach the system browser.
        if (intent == RaftLinkOpenIntent.RaftWebPage && decision.target != RaftLinkOpenTarget.Rejected && openRaftWebPageInApp(decision.url)) {
            return RaftLinkOpenDecision(RaftLinkOpenTarget.InAppDestination, decision.url)
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
                if (intent == RaftLinkOpenIntent.RaftWebPage || intent == RaftLinkOpenIntent.RaftWebPageInBrowser) {
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
            RaftLinkOpenTarget.ArtifactViewer,
            RaftLinkOpenTarget.ServerJoinPage,
            RaftLinkOpenTarget.InAppDestination -> decision
        }
    }
}

/**
 * [RaftLinkNavigator]'s Artifact opener: a tapped link to an Artifact, read as an external one is
 * ([parseRaftArtifactDeepLink]: the Artifacts home address `/s/<slug>/artifacts?artifact=<id>` or the
 * share address `/artifacts/<id>[/v/<n>]`, on Raft's built-in hosts, the client config's [appHosts]
 * or the Developer environment's [webUrl]), starts an open trace and goes to [openViewer]; false
 * (any other link) leaves it to the link setting.
 */
internal fun raftArtifactLinkOpener(
    webUrl: () -> String?,
    appHosts: () -> Set<String> = { RaftClientConfigAppHosts.current },
    openViewer: (ArtifactLinkTarget) -> Boolean,
): (String) -> Boolean = { url ->
    val target = parseRaftArtifactDeepLink(url, webUrl(), appHosts())
    if (target == null) {
        false
    } else {
        RaftArtifactOpenTrace.tap("link", target.id)
        openViewer(target)
    }
}

/**
 * [RaftLinkNavigator]'s join link opener: a tapped server join link, read as an external one is
 * ([parseRaftServerJoinLink]: `/join/<token>` on Raft's built-in hosts, the client config's
 * [appHosts] or the Developer environment's [webUrl]), waits in [RaftServerJoinLinkInbox] for the
 * app shell, which opens the join page over whatever is shown; false (any other link) leaves it to
 * the link setting.
 */
internal fun raftServerJoinLinkOpener(
    webUrl: () -> String?,
    appHosts: () -> Set<String> = { RaftClientConfigAppHosts.current },
    inbox: RaftServerJoinLinkInbox = RaftServerJoinLinkInbox.Process,
    log: (String) -> Unit = {},
): (String) -> Boolean = { url ->
    val token = parseRaftServerJoinLink(url, webUrl(), appHosts())
    if (token == null) {
        false
    } else {
        val pending = inbox.submit(token, "message_link")
        log("server_join_link_tap result=pending id=${pending.id}")
        true
    }
}

/**
 * [artifactConversationId]: the conversation an Artifact link tapped here is opened from
 * (`hostConversation`, [LocalRaftArtifactOpenConversation]); null where links are not a conversation's.
 */
@Composable
internal fun rememberRaftLinkNavigator(
    pager: Pager,
    bridge: BridgeModule,
    routeScope: RaftInAppBrowserRouteScope = RaftInAppBrowserRouteScope.InheritShell,
    artifactConversationId: String? = null,
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
    val artifactConversation = raftArtifactViewerHostConversationOrNull(artifactConversationId)
    return remember(pager, bridge, preferenceRepository, routeScope, rejectedMessage, artifactStoreScope, artifactConversation) {
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
            openArtifactAddress =
                raftArtifactLinkOpener(
                    // Read at the tap (AGENTS.md "Environment Web Link URL Rule"): the active Developer environment's web.
                    webUrl = { RaftSharedStoreRegistry.get(bridge).apiEnvironmentStore.current().webUrl },
                ) { target ->
                    pager.openRaftArtifactViewerPage(target, artifactStoreScope, artifactConversation).also { opened ->
                        if (!opened) RaftArtifactOpenTrace.mark("browser_fallback", "reason=no_page_scope")
                    }
                },
            openServerJoinLink =
                raftServerJoinLinkOpener(
                    webUrl = { RaftSharedStoreRegistry.get(bridge).apiEnvironmentStore.current().webUrl },
                    log = { bridge.log(it) },
                ),
            openOwnLink =
                raftOwnLinkShellOpener(
                    webUrl = { RaftSharedStoreRegistry.get(bridge).apiEnvironmentStore.current().webUrl },
                    log = { bridge.log(it) },
                ),
            isRaftWebPage = { url -> raftOwnLinkIsWebPage(url, RaftSharedStoreRegistry.get(bridge).apiEnvironmentStore.current().webUrl) },
            openRaftWebPageInApp = { url ->
                routeScope == RaftInAppBrowserRouteScope.InheritShell &&
                    pager.openRaftOwnWebPage(
                        rawUrl = url,
                        storeScopeOverride = artifactStoreScope,
                        activeWebBaseUrl = RaftSharedStoreRegistry.get(bridge).apiEnvironmentStore.current().webUrl,
                    )
            },
            isAppStoreRestrictedPurchaseUrl = { url ->
                raftPlatformFollowsAppStoreBillingRules() &&
                    raftUrlIsAppStoreRestrictedPurchaseUrl(
                        rawUrl = url,
                        activeWebBaseUrl = RaftSharedStoreRegistry.get(bridge).apiEnvironmentStore.current().webUrl,
                    )
            },
            openAppStoreRestrictedPurchaseUrl = { url ->
                val activeWebUrl = RaftSharedStoreRegistry.get(bridge).apiEnvironmentStore.current().webUrl
                if (
                    routeScope == RaftInAppBrowserRouteScope.InheritShell &&
                    raftUrlIsOwnBillingPath(url, activeWebUrl)
                ) {
                    val scope = artifactStoreScope ?: pager.pageData.params.raftShellStoreScopeOrNull()
                    if (scope != null) {
                        pager.openSettingsBillingPage(scope)
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            },
            openInAppBrowserWithEntry = { url, appEntryToken ->
                when (routeScope) {
                    RaftInAppBrowserRouteScope.InheritShell ->
                        pager.openRaftInAppBrowserPage(url, storeScopeOverride = artifactStoreScope, appEntryToken = appEntryToken)
                    RaftInAppBrowserRouteScope.Unscoped ->
                        pager.openUnscopedRaftInAppBrowserPage(url, appEntryToken = appEntryToken)
                }
            },
            raftHostsProvider = {
                val env = RaftSharedStoreRegistry.get(bridge).apiEnvironmentStore.current()
                raftBrowserRaftHosts(env.webUrl, env.apiBaseUrl)
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

/**
 * Opens a page of Raft's web as the web page ([RaftLinkOpenIntent.RaftWebPage]): never the in-app
 * Artifact viewer, in the in-app or a real browser as the link setting says. For an Artifact's
 * `/artifacts/<id>` page asked for as a page (a card's Open in New Page) or as the viewer's fallback
 * (no page can open the viewer, already traced: an Ordinary open would trace and try it again).
 */
@Composable
internal fun rememberRaftWebPageOpener(): (String) -> Unit {
    val navigator = LocalRaftLinkNavigator.current
    val uriHandler = LocalUriHandler.current
    return remember(navigator, uriHandler) {
        { url ->
            if (navigator != null) navigator.open(url, RaftLinkOpenIntent.RaftWebPage) else uriHandler.openUri(url)
        }
    }
}

/**
 * Opens a page of Raft's web where the person confirms with a passkey ([RaftLinkOpenIntent.RaftWebPageInBrowser]):
 * the default browser whatever the link setting, asked for as a browser.
 */
@Composable
internal fun rememberRaftPasskeyWebPageOpener(): (String) -> Unit {
    val navigator = LocalRaftLinkNavigator.current
    val uriHandler = LocalUriHandler.current
    return remember(navigator, uriHandler) {
        { url ->
            if (navigator != null) navigator.open(url, RaftLinkOpenIntent.RaftWebPageInBrowser) else uriHandler.openUri(url)
        }
    }
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
