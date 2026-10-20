package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// task #113: whole-group edit-session reconciliation pure-function contract tests.
// The frozen authority (groupId, count) lives on the state; fold only accepts ACTUAL
// event fields and must be zero-mutation on any group/total/range mismatch. A
// same-index idempotent replay is a no-op (must not poison a complete group); a
// conflicting itemKey at an occupied index is rejected. No token pseudo-contract:
// freshness/generation is proven by unique groupId + the consumer's page-generation gate.
class RaftImageEditGroupReconcileTest {
    private fun state(groupId: String = "g1", count: Int = 3) =
        RaftImageEditGroupState(expectedGroupId = groupId, expectedCount = count)

    private fun fold(s: RaftImageEditGroupState, index: Int, total: Int, key: String, gid: String = "g1") =
        RaftImageEditGroupReconcile.fold(s, actualGroupId = gid, index = index, total = total, itemKey = key)

    @Test
    fun groupCompletesOnlyWhenAllNNIndicesPresentOrderIndependent() {
        var s = state()
        s = fold(s, 2, 3, "c")
        assertFalse(s.isComplete)
        s = fold(s, 0, 3, "a")
        assertFalse(s.isComplete)
        s = fold(s, 1, 3, "b")
        assertTrue(s.isComplete, "all 0..2 present => N/N complete")
        assertEquals(setOf(0, 1, 2), s.itemsByIndex.keys)
        assertTrue(s.rejects.isEmpty())
    }

    @Test
    fun missingIndexNeverCompletesAndYieldsNoPartialCommit() {
        var s = state()
        s = fold(s, 0, 3, "a")
        s = fold(s, 2, 3, "c")
        assertFalse(s.isComplete, "index 1 missing => never complete")
        // Explicit completion/output assertion: no partial can be derived.
        assertEquals(2, s.itemsByIndex.size)
        assertEquals(false, s.isComplete)
    }

    @Test
    fun conflictingDuplicateIndexRejectedProductionAndNeverOverwrites() {
        var s = state()
        s = fold(s, 1, 3, "original")
        val after = fold(s, 1, 3, "tampered") // same index, different itemKey
        assertTrue(RaftImageEditGroupReconcile.REJECT_DUPLICATE_CONFLICT in after.rejects)
        assertEquals("original", after.itemsByIndex[1], "first writer wins; conflict must not overwrite")
        assertFalse(after.isCommittable, "a conflict poisons the group")
    }

    @Test
    fun identicalReplayIsIdempotentNoOpAndKeepsGroupCommittable() {
        var s = state()
        s = fold(s, 0, 3, "a")
        s = fold(s, 1, 3, "b")
        s = fold(s, 2, 3, "c")
        // Cross-process exactly-once redelivery replays an identical event.
        val replay = fold(s, 1, 3, "b")
        assertTrue(replay.isComplete)
        assertTrue(replay.isCommittable, "identical replay must be a no-op, not poison")
        assertTrue(replay.rejects.isEmpty(), "identical replay adds no reject")
        assertEquals(3, replay.itemsByIndex.size)
    }

    @Test
    fun outOfRangeRejected() {
        var s = state()
        s = fold(s, 3, 3, "oob")
        assertTrue(RaftImageEditGroupReconcile.REJECT_OUT_OF_RANGE in s.rejects)
        assertTrue(s.itemsByIndex.isEmpty(), "zero mutation on reject")
        assertFalse(s.isComplete)
    }

    @Test
    fun totalMismatchAndZeroAndNegativeRejected() {
        var s = state()
        s = fold(s, 0, 4, "a")
        assertTrue(RaftImageEditGroupReconcile.REJECT_TOTAL_MISMATCH in s.rejects)
        assertTrue(s.itemsByIndex.isEmpty())

        var z = state()
        z = fold(z, 0, 0, "a") // zero total
        assertTrue(RaftImageEditGroupReconcile.REJECT_TOTAL_MISMATCH in z.rejects)
        assertTrue(z.itemsByIndex.isEmpty())

        var n = state()
        n = fold(n, 0, -1, "a") // negative total
        assertTrue(RaftImageEditGroupReconcile.REJECT_TOTAL_MISMATCH in n.rejects)
        assertTrue(n.itemsByIndex.isEmpty())
    }

    @Test
    fun wrongGroupIdRejectedZeroMutation() {
        var s = state()
        s = fold(s, 0, 3, "a", gid = "OTHER-GROUP")
        assertTrue(RaftImageEditGroupReconcile.REJECT_WRONG_GROUP in s.rejects)
        assertTrue(s.itemsByIndex.isEmpty(), "wrong-group event must not write")
        assertFalse(s.isComplete)
    }

    @Test
    fun staleLikeOldEventWithWrongGroupNeverCompletes() {
        var s = state()
        // A stale/old event from a previous session would carry a different groupId; it must not write.
        s = fold(s, 0, 3, "stale", gid = "OLD-SESSION")
        assertFalse(s.isComplete)
        assertFalse(s.isCommittable)
    }

    @Test
    fun singleImageIsNNOne() {
        var s = state(count = 1)
        s = fold(s, 0, 1, "only")
        assertTrue(s.isComplete, "single image = total 1, complete after its one event")
        assertTrue(s.isCommittable)
        assertEquals(1, s.itemsByIndex.size)
    }

    @Test
    fun committableRequiresCompleteAndNoRejects() {
        var s = state()
        s = fold(s, 0, 3, "a")
        s = fold(s, 1, 3, "b")
        s = fold(s, 2, 3, "c")
        assertTrue(s.isCommittable)

        var bad = state()
        bad = fold(bad, 0, 3, "a", gid = "WRONG-GROUP") // rejected, no item
        bad = fold(bad, 0, 3, "a")
        bad = fold(bad, 1, 3, "b")
        bad = fold(bad, 2, 3, "c")
        assertTrue(bad.isComplete)
        assertFalse(bad.isCommittable, "any reject must prevent commit")
        assertTrue(RaftImageEditGroupReconcile.REJECT_WRONG_GROUP in bad.rejects)
    }

    // --- Production-used group-total resolution (Vera #2): bound to the actual emitter helper. ---
    @Test
    fun groupTotalResolutionBlankLegacy() {
        assertEquals(2, RaftImageEditGroupReconcile.resolveGroupTotal("", 2))
        assertEquals(0, RaftImageEditGroupReconcile.resolveGroupTotal("", 0), "blank groupId keeps legacy on-zero")
    }

    @Test
    fun groupTotalResolutionGroupedSingleWithoutGalleryFloorsToOne() {
        assertEquals(1, RaftImageEditGroupReconcile.resolveGroupTotal("g1", 0), "grouped single no gallery => total 1")
        assertEquals(1, RaftImageEditGroupReconcile.resolveGroupTotal("g1", 1))
    }

    @Test
    fun groupTotalResolutionGroupedMulti() {
        assertEquals(4, RaftImageEditGroupReconcile.resolveGroupTotal("g1", 4))
    }
}
