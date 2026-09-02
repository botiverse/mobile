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
import build.raft.app.data.ForwardMessageTargetRequiredAction
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
            executeThreadForward(
                repository = repository,
                liveSourceServerId = sourceServerId,
                sourceChannelId = ChannelId("source"),
                receipt = selected.receiptAt(generation = 7),
                currentDirectoryGeneration = 7,
                messageIds = listOf("message-1", "message-2"),
                mode = ForwardMessageSendMode.Merged,
                note = "Selected note"
            )

        assertEquals(null, outcome.failureCode)
        assertEquals(1, repository.requests.size)
        val request = repository.requests.single()
        assertEquals(sourceServerId, request.sourceServerId)
        assertEquals(sourceServerId, request.destination.serverId)
        assertEquals(ChannelId("channel-selected"), request.targetChannelId)
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
    fun forwardNoteBodySuppressesOnlyTheGeneratedPlaceholder() {
        assertEquals("see this", threadForwardNoteBody(" see this ", 3))
        assertEquals(null, threadForwardNoteBody("Forwarded 3 messages", 3))
        assertEquals(null, threadForwardNoteBody("Forwarded 1 message", 1))
        assertEquals("Forwarded 1 message", threadForwardNoteBody("Forwarded 1 message", 2))
        assertEquals("Forwarded 3 messages", threadForwardNoteBody("Forwarded 3 messages", 1))
        assertEquals(null, threadForwardNoteBody("   ", 2))
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
    fun directMessageForwardSuccessUsesDirectMessageIdentity() {
        val target = target("dm-target", ForwardMessageTargetKind.RecentDirectMessage, ChannelId("dm-target"))
            .copy(channelKind = ConversationKind.DirectMessage, channelType = "dm", memberId = MemberId("member-target"))

        assertEquals("dm-target", target.forwardSuccessDisplayTitle())
        assertEquals(
            ConversationKind.DirectMessage,
            ThreadForwardSuccessBanner(
                target = target,
                destination = ForwardMessageDestination(sourceServerId, ChannelId("dm-target"), ConversationKind.DirectMessage),
                messageId = MessageId("forwarded")
            ).forwardSuccessConversationIdentity()?.kind
        )
    }

    @Test
    fun directMessageBannerFailsClosedWithoutMemberIdentity() {
        val target = target("dm-target", ForwardMessageTargetKind.RecentDirectMessage, ChannelId("dm-target"))
            .copy(channelKind = ConversationKind.DirectMessage, channelType = "dm", memberId = null)
        val banner =
            ThreadForwardSuccessBanner(
                target = target,
                destination = ForwardMessageDestination(sourceServerId, ChannelId("dm-target"), ConversationKind.DirectMessage),
                messageId = MessageId("forwarded")
            )
        assertEquals(null, banner.forwardSuccessConversationIdentity())
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
    fun singleTargetForwardReportsFailureWithoutRetrying() = runBlocking {
        val selected = target("selected", ForwardMessageTargetKind.RecentDirectMessage, ChannelId("channel-selected"))
        val repository =
            RecordingForwardRepository(
                forwardResults =
                    mapOf(
                        ChannelId("channel-selected") to
                            DataResult.Error(ScreenError(code = "selected_failed", retryable = true))
                    )
            )

        val outcome =
            executeThreadForward(
                repository = repository,
                liveSourceServerId = sourceServerId,
                sourceChannelId = ChannelId("source"),
                receipt = selected.receiptAt(generation = 7),
                currentDirectoryGeneration = 7,
                messageIds = listOf("message-1"),
                mode = ForwardMessageSendMode.Merged,
                note = ""
            )

        assertEquals("selected_failed", outcome.failureCode)
        assertEquals(listOf(ChannelId("channel-selected")), repository.requests.map { it.targetChannelId })
    }

    @Test
    fun candidateResolvingToSourceConversationIsRejectedWithoutForwardRequest() = runBlocking {
        val candidate =
            ForwardMessageTarget(
                serverId = sourceServerId,
                kind = ForwardMessageTargetKind.HumanDirectMessageCandidate,
                id = "user-source",
                title = "Source peer",
                memberId = MemberId("user-source")
            )
        val sourceChannelId = ChannelId("dm-source")
        val repository =
            RecordingForwardRepository(
                resolvedChannels = mapOf(candidate.id to sourceChannelId)
            )

        val outcome =
            executeThreadForward(
                repository = repository,
                liveSourceServerId = sourceServerId,
                sourceChannelId = sourceChannelId,
                receipt = candidate.receiptAt(generation = 3),
                currentDirectoryGeneration = 3,
                messageIds = listOf("message-1"),
                mode = ForwardMessageSendMode.Merged,
                note = ""
            )

        assertEquals("forward_source_target_forbidden", outcome.failureCode)
        assertEquals(emptyList(), repository.requests)
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
            executeThreadForward(
                repository = repository,
                liveSourceServerId = sourceServerId,
                sourceChannelId = ChannelId("dm-source"),
                receipt = candidate.receiptAt(generation = 3),
                currentDirectoryGeneration = 3,
                messageIds = listOf("message-1"),
                mode = ForwardMessageSendMode.Merged,
                note = "Review this"
            )

        assertEquals(null, outcome.failureCode)
        assertEquals(listOf(resolvedChannelId), repository.requests.map { it.targetChannelId })
    }

    @Test
    fun crossServerJointTargetUsesResolvedSourceServerProjection() = runBlocking {
        val selected =
            ForwardMessageTarget(
                serverId = ServerId("server-peer"),
                kind = ForwardMessageTargetKind.RecentChannel,
                id = "joint-peer-projection",
                title = "#joint-room",
                channelId = ChannelId("joint-peer-projection"),
                channelKind = ConversationKind.Joint,
                jointChannelId = "joint-root"
            )
        val resolved =
            ForwardMessageDestination(
                serverId = sourceServerId,
                channelId = ChannelId("joint-source-projection"),
                channelKind = ConversationKind.Joint,
                jointChannelId = "joint-root"
            )
        val repository = RecordingForwardRepository(resolvedDestinations = mapOf(selected.id to resolved))

        val outcome =
            executeThreadForward(
                repository = repository,
                liveSourceServerId = sourceServerId,
                sourceChannelId = ChannelId("source-channel"),
                receipt = selected.receiptAt(generation = 1),
                currentDirectoryGeneration = 1,
                messageIds = listOf("message-1"),
                mode = ForwardMessageSendMode.Individual,
                note = ""
            )

        assertEquals(null, outcome.failureCode)
        assertEquals(resolved, repository.requests.single().destination)
    }

    // task #803 drift teeth: every drift class fails typed with ZERO forward
    // API traffic — never forward elsewhere (桑兰 contract, feedback #152).

    @Test
    fun serverSwitchAfterTapFailsClosedWithZeroNetwork() = runBlocking {
        val selected = target("selected", ForwardMessageTargetKind.RecentChannel, ChannelId("channel-selected"))
        val repository = RecordingForwardRepository()

        val outcome =
            executeThreadForward(
                repository = repository,
                liveSourceServerId = ServerId("server-switched"),
                sourceChannelId = ChannelId("source"),
                receipt = selected.receiptAt(generation = 7),
                currentDirectoryGeneration = 7,
                messageIds = listOf("message-1"),
                mode = ForwardMessageSendMode.Merged,
                note = ""
            )

        assertEquals("forward_source_server_changed", outcome.failureCode)
        assertEquals(0, repository.resolveCalls)
        assertEquals(emptyList(), repository.requests)
    }

    @Test
    fun directoryRefreshBetweenTapAndSendFailsClosedWithZeroNetwork() = runBlocking {
        val selected = target("selected", ForwardMessageTargetKind.RecentChannel, ChannelId("channel-selected"))
        val repository = RecordingForwardRepository()

        val outcome =
            executeThreadForward(
                repository = repository,
                liveSourceServerId = sourceServerId,
                sourceChannelId = ChannelId("source"),
                receipt = selected.receiptAt(generation = 7),
                currentDirectoryGeneration = 8,
                messageIds = listOf("message-1"),
                mode = ForwardMessageSendMode.Merged,
                note = ""
            )

        assertEquals("forward_directory_refreshed", outcome.failureCode)
        assertEquals(0, repository.resolveCalls)
        assertEquals(emptyList(), repository.requests)
    }

    @Test
    fun resolvedChannelDriftBlocksForwardAfterResolve() = runBlocking {
        val selected = target("selected", ForwardMessageTargetKind.RecentChannel, ChannelId("channel-displayed"))
        val repository =
            RecordingForwardRepository(
                resolvedDestinations =
                    mapOf(
                        selected.id to
                            ForwardMessageDestination(
                                serverId = sourceServerId,
                                channelId = ChannelId("channel-other"),
                                channelKind = ConversationKind.Channel
                            )
                    )
            )

        val outcome =
            executeThreadForward(
                repository = repository,
                liveSourceServerId = sourceServerId,
                sourceChannelId = ChannelId("source"),
                receipt = selected.receiptAt(generation = 2),
                currentDirectoryGeneration = 2,
                messageIds = listOf("message-1"),
                mode = ForwardMessageSendMode.Merged,
                note = ""
            )

        assertEquals("forward_destination_identity_mismatch", outcome.failureCode)
        assertEquals(1, repository.resolveCalls)
        assertEquals(emptyList(), repository.requests)
    }

    @Test
    fun memberCandidateResolvedOnWrongServerBlocksForward() = runBlocking {
        val candidate =
            ForwardMessageTarget(
                serverId = sourceServerId,
                kind = ForwardMessageTargetKind.HumanDirectMessageCandidate,
                id = "user-b",
                title = "User B",
                memberId = MemberId("user-b")
            )
        val repository =
            RecordingForwardRepository(
                resolvedDestinations =
                    mapOf(
                        candidate.id to
                            ForwardMessageDestination(
                                serverId = ServerId("server-elsewhere"),
                                channelId = ChannelId("dm-elsewhere"),
                                channelKind = ConversationKind.DirectMessage
                            )
                    )
            )

        val outcome =
            executeThreadForward(
                repository = repository,
                liveSourceServerId = sourceServerId,
                sourceChannelId = ChannelId("source"),
                receipt = candidate.receiptAt(generation = 4),
                currentDirectoryGeneration = 4,
                messageIds = listOf("message-1"),
                mode = ForwardMessageSendMode.Merged,
                note = ""
            )

        assertEquals("forward_destination_identity_mismatch", outcome.failureCode)
        assertEquals(emptyList(), repository.requests)
    }

    @Test
    fun tamperedReceiptIdentityFailsBeforeResolve() = runBlocking {
        val selected = target("selected", ForwardMessageTargetKind.RecentChannel, ChannelId("channel-selected"))
        val repository = RecordingForwardRepository()

        val outcome =
            executeThreadForward(
                repository = repository,
                liveSourceServerId = sourceServerId,
                sourceChannelId = ChannelId("source"),
                receipt = selected.receiptAt(generation = 5)
                    .copy(target = target("someone-else", ForwardMessageTargetKind.RecentChannel, ChannelId("channel-x"))),
                currentDirectoryGeneration = 5,
                messageIds = listOf("message-1"),
                mode = ForwardMessageSendMode.Merged,
                note = ""
            )

        assertEquals("forward_destination_identity_mismatch", outcome.failureCode)
        assertEquals(0, repository.resolveCalls)
        assertEquals(emptyList(), repository.requests)
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
            executeThreadForward(
                repository = repository,
                liveSourceServerId = sourceServerId,
                sourceChannelId = ChannelId("source"),
                receipt = tappedB.receiptAt(generation = 6),
                currentDirectoryGeneration = 6,
                messageIds = listOf("message-1"),
                mode = ForwardMessageSendMode.Merged,
                note = ""
            )

        assertEquals(null, outcome.failureCode)
        assertEquals(listOf(ChannelId("channel-b")), repository.requests.map { it.targetChannelId })
    }

    @Test
    fun sameKeyDifferentRoutingFieldsFailBeforeResolve() = runBlocking {
        val base = target("selected", ForwardMessageTargetKind.RecentChannel, ChannelId("channel-a"))
        val receipt = base.receiptAt(generation = 5)
        // Same server/kind/id (same display key) — each routing-field change
        // must fail closed BEFORE resolve (LiBai blocker 2).
        val tampered =
            listOf(
                base.copy(channelId = ChannelId("channel-b")),
                base.copy(channelId = null, memberId = MemberId("member-x")),
                base.copy(jointChannelId = "joint-x"),
                base.copy(channelKind = ConversationKind.Joint),
                base.copy(requiredAction = ForwardMessageTargetRequiredAction.JoinChannel)
            )
        for (variant in tampered) {
            val repository = RecordingForwardRepository()
            val outcome =
                executeThreadForward(
                    repository = repository,
                    liveSourceServerId = sourceServerId,
                    sourceChannelId = ChannelId("source"),
                    receipt = receipt.copy(target = variant),
                    currentDirectoryGeneration = 5,
                    messageIds = listOf("message-1"),
                    mode = ForwardMessageSendMode.Merged,
                    note = ""
                )
            assertEquals("forward_destination_identity_mismatch", outcome.failureCode)
            assertEquals(0, repository.resolveCalls)
            assertEquals(emptyList(), repository.requests)
        }
    }

    @Test
    fun plainChannelResolvedOnDifferentServerBlocksForward() = runBlocking {
        val selected = target("selected", ForwardMessageTargetKind.RecentChannel, ChannelId("channel-a"))
        val repository =
            RecordingForwardRepository(
                resolvedDestinations =
                    mapOf(
                        selected.id to
                            ForwardMessageDestination(
                                serverId = ServerId("server-elsewhere"),
                                channelId = ChannelId("channel-a"),
                                channelKind = ConversationKind.Channel
                            )
                    )
            )
        val outcome =
            executeThreadForward(
                repository = repository,
                liveSourceServerId = sourceServerId,
                sourceChannelId = ChannelId("source"),
                receipt = selected.receiptAt(generation = 2),
                currentDirectoryGeneration = 2,
                messageIds = listOf("message-1"),
                mode = ForwardMessageSendMode.Merged,
                note = ""
            )
        assertEquals("forward_destination_identity_mismatch", outcome.failureCode)
        assertEquals(1, repository.resolveCalls)
        assertEquals(emptyList(), repository.requests)
    }

    @Test
    fun sameServerWrongMemberDmBlocksForward() = runBlocking {
        val candidate =
            ForwardMessageTarget(
                serverId = sourceServerId,
                kind = ForwardMessageTargetKind.HumanDirectMessageCandidate,
                id = "user-a",
                title = "User A",
                memberId = MemberId("user-a")
            )
        val repository =
            RecordingForwardRepository(
                resolvedDestinations =
                    mapOf(
                        candidate.id to
                            ForwardMessageDestination(
                                serverId = sourceServerId,
                                channelId = ChannelId("dm-user-b"),
                                channelKind = ConversationKind.DirectMessage,
                                resolvedMemberId = MemberId("user-b")
                            )
                    )
            )
        val outcome =
            executeThreadForward(
                repository = repository,
                liveSourceServerId = sourceServerId,
                sourceChannelId = ChannelId("source"),
                receipt = candidate.receiptAt(generation = 3),
                currentDirectoryGeneration = 3,
                messageIds = listOf("message-1"),
                mode = ForwardMessageSendMode.Merged,
                note = ""
            )
        assertEquals("forward_destination_identity_mismatch", outcome.failureCode)
        assertEquals(1, repository.resolveCalls)
        assertEquals(emptyList(), repository.requests)
    }

    @Test
    fun memberDmWithoutResolverProvenanceBlocksForward() = runBlocking {
        val candidate =
            ForwardMessageTarget(
                serverId = sourceServerId,
                kind = ForwardMessageTargetKind.AgentDirectMessageCandidate,
                id = "agent-a",
                title = "Agent A",
                memberId = MemberId("agent-a")
            )
        val repository =
            RecordingForwardRepository(
                resolvedDestinations =
                    mapOf(
                        candidate.id to
                            ForwardMessageDestination(
                                serverId = sourceServerId,
                                channelId = ChannelId("dm-agent-a"),
                                channelKind = ConversationKind.DirectMessage
                            )
                    )
            )
        val outcome =
            executeThreadForward(
                repository = repository,
                liveSourceServerId = sourceServerId,
                sourceChannelId = ChannelId("source"),
                receipt = candidate.receiptAt(generation = 3),
                currentDirectoryGeneration = 3,
                messageIds = listOf("message-1"),
                mode = ForwardMessageSendMode.Merged,
                note = ""
            )
        assertEquals("forward_destination_identity_mismatch", outcome.failureCode)
        assertEquals(emptyList(), repository.requests)
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
    private val resolvedDestinations: Map<String, ForwardMessageDestination> = emptyMap(),
    private val forwardResults: Map<ChannelId, DataResult<ForwardMessageSendResult>> = emptyMap()
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
        return forwardResults[request.targetChannelId] ?: DataResult.Content(ForwardMessageSendResult(message = null))
    }

    override suspend fun forwardMessagesBatch(request: ForwardMessageBatchExecuteRequest): DataResult<ForwardMessageBatchResult> {
        batchRequests += request
        return DataResult.Content(ForwardMessageBatchResult(deliveries = emptyList(), failures = emptyList()))
    }
}
