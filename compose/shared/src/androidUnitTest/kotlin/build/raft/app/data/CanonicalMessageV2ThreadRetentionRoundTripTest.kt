package build.raft.app.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.network.sync.CanonicalMessageV2BaselineCoordinator
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2RetentionPolicy
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SqlDelightCanonicalMessageV2Store
import build.raft.app.network.sync.ThreadDurableReplyParent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task #168 step 3 retention on the real SQLite schema: reply-thread scopes have their own budget
 * and never push a channel / DM scope out; a thread write refreshes its parent so it can never evict
 * the parent (and cascade-delete itself); channel-only partitions evict exactly as before.
 */
class CanonicalMessageV2ThreadRetentionRoundTripTest {
    @Test
    fun writingThreadsNeverEvictsTheirParentOrAnyChannel() = withFreshDatabase { database ->
        // The probe scenario that used to wipe the partition: channel cap 2, two channels, then
        // threads under both — and a later thread write after the channels' own last write went
        // stale (only the thread writes keep the parents alive).
        val rig = Rig(database, CanonicalMessageV2RetentionPolicy(maxScopesPerPartition = 2, scopeTtlMs = 1_000L))
        runBlocking {
            rig.at(1) { rig.enterChannel("c1") }
            rig.at(2) { rig.enterChannel("c2") }
            rig.at(3) { rig.enterThread("ta", parent = "c2") }
            rig.at(4) { rig.enterThread("tb", parent = "c2") }
            rig.at(5) { rig.enterThread("tc", parent = "c1") }
            rig.at(500) { rig.enterThread("td", parent = "c1") }
            // c2 was last written at 2 and c1 at 1: both are older than the ttl cutoff (1003 - 1000)
            // unless the thread writes under them refreshed them.
            rig.at(1003) { rig.enterThread("te", parent = "c2") }

            assertEquals(setOf("c1", "c2"), rig.storedScopes("channel"))
            assertEquals(setOf("ta", "tb", "tc", "td", "te"), rig.storedScopes("thread"))
            assertTrue(rig.logs.none { "scope_evicted" in it }, rig.logs.toString())
        }
    }

    @Test
    fun the25thThreadEvictsTheLeastRecentlyWrittenThreadAndNoChannel() = withFreshDatabase { database ->
        val rig = Rig(database, CanonicalMessageV2RetentionPolicy())
        runBlocking {
            rig.at(1) { rig.enterChannel("c1") }
            rig.at(2) { rig.enterChannel("c2") }
            val c2Before = rig.channelFacts("c2")
            (1..24).forEach { index -> rig.at(10L + index) { rig.enterThread("t$index", parent = "c1") } }
            assertEquals((1..24).map { "t$it" }.toSet(), rig.storedScopes("thread"))
            assertTrue(rig.logs.none { "scope_evicted" in it }, rig.logs.toString())

            rig.at(100) { rig.enterThread("t25", parent = "c1") }
            assertEquals((2..25).map { "t$it" }.toSet(), rig.storedScopes("thread"))
            assertEquals(setOf("c1", "c2"), rig.storedScopes("channel"))
            assertEquals(c2Before, rig.channelFacts("c2"))
            val evicted = rig.logs.filter { "scope_evicted" in it }
            assertEquals(1, evicted.size, evicted.toString())
            assertTrue("scope=t1 scopeKind=thread reason=thread_cap" in evicted.single(), evicted.single())
        }
    }

    @Test
    fun factCapPressureEvictsThreadsBeforeChannels() = withFreshDatabase { database ->
        // 2 + 2 channel facts + 1 + 2 reply facts = 7 > 6.
        val rig = Rig(database, CanonicalMessageV2RetentionPolicy(maxFactsPerScope = 4, maxFactsPerPartition = 6))
        runBlocking {
            rig.at(1) { rig.enterChannel("c1", facts = 2) }
            rig.at(2) { rig.enterChannel("c2", facts = 2) }
            rig.at(3) { rig.enterThread("ta", parent = "c1", replies = 1) }
            rig.at(4) { rig.enterThread("tb", parent = "c2", replies = 2) }

            assertEquals(setOf("c1", "c2"), rig.storedScopes("channel"))
            assertEquals(setOf("tb"), rig.storedScopes("thread"))
            val evicted = rig.logs.filter { "scope_evicted" in it }
            assertEquals(1, evicted.size, evicted.toString())
            assertTrue("scope=ta scopeKind=thread reason=fact_cap" in evicted.single(), evicted.single())
        }
    }

    @Test
    fun channelOnlyWritesStillEvictTheOldestChannelAtTheChannelCap() = withFreshDatabase { database ->
        // Negative twin of the first test: the same channel cap, no threads.
        val rig = Rig(database, CanonicalMessageV2RetentionPolicy(maxScopesPerPartition = 2, scopeTtlMs = 1_000L))
        runBlocking {
            rig.at(1) { rig.enterChannel("c1") }
            rig.at(2) { rig.enterChannel("c2") }
            assertEquals(setOf("c1", "c2"), rig.storedScopes("channel"))
            rig.at(3) { rig.enterChannel("c3") }
            assertEquals(setOf("c2", "c3"), rig.storedScopes("channel"))
            val evicted = rig.logs.filter { "scope_evicted" in it }
            assertEquals(1, evicted.size, evicted.toString())
            assertTrue("scope=c1 scopeKind=channel reason=scope_cap" in evicted.single(), evicted.single())
        }
    }

    private class Rig(private val database: SlockSqlDatabase, retention: CanonicalMessageV2RetentionPolicy) {
        val logs = mutableListOf<String>()
        private var clock = 0L
        private var nextSeq = 1L
        private val network = PathNetworkClient(mutableMapOf())
        private val store = SqlDelightCanonicalMessageV2Store(
            database = database,
            now = { clock },
            retention = retention,
            logger = logs::add,
        )
        private val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        private val loader = CanonicalMessageV2EnteredScopeLoader(
            canary = CanonicalMessageV2ProductionCanary(isEnabledForServer = { true }, generationEngine = engine),
            generationEngine = engine,
            fetcher = CanonicalMessageV2RestSnapshotFetcher(network, logs::add),
            isEnabledForServer = { true },
            logger = logs::add,
        )

        suspend fun at(time: Long, block: suspend () -> Unit) {
            clock = time
            block()
        }

        suspend fun enterChannel(id: String, facts: Int = 1) {
            network.bodies["channels/$id"] = """{"id":"$id","type":"channel"}"""
            val nodes = (1..facts).map { adjacentPageNode("$id-m$it", nextSeq++, channelId = id) }
            network.bodies["messages/channel/$id"] = """{"messages":${adjacentPageOf(*nodes.toTypedArray())},"historyLimited":false}"""
            loader.loadOnEnter(partition, id) { true }
        }

        suspend fun enterThread(id: String, parent: String, replies: Int = 1) {
            val root = "$parent-m1"
            network.bodies["channels/$id"] =
                """{"id":"$id","type":"thread","parentChannelId":"$parent","parentMessageId":"$root"}"""
            network.bodies["messages/channel/$id"] = replyPage(*(1..replies).map { replyNode(nextSeq++, id) }.toTypedArray())
            loader.loadReplyThreadOnEnter(partition, id, ThreadDurableReplyParent(parent, root)) { true }
        }

        fun storedScopes(kind: String): Set<String> =
            database.queries.selectCanonicalScopeRetentionStats(
                environmentId = partition.environmentId,
                serverId = partition.serverId,
                principalId = partition.principalId,
                consumerId = partition.consumerId,
            ).executeAsList().filter { it.scopeKind == kind }.map { it.scopeId }.toSet()

        fun channelFacts(id: String): List<String> =
            database.queries.selectServerBackedMessagesForChannel(
                environmentId = partition.environmentId,
                serverId = partition.serverId,
                principalId = partition.principalId,
                consumerId = partition.consumerId,
                scopeKind = "channel",
                channelId = id,
            ).executeAsList().map { it.canonicalFactJson.decodeToStringOrSelf() }
    }

    private fun withFreshDatabase(block: (SlockSqlDatabase) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            SlockSqlDatabase.Schema.create(driver).value
            block(SlockSqlDatabase(driver))
        } finally {
            driver.close()
        }
    }

    private companion object {
        val partition = canonicalV2TestGrant().durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
    }
}

private fun Any?.decodeToStringOrSelf(): String = when (this) {
    is ByteArray -> decodeToString()
    else -> toString()
}
