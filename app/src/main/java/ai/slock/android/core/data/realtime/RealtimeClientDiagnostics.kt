package ai.slock.android.core.data.realtime

import ai.slock.android.core.model.RealtimeEvent
import ai.slock.android.core.model.ServerId

interface RealtimeClientDiagnostics {
    fun onConnectRequested(serverId: ServerId, lastSeq: Long)
    fun onConnected(serverId: ServerId?, serverSeq: Long? = null)
    fun onIntentionalDisconnect(reason: String, serverId: ServerId? = null)
    fun onDisconnected(reason: String, serverId: ServerId? = null)
    fun onConnectionFailed(reason: String, serverId: ServerId? = null)
    fun onEvent(event: RealtimeEvent, serverId: ServerId?, visibleThreadId: String?)
}
