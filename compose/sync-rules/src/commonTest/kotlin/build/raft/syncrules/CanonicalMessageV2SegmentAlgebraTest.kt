package build.raft.syncrules

import build.raft.syncrules.CanonicalMessageV2SegmentAlgebra.head
import build.raft.syncrules.CanonicalMessageV2SegmentAlgebra.isNormalized
import build.raft.syncrules.CanonicalMessageV2SegmentAlgebra.memberOf
import build.raft.syncrules.CanonicalMessageV2SegmentAlgebra.trimBelow
import build.raft.syncrules.CanonicalMessageV2SegmentAlgebra.union
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        source: CanonicalMessageV2SegmentSource = CanonicalMessageV2SegmentSource.Older,
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
    fun overlappingAndContainedRangesMergeIntoOne() {
        assertEquals(listOf(1L to 9L), union(listOf(seg(1, 5)), seg(4, 9)).ranges())
        assertEquals(listOf(1L to 10L), union(listOf(seg(1, 10)), seg(3, 4)).ranges())
        assertEquals(listOf(1L to 10L), union(listOf(seg(3, 4)), seg(1, 10)).ranges())
        // A claim bridging two islands joins all three; one seq short of the upper island it does not.
        assertEquals(listOf(1L to 9L), union(listOf(seg(1, 3), seg(7, 9)), seg(4, 6)).ranges())
        assertEquals(listOf(1L to 5L, 7L to 9L), union(listOf(seg(1, 3), seg(7, 9)), seg(4, 5)).ranges())
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
    fun headAtVerifyComesFromTheSegmentReachingHighest() {
        assertTrue(union(listOf(seg(1, 5)), seg(6, 9, head = true)).single().headAtVerify)
        // The head moved above the verified-at-head range: no longer the head.
        assertFalse(union(listOf(seg(1, 5, head = true)), seg(6, 9)).single().headAtVerify)
        // A contained claim does not take the head away.
        assertTrue(union(listOf(seg(1, 9, head = true)), seg(2, 3)).single().headAtVerify)
        // Same high: either verification at the head counts.
        assertTrue(union(listOf(seg(1, 9)), seg(5, 9, head = true)).single().headAtVerify)
    }

    @Test
    fun aMergeKeepsTheNewestVerificationAndItsSource() {
        val merged = union(
            listOf(seg(1, 5, at = 10, source = CanonicalMessageV2SegmentSource.Migrated)),
            seg(6, 9, at = 20, source = CanonicalMessageV2SegmentSource.Latest),
        ).single()
        assertEquals(20L, merged.verifiedAtMs)
        assertEquals(CanonicalMessageV2SegmentSource.Latest, merged.source)
    }

    @Test
    fun unionAlwaysReturnsANormalizedListAndRefusesAnUnnormalizedOne() {
        val result = union(listOf(seg(1, 2), seg(10, 12), seg(20, 25)), seg(13, 18))
        assertEquals(listOf(1L to 2L, 10L to 18L, 20L to 25L), result.ranges())
        assertTrue(isNormalized(result))
        assertFalse(isNormalized(listOf(seg(1, 5), seg(6, 9))), "touching segments are not normalized")
        assertTrue(isNormalized(listOf(seg(1, 5), seg(7, 9))))
        assertFailsWith<IllegalArgumentException> { union(listOf(seg(1, 5), seg(6, 9)), seg(20, 21)) }
    }

    @Test
    fun membershipIsInclusiveAndAReachesOldestSegmentOwnsEverythingBelow() {
        val segments = listOf(seg(5, 10), seg(20, 30, oldest = false))
        assertEquals(5L, memberOf(segments, 5)?.lowSeq)
        assertEquals(5L, memberOf(segments, 10)?.lowSeq)
        assertNull(memberOf(segments, 11), "a seq between segments is loose")
        assertNull(memberOf(segments, 4))
        assertEquals(5L, memberOf(listOf(seg(5, 10, oldest = true)), 4)?.lowSeq)
        assertEquals(30L, head(segments)?.highSeq)
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
    fun aMessageWindowProvesTheRangeAboveItsPredecessor() {
        val older = CanonicalMessageV2SegmentSource.Older
        // Nothing below the page and no history cutoff: the page reaches the oldest.
        assertEquals(
            CanonicalMessageV2Coverage(1, 20, reachesOldest = true, headAtVerify = true, older),
            CanonicalMessageV2Coverage.fromMessageWindow(0, 20, completeThroughLatest = true, historyLimited = false, older),
        )
        // Same bounds, a plan cutoff hid older rows: no reachesOldest.
        assertEquals(
            CanonicalMessageV2Coverage(1, 20, reachesOldest = false, headAtVerify = true, older),
            CanonicalMessageV2Coverage.fromMessageWindow(0, 20, completeThroughLatest = true, historyLimited = true, older),
        )
        // A predecessor exists: the claim starts right above it, never at the page's first row.
        assertEquals(
            CanonicalMessageV2Coverage(5, 20, reachesOldest = false, headAtVerify = false, older),
            CanonicalMessageV2Coverage.fromMessageWindow(4, 20, completeThroughLatest = false, historyLimited = false, older),
        )
        // An empty page: coveredAfterSeq = coveredThroughSeq = high-water. Only an empty channel says anything.
        assertNull(CanonicalMessageV2Coverage.fromMessageWindow(7, 7, completeThroughLatest = true, historyLimited = false, older))
        assertEquals(
            CanonicalMessageV2Coverage(0, 0, reachesOldest = true, headAtVerify = true, older),
            CanonicalMessageV2Coverage.fromMessageWindow(0, 0, completeThroughLatest = true, historyLimited = false, older),
        )
        assertNull(CanonicalMessageV2Coverage.fromMessageWindow(0, 0, completeThroughLatest = true, historyLimited = true, older))
    }

    @Test
    fun aContextPageProvesItsRowsAndAnOpenEdgeOnlyWhereTheServerSaysSo() {
        assertEquals(
            CanonicalMessageV2Coverage(7, 9, reachesOldest = false, headAtVerify = false, CanonicalMessageV2SegmentSource.Context),
            CanonicalMessageV2Coverage.fromContextPage(listOf(9, 7, 8), hasOlder = true, hasNewer = true, historyLimited = false),
        )
        assertEquals(
            CanonicalMessageV2Coverage(7, 9, reachesOldest = true, headAtVerify = true, CanonicalMessageV2SegmentSource.Context),
            CanonicalMessageV2Coverage.fromContextPage(listOf(9, 7, 8), hasOlder = false, hasNewer = false, historyLimited = false),
        )
        assertFalse(
            CanonicalMessageV2Coverage.fromContextPage(listOf(7), hasOlder = false, hasNewer = false, historyLimited = true)!!.reachesOldest,
        )
        assertNull(CanonicalMessageV2Coverage.fromContextPage(emptyList(), hasOlder = false, hasNewer = false, historyLimited = false))
    }

    @Test
    fun aLegacyWindowAndTheHeadMirrorRoundTrip() {
        val window = PersistedCursor(20, 5, 20, hasLoadedOldest = false, hasLoadedNewest = true, epoch = "e")
        val segment = window.legacyWindowSegment(verifiedAtMs = 3L)!!
        assertEquals(seg(5, 20, head = true, at = 3L, source = CanonicalMessageV2SegmentSource.Legacy), segment)
        assertEquals(window, window.mirroring(segment, 5L to 20L))
        val empty = PersistedCursor(null, null, null, hasLoadedOldest = true, hasLoadedNewest = true)
        val emptySegment = empty.legacyWindowSegment(verifiedAtMs = 3L)!!
        assertTrue(emptySegment.isEmptyBaseline)
        assertEquals(empty, empty.mirroring(emptySegment, null))
        // No window: no segment, and the mirror of no head claims nothing.
        val none = PersistedCursor(null, null, null, hasLoadedOldest = false, hasLoadedNewest = true)
        assertNull(none.legacyWindowSegment(verifiedAtMs = 3L))
        assertEquals(none.copy(hasLoadedNewest = false), none.mirroring(null, null))
        assertNull(PersistedCursor(9, 9, 4).legacyWindowSegment(verifiedAtMs = 3L), "an inverted window claims nothing")
        // The edges are stored-fact seqs, never a claim bound: the same head over facts 7..18 mirrors 7..18.
        assertEquals(window.copy(oldestLoadedSeq = 7, newestLoadedSeq = 18), window.mirroring(segment, 7L to 18L))
        assertEquals(window.copy(oldestLoadedSeq = null, newestLoadedSeq = null), window.mirroring(segment, null))
    }
}
