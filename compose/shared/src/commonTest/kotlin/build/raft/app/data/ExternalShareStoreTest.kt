package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ServerId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ExternalShareStoreTest {
    @Test
    fun freshEnvelopeIsPersistedBeforeNativeClaimAndRestoresPicker() = runTest {
        val envelope = envelope()
        val native = FakeEnvelopeRepository(envelope)
        val drafts = FakeDraftRepository()
        val store = store(native, drafts, FakeTargetRepository(listOf(target("one"))))

        store.dispatch(load())
        advanceUntilIdle()

        val state = assertIs<ExternalShareState.SelectingTargets>(store.state.value)
        assertEquals("account", state.accountId)
        assertEquals(listOf("one"), state.targets.map { it.id })
        assertEquals(listOf("save:SelectingTargets:false", "save:SelectingTargets:true"), drafts.events)
        assertEquals(listOf("consume"), native.events)
        assertTrue(drafts.draft?.envelopeConsumed == true)
    }

    @Test
    fun selectingAnotherTargetReplacesPreviousAndContinueOnlyCreatesDurableHandoff() = runTest {
        val first = target("one")
        val second = target("two")
        val native = FakeEnvelopeRepository(envelope())
        val drafts = FakeDraftRepository()
        val targets = FakeTargetRepository(listOf(first, second))
        val store = store(native, drafts, targets)
        store.dispatch(load())
        advanceUntilIdle()

        select(store, first)
        select(store, second)
        assertEquals(listOf(second), assertIs<ExternalShareState.SelectingTargets>(store.state.value).selectedTargets)

        store.dispatch(ExternalShareIntent.Continue)
        advanceUntilIdle()

        val ready = assertIs<ExternalShareState.ReadyForComposer>(store.state.value)
        assertEquals(second.id, ready.handoff.target.id)
        assertEquals(ChannelId("resolved-two"), ready.handoff.target.channelId)
        assertEquals(1, targets.resolveCalls)
        assertEquals(ExternalShareDraftStage.ComposerHandoff, drafts.draft?.stage)
        assertTrue(drafts.draft?.deliveries.isNullOrEmpty())
        assertFalse(native.deleted)
    }

    @Test
    fun legacySingleTargetConfirmationMigratesWithoutUploadOrSendReplay() = runTest {
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
        advanceUntilIdle()

        val ready = assertIs<ExternalShareState.ReadyForComposer>(store.state.value)
        assertEquals("one", ready.handoff.target.id)
        assertEquals(1, targets.resolveCalls)
        assertEquals(ExternalShareDraftStage.ComposerHandoff, drafts.draft?.stage)
        assertEquals("", drafts.draft?.comment)
    }

    @Test
    fun legacyDeliveryFailsClosedAndNeverResolvesOrReplays() = runTest {
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
        advanceUntilIdle()

        val error = assertIs<ExternalShareState.Error>(store.state.value)
        assertEquals("external_share_draft_invalid", error.errorCode)
        assertEquals(0, targets.resolveCalls)
    }

    @Test
    fun restoredComposerHandoffReemitsTypedHandoffAfterProcessDeath() = runTest {
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
        advanceUntilIdle()

        assertIs<ExternalShareState.ReadyForComposer>(store.state.value)
        assertEquals(ExternalShareDraftStage.ComposerHandoff, drafts.draft?.stage)
    }

    @Test
    fun acknowledgedHandoffDeletesOnlyPickerDraftWhileCancelDeletesPrivateSource() = runTest {
        val native = FakeEnvelopeRepository(envelope())
        val drafts = FakeDraftRepository()
        val store = store(native, drafts, FakeTargetRepository(listOf(target("one"))))
        store.dispatch(load())
        advanceUntilIdle()
        select(store, target("one"))
        store.dispatch(ExternalShareIntent.Continue)
        advanceUntilIdle()
        val handoff = assertIs<ExternalShareState.ReadyForComposer>(store.state.value).handoff

        store.dispatch(ExternalShareIntent.HandoffCompleted(handoff.handoffId))
        advanceUntilIdle()

        assertIs<ExternalShareState.Idle>(store.state.value)
        assertNull(drafts.draft)
        assertFalse(native.deleted)

        val cancelNative = FakeEnvelopeRepository(envelope("cancel-token_1234567890"))
        val cancelDrafts = FakeDraftRepository()
        val cancelStore = store(cancelNative, cancelDrafts, FakeTargetRepository(emptyList()))
        cancelStore.dispatch(load("cancel-token_1234567890"))
        advanceUntilIdle()
        cancelStore.dispatch(ExternalShareIntent.Cancel)
        advanceUntilIdle()
        assertTrue(cancelNative.deleted)
        assertNull(cancelDrafts.draft)
    }

    private fun TestScope.store(
        native: FakeEnvelopeRepository,
        drafts: FakeDraftRepository,
        targets: FakeTargetRepository
    ) = ExternalShareStore(
        envelopeRepository = native,
        draftRepository = drafts,
        targetRepository = targets,
        scope = this
    )

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

        override suspend fun recentTargets(serverId: ServerId, limit: Int): DataResult<List<ForwardMessageTarget>> =
            DataResult.Content(values)

        override suspend fun searchTargets(
            serverId: ServerId,
            query: String,
            limit: Int
        ): DataResult<List<ForwardMessageTarget>> = DataResult.Content(values)

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

        override suspend fun forwardMessages(request: ForwardMessageExecuteRequest): DataResult<Unit> =
            error("External Share handoff must not forward messages")
    }
}
