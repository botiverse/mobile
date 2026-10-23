package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary

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
