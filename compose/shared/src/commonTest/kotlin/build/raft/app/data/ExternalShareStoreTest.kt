package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.runPortableCoroutineTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalShareStoreTest {
    @Test
    fun freshEnvelopeIsPersistedBeforeNativeClaimAndRestoresPicker() = runPortableCoroutineTest {
        val envelope = envelope()
        val native = FakeEnvelopeRepository(envelope)
        val drafts = FakeDraftRepository()
        val store = store(native, drafts, FakeTargetRepository(listOf(target("one"))))

        store.dispatch(load())

        val state = store.awaitState<ExternalShareState.SelectingTargets> { !it.loading && it.targets.isNotEmpty() }
        assertEquals("account", state.accountId)
        assertEquals(listOf("one"), state.targets.map { it.id })
        assertEquals(listOf("save:SelectingTargets:false", "save:SelectingTargets:true"), drafts.events)
        assertEquals(listOf("consume"), native.events)
        assertTrue(drafts.draft?.envelopeConsumed == true)
    }

    @Test
    fun selectingAnotherTargetReplacesPreviousAndContinueOnlyCreatesDurableHandoff() = runPortableCoroutineTest {
        val first = target("one")
        val second = target("two")
        val native = FakeEnvelopeRepository(envelope())
        val drafts = FakeDraftRepository()
        val targets = FakeTargetRepository(listOf(first, second))
        val store = store(native, drafts, targets)
        store.dispatch(load())
        store.awaitState<ExternalShareState.SelectingTargets> { !it.loading && it.targets.size == 2 }

        select(store, first)
        select(store, second)
        assertEquals(listOf(second), assertIs<ExternalShareState.SelectingTargets>(store.state.value).selectedTargets)

        store.dispatch(ExternalShareIntent.Continue)

        val ready = store.awaitState<ExternalShareState.ReadyForComposer>()
        assertEquals(second.id, ready.handoff.target.id)
        assertEquals(ChannelId("resolved-two"), ready.handoff.target.channelId)
        assertEquals(1, targets.resolveCalls)
        assertEquals(ExternalShareDraftStage.ComposerHandoff, drafts.draft?.stage)
        assertTrue(drafts.draft?.deliveries.isNullOrEmpty())
        assertFalse(native.deleted)
    }

    @Test
    fun legacySingleTargetConfirmationMigratesWithoutUploadOrSendReplay() = runPortableCoroutineTest {
        val legacy =
            ExternalShareDraft(
                envelope = envelope(),
                serverId = "server",
                stage = ExternalShareDraftStage.Confirming,
                selectedTargets = listOf(target("one")),
                comment = "legacy note",
                envelopeConsumed = true
            )
        val drafts = FakeDraftRepository(legacy)
        val targets = FakeTargetRepository(emptyList())
        val store = store(FakeEnvelopeRepository(envelope()), drafts, targets)

        store.dispatch(load())

        val ready = store.awaitState<ExternalShareState.ReadyForComposer>()
        assertEquals("one", ready.handoff.target.id)
        assertEquals(1, targets.resolveCalls)
        assertEquals(ExternalShareDraftStage.ComposerHandoff, drafts.draft?.stage)
        assertEquals("", drafts.draft?.comment)
    }

    @Test
    fun legacyDeliveryFailsClosedAndNeverResolvesOrReplays() = runPortableCoroutineTest {
        val destination = target("one")
        val legacy =
            ExternalShareDraft(
                envelope = envelope(),
                serverId = "server",
                stage = ExternalShareDraftStage.Delivering,
                selectedTargets = listOf(destination),
                deliveries = listOf(ExternalShareDelivery(destination, "delivery", ExternalShareDeliveryStatus.Sending)),
                envelopeConsumed = true
            )
        val targets = FakeTargetRepository(emptyList())
        val store = store(FakeEnvelopeRepository(envelope()), FakeDraftRepository(legacy), targets)

        store.dispatch(load())

        val error = store.awaitState<ExternalShareState.Error>()
        assertEquals("external_share_draft_invalid", error.errorCode)
        assertEquals(0, targets.resolveCalls)
    }

    @Test
    fun restoredComposerHandoffReemitsTypedHandoffAfterProcessDeath() = runPortableCoroutineTest {
        val persisted =
            ExternalShareDraft(
                envelope = envelope(),
                serverId = "server",
                stage = ExternalShareDraftStage.ComposerHandoff,
                selectedTargets = listOf(target("one")),
                envelopeConsumed = true
            )
        val drafts = FakeDraftRepository(persisted)
        val store = store(FakeEnvelopeRepository(envelope()), drafts, FakeTargetRepository(emptyList()))

        store.dispatch(load())

        store.awaitState<ExternalShareState.ReadyForComposer>()
        assertEquals(ExternalShareDraftStage.ComposerHandoff, drafts.draft?.stage)
    }

    @Test
    fun acknowledgedHandoffDeletesOnlyPickerDraftWhileCancelDeletesPrivateSource() = runPortableCoroutineTest {
        val native = FakeEnvelopeRepository(envelope())
        val drafts = FakeDraftRepository()
        val store = store(native, drafts, FakeTargetRepository(listOf(target("one"))))
        store.dispatch(load())
        store.awaitState<ExternalShareState.SelectingTargets> { !it.loading && it.targets.isNotEmpty() }
        select(store, target("one"))
        store.dispatch(ExternalShareIntent.Continue)
        val handoff = store.awaitState<ExternalShareState.ReadyForComposer>().handoff

        store.dispatch(ExternalShareIntent.HandoffCompleted(handoff.handoffId))
        store.awaitState<ExternalShareState.Idle>()
        awaitCondition { drafts.draft == null }

        assertIs<ExternalShareState.Idle>(store.state.value)
        assertNull(drafts.draft)
        assertFalse(native.deleted)

        val cancelNative = FakeEnvelopeRepository(envelope("cancel-token_1234567890"))
        val cancelDrafts = FakeDraftRepository()
        val cancelStore = store(cancelNative, cancelDrafts, FakeTargetRepository(emptyList()))
        cancelStore.dispatch(load("cancel-token_1234567890"))
        cancelStore.awaitState<ExternalShareState.SelectingTargets> { !it.loading }
        cancelStore.dispatch(ExternalShareIntent.Cancel)
        awaitCondition { cancelNative.deleted && cancelDrafts.draft == null }
        assertTrue(cancelNative.deleted)
        assertNull(cancelDrafts.draft)
    }

    @Test
    fun searchShowsLocalInterimWhileLoadingThenOnlyTheLatestServerAnswer() = runPortableCoroutineTest {
        val targets = FakeTargetRepository(listOf(target("one")))
        val store = store(FakeEnvelopeRepository(envelope()), FakeDraftRepository(), targets)
        store.dispatch(load())
        store.awaitState<ExternalShareState.SelectingTargets> { !it.loading && it.targets.isNotEmpty() }
        targets.interimByQuery = { query -> listOf(target("local-$query")) }
        targets.serverGates = mutableMapOf()

        for (query in listOf("a", "ab", "abc")) {
            store.dispatch(ExternalShareIntent.Search(query))
            store.awaitState<ExternalShareState.SelectingTargets> {
                it.loading && it.targets.map { target -> target.id } == listOf("local-$query")
            }
        }
        // The latest answers first, then the stale ones: none of them may replace it.
        targets.serverGates!!.getValue("abc").complete(Unit)
        val settled = store.awaitState<ExternalShareState.SelectingTargets> { !it.loading }
        assertEquals(listOf("server-abc"), settled.targets.map { it.id })
        targets.serverGates!!.getValue("ab").complete(Unit)
        targets.serverGates!!.getValue("a").complete(Unit)
        repeat(20) { yield() }
        val latest = assertIs<ExternalShareState.SelectingTargets>(store.state.value)
        assertEquals("abc", latest.query)
        assertEquals(listOf("server-abc"), latest.targets.map { it.id })
    }

    @Test
    fun conversationTargetsLoadInParallelWithoutBlockingRecentTargetsAndRefreshOnServerSwitch() = runPortableCoroutineTest {
        val serverAGate = CompletableDeferred<List<ForwardMessageTarget>>()
        val serverBGate = CompletableDeferred<List<ForwardMessageTarget>>()
        val targets =
            FakeTargetRepository(listOf(target("one"))).apply {
                targetsByServer =
                    mapOf(
                        "server" to listOf(target("one", serverId = "server")),
                        "server-b" to listOf(target("two", serverId = "server-b"))
                    )
                conversationTargetsLoader = { serverId ->
                    if (serverId.value == "server") serverAGate.await() else serverBGate.await()
                }
            }
        val store = store(FakeEnvelopeRepository(envelope()), FakeDraftRepository(), targets)

        store.dispatch(
            ExternalShareIntent.Load(
                token = "opaque-token_1234567890",
                accountId = "account",
                serverId = "server",
                serverOptions =
                    listOf(
                        ExternalShareServerOption("server", "Server A"),
                        ExternalShareServerOption("server-b", "Server B")
                    )
            )
        )

        // Recent channels/DMs become interactive immediately even while conversationTargets is still in flight.
        val beforeConversations = store.awaitState<ExternalShareState.SelectingTargets> { !it.loading && it.targets.isNotEmpty() }
        assertEquals(listOf("one"), beforeConversations.targets.map { it.id })
        assertEquals(emptyList(), beforeConversations.conversationTargets)

        val threadA = threadTarget("thread-a", serverId = "server")
        val taskA = taskTarget("task-a", serverId = "server")
        serverAGate.complete(listOf(threadA, taskA))
        val withConversations = store.awaitState<ExternalShareState.SelectingTargets> { it.conversationTargets.size == 2 }
        assertEquals(listOf("thread-a", "task-a"), withConversations.conversationTargets.map { it.id })

        // Switch server: old conversationTargets clear and refresh for the new server.
        store.dispatch(ExternalShareIntent.SelectServer("server-b"))
        val switched = store.awaitState<ExternalShareState.SelectingTargets> { it.serverId == "server-b" && !it.loading }
        assertEquals(listOf("two"), switched.targets.map { it.id })
        assertEquals(emptyList(), switched.conversationTargets)

        val threadB = threadTarget("thread-b", serverId = "server-b")
        serverBGate.complete(listOf(threadB))
        val settledB = store.awaitState<ExternalShareState.SelectingTargets> { it.serverId == "server-b" && it.conversationTargets.size == 1 }
        assertEquals(listOf("thread-b"), settledB.conversationTargets.map { it.id })
    }

    @Test
    fun searchRetainsThreadAndTaskTargetsAndHandoffResolvesReplyThreadParent() = runPortableCoroutineTest {
        val thread = threadTarget("thread-5", serverId = "server")
        val task = taskTarget("task-7", serverId = "server")
        val targets =
            FakeTargetRepository(listOf(target("one"))).apply {
                conversationTargetsLoader = { listOf(thread, task) }
                customSearchResults = listOf(target("ch-hit"), thread, task)
                threadParentResolver = { _, threadChannelId ->
                    DataResult.Content(
                        ForwardThreadParentInfo(
                            parentChannelId = ChannelId("room-1"),
                            parentMessageId = MessageId("parent-5")
                        )
                    )
                }
            }
        val store = store(FakeEnvelopeRepository(envelope()), FakeDraftRepository(), targets)

        store.dispatch(load())
        store.awaitState<ExternalShareState.SelectingTargets> { !it.loading && it.conversationTargets.size == 2 }

        // Search retains thread & task rows alongside channels.
        store.dispatch(ExternalShareIntent.Search("topic"))
        val searched = store.awaitState<ExternalShareState.SelectingTargets> { !it.loading && it.query == "topic" }
        assertEquals(listOf("ch-hit", "thread-5", "task-7"), searched.targets.map { it.id })

        // Return to empty query, select thread from conversationTargets, and continue: parentMessageId is resolved.
        store.dispatch(ExternalShareIntent.Search(""))
        store.awaitState<ExternalShareState.SelectingTargets> { !it.loading && it.query.isBlank() }
        select(store, thread)
        store.dispatch(ExternalShareIntent.Continue)

        val ready = store.awaitState<ExternalShareState.ReadyForComposer>()
        assertEquals(ChannelId("resolved-thread-5"), ready.handoff.target.channelId)
        assertEquals(ConversationKind.Thread, ready.handoff.target.channelKind)
        assertEquals(ChannelId("room-1"), ready.handoff.target.parentChannelId)
        assertEquals(MessageId("parent-5"), ready.handoff.target.parentMessageId)
        assertEquals(1, targets.threadParentResolveCalls)
    }

    @Test
    fun selectingTaskConversationFromConversationTargetsHandsOffWithoutThreadParentProbe() = runPortableCoroutineTest {
        val task = taskTarget("task-7", serverId = "server")
        val targets =
            FakeTargetRepository(listOf(target("one"))).apply {
                conversationTargetsLoader = { listOf(task) }
            }
        val store = store(FakeEnvelopeRepository(envelope()), FakeDraftRepository(), targets)

        store.dispatch(load())
        store.awaitState<ExternalShareState.SelectingTargets> { !it.loading && it.conversationTargets.size == 1 }

        select(store, task)
        store.dispatch(ExternalShareIntent.Continue)

        val ready = store.awaitState<ExternalShareState.ReadyForComposer>()
        assertEquals(ChannelId("resolved-task-7"), ready.handoff.target.channelId)
        assertEquals(ConversationKind.Task, ready.handoff.target.channelKind)
        assertEquals(0, targets.threadParentResolveCalls)
    }

    @Test
    fun conversationTargetsFailureDoesNotFailPickerLoad() = runPortableCoroutineTest {
        val targets =
            FakeTargetRepository(listOf(target("one"))).apply {
                conversationTargetsLoader = { error("offline") }
            }
        val store = store(FakeEnvelopeRepository(envelope()), FakeDraftRepository(), targets)

        store.dispatch(load())
        val settled = store.awaitState<ExternalShareState.SelectingTargets> { !it.loading && it.targets.isNotEmpty() }
        repeat(10) { yield() }

        val finalState = assertIs<ExternalShareState.SelectingTargets>(store.state.value)
        assertNull(finalState.errorCode)
        assertEquals(listOf("one"), settled.targets.map { it.id })
        assertEquals(emptyList(), finalState.conversationTargets)
    }

    private fun CoroutineScope.store(
        native: FakeEnvelopeRepository,
        drafts: FakeDraftRepository,
        targets: FakeTargetRepository
    ) = ExternalShareStore(
        envelopeRepository = native,
        draftRepository = drafts,
        targetRepository = targets,
        scope = this
    )

    private suspend inline fun <reified T : ExternalShareState> ExternalShareStore.awaitState(
        predicate: (T) -> Boolean = { true }
    ): T {
        repeat(100) {
            val current = state.value
            if (current is T && predicate(current)) return current
            yield()
        }
        error("ExternalShareStore did not reach ${T::class.simpleName}; current=${state.value}")
    }

    private suspend fun awaitCondition(predicate: () -> Boolean) {
        repeat(100) {
            if (predicate()) return
            yield()
        }
        error("ExternalShareStore side effect did not settle")
    }

    private fun load(token: String = "opaque-token_1234567890") =
        ExternalShareIntent.Load(
            token = token,
            accountId = "account",
            serverId = "server"
        )

    private fun select(store: ExternalShareStore, target: ForwardMessageTarget) {
        val state = assertIs<ExternalShareState.SelectingTargets>(store.state.value)
        store.dispatch(
            ExternalShareIntent.ToggleTarget(
                target = target,
                sessionId = state.externalShareDirectorySessionId(),
                renderGeneration = state.requestGeneration
            )
        )
    }

    private fun envelope(token: String = "opaque-token_1234567890") =
        ExternalShareEnvelope(
            token = token,
            createdAtEpochMillis = 1L,
            sourcePlatform = "android",
            text = "hello",
            items = listOf(ExternalShareItem("private://one", "one.jpg", "image/jpeg", 10L))
        )

    private fun target(id: String, serverId: String = "server") =
        ForwardMessageTarget(
            serverId = ServerId(serverId),
            kind = ForwardMessageTargetKind.RecentChannel,
            id = id,
            title = id,
            channelId = ChannelId(id),
            channelKind = ConversationKind.Channel
        )

    private fun threadTarget(id: String, serverId: String = "server") =
        ForwardMessageTarget(
            serverId = ServerId(serverId),
            kind = ForwardMessageTargetKind.ConversationThread,
            id = id,
            title = "#general › $id",
            channelId = ChannelId(id),
            channelKind = ConversationKind.Thread,
            channelType = "thread",
            parentChannelId = ChannelId("room-1"),
            parentChannelName = "general",
            parentChannelType = "channel",
            parentMessagePreview = "topic preview"
        )

    private fun taskTarget(id: String, serverId: String = "server") =
        ForwardMessageTarget(
            serverId = ServerId(serverId),
            kind = ForwardMessageTargetKind.ConversationTask,
            id = id,
            title = "#general:#7 $id",
            channelId = ChannelId(id),
            channelKind = ConversationKind.Task,
            channelType = "task",
            parentChannelId = ChannelId("room-1"),
            parentChannelName = "general",
            parentChannelType = "channel",
            taskNumber = 7,
            taskTitle = id
        )

    @Test
    fun iosZipEnvelopeHandsOffArchiveItemToComposerWithoutMimeRestriction() = runPortableCoroutineTest {
        val zipEnvelope =
            ExternalShareEnvelope(
                token = "ios-wechat-zip-token-123456",
                createdAtEpochMillis = 1_700_000_000_000L,
                sourcePlatform = "ios",
                text = "",
                subject = "",
                items =
                    listOf(
                        ExternalShareItem(
                            localRef = "file:///private/var/mobile/Containers/Shared/AppGroup/test/ExternalShareInbox/ios-wechat-zip-token-123456/item-1-ChatHistory.zip",
                            displayName = "微信聊天记录.zip",
                            mimeType = "application/zip",
                            sizeBytes = 4_096L
                        )
                    )
            )
        val dmTarget =
            ForwardMessageTarget(
                serverId = ServerId("srv-private"),
                kind = ForwardMessageTargetKind.AgentDirectMessageCandidate,
                id = "agent-1",
                title = "AirenaAgent",
                channelId = ChannelId("dm-agent-1"),
                channelKind = ConversationKind.DirectMessage
            )
        val envelopes = FakeEnvelopeRepository(zipEnvelope)
        val drafts = FakeDraftRepository()
        val targets = FakeTargetRepository(listOf(dmTarget))
        val store = store(envelopes, drafts, targets)

        store.dispatch(
            ExternalShareIntent.Load(
                token = zipEnvelope.token,
                accountId = "acct-private",
                serverId = "srv-private"
            )
        )

        val selecting = store.awaitState<ExternalShareState.SelectingTargets> { !it.loading && it.targets.isNotEmpty() }
        assertEquals(1, selecting.envelope.items.size)
        assertEquals("application/zip", selecting.envelope.items.single().mimeType)
        assertEquals("微信聊天记录.zip", selecting.envelope.items.single().displayName)

        select(store, dmTarget)
        store.dispatch(ExternalShareIntent.Continue)

        val handoff = store.awaitState<ExternalShareState.ReadyForComposer>().handoff
        assertEquals("srv-private", handoff.serverId)
        assertEquals("resolved-agent-1", handoff.target.channelId?.value)
        assertEquals("application/zip", handoff.envelope.items.single().mimeType)
    }

    private class FakeEnvelopeRepository(
        private val value: ExternalShareEnvelope
    ) : ExternalShareEnvelopeRepository {
        val events = mutableListOf<String>()
        var deleted = false

        override suspend fun load(token: String): DataResult<ExternalShareEnvelope> =
            DataResult.Content(value)

        override suspend fun consume(token: String, consumptionId: String): DataResult<Unit> {
            events += "consume"
            return DataResult.Content(Unit)
        }

        override suspend fun delete(token: String) {
            deleted = true
        }
    }

    private class FakeDraftRepository(
        initial: ExternalShareDraft? = null
    ) : ExternalShareDraftRepository {
        var draft: ExternalShareDraft? = initial
        val events = mutableListOf<String>()

        override suspend fun load(token: String): DataResult<ExternalShareDraft> =
            draft?.let { DataResult.Content(it) } ?: DataResult.Empty(EmptyReason.NoSettingsAvailable)

        override suspend fun save(draft: ExternalShareDraft): DataResult<Unit> {
            this.draft = draft
            events += "save:${draft.stage.name}:${draft.envelopeConsumed}"
            return DataResult.Content(Unit)
        }

        override suspend fun delete(token: String): DataResult<Unit> {
            draft = null
            return DataResult.Content(Unit)
        }
    }

    private class FakeTargetRepository(
        private val values: List<ForwardMessageTarget>
    ) : ForwardMessageTargetRepository {
        var resolveCalls = 0
        var threadParentResolveCalls = 0
        var targetsByServer: Map<String, List<ForwardMessageTarget>>? = null
        var conversationTargetsLoader: suspend (ServerId) -> List<ForwardMessageTarget> = { emptyList() }
        var customSearchResults: List<ForwardMessageTarget>? = null
        var threadParentResolver: suspend (ServerId, ChannelId) -> DataResult<ForwardThreadParentInfo> = { _, _ ->
            DataResult.Error(ScreenError("forward_thread_parent_unavailable", retryable = false))
        }
        var interimByQuery: (String) -> List<ForwardMessageTarget> = { emptyList() }

        /** When set, each search query waits for its own gate and answers `server-<query>`. */
        var serverGates: MutableMap<String, CompletableDeferred<Unit>>? = null

        override suspend fun interimSearchTargets(
            serverId: ServerId,
            query: String,
            limit: Int
        ): List<ForwardMessageTarget> = interimByQuery(query)

        override suspend fun recentTargets(serverId: ServerId, limit: Int): DataResult<List<ForwardMessageTarget>> =
            DataResult.Content(targetsByServer?.get(serverId.value) ?: values)

        override suspend fun conversationTargets(
            serverId: ServerId,
            currentChannelId: ChannelId?
        ): List<ForwardMessageTarget> = conversationTargetsLoader(serverId)

        override suspend fun resolveConversationThreadParent(
            serverId: ServerId,
            threadChannelId: ChannelId
        ): DataResult<ForwardThreadParentInfo> {
            threadParentResolveCalls += 1
            return threadParentResolver(serverId, threadChannelId)
        }

        override suspend fun searchTargets(
            serverId: ServerId,
            query: String,
            limit: Int
        ): DataResult<List<ForwardMessageTarget>> {
            customSearchResults?.let { return DataResult.Content(it) }
            val gates = serverGates ?: return DataResult.Content(targetsByServer?.get(serverId.value) ?: values)
            gates.getOrPut(query) { CompletableDeferred() }.await()
            return DataResult.Content(
                listOf(
                    ForwardMessageTarget(
                        serverId = serverId,
                        kind = ForwardMessageTargetKind.RecentChannel,
                        id = "server-$query",
                        title = "server-$query",
                        channelId = ChannelId("server-$query"),
                        channelKind = ConversationKind.Channel
                    )
                )
            )
        }

        override suspend fun resolveTargetChannel(
            sourceServerId: ServerId,
            target: ForwardMessageTarget,
            allowJoin: Boolean
        ): DataResult<ForwardMessageDestination> {
            resolveCalls += 1
            return DataResult.Content(
                ForwardMessageDestination(
                    serverId = sourceServerId,
                    channelId = ChannelId("resolved-${target.id}"),
                    channelKind = target.channelKind
                )
            )
        }

        override suspend fun forwardMessages(request: ForwardMessageExecuteRequest): DataResult<ForwardMessageSendResult> =
            error("External Share handoff must not forward messages")
    }
}
