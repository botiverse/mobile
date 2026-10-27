package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Saved tab with nothing saved, for Search store tests that never open Saved. */
internal object EmptySavedRepository : SavedRepository {
    override suspend fun loadInitial(serverId: ServerId, limit: Int): DataResult<SavedSnapshot> =
        DataResult.Empty(EmptyReason.NoInboxItems)

    override suspend fun loadMore(serverId: ServerId, offset: Int, limit: Int): DataResult<SavedSnapshot> =
        DataResult.Empty(EmptyReason.NoInboxItems)

    override suspend fun saveMessage(serverId: ServerId, messageId: MessageId): DataResult<SavedMessageMutationResult> =
        DataResult.Content(SavedMessageMutationResult(messageId))

    override suspend fun unsaveMessage(serverId: ServerId, messageId: MessageId): DataResult<SavedMessageMutationResult> =
        DataResult.Content(SavedMessageMutationResult(messageId))
}

/** A channel search hit keyed by [key] (also its message id). */
internal fun searchHitRow(key: String): InboxItem =
    InboxItem(
        key = key,
        kind = InboxItemKind.Channel,
        channelId = ChannelId("channel-1"),
        channelName = "general",
        latestActivityMessageId = MessageId(key),
        lastMessageId = MessageId(key),
    )

internal fun searchTestServer(id: ServerId): ServerSummary = ServerSummary(id = id, name = "Slock")

/** Saved first page that answers only when the test completes it. */
internal class PendingSavedRepository : SavedRepository {
    private val pending = CompletableDeferred<DataResult<SavedSnapshot>>()

    override suspend fun loadInitial(serverId: ServerId, limit: Int): DataResult<SavedSnapshot> =
        withContext(NonCancellable) { pending.await() }

    override suspend fun loadMore(serverId: ServerId, offset: Int, limit: Int): DataResult<SavedSnapshot> =
        DataResult.Empty(EmptyReason.NoInboxItems)

    override suspend fun saveMessage(serverId: ServerId, messageId: MessageId): DataResult<SavedMessageMutationResult> =
        error("not used")

    override suspend fun unsaveMessage(serverId: ServerId, messageId: MessageId): DataResult<SavedMessageMutationResult> =
        error("not used")

    fun complete(result: DataResult<SavedSnapshot>) {
        pending.complete(result)
    }
}

internal class FailingSavedRepository(
    private val result: DataResult<SavedSnapshot> = DataResult.Error(ScreenError("saved_network")),
) : SavedRepository {
    override suspend fun loadInitial(serverId: ServerId, limit: Int): DataResult<SavedSnapshot> = result

    override suspend fun loadMore(serverId: ServerId, offset: Int, limit: Int): DataResult<SavedSnapshot> = result

    override suspend fun saveMessage(serverId: ServerId, messageId: MessageId): DataResult<SavedMessageMutationResult> =
        DataResult.Error(ScreenError("saved_network"))

    override suspend fun unsaveMessage(serverId: ServerId, messageId: MessageId): DataResult<SavedMessageMutationResult> =
        DataResult.Error(ScreenError("saved_network"))
}

internal class DeferredUnsaveSavedRepository(
    private val items: List<InboxItem>,
) : SavedRepository {
    private val unsaves = mutableMapOf<String, CompletableDeferred<DataResult<SavedMessageMutationResult>>>()

    override suspend fun loadInitial(serverId: ServerId, limit: Int): DataResult<SavedSnapshot> =
        DataResult.Content(SavedSnapshot(server = searchTestServer(serverId), items = items))

    override suspend fun loadMore(serverId: ServerId, offset: Int, limit: Int): DataResult<SavedSnapshot> =
        DataResult.Empty(EmptyReason.NoInboxItems)

    override suspend fun saveMessage(serverId: ServerId, messageId: MessageId): DataResult<SavedMessageMutationResult> =
        DataResult.Content(SavedMessageMutationResult(messageId))

    override suspend fun unsaveMessage(serverId: ServerId, messageId: MessageId): DataResult<SavedMessageMutationResult> {
        val result = unsaves.getOrPut(messageId.value) { CompletableDeferred() }
        return withContext(NonCancellable) { result.await() }
    }

    fun complete(messageId: String, result: DataResult<SavedMessageMutationResult>) {
        unsaves.getOrPut(messageId) { CompletableDeferred() }.complete(result)
    }
}

/** Search that never finds anything, for Saved store tests. */
internal object EmptySearchRepository : SearchRepository {
    override suspend fun search(
        serverId: ServerId,
        query: String,
        offset: Int,
        limit: Int,
        options: SearchQueryOptions,
        cursor: String?,
    ): DataResult<SearchSnapshot> = DataResult.Empty(EmptyReason.NoInboxItems)
}

/** The shape `parseSharedSavedItems` produces: key `saved:<messageId>`. */
internal fun savedListRow(messageId: String): InboxItem =
    InboxItem(
        key = "saved:$messageId",
        kind = InboxItemKind.Channel,
        channelId = ChannelId("channel-1"),
        channelName = "general",
        lastMessageId = MessageId(messageId),
        latestActivityMessageId = MessageId(messageId),
        lastMessagePreview = "Saved $messageId",
    )

/** [RaftOfflineResourceRows] in memory (the SQL rows are covered by the androidUnitTest round trip). */
internal class InMemoryRaftOfflineResourceRows : RaftOfflineResourceRows {
    val rows = linkedMapOf<String, RaftOfflineResourceRow>()

    override suspend fun read(id: String): RaftOfflineResourceRow? = rows[id]

    override suspend fun writeAndEvict(
        id: String,
        value: String,
        updatedAtMs: Long,
        evict: (List<RaftOfflineResourceRowMeta>) -> List<String>,
    ) {
        rows[id] = RaftOfflineResourceRow(value, updatedAtMs)
        evict(rows.map { (rowId, row) -> RaftOfflineResourceRowMeta(rowId, row.updatedAtMs) }).forEach(rows::remove)
    }

    override suspend fun delete(ids: List<String>) {
        ids.forEach(rows::remove)
    }

    override suspend fun deleteAll() {
        rows.clear()
    }

    override suspend fun ids(): List<String> = rows.keys.toList()
}
