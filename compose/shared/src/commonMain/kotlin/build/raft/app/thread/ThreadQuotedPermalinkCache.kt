package build.raft.app.thread

import build.raft.app.data.RaftQuotedPermalinkViewer
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Maximum number of resolved quoted message permalinks kept in process memory. */
internal const val THREAD_QUOTED_PERMALINK_CACHE_MAX_ENTRIES: Int = 200

/**
 * Soft TTL for cached quoted message permalinks so edits or retractions refresh eventually while
 * routine scrolling and conversation re-opens render immediately with zero loading flash.
 */
internal val THREAD_QUOTED_PERMALINK_CACHE_TTL: Duration = 5.minutes

/**
 * Short TTL for definitive 404/403 refusals (#proj-mobile:#386 P1-3) so scrolling back or
 * reopening a thread renders Unavailable immediately without flashing "Loading message…" or
 * hammering the server. Transient 5xx/network errors are never cached.
 */
internal val THREAD_QUOTED_PERMALINK_NEGATIVE_CACHE_TTL: Duration = 60.seconds

internal sealed interface ThreadQuotedPermalinkFetchOutcome {
    data class Loaded(val message: ThreadQuotedPermalinkMessage) : ThreadQuotedPermalinkFetchOutcome
    /** Behind the Free plan's history window (404 `history_limited`): kept in `RaftQuotedPermalinkHistoryLimitedMemo`, never negative-cached here. */
    data class Older(val target: build.raft.app.data.MessageContextHiddenTarget) : ThreadQuotedPermalinkFetchOutcome
    /** Definitive refusal (404/403/missing target) eligible for short negative caching. */
    data object DefinitiveUnavailable : ThreadQuotedPermalinkFetchOutcome
    /** Transient failure (5xx/429/network/401): never cached here. */
    data object TransientFailure : ThreadQuotedPermalinkFetchOutcome
}

/**
 * Process-memory LRU cache of resolved [ThreadQuotedPermalinkMessage]s (#proj-mobile:#383) and
 * short-TTL definitive 404/403 negative entries (#proj-mobile:#386):
 * - keyed by `(accountUserId, serverId, channelId, messageId)` so another account or server on the
 *   same device never sees a previous viewer's quoted messages;
 * - bounded at [maxEntries] entries with true LRU eviction (hits promote recency) and soft
 *   [ttl] / [negativeTtl] measured via [timeSource];
 * - cleared on sign-out, account switch, or environment switch ([clear]), and per lost server
 *   ([dropServer]), with a monotonic generation guard so in-flight fetches started before eviction
 *   cannot repopulate cleared state;
 * - never caches transient 5xx/network failures so retries still work.
 */
internal class ThreadQuotedPermalinkCache(
    private val maxEntries: Int = THREAD_QUOTED_PERMALINK_CACHE_MAX_ENTRIES,
    private val ttl: Duration = THREAD_QUOTED_PERMALINK_CACHE_TTL,
    private val negativeTtl: Duration = THREAD_QUOTED_PERMALINK_NEGATIVE_CACHE_TTL,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(!ttl.isNegative() && ttl > Duration.ZERO) { "ttl must be positive" }
        require(!negativeTtl.isNegative() && negativeTtl > Duration.ZERO) { "negativeTtl must be positive" }
    }

    private data class Key(
        val accountUserId: String,
        val serverId: String,
        val channelId: String,
        val messageId: String,
    )

    private data class Entry(
        val message: ThreadQuotedPermalinkMessage,
        val cachedAt: TimeMark,
    )

    private val lock = SynchronizedObject()
    private val entries = LinkedHashMap<Key, Entry>()
    private val negativeEntries = LinkedHashMap<Key, TimeMark>()
    private var generation: Long = 0L

    fun currentGeneration(): Long = synchronized(lock) { generation }

    /**
     * Synchronous tri-state cache lookup safe to call inside Compose `remember(...)` on Frame 1.
     */
    fun peekLookup(
        accountUserId: String?,
        serverId: String?,
        channelId: String?,
        messageId: String?,
    ): ThreadQuotedPermalinkCacheLookup {
        val key = keyOf(accountUserId, serverId, channelId, messageId) ?: return ThreadQuotedPermalinkCacheLookup.Miss
        return synchronized(lock) {
            val existing = entries[key]
            if (existing != null) {
                if (existing.cachedAt.elapsedNow() >= ttl) {
                    entries.remove(key)
                } else {
                    entries.remove(key)
                    entries[key] = existing
                    return@synchronized ThreadQuotedPermalinkCacheLookup.Hit(existing.message)
                }
            }
            val negativeAt = negativeEntries[key]
            if (negativeAt != null) {
                if (negativeAt.elapsedNow() >= negativeTtl) {
                    negativeEntries.remove(key)
                } else {
                    negativeEntries.remove(key)
                    negativeEntries[key] = negativeAt
                    return@synchronized ThreadQuotedPermalinkCacheLookup.KnownUnavailable
                }
            }
            ThreadQuotedPermalinkCacheLookup.Miss
        }
    }

    /**
     * Synchronous cache lookup safe to call inside Compose `remember(...)` on Frame 1. Returns
     * `null` on miss, known-unavailable, missing viewer/message scope, or TTL expiration.
     */
    fun peek(
        accountUserId: String?,
        serverId: String?,
        channelId: String?,
        messageId: String?,
    ): ThreadQuotedPermalinkMessage? =
        (peekLookup(accountUserId, serverId, channelId, messageId) as? ThreadQuotedPermalinkCacheLookup.Hit)?.message

    /**
     * Stores [message] under `(accountUserId, serverId, channelId, messageId)` unless scope is
     * incomplete or [expectedGeneration] no longer matches the current cache generation.
     */
    fun put(
        accountUserId: String?,
        serverId: String?,
        channelId: String?,
        messageId: String?,
        message: ThreadQuotedPermalinkMessage,
        expectedGeneration: Long? = null,
    ) {
        val key = keyOf(accountUserId, serverId, channelId, messageId) ?: return
        synchronized(lock) {
            if (expectedGeneration != null && generation != expectedGeneration) return
            negativeEntries.remove(key)
            entries.remove(key)
            entries[key] = Entry(message = message, cachedAt = timeSource.markNow())
            while (entries.size > maxEntries) {
                val oldestKey = entries.keys.firstOrNull() ?: break
                entries.remove(oldestKey)
            }
        }
    }

    /**
     * Stores a short-TTL negative entry for a definitive 404/403 refusal unless scope is
     * incomplete or [expectedGeneration] no longer matches the current cache generation.
     */
    fun putUnavailable(
        accountUserId: String?,
        serverId: String?,
        channelId: String?,
        messageId: String?,
        expectedGeneration: Long? = null,
    ) {
        val key = keyOf(accountUserId, serverId, channelId, messageId) ?: return
        synchronized(lock) {
            if (expectedGeneration != null && generation != expectedGeneration) return
            entries.remove(key)
            negativeEntries.remove(key)
            negativeEntries[key] = timeSource.markNow()
            while (negativeEntries.size > maxEntries) {
                val oldestKey = negativeEntries.keys.firstOrNull() ?: break
                negativeEntries.remove(oldestKey)
            }
        }
    }

    /**
     * Resolves a quoted permalink from cache when fresh; otherwise invokes [fetch], verifying that
     * the viewer identity and cache generation remain unchanged before storing a non-null result.
     * Transient `null` (`Unavailable`) results are never cached.
     */
    suspend fun getOrFetch(
        viewerProvider: suspend () -> RaftQuotedPermalinkViewer?,
        channelId: String?,
        messageId: String,
        fetch: suspend (RaftQuotedPermalinkViewer?) -> ThreadQuotedPermalinkMessage?,
    ): ThreadQuotedPermalinkMessage? =
        getOrFetchOutcome(
            viewerProvider = viewerProvider,
            channelId = channelId,
            messageId = messageId,
        ) { viewer ->
            val resolved = fetch(viewer)
            if (resolved != null) {
                ThreadQuotedPermalinkFetchOutcome.Loaded(resolved)
            } else {
                ThreadQuotedPermalinkFetchOutcome.TransientFailure
            }
        }

    /**
     * Tri-state resolver supporting short-TTL negative caching for definitive 404/403 refusals
     * while keeping transient 5xx/network failures uncached so retries succeed.
     */
    suspend fun getOrFetchOutcome(
        viewerProvider: suspend () -> RaftQuotedPermalinkViewer?,
        channelId: String?,
        messageId: String,
        fetch: suspend (RaftQuotedPermalinkViewer?) -> ThreadQuotedPermalinkFetchOutcome,
    ): ThreadQuotedPermalinkMessage? =
        (getOrFetchResult(viewerProvider, channelId, messageId, fetch) as? ThreadQuotedPermalinkLoadResult.Loaded)?.message

    /**
     * Full-outcome resolver returning [ThreadQuotedPermalinkLoadResult.Loaded],
     * [ThreadQuotedPermalinkLoadResult.Older] (404 `history_limited`, never negative-cached here),
     * or [ThreadQuotedPermalinkLoadResult.Unavailable].
     */
    suspend fun getOrFetchResult(
        viewerProvider: suspend () -> RaftQuotedPermalinkViewer?,
        channelId: String?,
        messageId: String,
        fetch: suspend (RaftQuotedPermalinkViewer?) -> ThreadQuotedPermalinkFetchOutcome,
    ): ThreadQuotedPermalinkLoadResult {
        val viewer = viewerProvider()
        if (viewer != null) {
            when (
                val cached =
                    peekLookup(
                        accountUserId = viewer.userId,
                        serverId = viewer.serverId,
                        channelId = channelId,
                        messageId = messageId,
                    )
            ) {
                is ThreadQuotedPermalinkCacheLookup.Hit -> return ThreadQuotedPermalinkLoadResult.Loaded(cached.message)
                is ThreadQuotedPermalinkCacheLookup.Older -> return ThreadQuotedPermalinkLoadResult.Older(cached.target)
                ThreadQuotedPermalinkCacheLookup.KnownUnavailable -> return ThreadQuotedPermalinkLoadResult.Unavailable
                ThreadQuotedPermalinkCacheLookup.Miss -> Unit
            }
        }
        val startGeneration = currentGeneration()
        return when (val outcome = fetch(viewer)) {
            is ThreadQuotedPermalinkFetchOutcome.Loaded -> {
                if (viewer != null) {
                    val currentViewer = viewerProvider()
                    if (currentViewer?.userId != viewer.userId || currentViewer?.serverId != viewer.serverId) {
                        return ThreadQuotedPermalinkLoadResult.Unavailable
                    }
                    put(
                        accountUserId = viewer.userId,
                        serverId = viewer.serverId,
                        channelId = channelId,
                        messageId = messageId,
                        message = outcome.message,
                        expectedGeneration = startGeneration,
                    )
                }
                ThreadQuotedPermalinkLoadResult.Loaded(outcome.message)
            }
            is ThreadQuotedPermalinkFetchOutcome.Older -> {
                if (viewer != null) {
                    val currentViewer = viewerProvider()
                    if (currentViewer?.userId != viewer.userId || currentViewer?.serverId != viewer.serverId) {
                        return ThreadQuotedPermalinkLoadResult.Unavailable
                    }
                }
                ThreadQuotedPermalinkLoadResult.Older(outcome.target)
            }
            ThreadQuotedPermalinkFetchOutcome.DefinitiveUnavailable -> {
                if (viewer != null) {
                    val currentViewer = viewerProvider()
                    if (currentViewer?.userId == viewer.userId && currentViewer?.serverId == viewer.serverId) {
                        putUnavailable(
                            accountUserId = viewer.userId,
                            serverId = viewer.serverId,
                            channelId = channelId,
                            messageId = messageId,
                            expectedGeneration = startGeneration,
                        )
                    }
                }
                ThreadQuotedPermalinkLoadResult.Unavailable
            }
            ThreadQuotedPermalinkFetchOutcome.TransientFailure -> ThreadQuotedPermalinkLoadResult.Unavailable
        }
    }

    /** Clears every cached entry (sign-out, account switch, or environment switch) and fences in-flight writes. */
    fun clear() {
        synchronized(lock) {
            entries.clear()
            negativeEntries.clear()
            generation += 1L
        }
    }

    /** Drops every cached entry for [serverId] (optionally scoped to [accountUserId]) and fences in-flight writes. */
    fun dropServer(serverId: String, accountUserId: String? = null) {
        val cleanServerId = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        val cleanAccountId = accountUserId?.trim()?.takeIf { it.isNotEmpty() }
        synchronized(lock) {
            val iterator = entries.entries.iterator()
            while (iterator.hasNext()) {
                val key = iterator.next().key
                if (key.serverId == cleanServerId && (cleanAccountId == null || key.accountUserId == cleanAccountId)) {
                    iterator.remove()
                }
            }
            val negativeIterator = negativeEntries.entries.iterator()
            while (negativeIterator.hasNext()) {
                val key = negativeIterator.next().key
                if (key.serverId == cleanServerId && (cleanAccountId == null || key.accountUserId == cleanAccountId)) {
                    negativeIterator.remove()
                }
            }
            generation += 1L
        }
    }

    private fun keyOf(
        accountUserId: String?,
        serverId: String?,
        channelId: String?,
        messageId: String?,
    ): Key? {
        val user = accountUserId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val server = serverId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val message = messageId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Key(
            accountUserId = user,
            serverId = server,
            channelId = channelId?.trim().orEmpty(),
            messageId = message,
        )
    }
}
