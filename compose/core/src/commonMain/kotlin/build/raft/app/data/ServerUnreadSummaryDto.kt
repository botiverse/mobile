package build.raft.app.data

import kotlinx.serialization.Serializable

@Serializable
data class ServerUnreadSummaryDto(
    val serverId: String,
    val unreadCount: Int,
    val serverPushMuted: Boolean = false,
    val activityUnreadCount: Long? = null,
) {
    /**
     * The Phase 1 wire contract reserves present non-negative integers for a
     * known Activity count. Invalid negative values fail closed to unknown so
     * they can never be rendered as either zero or a numeric badge.
     */
    val knownActivityUnreadCount: Long?
        get() = activityUnreadCount?.takeIf { it >= 0 }
}
