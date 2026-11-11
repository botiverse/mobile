package build.raft.app

import build.raft.designsystem.theme.RaftTheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import build.raft.app.compat.module.RouterModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import build.raft.app.data.RaftShellStoreScope

internal const val SETTINGS_COMPUTER_ID = "settingsComputerId"

/**
 * Page flag: the Computers list opens its Add Computer dialog once its snapshot has loaded (web
 * Create Agent's "Add Computer" -> `setShowAddMachine(true)`).
 */
internal const val SETTINGS_COMPUTERS_OPEN_ADD = "settingsComputersOpenAdd"
internal const val SETTINGS_COMPUTERS_FILTER_ATTENTION = "settingsComputersFilterAttention"

/**
 * Identity-only target for the native Computers page. The page loads the
 * canonical machine snapshot itself; this is never a route payload carrying a
 * computer name, status, or any other content.
 */
internal data class SettingsComputerRouteIdentity(
    val serverId: String,
    val machineId: String,
)

internal fun settingsComputerRouteIdentity(
    serverId: String?,
    machineId: String?,
): SettingsComputerRouteIdentity? {
    val normalizedServerId = serverId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalizedMachineId = machineId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return SettingsComputerRouteIdentity(
        serverId = normalizedServerId,
        machineId = normalizedMachineId,
    )
}

internal fun settingsComputerRouteIdentityFromPageData(
    params: JSONObject,
): SettingsComputerRouteIdentity? =
    settingsComputerRouteIdentity(
        serverId = params.optString(RAFT_SHELL_SERVER_ID),
        machineId = params.optString(SETTINGS_COMPUTER_ID),
    )

/** Settings > Computers: the native [RaftSettingsComputersPage] list. */
internal fun settingsComputersPageData(storeScope: RaftShellStoreScope): JSONObject =
    settingsStandalonePageData(SETTINGS_COMPUTERS_PAGE, storeScope)

/** One machine's page: the native [RaftSettingsComputersPage] in detail mode. */
internal fun settingsComputerPageData(
    storeScope: RaftShellStoreScope,
    target: SettingsComputerRouteIdentity,
): JSONObject =
    settingsComputersPageData(storeScope).apply {
        put(RAFT_SHELL_SERVER_ID, target.serverId)
        put(SETTINGS_COMPUTER_ID, target.machineId)
    }

/**
 * [attention]: opened from a computer attention entry (a Home machine notice's View, web
 * `/computers?filter=attention`): the list starts at Status: Needs attention. Intent only.
 */
internal fun Pager.openSettingsComputersPage(storeScope: RaftShellStoreScope, openAddComputer: Boolean = false, attention: Boolean = false) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(
        SETTINGS_COMPUTERS_PAGE,
        settingsComputersPageData(storeScope).apply {
            if (openAddComputer) put(SETTINGS_COMPUTERS_OPEN_ADD, true)
            if (attention) put(SETTINGS_COMPUTERS_FILTER_ATTENTION, true)
        },
    )
}

internal fun Pager.openSettingsComputerPage(
    storeScope: RaftShellStoreScope,
    target: SettingsComputerRouteIdentity,
) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(
        SETTINGS_COMPUTERS_PAGE,
        settingsComputerPageData(storeScope, target),
    )
}

@Composable
internal fun SettingsComputersLoadingContent() {
    Box(modifier = Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas))
}
