package build.raft.app.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.MessageAggregateField
import build.raft.app.network.sync.CanonicalMessageFoldInput
import build.raft.app.network.sync.CanonicalMessageFoldKind
import build.raft.app.network.sync.CanonicalMessageV2BaselineCoordinator
import build.raft.app.network.sync.CanonicalMessageV2CanaryOutcome
import build.raft.app.network.sync.CanonicalMessageV2DurableLastKnownReader
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2IncrementalFrame
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2RetentionPolicy
import build.raft.app.network.sync.CanonicalMessageV2ScopeContinuity
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.CanonicalMessageV2SnapshotFrame
import build.raft.app.network.sync.CanonicalMessageV2Store
import build.raft.app.network.sync.CanonicalMessageV2StoreWriteResult
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.PersistedCursor
import build.raft.app.network.sync.RestoredCanonicalMessageV2Scope
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SqlDelightCanonicalMessageV2Store
import build.raft.app.network.sync.SyncScopeId
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.ThreadDurablePageDirection
import build.raft.app.network.sync.toCanonicalMessageV2Envelope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task #168 device FAIL (build 33, canary channel #all): the enter snapshot, adjacent-page and gap
 * catch-up writers skipped the realtime canary's scope ("the canary owns it"), but the canary only writes
 * realtime frames — the catch-up page after 210 offline messages never reached disk and the offline cold
 * start showed the pre-disconnect page. On the real SQLite store:
 * - the three writers write a realtime window exactly like any other scope;
 * - task #168 step 3: an older page keeps the window's realtime continuity (it only moves the oldest edge);
 *   after a newer page / catch-up commit (#3008 kept) the window is display only for realtime frames — no
 *   background re-snapshot — until the next enter snapshot makes it continuous again;
 * - an external commit while an enter snapshot is in flight keeps that enter's window display only.
 */
class CanonicalMessageV2CanaryExternalCommitRoundTripTest {
    // ---- gap catch-up -------------------------------------------------------------------------------

    @Test
    fun aRealtimeWindowsLargeGapCatchUpKeepsTheOldWindowAsAnIslandOnDisk() {
        val continuous = withFreshDatabase { database ->
            val rig = Rig(database)
            runBlocking {
                rig.enter(pageOf(10L, 20L, 30L))
                assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, rig.canary.continuityOf(scope))
                rig.latest(pageOf(100L, 110L, 120L), limit = 3)
                rig.assertIslandKeptAndHeadIs100To120()
                assertTrue(rig.logs.none { "canary_owned" in it }, rig.logs.toString())
                rig.coldStartSeqs()
            }
        }
        // Twin: the same catch-up on a window the canary holds no state for (restored after a cold start).
        val restored = withFreshDatabase { database ->
            val seeding = Rig(database)
            runBlocking { seeding.enter(pageOf(10L, 20L, 30L)) }
            val rig = Rig(database)
            runBlocking {
                assertEquals(CanonicalMessageV2ScopeContinuity.NoWindow, rig.canary.continuityOf(scope))
                rig.latest(pageOf(100L, 110L, 120L), limit = 3)
                rig.assertIslandKeptAndHeadIs100To120()
                rig.coldStartSeqs()
            }
        }
        assertEquals(listOf(100L, 110L, 120L), continuous)
        assertEquals(continuous, restored)
    }

    @Test
    fun afterTheCatchUpTheWindowIsDisplayOnlyUntilTheNextEnterWithoutAnyLoad() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.connect(1L)
            rig.enter(pageOf(10L, 20L, 30L))
            assertEquals(CanonicalMessageV2CanaryOutcome.Committed, rig.accept(40L))
            rig.latest(pageOf(100L, 110L, 120L), limit = 3)
            val paths = rig.networkPaths()

            // Same epoch as the baseline: without the external-commit mark this would append onto the
            // catch-up window (Committed).
            assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, rig.accept(130L))
            assertEquals(listOf(100L, 110L, 120L), rig.coldStartSeqs())
            assertEquals(paths, rig.networkPaths(), "no re-snapshot (task #168 step 3)")
            assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, rig.canary.continuityOf(scope))

            // The user opens the channel: continuous again, the next frame appends. Its short page (4 < limit)
            // proves nothing exists below it, so the older stored messages join the head range again.
            rig.enter(pageOf(100L, 110L, 120L, 130L))
            assertEquals(CanonicalMessageV2CanaryOutcome.Committed, rig.accept(140L))
            assertEquals(listOf(10L, 20L, 30L, 40L, 100L, 110L, 120L, 130L, 140L), rig.coldStartSeqs())
        }
    }

    // ---- enter snapshot / adjacent page -------------------------------------------------------------

    @Test
    fun anEnterSnapshotOnAContinuousWindowIsWrittenAndStaysContinuous() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.enter(pageOf(10L, 20L, 30L))
            val snapshotsBefore = rig.store.snapshotWrites
            rig.enter(pageOf(50L, 60L))
            assertEquals(snapshotsBefore + 1, rig.store.snapshotWrites)
            // Phase 2a: the second enter adds its page; its short page reaches the oldest, so the first page's
            // messages are inside the same head range (never dropped by a rebaseline).
            assertEquals(listOf(10L, 20L, 30L, 50L, 60L), rig.coldStartSeqs())
            assertTrue(rig.logs.any { it.startsWith("canonical_v2_enter_snapshot ") && "outcome=committed facts=2" in it }, rig.logs.toString())
            assertTrue(rig.logs.none { "canary_owned" in it }, rig.logs.toString())

            assertEquals(CanonicalMessageV2CanaryOutcome.Committed, rig.accept(70L))
            assertEquals(listOf(10L, 20L, 30L, 50L, 60L, 70L), rig.coldStartSeqs())
        }
    }

    @Test
    fun anOlderPageOnARealtimeWindowIsWrittenAndTheWindowKeepsAppending() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.connect(1L)
            rig.enter(pageOf(10L, 20L, 30L))
            val incrementalsBefore = rig.store.incrementalWrites
            rig.extend(ThreadDurablePageDirection.Older, anchorSeq = 10L, pageOf(5L, 8L), limit = 3)
            assertEquals(incrementalsBefore + 1, rig.store.incrementalWrites)
            assertEquals(listOf(5L, 8L, 10L, 20L, 30L), rig.coldStartSeqs())
            assertEquals(5L, rig.coldStartCursor()?.oldestLoadedSeq)
            assertTrue(rig.logs.any { it.startsWith("canonical_v2_adjacent_page ") && "outcome=committed facts=2" in it }, rig.logs.toString())
            assertTrue(rig.logs.none { "canary_owned" in it }, rig.logs.toString())

            // Task #168: the viewed channel keeps its realtime cache while the user scrolls up.
            val paths = rig.networkPaths()
            assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, rig.canary.continuityOf(scope))
            assertEquals(CanonicalMessageV2CanaryOutcome.Committed, rig.accept(40L))
            assertEquals(listOf(5L, 8L, 10L, 20L, 30L, 40L), rig.coldStartSeqs())
            assertEquals(5L, rig.coldStartCursor()?.oldestLoadedSeq, "the append kept the page's oldest edge")
            assertEquals(paths, rig.networkPaths())
        }
    }

    @Test
    fun aNewerPageOnARealtimeWindowIsWrittenAndTheWindowBecomesDisplayOnly() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.connect(1L)
            rig.enter(pageOf(10L, 20L, 30L))
            rig.extend(ThreadDurablePageDirection.Newer, anchorSeq = 30L, pageOf(35L, 38L), limit = 3)
            assertEquals(listOf(10L, 20L, 30L, 35L, 38L), rig.coldStartSeqs())
            assertTrue(rig.logs.any { it.startsWith("canonical_v2_adjacent_page ") && "outcome=committed facts=2" in it }, rig.logs.toString())

            // Same epoch, same next frame as the older twin: the newest edge moved, so display only.
            val paths = rig.networkPaths()
            assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, rig.canary.continuityOf(scope))
            assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, rig.accept(40L))
            assertEquals(listOf(10L, 20L, 30L, 35L, 38L), rig.coldStartSeqs())
            assertEquals(paths, rig.networkPaths())
        }
    }

    @Test
    fun aCatchUpCommittedWhileAnEnterLoadsKeepsThatEntersWindowDisplayOnly() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.enter(pageOf(10L, 20L, 30L))
            val gate = CompletableDeferred<Unit>()
            val pagesBefore = rig.networkPaths().count { it == "messages/channel/channel-1" }
            val entering = async { rig.enter(pageOf(10L, 20L, 30L, 40L), gate) }
            while (rig.networkPaths().count { it == "messages/channel/channel-1" } == pagesBefore) yield()
            // The enter's page is in flight; the gap catch-up commits a newer page meanwhile.
            rig.latest(pageOf(30L, 40L, 50L), limit = 3)
            gate.complete(Unit)
            entering.await()

            assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, rig.canary.continuityOf(scope))
            assertTrue(rig.logs.any { "reason=external_commit" in it }, rig.logs.toString())
            assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, rig.accept(60L))

            // Twin: the same enter without a commit in between is continuous.
            rig.enter(pageOf(10L, 20L, 30L, 40L, 50L))
            assertEquals(CanonicalMessageV2CanaryOutcome.Committed, rig.accept(60L))
        }
    }

    @Test
    fun anEnterWhosePageProvesNoRangeIsNoRealtimeBaseline() {
        // Message storage Phase 2a. Pair: the same second enter (rows 100..120 over a stored 10..30) whose server
        // window is usable ([100, 120]) or names another channel (no claim: the rows are stored loose). Only the
        // proven page may become the realtime baseline; the unproven one must not continue the old head past
        // the hole between 30 and 100.
        fun run(windowScopeId: String) = withFreshDatabase { database ->
            val rig = Rig(database)
            runBlocking {
                rig.enter(pageOf(10L, 20L, 30L))
                rig.enter(pageOf(100L, 110L, 120L), window = channelWindow(99, 120, scopeId = windowScopeId))
                Triple(rig.canary.continuityOf(scope), rig.accept(130L), rig.store.readSegments(scope).map { it.lowSeq to it.highSeq })
            }
        }
        assertEquals(
            Triple(CanonicalMessageV2ScopeContinuity.Continuous, CanonicalMessageV2CanaryOutcome.Committed, listOf(10L to 30L, 100L to 130L)),
            run("channel-1"),
        )
        val (continuity, outcome, segments) = run("channel-2")
        assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, continuity)
        assertEquals(CanonicalMessageV2CanaryOutcome.NotSelected, outcome)
        assertEquals(listOf(10L to 30L), segments, "no claim over the hole")
    }

    // ---- log lines --------------------------------------------------------------------------------------

    @Test
    fun theLatestPageLineHasExactlyOnePrefixThroughTheProductionLogger() = withFreshDatabase { database ->
        val lines = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        // The provider's sink for the paging writer (device: `canonical_v2_adjacent_page canonical_v2_latest_page …`).
        val logger = canonicalMessageV2SelfTaggedLogger("CanonicalMessageV2AdjacentPage") { tag, message -> lines += tag to message }
        val rig = Rig(database, writerLogger = logger)
        runBlocking {
            rig.enter(pageOf(10L, 20L, 30L))
            rig.latest(pageOf(100L, 110L, 120L), limit = 3)
        }
        val (tag, line) = lines.single { "island_kept" in it.second }
        assertEquals("CanonicalMessageV2AdjacentPage", tag)
        assertTrue(line.startsWith("canonical_v2_latest_page server=server-1 scope=channel-1 outcome=island_kept "), line)
        assertFalse("canonical_v2_adjacent_page" in line, line)
    }

    // ---- rig ----------------------------------------------------------------------------------------

    private fun bounded(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(30_000L) { block() }
    }

    private class Rig(
        private val database: RaftSqlDatabase,
        writerLogger: ((String) -> Unit)? = null,
    ) {
        val logs: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        private val networks = java.util.Collections.synchronizedList(mutableListOf<RecordingNetworkClient>())
        val store = CountingStore(
            SqlDelightCanonicalMessageV2Store(
                database = database,
                now = { 1L },
                retention = CanonicalMessageV2RetentionPolicy(maxFactsPerScope = 500),
            ),
        )
        private val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        val canary = CanonicalMessageV2ProductionCanary(
            isEnabledForServer = { true },
            generationEngine = engine,
            logger = { logs += it },
        )
        private val writer = CanonicalMessageV2AdjacentPageWriter(
            canary = canary,
            generationEngine = engine,
            fetcher = CanonicalMessageV2RestSnapshotFetcher(
                RecordingNetworkClient(canonicalV2TestPage()).also { networks += it },
                { logs += it },
            ),
            isEnabledForServer = { true },
            logger = writerLogger ?: { logs += it },
        )

        /** A realtime `New` of `m<seq>` (frame epoch = the recorded one). */
        suspend fun accept(seq: Long): CanonicalMessageV2CanaryOutcome = canary.accept(partition, input(seq))

        private var connected = false

        /** The socket (re)connected as [epoch] and its `rooms:joined` arrived. */
        suspend fun connect(epoch: Long) {
            canary.noteTransportEpoch(partition, epoch)
            canary.noteRoomsJoined(partition, epoch)
            connected = true
        }

        /** The user opens channel-1 and the server answers [rawMessages] (after [gate], when given). */
        suspend fun enter(rawMessages: JsonArray, gate: CompletableDeferred<Unit>? = null, window: ChannelMessageWindow? = null) {
            if (!connected) connect(1L)
            val windowField = window?.let { ""","messageWindow":${channelWindowJson(it)}""" }.orEmpty()
            val body = """{"messages":$rawMessages,"historyLimited":false$windowField}"""
            val network = RecordingNetworkClient(body).also { it.pageGate = gate; networks += it }
            CanonicalMessageV2EnteredScopeLoader(
                canary = canary,
                generationEngine = engine,
                fetcher = CanonicalMessageV2RestSnapshotFetcher(network),
                isEnabledForServer = { true },
                logger = { logs += it },
            ).loadOnEnter(partition, "channel-1") { true }
        }

        /** Every request made so far by any network client of this rig. */
        fun networkPaths(): List<String> = networks.flatMap { it.paths.toList() }

        suspend fun latest(rawMessages: JsonArray, limit: Int) =
            writer.applyLatestPage(partition, "channel-1", rawMessages, limit, replyParent = null, requestHeadEpoch = null) { true }

        suspend fun extend(direction: ThreadDurablePageDirection, anchorSeq: Long, rawMessages: JsonArray, limit: Int) =
            writer.extendWithAdjacentPage(partition, "channel-1", direction, anchorSeq, rawMessages, limit, replyParent = null, requestHeadEpoch = null) { true }

        /** A reader over a NEW store instance on the same database: what an offline cold start reads. */
        private fun coldStartReader() =
            CanonicalMessageV2DurableLastKnownReader(
                SqlDelightCanonicalMessageV2Store(database = database, now = { 2L }),
                isEnabledForServer = { true },
            )

        suspend fun coldStartSeqs(): List<Long> =
            coldStartReader().read(partition, "channel-1")?.envelopes.orEmpty().map { it.fact.seq }.sorted()

        suspend fun coldStartCursor(): PersistedCursor? = coldStartReader().read(partition, "channel-1")?.cursor

        /** Phase 2a: the old window is not replaced; it stays on disk below the page's head range. */
        suspend fun assertIslandKeptAndHeadIs100To120() {
            val kept = logs.single { "canonical_v2_latest_page" in it && "island_kept" in it }
            assertTrue("new=100..120 committed facts=3" in kept, kept)
            val onDisk = store.restore(scope).envelopes.map { it.fact.seq }.sorted()
            assertEquals(listOf(10L, 20L, 30L, 100L, 110L, 120L), onDisk, "nothing stored is dropped")
        }

        private fun input(seq: Long) = CanonicalMessageFoldInput(
            kind = CanonicalMessageFoldKind.New,
            row = CanonicalMessageRow(
                id = "m$seq",
                channelId = "channel-1",
                seq = seq,
                senderType = "user",
                senderId = "user-1",
                content = "hello m$seq",
                messageType = "chat",
                createdAt = "2026-07-17T00:00:00.000Z",
                senderName = "Alice",
                conversationContext = CanonicalConversationContextWire("channel"),
            ),
            presentAggregates = MessageAggregateField.entries.toList(),
        )

        private fun envelope(seq: Long) =
            requireNotNull(input(seq).toCanonicalMessageV2Envelope(partition, replyCount = 0)) { "fixture must normalize" }
    }

    private class CountingStore(private val delegate: CanonicalMessageV2Store) : CanonicalMessageV2Store {
        @Volatile var snapshotWrites = 0
        @Volatile var incrementalWrites = 0

        override suspend fun applySnapshot(frame: CanonicalMessageV2SnapshotFrame): CanonicalMessageV2StoreWriteResult {
            snapshotWrites += 1
            return delegate.applySnapshot(frame)
        }

        override suspend fun applyIncremental(frame: CanonicalMessageV2IncrementalFrame): CanonicalMessageV2StoreWriteResult {
            incrementalWrites += 1
            return delegate.applyIncremental(frame)
        }

        override suspend fun restore(scope: SyncScopeKey): RestoredCanonicalMessageV2Scope = delegate.restore(scope)
        override suspend fun clearScope(scope: SyncScopeKey): Set<SyncScopeKey> = delegate.clearScope(scope)
        override suspend fun clearPartition(partition: DurablePartitionKey) = delegate.clearPartition(partition)
        override suspend fun knownPartitions(): List<DurablePartitionKey> = delegate.knownPartitions()
        override suspend fun baselinedScopeKinds(partition: DurablePartitionKey, scopeId: SyncScopeId): List<String> =
            delegate.baselinedScopeKinds(partition, scopeId)
        override suspend fun readCursor(scope: SyncScopeKey): PersistedCursor? = delegate.readCursor(scope)
        override suspend fun readSegments(scope: SyncScopeKey) = delegate.readSegments(scope)
        override suspend fun maxLocalRev(partition: DurablePartitionKey): Long = delegate.maxLocalRev(partition)

        /** Since message storage Phase 2a the enter / catch-up page is a coverage write: counted with the snapshots. */
        override suspend fun applyCoverage(
            scope: SyncScopeKey,
            envelopes: List<build.raft.app.network.sync.CanonicalMessageEnvelopeV2>,
            coverage: build.raft.app.network.sync.CanonicalMessageV2Coverage?,
            revAtRequest: Long,
            userDriven: Boolean,
            threadParent: build.raft.app.network.sync.CanonicalMessageV2ThreadParent?,
            insertOnly: Boolean,
            unknownFieldsByFactId: Map<String, Set<build.raft.app.canonical.message.MessageAggregateField>>,
            runPartitionRetention: Boolean,
            excludedSeqs: Set<Long>,
        ): CanonicalMessageV2StoreWriteResult {
            snapshotWrites += 1
            return delegate.applyCoverage(scope, envelopes, coverage, revAtRequest, userDriven, threadParent, insertOnly, unknownFieldsByFactId, runPartitionRetention, excludedSeqs)
        }
    }

    private fun <T> withFreshDatabase(block: (RaftSqlDatabase) -> T): T {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            RaftSqlDatabase.Schema.create(driver).value
            return block(RaftSqlDatabase(driver))
        } finally {
            driver.close()
        }
    }

    private companion object {
        val partition = canonicalV2TestGrant().durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        val scope = SyncScopeKey(partition, "channel-1", "channel")

        fun pageOf(vararg seqs: Long): JsonArray = adjacentPageOf(*seqs.map { adjacentPageNode("m$it", it) }.toTypedArray())
    }
}
