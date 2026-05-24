package ai.slock.compose.data.realtime

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ServerId

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
