package build.raft.app.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.network.sync.CanonicalMessageV2BaselineCoordinator
import build.raft.app.network.sync.CanonicalMessageV2DurableLastKnownReader
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SqlDelightCanonicalMessageV2Store
import build.raft.app.network.sync.ThreadDurablePageDirection
import build.raft.app.network.sync.ThreadDurableReplyParent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task #168 step 3 on the real SQLite schema: a reply thread opened online is stored as its own
 * `thread` scope under its parent, read back offline only with that parent, paged from disk, and
 * extended by adjacent reply pages — without changing anything stored for the parent channel.
 */
class CanonicalMessageV2ReplyThreadRoundTripTest {
    @Test
    fun replyThreadIsStoredBesideItsChannelWithoutChangingTheChannelScope() = withFreshDatabase { database ->
        val rig = Rig(database)
        runBlocking {
            rig.loader.loadOnEnter(partition, "channel-1") { true }
            val channelBefore = assertNotNull(rig.reader.read(partition, "channel-1"))
            assertEquals(listOf("m20", "m30", "m40"), channelBefore.envelopes.map { it.fact.id })

            rig.loader.loadReplyThreadOnEnter(partition, "thread-1", parent) { true }
            assertTrue(rig.logs.any { "scope=thread-1 scopeKind=thread outcome=committed facts=3" in it }, rig.logs.toString())

            assertEquals(channelBefore, rig.reader.read(partition, "channel-1"), "the channel scope is byte-for-byte unchanged")
            val thread = assertNotNull(rig.reader.readReplyThread(partition, "thread-1", parent))
            assertEquals(listOf("r50", "r60", "r70"), thread.envelopes.map { it.fact.id })
            assertEquals("thread", thread.scope.scopeKind)
            assertEquals("channel", thread.cursor.parentScopeKind)
            assertEquals("channel-1", thread.cursor.parentScopeId)
            assertEquals("m30", thread.cursor.rootMessageId)
            assertNull(rig.reader.read(partition, "thread-1"), "the top-level reader never serves a thread scope")

            // Offline older paging inside the thread, from disk.
            val older = assertNotNull(rig.reader.readReplyThreadBefore(partition, "thread-1", parent, beforeSeq = 70, limit = 1))
            assertEquals(listOf("r60"), older.envelopes.map { it.fact.id })
            assertTrue(older.hasOlderStored)

            // An adjacent newer reply page is accepted by the real store's thread-scope checks.
            rig.writer.extendWithAdjacentPage(
                partition,
                "thread-1",
                ThreadDurablePageDirection.Newer,
                70,
                replyPageArray(80),
                30,
                parent,
            ) { true }
            assertEquals(
                listOf(50L, 60L, 70L, 80L),
                assertNotNull(rig.reader.readReplyThread(partition, "thread-1", parent)).envelopes.map { it.fact.seq },
            )
            assertEquals(channelBefore, rig.reader.read(partition, "channel-1"), "still unchanged after thread paging")
        }
    }

    @Test
    fun storedThreadIsNotServedForAnotherRoot() = withFreshDatabase { database ->
        // Twin of the read above: identical stored thread, asked for with another root message.
        val rig = Rig(database)
        runBlocking {
            rig.loader.loadReplyThreadOnEnter(partition, "thread-1", parent) { true }
            assertNotNull(rig.reader.readReplyThread(partition, "thread-1", parent))
            assertNull(rig.reader.readReplyThread(partition, "thread-1", ThreadDurableReplyParent("channel-1", "m20")))
            assertTrue(rig.logs.any { "durable_last_known outcome=parent_mismatch" in it && "scopeKind=thread" in it }, rig.logs.toString())
        }
    }

    @Test
    fun anchorContextPageJoinsTheStoredThreadUnderItsParent() = withFreshDatabase { database ->
        // Task #14 part C: a reply's context page that overlaps the stored thread window joins it.
        val rig = Rig(database)
        runBlocking {
            rig.loader.loadReplyThreadOnEnter(partition, "thread-1", parent) { true }
            rig.writer.persistContextPage(partition, "thread-1", "r40", replyPageArray(30, 40, 50), true, true, parent) { true }
            assertEquals(
                listOf(30L, 40L, 50L, 60L, 70L),
                assertNotNull(rig.reader.readReplyThread(partition, "thread-1", parent)).envelopes.map { it.fact.seq },
            )
            assertEquals(
                "canonical_v2_context_page server=server-1 scope=thread-1 scopeKind=thread anchor=r40 outcome=committed " +
                    "reason=window_extended_older facts=2",
                rig.logs.last(),
            )
        }
    }

    @Test
    fun anchorContextPageUnderAnotherParentIsRefused() = withFreshDatabase { database ->
        // Twin of the test above: identical stored thread and page, the route names another root message.
        val rig = Rig(database)
        runBlocking {
            rig.loader.loadReplyThreadOnEnter(partition, "thread-1", parent) { true }
            rig.writer.persistContextPage(
                partition, "thread-1", "r40", replyPageArray(30, 40, 50), true, true,
                ThreadDurableReplyParent("channel-1", "m20"),
            ) { true }
            assertEquals(
                listOf(50L, 60L, 70L),
                assertNotNull(rig.reader.readReplyThread(partition, "thread-1", parent)).envelopes.map { it.fact.seq },
            )
            assertTrue(rig.logs.last().endsWith("anchor=r40 outcome=skipped reason=parent_mismatch facts=0"), rig.logs.last())
        }
    }

    private class Rig(database: RaftSqlDatabase) {
        val logs = mutableListOf<String>()
        private val network = PathNetworkClient(
            mutableMapOf(
                "channels/channel-1" to """{"id":"channel-1","type":"channel"}""",
                "messages/channel/channel-1" to
                    """{"messages":${adjacentPageOf(adjacentPageNode("m20", 20), adjacentPageNode("m30", 30, threadId = "thread-1"), adjacentPageNode("m40", 40))},"historyLimited":false}""",
                "channels/channel-1/threads" to """{"m30":{"threadChannelId":"thread-1","replyCount":3,"unreadCount":0,"participantIds":[]}}""",
                "channels/thread-1" to """{"id":"thread-1","type":"thread","parentChannelId":"channel-1","parentMessageId":"m30"}""",
                "messages/channel/thread-1" to replyPage(replyNode(50), replyNode(60), replyNode(70)),
            ),
        )
        private val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L })
        private val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        private val canary = CanonicalMessageV2ProductionCanary(isEnabledForServer = { true }, generationEngine = engine)
        val reader = CanonicalMessageV2DurableLastKnownReader(store, isEnabledForServer = { true }, logger = logs::add)
        val loader = CanonicalMessageV2EnteredScopeLoader(
            canary = canary,
            generationEngine = engine,
            fetcher = CanonicalMessageV2RestSnapshotFetcher(network, logs::add),
            isEnabledForServer = { true },
            logger = logs::add,
        )
        val writer = CanonicalMessageV2AdjacentPageWriter(
            canary = canary,
            generationEngine = engine,
            fetcher = CanonicalMessageV2RestSnapshotFetcher(network, logs::add),
            isEnabledForServer = { true },
            logger = logs::add,
        )
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
        val parent = ThreadDurableReplyParent(parentChannelId = "channel-1", rootMessageId = "m30")
    }
}
