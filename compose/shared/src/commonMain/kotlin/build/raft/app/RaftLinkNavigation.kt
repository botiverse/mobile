package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import build.raft.app.base.BridgeModule
import build.raft.app.data.KeyValueLinkOpenPreferenceStore
import build.raft.app.data.LinkOpenMode
import build.raft.app.data.PersistentLinkOpenPreferenceRepository
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.resources.MR
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import com.tencent.tmm.kmmresource.compose.stringResource

internal enum class RaftLinkOpenIntent {
    Ordinary,
    ForceExternal,
}

internal enum class RaftLinkOpenTarget {
    InAppBrowser,
    ExternalBrowser,
    Rejected,
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
) {
    fun open(
        rawUrl: String,
        intent: RaftLinkOpenIntent = RaftLinkOpenIntent.Ordinary,
    ): RaftLinkOpenDecision {
        val decision = resolveRaftLinkOpenDecision(rawUrl, modeProvider(), intent)
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
                openExternalBrowser(decision.url)
                decision
            }
            RaftLinkOpenTarget.Rejected -> {
                onRejected(requireNotNull(decision.rejectReason))
                decision
            }
        }
    }
}

@Composable
internal fun rememberRaftLinkNavigator(
    pager: Pager,
    bridge: BridgeModule,
    routeScope: RaftInAppBrowserRouteScope = RaftInAppBrowserRouteScope.InheritShell,
): RaftLinkNavigator {
    val preferenceRepository =
        remember {
            PersistentLinkOpenPreferenceRepository(
                KeyValueLinkOpenPreferenceStore(raftSharedPreferenceStore())
            )
        }
    val rejectedMessage = stringResource(MR.strings.in_app_browser_unable_to_open_link)
    return remember(pager, bridge, preferenceRepository, routeScope, rejectedMessage) {
        RaftLinkNavigator(
            modeProvider = preferenceRepository::getLinkOpenModeSync,
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
        )
    }
}
