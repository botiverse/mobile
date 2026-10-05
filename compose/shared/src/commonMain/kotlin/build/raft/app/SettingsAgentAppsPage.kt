package build.raft.app

import build.raft.app.data.SlockShellStoreScope
import build.raft.app.data.StoredAuthSession
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager

internal const val SETTINGS_AGENT_APPS_ACCOUNT_ID = "settingsAgentAppsAccountId"
internal const val SETTINGS_AGENT_APPS_AGENT_ID = "settingsAgentAppsAgentId"

/** Only authority and target identity cross the route; Web owns all Apps data. */
internal data class SettingsAgentAppsRouteIdentity(
    val accountId: String,
    val serverId: String,
    val agentId: String,
)

internal fun settingsAgentAppsRouteIdentityFromPageData(params: JSONObject): SettingsAgentAppsRouteIdentity? {
    val accountId = params.optString(SETTINGS_AGENT_APPS_ACCOUNT_ID).takeIf { it.isNotBlank() } ?: return null
    val serverId = params.optString(SLOCK_SHELL_SERVER_ID).takeIf { it.isNotBlank() } ?: return null
    val agentId = params.optString(SETTINGS_AGENT_APPS_AGENT_ID).takeIf { it.settingsWebPathSegmentOrNull() != null } ?: return null
    return SettingsAgentAppsRouteIdentity(accountId, serverId, agentId)
}

internal fun settingsAgentAppsPageData(scope: SlockShellStoreScope, target: SettingsAgentAppsRouteIdentity): JSONObject =
    settingsWebViewPageData(SettingsWebViewSurface.AgentApps, scope).apply {
        put(SETTINGS_AGENT_APPS_ACCOUNT_ID, target.accountId)
        put(SLOCK_SHELL_SERVER_ID, target.serverId)
        put(SETTINGS_AGENT_APPS_AGENT_ID, target.agentId)
    }

internal fun Pager.openSettingsAgentAppsPage(scope: SlockShellStoreScope, target: SettingsAgentAppsRouteIdentity) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(
        SETTINGS_WEB_VIEW_PAGE, settingsAgentAppsPageData(scope, target),
    )
}

internal fun settingsAgentAppsWebViewUrl(
    webBaseUrl: String?, session: StoredAuthSession?, target: SettingsAgentAppsRouteIdentity,
): String? {
    if (session == null || session.user.id != target.accountId) return null
    val slug = session.servers.firstOrNull { it.id == target.serverId }?.slug?.settingsWebPathSegmentOrNull() ?: return null
    val agentId = target.agentId.settingsWebPathSegmentOrNull() ?: return null
    // Apps is the display label; the Web route contract calls this tab integrations.
    return sharedSettingsHostShellWebUrl(webBaseUrl, "s/$slug/agent/$agentId", listOf("agentTab" to "integrations"))
}

internal fun settingsAgentAppsWebViewSpec(target: SettingsAgentAppsRouteIdentity) =
    StandaloneSettingsWebViewSpec(
        surface = SettingsWebViewSurface.AgentApps,
        acceptsSession = { settingsAgentAppsWebViewUrl(null, it, target) != null },
        buildWebUrl = { base, session, _ -> settingsAgentAppsWebViewUrl(base, session, target).orEmpty() },
    )
