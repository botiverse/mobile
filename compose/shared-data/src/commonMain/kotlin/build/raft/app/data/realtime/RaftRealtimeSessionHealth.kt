package build.raft.app.data.realtime

import build.raft.app.RaftCoreInternalApi

import build.raft.app.data.ProcessRaftDatetimeFoundationOwner
import build.raft.app.data.RaftClientTraceEvents
import build.raft.app.data.RaftWebSpanRecord
import build.raft.app.model.raftEpochMillisUtcIso
import build.raft.app.network.RaftTraceIds
import build.raft.app.network.RaftTransportFailureKind
import build.raft.app.network.classifyRaftKtorFailure
import build.raft.app.network.newRaftTraceIds
import build.raft.app.raftLogInfo
import build.raft.realtime.socket.RaftSocketConnection
import build.raft.realtime.socket.RaftSocketDisconnectReason
import build.raft.realtime.socket.RaftSocketEvent
import build.raft.realtime.socket.RaftSocketHandshakeError
import build.raft.realtime.socket.RaftSocketHandshakeErrorKind
import build.raft.realtime.socket.RaftSocketIncomingAck
import build.raft.realtime.socket.RaftSocketIoListener
import build.raft.realtime.socket.RaftWebSocketConnector
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * `mobile.realtime.session_health` (#proj-mobile:#172): one span per window saying how the realtime
 * (Socket.IO) connection of the signed-in server held up, so reconnect rate and time without realtime
 * can be charted per platform and app version. It goes through the client-trace pipeline as
 * `mobile.thread_open` does ([RaftClientTraceEvents.emitSpan], which adds `clientPlatform` and
 * `appVersion`; privacy mode permitting), and is never sampled (`sample_rate` 1).
 *
 * Window: opens while the app is in the foreground with a signed-in shell server
 * ([build.raft.app.data.RaftProductEvents.setSignedIn]); ends when the app goes to the background
 * (`background`), the shell moves to another server (`server_switch`), signs out or changes account
 * (`logout`), or after [RaftRealtimeSessionHealthWindowMillis] of continuous foreground (`periodic`;
 * the next window starts at that instant). Span start = window start, duration = `window_ms`. A
 * window shorter than [RaftRealtimeSessionHealthMinWindowMillis] with no connect activity is not
 * sent. Only foreground time counts: the keep-alive background connection of Android devices without
 * Play services is out of scope for v1. Nothing is persisted, so a window the OS kills the app in is
 * lost.
 *
 * Fed only by existing transitions: process visibility
 * ([build.raft.app.network.updateRaftProcessNetworkVisibility]; desktop never reports one and stays
 * in the foreground), the signed-in shell server, the host network status
 * ([build.raft.app.data.RaftHostNetworkEvents]; only Android sends one today), and every socket of
 * the common client through [RaftRealtimeSocketHealthProbe], which both shared facades install:
 * [RaftKmpRealtimeSocketTransport] (Android, desktop) and [RaftKmpRealtimeSocket] (iOS). Durations use
 * a monotonic clock.
 *
 * Attributes (integers unless noted):
 * - `window_ms`. `connected_ms`: a socket of the server connected and the network up (or not
 *   reported). `disconnected_ms`: the network up (or not reported) and no socket connected; opening
 *   and handshakes count here. `offline_ms`: the OS reports no network, whatever the socket says.
 *   The three always add up to `window_ms`.
 * - `longest_gap_ms`: the longest single stretch outside `connected_ms` (0 if none).
 * - `connects`: successful connects, the first included. `reconnects`: those after the server's
 *   connection ended earlier in the window (a drop, a server close, or the app replacing a dead
 *   socket). `reconnect_attempts`: retries the client scheduled, after a drop or a failed attempt.
 * - `handshake_failures`: attempts that ended before the namespace connected (the WebSocket or
 *   Engine.IO open failed or timed out, or the server rejected or did not answer the Socket.IO
 *   CONNECT). `last_error` (string): the [RaftRealtimeHealthError] code of the last failed attempt
 *   or dropped connection, "" if none; never error text, URLs or host names.
 * - `resume_ok` / `resume_fallback`: reconnects whose `sync:resume` answer replayed the gap, and those
 *   that fell back to a full refetch (`horizonReset: true`, or on Android and desktop a reconnect
 *   without a cursor, SocketIoRealtimeClient.requestCatchUp). The first outcome of a reconnect counts.
 * - `events_received` / `events_dropped`: server events on the server's sockets, and those the
 *   realtime client discarded there (not subscribed, malformed packet, outside a connection). Drops
 *   further up (payload decode, server-id scope) are not counted: each platform decodes an event in
 *   more than one place, so those counts would not compare across platforms.
 * - `network` (string): `none` when the OS reported no network for most of the window, else
 *   `unknown` (no platform hands its interface type to shared yet).
 * - `end` (string): [RaftRealtimeHealthWindowEnd].
 */
@RaftCoreInternalApi const val RaftRealtimeSessionHealthSpanName = "mobile.realtime.session_health"

/** A window of continuous foreground is cut here and the next one starts. */
@RaftCoreInternalApi const val RaftRealtimeSessionHealthWindowMillis = 30 * 60_000L

/** Shorter than this with no connect activity: nothing happened, nothing is sent. */
@RaftCoreInternalApi const val RaftRealtimeSessionHealthMinWindowMillis = 5_000L

@RaftCoreInternalApi enum class RaftRealtimeHealthWindowEnd(val wire: String) {
    Background("background"),
    Periodic("periodic"),
    ServerSwitch("server_switch"),
    Logout("logout"),
}

/**
 * `last_error`: a closed set of codes, each from a typed client signal. `http_5xx` is never sent:
 * neither Ktor engine hands the status of a refused WebSocket upgrade over as a value (OkHttp names it
 * only in its exception's message), so such a failure reads `unknown`.
 */
@RaftCoreInternalApi enum class RaftRealtimeHealthError(val wire: String) {
    /** No open, open packet or CONNECT answer in time, no server ping (ping timeout), or a transport timeout. */
    Timeout("timeout"),
    Dns("dns"),
    Tls("tls"),

    /** The connection could not be made (refused, unreachable), or a live WebSocket failed. */
    Network("network"),

    /** The server ended it: a Socket.IO server disconnect, an Engine.IO close packet, the WebSocket closed by the peer. */
    ClosedByServer("closed_by_server"),

    /** The handshake's token was refused by the server's socket auth (missing, wrong type, expired, changed): its 401. */
    Http401("http_401"),

    /** Refused for access (not a member of the server, SSO required), what the API answers with a 403. */
    Http4xx("http_4xx"),

    /** An unknown handshake message, a protocol error, an unclassified transport failure. */
    Unknown("unknown"),
}

@RaftCoreInternalApi fun RaftSocketHandshakeErrorKind.realtimeHealthError(): RaftRealtimeHealthError =
    when (this) {
        RaftSocketHandshakeErrorKind.AuthenticationRequired,
        RaftSocketHandshakeErrorKind.InvalidTokenType,
        RaftSocketHandshakeErrorKind.InvalidOrExpiredToken,
        RaftSocketHandshakeErrorKind.AuthenticationChanged -> RaftRealtimeHealthError.Http401
        RaftSocketHandshakeErrorKind.NotServerMember,
        RaftSocketHandshakeErrorKind.SsoRequired -> RaftRealtimeHealthError.Http4xx
        RaftSocketHandshakeErrorKind.Unknown -> RaftRealtimeHealthError.Unknown
    }

/** Why a connection ended or an attempt failed; null when the app itself ended it (not a failure). */
@RaftCoreInternalApi fun RaftSocketDisconnectReason.realtimeHealthError(): RaftRealtimeHealthError? =
    when (this) {
        RaftSocketDisconnectReason.ConnectTimeout,
        RaftSocketDisconnectReason.PingTimeout -> RaftRealtimeHealthError.Timeout
        RaftSocketDisconnectReason.TransportError -> RaftRealtimeHealthError.Network
        RaftSocketDisconnectReason.TransportClosed,
        RaftSocketDisconnectReason.EngineClose,
        RaftSocketDisconnectReason.ServerDisconnect -> RaftRealtimeHealthError.ClosedByServer
        RaftSocketDisconnectReason.HandshakeRejected,
        RaftSocketDisconnectReason.ProtocolError -> RaftRealtimeHealthError.Unknown
        RaftSocketDisconnectReason.Forced,
        RaftSocketDisconnectReason.ClientDisconnect,
        RaftSocketDisconnectReason.Stopped,
        RaftSocketDisconnectReason.NoCredentials -> null
    }

/** A failed WebSocket open as the app's HTTP transport classifies it; null for a cancellation. */
@RaftCoreInternalApi fun RaftTransportFailureKind.realtimeHealthError(): RaftRealtimeHealthError? =
    when (this) {
        RaftTransportFailureKind.TIMEOUT -> RaftRealtimeHealthError.Timeout
        RaftTransportFailureKind.DNS -> RaftRealtimeHealthError.Dns
        RaftTransportFailureKind.TLS -> RaftRealtimeHealthError.Tls
        RaftTransportFailureKind.CONNECT -> RaftRealtimeHealthError.Network
        RaftTransportFailureKind.UNKNOWN -> RaftRealtimeHealthError.Unknown
        RaftTransportFailureKind.CANCELLED -> null
    }

/** What the realtime sockets report ([RaftRealtimeSocketHealthProbe]); [socket] tells connections apart. */
@RaftCoreInternalApi interface RaftRealtimeSocketHealthSink {
    /** A Socket.IO CONNECT was sent. */
    fun onConnectAttempt(serverId: String)

    fun onConnected(serverId: String, socket: Any)

    /** An established connection ended; [error] null when the app ended it. */
    fun onDisconnected(serverId: String, socket: Any, error: RaftRealtimeHealthError?)

    /** An attempt ended before the namespace connected. */
    fun onAttemptFailed(serverId: String, error: RaftRealtimeHealthError)

    /** The client scheduled a retry (after a drop or a failed attempt). */
    fun onRetryScheduled(serverId: String)

    fun onEventReceived(serverId: String)

    /** A received event was discarded (it was reported by [onEventReceived] too). */
    fun onEventDropped(serverId: String)

    /** The resume of the latest reconnect replayed the gap ([ok]) or fell back to a full refetch. */
    fun onResumeOutcome(serverId: String, ok: Boolean)
}

/**
 * The windows and their counts ([RaftRealtimeSessionHealthSpanName]). Thread-safe: callers are the
 * sockets' own threads, the visibility owner and the shell. [emit] and [log] run outside the lock;
 * [onDeadline] runs under it, with the monotonic time the open window splits at (null when none is
 * open), so the latest value always wins.
 */
@RaftCoreInternalApi class RaftRealtimeSessionHealthCollector(
    private val nowMillis: () -> Long,
    private val nowEpochMillis: () -> Long,
    private val emit: (serverId: String, span: RaftWebSpanRecord) -> Unit,
    private val log: (String) -> Unit = {},
    private val newTraceIds: () -> RaftTraceIds = { newRaftTraceIds() },
    private val onDeadline: (Long?) -> Unit = {},
    private val windowMillis: Long = RaftRealtimeSessionHealthWindowMillis,
) : RaftRealtimeSocketHealthSink {
    init {
        require(windowMillis > 0L) { "windowMillis must be positive" }
    }

    private enum class Link { Connected, Disconnected, Offline }

    private class Ended(val serverId: String, val span: RaftWebSpanRecord, val line: String)

    /** The signed-in shell: its server, and the account it is signed in with. */
    private data class Session(val serverId: String, val account: String?)

    private class Window(val serverId: String, val startMs: Long, val startEpochMs: Long) {
        var markMs = startMs
        var connectedMs = 0L
        var disconnectedMs = 0L
        var offlineMs = 0L
        var gapStartMs = -1L
        var longestGapMs = 0L
        var connects = 0
        var reconnectAttempts = 0
        var reconnects = 0
        var handshakeFailures = 0
        var lastError: RaftRealtimeHealthError? = null
        var resumeOk = 0
        var resumeFallback = 0
        var eventsReceived = 0L
        var eventsDropped = 0L

        /** A connect attempt, connect, drop or retry happened in this window. */
        var activity = false

        /** The server's connection ended in this window: its next connect is a reconnect. */
        var dropPending = false

        /** The latest reconnect still waits for its resume outcome. */
        var resumePending = false

        /** Adds the time since the last mark to [link]'s bucket. */
        fun accrue(now: Long, link: Link) {
            val elapsed = now - markMs
            if (elapsed <= 0L) return
            when (link) {
                Link.Connected -> connectedMs += elapsed
                Link.Disconnected -> disconnectedMs += elapsed
                Link.Offline -> offlineMs += elapsed
            }
            markMs = now
        }

        /** A stretch without a connection starts or ends at [now]. */
        fun noteLink(now: Long, link: Link) {
            if (link != Link.Connected) {
                if (gapStartMs < 0L) gapStartMs = now
            } else {
                closeGap(now)
            }
        }

        fun closeGap(now: Long) {
            if (gapStartMs < 0L) return
            longestGapMs = maxOf(longestGapMs, now - gapStartMs)
            gapStartMs = -1L
        }
    }

    private val lock = SynchronizedObject()
    private var foreground = true
    private var session: Session? = null

    /** Last host network status; null until one is reported (iOS and desktop report none). */
    private var online: Boolean? = null
    private val connectedSockets = mutableMapOf<String, MutableSet<Any>>()
    private var window: Window? = null
    private val ended = mutableListOf<Ended>()

    /** The process visibility. Hidden ends the window (`background`); shown opens one when signed in. */
    fun onForeground(foreground: Boolean) =
        update { now ->
            if (this.foreground == foreground) return@update
            this.foreground = foreground
            reconcileWindowLocked(now, RaftRealtimeHealthWindowEnd.Background)
        }

    /** The signed-in shell's server ([account]: its user id). Another server is `server_switch`, another account `logout`. */
    fun onSignedIn(serverId: String, account: String?) =
        update { now ->
            val server = serverId.trim().takeIf { it.isNotEmpty() } ?: return@update
            val next = Session(server, account?.trim()?.takeIf { it.isNotEmpty() })
            val previous = session
            if (previous == next) return@update
            session = next
            val end =
                if (previous != null && previous.account != next.account) {
                    RaftRealtimeHealthWindowEnd.Logout
                } else {
                    RaftRealtimeHealthWindowEnd.ServerSwitch
                }
            reconcileWindowLocked(now, end)
        }

    fun onSignedOut() =
        update { now ->
            if (session == null) return@update
            session = null
            reconcileWindowLocked(now, RaftRealtimeHealthWindowEnd.Logout)
        }

    /** The OS network status ([build.raft.app.data.RaftHostNetworkEvents]). */
    fun onHostNetwork(online: Boolean) =
        update { now ->
            if (this.online == online) return@update
            transitionLocked(now) { this.online = online }
        }

    /** The window's deadline passed ([onDeadline]): split it. */
    fun tick() = update { }

    override fun onConnectAttempt(serverId: String) =
        update { windowFor(serverId)?.activity = true }

    override fun onConnected(serverId: String, socket: Any) =
        update { now ->
            transitionLocked(now) { connectedSockets.getOrPut(serverId) { mutableSetOf() }.add(socket) }
            val current = windowFor(serverId) ?: return@update
            current.activity = true
            current.connects += 1
            current.resumePending = current.dropPending
            if (current.dropPending) {
                current.reconnects += 1
                current.dropPending = false
            }
        }

    override fun onDisconnected(serverId: String, socket: Any, error: RaftRealtimeHealthError?) =
        update { now ->
            var lost = false
            transitionLocked(now) {
                val sockets = connectedSockets[serverId]
                if (sockets != null && sockets.remove(socket) && sockets.isEmpty()) {
                    connectedSockets.remove(serverId)
                    lost = true
                }
            }
            val current = windowFor(serverId) ?: return@update
            current.activity = true
            // Another socket of the server still up (a replacement connected first): realtime never went.
            if (lost) current.dropPending = true
            if (error != null) current.lastError = error
        }

    override fun onAttemptFailed(serverId: String, error: RaftRealtimeHealthError) =
        update {
            val current = windowFor(serverId) ?: return@update
            current.activity = true
            current.handshakeFailures += 1
            current.lastError = error
        }

    override fun onRetryScheduled(serverId: String) =
        update {
            val current = windowFor(serverId) ?: return@update
            current.activity = true
            current.reconnectAttempts += 1
        }

    override fun onEventReceived(serverId: String) =
        update { windowFor(serverId)?.let { it.eventsReceived += 1 } }

    override fun onEventDropped(serverId: String) =
        update { windowFor(serverId)?.let { it.eventsDropped += 1 } }

    override fun onResumeOutcome(serverId: String, ok: Boolean) =
        update {
            val current = windowFor(serverId)?.takeIf { it.resumePending } ?: return@update
            current.resumePending = false
            if (ok) current.resumeOk += 1 else current.resumeFallback += 1
        }

    /** Splits a due window first, runs [block] under the lock, then logs and emits what ended. */
    private fun update(block: (now: Long) -> Unit) {
        val out =
            synchronized(lock) {
                val now = nowMillis()
                rollLocked(now)
                block(now)
                onDeadline(window?.let { it.startMs + windowMillis })
                if (ended.isEmpty()) emptyList() else ended.toList().also { ended.clear() }
            }
        out.forEach { record ->
            runCatching { log(record.line) }
            runCatching { emit(record.serverId, record.span) }
        }
    }

    private fun windowFor(serverId: String): Window? = window?.takeIf { it.serverId == serverId }

    private fun linkLocked(serverId: String): Link =
        when {
            online == false -> Link.Offline
            connectedSockets[serverId]?.isNotEmpty() == true -> Link.Connected
            else -> Link.Disconnected
        }

    /** Accrues the open window in the state it had until [now], applies [change], then notes a gap starting or ending. */
    private inline fun transitionLocked(now: Long, change: () -> Unit) {
        val current = window
        current?.accrue(now, linkLocked(current.serverId))
        change()
        current?.noteLink(now, linkLocked(current.serverId))
    }

    private fun reconcileWindowLocked(now: Long, end: RaftRealtimeHealthWindowEnd) {
        val wanted = session?.serverId?.takeIf { foreground }
        val current = window
        if (current != null && (current.serverId != wanted || end == RaftRealtimeHealthWindowEnd.Logout)) {
            closeLocked(current, now, end)
            window = null
        }
        if (window == null && wanted != null) openLocked(wanted, now, nowEpochMillis(), carried = null)
    }

    /** Every [windowMillis] of one window: closed as `periodic`, the next one opens at the split. */
    private fun rollLocked(now: Long) {
        while (true) {
            val current = window ?: return
            val splitAt = current.startMs + windowMillis
            if (now < splitAt) return
            closeLocked(current, splitAt, RaftRealtimeHealthWindowEnd.Periodic)
            openLocked(current.serverId, splitAt, current.startEpochMs + windowMillis, carried = current)
        }
    }

    private fun openLocked(serverId: String, now: Long, startEpochMs: Long, carried: Window?) {
        val next = Window(serverId, now, startEpochMs)
        if (carried != null) {
            // The foreground went on: a drop just before the split still makes the next connect a reconnect.
            next.dropPending = carried.dropPending
            next.resumePending = carried.resumePending
        }
        next.noteLink(now, linkLocked(serverId))
        window = next
    }

    private fun closeLocked(current: Window, at: Long, end: RaftRealtimeHealthWindowEnd) {
        current.accrue(at, linkLocked(current.serverId))
        current.closeGap(at)
        val windowMs = (at - current.startMs).coerceAtLeast(0L)
        if (windowMs < RaftRealtimeSessionHealthMinWindowMillis && !current.activity) return
        val attrs = current.attrs(windowMs, end)
        val ids = newTraceIds()
        val span =
            RaftWebSpanRecord(
                traceId = ids.traceId,
                spanId = ids.spanId,
                name = RaftRealtimeSessionHealthSpanName,
                startTime = raftEpochMillisUtcIso(current.startEpochMs),
                endTime = raftEpochMillisUtcIso(current.startEpochMs + windowMs),
                attrs = JsonObject(attrs.mapValues { (_, value) -> value.toJsonPrimitive() }),
            )
        val line =
            "realtime_session_health serverId=${current.serverId} " +
                attrs.entries.joinToString(" ") { (key, value) -> "$key=${value.toString().ifEmpty { "none" }}" }
        ended += Ended(current.serverId, span, line)
    }

    private fun Window.attrs(windowMs: Long, end: RaftRealtimeHealthWindowEnd): Map<String, Any> =
        linkedMapOf(
            "window_ms" to windowMs,
            "connected_ms" to connectedMs,
            "disconnected_ms" to disconnectedMs,
            "offline_ms" to offlineMs,
            "longest_gap_ms" to longestGapMs,
            "connects" to connects,
            "reconnect_attempts" to reconnectAttempts,
            "reconnects" to reconnects,
            "handshake_failures" to handshakeFailures,
            "last_error" to (lastError?.wire ?: ""),
            "resume_ok" to resumeOk,
            "resume_fallback" to resumeFallback,
            "events_received" to eventsReceived,
            "events_dropped" to eventsDropped,
            "network" to (if (offlineMs > windowMs - offlineMs) "none" else "unknown"),
            "end" to end.wire,
            // Never sampled: every window is sent, so readers weight it 1 (docs/observability/trace-sampling.md).
            "sample_rate" to 1.0,
        )

    private fun Any.toJsonPrimitive(): JsonPrimitive =
        when (this) {
            is Number -> JsonPrimitive(this)
            else -> JsonPrimitive(toString())
        }
}

/**
 * One realtime socket's reporting, one per common-client connection (a [RaftKmpRealtimeSocket], or one
 * connection of a [RaftKmpRealtimeSocketTransport]). [listener] wraps the socket's own listener and
 * [connector] the WebSocket connector, which names a failed open (DNS, TLS, timeout). Both run on the
 * client's serial dispatcher, so this state needs no lock. Reporting never throws into the socket.
 */
@RaftCoreInternalApi class RaftRealtimeSocketHealthProbe(
    private val serverId: String,
    private val sink: RaftRealtimeSocketHealthSink = RaftRealtimeSessionHealth,
) {
    /** The connection ended since the last scheduled retry: that retry follows a drop, not a failed attempt. */
    private var endedSinceRetry = false

    /** How the last WebSocket open failed, for the retry that follows it. */
    private var openFailure: RaftRealtimeHealthError? = null

    /** The connection's first `sync:resume:response` was seen (or there is no connection). */
    private var resumeAnswered = true

    fun connector(delegate: RaftWebSocketConnector): RaftWebSocketConnector =
        RaftWebSocketConnector { url, headers ->
            try {
                delegate.connect(url, headers)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                openFailure = runCatching { classifyRaftKtorFailure(error).realtimeHealthError() }.getOrNull()
                throw error
            }
        }

    fun listener(delegate: RaftSocketIoListener): RaftSocketIoListener = Listener(delegate)

    /** A delivered event no transport listener took (Android and desktop): dropped above the socket. */
    fun onEventUnhandled() {
        try {
            sink.onEventDropped(serverId)
        } catch (_: Throwable) {
            // Telemetry never affects the connection.
        }
    }

    private inner class Listener(private val delegate: RaftSocketIoListener) : RaftSocketIoListener {
        private inline fun report(block: () -> Unit) {
            try {
                block()
            } catch (_: Throwable) {
                // Telemetry never affects the connection.
            }
        }

        override fun onConnecting(attempt: Int) {
            endedSinceRetry = false
            report { sink.onConnectAttempt(serverId) }
            delegate.onConnecting(attempt)
        }

        override fun onConnected(connection: RaftSocketConnection) {
            endedSinceRetry = false
            openFailure = null
            resumeAnswered = false
            report { sink.onConnected(serverId, this@RaftRealtimeSocketHealthProbe) }
            delegate.onConnected(connection)
        }

        override fun onEvent(connection: RaftSocketConnection, event: RaftSocketEvent, ack: RaftSocketIncomingAck?) {
            report {
                sink.onEventReceived(serverId)
                if (event.name == RealtimeSocketEvents.SyncResumeResponse && !resumeAnswered) {
                    resumeAnswered = true
                    // botiverse/slock packages/server/src/socket/syncResume.ts: `horizonReset: true` sends no
                    // rows and asks the client to refetch its visible scope.
                    sink.onResumeOutcome(serverId, ok = !event.horizonReset())
                }
            }
            delegate.onEvent(connection, event, ack)
        }

        override fun onConnectError(error: RaftSocketHandshakeError, willRetry: Boolean) {
            report { sink.onAttemptFailed(serverId, error.kind.realtimeHealthError()) }
            delegate.onConnectError(error, willRetry)
        }

        override fun onDisconnected(connection: RaftSocketConnection, reason: RaftSocketDisconnectReason, detail: String) {
            endedSinceRetry = true
            report { sink.onDisconnected(serverId, this@RaftRealtimeSocketHealthProbe, reason.realtimeHealthError()) }
            delegate.onDisconnected(connection, reason, detail)
        }

        override fun onReconnectScheduled(attempt: Int, delayMillis: Long, reason: RaftSocketDisconnectReason) {
            val failure =
                when {
                    // The retry after a drop: onDisconnected reported it.
                    endedSinceRetry -> null
                    // onConnectError reported the rejection.
                    reason == RaftSocketDisconnectReason.HandshakeRejected -> null
                    reason == RaftSocketDisconnectReason.TransportError -> openFailure ?: RaftRealtimeHealthError.Network
                    else -> reason.realtimeHealthError()
                }
            endedSinceRetry = false
            openFailure = null
            report {
                sink.onRetryScheduled(serverId)
                failure?.let { sink.onAttemptFailed(serverId, it) }
            }
            delegate.onReconnectScheduled(attempt, delayMillis, reason)
        }

        override fun onEventDropped(name: String, reason: String) {
            if (reason.isReceivedEventDrop()) {
                report {
                    sink.onEventReceived(serverId)
                    sink.onEventDropped(serverId)
                }
            }
            delegate.onEventDropped(name, reason)
        }
    }
}

/**
 * The [RaftSocketIoListener.onEventDropped] reasons that discard something the server sent
 * (RaftSocketIoClient: `recordUnlisted`, `onSocketIo`, `onEvent`, `dropReceiveBuffer`); the others
 * are emits or acks of the app's own.
 */
private fun String.isReceivedEventDrop(): Boolean =
    this == "unlisted" || this == "event_before_connect" || startsWith("buffered_") || startsWith("socketio_")

private fun RaftSocketEvent.horizonReset(): Boolean =
    ((args.firstOrNull() as? JsonObject)?.get(RealtimeSyncHorizon.HorizonResetKey) as? JsonPrimitive)?.booleanOrNull == true

/**
 * The process's [RaftRealtimeSessionHealthCollector]: the production clocks, the client-trace
 * pipeline, a diagnostics line per window and a timer for the 30-minute split that runs only while a
 * window is open. Every entry point swallows its failures: telemetry never affects the app.
 */
@RaftCoreInternalApi object RaftRealtimeSessionHealth : RaftRealtimeSocketHealthSink {
    private val clockOrigin = TimeSource.Monotonic.markNow()
    private val deadline = MutableStateFlow<Long?>(null)

    private fun nowMillis(): Long = clockOrigin.elapsedNow().inWholeMilliseconds

    private val collector: RaftRealtimeSessionHealthCollector by lazy {
        val created =
            RaftRealtimeSessionHealthCollector(
                nowMillis = ::nowMillis,
                nowEpochMillis = { ProcessRaftDatetimeFoundationOwner.nowEpochMillis() },
                emit = { serverId, span -> RaftClientTraceEvents.emitSpan(serverId, span, emptyList()) },
                log = { line -> raftLogInfo("SlockRealtime", line) },
                onDeadline = { at -> deadline.value = at },
            )
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            deadline.collectLatest { at ->
                if (at == null) return@collectLatest
                delay((at - nowMillis()).coerceAtLeast(0L))
                guarded { created.tick() }
            }
        }
        created
    }

    fun onProcessVisibility(foreground: Boolean) = guarded { collector.onForeground(foreground) }

    fun onSignedIn(serverId: String, account: String?) = guarded { collector.onSignedIn(serverId, account) }

    fun onSignedOut() = guarded { collector.onSignedOut() }

    fun onHostNetwork(online: Boolean) = guarded { collector.onHostNetwork(online) }

    override fun onConnectAttempt(serverId: String) = guarded { collector.onConnectAttempt(serverId) }

    override fun onConnected(serverId: String, socket: Any) = guarded { collector.onConnected(serverId, socket) }

    override fun onDisconnected(serverId: String, socket: Any, error: RaftRealtimeHealthError?) =
        guarded { collector.onDisconnected(serverId, socket, error) }

    override fun onAttemptFailed(serverId: String, error: RaftRealtimeHealthError) =
        guarded { collector.onAttemptFailed(serverId, error) }

    override fun onRetryScheduled(serverId: String) = guarded { collector.onRetryScheduled(serverId) }

    override fun onEventReceived(serverId: String) = guarded { collector.onEventReceived(serverId) }

    override fun onEventDropped(serverId: String) = guarded { collector.onEventDropped(serverId) }

    override fun onResumeOutcome(serverId: String, ok: Boolean) = guarded { collector.onResumeOutcome(serverId, ok) }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // Telemetry never affects the app.
        }
    }
}
