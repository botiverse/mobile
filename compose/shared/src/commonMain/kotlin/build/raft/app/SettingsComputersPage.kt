package build.raft.app

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

internal fun Pager.openSettingsComputersPage(storeScope: RaftShellStoreScope) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(
        SETTINGS_COMPUTERS_PAGE,
        settingsComputersPageData(storeScope),
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
    Box(modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceCream))
}
