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
    fun emptyBaselineReadsAnEmptyFrameAndReplyThreadBaselineReadsNothing() = withFreshDatabase { database ->
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L })
        val reader = CanonicalMessageV2DurableLastKnownReader(store)
        runBlocking {
            // Negative control: no baseline at all is still no frame.
            assertNull(reader.read(partitionA, "channel-a"))
            // Task #1137: a committed baseline with zero facts is the answer "empty channel", not a miss.
            store.applySnapshot(CanonicalMessageV2SnapshotFrame(SyncScopeKey(partitionA, "channel-a", "channel"), emptyList(), cursor(latest = null)))
            val empty = assertNotNull(reader.read(partitionA, "channel-a"))
            assertEquals("channel", empty.scope.scopeKind)
            assertTrue(empty.envelopes.isEmpty())
            assertNull(empty.cursor.latestSeq)

            // Reply-thread scopes are keyed by the thread id and are not this reader's surface.
            val threadScope = SyncScopeKey(partitionA, "thread-a", "thread")
            val reply = CanonicalMessageEnvelopeV2(
                fact = fact(7),
                context = CanonicalConversationContextV2.Thread(
                    replyScope = ReplyScopeRefV2("server-a", "thread-a"),
                    parentTopLevelScope = TopLevelScopeRefV2.Channel("server-a", "channel-a"),
                    rootMessageRef = MessageRefV2("server-a", "message-root"),
                ),
                replyMembership = ReplyMembershipV2(
                    root = MessageRefV2("server-a", "message-root"),
                    parentScope = ReplyScopeRefV2("server-a", "thread-a"),
                ),
            )
            store.applySnapshot(
                CanonicalMessageV2SnapshotFrame(
                    threadScope,
                    listOf(reply),
                    cursor().copy(parentScopeKind = "channel", parentScopeId = "channel-a", rootMessageId = "message-root"),
                ),
            )
            assertEquals(listOf("thread"), store.baselinedScopeKinds(partitionA, "thread-a"))
            assertNull(reader.read(partitionA, "thread-a"))
        }
    }

    @Test
    fun twoTopLevelKindsUnderOneIdAreAmbiguousAndReadNothing() = withFreshDatabase { database ->
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L })
        val logs = mutableListOf<String>()
        val reader = CanonicalMessageV2DurableLastKnownReader(store, logger = logs::add)
        runBlocking {
            store.applySnapshot(CanonicalMessageV2SnapshotFrame(SyncScopeKey(partitionA, "channel-a", "channel"), listOf(envelope(1)), cursor()))
            // Positive control: one committed kind reads back.
            assertNotNull(reader.read(partitionA, "channel-a"))
            assertTrue(logs.isEmpty(), logs.toString())

            // The current writer never commits two top-level kinds for one id; if it ever did, the
            // reader must refuse to guess which one the route means.
            val privateEnvelope = CanonicalMessageEnvelopeV2(
                fact = fact(2),
                context = CanonicalConversationContextV2.TopLevel(TopLevelScopeRefV2.Private("server-a", "channel-a")),
            )
            store.applySnapshot(CanonicalMessageV2SnapshotFrame(SyncScopeKey(partitionA, "channel-a", "private"), listOf(privateEnvelope), cursor(latest = 2)))
            assertEquals(setOf("channel", "private"), store.baselinedScopeKinds(partitionA, "channel-a").toSet())
            assertNull(reader.read(partitionA, "channel-a"))
        }
        val ambiguous = assertNotNull(logs.singleOrNull(), logs.toString())
        assertTrue("outcome=ambiguous_scope_kind" in ambiguous, ambiguous)
        assertTrue("scope=channel-a" in ambiguous, ambiguous)
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
    fun readerRejectsV1PartitionAndUntrimmedChannelId() = withFreshDatabase { database ->
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L })
        val reader = CanonicalMessageV2DurableLastKnownReader(store)
        runBlocking {
            assertFailsWith<IllegalArgumentException> {
                reader.read(partitionA.copy(consumerId = SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID), "channel-a")
            }
            assertNull(reader.read(partitionA, " channel-a"))
            assertNull(reader.read(partitionA, ""))
        }
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

            // readTimeline(Newest(5)) skips non-head island [100..110] and returns head tail + loose fact 120 with gap (20, 120)!
            val newest = assertNotNull(reader.readTimeline(partitionA, "channel-a", ThreadDurableTimelineAnchor.Newest(limit = 5)))
            assertEquals(listOf(17L, 18L, 19L, 20L, 120L), newest.envelopes.map { it.fact.seq })
            assertEquals(listOf(CanonicalMessageV2TimelineGap(afterSeq = 20L, beforeSeq = 120L)), newest.gaps)
            assertTrue(newest.hasOlderStored)
            assertFalse(newest.insideProvenSegment, "newest row 120 is loose above head")

            // Legacy readBefore(beforeSeq=120, limit=5) also jumps past island [100..110] and returns head [16..20] + hasOlderStored=true!
            val legacyBefore = assertNotNull(reader.readBefore(partitionA, "channel-a", beforeSeq = 120L, limit = 5))
            assertEquals(listOf(16L, 17L, 18L, 19L, 20L), legacyBefore.envelopes.map { it.fact.seq })
            assertTrue(legacyBefore.hasOlderStored)
            assertTrue(legacyBefore.hasLoadedOldest)

            // Channel B: head == null, loose realtime fact 50, context island above it [90..110].
            val scopeB = SyncScopeKey(partitionA, "channel-b", "channel")
            fun envB(seq: Long) = CanonicalMessageEnvelopeV2(
                fact = fact(seq),
                context = CanonicalConversationContextV2.TopLevel(TopLevelScopeRefV2.Channel("server-a", "channel-b")),
            )
            store.applyCoverage(
                scope = scopeB,
                envelopes = listOf(envB(50L)),
                coverage = null,
                revAtRequest = 0L,
                userDriven = false,
            )
            store.applyCoverage(
                scope = scopeB,
                envelopes = (90L..110L).map(::envB),
                coverage = CanonicalMessageV2Coverage(90L, 110L, reachesOldest = false, headAtVerify = false, CanonicalMessageV2SegmentSource.Context),
                revAtRequest = 0L,
                userDriven = false,
            )
            val looseOnly = assertNotNull(reader.readTimeline(partitionA, "channel-b", ThreadDurableTimelineAnchor.Newest(limit = 5)))
            assertEquals(listOf(50L), looseOnly.envelopes.map { it.fact.seq })
            assertFalse(looseOnly.hasOlderStored, "skipped newer island above 50 must not mark hasOlderStored=true")
            assertFalse(looseOnly.insideProvenSegment)
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
