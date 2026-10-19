package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * task #400 / Hands 80c0dce2: on a 11401002 build the thread list sat at `viewportStartOffset = -134`
 * for 33 minutes (184 log lines across 10 rotated files), which the user sees as a blank strip above
 * the content when dragging up.
 *
 * Every one of those log lines carries `fitHeightPx = -1` and
 * `measurementResult = measurement_unavailable` alongside `tailFits = false`, i.e. the anchor ran
 * although the focused tail's height could not be measured.
 *
 * This file exists to answer one question from the source rather than from opinion: **when the fit
 * height is unavailable, what does this code intend to do?** The assertions below state the expected
 * contract; if they fail, the contract is different from what the module implements and the
 * discrepancy is the finding.
 */
class ThreadComposerLayoutMeasurementUnavailableTest {

    private fun state(
        pendingLayoutChange: Boolean = false,
        initialBottomAnchorReady: Boolean = true,
        latestItemIndex: Int = 30,
        allowBottomAutoAnchor: Boolean = true,
        viewportEpochCoherent: Boolean = true,
        focusedAnchorOwnsViewport: Boolean = false,
        focusedActiveTailAnchorCanCorrectLayout: Boolean = false,
        focusedTailAnchorCanCorrectLayout: Boolean = false,
    ) = ThreadComposerLayoutBottomAnchorState(
        previousKeyboardInsetDp = 0f,
        nextKeyboardInsetDp = 0f,
        imeProgrammaticViewportPolicy = ThreadImeProgrammaticViewportPolicy.SuppressWhileImeOpen,
        previousViewportHeightDp = 700f,
        nextViewportHeightDp = 593.37f,
        pendingLayoutChange = pendingLayoutChange,
        initialBottomAnchorReady = initialBottomAnchorReady,
        latestItemIndex = latestItemIndex,
        allowBottomAutoAnchor = allowBottomAutoAnchor,
        viewportEpochCoherent = viewportEpochCoherent,
        focusedAnchorOwnsViewport = focusedAnchorOwnsViewport,
        focusedActiveTailAnchorCanCorrectLayout = focusedActiveTailAnchorCanCorrectLayout,
        focusedTailAnchorCanCorrectLayout = focusedTailAnchorCanCorrectLayout,
    )

    @Test
    fun anUnmeasurableTailIsNeverTreatedAsFitting() {
        // The measurement helper is the only place that decides "does the target-to-tail span fit".
        // With fitHeightPx <= 0 it must answer false: geometry it could not measure is not geometry it
        // may act on. This is the invariant the -134 state needs.
        // Null offsets stand for "not measured" -- the state the -1 fit height comes from.
        val unavailable = resolveThreadFocusedTailFit(
            targetItemIndex = 30,
            latestMessageItemIndex = 31,
            bottomPaddingItemIndex = 31,
            targetOffsetPx = null,
            targetSizePx = null,
            latestMessageOffsetPx = null,
            latestMessageSizePx = null,
            viewportHeightPx = 1993,
            trailingPaddingPx = 54,
        )
        assertTrue(unavailable.fitHeightPx <= 0, "this fixture stands for 'measurement unavailable'")
        assertFalse(
            unavailable.fitsViewport,
            "geometry that could not be measured must never be reported as fitting"
        )
    }

    @Test
    fun anIncoherentViewportEpochDefersInsteadOfAnchoring() {
        // When the measured viewport and the layout viewport disagree, the safe behaviour is to stay
        // pending. Anchoring on a stale epoch is what turns a relayout into a permanent offset.
        val decision = resolveThreadComposerLayoutBottomAnchor(
            state(pendingLayoutChange = true, viewportEpochCoherent = false)
        )
        assertFalse(decision.shouldAnchor, "must not anchor while the viewport epoch is incoherent")
        assertTrue(decision.pendingLayoutChange, "the change must stay pending until the epoch settles")
    }

    @Test
    fun withNoCoherentMeasurementAndNoOwnerTheChangeStaysPending() {
        // The 20:02 shape: nothing owns the viewport, the fit was not measurable, and the layout
        // changed. Expected behaviour is "wait for a usable measurement", not "anchor anyway".
        val decision = resolveThreadComposerLayoutBottomAnchor(
            state(
                pendingLayoutChange = false,
                initialBottomAnchorReady = false,
                viewportEpochCoherent = true,
                focusedAnchorOwnsViewport = false,
                focusedActiveTailAnchorCanCorrectLayout = false,
                focusedTailAnchorCanCorrectLayout = false,
            )
        )
        assertFalse(
            decision.shouldAnchor,
            "without a measurable tail and without ownership there is nothing safe to anchor to"
        )
    }
}
