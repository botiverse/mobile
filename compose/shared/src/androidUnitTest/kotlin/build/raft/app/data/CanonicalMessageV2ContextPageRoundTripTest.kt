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
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Task #14 part C on the real SQLite schema: the anchor context page (`messages/context/{id}`) is joined to the
 * stored window only when a stored fact inside the window is on the page; only the facts outside the window's
 * range are added (insert-only), each edge moves with the page's own `hasOlder` / `hasNewer`, and a page that
 * does not join is never written (one contiguous window per scope, no islands).
 */
class CanonicalMessageV2ContextPageRoundTripTest {
    @Test
    fun contextPageOverlappingTheWindowExtendsItsOldestEdge() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.enter(listOf(50L, 60L, 70L, 80L))
            rig.persist("m30", page(20, 30, 40, 50), hasOlder = true, hasNewer = true)

            val frame = assertNotNull(rig.reader.read(partition, "channel-1"))
            assertEquals(listOf(20L, 30L, 40L, 50L, 60L, 70L, 80L), frame.envelopes.map { it.fact.seq })
            assertEquals(20L, frame.cursor.oldestLoadedSeq)
            assertEquals(false, frame.cursor.hasLoadedOldest, "the server said hasOlder")
            assertEquals(80L, frame.cursor.newestLoadedSeq)
            assertEquals(
                "canonical_v2_context_page server=server-1 scope=channel-1 anchor=m30 outcome=committed " +
                    "reason=window_extended_older facts=3",
                rig.logs.last(),
            )
        }
    }

    @Test
    fun contextPageWithAGapToTheWindowIsNotWritten() = withFreshDatabase { database ->
        // Twin of the test above: identical window and page shape, but no stored fact is on the page.
        val rig = Rig(database)
        runBlocking {
            rig.enter(listOf(50L, 60L, 70L, 80L))
            rig.persist("m20", page(10, 20, 30, 40), hasOlder = true, hasNewer = true)

            val frame = assertNotNull(rig.reader.read(partition, "channel-1"))
            assertEquals(listOf(50L, 60L, 70L, 80L), frame.envelopes.map { it.fact.seq })
            assertEquals(50L, frame.cursor.oldestLoadedSeq)
            assertEquals(
                "canonical_v2_context_page server=server-1 scope=channel-1 anchor=m20 outcome=skipped reason=not_joined facts=0",
                rig.logs.last(),
            )
        }
    }

    @Test
    fun contextPageReachingPastTheNewestEdgeTakesThePagesNewerFact() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.enter(listOf(50L, 60L))
            rig.persist("m70", page(60, 70, 80), hasOlder = true, hasNewer = false)
            val frame = assertNotNull(rig.reader.read(partition, "channel-1"))
            assertEquals(listOf(50L, 60L, 70L, 80L), frame.envelopes.map { it.fact.seq })
            assertEquals(80L, frame.cursor.newestLoadedSeq)
            assertEquals(80L, frame.cursor.latestSeq)
            assertEquals(true, frame.cursor.hasLoadedNewest)
            assertEquals(50L, frame.cursor.oldestLoadedSeq, "the oldest edge only moves where the page reaches past it")
            assertEquals(true, rig.logs.last().endsWith("outcome=committed reason=window_extended_newer facts=2"), rig.logs.last())
        }
    }

    @Test
    fun contextPageReachingPastBothEdgesExtendsBoth() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.enter(listOf(50L, 60L))
            rig.persist("m55", page(40, 50, 55, 60, 70), hasOlder = false, hasNewer = true)
            val frame = assertNotNull(rig.reader.read(partition, "channel-1"))
            // m55 lies inside the stored range but is not stored: the window is the record there (deleted).
            assertEquals(listOf(40L, 50L, 60L, 70L), frame.envelopes.map { it.fact.seq })
            assertEquals(40L, frame.cursor.oldestLoadedSeq)
            assertEquals(true, frame.cursor.hasLoadedOldest)
            assertEquals(70L, frame.cursor.newestLoadedSeq)
            assertEquals(false, frame.cursor.hasLoadedNewest)
            assertEquals(true, rig.logs.last().endsWith("outcome=committed reason=window_extended_both facts=2"), rig.logs.last())
        }
    }

    @Test
    fun storedFactsAndTheWindowsRangeAreNeverRewrittenByTheContextPage() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.enter(listOf(50L, 60L, 70L))
            // The page is entirely inside the window and has a message (m55) the window does not: inside the
            // range the window is the complete record (m55 was deleted), so nothing is written.
            rig.persist("m60", page(50, 55, 60, 70), hasOlder = true, hasNewer = true)
            val frame = assertNotNull(rig.reader.read(partition, "channel-1"))
            assertEquals(listOf(50L, 60L, 70L), frame.envelopes.map { it.fact.seq })
            assertEquals(true, rig.logs.last().endsWith("outcome=skipped reason=inside_window facts=0"), rig.logs.last())
        }
    }

    @Test
    fun flagOffAndAnchorMissingWriteNothing() = withFreshDatabase { database ->
        runBlocking {
            val off = Rig(database, flagOn = false)
            off.enter(listOf(50L, 60L))
            off.persist("m40", page(40, 50), hasOlder = true, hasNewer = true)
            assertEquals(listOf(50L, 60L), assertNotNull(off.reader.read(partition, "channel-1")).envelopes.map { it.fact.seq })
            assertEquals(true, off.logs.last().endsWith("anchor=m40 outcome=skipped reason=flag_off facts=0"), off.logs.last())

            val on = Rig(database)
            on.persist("m99", page(40, 50), hasOlder = true, hasNewer = true)
            assertEquals(listOf(50L, 60L), assertNotNull(on.reader.read(partition, "channel-1")).envelopes.map { it.fact.seq })
            assertEquals(true, on.logs.last().endsWith("anchor=m99 outcome=skipped reason=anchor_not_on_page facts=0"), on.logs.last())

            // Positive control on the same database and rig: the anchor on the page is written.
            on.persist("m40", page(40, 50), hasOlder = true, hasNewer = true)
            assertEquals(listOf(40L, 50L, 60L), assertNotNull(on.reader.read(partition, "channel-1")).envelopes.map { it.fact.seq })
        }
    }

    @Test
    fun olderReachPastTheRetentionCapIsRefused() = withFreshDatabase { database ->
        val rig = Rig(database, maxFactsPerScope = 4)
        runBlocking {
            rig.enter(listOf(50L, 60L, 70L))
            rig.persist("m30", page(30, 40, 50), hasOlder = true, hasNewer = true)
            assertEquals(listOf(50L, 60L, 70L), assertNotNull(rig.reader.read(partition, "channel-1")).envelopes.map { it.fact.seq })
            assertEquals(true, rig.logs.last().endsWith("outcome=skipped reason=at_capacity facts=0"), rig.logs.last())
        }
    }

    private class Rig(database: RaftSqlDatabase, flagOn: Boolean = true, maxFactsPerScope: Int = 500) {
        val logs = mutableListOf<String>()
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
            fetcher = CanonicalMessageV2RestSnapshotFetcher(RecordingNetworkClient(canonicalV2TestPage()), logs::add),
            isEnabledForServer = { flagOn },
            logger = logs::add,
            maxFactsPerScope = maxFactsPerScope,
        )

        suspend fun enter(seqs: List<Long>) {
            val body = """{"messages":${page(*seqs.toLongArray())},"historyLimited":false}"""
            CanonicalMessageV2EnteredScopeLoader(
                canary = canary,
                generationEngine = engine,
                fetcher = CanonicalMessageV2RestSnapshotFetcher(RecordingNetworkClient(body)),
                isEnabledForServer = { true },
            ).loadOnEnter(partition, "channel-1") { true }
        }

        suspend fun persist(
            anchor: String,
            rawMessages: kotlinx.serialization.json.JsonArray,
            hasOlder: Boolean,
            hasNewer: Boolean,
        ) = writer.persistContextPage(partition, "channel-1", anchor, rawMessages, hasOlder, hasNewer, replyParent = null) { true }
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

        fun page(vararg seqs: Long) = adjacentPageOf(*seqs.map { adjacentPageNode("m$it", it) }.toTypedArray())
    }
}
