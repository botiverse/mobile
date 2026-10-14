package build.raft.app.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.network.sync.CanonicalMessageV2BaselineCoordinator
import build.raft.app.network.sync.CanonicalMessageV2DurableLastKnownReader
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2RetentionPolicy
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SqlDelightCanonicalMessageV2Store
import build.raft.app.network.sync.ThreadDurablePageDirection
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Task #168 step 1 (option A) on the real SQLite schema: the disconnect catch-up's latest page either
 * extends the stored window's newest edge (it overlaps the window) or replaces the window
 * (`window_replaced`). The offline reader — a fresh store over the same database, i.e. a cold start —
 * always sees one contiguous window, and online older paging after a replacement grows it back with
 * no hole.
 */
class CanonicalMessageV2GapCatchUpRoundTripTest {
    @Test
    fun smallGapExtendsTheNewestEdgeWithNoHole() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.enter(listOf(10L, 20L, 30L))
            // The latest page still holds stored message m30: the missing m40/m50 attach at the top.
            rig.latest(pageOf(20L, 30L, 40L, 50L), limit = 200)

            val frame = assertNotNull(rig.coldStartReader().read(partition, "channel-1"))
            assertEquals(listOf(10L, 20L, 30L, 40L, 50L), frame.envelopes.map { it.fact.seq })
            assertEquals(10L, frame.cursor.oldestLoadedSeq)
            assertEquals(50L, frame.cursor.newestLoadedSeq)
            assertEquals(50L, frame.cursor.latestSeq)
            assertTrue(frame.cursor.hasLoadedNewest)
            assertTrue(rig.logs.any { "canonical_v2_latest_page" in it && "outcome=extended committed facts=4" in it }, rig.logs.toString())
            assertTrue(rig.logs.none { "window_replaced" in it }, rig.logs.toString())
        }
    }

    @Test
    fun largeGapReplacesTheWindowAndColdStartShowsTheNewestPage() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.enter(listOf(10L, 20L, 30L))
            // The gap is wider than the page: no stored message is on it.
            rig.latest(pageOf(100L, 110L, 120L), limit = 3)

            val frame = assertNotNull(rig.coldStartReader().read(partition, "channel-1"))
            assertEquals(listOf(100L, 110L, 120L), frame.envelopes.map { it.fact.seq }, "old facts are gone")
            assertEquals(100L, frame.cursor.oldestLoadedSeq)
            assertEquals(120L, frame.cursor.newestLoadedSeq)
            assertEquals(false, frame.cursor.hasLoadedOldest, "a full page says nothing about older history")
            assertTrue(frame.cursor.hasLoadedNewest)
            val replaced = rig.logs.single { "window_replaced" in it }
            assertTrue("old=10..30 oldFacts=3 new=100..120 committed facts=3" in replaced, replaced)

            // Online scroll-up after the replacement: the older page anchored on the new window's oldest
            // message is adjacent, so the #2980 writer grows the window back with no hole.
            rig.extend(ThreadDurablePageDirection.Older, anchorSeq = 100, pageOf(70L, 80L, 90L), limit = 3)
            val grown = assertNotNull(rig.coldStartReader().read(partition, "channel-1"))
            assertEquals(listOf(70L, 80L, 90L, 100L, 110L, 120L), grown.envelopes.map { it.fact.seq })
            assertEquals(70L, grown.cursor.oldestLoadedSeq)
            assertEquals(120L, grown.cursor.newestLoadedSeq)
        }
    }

    @Test
    fun anchorOnPageExtendsAndAnchorAbsentReplacesUnderIdenticalSeqArithmetic() {
        // Twin pair: the same stored window (10, 20, 30) and the same page seqs (30, 40, 50) with the
        // same limit — every count and seq comparison is identical. Only the id at seq 30 differs:
        // the stored message m30 is on page A and absent from page B. Only code that reads the join
        // fact (a stored message is on the page) keeps the old window in A and drops it in B.
        val extended = withFreshDatabase { database ->
            val rig = Rig(database)
            runBlocking {
                rig.enter(listOf(10L, 20L, 30L))
                rig.latest(adjacentPageOf(node("m30", 30), node("m40", 40), node("m50", 50)), limit = 200)
                assertNotNull(rig.coldStartReader().read(partition, "channel-1")).envelopes.map { it.fact.id }
            }
        }
        val replaced = withFreshDatabase { database ->
            val rig = Rig(database)
            runBlocking {
                rig.enter(listOf(10L, 20L, 30L))
                rig.latest(adjacentPageOf(node("n30", 30), node("m40", 40), node("m50", 50)), limit = 200)
                assertNotNull(rig.coldStartReader().read(partition, "channel-1")).envelopes.map { it.fact.id }
            }
        }
        assertEquals(listOf("m10", "m20", "m30", "m40", "m50"), extended)
        assertEquals(listOf("n30", "m40", "m50"), replaced)
    }

    @Test
    fun aRefusedPageLeavesTheStoredWindowUnchanged() = withFreshDatabase { database ->
        // The page carries a thread parent and the reply-count lookup fails: the conversion is refused
        // as a whole, before the scope writer — the window on disk is exactly what it was.
        val rig = Rig(database, threadSummariesBody = null)
        runBlocking {
            rig.enter(listOf(10L, 20L, 30L))
            val before = assertNotNull(rig.coldStartReader().read(partition, "channel-1"))
            rig.latest(adjacentPageOf(node("m100", 100, threadId = "thread-100"), node("m110", 110)), limit = 2)
            val after = assertNotNull(rig.coldStartReader().read(partition, "channel-1"))
            assertEquals(before.envelopes.map { it.fact.id }, after.envelopes.map { it.fact.id })
            assertEquals(before.cursor, after.cursor)
            assertTrue(rig.logs.last().endsWith("outcome=canonical_v2_thread_summaries_unavailable"), rig.logs.toString())
        }
    }

    private class Rig(private val database: SlockSqlDatabase, threadSummariesBody: String? = "{}") {
        val logs = mutableListOf<String>()
        private val network = RecordingNetworkClient(canonicalV2TestPage(), threadSummariesBody = threadSummariesBody)
        private val store = SqlDelightCanonicalMessageV2Store(
            database = database,
            now = { 1L },
            retention = CanonicalMessageV2RetentionPolicy(maxFactsPerScope = 500),
        )
        private val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        private val canary = CanonicalMessageV2ProductionCanary(isEnabledForServer = { true }, generationEngine = engine)
        private val writer = CanonicalMessageV2AdjacentPageWriter(
            canary = canary,
            generationEngine = engine,
            fetcher = CanonicalMessageV2RestSnapshotFetcher(network, logs::add),
            isEnabledForServer = { true },
            logger = logs::add,
        )

        /** A reader over a NEW store instance on the same database: what an offline cold start reads. */
        fun coldStartReader() =
            CanonicalMessageV2DurableLastKnownReader(
                SqlDelightCanonicalMessageV2Store(database = database, now = { 2L }),
                isEnabledForServer = { true },
            )

        suspend fun enter(seqs: List<Long>) {
            val body = """{"messages":${pageOf(*seqs.toLongArray())},"historyLimited":false}"""
            CanonicalMessageV2EnteredScopeLoader(
                canary = canary,
                generationEngine = engine,
                fetcher = CanonicalMessageV2RestSnapshotFetcher(RecordingNetworkClient(body)),
                isEnabledForServer = { true },
            ).loadOnEnter(partition, "channel-1") { true }
        }

        suspend fun latest(rawMessages: JsonArray, limit: Int) =
            writer.applyLatestPage(partition, "channel-1", rawMessages, limit, replyParent = null) { true }

        suspend fun extend(direction: ThreadDurablePageDirection, anchorSeq: Long, rawMessages: JsonArray, limit: Int) =
            writer.extendWithAdjacentPage(partition, "channel-1", direction, anchorSeq, rawMessages, limit, replyParent = null) { true }
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

        fun node(id: String, seq: Long, threadId: String? = null): String = adjacentPageNode(id, seq, threadId = threadId)

        fun pageOf(vararg seqs: Long): JsonArray = adjacentPageOf(*seqs.map { node("m$it", it) }.toTypedArray())
    }
}
