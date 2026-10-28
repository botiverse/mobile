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
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.ThreadDurablePageDirection
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Task #168 step 1 (option A) on the real SQLite schema, since message storage Phase 2a (P1): the disconnect
 * catch-up's latest page — and a second enter — is ADDED with the seq range it proves (the server's
 * `messageWindow`, else the legacy latest-page claim). A page that reaches the stored head extends it
 * (`window_extended`); one that does not becomes the new head and the old window stays on disk as an older island
 * (`island_kept`). The offline reader — a fresh store over the same database, i.e. a cold start — reads the head
 * range only (no gap rows before Phase 2d), and online older paging grows the head with no hole.
 */
class CanonicalMessageV2GapCatchUpRoundTripTest {
    @Test
    fun smallGapExtendsTheNewestEdgeWithNoHole() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.enter(listOf(10L, 20L, 30L))
            // The latest page reaches into the stored window: the missing m40/m50 attach at the top.
            rig.latest(pageOf(20L, 30L, 40L, 50L), limit = 200)

            val frame = assertNotNull(rig.coldStartReader().read(partition, "channel-1"))
            assertEquals(listOf(10L, 20L, 30L, 40L, 50L), frame.envelopes.map { it.fact.seq })
            assertEquals(10L, frame.cursor.oldestLoadedSeq)
            assertEquals(50L, frame.cursor.newestLoadedSeq)
            assertEquals(50L, frame.cursor.latestSeq)
            assertTrue(frame.cursor.hasLoadedNewest)
            assertTrue(rig.logs.any { "canonical_v2_latest_page" in it && "outcome=window_extended new=20..50 committed facts=4" in it }, rig.logs.toString())
            assertTrue(rig.logs.none { "island_kept" in it }, rig.logs.toString())
        }
    }

    @Test
    fun largeGapKeepsTheOldWindowAsAnIslandAndColdStartShowsTheNewestPage() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.enter(listOf(10L, 20L, 30L))
            // The gap is wider than the (full) page: it proves only [100, 120].
            rig.latest(pageOf(100L, 110L, 120L), limit = 3)

            val frame = assertNotNull(rig.coldStartReader().read(partition, "channel-1"))
            assertEquals(listOf(100L, 110L, 120L), frame.envelopes.map { it.fact.seq }, "the head range only")
            assertEquals(listOf(10L, 20L, 30L, 100L, 110L, 120L), rig.onDiskSeqs(), "the old page is kept on disk")
            assertEquals(listOf(10L to 30L, 100L to 120L), rig.segments())
            assertEquals(100L, frame.cursor.oldestLoadedSeq)
            assertEquals(120L, frame.cursor.newestLoadedSeq)
            assertEquals(false, frame.cursor.hasLoadedOldest, "a full page says nothing about older history")
            assertTrue(frame.cursor.hasLoadedNewest)
            val kept = rig.logs.single { "island_kept" in it }
            assertTrue("new=100..120 committed facts=3" in kept, kept)

            // Online scroll-up: the older page anchored on the head's oldest message is adjacent, so the #2980
            // writer grows the head back with no hole; the island below stays apart.
            rig.extend(ThreadDurablePageDirection.Older, anchorSeq = 100, pageOf(70L, 80L, 90L), limit = 3)
            val grown = assertNotNull(rig.coldStartReader().read(partition, "channel-1"))
            assertEquals(listOf(70L, 80L, 90L, 100L, 110L, 120L), grown.envelopes.map { it.fact.seq })
            assertEquals(70L, grown.cursor.oldestLoadedSeq)
            assertEquals(120L, grown.cursor.newestLoadedSeq)
            assertEquals(listOf(10L to 30L, 70L to 120L), rig.segments())
        }
    }

    @Test
    fun theServerWindowDecidesWhetherTheLatestPageJoinsUnderIdenticalRows() {
        // Twin pair: the same stored window (10, 20, 30) and the SAME page rows (40, 50, a full page): every
        // count and seq comparison is identical. Only the server's messageWindow differs — A: nothing lies
        // between seq 30 and the page ([31, 50]); B: seq 31 exists and is not on the page ([32, 50]). Only
        // code that reads the producer's coverage keeps one range in A and two in B.
        fun run(coveredAfterSeq: Long) = withFreshDatabase { database ->
            val rig = Rig(database)
            runBlocking {
                rig.enter(listOf(10L, 20L, 30L))
                rig.latest(pageOf(40L, 50L), limit = 2, window = channelWindow(coveredAfterSeq, coveredThroughSeq = 50))
                Triple(
                    assertNotNull(rig.coldStartReader().read(partition, "channel-1")).envelopes.map { it.fact.seq },
                    rig.segments(),
                    rig.onDiskSeqs(),
                )
            }
        }
        val joined = run(coveredAfterSeq = 30)
        val island = run(coveredAfterSeq = 31)
        assertEquals(Triple(listOf(10L, 20L, 30L, 40L, 50L), listOf(10L to 50L), listOf(10L, 20L, 30L, 40L, 50L)), joined)
        assertEquals(Triple(listOf(40L, 50L), listOf(10L to 30L, 32L to 50L), listOf(10L, 20L, 30L, 40L, 50L)), island)
    }

    @Test
    fun twoEntersKeepTheOlderPage() {
        // P1 acceptance: a second enter never wipes the first one's messages. Pair: the second page's window says
        // it starts right above seq 99 (island) or right above the stored 30 (joined) — same rows.
        fun run(coveredAfterSeq: Long) = withFreshDatabase { database ->
            val rig = Rig(database)
            runBlocking {
                rig.enter(listOf(10L, 20L, 30L))
                rig.enter(listOf(100L, 110L, 120L), window = channelWindow(coveredAfterSeq, coveredThroughSeq = 120))
                Pair(rig.onDiskSeqs(), rig.segments())
            }
        }
        assertEquals(Pair(listOf(10L, 20L, 30L, 100L, 110L, 120L), listOf(10L to 30L, 100L to 120L)), run(coveredAfterSeq = 99))
        assertEquals(Pair(listOf(10L, 20L, 30L, 100L, 110L, 120L), listOf(10L to 120L)), run(coveredAfterSeq = 30))
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

    private class Rig(private val database: RaftSqlDatabase, threadSummariesBody: String? = "{}") {
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

        suspend fun enter(seqs: List<Long>, window: ChannelMessageWindow? = null) {
            val windowField = window?.let { ""","messageWindow":${channelWindowJson(it)}""" }.orEmpty()
            val body = """{"messages":${pageOf(*seqs.toLongArray())},"historyLimited":false$windowField}"""
            CanonicalMessageV2EnteredScopeLoader(
                canary = canary,
                generationEngine = engine,
                fetcher = CanonicalMessageV2RestSnapshotFetcher(RecordingNetworkClient(body)),
                isEnabledForServer = { true },
            ).loadOnEnter(partition, "channel-1") { true }
        }

        suspend fun latest(rawMessages: JsonArray, limit: Int, window: ChannelMessageWindow? = null) =
            writer.applyLatestPage(partition, "channel-1", rawMessages, limit, replyParent = null, messageWindow = window) { true }

        private val scope = SyncScopeKey(partition, "channel-1", "channel")

        suspend fun onDiskSeqs(): List<Long> = store.restore(scope).envelopes.map { it.fact.seq }.sorted()

        suspend fun segments(): List<Pair<Long, Long>> = store.readSegments(scope).map { it.lowSeq to it.highSeq }

        suspend fun extend(direction: ThreadDurablePageDirection, anchorSeq: Long, rawMessages: JsonArray, limit: Int) =
            writer.extendWithAdjacentPage(partition, "channel-1", direction, anchorSeq, rawMessages, limit, replyParent = null) { true }
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

        fun node(id: String, seq: Long, threadId: String? = null): String = adjacentPageNode(id, seq, threadId = threadId)

        fun pageOf(vararg seqs: Long): JsonArray = adjacentPageOf(*seqs.map { node("m$it", it) }.toTypedArray())
    }
}
