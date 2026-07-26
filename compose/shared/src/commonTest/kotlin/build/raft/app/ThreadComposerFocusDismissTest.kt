package build.raft.app

import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThreadComposerFocusDismissTest {
    @Test
    fun stationaryLongPressKeepsComposerStableUntilThePointerIsReleased() {
        val tracker = SlockInputFocusDismissGestureTracker()
        val token = SlockInputFocusDismissToken(11L, 1L)

        assertNull(tracker.onTouch(TouchType.Down, dismissToken = token))
        assertNull(tracker.onTouch(TouchType.Move))
        // A descendant may recognize its long press while the pointer stays down. No focus clear
        // or IME relayout is allowed before that recognition completes.
        assertEquals(token, tracker.onTouch(TouchType.Up))
    }

    @Test
    fun dragDefersLayoutChangingDismissUntilTerminalPointerUp() {
        val tracker = SlockInputFocusDismissGestureTracker()
        val token = SlockInputFocusDismissToken(12L, 1L)

        assertNull(tracker.onTouch(TouchType.Down, dismissToken = token))
        assertNull(tracker.onTouch(TouchType.Move))
        assertNull(tracker.onTouch(TouchType.Move))
        assertEquals(token, tracker.onTouch(TouchType.Up))
    }

    @Test
    fun insideInputGestureNeverArmsOutsideDismiss() {
        val tracker = SlockInputFocusDismissGestureTracker()

        assertNull(tracker.onTouch(TouchType.Down))
        assertNull(tracker.onTouch(TouchType.Move))
        assertNull(tracker.onTouch(TouchType.Up))
    }

    @Test
    fun additionalPointerCancelsThePendingOutsideDismiss() {
        val tracker = SlockInputFocusDismissGestureTracker()
        val token = SlockInputFocusDismissToken(13L, 1L)

        assertNull(tracker.onTouch(TouchType.Down, dismissToken = token))
        tracker.cancel()

        assertNull(tracker.onTouch(TouchType.Up))
    }

    @Test
    fun nativeCancelIsNotFoldedIntoPhysicalPointerUp() {
        assertEquals(
            true,
            isNativeInputFocusDismissCancellation(
                nativeEvent = "cancel",
                hasTerminalChange = true
            )
        )
        assertEquals(
            false,
            isNativeInputFocusDismissCancellation(
                nativeEvent = null,
                hasTerminalChange = true
            )
        )
        assertEquals(
            false,
            isNativeInputFocusDismissCancellation(
                nativeEvent = "cancel",
                hasTerminalChange = false
            )
        )
    }

    @Test
    fun completedGestureRearmsTheNextOutsideTap() {
        val tracker = SlockInputFocusDismissGestureTracker()
        val decisions = mutableListOf<SlockInputFocusDismissToken?>()
        val tokens = listOf(
            SlockInputFocusDismissToken(20L, 1L),
            SlockInputFocusDismissToken(21L, 1L)
        )

        tokens.forEach { token ->
            decisions += tracker.onTouch(TouchType.Down, dismissToken = token)
            decisions += tracker.onTouch(TouchType.Up)
        }

        assertEquals(listOf(null, tokens[0], null, tokens[1]), decisions)
    }

    @Test
    fun duplicateObserversCanClaimOneClearForTheSameFocusGeneration() {
        val registry = SlockInputFocusOwnerRegistry()
        val owner = Any()
        registry.setFocused(owner, true) { Rect(0f, 0f, 10f, 10f) }
        val token = requireNotNull(registry.outsideFocusToken(Offset(20f, 20f)))

        assertEquals(true, registry.claimFocusClear(token))
        assertEquals(false, registry.claimFocusClear(token))
    }

    @Test
    fun unreconciledClearClaimIsReleasedForTheNextGesture() {
        val registry = SlockInputFocusOwnerRegistry()
        val owner = Any()
        registry.setFocused(owner, true) { Rect(0f, 0f, 10f, 10f) }
        val token = requireNotNull(registry.outsideFocusToken(Offset(20f, 20f)))

        assertEquals(true, registry.claimFocusClear(token))
        registry.releaseUnreconciledFocusClearClaim(token)

        assertEquals(true, registry.claimFocusClear(token))
    }

    @Test
    fun rootObservedSecondPointerInvalidatesScrollableObserverToken() {
        val registry = SlockInputFocusOwnerRegistry()
        val owner = Any()
        registry.setFocused(owner, true) { Rect(0f, 0f, 10f, 10f) }
        val firstPointerToken = requireNotNull(registry.outsideFocusToken(Offset(20f, 20f)))

        registry.blockMultiPointerFocusClear()
        assertNull(registry.outsideFocusToken(Offset(20f, 20f)))
        assertEquals(false, registry.claimFocusClear(firstPointerToken))

        registry.unblockMultiPointerFocusClear()
        val nextGestureToken = requireNotNull(registry.outsideFocusToken(Offset(20f, 20f)))
        assertEquals(true, registry.claimFocusClear(nextGestureToken))
    }

    @Test
    fun rootObservedNativeCancelInvalidatesScrollableObserverToken() {
        val registry = SlockInputFocusOwnerRegistry()
        val owner = Any()
        registry.setFocused(owner, true) { Rect(0f, 0f, 10f, 10f) }
        val canceledToken = requireNotNull(registry.outsideFocusToken(Offset(20f, 20f)))

        registry.invalidatePointerFocusClearCandidates()

        assertEquals(false, registry.claimFocusClear(canceledToken))
        val nextGestureToken = requireNotNull(registry.outsideFocusToken(Offset(20f, 20f)))
        assertEquals(true, registry.claimFocusClear(nextGestureToken))
    }

    @Test
    fun newerPointerDownInvalidatesAnOlderGesturesDelayedClear() {
        val registry = SlockInputFocusOwnerRegistry()
        val owner = Any()
        registry.setFocused(owner, true) { Rect(0f, 0f, 10f, 10f) }
        registry.beginPointerFocusClearSequence()
        val firstGestureToken = requireNotNull(registry.outsideFocusToken(Offset(20f, 20f)))

        registry.beginPointerFocusClearSequence()

        assertEquals(false, registry.claimFocusClear(firstGestureToken))
        val nextGestureToken = requireNotNull(registry.outsideFocusToken(Offset(20f, 20f)))
        assertEquals(true, registry.claimFocusClear(nextGestureToken))
    }

    @Test
    fun staleDeferredClearCannotDismissAReplacementFocusOwner() {
        val registry = SlockInputFocusOwnerRegistry()
        val firstOwner = Any()
        val replacementOwner = Any()
        registry.setFocused(firstOwner, true) { Rect(0f, 0f, 10f, 10f) }
        val staleToken = requireNotNull(registry.outsideFocusToken(Offset(20f, 20f)))

        registry.setFocused(firstOwner, false)
        registry.setFocused(replacementOwner, true) { Rect(0f, 0f, 10f, 10f) }

        assertEquals(false, registry.claimFocusClear(staleToken))
    }

    @Test
    fun composerInteractionRegionPreservesFocusWhileOutsideBlankStillDismisses() {
        val registry = SlockInputFocusOwnerRegistry()
        val fieldOwner = Any()
        val composerInteractionOwner = Any()
        registry.setFocused(fieldOwner, true) { Rect(0f, 80f, 100f, 120f) }
        registry.retainFocusForInteraction(composerInteractionOwner) {
            Rect(0f, 20f, 100f, 120f)
        }

        assertNull(registry.outsideFocusToken(Offset(20f, 30f)))
        assertNull(registry.outsideFocusToken(Offset(20f, 100f)))
        val timelineBlankToken = requireNotNull(registry.outsideFocusToken(Offset(20f, 10f)))
        assertEquals(true, registry.claimFocusClear(timelineBlankToken))
    }

    @Test
    fun disposedInteractionRegionNoLongerSuppressesOutsideDismiss() {
        val registry = SlockInputFocusOwnerRegistry()
        val fieldOwner = Any()
        val composerInteractionOwner = Any()
        registry.setFocused(fieldOwner, true) { Rect(0f, 80f, 100f, 120f) }
        registry.retainFocusForInteraction(composerInteractionOwner) {
            Rect(0f, 20f, 100f, 120f)
        }

        registry.removeRetainedInteraction(composerInteractionOwner)

        val formerSuggestionPanelToken = requireNotNull(registry.outsideFocusToken(Offset(20f, 30f)))
        assertEquals(true, registry.claimFocusClear(formerSuggestionPanelToken))
    }
}
