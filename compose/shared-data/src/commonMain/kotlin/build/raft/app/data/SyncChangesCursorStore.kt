package build.raft.app.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val SYNC_CHANGES_CURSOR_STORAGE_KEY: String = "syncChangesCursor.v1"

/** Whose cursor: one per environment, account and server. */
data class RaftSyncScope(
    val environmentId: String,
    val accountId: String,
    val serverId: String,
) {
    companion object {
        /** Null when any part is blank (no signed-in binding yet). */
        fun of(environmentId: String?, accountId: String?, serverId: String?): RaftSyncScope? {
            val environment = environmentId?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
            val account = accountId?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val server = serverId?.trim()?.takeIf { it.isNotBlank() } ?: return null
            return RaftSyncScope(environment, account, server)
        }
    }
}

@Serializable
private data class PersistedSyncCursor(
    val environmentId: String,
    val accountId: String,
    val serverId: String,
    val cursor: String,
    /**
     * Consecutive failed pulls (network, 5xx, timeout) on [cursor]. Stored with the cursor so an
     * app restart does not make every failure the first one (#proj-mobile:#112). A new cursor
     * starts at 0; documents written before this field read as 0.
     */
    val failures: Int = 0,
)

@Serializable
private data class PersistedSyncCursorDocument(
    val version: Int = 1,
    val entries: List<PersistedSyncCursor> = emptyList(),
)

private val syncCursorJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * The `GET /api/sync/changes` cursor per (environment, account, server), on disk so a cold start
 * pulls only what changed since the last session.
 *
 * Same lifetime as the other account caches: kept on sign-out with the account's stored data
 * (owner decision 2026-10-27, see AccountLocalData), [clearServer] when the account loses a server. A missing cursor means "first sync"
 * (snapshots, then pull), so clearing is always safe.
 *
 * Each cursor carries its count of consecutive failed pulls ([recordFailure]): the engine forgets
 * a cursor only on the second one in a row (#proj-mobile:#112). [write] (a new cursor, or a pull
 * that succeeded) sets it back to 0.
 */
class RaftSyncCursorStore(
    private val store: SharedPreferenceStore,
    private val maxEntries: Int = 64,
) {
    private val lock = SynchronizedObject()

    fun read(scope: RaftSyncScope): String? =
        synchronized(lock) { readLocked().firstOrNull { it.matches(scope) }?.cursor }

    /** Consecutive failed pulls on [scope]'s stored cursor (0 without one). */
    fun failures(scope: RaftSyncScope): Int =
        synchronized(lock) { readLocked().firstOrNull { it.matches(scope) }?.failures ?: 0 }

    /**
     * One more failed pull on [cursor]; returns the count including it. Only a stored cursor
     * counts: when [cursor] is no longer [scope]'s (cleared or replaced meanwhile) nothing is
     * written and this is the first failure (1).
     */
    fun recordFailure(scope: RaftSyncScope, cursor: String): Int =
        synchronized(lock) {
            val entries = readLocked()
            val index = entries.indexOfFirst { it.matches(scope) }
            val stored = entries.getOrNull(index)?.takeIf { it.cursor == cursor } ?: return@synchronized 1
            val failures = stored.failures.coerceAtLeast(0) + 1
            writeLocked(entries.toMutableList().also { it[index] = stored.copy(failures = failures) })
            failures
        }

    /** Stores [cursor] for [scope] with no failures counted (a new cursor, or a pull that succeeded). */
    fun write(scope: RaftSyncScope, cursor: String) {
        if (cursor.isBlank()) return
        synchronized(lock) {
            val others = readLocked().filterNot { it.matches(scope) }
            val next = (others + PersistedSyncCursor(scope.environmentId, scope.accountId, scope.serverId, cursor))
                .takeLast(maxEntries.coerceAtLeast(1))
            writeLocked(next)
        }
    }

    fun clear(scope: RaftSyncScope) {
        synchronized(lock) {
            val entries = readLocked()
            val next = entries.filterNot { it.matches(scope) }
            if (next.size != entries.size) writeLocked(next)
        }
    }

    /** The account lost [serverId]: forget its cursor (every environment, or only [accountId]'s). */
    fun clearServer(serverId: String, accountId: String? = null) {
        val server = serverId.trim()
        synchronized(lock) {
            val entries = readLocked()
            val next = entries.filterNot { it.serverId == server && (accountId == null || it.accountId == accountId.trim()) }
            if (next.size != entries.size) writeLocked(next)
        }
    }

    fun clearAll() {
        synchronized(lock) { store.putString(SYNC_CHANGES_CURSOR_STORAGE_KEY, null) }
    }

    private fun PersistedSyncCursor.matches(scope: RaftSyncScope): Boolean =
        environmentId == scope.environmentId && accountId == scope.accountId && serverId == scope.serverId

    private fun readLocked(): List<PersistedSyncCursor> {
        val raw = store.getString(SYNC_CHANGES_CURSOR_STORAGE_KEY)?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching { syncCursorJson.decodeFromString(PersistedSyncCursorDocument.serializer(), raw).entries }
            .getOrDefault(emptyList())
    }

    private fun writeLocked(entries: List<PersistedSyncCursor>) {
        store.putString(
            SYNC_CHANGES_CURSOR_STORAGE_KEY,
            if (entries.isEmpty()) {
                null
            } else {
                syncCursorJson.encodeToString(PersistedSyncCursorDocument.serializer(), PersistedSyncCursorDocument(entries = entries))
            },
        )
    }
}
