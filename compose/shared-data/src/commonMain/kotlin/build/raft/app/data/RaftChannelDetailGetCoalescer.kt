package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Single-flight + short TTL response coalescer for plain `GET channels/<uuid>` detail calls
 * (Hands `5abe9394`: opening a conversation triggered 3–4 concurrent/staggered `GET channels/<id>`
 * calls within <1 s across conversation detail, peer read receipts, canonical sync, joint
 * connections, and conversion APIs — split by `serverIdOverride = null` vs explicit `ServerId`,
 * rapid completion after <200 ms, and unrelated `POST` writes invalidating generic GET dedupe).
 *
 * Guarantees:
 * - Concurrent plain `GET channels/<uuid>` requests for the exact same
 *   `(baseUrl, sessionUserId, sessionIdentity, serverId, channelId)` share ONE in-flight HTTP call.
 * - A successful HTTP `200` snapshot is reused for up to [ttlMillis] (default 3 s, monotonic clock).
 *   Non-200 responses are never cached.
 * - Fail-safe invalidation: ANY non-GET request targeting `channels/<uuid>` or `channels/<uuid>/...`
 *   (or a non-UUID `channels/...` mutation) immediately evicts cached entries, detaches pre-mutation
 *   in-flight entries, and advances a generation counter both before and after the mutation so
 *   pre-mutation reads can never populate the cache or be joined by post-mutation callers.
 * - Cancellation isolation: if the sender cancels while waiters are awaiting the shared flight,
 *   waiters do not inherit the cancellation and transparently retry; if a waiter cancels, the
 *   sender and other waiters are unaffected.
 */
@RaftCoreInternalApi class RaftChannelDetailGetCoalescer(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxGenerationEntries: Int = DEFAULT_MAX_GENERATION_ENTRIES,
    private val nowMillis: () -> Long = channelDetailMonotonicMillisClock(),
) {
    init {
        require(maxEntries > 0)
        require(maxGenerationEntries > 0)
    }

    @RaftCoreInternalApi data class Key(
        val baseUrl: String,
        val sessionUserId: String,
        val sessionIdentity: String,
        val serverId: String,
        val channelId: String,
    ) {
        override fun toString(): String =
            "Key(baseUrl=$baseUrl, sessionUserId=$sessionUserId, sessionIdentity=<redacted>, serverId=$serverId, channelId=$channelId)"
    }

    private data class ScopeKey(
        val baseUrl: String,
        val sessionUserId: String,
    )

    private data class ChannelScopeKey(
        val baseUrl: String,
        val sessionUserId: String,
        val channelId: String,
    )

    private data class Generation(
        val global: Long,
        val scope: Long,
        val channel: Long,
    )

    private class Flight(
        val generation: Generation,
    ) {
        val answer = CompletableDeferred<HomeNetworkResponse>()
    }

    private data class CachedEntry(
        val response: HomeNetworkResponse,
        val generation: Generation,
        val cachedAtMillis: Long,
    )

    /** The sender cancelled before answering; active waiters re-enter and send on their own. */
    private class SenderGone : CancellationException("channel detail GET sender cancelled")

    private val lock = SynchronizedObject()
    private val flights = mutableMapOf<Key, Flight>()
    private val cache = linkedMapOf<Key, CachedEntry>()
    private val scopeGenerations = mutableMapOf<ScopeKey, Long>()
    private val channelGenerations = mutableMapOf<ChannelScopeKey, Long>()
    private var globalGeneration: Long = 0L

    /** Diagnostics / test counters. */
    var sent: Long = 0L
        private set
    var joined: Long = 0L
        private set
    var cacheHits: Long = 0L
        private set

    fun invalidateChannel(baseUrl: String, sessionUserId: String?, channelId: String) {
        val normalizedUser = sessionUserId?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedChannel = channelId.trim().lowercase()
        synchronized(lock) {
            if (normalizedUser == null || normalizedChannel.isEmpty()) {
                globalGeneration += 1L
                cache.clear()
                flights.clear()
                return
            }
            val scopeKey = ChannelScopeKey(baseUrl, normalizedUser, normalizedChannel)
            channelGenerations[scopeKey] = (channelGenerations[scopeKey] ?: 0L) + 1L
            if (channelGenerations.size > maxGenerationEntries) {
                globalGeneration += 1L
                channelGenerations.clear()
            }
            cache.entries.removeAll { (key, _) ->
                key.baseUrl == baseUrl && key.sessionUserId == normalizedUser && key.channelId == normalizedChannel
            }
            flights.entries.removeAll { (key, _) ->
                key.baseUrl == baseUrl && key.sessionUserId == normalizedUser && key.channelId == normalizedChannel
            }
        }
    }

    fun invalidateAllChannels(baseUrl: String, sessionUserId: String?) {
        val normalizedUser = sessionUserId?.trim()?.takeIf { it.isNotEmpty() }
        synchronized(lock) {
            if (normalizedUser == null) {
                globalGeneration += 1L
                cache.clear()
                flights.clear()
                return
            }
            val scopeKey = ScopeKey(baseUrl, normalizedUser)
            scopeGenerations[scopeKey] = (scopeGenerations[scopeKey] ?: 0L) + 1L
            if (scopeGenerations.size > maxGenerationEntries) {
                globalGeneration += 1L
                scopeGenerations.clear()
            }
            cache.entries.removeAll { (key, _) ->
                key.baseUrl == baseUrl && key.sessionUserId == normalizedUser
            }
            flights.entries.removeAll { (key, _) ->
                key.baseUrl == baseUrl && key.sessionUserId == normalizedUser
            }
        }
    }

    suspend fun <T> writeChannel(
        baseUrl: String,
        sessionUserId: String?,
        channelId: String,
        send: suspend () -> T,
    ): T {
        invalidateChannel(baseUrl, sessionUserId, channelId)
        try {
            return send()
        } finally {
            invalidateChannel(baseUrl, sessionUserId, channelId)
        }
    }

    suspend fun <T> writeAllChannels(
        baseUrl: String,
        sessionUserId: String?,
        send: suspend () -> T,
    ): T {
        invalidateAllChannels(baseUrl, sessionUserId)
        try {
            return send()
        } finally {
            invalidateAllChannels(baseUrl, sessionUserId)
        }
    }

    suspend fun run(
        key: Key,
        send: suspend () -> HomeNetworkResponse,
    ): HomeNetworkResponse {
        while (true) {
            val now = nowMillis()
            var mine: Flight? = null
            var cachedHit: HomeNetworkResponse? = null
            val joinedFlight =
                synchronized(lock) {
                    pruneExpiredLocked(now)
                    val generation = generationLocked(key)
                    if (ttlMillis > 0L) {
                        val cached = cache[key]
                        if (cached != null) {
                            if (cached.generation == generation && now - cached.cachedAtMillis < ttlMillis) {
                                cache.remove(key)
                                cache[key] = cached
                                cacheHits += 1
                                cachedHit = cached.response
                                null
                            } else {
                                cache.remove(key)
                                resolveFlightLocked(key, generation).also { (started, _) -> mine = started }.second
                            }
                        } else {
                            resolveFlightLocked(key, generation).also { (started, _) -> mine = started }.second
                        }
                    } else {
                        resolveFlightLocked(key, generation).also { (started, _) -> mine = started }.second
                    }
                }
            if (cachedHit != null) return cachedHit!!
            if (joinedFlight != null) {
                try {
                    return joinedFlight.answer.await()
                } catch (cancellation: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    continue
                }
            }
            val flight = mine ?: continue
            try {
                val response = send()
                val completedAt = nowMillis()
                synchronized(lock) {
                    if (flights[key] === flight) flights.remove(key)
                    if (response.code == 200 && ttlMillis > 0L && generationLocked(key) == flight.generation) {
                        cache.remove(key)
                        cache[key] = CachedEntry(response = response, generation = flight.generation, cachedAtMillis = completedAt)
                        while (cache.size > maxEntries) {
                            cache.remove(cache.keys.first())
                        }
                    }
                }
                flight.answer.complete(response)
                return response
            } catch (cancellation: CancellationException) {
                synchronized(lock) {
                    if (flights[key] === flight) flights.remove(key)
                }
                flight.answer.completeExceptionally(SenderGone())
                throw cancellation
            } catch (error: Throwable) {
                synchronized(lock) {
                    if (flights[key] === flight) flights.remove(key)
                }
                flight.answer.completeExceptionally(error)
                throw error
            }
        }
    }

    private fun resolveFlightLocked(key: Key, generation: Generation): Pair<Flight?, Flight?> {
        val existing = flights[key]
        return if (existing != null && !existing.answer.isCompleted && existing.generation == generation) {
            joined += 1
            null to existing
        } else {
            val created = Flight(generation)
            flights[key] = created
            sent += 1
            created to null
        }
    }

    private fun generationLocked(key: Key): Generation =
        Generation(
            global = globalGeneration,
            scope = scopeGenerations[ScopeKey(key.baseUrl, key.sessionUserId)] ?: 0L,
            channel = channelGenerations[ChannelScopeKey(key.baseUrl, key.sessionUserId, key.channelId)] ?: 0L,
        )

    private fun pruneExpiredLocked(now: Long) {
        if (ttlMillis <= 0L || cache.isEmpty()) return
        cache.entries.removeAll { (_, entry) -> now - entry.cachedAtMillis >= ttlMillis }
    }

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 3_000L
        private const val DEFAULT_MAX_ENTRIES: Int = 64
        private const val DEFAULT_MAX_GENERATION_ENTRIES: Int = 512
    }
}

/** Extracts the lowercase channel UUID from a plain `channels/<uuid>` path, or null otherwise. */
@RaftCoreInternalApi fun extractPlainChannelDetailId(path: String): String? {
    val normalized = path.trim().trimStart('/')
    if (!normalized.startsWith("channels/")) return null
    val rest = normalized.removePrefix("channels/")
    if (rest.indexOf('/') >= 0 || rest.indexOf('?') >= 0 || rest.indexOf('#') >= 0) return null
    return rest.takeIf { it.isCanonicalChannelUuid() }?.lowercase()
}

/** Extracts the lowercase channel UUID targeted by `channels/<uuid>` or `channels/<uuid>/...`, or null. */
@RaftCoreInternalApi fun extractMutatedChannelId(path: String): String? {
    val normalized = path.trim().trimStart('/').substringBefore('?').substringBefore('#')
    if (!normalized.startsWith("channels/")) return null
    val segment = normalized.removePrefix("channels/").substringBefore('/')
    return segment.takeIf { it.isCanonicalChannelUuid() }?.lowercase()
}

/** True for any path on the `channels` route (`channels` or `channels/...`). */
@RaftCoreInternalApi fun isChannelRoutePath(path: String): Boolean {
    val normalized = path.trim().trimStart('/').substringBefore('?').substringBefore('#')
    return normalized == "channels" || normalized.startsWith("channels/")
}

private fun String.isCanonicalChannelUuid(): Boolean {
    if (length != 36) return false
    for (index in indices) {
        val ch = this[index]
        if (index == 8 || index == 13 || index == 18 || index == 23) {
            if (ch != '-') return false
        } else if (ch !in '0'..'9' && ch !in 'a'..'f' && ch !in 'A'..'F') {
            return false
        }
    }
    return true
}

private fun channelDetailMonotonicMillisClock(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}
