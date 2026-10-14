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
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.CanonicalMessageV2SnapshotFrame
import build.raft.app.network.sync.CanonicalMessageV2SnapshotLoadResult
import build.raft.app.network.sync.CanonicalMessageV2SnapshotLoader
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Task #168 device FAIL (build 33, canary channel #all): the enter snapshot, adjacent-page and gap
 * catch-up writers skipped the realtime canary's scope ("the canary owns it"), but the canary only writes
 * realtime frames — the catch-up page after 210 offline messages never reached disk and the offline cold
 * start showed the pre-disconnect page. On the real SQLite store:
 * - the three writers now write the canary's scope exactly like any other scope;
 * - after such an external commit the canary's next realtime frame re-snapshots once instead of appending
 *   onto a window it did not produce;
 * - a canary background snapshot still pending at that moment is superseded by an explicit signal (cancel +
 *   one fresh snapshot), so its older page never overwrites the newer external window.
 */
class CanonicalMessageV2CanaryExternalCommitRoundTripTest {
    // ---- gap catch-up -------------------------------------------------------------------------------

    @Test
    fun aCanarySelectedScopesLargeGapCatchUpReplacesTheWindowOnDisk() {
        val selected = withFreshDatabase { database ->
            val rig = Rig(database)
            runBlocking {
                rig.canaryBaseline(10L, 20L, 30L)
                assertTrue(rig.canary.isSelected(scope))
                rig.latest(pageOf(100L, 110L, 120L), limit = 3)
                rig.assertReplacedTo100To120()
                assertTrue(rig.logs.none { "canary_owned" in it }, rig.logs.toString())
                rig.coldStartSeqs()
            }
        }
        // Twin: the same catch-up on a scope the canary never selected (window from the enter snapshot).
        val notSelected = withFreshDatabase { database ->
            val rig = Rig(database)
            runBlocking {
                rig.enter(pageOf(10L, 20L, 30L))
                assertFalse(rig.canary.isSelected(scope))
                rig.latest(pageOf(100L, 110L, 120L), limit = 3)
                rig.assertReplacedTo100To120()
                rig.coldStartSeqs()
            }
        }
        assertEquals(listOf(100L, 110L, 120L), selected)
        assertEquals(selected, notSelected)
    }

    @Test
    fun afterTheCatchUpTheNextSameEpochFrameReSnapshotsOnceWithNoHole() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.canaryBaseline(10L, 20L, 30L)
            rig.latest(pageOf(100L, 110L, 120L), limit = 3)
            val loadsBefore = rig.loaderCalls

            // Same epoch as the canary's last commit: without the external-commit mark this would append
            // onto the catch-up window (Committed, no load).
            rig.serverHas(100L, 110L, 120L, 130L)
            assertEquals(CanonicalMessageV2CanaryOutcome.Pending, rig.accept(130L))
            assertEquals(loadsBefore + 1, rig.loaderCalls, "exactly one re-snapshot")
            assertEquals(listOf(100L, 110L, 120L, 130L), rig.coldStartSeqs())
            val cursor = assertNotNull(rig.coldStartCursor())
            assertEquals(100L, cursor.oldestLoadedSeq)
            assertEquals(130L, cursor.newestLoadedSeq)

            // The canary owns continuity again: the next frame appends without a load.
            rig.serverHas(100L, 110L, 120L, 130L, 140L)
            assertEquals(CanonicalMessageV2CanaryOutcome.Committed, rig.accept(140L))
            assertEquals(loadsBefore + 1, rig.loaderCalls)
            assertEquals(listOf(100L, 110L, 120L, 130L, 140L), rig.coldStartSeqs())
        }
    }

    // ---- enter snapshot / adjacent page -------------------------------------------------------------

    @Test
    fun anEnterSnapshotOnTheCanarysScopeIsWrittenAndTheNextFrameReSnapshots() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.canaryBaseline(10L, 20L, 30L)
            val snapshotsBefore = rig.store.snapshotWrites
            rig.enter(pageOf(50L, 60L))
            assertEquals(snapshotsBefore + 1, rig.store.snapshotWrites)
            assertEquals(listOf(50L, 60L), rig.coldStartSeqs())
            assertTrue(rig.logs.any { "canonical_v2_enter_snapshot" in it && "outcome=committed facts=2" in it }, rig.logs.toString())
            assertTrue(rig.logs.none { "canary_owned" in it }, rig.logs.toString())

            val loadsBefore = rig.loaderCalls
            rig.serverHas(50L, 60L, 70L)
            assertEquals(CanonicalMessageV2CanaryOutcome.Pending, rig.accept(70L))
            assertEquals(loadsBefore + 1, rig.loaderCalls)
            assertEquals(listOf(50L, 60L, 70L), rig.coldStartSeqs())
        }
    }

    @Test
    fun anAdjacentPageOnTheCanarysScopeIsWrittenAndTheNextFrameReSnapshots() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.canaryBaseline(10L, 20L, 30L)
            val incrementalsBefore = rig.store.incrementalWrites
            rig.extend(ThreadDurablePageDirection.Older, anchorSeq = 10L, pageOf(5L, 8L), limit = 3)
            assertEquals(incrementalsBefore + 1, rig.store.incrementalWrites)
            assertEquals(listOf(5L, 8L, 10L, 20L, 30L), rig.coldStartSeqs())
            assertEquals(5L, rig.coldStartCursor()?.oldestLoadedSeq)
            assertTrue(rig.logs.any { "canonical_v2_adjacent_page" in it && "outcome=committed facts=2" in it }, rig.logs.toString())
            assertTrue(rig.logs.none { "canary_owned" in it }, rig.logs.toString())

            val loadsBefore = rig.loaderCalls
            rig.serverHas(5L, 8L, 10L, 20L, 30L, 40L)
            assertEquals(CanonicalMessageV2CanaryOutcome.Pending, rig.accept(40L))
            assertEquals(loadsBefore + 1, rig.loaderCalls)
            assertEquals(listOf(5L, 8L, 10L, 20L, 30L, 40L), rig.coldStartSeqs())
        }
    }

    // ---- a pending canary snapshot vs. an external commit -------------------------------------------

    @Test
    fun anExternalCommitSupersedesAPendingCanarySnapshotWhichNeverOverwritesIt() = withFreshDatabase { database ->
        val rig = Rig(database)
        bounded {
            rig.canaryBaseline(10L, 20L, 30L)
            val loadsAfterBaseline = rig.loaderCalls

            // Reconnect as epoch 2: m200 schedules a snapshot that reads the server NOW (180..200) and is
            // held. The load ignores cancellation, so its stale result does reach the scope writer.
            rig.canary.noteTransportEpoch(partition, 2L)
            rig.serverHas(180L, 190L, 200L)
            val hold = rig.holdNextSnapshot(ignoresCancellation = true)
            assertEquals(CanonicalMessageV2CanaryOutcome.Pending, rig.accept(200L, settle = false))
            hold.started.await()
            // m210 arrives while it loads: displayed and buffered.
            rig.serverHas(180L, 190L, 200L, 210L)
            assertEquals(CanonicalMessageV2CanaryOutcome.Pending, rig.accept(210L, settle = false))

            // The gap catch-up fetched a newer page and commits while that snapshot is still pending.
            rig.serverHas(190L, 200L, 210L, 220L)
            rig.latest(pageOf(190L, 200L, 210L, 220L), limit = 4)
            assertTrue(rig.logs.any { "canonical_v2_latest_page" in it && "window_replaced" in it }, rig.logs.toString())

            // The old load returns now, after the newer window: it must be dropped by the explicit signal.
            hold.release.complete(Unit)
            rig.settle()

            val seqs = rig.coldStartSeqs()
            assertFalse(180L in seqs, "the older pending snapshot never overwrote the external window: $seqs")
            // Nothing lost: the trigger m200, the buffered m210 and the catch-up's m220 are all on disk.
            assertEquals(listOf(190L, 200L, 210L, 220L), seqs)
            assertTrue(
                rig.logs.any { "canonical_v2_canary external_commit" in it && "superseded=true" in it },
                rig.logs.toString(),
            )
            assertTrue(rig.logs.any { "canonical_v2_canary snapshot=stale_dropped" in it }, rig.logs.toString())
            assertEquals(220L, rig.coldStartCursor()?.newestLoadedSeq)
            assertEquals(loadsAfterBaseline + 2, rig.loaderCalls, "the superseded load plus at most one fresh snapshot")

            // The fresh snapshot committed under epoch 2: the next epoch-2 frame appends, no further load.
            rig.serverHas(190L, 200L, 210L, 220L, 230L)
            assertEquals(CanonicalMessageV2CanaryOutcome.Committed, rig.accept(230L))
            assertEquals(loadsAfterBaseline + 2, rig.loaderCalls)
            assertEquals(listOf(190L, 200L, 210L, 220L, 230L), rig.coldStartSeqs())
        }
    }

    // ---- rig ----------------------------------------------------------------------------------------

    private fun bounded(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(30_000L) { block() }
    }

    private class SnapshotHold(val ignoresCancellation: Boolean) {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    private class Rig(private val database: SlockSqlDatabase) {
        val logs: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
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
            fetcher = CanonicalMessageV2RestSnapshotFetcher(RecordingNetworkClient(canonicalV2TestPage()), { logs += it }),
            isEnabledForServer = { true },
            logger = { logs += it },
        )

        /** The canary snapshot's answer: what the server holds when the load starts. */
        @Volatile private var serverSeqs: List<Long> = emptyList()

        @Volatile private var nextHold: SnapshotHold? = null

        /** Read after [settle] or while a load is held. */
        @Volatile var loaderCalls = 0

        private val loader = CanonicalMessageV2SnapshotLoader { request ->
            loaderCalls += 1
            val hold = nextHold.also { nextHold = null }
            val seqs = serverSeqs
            if (hold != null) {
                hold.started.complete(Unit)
                if (hold.ignoresCancellation) withContext(NonCancellable) { hold.release.await() } else hold.release.await()
            }
            CanonicalMessageV2SnapshotLoadResult.Loaded(
                CanonicalMessageV2SnapshotFrame(
                    scope = request.scope,
                    envelopes = seqs.map { envelope(it) },
                    cursor = PersistedCursor(
                        latestSeq = seqs.max(),
                        oldestLoadedSeq = seqs.min(),
                        newestLoadedSeq = seqs.max(),
                        hasLoadedOldest = false,
                        hasLoadedNewest = true,
                    ),
                ),
            )
        }

        fun serverHas(vararg seqs: Long) {
            serverSeqs = seqs.toList()
        }

        fun holdNextSnapshot(ignoresCancellation: Boolean = false): SnapshotHold =
            SnapshotHold(ignoresCancellation).also { nextHold = it }

        suspend fun settle() = canary.awaitSnapshotJobs()

        /** The canary selects channel-1 and commits its first snapshot under epoch 1. */
        suspend fun canaryBaseline(vararg seqs: Long) {
            canary.noteTransportEpoch(partition, 1L)
            serverHas(*seqs)
            assertEquals(CanonicalMessageV2CanaryOutcome.Pending, accept(seqs.max()))
            assertEquals(seqs.toList(), coldStartSeqs())
        }

        /** A realtime `New` of `m<seq>` (frame epoch = the recorded one). */
        suspend fun accept(seq: Long, settle: Boolean = true): CanonicalMessageV2CanaryOutcome =
            canary.accept(partition, input(seq), loader).also { if (settle) settle() }

        suspend fun enter(rawMessages: JsonArray) {
            val body = """{"messages":$rawMessages,"historyLimited":false}"""
            CanonicalMessageV2EnteredScopeLoader(
                canary = canary,
                generationEngine = engine,
                fetcher = CanonicalMessageV2RestSnapshotFetcher(RecordingNetworkClient(body)),
                isEnabledForServer = { true },
                logger = { logs += it },
            ).loadOnEnter(partition, "channel-1") { true }
        }

        suspend fun latest(rawMessages: JsonArray, limit: Int) =
            writer.applyLatestPage(partition, "channel-1", rawMessages, limit, replyParent = null) { true }

        suspend fun extend(direction: ThreadDurablePageDirection, anchorSeq: Long, rawMessages: JsonArray, limit: Int) =
            writer.extendWithAdjacentPage(partition, "channel-1", direction, anchorSeq, rawMessages, limit, replyParent = null) { true }

        /** A reader over a NEW store instance on the same database: what an offline cold start reads. */
        private fun coldStartReader() =
            CanonicalMessageV2DurableLastKnownReader(
                SqlDelightCanonicalMessageV2Store(database = database, now = { 2L }),
                isEnabledForServer = { true },
            )

        suspend fun coldStartSeqs(): List<Long> =
            coldStartReader().read(partition, "channel-1")?.envelopes.orEmpty().map { it.fact.seq }.sorted()

        suspend fun coldStartCursor(): PersistedCursor? = coldStartReader().read(partition, "channel-1")?.cursor

        fun assertReplacedTo100To120() {
            val replaced = logs.single { "canonical_v2_latest_page" in it && "window_replaced" in it }
            assertTrue("old=10..30 oldFacts=3 new=100..120 committed facts=3" in replaced, replaced)
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
    }

    private fun <T> withFreshDatabase(block: (SlockSqlDatabase) -> T): T {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            SlockSqlDatabase.Schema.create(driver).value
            return block(SlockSqlDatabase(driver))
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
