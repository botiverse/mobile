package build.raft.app.network.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.data.RaftSqlDatabase
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Message storage design 2026-10-27, Phase 1, on real SQLite: `applyCoverage` (facts + segments + retention +
 * header in one transaction), the legacy writers keeping segments consistent with their window, and every
 * clear path dropping segments.
 */
class CanonicalMessageV2SegmentStoreTest {
    private val partition = DurablePartitionKey("server-a", "principal-a", SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID, "env-test")
    private val scope = SyncScopeKey(partition, "channel-a", "channel")
    private val otherScope = SyncScopeKey(partition, "channel-b", "channel")
    private var clock = 1_000L

    // ---- applyCoverage: claims -------------------------------------------------------------------------------

    @Test
    fun aPageNeverDeletesAStoredFactItDoesNotCarry() = withStore { store, _ ->
        store.cover(1..10L, CanonicalMessageV2Coverage(1, 10, reachesOldest = true, headAtVerify = true, Latest))
        // A later page over [3, 5] without fact 4 (the server has no message delete): fact 4 stays, and so do
        // the facts outside the page's range.
        store.cover(listOf(3L, 5L), CanonicalMessageV2Coverage(3, 5, reachesOldest = false, headAtVerify = false, Context))
        assertEquals((1..10L).map(::id), store.ids())
        // Loose facts (no coverage) are stored too.
        store.cover(listOf(40L), coverage = null)
        assertEquals((1..10L).map(::id) + id(40), store.ids())
        assertEquals(listOf(1L to 10L), store.readSegments(scope).map { it.lowSeq to it.highSeq })
    }

    @Test
    fun aFactRewrittenAfterTheRequestStartedIsKeptAndAnOlderOneIsOverwritten() = withStore { store, _ ->
        store.cover(listOf(1L), coverage = null, content = "a")
        assertEquals(1L, store.maxLocalRev(scope.partition))
        val revAtStaleRequest = store.maxLocalRev(scope.partition)
        store.cover(listOf(1L), coverage = null, content = "c", revAtRequest = store.maxLocalRev(scope.partition))
        assertEquals(2L, store.maxLocalRev(scope.partition))
        // The stale page (requested at rev 1) arrives after the rev-2 write: the newer fact stays.
        store.cover(listOf(1L), coverage = null, content = "b", revAtRequest = revAtStaleRequest)
        assertEquals("c", store.content(1))
        // The same page requested after the rev-2 write overwrites it.
        store.cover(listOf(1L), coverage = null, content = "b", revAtRequest = 2L)
        assertEquals("b", store.content(1))
    }

    @Test
    fun theEngineRefusesACoverageWriteFromBeforeAPartitionClear() = withStore { store, _ ->
        val engine = CanonicalMessageV2GenerationEngine(CanonicalMessageV2BaselineCoordinator(), store) { }
        val generation = engine.partitionGeneration(partition)
        val rev = engine.storedLocalRev(partition)
        engine.clearPartition(partition)
        assertEquals(
            CanonicalMessageV2CommitResult.PartitionRevoked,
            engine.applyCoverage(scope, listOf(envelope(1)), CanonicalMessageV2Coverage(1, 1, true, true, Latest), rev, true, generation),
        )
        assertTrue(store.ids().isEmpty(), "a refused write stores nothing")
        // Positive control: the same write at the current generation commits, and the engine caches its header.
        assertEquals(
            CanonicalMessageV2CommitResult.Committed,
            engine.applyCoverage(
                scope, listOf(envelope(1)), CanonicalMessageV2Coverage(1, 1, true, true, Latest), rev, true,
                engine.partitionGeneration(partition),
            ),
        )
        assertEquals(listOf(id(1)), store.ids())
        assertEquals(store.readCursor(scope), engine.currentCursor(scope))
    }

    @Test
    fun onlyABaselineCoverageWriteMakesTheScopeWritableForRealtime() = withStore { store, _ ->
        val observed = mutableListOf<SyncScopeKey>()
        val engine = CanonicalMessageV2GenerationEngine(CanonicalMessageV2BaselineCoordinator(), store) { observed += it }
        val generation = engine.partitionGeneration(partition)
        val incremental = CanonicalMessageV2IncrementalFrame(scope, listOf(envelope(3)), legacyCursor(1, 3))
        engine.applyCoverage(scope, listOf(envelope(1)), CanonicalMessageV2Coverage(1, 1, true, true, Latest), 0L, true, generation)
        assertIs<CanonicalMessageV2CommitResult.BaselineRequired>(engine.applyIncremental(incremental, generation))
        assertTrue(observed.isEmpty())
        engine.applyCoverage(
            scope, listOf(envelope(2)), CanonicalMessageV2Coverage(1, 2, true, true, Latest), 0L, true, generation,
            establishesBaseline = true,
        )
        assertEquals(listOf(scope), observed)
        assertEquals(CanonicalMessageV2CommitResult.Committed, engine.applyIncremental(incremental, generation))
    }

    @Test
    fun theReadQueriesPageAroundASeqAndLocateAFactById() = withStore { store, database ->
        store.cover(listOf(1L, 3L, 5L, 7L), coverage = null)
        store.cover(listOf(2L), coverage = null, target = otherScope)
        val q = database.queries
        fun before(seq: Long, limit: Long) = q.selectFactsInScopeBefore(
            "env-test", "server-a", "principal-a", SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID, "channel", "channel-a", seq, limit,
        ).executeAsList().map { it.seq }
        fun after(seq: Long, limit: Long) = q.selectFactsInScopeAfter(
            "env-test", "server-a", "principal-a", SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID, "channel", "channel-a", seq, limit,
        ).executeAsList().map { it.seq }
        // Strictly below / above, nearest first, bounded; the other scope's fact never shows.
        assertEquals(listOf(3L, 1L), before(5, 10))
        assertEquals(listOf(3L), before(5, 1))
        assertEquals(listOf(5L, 7L), after(3, 10))
        assertEquals(listOf(5L), after(3, 1))
        val location = q.selectFactLocationById("env-test", "server-a", "principal-a", SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID, id(2))
            .executeAsOne()
        assertEquals(Triple("channel", "channel-b", 2L), Triple(location.scopeKind, location.channelId, location.seq))
        assertNull(
            q.selectFactLocationById("env-test", "server-a", "principal-a", SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID, id(99))
                .executeAsOneOrNull(),
        )
    }

    // ---- applyCoverage: segment-aware scope retention ----------------------------------------------------------

    // ---- partition retention rank ------------------------------------------------------------------------------

    @Test
    fun aNeverOpenedScopeIsEvictedBeforeAnOpenedOne() = withStore(policy(maxScopes = 1)) { store, _ ->
        clock = 10L
        store.cover(listOf(1L), CanonicalMessageV2Coverage(1, 1, true, true, Latest), userDriven = true)
        clock = 20L
        val evicted = store.cover(listOf(2L), CanonicalMessageV2Coverage(2, 2, true, true, Live), target = otherScope, userDriven = false)
        assertEquals(setOf(otherScope), evicted, "the realtime-only scope ranks last although written later")
        assertTrue(store.readSegments(otherScope).isEmpty())
        assertEquals(1, store.readSegments(scope).size)
    }

    // ---- legacy writers keep segments consistent ---------------------------------------------------------------

    // ---- clear paths ------------------------------------------------------------------------------------------

    @Test
    fun clearingAScopeOrAPartitionDropsItsSegments() = withStore { store, database ->
        store.cover(listOf(1L), CanonicalMessageV2Coverage(1, 1, true, true, Latest))
        store.cover(listOf(2L), CanonicalMessageV2Coverage(2, 2, true, true, Latest), target = otherScope)
        val engine = CanonicalMessageV2GenerationEngine(CanonicalMessageV2BaselineCoordinator(), store) { }
        engine.clearScope(scope)
        assertTrue(store.readSegments(scope).isEmpty())
        assertEquals(1, store.readSegments(otherScope).size, "positive control: the other scope keeps its segment")
        // AccountLocalData.onServerAccessLost and ThreadDurableAccessEvictor revoke through clearPartition.
        engine.clearPartition(partition)
        assertEquals(0L, database.segmentRows())
        assertTrue(store.knownPartitions().isEmpty())
    }

    // ---- kill / restart ---------------------------------------------------------------------------------------

    @Test
    fun aKillBeforeCommitLeavesTheOldStateAndAKillAfterCommitTheNewNeverMixed() {
        for (point in listOf(PersistFaultPoint.BeforeCommit, PersistFaultPoint.AfterCommit)) {
            val path = Files.createTempFile("slock-segments-", ".db").also { it.deleteIfExists() }
            try {
                val url = "jdbc:sqlite:${path.toAbsolutePath()}"
                JdbcSqliteDriver(url).let { driver ->
                    RaftSqlDatabase.Schema.create(driver).value
                    val database = RaftSqlDatabase(driver)
                    var kill = false
                    val store = SqlDelightCanonicalMessageV2Store(database, now = { clock }, faultHook = { it == point && kill })
                    runBlocking {
                        store.cover(1..3L, CanonicalMessageV2Coverage(1, 3, true, false, Latest))
                        kill = true
                        assertFailsWith<PersistKilled> {
                            store.cover(4..6L, CanonicalMessageV2Coverage(4, 6, false, true, Latest), content = "second")
                        }
                    }
                    driver.close()
                }
                // "Restart": a fresh driver + store on the same file.
                JdbcSqliteDriver(url).let { driver ->
                    val store = SqlDelightCanonicalMessageV2Store(RaftSqlDatabase(driver), now = { clock })
                    runBlocking {
                        val segments = store.readSegments(scope).map { it.lowSeq to it.highSeq }
                        val cursor = store.readCursor(scope)
                        if (point == PersistFaultPoint.BeforeCommit) {
                            assertEquals((1..3L).map(::id), store.ids())
                            assertEquals(listOf(1L to 3L), segments)
                            assertEquals(PersistedCursor(3, 1, 3, hasLoadedOldest = true, hasLoadedNewest = false), cursor)
                            assertEquals(1L, store.maxLocalRev(scope.partition))
                        } else {
                            assertEquals((1..6L).map(::id), store.ids())
                            assertEquals(listOf(1L to 6L), segments)
                            assertEquals(PersistedCursor(6, 1, 6, hasLoadedOldest = true, hasLoadedNewest = true), cursor)
                            assertEquals(2L, store.maxLocalRev(scope.partition))
                        }
                    }
                    driver.close()
                }
            } finally {
                path.deleteIfExists()
            }
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private fun legacyCursor(low: Long, high: Long, reachesOldest: Boolean = false) =
        PersistedCursor(high, low, high, hasLoadedOldest = reachesOldest, hasLoadedNewest = true)

    private fun policy(maxFacts: Int = 500, allowance: Int = 2, maxSegments: Int = 32, maxScopes: Int = 64) =
        CanonicalMessageV2RetentionPolicy(
            maxFactsPerScope = maxFacts,
            maxSegmentsPerScope = maxSegments,
            looseFactAllowancePerScope = minOf(allowance, maxFacts - 1),
            maxScopesPerPartition = maxScopes,
        )

    private suspend fun SqlDelightCanonicalMessageV2Store.cover(
        seqs: Iterable<Long>,
        coverage: CanonicalMessageV2Coverage?,
        content: String? = null,
        revAtRequest: Long? = null,
        userDriven: Boolean = true,
        target: SyncScopeKey = scope,
    ): Set<SyncScopeKey> = applyCoverage(
        scope = target,
        envelopes = seqs.map { envelope(it, content, target.scopeId) },
        coverage = coverage,
        revAtRequest = revAtRequest ?: maxLocalRev(target.partition),
        userDriven = userDriven,
    ).evictedScopes

    private suspend fun SqlDelightCanonicalMessageV2Store.ids(target: SyncScopeKey = scope) =
        restore(target).envelopes.map { it.fact.id }

    private suspend fun SqlDelightCanonicalMessageV2Store.content(seq: Long) =
        restore(scope).envelopes.single { it.fact.id == id(seq) }.fact.content

    /** Every segment row in the database, any partition or consumer. */
    private var segmentRowCounter: () -> Long = { error("no store") }

    private fun RaftSqlDatabase.segmentRows(): Long = segmentRowCounter()

    private fun id(seq: Long) = "message-$seq"

    private fun envelope(seq: Long) = envelope(seq, null, scope.scopeId)

    private fun envelope(seq: Long, content: String?, channelId: String) = CanonicalMessageEnvelopeV2(
        fact = CanonicalMessageNodeV2(
            id = id(seq),
            seq = seq,
            senderType = "agent",
            senderId = "agent-a",
            content = content ?: "body $seq",
            messageType = "chat",
            createdAt = "2026-10-27T08:00:00.000Z",
            senderName = "Friday",
        ),
        context = CanonicalConversationContextV2.TopLevel(TopLevelScopeRefV2.Channel("server-a", channelId)),
    )

    private fun withStore(
        retention: CanonicalMessageV2RetentionPolicy = CanonicalMessageV2RetentionPolicy(),
        block: suspend (SqlDelightCanonicalMessageV2Store, RaftSqlDatabase) -> Unit,
    ) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            RaftSqlDatabase.Schema.create(driver).value
            val database = RaftSqlDatabase(driver)
            segmentRowCounter = {
                driver.executeQuery(
                    null,
                    "SELECT COUNT(*) FROM messageSegmentEntity",
                    { cursor -> app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!! else 0L) },
                    0,
                ).value
            }
            runBlocking { block(SqlDelightCanonicalMessageV2Store(database, now = { clock }, retention = retention), database) }
        } finally {
            driver.close()
        }
    }

    private companion object {
        val Older = CanonicalMessageV2SegmentSource.Older
        val Latest = CanonicalMessageV2SegmentSource.Latest
        val Context = CanonicalMessageV2SegmentSource.Context
        val Live = CanonicalMessageV2SegmentSource.Live
    }
}
