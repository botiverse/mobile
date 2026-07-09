package build.raft.app

import build.raft.app.data.SlockShellStoreScope
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

internal const val SLOCK_SHELL_INSTANCE_ID = "slockShellInstanceId"
internal const val SLOCK_SHELL_SERVER_ID = "serverId"

/** Identity-only launch contract shared by AppShell and its child routes. */
internal fun JSONObject.slockShellStoreScope(fallbackShellInstanceId: String): SlockShellStoreScope =
    SlockShellStoreScope.create(
        shellInstanceId = optString(SLOCK_SHELL_INSTANCE_ID).ifBlank { fallbackShellInstanceId },
        initialServerId = optString(SLOCK_SHELL_SERVER_ID).takeIf { it.isNotBlank() }
    )
