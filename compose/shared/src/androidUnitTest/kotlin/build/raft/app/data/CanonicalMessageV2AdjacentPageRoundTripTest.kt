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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Task #168 on the real SQLite schema: the enter creates the window, paging pages connected to it grow it, and the cursor the reader sees always describes facts that are actually on disk —
 * including after per-scope retention pruning.
 */
class CanonicalMessageV2AdjacentPageRoundTripTest {
    @Test
    fun enteredWindowIsExtendedByAdjacentPagesInBothDirections() = withFreshDatabase { database ->
        val rig = Rig(database, maxFactsPerScope = 500)
        runBlocking {
            rig.enter(listOf(20L, 30L, 40L))
            rig.extend(ThreadDurablePageDirection.Older, 20, adjacentPageOf(adjacentPageNode("m5", 5), adjacentPageNode("m10", 10)), coveredAfterSeq = 0)
            rig.extend(ThreadDurablePageDirection.Newer, 40, adjacentPageOf(adjacentPageNode("m50", 50)))

            val frame = assertNotNull(rig.reader.read(partition, "channel-1"))
            assertEquals(listOf(5L, 10L, 20L, 30L, 40L, 50L), frame.envelopes.map { it.fact.seq })
            assertEquals(5L, frame.cursor.oldestLoadedSeq)
            assertEquals(true, frame.cursor.hasLoadedOldest)
            assertEquals(50L, frame.cursor.newestLoadedSeq)
            assertEquals(50L, frame.cursor.latestSeq)
            assertEquals(false, frame.cursor.hasLoadedNewest, "only a latest fetch proves the head")
        }
    }

    @Test
    fun olderPageAnchoredOnTheThirtiethNewestMessageGrowsTheEnteredWindow() = withFreshDatabase { database ->
        val rig = Rig(database, maxFactsPerScope = 500)
        runBlocking {
            val stored = (0 until 40).map { 1000L + it * 10 }
            rig.enter(stored)
            val pageSeqs = (0 until 30).map { 800L + it * 10 }
            rig.extend(
                ThreadDurablePageDirection.Older,
                stored.sortedDescending()[29],
                adjacentPageOf(*pageSeqs.map { adjacentPageNode("m$it", it) }.toTypedArray()),
            )
            val frame = assertNotNull(rig.reader.read(partition, "channel-1"))
            assertEquals((pageSeqs + stored).distinct().sorted(), frame.envelopes.map { it.fact.seq })
            assertEquals(800L, frame.cursor.oldestLoadedSeq)
            assertEquals(false, frame.cursor.hasLoadedOldest)
            assertEquals(1390L, frame.cursor.newestLoadedSeq)
        }
    }

    @Test
    fun cursorStaysTrueToDiskAcrossRetentionPruning() = withFreshDatabase { database ->
        val rig = Rig(database, maxFactsPerScope = 4)
        runBlocking {
            rig.enter(listOf(20L, 30L, 40L))

            // Older: 3 stored + 2 new > cap 4: the store prunes the head's low end (the page's oldest) and the cursor
            // says what is on disk.
            rig.extend(ThreadDurablePageDirection.Older, 20, adjacentPageOf(adjacentPageNode("m5", 5), adjacentPageNode("m10", 10)))
            val afterOlder = assertNotNull(rig.reader.read(partition, "channel-1"))
            assertEquals(listOf(10L, 20L, 30L, 40L), afterOlder.envelopes.map { it.fact.seq })
            assertEquals(10L, afterOlder.cursor.oldestLoadedSeq)
            assertEquals(false, afterOlder.cursor.hasLoadedOldest)

            // Newer: 4 stored + 2 new > cap 4: the oldest go and the cursor's oldest edge moves to what survived.
            rig.extend(ThreadDurablePageDirection.Newer, 40, adjacentPageOf(adjacentPageNode("m50", 50), adjacentPageNode("m60", 60)))
            val afterNewer = assertNotNull(rig.reader.read(partition, "channel-1"))
            assertEquals(listOf(30L, 40L, 50L, 60L), afterNewer.envelopes.map { it.fact.seq })
            assertEquals(30L, afterNewer.cursor.oldestLoadedSeq)
            assertEquals(false, afterNewer.cursor.hasLoadedOldest)
            assertEquals(60L, afterNewer.cursor.newestLoadedSeq)
        }
    }

    @Test
    fun anIslandAboveTheWindowNeverCostsTheWindowItsFacts() = withFreshDatabase { database ->
        val rig = Rig(database, maxFactsPerScope = 4)
        runBlocking {
            rig.enter(listOf(20L, 30L, 40L))
            // Connects to nothing (a message exists between 40 and the page), and the scope is at the cap afterwards.
            rig.extend(ThreadDurablePageDirection.Newer, 40, adjacentPageOf(adjacentPageNode("m90", 90), adjacentPageNode("m100", 100)), coveredAfterSeq = 80)
            val frame = assertNotNull(rig.reader.read(partition, "channel-1"))
            assertEquals(listOf(20L, 30L, 40L), frame.envelopes.map { it.fact.seq }, "the island went, not the window")
            assertEquals(20L, frame.cursor.oldestLoadedSeq)
            assertEquals(40L, frame.cursor.newestLoadedSeq)
        }
    }

    private class Rig(database: RaftSqlDatabase, maxFactsPerScope: Int) {
        val logs = mutableListOf<String>()
        private val network = RecordingNetworkClient(canonicalV2TestPage())
        private val store = SqlDelightCanonicalMessageV2Store(
            database = database,
            now = { 1L },
            retention = CanonicalMessageV2RetentionPolicy(maxFactsPerScope = maxFactsPerScope),
        )
        private val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        private val canary = CanonicalMessageV2ProductionCanary(isEnabledForServer = { true }, generationEngine = engine)
        val reader = CanonicalMessageV2DurableLastKnownReader(store, isEnabledForServer = { true })
        private val writer = CanonicalMessageV2AdjacentPageWriter(
            canary = canary,
            generationEngine = engine,
            fetcher = CanonicalMessageV2RestSnapshotFetcher(network, logs::add),
            isEnabledForServer = { true },
            logger = logs::add,
        )

        /** The real enter loader commits the window from a REST page (seqs ascending). */
        suspend fun enter(seqs: List<Long>) {
            val window = channelWindowJson(channelWindow(coveredAfterSeq = seqs.min() - 1L, coveredThroughSeq = seqs.max()))
            val body = """{"messages":${adjacentPageOf(*seqs.map { adjacentPageNode("m$it", it) }.toTypedArray())},"historyLimited":false,"messageWindow":$window}"""
            CanonicalMessageV2EnteredScopeLoader(
                canary = canary,
                generationEngine = engine,
                fetcher = CanonicalMessageV2RestSnapshotFetcher(RecordingNetworkClient(body)),
                isEnabledForServer = { true },
            ).loadOnEnter(partition, "channel-1") { true }
        }

        suspend fun extend(
            direction: ThreadDurablePageDirection,
            anchorSeq: Long,
            rawMessages: kotlinx.serialization.json.JsonArray,
            coveredAfterSeq: Long? = null,
        ) = writer.extendWithAdjacentPage(
            partition, "channel-1", direction, anchorSeq, rawMessages, 3, replyParent = null, requestHeadEpoch = null,
            messageWindow = pagingWindowFor(direction, anchorSeq, rawMessages, coveredAfterSeq = coveredAfterSeq),
        ) { true }
    }

    private fun withFreshDatabase(block: (RaftSqlDatabase) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            RaftSqlDatabase.Schema.create(driver).value
            block(RaftSqlDatabase(driver))
        } finally {
            driver.close()
        }
    }

    private companion object {
        val partition = canonicalV2TestGrant().durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
    }
}
