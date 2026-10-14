package build.raft.app.data

import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.MessageAggregateField
import build.raft.app.network.sync.CanonicalConversationContextV2
import build.raft.app.network.sync.CanonicalMessageFoldInput
import build.raft.app.network.sync.CanonicalMessageFoldKind
import build.raft.app.network.sync.CanonicalMessageV2BaselineCoordinator
import build.raft.app.network.sync.CanonicalMessageV2CanaryMode
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
        val canary = CanonicalMessageV2ProductionCanary(
            isEnabledForServer = { false },
            generationEngine = engine,
        )
        val gateway = ProviderBackedCanonicalMessageV2OwnerGateway(
            canary = canary,
            authStore = InMemorySlockAuthSessionStore(null),
            networkClient = network,
            isGrantCurrent = { true },
        )

        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, gateway.accept(grant(), input()))
        canary.awaitSnapshotJobs()
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
        val (gateway, canary) = gateway(store, network)

        // Task #168: the snapshot loads in the background; accept never waits for it.
        assertEquals(CanonicalMessageV2CanaryOutcome.Pending, gateway.accept(grant(), input()))
        canary.awaitSnapshotJobs()
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
        val (gateway, canary) = gateway(
            store,
            RecordingNetworkClient(canonicalV2TestThreadParentPage(), threadSummariesBody = "{}"),
        )

        assertEquals(CanonicalMessageV2CanaryOutcome.Pending, gateway.accept(grant(), input()))
        canary.awaitSnapshotJobs()
        assertEquals(0, store.snapshots.size)
        assertEquals(CanonicalMessageV2CanaryMode.Broken, canary.modeFor("server-1", "channel-1"))
    }

    @Test
    fun aSnapshotWhoseGrantWasRevokedWhileItLoadedIsNeverCommitted() = runBlocking {
        val store = RecordingStore()
        val network = RecordingNetworkClient(
            canonicalV2TestThreadParentPage(),
            threadSummariesBody = canonicalV2TestThreadSummaries(replyCount = 4),
        )
        // The socket dropped / the server was switched while the page was in flight.
        val (gateway, canary) = gateway(store, network, isGrantCurrent = { false })

        assertEquals(CanonicalMessageV2CanaryOutcome.Pending, gateway.accept(grant(), input()))
        canary.awaitSnapshotJobs()
        assertEquals(3, network.paths.size, "the same page was fetched as in the committed twin")
        assertEquals(0, store.snapshots.size)
        assertEquals(CanonicalMessageV2CanaryMode.Broken, canary.modeFor("server-1", "channel-1"))
    }

    private fun gateway(
        store: RecordingStore,
        network: RecordingNetworkClient,
        isGrantCurrent: suspend (RealtimeGrantRecord) -> Boolean = { true },
    ): Pair<ProviderBackedCanonicalMessageV2OwnerGateway, CanonicalMessageV2ProductionCanary> {
        val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        val canary = CanonicalMessageV2ProductionCanary(
            isEnabledForServer = { true },
            generationEngine = engine,
        )
        return ProviderBackedCanonicalMessageV2OwnerGateway(
            canary = canary,
            authStore = InMemorySlockAuthSessionStore(session()),
            networkClient = network,
            isGrantCurrent = isGrantCurrent,
        ) to canary
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
