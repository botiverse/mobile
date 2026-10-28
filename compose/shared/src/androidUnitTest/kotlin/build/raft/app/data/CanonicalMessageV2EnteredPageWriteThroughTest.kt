package build.raft.app.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.network.sync.CanonicalMessageV2BaselineCoordinator
import build.raft.app.network.sync.CanonicalMessageV2DurableLastKnownReader
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SqlDelightCanonicalMessageV2Store
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.ThreadDurableEnteredPage
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Message storage Phase 2b on the real SQLite store: the page the thread store fetched is written through
 * ([CanonicalMessageV2EnteredScopeLoader.beginEnter] before the request, [CanonicalMessageV2EnteredScopeLoader.persistEnteredPage]
 * after it) — the writer makes none of the three requests the old enter fetch made (`channels/{id}`,
 * `messages/channel?limit=200`, `channels/{id}/threads`), a bad row splits the page's claim instead of refusing the page,
 * and the page is on disk after a restart.
 */
class CanonicalMessageV2EnteredPageWriteThroughTest {
    private val partition = canonicalV2TestGrant().durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
    private val scope = SyncScopeKey(partition, "channel-1", "channel")

    @Test
    fun theWriterMakesNoneOfTheOldEnterRequests() {
        // Pair: the same thread-parent page with its embedded reply counts (current server) and without them (an old
        // server). Only the old server's page makes the writer ask `channels/{id}/threads` — one request, not three.
        fun requestsFor(embedded: Boolean) = withDatabase { database ->
            val rig = Rig(database)
            runBlocking {
                val summaries = if (embedded) ""","threadSummariesByParentMessageId":{"m20":{"threadChannelId":"t-20","replyCount":3}}""" else ""
                rig.enter(
                    """{"messages":${pageOf(node("m10", 10), node("m20", 20, threadId = "t-20"))},"historyLimited":false$summaries}""",
                    channelType = "channel",
                )
                assertEquals(listOf(10L, 20L), rig.onDisk().map { it.fact.seq })
                assertEquals(3, rig.onDisk().single { it.fact.id == "m20" }.fact.replyCount)
                rig.network.paths.toList()
            }
        }
        assertEquals(emptyList(), requestsFor(embedded = true), "the visible page carried everything")
        assertEquals(listOf("channels/channel-1/threads"), requestsFor(embedded = false))
    }

    @Test
    fun theScopeKindComesFromThePageAndTheServerIsAskedOnlyWhenNothingNamesIt() {
        // Pair on an empty page (no row names the kind): with the channel detail the visible load already fetched, no
        // request; without it, one `channels/{id}`.
        fun requestsFor(channelType: String?) = withDatabase { database ->
            val rig = Rig(database)
            runBlocking {
                rig.enter("""{"messages":[],"historyLimited":false}""", channelType = channelType)
                assertNotNull(rig.coldStart(), "an empty channel is stored as a frame")
                rig.network.paths.toList()
            }
        }
        assertEquals(emptyList(), requestsFor("channel"))
        assertEquals(listOf("channels/channel-1"), requestsFor(null))
    }

    @Test
    fun aPageWithOneBadRowIsStoredAroundItAsTwoRanges() {
        // Pair with identical seqs and server window [6, 30]: one row (seq 20) is not full v6. It is left out, the other
        // rows are stored, and the claim splits around 20; the all-good twin is one range.
        fun run(badRow: Boolean) = withDatabase { database ->
            val rig = Rig(database)
            runBlocking {
                val window = channelWindowJson(channelWindow(coveredAfterSeq = 5, coveredThroughSeq = 30))
                val page = pageOf(node("m10", 10), node("m20", 20, thin = badRow), node("m30", 30))
                rig.enter("""{"messages":$page,"historyLimited":false,"messageWindow":$window}""", channelType = "channel")
                rig.onDisk().map { it.fact.seq } to rig.store.readSegments(scope).map { it.lowSeq to it.highSeq }
            }
        }
        assertEquals(listOf(10L, 30L) to listOf(6L to 19L, 21L to 30L), run(badRow = true))
        assertEquals(listOf(10L, 20L, 30L) to listOf(6L to 30L), run(badRow = false))
    }

    @Test
    fun theVisiblePageIsOnDiskAfterARestart() {
        val path = Files.createTempFile("slock-entered-page-", ".db").also { it.deleteIfExists() }
        try {
            val url = "jdbc:sqlite:${path.toAbsolutePath()}"
            JdbcSqliteDriver(url).let { driver ->
                RaftSqlDatabase.Schema.create(driver).value
                val rig = Rig(RaftSqlDatabase(driver))
                runBlocking { rig.enter("""{"messages":${pageOf(node("m10", 10), node("m20", 20))},"historyLimited":false}""", "channel") }
                driver.close()
            }
            // Restart: a fresh driver, store and reader on the same file.
            JdbcSqliteDriver(url).let { driver ->
                val reader = CanonicalMessageV2DurableLastKnownReader(
                    SqlDelightCanonicalMessageV2Store(RaftSqlDatabase(driver), now = { 2L }),
                    isEnabledForServer = { true },
                )
                val frame = assertNotNull(runBlocking { reader.read(partition, "channel-1") })
                assertEquals(listOf("m10", "m20"), frame.envelopes.map { it.fact.id })
                driver.close()
            }
        } finally {
            path.deleteIfExists()
        }
    }

    @Test
    fun aCatchUpPageReadBeforeItsFetchNeverOverwritesALooseRealtimeUpdate() {
        // 2a TODO resolved: the catch-up's revAtRequest is read before its fetch. A realtime loose write (no commit
        // generation bump) updates m20 during the fetch. Pair: with the pre-fetch revision the page's older copy is
        // kept out; read at write time (the old behaviour), it overwrites the realtime update.
        fun contentAfter(passRevision: Boolean) = withDatabase { database ->
            val rig = Rig(database)
            runBlocking {
                rig.enter("""{"messages":${pageOf(node("m10", 10), node("m20", 20))},"historyLimited":false}""", "channel")
                val revAtFetch = rig.writer.currentLocalRev(partition)
                // The realtime update lands while the catch-up's request is in flight.
                rig.store.applyCoverage(
                    scope = scope,
                    envelopes = listOf(rig.onDisk().single { it.fact.id == "m20" }.let { it.copy(fact = it.fact.copy(content = "edited live")) }),
                    coverage = null,
                    revAtRequest = rig.store.maxLocalRev(partition),
                    userDriven = false,
                )
                rig.writer.applyLatestPage(
                    partition, "channel-1", pageOf(node("m20", 20), node("m30", 30)), 200, replyParent = null,
                    revAtRequest = if (passRevision) revAtFetch else null,
                ) { true }
                rig.onDisk().single { it.fact.id == "m20" }.fact.content
            }
        }
        assertEquals("edited live", contentAfter(passRevision = true))
        assertEquals("hello m20", contentAfter(passRevision = false))
    }

    private class Rig(database: RaftSqlDatabase) {
        val network = RecordingNetworkClient(
            canonicalV2TestPage(),
            threadSummariesBody = """{"m20":{"threadChannelId":"t-20","replyCount":3}}""",
        )
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L })
        private val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        private val canary = CanonicalMessageV2ProductionCanary(isEnabledForServer = { true }, generationEngine = engine)
        private val fetcher = CanonicalMessageV2RestSnapshotFetcher(network)
        val loader = CanonicalMessageV2EnteredScopeLoader(canary, engine, fetcher, isEnabledForServer = { true })
        val writer = CanonicalMessageV2AdjacentPageWriter(canary, engine, fetcher, isEnabledForServer = { true })
        private val partition = canonicalV2TestGrant().durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
        private val scope = SyncScopeKey(partition, "channel-1", "channel")

        /** The thread store's part: a ticket before the request, then its page (here: [body]) written through. */
        suspend fun enter(body: String, channelType: String?) {
            val ticket = assertNotNull(loader.beginEnter(partition, "channel-1", replyParent = null))
            val page: ThreadDurableEnteredPage = assertNotNull(enteredPageFromBody("channel-1", body, channelType = channelType))
            loader.persistEnteredPage(ticket, page) { true }
        }

        suspend fun onDisk() = store.restore(scope).envelopes

        suspend fun coldStart() =
            CanonicalMessageV2DurableLastKnownReader(store, isEnabledForServer = { true }).read(partition, "channel-1")
    }

    private fun pageOf(vararg nodes: String): JsonArray = adjacentPageOf(*nodes)

    private fun node(id: String, seq: Long, threadId: String? = null, thin: Boolean = false) =
        adjacentPageNode(id, seq, threadId = threadId, thin = thin)

    private fun <T> withDatabase(block: (RaftSqlDatabase) -> T): T {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            RaftSqlDatabase.Schema.create(driver).value
            return block(RaftSqlDatabase(driver))
        } finally {
            driver.close()
        }
    }
}
