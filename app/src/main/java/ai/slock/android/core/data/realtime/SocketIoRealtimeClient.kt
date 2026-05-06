package ai.slock.android.core.data.realtime

import ai.slock.android.core.data.repository.RealtimeSubscription
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.RealtimeEvent

class SocketIoRealtimeClient(
    private val transport: RealtimeSocketTransport,
    private val eventMapper: RealtimeEventMapper = RealtimeEventMapper()
) {
    private val listeners = mutableSetOf<(RealtimeEvent) -> Unit>()
    private val transportSubscriptions = mutableListOf<RealtimeSubscription>()
    private var lastSeq: Long = 0
    var state: RealtimeConnectionState = RealtimeConnectionState.Disconnected
        private set

    fun connect(request: RealtimeConnectRequest) {
        lastSeq = request.lastSeq
        state = RealtimeConnectionState.Connecting
        ensureTransportListeners()
        transport.connect(
            mapOf(
                "token" to request.auth.token,
                "serverId" to request.auth.serverId.value
            )
        )
    }

    fun disconnect() {
        transport.disconnect()
        state = RealtimeConnectionState.Disconnected
    }

    fun joinChannel(channelId: ChannelId) {
        transport.emit(RealtimeSocketEvents.JoinChannel, mapOf("channelId" to channelId.value))
    }

    fun observeEvents(listener: (RealtimeEvent) -> Unit): RealtimeSubscription {
        listeners += listener
        return RealtimeSubscription { listeners -= listener }
    }

    private fun ensureTransportListeners() {
        if (transportSubscriptions.isNotEmpty()) return
        transportSubscriptions += transport.on(RealtimeSocketEvents.Connect) {
            state = RealtimeConnectionState.Connected
        }
        transportSubscriptions += transport.on(RealtimeSocketEvents.RoomsJoined) {
            state = RealtimeConnectionState.RoomsJoined
            if (lastSeq > 0) {
                transport.emit(RealtimeSocketEvents.SyncResume, RealtimeResumeRequest(lastSeq).toWireMap())
            }
        }
        transportSubscriptions += transport.on(RealtimeSocketEvents.ConnectError) { payload ->
            state = RealtimeConnectionState.Failed(payload?.toString().orEmpty())
        }
        transportSubscriptions += transport.on(RealtimeSocketEvents.Disconnect) {
            state = RealtimeConnectionState.Disconnected
        }
        RealtimeWireEventNames.forEach { eventName ->
            transportSubscriptions += transport.on(eventName) { payload ->
                eventMapper.map(eventName, payload)?.let(::dispatch)
            }
        }
    }

    private fun dispatch(event: RealtimeEvent) {
        if (event is RealtimeEvent.Heartbeat) lastSeq = maxOf(lastSeq, event.seq)
        if (event is RealtimeEvent.SyncResumeResponse) lastSeq = maxOf(lastSeq, event.currentSeq)
        listeners.toList().forEach { it(event) }
    }

    companion object {
        const val DEFAULT_SOCKET_URL = "https://api.slock.ai"

        val RealtimeWireEventNames = listOf(
            RealtimeSocketEvents.MessageNew,
            RealtimeSocketEvents.MessageUpdated,
            RealtimeSocketEvents.ThreadUpdated,
            RealtimeSocketEvents.TaskCreated,
            RealtimeSocketEvents.TaskUpdated,
            RealtimeSocketEvents.TaskDeleted,
            RealtimeSocketEvents.SyncResumeResponse,
            RealtimeSocketEvents.Heartbeat
        )
    }
}
