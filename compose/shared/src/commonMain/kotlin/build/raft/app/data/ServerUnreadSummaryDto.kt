package build.raft.app.data

import kotlinx.serialization.Serializable

@Serializable
data class ServerUnreadSummaryDto(
    val serverId: String,
    val unreadCount: Int
)
