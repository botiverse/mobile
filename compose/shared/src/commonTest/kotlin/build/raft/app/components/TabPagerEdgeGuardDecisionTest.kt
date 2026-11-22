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
    fun `when cannot scroll backward rightward drag past slop releases for system back gesture`() {
        // Task #151: On the first tab (canScrollBackward == false), a rightward drag
        // must not be claimed/consumed by the edge guard so iOS fullscreen/edge pop
        // gesture is not cancelled by preventTouch(true).
        assertEquals(
            EdgeGuardGestureDecision.Release,
            edgeGuardGestureDecision(
                horizontalDragDp = 6f,
                verticalDragDp = 1f,
                canScrollBackward = false,
            ),
        )
        assertEquals(
            EdgeGuardGestureDecision.Release,
            edgeGuardGestureDecision(
                horizontalDragDp = 12f,
                verticalDragDp = 0f,
                canScrollBackward = false,
            ),
        )
    }
}
