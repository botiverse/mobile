package build.raft.app.network.sync

import build.raft.app.network.sync.CanonicalMessageV2SegmentAlgebra.head
import build.raft.app.network.sync.CanonicalMessageV2SegmentAlgebra.provenSegmentAfter
import build.raft.app.network.sync.CanonicalMessageV2SegmentAlgebra.provenSegmentBefore
import build.raft.app.network.sync.CanonicalMessageV2SegmentAlgebra.reachesHeadAt
import build.raft.app.network.sync.CanonicalMessageV2SegmentAlgebra.reachesOldestAt
import build.raft.app.network.sync.CanonicalMessageV2SegmentAlgebra.trimBelow
import build.raft.app.network.sync.CanonicalMessageV2SegmentAlgebra.union
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Message storage design 2026-10-27 §2 / I3: the pure segment algebra. Every rule is a pair with the same
 * arithmetic shape and opposite outcomes, so only code that applies the rule (not a coincidence) is green.
 */
class CanonicalMessageV2SegmentAlgebraTest {
    private fun seg(
        low: Long,
        high: Long,
        oldest: Boolean = false,
        head: Boolean = false,
        at: Long = 1L,
        source: CanonicalMessageV2SegmentSource = CanonicalMessageV2SegmentSource.Latest,
    ) = CanonicalMessageV2Segment(low, high, oldest, head, at, source)

    private fun List<CanonicalMessageV2Segment>.ranges() = map { it.lowSeq to it.highSeq }

    @Test
    fun touchingRangesMergeButAOneSeqGapKeepsThemApart() {
        // h1 + 1 == l2 touches; h1 + 2 == l2 leaves seq 6 unproven.
        assertEquals(listOf(1L to 9L), union(listOf(seg(1, 5)), seg(6, 9)).ranges())
        assertEquals(listOf(1L to 5L, 7L to 9L), union(listOf(seg(1, 5)), seg(7, 9)).ranges())
        // Same from the other side.
        assertEquals(listOf(1L to 9L), union(listOf(seg(6, 9)), seg(1, 5)).ranges())
        assertEquals(listOf(1L to 5L, 7L to 9L), union(listOf(seg(7, 9)), seg(1, 5)).ranges())
    }

    @Test
    fun reachesOldestPropagatesThroughAMergeAndCoversEverythingBelow() {
        val merged = union(listOf(seg(10, 20, oldest = true)), seg(21, 30)).single()
        assertEquals(10L to 30L, merged.lowSeq to merged.highSeq)
        assertTrue(merged.reachesOldest)
        // Same ranges without the flag: no propagation.
        assertFalse(union(listOf(seg(10, 20)), seg(21, 30)).single().reachesOldest)
        // A reachesOldest claim proves nothing exists below it, so it absorbs a lower island ...
        val absorbed = union(listOf(seg(3, 5)), seg(10, 20, oldest = true))
        assertEquals(listOf(3L to 20L), absorbed.ranges())
        assertTrue(absorbed.single().reachesOldest)
        // ... while the same claim without it leaves the island apart.
        assertEquals(listOf(3L to 5L, 10L to 20L), union(listOf(seg(3, 5)), seg(10, 20)).ranges())
    }

    @Test
    fun anIslandIsNeverTheHeadEvenAboveTheWindow() {
        val window = seg(10, 20, source = CanonicalMessageV2SegmentSource.Latest)
        val island = seg(100, 110, source = CanonicalMessageV2SegmentSource.Context)
        assertEquals(window, head(listOf(window, island)))
        assertEquals(window, head(listOf(window, seg(100, 110, source = CanonicalMessageV2SegmentSource.Newer))))
        assertNull(head(listOf(island)))
    }

    @Test
    fun trimmingTheLowEndClearsReachesOldest() {
        // The cut reaches into the segment: it starts above the cut and no longer reaches the oldest.
        val trimmed = trimBelow(listOf(seg(1, 10, oldest = true, head = true)), cutSeq = 4).single()
        assertEquals(5L to 10L, trimmed.lowSeq to trimmed.highSeq)
        assertFalse(trimmed.reachesOldest)
        assertTrue(trimmed.headAtVerify)
        // One below the segment: a plain segment is untouched ...
        assertEquals(seg(5, 10), trimBelow(listOf(seg(5, 10)), cutSeq = 4).single())
        // ... but a reachesOldest one claimed the deleted seqs too, so it loses the flag (keeps its low).
        val claimedBelow = trimBelow(listOf(seg(5, 10, oldest = true)), cutSeq = 4).single()
        assertEquals(5L to 10L, claimedBelow.lowSeq to claimedBelow.highSeq)
        assertFalse(claimedBelow.reachesOldest)
        // A segment entirely at or below the cut goes; one above stays.
        assertEquals(listOf(11L to 12L), trimBelow(listOf(seg(1, 10), seg(11, 12)), cutSeq = 10).ranges())
        assertEquals(listOf(10L to 10L), trimBelow(listOf(seg(1, 10)), cutSeq = 9).ranges())
    }

    @Test
    fun aContextPageProvesItsRowsAndAnOpenEdgeOnlyWhereTheServerSaysSo() {
        assertEquals(
            CanonicalMessageV2Coverage(7, 9, reachesOldest = false, headAtVerify = false, CanonicalMessageV2SegmentSource.Context),
            CanonicalMessageV2Coverage.fromContextPage(listOf(9, 7, 8), hasOlder = true, historyLimited = false),
        )
        assertEquals(
            // hasNewer false proves nothing: seqs are allocated before commit.
            CanonicalMessageV2Coverage(7, 9, reachesOldest = true, headAtVerify = false, CanonicalMessageV2SegmentSource.Context),
            CanonicalMessageV2Coverage.fromContextPage(listOf(9, 7, 8), hasOlder = false, historyLimited = false),
        )
        assertFalse(
            CanonicalMessageV2Coverage.fromContextPage(listOf(7), hasOlder = false, historyLimited = true)!!.reachesOldest,
        )
        assertNull(CanonicalMessageV2Coverage.fromContextPage(emptyList(), hasOlder = false, historyLimited = false))
    }

    @Test
    fun provenSegmentBeforeAndAfterDistinguishSegmentInteriorFromEdges() {
        val lower = seg(1, 10, oldest = true, source = CanonicalMessageV2SegmentSource.Context)
        val unclosedLower = seg(1, 10, oldest = false, source = CanonicalMessageV2SegmentSource.Context)
        val upper = seg(20, 30, head = true, source = CanonicalMessageV2SegmentSource.Latest)
        val segments = listOf(lower, upper)

        // Strictly inside upper: beforeSeq = 21 -> predecessor 20 is in upper.
        assertEquals(upper, provenSegmentBefore(segments, beforeSeq = 21L))
        // At lower edge of upper: beforeSeq = 20 -> predecessor 19 is in the unproven gap.
        assertNull(provenSegmentBefore(segments, beforeSeq = 20L))
        // At lower edge of a reachesOldest segment: beforeSeq = 5 -> 4, and beforeSeq = 1 -> 0 is covered by effectiveLowSeq = 0.
        assertEquals(lower, provenSegmentBefore(segments, beforeSeq = 5L))
        assertEquals(lower, provenSegmentBefore(segments, beforeSeq = 1L))
        assertNull(provenSegmentBefore(listOf(unclosedLower), beforeSeq = 1L))
        assertNull(provenSegmentBefore(segments, beforeSeq = 0L))
        assertNull(provenSegmentBefore(segments, beforeSeq = Long.MIN_VALUE))

        // Strictly inside lower: afterSeq = 9 -> successor 10 is in lower.
        assertEquals(lower, provenSegmentAfter(segments, afterSeq = 9L))
        // At upper edge of lower: afterSeq = 10 -> successor 11 is unproven.
        assertNull(provenSegmentAfter(segments, afterSeq = 10L))
        // At upper edge of head: afterSeq = 30 -> successor 31 is outside segment.
        assertNull(provenSegmentAfter(segments, afterSeq = 30L))
        assertNull(provenSegmentAfter(segments, afterSeq = Long.MAX_VALUE))
    }

    @Test
    fun reachesOldestAndReachesHeadRequireBothSegmentProofAndNoMoreStoredRows() {
        val reached = listOf(seg(5, 20, oldest = true, head = true))
        val unreached = listOf(seg(5, 20, oldest = false, head = false))
        val emptyBaseline = listOf(seg(0, 0, oldest = true, head = true))

        assertTrue(reachesOldestAt(oldestSeq = 5L, segments = reached, hasOlderStored = false))
        assertFalse(reachesOldestAt(oldestSeq = 5L, segments = reached, hasOlderStored = true))
        assertFalse(reachesOldestAt(oldestSeq = 5L, segments = unreached, hasOlderStored = false))
        assertFalse(reachesOldestAt(oldestSeq = 25L, segments = reached, hasOlderStored = false))
        assertTrue(reachesOldestAt(oldestSeq = null, segments = emptyBaseline, hasOlderStored = false))
        assertFalse(reachesOldestAt(oldestSeq = null, segments = unreached, hasOlderStored = false))

        assertTrue(reachesHeadAt(newestSeq = 20L, segments = reached, hasNewerStored = false))
        assertFalse(reachesHeadAt(newestSeq = 20L, segments = reached, hasNewerStored = true))
        assertFalse(reachesHeadAt(newestSeq = 20L, segments = unreached, hasNewerStored = false))
        assertFalse(reachesHeadAt(newestSeq = 25L, segments = reached, hasNewerStored = false))
        assertTrue(reachesHeadAt(newestSeq = null, segments = emptyBaseline, hasNewerStored = false))
        assertFalse(reachesHeadAt(newestSeq = null, segments = unreached, hasNewerStored = false))
    }
}
