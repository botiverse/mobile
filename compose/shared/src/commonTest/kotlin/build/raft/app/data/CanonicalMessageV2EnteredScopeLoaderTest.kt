package build.raft.app.data

import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.MessageAggregateField
import build.raft.app.network.sync.CanonicalMessageFoldInput
import build.raft.app.network.sync.CanonicalMessageFoldKind
import build.raft.app.network.sync.CanonicalMessageV2BaselineCoordinator
import build.raft.app.network.sync.CanonicalMessageV2CanaryOutcome
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SyncScopeKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task #1124 write entry: the enter-snapshot loader fetches the entered channel's REST page and
 * commits it as that channel's V2 baseline — only when the server flag is on, only for a scope the
 * canary does not own, only while the identity that entered is still current.
 */
class CanonicalMessageV2EnteredScopeLoaderTest {
    @Test
    fun flagOffWritesNothingAndNeverTouchesTheNetwork() = runBlocking {
        val off = harness(flagOn = false)
        off.loader.loadOnEnter(partition, "channel-1") { true }
        assertEquals(emptyList(), off.network.paths)
        assertEquals(0, off.store.snapshots.size)

        // Positive control: same inputs with the flag on write exactly one whole-page baseline.
        val on = harness(flagOn = true)
        on.loader.loadOnEnter(partition, "channel-1") { true }
        assertEquals(listOf("channels/channel-1", "messages/channel/channel-1"), on.network.paths)
        val frame = on.store.snapshots.single()
        assertEquals(SyncScopeKey(partition, "channel-1", "channel"), frame.scope)
        assertEquals(listOf("message-1"), frame.envelopes.map { it.fact.id })
        assertEquals(0, frame.envelopes.single().fact.replyCount, "no threadId ⇒ exactly zero replies")
        assertEquals(7L, frame.cursor.latestSeq)
        assertTrue(frame.cursor.hasLoadedOldest)
        assertTrue(frame.cursor.hasLoadedNewest)
        assertTrue(on.logs.any { "outcome=committed facts=1" in it }, on.logs.toString())
    }

    @Test
    fun threadParentReplyCountComesFromTheSummariesEndpoint() = runBlocking {
        val h = harness(
            flagOn = true,
            pageBody = canonicalV2TestThreadParentPage(),
            threadSummariesBody = canonicalV2TestThreadSummaries(replyCount = 4),
        )
        h.loader.loadOnEnter(partition, "channel-1") { true }
        assertEquals(
            listOf("channels/channel-1", "messages/channel/channel-1", "channels/channel-1/threads"),
            h.network.paths,
        )
        assertEquals(4, h.store.snapshots.single().envelopes.single().fact.replyCount)
        assertTrue(h.logs.any { "outcome=committed facts=1" in it }, h.logs.toString())
    }

    @Test
    fun summariesOutageWithThreadParentRejectsThePage() = runBlocking {
        val h = harness(
            flagOn = true,
            pageBody = canonicalV2TestThreadParentPage(),
            threadSummariesBody = null,
        )
        h.loader.loadOnEnter(partition, "channel-1") { true }
        assertEquals(0, h.store.snapshots.size)
        assertTrue(h.logs.any { "outcome=canonical_v2_thread_summaries_unavailable" in it }, h.logs.toString())
    }

    @Test
    fun summariesOutageWithoutThreadParentsStillLoads() = runBlocking {
        val h = harness(flagOn = true, threadSummariesBody = null)
        h.loader.loadOnEnter(partition, "channel-1") { true }
        assertEquals(1, h.store.snapshots.size)
        assertTrue(
            h.network.paths.none { it.endsWith("/threads") },
            "no thread parents ⇒ the summaries endpoint is never called: ${h.network.paths}",
        )
    }

    @Test
    fun threadParentWithoutSummaryDropsTheNodeAndRefusesThePage() = runBlocking {
        val h = harness(
            flagOn = true,
            pageBody = canonicalV2TestThreadParentPage(),
            threadSummariesBody = "{}",
        )
        h.loader.loadOnEnter(partition, "channel-1") { true }
        assertEquals(0, h.store.snapshots.size)
        assertTrue(h.logs.any { "canonical_v2_snapshot_reply_count_missing" in it }, h.logs.toString())
        assertTrue(h.logs.any { "outcome=canonical_v2_snapshot_not_full_v6" in it }, h.logs.toString())
    }

    @Test
    fun emptyPageIsCommittedAsAnEmptyBaseline() = runBlocking {
        // Task #1137: an empty channel is an answer, persisted as a baseline with no facts and no seqs.
        val h = harness(flagOn = true, pageBody = """{"messages":[],"historyLimited":false}""")
        h.loader.loadOnEnter(partition, "channel-1") { true }
        val frame = h.store.snapshots.single()
        assertEquals(SyncScopeKey(partition, "channel-1", "channel"), frame.scope)
        assertTrue(frame.envelopes.isEmpty())
        assertEquals(null, frame.cursor.latestSeq)
        assertEquals(null, frame.cursor.oldestLoadedSeq)
        assertEquals(null, frame.cursor.newestLoadedSeq)
        assertTrue(frame.cursor.hasLoadedOldest, "an empty channel has nothing older")
        assertTrue(frame.cursor.hasLoadedNewest)
        assertTrue(h.logs.any { "canonical_v2_snapshot_empty_page scope=channel-1" in it }, h.logs.toString())
        assertTrue(h.logs.any { "outcome=committed facts=0" in it }, h.logs.toString())
    }

    @Test
    fun theCanarysOwnScopeIsLeftToTheCanary() = runBlocking {
        val h = harness(flagOn = true, channelIds = setOf("channel-1", "channel-2"))
        // The canary selects channel-1 through its ordinary first-event path.
        val gateway = ProviderBackedCanonicalMessageV2OwnerGateway(
            canary = h.canary,
            authStore = InMemorySlockAuthSessionStore(canonicalV2TestSession()),
            networkClient = h.network,
        )
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, gateway.accept(canonicalV2TestGrant(), foldInput("channel-1")))
        assertEquals(1, h.store.snapshots.size)
        val pathsAfterCanary = h.network.paths.size

        h.loader.loadOnEnter(partition, "channel-1") { true }
        assertEquals(1, h.store.snapshots.size, "the canary-owned scope must not be rewritten by the enter path")
        assertTrue(h.logs.any { "scope=channel-1 outcome=canary_owned" in it }, h.logs.toString())

        // Positive control: a sibling scope on the same partition is written.
        h.loader.loadOnEnter(partition, "channel-2") { true }
        assertEquals(2, h.store.snapshots.size)
        assertEquals("channel-2", h.store.snapshots.last().scope.scopeId)
        assertTrue(h.network.paths.size > pathsAfterCanary)
    }

    @Test
    fun identityChangeDuringTheFetchWritesNothing() = runBlocking {
        val h = harness(flagOn = true)
        var current = true
        // The identity flips while the page request is in flight: a pre-fetch check alone would pass.
        h.network.onPageRequested = { current = false }
        h.loader.loadOnEnter(partition, "channel-1") { current }
        assertEquals(listOf("channels/channel-1", "messages/channel/channel-1"), h.network.paths)
        assertEquals(0, h.store.snapshots.size)
        assertTrue(h.logs.any { "outcome=identity_changed" in it }, h.logs.toString())
    }

    @Test
    fun partitionClearedDuringTheFetchIsRefusedByTheEngine() = runBlocking {
        val h = harness(flagOn = true)
        // Sign-out while the page request is in flight bumps the partition generation.
        h.network.onPageRequested = { h.engine.clearPartition(partition) }
        h.loader.loadOnEnter(partition, "channel-1") { true }
        assertEquals(0, h.store.snapshots.size)
        assertTrue(h.logs.any { "outcome=partition_revoked" in it }, h.logs.toString())
    }

    @Test
    fun nonTopLevelChannelOrThinPageWritesNothing() = runBlocking {
        val thread = harness(flagOn = true, channelType = "thread")
        thread.loader.loadOnEnter(partition, "channel-1") { true }
        assertEquals(listOf("channels/channel-1"), thread.network.paths, "no page request for a non-top-level scope")
        assertEquals(0, thread.store.snapshots.size)
        assertTrue(thread.logs.any { "outcome=canonical_v2_scope_not_top_level" in it }, thread.logs.toString())

        val thin = harness(flagOn = true, pageBody = canonicalV2TestThinPage())
        thin.loader.loadOnEnter(partition, "channel-1") { true }
        assertEquals(0, thin.store.snapshots.size)
        assertTrue(thin.logs.any { "outcome=canonical_v2_snapshot_not_full_v6" in it }, thin.logs.toString())
    }

    @Test
    fun concurrentEntriesOfTheSameChannelFetchAndWriteOnce() = runBlocking {
        val h = harness(flagOn = true)
        val gate = CompletableDeferred<Unit>()
        h.network.pageGate = gate
        val first = launch { h.loader.loadOnEnter(partition, "channel-1") { true } }
        val second = launch { h.loader.loadOnEnter(partition, "channel-1") { true } }
        yield()
        assertTrue(h.logs.any { "outcome=in_flight" in it }, h.logs.toString())
        gate.complete(Unit)
        joinAll(first, second)
        assertEquals(listOf("channels/channel-1", "messages/channel/channel-1"), h.network.paths)
        assertEquals(1, h.store.snapshots.size)

        // Positive control: a later entry, after the first finished, fetches again (every entry refreshes).
        h.loader.loadOnEnter(partition, "channel-1") { true }
        assertEquals(2, h.store.snapshots.size)
    }

    private class Harness(
        val network: RecordingNetworkClient,
        val store: RecordingStore,
        val engine: CanonicalMessageV2GenerationEngine,
        val canary: CanonicalMessageV2ProductionCanary,
        val loader: CanonicalMessageV2EnteredScopeLoader,
        val logs: MutableList<String>,
    )

    private fun harness(
        flagOn: Boolean,
        channelIds: Set<String> = setOf("channel-1"),
        channelType: String = "channel",
        pageBody: String = canonicalV2TestPage(),
        threadSummariesBody: String? = "{}",
    ): Harness {
        val network = RecordingNetworkClient(
            pageBody,
            channelIds = channelIds,
            channelType = channelType,
            threadSummariesBody = threadSummariesBody,
        )
        val store = RecordingStore()
        val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        val canary = CanonicalMessageV2ProductionCanary(isEnabledForServer = { flagOn }, generationEngine = engine)
        val logs = mutableListOf<String>()
        val loader = CanonicalMessageV2EnteredScopeLoader(
            canary = canary,
            generationEngine = engine,
            fetcher = CanonicalMessageV2RestSnapshotFetcher(network, logs::add),
            isEnabledForServer = { flagOn },
            logger = logs::add,
        )
        return Harness(network, store, engine, canary, loader, logs)
    }

    private val partition = canonicalV2TestGrant().durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)

    private fun foldInput(channelId: String) = CanonicalMessageFoldInput(
        kind = CanonicalMessageFoldKind.New,
        row = CanonicalMessageRow(
            id = "message-1",
            channelId = channelId,
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
}
