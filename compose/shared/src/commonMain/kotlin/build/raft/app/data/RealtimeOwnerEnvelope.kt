package build.raft.app.data

data class RealtimeOwnerEpoch(
    val serverId: String,
    val memberId: String,
    val connectionEpoch: Long,
)

data class RealtimeOwnerEnvelope<out T>(
    val owner: RealtimeOwnerEpoch,
    val payload: T,
)
