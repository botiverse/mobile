package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import build.raft.app.links.raftComparableHostname
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
 * - Must be a valid `http://` or `https://` URL (no whitespace, control chars, backslash, or userinfo).
 * - Rejects trailing-dot hostnames (`app.raft.build.`) whose browser `window.location.origin`
 *   disagrees with the normalized `mainFrameOrigin` / auth-guard origin.
 * - [activeWebBaseUrl] must be explicitly configured (never falls back to production for a custom
 *   environment with a blank web URL), and origin must match **exactly** so staging/dev credentials
 *   never reach production (or `open.raft.build`) and vice versa.
 * - Rejects Artifact content and CDN hosts ([isArtifactContentHost], [RaftRetiredContentHost],
 *   [RaftContentCdnHost]), any path whose normalized/percent-decoded segments contain `_raft`, and
 *   any query parameter whose percent-decoded key is `ticket`.
 * - Strips any `#host=...` command fragment and all reserved host-embed query params (`embed`,
 *   `shell`, `back`, `chrome`, `host*`) on entry so untrusted links cannot smuggle host actions or
 *   broken host chrome.
 * - When [blockAppStoreBilling] is true (iOS App Store Guideline 3.1.1), blocks all restricted
 *   purchase URLs and allowlists only verified paywall-free standalone Artifact fallback pages
 *   (`/artifacts/<id>` and `/artifacts/<id>/v/<n>`).
 * - When [embedHostShell] is true, enforces `embed=raft-settings-v1&shell=host` while preserving
 *   other query params and non-`#host=` fragments.
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

    val configuredBase = activeWebBaseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val expectedOrigin = raftWebOriginOf(configuredBase) ?: return null
    val actualOrigin = raftWebOriginOf(url) ?: return null
    if (actualOrigin != expectedOrigin) return null

    val host = actualOrigin.substringAfter("://").substringBefore(':').lowercase().trimEnd('.')
    if (isArtifactContentHost(host) || host == RaftRetiredContentHost || host == RaftContentCdnHost) {
        return null
    }

    val hashIndex = url.indexOf('#')
    val beforeFragment = if (hashIndex >= 0) url.substring(0, hashIndex) else url
    val rawFragment = if (hashIndex >= 0) url.substring(hashIndex + 1) else null
    val safeFragment = rawFragment?.takeUnless { it.startsWith("host=", ignoreCase = true) }

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
    val keptQueryParts = ArrayList<String>(queryParts.size)
    for (part in queryParts) {
        val decodedKey = strictPercentDecodeUtf8OrNull(part.substringBefore('=')) ?: return null
        if (decodedKey.equals("ticket", ignoreCase = true)) return null
        if (blockAppStoreBilling && isAppStoreUpgradeQueryKey(decodedKey)) return null
        if (!isReservedOwnWebEmbedQueryKey(decodedKey)) {
            keptQueryParts += part
        }
    }
    if (blockAppStoreBilling) {
        if (raftUrlIsAppStoreRestrictedPurchaseUrl(url, configuredBase)) return null
        if (!raftOwnWebPathSegmentsAllowedOnAppStore(normalizedSegments)) return null
    }

    val nextParts =
        if (embedHostShell) {
            buildList(keptQueryParts.size + 2) {
                addAll(keptQueryParts)
                add("embed=$SharedSettingsEmbedContract")
                add("shell=$SharedSettingsEmbedHostShell")
            }
        } else {
            keptQueryParts
        }
    val rebuilt = if (nextParts.isEmpty()) baseAndPath else "$baseAndPath?${nextParts.joinToString("&")}"
    return if (safeFragment != null) "$rebuilt#$safeFragment" else rebuilt
}

/**
 * Whether [rawUrl] points to a Raft billing path (`/settings/billing` or `/s/<slug>/settings/billing`,
 * after strict percent-decoding and dot-segment normalization) on the active environment or another
 * Raft own host.
 */
internal fun raftUrlIsOwnBillingPath(
    rawUrl: String,
    activeWebBaseUrl: String?,
    appHosts: Set<String> = RaftClientConfigAppHosts.current,
): Boolean {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) return false
    if (!raftUrlIsOnOwnRaftHost(trimmed, activeWebBaseUrl, appHosts)) return false
    val segments = raftNormalizedOwnWebPathSegmentsOrNull(raftRawPathOf(trimmed)) ?: return false
    return raftOwnWebPathSegmentsAreBilling(segments)
}

/**
 * Whether [rawUrl] exposes a payment or upgrade flow restricted under iOS App Store Guideline 3.1.1:
 * - Any Stripe host (`stripe.com` or `*.stripe.com`).
 * - Any Raft own-host `/settings/billing` or `/s/<slug>/settings/billing` path.
 * - Any Raft own-host `/servers/<id>/upgrade` or `/s/<slug>/servers/<id>/upgrade` path.
 * - Any Raft own-host URL carrying an `upgrade` / `upgradeServer` query parameter (which opens
 *   web's `DeferredUpgradeDialogHost` on any route).
 * - Fails closed (returns true) when an own-host URL has malformed/encoded-separator path segments
 *   (`%2F`, `%5C`, `\`, invalid percent-encoding) or malformed query keys.
 */
internal fun raftUrlIsAppStoreRestrictedPurchaseUrl(
    rawUrl: String,
    activeWebBaseUrl: String?,
    appHosts: Set<String> = RaftClientConfigAppHosts.current,
): Boolean {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) return false
    val scheme = trimmed.substringBefore("://", "").lowercase()
    if (scheme != "http" && scheme != "https") return false
    val host = raftDefensiveHostOf(trimmed) ?: return false
    if (host == "stripe.com" || host.endsWith(".stripe.com")) {
        return true
    }
    if (!raftUrlIsOnOwnRaftHost(trimmed, activeWebBaseUrl, appHosts)) return false
    val segments = raftNormalizedOwnWebPathSegmentsOrNull(raftRawPathOf(trimmed)) ?: return true
    if (raftOwnWebPathSegmentsAreBilling(segments) || raftOwnWebPathSegmentsAreUpgrade(segments)) {
        return true
    }
    val beforeFragment = trimmed.substringBefore('#')
    val queryIndex = beforeFragment.indexOf('?')
    if (queryIndex >= 0) {
        val query = beforeFragment.substring(queryIndex + 1)
        for (part in query.split('&')) {
            if (part.isEmpty()) continue
            val decodedKey = strictPercentDecodeUtf8OrNull(part.substringBefore('=')) ?: return true
            if (isAppStoreUpgradeQueryKey(decodedKey)) return true
        }
    }
    return false
}

/**
 * Strips host-shell embed parameters (`embed`, `shell`, `back`, `chrome`, `host*`) and any
 * `#host=...` command fragment before handing the current page URL to a browser.
 */
internal fun stripRaftOwnWebPageEmbedParams(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return trimmed
    val hashIndex = trimmed.indexOf('#')
    val beforeFragment = if (hashIndex >= 0) trimmed.substring(0, hashIndex) else trimmed
    val rawFragment = if (hashIndex >= 0) trimmed.substring(hashIndex + 1) else null
    val keptFragment = rawFragment?.takeUnless { it.startsWith("host=", ignoreCase = true) }

    val queryIndex = beforeFragment.indexOf('?')
    val baseAndPath = if (queryIndex >= 0) beforeFragment.substring(0, queryIndex) else beforeFragment
    val query = if (queryIndex >= 0) beforeFragment.substring(queryIndex + 1) else null

    val keptParts =
        query
            ?.split('&')
            ?.filter { part ->
                if (part.isEmpty()) return@filter false
                val rawKey = part.substringBefore('=')
                val decodedKey = strictPercentDecodeUtf8OrNull(rawKey) ?: rawKey
                !isReservedOwnWebEmbedQueryKey(decodedKey)
            }
            .orEmpty()

    val rebuilt = if (keptParts.isEmpty()) baseAndPath else "$baseAndPath?${keptParts.joinToString("&")}"
    return if (keptFragment != null) "$rebuilt#$keptFragment" else rebuilt
}

internal fun raftOwnWebPageNeedsArtifactContentFrame(url: String): Boolean {
    val segments = raftNormalizedOwnWebPathSegmentsOrNull(raftRawPathOf(url.trim())) ?: return false
    return (segments.isNotEmpty() && segments[0] == "artifacts") ||
        (segments.size >= 3 && segments[0] == "s" && segments[2] == "artifacts")
}

/**
 * Chooses the [RaftLinkOpenIntent] when [SettingsOwnWebViewPage] opens a link externally
 * (`#host=open-external` or an off-origin blocked navigation):
 * - Own Raft URLs use [RaftLinkOpenIntent.RaftWebPageInBrowser] so App Links / Universal Links
 *   cannot bounce them straight back into this WebView.
 * - Third-party URLs use [RaftLinkOpenIntent.Ordinary] so the user's [LinkOpenMode] preference
 *   still decides between in-app browser and system browser.
 */
internal fun settingsOwnWebExternalOpenIntent(
    targetUrl: String,
    expectedOrigin: String?,
    activeWebBaseUrl: String?,
    appHosts: Set<String> = RaftClientConfigAppHosts.current,
): RaftLinkOpenIntent {
    val isOwnRaftOrigin =
        (expectedOrigin != null && raftWebOriginOf(targetUrl) == expectedOrigin) ||
            raftOwnLinkRoute(targetUrl, activeWebBaseUrl, appHosts) != null
    return if (isOwnRaftOrigin) RaftLinkOpenIntent.RaftWebPageInBrowser else RaftLinkOpenIntent.Ordinary
}

/**
 * Render-time re-validation of the route payload against the live environment and platform rules
 * (fail-closed on restored routes, environment switches, blank custom web URLs, or App Store rules).
 */
internal fun resolveSettingsOwnWebViewRenderUrl(
    params: JSONObject,
    activeWebBaseUrl: String?,
    blockAppStoreBilling: Boolean = raftPlatformFollowsAppStoreBillingRules(),
): String? =
    settingsWebViewTargetUrlFromPageData(params)?.let { target ->
        raftAuthenticatedOwnWebPageUrlOrNull(
            rawUrl = target,
            activeWebBaseUrl = activeWebBaseUrl,
            embedHostShell = true,
            blockAppStoreBilling = blockAppStoreBilling,
        )
    }

private fun raftDefensiveHostOf(trimmedUrl: String): String? {
    val afterScheme = trimmedUrl.substringAfter("://", "")
    if (afterScheme.isEmpty()) return null
    val authorityEnd = afterScheme.indexOfFirst { it == '/' || it == '\\' || it == '?' || it == '#' }
    val authority = if (authorityEnd < 0) afterScheme else afterScheme.substring(0, authorityEnd)
    val host =
        authority
            .substringAfterLast('@')
            .substringBefore(':')
            .lowercase()
            .trimEnd('.')
    return host.takeIf { it.isNotEmpty() }
}

private fun raftUrlIsOnOwnRaftHost(
    trimmedUrl: String,
    activeWebBaseUrl: String?,
    appHosts: Set<String>,
): Boolean {
    val host = raftDefensiveHostOf(trimmedUrl) ?: return false
    val activeHost = activeWebBaseUrl?.trim()?.takeIf { it.isNotEmpty() }?.let(::raftDefensiveHostOf)
    if (activeHost != null && host == activeHost) return true
    if (host in RaftMessageLinkProductionHosts) return true
    if (appHosts.any { raftComparableHostname(it.trim()) == host }) return true
    return raftOwnWebLinkPath(trimmedUrl, activeWebBaseUrl, appHosts) != null
}

private fun raftRawPathOf(trimmedUrl: String): String =
    trimmedUrl
        .substringBefore('#')
        .substringBefore('?')
        .substringAfter("://", "")
        .let { rest -> rest.indexOfFirst { it == '/' || it == '\\' }.let { if (it < 0) "/" else rest.substring(it) } }

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

private fun isReservedOwnWebEmbedQueryKey(decodedKey: String): Boolean {
    val lower = decodedKey.lowercase()
    return lower == "embed" ||
        lower == "shell" ||
        lower == "back" ||
        lower == "chrome" ||
        lower.startsWith("host")
}

private fun isAppStoreUpgradeQueryKey(decodedKey: String): Boolean {
    val lower = decodedKey.lowercase()
    return lower == "upgrade" || lower == "upgradeserver"
}

private fun raftOwnWebPathSegmentsAreBilling(segments: List<String>): Boolean =
    (segments.size >= 2 && segments[0] == "settings" && segments[1] == "billing") ||
        (segments.size >= 4 && segments[0] == "s" && segments[2] == "settings" && segments[3] == "billing")

private fun raftOwnWebPathSegmentsAreUpgrade(segments: List<String>): Boolean =
    (segments.size >= 3 && segments[0] == "servers" && segments[2] == "upgrade") ||
        (segments.size >= 5 && segments[0] == "s" && segments[2] == "servers" && segments[4] == "upgrade")

/**
 * Verified paywall-free routes allowed to open in [SettingsWebViewSurface.WebPage] on platforms
 * subject to App Store Guideline 3.1.1 (iOS): standalone Artifact fallback pages only
 * (`/artifacts/<id>` and `/artifacts/<id>/v/<n>`). Server-shell routes (`/s/<slug>/...`) can render
 * 30-day history or limit upgrade banners and therefore stay excluded on iOS.
 */
private fun raftOwnWebPathSegmentsAllowedOnAppStore(segments: List<String>): Boolean =
    (segments.size == 2 && segments[0] == "artifacts" && isRaftLinkIdentifier(segments[1])) ||
        (
            segments.size == 4 &&
                segments[0] == "artifacts" &&
                isRaftLinkIdentifier(segments[1]) &&
                segments[2] == "v" &&
                segments[3].toIntOrNull()?.let { it > 0 } == true
        )

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
    val activeEnvironment by sharedStores.apiEnvironmentStore.state.collectAsState()
    val webBaseUrl = activeEnvironment.webUrl
    val storeScope = remember(pager.pageData.params.toString()) { pager.pageData.params.raftShellStoreScopeOrNull() }
    val appStoreBillingRules = raftPlatformFollowsAppStoreBillingRules()
    // Re-validate against the live active environment at render time (fail closed on restore/env switch).
    val validatedUrl =
        remember(pager.pageData.params.toString(), webBaseUrl, appStoreBillingRules) {
            resolveSettingsOwnWebViewRenderUrl(
                params = pager.pageData.params,
                activeWebBaseUrl = webBaseUrl,
                blockAppStoreBilling = appStoreBillingRules,
            )
        }
    if (validatedUrl == null) {
        SettingsWebViewInvalidRoute(pager)
        return
    }
    val expectedOrigin = remember(validatedUrl) { raftWebOriginOf(validatedUrl) }
    val linkNavigator = LocalRaftLinkNavigator.current
    var currentUrl by remember(validatedUrl) { mutableStateOf(validatedUrl) }
    var deflectedNavigation by remember(validatedUrl) { mutableStateOf(false) }
    val openInBrowserLabel = stringResource(Res.string.in_app_browser_open_system)

    fun handleAppStoreRestrictedPurchase(targetUrl: String, closeCurrentPage: Boolean): Boolean {
        if (!appStoreBillingRules || !raftUrlIsAppStoreRestrictedPurchaseUrl(targetUrl, webBaseUrl)) return false
        bridge.logWarn("settings_web-page_appstore_purchase_blocked url=${raftOwnWebUrlForLog(targetUrl)}")
        if (
            raftUrlIsOwnBillingPath(targetUrl, webBaseUrl) &&
            storeScope != null &&
            settingsBillingOpenTarget() != SettingsBillingOpenTarget.ExternalBrowser
        ) {
            pager.openSettingsBillingPage(storeScope, replaceCurrentPage = closeCurrentPage)
        } else if (closeCurrentPage) {
            pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
        }
        return true
    }

    fun openCurrentInBrowser() {
        val candidate = currentUrl.takeIf { it.isNotBlank() && raftWebOriginOf(it) == expectedOrigin } ?: validatedUrl
        val cleanUrl = stripRaftOwnWebPageEmbedParams(candidate)
        if (handleAppStoreRestrictedPurchase(cleanUrl, closeCurrentPage = false)) return
        bridge.log("settings_web-page_open_in_browser url=${raftOwnWebUrlForLog(cleanUrl)}")
        bridge.openUrl(cleanUrl, inBrowser = true)
    }

    fun openExternally(targetUrl: String) {
        val isSameOrigin = raftWebOriginOf(targetUrl) == expectedOrigin
        val cleanUrl = if (isSameOrigin) stripRaftOwnWebPageEmbedParams(targetUrl) else targetUrl
        if (handleAppStoreRestrictedPurchase(cleanUrl, closeCurrentPage = false)) return
        val intent = settingsOwnWebExternalOpenIntent(cleanUrl, expectedOrigin, webBaseUrl)
        if (linkNavigator != null) {
            linkNavigator.open(cleanUrl, intent)
        } else {
            bridge.openUrl(cleanUrl, inBrowser = intent == RaftLinkOpenIntent.RaftWebPageInBrowser)
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
                // iOS App Store Guideline 3.1.1: catch client-side SPA pushState navigations that
                // leave the iOS allowlist (e.g. to /s/<slug>/..., billing, or upgrade routes).
                val disallowedOnAppStore =
                    appStoreBillingRules &&
                        raftAuthenticatedOwnWebPageUrlOrNull(
                            rawUrl = nextUrl,
                            activeWebBaseUrl = webBaseUrl,
                            embedHostShell = false,
                            blockAppStoreBilling = true,
                        ) == null
                if (disallowedOnAppStore) {
                    if (!deflectedNavigation) {
                        deflectedNavigation = true
                        if (!handleAppStoreRestrictedPurchase(nextUrl, closeCurrentPage = true)) {
                            bridge.logWarn("settings_web-page_appstore_route_blocked url=${raftOwnWebUrlForLog(nextUrl)}")
                            pager.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
                        }
                    }
                } else {
                    currentUrl = nextUrl
                }
            }
        },
        // #host=open-external: own Raft URLs avoid bouncing back; third-party URLs honor LinkOpenMode.
        onOpenExternalLink = ::openExternally,
        // Off-origin blocked navigations (and iOS restricted purchase / disallowed same-origin navigations)
        // never carry auth; same-origin target="_blank" links route through linkNavigator unless targeting
        // the same URL.
        onOpenBlockedNavigation = { targetUrl ->
            if (handleAppStoreRestrictedPurchase(targetUrl, closeCurrentPage = false)) {
                return@StandaloneSettingsWebViewPage
            }
            if (raftWebOriginOf(targetUrl) == expectedOrigin) {
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
                openExternally(targetUrl)
            }
        },
        // iOS App Store Guideline 3.1.1: intercept any in-page navigation that leaves the iOS
        // allowlist (including /s/<slug>/..., billing, /servers/*/upgrade, ?upgrade=..., and Stripe)
        // so paywall/purchase UI cannot load inside the authenticated iOS WKWebView.
        routesMainFrameNavigation =
            if (appStoreBillingRules) {
                { candidateUrl ->
                    raftAuthenticatedOwnWebPageUrlOrNull(
                        rawUrl = candidateUrl,
                        activeWebBaseUrl = webBaseUrl,
                        embedHostShell = false,
                        blockAppStoreBilling = true,
                    ) == null
                }
            } else {
                null
            },
    )
}
