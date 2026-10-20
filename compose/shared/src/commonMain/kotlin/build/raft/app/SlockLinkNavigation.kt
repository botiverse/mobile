package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import build.raft.app.base.BridgeModule
import build.raft.app.data.KeyValueLinkOpenPreferenceStore
import build.raft.app.data.LinkOpenMode
import build.raft.app.data.PersistentLinkOpenPreferenceRepository
import build.raft.app.data.SlockShellStoreScope
import build.raft.app.data.slockSharedPreferenceStore
import build.raft.app.resources.MR
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import com.tencent.tmm.kmmresource.compose.stringResource

internal enum class SlockLinkOpenIntent {
    Ordinary,
    ForceExternal,
}

internal enum class SlockLinkOpenTarget {
    InAppBrowser,
    ExternalBrowser,
    Rejected,
}

internal enum class SlockLinkRejectReason {
    Blank,
    Invalid,
    UnsupportedScheme,
    RouteUnavailable,
}

internal data class SlockLinkOpenDecision(
    val target: SlockLinkOpenTarget,
    val url: String = "",
    val rejectReason: SlockLinkRejectReason? = null,
)

internal enum class SlockInAppBrowserRouteScope {
    InheritShell,
    Unscoped,
}

internal fun resolveSlockLinkOpenDecision(
    rawUrl: String,
    mode: LinkOpenMode,
    intent: SlockLinkOpenIntent = SlockLinkOpenIntent.Ordinary,
): SlockLinkOpenDecision {
    val url = rawUrl.trim()
    if (url.isBlank()) return rejectedLink(SlockLinkRejectReason.Blank)
    if (url.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) {
        return rejectedLink(SlockLinkRejectReason.Invalid)
    }

    val scheme = url.slockAbsoluteSchemeOrNull()
        ?: return rejectedLink(SlockLinkRejectReason.Invalid)
    return when (scheme) {
        "http", "https" -> {
            if (!url.hasValidHttpAuthority(scheme)) {
                rejectedLink(SlockLinkRejectReason.Invalid)
            } else if (intent == SlockLinkOpenIntent.ForceExternal || mode == LinkOpenMode.SystemBrowser) {
                SlockLinkOpenDecision(SlockLinkOpenTarget.ExternalBrowser, url)
            } else {
                SlockLinkOpenDecision(SlockLinkOpenTarget.InAppBrowser, url)
            }
        }
        "mailto", "tel" -> {
            if (url.substringAfter(':', missingDelimiterValue = "").isBlank()) {
                rejectedLink(SlockLinkRejectReason.Invalid)
            } else {
                SlockLinkOpenDecision(SlockLinkOpenTarget.ExternalBrowser, url)
            }
        }
        else -> rejectedLink(SlockLinkRejectReason.UnsupportedScheme)
    }
}

private fun rejectedLink(reason: SlockLinkRejectReason): SlockLinkOpenDecision =
    SlockLinkOpenDecision(
        target = SlockLinkOpenTarget.Rejected,
        rejectReason = reason,
    )

private fun String.slockAbsoluteSchemeOrNull(): String? {
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

internal fun slockInAppBrowserDisplayUrl(rawUrl: String): String {
    val url = rawUrl.trim()
    val queryIndex = url.indexOf('?').takeIf { it >= 0 }
    val fragmentIndex = url.indexOf('#').takeIf { it >= 0 }
    val end = listOfNotNull(queryIndex, fragmentIndex).minOrNull() ?: url.length
    return url.substring(0, end)
}

internal fun slockInAppBrowserPageData(rawUrl: String): JSONObject? {
    val decision = resolveSlockLinkOpenDecision(rawUrl, LinkOpenMode.InAppBrowser)
    if (decision.target != SlockLinkOpenTarget.InAppBrowser) return null
    return JSONObject()
        .put("pageName", SLOCK_IN_APP_BROWSER_PAGE)
        .put(SLOCK_IN_APP_BROWSER_URL_KEY, decision.url)
}

internal fun JSONObject.slockInAppBrowserUrlOrNull(): String? =
    slockInAppBrowserPageData(optString(SLOCK_IN_APP_BROWSER_URL_KEY))
        ?.optString(SLOCK_IN_APP_BROWSER_URL_KEY)
        ?.takeIf { it.isNotBlank() }

internal fun Pager.openSlockInAppBrowserPage(
    rawUrl: String,
    storeScopeOverride: SlockShellStoreScope? = null,
): Boolean {
    val pageData = slockInAppBrowserPageData(rawUrl) ?: return false
    return openSlockPage(
        pageName = SLOCK_IN_APP_BROWSER_PAGE,
        pageData = pageData,
        storeScopeOverride = storeScopeOverride,
    )
}

internal fun Pager.openUnscopedSlockInAppBrowserPage(rawUrl: String): Boolean {
    val pageData = slockInAppBrowserPageData(rawUrl) ?: return false
    acquireModule<RouterModule>(RouterModule.MODULE_NAME)
        .openPage(SLOCK_IN_APP_BROWSER_PAGE, pageData)
    return true
}

internal class SlockLinkNavigator(
    private val modeProvider: () -> LinkOpenMode,
    private val openInAppBrowser: (String) -> Boolean,
    private val openExternalBrowser: (String) -> Unit,
    private val onRejected: (SlockLinkRejectReason) -> Unit,
) {
    fun open(
        rawUrl: String,
        intent: SlockLinkOpenIntent = SlockLinkOpenIntent.Ordinary,
    ): SlockLinkOpenDecision {
        val decision = resolveSlockLinkOpenDecision(rawUrl, modeProvider(), intent)
        return when (decision.target) {
            SlockLinkOpenTarget.InAppBrowser -> {
                if (openInAppBrowser(decision.url)) {
                    decision
                } else {
                    onRejected(SlockLinkRejectReason.RouteUnavailable)
                    rejectedLink(SlockLinkRejectReason.RouteUnavailable)
                }
            }
            SlockLinkOpenTarget.ExternalBrowser -> {
                openExternalBrowser(decision.url)
                decision
            }
            SlockLinkOpenTarget.Rejected -> {
                onRejected(requireNotNull(decision.rejectReason))
                decision
            }
        }
    }
}

@Composable
internal fun rememberSlockLinkNavigator(
    pager: Pager,
    bridge: BridgeModule,
    routeScope: SlockInAppBrowserRouteScope = SlockInAppBrowserRouteScope.InheritShell,
): SlockLinkNavigator {
    val preferenceRepository =
        remember {
            PersistentLinkOpenPreferenceRepository(
                KeyValueLinkOpenPreferenceStore(slockSharedPreferenceStore())
            )
        }
    val rejectedMessage = stringResource(MR.strings.in_app_browser_unable_to_open_link)
    return remember(pager, bridge, preferenceRepository, routeScope, rejectedMessage) {
        SlockLinkNavigator(
            modeProvider = preferenceRepository::getLinkOpenModeSync,
            openInAppBrowser = { url ->
                when (routeScope) {
                    SlockInAppBrowserRouteScope.InheritShell -> pager.openSlockInAppBrowserPage(url)
                    SlockInAppBrowserRouteScope.Unscoped -> pager.openUnscopedSlockInAppBrowserPage(url)
                }
            },
            openExternalBrowser = { url -> bridge.openUrl(url) },
            onRejected = { reason ->
                bridge.logWarn("link_open_rejected reason=${reason.name.lowercase()}")
                bridge.toast(rejectedMessage)
            },
        )
    }
}
