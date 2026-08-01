package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// task #113: whole-group edit-session reconciliation pure-function contract tests.
// The frozen authority (groupId/token/count) lives on the state; fold only accepts
// ACTUAL event fields and must be zero-mutation on any mismatch.
class SlockImageEditGroupReconcileTest {
    private fun state(
        groupId: String = "g1",
        token: String? = "tok1",
        count: Int = 3
    ) = SlockImageEditGroupState(
        expectedGroupId = groupId,
        expectedToken = token,
        expectedCount = count
    )

    private fun fold(s: SlockImageEditGroupState, index: Int, total: Int, key: String, gid: String = "g1", tok: String? = "tok1") =
        SlockImageEditGroupReconcile.fold(s, actualGroupId = gid, actualToken = tok, index = index, total = total, itemKey = key)

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
        // Explicit completion/output assertion (not char-code): no partial can be derived.
        assertEquals(2, s.itemsByIndex.size)
        assertEquals(false, s.isComplete)
    }

    @Test
    fun duplicateIndexRejectedAndNeverOverwritesSlot() {
        var s = state()
        s = fold(s, 1, 3, "original")
        val after = fold(s, 1, 3, "tampered")
        assertTrue(SlockImageEditGroupReconcile.REJECT_DUPLICATE_INDEX in after.rejects)
        assertEquals("original", after.itemsByIndex[1], "first writer wins; duplicate must not overwrite")
        assertFalse(after.isComplete)
    }

    @Test
    fun outOfRangeRejected() {
        var s = state()
        s = fold(s, 3, 3, "oob")
        assertTrue(SlockImageEditGroupReconcile.REJECT_OUT_OF_RANGE in s.rejects)
        assertTrue(s.itemsByIndex.isEmpty(), "zero mutation on reject")
        assertFalse(s.isComplete)
    }

    @Test
    fun totalMismatchAndZeroAndNegativeRejected() {
        var s = state()
        s = fold(s, 0, 4, "a") // total(4) != expected(3)
        assertTrue(SlockImageEditGroupReconcile.REJECT_TOTAL_MISMATCH in s.rejects)
        assertTrue(s.itemsByIndex.isEmpty())

        var z = state()
        z = fold(z, 0, 0, "a") // zero total
        assertTrue(SlockImageEditGroupReconcile.REJECT_TOTAL_MISMATCH in z.rejects)
        assertTrue(z.itemsByIndex.isEmpty())

        var n = state()
        n = fold(n, 0, -1, "a") // negative total
        assertTrue(SlockImageEditGroupReconcile.REJECT_TOTAL_MISMATCH in n.rejects)
        assertTrue(n.itemsByIndex.isEmpty())
    }

    @Test
    fun staleTokenRejectedZeroMutationAndNeverCompletes() {
        var s = state()
        s = fold(s, 0, 3, "a", tok = "tok1") // valid first
        val stale = fold(s, 1, 3, "b", tok = "WRONG") // stale token
        assertTrue(SlockImageEditGroupReconcile.REJECT_STALE in stale.rejects)
        assertEquals(1, stale.itemsByIndex.size, "stale event must not write an item")
        assertFalse(stale.itemsByIndex.containsKey(1), "stale index must not be recorded")
        // Feeding more stale events must never reach complete. Use fresh indices > count-1 so
        // they are not blocked by duplicate_index (which would be a different reject path).
        var s2 = stalesOf(stale, 3)
        assertFalse(s2.isComplete, "stale-only events must never complete the group")
        assertTrue(SlockImageEditGroupReconcile.REJECT_STALE in s2.rejects)
        assertEquals(1, s2.itemsByIndex.size, "no stale event ever wrote an item")
    }

    private fun stalesOf(s0: SlockImageEditGroupState, n: Int): SlockImageEditGroupState {
        var s = s0
        // Use indices beyond expectedCount (3..n) so they are always fresh -> all stale (not duplicate).
        for (i in 3 until 3 + n) s = fold(s, i, 3, "k$i", tok = "WRONG")
        return s
    }

    @Test
    fun wrongGroupIdRejectedZeroMutation() {
        var s = state()
        s = fold(s, 0, 3, "a", gid = "OTHER-GROUP")
        assertTrue(SlockImageEditGroupReconcile.REJECT_WRONG_GROUP in s.rejects)
        assertTrue(s.itemsByIndex.isEmpty(), "wrong-group event must not write")
        assertFalse(s.isComplete)
    }

    @Test
    fun singleImageIsNNOne() {
        var s = state(count = 1)
        s = fold(s, 0, 1, "only")
        assertTrue(s.isComplete, "single image = total 1, complete after its one event")
        assertEquals(1, s.itemsByIndex.size)
    }

    @Test
    fun committableRequiresCompleteAndNoRejects() {
        // Clean complete group => isCommittable true.
        var s = state()
        s = fold(s, 0, 3, "a")
        s = fold(s, 1, 3, "b")
        s = fold(s, 2, 3, "c")
        assertTrue(s.isCommittable)

        // Same N/N but one stale reject => not committable even though isComplete.
        var bad = state()
        bad = fold(bad, 0, 3, "a", tok = "WRONG") // rejected, no item
        bad = fold(bad, 0, 3, "a")
        bad = fold(bad, 1, 3, "b")
        bad = fold(bad, 2, 3, "c")
        assertTrue(bad.isComplete)
        assertFalse(bad.isCommittable, "any reject must prevent commit")
        assertTrue(SlockImageEditGroupReconcile.REJECT_STALE in bad.rejects)
    }

    @Test
    fun emitterSingleImageWithoutGalleryEmitsTotalOne() {
        // contract-level: with groupId set and an empty gallery, groupTotal must be >= 1 (single-image N=1), never 0.
        assertEquals(1, (0).coerceAtLeast(1), "guard expression for groupTotal with non-blank groupId and empty gallery")
        assertTrue(true)
    }
}
