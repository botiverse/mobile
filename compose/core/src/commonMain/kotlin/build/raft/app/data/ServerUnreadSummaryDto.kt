package build.raft.app.data

import kotlinx.serialization.Serializable

@Serializable
data class ServerUnreadSummaryDto(
    val serverId: String,
    val unreadCount: Int,
    val serverPushMuted: Boolean = false,
    val activityUnreadCount: Long? = null,
    /**
     * Workspace Artifacts (botiverse/slock `activityObjectsContract.ts` `ObjectUnreadSummaryFields`):
     * the server's unread object rows, apart from [activityUnreadCount]; absent while every object
     * app is off, or unknown.
     */
    val activityObjectUnreadCount: Long? = null,
) {
    /**
     * The Phase 1 wire contract reserves present non-negative integers for a
     * known Activity count. Invalid negative values fail closed to unknown so
     * they can never be rendered as either zero or a numeric badge. A known
     * count adds the object rows' unread (a client that shows object rows adds
     * it itself; the server never folds it in).
     */
    val knownActivityUnreadCount: Long?
        get() = activityUnreadCount?.takeIf { it >= 0 }?.let { it + (activityObjectUnreadCount?.takeIf { objects -> objects >= 0 } ?: 0L) }
}
