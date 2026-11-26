package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import kotlin.concurrent.Volatile

/** Live connection state reported by a native LiveKit voice room engine. */
@RaftCoreInternalApi
object RaftAgentVoiceEngineState {
    const val CONNECTING: String = "connecting"
    const val CONNECTED: String = "connected"
    const val RECONNECTING: String = "reconnecting"
    const val DISCONNECTED: String = "disconnected"
}

/** Reason a LiveKit room closed (mirrors web `@holtjs/live-client`). */
@RaftCoreInternalApi
object RaftAgentVoiceDisconnectReason {
    const val CLIENT_INITIATED: String = "client_initiated"
    const val SERVER_CLOSED: String = "server_closed"
    const val FAILED: String = "failed"
}

/** Callback sink delivered from shared store into the platform LiveKit engine. */
@RaftCoreInternalApi
interface RaftAgentVoiceEngineListener {
    fun onConnectionState(state: String)
    fun onAgentSpeaking(speaking: Boolean)
    fun onCaptionData(jsonText: String)
    fun onMicError(reason: String)
    fun onDisconnected(reason: String, detail: String?)
    fun onTokenRefreshNeeded()
}

/** Platform LiveKit room engine (registered at startup by Android and iOS hosts). */
@RaftCoreInternalApi
interface RaftAgentVoiceCallEngine {
    val isSupported: Boolean
    fun connect(
        callId: String,
        url: String,
        token: String,
        muted: Boolean,
        speakerOn: Boolean,
        listener: RaftAgentVoiceEngineListener
    )
    fun setMuted(muted: Boolean)
    fun setSpeakerOn(speakerOn: Boolean)
    fun updateToken(token: String)
    fun disconnect()
}

@RaftCoreInternalApi
object RaftAgentVoiceCallEngineRegistry {
    @Volatile
    private var current: RaftAgentVoiceCallEngine? = null

    fun register(engine: RaftAgentVoiceCallEngine) {
        current = engine
    }

    fun unregister() {
        current = null
    }

    fun currentOrNull(): RaftAgentVoiceCallEngine? = current

    fun isSupported(): Boolean = current?.isSupported == true
}
