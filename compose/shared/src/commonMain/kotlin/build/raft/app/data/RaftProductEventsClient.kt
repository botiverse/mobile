package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.model.raftEpochMillisUtcIso
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The two product-event endpoints, per server (`X-Server-Id`). Implementations never throw for HTTP errors. */
interface RaftProductEventsTransport {
    suspend fun fetchConfig(serverId: ServerId): RaftProductEventsConfigResult

    suspend fun sendBatch(serverId: ServerId, batch: RaftProductEventBatchRequest): RaftProductEventsSendResult
}

/** Batch-level client facts: the registry `source` and the app version. */
data class RaftProductEventsClientInfo(
    val source: RaftProductEventSource,
    val appVersion: String?,
)

data class RaftProductEventsClientConfig(
    /** Like web's FLUSH_DELAY_MS: a quiet queue flushes this long after its first event. */
    val flushIntervalMillis: Long = 10_000L,
    /** A queue this long flushes at once. */
    val flushSizeThreshold: Int = 20,
    /** Oldest events are dropped past this many (web MAX_QUEUED_EVENTS). */
    val maxQueuedEvents: Int = 500,
    /** Events recorded before sign-in is known (cold start) wait for it, up to this many. */
    val maxUnboundEvents: Int = 50,
    /** A server's `clientEventsAllowed` answer is asked again after this long. */
    val consentTtlMillis: Long = 10 * 60_000L,
    val retryBaseMillis: Long = 2_000L,
    val retryMaxMillis: Long = 5 * 60_000L,
    /** Ingest rejects timestamps older than 24 h; older queued events are dropped here instead. */
    val maxEventAgeMillis: Long = 23 * 60 * 60_000L,
)

/**
 * In-memory product-event queue (RFC-067 client side), shared by Android and iOS.
 *
 * - Nothing leaves the device until the account is signed in to a server ([setSignedIn]); events
 *   recorded while sign-in is still unknown wait (bounded), and [setSignedOut] drops everything.
 * - Before the first batch for a server it asks `GET /api/product-events/config`, caches the
 *   answer per server for [RaftProductEventsClientConfig.consentTtlMillis] and refreshes it after.
 *   `clientEventsAllowed: false` drops that server's events: they are never sent.
 * - Flushes on size, on a timer and on demand (app background); one batch per server, at most
 *   [RaftProductEventMaxBatchEvents] events, always at Background network priority.
 * - Offline / 5xx / 429 keep the batch and retry with exponential backoff; a 4xx answer (400
 *   malformed, 401, 403, ...) drops the batch. The queue is capped; [track] never blocks or throws.
 */
class RaftProductEventsClient(
    private val scope: CoroutineScope,
    private val nowEpochMillis: () -> Long,
    private val newUuid: () -> String,
    private val clientInfo: () -> RaftProductEventsClientInfo?,
    private val config: RaftProductEventsClientConfig = RaftProductEventsClientConfig(),
    private val log: (String) -> Unit = {},
) {
    private sealed interface AuthState {
        data object Unknown : AuthState

        data object SignedOut : AuthState

        data class SignedIn(val serverId: ServerId) : AuthState
    }

    private class Queued(
        var serverId: ServerId?,
        val wire: RaftProductEventWire,
        val recordedAtMillis: Long,
    )

    private class Consent(val allowed: Boolean, val answeredAtMillis: Long)

    private sealed interface NextFlush {
        data object Idle : NextFlush

        data class After(val delayMillis: Long) : NextFlush
    }

    private val lock = SynchronizedObject()
    private var transport: RaftProductEventsTransport? = null
    private var auth: AuthState = AuthState.Unknown
    private val queue = ArrayDeque<Queued>()
    private val consent = HashMap<ServerId, Consent>()
    private var sessionId: String = newUuid()
    private var generation = 0L
    private var flushJob: Job? = null
    private var flushInFlight = false
    private var flushAgain = false
    private var retryAttempt = 0

    val queuedEventCount: Int
        get() = synchronized(lock) { queue.size }

    fun bindTransport(transport: RaftProductEventsTransport) {
        val pending = synchronized(lock) {
            this.transport = transport
            queue.isNotEmpty()
        }
        if (pending) scheduleFlush(config.flushIntervalMillis)
    }

    /** Records [event] for the signed-in server. Never blocks on I/O and never throws. */
    fun track(event: RaftProductEvent<*>) {
        val properties = runCatching { event.encodeProperties() }.getOrNull() ?: return
        val now = nowEpochMillis()
        val flushDelay =
            synchronized(lock) {
                val serverId =
                    when (val state = auth) {
                        AuthState.SignedOut -> return
                        AuthState.Unknown -> null
                        is AuthState.SignedIn -> state.serverId
                    }
                if (serverId != null && consent[serverId]?.isFresh(now) == true && consent[serverId]?.allowed == false) {
                    return
                }
                if (serverId == null && queue.count { it.serverId == null } >= config.maxUnboundEvents) return
                queue.addLast(
                    Queued(
                        serverId = serverId,
                        wire =
                            RaftProductEventWire(
                                uuid = newUuid(),
                                event = event.name,
                                timestamp = raftEpochMillisUtcIso(now),
                                clientSessionId = sessionId,
                                properties = properties,
                            ),
                        recordedAtMillis = now,
                    )
                )
                while (queue.size > config.maxQueuedEvents) queue.removeFirst()
                when {
                    serverId == null -> null
                    queue.size >= config.flushSizeThreshold -> 0L
                    else -> config.flushIntervalMillis
                }
            }
        if (flushDelay != null) scheduleFlush(flushDelay)
    }

    /** The shell is signed in and showing [serverId]: events recorded before sign-in was known join it. */
    fun setSignedIn(serverId: ServerId) {
        val hasEvents =
            synchronized(lock) {
                if (auth == AuthState.SignedIn(serverId)) return
                auth = AuthState.SignedIn(serverId)
                queue.forEach { if (it.serverId == null) it.serverId = serverId }
                queue.isNotEmpty()
            }
        if (hasEvents) scheduleFlush(config.flushIntervalMillis)
    }

    /** No signed-in server (sign-in screen, logout, auth loss): nothing recorded so far is sent. */
    fun setSignedOut() {
        synchronized(lock) {
            if (auth == AuthState.SignedOut && queue.isEmpty()) return
            auth = AuthState.SignedOut
            queue.clear()
            consent.clear()
            generation += 1
            retryAttempt = 0
            if (!flushInFlight) {
                flushJob?.cancel()
                flushJob = null
            }
        }
    }

    /** A new product session (fresh launch or a return after the session gap). */
    fun startNewSession() {
        synchronized(lock) { sessionId = newUuid() }
    }

    /** Flush now (app going to the background, tests). */
    fun flushNow() {
        scheduleFlush(0L)
    }

    private fun Consent.isFresh(now: Long): Boolean = now - answeredAtMillis < config.consentTtlMillis

    private fun scheduleFlush(delayMillis: Long) {
        synchronized(lock) {
            val current = flushJob
            if (flushInFlight) {
                if (delayMillis == 0L) flushAgain = true
                return
            }
            if (current != null && current.isActive) {
                // A pending timer keeps its time unless an immediate flush is asked for.
                if (delayMillis > 0L) return
                current.cancel()
            }
            flushJob =
                scope.launch {
                    if (delayMillis > 0L) delay(delayMillis)
                    val next = runFlush()
                    val again =
                        synchronized(lock) {
                            flushInFlight = false
                            flushJob = null
                            flushAgain.also { flushAgain = false }
                        }
                    when {
                        next is NextFlush.After -> scheduleFlush(next.delayMillis)
                        again -> scheduleFlush(0L)
                    }
                }
        }
    }

    private suspend fun runFlush(): NextFlush {
        synchronized(lock) { flushInFlight = true }
        while (true) {
            val now = nowEpochMillis()
            val work =
                synchronized(lock) {
                    val currentTransport = transport ?: return NextFlush.Idle
                    if (auth !is AuthState.SignedIn) return NextFlush.Idle
                    queue.removeAll { now - it.recordedAtMillis > config.maxEventAgeMillis }
                    val serverId = queue.firstOrNull { it.serverId != null }?.serverId ?: return NextFlush.Idle
                    val batch = ArrayList<Queued>()
                    val iterator = queue.iterator()
                    while (iterator.hasNext() && batch.size < RaftProductEventMaxBatchEvents) {
                        val entry = iterator.next()
                        if (entry.serverId == serverId) {
                            batch += entry
                            iterator.remove()
                        }
                    }
                    FlushWork(currentTransport, serverId, batch, generation, consent[serverId]?.takeIf { it.isFresh(now) })
                }
            val info = clientInfo()
            if (info == null) {
                requeue(work)
                return NextFlush.Idle
            }
            val allowed =
                work.consent?.allowed
                    ?: when (val answer = safeFetchConfig(work.transport, work.serverId)) {
                        is RaftProductEventsConfigResult.Answered -> {
                            synchronized(lock) {
                                if (generation == work.generation) {
                                    consent[work.serverId] = Consent(answer.clientEventsAllowed, nowEpochMillis())
                                }
                            }
                            answer.clientEventsAllowed
                        }
                        RaftProductEventsConfigResult.Unavailable -> {
                            requeue(work)
                            return retryLater("config_unavailable")
                        }
                    }
            if (!allowed) {
                synchronized(lock) { queue.removeAll { it.serverId == work.serverId } }
                log("product_events_dropped reason=not_allowed count=${work.events.size}")
                continue
            }
            val batch =
                RaftProductEventBatchRequest(
                    source = info.source,
                    appVersion = raftProductEventAppVersion(info.appVersion),
                    events = work.events.map { it.wire },
                )
            when (val result = safeSend(work.transport, work.serverId, batch)) {
                is RaftProductEventsSendResult.Accepted -> {
                    synchronized(lock) { retryAttempt = 0 }
                    log("product_events_sent count=${work.events.size} accepted=${result.accepted}")
                }
                is RaftProductEventsSendResult.Rejected -> {
                    synchronized(lock) { retryAttempt = 0 }
                    log("product_events_dropped reason=http_${result.code} count=${work.events.size}")
                }
                is RaftProductEventsSendResult.Retryable -> {
                    requeue(work)
                    return retryLater("send_${result.code}")
                }
            }
        }
    }

    private class FlushWork(
        val transport: RaftProductEventsTransport,
        val serverId: ServerId,
        val events: List<Queued>,
        val generation: Long,
        val consent: Consent?,
    )

    /** Puts an unsent batch back at the head of the queue unless the account changed meanwhile. */
    private fun requeue(work: FlushWork) {
        synchronized(lock) {
            if (generation != work.generation) return
            work.events.asReversed().forEach { queue.addFirst(it) }
            while (queue.size > config.maxQueuedEvents) queue.removeFirst()
        }
    }

    private fun retryLater(reason: String): NextFlush {
        val delayMillis =
            synchronized(lock) {
                val shift = retryAttempt.coerceAtMost(20)
                retryAttempt += 1
                (config.retryBaseMillis shl shift).coerceAtMost(config.retryMaxMillis)
            }
        log("product_events_retry reason=$reason delayMs=$delayMillis")
        return NextFlush.After(delayMillis)
    }

    private suspend fun safeFetchConfig(
        transport: RaftProductEventsTransport,
        serverId: ServerId,
    ): RaftProductEventsConfigResult =
        try {
            transport.fetchConfig(serverId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            RaftProductEventsConfigResult.Unavailable
        }

    private suspend fun safeSend(
        transport: RaftProductEventsTransport,
        serverId: ServerId,
        batch: RaftProductEventBatchRequest,
    ): RaftProductEventsSendResult =
        try {
            transport.sendBatch(serverId, batch)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            RaftProductEventsSendResult.Retryable(0)
        }
}

/** The shared network transport: Background priority, `X-Server-Id` per call, shared DTOs. */
internal class RaftNetworkProductEventsTransport(
    private val networkClient: RaftNetworkClient,
) : RaftProductEventsTransport {
    override suspend fun fetchConfig(serverId: ServerId): RaftProductEventsConfigResult =
        parseRaftProductEventsConfig(
            networkClient.request(
                priority = DataRequestPriority.Background,
                path = "product-events/config",
                serverIdOverride = serverId,
            )
        )

    override suspend fun sendBatch(serverId: ServerId, batch: RaftProductEventBatchRequest): RaftProductEventsSendResult =
        parseRaftProductEventsSend(
            networkClient.request(
                priority = DataRequestPriority.Background,
                path = "product-events/batch",
                method = "POST",
                serverIdOverride = serverId,
                bodyText = encodeRaftProductEventBatch(batch),
            )
        )
}
