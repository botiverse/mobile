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
    const val Connect = ai.slock.compose.data.realtime.RealtimeSocketEvents.Connect
    const val ConnectError = ai.slock.compose.data.realtime.RealtimeSocketEvents.ConnectError
    const val Disconnect = ai.slock.compose.data.realtime.RealtimeSocketEvents.Disconnect
    const val Reconnect = ai.slock.compose.data.realtime.RealtimeSocketEvents.Reconnect
    const val ReconnectAttempt = ai.slock.compose.data.realtime.RealtimeSocketEvents.ReconnectAttempt
    const val ReconnectError = ai.slock.compose.data.realtime.RealtimeSocketEvents.ReconnectError
    const val ReconnectFailed = ai.slock.compose.data.realtime.RealtimeSocketEvents.ReconnectFailed
    const val RoomsJoined = ai.slock.compose.data.realtime.RealtimeSocketEvents.RoomsJoined
    const val SyncResume = ai.slock.compose.data.realtime.RealtimeSocketEvents.SyncResume
    const val SyncResumeResponse = ai.slock.compose.data.realtime.RealtimeSocketEvents.SyncResumeResponse
    const val JoinChannel = ai.slock.compose.data.realtime.RealtimeSocketEvents.JoinChannel
    const val Heartbeat = ai.slock.compose.data.realtime.RealtimeSocketEvents.Heartbeat
    const val MessageNew = ai.slock.compose.data.realtime.RealtimeSocketEvents.MessageNew
    const val MessageUpdated = ai.slock.compose.data.realtime.RealtimeSocketEvents.MessageUpdated
    const val DirectMessageNew = ai.slock.compose.data.realtime.RealtimeSocketEvents.DirectMessageNew
    const val ThreadUpdated = ai.slock.compose.data.realtime.RealtimeSocketEvents.ThreadUpdated
    const val TaskCreated = ai.slock.compose.data.realtime.RealtimeSocketEvents.TaskCreated
    const val TaskUpdated = ai.slock.compose.data.realtime.RealtimeSocketEvents.TaskUpdated
    const val TaskDeleted = ai.slock.compose.data.realtime.RealtimeSocketEvents.TaskDeleted
    const val AgentActivity = ai.slock.compose.data.realtime.RealtimeSocketEvents.AgentActivity
    const val ServerMemberAdded = ai.slock.compose.data.realtime.RealtimeSocketEvents.ServerMemberAdded
    const val ServerMemberUpdated = ai.slock.compose.data.realtime.RealtimeSocketEvents.ServerMemberUpdated
    const val ServerMemberLeft = ai.slock.compose.data.realtime.RealtimeSocketEvents.ServerMemberLeft
    const val ServerMemberRemoved = ai.slock.compose.data.realtime.RealtimeSocketEvents.ServerMemberRemoved
}
