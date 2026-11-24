package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.isAuthSessionBlocker
import build.raft.app.data.isEdgeBlockedMarker

internal fun DataResult<*>.isConversationAccessLost(): Boolean =
    when (this) {
        is DataResult.NoPermission ->
            !blocker.isAuthSessionBlocker() && !blocker.serverMessage.isEdgeBlockedMarker()
        is DataResult.Error -> error.code == "http_404"
        else -> false
    }
