package build.raft.app

import build.raft.app.data.SlockShellStoreScope
import build.raft.app.data.SlockSharedStoreRegistry
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

internal const val SLOCK_SHELL_INSTANCE_ID = "slockShellInstanceId"
internal const val SLOCK_SHELL_SERVER_ID = "serverId"
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
        initialServerId = optString(SLOCK_SHELL_SERVER_ID).takeIf { it.isNotBlank() }
    )

/** Android lifecycle bridge; identity-only so platform code never reaches provider internals. */
fun releaseSlockShellStoreScope(shellInstanceId: String, serverId: String? = null) {
    val normalizedShellInstanceId = shellInstanceId.trim()
    if (normalizedShellInstanceId.isBlank()) return
    SlockSharedStoreRegistry.currentOrNull()?.releaseShellStoreScope(
        SlockShellStoreScope.create(
            shellInstanceId = normalizedShellInstanceId,
            initialServerId = serverId?.trim()?.takeIf { it.isNotBlank() },
        )
    )
}
