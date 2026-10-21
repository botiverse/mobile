package build.raft.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

/**
 * Durable last-known snapshot per environment + account + server (+ entity), on the
 * `localSnapshotEntity` table (data-layer plan step 5; the generic form of
 * [RaftServerMembersDurableStore]). No schema migration: each consumer owns one snapshot scope.
 *
 * Rules (Tenny):
 * 1. Keyed by environment + account + server (+ entity id). Only an account / environment
 *    boundary fences writes: [clearAll] and the eviction calls advance [epoch]; a write captured
 *    under an older epoch is dropped. A server switch or a token refresh never moves the epoch.
 * 2. Lost access evicts: [evict] (403 / 404 on the entity), [evictServer] (removed from the
 *    server / server deleted) and [clearAll] (sign-out).
 * 3. Capped: at most [maxRowsPerServer] rows per environment + account + server,
 *    [maxRowsPerAccount] per environment + account and [maxRows] in total, least recently written
 *    first.
 */
internal class RaftKeyedDurableSnapshotStore<T>(
    private val storage: RaftDurableRowStorage,
    private val serializer: KSerializer<T>,
    private val label: String,
    private val maxRowsPerServer: Int = Int.MAX_VALUE,
    private val maxRowsPerAccount: Int = DefaultMaxRowsPerAccount,
    private val maxRows: Int = DefaultMaxRows,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val logger: (String) -> Unit = {},
) {
    private val mutex = Mutex()

    @Volatile
    private var epochValue = 0L

    /** Capture before a request starts; pass to [write] so a write across a boundary is dropped. */
    val epoch: Long
        get() = epochValue

    suspend fun read(key: RaftDurableSnapshotKey): T? {
        val id = key.rowId ?: return null
        return try {
            storage.read(id)?.let { encoded -> DurableJson.decodeFromString(serializer, encoded) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            logger("${label}_durable_read result=failure type=${error::class.simpleName}")
            null
        }
    }

    /** Persists [value] for [key] unless a boundary / eviction happened after [expectedEpoch]. */
    suspend fun write(key: RaftDurableSnapshotKey, value: T, expectedEpoch: Long): Boolean {
        val id = key.rowId ?: return false
        return mutex.withLock {
            if (expectedEpoch != epochValue) {
                logger("${label}_durable_write result=fenced serverId=${key.serverId}")
                return@withLock false
            }
            try {
                storage.write(id, DurableJson.encodeToString(serializer, value))
                val index = readIndexLocked().filterNot { it == id } + id
                val victims = capVictims(index)
                victims.forEach { victim -> storage.delete(victim) }
                writeIndexLocked(index.filterNot(victims::contains))
                true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                logger("${label}_durable_write result=failure type=${error::class.simpleName}")
                false
            }
        }
    }

    /** Lost access to one entity (403 / 404): drop its row and fence in-flight writes. */
    suspend fun evict(key: RaftDurableSnapshotKey) {
        val id = key.rowId ?: return
        mutex.withLock {
            epochValue += 1L
            runCatchingNonCancellation {
                storage.delete(id)
                writeIndexLocked(readIndexLocked().filterNot { it == id })
            }
        }
    }

    /** Lost access to [serverId] for [accountId] (every environment, every entity). */
    suspend fun evictServer(serverId: String, accountId: String? = null) {
        val serverKey = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        val accountKey = accountId?.trim()?.takeIf { it.isNotEmpty() }
        mutex.withLock {
            epochValue += 1L
            runCatchingNonCancellation {
                val ids = (readIndexLocked() + storage.ids().filterNot { it == IndexRowId }).distinct()
                val victims =
                    ids.filter { id ->
                        val parts = id.split(KeySeparator)
                        parts.size >= 3 && parts[2] == serverKey && (accountKey == null || parts[1] == accountKey)
                    }
                victims.forEach { storage.delete(it) }
                writeIndexLocked(readIndexLocked().filterNot(victims::contains))
            }
        }
    }

    /** Session end (sign-out): every row leaves with the session; in-flight writes are fenced. */
    suspend fun clearAll() {
        mutex.withLock {
            epochValue += 1L
            runCatchingNonCancellation {
                storage.ids().forEach { storage.delete(it) }
            }
        }
    }

    fun clearAllAsync() {
        scope.launch { clearAll() }
    }

    fun evictServerAsync(serverId: String, accountId: String? = null) {
        scope.launch { evictServer(serverId, accountId) }
    }

    /** Test seam: row ids in least-recently-written order. */
    internal suspend fun rowIdsForTest(): List<String> = mutex.withLock { readIndexLocked() }

    private fun capVictims(index: List<String>): Set<String> {
        val victims = linkedSetOf<String>()
        fun capGroups(groupOf: (String) -> String, cap: Int) {
            val limit = cap.coerceAtLeast(1)
            index.filterNot(victims::contains).groupBy(groupOf).values.forEach { ids ->
                if (ids.size > limit) victims += ids.take(ids.size - limit)
            }
        }
        capGroups({ id -> id.split(KeySeparator).take(3).joinToString(KeySeparator) }, maxRowsPerServer)
        capGroups({ id -> id.split(KeySeparator).take(2).joinToString(KeySeparator) }, maxRowsPerAccount)
        val survivors = index.filterNot(victims::contains)
        val totalCap = maxRows.coerceAtLeast(1)
        if (survivors.size > totalCap) victims += survivors.take(survivors.size - totalCap)
        return victims
    }

    private suspend fun readIndexLocked(): List<String> =
        storage.read(IndexRowId)
            ?.let { encoded -> runCatching { DurableJson.decodeFromString(IndexSerializer, encoded) }.getOrNull() }
            .orEmpty()

    private suspend fun writeIndexLocked(ids: List<String>) {
        if (ids.isEmpty()) {
            storage.delete(IndexRowId)
        } else {
            storage.write(IndexRowId, DurableJson.encodeToString(IndexSerializer, ids.distinct()))
        }
    }

    private suspend fun runCatchingNonCancellation(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            logger("${label}_durable_mutation result=failure type=${error::class.simpleName}")
        }
    }

    internal companion object {
        const val DefaultMaxRowsPerAccount = 12
        const val DefaultMaxRows = 24
        internal const val KeySeparator = "\u001f"
        private const val IndexRowId = "\u0000index"
        private val DurableJson = Json { ignoreUnknownKeys = true }
        private val IndexSerializer = ListSerializer(String.serializer())
    }
}

/** Environment + account + server (+ entity) owner of one durable row. */
internal data class RaftDurableSnapshotKey(
    val environmentId: String,
    val accountId: String,
    val serverId: String,
    val entityId: String? = null,
) {
    val rowId: String?
        get() {
            val environment = environmentId.trim().trimEnd('/').takeIf { it.isNotEmpty() } ?: return null
            val account = accountId.trim().takeIf { it.isNotEmpty() } ?: return null
            val server = serverId.trim().takeIf { it.isNotEmpty() } ?: return null
            val entity =
                if (entityId == null) {
                    null
                } else {
                    entityId.trim().takeIf { it.isNotEmpty() } ?: return null
                }
            val parts = listOfNotNull(environment, account, server, entity)
            if (parts.any { it.contains(RaftKeyedDurableSnapshotStore.KeySeparator) }) return null
            return parts.joinToString(RaftKeyedDurableSnapshotStore.KeySeparator)
        }
}

/** Raw row storage. Production uses the `localSnapshotEntity` table; tests use a map. */
internal interface RaftDurableRowStorage {
    suspend fun read(id: String): String?

    suspend fun write(id: String, value: String)

    suspend fun delete(id: String)

    suspend fun ids(): List<String>
}

/** [RaftDurableRowStorage] on `localSnapshotEntity`, one persisted [snapshotScope] per consumer. */
internal class SqlDelightRaftDurableRowStorage(
    private val database: RaftSqlDatabase,
    private val snapshotScope: String,
    private val now: () -> Long = ::defaultNowMillis,
) : RaftDurableRowStorage {
    override suspend fun read(id: String): String? =
        RaftStoreThreading.runOffStoreDispatcher {
            database.queries.selectSnapshot(snapshotScope, id).executeAsOneOrNull()
        }

    override suspend fun write(id: String, value: String) {
        RaftStoreThreading.runOffStoreDispatcher {
            database.queries.upsertSnapshot(snapshotScope, id, value, now())
        }
    }

    override suspend fun delete(id: String) {
        RaftStoreThreading.runOffStoreDispatcher {
            database.queries.deleteSnapshot(snapshotScope, id)
        }
    }

    override suspend fun ids(): List<String> =
        RaftStoreThreading.runOffStoreDispatcher {
            database.queries.selectSnapshotIds(snapshotScope).executeAsList()
        }
}
