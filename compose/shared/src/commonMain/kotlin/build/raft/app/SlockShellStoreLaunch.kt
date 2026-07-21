package build.raft.app

import build.raft.app.data.SlockShellStoreScope
import build.raft.app.data.SlockSharedStoreRegistry
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.pager.Pager

internal const val SLOCK_SHELL_INSTANCE_ID = "slockShellInstanceId"
internal const val SLOCK_SHELL_SERVER_ID = "slockShellServerId"
private const val LEGACY_SLOCK_SHELL_SERVER_ID = "serverId"
internal const val SLOCK_DOCUMENT_SHELL = "slockDocumentShell"

internal fun appLifecycleEventTargetsShell(
    eventShellInstanceId: String,
    routeShellInstanceId: String
): Boolean =
    if (eventShellInstanceId.isBlank()) {
        routeShellInstanceId == SlockShellStoreScope.Legacy.shellInstanceId
    } else {
        eventShellInstanceId == routeShellInstanceId
    }

/** Identity-only launch contract shared by AppShell and its child routes. */
internal fun JSONObject.slockShellStoreScope(fallbackShellInstanceId: String): SlockShellStoreScope =
    SlockShellStoreScope.create(
        shellInstanceId = optString(SLOCK_SHELL_INSTANCE_ID).ifBlank { fallbackShellInstanceId },
        initialServerId = slockShellServerIdOrNull()
    )

internal fun JSONObject.slockShellStoreScopeOrNull(): SlockShellStoreScope? {
    val shellInstanceId = optString(SLOCK_SHELL_INSTANCE_ID).trim().takeIf { it.isNotBlank() } ?: return null
    return SlockShellStoreScope.create(
        shellInstanceId = shellInstanceId,
        initialServerId = slockShellServerIdOrNull()
    )
}

private fun JSONObject.slockShellServerIdOrNull(): String? =
    optString(SLOCK_SHELL_SERVER_ID).trim().takeIf { it.isNotBlank() }
        ?: optString(LEGACY_SLOCK_SHELL_SERVER_ID).trim().takeIf { it.isNotBlank() }

internal fun JSONObject.withSlockShellStoreScope(storeScope: SlockShellStoreScope): JSONObject =
    put(SLOCK_SHELL_INSTANCE_ID, storeScope.shellInstanceId)
        .also { data ->
            storeScope.initialServerId?.let { data.put(SLOCK_SHELL_SERVER_ID, it) }
        }

/**
 * The only production boundary for opening shell-owned child pages.
 * Feature page data carries target identity/intent; this facade inherits source ownership.
 */
internal fun Pager.openSlockPage(
    pageName: String,
    pageData: JSONObject,
    storeScopeOverride: SlockShellStoreScope? = null,
    onMissingScope: () -> Unit = {}
): Boolean {
    val storeScope = storeScopeOverride ?: sourcePageDataParams().slockShellStoreScopeOrNull()
    if (storeScope == null) {
        slockLogWarn(
            tag = "SlockScopedNavigation",
            message = "slock_scoped_navigation_rejected reason=missing_source_scope target=$pageName"
        )
        onMissingScope()
        return false
    }
    acquireModule<RouterModule>(RouterModule.MODULE_NAME)
        .openPage(pageName, pageData.withSlockShellStoreScope(storeScope))
    return true
}

private fun Pager.sourcePageDataParams(): JSONObject = pageData.params

/** Android lifecycle bridge; identity-only so platform code never reaches provider internals. */
fun releaseSlockShellStoreScope(shellInstanceId: String, serverId: String? = null) {
    val normalizedShellInstanceId = shellInstanceId.trim()
    if (normalizedShellInstanceId.isBlank()) return
    ProcessAttachmentAuthScopes.retireShell(normalizedShellInstanceId)
    SlockSharedStoreRegistry.currentOrNull()?.releaseShellStoreScope(
        SlockShellStoreScope.create(
            shellInstanceId = normalizedShellInstanceId,
            initialServerId = serverId?.trim()?.takeIf { it.isNotBlank() },
        )
    )
}
