package build.raft.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThreadComposerFocusDismissTest {
    @Test
    fun stationaryLongPressKeepsComposerStableUntilThePointerIsReleased() {
        val tracker = RaftInputFocusDismissGestureTracker()
        val token = RaftInputFocusDismissToken(11L, 1L)

        assertNull(tracker.onTouch(TouchType.Down, dismissToken = token))
        assertNull(tracker.onTouch(TouchType.Move))
        // A descendant may recognize its long press while the pointer stays down. No focus clear
        // or IME relayout is allowed before that recognition completes.
        assertEquals(token, tracker.onTouch(TouchType.Up))
    }

    @Test
    fun staleDeferredClearCannotDismissAReplacementFocusOwner() {
        val registry = RaftInputFocusOwnerRegistry()
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
        val registry = RaftInputFocusOwnerRegistry()
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
}
