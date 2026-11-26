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

/**
 * A `GET /api/sync/changes` body with [changes] (raw JSON objects) and an optional raw [reset]; with the
 * inbox_row capability (slock #15312) also a raw [inboxReset] and [retryAfterMs] when given.
 */
internal fun syncChangesBody(
    cursor: String,
    hasMore: Boolean = false,
    changes: List<String> = emptyList(),
    reset: String = "null",
    inboxReset: String? = null,
    retryAfterMs: Long? = null,
): String =
    """{"cursor":"$cursor","hasMore":$hasMore,"changes":[${changes.joinToString(",")}],"reset":$reset""" +
        (inboxReset?.let { ""","inboxReset":$it""" } ?: "") +
        (retryAfterMs?.let { ""","retryAfterMs":$it""" } ?: "") + "}"

/** An `inbox_row` upsert (slock #15312 `SyncInboxRowChange`): [row] is the raw `?ids=` row JSON. */
internal fun syncInboxRowUpsert(seq: Long, id: String, row: String, source: String = "message"): String =
    """{"seq":$seq,"entity":"inbox_row","id":"$id","op":"upsert","row":$row,"source":"$source"}"""

/** An `inbox_row` delete; [position] null is JSON null. */
internal fun syncInboxRowDelete(seq: Long, id: String, position: String?, reason: String = "done"): String =
    """{"seq":$seq,"entity":"inbox_row","id":"$id","op":"delete","position":${position?.let { "\"$it\"" } ?: "null"},"reason":"$reason"}"""

/**
 * A channel row as `GET /api/channels/inbox?ids=` serves it (slock channelService `InboxItem`, channel arm),
 * at [seq] with [unread] unread; [maxReadSeq] fills its read frontier.
 */
internal fun syncInboxChannelRow(
    channelId: String,
    seq: String,
    unread: Int = 1,
    hasMention: Boolean = false,
    at: String = "2026-11-17T10:00:00.000Z",
    maxReadSeq: String? = null,
): String {
    val readState =
        maxReadSeq?.let {
            ""","readState":{"kind":"present","readStateVersion":2,"maxReadSeq":"$it","latestActivity":{"messageId":"m-$seq","seq":"$seq"}}"""
        } ?: ""
    return """{"kind":"channel","channelId":"$channelId","channelName":"$channelId","channelType":"channel",""" +
        """"unreadCount":$unread,"hasMention":$hasMention,"lastMessageId":"m-$seq","lastMessagePreview":"hi",""" +
        """"lastMessageAt":"$at","latestActivitySeq":"$seq"$readState}"""
}

internal fun syncUpsert(seq: Long, entity: String, id: String, row: String): String =
    """{"seq":$seq,"entity":"$entity","id":"$id","op":"upsert","row":$row}"""

internal fun syncDelete(seq: Long, entity: String, id: String): String =
    """{"seq":$seq,"entity":"$entity","id":"$id","op":"delete"}"""

internal fun syncStale(seq: Long, conversationId: String, refSeq: Long?): String =
    """{"seq":$seq,"entity":"conversation","id":"$conversationId","op":"stale","refSeq":${refSeq ?: "null"}}"""

/** A `conversation` change carrying its state parts (raw JSON objects, null = left out), as slock bab9dd8c0 sends them. */
internal fun syncStaleWithState(seq: Long, conversationId: String, refSeq: Long? = null, read: String? = null, done: String? = null, follow: String? = null): String {
    val parts = listOfNotNull(read?.let { "\"read\":$it" }, done?.let { "\"done\":$it" }, follow?.let { "\"follow\":$it" })
    return """{"seq":$seq,"entity":"conversation","id":"$conversationId","op":"stale","refSeq":${refSeq ?: "null"}""" +
        parts.joinToString("") { ",$it" } + "}"
}

/*
 * `task` changes as slock eeb984b99 (#14975) sends them (packages/shared/src/syncChanges.ts,
 * packages/server/src/routes/syncChanges.tasks.api.test.ts): an upsert carries the task as
 * GET /api/tasks/channel/:id lists it, a delete names its channel (`SyncTaskDelete`), and a
 * resnapshot's id is a channel id (`SyncTaskResnapshot`).
 */
internal fun syncTaskUpsert(
    seq: Long,
    id: String,
    channelId: String,
    title: String = "Write the sync rows",
    status: String = "in_progress",
    taskNumber: Int = 7,
    revision: Long? = null,
    extraFields: String = "",
): String {
    val rev = revision?.let { ""","revision":$it""" } ?: ""
    val extra = if (extraFields.isNotBlank()) ",$extraFields" else ""
    return syncUpsert(
        seq,
        "task",
        id,
        """{"id":"$id","channelId":"$channelId","taskNumber":$taskNumber,"title":"$title","status":"$status"$rev$extra}""",
    )
}

internal fun syncTaskDelete(seq: Long, id: String, channelId: String): String =
    """{"seq":$seq,"entity":"task","id":"$id","op":"delete","channelId":"$channelId"}"""

internal fun syncTaskResnapshot(seq: Long, channelId: String): String =
    """{"seq":$seq,"entity":"task","id":"$channelId","op":"resnapshot"}"""

internal fun syncScope(seq: Long, kind: String, id: String, op: String, snapshots: List<String>): String =
    """{"seq":$seq,"entity":"scope","id":"$id","op":"$op","scopeKind":"$kind","snapshots":[${snapshots.joinToString(",") { "\"$it\"" }}]}"""

/** `SYNC_CHANNEL_SCOPE_SNAPSHOTS`. */
internal val syncChannelScopeSnapshots = listOf("channels", "channel_members", "inbox", "unread")

/** `SYNC_SNAPSHOT_NAMES`, in the server's order. */
internal val syncAllSnapshotNames =
    listOf(
        "channels", "channel_members", "server_members", "agents", "inbox", "unread",
        "followed_threads", "machines", "sidebar_order", "server_order", "tasks",
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

    override suspend fun reloadInbox(scope: RaftSyncScope, reason: String) {
        calls += "inbox:$reason"
    }
}
