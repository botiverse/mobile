package ai.slock.android.core.data.realtime

import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.RealtimeEvent
import ai.slock.android.core.model.ServerId
import ai.slock.compose.data.DataResult

interface RealtimeRepository {
    suspend fun connect(serverId: ServerId, lastSeq: Long = 0): DataResult<Unit>
    fun disconnect()
    fun joinChannel(channelId: ChannelId)
    fun observeEvents(listener: (RealtimeEvent) -> Unit): RealtimeSubscription
}

fun interface RealtimeSubscription {
    fun close()
}
