package build.raft.app.components

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #2224 review blocker 2: the edge guard used to consume every gesture born
 * inside the inset from the first down, which made the iOS 24dp strip dead to
 * row taps and vertical list scrolls. These tests pin the claim policy that
 * fixes it: nothing is claimed until the pointer proves it is a
 * horizontal-dominant drag past the slop; stationary taps (and their natural
 * jitter) stay Undecided forever, vertical-dominant drags are released to the
 * content. Whether the surrounding consumption loop actually blocks the
 * port's pager is runtime evidence (G-edge acceptance case), not this test's
 * claim.
 */
class TabPagerEdgeGuardDecisionTest {
    @Test
    fun `stationary tap never claims`() {
        assertEquals(
            EdgeGuardGestureDecision.Undecided,
            edgeGuardGestureDecision(horizontalDragDp = 0f, verticalDragDp = 0f),
        )
    }

    @Test
    fun `tap jitter below the slop never claims`() {
        assertEquals(
            EdgeGuardGestureDecision.Undecided,
            edgeGuardGestureDecision(horizontalDragDp = 2f, verticalDragDp = 1.5f),
        )
        assertEquals(
            EdgeGuardGestureDecision.Undecided,
            edgeGuardGestureDecision(horizontalDragDp = -3f, verticalDragDp = 2f),
        )
    }

    @Test
    fun `horizontal drag past the slop claims in both directions`() {
        assertEquals(
            EdgeGuardGestureDecision.Claim,
            edgeGuardGestureDecision(horizontalDragDp = 6f, verticalDragDp = 1f),
        )
        assertEquals(
            EdgeGuardGestureDecision.Claim,
            edgeGuardGestureDecision(horizontalDragDp = -8f, verticalDragDp = 2f),
        )
    }

    @Test
    fun `vertical drag past the slop releases to the content below`() {
        assertEquals(
            EdgeGuardGestureDecision.Release,
            edgeGuardGestureDecision(horizontalDragDp = 1f, verticalDragDp = 9f),
        )
        assertEquals(
            EdgeGuardGestureDecision.Release,
            edgeGuardGestureDecision(horizontalDragDp = 0f, verticalDragDp = -7f),
        )
    }

    @Test
    fun `claim slop boundary pins the constant to exactly six dp`() {
        // These two assertions pin EdgeGuardClaimSlopDp to the single value
        // 6.0 (verified by mutation: 4/5/5.999 and 6.5/7 all go red). The
        // literals are DELIBERATELY hardcoded: rewriting them in terms of the
        // constant ("clean up the magic number") turns both into tautologies
        // that stay green for ANY slop value — they would then test the
        // function's shape at its own threshold, not the threshold's value.
        assertEquals(
            EdgeGuardGestureDecision.Undecided,
            edgeGuardGestureDecision(horizontalDragDp = 5.999f, verticalDragDp = 0f),
        )
        assertEquals(
            EdgeGuardGestureDecision.Claim,
            edgeGuardGestureDecision(horizontalDragDp = 6.0f, verticalDragDp = 0f),
        )
    }

    @Test
    fun `diagonal tie goes to the horizontal claim`() {
        // The guard exists exactly for horizontal edge gestures, so an
        // ambiguous 45-degree drag is claimed rather than leaked to the pager.
        assertEquals(
            EdgeGuardGestureDecision.Claim,
            edgeGuardGestureDecision(horizontalDragDp = 9f, verticalDragDp = 9f),
        )
    }

    @Test
    fun `sub slop dominance does not decide early`() {
        // Dominance alone is not enough: a 3dp-vs-1dp drift is still within
        // tap jitter and must not lock the gesture either way.
        assertEquals(
            EdgeGuardGestureDecision.Undecided,
            edgeGuardGestureDecision(horizontalDragDp = 3f, verticalDragDp = 1f),
        )
        assertEquals(
            EdgeGuardGestureDecision.Undecided,
            edgeGuardGestureDecision(horizontalDragDp = 1f, verticalDragDp = 3f),
        )
    }
}
