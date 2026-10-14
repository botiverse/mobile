package build.raft.app.data

import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.MessageAggregateField
import build.raft.app.network.sync.CanonicalMessageEnvelopeV2
import build.raft.app.network.sync.CanonicalMessageFoldInput
import build.raft.app.network.sync.CanonicalMessageFoldKind
import build.raft.app.network.sync.CanonicalMessageV2BaselineCoordinator
import build.raft.app.network.sync.CanonicalMessageV2CanaryOutcome
import build.raft.app.network.sync.CanonicalMessageV2CommitResult
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2IncrementalFrame
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.CanonicalMessageV2SnapshotFrame
import build.raft.app.network.sync.CanonicalMessageV2Store
import build.raft.app.network.sync.CanonicalMessageV2StoreWriteResult
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.PersistedCursor
import build.raft.app.network.sync.RestoredCanonicalMessageV2Scope
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SyncScopeId
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.ThreadDurablePageDirection
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task #168: an older / newer paging page is written into the stored V2 window only when it joins
 * it without a gap: the anchor is on disk inside the stored window and the page lies past it. Every
 * positive below has a negative twin whose setup differs in exactly one fact, so only the gate
 * under test separates them.
 *
 * Default stored window: facts seq 20, 30, 40 of channel-1; cursor oldest=20, newest=40.
 */
class CanonicalMessageV2AdjacentPageWriterTest {
    @Test
    fun adjacentOlderPageIsAppendedAndExtendsTheOldestEdge() = runBlocking {
        val h = harness()
        h.seedWindow()
        h.extend(ThreadDurablePageDirection.Older, anchorSeq = 20, adjacentPageOf(adjacentPageNode("m5", 5), adjacentPageNode("m10", 10)), limit = 3)

        assertEquals(listOf(5L, 10L, 20L, 30L, 40L), h.store.seqs(scope))
        val cursor = h.store.cursors.getValue(scope)
        assertEquals(5L, cursor.oldestLoadedSeq)
        assertTrue(cursor.hasLoadedOldest, "2 < limit 3 ⇒ the page reached the channel's start")
        assertEquals(40L, cursor.newestLoadedSeq, "the newer edge is kept")
        assertEquals(40L, cursor.latestSeq)
        assertTrue(cursor.hasLoadedNewest)
        assertEquals(cursor, h.writerEngine.currentCursor(scope), "the engine holds the same union cursor")
        assertTrue(h.logs.any { "direction=older outcome=committed facts=2" in it }, h.logs.toString())
    }

    @Test
    fun fullOlderPageLeavesHasLoadedOldestFalse() = runBlocking {
        val h = harness()
        h.seedWindow()
        h.extend(ThreadDurablePageDirection.Older, anchorSeq = 20, adjacentPageOf(adjacentPageNode("m5", 5), adjacentPageNode("m10", 10)), limit = 2)
        val cursor = h.store.cursors.getValue(scope)
        assertEquals(5L, cursor.oldestLoadedSeq)
        assertEquals(false, cursor.hasLoadedOldest, "a full page says nothing about older history")
    }

    @Test
    fun olderPageAnchoredOnTheThirtiethNewestStoredMessageGrowsTheWindow() = runBlocking {
        // The production shape: the enter snapshot stored 40 messages (1000..1390), the screen shows
        // the newest 30, and its first older page is anchored on the 30th-newest stored message
        // (1100). The 30-message page before it overlaps the window (1000..1090) and goes past its
        // oldest edge (800..990), so the window grows to 800 with no hole.
        val h = harness()
        val stored = (0 until 40).map { 1000L + it * 10 }
        h.seedWindow(seqs = stored)
        val anchor = stored.sortedDescending()[29]
        assertEquals(1100L, anchor)
        val pageSeqs = (0 until 30).map { 800L + it * 10 }
        h.extend(ThreadDurablePageDirection.Older, anchor, adjacentPageOf(*pageSeqs.map { adjacentPageNode("m$it", it) }.toTypedArray()), limit = 30)

        assertEquals((pageSeqs + stored).distinct().sorted(), h.store.seqs(scope))
        assertEquals(60, h.store.seqs(scope).size, "20 new facts + 40 stored; the 10 overlapping are upserts")
        val cursor = h.store.cursors.getValue(scope)
        assertEquals(800L, cursor.oldestLoadedSeq)
        assertEquals(false, cursor.hasLoadedOldest, "a full page (30 = limit) says nothing about older history")
        assertEquals(1390L, cursor.newestLoadedSeq)
        assertTrue(h.logs.any { "direction=older outcome=committed facts=30" in it }, h.logs.toString())
    }

    @Test
    fun anchorInsideTheWindowRangeButNotOnDiskWritesNothing() = runBlocking {
        // Twin of the test above: identical window and page, but the anchor message (1100) is not on
        // disk although 1100 lies between the stored edges.
        val h = harness()
        val stored = (0 until 40).map { 1000L + it * 10 }
        h.seedWindow(seqs = stored - 1100L)
        val pageSeqs = (0 until 30).map { 800L + it * 10 }
        h.extend(ThreadDurablePageDirection.Older, 1100L, adjacentPageOf(*pageSeqs.map { adjacentPageNode("m$it", it) }.toTypedArray()), limit = 30)
        assertEquals(0, h.store.incrementals.size)
        assertEquals(1000L, h.store.cursors.getValue(scope).oldestLoadedSeq)
        assertTrue(h.logs.any { "direction=older outcome=anchor_missing" in it }, h.logs.toString())
    }

    @Test
    fun anchorOutsideTheStoredWindowWritesNothing() = runBlocking {
        // The anchor is newer than the stored newest edge: the gap (40, 45) is unknown.
        val older = harness()
        older.seedWindow()
        older.extend(ThreadDurablePageDirection.Older, anchorSeq = 45, adjacentPageOf(adjacentPageNode("m5", 5), adjacentPageNode("m10", 10)), limit = 3)
        assertEquals(listOf(20L, 30L, 40L), older.store.seqs(scope))
        assertEquals(0, older.store.incrementals.size)
        assertTrue(older.logs.any { "direction=older outcome=not_adjacent" in it }, older.logs.toString())

        // The anchor is older than the stored oldest edge: the gap (15, 20) is unknown.
        val newer = harness()
        newer.seedWindow()
        newer.extend(ThreadDurablePageDirection.Newer, anchorSeq = 15, adjacentPageOf(adjacentPageNode("m50", 50)), limit = 3)
        assertEquals(0, newer.store.incrementals.size)
        assertTrue(newer.logs.any { "direction=newer outcome=not_adjacent" in it }, newer.logs.toString())
    }

    @Test
    fun pageEntirelyInsideTheWindowIsARefreshWithTheCursorUnchanged() = runBlocking {
        val older = harness()
        older.seedWindow()
        val before = older.store.cursors.getValue(scope)
        // Short page (2 < 3) that does NOT pass the oldest edge: it must not claim the channel start.
        older.extend(ThreadDurablePageDirection.Older, anchorSeq = 40, adjacentPageOf(adjacentPageNode("m20", 20), adjacentPageNode("m30", 30)), limit = 3)
        assertEquals(1, older.store.incrementals.size, "the known facts are refreshed")
        assertEquals(before, older.store.cursors.getValue(scope))
        assertEquals(listOf(20L, 30L, 40L), older.store.seqs(scope))

        val newer = harness()
        newer.seedWindow()
        val newerBefore = newer.store.cursors.getValue(scope)
        // Full page (2 = limit) that does NOT pass the newest edge: it must not clear hasLoadedNewest.
        newer.extend(ThreadDurablePageDirection.Newer, anchorSeq = 20, adjacentPageOf(adjacentPageNode("m30", 30), adjacentPageNode("m40", 40)), limit = 2)
        assertEquals(1, newer.store.incrementals.size)
        assertEquals(newerBefore, newer.store.cursors.getValue(scope))
    }

    @Test
    fun adjacentNewerPageIsAppendedAndExtendsTheNewestEdge() = runBlocking {
        val h = harness()
        h.seedWindow()
        h.extend(ThreadDurablePageDirection.Newer, anchorSeq = 40, adjacentPageOf(adjacentPageNode("m50", 50), adjacentPageNode("m60", 60)), limit = 2)

        assertEquals(listOf(20L, 30L, 40L, 50L, 60L), h.store.seqs(scope))
        val cursor = h.store.cursors.getValue(scope)
        assertEquals(60L, cursor.newestLoadedSeq)
        assertEquals(60L, cursor.latestSeq)
        assertEquals(false, cursor.hasLoadedNewest, "page filled the limit ⇒ more may follow")
        assertEquals(20L, cursor.oldestLoadedSeq, "the older edge is kept")
        assertEquals(false, cursor.hasLoadedOldest)
        assertTrue(h.logs.any { "direction=newer outcome=committed facts=2" in it }, h.logs.toString())
    }

    @Test
    fun newerPageAnchoredInsideTheWindowExtendsPastTheNewestEdge() = runBlocking {
        val h = harness()
        h.seedWindow()
        h.extend(ThreadDurablePageDirection.Newer, anchorSeq = 30, adjacentPageOf(adjacentPageNode("m40", 40), adjacentPageNode("m50", 50)), limit = 3)
        assertEquals(listOf(20L, 30L, 40L, 50L), h.store.seqs(scope))
        val cursor = h.store.cursors.getValue(scope)
        assertEquals(50L, cursor.newestLoadedSeq)
        assertEquals(50L, cursor.latestSeq)
        assertEquals(true, cursor.hasLoadedNewest, "2 < limit 3 and the page passed the edge ⇒ head reached")
    }

    @Test
    fun latestPageOverlappingTheWindowExtendsItAndADisjointOneReplacesIt() = runBlocking {
        // Task #168 step 1. Positive: m40 (stored) is on the page ⇒ incremental, newest edge moves.
        val joined = harness()
        joined.seedWindow()
        joined.latest(adjacentPageOf(adjacentPageNode("m40", 40), adjacentPageNode("m50", 50)), limit = 200)
        assertEquals(listOf(20L, 30L, 40L, 50L), joined.store.seqs(scope))
        assertEquals(1, joined.store.incrementals.size)
        assertEquals(50L, joined.store.cursors.getValue(scope).newestLoadedSeq)
        assertEquals(20L, joined.store.cursors.getValue(scope).oldestLoadedSeq)

        // Twin: the same seqs, but the seq-40 message on the page is not the stored one ⇒ snapshot.
        val disjoint = harness()
        disjoint.seedWindow()
        disjoint.latest(adjacentPageOf(adjacentPageNode("x40", 40), adjacentPageNode("m50", 50)), limit = 200)
        assertEquals(listOf(40L, 50L), disjoint.store.seqs(scope))
        assertEquals(1, disjoint.store.snapshots.size - 1, "one replacement snapshot on top of the seed")
        val cursor = disjoint.store.cursors.getValue(scope)
        assertEquals(40L, cursor.oldestLoadedSeq)
        assertTrue(cursor.hasLoadedOldest, "2 < limit 200 ⇒ the page reached the channel's start")
        assertTrue(disjoint.logs.any { "outcome=window_replaced old=20..40 oldFacts=3 new=40..50" in it }, disjoint.logs.toString())
    }

    @Test
    fun latestPageKeepsTheAdjacentWritersAdmissionGates() = runBlocking {
        val page = adjacentPageOf(adjacentPageNode("m100", 100))
        val none = harness()
        none.latest(page, limit = 200)
        assertTrue(none.store.cursors.isEmpty(), "the catch-up never creates a window")
        assertTrue(none.logs.any { "canonical_v2_latest_page" in it && "outcome=no_window" in it }, none.logs.toString())

        val changed = harness()
        changed.seedWindow()
        changed.latest(page, limit = 200, stillCurrent = { false })
        assertEquals(listOf(20L, 30L, 40L), changed.store.seqs(scope))
        assertTrue(changed.logs.any { "outcome=identity_changed" in it }, changed.logs.toString())

        val owned = harness(sameEngineForWriter = true)
        val gateway = ProviderBackedCanonicalMessageV2OwnerGateway(
            canary = owned.canary,
            authStore = InMemorySlockAuthSessionStore(canonicalV2TestSession()),
            networkClient = owned.network,
        )
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, gateway.accept(canonicalV2TestGrant(), foldInput("channel-1")))
        val canaryCursor = owned.store.cursors.getValue(scope)
        owned.latest(page, limit = 200)
        assertEquals(canaryCursor, owned.store.cursors.getValue(scope))
        assertTrue(owned.logs.any { "canonical_v2_latest_page" in it && "outcome=canary_owned" in it }, owned.logs.toString())
    }

    @Test
    fun noStoredWindowWritesNothingAndNeverCreatesOne() = runBlocking {
        val h = harness()
        // Same page and anchor as the positive older case, but no window was ever committed.
        h.extend(ThreadDurablePageDirection.Older, anchorSeq = 20, adjacentPageOf(adjacentPageNode("m5", 5), adjacentPageNode("m10", 10)), limit = 3)
        assertTrue(h.store.cursors.isEmpty())
        assertEquals(0, h.store.incrementals.size)
        assertEquals(0, h.store.snapshots.size)
        assertTrue(h.logs.any { "scope=channel-1 direction=older outcome=no_window" in it }, h.logs.toString())
    }

    @Test
    fun canaryOwnedScopeIsLeftToTheCanary() = runBlocking {
        val h = harness(channelIds = setOf("channel-1", "channel-2"), sameEngineForWriter = true)
        val gateway = ProviderBackedCanonicalMessageV2OwnerGateway(
            canary = h.canary,
            authStore = InMemorySlockAuthSessionStore(canonicalV2TestSession()),
            networkClient = h.network,
        )
        assertEquals(CanonicalMessageV2CanaryOutcome.Committed, gateway.accept(canonicalV2TestGrant(), foldInput("channel-1")))
        val canaryScope = SyncScopeKey(partition, "channel-1", "channel")
        val canaryCursor = h.store.cursors.getValue(canaryScope)
        // The canary's window edge is seq 7 (its snapshot page); offer an exactly adjacent page.
        h.extend(ThreadDurablePageDirection.Older, anchorSeq = 7, adjacentPageOf(adjacentPageNode("m5", 5)), limit = 3, channelId = "channel-1")
        assertEquals(0, h.store.incrementals.size)
        assertEquals(canaryCursor, h.store.cursors.getValue(canaryScope))
        assertTrue(h.logs.any { "scope=channel-1 direction=older outcome=canary_owned" in it }, h.logs.toString())

        // Twin: a sibling scope on the same partition that the canary does not own is extended.
        val sibling = SyncScopeKey(partition, "channel-2", "channel")
        h.seedWindow(sibling)
        h.extend(
            ThreadDurablePageDirection.Older,
            anchorSeq = 20,
            adjacentPageOf(adjacentPageNode("m5", 5, channelId = "channel-2")),
            limit = 3,
            channelId = "channel-2",
        )
        assertEquals(listOf(5L, 20L, 30L, 40L), h.store.seqs(sibling))
    }

    @Test
    fun identityChangeOrFlagOffWritesNothing() = runBlocking {
        val changed = harness()
        changed.seedWindow()
        changed.extend(ThreadDurablePageDirection.Older, 20, adjacentPageOf(adjacentPageNode("m5", 5)), limit = 3, stillCurrent = { false })
        assertEquals(0, changed.store.incrementals.size)
        assertTrue(changed.logs.any { "outcome=identity_changed" in it }, changed.logs.toString())

        var flagOn = true
        val off = harness(flag = { flagOn })
        off.seedWindow()
        flagOn = false
        off.extend(ThreadDurablePageDirection.Older, 20, adjacentPageOf(adjacentPageNode("m5", 5)), limit = 3)
        assertEquals(0, off.store.incrementals.size)
        assertTrue(off.logs.any { "outcome=flag_off" in it }, off.logs.toString())

        // Twin: same seed, current identity, flag on.
        val on = harness()
        on.seedWindow()
        on.extend(ThreadDurablePageDirection.Older, 20, adjacentPageOf(adjacentPageNode("m5", 5)), limit = 3)
        assertEquals(1, on.store.incrementals.size)
    }

    @Test
    fun oneNodeWithoutFullV6KeysRefusesTheWholePage() = runBlocking {
        val h = harness()
        h.seedWindow()
        h.extend(ThreadDurablePageDirection.Older, 20, adjacentPageOf(adjacentPageNode("m5", 5), adjacentPageNode("m10", 10, thin = true)), limit = 3)
        assertEquals(listOf(20L, 30L, 40L), h.store.seqs(scope))
        assertEquals(0, h.store.incrementals.size)
        assertTrue(h.logs.any { "outcome=canonical_v2_snapshot_not_full_v6" in it }, h.logs.toString())

        // Twin: the same two nodes, both full.
        h.extend(ThreadDurablePageDirection.Older, 20, adjacentPageOf(adjacentPageNode("m5", 5), adjacentPageNode("m10", 10)), limit = 3)
        assertEquals(listOf(5L, 10L, 20L, 30L, 40L), h.store.seqs(scope))
    }

    @Test
    fun pageSeqOnTheWrongSideOfTheAnchorIsRefused() = runBlocking {
        val older = harness()
        older.seedWindow()
        older.extend(ThreadDurablePageDirection.Older, 20, adjacentPageOf(adjacentPageNode("m10", 10), adjacentPageNode("m25", 25)), limit = 3)
        assertEquals(0, older.store.incrementals.size)
        assertTrue(older.logs.any { "direction=older outcome=wrong_side_of_anchor" in it }, older.logs.toString())

        val newer = harness()
        newer.seedWindow()
        newer.extend(ThreadDurablePageDirection.Newer, 40, adjacentPageOf(adjacentPageNode("m35", 35), adjacentPageNode("m50", 50)), limit = 3)
        assertEquals(0, newer.store.incrementals.size)
        assertTrue(newer.logs.any { "direction=newer outcome=wrong_side_of_anchor" in it }, newer.logs.toString())

        // An anchor-equal seq is not "older than the anchor" either.
        val equal = harness()
        equal.seedWindow()
        equal.extend(ThreadDurablePageDirection.Older, 20, adjacentPageOf(adjacentPageNode("m20", 20)), limit = 3)
        assertEquals(0, equal.store.incrementals.size)
    }

    @Test
    fun cursorEdgeWhoseFactIsNotOnDiskIsNotAWindowEdge() = runBlocking {
        val h = harness()
        // Cursor claims oldest=20 but fact 20 is gone (e.g. pruned by an earlier writer).
        h.seedWindow(seqs = listOf(30L, 40L), oldest = 20L)
        h.extend(ThreadDurablePageDirection.Older, 20, adjacentPageOf(adjacentPageNode("m5", 5)), limit = 3)
        assertEquals(0, h.store.incrementals.size)
        assertTrue(h.logs.any { "outcome=anchor_missing" in it }, h.logs.toString())
    }

    @Test
    fun olderPageThatWouldExceedTheScopeCapIsRefused() = runBlocking {
        val full = harness(maxFactsPerScope = 4)
        full.seedWindow()
        full.extend(ThreadDurablePageDirection.Older, 20, adjacentPageOf(adjacentPageNode("m5", 5), adjacentPageNode("m10", 10)), limit = 3)
        assertEquals(0, full.store.incrementals.size)
        assertTrue(full.logs.any { "direction=older outcome=at_capacity" in it }, full.logs.toString())

        // Twin: one fewer message fits exactly at the cap.
        val fits = harness(maxFactsPerScope = 4)
        fits.seedWindow()
        fits.extend(ThreadDurablePageDirection.Older, 20, adjacentPageOf(adjacentPageNode("m10", 10)), limit = 3)
        assertEquals(listOf(10L, 20L, 30L, 40L), fits.store.seqs(scope))
        assertEquals(10L, fits.store.cursors.getValue(scope).oldestLoadedSeq)

        // Only facts that are actually new count: a refresh of known facts at the cap is written.
        val atCap = harness(maxFactsPerScope = 3)
        atCap.seedWindow()
        atCap.extend(ThreadDurablePageDirection.Older, 40, adjacentPageOf(adjacentPageNode("m20", 20), adjacentPageNode("m30", 30)), limit = 3)
        assertEquals(1, atCap.store.incrementals.size)
        assertTrue(atCap.logs.any { "outcome=committed facts=2" in it }, atCap.logs.toString())
    }

    @Test
    fun newerPageOverflowingTheCapMovesTheOldestEdgeToWhatSurvivesPruning() = runBlocking {
        val h = harness(maxFactsPerScope = 4)
        h.seedWindow(hasLoadedOldest = true)
        h.extend(ThreadDurablePageDirection.Newer, 40, adjacentPageOf(adjacentPageNode("m50", 50), adjacentPageNode("m60", 60)), limit = 3)
        val cursor = h.store.cursors.getValue(scope)
        assertEquals(60L, cursor.newestLoadedSeq)
        assertEquals(30L, cursor.oldestLoadedSeq, "newest 4 of {20,30,40,50,60} start at 30")
        assertEquals(false, cursor.hasLoadedOldest, "the channel's start is pruned away")

        // Twin: no overflow ⇒ the oldest edge and its flag are kept.
        val roomy = harness(maxFactsPerScope = 5)
        roomy.seedWindow(hasLoadedOldest = true)
        roomy.extend(ThreadDurablePageDirection.Newer, 40, adjacentPageOf(adjacentPageNode("m50", 50), adjacentPageNode("m60", 60)), limit = 3)
        val kept = roomy.store.cursors.getValue(scope)
        assertEquals(20L, kept.oldestLoadedSeq)
        assertEquals(true, kept.hasLoadedOldest)
        assertEquals(true, kept.hasLoadedNewest, "2 < limit 3 ⇒ reached the head")
    }

    @Test
    fun threadParentInAPageGetsItsReplyCountFromTheSummariesEndpoint() = runBlocking {
        val h = harness(threadSummariesBody = """{"m10":{"threadChannelId":"thread-9","replyCount":3}}""")
        h.seedWindow()
        h.extend(ThreadDurablePageDirection.Older, 20, adjacentPageOf(adjacentPageNode("m10", 10, threadId = "thread-9")), limit = 3)
        assertEquals(listOf("channels/channel-1/threads"), h.network.paths)
        assertEquals(3, h.store.facts.getValue(scope).getValue("m10").fact.replyCount)

        // Twin: summaries outage with a thread parent present refuses the page.
        val outage = harness(threadSummariesBody = null)
        outage.seedWindow()
        outage.extend(ThreadDurablePageDirection.Older, 20, adjacentPageOf(adjacentPageNode("m10", 10, threadId = "thread-9")), limit = 3)
        assertEquals(0, outage.store.incrementals.size)
        assertTrue(outage.logs.any { "outcome=canonical_v2_thread_summaries_unavailable" in it }, outage.logs.toString())
    }

    @Test
    fun emptyPageWritesNothing() = runBlocking {
        val h = harness()
        h.seedWindow()
        h.extend(ThreadDurablePageDirection.Older, 20, JsonArray(emptyList()), limit = 3)
        assertEquals(0, h.store.incrementals.size)
        assertEquals(false, h.store.cursors.getValue(scope).hasLoadedOldest, "an empty page is skipped, not folded")
        assertTrue(h.logs.any { "outcome=empty_page" in it }, h.logs.toString())
    }

    private inner class Harness(
        val network: RecordingNetworkClient,
        val store: InMemoryCanonicalMessageV2Store,
        val seedEngine: CanonicalMessageV2GenerationEngine,
        val writerEngine: CanonicalMessageV2GenerationEngine,
        val canary: CanonicalMessageV2ProductionCanary,
        val writer: CanonicalMessageV2AdjacentPageWriter,
        val logs: MutableList<String>,
    ) {
        suspend fun seedWindow(
            target: SyncScopeKey = scope,
            seqs: List<Long> = listOf(20L, 30L, 40L),
            oldest: Long = seqs.min(),
            hasLoadedOldest: Boolean = false,
        ) {
            val converted = CanonicalMessageV2RestSnapshotFetcher(network).convertPage(
                partition = partition,
                scopeId = target.scopeId,
                channelType = target.scopeKind,
                rawMessages = adjacentPageOf(*seqs.map { adjacentPageNode("m$it", it, channelId = target.scopeId) }.toTypedArray()),
            ) as CanonicalMessageV2PageConversion.Converted
            val result = seedEngine.applySnapshot(
                CanonicalMessageV2SnapshotFrame(
                    scope = target,
                    envelopes = converted.envelopes,
                    cursor = PersistedCursor(
                        latestSeq = seqs.max(),
                        oldestLoadedSeq = oldest,
                        newestLoadedSeq = seqs.max(),
                        hasLoadedOldest = hasLoadedOldest,
                        hasLoadedNewest = true,
                    ),
                ),
            )
            assertEquals(CanonicalMessageV2CommitResult.Committed, result)
        }

        suspend fun extend(
            direction: ThreadDurablePageDirection,
            anchorSeq: Long,
            rawMessages: JsonArray,
            limit: Int,
            channelId: SyncScopeId = "channel-1",
            stillCurrent: () -> Boolean = { true },
        ) = writer.extendWithAdjacentPage(partition, channelId, direction, anchorSeq, rawMessages, limit, null, stillCurrent)

        suspend fun latest(
            rawMessages: JsonArray,
            limit: Int,
            channelId: SyncScopeId = "channel-1",
            stillCurrent: () -> Boolean = { true },
        ) = writer.applyLatestPage(partition, channelId, rawMessages, limit, null, stillCurrent)
    }

    /**
     * `sameEngineForWriter = false` (default) gives the writer a fresh engine over the same store, so
     * the window is NOT baselined in the writer's process — the restore path production takes after
     * a cold start.
     */
    private fun harness(
        flag: () -> Boolean = { true },
        channelIds: Set<String> = setOf("channel-1"),
        threadSummariesBody: String? = "{}",
        maxFactsPerScope: Int = 500,
        sameEngineForWriter: Boolean = false,
    ): Harness {
        val network = RecordingNetworkClient(
            canonicalV2TestPage(),
            channelIds = channelIds,
            threadSummariesBody = threadSummariesBody,
        )
        val store = InMemoryCanonicalMessageV2Store()
        fun engine() = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        val seedEngine = engine()
        val writerEngine = if (sameEngineForWriter) seedEngine else engine()
        val canary = CanonicalMessageV2ProductionCanary(isEnabledForServer = { flag() }, generationEngine = writerEngine)
        val logs = mutableListOf<String>()
        val writer = CanonicalMessageV2AdjacentPageWriter(
            canary = canary,
            generationEngine = writerEngine,
            fetcher = CanonicalMessageV2RestSnapshotFetcher(network, logs::add),
            isEnabledForServer = { flag() },
            logger = logs::add,
            maxFactsPerScope = maxFactsPerScope,
        )
        return Harness(network, store, seedEngine, writerEngine, canary, writer, logs)
    }

    private val partition = canonicalV2TestGrant().durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)
    private val scope = SyncScopeKey(partition, "channel-1", "channel")

    private fun foldInput(channelId: String) = CanonicalMessageFoldInput(
        kind = CanonicalMessageFoldKind.New,
        row = CanonicalMessageRow(
            id = "message-1",
            channelId = channelId,
            seq = 7L,
            senderType = "user",
            senderId = "user-1",
            content = "hello",
            messageType = "chat",
            createdAt = "2026-07-17T00:00:00.000Z",
            senderName = "Alice",
            conversationContext = CanonicalConversationContextWire("channel"),
        ),
        presentAggregates = MessageAggregateField.entries.toList(),
    )
}

/** One full-v6 top-level node in the real server shape; `thin` drops the required `reactions` key. */
internal fun adjacentPageNode(
    id: String,
    seq: Long,
    channelId: String = "channel-1",
    threadId: String? = null,
    thin: Boolean = false,
): String {
    val thread = threadId?.let { "\"$it\"" } ?: "null"
    val reactions = if (thin) "" else "\"reactions\":[],"
    fun two(value: Long) = value.toString().padStart(2, '0')
    val time = "${two(seq / 3600 % 24)}:${two(seq / 60 % 60)}:${two(seq % 60)}"
    return """{"id":"$id","seq":$seq,"channelId":"$channelId","senderType":"user","senderId":"user-1",""" +
        """"content":"hello $id","messageType":"chat","randomId":null,"threadId":$thread,""" +
        """"createdAt":"2026-07-17T$time.000Z","senderName":"Alice","senderDescription":null,""" +
        """"senderMembershipStatus":null,"mentions":[],"attachments":[],$reactions"commentRef":null,"actionMetadata":null}"""
}

internal fun adjacentPageOf(vararg nodes: String): JsonArray =
    Json.parseToJsonElement("[${nodes.joinToString(",")}]") as JsonArray

/** A V2 store that keeps facts and cursors in memory (no retention), for the writer's gates. */
internal class InMemoryCanonicalMessageV2Store : CanonicalMessageV2Store {
    val facts = mutableMapOf<SyncScopeKey, MutableMap<String, CanonicalMessageEnvelopeV2>>()
    val cursors = mutableMapOf<SyncScopeKey, PersistedCursor>()
    val snapshots = mutableListOf<CanonicalMessageV2SnapshotFrame>()
    val incrementals = mutableListOf<CanonicalMessageV2IncrementalFrame>()

    fun seqs(scope: SyncScopeKey): List<Long> = facts[scope].orEmpty().values.map { it.fact.seq }.sorted()

    override suspend fun applySnapshot(frame: CanonicalMessageV2SnapshotFrame): CanonicalMessageV2StoreWriteResult {
        snapshots += frame
        facts[frame.scope] = frame.envelopes.associateByTo(mutableMapOf()) { it.fact.id }
        cursors[frame.scope] = frame.cursor
        return CanonicalMessageV2StoreWriteResult()
    }

    override suspend fun applyIncremental(frame: CanonicalMessageV2IncrementalFrame): CanonicalMessageV2StoreWriteResult {
        incrementals += frame
        val scopeFacts = facts.getOrPut(frame.scope) { mutableMapOf() }
        frame.envelopes.forEach { scopeFacts[it.fact.id] = it }
        cursors[frame.scope] = frame.cursor
        return CanonicalMessageV2StoreWriteResult()
    }

    override suspend fun restore(scope: SyncScopeKey): RestoredCanonicalMessageV2Scope =
        RestoredCanonicalMessageV2Scope(
            envelopes = facts[scope].orEmpty().values.sortedBy { it.fact.seq },
            cursor = cursors[scope],
        )

    override suspend fun clearScope(scope: SyncScopeKey): Set<SyncScopeKey> {
        facts.remove(scope)
        cursors.remove(scope)
        return setOf(scope)
    }

    override suspend fun clearPartition(partition: DurablePartitionKey) {
        facts.keys.removeAll { it.partition == partition }
        cursors.keys.removeAll { it.partition == partition }
    }

    override suspend fun baselinedScopeKinds(partition: DurablePartitionKey, scopeId: SyncScopeId): List<String> =
        cursors.keys.filter { it.partition == partition && it.scopeId == scopeId }.map { it.scopeKind }.sorted()
}
