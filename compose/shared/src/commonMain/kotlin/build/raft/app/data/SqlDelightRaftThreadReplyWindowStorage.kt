package build.raft.app.data

import build.raft.app.network.sync.RaftThreadReplyWindowStorage

/**
 * [RaftThreadReplyWindowStorage] on the `localSnapshotEntity` table (scope
 * [RaftThreadReplyWindowSnapshotScope], one row per replies page). Driver calls block, so they run
 * off the store dispatcher like every other [RaftSqlDatabase] access.
 */
internal class SqlDelightRaftThreadReplyWindowStorage(
    private val database: RaftSqlDatabase,
    private val now: () -> Long,
) : RaftThreadReplyWindowStorage {
    override suspend fun read(pageKey: String): String? =
        RaftStoreThreading.runOffStoreDispatcher {
            database.queries.selectSnapshot(RaftThreadReplyWindowSnapshotScope, pageKey).executeAsOneOrNull()
        }

    override suspend fun write(pageKey: String, value: String) {
        RaftStoreThreading.runOffStoreDispatcher {
            database.queries.upsertSnapshot(RaftThreadReplyWindowSnapshotScope, pageKey, value, now())
        }
    }

    override suspend fun clearAll() {
        RaftStoreThreading.runOffStoreDispatcher {
            database.queries.transaction {
                database.queries.selectSnapshotIds(RaftThreadReplyWindowSnapshotScope).executeAsList().forEach { id ->
                    database.queries.deleteSnapshot(RaftThreadReplyWindowSnapshotScope, id)
                }
            }
        }
    }

    override suspend fun pageKeys(): List<String> =
        RaftStoreThreading.runOffStoreDispatcher {
            database.queries.selectSnapshotIds(RaftThreadReplyWindowSnapshotScope).executeAsList()
        }

    override suspend fun delete(pageKeys: List<String>) {
        if (pageKeys.isEmpty()) return
        RaftStoreThreading.runOffStoreDispatcher {
            database.queries.transaction {
                pageKeys.forEach { id -> database.queries.deleteSnapshot(RaftThreadReplyWindowSnapshotScope, id) }
            }
        }
    }
}

/** Persisted scope name: keep the spelling stable across releases. */
internal const val RaftThreadReplyWindowSnapshotScope = "raft_thread_reply_windows_v1"
