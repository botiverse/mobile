package ai.slock.compose.data.realtime

import ai.slock.compose.model.RealtimeEvent
import ai.slock.compose.model.ServerId

interface RealtimeClientDiagnostics {
    fun onConnectRequested(serverId: ServerId, lastSeq: Long)
    fun onConnected(serverId: ServerId?, serverSeq: Long? = null)
    fun onRoomsJoined(serverId: ServerId?, lastSeq: Long? = null) = Unit
    fun onIntentionalDisconnect(reason: String, serverId: ServerId? = null)
    fun onDisconnected(reason: String, serverId: ServerId? = null)
    fun onConnectionFailed(reason: String, serverId: ServerId? = null)
    fun onEvent(event: RealtimeEvent, serverId: ServerId?, visibleThreadId: String?)
}
