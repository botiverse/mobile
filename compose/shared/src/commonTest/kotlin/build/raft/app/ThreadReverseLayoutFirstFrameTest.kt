package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * task #193: on a reply-cache hit (#3083) the thread renders content on the FIRST frame, while the
 * reverse-layout gate inputs (reply-cache provenance) are still null. The list used to fall back to
 * FORWARD in that window and latch it, so the real answer never arrived: latest item off the bottom,
 * a large empty band, back-to-bottom pill floating mid-screen. 1.14.0 did not show this because its
 * first frame was a loading shell, by which time the gate had resolved.
 *
 * Decided contract (artin): with no explicit user choice and the gate unresolved, the open runs
 * REVERSE for its whole lifetime. A gate that later resolves to `false` takes effect on the NEXT
 * open -- the direction is latched once per thread instance, so nothing within one open flips.
 */
class ThreadReverseLayoutFirstFrameTest {

    /**
     * Models `threadReverseLayoutEffective(userChoice) { gate }` with the one change that matters:
     * the unresolved-gate fallback is [THREAD_REVERSE_LAYOUT_UNDECIDED_DEFAULT] rather than `false`.
     */
    private fun directionForOpen(userChoice: Boolean?, gateResolvedTo: Boolean?): Boolean =
        userChoice ?: (gateResolvedTo ?: THREAD_REVERSE_LAYOUT_UNDECIDED_DEFAULT)

    @Test
    fun warmEntryWithALateGateRunsReverseOnTheFirstFrame() {
        // The regression. A cache-hit entry composes with the gate unresolved; before this change
        // that produced `false` and the list was latched forward. Reading `false` here means the bug
        // is back.
        assertTrue(
            directionForOpen(userChoice = null, gateResolvedTo = null),
            "an unresolved gate must not produce a forward list on a warm entry"
        )
        assertTrue(THREAD_REVERSE_LAYOUT_UNDECIDED_DEFAULT)
    }

    @Test
    fun anExplicitUserChoiceAlwaysWins() {
        assertFalse(
            directionForOpen(userChoice = false, gateResolvedTo = null),
            "a user who chose forward must not be flipped to reverse by the new default"
        )
        assertTrue(directionForOpen(userChoice = true, gateResolvedTo = null))
        assertFalse(
            directionForOpen(userChoice = false, gateResolvedTo = true),
            "an explicit forward choice is not overridden by a reverse gate either"
        )
    }

    @Test
    fun aGateThatResolvesFalseDoesNotFlipTheOpenItArrivedIn() {
        // Latching once per thread instance: the second argument is only consulted when the gate was
        // already resolved at first composition. Within an open that started undecided, the late
        // `false` must not change it -- it applies on the next open.
        val firstOpenWithLateGate = directionForOpen(userChoice = null, gateResolvedTo = null)
        val nextOpenWithKnownGate = directionForOpen(userChoice = null, gateResolvedTo = false)
        assertTrue(firstOpenWithLateGate, "the open that raced the gate stays reverse")
        assertFalse(nextOpenWithKnownGate, "the following open honours the resolved false")
    }

    @Test
    fun aGateThatResolvesTrueKeepsTheOpenReverse() {
        // The common case: reverse either way, so the open is consistent from its first frame to its
        // last and there is no in-open direction change to handle.
        assertTrue(directionForOpen(userChoice = null, gateResolvedTo = null))
        assertTrue(directionForOpen(userChoice = null, gateResolvedTo = true))
    }
}
