package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.compat.pager.raftPlatformName
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.network.RaftAppIdentityRegistry
import build.raft.app.network.platformRaftUserAgentPlatformName
import build.raft.webcontainer.jsb.JsbAppInfo
import build.raft.webcontainer.jsb.JsbAuthStatus
import build.raft.webcontainer.jsb.JsbCallContext
import build.raft.webcontainer.jsb.JsbHapticStyle
import build.raft.webcontainer.jsb.JsbStandardCapabilities
import build.raft.webcontainer.jsb.JsbStandardHost
import build.raft.webcontainer.jsb.JsbThemeInfo
import build.raft.webcontainer.jsb.JsbToastDuration

/**
 * Safe standard JSB capability subset for `artifact_viewer` (RFC 5.3.3): exposes presentation,
 * clipboard, toast, haptic, external-link, and close capabilities while explicitly excluding
 * [JsbStandardCapabilities.AUTH_STATUS].
 */
internal val RaftArtifactViewerStandardJsbCapabilities: Set<String> =
    setOf(
        JsbStandardCapabilities.APP_INFO,
        JsbStandardCapabilities.UI_THEME,
        JsbStandardCapabilities.UI_HAPTIC,
        JsbStandardCapabilities.UI_CLIPBOARD,
        JsbStandardCapabilities.UI_TOAST,
        JsbStandardCapabilities.NAV_OPEN_EXTERNAL,
        JsbStandardCapabilities.NAV_CLOSE,
    )

/**
 * Least-privilege surface capability matrix for `:web-container`'s standard JSB pack.
 *
 * - Non-[RaftWebViewStoragePartition.Default] partitions (`Ephemeral`, `IsolatedThirdParty`) ALWAYS
 *   receive an empty set.
 * - First-party Settings surfaces (`settings_web_view`, `settings_web_page`) receive the full
 *   standard capability set.
 * - `artifact_viewer` receives only [RaftArtifactViewerStandardJsbCapabilities] (no `auth.status`).
 * - General browser (`in_app_browser`), HTML attachment preview (`file_preview_html`), and any
 *   unlisted surface fail closed to an empty set.
 */
internal fun resolveRaftSurfaceStandardJsbCapabilities(
    surfaceId: String,
    partition: RaftWebViewStoragePartition,
): Set<String> {
    if (partition != RaftWebViewStoragePartition.Default) return emptySet()
    return when (surfaceId.trim().lowercase()) {
        "settings_web_view",
        SETTINGS_WEB_PAGE_DIAGNOSTICS_NAME,
        -> JsbStandardCapabilities.All
        RAFT_ARTIFACT_VIEWER_WEBVIEW_SURFACE -> RaftArtifactViewerStandardJsbCapabilities
        else -> emptySet()
    }
}

/**
 * Default `:shared` implementation of `:web-container`'s [JsbStandardHost] bridging standard v1
 * JSB calls onto [RaftCmpHost] capabilities, theme state, non-secret session status, and App Store
 * billing URL compliance.
 */
@OptIn(RaftCoreInternalApi::class)
internal class RaftCmpJsbStandardHost(
    internal val boundHost: RaftCmpHost? = null,
    private val readAppInfo: () -> JsbAppInfo,
    private val isDarkTheme: () -> Boolean,
    private val performHaptic: (JsbHapticStyle) -> Boolean,
    private val copyToPasteboard: (String) -> Unit,
    private val showToastMessage: (message: String, brief: Boolean) -> Unit,
    private val openExternalBrowser: (String) -> Unit,
    private val closePage: () -> Unit,
    private val readAuthStatus: () -> JsbAuthStatus,
    private val followsAppStoreBillingRules: () -> Boolean = ::raftPlatformFollowsAppStoreBillingRules,
    private val isAppStoreRestrictedPurchaseUrl: (String) -> Boolean = { candidate ->
        raftUrlIsAppStoreRestrictedPurchaseUrl(candidate, activeWebBaseUrl = null)
    },
) : JsbStandardHost {
    override suspend fun getAppInfo(context: JsbCallContext): JsbAppInfo = readAppInfo()

    override suspend fun getThemeInfo(context: JsbCallContext): JsbThemeInfo =
        JsbThemeInfo(isDark = isDarkTheme())

    override suspend fun triggerHaptic(
        context: JsbCallContext,
        style: JsbHapticStyle,
    ): Boolean = performHaptic(style)

    override suspend fun copyText(
        context: JsbCallContext,
        text: String,
    ): Boolean {
        copyToPasteboard(text)
        return true
    }

    override suspend fun showToast(
        context: JsbCallContext,
        message: String,
        duration: JsbToastDuration,
    ): Boolean {
        showToastMessage(message, duration == JsbToastDuration.SHORT)
        return true
    }

    override suspend fun openExternalUrl(
        context: JsbCallContext,
        url: String,
    ): Boolean {
        if (followsAppStoreBillingRules() && isAppStoreRestrictedPurchaseUrl(url)) {
            raftLogWarn(
                RAFT_WEB_CONTAINER_LOG_TAG,
                "web_container_jsb_open_external_blocked reason=appstore_billing_rules surface=${context.surfaceId}",
            )
            return false
        }
        openExternalBrowser(url)
        return true
    }

    override suspend fun closeContainer(
        context: JsbCallContext,
        reason: String?,
    ): Boolean {
        closePage()
        return true
    }

    override suspend fun getAuthStatus(context: JsbCallContext): JsbAuthStatus = readAuthStatus()
}

@OptIn(RaftCoreInternalApi::class)
@Composable
internal fun rememberRaftCmpJsbStandardHost(host: RaftCmpHost): JsbStandardHost {
    val haptic: HapticFeedback = LocalHapticFeedback.current
    return remember(host, haptic) {
        RaftCmpJsbStandardHost(
            boundHost = host,
            readAppInfo = {
                val identity = RaftAppIdentityRegistry.current()
                JsbAppInfo(
                    appVersion = identity?.version.orEmpty(),
                    buildNumber = identity?.buildNumber.orEmpty(),
                    platform = raftPlatformName().lowercase(),
                    osVersion = runCatching { platformRaftUserAgentPlatformName() }.getOrDefault(""),
                    locale = runCatching { raftEmbeddedWebLangTag() }.getOrDefault(""),
                )
            },
            isDarkTheme = { RaftAppThemeState.isDark },
            performHaptic = { style ->
                val feedbackType =
                    when (style) {
                        JsbHapticStyle.HEAVY,
                        JsbHapticStyle.ERROR,
                        JsbHapticStyle.WARNING,
                        -> HapticFeedbackType.LongPress
                        JsbHapticStyle.LIGHT,
                        JsbHapticStyle.MEDIUM,
                        JsbHapticStyle.SELECTION,
                        JsbHapticStyle.SUCCESS,
                        -> HapticFeedbackType.TextHandleMove
                    }
                runCatching { haptic.performHapticFeedback(feedbackType) }.isSuccess
            },
            copyToPasteboard = { text -> host.capabilities.copyToPasteboard(text) },
            showToastMessage = { message, brief ->
                if (brief) {
                    host.capabilities.briefToast(message)
                } else {
                    host.capabilities.toast(message)
                }
            },
            openExternalBrowser = { url -> host.capabilities.openUrlInBrowser(url) },
            closePage = { host.navigator.closePage() },
            readAuthStatus = {
                val session =
                    RaftSharedStoreRegistry.currentOrNull()
                        ?.let { provider -> runCatching { provider.authStore.readCommittedSessionSnapshot() }.getOrNull() }
                val authenticated = !session?.accessToken.isNullOrBlank()
                val accountId = session?.user?.id?.trim()?.takeIf { it.isNotEmpty() }
                val serverId =
                    session?.currentServerId?.trim()?.takeIf { it.isNotEmpty() }
                        ?: session?.servers?.firstOrNull()?.id?.trim()?.takeIf { it.isNotEmpty() }
                JsbAuthStatus(
                    authenticated = authenticated,
                    accountId = if (authenticated) accountId else null,
                    serverId = if (authenticated) serverId else null,
                )
            },
            followsAppStoreBillingRules = ::raftPlatformFollowsAppStoreBillingRules,
            isAppStoreRestrictedPurchaseUrl = { candidate ->
                val webBaseUrl =
                    RaftSharedStoreRegistry.currentOrNull()
                        ?.let { provider -> runCatching { standaloneSettingsWebBaseUrl(provider.apiEnvironmentStore) }.getOrNull() }
                raftUrlIsAppStoreRestrictedPurchaseUrl(candidate, webBaseUrl)
            },
        )
    }
}
