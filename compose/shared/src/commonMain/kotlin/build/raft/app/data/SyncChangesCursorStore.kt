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
 */
class RaftSyncCursorStore(
    private val store: SharedPreferenceStore,
    private val maxEntries: Int = 64,
) {
    private val lock = SynchronizedObject()

    fun read(scope: RaftSyncScope): String? =
        synchronized(lock) { readLocked().firstOrNull { it.matches(scope) }?.cursor }

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
