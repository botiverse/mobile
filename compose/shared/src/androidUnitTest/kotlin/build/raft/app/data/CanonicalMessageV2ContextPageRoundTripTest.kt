package build.raft.app.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.network.sync.CanonicalMessageV2BaselineCoordinator
import build.raft.app.network.sync.CanonicalMessageV2DurableLastKnownReader
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2RetentionPolicy
import build.raft.app.network.sync.CanonicalMessageV2IncrementalFrame
import build.raft.app.network.sync.CanonicalMessageV2ScopeContinuity
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SqlDelightCanonicalMessageV2Store
import build.raft.app.network.sync.SyncScopeKey
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Message storage Phase 2c on the real SQLite schema: a context page merges with a stored range only through a shared
 * message. Any other is an island: stored and kept across a restart, but not the head, not in the mirror columns and
 * not read until Phase 2d.
 */
class CanonicalMessageV2ContextPageRoundTripTest {
    @Test
    fun contextPageSharingAMessageGrowsTheWindow() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.enter(listOf(50L, 60L, 70L, 80L))
            rig.persist("m30", page(20, 30, 40, 50), hasOlder = true)

            val frame = assertNotNull(rig.reader.read(partition, "channel-1"))
            assertEquals(listOf(20L, 30L, 40L, 50L, 60L, 70L, 80L), frame.envelopes.map { it.fact.seq })
            assertEquals(20L, frame.cursor.oldestLoadedSeq)
            assertEquals(false, frame.cursor.hasLoadedOldest, "the server said hasOlder")
            assertEquals(80L, frame.cursor.newestLoadedSeq)
            assertEquals(
                "canonical_v2_context_page server=server-1 scope=channel-1 anchor=m30 outcome=committed " +
                    "reason=head_extended facts=4",
                rig.logs.last(),
            )
        }
    }

    @Test
    fun anIslandBelowOrAboveTheWindowIsStoredButNotRead() = withFreshDatabase { database ->
        // Twin of the test above: identical window and page shape, but no stored fact is on the page.
        val rig = Rig(database)
        runBlocking {
            rig.enter(listOf(50L, 60L, 70L, 80L))
            val before = assertNotNull(rig.reader.read(partition, "channel-1")).cursor
            rig.persist("m20", page(10, 20, 30, 40), hasOlder = true)
            assertEquals(true, rig.logs.last().endsWith("outcome=committed reason=island_stored facts=4"), rig.logs.last())
            rig.persist("m100", page(100, 110), hasOlder = true)
            assertEquals(true, rig.logs.last().endsWith("outcome=committed reason=island_stored facts=2"), rig.logs.last())

            val frame = assertNotNull(rig.reader.read(partition, "channel-1"))
            assertEquals(listOf(50L, 60L, 70L, 80L), frame.envelopes.map { it.fact.seq })
            assertEquals(before, frame.cursor, "no mirror column moves")
            assertEquals(listOf(10L to 40L, 50L to 80L, 100L to 110L), rig.segments())

            // A restart keeps them, and shows the same window.
            val cold = CanonicalMessageV2DurableLastKnownReader(rig.freshStore(), isEnabledForServer = { true })
            assertEquals(listOf(50L, 60L, 70L, 80L), assertNotNull(cold.read(partition, "channel-1")).envelopes.map { it.fact.seq })
            assertEquals(10, rig.storedSeqs().size)
        }
    }

    @Test
    fun theNextEnterKeepsTheHeadOnTheLatestPageWithAnIslandAboveItsOldWindow() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.enter(listOf(50L, 60L))
            rig.persist("m100", page(100), hasOlder = true)
            rig.enter(listOf(60L, 70L))
            val frame = assertNotNull(rig.reader.read(partition, "channel-1"))
            assertEquals(listOf(50L, 60L, 70L), frame.envelopes.map { it.fact.seq })
            assertEquals(70L, frame.cursor.newestLoadedSeq)
        }
    }

    @Test
    fun aRealtimeAppendDoesNotTurnAnIslandIntoLooseFacts() = withFreshDatabase { database ->
        // A legacy write owns the window and used to drop every range above it, which the reader would then show as
        // loose facts.
        val rig = Rig(database)
        runBlocking {
            rig.enter(listOf(50L, 60L))
            rig.persist("m100", page(100, 110), hasOlder = true)
            rig.appendLegacy(page(70))
            assertEquals(listOf(50L, 60L, 70L), assertNotNull(rig.reader.read(partition, "channel-1")).envelopes.map { it.fact.seq })
            assertEquals(listOf(50L to 70L, 100L to 110L), rig.segments())
        }
    }

    @Test
    fun aNeverOpenedChannelGetsNoOfflineScreen() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.persist("m100", page(100), hasOlder = true, userDriven = false, channelType = "channel")
            assertEquals(listOf(100L), rig.storedSeqs())
            assertNull(rig.reader.read(partition, "channel-1"))

            // The first enter then sees only its own window; the island stays apart.
            rig.enter(listOf(200L, 210L))
            assertEquals(listOf(200L, 210L), assertNotNull(rig.reader.read(partition, "channel-1")).envelopes.map { it.fact.seq })
        }
    }

    @Test
    fun aLinkOrOverviewWriteIsNoUseOfTheScopeForRetention() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.enter(listOf(50L, 60L))
            val accessed = rig.lastUserAccessAtMs()
            rig.advanceClock()
            rig.persist("m100", page(100), hasOlder = true, userDriven = false)
            assertEquals(accessed, rig.lastUserAccessAtMs())

            // Twin: the same page opened from the conversation counts.
            rig.persist("m120", page(120), hasOlder = true)
            assertEquals(false, accessed == rig.lastUserAccessAtMs())
        }
    }

    @Test
    fun aPageReachingPastTheNewestEdgeNeverProvesTheHead() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.enter(listOf(50L, 60L))
            rig.persist("m70", page(60, 70, 80), hasOlder = true, hasNewer = false)
            val frame = assertNotNull(rig.reader.read(partition, "channel-1"))
            assertEquals(listOf(50L, 60L, 70L, 80L), frame.envelopes.map { it.fact.seq })
            assertEquals(80L, frame.cursor.newestLoadedSeq)
            assertEquals(80L, frame.cursor.latestSeq)
            assertEquals(false, frame.cursor.hasLoadedNewest, "hasNewer false is no head proof")
            assertEquals(50L, frame.cursor.oldestLoadedSeq)
            assertEquals(true, rig.logs.last().endsWith("outcome=committed reason=head_extended facts=3"), rig.logs.last())
        }
    }

    @Test
    fun realtimeStaysContinuousAfterAnOlderReachOrIslandAndTurnsDisplayOnlyAfterANewerReach() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            // The socket is connected and joined, so the enter makes the window continuous.
            rig.canary.noteTransportEpoch(partition, 1L)
            rig.canary.noteRoomsJoined(partition, 1L)
            rig.enter(listOf(50L, 60L, 70L))
            val scope = assertNotNull(rig.reader.read(partition, "channel-1")).scope
            assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, rig.canary.continuityOf(scope))
            rig.persist("m40", page(40, 50), hasOlder = true)
            assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, rig.canary.continuityOf(scope))
            rig.persist("m100", page(100), hasOlder = true)
            assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, rig.canary.continuityOf(scope))
            rig.persist("m80", page(70, 80), hasOlder = true)
            assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, rig.canary.continuityOf(scope))
        }
    }

    @Test
    fun aLateCommitInsideTheWindowIsInsertedInPlace() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.enter(listOf(50L, 60L, 70L))
            rig.persist("m60", page(50, 55, 60, 70), hasOlder = true)
            assertEquals(listOf(50L, 55L, 60L, 70L), assertNotNull(rig.reader.read(partition, "channel-1")).envelopes.map { it.fact.seq })
        }
    }

    @Test
    fun aPlanCutoffIsNotTheChannelsStart() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.enter(listOf(50L, 60L))
            rig.persist("m40", page(40, 50), hasOlder = false, historyLimited = true)
            assertEquals(false, assertNotNull(rig.reader.read(partition, "channel-1")).cursor.hasLoadedOldest)
            // Twin: no cutoff, so nothing is older.
            rig.persist("m30", page(30, 40), hasOlder = false)
            assertEquals(true, assertNotNull(rig.reader.read(partition, "channel-1")).cursor.hasLoadedOldest)
        }
    }

    @Test
    fun flagOffAndAnchorMissingWriteNothing() = withFreshDatabase { database ->
        runBlocking {
            val off = Rig(database, flagOn = false)
            off.enter(listOf(50L, 60L))
            off.persist("m40", page(40, 50), hasOlder = true)
            assertEquals(listOf(50L, 60L), assertNotNull(off.reader.read(partition, "channel-1")).envelopes.map { it.fact.seq })
            assertEquals(true, off.logs.last().endsWith("anchor=m40 outcome=skipped reason=flag_off facts=0"), off.logs.last())

            val on = Rig(database)
            on.persist("m99", page(40, 50), hasOlder = true)
            assertEquals(listOf(50L, 60L), assertNotNull(on.reader.read(partition, "channel-1")).envelopes.map { it.fact.seq })
            assertEquals(true, on.logs.last().endsWith("anchor=m99 outcome=skipped reason=anchor_not_on_page facts=0"), on.logs.last())

            // Positive control on the same database and rig: the anchor on the page is written.
            on.persist("m40", page(40, 50), hasOlder = true)
            assertEquals(listOf(40L, 50L, 60L), assertNotNull(on.reader.read(partition, "channel-1")).envelopes.map { it.fact.seq })
        }
    }

    @Test
    fun anIslandMakesRoomBeforeTheWindowDoesAtTheCap() = withFreshDatabase { database ->
        val rig = Rig(database, maxFactsPerScope = 4)
        runBlocking {
            rig.enter(listOf(50L, 60L, 70L))
            rig.persist("m100", page(100, 110), hasOlder = true)
            assertEquals(listOf(50L, 60L, 70L), assertNotNull(rig.reader.read(partition, "channel-1")).envelopes.map { it.fact.seq })
            assertEquals(listOf(50L to 70L), rig.segments(), "the just-written island goes; the window is never evictable for it")
        }
    }

    private class Rig(private val database: RaftSqlDatabase, flagOn: Boolean = true, private val maxFactsPerScope: Int = 500) {
        val logs = mutableListOf<String>()
        private var clock = 1L
        private val store = freshStore()
        private val scope = SyncScopeKey(partition, "channel-1", "channel")
        private val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        val canary = CanonicalMessageV2ProductionCanary(isEnabledForServer = { true }, generationEngine = engine)
        val reader = CanonicalMessageV2DurableLastKnownReader(store, isEnabledForServer = { true })
        private val writer = CanonicalMessageV2AdjacentPageWriter(
            canary = canary,
            generationEngine = engine,
            fetcher = CanonicalMessageV2RestSnapshotFetcher(RecordingNetworkClient(canonicalV2TestPage()), logs::add),
            isEnabledForServer = { flagOn },
            logger = logs::add,
        )

        fun freshStore() = SqlDelightCanonicalMessageV2Store(
            database = database,
            now = { clock },
            retention = CanonicalMessageV2RetentionPolicy(maxFactsPerScope = maxFactsPerScope),
        )

        fun advanceClock() {
            clock += 1_000L
        }

        fun lastUserAccessAtMs(): Long? = database.queries.selectCanonicalScopeRetentionStats(
            environmentId = partition.environmentId,
            serverId = partition.serverId,
            principalId = partition.principalId,
            consumerId = partition.consumerId,
        ).executeAsList().single().lastUserAccessAtMs

        suspend fun segments(): List<Pair<Long, Long>> = store.readSegments(scope).map { it.lowSeq to it.highSeq }

        suspend fun storedSeqs(): List<Long> = store.restore(scope).envelopes.map { it.fact.seq }.sorted()

        /** A realtime append: the legacy writer, with the window one fact wider. */
        suspend fun appendLegacy(rawMessages: kotlinx.serialization.json.JsonArray) {
            val converted = CanonicalMessageV2RestSnapshotFetcher(RecordingNetworkClient(canonicalV2TestPage()))
                .convertPage(partition, "channel-1", "channel", rawMessages) as CanonicalMessageV2PageConversion.Converted
            val cursor = assertNotNull(store.readCursor(scope))
            val newest = converted.envelopes.maxOf { it.fact.seq }
            engine.restore(scope)
            engine.applyIncremental(
                CanonicalMessageV2IncrementalFrame(scope, converted.envelopes, cursor.copy(latestSeq = newest, newestLoadedSeq = newest)),
            )
        }

        suspend fun enter(seqs: List<Long>) {
            val window = channelWindowJson(channelWindow(coveredAfterSeq = seqs.min() - 1L, coveredThroughSeq = seqs.max()))
            val body = """{"messages":${page(*seqs.toLongArray())},"historyLimited":false,"messageWindow":$window}"""
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
            hasNewer: Boolean = true,
            historyLimited: Boolean = false,
            userDriven: Boolean = true,
            channelType: String? = null,
        ) = writer.persistContextPage(
            partition, "channel-1", anchor, rawMessages, hasOlder, hasNewer, replyParent = null,
            historyLimited = historyLimited,
            userDriven = userDriven,
            channelType = channelType,
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

        fun page(vararg seqs: Long) = adjacentPageOf(*seqs.map { adjacentPageNode("m$it", it) }.toTypedArray())
    }
}
