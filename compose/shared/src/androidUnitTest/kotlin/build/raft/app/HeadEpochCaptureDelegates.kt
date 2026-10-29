package build.raft.app

import build.raft.app.data.ChannelMessageWindow
import build.raft.app.network.sync.CanonicalMessageV2EnterBaseline
import build.raft.app.network.sync.CanonicalMessageV2ThreadJoinConfirmed
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.SyncScopeId
import build.raft.app.network.sync.ThreadDurableEnterTicket
import build.raft.app.network.sync.ThreadDurableEnteredPage
import build.raft.app.network.sync.ThreadDurablePageDirection
import build.raft.app.network.sync.ThreadDurablePageExtender
import build.raft.app.network.sync.ThreadDurableReplyParent
import build.raft.app.network.sync.ThreadDurableRequestStamp
import build.raft.app.network.sync.ThreadEnteredScopeSnapshotLoader
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray

/**
 * Message storage Phase 3b test seam: the real loader, but [headEpoch] answers [epoch] (a test makes it move with
 * every request) and every prewarm write records the epoch its caller handed over. Everything else is forwarded.
 */
internal class HeadEpochRecordingLoader(
    private val delegate: ThreadEnteredScopeSnapshotLoader,
    private val epoch: () -> Long,
) : ThreadEnteredScopeSnapshotLoader {
    val prewarmRequestEpochs: MutableList<Long?> = java.util.Collections.synchronizedList(mutableListOf())

    override fun headEpoch(partition: DurablePartitionKey): Long = epoch()

    override suspend fun beginEnter(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        replyParent: ThreadDurableReplyParent?,
        registerBaseline: Boolean,
        stamp: ThreadDurableRequestStamp?,
    ): ThreadDurableEnterTicket? = delegate.beginEnter(partition, channelId, replyParent, registerBaseline, stamp)

    override suspend fun requestStamp(partition: DurablePartitionKey): ThreadDurableRequestStamp? =
        delegate.requestStamp(partition)

    override suspend fun persistEnteredPage(
        ticket: ThreadDurableEnterTicket,
        page: ThreadDurableEnteredPage,
        stillCurrent: () -> Boolean,
    ) = delegate.persistEnteredPage(ticket, page, stillCurrent)

    override suspend fun abandonEnter(ticket: ThreadDurableEnterTicket) = delegate.abandonEnter(ticket)

    override fun replyThreadCommitGeneration(partition: DurablePartitionKey, threadChannelId: SyncScopeId): Long? =
        delegate.replyThreadCommitGeneration(partition, threadChannelId)

    override suspend fun currentLocalRev(partition: DurablePartitionKey): Long? = delegate.currentLocalRev(partition)

    override suspend fun persistReplyThreadPrewarmPage(
        partition: DurablePartitionKey,
        threadChannelId: SyncScopeId,
        replyParent: ThreadDurableReplyParent,
        rawMessages: JsonArray,
        requestedLimit: Int,
        expectedCommitGeneration: Long,
        messageWindow: ChannelMessageWindow?,
        historyLimited: Boolean,
        requestHeadEpoch: Long?,
        revAtRequest: Long?,
        stillCurrent: () -> Boolean,
    ) {
        prewarmRequestEpochs += requestHeadEpoch
        delegate.persistReplyThreadPrewarmPage(
            partition, threadChannelId, replyParent, rawMessages, requestedLimit, expectedCommitGeneration,
            messageWindow, historyLimited, requestHeadEpoch, revAtRequest, stillCurrent,
        )
    }
}

/** The extender counterpart of [HeadEpochRecordingLoader]: records the epoch handed to every context-page write. */
internal class HeadEpochRecordingExtender(
    private val delegate: ThreadDurablePageExtender,
    private val epoch: () -> Long,
) : ThreadDurablePageExtender {
    val contextRequestEpochs: MutableList<Long?> = java.util.Collections.synchronizedList(mutableListOf())

    override fun headEpoch(partition: DurablePartitionKey): Long = epoch()

    override suspend fun extendWithAdjacentPage(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        direction: ThreadDurablePageDirection,
        anchorSeq: Long,
        rawMessages: JsonArray,
        requestedLimit: Int,
        replyParent: ThreadDurableReplyParent?,
        requestHeadEpoch: Long?,
        stillCurrent: () -> Boolean,
    ) = delegate.extendWithAdjacentPage(
        partition, channelId, direction, anchorSeq, rawMessages, requestedLimit, replyParent, requestHeadEpoch, stillCurrent,
    )

    override suspend fun applyLatestPage(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        rawMessages: JsonArray,
        requestedLimit: Int,
        replyParent: ThreadDurableReplyParent?,
        messageWindow: ChannelMessageWindow?,
        historyLimited: Boolean,
        requestHeadEpoch: Long?,
        revAtRequest: Long?,
        stillCurrent: () -> Boolean,
    ) = delegate.applyLatestPage(
        partition, channelId, rawMessages, requestedLimit, replyParent, messageWindow, historyLimited, requestHeadEpoch,
        revAtRequest, stillCurrent,
    )

    override suspend fun currentLocalRev(partition: DurablePartitionKey): Long? = delegate.currentLocalRev(partition)

    override suspend fun beginLatestPageBaseline(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        replyParent: ThreadDurableReplyParent?,
    ): CanonicalMessageV2EnterBaseline? = delegate.beginLatestPageBaseline(partition, channelId, replyParent)

    override fun threadJoinConfirmations(): Flow<CanonicalMessageV2ThreadJoinConfirmed> = delegate.threadJoinConfirmations()

    override suspend fun abandonLatestPageBaseline(baseline: CanonicalMessageV2EnterBaseline) =
        delegate.abandonLatestPageBaseline(baseline)

    override suspend fun applyLatestPageAfterBaseline(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        rawMessages: JsonArray,
        requestedLimit: Int,
        replyParent: ThreadDurableReplyParent?,
        baseline: CanonicalMessageV2EnterBaseline?,
        messageWindow: ChannelMessageWindow?,
        historyLimited: Boolean,
        requestHeadEpoch: Long?,
        revAtRequest: Long?,
        stillCurrent: () -> Boolean,
    ) = delegate.applyLatestPageAfterBaseline(
        partition, channelId, rawMessages, requestedLimit, replyParent, baseline, messageWindow, historyLimited,
        requestHeadEpoch, revAtRequest, stillCurrent,
    )

    override suspend fun persistContextPage(
        partition: DurablePartitionKey,
        channelId: SyncScopeId,
        anchorMessageId: String,
        rawMessages: JsonArray,
        hasOlder: Boolean,
        hasNewer: Boolean,
        replyParent: ThreadDurableReplyParent?,
        requestHeadEpoch: Long?,
        stillCurrent: () -> Boolean,
    ) {
        contextRequestEpochs += requestHeadEpoch
        delegate.persistContextPage(
            partition, channelId, anchorMessageId, rawMessages, hasOlder, hasNewer, replyParent, requestHeadEpoch, stillCurrent,
        )
    }
}
