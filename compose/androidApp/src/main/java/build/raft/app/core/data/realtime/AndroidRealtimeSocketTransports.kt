package build.raft.app.core.data.realtime

import build.raft.app.core.data.diagnostics.RaftAppLog
import build.raft.app.data.realtime.RaftKmpRealtimeSocketTransport
import build.raft.app.data.realtime.RaftRealtimeKmpClientFlag
import build.raft.app.data.realtime.RealtimeSocketTransportSelector
import build.raft.app.data.realtime.raftRealtimeKmpClientDecision

private const val REALTIME_KMP_LOG_TAG = "SlockRealtime"

/**
 * The Android realtime socket: the common Kotlin Socket.IO client ([RaftKmpRealtimeSocketTransport] over
 * `:realtime-socket`) when `realtime_kmp_client_v1` reads on as a connection is created
 * ([RaftRealtimeKmpClientFlag]: Settings > Developer switch, else the server, else the build default, on
 * for debug / alpha builds), socket.io-client-java ([SocketIoSocketTransport]) otherwise. Both run under
 * one generation fence lock and hand SocketIoRealtimeClient the same contract, so cursor, sync horizon,
 * resume, rejoin and presence are unchanged; `clientKind` stays `mobile`.
 *
 * Diagnostics: one `realtime_kmp_transport selected=kmp|legacy source=...` line per connection and the
 * common client's `realtime_kmp transport_*` lifecycle lines (always kept); its other lines, prefixed
 * `realtime_kmp `, need verbose diagnostics.
 */
internal fun createAndroidRealtimeSocketTransport(
    socketUrl: String,
    decision: () -> RaftRealtimeKmpClientFlag.Decision = ::raftRealtimeKmpClientDecision,
): RealtimeSocketTransportSelector {
    val fence = RealtimeSocketGenerationFence()
    return RealtimeSocketTransportSelector(
        linearizer = fence,
        legacy = SocketIoSocketTransport(socketUrl, fence),
        kmpFactory = {
            RaftKmpRealtimeSocketTransport(
                socketUrl = socketUrl,
                clientKind = "mobile",
                logger = { message ->
                    // Connection lifecycle lines are always kept (few per connection); the rest is verbose.
                    if (message.startsWith("transport_")) {
                        RaftAppLog.w(REALTIME_KMP_LOG_TAG, "realtime_kmp $message")
                    } else {
                        RaftAppLog.d(REALTIME_KMP_LOG_TAG, "realtime_kmp $message")
                    }
                },
                linearizer = fence,
            )
        },
        decision = decision,
        logger = { message -> RaftAppLog.w(REALTIME_KMP_LOG_TAG, message) },
    )
}
