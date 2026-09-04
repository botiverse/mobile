package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.data.StoredAuthSession
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager

internal const val SETTINGS_COMPUTER_ID = "settingsComputerId"

/**
 * Identity-only target for the existing Computers Web surface. The embedded
 * Web app loads the canonical machine snapshot; this is never a route payload
 * carrying a computer name, status, or any other content.
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
        serverId = params.optString(SLOCK_SHELL_SERVER_ID),
        machineId = params.optString(SETTINGS_COMPUTER_ID),
    )

internal fun settingsComputersPageData(storeScope: build.raft.app.data.SlockShellStoreScope) =
    settingsWebViewPageData(SettingsWebViewSurface.Computers, storeScope)

internal fun settingsComputerPageData(
    storeScope: build.raft.app.data.SlockShellStoreScope,
    target: SettingsComputerRouteIdentity,
): JSONObject =
    settingsComputersPageData(storeScope).apply {
        put(SLOCK_SHELL_SERVER_ID, target.serverId)
        put(SETTINGS_COMPUTER_ID, target.machineId)
    }

internal fun Pager.openSettingsComputersPage(storeScope: build.raft.app.data.SlockShellStoreScope) {
    openSettingsWebViewPage(SettingsWebViewSurface.Computers, storeScope)
}

internal fun Pager.openSettingsComputerPage(
    storeScope: build.raft.app.data.SlockShellStoreScope,
    target: SettingsComputerRouteIdentity,
) {
    acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage(
        SETTINGS_WEB_VIEW_PAGE,
        settingsComputerPageData(storeScope, target),
    )
}

/**
 * Computers is owned by the Web settings surface. Mobile supplies only the
 * selected server identity and auth bootstrap, while the Web MVI/data layer
 * loads and mutates the canonical computer state.
 */
internal val settingsComputersWebViewSpec =
    StandaloneSettingsWebViewSpec(
        surface = SettingsWebViewSurface.Computers,
        headerFollowsDocumentTitle = true,
        buildWebUrl = { webBaseUrl, session, _ -> settingsComputersWebViewUrl(webBaseUrl, session) },
    )

internal fun settingsComputerWebViewSpec(
    target: SettingsComputerRouteIdentity,
): StandaloneSettingsWebViewSpec =
    settingsComputersWebViewSpec.copy(
        buildWebUrl = { webBaseUrl, session, _ ->
            settingsComputerWebViewUrl(webBaseUrl, session, target)
        },
    )

@Composable
internal fun SettingsComputersLoadingContent() {
    Box(modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceCream))
}

internal fun settingsComputersWebViewUrl(
    webBaseUrl: String?,
    session: StoredAuthSession?
): String {
    val serverSlug = standaloneSettingsServerSlug(session)
    val path =
        serverSlug
            ?.takeIf { it.isNotBlank() }
            ?.let { "s/$it/computers" }
            ?: "computers"
    return sharedSettingsHostShellWebUrl(webBaseUrl, path)
}

internal fun settingsComputerWebViewUrl(
    webBaseUrl: String?,
    session: StoredAuthSession?,
    target: SettingsComputerRouteIdentity,
): String {
    val serverSlug =
        session
            ?.servers
            ?.firstOrNull { it.id == target.serverId }
            ?.slug
            ?.settingsWebPathSegmentOrNull()
    val machineId = target.machineId.settingsWebPathSegmentOrNull()
    if (serverSlug == null || machineId == null) {
        // Never fall back to another server's machine detail. If the target
        // identity cannot be resolved from the authenticated session, land on
        // the safe Computers list instead.
        return settingsComputersWebViewUrl(webBaseUrl, session)
    }
    return sharedSettingsHostShellWebUrl(
        webBaseUrl,
        "s/$serverSlug/computer/$machineId",
    )
}

private fun String.settingsWebPathSegmentOrNull(): String? {
    val normalized = trim().takeIf { it.isNotBlank() } ?: return null
    if (normalized == "." || normalized == "..") return null
    if (normalized.any { it == '/' || it == '\\' || it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) {
        return null
    }
    val bytes = normalized.encodeToByteArray()
    return buildString(bytes.size) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            if (
                char in 'A'..'Z' ||
                char in 'a'..'z' ||
                char in '0'..'9' ||
                char == '-' ||
                char == '.' ||
                char == '_' ||
                char == '~'
            ) {
                append(char)
            } else {
                append('%')
                append(value.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
}

internal fun settingsComputersWebViewInjectedJavaScript(
    session: StoredAuthSession?,
    hostBinding: SettingsWebViewHostAuthBinding? = null,
): String {
    return standaloneSettingsWebViewInjectedJavaScript(session, hostBinding, SettingsWebViewSurface.Computers)
}
