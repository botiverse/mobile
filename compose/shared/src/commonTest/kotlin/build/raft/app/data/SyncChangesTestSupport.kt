package build.raft.app.data

import build.raft.app.model.ServerId

/*
 * Shared fixtures of the sync-changes tests. Wire shapes follow botiverse/slock 0d030e341
 * (PR #11459): packages/shared/src/syncChanges.ts and packages/server/src/routes/syncChanges.api.test.ts.
 */

internal class SyncTestPreferences : SharedPreferenceStore {
    val values = mutableMapOf<String, String?>()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String?) {
        values[key] = value
    }
}

internal val syncTestScope = RaftSyncScope("https://api.example.test", "user-1", "server-1")

/** A `GET /api/sync/changes` body with [changes] (raw JSON objects) and an optional raw [reset]. */
internal fun syncChangesBody(
    cursor: String,
    hasMore: Boolean = false,
    changes: List<String> = emptyList(),
    reset: String = "null",
): String = """{"cursor":"$cursor","hasMore":$hasMore,"changes":[${changes.joinToString(",")}],"reset":$reset}"""

internal fun syncUpsert(seq: Long, entity: String, id: String, row: String): String =
    """{"seq":$seq,"entity":"$entity","id":"$id","op":"upsert","row":$row}"""

internal fun syncDelete(seq: Long, entity: String, id: String): String =
    """{"seq":$seq,"entity":"$entity","id":"$id","op":"delete"}"""

internal fun syncStale(seq: Long, conversationId: String, refSeq: Long?): String =
    """{"seq":$seq,"entity":"conversation","id":"$conversationId","op":"stale","refSeq":${refSeq ?: "null"}}"""

internal fun syncScope(seq: Long, kind: String, id: String, op: String, snapshots: List<String>): String =
    """{"seq":$seq,"entity":"scope","id":"$id","op":"$op","scopeKind":"$kind","snapshots":[${snapshots.joinToString(",") { "\"$it\"" }}]}"""

/** `SYNC_CHANNEL_SCOPE_SNAPSHOTS`. */
internal val syncChannelScopeSnapshots = listOf("channels", "channel_members", "inbox", "unread")

/** `SYNC_SNAPSHOT_NAMES`, in the server's order. */
internal val syncAllSnapshotNames =
    listOf(
        "channels", "channel_members", "server_members", "agents", "inbox", "unread",
        "followed_threads", "machines", "sidebar_order", "server_order",
    )

/** A scripted API: answers in order, records each request's `since`. */
internal class ScriptedSyncChangesApi(vararg answers: RaftSyncChangesFetch) : RaftSyncChangesApi {
    private val queue = ArrayDeque(answers.toList())
    val sinces = mutableListOf<String?>()

    override suspend fun changes(serverId: ServerId, since: String?, limit: Int): RaftSyncChangesFetch {
        sinces += since
        return queue.removeFirstOrNull() ?: error("no scripted answer for since=$since (asked ${sinces.size} times)")
    }
}

internal fun syncPage(body: String): RaftSyncChangesFetch =
    RaftSyncChangesFetch.Page(parseSyncChangesPage(body) ?: error("fixture body does not parse: $body"))

/** Records what the engine hands the stores, with a call log to check ordering. */
internal class RecordingSyncChangesSink(
    var snapshotsSucceed: Boolean = true,
    private val calls: MutableList<String> = mutableListOf(),
) : RaftSyncChangesSink {
    val log: List<String>
        get() = calls
    val snapshotReloads = mutableListOf<Pair<Set<RaftSyncSnapshot>, String>>()
    val plans = mutableListOf<RaftSyncApplyPlan>()

    override suspend fun reloadSnapshots(scope: RaftSyncScope, snapshots: Set<RaftSyncSnapshot>, reason: String): Boolean {
        calls += "snapshots:$reason"
        snapshotReloads += snapshots to reason
        return snapshotsSucceed
    }

    override suspend fun apply(scope: RaftSyncScope, plan: RaftSyncApplyPlan) {
        calls += "apply"
        plans += plan
    }
}
