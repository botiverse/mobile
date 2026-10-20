package build.raft.app.network

import com.tencent.kmm.network.socketio.NetworkSocketIoClient
import com.tencent.kmm.network.socketio.NetworkSocketIoConfig
import com.tencent.kmm.network.socketio.NetworkSocketIoFactory
import com.tencent.kmm.network.socketio.NetworkSocketIoListener
import com.tencent.kmm.network.socketio.NetworkSocketIoState

// Task #154: platform-neutral view of NetworkKMM's curl Socket.IO client. NetworkKMM types are not
// exported to androidApp or to the iOS framework, so each platform seam (RaftAndroidCurlSocketIo,
// raftIosCreateCurlSocketIoSession) hands out these plain types instead.

/** Task #154: one curl Socket.IO connection (with its own reconnect loop) as seen by a realtime host. */
interface RaftCurlSocketIoSession {
    fun start(): Boolean

    fun emit(eventName: String, payloadJson: String): Boolean

    /**
     * Blocks until the native connection thread exits: never call it while holding a lock a callback
     * may wait for, nor from a callback of another session.
     */
    fun close()
}

enum class RaftCurlSocketIoState {
    Connecting,
    EngineOpen,
    Connected,
    Disconnected,
    Reconnecting,
    Error,
}

interface RaftCurlSocketIoListener {
    fun onState(state: RaftCurlSocketIoState, code: Int, detail: String)

    /** [payloadJson] is the event's first argument as JSON text; `null` for an event without one. */
    fun onEvent(eventName: String, payloadJson: String)
}


/**
 * Creates a session over NetworkKMM's [NetworkSocketIoFactory] (the platform's curl client; DoH
 * fallback providers come from VBTransportCurl and are tried only after the system resolver fails).
 * Null when the platform has no curl Socket.IO client.
 */
internal fun createRaftCurlSocketIoSession(
    serverUrl: String,
    authJson: String,
    headers: Map<String, String>,
    caInfoPath: String?,
    proxyUrl: String,
    listener: RaftCurlSocketIoListener,
): RaftCurlSocketIoSession? {
    if (!NetworkSocketIoFactory.isSupported) return null
    val client =
        NetworkSocketIoFactory.create(
            NetworkSocketIoConfig(
                serverUrl = serverUrl,
                authJson = authJson,
                headers = headers,
                caInfoPath = caInfoPath,
                proxyUrl = proxyUrl,
            ),
            object : NetworkSocketIoListener {
                override fun onState(state: NetworkSocketIoState, code: Int, detail: String) {
                    listener.onState(state.toRaftState(), code, detail)
                }

                override fun onEvent(eventName: String, payloadJson: String) {
                    listener.onEvent(eventName, payloadJson)
                }
            },
        )
    return NetworkSocketIoSession(client)
}

internal val raftCurlSocketIoSupported: Boolean
    get() = NetworkSocketIoFactory.isSupported

private class NetworkSocketIoSession(private val client: NetworkSocketIoClient) : RaftCurlSocketIoSession {
    override fun start(): Boolean = client.start()

    override fun emit(eventName: String, payloadJson: String): Boolean = client.emit(eventName, payloadJson)

    override fun close() = client.close()
}

private fun NetworkSocketIoState.toRaftState(): RaftCurlSocketIoState =
    when (this) {
        NetworkSocketIoState.CONNECTING -> RaftCurlSocketIoState.Connecting
        NetworkSocketIoState.ENGINE_OPEN -> RaftCurlSocketIoState.EngineOpen
        NetworkSocketIoState.CONNECTED -> RaftCurlSocketIoState.Connected
        NetworkSocketIoState.DISCONNECTED -> RaftCurlSocketIoState.Disconnected
        NetworkSocketIoState.RECONNECTING -> RaftCurlSocketIoState.Reconnecting
        NetworkSocketIoState.ERROR -> RaftCurlSocketIoState.Error
    }
