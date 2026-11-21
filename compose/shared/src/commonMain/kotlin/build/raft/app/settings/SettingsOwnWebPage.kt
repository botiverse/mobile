package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import build.raft.app.base.Utils
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.pager.Pager
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.ARTIFACT_CONTENT_DOMAIN
import build.raft.app.data.LinkOpenMode
import build.raft.app.data.RaftClientConfigAppHosts
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.isArtifactContentHost
import build.raft.app.nav.RAFT_REPLACE_CURRENT_PAGE
import build.raft.app.network.RaftContentCdnHost
import build.raft.app.network.RaftRetiredContentHost
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize

internal const val SETTINGS_WEB_VIEW_TARGET_URL = "settingsWebViewTargetUrl"

/**
 * Validates and optionally embeds a Raft own-origin web URL for the authenticated in-app WebView
 * ([SettingsWebViewSurface.WebPage], #proj-mobile:#204).
 *
 * Strictly fail-closed:
 * - Must be a valid `http://` or `https://` URL (no whitespace, control chars, or userinfo).
 * - Rejects trailing-dot hostnames (`app.raft.build.`) whose browser `window.location.origin`
 *   disagrees with the normalized `mainFrameOrigin` / auth-guard origin.
 * - Origin must match [activeWebBaseUrl] (resolved through [sharedSettingsResolvedWebBaseUrl])
 *   **exactly** so staging credentials never reach production (or `open.raft.build`) and vice versa.
 * - Rejects Artifact content and CDN hosts ([isArtifactContentHost], [RaftRetiredContentHost],
 *   [RaftContentCdnHost]), any path whose normalized/percent-decoded segments contain `_raft`, and
 *   any query parameter whose percent-decoded key is `ticket`.
 * - Strips any `#host=...` command fragment on entry so untrusted links cannot smuggle host actions.
 * - When [blockAppStoreBilling] is true (iOS App Store Guideline 3.1.1), rejects normalized
 *   `/settings/billing` and `/s/<slug>/settings/billing` paths.
 * - When [embedHostShell] is true, enforces `embed=raft-settings-v1&shell=host` (replacing any
 *   caller-supplied `embed` or `shell` parameters) while preserving other query params and fragments.
 */
internal fun raftAuthenticatedOwnWebPageUrlOrNull(
    rawUrl: String,
    activeWebBaseUrl: String?,
    embedHostShell: Boolean = true,
    blockAppStoreBilling: Boolean = raftPlatformFollowsAppStoreBillingRules(),
): String? {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) return null
    val decision = resolveRaftLinkOpenDecision(trimmed, LinkOpenMode.InAppBrowser)
    if (decision.target != RaftLinkOpenTarget.InAppBrowser) return null

    val url = decision.url
    val rawAuthority =
        url.substringAfter("://", "")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
    val rawHost = rawAuthority.substringBefore(':')
    if (rawHost.isEmpty() || rawHost.endsWith('.')) return null

    val resolvedBase = sharedSettingsResolvedWebBaseUrl(activeWebBaseUrl)
    val expectedOrigin = raftWebOriginOf(resolvedBase) ?: return null
    val actualOrigin = raftWebOriginOf(url) ?: return null
    if (actualOrigin != expectedOrigin) return null

    val host = actualOrigin.substringAfter("://").substringBefore(':').lowercase().trimEnd('.')
    if (isArtifactContentHost(host) || host == RaftRetiredContentHost || host == RaftContentCdnHost) {
        return null
    }

    val hashIndex = url.indexOf('#')
    val beforeFragment = if (hashIndex >= 0) url.substring(0, hashIndex) else url
    val rawFragment = if (hashIndex >= 0) url.substring(hashIndex + 1) else null
    val safeFragment = rawFragment?.takeUnless { it.startsWith("host=") }

    val queryIndex = beforeFragment.indexOf('?')
    val baseAndPath = if (queryIndex >= 0) beforeFragment.substring(0, queryIndex) else beforeFragment
    val query = if (queryIndex >= 0) beforeFragment.substring(queryIndex + 1) else ""

    val afterScheme = baseAndPath.substringAfter("://", "")
    val slashIndex = afterScheme.indexOf('/')
    val path = if (slashIndex < 0) "/" else afterScheme.substring(slashIndex)
    val normalizedSegments = raftNormalizedOwnWebPathSegmentsOrNull(path) ?: return null
    if (normalizedSegments.any { it == "_raft" }) {
        return null
    }

    val queryParts = if (query.isEmpty()) emptyList() else query.split('&').filter { it.isNotEmpty() }
    val decodedKeys = ArrayList<String>(queryParts.size)
    for (part in queryParts) {
        val decodedKey = strictPercentDecodeUtf8OrNull(part.substringBefore('=')) ?: return null
        if (decodedKey.equals("ticket", ignoreCase = true)) return null
        decodedKeys += decodedKey
    }
    if (blockAppStoreBilling && raftOwnWebPathSegmentsAreBilling(normalizedSegments)) {
        return null
    }
    if (!embedHostShell) {
        return if (safeFragment != null) "$beforeFragment#$safeFragment" else beforeFragment
    }

    val nextParts =
        buildList {
            for (index in queryParts.indices) {
                val key = decodedKeys[index]
                if (!key.equals("embed", ignoreCase = true) && !key.equals("shell", ignoreCase = true)) {
                    add(queryParts[index])
                }
            }
            add("embed=$SharedSettingsEmbedContract")
            add("shell=$SharedSettingsEmbedHostShell")
        }
    val nextUrl = "$baseAndPath?${nextParts.joinToString("&")}"
    return if (safeFragment != null) "$nextUrl#$safeFragment" else nextUrl
}

/**
 * Whether [rawUrl] points to a Raft billing path (`/settings/billing` or `/s/<slug>/settings/billing`,
 * after strict percent-decoding and dot-segment normalization) on the active environment or another
 * Raft own host. Used to enforce iOS App Store Guideline 3.1.1 so billing never enters any in-app
 * WebView on iOS.
 */
internal fun raftUrlIsOwnBillingPath(
    rawUrl: String,
    activeWebBaseUrl: String?,
    appHosts: Set<String> = RaftClientConfigAppHosts.current,
): Boolean {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) return false
    val activeOrigin = raftWebOriginOf(sharedSettingsResolvedWebBaseUrl(activeWebBaseUrl))
    val urlOrigin = raftWebOriginOf(trimmed) ?: return false
    val isOwnHost = (activeOrigin != null && urlOrigin == activeOrigin) ||
        raftOwnWebLinkPath(trimmed, activeWebBaseUrl, appHosts) != null
    if (!isOwnHost) return false
    val path =
        trimmed
            .substringBefore('#')
            .substringBefore('?')
            .substringAfter("://", "")
            .let { rest -> rest.indexOf('/').let { if (it < 0) "/" else rest.substring(it) } }
    val segments = raftNormalizedOwnWebPathSegmentsOrNull(path) ?: return true
    return raftOwnWebPathSegmentsAreBilling(segments)
}

/**
 * Strips the host-shell embed parameters (`embed=raft-settings-v1`, `shell=host`) and any
 * `#host=...` command fragment before handing the current page URL to the system browser.
 */
internal fun stripRaftOwnWebPageEmbedParams(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return trimmed
    val hashIndex = trimmed.indexOf('#')
    val beforeFragment = if (hashIndex >= 0) trimmed.substring(0, hashIndex) else trimmed
    val rawFragment = if (hashIndex >= 0) trimmed.substring(hashIndex + 1) else null
    val keptFragment = rawFragment?.takeUnless { it.startsWith("host=") }

    val queryIndex = beforeFragment.indexOf('?')
    val baseAndPath = if (queryIndex >= 0) beforeFragment.substring(0, queryIndex) else beforeFragment
    val query = if (queryIndex >= 0) beforeFragment.substring(queryIndex + 1) else null

    val keptParts =
        query
            ?.split('&')
            ?.filter { part ->
                if (part.isEmpty()) return@filter false
                val key = part.substringBefore('=').lowercase()
                val value = part.substringAfter('=', "")
                !(key == "embed" && value == SharedSettingsEmbedContract) &&
                    !(key == "shell" && value == SharedSettingsEmbedHostShell)
            }
            .orEmpty()

    val rebuilt = if (keptParts.isEmpty()) baseAndPath else "$baseAndPath?${keptParts.joinToString("&")}"
    return if (keptFragment != null) "$rebuilt#$keptFragment" else rebuilt
}

internal fun raftOwnWebPageNeedsArtifactContentFrame(url: String): Boolean {
    val path =
        url.trim()
            .substringBefore('#')
            .substringBefore('?')
            .substringAfter("://", "")
            .let { rest -> rest.indexOf('/').let { if (it < 0) "/" else rest.substring(it) } }
    val segments = raftNormalizedOwnWebPathSegmentsOrNull(path) ?: return false
    return (segments.isNotEmpty() && segments[0] == "artifacts") ||
        (segments.size >= 3 && segments[0] == "s" && segments[2] == "artifacts")
}

private fun raftNormalizedOwnWebPathSegmentsOrNull(path: String): List<String>? {
    val segments = ArrayList<String>()
    for (rawSegment in path.split('/')) {
        if (rawSegment.isEmpty()) continue
        val decoded = strictPercentDecodeUtf8OrNull(rawSegment) ?: return null
        if (decoded.any { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7f }) return null
        when (decoded) {
            "." -> Unit
            ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
            else -> segments.add(decoded.lowercase())
        }
    }
    return segments
}

private fun raftOwnWebPathSegmentsAreBilling(segments: List<String>): Boolean =
    (segments.size >= 2 && segments[0] == "settings" && segments[1] == "billing") ||
        (segments.size >= 4 && segments[0] == "s" && segments[2] == "settings" && segments[3] == "billing")

internal fun settingsWebViewTargetUrlFromPageData(params: JSONObject): String? =
    params.optString(SETTINGS_WEB_VIEW_TARGET_URL).trim().takeIf { it.isNotEmpty() }

internal fun settingsOwnWebViewPageData(
    validatedUrl: String,
    replaceCurrentPage: Boolean = false,
): JSONObject =
    JSONObject().apply {
        put("pageName", SETTINGS_WEB_VIEW_PAGE)
        put(SETTINGS_WEB_VIEW_SURFACE, SettingsWebViewSurface.WebPage.diagnosticsName)
        put(SETTINGS_WEB_VIEW_TARGET_URL, validatedUrl)
        if (replaceCurrentPage) put(RAFT_REPLACE_CURRENT_PAGE, true)
    }

/**
 * Opens a validated active-environment Raft web page in the authenticated in-app WebView
 * ([SettingsWebViewSurface.WebPage]). Returns false without navigating when [rawUrl] fails
 * origin/safety validation or no shell scope is available so callers can fall back cleanly.
 */
internal fun Pager.openRaftOwnWebPage(
    rawUrl: String,
    storeScopeOverride: RaftShellStoreScope? = null,
    activeWebBaseUrl: String? = null,
    replaceCurrentPage: Boolean = false,
): Boolean {
    val webBaseUrl = activeWebBaseUrl ?: RaftSharedStoreRegistry.currentOrNull()?.apiEnvironmentStore?.current()?.webUrl
    val validatedUrl =
        raftAuthenticatedOwnWebPageUrlOrNull(
            rawUrl = rawUrl,
            activeWebBaseUrl = webBaseUrl,
            embedHostShell = false,
        ) ?: return false
    return openRaftPage(
        pageName = SETTINGS_WEB_VIEW_PAGE,
        pageData = settingsOwnWebViewPageData(validatedUrl, replaceCurrentPage = replaceCurrentPage),
        storeScopeOverride = storeScopeOverride,
    )
}

@Composable
internal fun SettingsOwnWebViewPage(pager: Pager) {
    val bridge = Utils.bridgeModule(pager.pagerId)
    val sharedStores = remember(bridge) { RaftSharedStoreRegistry.get(bridge) }
    val webBaseUrl = standaloneSettingsWebBaseUrl(sharedStores.apiEnvironmentStore)
    val rawTargetUrl = remember(pager.pageData.params.toString()) { settingsWebViewTargetUrlFromPageData(pager.pageData.params) }
    // Re-validate against the live active environment at render time (fail closed on restore/env switch).
    val validatedUrl =
        remember(rawTargetUrl, webBaseUrl) {
            rawTargetUrl?.let { raftAuthenticatedOwnWebPageUrlOrNull(it, webBaseUrl, embedHostShell = true) }
        }
    if (validatedUrl == null) {
        SettingsWebViewInvalidRoute(pager)
        return
    }
    val expectedOrigin = remember(validatedUrl) { raftWebOriginOf(validatedUrl) }
    val linkNavigator = LocalRaftLinkNavigator.current
    var currentUrl by remember(validatedUrl) { mutableStateOf(validatedUrl) }
    var deflectedBilling by remember(validatedUrl) { mutableStateOf(false) }
    val openInBrowserLabel = stringResource(Res.string.in_app_browser_open_system)
    val appStoreBillingRules = raftPlatformFollowsAppStoreBillingRules()

    fun openCurrentInBrowser() {
        val candidate = currentUrl.takeIf { it.isNotBlank() && raftWebOriginOf(it) == expectedOrigin } ?: validatedUrl
        val cleanUrl = stripRaftOwnWebPageEmbedParams(candidate)
        bridge.log("settings_web-page_open_in_browser url=${raftWebViewUrlForLog(cleanUrl)}")
        bridge.openUrl(cleanUrl, inBrowser = true)
    }

    fun openExternallyNoBounce(targetUrl: String) {
        val isSameOrigin = raftWebOriginOf(targetUrl) == expectedOrigin
        val cleanUrl = if (isSameOrigin) stripRaftOwnWebPageEmbedParams(targetUrl) else targetUrl
        val isOwnRaftOrigin = isSameOrigin || raftOwnLinkRoute(cleanUrl, webBaseUrl) != null
        val intent = if (isOwnRaftOrigin) RaftLinkOpenIntent.RaftWebPageInBrowser else RaftLinkOpenIntent.ForceExternal
        if (linkNavigator != null) {
            linkNavigator.open(cleanUrl, intent)
        } else {
            bridge.openUrl(cleanUrl, inBrowser = isOwnRaftOrigin)
        }
    }

    val spec =
        remember(validatedUrl) {
            StandaloneSettingsWebViewSpec(
                surface = SettingsWebViewSurface.WebPage,
                includeLocaleQuery = false,
                headerFollowsDocumentTitle = true,
                allowNavigation = true,
                allowedNavigationSchemes = setOf("http", "https"),
                lockToWebOrigin = true,
                frameHosts =
                    if (raftOwnWebPageNeedsArtifactContentFrame(validatedUrl)) {
                        setOf(ARTIFACT_CONTENT_DOMAIN)
                    } else {
                        emptySet()
                    },
                hostEventsEnabled = false,
                buildWebUrl = { _, _, _ -> validatedUrl },
            )
        }

    val currentOpenInBrowser by rememberUpdatedState { openCurrentInBrowser() }
    val nativeActions =
        remember(openInBrowserLabel, validatedUrl, bridge) {
            listOf(
                RaftNativeNavBarAction(
                    id = "open_in_browser",
                    label = openInBrowserLabel,
                    systemSymbol = "arrow.up.right",
                    icon = RaftLucideIconName.ArrowUpRight,
                    onClick = { currentOpenInBrowser() },
                ),
            )
        }

    StandaloneSettingsWebViewPage(
        pager = pager,
        title = "Raft",
        iconName = RaftLucideIconName.Globe,
        spec = spec,
        trailingContent = {
            RaftIconButton(
                onClick = ::openCurrentInBrowser,
                tooltip = openInBrowserLabel,
                variant = RaftButtonVariant.Ghost,
                size = RaftIconButtonSize.Md,
            ) {
                RaftSlotIcon(RaftLucideIconName.ArrowUpRight)
            }
        },
        nativeActions = nativeActions,
        onNavigationStateChange = { update ->
            val nextUrl = update.state.url.trim()
            if (nextUrl.isNotEmpty() && raftWebOriginOf(nextUrl) == expectedOrigin) {
                // iOS App Store Guideline 3.1.1: also catch client-side SPA pushState navigations
                // to billing paths that bypass WKNavigationDelegate.decidePolicyForNavigationAction.
                if (appStoreBillingRules && raftUrlIsOwnBillingPath(nextUrl, webBaseUrl)) {
                    if (!deflectedBilling) {
                        deflectedBilling = true
                        openExternallyNoBounce(nextUrl)
                        pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
                    }
                } else {
                    currentUrl = nextUrl
                }
            }
        },
        // #host=open-external explicitly requests leaving the authenticated WebView (never loop back into WebPage).
        onOpenExternalLink = ::openExternallyNoBounce,
        // Off-origin blocked navigations (and iOS billing navigations) open externally with no auth
        // and no loop; same-origin target="_blank" links route through linkNavigator unless targeting
        // the same URL.
        onOpenBlockedNavigation = { targetUrl ->
            if (appStoreBillingRules && raftUrlIsOwnBillingPath(targetUrl, webBaseUrl)) {
                openExternallyNoBounce(targetUrl)
            } else if (raftWebOriginOf(targetUrl) == expectedOrigin) {
                val cleanTarget = stripRaftOwnWebPageEmbedParams(targetUrl)
                val cleanCurrent = stripRaftOwnWebPageEmbedParams(currentUrl)
                if (cleanTarget == cleanCurrent) {
                    bridge.openUrl(cleanTarget, inBrowser = true)
                } else if (linkNavigator != null) {
                    linkNavigator.open(cleanTarget)
                } else {
                    bridge.openUrl(cleanTarget, inBrowser = true)
                }
            } else {
                openExternallyNoBounce(targetUrl)
            }
        },
        // iOS App Store Guideline 3.1.1: intercept in-page navigations to billing paths so Stripe
        // checkout cannot open inside the authenticated WKWebView.
        routesMainFrameNavigation =
            if (appStoreBillingRules) {
                { candidateUrl -> raftUrlIsOwnBillingPath(candidateUrl, webBaseUrl) }
            } else {
                null
            },
    )
}
