package build.raft.app.network.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.data.RaftSqlDatabase
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun twoTouchingCoveragesMergeAndTheHeaderMirrorsTheHead() = withStore { store, _ ->
        store.cover(1..3L, CanonicalMessageV2Coverage(1, 3, reachesOldest = true, headAtVerify = false, Older))
        store.cover(4..6L, CanonicalMessageV2Coverage(4, 6, reachesOldest = false, headAtVerify = true, Latest))
        val segment = store.readSegments(scope).single()
        assertEquals(1L to 6L, segment.lowSeq to segment.highSeq)
        assertTrue(segment.reachesOldest)
        assertTrue(segment.headAtVerify)
        assertEquals(PersistedCursor(6, 1, 6, hasLoadedOldest = true, hasLoadedNewest = true), store.readCursor(scope))
        assertEquals((1..6L).map(::id), store.ids())
    }

    @Test
    fun disjointCoveragesStaySeparateAndTheHeadIsTheHighest() = withStore { store, _ ->
        store.cover(1..3L, CanonicalMessageV2Coverage(1, 3, reachesOldest = true, headAtVerify = false, Older))
        // One seq short of touching: seq 4 is unproven.
        store.cover(5..6L, CanonicalMessageV2Coverage(5, 6, reachesOldest = false, headAtVerify = true, Latest))
        assertEquals(listOf(1L to 3L, 5L to 6L), store.readSegments(scope).map { it.lowSeq to it.highSeq })
        assertEquals(PersistedCursor(6, 5, 6, hasLoadedOldest = false, hasLoadedNewest = true), store.readCursor(scope))
    }

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
    fun theWriteRevisionNeverGoesBackWhenTheScopeIsCleared() = withStore { store, _ ->
        store.cover(1..3L, coverage = null, content = "a")
        val revAtStaleRequest = store.maxLocalRev(scope.partition)
        CanonicalMessageV2GenerationEngine(CanonicalMessageV2BaselineCoordinator(), store) { }.clearScope(scope)
        assertEquals(revAtStaleRequest, store.maxLocalRev(scope.partition), "a scope clear never lowers the revision")
        store.cover(listOf(1L), coverage = null, content = "live")
        // A page fetched before the clear and the live write must not overwrite the live fact.
        store.cover(listOf(1L), coverage = null, content = "stale", revAtRequest = revAtStaleRequest)
        assertEquals("live", store.content(1))
    }

    @Test
    fun theWriteRevisionSurvivesAPartitionClear() = withStore { store, _ ->
        store.cover(listOf(1L, 2L), coverage = null, content = "a")
        store.cover(listOf(1L), coverage = null, content = "a2")
        val revAtStaleRequest = store.maxLocalRev(scope.partition)
        // Server-access loss / revoke clears the whole partition while the request is in flight.
        store.clearPartition(partition)
        assertEquals(revAtStaleRequest, store.maxLocalRev(scope.partition), "a partition clear never resets the revision")
        store.cover(listOf(1L), coverage = null, content = "live")
        store.cover(listOf(1L), coverage = null, content = "stale", revAtRequest = revAtStaleRequest)
        assertEquals("live", store.content(1))
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
    fun aBaselineNeedsAHeadCoverage() = withStore { store, _ ->
        val engine = CanonicalMessageV2GenerationEngine(CanonicalMessageV2BaselineCoordinator(), store) { }
        val generation = engine.partitionGeneration(partition)
        // Same page, same range: only the head proof differs.
        assertFailsWith<IllegalArgumentException> {
            engine.applyCoverage(
                scope, listOf(envelope(2)), CanonicalMessageV2Coverage(1, 2, true, headAtVerify = false, Latest), 0L, true,
                generation, establishesBaseline = true,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            engine.applyCoverage(scope, listOf(envelope(2)), null, 0L, true, generation, establishesBaseline = true)
        }
        assertTrue(store.ids().isEmpty(), "a refused baseline writes nothing")
        assertEquals(
            CanonicalMessageV2CommitResult.Committed,
            engine.applyCoverage(
                scope, listOf(envelope(2)), CanonicalMessageV2Coverage(1, 2, true, headAtVerify = true, Latest), 0L, true,
                generation, establishesBaseline = true,
            ),
        )
    }

    @Test
    fun onlyAPageWriterChangesAThreadsParentKindALooseWriteKeepsTheStoredOne() {
        // Pair: the stored thread header says the parent is `private`; a reply arrives naming parent kind `channel`
        // (e.g. a stale realtime frame). A loose (realtime, not user-driven) write stores the reply and keeps
        // `private`; a page (user-driven, server-confirmed) write with the same reply takes `channel`.
        val thread = SyncScopeKey(partition, "thread-a", "thread")
        fun reply(seq: Long) = CanonicalMessageEnvelopeV2(
            fact = envelope(seq).fact,
            context = CanonicalConversationContextV2.Thread(
                replyScope = ReplyScopeRefV2("server-a", "thread-a"),
                parentTopLevelScope = TopLevelScopeRefV2.Channel("server-a", "channel-a"),
                rootMessageRef = MessageRefV2("server-a", "root-1"),
            ),
            replyMembership = ReplyMembershipV2(
                root = MessageRefV2("server-a", "root-1"),
                parentScope = ReplyScopeRefV2("server-a", "thread-a"),
            ),
        )
        fun run(userDriven: Boolean): Pair<String?, List<String>> {
            var result: Pair<String?, List<String>>? = null
            withStore { store, _ ->
                store.applyCoverage(
                    thread, emptyList(), CanonicalMessageV2Coverage(0, 0, true, true, Latest), 0L, userDriven = true,
                    threadParent = CanonicalMessageV2ThreadParent("private", "channel-a", "root-1"),
                )
                store.applyCoverage(
                    thread, listOf(reply(5)), null, store.maxLocalRev(partition), userDriven = userDriven,
                    threadParent = CanonicalMessageV2ThreadParent("channel", "channel-a", "root-1"),
                )
                result = store.readCursor(thread)?.parentScopeKind to store.ids(thread)
            }
            return result!!
        }
        assertEquals("private" to listOf(id(5)), run(userDriven = false))
        assertEquals("channel" to listOf(id(5)), run(userDriven = true))
    }

    @Test
    fun theHeaderEdgesAreStoredFactSeqsNotClaimBounds() = withStore { store, _ ->
        store.applyCoverage(scope, emptyList(), CanonicalMessageV2Coverage(0, 0, true, true, Latest), 0L, userDriven = true)
        assertEquals(PersistedCursor(null, null, null, hasLoadedOldest = true, hasLoadedNewest = true), store.readCursor(scope))
        // A page proving [1, 9] (nothing below its first row 4) merges with the empty baseline into [0, 9].
        store.cover(listOf(4L, 9L), CanonicalMessageV2Coverage(1, 9, reachesOldest = true, headAtVerify = true, Latest))
        assertEquals(0L to 9L, store.readSegments(scope).single().let { it.lowSeq to it.highSeq })
        assertEquals(PersistedCursor(9, 4, 9, hasLoadedOldest = true, hasLoadedNewest = true), store.readCursor(scope))
    }

    @Test
    fun theLocalRevGuardStillUnionsThePagesCoverage() = withStore { store, _ ->
        store.cover(listOf(2L), coverage = null, content = "new")
        store.cover(listOf(1L, 2L), CanonicalMessageV2Coverage(1, 2, reachesOldest = true, headAtVerify = true, Latest), content = "old", revAtRequest = 0L)
        assertEquals("new", store.content(2))
        assertEquals("old", store.content(1))
        assertEquals(listOf(1L to 2L), store.readSegments(scope).map { it.lowSeq to it.highSeq })
    }

    @Test
    fun userDrivenWritesMoveLastUserAccessAndRealtimeWritesDoNot() = withStore { store, database ->
        clock = 10L
        store.cover(listOf(1L), coverage = null, userDriven = false)
        assertNull(database.lastUserAccess(scope), "a never-opened scope has no access time")
        clock = 20L
        store.cover(listOf(2L), coverage = null, userDriven = true)
        assertEquals(20L, database.lastUserAccess(scope))
        clock = 30L
        store.cover(listOf(3L), coverage = null, userDriven = false)
        assertEquals(20L, database.lastUserAccess(scope))
    }

    @Test
    fun aReplyThreadHeaderNeedsItsParentAndKeepsIt() = withStore { store, _ ->
        val thread = SyncScopeKey(partition, "thread-a", "thread")
        assertFailsWith<CanonicalMessageV2ScopePoisoned> {
            store.applyCoverage(thread, emptyList(), CanonicalMessageV2Coverage(0, 0, true, true, Latest), 0L, userDriven = true)
        }
        val parent = CanonicalMessageV2ThreadParent("channel", "channel-a", "root-1")
        store.applyCoverage(thread, emptyList(), CanonicalMessageV2Coverage(0, 0, true, true, Latest), 0L, userDriven = true, threadParent = parent)
        val cursor = store.readCursor(thread)!!
        assertEquals(Triple("channel", "channel-a", "root-1"), Triple(cursor.parentScopeKind, cursor.parentScopeId, cursor.rootMessageId))
        assertNull(cursor.oldestLoadedSeq, "an empty-baseline head mirrors as no edges")
        assertTrue(cursor.hasLoadedOldest)
        assertFailsWith<CanonicalMessageV2ScopePoisoned> {
            store.applyCoverage(thread, emptyList(), null, 0L, userDriven = true, threadParent = parent.copy(rootMessageId = "root-2"))
        }
        assertFailsWith<CanonicalMessageV2ScopePoisoned> {
            store.applyCoverage(thread, emptyList(), null, 0L, userDriven = true, threadParent = parent.copy(parentScopeId = "channel-b"))
        }
        // The same parent made private on the server: the writer's server-confirmed kind wins (never stuck).
        store.applyCoverage(thread, emptyList(), null, 0L, userDriven = true, threadParent = parent.copy(parentScopeKind = "private"))
        assertEquals("private", store.readCursor(thread)!!.parentScopeKind)
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

    @Test
    fun trimmingTheHeadsLowEndClearsReachesOldest() = withStore(policy(maxFacts = 10)) { store, _ ->
        store.cover(1..12L, CanonicalMessageV2Coverage(1, 12, reachesOldest = true, headAtVerify = true, Latest))
        assertEquals((3..12L).map(::id), store.ids())
        val head = store.readSegments(scope).single()
        assertEquals(3L to 12L, head.lowSeq to head.highSeq)
        assertFalse(head.reachesOldest)
        assertEquals(PersistedCursor(12, 3, 12, hasLoadedOldest = false, hasLoadedNewest = true), store.readCursor(scope))
    }

    @Test
    fun atTheFactCapNothingIsTrimmedAndReachesOldestStays() = withStore(policy(maxFacts = 12)) { store, _ ->
        store.cover(1..12L, CanonicalMessageV2Coverage(1, 12, reachesOldest = true, headAtVerify = true, Latest))
        assertTrue(store.readSegments(scope).single().reachesOldest)
        assertEquals(12, store.ids().size)
    }

    @Test
    fun looseFactsBeyondTheAllowanceGoBeforeAnyClaimedFact() = withStore(policy(maxFacts = 10, allowance = 2)) { store, _ ->
        store.cover(listOf(1L, 2L, 3L, 4L), coverage = null)
        store.cover(20..27L, CanonicalMessageV2Coverage(20, 27, reachesOldest = false, headAtVerify = true, Latest))
        // 12 facts, 2 over: the two oldest loose facts beyond the allowance of 2 go; the head is intact.
        assertEquals(listOf(id(3), id(4)) + (20..27L).map(::id), store.ids())
        assertEquals(listOf(20L to 27L), store.readSegments(scope).map { it.lowSeq to it.highSeq })
    }

    @Test
    fun looseFactsWithinTheAllowanceStayAndTheHeadIsTrimmedInstead() = withStore(policy(maxFacts = 10, allowance = 4)) { store, _ ->
        store.cover(listOf(1L, 2L, 3L, 4L), coverage = null)
        store.cover(20..27L, CanonicalMessageV2Coverage(20, 27, reachesOldest = false, headAtVerify = true, Latest))
        assertEquals(listOf(1L, 2L, 3L, 4L).map(::id) + (22..27L).map(::id), store.ids())
        assertEquals(listOf(22L to 27L), store.readSegments(scope).map { it.lowSeq to it.highSeq })
    }

    @Test
    fun theLeastRecentlyVerifiedIslandGoesBeforeTheHead() = withStore(policy(maxFacts = 10)) { store, _ ->
        clock = 1L
        store.cover(1..3L, CanonicalMessageV2Coverage(1, 3, reachesOldest = false, headAtVerify = false, Older))
        clock = 2L
        store.cover(10..12L, CanonicalMessageV2Coverage(10, 12, reachesOldest = false, headAtVerify = false, Context))
        clock = 3L
        store.cover(20..25L, CanonicalMessageV2Coverage(20, 25, reachesOldest = false, headAtVerify = true, Latest))
        // 12 facts: the island verified first ([1, 3]) goes whole; the newer island and the head stay.
        assertEquals((10..12L).map(::id) + (20..25L).map(::id), store.ids())
        assertEquals(listOf(10L to 12L, 20L to 25L), store.readSegments(scope).map { it.lowSeq to it.highSeq })
    }

    @Test
    fun aScopeKeepsAtMostTheSegmentCap() = withStore(policy(maxSegments = 2)) { store, _ ->
        clock = 1L
        store.cover(listOf(1L), CanonicalMessageV2Coverage(1, 1, reachesOldest = false, headAtVerify = false, Older))
        clock = 2L
        store.cover(listOf(5L), CanonicalMessageV2Coverage(5, 5, reachesOldest = false, headAtVerify = false, Older))
        clock = 3L
        store.cover(listOf(9L), CanonicalMessageV2Coverage(9, 9, reachesOldest = false, headAtVerify = true, Latest))
        assertEquals(listOf(5L to 5L, 9L to 9L), store.readSegments(scope).map { it.lowSeq to it.highSeq })
        assertEquals(listOf(id(5), id(9)), store.ids())
    }

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

    @Test
    fun aMoreRecentlyOpenedScopeEvictsAnOlderOpenedOne() = withStore(policy(maxScopes = 1)) { store, _ ->
        clock = 10L
        store.cover(listOf(1L), CanonicalMessageV2Coverage(1, 1, true, true, Latest), userDriven = true)
        clock = 20L
        val evicted = store.cover(listOf(2L), CanonicalMessageV2Coverage(2, 2, true, true, Latest), target = otherScope, userDriven = true)
        assertEquals(setOf(scope), evicted)
        assertTrue(store.readSegments(scope).isEmpty(), "eviction drops the scope's segments with its facts")
    }

    // ---- legacy writers keep segments consistent ---------------------------------------------------------------

    @Test
    fun aLegacySnapshotReplacesTheSegmentsWithItsWindowAndIncrementalsExtendIt() = withStore { store, _ ->
        store.cover(listOf(1L), CanonicalMessageV2Coverage(1, 1, reachesOldest = false, headAtVerify = false, Context))
        store.applySnapshot(CanonicalMessageV2SnapshotFrame(scope, (5..8L).map(::envelope), legacyCursor(5, 8)))
        // The snapshot cleared every unpinned fact, so no other claim survives.
        assertEquals(listOf(Legacy(5, 8)), store.readSegments(scope).map(::legacy))
        assertEquals(legacyCursor(5, 8), store.readCursor(scope))

        store.applyIncremental(CanonicalMessageV2IncrementalFrame(scope, listOf(envelope(9)), legacyCursor(5, 9)))
        assertEquals(listOf(Legacy(5, 9)), store.readSegments(scope).map(::legacy))
        assertEquals(legacyCursor(5, 9), store.readCursor(scope), "the header stays the legacy cursor")

        // A frame inside the window keeps the stored row as it is (its verification time too).
        val before = store.readSegments(scope)
        clock += 100
        store.applyIncremental(CanonicalMessageV2IncrementalFrame(scope, listOf(envelope(7)), legacyCursor(5, 9), insertOnly = true))
        assertEquals(before, store.readSegments(scope))
    }

    @Test
    fun aLegacyIncrementalKeepsAnIslandBelowItsWindowButNotOneTouchingIt() = withStore { store, _ ->
        store.applySnapshot(CanonicalMessageV2SnapshotFrame(scope, (10..12L).map(::envelope), legacyCursor(10, 12)))
        store.cover(listOf(1L, 2L), CanonicalMessageV2Coverage(1, 2, reachesOldest = false, headAtVerify = false, Context))
        store.cover(listOf(8L), CanonicalMessageV2Coverage(8, 8, reachesOldest = false, headAtVerify = false, Context))
        assertEquals(listOf(1L to 2L, 8L to 8L, 10L to 12L), store.readSegments(scope).map { it.lowSeq to it.highSeq })
        store.applyIncremental(CanonicalMessageV2IncrementalFrame(scope, listOf(envelope(13)), legacyCursor(9, 13)))
        // [8, 8] touches the window [9, 13] (the legacy window owns it now); [1, 2] stays apart.
        assertEquals(listOf(1L to 2L, 9L to 13L), store.readSegments(scope).map { it.lowSeq to it.highSeq })
    }

    @Test
    fun anEmptyLegacyBaselineIsTheZeroSegmentAndAnAdvanceReplacesIt() = withStore { store, _ ->
        val empty = PersistedCursor(null, null, null, hasLoadedOldest = true, hasLoadedNewest = true)
        store.applySnapshot(CanonicalMessageV2SnapshotFrame(scope, emptyList(), empty))
        val zero = store.readSegments(scope).single()
        assertTrue(zero.isEmptyBaseline)
        store.applyIncremental(
            CanonicalMessageV2IncrementalFrame(scope, listOf(envelope(40)), PersistedCursor(40, 40, 40, hasLoadedOldest = true, hasLoadedNewest = true)),
        )
        val advanced = store.readSegments(scope).single()
        assertEquals(40L to 40L, advanced.lowSeq to advanced.highSeq)
        assertTrue(advanced.reachesOldest)
    }

    @Test
    fun theLegacyCapTrimsSegmentsBelowWhatItPruned() = withStore(policy(maxFacts = 3)) { store, _ ->
        store.applySnapshot(CanonicalMessageV2SnapshotFrame(scope, (1..5L).map(::envelope), legacyCursor(1, 5, reachesOldest = true)))
        assertEquals(listOf(Legacy(3, 5)), store.readSegments(scope).map(::legacy))
        assertEquals(PersistedCursor(5, 3, 5, hasLoadedOldest = false), store.readCursor(scope))
    }

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

    @Test
    fun evictingAParentDropsItsReplyThreadsSegments() = withStore(policy(maxScopes = 1)) { store, database ->
        val thread = SyncScopeKey(partition, "thread-a", "thread")
        clock = 10L
        store.cover(listOf(1L), CanonicalMessageV2Coverage(1, 1, true, true, Latest), userDriven = true)
        store.applyCoverage(
            thread, emptyList(), CanonicalMessageV2Coverage(0, 0, true, true, Latest), 0L, userDriven = true,
            threadParent = CanonicalMessageV2ThreadParent("channel", "channel-a", "root-1"),
        )
        clock = 20L
        store.cover(listOf(2L), CanonicalMessageV2Coverage(2, 2, true, true, Latest), target = otherScope, userDriven = true)
        assertTrue(store.readSegments(thread).isEmpty())
        assertEquals(1L, database.segmentRows())
    }

    @Test
    fun theV1PurgeDropsV1SegmentsOnly() = withStore { store, database ->
        store.cover(listOf(1L), CanonicalMessageV2Coverage(1, 1, true, true, Latest))
        database.queries.upsertSegment("env-test", "server-a", "principal-a", SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID, "channel", "channel-a", 1, 1, 1, 1, 1, "latest")
        assertEquals(2L, database.segmentRows())
        purgeSyncCoreMessagesV1Rows(database)
        assertEquals(1L, database.segmentRows())
        assertEquals(1, store.readSegments(scope).size)
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
                        store.cover(1..3L, CanonicalMessageV2Coverage(1, 3, true, false, Older))
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

    private data class Legacy(val low: Long, val high: Long)

    private fun legacy(segment: CanonicalMessageV2Segment): Legacy {
        assertEquals(CanonicalMessageV2SegmentSource.Legacy, segment.source)
        return Legacy(segment.lowSeq, segment.highSeq)
    }

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

    private fun RaftSqlDatabase.lastUserAccess(key: SyncScopeKey): Long? =
        queries.selectSyncCursor(key.serverId, key.environmentId, key.principalId, key.consumerId, key.scopeKind, key.scopeId)
            .executeAsOne().lastUserAccessAtMs

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
