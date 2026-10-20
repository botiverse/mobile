package build.raft.app.network

import kotlin.concurrent.Volatile

/** Set by [installIosRaftNetworkTransportAtStartup] once VBTransportCurl accepted the CA bundle. */
private object IosCurlTrust {
    @Volatile
    var caBundlePath: String? = null
}

internal fun rememberIosCurlCaBundlePath(path: String?) {
    IosCurlTrust.caBundlePath = path?.takeIf(String::isNotBlank)
}

/**
 * Task #154: why the iOS realtime host cannot use curl right now, or null when it can. Swift calls
 * this before [raftIosCreateCurlSocketIoSession] and stays on Socket.IO-Client-Swift otherwise.
 */
fun raftIosCurlSocketIoUnavailableReason(): String? =
    when {
        !raftCurlSocketIoSupported -> "curl_socket_io_unsupported"
        IosCurlTrust.caBundlePath == null -> "curl_trust_store_unconfigured"
        else -> null
    }

/**
 * Task #154: a curl Socket.IO session for the iOS realtime host, with the same pinned CA bundle as the
 * curl HTTP path and the same direct (no proxy) policy, and the DoH fallback providers on
 * VBTransportCurl (tried only after the system resolver fails). Callbacks arrive on the native
 * connection thread; the session reconnects by itself, so the host must not run its own backoff.
 */
fun raftIosCreateCurlSocketIoSession(
    serverUrl: String,
    authJson: String,
    headers: Map<String, String>,
    listener: RaftCurlSocketIoListener,
): RaftCurlSocketIoSession? {
    val caBundlePath = IosCurlTrust.caBundlePath ?: return null
    return createRaftCurlSocketIoSession(
        serverUrl = serverUrl,
        authJson = authJson,
        headers = headers,
        caInfoPath = caBundlePath,
        proxyUrl = "",
        listener = listener,
    )
}

/** For Swift: the per-connect decision (Settings switch, else the server flag). */
fun raftIosCurlSocketIoEnabledFor(serverId: String): Boolean = RaftCurlSocketIoPolicy.isEnabledFor(serverId)
