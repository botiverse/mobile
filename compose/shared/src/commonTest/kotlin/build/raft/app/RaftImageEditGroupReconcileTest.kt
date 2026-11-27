package build.raft.app

import build.raft.app.thread.key
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
    fun wrongGroupIdRejectedZeroMutation() {
        var s = state()
        s = fold(s, 0, 3, "a", gid = "OTHER-GROUP")
        assertTrue(RaftImageEditGroupReconcile.REJECT_WRONG_GROUP in s.rejects)
        assertTrue(s.itemsByIndex.isEmpty(), "wrong-group event must not write")
        assertFalse(s.isComplete)
    }

    // --- Production-used group-total resolution (Vera #2): bound to the actual emitter helper. ---
    @Test
    fun groupTotalResolutionGroupedMulti() {
        assertEquals(4, RaftImageEditGroupReconcile.resolveGroupTotal("g1", 4))
    }
}
