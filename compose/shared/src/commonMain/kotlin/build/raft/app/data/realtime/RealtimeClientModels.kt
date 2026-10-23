package build.raft.app.data.realtime

import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId

data class RealtimeAuth(
    val token: String,
    val serverId: ServerId
)

data class RealtimeConnectionWitnessToken(
    val scopeLeaseId: String,
    val principalId: String,
    val authGeneration: Long,
    val connectionFamilyId: String,
)

val UnboundRealtimeConnectionWitnessToken =
    RealtimeConnectionWitnessToken(
        scopeLeaseId = "",
        principalId = "",
        authGeneration = 0L,
        connectionFamilyId = "",
    )

data class RealtimeSocketConnectionContext(
    val serverId: ServerId,
    val witness: RealtimeConnectionWitnessToken,
)

data class RealtimeConnectRequest(
    val auth: RealtimeAuth,
    val lastSeq: Long = 0,
    val witness: RealtimeConnectionWitnessToken = UnboundRealtimeConnectionWitnessToken,
)

data class RealtimeChannelGap(
    val channelId: ChannelId,
    val sinceSeq: Long
)

data class RealtimeResumeRequest(
    val lastSeq: Long,
    val channelGaps: List<RealtimeChannelGap> = emptyList(),
    /** The held sync horizon, as text ([RealtimeSyncHorizon]); omitted when none is held. */
    val sinceHorizon: String? = null,
) {
    fun toWireMap(): Map<String, Any> =
        buildMap {
            put("lastSeq", lastSeq)
            sinceHorizon?.let { put(RealtimeSyncHorizon.SinceHorizonKey, it) }
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
