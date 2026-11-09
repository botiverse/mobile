package build.raft.app.data

import build.raft.app.network.sync.legacyWindowSegment
import build.raft.app.network.sync.mirroring
import build.raft.app.canonical.message.CanonicalConversationContextWire
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.canonical.message.MessageAggregateField
import build.raft.app.network.sync.CanonicalConversationContextV2
import build.raft.app.network.sync.CanonicalMessageEnvelopeV2
import build.raft.app.network.sync.TopLevelScopeRefV2
import build.raft.app.network.sync.CanonicalMessageFoldInput
import build.raft.app.network.sync.CanonicalMessageFoldKind
import build.raft.app.network.sync.CanonicalMessageV2BaselineCoordinator
import build.raft.app.network.sync.CanonicalMessageV2ScopeContinuity
import build.raft.app.network.sync.toCanonicalMessageV2Envelope
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.network.sync.CanonicalMessageV2CommitResult
import build.raft.app.network.sync.CanonicalMessageV2DurableLastKnownReader
import build.raft.app.network.sync.CanonicalMessageV2SegmentAlgebra
import build.raft.app.network.sync.CanonicalMessageV2SegmentSource
import build.raft.app.network.sync.ThreadDurableReplyParent
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
 * Task #168, Phase 2c: an older / newer paging page is stored with the range its server window proves. One that connects
 * to the head's coverage grows the window; any other is an island nothing reads until Phase 2d. Every positive below
 * has a negative twin whose setup differs in exactly one fact, so only the gate under test separates them.
 *
 * Default stored window: facts seq 20, 30, 40 of channel-1; cursor oldest=20, newest=40.
 */
class CanonicalMessageV2AdjacentPageWriterTest {
    @Test
    fun adjacentOlderPageIsAppendedAndExtendsTheOldestEdge() = runBlocking {
        val h = harness()
        h.seedWindow()
        h.extend(ThreadDurablePageDirection.Older, anchorSeq = 20, adjacentPageOf(adjacentPageNode("m5", 5), adjacentPageNode("m10", 10)), limit = 3, coveredAfterSeq = 0)

        assertEquals(listOf(5L, 10L, 20L, 30L, 40L), h.store.seqs(scope))
        val cursor = h.store.cursors.getValue(scope)
        assertEquals(5L, cursor.oldestLoadedSeq)
        assertTrue(cursor.hasLoadedOldest, "coveredAfterSeq 0 without a cutoff: nothing below the page")
        assertEquals(40L, cursor.newestLoadedSeq, "the newer edge is kept")
        assertEquals(40L, cursor.latestSeq)
        assertTrue(cursor.hasLoadedNewest)
        assertEquals(cursor, h.writerEngine.currentCursor(scope), "the engine holds the same union cursor")
        assertTrue(h.logs.any { "direction=older outcome=committed facts=2" in it }, h.logs.toString())
    }

    @Test
    fun olderPageWithAPredecessorLeavesHasLoadedOldestFalse() = runBlocking {
        val h = harness()
        h.seedWindow()
        h.extend(ThreadDurablePageDirection.Older, anchorSeq = 20, adjacentPageOf(adjacentPageNode("m5", 5), adjacentPageNode("m10", 10)), limit = 2)
        val cursor = h.store.cursors.getValue(scope)
        assertEquals(5L, cursor.oldestLoadedSeq)
        assertEquals(false, cursor.hasLoadedOldest, "a message exists below the page")
    }

    @Test
    fun aPlanCutoffIsNeverTheChannelsStart() = runBlocking {
        // Twin of the first test: same page, same window, but the plan's cutoff hid what is older.
        val h = harness()
        h.seedWindow()
        h.extend(
            ThreadDurablePageDirection.Older, anchorSeq = 20, adjacentPageOf(adjacentPageNode("m5", 5), adjacentPageNode("m10", 10)),
            limit = 3, coveredAfterSeq = 0, historyLimited = true,
        )
        assertEquals(false, h.store.cursors.getValue(scope).hasLoadedOldest)
    }

    @Test
    fun olderPageAnchoredOnTheThirtiethNewestStoredMessageGrowsTheWindow() = runBlocking {
        // The production shape: the enter stored 40 messages (1000..1390), the screen shows the newest 30, and its first
        // older page is anchored on the 30th-newest stored message (1100). The 30-message page before it overlaps the
        // window (1000..1090) and goes past its oldest edge (800..990), so the window grows to 800 with no hole.
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
        assertEquals(false, cursor.hasLoadedOldest)
        assertEquals(1390L, cursor.newestLoadedSeq)
        assertTrue(h.logs.any { "direction=older outcome=committed facts=30" in it }, h.logs.toString())
    }

    @Test
    fun olderPageConnectsAcrossTheSparseSeqsBetweenItsLastRowAndTheAnchor() = runBlocking {
        // coveredThroughSeq is the page's last row (10), not the anchor (20): other channels used the seqs between.
        val connected = harness()
        connected.seedWindow()
        connected.extend(ThreadDurablePageDirection.Older, anchorSeq = 20, adjacentPageOf(adjacentPageNode("m10", 10)), limit = 3)
        assertEquals(listOf(10L to 40L), connected.store.segments.getValue(scope).map { it.lowSeq to it.highSeq })

        // Twin: an anchor outside stored coverage proves no connection; the page is an island below the window.
        val apart = harness()
        apart.seedWindow(seqs = listOf(30L, 40L))
        apart.extend(ThreadDurablePageDirection.Older, anchorSeq = 20, adjacentPageOf(adjacentPageNode("m10", 10)), limit = 3)
        assertEquals(listOf(10L to 10L, 30L to 40L), apart.store.segments.getValue(scope).map { it.lowSeq to it.highSeq })
        assertEquals(30L, apart.store.cursors.getValue(scope).oldestLoadedSeq)
    }

    @Test
    fun newerPageConnectsOnlyWhenItsPredecessorIsAtOrBelowTheCoveredTail() = runBlocking {
        val page = adjacentPageOf(adjacentPageNode("m1000", 1000))
        // Sparse: 960 seqs apart, yet the server says the predecessor is the tail.
        val connected = harness()
        connected.seedWindow()
        connected.extend(ThreadDurablePageDirection.Newer, anchorSeq = 40, page, limit = 3, coveredAfterSeq = 40)
        assertEquals(listOf(20L to 1000L), connected.store.segments.getValue(scope).map { it.lowSeq to it.highSeq })
        assertEquals(1000L, connected.store.cursors.getValue(scope).newestLoadedSeq)

        // Twin: a message exists between the tail and the page. An island above the window: the mirror does not move.
        val apart = harness()
        apart.seedWindow()
        val before = apart.store.cursors.getValue(scope)
        apart.extend(ThreadDurablePageDirection.Newer, anchorSeq = 40, page, limit = 3, coveredAfterSeq = 41)
        assertEquals(listOf(20L to 40L, 42L to 1000L), apart.store.segments.getValue(scope).map { it.lowSeq to it.highSeq })
        assertEquals(before, apart.store.cursors.getValue(scope))
    }

    @Test
    fun aNewerPageNeverProvesTheHeadOrTheChannelsStart() = runBlocking {
        // The page reaches the high-water, the window even says so, and coveredAfterSeq is 0: still no proof of either.
        val h = harness()
        val page = adjacentPageOf(adjacentPageNode("m50", 50))
        h.seedWindow()
        h.writer.extendWithAdjacentPage(
            partition, "channel-1", ThreadDurablePageDirection.Newer, 40, page, 3, null, null,
            channelWindow(coveredAfterSeq = 0, coveredThroughSeq = 50, completeThroughLatest = true).copy(remoteHighWaterSeq = 50),
        ) { true }
        val cursor = h.store.cursors.getValue(scope)
        assertEquals(50L, cursor.newestLoadedSeq)
        assertEquals(false, cursor.hasLoadedNewest, "only a direction: latest fetch proves the head")
        assertEquals(false, cursor.hasLoadedOldest)
    }

    @Test
    fun thePageWithoutAWindowNamesNoScopeAndIsNotWritten() = runBlocking {
        // An older server, or a `by-activity` page (a forum channel): no scope key, no range.
        val h = harness()
        h.seedWindow()
        h.writer.extendWithAdjacentPage(
            partition, "channel-1", ThreadDurablePageDirection.Older, 20, adjacentPageOf(adjacentPageNode("m5", 5)), 3, null, null,
        ) { true }
        assertEquals(listOf(20L, 30L, 40L), h.store.seqs(scope))
        assertTrue(h.logs.any { "outcome=no_message_window" in it }, h.logs.toString())
    }

    @Test
    fun thePageIsWrittenUnderTheScopeItsWindowNames() = runBlocking {
        val h = harness(channelIds = setOf("channel-1", "channel-2"))
        val sibling = SyncScopeKey(partition, "channel-2", "channel")
        h.seedWindow()
        h.seedWindow(sibling)
        h.writer.extendWithAdjacentPage(
            partition, "channel-1", ThreadDurablePageDirection.Older, 20,
            adjacentPageOf(adjacentPageNode("m5", 5, channelId = "channel-2")), 3, null, null,
            channelWindow(coveredAfterSeq = 4, coveredThroughSeq = 5, scopeId = "channel-2"),
        ) { true }
        assertEquals(listOf(20L, 30L, 40L), h.store.seqs(scope))
        assertEquals(listOf(5L, 20L, 30L, 40L), h.store.seqs(sibling))
    }

    @Test
    fun theLastMinuteOfARangeIsNotSealed() = runBlocking {
        // adjacentPageNode's createdAt is seq seconds after 2026-07-17T00:00:00Z: seq 50 is 70 s old, seq 100 is 20 s old.
        val now = checkNotNull(parseRaftTimestampEpochMillisOrNull("2026-07-17T00:02:00.000Z", localOffsetMinutes = 0))
        val page = adjacentPageOf(adjacentPageNode("m50", 50), adjacentPageNode("m100", 100))

        val connected = harness(nowMs = { now })
        connected.seedWindow()
        connected.extend(ThreadDurablePageDirection.Newer, anchorSeq = 40, page, limit = 3)
        assertEquals(listOf(20L to 99L), connected.store.segments.getValue(scope).map { it.lowSeq to it.highSeq }, "stops below the recent row")
        assertEquals(listOf(20L, 30L, 40L, 50L, 100L), connected.store.seqs(scope), "connected to the head: kept as a loose fact")

        // Twin: the same page in an island above the window. A loose fact would be read offline, so it is left out.
        val island = harness(nowMs = { now })
        island.seedWindow()
        island.extend(ThreadDurablePageDirection.Newer, anchorSeq = 40, page, limit = 3, coveredAfterSeq = 45)
        assertEquals(listOf(20L to 40L, 46L to 99L), island.store.segments.getValue(scope).map { it.lowSeq to it.highSeq })
        assertEquals(listOf(20L, 30L, 40L, 50L), island.store.seqs(scope))

        // Control: a minute later both rows are sealed.
        val later = harness(nowMs = { now + 60_000L })
        later.seedWindow()
        later.extend(ThreadDurablePageDirection.Newer, anchorSeq = 40, page, limit = 3)
        assertEquals(listOf(20L to 100L), later.store.segments.getValue(scope).map { it.lowSeq to it.highSeq })
    }

    @Test
    fun pageEntirelyInsideTheWindowIsARefreshWithTheCursorUnchanged() = runBlocking {
        val older = harness()
        older.seedWindow()
        val before = older.store.cursors.getValue(scope)
        older.extend(ThreadDurablePageDirection.Older, anchorSeq = 40, adjacentPageOf(adjacentPageNode("m20", 20), adjacentPageNode("m30", 30)), limit = 3)
        assertTrue(older.logs.any { "outcome=committed facts=2" in it }, "the known facts are refreshed")
        assertEquals(before, older.store.cursors.getValue(scope))
        assertEquals(listOf(20L, 30L, 40L), older.store.seqs(scope))

        val newer = harness()
        newer.seedWindow()
        val newerBefore = newer.store.cursors.getValue(scope)
        // A page that does NOT pass the newest edge: it must not clear hasLoadedNewest.
        newer.extend(ThreadDurablePageDirection.Newer, anchorSeq = 20, adjacentPageOf(adjacentPageNode("m30", 30), adjacentPageNode("m40", 40)), limit = 2)
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
        assertEquals(false, cursor.hasLoadedNewest)
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
        assertEquals(false, cursor.hasLoadedNewest, "a short page reaching the top proves nothing")
    }

    @Test
    fun aContextIslandIsStoredButIsNotTheHeadAndTheMirrorStays() = runBlocking {
        val h = harness()
        h.seedWindow()
        val before = h.store.cursors.getValue(scope)
        // Above the window, with no shared message.
        h.context("m100", adjacentPageOf(adjacentPageNode("m90", 90), adjacentPageNode("m100", 100)), hasOlder = true, hasNewer = false)
        assertEquals(listOf(20L, 30L, 40L, 90L, 100L), h.store.seqs(scope))
        val segments = h.store.segments.getValue(scope)
        assertEquals(listOf(20L to 40L, 90L to 100L), segments.map { it.lowSeq to it.highSeq })
        assertEquals(20L to 40L, CanonicalMessageV2SegmentAlgebra.head(segments)?.let { it.lowSeq to it.highSeq })
        assertEquals(before, h.store.cursors.getValue(scope), "no mirror column moves")
        assertTrue(h.logs.any { "outcome=committed reason=island_stored facts=2" in it }, h.logs.toString())

        val reader = CanonicalMessageV2DurableLastKnownReader(h.store)
        assertEquals(listOf(20L, 30L, 40L), reader.read(partition, "channel-1")?.envelopes?.map { it.fact.seq }, "users see the same window")
    }

    @Test
    fun aContextPageSharingAMessageMergesAndOneThatOnlyTouchesDoesNot() = runBlocking {
        val shared = harness()
        shared.seedWindow()
        shared.context("m50", adjacentPageOf(adjacentPageNode("m40", 40), adjacentPageNode("m50", 50)), hasOlder = true, hasNewer = true)
        assertEquals(listOf(20L to 50L), shared.store.segments.getValue(scope).map { it.lowSeq to it.highSeq })
        assertEquals(false, shared.store.cursors.getValue(scope).hasLoadedNewest)
        assertTrue(shared.logs.any { "reason=head_extended facts=2" in it }, shared.logs.toString())

        // Twin: the page's seqs touch the window (41 is right after 40), but no message is shared.
        val touching = harness()
        touching.seedWindow()
        touching.context("m41", adjacentPageOf(adjacentPageNode("m41", 41), adjacentPageNode("m50", 50)), hasOlder = true, hasNewer = true)
        assertEquals(listOf(20L, 30L, 40L), touching.store.seqs(scope))
        assertTrue(touching.logs.any { "reason=touches_without_shared_ids" in it }, touching.logs.toString())
    }

    @Test
    fun aContextPageOfANeverOpenedChannelCreatesNoScreen() = runBlocking {
        val h = harness()
        h.context("m100", adjacentPageOf(adjacentPageNode("m100", 100)), hasOlder = true, hasNewer = false, userDriven = false, channelType = "channel")
        assertEquals(listOf(100L), h.store.seqs(scope), "the fact is kept for Phase 2d")
        val cursor = h.store.cursors.getValue(scope)
        assertEquals(null, cursor.oldestLoadedSeq)
        assertEquals(null, cursor.newestLoadedSeq)
        assertEquals(null, cursor.latestSeq)
        val reader = CanonicalMessageV2DurableLastKnownReader(h.store)
        assertEquals(null, reader.read(partition, "channel-1"), "offline stays what it was")
        assertEquals(emptyList<String>(), h.network.paths, "the caller named the type: no extra request")
    }

    @Test
    fun aContextPageIsWrittenUnderItsResponseScopeNeverTheParents() = runBlocking {
        // The response's channelId is the thread. The parent's window is untouched and shares nothing with it.
        val h = harness(channelIds = setOf("channel-1"))
        h.seedWindow()
        val before = h.store.seqs(scope)
        h.writer.persistContextPage(
            partition, "thread-9", "r5", adjacentPageOf(adjacentPageNode("r5", 5, channelId = "thread-9")), true, true,
            ThreadDurableReplyParent("channel-1", "m20"),
        ) { true }
        assertEquals(before, h.store.seqs(scope))
    }

    @Test
    fun aContextPageNeverAsksForThreadSummaries() = runBlocking {
        val h = harness()
        h.seedWindow()
        h.context(
            "m90", adjacentPageOf(adjacentPageNode("m80", 80, threadId = "thread-9"), adjacentPageNode("m90", 90)),
            hasOlder = true, hasNewer = false, replyCounts = mapOf("m80" to 3),
        )
        assertEquals(3, h.store.facts.getValue(scope).getValue("m80").fact.replyCount)
        assertEquals(emptyList<String>(), h.network.paths)
    }

    @Test
    fun onlyAWriteThatMovesTheHeadVoidsTheEnterBaseline() = runBlocking {
        suspend fun enterAround(write: suspend (Harness) -> Unit): CanonicalMessageV2ScopeContinuity {
            val h = harness(sameEngineForWriter = true)
            h.seedWindow()
            h.canary.noteTransportEpoch(partition, 1L)
            h.canary.noteRoomsJoined(partition, 1L)
            val baseline = requireNotNull(h.canary.beginEnterBaseline(partition, "channel-1", nonMember = false))
            write(h)
            val frame = CanonicalMessageV2SnapshotFrame(scope, emptyList(), h.store.cursors.getValue(scope))
            h.writerEngine.withScopeWriter(scope) { h.canary.onEnterCommitted(baseline, frame) }
            return h.canary.continuityOf(scope)
        }
        // An island above the window: the enter still becomes the realtime baseline.
        assertEquals(
            CanonicalMessageV2ScopeContinuity.Continuous,
            enterAround { it.context("m100", adjacentPageOf(adjacentPageNode("m100", 100)), hasOlder = true, hasNewer = false) },
        )
        // Twin: a page sharing a message moves the head's top, which nothing proves continuous.
        assertEquals(
            CanonicalMessageV2ScopeContinuity.Discontinuous,
            enterAround { it.context("m50", adjacentPageOf(adjacentPageNode("m40", 40), adjacentPageNode("m50", 50)), hasOlder = true, hasNewer = false) },
        )
    }

    @Test
    fun latestPageReachingTheWindowExtendsItAndOneSeqShortKeepsTheWindowAsAnIsland() = runBlocking {
        // Message storage Phase 2a. Twin pair with IDENTICAL rows (m60, m70, a full page): only the server's
        // messageWindow differs. A says nothing lies between seq 40 and the page ([41, 70]: touches the stored
        // [20, 40]); B says seq 41 exists and is not on the page ([42, 70]: one seq short).
        val page = adjacentPageOf(adjacentPageNode("m60", 60), adjacentPageNode("m70", 70))
        val joined = harness()
        joined.seedWindow()
        joined.latest(page, limit = 2, messageWindow = channelWindow(coveredAfterSeq = 40, coveredThroughSeq = 70))
        assertEquals(listOf(20L, 30L, 40L, 60L, 70L), joined.store.seqs(scope))
        assertEquals(listOf(20L to 70L), joined.store.segments.getValue(scope).map { it.lowSeq to it.highSeq })
        assertEquals(20L, joined.store.cursors.getValue(scope).oldestLoadedSeq)
        assertEquals(70L, joined.store.cursors.getValue(scope).newestLoadedSeq)
        assertTrue(joined.logs.any { "outcome=window_extended new=60..70" in it }, joined.logs.toString())

        val island = harness()
        island.seedWindow()
        island.latest(page, limit = 2, messageWindow = channelWindow(coveredAfterSeq = 41, coveredThroughSeq = 70))
        assertEquals(listOf(20L, 30L, 40L, 60L, 70L), island.store.seqs(scope), "nothing stored is dropped")
        assertEquals(listOf(20L to 40L, 42L to 70L), island.store.segments.getValue(scope).map { it.lowSeq to it.highSeq })
        val cursor = island.store.cursors.getValue(scope)
        assertEquals(60L, cursor.oldestLoadedSeq, "the header mirrors the new head")
        assertEquals(70L, cursor.newestLoadedSeq)
        assertTrue(island.logs.any { "outcome=island_kept new=60..70" in it }, island.logs.toString())
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
        // A continuous realtime window (committed by an enter snapshot, task #168 step 3).
        owned.enterWindow(foldInput("channel-1"))
        assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, owned.canary.continuityOf(scope))
        // Task #168 device FAIL: the canary's own scope is no longer skipped — its catch-up page is written.
        owned.latest(page, limit = 1)
        assertEquals(listOf(7L, 100L), owned.store.seqs(scope), "the enter's page stays (an older island)")
        assertEquals(100L, owned.store.cursors.getValue(scope).newestLoadedSeq)
        assertTrue(owned.logs.any { "canonical_v2_latest_page" in it && "outcome=island_kept" in it }, owned.logs.toString())
        assertTrue(owned.logs.none { "canary_owned" in it }, owned.logs.toString())
        // #3008 kept for the catch-up writer: nothing proves the page continuous with the realtime stream.
        assertEquals(CanonicalMessageV2ScopeContinuity.Discontinuous, owned.canary.continuityOf(scope))
    }

    @Test
    fun noStoredKindWritesNothingAndNeverCreatesOne() = runBlocking {
        val h = harness()
        // Same page and anchor as the positive older case, but no window was ever committed.
        h.extend(ThreadDurablePageDirection.Older, anchorSeq = 20, adjacentPageOf(adjacentPageNode("m5", 5), adjacentPageNode("m10", 10)), limit = 3)
        assertTrue(h.store.cursors.isEmpty())
        assertEquals(0, h.store.incrementals.size)
        assertEquals(0, h.store.snapshots.size)
        assertTrue(h.logs.any { "scope=channel-1 direction=older outcome=no_window" in it }, h.logs.toString())
    }

    @Test
    fun canaryOwnedScopeIsExtendedLikeAnyOtherScope() = runBlocking {
        val h = harness(channelIds = setOf("channel-1", "channel-2"), sameEngineForWriter = true)
        h.enterWindow(foldInput("channel-1"))
        val canaryScope = SyncScopeKey(partition, "channel-1", "channel")
        // The realtime window's edge is seq 7 (its enter page). Task #168: the canary only writes realtime frames, so
        // the page is written here.
        h.extend(ThreadDurablePageDirection.Older, anchorSeq = 7, adjacentPageOf(adjacentPageNode("m5", 5)), limit = 3, channelId = "channel-1")
        assertEquals(listOf(5L, 7L), h.store.seqs(canaryScope))
        assertEquals(5L, h.store.cursors.getValue(canaryScope).oldestLoadedSeq)
        assertTrue(h.logs.any { "scope=channel-1 direction=older outcome=committed facts=1" in it }, h.logs.toString())
        // Task #168: an older page only moves the oldest edge; the realtime window stays continuous.
        assertEquals(CanonicalMessageV2ScopeContinuity.Continuous, h.canary.continuityOf(canaryScope))

        // Twin: a sibling scope on the same partition that the canary does not own is extended the same way.
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
    fun identityChangeWritesNothing() = runBlocking {
        val changed = harness()
        changed.seedWindow()
        changed.extend(ThreadDurablePageDirection.Older, 20, adjacentPageOf(adjacentPageNode("m5", 5)), limit = 3, stillCurrent = { false })
        assertEquals(listOf(20L, 30L, 40L), changed.store.seqs(scope))
        assertTrue(changed.logs.any { "outcome=identity_changed" in it }, changed.logs.toString())

        // Twin: same seed, current identity.
        val on = harness()
        on.seedWindow()
        on.extend(ThreadDurablePageDirection.Older, 20, adjacentPageOf(adjacentPageNode("m5", 5)), limit = 3)
        assertEquals(listOf(5L, 20L, 30L, 40L), on.store.seqs(scope))
    }

    @Test
    fun aRowWithoutFullV6KeysIsLeftOutAndTheClaimSplitsAroundIt() = runBlocking {
        val h = harness()
        h.seedWindow()
        h.extend(ThreadDurablePageDirection.Older, 20, adjacentPageOf(adjacentPageNode("m5", 5), adjacentPageNode("m10", 10, thin = true)), limit = 3)
        assertEquals(listOf(5L, 20L, 30L, 40L), h.store.seqs(scope))
        assertEquals(listOf(5L to 9L, 11L to 40L), h.store.segments.getValue(scope).map { it.lowSeq to it.highSeq })
        assertEquals(11L, CanonicalMessageV2SegmentAlgebra.head(h.store.segments.getValue(scope))?.lowSeq)

        // Twin: the same two nodes, both full.
        val full = harness()
        full.seedWindow()
        full.extend(ThreadDurablePageDirection.Older, 20, adjacentPageOf(adjacentPageNode("m5", 5), adjacentPageNode("m10", 10)), limit = 3)
        assertEquals(listOf(5L to 40L), full.store.segments.getValue(scope).map { it.lowSeq to it.highSeq })
    }

    @Test
    fun pageSeqOnTheWrongSideOfTheAnchorIsRefused() = runBlocking {
        val older = harness()
        older.seedWindow()
        older.extend(ThreadDurablePageDirection.Older, 20, adjacentPageOf(adjacentPageNode("m10", 10), adjacentPageNode("m25", 25)), limit = 3)
        assertEquals(listOf(20L, 30L, 40L), older.store.seqs(scope))
        assertTrue(older.logs.any { "direction=older outcome=wrong_side_of_anchor" in it }, older.logs.toString())

        val newer = harness()
        newer.seedWindow()
        newer.extend(ThreadDurablePageDirection.Newer, 40, adjacentPageOf(adjacentPageNode("m35", 35), adjacentPageNode("m50", 50)), limit = 3)
        assertEquals(listOf(20L, 30L, 40L), newer.store.seqs(scope))
        assertTrue(newer.logs.any { "direction=newer outcome=wrong_side_of_anchor" in it }, newer.logs.toString())

        // An anchor-equal seq is not "older than the anchor" either.
        val equal = harness()
        equal.seedWindow()
        equal.extend(ThreadDurablePageDirection.Older, 20, adjacentPageOf(adjacentPageNode("m20", 20)), limit = 3)
        assertTrue(equal.logs.any { "outcome=wrong_side_of_anchor" in it }, equal.logs.toString())
    }

    @Test
    fun threadParentInAPageTakesItsReplyCountFromTheResponseAndNoRequestIsMade() = runBlocking {
        val h = harness()
        h.seedWindow()
        h.extend(
            ThreadDurablePageDirection.Older, 20, adjacentPageOf(adjacentPageNode("m10", 10, threadId = "thread-9")), limit = 3,
            replyCounts = mapOf("m10" to 3),
        )
        assertEquals(emptyList<String>(), h.network.paths)
        assertEquals(3, h.store.facts.getValue(scope).getValue("m10").fact.replyCount)

        // Twin: the response has no count for it. The row is left out, still without a request.
        val unknown = harness()
        unknown.seedWindow()
        unknown.extend(ThreadDurablePageDirection.Older, 20, adjacentPageOf(adjacentPageNode("m10", 10, threadId = "thread-9")), limit = 3)
        assertEquals(emptyList<String>(), unknown.network.paths)
        assertEquals(listOf(20L, 30L, 40L), unknown.store.seqs(scope))
    }

    @Test
    fun emptyPageWritesNothingAndClaimsNothing() = runBlocking {
        val h = harness()
        h.seedWindow()
        h.extend(ThreadDurablePageDirection.Older, 20, JsonArray(emptyList()), limit = 3)
        assertEquals(false, h.store.cursors.getValue(scope).hasLoadedOldest, "an empty page is skipped, not folded")
        assertTrue(h.logs.any { "outcome=empty_page" in it }, h.logs.toString())

        // An empty page's window: coveredAfterSeq is the high-water with no row, which must not read as a range.
        assertEquals(
            null,
            channelWindow(coveredAfterSeq = 900, coveredThroughSeq = 900, completeThroughLatest = false)
                .toCoverage("server-1", "channel-1", historyLimited = false, CanonicalMessageV2SegmentSource.Older),
        )
    }

    @Test
    fun anOlderPageOfATaskConversationIsStoredUnderItsTaskChannel() = runBlocking {
        // Task #proj-mobile:#46. The page rows name no context, so the parent comes from `channels/conv-1`.
        val h = taskHarness()
        h.seedWindow(taskScope, taskParentChannelId = "channel-1")
        h.extend(
            ThreadDurablePageDirection.Older, anchorSeq = 20,
            adjacentPageOf(adjacentPageNode("m5", 5, channelId = "conv-1"), adjacentPageNode("m10", 10, channelId = "conv-1")),
            limit = 3, channelId = "conv-1", coveredAfterSeq = 0,
        )

        assertEquals(listOf(5L, 10L, 20L, 30L, 40L), h.store.seqs(taskScope))
        assertEquals(listOf(1L to 40L), h.store.segments.getValue(taskScope).map { it.lowSeq to it.highSeq })
        val cursor = h.store.cursors.getValue(taskScope)
        assertEquals(5L, cursor.oldestLoadedSeq)
        assertTrue(cursor.hasLoadedOldest, "coveredAfterSeq 0: nothing below the page")
        assertEquals(40L, cursor.newestLoadedSeq)
        assertEquals(null, cursor.parentScopeId, "a task header names no reply parent")
        val context = h.store.facts.getValue(taskScope).getValue("m5").context as CanonicalConversationContextV2.TopLevel
        assertEquals(TopLevelScopeRefV2.Task("server-1", "conv-1", "channel-1"), context.scope)
        assertEquals(listOf("channels/conv-1"), h.network.paths)
        assertTrue(h.logs.any { "scope=conv-1 direction=older outcome=committed facts=2" in it }, h.logs.toString())
    }

    @Test
    fun aTaskPageWhoseRowsNameTheirChannelNeedsNoRequest() = runBlocking {
        val node = { id: String, seq: Long ->
            adjacentPageNode(id, seq, channelId = "conv-1", conversationContext = """{"channelType":"task","parentChannelId":"channel-1"}""")
        }
        // The server could not name the parent: only the rows do.
        val h = taskHarness(parentChannelId = null)
        h.seedWindow(taskScope, taskParentChannelId = "channel-1")
        h.extend(ThreadDurablePageDirection.Older, 20, adjacentPageOf(node("m10", 10)), limit = 3, channelId = "conv-1")
        assertEquals(listOf(10L, 20L, 30L, 40L), h.store.seqs(taskScope))
        assertEquals(listOf(10L to 40L), h.store.segments.getValue(taskScope).map { it.lowSeq to it.highSeq })
        assertEquals(emptyList<String>(), h.network.paths)

        // Twin: same page without the row context. Nothing names the parent, so nothing is written.
        val unknown = taskHarness(parentChannelId = null)
        unknown.seedWindow(taskScope, taskParentChannelId = "channel-1")
        unknown.extend(ThreadDurablePageDirection.Older, 20, adjacentPageOf(adjacentPageNode("m10", 10, channelId = "conv-1")), limit = 3, channelId = "conv-1")
        assertEquals(listOf(20L, 30L, 40L), unknown.store.seqs(taskScope))
        assertEquals(listOf(20L to 40L), unknown.store.segments.getValue(taskScope).map { it.lowSeq to it.highSeq })
        assertTrue(unknown.logs.any { "scope=conv-1 direction=older outcome=task_parent_unknown" in it }, unknown.logs.toString())
    }

    @Test
    fun aNewerAndALatestPageOfATaskConversationAreStoredToo() = runBlocking {
        val newer = taskHarness()
        newer.seedWindow(taskScope, taskParentChannelId = "channel-1")
        newer.extend(ThreadDurablePageDirection.Newer, 40, adjacentPageOf(adjacentPageNode("m50", 50, channelId = "conv-1")), limit = 3, channelId = "conv-1")
        assertEquals(listOf(20L to 50L), newer.store.segments.getValue(taskScope).map { it.lowSeq to it.highSeq })
        assertEquals(50L, newer.store.cursors.getValue(taskScope).newestLoadedSeq)

        val latest = taskHarness()
        latest.seedWindow(taskScope, taskParentChannelId = "channel-1")
        latest.latest(
            adjacentPageOf(adjacentPageNode("m60", 60, channelId = "conv-1"), adjacentPageNode("m70", 70, channelId = "conv-1")),
            limit = 2, channelId = "conv-1",
            messageWindow = channelWindow(coveredAfterSeq = 40, coveredThroughSeq = 70, scopeId = "conv-1"),
        )
        assertEquals(listOf(20L, 30L, 40L, 60L, 70L), latest.store.seqs(taskScope))
        assertEquals(listOf(20L to 70L), latest.store.segments.getValue(taskScope).map { it.lowSeq to it.highSeq })
        assertTrue(latest.logs.any { "scope=conv-1 outcome=window_extended new=60..70" in it }, latest.logs.toString())
    }

    @Test
    fun aChannelPageStillAsksForNoParent() = runBlocking {
        // Unchanged channel behaviour: the parent lookup is for task windows only.
        val h = harness()
        h.seedWindow()
        h.extend(ThreadDurablePageDirection.Older, 20, adjacentPageOf(adjacentPageNode("m10", 10)), limit = 3)
        assertEquals(listOf(10L to 40L), h.store.segments.getValue(scope).map { it.lowSeq to it.highSeq })
        assertEquals(emptyList<String>(), h.network.paths)
        val context = h.store.facts.getValue(scope).getValue("m10").context as CanonicalConversationContextV2.TopLevel
        assertEquals(TopLevelScopeRefV2.Channel("server-1", "channel-1"), context.scope)
    }

    private fun taskHarness(parentChannelId: String? = "channel-1") =
        harness(channelIds = setOf("conv-1"), channelType = "task", parentChannelId = parentChannelId)

    private val taskScope get() = SyncScopeKey(partition, "conv-1", "task")

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
            taskParentChannelId: SyncScopeId? = null,
        ) {
            val converted = CanonicalMessageV2RestSnapshotFetcher(network).convertPage(
                partition = partition,
                scopeId = target.scopeId,
                channelType = target.scopeKind,
                rawMessages = adjacentPageOf(*seqs.map { adjacentPageNode("m$it", it, channelId = target.scopeId) }.toTypedArray()),
                taskParentChannelId = taskParentChannelId,
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

        /** The enter snapshot of [input]'s channel (one fact), through the canary's baseline API. */
        suspend fun enterWindow(input: CanonicalMessageFoldInput) {
            val envelope = requireNotNull(input.toCanonicalMessageV2Envelope(partition, replyCount = 0))
            val frame = CanonicalMessageV2SnapshotFrame(
                scope = SyncScopeKey(partition, input.row.channelId, "channel"),
                envelopes = listOf(envelope),
                cursor = PersistedCursor(
                    latestSeq = envelope.fact.seq,
                    oldestLoadedSeq = envelope.fact.seq,
                    newestLoadedSeq = envelope.fact.seq,
                    hasLoadedOldest = false,
                    hasLoadedNewest = true,
                ),
            )
            assertEquals(CanonicalMessageV2CommitResult.Committed, commitEnterBaselineForTest(canary, writerEngine, frame))
        }

        suspend fun extend(
            direction: ThreadDurablePageDirection,
            anchorSeq: Long,
            rawMessages: JsonArray,
            limit: Int,
            channelId: SyncScopeId = "channel-1",
            coveredAfterSeq: Long? = null,
            historyLimited: Boolean = false,
            replyCounts: Map<String, Int>? = null,
            stillCurrent: () -> Boolean = { true },
        ) = writer.extendWithAdjacentPage(
            partition, channelId, direction, anchorSeq, rawMessages, limit, null, null,
            pagingWindowFor(direction, anchorSeq, rawMessages, channelId, coveredAfterSeq),
            historyLimited,
            replyCountByParentId = replyCounts,
            stillCurrent = stillCurrent,
        )

        suspend fun context(
            anchor: String,
            rawMessages: JsonArray,
            hasOlder: Boolean,
            hasNewer: Boolean,
            userDriven: Boolean = true,
            channelType: String? = null,
            replyCounts: Map<String, Int>? = null,
        ) = writer.persistContextPage(
            partition, "channel-1", anchor, rawMessages, hasOlder, hasNewer, null,
            replyCountByParentId = replyCounts,
            userDriven = userDriven,
            channelType = channelType,
        ) { true }

        suspend fun latest(
            rawMessages: JsonArray,
            limit: Int,
            channelId: SyncScopeId = "channel-1",
            stillCurrent: () -> Boolean = { true },
            messageWindow: ChannelMessageWindow? = null,
        ) = writer.applyLatestPage(partition, channelId, rawMessages, limit, null, messageWindow, false, null, null, stillCurrent)
    }

    /**
     * `sameEngineForWriter = false` (default) gives the writer a fresh engine over the same store, so
     * the window is NOT baselined in the writer's process — the restore path production takes after
     * a cold start.
     */
    private fun harness(
        channelIds: Set<String> = setOf("channel-1"),
        threadSummariesBody: String? = "{}",
        sameEngineForWriter: Boolean = false,
        nowMs: () -> Long = { Long.MAX_VALUE / 2 },
        channelType: String = "channel",
        parentChannelId: String? = null,
    ): Harness {
        val network = RecordingNetworkClient(
            canonicalV2TestPage(),
            channelIds = channelIds,
            channelType = channelType,
            threadSummariesBody = threadSummariesBody,
            parentChannelId = parentChannelId,
        )
        val store = InMemoryCanonicalMessageV2Store()
        fun engine() = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        val seedEngine = engine()
        val writerEngine = if (sameEngineForWriter) seedEngine else engine()
        val canary = CanonicalMessageV2ProductionCanary(generationEngine = writerEngine)
        val logs = mutableListOf<String>()
        val writer = CanonicalMessageV2AdjacentPageWriter(
            canary = canary,
            generationEngine = writerEngine,
            fetcher = CanonicalMessageV2RestSnapshotFetcher(network, logs::add),
            logger = logs::add,
            nowMs = nowMs,
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
    /** Raw `conversationContext` JSON object of the row; omitted when null. */
    conversationContext: String? = null,
): String {
    val thread = threadId?.let { "\"$it\"" } ?: "null"
    val reactions = if (thin) "" else "\"reactions\":[],"
    val context = conversationContext?.let { ",\"conversationContext\":$it" }.orEmpty()
    fun two(value: Long) = value.toString().padStart(2, '0')
    val time = "${two(seq / 3600 % 24)}:${two(seq / 60 % 60)}:${two(seq % 60)}"
    return """{"id":"$id","seq":$seq,"channelId":"$channelId","senderType":"user","senderId":"user-1",""" +
        """"content":"hello $id","messageType":"chat","randomId":null,"threadId":$thread,""" +
        """"createdAt":"2026-07-17T$time.000Z","senderName":"Alice","senderDescription":null,""" +
        """"senderMembershipStatus":null,"mentions":[],"attachments":[],$reactions"commentRef":null,"actionMetadata":null$context}"""
}

internal fun adjacentPageOf(vararg nodes: String): JsonArray =
    Json.parseToJsonElement("[${nodes.joinToString(",")}]") as JsonArray

/** The server `messageWindow` of a `latest`-direction page of [scopeId] on server-1. */
internal fun channelWindow(
    coveredAfterSeq: Long,
    coveredThroughSeq: Long,
    completeThroughLatest: Boolean = true,
    scopeId: String = "channel-1",
): ChannelMessageWindow = ChannelMessageWindow(
    schemaVersion = CHANNEL_MESSAGE_WINDOW_SCHEMA_VERSION,
    domain = CHANNEL_MESSAGE_WINDOW_DOMAIN,
    serverId = "server-1",
    scopeId = scopeId,
    coveredAfterSeq = coveredAfterSeq,
    coveredThroughSeq = coveredThroughSeq,
    completeThroughLatest = completeThroughLatest,
)

/**
 * The server `messageWindow` of a `before` / `after` page of [rawMessages]: the page's last row, and a predecessor that is
 * the row just below an older page or the anchor of a newer one, unless [coveredAfterSeq] says otherwise.
 */
internal fun pagingWindowFor(
    direction: ThreadDurablePageDirection,
    anchorSeq: Long,
    rawMessages: JsonArray,
    scopeId: String = "channel-1",
    coveredAfterSeq: Long? = null,
): ChannelMessageWindow {
    val seqs = rawMessages.mapNotNull { (it as? kotlinx.serialization.json.JsonObject)?.get("seq")?.toString()?.toLongOrNull() }
    val after = coveredAfterSeq ?: when (direction) {
        ThreadDurablePageDirection.Older -> (seqs.minOrNull() ?: anchorSeq) - 1L
        ThreadDurablePageDirection.Newer -> anchorSeq
    }
    return channelWindow(after, seqs.maxOrNull() ?: after, completeThroughLatest = false, scopeId = scopeId)
}

/** The JSON of [window] for a raw page body. */
internal fun channelWindowJson(window: ChannelMessageWindow): String =
    kotlinx.serialization.json.Json.encodeToString(ChannelMessageWindow.serializer(), window)

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
        // As the SQLite store: the legacy window is the scope's (head) segment.
        segments[frame.scope] = listOfNotNull(frame.cursor.legacyWindowSegment(verifiedAtMs = 0L))
        return CanonicalMessageV2StoreWriteResult()
    }

    override suspend fun applyIncremental(frame: CanonicalMessageV2IncrementalFrame): CanonicalMessageV2StoreWriteResult {
        incrementals += frame
        val scopeFacts = facts.getOrPut(frame.scope) { mutableMapOf() }
        frame.envelopes.forEach { scopeFacts[it.fact.id] = it }
        cursors[frame.scope] = frame.cursor
        val window = frame.cursor.legacyWindowSegment(verifiedAtMs = 0L)
        segments[frame.scope] = if (window == null) {
            emptyList()
        } else {
            segments[frame.scope].orEmpty().filter { it.highSeq + 1L < window.effectiveLowSeq } + window
        }
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

    val segments = mutableMapOf<SyncScopeKey, List<build.raft.app.network.sync.CanonicalMessageV2Segment>>()
    var localRev = 0L

    /** Phase 2a: facts upserted (no localRev guard), the coverage unioned, the header mirroring the head. */
    override suspend fun applyCoverage(
        scope: SyncScopeKey,
        envelopes: List<CanonicalMessageEnvelopeV2>,
        coverage: build.raft.app.network.sync.CanonicalMessageV2Coverage?,
        revAtRequest: Long,
        userDriven: Boolean,
        threadParent: build.raft.app.network.sync.CanonicalMessageV2ThreadParent?,
        insertOnly: Boolean,
        unknownFieldsByFactId: Map<String, Set<build.raft.app.canonical.message.MessageAggregateField>>,
        runPartitionRetention: Boolean,
        excludedSeqs: Set<Long>,
    ): CanonicalMessageV2StoreWriteResult {
        localRev += 1
        val scopeFacts = facts.getOrPut(scope) { mutableMapOf() }
        envelopes.forEach { scopeFacts[it.fact.id] = it }
        val unioned = coverage?.excluding(excludedSeqs).orEmpty().fold(segments[scope].orEmpty()) { acc, piece ->
            build.raft.app.network.sync.CanonicalMessageV2SegmentAlgebra.union(acc, piece.toSegment(localRev))
        }
        segments[scope] = unioned
        val written = coverageHeader(cursors[scope], unioned, scopeFacts.values, threadParent, envelopes)
        cursors[scope] = written
        snapshots += CanonicalMessageV2SnapshotFrame(scope, envelopes, written)
        return CanonicalMessageV2StoreWriteResult(committedCursor = written)
    }

    override suspend fun readSegments(scope: SyncScopeKey) = segments[scope].orEmpty()

    override suspend fun maxLocalRev(partition: DurablePartitionKey): Long = localRev
}

/** The header a coverage write commits: [stored] (or a new one) with its window mirroring the head segment. */
internal fun coverageHeader(
    stored: PersistedCursor?,
    segments: List<build.raft.app.network.sync.CanonicalMessageV2Segment>,
    facts: Collection<CanonicalMessageEnvelopeV2>,
    threadParent: build.raft.app.network.sync.CanonicalMessageV2ThreadParent?,
    envelopes: List<CanonicalMessageEnvelopeV2>,
): PersistedCursor {
    val base = stored ?: PersistedCursor(
        latestSeq = null,
        oldestLoadedSeq = null,
        newestLoadedSeq = null,
        hasLoadedOldest = false,
        hasLoadedNewest = false,
        parentScopeKind = threadParent?.parentScopeKind,
        parentScopeId = threadParent?.parentScopeId,
        rootMessageId = threadParent?.rootMessageId,
    )
    val head = build.raft.app.network.sync.CanonicalMessageV2SegmentAlgebra.head(segments)
    val inHead = head?.let { h -> facts.map { it.fact.seq }.filter { it in h } }.orEmpty()
    val bounds = if (inHead.isEmpty()) null else inHead.min() to inHead.max()
    val movesLatest = envelopes.map { it.fact.seq }.filter { seq ->
        build.raft.app.network.sync.CanonicalMessageV2SegmentAlgebra.memberOf(segments, seq).let { it == null || it == head }
    }
    return base.mirroring(head, bounds).copy(latestSeq = (listOfNotNull(base.latestSeq) + movesLatest).maxOrNull())
}
