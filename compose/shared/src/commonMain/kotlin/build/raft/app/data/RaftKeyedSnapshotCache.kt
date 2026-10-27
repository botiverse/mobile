package build.raft.app.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Process cache of one entity kind keyed by environment + account + server + entity id (data-layer
 * plan step 5: joint connections, agent detail). Pages read [peek] synchronously for their first
 * frame, refresh once in the background and [write] the answer back.
 *
 * Rules (Tenny):
 * 1. Keyed by account, never by a session / request generation: a server switch or a token refresh
 *    keeps every entry, and a late answer for server A after switching to B still lands on A's key.
 *    Only an account boundary ([clearAll]) or an eviction fences writes, through the [ticket]
 *    captured before the request.
 * 2. Lost access evicts: [evict] (403 / 404 on the entity), [dropServer] (removed from the server /
 *    server deleted), [clearAll] (sign-out).
 * 3. Capped per server ([maxPerServer], least recently used first) and in total ([maxTotal]).
 *
 * [durable] (optional) persists entries through [toDurable] / [fromDurable] so a cold start paints
 * from disk; a null [durable] keeps the cache memory-only.
 */
internal class RaftKeyedSnapshotCache<T : Any, D>(
    private val durable: RaftKeyedDurableSnapshotStore<D>? = null,
    private val toDurable: (T) -> D? = { null },
    private val fromDurable: (D) -> T? = { null },
    private val maxPerServer: Int = DefaultMaxPerServer,
    private val maxTotal: Int = DefaultMaxTotal,
) {
    private val lock = SynchronizedObject()

    // Access-ordered: the first entry is the least recently used.
    private val entries = LinkedHashMap<String, Pair<RaftDurableSnapshotKey, T>>()
    private var memoryEpoch = 0L

    /** Captured before a request; a [write] under an older ticket is dropped. */
    data class Ticket(val memory: Long, val durable: Long)

    fun ticket(): Ticket = synchronized(lock) { Ticket(memoryEpoch, durable?.epoch ?: 0L) }

    /** Synchronous first-frame read (memory only). */
    fun peek(key: RaftDurableSnapshotKey): T? {
        val id = key.rowId ?: return null
        return synchronized(lock) {
            entries.remove(id)?.also { entry -> entries[id] = entry }?.second
        }
    }

    /** Memory, else the durable row (which then seeds memory). */
    suspend fun read(key: RaftDurableSnapshotKey): T? {
        peek(key)?.let { return it }
        val store = durable ?: return null
        val ticket = ticket()
        val value = store.read(key)?.let(fromDurable) ?: return null
        synchronized(lock) {
            if (ticket.memory != memoryEpoch) return null
            putLocked(key, value)
        }
        return value
    }

    /** Stores [value] for [key] unless an account boundary / eviction happened after [ticket]. */
    suspend fun write(key: RaftDurableSnapshotKey, value: T, ticket: Ticket): Boolean {
        val accepted =
            synchronized(lock) {
                if (ticket.memory != memoryEpoch || key.rowId == null) {
                    false
                } else {
                    putLocked(key, value)
                    true
                }
            }
        if (!accepted) return false
        val store = durable ?: return true
        toDurable(value)?.let { record -> store.write(key, record, ticket.durable) }
        return true
    }

    suspend fun evict(key: RaftDurableSnapshotKey) {
        val id = key.rowId ?: return
        synchronized(lock) {
            memoryEpoch += 1L
            entries.remove(id)
        }
        durable?.evict(key)
    }

    /** Lost access to [serverId] (every environment; [accountId] null = every account). */
    suspend fun dropServer(serverId: String, accountId: String? = null) {
        val server = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        val account = accountId?.trim()?.takeIf { it.isNotEmpty() }
        synchronized(lock) {
            memoryEpoch += 1L
            entries.entries.removeAll { (_, entry) ->
                entry.first.serverId.trim() == server && (account == null || entry.first.accountId.trim() == account)
            }
        }
        durable?.evictServer(server, account)
    }

    /**
     * Sign-out: the in-memory entries leave with the session; the durable rows stay (they are keyed by
     * environment + account, so only the same account reads them again).
     */
    fun clearMemory() {
        synchronized(lock) {
            memoryEpoch += 1L
            entries.clear()
        }
    }

    /** Memory and disk. */
    suspend fun clearAll() {
        synchronized(lock) {
            memoryEpoch += 1L
            entries.clear()
        }
        durable?.clearAll()
    }

    /** Test seam. */
    internal fun sizeForTest(): Int = synchronized(lock) { entries.size }

    private fun putLocked(key: RaftDurableSnapshotKey, value: T) {
        val id = key.rowId ?: return
        entries.remove(id)
        entries[id] = key to value
        val serverOf: (RaftDurableSnapshotKey) -> Triple<String, String, String> = {
            Triple(it.environmentId.trim().trimEnd('/'), it.accountId.trim(), it.serverId.trim())
        }
        val server = serverOf(key)
        val sameServer = entries.filter { (_, entry) -> serverOf(entry.first) == server }.keys.toList()
        val perServerCap = maxPerServer.coerceAtLeast(1)
        if (sameServer.size > perServerCap) {
            sameServer.take(sameServer.size - perServerCap).forEach(entries::remove)
        }
        val totalCap = maxTotal.coerceAtLeast(1)
        while (entries.size > totalCap) {
            entries.remove(entries.keys.first())
        }
    }

    internal companion object {
        const val DefaultMaxPerServer = 32
        const val DefaultMaxTotal = 128
    }
}
