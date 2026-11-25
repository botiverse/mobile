package build.raft.app.network.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.data.RaftSqlDatabase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task #1123 read entry against the real SQLite schema: the reader returns exactly what the V2
 * writer committed for the caller's own partition, and nothing in every other case.
 */
class CanonicalMessageV2DurableLastKnownReaderTest {
    @Test
    fun readsBackTheCommittedTopLevelBaselineForTheSamePartition() = withFreshDatabase { database ->
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L })
        val scope = SyncScopeKey(partitionA, "channel-a", "channel")
        val cursor = PersistedCursor(3, 1, 3, hasLoadedOldest = true, epoch = "epoch-v2")
        val logs = mutableListOf<String>()
        val reader = CanonicalMessageV2DurableLastKnownReader(store, logger = logs::add)

        runBlocking {
            store.applySnapshot(CanonicalMessageV2SnapshotFrame(scope, listOf(envelope(1), envelope(3)), cursor))
            val frame = assertNotNull(reader.read(partitionA, "channel-a"))
            assertEquals(scope, frame.scope)
            assertEquals(listOf("message-1", "message-3"), frame.envelopes.map { it.fact.id })
            assertEquals(cursor, frame.cursor)
        }
        assertTrue(logs.isEmpty(), logs.toString())
    }

    @Test
    fun anotherPrincipalOnTheSameServerReadsNothing() = withFreshDatabase { database ->
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L })
        val reader = CanonicalMessageV2DurableLastKnownReader(store)
        runBlocking {
            store.applySnapshot(CanonicalMessageV2SnapshotFrame(SyncScopeKey(partitionA, "channel-a", "channel"), listOf(envelope(1)), cursor()))
            // Positive control: the writer's own partition sees the frame.
            assertNotNull(reader.read(partitionA, "channel-a"))
            assertNull(reader.read(partitionA.copy(principalId = "principal-b"), "channel-a"))
            assertNull(reader.read(partitionA.copy(environmentId = "env-other"), "channel-a"))
            assertNull(reader.read(partitionA, "channel-b"))
        }
    }

    @Test
    fun poisonedScopeReadsNothingAndLogsInsteadOfThrowing() = withFreshDatabase { database ->
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L })
        val scope = SyncScopeKey(partitionA, "channel-a", "channel")
        val logs = mutableListOf<String>()
        val reader = CanonicalMessageV2DurableLastKnownReader(store, logger = logs::add)
        runBlocking {
            store.applySnapshot(CanonicalMessageV2SnapshotFrame(scope, listOf(envelope(1)), cursor()))
            database.queries.updateCanonicalFact(
                seq = 1,
                createdAt = "2026-07-16T00:00:00.000Z",
                canonicalFactJson = "malformed",
                id = "message-1",
                environmentId = scope.environmentId,
                serverId = scope.serverId,
                principalId = scope.principalId,
                consumerId = scope.consumerId,
                scopeKind = scope.scopeKind,
                channelId = scope.scopeId,
            )
            assertFailsWith<CanonicalMessageV2ScopePoisoned> { store.restore(scope) }
            assertNull(reader.read(partitionA, "channel-a"))
        }
        assertEquals(1, logs.size, logs.toString())
        assertTrue(logs.single().contains("outcome=poisoned"), logs.single())
    }

    @Test
    fun boundedTimelineReadNeverCallsFullRestoreAndBoundsRowsToLimit() = withFreshDatabase { database ->
        var clock = 100L
        val realStore = SqlDelightCanonicalMessageV2Store(database = database, now = { clock })
        val countingStore = RestoreCountingStore(realStore)
        val scope = SyncScopeKey(partitionA, "channel-a", "channel")
        val reader = CanonicalMessageV2DurableLastKnownReader(countingStore)

        runBlocking {
            realStore.applyCoverage(
                scope = scope,
                envelopes = (1L..15L).map(::envelope),
                coverage = CanonicalMessageV2Coverage(1L, 15L, reachesOldest = true, headAtVerify = true, CanonicalMessageV2SegmentSource.Latest),
                revAtRequest = 0L,
                userDriven = true,
            )
            // Poison an old row far outside the newest 5-row page: bounded Newest read must succeed
            // without decoding row 1, proving SQLite LIMIT bounds the scan.
            database.queries.updateCanonicalFact(
                seq = 1L,
                createdAt = "2026-07-16T00:00:00.000Z",
                canonicalFactJson = "malformed-far-away",
                id = "message-1",
                environmentId = scope.environmentId,
                serverId = scope.serverId,
                principalId = scope.principalId,
                consumerId = scope.consumerId,
                scopeKind = scope.scopeKind,
                channelId = scope.scopeId,
            )

            clock = 500L
            val newest = assertNotNull(reader.readTimeline(partitionA, "channel-a", ThreadDurableTimelineAnchor.Newest(limit = 5)))
            assertEquals(listOf(11L, 12L, 13L, 14L, 15L), newest.envelopes.map { it.fact.seq })
            assertTrue(newest.gaps.isEmpty())
            assertTrue(newest.hasOlderStored)
            assertFalse(newest.hasNewerStored)
            assertFalse(newest.reachesOldest)
            assertTrue(newest.reachesHead)
            assertTrue(newest.insideProvenSegment)
            assertEquals(0, countingStore.restoreCalls, "readTimeline must never call full store.restore()")

            // Reading the older page [6..10] also stays bounded and avoids row 1.
            val middle = assertNotNull(
                reader.readTimeline(partitionA, "channel-a", ThreadDurableTimelineAnchor.Before(11L, limit = 5, crossGaps = false)),
            )
            assertEquals(listOf(6L, 7L, 8L, 9L, 10L), middle.envelopes.map { it.fact.seq })
            assertTrue(middle.insideProvenSegment)
            assertTrue(middle.gaps.isEmpty())
            assertTrue(middle.hasOlderStored)
            assertTrue(middle.hasNewerStored)
            assertFalse(middle.reachesOldest)
            assertEquals(0, countingStore.restoreCalls)

            // Reaching the poisoned row 1 inside the requested window fails closed (returns null) and logs.
            val logs = mutableListOf<String>()
            val loggingReader = CanonicalMessageV2DurableLastKnownReader(countingStore, logger = logs::add)
            assertNull(loggingReader.readTimeline(partitionA, "channel-a", ThreadDurableTimelineAnchor.Before(6L, limit = 5, crossGaps = false)))
            assertTrue(logs.single().contains("outcome=poisoned"), logs.toString())
            assertEquals(0, countingStore.restoreCalls)
        }
    }

    @Test
    fun beforeAndAfterPagingRespectsSegmentBoundariesOnlineAndCrossesIslandsWithGapsOffline() = withFreshDatabase { database ->
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 10L })
        val scope = SyncScopeKey(partitionA, "channel-a", "channel")
        val reader = CanonicalMessageV2DurableLastKnownReader(store)

        runBlocking {
            // Lower island [1..5] (reachesOldest=true), gap (5, 20), head [20..25] (headAtVerify=true).
            store.applyCoverage(
                scope = scope,
                envelopes = (1L..5L).map(::envelope),
                coverage = CanonicalMessageV2Coverage(1L, 5L, reachesOldest = true, headAtVerify = false, CanonicalMessageV2SegmentSource.Context),
                revAtRequest = 0L,
                userDriven = false,
            )
            store.applyCoverage(
                scope = scope,
                envelopes = (20L..25L).map(::envelope),
                coverage = CanonicalMessageV2Coverage(20L, 25L, reachesOldest = false, headAtVerify = true, CanonicalMessageV2SegmentSource.Latest),
                revAtRequest = 0L,
                userDriven = true,
            )

            // Online interior read inside head (beforeSeq=24, limit=10): stops at head lower edge [20..23],
            // never leaks lower island [1..5], zero gaps.
            val onlineInterior = assertNotNull(
                reader.readTimeline(partitionA, "channel-a", ThreadDurableTimelineAnchor.Before(24L, limit = 10, crossGaps = false)),
            )
            assertEquals(listOf(20L, 21L, 22L, 23L), onlineInterior.envelopes.map { it.fact.seq })
            assertTrue(onlineInterior.insideProvenSegment)
            assertTrue(onlineInterior.gaps.isEmpty())
            assertTrue(onlineInterior.hasOlderStored, "lower island still sits on disk")
            assertFalse(onlineInterior.reachesOldest)

            // Online at head lower edge (beforeSeq=20): not inside proven segment -> empty so caller fetches network,
            // while still accurately reporting hasOlderStored = true (lower island sits on disk).
            val onlineEdge = assertNotNull(
                reader.readTimeline(partitionA, "channel-a", ThreadDurableTimelineAnchor.Before(20L, limit = 10, crossGaps = false)),
            )
            assertTrue(onlineEdge.envelopes.isEmpty())
            assertFalse(onlineEdge.insideProvenSegment)
            assertTrue(onlineEdge.hasOlderStored)
            assertFalse(onlineEdge.reachesOldest)

            // Offline at head lower edge (beforeSeq=20, crossGaps=true): crosses into lower island [1..5]
            // and emits boundary gap (5, 20), with reachesOldest=true!
            val offlineCross = assertNotNull(
                reader.readTimeline(partitionA, "channel-a", ThreadDurableTimelineAnchor.Before(20L, limit = 10, crossGaps = true)),
            )
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L), offlineCross.envelopes.map { it.fact.seq })
            assertEquals(listOf(CanonicalMessageV2TimelineGap(afterSeq = 5L, beforeSeq = 20L)), offlineCross.gaps)
            assertFalse(offlineCross.hasOlderStored)
            assertTrue(offlineCross.reachesOldest)

            // Online at seq=1 on reachesOldest=true lower island closes paging with 0 network calls!
            val seqOneReached = assertNotNull(
                reader.readTimeline(partitionA, "channel-a", ThreadDurableTimelineAnchor.Before(1L, limit = 10, crossGaps = false)),
            )
            assertTrue(seqOneReached.envelopes.isEmpty())
            assertTrue(seqOneReached.insideProvenSegment)
            assertTrue(seqOneReached.reachesOldest)

            // Symmetric After paging from lower island:
            // Online inside lower island (afterSeq=2): returns [3..5] without crossing into [20..25].
            val onlineAfterInterior = assertNotNull(
                reader.readTimeline(partitionA, "channel-a", ThreadDurableTimelineAnchor.After(2L, limit = 10, crossGaps = false)),
            )
            assertEquals(listOf(3L, 4L, 5L), onlineAfterInterior.envelopes.map { it.fact.seq })
            assertTrue(onlineAfterInterior.insideProvenSegment)
            assertTrue(onlineAfterInterior.gaps.isEmpty())
            assertTrue(onlineAfterInterior.hasNewerStored)
            assertFalse(onlineAfterInterior.reachesHead)

            // Online at lower island upper edge (afterSeq=5): empty + insideProvenSegment=false + hasNewerStored=true.
            val onlineAfterEdge = assertNotNull(
                reader.readTimeline(partitionA, "channel-a", ThreadDurableTimelineAnchor.After(5L, limit = 10, crossGaps = false)),
            )
            assertTrue(onlineAfterEdge.envelopes.isEmpty())
            assertFalse(onlineAfterEdge.insideProvenSegment)
            assertTrue(onlineAfterEdge.hasNewerStored)
            assertFalse(onlineAfterEdge.reachesHead)

            // Offline at lower island upper edge (afterSeq=5, crossGaps=true): crosses into [20..25] with gap (5, 20) and reachesHead=true!
            val offlineAfterCross = assertNotNull(
                reader.readTimeline(partitionA, "channel-a", ThreadDurableTimelineAnchor.After(5L, limit = 10, crossGaps = true)),
            )
            assertEquals(listOf(20L, 21L, 22L, 23L, 24L, 25L), offlineAfterCross.envelopes.map { it.fact.seq })
            assertEquals(listOf(CanonicalMessageV2TimelineGap(afterSeq = 5L, beforeSeq = 20L)), offlineAfterCross.gaps)
            assertFalse(offlineAfterCross.hasNewerStored)
            assertTrue(offlineAfterCross.reachesHead)

            // After(head.highSeq, crossGaps=true) at verified head reports reachesHead=true.
            val afterHead = assertNotNull(
                reader.readTimeline(partitionA, "channel-a", ThreadDurableTimelineAnchor.After(25L, limit = 10, crossGaps = true)),
            )
            assertTrue(afterHead.envelopes.isEmpty())
            assertFalse(afterHead.hasNewerStored)
            assertTrue(afterHead.reachesHead)
        }
    }

    @Test
    fun sparseSeqSegmentWithNoFactsBelowOldestDistinguishesReachesOldestTrueFromFalse() = withFreshDatabase { database ->
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 10L })
        val scope = SyncScopeKey(partitionA, "channel-a", "channel")
        val reader = CanonicalMessageV2DurableLastKnownReader(store)

        runBlocking {
            // Segment covers [8..20] (reachesOldest=false), but oldest actual fact in this channel is seq 10
            // (seqs 8..9 belonged to other channels).
            store.applyCoverage(
                scope = scope,
                envelopes = listOf(envelope(10L), envelope(20L)),
                coverage = CanonicalMessageV2Coverage(8L, 20L, reachesOldest = false, headAtVerify = true, CanonicalMessageV2SegmentSource.Latest),
                revAtRequest = 0L,
                userDriven = true,
            )
            val unreached = assertNotNull(
                reader.readTimeline(partitionA, "channel-a", ThreadDurableTimelineAnchor.Before(10L, limit = 10, crossGaps = false)),
            )
            assertTrue(unreached.envelopes.isEmpty())
            assertTrue(unreached.insideProvenSegment)
            assertFalse(unreached.reachesOldest, "unproven below 8 -> online must still fetch")

            // Same sparse facts, now proven reachesOldest=true: online closes paging with 0 network calls.
            store.applyCoverage(
                scope = scope,
                envelopes = emptyList(),
                coverage = CanonicalMessageV2Coverage(1L, 8L, reachesOldest = true, headAtVerify = false, CanonicalMessageV2SegmentSource.Older),
                revAtRequest = 0L,
                userDriven = true,
            )
            val reached = assertNotNull(
                reader.readTimeline(partitionA, "channel-a", ThreadDurableTimelineAnchor.Before(10L, limit = 10, crossGaps = false)),
            )
            assertTrue(reached.envelopes.isEmpty())
            assertTrue(reached.insideProvenSegment)
            assertTrue(reached.reachesOldest)
        }
    }

    @Test
    fun aroundMessageIdOpensIslandOnlyScopeAndIsolatesZeroAfterCountFromPoisonedNewerRow() = withFreshDatabase { database ->
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 10L })
        val scope = SyncScopeKey(partitionA, "channel-a", "channel")
        val reader = CanonicalMessageV2DurableLastKnownReader(store)

        runBlocking {
            // Context island only (no head-rooted segment).
            store.applyCoverage(
                scope = scope,
                envelopes = listOf(envelope(50L), envelope(52L), envelope(55L), envelope(58L), envelope(60L)),
                coverage = CanonicalMessageV2Coverage(50L, 60L, reachesOldest = false, headAtVerify = false, CanonicalMessageV2SegmentSource.Context),
                revAtRequest = 0L,
                userDriven = false,
            )

            // Unanchored open and legacy readBefore reject an island-only scope.
            assertNull(reader.readTimeline(partitionA, "channel-a", ThreadDurableTimelineAnchor.Newest(limit = 10)))
            assertNull(reader.readBefore(partitionA, "channel-a", beforeSeq = 60L, limit = 5))

            // Anchored open via selectFactLocationById opens right around message-55!
            val anchored = assertNotNull(
                reader.readTimeline(
                    partition = partitionA,
                    channelId = "channel-a",
                    anchor = ThreadDurableTimelineAnchor.AroundMessageId("message-55", beforeCount = 1, afterCount = 1),
                ),
            )
            assertEquals(listOf(52L, 55L, 58L), anchored.envelopes.map { it.fact.seq })
            assertTrue(anchored.gaps.isEmpty())
            assertTrue(anchored.hasOlderStored)
            assertTrue(anchored.hasNewerStored)
            assertFalse(anchored.reachesOldest)
            assertFalse(anchored.reachesHead)
            assertTrue(anchored.insideProvenSegment)

            // Poison seq 58 strictly above anchor: afterCount = 0 must STILL succeed via non-decoding hasFactAfter.
            database.queries.updateCanonicalFact(
                seq = 58L,
                createdAt = "2026-07-16T00:00:00.000Z",
                canonicalFactJson = "malformed-after",
                id = "message-58",
                environmentId = scope.environmentId,
                serverId = scope.serverId,
                principalId = scope.principalId,
                consumerId = scope.consumerId,
                scopeKind = scope.scopeKind,
                channelId = scope.scopeId,
            )
            val zeroAfter = assertNotNull(
                reader.readTimeline(
                    partition = partitionA,
                    channelId = "channel-a",
                    anchor = ThreadDurableTimelineAnchor.AroundMessageId("message-55", beforeCount = 1, afterCount = 0),
                ),
            )
            assertEquals(listOf(52L, 55L), zeroAfter.envelopes.map { it.fact.seq })
            assertTrue(zeroAfter.hasNewerStored)

            // Missing message id returns null.
            assertNull(
                reader.readTimeline(
                    partition = partitionA,
                    channelId = "channel-a",
                    anchor = ThreadDurableTimelineAnchor.AroundMessageId("message-999", beforeCount = 5, afterCount = 5),
                ),
            )
        }
    }

    @Test
    fun contextIslandAboveHeadNeverHijacksNewestTimelineOrStarvesLegacyReadBeforeOrLooseFacts() = withFreshDatabase { database ->
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 10L })
        val scope = SyncScopeKey(partitionA, "channel-a", "channel")
        val reader = CanonicalMessageV2DurableLastKnownReader(store)

        runBlocking {
            // Head [10..20] (Latest, headAtVerify=true), context island ABOVE head [100..110], loose realtime fact 120.
            store.applyCoverage(
                scope = scope,
                envelopes = (10L..20L).map(::envelope),
                coverage = CanonicalMessageV2Coverage(10L, 20L, reachesOldest = true, headAtVerify = true, CanonicalMessageV2SegmentSource.Latest),
                revAtRequest = 0L,
                userDriven = true,
            )
            store.applyCoverage(
                scope = scope,
                envelopes = (100L..110L).map(::envelope),
                coverage = CanonicalMessageV2Coverage(100L, 110L, reachesOldest = false, headAtVerify = false, CanonicalMessageV2SegmentSource.Context),
                revAtRequest = 0L,
                userDriven = false,
            )
            store.applyCoverage(
                scope = scope,
                envelopes = listOf(envelope(120L)),
                coverage = null,
                revAtRequest = 0L,
                userDriven = false,
            )

            // readTimeline(Newest(5)) stays strictly inside head [10..20] up to head.highSeq (never splicing
            // unproven loose fact 120 or context island [100..110] with a mid-timeline gap).
            val newest = assertNotNull(reader.readTimeline(partitionA, "channel-a", ThreadDurableTimelineAnchor.Newest(limit = 5)))
            assertEquals(listOf(16L, 17L, 18L, 19L, 20L), newest.envelopes.map { it.fact.seq })
            assertTrue(newest.gaps.isEmpty(), "unanchored Newest must stay gap-free inside head: ${newest.gaps}")
            assertTrue(newest.hasOlderStored)
            assertTrue(newest.reachesHead)
            assertTrue(newest.insideProvenSegment)

            // Anchored open still locates loose fact 120 on disk without losing it.
            val anchoredLoose = assertNotNull(
                reader.readTimeline(
                    partition = partitionA,
                    channelId = "channel-a",
                    anchor = ThreadDurableTimelineAnchor.AroundMessageId("message-120", beforeCount = 0, afterCount = 0),
                ),
            )
            assertEquals(listOf(120L), anchoredLoose.envelopes.map { it.fact.seq })

            // Legacy readBefore(beforeSeq=120, limit=5) also jumps past island [100..110] and returns head [16..20] + hasOlderStored=true!
            val legacyBefore = assertNotNull(reader.readBefore(partitionA, "channel-a", beforeSeq = 120L, limit = 5))
            assertEquals(listOf(16L, 17L, 18L, 19L, 20L), legacyBefore.envelopes.map { it.fact.seq })
            assertTrue(legacyBefore.hasOlderStored)
            assertTrue(legacyBefore.hasLoadedOldest)

            // Channel B: head == null, loose realtime fact 50, context island above it [90..110].
            val scopeB = SyncScopeKey(partitionA, "channel-b", "channel")
            fun envB(seq: Long) = CanonicalMessageEnvelopeV2(
                fact = fact(seq).copy(id = "channel-b-message-$seq"),
                context = CanonicalConversationContextV2.TopLevel(TopLevelScopeRefV2.Channel("server-a", "channel-b")),
            )
            store.applyCoverage(
                scope = scopeB,
                envelopes = (90L..110L).map(::envB),
                coverage = CanonicalMessageV2Coverage(90L, 110L, reachesOldest = false, headAtVerify = false, CanonicalMessageV2SegmentSource.Context),
                revAtRequest = 0L,
                userDriven = false,
            )
            store.applyCoverage(
                scope = scopeB,
                envelopes = listOf(envB(50L)),
                coverage = null,
                revAtRequest = 0L,
                userDriven = false,
            )
            val looseOnly = assertNotNull(reader.readTimeline(partitionA, "channel-b", ThreadDurableTimelineAnchor.Newest(limit = 5)))
            assertEquals(listOf(50L), looseOnly.envelopes.map { it.fact.seq })
            assertTrue(looseOnly.gaps.isEmpty())
            assertFalse(looseOnly.hasOlderStored, "skipped newer island above 50 must not mark hasOlderStored=true")
            assertFalse(looseOnly.insideProvenSegment)
        }
    }

    @Test
    fun newestTimelineStaysGapFreeAcrossLowerIslandsLooseFactsAndAbsorbsLooseFactsAfterLatestRefresh() = withFreshDatabase { database ->
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 10L })
        val scope = SyncScopeKey(partitionA, "channel-a", "channel")
        val reader = CanonicalMessageV2DurableLastKnownReader(store)

        runBlocking {
            // Lower island [1..5] (reachesOldest=true), small head [20..24] (< limit), loose realtime facts 50 & 65,
            // and non-head context island [80..85] above head.
            store.applyCoverage(
                scope = scope,
                envelopes = (1L..5L).map(::envelope),
                coverage = CanonicalMessageV2Coverage(1L, 5L, reachesOldest = true, headAtVerify = false, CanonicalMessageV2SegmentSource.Context),
                revAtRequest = 0L,
                userDriven = false,
            )
            store.applyCoverage(
                scope = scope,
                envelopes = (20L..24L).map(::envelope),
                coverage = CanonicalMessageV2Coverage(20L, 24L, reachesOldest = false, headAtVerify = true, CanonicalMessageV2SegmentSource.Latest),
                revAtRequest = 0L,
                userDriven = true,
            )
            store.applyCoverage(
                scope = scope,
                envelopes = (80L..85L).map(::envelope),
                coverage = CanonicalMessageV2Coverage(80L, 85L, reachesOldest = false, headAtVerify = false, CanonicalMessageV2SegmentSource.Context),
                revAtRequest = 0L,
                userDriven = false,
            )
            store.applyCoverage(
                scope = scope,
                envelopes = listOf(envelope(50L), envelope(65L)),
                coverage = null,
                revAtRequest = 0L,
                userDriven = false,
            )

            // 1. Unanchored Newest(10) returns strictly contiguous head [20..24] with zero gaps,
            //    keeping hasOlderStored=true so explicit offline LoadOlder can still cross into [1..5].
            val newestBeforeRefresh = assertNotNull(
                reader.readTimeline(partitionA, "channel-a", ThreadDurableTimelineAnchor.Newest(limit = 10)),
            )
            assertEquals(listOf(20L, 21L, 22L, 23L, 24L), newestBeforeRefresh.envelopes.map { it.fact.seq })
            assertTrue(newestBeforeRefresh.gaps.isEmpty())
            assertTrue(newestBeforeRefresh.hasOlderStored, "lower island [1..5] must remain reachable via offline Before")
            assertFalse(newestBeforeRefresh.reachesOldest)
            assertTrue(newestBeforeRefresh.reachesHead)
            assertTrue(newestBeforeRefresh.insideProvenSegment)

            // 2. Explicit offline Before(20, crossGaps=true) still crosses into lower island [1..5] with gap (5, 20).
            val offlineOlder = assertNotNull(
                reader.readTimeline(partitionA, "channel-a", ThreadDurableTimelineAnchor.Before(20L, limit = 10, crossGaps = true)),
            )
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L), offlineOlder.envelopes.map { it.fact.seq })
            assertEquals(listOf(CanonicalMessageV2TimelineGap(afterSeq = 5L, beforeSeq = 20L)), offlineOlder.gaps)
            assertTrue(offlineOlder.reachesOldest)

            // 3. Online refresh commits Latest coverage [25..65] covering through 65: merges with head [20..24]
            //    into [20..65] and absorbs loose realtime facts 50 & 65 with zero residual gaps!
            store.applyCoverage(
                scope = scope,
                envelopes = listOf(envelope(25L), envelope(50L), envelope(65L)),
                coverage = CanonicalMessageV2Coverage(25L, 65L, reachesOldest = false, headAtVerify = true, CanonicalMessageV2SegmentSource.Latest),
                revAtRequest = 0L,
                userDriven = true,
            )
            val newestAfterRefresh = assertNotNull(
                reader.readTimeline(partitionA, "channel-a", ThreadDurableTimelineAnchor.Newest(limit = 10)),
            )
            assertEquals(listOf(20L, 21L, 22L, 23L, 24L, 25L, 50L, 65L), newestAfterRefresh.envelopes.map { it.fact.seq })
            assertTrue(newestAfterRefresh.gaps.isEmpty(), "Latest coverage must absorb loose facts cleanly: ${newestAfterRefresh.gaps}")
            assertTrue(newestAfterRefresh.hasOlderStored)
            assertTrue(newestAfterRefresh.reachesHead)
            assertTrue(newestAfterRefresh.insideProvenSegment)

            // 4. Headless scope with non-consecutive loose facts [50, 55, 62] returns only gap-free newest [62],
            //    while consecutive loose facts [61, 62] return both [61, 62] with zero gaps and hasOlderStored=true.
            val scopeC = SyncScopeKey(partitionA, "channel-c", "channel")
            fun envC(seq: Long) = CanonicalMessageEnvelopeV2(
                fact = fact(seq).copy(id = "channel-c-message-$seq"),
                context = CanonicalConversationContextV2.TopLevel(TopLevelScopeRefV2.Channel("server-a", "channel-c")),
            )
            store.applyCoverage(
                scope = scopeC,
                envelopes = listOf(envC(50L), envC(55L), envC(62L)),
                coverage = null,
                revAtRequest = 0L,
                userDriven = false,
            )
            val nonConsecutiveLoose = assertNotNull(
                reader.readTimeline(partitionA, "channel-c", ThreadDurableTimelineAnchor.Newest(limit = 5)),
            )
            assertEquals(listOf(62L), nonConsecutiveLoose.envelopes.map { it.fact.seq })
            assertTrue(nonConsecutiveLoose.gaps.isEmpty())
            assertTrue(nonConsecutiveLoose.hasOlderStored)
            assertFalse(nonConsecutiveLoose.insideProvenSegment)

            // Offline Before(62, crossGaps=true) on a headless scope with only unsegmented loose facts
            // never splices loose facts row-by-row into stacked gaps (#proj-mobile:#386 P0-1).
            val offlineLooseOlder = assertNotNull(
                reader.readTimeline(partitionA, "channel-c", ThreadDurableTimelineAnchor.Before(62L, limit = 5, crossGaps = true)),
            )
            assertTrue(offlineLooseOlder.envelopes.isEmpty(), "loose facts must not be bridged across gaps: ${offlineLooseOlder.envelopes}")
            assertTrue(offlineLooseOlder.gaps.isEmpty())
            assertFalse(offlineLooseOlder.hasOlderStored)

            // Adding consecutive loose fact 61 makes [61, 62] provably gap-free.
            store.applyCoverage(
                scope = scopeC,
                envelopes = listOf(envC(61L)),
                coverage = null,
                revAtRequest = 0L,
                userDriven = false,
            )
            val consecutiveLoose = assertNotNull(
                reader.readTimeline(partitionA, "channel-c", ThreadDurableTimelineAnchor.Newest(limit = 5)),
            )
            assertEquals(listOf(61L, 62L), consecutiveLoose.envelopes.map { it.fact.seq })
            assertTrue(consecutiveLoose.gaps.isEmpty())
            assertTrue(consecutiveLoose.hasOlderStored)
        }
    }

    @Test
    fun newestTimelineCoversEmptyBaselineMaxSeqPoisonedLooseTouchingIslandAndUnknownFieldFiltering() = withFreshDatabase { database ->
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 10L })
        val reader = CanonicalMessageV2DurableLastKnownReader(store)

        runBlocking {
            // 1. Empty baseline [0, 0] with loose realtime fact 10 above 0 still returns clean empty stand-in.
            val emptyScope = SyncScopeKey(partitionA, "channel-empty", "channel")
            fun envOf(channelId: String, seq: Long) = CanonicalMessageEnvelopeV2(
                fact = fact(seq).copy(id = "$channelId-message-$seq"),
                context = CanonicalConversationContextV2.TopLevel(TopLevelScopeRefV2.Channel("server-a", channelId)),
            )
            store.applyCoverage(
                scope = emptyScope,
                envelopes = emptyList(),
                coverage = CanonicalMessageV2Coverage(0L, 0L, reachesOldest = true, headAtVerify = true, CanonicalMessageV2SegmentSource.Latest),
                revAtRequest = 0L,
                userDriven = true,
            )
            store.applyCoverage(
                scope = emptyScope,
                envelopes = listOf(envOf("channel-empty", 10L)),
                coverage = null,
                revAtRequest = 0L,
                userDriven = false,
            )
            val emptyPage = assertNotNull(reader.readTimeline(partitionA, "channel-empty", ThreadDurableTimelineAnchor.Newest(limit = 5)))
            assertTrue(emptyPage.envelopes.isEmpty())
            assertTrue(emptyPage.gaps.isEmpty())
            assertFalse(emptyPage.hasOlderStored)
            assertTrue(emptyPage.reachesOldest)
            assertTrue(emptyPage.reachesHead)
            assertTrue(emptyPage.insideProvenSegment)

            // 2. head.highSeq == Long.MAX_VALUE: limit == 1 and limit > 1 both handle max-seq without overflow.
            val maxHeadScope = SyncScopeKey(partitionA, "channel-max-head", "channel")
            val maxSeq = Long.MAX_VALUE
            store.applyCoverage(
                scope = maxHeadScope,
                envelopes = listOf(envOf("channel-max-head", maxSeq - 2L), envOf("channel-max-head", maxSeq - 1L), envOf("channel-max-head", maxSeq)),
                coverage = CanonicalMessageV2Coverage(maxSeq - 2L, maxSeq, reachesOldest = false, headAtVerify = true, CanonicalMessageV2SegmentSource.Latest),
                revAtRequest = 0L,
                userDriven = true,
            )
            val maxHeadOne = assertNotNull(reader.readTimeline(partitionA, "channel-max-head", ThreadDurableTimelineAnchor.Newest(limit = 1)))
            assertEquals(listOf(maxSeq), maxHeadOne.envelopes.map { it.fact.seq })
            assertTrue(maxHeadOne.gaps.isEmpty())
            assertTrue(maxHeadOne.hasOlderStored)
            assertTrue(maxHeadOne.reachesHead)
            assertTrue(maxHeadOne.insideProvenSegment)

            val maxHeadMany = assertNotNull(reader.readTimeline(partitionA, "channel-max-head", ThreadDurableTimelineAnchor.Newest(limit = 5)))
            assertEquals(listOf(maxSeq - 2L, maxSeq - 1L, maxSeq), maxHeadMany.envelopes.map { it.fact.seq })
            assertTrue(maxHeadMany.gaps.isEmpty())
            assertFalse(maxHeadMany.hasOlderStored)
            assertTrue(maxHeadMany.reachesHead)

            // 3. head == null with loose fact at Long.MAX_VALUE (and partial unknownFields) + non-consecutive lower fact.
            val maxLooseScope = SyncScopeKey(partitionA, "channel-max-loose", "channel")
            val maxLooseFact = envOf("channel-max-loose", maxSeq)
            store.applyCoverage(
                scope = maxLooseScope,
                envelopes = listOf(envOf("channel-max-loose", 10L), maxLooseFact),
                coverage = null,
                revAtRequest = 0L,
                userDriven = false,
                unknownFieldsByFactId = mapOf(maxLooseFact.fact.id to setOf(build.raft.app.canonical.message.MessageAggregateField.Reactions)),
            )
            val maxLoosePage = assertNotNull(reader.readTimeline(partitionA, "channel-max-loose", ThreadDurableTimelineAnchor.Newest(limit = 5)))
            assertEquals(listOf(maxSeq), maxLoosePage.envelopes.map { it.fact.seq })
            assertTrue(maxLoosePage.gaps.isEmpty())
            assertTrue(maxLoosePage.hasOlderStored)
            assertEquals(
                setOf(build.raft.app.canonical.message.MessageAggregateField.Reactions),
                maxLoosePage.unknownFieldsByFactId[maxLooseFact.fact.id],
            )

            // 4. head == null isolates poisoned non-consecutive loose fact 55 below newest loose fact 62,
            //    and stops a consecutive run before a touching lower context island [50..61].
            val poisonedLooseScope = SyncScopeKey(partitionA, "channel-poison-loose", "channel")
            store.applyCoverage(
                scope = poisonedLooseScope,
                envelopes = listOf(envOf("channel-poison-loose", 50L), envOf("channel-poison-loose", 55L), envOf("channel-poison-loose", 62L)),
                coverage = null,
                revAtRequest = 0L,
                userDriven = false,
            )
            database.queries.updateCanonicalFact(
                seq = 55L,
                createdAt = "2026-07-16T00:00:00.000Z",
                canonicalFactJson = "malformed-loose-55",
                id = "channel-poison-loose-message-55",
                environmentId = poisonedLooseScope.environmentId,
                serverId = poisonedLooseScope.serverId,
                principalId = poisonedLooseScope.principalId,
                consumerId = poisonedLooseScope.consumerId,
                scopeKind = poisonedLooseScope.scopeKind,
                channelId = poisonedLooseScope.scopeId,
            )
            val poisonedLoosePage = assertNotNull(reader.readTimeline(partitionA, "channel-poison-loose", ThreadDurableTimelineAnchor.Newest(limit = 5)))
            assertEquals(listOf(62L), poisonedLoosePage.envelopes.map { it.fact.seq })
            assertTrue(poisonedLoosePage.gaps.isEmpty())
            assertTrue(poisonedLoosePage.hasOlderStored)

            val touchingScope = SyncScopeKey(partitionA, "channel-touching-island", "channel")
            store.applyCoverage(
                scope = touchingScope,
                envelopes = (50L..61L).map { envOf("channel-touching-island", it) },
                coverage = CanonicalMessageV2Coverage(50L, 61L, reachesOldest = false, headAtVerify = false, CanonicalMessageV2SegmentSource.Context),
                revAtRequest = 0L,
                userDriven = false,
            )
            store.applyCoverage(
                scope = touchingScope,
                envelopes = listOf(envOf("channel-touching-island", 62L)),
                coverage = null,
                revAtRequest = 0L,
                userDriven = false,
            )
            val touchingPage = assertNotNull(reader.readTimeline(partitionA, "channel-touching-island", ThreadDurableTimelineAnchor.Newest(limit = 5)))
            assertEquals(listOf(62L), touchingPage.envelopes.map { it.fact.seq })
            assertTrue(touchingPage.gaps.isEmpty())
            assertTrue(touchingPage.hasOlderStored)

            // 5. unknownFieldsByFactId filters out lower-island rows excluded below head.effectiveLowSeq.
            val unknownFilterScope = SyncScopeKey(partitionA, "channel-unknown-filter", "channel")
            val lowerFact = envOf("channel-unknown-filter", 5L)
            val headFact = envOf("channel-unknown-filter", 20L)
            store.applyCoverage(
                scope = unknownFilterScope,
                envelopes = listOf(lowerFact),
                coverage = CanonicalMessageV2Coverage(5L, 5L, reachesOldest = true, headAtVerify = false, CanonicalMessageV2SegmentSource.Context),
                revAtRequest = 0L,
                userDriven = false,
                unknownFieldsByFactId = mapOf(lowerFact.fact.id to setOf(build.raft.app.canonical.message.MessageAggregateField.Reactions)),
            )
            store.applyCoverage(
                scope = unknownFilterScope,
                envelopes = listOf(headFact),
                coverage = CanonicalMessageV2Coverage(20L, 20L, reachesOldest = false, headAtVerify = true, CanonicalMessageV2SegmentSource.Latest),
                revAtRequest = 0L,
                userDriven = true,
                unknownFieldsByFactId = mapOf(headFact.fact.id to setOf(build.raft.app.canonical.message.MessageAggregateField.Reactions)),
            )
            val filteredPage = assertNotNull(reader.readTimeline(partitionA, "channel-unknown-filter", ThreadDurableTimelineAnchor.Newest(limit = 10)))
            assertEquals(listOf(20L), filteredPage.envelopes.map { it.fact.seq })
            assertEquals(setOf(headFact.fact.id), filteredPage.unknownFieldsByFactId.keys)
            assertTrue(filteredPage.hasOlderStored)
        }
    }

    @Test
    fun legacyReadsStayReadOnlyWhilePositiveTimelineReadUpdatesLastUserAccessAtMs() = withFreshDatabase { database ->
        var clock = 100L
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { clock })
        val parentScope = SyncScopeKey(partitionA, "channel-a", "channel")
        val threadScope = SyncScopeKey(partitionA, "thread-a", "thread")
        val reader = CanonicalMessageV2DurableLastKnownReader(store)

        fun lastAccess(scope: SyncScopeKey): Long? =
            database.queries.selectSyncCursor(
                environmentId = scope.environmentId,
                serverId = scope.serverId,
                principalId = scope.principalId,
                consumerId = scope.consumerId,
                scopeKind = scope.scopeKind,
                scopeId = scope.scopeId,
            ).executeAsOneOrNull()?.lastUserAccessAtMs

        runBlocking {
            store.applyCoverage(
                scope = parentScope,
                envelopes = listOf(envelope(1L)),
                coverage = CanonicalMessageV2Coverage(1L, 1L, reachesOldest = true, headAtVerify = true, CanonicalMessageV2SegmentSource.Latest),
                revAtRequest = 0L,
                userDriven = false,
            )
            val reply = CanonicalMessageEnvelopeV2(
                fact = fact(2L),
                context = CanonicalConversationContextV2.Thread(
                    replyScope = ReplyScopeRefV2("server-a", "thread-a"),
                    parentTopLevelScope = TopLevelScopeRefV2.Channel("server-a", "channel-a"),
                    rootMessageRef = MessageRefV2("server-a", "message-1"),
                ),
                replyMembership = ReplyMembershipV2(
                    root = MessageRefV2("server-a", "message-1"),
                    parentScope = ReplyScopeRefV2("server-a", "thread-a"),
                ),
            )
            store.applyCoverage(
                scope = threadScope,
                envelopes = listOf(reply),
                coverage = CanonicalMessageV2Coverage(2L, 2L, reachesOldest = true, headAtVerify = true, CanonicalMessageV2SegmentSource.Latest),
                revAtRequest = 0L,
                userDriven = false,
                threadParent = CanonicalMessageV2ThreadParent("channel", "channel-a", "message-1"),
            )
            // Prewarm / non-user-driven write leaves lastUserAccessAtMs null.
            assertNull(lastAccess(threadScope))
            assertNull(lastAccess(parentScope))

            // Legacy read / readReplyThread / readBefore stay strictly read-only.
            clock = 400L
            assertNotNull(reader.read(partitionA, "channel-a"))
            assertNotNull(reader.readBefore(partitionA, "channel-a", beforeSeq = 2L, limit = 5))
            assertNotNull(reader.readReplyThread(partitionA, "thread-a", ThreadDurableReplyParent("channel-a", "message-1")))
            assertNull(lastAccess(parentScope))
            assertNull(lastAccess(threadScope))

            // Wrong reply parent -> returns null and must NOT touch lastUserAccessAtMs.
            clock = 500L
            assertNull(
                reader.readReplyThreadTimeline(
                    partition = partitionA,
                    threadChannelId = "thread-a",
                    replyParent = ThreadDurableReplyParent("channel-wrong", "message-1"),
                    anchor = ThreadDurableTimelineAnchor.Newest(limit = 10),
                ),
            )
            assertNull(lastAccess(threadScope))

            // Deferred touchUserAccess = false leaves lastUserAccessAtMs null until caller invokes touchUserAccess(scope).
            clock = 700L
            val deferred = assertNotNull(
                reader.readReplyThreadTimeline(
                    partition = partitionA,
                    threadChannelId = "thread-a",
                    replyParent = ThreadDurableReplyParent("channel-a", "message-1"),
                    anchor = ThreadDurableTimelineAnchor.Newest(limit = 10),
                    touchUserAccess = false,
                ),
            )
            assertEquals(listOf("message-2"), deferred.envelopes.map { it.fact.id })
            assertNull(lastAccess(threadScope))
            assertNull(lastAccess(parentScope))

            // Valid reply-thread timeline read (or explicit touchUserAccess) touches BOTH thread and parent with same timestamp!
            clock = 900L
            val page = assertNotNull(
                reader.readReplyThreadTimeline(
                    partition = partitionA,
                    threadChannelId = "thread-a",
                    replyParent = ThreadDurableReplyParent("channel-a", "message-1"),
                    anchor = ThreadDurableTimelineAnchor.Newest(limit = 10),
                ),
            )
            assertEquals(listOf("message-2"), page.envelopes.map { it.fact.id })
            assertEquals(900L, lastAccess(threadScope))
            assertEquals(900L, lastAccess(parentScope))
        }
    }

    @Test
    fun boundedNewestIsolatesPoisonImmediatelyBelowWindowAndInsideSkippedAboveHeadIsland() = withFreshDatabase { database ->
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 10L })
        val scope = SyncScopeKey(partitionA, "channel-a", "channel")
        val reader = CanonicalMessageV2DurableLastKnownReader(store)

        runBlocking {
            // Head [1..15] + context island above head [100..105].
            store.applyCoverage(
                scope = scope,
                envelopes = (1L..15L).map(::envelope),
                coverage = CanonicalMessageV2Coverage(1L, 15L, reachesOldest = true, headAtVerify = true, CanonicalMessageV2SegmentSource.Latest),
                revAtRequest = 0L,
                userDriven = true,
            )
            store.applyCoverage(
                scope = scope,
                envelopes = (100L..105L).map(::envelope),
                coverage = CanonicalMessageV2Coverage(100L, 105L, reachesOldest = false, headAtVerify = false, CanonicalMessageV2SegmentSource.Context),
                revAtRequest = 0L,
                userDriven = false,
            )
            // Poison row 10 (exact limit+1 boundary below Newest(5) = [11..15]) and row 105 (top of skipped above-head island).
            for (poisonSeq in listOf(10L, 105L)) {
                database.queries.updateCanonicalFact(
                    seq = poisonSeq,
                    createdAt = "2026-07-16T00:00:00.000Z",
                    canonicalFactJson = "malformed-$poisonSeq",
                    id = "message-$poisonSeq",
                    environmentId = scope.environmentId,
                    serverId = scope.serverId,
                    principalId = scope.principalId,
                    consumerId = scope.consumerId,
                    scopeKind = scope.scopeKind,
                    channelId = scope.scopeId,
                )
            }

            val newest = assertNotNull(
                reader.readTimeline(partitionA, "channel-a", ThreadDurableTimelineAnchor.Newest(limit = 5)),
            )
            assertEquals(listOf(11L, 12L, 13L, 14L, 15L), newest.envelopes.map { it.fact.seq })
            assertTrue(newest.hasOlderStored)
            assertFalse(newest.reachesOldest)
            assertTrue(newest.reachesHead)
            assertTrue(newest.insideProvenSegment)

            // Extreme boundary anchors report opposite-direction presence accurately without overflow.
            val beforeMin = assertNotNull(
                reader.readTimeline(partitionA, "channel-a", ThreadDurableTimelineAnchor.Before(Long.MIN_VALUE, limit = 5, crossGaps = true)),
            )
            assertTrue(beforeMin.envelopes.isEmpty())
            assertFalse(beforeMin.hasOlderStored)
            assertTrue(beforeMin.hasNewerStored)

            val afterMax = assertNotNull(
                reader.readTimeline(partitionA, "channel-a", ThreadDurableTimelineAnchor.After(Long.MAX_VALUE, limit = 5, crossGaps = true)),
            )
            assertTrue(afterMax.envelopes.isEmpty())
            assertTrue(afterMax.hasOlderStored)
            assertFalse(afterMax.hasNewerStored)
        }
    }

    @Test
    fun aroundMessageIdInsideProvenSegmentStaysGapFreeExcludesOutsideFactsAndPreservesOfflinePagingFlags() =
        withFreshDatabase { database ->
            val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 10L })
            val reader = CanonicalMessageV2DurableLastKnownReader(store)
            val reactions = setOf(build.raft.app.canonical.message.MessageAggregateField.Reactions)

            runBlocking {
                val scope = SyncScopeKey(partitionA, "channel-a", "channel")
                // Lower island [1..5], loose fact 12, anchor segment [20..25], loose fact 40, upper island [60..65].
                store.applyCoverage(
                    scope = scope,
                    envelopes = (1L..5L).map(::envelope),
                    coverage = CanonicalMessageV2Coverage(1L, 5L, reachesOldest = true, headAtVerify = false, CanonicalMessageV2SegmentSource.Context),
                    revAtRequest = 0L,
                    userDriven = false,
                    unknownFieldsByFactId = mapOf("message-5" to reactions),
                )
                store.applyCoverage(
                    scope = scope,
                    envelopes = (20L..25L).map(::envelope),
                    coverage = CanonicalMessageV2Coverage(20L, 25L, reachesOldest = false, headAtVerify = false, CanonicalMessageV2SegmentSource.Context),
                    revAtRequest = 0L,
                    userDriven = false,
                    unknownFieldsByFactId = mapOf("message-22" to reactions),
                )
                store.applyCoverage(
                    scope = scope,
                    envelopes = (60L..65L).map(::envelope),
                    coverage = CanonicalMessageV2Coverage(60L, 65L, reachesOldest = false, headAtVerify = true, CanonicalMessageV2SegmentSource.Latest),
                    revAtRequest = 0L,
                    userDriven = true,
                    unknownFieldsByFactId = mapOf("message-60" to reactions),
                )
                store.applyCoverage(
                    scope = scope,
                    envelopes = listOf(envelope(12L), envelope(40L)),
                    coverage = null,
                    revAtRequest = 0L,
                    userDriven = false,
                    unknownFieldsByFactId = mapOf("message-12" to reactions, "message-40" to reactions),
                )

                // Large beforeCount / afterCount stays strictly inside [20..25] with gaps=emptyList(),
                // preserves hasOlderStored / hasNewerStored for offline island crossing, and filters unknownFields.
                val inSegment = assertNotNull(
                    reader.readTimeline(
                        partition = partitionA,
                        channelId = "channel-a",
                        anchor = ThreadDurableTimelineAnchor.AroundMessageId("message-22", beforeCount = 15, afterCount = 15),
                    ),
                )
                assertEquals(listOf(20L, 21L, 22L, 23L, 24L, 25L), inSegment.envelopes.map { it.fact.seq })
                assertTrue(inSegment.gaps.isEmpty(), "anchored in-segment open must be gap-free: ${inSegment.gaps}")
                assertTrue(inSegment.hasOlderStored)
                assertTrue(inSegment.hasNewerStored)
                assertFalse(inSegment.reachesOldest)
                assertFalse(inSegment.reachesHead)
                assertTrue(inSegment.insideProvenSegment)
                assertEquals(setOf("message-22"), inSegment.unknownFieldsByFactId.keys)

                // Explicit offline Before / After with crossGaps=true bridges only proven segments and
                // skips unsegmented loose realtime facts (12, 40) so stacked gaps never appear (#proj-mobile:#386 P0-1).
                val offlineOlder = assertNotNull(
                    reader.readTimeline(partitionA, "channel-a", ThreadDurableTimelineAnchor.Before(20L, limit = 10, crossGaps = true)),
                )
                assertEquals(listOf(1L, 2L, 3L, 4L, 5L), offlineOlder.envelopes.map { it.fact.seq })
                assertEquals(
                    listOf(
                        CanonicalMessageV2TimelineGap(afterSeq = 5L, beforeSeq = 20L),
                    ),
                    offlineOlder.gaps,
                )
                assertFalse(offlineOlder.hasOlderStored)
                assertTrue(offlineOlder.reachesOldest)
                val offlineNewer = assertNotNull(
                    reader.readTimeline(partitionA, "channel-a", ThreadDurableTimelineAnchor.After(25L, limit = 10, crossGaps = true)),
                )
                assertEquals(listOf(60L, 61L, 62L, 63L, 64L, 65L), offlineNewer.envelopes.map { it.fact.seq })
                assertEquals(
                    listOf(
                        CanonicalMessageV2TimelineGap(afterSeq = 25L, beforeSeq = 60L),
                    ),
                    offlineNewer.gaps,
                )
                assertFalse(offlineNewer.hasNewerStored)
                assertTrue(offlineNewer.reachesHead)

                // Anchor right on segment boundaries (20 and 25) isolates poisoned outside facts (12 and 40).
                for (poisonSeq in listOf(12L, 40L)) {
                    database.queries.updateCanonicalFact(
                        seq = poisonSeq,
                        createdAt = "2026-07-16T00:00:00.000Z",
                        canonicalFactJson = "malformed-$poisonSeq",
                        id = "message-$poisonSeq",
                        environmentId = scope.environmentId,
                        serverId = scope.serverId,
                        principalId = scope.principalId,
                        consumerId = scope.consumerId,
                        scopeKind = scope.scopeKind,
                        channelId = scope.scopeId,
                    )
                }
                val atLowEdge = assertNotNull(
                    reader.readTimeline(
                        partition = partitionA,
                        channelId = "channel-a",
                        anchor = ThreadDurableTimelineAnchor.AroundMessageId("message-20", beforeCount = 10, afterCount = 2),
                    ),
                )
                assertEquals(listOf(20L, 21L, 22L), atLowEdge.envelopes.map { it.fact.seq })
                assertTrue(atLowEdge.gaps.isEmpty())
                assertTrue(atLowEdge.hasOlderStored)
                assertTrue(atLowEdge.hasNewerStored)

                val atHighEdge = assertNotNull(
                    reader.readTimeline(
                        partition = partitionA,
                        channelId = "channel-a",
                        anchor = ThreadDurableTimelineAnchor.AroundMessageId("message-25", beforeCount = 2, afterCount = 10),
                    ),
                )
                assertEquals(listOf(23L, 24L, 25L), atHighEdge.envelopes.map { it.fact.seq })
                assertTrue(atHighEdge.gaps.isEmpty())
                assertTrue(atHighEdge.hasOlderStored)
                assertTrue(atHighEdge.hasNewerStored)

                // Segment with reachesOldest=true and headAtVerify=true and no outside facts preserves both flags,
                // and Long.MAX_VALUE anchor inside a max-seq segment works without overflow.
                val maxScope = SyncScopeKey(partitionA, "channel-max-anchored", "channel")
                val maxSeq = Long.MAX_VALUE
                fun envMax(seq: Long) = CanonicalMessageEnvelopeV2(
                    fact = fact(seq).copy(id = "max-message-$seq"),
                    context = CanonicalConversationContextV2.TopLevel(TopLevelScopeRefV2.Channel("server-a", "channel-max-anchored")),
                )
                store.applyCoverage(
                    scope = maxScope,
                    envelopes = listOf(envMax(maxSeq - 2L), envMax(maxSeq - 1L), envMax(maxSeq)),
                    coverage = CanonicalMessageV2Coverage(maxSeq - 2L, maxSeq, reachesOldest = true, headAtVerify = true, CanonicalMessageV2SegmentSource.Latest),
                    revAtRequest = 0L,
                    userDriven = true,
                )
                val anchoredMax = assertNotNull(
                    reader.readTimeline(
                        partition = partitionA,
                        channelId = "channel-max-anchored",
                        anchor = ThreadDurableTimelineAnchor.AroundMessageId("max-message-$maxSeq", beforeCount = 5, afterCount = 5),
                    ),
                )
                assertEquals(listOf(maxSeq - 2L, maxSeq - 1L, maxSeq), anchoredMax.envelopes.map { it.fact.seq })
                assertTrue(anchoredMax.gaps.isEmpty())
                assertFalse(anchoredMax.hasOlderStored)
                assertFalse(anchoredMax.hasNewerStored)
                assertTrue(anchoredMax.reachesOldest)
                assertTrue(anchoredMax.reachesHead)
                assertTrue(anchoredMax.insideProvenSegment)
            }
        }

    @Test
    fun aroundMessageIdOnLooseFactStaysGapFreeReturnsOnlyConsecutiveLooseRunAndIsolatesPoisonAndTouchingSegments() =
        withFreshDatabase { database ->
            val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 10L })
            val reader = CanonicalMessageV2DurableLastKnownReader(store)
            val reactions = setOf(build.raft.app.canonical.message.MessageAggregateField.Reactions)

            runBlocking {
                // 1. Multiple non-consecutive loose realtime facts [100, 105, 110] with head [10..20] and upper island [200..210].
                val sparseScope = SyncScopeKey(partitionA, "channel-loose-sparse", "channel")
                fun envOf(channelId: String, seq: Long) = CanonicalMessageEnvelopeV2(
                    fact = fact(seq).copy(id = "$channelId-message-$seq"),
                    context = CanonicalConversationContextV2.TopLevel(TopLevelScopeRefV2.Channel("server-a", channelId)),
                )
                store.applyCoverage(
                    scope = sparseScope,
                    envelopes = (10L..20L).map { envOf("channel-loose-sparse", it) },
                    coverage = CanonicalMessageV2Coverage(10L, 20L, reachesOldest = true, headAtVerify = true, CanonicalMessageV2SegmentSource.Latest),
                    revAtRequest = 0L,
                    userDriven = true,
                )
                store.applyCoverage(
                    scope = sparseScope,
                    envelopes = (200L..210L).map { envOf("channel-loose-sparse", it) },
                    coverage = CanonicalMessageV2Coverage(200L, 210L, reachesOldest = false, headAtVerify = false, CanonicalMessageV2SegmentSource.Context),
                    revAtRequest = 0L,
                    userDriven = false,
                )
                store.applyCoverage(
                    scope = sparseScope,
                    envelopes = listOf(envOf("channel-loose-sparse", 100L), envOf("channel-loose-sparse", 105L), envOf("channel-loose-sparse", 110L)),
                    coverage = null,
                    revAtRequest = 0L,
                    userDriven = false,
                    unknownFieldsByFactId = mapOf("channel-loose-sparse-message-105" to reactions),
                )

                val sparseLoose = assertNotNull(
                    reader.readTimeline(
                        partition = partitionA,
                        channelId = "channel-loose-sparse",
                        anchor = ThreadDurableTimelineAnchor.AroundMessageId("channel-loose-sparse-message-105", beforeCount = 15, afterCount = 15),
                    ),
                )
                assertEquals(listOf(105L), sparseLoose.envelopes.map { it.fact.seq })
                assertTrue(sparseLoose.gaps.isEmpty(), "loose anchored open must never emit mid-timeline gaps: ${sparseLoose.gaps}")
                assertTrue(sparseLoose.hasOlderStored)
                assertTrue(sparseLoose.hasNewerStored)
                assertFalse(sparseLoose.reachesOldest)
                assertFalse(sparseLoose.reachesHead)
                assertFalse(sparseLoose.insideProvenSegment)
                assertEquals(setOf("channel-loose-sparse-message-105"), sparseLoose.unknownFieldsByFactId.keys)

                // Poison non-consecutive neighbors 100 and 110: anchored read on 105 must NOT decode them.
                for (poisonSeq in listOf(100L, 110L)) {
                    database.queries.updateCanonicalFact(
                        seq = poisonSeq,
                        createdAt = "2026-07-16T00:00:00.000Z",
                        canonicalFactJson = "malformed-loose-$poisonSeq",
                        id = "channel-loose-sparse-message-$poisonSeq",
                        environmentId = sparseScope.environmentId,
                        serverId = sparseScope.serverId,
                        principalId = sparseScope.principalId,
                        consumerId = sparseScope.consumerId,
                        scopeKind = sparseScope.scopeKind,
                        channelId = sparseScope.scopeId,
                    )
                }
                val sparsePoisonIsolated = assertNotNull(
                    reader.readTimeline(
                        partition = partitionA,
                        channelId = "channel-loose-sparse",
                        anchor = ThreadDurableTimelineAnchor.AroundMessageId("channel-loose-sparse-message-105", beforeCount = 15, afterCount = 15),
                    ),
                )
                assertEquals(listOf(105L), sparsePoisonIsolated.envelopes.map { it.fact.seq })
                assertTrue(sparsePoisonIsolated.gaps.isEmpty())
                assertTrue(sparsePoisonIsolated.hasOlderStored)
                assertTrue(sparsePoisonIsolated.hasNewerStored)

                // 2. Consecutive loose run [104, 105, 106] bounded by touching segments [100..103] and [107..110]:
                //    stops at both segment boundaries without crossing or decoding touching poisoned segment rows.
                val consecutiveScope = SyncScopeKey(partitionA, "channel-loose-consecutive", "channel")
                store.applyCoverage(
                    scope = consecutiveScope,
                    envelopes = (100L..103L).map { envOf("channel-loose-consecutive", it) },
                    coverage = CanonicalMessageV2Coverage(100L, 103L, reachesOldest = false, headAtVerify = false, CanonicalMessageV2SegmentSource.Context),
                    revAtRequest = 0L,
                    userDriven = false,
                )
                store.applyCoverage(
                    scope = consecutiveScope,
                    envelopes = (107L..110L).map { envOf("channel-loose-consecutive", it) },
                    coverage = CanonicalMessageV2Coverage(107L, 110L, reachesOldest = false, headAtVerify = false, CanonicalMessageV2SegmentSource.Context),
                    revAtRequest = 0L,
                    userDriven = false,
                )
                store.applyCoverage(
                    scope = consecutiveScope,
                    envelopes = listOf(
                        envOf("channel-loose-consecutive", 104L),
                        envOf("channel-loose-consecutive", 105L),
                        envOf("channel-loose-consecutive", 106L),
                    ),
                    coverage = null,
                    revAtRequest = 0L,
                    userDriven = false,
                )
                for (poisonSeq in listOf(103L, 107L)) {
                    database.queries.updateCanonicalFact(
                        seq = poisonSeq,
                        createdAt = "2026-07-16T00:00:00.000Z",
                        canonicalFactJson = "malformed-touching-$poisonSeq",
                        id = "channel-loose-consecutive-message-$poisonSeq",
                        environmentId = consecutiveScope.environmentId,
                        serverId = consecutiveScope.serverId,
                        principalId = consecutiveScope.principalId,
                        consumerId = consecutiveScope.consumerId,
                        scopeKind = consecutiveScope.scopeKind,
                        channelId = consecutiveScope.scopeId,
                    )
                }

                val consecutiveRun = assertNotNull(
                    reader.readTimeline(
                        partition = partitionA,
                        channelId = "channel-loose-consecutive",
                        anchor = ThreadDurableTimelineAnchor.AroundMessageId("channel-loose-consecutive-message-105", beforeCount = 5, afterCount = 5),
                    ),
                )
                assertEquals(listOf(104L, 105L, 106L), consecutiveRun.envelopes.map { it.fact.seq })
                assertTrue(consecutiveRun.gaps.isEmpty())
                assertTrue(consecutiveRun.hasOlderStored)
                assertTrue(consecutiveRun.hasNewerStored)
                assertFalse(consecutiveRun.insideProvenSegment)

                // Respects beforeCount / afterCount caps inside the consecutive run.
                val cappedRun = assertNotNull(
                    reader.readTimeline(
                        partition = partitionA,
                        channelId = "channel-loose-consecutive",
                        anchor = ThreadDurableTimelineAnchor.AroundMessageId("channel-loose-consecutive-message-105", beforeCount = 0, afterCount = 1),
                    ),
                )
                assertEquals(listOf(105L, 106L), cappedRun.envelopes.map { it.fact.seq })
                assertTrue(cappedRun.gaps.isEmpty())
                assertTrue(cappedRun.hasOlderStored)
                assertTrue(cappedRun.hasNewerStored)

                // 3. Loose anchor at Long.MAX_VALUE with consecutive Long.MAX_VALUE - 1 works without overflow.
                val maxLooseScope = SyncScopeKey(partitionA, "channel-loose-max", "channel")
                val maxSeq = Long.MAX_VALUE
                store.applyCoverage(
                    scope = maxLooseScope,
                    envelopes = listOf(envOf("channel-loose-max", maxSeq - 1L), envOf("channel-loose-max", maxSeq)),
                    coverage = null,
                    revAtRequest = 0L,
                    userDriven = false,
                )
                val anchoredMaxLoose = assertNotNull(
                    reader.readTimeline(
                        partition = partitionA,
                        channelId = "channel-loose-max",
                        anchor = ThreadDurableTimelineAnchor.AroundMessageId("channel-loose-max-message-$maxSeq", beforeCount = 2, afterCount = 2),
                    ),
                )
                assertEquals(listOf(maxSeq - 1L, maxSeq), anchoredMaxLoose.envelopes.map { it.fact.seq })
                assertTrue(anchoredMaxLoose.gaps.isEmpty())
                assertFalse(anchoredMaxLoose.hasOlderStored)
                assertFalse(anchoredMaxLoose.hasNewerStored)
                assertFalse(anchoredMaxLoose.insideProvenSegment)
            }
        }

    private class RestoreCountingStore(private val delegate: CanonicalMessageV2Store) : CanonicalMessageV2Store {
        var restoreCalls = 0
        override suspend fun applySnapshot(frame: CanonicalMessageV2SnapshotFrame) = delegate.applySnapshot(frame)
        override suspend fun applyIncremental(frame: CanonicalMessageV2IncrementalFrame) = delegate.applyIncremental(frame)
        override suspend fun restore(scope: SyncScopeKey): RestoredCanonicalMessageV2Scope {
            restoreCalls += 1
            return delegate.restore(scope)
        }
        override suspend fun clearScope(scope: SyncScopeKey) = delegate.clearScope(scope)
        override suspend fun clearPartition(partition: DurablePartitionKey) = delegate.clearPartition(partition)
        override suspend fun knownPartitions() = delegate.knownPartitions()
        override suspend fun baselinedScopeKinds(partition: DurablePartitionKey, scopeId: SyncScopeId) =
            delegate.baselinedScopeKinds(partition, scopeId)
        override suspend fun readCursor(scope: SyncScopeKey) = delegate.readCursor(scope)
        override suspend fun containsFact(scope: SyncScopeKey, factId: String) = delegate.containsFact(scope, factId)
        override suspend fun readSegments(scope: SyncScopeKey) = delegate.readSegments(scope)
        override suspend fun maxLocalRev(partition: DurablePartitionKey) = delegate.maxLocalRev(partition)
        override suspend fun readFactsBefore(scope: SyncScopeKey, beforeSeq: Long, limit: Int) =
            delegate.readFactsBefore(scope, beforeSeq, limit)
        override suspend fun readFactsAfter(scope: SyncScopeKey, afterSeq: Long, limit: Int) =
            delegate.readFactsAfter(scope, afterSeq, limit)
        override suspend fun hasFactBefore(scope: SyncScopeKey, beforeSeq: Long) =
            delegate.hasFactBefore(scope, beforeSeq)
        override suspend fun hasFactAfter(scope: SyncScopeKey, afterSeq: Long) =
            delegate.hasFactAfter(scope, afterSeq)
        override suspend fun highestFactSeqBefore(scope: SyncScopeKey, beforeSeq: Long) =
            delegate.highestFactSeqBefore(scope, beforeSeq)
        override suspend fun lowestFactSeqAfter(scope: SyncScopeKey, afterSeq: Long) =
            delegate.lowestFactSeqAfter(scope, afterSeq)
        override suspend fun locateFact(partition: DurablePartitionKey, factId: String) =
            delegate.locateFact(partition, factId)
        override suspend fun touchUserAccess(scope: SyncScopeKey) = delegate.touchUserAccess(scope)
    }

    private val partitionA = DurablePartitionKey("server-a", "principal-a", SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID, "env-test")

    private fun cursor(latest: Long? = 1) = PersistedCursor(latest, latest, latest, hasLoadedOldest = true, epoch = "epoch-v2")

    private fun fact(seq: Long) = CanonicalMessageNodeV2(
        id = "message-$seq",
        seq = seq,
        senderType = "user",
        senderId = "human-a",
        content = "body $seq",
        messageType = "chat",
        createdAt = "2026-07-16T00:00:00.000Z",
        senderName = "Human",
    )

    private fun envelope(seq: Long) = CanonicalMessageEnvelopeV2(
        fact = fact(seq),
        context = CanonicalConversationContextV2.TopLevel(TopLevelScopeRefV2.Channel("server-a", "channel-a")),
    )

    @Test
    fun offlineCrossGapsNeverSplicesMultipleLooseRealtimeFactsIntoStackedGaps() = withFreshDatabase { database ->
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 100L })
        val reader = CanonicalMessageV2DurableLastKnownReader(store)
        val scope = SyncScopeKey(partitionA, "channel-a", "channel")

        runBlocking {
            store.applyCoverage(
                scope = scope,
                envelopes = (1L..5L).map(::envelope),
                coverage = CanonicalMessageV2Coverage(1L, 5L, reachesOldest = true, headAtVerify = false, CanonicalMessageV2SegmentSource.Older),
                revAtRequest = 0L,
                userDriven = true,
            )
            store.applyCoverage(
                scope = scope,
                envelopes = (20L..25L).map(::envelope),
                coverage = CanonicalMessageV2Coverage(20L, 25L, reachesOldest = false, headAtVerify = false, CanonicalMessageV2SegmentSource.Context),
                revAtRequest = 0L,
                userDriven = false,
            )
            store.applyCoverage(
                scope = scope,
                envelopes = (60L..65L).map(::envelope),
                coverage = CanonicalMessageV2Coverage(60L, 65L, reachesOldest = false, headAtVerify = true, CanonicalMessageV2SegmentSource.Latest),
                revAtRequest = 0L,
                userDriven = true,
            )
            // Five unsegmented loose WS facts between [1..5] and [20..25], and five between [20..25] and [60..65].
            store.applyCoverage(
                scope = scope,
                envelopes = listOf(10L, 12L, 14L, 16L, 18L, 30L, 35L, 40L, 45L, 50L).map(::envelope),
                coverage = null,
                revAtRequest = 0L,
                userDriven = false,
            )

            // Before(20, crossGaps=true) must bridge straight into [1..5] with ONE gap (5, 20), NOT 6 stacked gaps.
            val older = assertNotNull(
                reader.readTimeline(partitionA, "channel-a", ThreadDurableTimelineAnchor.Before(20L, limit = 20, crossGaps = true)),
            )
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L), older.envelopes.map { it.fact.seq })
            assertEquals(listOf(CanonicalMessageV2TimelineGap(afterSeq = 5L, beforeSeq = 20L)), older.gaps)
            assertFalse(older.hasOlderStored)
            assertTrue(older.reachesOldest)

            // After(25, crossGaps=true) must bridge straight into [60..65] with ONE gap (25, 60), NOT 6 stacked gaps.
            val newer = assertNotNull(
                reader.readTimeline(partitionA, "channel-a", ThreadDurableTimelineAnchor.After(25L, limit = 20, crossGaps = true)),
            )
            assertEquals(listOf(60L, 61L, 62L, 63L, 64L, 65L), newer.envelopes.map { it.fact.seq })
            assertEquals(listOf(CanonicalMessageV2TimelineGap(afterSeq = 25L, beforeSeq = 60L)), newer.gaps)
            assertFalse(newer.hasNewerStored)
            assertTrue(newer.reachesHead)

            // Limited read inside a lower segment reports hasOlderStored=true when more segmented rows remain.
            val partialOlder = assertNotNull(
                reader.readTimeline(partitionA, "channel-a", ThreadDurableTimelineAnchor.Before(20L, limit = 3, crossGaps = true)),
            )
            assertEquals(listOf(3L, 4L, 5L), partialOlder.envelopes.map { it.fact.seq })
            assertEquals(listOf(CanonicalMessageV2TimelineGap(afterSeq = 5L, beforeSeq = 20L)), partialOlder.gaps)
            assertTrue(partialOlder.hasOlderStored)
            assertFalse(partialOlder.reachesOldest)
        }
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
}
