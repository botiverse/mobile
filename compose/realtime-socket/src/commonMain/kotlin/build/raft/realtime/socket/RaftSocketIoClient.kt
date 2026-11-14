package build.raft.realtime.socket

import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A Socket.IO v5 client over Engine.IO v4 on a WebSocket: the counterpart of socket.io-client's
 * `Manager` (one Engine.IO connection, reconnection) and its `Socket`s ([socket], one per namespace).
 *
 * Engine.IO: waits for the open packet and takes the heartbeat (`pingInterval`, `pingTimeout`) and
 * `maxPayload` from it, never from local defaults (a missing field falls back to the server default and
 * is logged). Answers every server ping with a pong and declares the link dead after
 * `pingInterval + pingTimeout` without one. WebSocket only: HTTP long-polling and the polling to
 * WebSocket upgrade are not implemented (`upgrades` in the open packet is ignored); per-message deflate
 * is whatever the WebSocket engine negotiates.
 *
 * Socket.IO: every packet type, including BINARY_EVENT / BINARY_ACK with attachments, acks in both
 * directions, any number of namespaces with their own `auth`, CONNECT_ERROR typed as
 * [RaftSocketHandshakeError], and connection state recovery (`pid` / `offset`).
 *
 * Lifecycle: the Engine.IO connection is open while at least one namespace socket wants to be
 * connected. Losing it reconnects with exponential backoff and jitter ([RaftReconnectPolicy]) and then
 * re-CONNECTs every namespace, asking each for fresh `auth`. A namespace rejected with a non-terminal
 * error retries on the same connection (immediately once when its token must be refreshed, then with
 * backoff); a terminal one waits for [kick]. [kick] retries now (network back, foreground);
 * [forceReconnect] replaces a live connection (an app-level heartbeat watchdog).
 *
 * Concurrency: all state lives on one serial dispatcher per client, so listeners see frames in order and
 * never concurrently. Public methods may be called from any thread; they hop onto that dispatcher.
 *
 * Nothing is dropped silently: unlisted events, unknown namespaces, malformed packets, stray binary
 * frames, stale acks and emits without a connection are logged (`realtime_event_dropped reason=...`)
 * and counted ([diagnostics]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RaftSocketIoClient(
    private val config: RaftSocketIoConfig,
    private val connector: RaftWebSocketConnector,
    parentScope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val logger: (String) -> Unit = {},
    private val random: Random = Random.Default,
    private val engineListener: RaftSocketEngineListener? = null,
) {
    internal val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + dispatcher)
    private val mutableEngineState = MutableStateFlow<RaftSocketEngineState>(RaftSocketEngineState.Idle)

    /** The Engine.IO connection's state; readable from any thread. */
    val engineState: StateFlow<RaftSocketEngineState> = mutableEngineState.asStateFlow()

    // Everything below is confined to the serial dispatcher.
    private val namespaces = linkedMapOf<String, RaftSocketIoNamespace>()
    private var closed = false
    private var loopJob: Job? = null
    private var nextGeneration = 0L
    private var engine: ActiveEngine? = null
    private var engineWake: CompletableDeferred<Unit>? = null
    private var engineFailures = 0
    private var immediateRetry = false
    private val droppedByReason = linkedMapOf<String, Int>()
    private val unlistedEvents = linkedMapOf<String, Int>()
    private val handshakeErrorCounts = linkedMapOf<String, Int>()
    private val connectCounts = linkedMapOf<String, Int>()
    private var engineOpens = 0

    /**
     * The socket of [namespace] (created on first use; later calls return the same socket and ignore
     * [options]). It does not connect until [RaftSocketIoNamespace.connect].
     */
    fun socket(namespace: String = SocketIoPacket.ROOT_NAMESPACE, options: RaftSocketNamespaceOptions): RaftSocketIoNamespace {
        val normalized = normalizeNamespace(namespace)
        // Creation is synchronous so the caller can connect right away; registration hops to the dispatcher.
        val created = RaftSocketIoNamespace(this, normalized, options)
        val registered = CompletableDeferred<RaftSocketIoNamespace>()
        scope.launch { registered.complete(namespaces.getOrPut(normalized) { created }) }
        return created.also { it.registration = registered }
    }

    /** Retry now: ends the engine backoff wait and every namespace's backoff or terminal wait. */
    fun kick(reason: String) {
        scope.launch {
            log("realtime_socket_kick reason=${reason.token()}")
            engineFailures = 0
            engineWake?.complete(Unit)
            namespaces.values.forEach { it.kickLocked() }
        }
    }

    /** Replaces the live Engine.IO connection now (no backoff); a [kick] when there is none. */
    fun forceReconnect(reason: String) {
        scope.launch {
            val active = engine
            if (active == null) {
                engineFailures = 0
                engineWake?.complete(Unit)
                return@launch
            }
            log("realtime_socket_force_reconnect reason=${reason.token()} generation=${active.generation}")
            immediateRetry = true
            active.end(RaftSocketDisconnectReason.Forced, reason)
        }
    }

    /** Disconnects every namespace and releases the client for good. */
    fun close() {
        scope.launch {
            if (closed) return@launch
            closed = true
            namespaces.values.forEach { it.deactivateLocked(RaftSocketDisconnectReason.Stopped, "close") }
            engine?.end(RaftSocketDisconnectReason.Stopped, "close")
            engineWake?.complete(Unit)
            loopJob?.join()
            mutableEngineState.value = RaftSocketEngineState.Closed
            scope.cancel()
        }
    }

    /** Runs [block] on the client's serial dispatcher, in order with its callbacks (for state they share). */
    fun runOnDispatcher(block: () -> Unit) {
        scope.launch { safely("run_on_dispatcher") { block() } }
    }

    /** Snapshot of the counters; [onResult] runs on the client's dispatcher. */
    fun diagnostics(onResult: (RaftSocketDiagnostics) -> Unit) {
        scope.launch {
            onResult(
                RaftSocketDiagnostics(
                    droppedByReason = droppedByReason.toMap(),
                    unlistedEvents = unlistedEvents.toMap(),
                    engineOpens = engineOpens,
                    connects = connectCounts.toMap(),
                    handshakeErrors = handshakeErrorCounts.toMap(),
                ),
            )
        }
    }

    // ---- namespace hooks (dispatcher) ----

    internal fun namespaceActivated() {
        if (closed) return
        if (loopJob?.isActive != true) loopJob = scope.launch { runEngineLoop() }
        engineWake?.complete(Unit)
        engine?.takeIf { it.open != null }?.let { active -> namespaces.values.forEach { it.engineOpenedLocked(active.generation) } }
    }

    /** Closes the Engine.IO connection once no namespace wants it (all disconnected or waiting for [kick]). */
    internal fun namespaceDeactivated() {
        val active = engine ?: return
        if (!anyWantsEngine()) active.end(RaftSocketDisconnectReason.ClientDisconnect, "no_active_namespace")
    }

    private fun anyWantsEngine(): Boolean = namespaces.values.any { it.active && !it.waitingForKickLocked }

    internal val currentGeneration: Long? get() = engine?.takeIf { it.open != null && !it.ended }?.generation

    /** Sends [packet] on the live engine; false (logged) without one or when a frame exceeds maxPayload. */
    internal fun sendPacket(packet: SocketIoPacket, name: String, socket: RaftSocketIoNamespace?): Boolean {
        val active = engine
        val open = active?.open
        if (active == null || open == null || active.ended) {
            drop(name, "not_connected", socket)
            return false
        }
        val encoded = SocketIoCodec.encodeWithAttachments(packet)
        val text = EIO_MESSAGE + encoded.text
        if (text.encodeToByteArray().size > open.maxPayload || encoded.attachments.any { it.bytes.size > open.maxPayload }) {
            drop(name, "oversize", socket)
            return false
        }
        if (active.outgoing.trySend(Outgoing.Text(text)).isFailure) {
            drop(name, "send_closed", socket)
            return false
        }
        encoded.attachments.forEach { active.outgoing.trySend(Outgoing.Binary(it.bytes)) }
        return true
    }

    internal fun reconnectDelay(failures: Int): Long = config.reconnect.delayMillis(failures, random)

    internal val connectTimeoutMillis: Long get() = config.connectTimeoutMillis

    internal fun countHandshakeError(kind: RaftSocketHandshakeErrorKind) = count(handshakeErrorCounts, kind.name)

    internal fun countConnect(namespace: String) = count(connectCounts, namespace)

    internal fun recordUnlisted(name: String, socket: RaftSocketIoNamespace, hasAck: Boolean) {
        val seen = count(unlistedEvents, name)
        count(droppedByReason, "unlisted")
        if (seen == 1) log("projection_value_unknown source=realtime_socket field=event_name value=${name.token()}")
        if (seen == 1 || seen % UNLISTED_LOG_EVERY == 0) {
            log("realtime_event_dropped reason=unlisted event=${name.token()} nsp=${socket.namespace.token()} count=$seen ack=$hasAck")
        }
        socket.notifyDropped(name, "unlisted")
    }

    internal fun drop(name: String, reason: String, socket: RaftSocketIoNamespace?, detail: String = "") {
        val total = count(droppedByReason, reason)
        if (total <= DROP_LOG_FIRST || total % DROP_LOG_EVERY == 0) {
            log(
                "realtime_event_dropped reason=$reason event=${name.token()}" +
                    (socket?.let { " nsp=${it.namespace.token()}" } ?: "") + " count=$total" +
                    if (detail.isEmpty()) "" else " $detail",
            )
        }
        if (socket != null) socket.notifyDropped(name, reason) else safely("on_frame_dropped") { engineListener?.onFrameDropped(reason) }
    }

    internal fun log(message: String) {
        runCatching { logger(message) }
    }

    internal inline fun safely(callback: String, block: () -> Unit) {
        try {
            block()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            log("realtime_socket_listener_failed callback=$callback error=${error::class.simpleName}")
        }
    }

    // ---- engine ----

    private suspend fun runEngineLoop() {
        var attempt = 0
        while (!closed) {
            if (!anyWantsEngine()) {
                mutableEngineState.value = RaftSocketEngineState.Idle
                attempt = 0
                engineFailures = 0
                awaitEngineWake(null)
                continue
            }
            attempt += 1
            mutableEngineState.value = RaftSocketEngineState.Connecting(attempt)
            namespaces.values.filter { it.active && !it.waitingForKickLocked }.forEach { it.engineConnectingLocked(attempt) }
            val outcome = runEngine(attempt)
            if (closed) break
            if (outcome.opened) {
                attempt = 0
                engineFailures = 0
            }
            if (!anyWantsEngine()) {
                immediateRetry = false
                continue
            }
            val delayMillis =
                if (immediateRetry) {
                    0L
                } else {
                    reconnectDelay(engineFailures).also { engineFailures += 1 }
                }
            immediateRetry = false
            log(
                "realtime_socket_reconnect_scheduled attempt=${attempt + 1} delayMs=$delayMillis " +
                    "reason=${outcome.reason.token} failures=$engineFailures",
            )
            mutableEngineState.value = RaftSocketEngineState.WaitingToReconnect(attempt + 1, delayMillis, outcome.reason.token)
            namespaces.values.filter { it.active && !it.waitingForKickLocked }.forEach { it.engineWaitingLocked(attempt + 1, delayMillis, outcome.reason) }
            if (delayMillis > 0L && awaitEngineWake(delayMillis)) attempt = 0
        }
    }

    /** True when [kick] (or activation / close) ended the wait early. */
    private suspend fun awaitEngineWake(timeoutMillis: Long?): Boolean {
        val signal = CompletableDeferred<Unit>()
        engineWake = signal
        try {
            if (closed) return true
            if (timeoutMillis == null) {
                signal.await()
                return true
            }
            return withTimeoutOrNull(timeoutMillis) { signal.await() } != null
        } finally {
            if (engineWake === signal) engineWake = null
        }
    }

    private suspend fun runEngine(attempt: Int): EngineOutcome {
        val generation = ++nextGeneration
        val url = EngineIoCodec.webSocketUrl(config.baseUrl, config.path)
        log("realtime_socket_connecting attempt=$attempt generation=$generation")
        val session =
            try {
                withTimeout(config.connectTimeoutMillis) { connector.connect(url, config.headers) }
            } catch (timeout: TimeoutCancellationException) {
                log("realtime_socket_connect_failed reason=connect_timeout generation=$generation")
                return EngineOutcome(RaftSocketDisconnectReason.ConnectTimeout, opened = false)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                log("realtime_socket_connect_failed reason=transport_error error=${error::class.simpleName} generation=$generation")
                return EngineOutcome(RaftSocketDisconnectReason.TransportError, opened = false)
            }
        val active = ActiveEngine(generation, session)
        engine = active
        if (closed || !anyWantsEngine()) active.end(RaftSocketDisconnectReason.ClientDisconnect, "inactive_while_connecting")
        val writer =
            scope.launch {
                for (frame in active.outgoing) {
                    try {
                        when (frame) {
                            is Outgoing.Text -> session.send(frame.text)
                            is Outgoing.Binary -> session.sendBinary(frame.bytes)
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Throwable) {
                        active.end(RaftSocketDisconnectReason.TransportError, "send_failed:${error::class.simpleName}")
                        break
                    }
                }
            }
        active.openTimer =
            scope.launch {
                delay(config.connectTimeoutMillis)
                if (active.open == null) active.end(RaftSocketDisconnectReason.ConnectTimeout, "no_open_packet")
            }
        val reader =
            scope.launch {
                try {
                    for (frame in session.incoming) {
                        if (active.ended) break
                        handleFrame(active, frame)
                    }
                    active.end(RaftSocketDisconnectReason.TransportClosed, "closed")
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Throwable) {
                    active.end(RaftSocketDisconnectReason.TransportError, "receive_failed:${error::class.simpleName}")
                }
            }
        active.reader = reader
        if (active.ended) reader.cancel()
        reader.join()
        // No frame of this generation is handled after this point.
        if (engine === active) engine = null
        active.openTimer?.cancel()
        active.pingWatchdog?.cancel()
        active.outgoing.close()
        withTimeoutOrNull(WRITER_DRAIN_MILLIS) { writer.join() } ?: writer.cancel()
        runCatching { session.close(RaftWebSocketSession.NORMAL_CLOSURE, active.endReason?.token.orEmpty()) }
        val reason = active.endReason ?: RaftSocketDisconnectReason.TransportClosed
        log(
            "realtime_socket_disconnected reason=${reason.token} detail=${active.endDetail.token()} " +
                "generation=$generation opened=${active.open != null}",
        )
        namespaces.values.forEach { it.engineClosedLocked(generation, reason, active.endDetail) }
        if (active.open != null) safely("on_close") { engineListener?.onClose(generation, reason, active.endDetail) }
        return EngineOutcome(reason, opened = active.open != null)
    }

    private fun handleFrame(active: ActiveEngine, frame: RaftWebSocketFrame) {
        when (frame) {
            is RaftWebSocketFrame.Binary -> {
                if (!active.reconstructor.pending) {
                    drop("binary_frame", "binary_unexpected", null, detail = "size=${frame.bytes.size}")
                    return
                }
                active.reconstructor.add(frame.bytes)?.let { dispatchPacket(active, it) }
            }
            is RaftWebSocketFrame.Text -> {
                val packet = EngineIoCodec.decode(frame.text)
                if (packet == null) {
                    drop("engine_packet", "engine_packet_invalid", null)
                    return
                }
                when (packet.type) {
                    EngineIoPacketType.Open -> onOpen(active, packet.data)
                    EngineIoPacketType.Ping -> {
                        if (active.open == null) {
                            drop("engine_ping", "before_open", null)
                            return
                        }
                        active.outgoing.trySend(Outgoing.Text(EngineIoCodec.encode(EngineIoPacket(EngineIoPacketType.Pong, packet.data))))
                        restartPingWatchdog(active)
                    }
                    EngineIoPacketType.Pong -> Unit // v4 servers ping; nothing of ours to answer.
                    EngineIoPacketType.Close -> active.end(RaftSocketDisconnectReason.EngineClose, "engine_close")
                    EngineIoPacketType.Message -> {
                        if (active.open == null) {
                            drop("engine_message", "before_open", null)
                            return
                        }
                        onSocketIo(active, packet.data)
                    }
                    EngineIoPacketType.Upgrade, EngineIoPacketType.Noop -> Unit
                }
            }
        }
    }

    private fun onOpen(active: ActiveEngine, data: String) {
        if (active.open != null) {
            drop("engine_open", "duplicate_open", null)
            return
        }
        val open = EngineIoCodec.parseOpen(data)
        if (open == null) {
            active.end(RaftSocketDisconnectReason.ProtocolError, "bad_open_packet")
            return
        }
        active.open = open
        active.openTimer?.cancel()
        engineOpens += 1
        log(
            "realtime_socket_open generation=${active.generation} pingInterval=${open.pingIntervalMillis} " +
                "pingTimeout=${open.pingTimeoutMillis} maxPayload=${open.maxPayload}",
        )
        if (open.fallbacks.isNotEmpty()) {
            // Never expected from socket.io 4.x; logged so a server change cannot go unnoticed.
            log("realtime_socket_open_fallback generation=${active.generation} fields=${open.fallbacks.joinToString(",")}")
        }
        restartPingWatchdog(active)
        mutableEngineState.value = RaftSocketEngineState.Open(open, active.generation)
        safely("on_open") { engineListener?.onOpen(open, active.generation) }
        namespaces.values.forEach { it.engineOpenedLocked(active.generation) }
    }

    private fun onSocketIo(active: ActiveEngine, text: String) {
        when (val result = SocketIoCodec.decode(text)) {
            is SocketIoDecodeResult.Invalid -> {
                if (active.reconstructor.pending) {
                    active.reconstructor.reset()
                    drop("binary_packet", "binary_interrupted", null)
                }
                drop(peekEventName(text), "socketio_${result.reason}", namespaceOf(text))
            }
            is SocketIoDecodeResult.Packet -> {
                if (active.reconstructor.pending) {
                    // socket.io-parser: "got plaintext data when reconstructing a packet".
                    active.reconstructor.reset()
                    drop("binary_packet", "binary_interrupted", null)
                }
                if (result.attachmentsExpected > 0) {
                    active.reconstructor.start(result.packet, result.attachmentsExpected)
                } else {
                    dispatchPacket(active, result.packet)
                }
            }
        }
    }

    private fun dispatchPacket(active: ActiveEngine, packet: SocketIoPacket) {
        val socket = namespaces[packet.namespace]
        if (socket == null) {
            drop((packet as? SocketIoPacket.Event)?.name ?: "namespace", "namespace_unknown", null, detail = "nsp=${packet.namespace.token()}")
            return
        }
        socket.onPacketLocked(active.generation, active.open?.sid.orEmpty(), packet)
    }

    private fun namespaceOf(text: String): RaftSocketIoNamespace? {
        val slash = text.indexOf('/')
        if (slash != 1 && !(slash > 1 && text[1].isDigit() && text.indexOf('-') == slash - 1)) return namespaces[SocketIoPacket.ROOT_NAMESPACE]
        val comma = text.indexOf(',', slash).let { if (it < 0) text.length else it }
        return namespaces[text.substring(slash, comma)]
    }

    private fun restartPingWatchdog(active: ActiveEngine) {
        val open = active.open ?: return
        active.pingWatchdog?.cancel()
        active.pingWatchdog =
            scope.launch {
                delay(open.pingIntervalMillis + open.pingTimeoutMillis)
                active.end(RaftSocketDisconnectReason.PingTimeout, "no_ping")
            }
    }

    private fun count(counter: MutableMap<String, Int>, key: String): Int {
        val next = (counter[key] ?: 0) + 1
        counter[key] = next
        return next
    }

    private inner class ActiveEngine(val generation: Long, val session: RaftWebSocketSession) {
        val outgoing = Channel<Outgoing>(Channel.UNLIMITED)
        val reconstructor = SocketIoBinaryReconstructor()
        var open: EngineIoOpen? = null
        var reader: Job? = null
        var openTimer: Job? = null
        var pingWatchdog: Job? = null
        var endReason: RaftSocketDisconnectReason? = null
        var endDetail: String = ""
        val ended: Boolean get() = endReason != null

        /** The first reason wins; the reader stops and the session closes. */
        fun end(reason: RaftSocketDisconnectReason, detail: String) {
            if (endReason != null) return
            endReason = reason
            endDetail = detail
            reader?.cancel()
            pingWatchdog?.cancel()
            openTimer?.cancel()
        }
    }

    private sealed interface Outgoing {
        class Text(val text: String) : Outgoing

        class Binary(val bytes: ByteArray) : Outgoing
    }

    private class EngineOutcome(val reason: RaftSocketDisconnectReason, val opened: Boolean)

    companion object {
        /** The ack wait iOS used for `sync:horizon` (Socket.IO-Client-Swift `timingOut(after: 10)`). */
        const val DEFAULT_ACK_TIMEOUT_MILLIS: Long = 10_000L
        private const val EIO_MESSAGE = "4"
        private const val WRITER_DRAIN_MILLIS = 1_000L
        private const val UNLISTED_LOG_EVERY = 100
        private const val DROP_LOG_FIRST = 20
        private const val DROP_LOG_EVERY = 100

        fun normalizeNamespace(namespace: String): String {
            val trimmed = namespace.trim()
            return if (trimmed.isEmpty() || trimmed == "/") SocketIoPacket.ROOT_NAMESPACE else "/" + trimmed.trimStart('/')
        }

        /** A log-safe token: letters, digits and `:_-./` only, at most 64 characters. */
        internal fun String.token(): String {
            val cleaned =
                take(64).map { character ->
                    if (character.isLetterOrDigit() || character in ":_-./") character else '_'
                }.joinToString("")
            return cleaned.ifEmpty { "none" }
        }

        /** The event name of a malformed EVENT packet, for the drop log; never the payload. */
        internal fun peekEventName(text: String): String {
            val start = text.indexOf("[\"")
            if (start < 0) return "socketio"
            val end = text.indexOf('"', start + 2)
            return if (end > start + 2) text.substring(start + 2, end) else "socketio"
        }
    }
}

/**
 * One namespace socket of a [RaftSocketIoClient] (socket.io-client's `Socket`). Methods may be called
 * from any thread; callbacks run on the client's serial dispatcher.
 */
class RaftSocketIoNamespace internal constructor(
    private val client: RaftSocketIoClient,
    val namespace: String,
    private val options: RaftSocketNamespaceOptions,
) {
    private val mutableState = MutableStateFlow<RaftSocketState>(RaftSocketState.Idle)

    /** Readable from any thread. */
    val state: StateFlow<RaftSocketState> = mutableState.asStateFlow()

    internal var registration: CompletableDeferred<RaftSocketIoNamespace>? = null

    // Dispatcher-confined.
    internal var active = false
        private set
    private var everConnected = false
    private var attempt = 0
    private var failures = 0
    private var tokenRefreshRetried = false
    private var lastError: RaftSocketHandshakeError? = null
    private var waitingForKick = false
    private var retryJob: Job? = null
    private var connectJob: Job? = null
    private var connectTimer: Job? = null
    /** The engine generation a CONNECT was sent on, while waiting for the answer. */
    private var pendingGeneration: Long? = null
    private var connection: RaftSocketConnection? = null
    private var connectionSeq = 0L
    private var pid: String? = null
    private var lastOffset: String? = null
    private var nextAckId = 0L
    private val pendingAcks = linkedMapOf<Long, PendingAck>()
    /**
     * Events that arrived while this socket's CONNECT was pending. A recovering server sends the missed
     * packets before its CONNECT answer (socket.io `Socket` constructor), so socket.io-client buffers them
     * (`receiveBuffer`) and delivers them right after connect; so does this socket.
     */
    private val receiveBuffer = mutableListOf<SocketIoPacket.Event>()

    /** Connects (and keeps reconnecting) until [disconnect]. No-op while active. */
    fun connect() {
        client.scope.launch {
            if (!isRegistered()) return@launch
            if (active) return@launch
            active = true
            everConnected = false
            attempt = 0
            failures = 0
            tokenRefreshRetried = false
            lastError = null
            waitingForKick = false
            client.log("realtime_socket_namespace_connect nsp=${namespace.tokenSafe()}")
            client.namespaceActivated()
        }
    }

    /** Leaves the namespace (`1` to the server) and stops retrying. */
    fun disconnect() {
        client.scope.launch {
            if (!isRegistered()) return@launch
            deactivateLocked(RaftSocketDisconnectReason.ClientDisconnect, "disconnect")
            client.namespaceDeactivated()
        }
    }

    /** Emits on the live connection; dropped (and logged) without one. */
    fun emit(name: String, args: List<JsonElement> = emptyList(), attachments: List<RaftSocketBinary> = emptyList()) {
        client.scope.launch { emitNow(name, args, attachments, ackId = null) }
    }

    /**
     * Emits with an ack id; [onResult] runs once on the client's dispatcher: the ack, [RaftAckResult.Timeout]
     * after [timeoutMillis], or [RaftAckResult.NotDelivered] when not connected or the connection ends first.
     */
    fun emitWithAck(
        name: String,
        args: List<JsonElement> = emptyList(),
        attachments: List<RaftSocketBinary> = emptyList(),
        timeoutMillis: Long = RaftSocketIoClient.DEFAULT_ACK_TIMEOUT_MILLIS,
        onResult: (RaftAckResult) -> Unit,
    ) {
        client.scope.launch {
            val current = connection
            if (current == null || !isRegistered()) {
                client.drop(name, "not_connected", this@RaftSocketIoNamespace)
                client.safely("ack_result") { onResult(RaftAckResult.NotDelivered("not_connected")) }
                return@launch
            }
            val id = allocateAckId()
            val seq = connectionSeq
            val timeoutJob =
                client.scope.launch {
                    delay(timeoutMillis)
                    val pending = pendingAcks.remove(id) ?: return@launch
                    client.log("realtime_socket_ack_timeout event=${name.tokenSafe()} nsp=${namespace.tokenSafe()} ackId=$id")
                    client.drop(name, "ack_timeout", this@RaftSocketIoNamespace)
                    client.safely("ack_result") { pending.onResult(RaftAckResult.Timeout) }
                }
            pendingAcks[id] = PendingAck(seq, name, onResult, timeoutJob)
            if (!emitNow(name, args, attachments, ackId = id)) {
                pendingAcks.remove(id)
                timeoutJob.cancel()
                client.safely("ack_result") { onResult(RaftAckResult.NotDelivered("send_failed")) }
            }
        }
    }

    internal fun sendIncomingAck(ack: RaftSocketIncomingAck, args: List<JsonElement>, attachments: List<RaftSocketBinary>) {
        client.scope.launch {
            if (ack.sent) {
                client.drop(ack.eventName, "ack_already_sent", this@RaftSocketIoNamespace)
                return@launch
            }
            if (connection == null || connectionSeq != ack.connectionSeq) {
                client.drop(ack.eventName, "ack_stale_connection", this@RaftSocketIoNamespace)
                return@launch
            }
            ack.sent = true
            client.sendPacket(SocketIoPacket.Ack(ack.ackId, args, namespace, attachments), ack.eventName, this@RaftSocketIoNamespace)
        }
    }

    private suspend fun isRegistered(): Boolean {
        val registered = registration?.await() ?: return false
        if (registered !== this) {
            client.log("realtime_socket_namespace_duplicate nsp=${namespace.tokenSafe()} result=ignored")
            return false
        }
        return true
    }

    private fun emitNow(name: String, args: List<JsonElement>, attachments: List<RaftSocketBinary>, ackId: Long?): Boolean {
        if (connection == null) {
            client.drop(name, "not_connected", this)
            return false
        }
        return client.sendPacket(SocketIoPacket.Event(name, args, ackId, namespace, attachments), name, this)
    }

    // ---- engine callbacks (dispatcher) ----

    internal fun engineConnectingLocked(engineAttempt: Int) {
        if (connection == null && !waitingForKick) setState(RaftSocketState.Connecting(attempt.coerceAtLeast(engineAttempt)))
    }

    internal fun engineWaitingLocked(nextAttempt: Int, delayMillis: Long, reason: RaftSocketDisconnectReason) {
        if (waitingForKick) return
        setState(RaftSocketState.WaitingToReconnect(nextAttempt, delayMillis, reason.token))
        client.safely("on_reconnect_scheduled") { options.listener.onReconnectScheduled(nextAttempt, delayMillis, reason) }
    }

    internal fun engineOpenedLocked(generation: Long) {
        if (!active || waitingForKick || connection != null || pendingGeneration == generation || connectJob?.isActive == true) return
        if (retryJob?.isActive == true) return
        startConnectAttempt()
    }

    internal fun engineClosedLocked(generation: Long, reason: RaftSocketDisconnectReason, detail: String) {
        dropReceiveBuffer("connection_closed")
        connectJob?.cancel()
        connectTimer?.cancel()
        retryJob?.cancel()
        pendingGeneration = null
        val ended = connection
        connection = null
        failPendingAcks("connection_closed")
        if (ended != null) {
            if (active) setState(RaftSocketState.Connecting(attempt + 1))
            client.safely("on_disconnected") { options.listener.onDisconnected(ended, reason, detail) }
        }
    }

    internal fun kickLocked() {
        if (!active) return
        val waiting = waitingForKick || retryJob?.isActive == true
        if (!waiting) return
        retryJob?.cancel()
        waitingForKick = false
        failures = 0
        attempt = 0
        tokenRefreshRetried = false
        lastError = null
        if (client.currentGeneration != null) startConnectAttempt() else client.namespaceActivated()
    }

    internal fun deactivateLocked(reason: RaftSocketDisconnectReason, detail: String) {
        if (!active) return
        active = false
        waitingForKick = false
        dropReceiveBuffer("client_disconnect")
        retryJob?.cancel()
        connectJob?.cancel()
        connectTimer?.cancel()
        val ended = connection
        if (ended != null || pendingGeneration != null) {
            client.sendPacket(SocketIoPacket.Disconnect(namespace), "disconnect", this)
        }
        pendingGeneration = null
        connection = null
        failPendingAcks("client_disconnect")
        setState(RaftSocketState.Idle)
        if (ended != null) client.safely("on_disconnected") { options.listener.onDisconnected(ended, reason, detail) }
    }

    internal fun notifyDropped(name: String, reason: String) {
        client.safely("on_event_dropped") { options.listener.onEventDropped(name, reason) }
    }

    private fun startConnectAttempt() {
        val generation = client.currentGeneration ?: return
        attempt += 1
        val thisAttempt = attempt
        setState(RaftSocketState.Connecting(thisAttempt))
        client.safely("on_connecting") { options.listener.onConnecting(thisAttempt) }
        val request = RaftSocketAuthRequest(thisAttempt, lastError)
        connectJob =
            client.scope.launch {
                val auth =
                    try {
                        options.authProvider(request)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Throwable) {
                        client.log("realtime_socket_auth_provider_failed nsp=${namespace.tokenSafe()} error=${error::class.simpleName}")
                        null
                    }
                if (!active) return@launch
                if (client.currentGeneration != generation) return@launch // The engine moved on; its open re-attempts.
                if (auth == null) {
                    client.log("realtime_socket_connect_skip nsp=${namespace.tokenSafe()} reason=no_credentials attempt=$thisAttempt")
                    waitingForKick = true
                    setState(RaftSocketState.Failed(null))
                    client.namespaceDeactivated()
                    return@launch
                }
                val data =
                    if (options.stateRecovery && pid != null) {
                        JsonObject(
                            buildMap {
                                put("pid", JsonPrimitive(pid))
                                lastOffset?.let { put("offset", JsonPrimitive(it)) }
                                putAll(auth)
                            },
                        )
                    } else {
                        auth
                    }
                pendingGeneration = generation
                if (!client.sendPacket(SocketIoPacket.Connect(namespace, data), "connect", this@RaftSocketIoNamespace)) {
                    pendingGeneration = null
                    return@launch
                }
                connectTimer?.cancel()
                connectTimer =
                    client.scope.launch {
                        delay(client.connectTimeoutMillis)
                        if (pendingGeneration == generation && connection == null) {
                            pendingGeneration = null
                            client.log("realtime_socket_namespace_connect_timeout nsp=${namespace.tokenSafe()}")
                            scheduleRetry(RaftSocketDisconnectReason.ConnectTimeout, immediate = false)
                        }
                    }
            }
    }

    internal fun onPacketLocked(generation: Long, engineSid: String, packet: SocketIoPacket) {
        when (packet) {
            is SocketIoPacket.Connect -> onConnect(generation, engineSid, packet)
            is SocketIoPacket.ConnectError -> onConnectError(generation, packet)
            is SocketIoPacket.Disconnect -> {
                val ended = connection
                if (ended == null) {
                    client.drop("disconnect", "disconnect_not_connected", this)
                    return
                }
                connection = null
                failPendingAcks("io_server_disconnect")
                client.log("realtime_socket_namespace_server_disconnect nsp=${namespace.tokenSafe()}")
                client.safely("on_disconnected") {
                    options.listener.onDisconnected(ended, RaftSocketDisconnectReason.ServerDisconnect, "io_server_disconnect")
                }
                if (options.reconnectOnServerDisconnect && active) {
                    scheduleRetry(RaftSocketDisconnectReason.ServerDisconnect, immediate = false)
                } else {
                    active = false
                    setState(RaftSocketState.Idle)
                    client.namespaceDeactivated()
                }
            }
            is SocketIoPacket.Event ->
                if (connection == null && pendingGeneration == generation && active) {
                    receiveBuffer += packet
                } else {
                    onEvent(packet)
                }
            is SocketIoPacket.Ack -> {
                val pending = pendingAcks[packet.ackId]
                if (pending == null || pending.connectionSeq != connectionSeq || connection == null) {
                    client.drop("ack", "ack_unknown_id", this, detail = "ackId=${packet.ackId}")
                    return
                }
                pendingAcks.remove(packet.ackId)
                pending.timeoutJob.cancel()
                client.safely("ack_result") { pending.onResult(RaftAckResult.Acked(packet.args, packet.attachments)) }
            }
        }
    }

    private fun onConnect(generation: Long, engineSid: String, packet: SocketIoPacket.Connect) {
        if (connection != null) {
            client.drop("connect", "duplicate_connect", this)
            return
        }
        if (!active || pendingGeneration != generation) {
            client.drop("connect", "connect_unrequested", this)
            return
        }
        pendingGeneration = null
        connectTimer?.cancel()
        val socketId = (packet.data?.get("sid") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
        val newPid = (packet.data?.get("pid") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val recovered = newPid != null && newPid == pid
        if (newPid != pid) lastOffset = null
        pid = newPid
        connectionSeq += 1
        val established =
            RaftSocketConnection(
                namespace = namespace,
                generation = generation,
                socketId = socketId,
                engineSid = engineSid,
                attempt = attempt,
                reconnect = everConnected,
                recovered = recovered,
            )
        everConnected = true
        connection = established
        attempt = 0
        failures = 0
        tokenRefreshRetried = false
        lastError = null
        client.countConnect(namespace)
        client.log(
            "realtime_socket_connected nsp=${namespace.tokenSafe()} generation=$generation " +
                "reconnect=${established.reconnect} recovered=$recovered",
        )
        setState(RaftSocketState.Connected(established))
        client.safely("on_connected") { options.listener.onConnected(established) }
        val buffered = receiveBuffer.toList()
        receiveBuffer.clear()
        buffered.forEach { if (connection === established) onEvent(it) }
    }

    private fun onConnectError(generation: Long, packet: SocketIoPacket.ConnectError) {
        if (pendingGeneration != generation || connection != null) {
            client.drop("connect_error", "connect_error_unrequested", this)
            return
        }
        pendingGeneration = null
        connectTimer?.cancel()
        dropReceiveBuffer("connect_error")
        val error = RaftSocketHandshakeError.fromMessage(packet.message)
        lastError = error
        client.countHandshakeError(error.kind)
        val willRetry = active && options.retryOnConnectError && !error.terminal
        client.log(
            "realtime_socket_connect_error nsp=${namespace.tokenSafe()} kind=${error.kind.name} " +
                "terminal=${error.terminal} refresh=${error.requiresTokenRefresh} retry=$willRetry",
        )
        client.safely("on_connect_error") { options.listener.onConnectError(error, willRetry) }
        if (!active) return
        if (!willRetry) {
            waitingForKick = true
            setState(RaftSocketState.Failed(error))
            client.log("realtime_socket_wait nsp=${namespace.tokenSafe()} reason=${error.kind.name} until=kick")
            client.namespaceDeactivated()
            return
        }
        val immediate = error.requiresTokenRefresh && !tokenRefreshRetried
        if (immediate) tokenRefreshRetried = true
        scheduleRetry(RaftSocketDisconnectReason.HandshakeRejected, immediate)
    }

    private fun scheduleRetry(reason: RaftSocketDisconnectReason, immediate: Boolean) {
        val delayMillis = if (immediate) 0L else client.reconnectDelay(failures).also { failures += 1 }
        val next = attempt + 1
        client.log(
            "realtime_socket_namespace_retry nsp=${namespace.tokenSafe()} attempt=$next delayMs=$delayMillis reason=${reason.token}",
        )
        setState(RaftSocketState.WaitingToReconnect(next, delayMillis, reason.token))
        client.safely("on_reconnect_scheduled") { options.listener.onReconnectScheduled(next, delayMillis, reason) }
        retryJob?.cancel()
        retryJob =
            client.scope.launch {
                if (delayMillis > 0L) delay(delayMillis)
                retryJob = null
                if (active && connection == null && !waitingForKick) startConnectAttempt()
            }
    }

    private fun onEvent(packet: SocketIoPacket.Event) {
        val current = connection
        if (current == null) {
            client.drop(packet.name, "event_before_connect", this)
            return
        }
        // Connection state recovery: a recovery-enabled server appends the packet offset as the last arg.
        if (pid != null) {
            (packet.args.lastOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.let { lastOffset = it.content }
        }
        val allowed = options.allowedEvents
        if (allowed != null && packet.name !in allowed) {
            client.recordUnlisted(packet.name, this, packet.ackId != null)
            return
        }
        val ack = packet.ackId?.let { RaftSocketIncomingAck(this, connectionSeq, it, packet.name) }
        client.safely("on_event") { options.listener.onEvent(current, RaftSocketEvent(packet.name, packet.args, packet.attachments), ack) }
    }

    private fun dropReceiveBuffer(reason: String) {
        if (receiveBuffer.isEmpty()) return
        val dropped = receiveBuffer.toList()
        receiveBuffer.clear()
        dropped.forEach { client.drop(it.name, "buffered_$reason", this) }
    }

    private fun failPendingAcks(reason: String) {
        val stale = pendingAcks.toMap()
        pendingAcks.clear()
        stale.values.forEach { pending ->
            pending.timeoutJob.cancel()
            client.drop(pending.eventName, "ack_$reason", this)
            client.safely("ack_result") { pending.onResult(RaftAckResult.NotDelivered(reason)) }
        }
    }

    private fun allocateAckId(): Long {
        var id = nextAckId
        while (pendingAcks.containsKey(id)) id = if (id >= SocketIoCodec.MAX_ACK_ID) 0L else id + 1
        nextAckId = if (id >= SocketIoCodec.MAX_ACK_ID) 0L else id + 1
        return id
    }

    /** Test hook: the next ack id (to exercise the wrap-around at [SocketIoCodec.MAX_ACK_ID]). */
    internal fun setNextAckIdForTests(id: Long) {
        nextAckId = id
    }

    internal val waitingForKickLocked: Boolean get() = waitingForKick

    private fun setState(state: RaftSocketState) {
        mutableState.value = state
    }

    private fun String.tokenSafe(): String = with(RaftSocketIoClient) { token() }

    private class PendingAck(
        val connectionSeq: Long,
        val eventName: String,
        val onResult: (RaftAckResult) -> Unit,
        val timeoutJob: Job,
    )
}
