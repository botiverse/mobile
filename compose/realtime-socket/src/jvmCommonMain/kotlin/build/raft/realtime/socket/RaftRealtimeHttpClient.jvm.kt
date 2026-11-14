package build.raft.realtime.socket

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import java.util.concurrent.TimeUnit

/**
 * WebSocket pings every [RAFT_REALTIME_PING_INTERVAL_SECONDS] fail a half-open socket (a silent
 * Wi-Fi/cellular NAT drop) in about 15-30 s, so the client reconnects instead of waiting for the
 * Engine.IO ping timeout. Reads are left to the ping: a quiet socket is healthy while pongs arrive.
 * Same values as the OkHttp client under the removed socket.io-client-java.
 */
internal const val RAFT_REALTIME_PING_INTERVAL_SECONDS = 15L
internal const val RAFT_REALTIME_CONNECT_TIMEOUT_SECONDS = 10L

actual fun createRaftRealtimeHttpClient(): HttpClient =
    HttpClient(OkHttp) {
        engine {
            config {
                pingInterval(RAFT_REALTIME_PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
                connectTimeout(RAFT_REALTIME_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                readTimeout(0L, TimeUnit.MILLISECONDS)
                writeTimeout(20L, TimeUnit.SECONDS)
                retryOnConnectionFailure(true)
            }
        }
        install(WebSockets)
    }
