package build.raft.app.network

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

internal actual fun platformFailureCases(): List<Pair<Throwable, String>> =
    listOf(
        UnknownHostException("Unable to resolve host \"api.raft.test\"") to "DNS",
        ConnectException("Failed to connect to api.raft.test/203.0.113.1:443") to "CONNECT",
        SocketTimeoutException("timeout") to "TIMEOUT",
        SSLHandshakeException("Chain validation failed") to "TLS",
        // A reset on an open connection is not "host unreachable": it must not trip the reachability backoff.
        SocketException("Connection reset") to "UNKNOWN",
        IOException("Canceled") to "CANCELLED",
    )
