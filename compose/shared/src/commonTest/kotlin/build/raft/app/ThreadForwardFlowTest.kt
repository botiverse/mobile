package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.ForwardMessageBatchDelivery
import build.raft.app.data.ForwardMessageBatchExecuteRequest
import build.raft.app.data.ForwardMessageBatchResult
import build.raft.app.data.ForwardMessageDestination
import build.raft.app.data.ForwardMessageExecuteRequest
import build.raft.app.data.ForwardMessageSendMode
import build.raft.app.data.ForwardMessageSendResult
import build.raft.app.data.ForwardMessageTarget
import build.raft.app.data.ForwardMessageTargetKind
import build.raft.app.data.ForwardMessageTargetRepository
import build.raft.app.data.ScreenError
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageType
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import build.raft.app.thread.SharedThreadMessageStore
import build.raft.app.thread.ThreadForwardDestinationReceipt
import build.raft.app.thread.ThreadForwardSuccessBanner
import build.raft.app.thread.applyForwardMessageDeliveries
import build.raft.app.thread.executeThreadForwardBatch
import build.raft.app.thread.forwardRequestIdAfterSelectionChange
import build.raft.app.thread.forwardSuccessConversationIdentity
import build.raft.app.thread.forwardSuccessDisplayTitle
import build.raft.app.thread.threadForwardPickerTargets
import build.raft.app.thread.toThreadForwardDestinationReceipt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class ThreadForwardFlowTest {
    private val sourceServerId = ServerId("server-source")

    @Test
    fun pickerExcludesSourceConversationAndKeepsOtherDestinationKinds() {
        val source = ChannelId("channel-source")
        val targets =
            listOf(
                target("source", ForwardMessageTargetKind.RecentChannel, source),
                target("channel", ForwardMessageTargetKind.RecentChannel, ChannelId("channel-other")),
                target("dm", ForwardMessageTargetKind.RecentDirectMessage, ChannelId("dm-other")),
                ForwardMessageTarget(
                    serverId = sourceServerId,
                    kind = ForwardMessageTargetKind.AgentDirectMessageCandidate,
                    id = "agent-1",
                    title = "Agent One",
                    memberId = MemberId("agent-1")
                )
            )

        assertEquals(
            listOf("channel", "dm", "agent-1"),
            targets.threadForwardPickerTargets(sourceServerId, source).map { it.id }
        )
    }

    @Test
    fun singleTargetForwardPreservesMergedSelectionAndNote() = runBlocking {
        val selected = target("selected", ForwardMessageTargetKind.RecentChannel, ChannelId("channel-selected"))
        val repository = RecordingForwardRepository()

        val outcome =
            executeThreadForwardBatch(
                repository = repository,
                liveSourceServerId = sourceServerId,
                sourceChannelId = ChannelId("source"),
                receipts = listOf(selected.receiptAt(generation = 7)),
                currentDirectoryGeneration = 7,
                messageIds = listOf("message-1", "message-2"),
                mode = ForwardMessageSendMode.Merged,
                note = "Selected note",
                requestId = "11111111-2222-4333-8444-555555555555"
            )

        assertEquals(null, outcome.failureCode)
        assertEquals(1, repository.batchRequests.size)
        val request = repository.batchRequests.single()
        assertEquals(sourceServerId, request.sourceServerId)
        assertEquals(sourceServerId, request.destinations.single().serverId)
        assertEquals(ChannelId("channel-selected"), request.destinations.single().channelId)
        assertEquals(listOf("message-1", "message-2"), request.messageIds)
        assertEquals(ForwardMessageSendMode.Merged, request.mode)
        assertEquals("Selected note", request.note)
    }

    @Test
    fun multiTargetForwardResolvesAllReceiptsAndSendsOneBatch() = runBlocking {
        val first = target("first", ForwardMessageTargetKind.RecentChannel, ChannelId("channel-first"))
        val second = target("second", ForwardMessageTargetKind.RecentDirectMessage, ChannelId("channel-second"))
        val repository = RecordingForwardRepository()

        val outcome = executeThreadForwardBatch(
            repository = repository,
            liveSourceServerId = sourceServerId,
            sourceChannelId = ChannelId("source"),
            receipts = listOf(first.receiptAt(4), second.receiptAt(4)),
            currentDirectoryGeneration = 4,
            messageIds = listOf("message-1", "message-2"),
            mode = ForwardMessageSendMode.Merged,
            note = "Batch note",
            requestId = "11111111-2222-4333-8444-555555555555"
        )

        assertEquals(null, outcome.failureCode)
        assertEquals(2, repository.resolveCalls)
        assertEquals(1, repository.batchRequests.size)
        val request = repository.batchRequests.single()
        assertEquals(listOf(ChannelId("channel-first"), ChannelId("channel-second")), request.destinations.map { it.channelId })
        assertEquals(listOf("message-1", "message-2"), request.messageIds)
        assertEquals("Batch note", request.note)
    }

    @Test
    fun targetSetChangeClearsForwardRequestId() {
        assertEquals("request-r", forwardRequestIdAfterSelectionChange(setOf("a"), setOf("a"), "request-r"))
        assertEquals(null, forwardRequestIdAfterSelectionChange(setOf("a"), setOf("a", "b"), "request-r"))
        assertEquals(null, forwardRequestIdAfterSelectionChange(setOf("a", "b"), setOf("a"), "request-r"))
    }

    @Test
    fun singleForwardSuccessUsesTargetLabelAndCanonicalConversationIdentity() {
        val target = target("wg-forward-message", ForwardMessageTargetKind.RecentChannel, ChannelId("channel-target"))
            .copy(title = "#wg-forward-message", channelType = "public")

        assertEquals("#wg-forward-message", target.forwardSuccessDisplayTitle())
        val identity =
            ThreadForwardSuccessBanner(
                target = target,
                destination = ForwardMessageDestination(sourceServerId, ChannelId("channel-target"), ConversationKind.Channel),
                messageId = MessageId("forwarded")
            ).forwardSuccessConversationIdentity()
        assertEquals(ConversationKind.Channel, identity?.kind)
        assertEquals(ChannelId("channel-target"), identity?.channelId)
        assertEquals("public", identity?.channelType)
    }

    @Test
    fun returnedDeliveriesPopulateScopedMessageStoreAndRefreshHome() {
        val messageStore = SharedThreadMessageStore()
        var refreshCount = 0
        val destination = ForwardMessageDestination(
            serverId = sourceServerId,
            channelId = ChannelId("channel-target"),
            channelKind = ConversationKind.Channel
        )
        val message = Message(
            id = MessageId("forwarded-message"),
            threadId = ThreadId("channel-target"),
            channelId = destination.channelId,
            author = MemberProfile(
                id = MemberId("user-1"),
                displayName = "User",
                handle = "user-1",
                kind = MemberKind.Human,
                presence = Presence.Offline
            ),
            timestampLabel = "2026-09-02T00:00:00.000Z",
            body = "forwarded note",
            messageType = MessageType.Chat
        )

        applyForwardMessageDeliveries(
            result = ForwardMessageBatchResult(
                deliveries = listOf(ForwardMessageBatchDelivery(destination, message)),
                failures = emptyList()
            ),
            messageStore = messageStore,
            onHomeRefresh = { refreshCount += 1 }
        )

        assertEquals(message, messageStore.get(sourceServerId, destination.channelId, message.id))
        assertEquals(1, refreshCount)
    }

    @Test
    fun multiTargetForwardFailsClosedBeforeBatchWhenAnyReceiptDrifts() = runBlocking {
        val first = target("first", ForwardMessageTargetKind.RecentChannel, ChannelId("channel-first"))
        val second = target("second", ForwardMessageTargetKind.RecentChannel, ChannelId("channel-second"))
        val repository = RecordingForwardRepository()

        val outcome = executeThreadForwardBatch(
            repository = repository,
            liveSourceServerId = sourceServerId,
            sourceChannelId = ChannelId("source"),
            receipts = listOf(first.receiptAt(4), second.receiptAt(3)),
            currentDirectoryGeneration = 4,
            messageIds = listOf("message-1"),
            mode = ForwardMessageSendMode.Merged,
            note = "",
            requestId = "11111111-2222-4333-8444-555555555555"
        )

        assertEquals("forward_directory_refreshed", outcome.failureCode)
        assertEquals(0, repository.resolveCalls)
        assertEquals(emptyList(), repository.batchRequests)
    }

    @Test
    fun candidateResolvingToDifferentConversationForwardsExactlyOnce() = runBlocking {
        val candidate =
            ForwardMessageTarget(
                serverId = sourceServerId,
                kind = ForwardMessageTargetKind.AgentDirectMessageCandidate,
                id = "agent-other",
                title = "Other agent",
                memberId = MemberId("agent-other")
            )
        val resolvedChannelId = ChannelId("dm-other")
        val repository =
            RecordingForwardRepository(
                resolvedChannels = mapOf(candidate.id to resolvedChannelId)
            )

        val outcome =
            executeThreadForwardBatch(
                repository = repository,
                liveSourceServerId = sourceServerId,
                sourceChannelId = ChannelId("dm-source"),
                receipts = listOf(candidate.receiptAt(generation = 3)),
                currentDirectoryGeneration = 3,
                messageIds = listOf("message-1"),
                mode = ForwardMessageSendMode.Merged,
                note = "Review this",
                requestId = "11111111-2222-4333-8444-555555555555"
            )

        assertEquals(null, outcome.failureCode)
        assertEquals(listOf(resolvedChannelId), repository.batchRequests.single().destinations.map { it.channelId })
    }

    // task #803 drift teeth: every drift class fails typed with ZERO forward
    // API traffic — never forward elsewhere (桑兰 contract, feedback #152).

    @Test
    fun serverSwitchAfterTapFailsClosedWithZeroNetwork() = runBlocking {
        val selected = target("selected", ForwardMessageTargetKind.RecentChannel, ChannelId("channel-selected"))
        val repository = RecordingForwardRepository()

        val outcome =
            executeThreadForwardBatch(
                repository = repository,
                liveSourceServerId = ServerId("server-switched"),
                sourceChannelId = ChannelId("source"),
                receipts = listOf(selected.receiptAt(generation = 7)),
                currentDirectoryGeneration = 7,
                messageIds = listOf("message-1"),
                mode = ForwardMessageSendMode.Merged,
                note = "",
                requestId = "11111111-2222-4333-8444-555555555555"
            )

        assertEquals("forward_source_server_changed", outcome.failureCode)
        assertEquals(0, repository.resolveCalls)
        assertEquals(emptyList(), repository.batchRequests)
    }

    @Test
    fun duplicateDisplayNamesForwardToTheFrozenIdentityNotTheName() = runBlocking {
        // Two targets share the SAME display title; the receipt binds by id.
        val tappedB = ForwardMessageTarget(
            serverId = sourceServerId,
            kind = ForwardMessageTargetKind.RecentChannel,
            id = "channel-b",
            title = "general",
            channelId = ChannelId("channel-b")
        )
        val repository = RecordingForwardRepository()

        val outcome =
            executeThreadForwardBatch(
                repository = repository,
                liveSourceServerId = sourceServerId,
                sourceChannelId = ChannelId("source"),
                receipts = listOf(tappedB.receiptAt(generation = 6)),
                currentDirectoryGeneration = 6,
                messageIds = listOf("message-1"),
                mode = ForwardMessageSendMode.Merged,
                note = "",
                requestId = "11111111-2222-4333-8444-555555555555"
            )

        assertEquals(null, outcome.failureCode)
        assertEquals(listOf(ChannelId("channel-b")), repository.batchRequests.single().destinations.map { it.channelId })
    }

    private fun ForwardMessageTarget.receiptAt(generation: Int): ThreadForwardDestinationReceipt =
        toThreadForwardDestinationReceipt(
            operationId = "test-op",
            sourceServerId = this@ThreadForwardFlowTest.sourceServerId,
            directoryGeneration = generation
        )

    private fun target(
        id: String,
        kind: ForwardMessageTargetKind,
        channelId: ChannelId
    ): ForwardMessageTarget =
        ForwardMessageTarget(
            serverId = sourceServerId,
            kind = kind,
            id = id,
            title = id,
            channelId = channelId,
            canForwardNow = true
        )
}

private class RecordingForwardRepository(
    private val resolvedChannels: Map<String, ChannelId> = emptyMap(),
    private val resolvedDestinations: Map<String, ForwardMessageDestination> = emptyMap()
) : ForwardMessageTargetRepository {
    val requests = mutableListOf<ForwardMessageExecuteRequest>()
    val batchRequests = mutableListOf<ForwardMessageBatchExecuteRequest>()
    var resolveCalls = 0

    override suspend fun recentTargets(serverId: ServerId, limit: Int): DataResult<List<ForwardMessageTarget>> =
        DataResult.Empty(EmptyReason.NoMessages)

    override suspend fun searchTargets(
        serverId: ServerId,
        query: String,
        limit: Int
    ): DataResult<List<ForwardMessageTarget>> = DataResult.Empty(EmptyReason.NoMessages)

    override suspend fun resolveTargetChannel(
        sourceServerId: ServerId,
        target: ForwardMessageTarget,
        allowJoin: Boolean
    ): DataResult<ForwardMessageDestination> {
        resolveCalls += 1
        return resolvedDestinations[target.id]?.let { DataResult.Content(it) }
            ?: resolvedChannels[target.id]?.let {
                DataResult.Content(
                    ForwardMessageDestination(
                        serverId = target.serverId,
                        channelId = it,
                        channelKind = target.channelKind,
                        jointChannelId = target.jointChannelId,
                        // mirrors production: member resolution attests the
                        // member it was keyed by (task #803 provenance)
                        resolvedMemberId = target.memberId
                    )
                )
            }
            ?: target.channelId?.let {
                DataResult.Content(
                    ForwardMessageDestination(
                        serverId = target.serverId,
                        channelId = it,
                        channelKind = target.channelKind,
                        jointChannelId = target.jointChannelId
                    )
                )
            }
            ?: DataResult.Error(ScreenError(code = "missing_channel", retryable = false))
    }

    override suspend fun forwardMessages(request: ForwardMessageExecuteRequest): DataResult<ForwardMessageSendResult> {
        requests += request
        return DataResult.Content(ForwardMessageSendResult(message = null))
    }

    override suspend fun forwardMessagesBatch(request: ForwardMessageBatchExecuteRequest): DataResult<ForwardMessageBatchResult> {
        batchRequests += request
        return DataResult.Content(ForwardMessageBatchResult(deliveries = emptyList(), failures = emptyList()))
    }
}
