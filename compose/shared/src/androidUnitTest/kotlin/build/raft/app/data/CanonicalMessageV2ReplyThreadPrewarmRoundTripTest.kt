package build.raft.app.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.CanonicalMessageV2BaselineCoordinator
import build.raft.app.network.sync.CanonicalMessageV2DurableLastKnownReader
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SqlDelightCanonicalMessageV2Store
import build.raft.app.network.sync.ThreadDurableReplyParent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task #14b on the real SQLite schema: the Activity prewarm's reply page is written as the thread's
 * window through the enter loader's gates (flag, server-confirmed parent, scope writer), never
 * regresses a newer stored window, and leaves the following enter snapshot its usual behavior.
 * Every positive has a twin whose inputs differ in one fact.
 *
 * Default wire: thread `thread-1` hangs off root `m30` in `channel-1`; the server's latest replies
 * (what an enter fetches) are seq 50, 60, 70.
 */
class CanonicalMessageV2ReplyThreadPrewarmRoundTripTest {
    @Test
    fun prewarmPageIsStoredAsTheThreadWindowUnderTheServerConfirmedParent() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.prewarm(replyPageArray(50, 60, 70), limit = 30)
            assertTrue(rig.logs.any { committed("window_created", 3) in it }, rig.logs.toString())
            assertEquals(listOf("channels/thread-1", "channels/channel-1"), rig.network.paths, "no page refetch")
            val thread = assertNotNull(rig.reader.readReplyThread(partition, "thread-1", parent))
            assertEquals(listOf(50L, 60L, 70L), thread.envelopes.map { it.fact.seq })
            assertEquals(Triple("channel", "channel-1", "m30"), Triple(thread.cursor.parentScopeKind, thread.cursor.parentScopeId, thread.cursor.rootMessageId))
            assertTrue(thread.cursor.hasLoadedOldest, "3 < limit 30")
            assertTrue(rig.logs.none { "canonical_v2_canary enter=" in it }, "no realtime baseline is registered by a prewarm")
        }
    }

    @Test
    fun flagOffPrewarmWritesNothingAndNeverTouchesTheNetwork() = withFreshDatabase { database ->
        // Twin of the positive above: identical page and wire, flag off.
        val rig = Rig(database, flagOn = false)
        runBlocking {
            rig.prewarm(replyPageArray(50, 60, 70), limit = 30)
            assertTrue(rig.logs.any { skipped("flag_off") in it }, rig.logs.toString())
            assertTrue(rig.network.paths.isEmpty())
            assertNull(Rig(database).reader.readReplyThread(partition, "thread-1", parent))
        }
    }

    @Test
    fun parentTheServerDoesNotConfirmWritesNothing() = withFreshDatabase { database ->
        // Twin of the positive: identical page, but the server says the thread hangs off another root.
        val rig = Rig(database, threadParentMessageId = "m20")
        runBlocking {
            rig.prewarm(replyPageArray(50, 60, 70), limit = 30)
            assertTrue(rig.logs.any { skipped("canonical_v2_thread_parent_mismatch") in it }, rig.logs.toString())
            assertNull(rig.reader.readReplyThread(partition, "thread-1", parent))
        }
    }

    @Test
    fun storedWindowUnderAnotherParentIsNeverReplacedByAPrewarm() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.loader.loadReplyThreadOnEnter(partition, "thread-1", parent) { true }
            val otherRoot = ThreadDurableReplyParent("channel-1", "m20")
            val generation = assertNotNull(rig.loader.replyThreadCommitGeneration(partition, "thread-1"))
            rig.loader.persistReplyThreadPrewarmPage(partition, "thread-1", otherRoot, replyPageArray(80), 30, generation) { true }
            assertTrue(rig.logs.any { skipped("parent_mismatch") in it }, rig.logs.toString())
            assertEquals(listOf(50L, 60L, 70L), assertNotNull(rig.reader.readReplyThread(partition, "thread-1", parent)).envelopes.map { it.fact.seq })
        }
    }

    @Test
    fun olderPrewarmPageNeverRegressesTheWindowAnEnterStored() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.loader.loadReplyThreadOnEnter(partition, "thread-1", parent) { true }
            // A prewarm fetched before the newest reply (70) arrived lands after the enter committed.
            rig.prewarm(replyPageArray(50, 60), limit = 30)
            assertTrue(rig.logs.any { skipped("stored_not_older") in it }, rig.logs.toString())
            val thread = assertNotNull(rig.reader.readReplyThread(partition, "thread-1", parent))
            assertEquals(listOf(50L, 60L, 70L), thread.envelopes.map { it.fact.seq })
            assertEquals(70L, thread.cursor.newestLoadedSeq)
            // Same newest edge is not newer either.
            rig.prewarm(replyPageArray(60, 70), limit = 2)
            assertEquals(2, rig.logs.count { skipped("stored_not_older") in it })
            assertTrue(assertNotNull(rig.reader.readReplyThread(partition, "thread-1", parent)).cursor.hasLoadedOldest)
        }
    }

    @Test
    fun newerPrewarmPageOverlappingTheStoredWindowExtendsItAndKeepsEveryStoredFact() = withFreshDatabase { database ->
        // A long-read thread: the enter stored 150 replies (seq 1..150, it reaches the first reply). Two new
        // replies later, the prewarm's 30-reply page (seq 123..152) shares 28 replies with the window.
        val rig = Rig(database, enterPage = replyPage(*(1L..150L).map { replyNode(it) }.toTypedArray()))
        runBlocking {
            rig.loader.loadReplyThreadOnEnter(partition, "thread-1", parent) { true }
            rig.prewarm(replyPageArray(*(123L..152L).toList().toLongArray()), limit = 30)
            assertTrue(rig.logs.any { committed("window_extended", 30) in it }, rig.logs.toString())
            val thread = assertNotNull(rig.reader.readReplyThread(partition, "thread-1", parent))
            assertEquals((1L..152L).toList(), thread.envelopes.map { it.fact.seq }, "no stored reply is dropped")
            assertEquals(1L, thread.cursor.oldestLoadedSeq)
            assertTrue(thread.cursor.hasLoadedOldest, "the window still reaches the first reply")
            assertEquals(152L, thread.cursor.newestLoadedSeq, "the newest edge comes from the page")
            assertEquals(152L, thread.cursor.latestSeq)
            assertEquals(Triple("channel", "channel-1", "m30"), Triple(thread.cursor.parentScopeKind, thread.cursor.parentScopeId, thread.cursor.rootMessageId))
        }
    }

    @Test
    fun newerPrewarmPageWithAGapKeepsTheStoredWindowAsAnIsland() = withFreshDatabase { database ->
        // Twin of the extend above: stored window 50..70, but the full page (80, 90) proves only [80, 90], so
        // nothing proves page ∪ window contiguous. Message storage Phase 2a: the page becomes the head and the
        // stored window stays on disk as an older island (`island_kept`); the reader shows the head.
        val rig = Rig(database)
        runBlocking {
            rig.loader.loadReplyThreadOnEnter(partition, "thread-1", parent) { true }
            rig.prewarm(replyPageArray(80, 90), limit = 2)
            assertTrue(rig.logs.any { committed("island_kept", 2) in it }, rig.logs.toString())
            assertEquals(listOf(50L, 60L, 70L, 80L, 90L), rig.storedSeqs(), "nothing stored is dropped")
            val thread = assertNotNull(rig.reader.readReplyThread(partition, "thread-1", parent))
            assertEquals(listOf(80L, 90L), thread.envelopes.map { it.fact.seq })
            assertEquals(80L, thread.cursor.oldestLoadedSeq)
            assertFalse(thread.cursor.hasLoadedOldest, "a full page (2 = limit) says nothing about older replies")
        }
    }

    @Test
    fun replyDeletedWhileThePrewarmWasInFlightIsNotWrittenBack() = withFreshDatabase { database ->
        // The thread was opened before (window 50..70). The prewarm reads the commit generation and fetches
        // 50..80; reply 80 is then deleted and the user enters: the enter's page (50..70) commits. The prewarm's
        // page is newer by seq and overlaps, so only the commit generation can tell it is stale.
        val rig = Rig(database)
        runBlocking {
            rig.loader.loadReplyThreadOnEnter(partition, "thread-1", parent) { true }
            val atFetch = rig.commitGeneration()
            rig.loader.loadReplyThreadOnEnter(partition, "thread-1", parent) { true }
            rig.prewarm(replyPageArray(50, 60, 70, 80), limit = 30, generation = atFetch)
            assertTrue(rig.logs.any { skipped("superseded") in it }, rig.logs.toString())
            val thread = assertNotNull(rig.reader.readReplyThread(partition, "thread-1", parent))
            assertEquals(listOf(50L, 60L, 70L), thread.envelopes.map { it.fact.seq }, "the deleted reply stays deleted")
            assertEquals(70L, thread.cursor.newestLoadedSeq)
        }
    }

    @Test
    fun prewarmWithNoWriteSinceItsFetchExtendsTheWindow() = withFreshDatabase { database ->
        // Twin of the test above: identical stored window, generation read and page, without the second enter.
        val rig = Rig(database)
        runBlocking {
            rig.loader.loadReplyThreadOnEnter(partition, "thread-1", parent) { true }
            val atFetch = rig.commitGeneration()
            rig.prewarm(replyPageArray(50, 60, 70, 80), limit = 30, generation = atFetch)
            assertTrue(rig.logs.any { committed("window_extended", 4) in it }, rig.logs.toString())
            assertEquals(
                listOf(50L, 60L, 70L, 80L),
                assertNotNull(rig.reader.readReplyThread(partition, "thread-1", parent)).envelopes.map { it.fact.seq },
            )
        }
    }

    @Test
    fun enterAfterPrewarmCommitsItsSnapshotAsUsual() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.prewarm(replyPageArray(60, 70), limit = 2)
            assertFalse(assertNotNull(rig.reader.readReplyThread(partition, "thread-1", parent)).cursor.hasLoadedOldest)

            rig.loader.loadReplyThreadOnEnter(partition, "thread-1", parent) { true }
            assertTrue(rig.logs.any { "canonical_v2_enter_snapshot server=server-1 scope=thread-1 scopeKind=thread outcome=committed facts=3" in it }, rig.logs.toString())
            val thread = assertNotNull(rig.reader.readReplyThread(partition, "thread-1", parent))
            assertEquals(listOf(50L, 60L, 70L), thread.envelopes.map { it.fact.seq })
            assertTrue(thread.cursor.hasLoadedOldest, "the enter's 3 < 200 page reaches the first reply")
            assertTrue(thread.envelopes.all { it.fact.content.startsWith("hello r") })
        }
    }

    @Test
    fun prewarmDuringAnEnterOfTheSameThreadLeavesItToTheEnter() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.network.onPageRequested = {
                // The enter is between its parent confirmation and its page.
                rig.prewarm(replyPageArray(50, 60), limit = 30)
            }
            rig.loader.loadReplyThreadOnEnter(partition, "thread-1", parent) { true }
            assertTrue(rig.logs.any { skipped("enter_in_flight") in it }, rig.logs.toString())
            assertTrue(rig.logs.any { "canonical_v2_enter_snapshot server=server-1 scope=thread-1 scopeKind=thread outcome=committed facts=3" in it }, rig.logs.toString())
            assertEquals(listOf(50L, 60L, 70L), assertNotNull(rig.reader.readReplyThread(partition, "thread-1", parent)).envelopes.map { it.fact.seq })
        }
    }

    @Test
    fun anEnterRewritesARealtimeHeaderThatNamedAnotherRootAndKeepsOneNamingTheSameRoot() {
        // Pair: the realtime header names root m99 (another thread's) or m30 (this one). The server-confirmed parent
        // wins: the foreign header is cleared and rewritten; the matching one is simply filled. Both end readable.
        fun run(realtimeRoot: String) = onFreshDatabase { database ->
            val rig = Rig(database)
            runBlocking {
                rig.seedRealtimeHeader(realtimeRoot)
                rig.loader.loadReplyThreadOnEnter(partition, "thread-1", parent) { true }
                val thread = assertNotNull(rig.reader.readReplyThread(partition, "thread-1", parent))
                assertEquals(listOf(50L, 60L, 70L), thread.envelopes.map { it.fact.seq })
                assertEquals("m30", thread.cursor.rootMessageId)
                rig.logs.any { "parent_rewritten old=channel-1/m99" in it }
            }
        }
        assertTrue(run("m99"))
        assertFalse(run("m30"))
    }

    private class Rig(
        database: RaftSqlDatabase,
        flagOn: Boolean = true,
        threadParentMessageId: String = "m30",
        enterPage: String = replyPage(replyNode(50), replyNode(60), replyNode(70)),
    ) {
        val logs = mutableListOf<String>()
        val network = PathNetworkClient(
            mutableMapOf(
                "channels/channel-1" to """{"id":"channel-1","type":"channel"}""",
                "channels/thread-1" to
                    """{"id":"thread-1","type":"thread","parentChannelId":"channel-1","parentMessageId":"$threadParentMessageId"}""",
                "messages/channel/thread-1" to enterPage,
            ),
        )
        private val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L })

        /** A realtime reply created thread-1's header naming the parent its frame claimed (Phase 3a). */
        suspend fun seedRealtimeHeader(rootMessageId: String) {
            store.applyCoverage(
                SyncScopeKey(partition, "thread-1", "thread"), emptyList(), coverage = null, revAtRequest = 0L, userDriven = false,
                threadParent = build.raft.app.network.sync.CanonicalMessageV2ThreadParent("channel", "channel-1", rootMessageId),
            )
        }

        /** Every reply fact of thread-1 on disk, head or island. */
        suspend fun storedSeqs(): List<Long> =
            store.restore(SyncScopeKey(partition, "thread-1", "thread")).envelopes.map { it.fact.seq }.sorted()
        private val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        private val canary = CanonicalMessageV2ProductionCanary(isEnabledForServer = { flagOn }, generationEngine = engine, logger = logs::add)
        val reader = CanonicalMessageV2DurableLastKnownReader(store, isEnabledForServer = { true }, logger = logs::add)
        val loader = CanonicalMessageV2EnteredScopeLoader(
            canary = canary,
            generationEngine = engine,
            fetcher = CanonicalMessageV2RestSnapshotFetcher(network, logs::add),
            isEnabledForServer = { flagOn },
            logger = logs::add,
        )

        /** The thread scope's commit generation, as the prewarm reads it before its page fetch. */
        fun commitGeneration(): Long = assertNotNull(loader.replyThreadCommitGeneration(partition, "thread-1"))

        /** A prewarm whose page fetch started at [generation] (default: now, i.e. nothing wrote meanwhile). */
        suspend fun prewarm(page: JsonArray, limit: Int, generation: Long = commitGeneration()) =
            loader.persistReplyThreadPrewarmPage(partition, "thread-1", parent, page, limit, generation) { true }
    }

    private fun withFreshDatabase(block: (RaftSqlDatabase) -> Unit) = onFreshDatabase(block)

    private fun <T> onFreshDatabase(block: (RaftSqlDatabase) -> T): T {
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
        val parent = ThreadDurableReplyParent(parentChannelId = "channel-1", rootMessageId = "m30")

        fun committed(reason: String, facts: Int) =
            "canonical_v2_prewarm_page server=server-1 scope=thread-1 scopeKind=thread outcome=committed reason=$reason facts=$facts"

        fun skipped(reason: String) =
            "canonical_v2_prewarm_page server=server-1 scope=thread-1 scopeKind=thread outcome=skipped reason=$reason facts=0"
    }
}
