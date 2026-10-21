package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
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

    private fun target(id: String) =
        ForwardMessageTarget(
            serverId = ServerId("server"),
            kind = ForwardMessageTargetKind.RecentChannel,
            id = id,
            title = id,
            channelId = ChannelId(id),
            channelKind = ConversationKind.Channel
        )

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
        var interimByQuery: (String) -> List<ForwardMessageTarget> = { emptyList() }

        /** When set, each search query waits for its own gate and answers `server-<query>`. */
        var serverGates: MutableMap<String, CompletableDeferred<Unit>>? = null

        override suspend fun interimSearchTargets(
            serverId: ServerId,
            query: String,
            limit: Int
        ): List<ForwardMessageTarget> = interimByQuery(query)

        override suspend fun recentTargets(serverId: ServerId, limit: Int): DataResult<List<ForwardMessageTarget>> =
            DataResult.Content(values)

        override suspend fun searchTargets(
            serverId: ServerId,
            query: String,
            limit: Int
        ): DataResult<List<ForwardMessageTarget>> {
            val gates = serverGates ?: return DataResult.Content(values)
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
