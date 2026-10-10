package build.raft.app.network

import com.tencent.kmm.network.export.VBTransportAndroidCurl
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI

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
            !slockCurlSocketIoSupported -> "curl_socket_io_unsupported"
            currentCaInfoPath() == null -> "curl_trust_store_unconfigured"
            else -> null
        }

    fun create(
        serverUrl: String,
        authJson: String,
        listener: SlockCurlSocketIoListener,
    ): SlockCurlSocketIoSession? {
        val caInfoPath = currentCaInfoPath() ?: return null
        return createSlockCurlSocketIoSession(
            serverUrl = serverUrl,
            authJson = authJson,
            headers = emptyMap(),
            caInfoPath = caInfoPath,
            proxyUrl = systemProxyUrl(serverUrl),
            listener = listener,
        )
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
}
