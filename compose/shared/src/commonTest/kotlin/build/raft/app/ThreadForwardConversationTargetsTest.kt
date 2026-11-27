package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.ForwardMessageBatchExecuteRequest
import build.raft.app.data.ForwardMessageBatchResult
import build.raft.app.data.ForwardMessageDestination
import build.raft.app.data.ForwardMessageExecuteRequest
import build.raft.app.data.ForwardMessageSendMode
import build.raft.app.data.ForwardMessageSendResult
import build.raft.app.data.ForwardMessageTarget
import build.raft.app.data.ForwardMessageTargetKind
import build.raft.app.data.ForwardMessageTargetRepository
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageConversationContext
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.thread.RaftForwardPickerSessionKey
import build.raft.app.thread.RaftForwardTargetPickerUiState
import build.raft.app.thread.RaftForwardTargetVisualKind
import build.raft.app.thread.ThreadForwardDestinationReceipt
import build.raft.app.thread.ThreadForwardSuccessBanner
import build.raft.app.thread.executeThreadForwardBatch
import build.raft.app.thread.forwardPickerTopEyebrowVisible
import build.raft.app.thread.forwardSuccessConversationIdentity
import build.raft.app.thread.forwardSuccessDisplayTitle
import build.raft.app.thread.forwardSuccessViewInChatAvailable
import build.raft.app.thread.toRaftForwardTargetPickerItem
import build.raft.app.thread.toThreadForwardDestinationReceipt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** slock #12620 / #12621 @ b088f8467e: the message Forward picker's "Threads & tasks" group. */
class ThreadForwardConversationTargetsTest {
    private val serverId = ServerId("server-1")
    private val sourceThread = ChannelId("thread-source")

    @Test
    fun emptyQueryShowsTheGroupAboveRecentAndHidesTheBarEyebrow() {
        val items = listOf(conversation("thread-a", current = true), conversation("task-b", task = true))
            .map { it.toRaftForwardTargetPickerItem() }
        val state =
            RaftForwardTargetPickerUiState(
                sessionKey = RaftForwardPickerSessionKey("thread-1", serverId.value, 1),
                conversationItems = items
            )

        assertEquals(items, state.visibleConversationItems)
        assertFalse(state.forwardPickerTopEyebrowVisible(multiSelect = false))
        assertEquals(listOf(RaftForwardTargetVisualKind.Thread, RaftForwardTargetVisualKind.Task), items.map { it.visualKind })
        assertEquals(listOf(true, false), items.map { it.current })

        // Search mode: no group, the bar names the results.
        val searching = state.copy(query = "zep")
        assertEquals(emptyList(), searching.visibleConversationItems)
        assertTrue(searching.forwardPickerTopEyebrowVisible(multiSelect = false))
    }

    @Test
    fun theCurrentRowForwardsIntoTheSourceConversation() = runBlocking {
        val repository = CountingConversationRepository()
        val current = conversation(sourceThread.value, current = true)

        val outcome =
            executeThreadForwardBatch(
                repository = repository,
                liveSourceServerId = serverId,
                sourceChannelId = sourceThread,
                receipts = listOf(current.receiptAt(2)),
                currentDirectoryGeneration = 2,
                messageIds = listOf("message-1"),
                mode = ForwardMessageSendMode.Merged,
                note = "",
                requestId = "11111111-2222-4333-8444-555555555555"
            )

        assertNull(outcome.failureCode)
        assertEquals(listOf(sourceThread), repository.batchRequests.single().destinations.map { it.channelId })
        assertEquals(ConversationKind.Thread, repository.batchRequests.single().destinations.single().channelKind)
    }

    @Test
    fun anyOtherTargetEqualToTheSourceStaysRefused() = runBlocking {
        val repository = CountingConversationRepository()
        val notCurrent = conversation(sourceThread.value)
        val channelRow =
            ForwardMessageTarget(serverId, ForwardMessageTargetKind.RecentChannel, sourceThread.value, "#src", channelId = sourceThread)
                .copy(current = true)
        // Tampered after the tap: `current` flipped on a frozen non-current receipt.
        val tampered = notCurrent.receiptAt(2).let { it.copy(target = it.target.copy(current = true)) }

        for (receipt in listOf(notCurrent.receiptAt(2), channelRow.receiptAt(2))) {
            val outcome =
                executeThreadForwardBatch(
                    repository = repository,
                    liveSourceServerId = serverId,
                    sourceChannelId = sourceThread,
                    receipts = listOf(receipt),
                    currentDirectoryGeneration = 2,
                    messageIds = listOf("message-1"),
                    mode = ForwardMessageSendMode.Merged,
                    note = "",
                    requestId = "11111111-2222-4333-8444-555555555555"
                )
            assertEquals("forward_source_target_forbidden", outcome.failureCode)
        }
        val tamperedOutcome =
            executeThreadForwardBatch(
                repository = repository,
                liveSourceServerId = serverId,
                sourceChannelId = sourceThread,
                receipts = listOf(tampered),
                currentDirectoryGeneration = 2,
                messageIds = listOf("message-1"),
                mode = ForwardMessageSendMode.Merged,
                note = "",
                requestId = "11111111-2222-4333-8444-555555555555"
            )
        assertEquals("forward_destination_identity_mismatch", tamperedOutcome.failureCode)
        assertEquals(emptyList(), repository.batchRequests)
        assertEquals(emptyList(), repository.requests)
    }

    @Test
    fun viewInChatOpensTheThreadOnTheParentMessage() {
        val target = conversation("thread-5")
        val banner =
            ThreadForwardSuccessBanner(
                target = target,
                destination = ForwardMessageDestination(serverId, ChannelId("thread-5"), ConversationKind.Thread),
                messageId = MessageId("posted-1"),
                conversationContext =
                    MessageConversationContext(
                        channelKind = ConversationKind.Thread,
                        parentMessageId = MessageId("parent-5"),
                        parentChannelId = ChannelId("room-1"),
                        parentChannelKind = ConversationKind.Channel
                    )
            )

        val identity = assertNotNull(banner.forwardSuccessConversationIdentity())
        assertEquals(ConversationKind.Thread, identity.kind)
        assertEquals(ChannelId("room-1"), identity.channelId)
        assertEquals(MessageId("parent-5"), identity.thread?.parentMessageId)
        assertEquals(ChannelId("thread-5"), identity.thread?.streamChannelId)
        assertEquals(RaftThreadSurfaceKind.Channel, identity.thread?.parentSurfaceKind)
        assertTrue(banner.forwardSuccessViewInChatAvailable())
        assertEquals("#general › thread-5", target.forwardSuccessDisplayTitle())
    }

    private fun conversation(id: String, current: Boolean = false, task: Boolean = false): ForwardMessageTarget =
        ForwardMessageTarget(
            serverId = serverId,
            kind = if (task) ForwardMessageTargetKind.ConversationTask else ForwardMessageTargetKind.ConversationThread,
            id = id,
            title = if (task) "#general:#7 $id" else "#general › $id",
            channelId = ChannelId(id),
            channelKind = if (task) ConversationKind.Task else ConversationKind.Thread,
            channelType = if (task) "task" else "thread",
            joined = true,
            canForwardNow = true,
            parentChannelId = ChannelId("room-1"),
            parentChannelName = "general",
            parentChannelType = "channel",
            current = current
        )

    private fun ForwardMessageTarget.receiptAt(generation: Int): ThreadForwardDestinationReceipt =
        toThreadForwardDestinationReceipt(
            operationId = "test-op",
            sourceServerId = this@ThreadForwardConversationTargetsTest.serverId,
            directoryGeneration = generation
        )
}

private class CountingConversationRepository(
    private val conversations: List<ForwardMessageTarget> = emptyList()
) : ForwardMessageTargetRepository {
    var conversationCalls = 0
    val currentChannelIds = mutableListOf<ChannelId?>()
    val requests = mutableListOf<ForwardMessageExecuteRequest>()
    val batchRequests = mutableListOf<ForwardMessageBatchExecuteRequest>()

    override suspend fun recentTargets(serverId: ServerId, limit: Int): DataResult<List<ForwardMessageTarget>> =
        DataResult.Empty(EmptyReason.NoMessages)

    override suspend fun searchTargets(serverId: ServerId, query: String, limit: Int): DataResult<List<ForwardMessageTarget>> =
        DataResult.Empty(EmptyReason.NoMessages)

    override suspend fun conversationTargets(serverId: ServerId, currentChannelId: ChannelId?): List<ForwardMessageTarget> {
        conversationCalls += 1
        currentChannelIds += currentChannelId
        return conversations
    }

    override suspend fun resolveTargetChannel(
        sourceServerId: ServerId,
        target: ForwardMessageTarget,
        allowJoin: Boolean
    ): DataResult<ForwardMessageDestination> =
        DataResult.Content(ForwardMessageDestination(target.serverId, target.channelId!!, target.channelKind))

    override suspend fun forwardMessages(request: ForwardMessageExecuteRequest): DataResult<ForwardMessageSendResult> {
        requests += request
        return DataResult.Content(ForwardMessageSendResult(message = null))
    }

    override suspend fun forwardMessagesBatch(request: ForwardMessageBatchExecuteRequest): DataResult<ForwardMessageBatchResult> {
        batchRequests += request
        return DataResult.Content(ForwardMessageBatchResult(deliveries = emptyList(), failures = emptyList()))
    }
}
