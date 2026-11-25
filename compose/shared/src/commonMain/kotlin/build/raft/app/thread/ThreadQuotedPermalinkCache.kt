package build.raft.app

import build.raft.app.data.RaftQuotedPermalinkViewer
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
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
 * Process-memory LRU cache of resolved [ThreadQuotedPermalinkMessage]s (#proj-mobile:#383):
 * - keyed by `(accountUserId, serverId, channelId, messageId)` so another account or server on the
 *   same device never sees a previous viewer's quoted messages;
 * - bounded at [maxEntries] entries with true LRU eviction (hits promote recency) and a soft
 *   [ttl] measured via [timeSource];
 * - cleared on sign-out, account switch, or environment switch ([clear]), and per lost server
 *   ([dropServer]), with a monotonic generation guard so in-flight fetches started before eviction
 *   cannot repopulate cleared state;
 * - never caches transient `Unavailable` / `null` failures so retries still work.
 */
internal class ThreadQuotedPermalinkCache(
    private val maxEntries: Int = THREAD_QUOTED_PERMALINK_CACHE_MAX_ENTRIES,
    private val ttl: Duration = THREAD_QUOTED_PERMALINK_CACHE_TTL,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(!ttl.isNegative() && ttl > Duration.ZERO) { "ttl must be positive" }
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
    private var generation: Long = 0L

    fun currentGeneration(): Long = synchronized(lock) { generation }

    /**
     * Synchronous cache lookup safe to call inside Compose `remember(...)` on Frame 1. Returns
     * `null` on miss, missing viewer/message scope, or TTL expiration.
     */
    fun peek(
        accountUserId: String?,
        serverId: String?,
        channelId: String?,
        messageId: String?,
    ): ThreadQuotedPermalinkMessage? {
        val key = keyOf(accountUserId, serverId, channelId, messageId) ?: return null
        return synchronized(lock) {
            val existing = entries[key] ?: return@synchronized null
            if (existing.cachedAt.elapsedNow() >= ttl) {
                entries.remove(key)
                return@synchronized null
            }
            entries.remove(key)
            entries[key] = existing
            existing.message
        }
    }

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
            entries.remove(key)
            entries[key] = Entry(message = message, cachedAt = timeSource.markNow())
            while (entries.size > maxEntries) {
                val oldestKey = entries.keys.firstOrNull() ?: break
                entries.remove(oldestKey)
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
    ): ThreadQuotedPermalinkMessage? {
        val viewer = viewerProvider()
        if (viewer != null) {
            peek(
                accountUserId = viewer.userId,
                serverId = viewer.serverId,
                channelId = channelId,
                messageId = messageId,
            )?.let { return it }
        }
        val startGeneration = currentGeneration()
        val resolved = fetch(viewer) ?: return null
        if (viewer != null) {
            val currentViewer = viewerProvider()
            if (currentViewer?.userId != viewer.userId || currentViewer?.serverId != viewer.serverId) {
                return null
            }
            put(
                accountUserId = viewer.userId,
                serverId = viewer.serverId,
                channelId = channelId,
                messageId = messageId,
                message = resolved,
                expectedGeneration = startGeneration,
            )
        }
        return resolved
    }

    /** Clears every cached entry (sign-out, account switch, or environment switch) and fences in-flight writes. */
    fun clear() {
        synchronized(lock) {
            entries.clear()
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
