package ai.slock.android.core.data.realtime

import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ServerId

data class RealtimeAuth(
    val token: String,
    val serverId: ServerId
)

data class RealtimeConnectRequest(
    val auth: RealtimeAuth,
    val lastSeq: Long = 0
)

data class RealtimeChannelGap(
    val channelId: ChannelId,
    val sinceSeq: Long
)

data class RealtimeResumeRequest(
    val lastSeq: Long,
    val channelGaps: List<RealtimeChannelGap> = emptyList()
) {
    fun toWireMap(): Map<String, Any> =
        buildMap {
            put("lastSeq", lastSeq)
            if (channelGaps.isNotEmpty()) {
                put(
                    "channelGaps",
                    channelGaps.map { gap ->
                        mapOf(
                            "channelId" to gap.channelId.value,
                            "sinceSeq" to gap.sinceSeq
                        )
                    }
                )
            }
        }
}

sealed interface RealtimeConnectionState {
    data object Disconnected : RealtimeConnectionState
    data object Connecting : RealtimeConnectionState
    data object Connected : RealtimeConnectionState
    data object RoomsJoined : RealtimeConnectionState
    data class Failed(val reason: String) : RealtimeConnectionState
}

object RealtimeSocketEvents {
    const val Connect = "connect"
    const val ConnectError = "connect_error"
    const val Disconnect = "disconnect"
    const val RoomsJoined = "rooms:joined"
    const val SyncResume = "sync:resume"
    const val SyncResumeResponse = "sync:resume:response"
    const val JoinChannel = "join:channel"
    const val Heartbeat = "heartbeat"
    const val MessageNew = "message:new"
    const val MessageUpdated = "message:updated"
    const val ThreadUpdated = "thread:updated"
    const val TaskCreated = "task:created"
    const val TaskUpdated = "task:updated"
    const val TaskDeleted = "task:deleted"
}
