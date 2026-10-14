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
import build.raft.app.network.sync.CanonicalMessageV2ScopeContinuity
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.TopLevelScopeRefV2
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Task #168 step 3: the realtime gateway never touches the network. A window exists only after the enter
 * snapshot (whose REST page → full-V2 conversion is checked here end to end); realtime frames then append.
 */
class CanonicalMessageV2ProductionBindingTest {
    @Test
    fun flagOffDoesZeroV2Work() = runBlocking {
        val h = harness(flagOn = false)
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, h.gateway.accept(grant(), input(7L)))
        h.loader.loadOnEnter(partition, "channel-1") { true }
        assertEquals(emptyList(), h.network.paths)
        assertEquals(0, h.store.snapshots.size)
        assertEquals(0, h.store.incrementals.size)
    }

    @Test
    fun aRealtimeFrameWithoutAWindowNeverRequestsOrWritesAnything() = runBlocking {
        val h = harness(flagOn = true)
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, h.gateway.accept(grant(), input(7L)))
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, h.gateway.accept(grant(), input(8L)))
        assertEquals(emptyList(), h.network.paths, "no first-frame activation (task #168 step 3)")
        assertEquals(0, h.store.snapshots.size)
        assertEquals(0, h.store.incrementals.size)

        // Twin: the same frames after the user opened the channel are appended — still without a request.
        h.loader.loadOnEnter(partition, "channel-1") { true }
        val pathsAfterEnter = h.network.paths.toList()
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, h.gateway.accept(grant(), input(8L)))
        assertEquals(1, h.store.incrementals.size)
        assertEquals(pathsAfterEnter, h.network.paths, "a realtime append never requests the network")
    }

    @Test
    fun theEnterPageBecomesAFullV2SnapshotWithItsRealReplyCount() = runBlocking {
        val h = harness(
            flagOn = true,
            network = RecordingNetworkClient(
                canonicalV2TestThreadParentPage(),
                threadSummariesBody = canonicalV2TestThreadSummaries(replyCount = 4),
            ),
        )
        h.loader.loadOnEnter(partition, "channel-1") { true }
        assertEquals(
            listOf("channels/channel-1", "messages/channel/channel-1", "channels/channel-1/threads"),
            h.network.paths,
        )
        val frame = h.store.snapshots.single()
        assertEquals(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID, frame.scope.consumerId)
        val context = assertIs<CanonicalConversationContextV2.TopLevel>(frame.envelopes.single().context)
        assertIs<TopLevelScopeRefV2.Channel>(context.scope)
        assertEquals(4, frame.envelopes.single().fact.replyCount)
        assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, h.canary.continuityOf(frame.scope))
    }

    @Test
    fun aThreadParentWithoutItsSummaryNeverBecomesAWindow() = runBlocking {
        val h = harness(
            flagOn = true,
            network = RecordingNetworkClient(canonicalV2TestThreadParentPage(), threadSummariesBody = "{}"),
        )
        h.loader.loadOnEnter(partition, "channel-1") { true }
        assertEquals(0, h.store.snapshots.size)
        val scope = SyncScopeKey(partition, "channel-1", "channel")
        assertEquals(CanonicalMessageV2ScopeContinuity.NoWindow, h.canary.continuityOf(scope))
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, h.gateway.accept(grant(), input(8L)))
        assertTrue(h.store.incrementals.isEmpty())
    }

    private class Harness(
        val network: RecordingNetworkClient,
        val store: RecordingStore,
        val canary: CanonicalMessageV2ProductionCanary,
        val gateway: ProviderBackedCanonicalMessageV2OwnerGateway,
        val loader: CanonicalMessageV2EnteredScopeLoader,
    )

    private fun harness(
        flagOn: Boolean,
        network: RecordingNetworkClient = RecordingNetworkClient(canonicalV2TestPage()),
    ): Harness {
        val store = RecordingStore()
        val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        val canary = CanonicalMessageV2ProductionCanary(isEnabledForServer = { flagOn }, generationEngine = engine)
        // The connection's rooms:joined arrived: an entered member channel becomes continuous (task #168 step 3).
        runBlocking {
            canary.noteTransportEpoch(partition, 1L)
            canary.noteRoomsJoined(partition, 1L)
        }
        val loader = CanonicalMessageV2EnteredScopeLoader(
            canary = canary,
            generationEngine = engine,
            fetcher = CanonicalMessageV2RestSnapshotFetcher(network),
            isEnabledForServer = { flagOn },
        )
        return Harness(network, store, canary, ProviderBackedCanonicalMessageV2OwnerGateway(canary), loader)
    }

    private val partition = grant().durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)

    private fun input(seq: Long) = CanonicalMessageFoldInput(
        kind = CanonicalMessageFoldKind.New,
        row = CanonicalMessageRow(
            id = "message-$seq",
            channelId = "channel-1",
            seq = seq,
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

    private fun grant() = canonicalV2TestGrant()
}
