package build.raft.app.data

import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.MessageAggregateField
import build.raft.app.network.sync.CanonicalConversationContextV2
import build.raft.app.network.sync.CanonicalMessageFoldInput
import build.raft.app.network.sync.CanonicalMessageFoldKind
import build.raft.app.network.sync.CanonicalMessageV2BaselineCoordinator
import build.raft.app.network.sync.CanonicalMessageV2CanaryOutcome
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.TopLevelScopeRefV2
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CanonicalMessageV2ProductionBindingTest {
    @Test
    fun flagOffDoesNotReadNetworkOrRequireAnAuthSession() = runBlocking {
        val store = RecordingStore()
        val network = RecordingNetworkClient(canonicalV2TestPage())
        val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        val gateway = ProviderBackedCanonicalMessageV2OwnerGateway(
            canary = CanonicalMessageV2ProductionCanary(
                isEnabledForServer = { false },
                generationEngine = engine,
            ),
            authStore = InMemorySlockAuthSessionStore(null),
            networkClient = network,
        )

        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, gateway.accept(grant(), input()))
        assertEquals(emptyList(), network.paths)
        assertEquals(0, store.snapshots.size)
    }

    @Test
    fun serverPageBecomesFullV2SnapshotBeforeCommit() = runBlocking {
        val store = RecordingStore()
        val network = RecordingNetworkClient(
            canonicalV2TestThreadParentPage(),
            threadSummariesBody = canonicalV2TestThreadSummaries(replyCount = 4),
        )
        val gateway = gateway(store, network)

        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, gateway.accept(grant(), input()))
        assertEquals(
            listOf("channels/channel-1", "messages/channel/channel-1", "channels/channel-1/threads"),
            network.paths,
        )
        assertEquals(1, store.snapshots.size)
        val frame = store.snapshots.single()
        assertEquals(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID, frame.scope.consumerId)
        val context = assertIs<CanonicalConversationContextV2.TopLevel>(frame.envelopes.single().context)
        assertIs<TopLevelScopeRefV2.Channel>(context.scope)
        assertEquals(4, frame.envelopes.single().fact.replyCount)
    }

    @Test
    fun threadParentWithoutSummaryCannotEnterSoleApply() = runBlocking {
        val store = RecordingStore()
        val gateway = gateway(
            store,
            RecordingNetworkClient(canonicalV2TestThreadParentPage(), threadSummariesBody = "{}"),
        )

        assertIs<CanonicalMessageV2CanaryOutcome.Held>(gateway.accept(grant(), input()))
        assertEquals(0, store.snapshots.size)
    }

    private fun gateway(
        store: RecordingStore,
        network: RecordingNetworkClient,
    ): ProviderBackedCanonicalMessageV2OwnerGateway {
        val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        return ProviderBackedCanonicalMessageV2OwnerGateway(
            canary = CanonicalMessageV2ProductionCanary(
                isEnabledForServer = { true },
                generationEngine = engine,
            ),
            authStore = InMemorySlockAuthSessionStore(session()),
            networkClient = network,
        )
    }

    private fun input() = CanonicalMessageFoldInput(
        kind = CanonicalMessageFoldKind.New,
        row = CanonicalMessageRow(
            id = "message-1",
            channelId = "channel-1",
            seq = 7L,
            senderType = "user",
            senderId = "user-1",
            content = "hello",
            messageType = "chat",
            createdAt = "2026-07-17T00:00:00.000Z",
            senderName = "Alice",
            conversationContext = CanonicalConversationContextWire("channel"),
        ),
        presentAggregates = MessageAggregateField.entries.toList(),
    )

    private fun grant() = RealtimeGrantRecord(
        slotKey = RealtimeSlotKey("scope-1", "server-1"),
        principalId = "user-1",
        environmentId = "env-test",
        authGeneration = 1L,
        connectionFamilyId = "family-1",
        connectionEpochId = "epoch-1",
    )

    private fun session() = StoredAuthSession(
        user = StoredAuthUser(id = "user-1", email = "alice@example.com"),
        accessToken = "access",
        refreshToken = "refresh",
        selectedServerId = "server-1",
        servers = listOf(StoredAuthServer(id = "server-1", name = "Server", slug = "server")),
    )
}
