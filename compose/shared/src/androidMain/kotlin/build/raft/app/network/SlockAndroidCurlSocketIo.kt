package build.raft.app.network

import com.tencent.kmm.network.export.VBTransportAndroidCurl
import com.tencent.kmm.network.socketio.NetworkSocketIoClient
import com.tencent.kmm.network.socketio.NetworkSocketIoConfig
import com.tencent.kmm.network.socketio.NetworkSocketIoFactory
import com.tencent.kmm.network.socketio.NetworkSocketIoListener
import com.tencent.kmm.network.socketio.NetworkSocketIoState
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI

/** Task #154: one curl Socket.IO connection (with its own reconnect loop) as seen by androidApp. */
interface SlockCurlSocketIoSession {
    fun start(): Boolean

    fun emit(eventName: String, payloadJson: String): Boolean

    /** Blocks until the native connection thread exits; never call it on a thread a callback holds. */
    fun close()
}

enum class SlockCurlSocketIoState {
    Connecting,
    EngineOpen,
    Connected,
    Disconnected,
    Reconnecting,
    Error,
}

interface SlockCurlSocketIoListener {
    fun onState(state: SlockCurlSocketIoState, code: Int, detail: String)

    /** [payloadJson] is the event's first argument as JSON text; `null` for an event without one. */
    fun onEvent(eventName: String, payloadJson: String)
}

/**
 * Task #154: NetworkKMM's curl Socket.IO client for the Android realtime transport. androidApp does
 * not see NetworkKMM types, so this is the seam. The connection uses the same pinned CA bundle as the
 * curl HTTP path, the Android system proxy like the old library, and the DoH fallback providers
 * currently set on VBTransportCurl (tried only after the system resolver fails).
 */
object SlockAndroidCurlSocketIo {
    /** Null when curl can carry realtime; otherwise why not (the caller stays on the old library). */
    fun unavailableReason(): String? =
        when {
            !NetworkSocketIoFactory.isSupported -> "curl_socket_io_unsupported"
            currentCaInfoPath() == null -> "curl_trust_store_unconfigured"
            else -> null
        }

    fun create(
        serverUrl: String,
        authJson: String,
        listener: SlockCurlSocketIoListener,
    ): SlockCurlSocketIoSession? {
        if (!NetworkSocketIoFactory.isSupported) return null
        val caInfoPath = currentCaInfoPath() ?: return null
        val client =
            NetworkSocketIoFactory.create(
                NetworkSocketIoConfig(
                    serverUrl = serverUrl,
                    authJson = authJson,
                    caInfoPath = caInfoPath,
                    proxyUrl = systemProxyUrl(serverUrl),
                ),
                object : NetworkSocketIoListener {
                    override fun onState(state: NetworkSocketIoState, code: Int, detail: String) {
                        listener.onState(state.toSlockState(), code, detail)
                    }

                    override fun onEvent(eventName: String, payloadJson: String) {
                        listener.onEvent(eventName, payloadJson)
                    }
                },
            )
        return ClientSession(client)
    }

    @Suppress("DEPRECATION")
    private fun currentCaInfoPath(): String? =
        runCatching { VBTransportAndroidCurl.caInfoPath }.getOrNull()?.takeIf(String::isNotBlank)

    /** Empty is direct (CURLOPT_PROXY ""), matching the old library when no system proxy is set. */
    internal fun systemProxyUrl(serverUrl: String): String {
        val uri =
            runCatching { URI(serverUrl.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")) }
                .getOrNull() ?: return ""
        val proxy =
            runCatching { ProxySelector.getDefault()?.select(uri).orEmpty() }
                .getOrDefault(emptyList())
                .firstOrNull { it.type() == Proxy.Type.HTTP }
                ?: return ""
        val address = proxy.address() as? InetSocketAddress ?: return ""
        return "http://${address.hostString}:${address.port}"
    }

    private class ClientSession(private val client: NetworkSocketIoClient) : SlockCurlSocketIoSession {
        override fun start(): Boolean = client.start()

        override fun emit(eventName: String, payloadJson: String): Boolean = client.emit(eventName, payloadJson)

        override fun close() = client.close()
    }

    private fun NetworkSocketIoState.toSlockState(): SlockCurlSocketIoState =
        when (this) {
            NetworkSocketIoState.CONNECTING -> SlockCurlSocketIoState.Connecting
            NetworkSocketIoState.ENGINE_OPEN -> SlockCurlSocketIoState.EngineOpen
            NetworkSocketIoState.CONNECTED -> SlockCurlSocketIoState.Connected
            NetworkSocketIoState.DISCONNECTED -> SlockCurlSocketIoState.Disconnected
            NetworkSocketIoState.RECONNECTING -> SlockCurlSocketIoState.Reconnecting
            NetworkSocketIoState.ERROR -> SlockCurlSocketIoState.Error
        }
}
