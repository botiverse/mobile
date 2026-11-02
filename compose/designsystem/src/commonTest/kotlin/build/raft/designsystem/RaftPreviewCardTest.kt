package build.raft.designsystem

import build.raft.designsystem.components.RaftPreviewCardDefaults
import build.raft.designsystem.components.RaftPreviewCardState
import build.raft.designsystem.components.raftPreviewCardIsPinClick
import build.raft.designsystem.components.raftPreviewCardSwitchDelay
import build.raft.designsystem.components.raftPreviewCardWantsOpen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RaftPreviewCard's open / close decision (Base UI PreviewCard with the web mention's delays). */
class RaftPreviewCardTest {
    private val open = RaftPreviewCardDefaults.OpenDelayMillis
    private val close = RaftPreviewCardDefaults.CloseDelayMillis

    @Test
    fun theWebMentionDelays() {
        assertEquals(200L, open)
        assertEquals(120L, close)
    }

    @Test
    fun wantsOpenWhileThePointerIsOnTheTriggerOrTheCard() {
        assertFalse(raftPreviewCardWantsOpen(overTrigger = false, overCard = false, suppressed = false))
        assertTrue(raftPreviewCardWantsOpen(overTrigger = true, overCard = false, suppressed = false))
        assertTrue(raftPreviewCardWantsOpen(overTrigger = false, overCard = true, suppressed = false))
        assertTrue(raftPreviewCardWantsOpen(overTrigger = true, overCard = true, suppressed = false))
    }

    @Test
    fun aDismissedCardStaysShutWhateverThePointerDoes() {
        assertFalse(raftPreviewCardWantsOpen(overTrigger = true, overCard = false, suppressed = true))
        assertFalse(raftPreviewCardWantsOpen(overTrigger = true, overCard = true, suppressed = true))
    }

    @Test
    fun opensAfterTheOpenDelayAndClosesAfterTheCloseDelay() {
        assertEquals(open, raftPreviewCardSwitchDelay(open = false, wantsOpen = true, openDelayMillis = open, closeDelayMillis = close))
        assertEquals(close, raftPreviewCardSwitchDelay(open = true, wantsOpen = false, openDelayMillis = open, closeDelayMillis = close))
    }

    @Test
    fun nothingToWaitForWhenItAlreadyIsAsWanted() {
        assertNull(raftPreviewCardSwitchDelay(open = true, wantsOpen = true, openDelayMillis = open, closeDelayMillis = close))
        assertNull(raftPreviewCardSwitchDelay(open = false, wantsOpen = false, openDelayMillis = open, closeDelayMillis = close))
    }

    @Test
    fun crossingFromTheTriggerOntoTheCardKeepsItWanted() {
        val state = RaftPreviewCardState()
        assertFalse(state.wantsOpen)
        state.setTriggerHovered(true)
        assertTrue(state.wantsOpen)
        // In the 6dp gap: nothing wants it (the close delay would start)...
        state.setTriggerHovered(false)
        assertFalse(state.wantsOpen)
        // ...and on the card before it runs out: wanted again, so the pending close is dropped.
        state.setCardHovered(true)
        assertTrue(state.wantsOpen)
        state.setCardHovered(false)
        assertFalse(state.wantsOpen)
    }

    @Test
    fun dismissClosesAtOnceAndHoldsUntilThePointerLeavesTheTrigger() {
        val state = RaftPreviewCardState()
        state.setTriggerHovered(true)
        state.isOpen = true
        state.setCardHovered(true)
        state.dismiss()
        assertFalse(state.isOpen)
        assertFalse(state.isCardHovered)
        // Still on the trigger: stays shut.
        assertFalse(state.wantsOpen)
        // Leaving clears it; coming back wants it again.
        state.setTriggerHovered(false)
        state.setTriggerHovered(true)
        assertTrue(state.wantsOpen)
    }

    @Test
    fun dismissFromTheCardDoesNotHoldItShut() {
        // A button on the card dismissed it with the pointer off the trigger: the next rest on
        // the trigger opens it as usual.
        val state = RaftPreviewCardState()
        state.isOpen = true
        state.setCardHovered(true)
        state.dismiss()
        state.setTriggerHovered(true)
        assertTrue(state.wantsOpen)
    }

    @Test
    fun aPinnedCardOpensAtOnceAndStaysWhenThePointerLeaves() {
        // Web RuntimeUsageHoverCard: a click on the trigger pins the card.
        val state = RaftPreviewCardState()
        state.setTriggerHovered(true)
        state.pin()
        assertTrue(state.isOpen)
        assertTrue(state.isPinned)
        state.setTriggerHovered(false)
        state.setCardHovered(false)
        assertTrue(state.wantsOpen)
        assertTrue(raftPreviewCardWantsOpen(overTrigger = false, overCard = false, suppressed = true, pinned = true))
    }

    @Test
    fun dismissUnpins() {
        // A click outside, Escape or the card's close button.
        val state = RaftPreviewCardState()
        state.pin()
        state.dismiss()
        assertFalse(state.isOpen)
        assertFalse(state.isPinned)
        assertFalse(state.wantsOpen)
    }

    @Test
    fun aPinClickIsAReleaseWhereThePressWasBeforeALongPress() {
        assertTrue(raftPreviewCardIsPinClick(distance = 0f, heldMillis = 80L, touchSlop = 8f, longPressMillis = 400L))
        assertTrue(raftPreviewCardIsPinClick(distance = 8f, heldMillis = 399L, touchSlop = 8f, longPressMillis = 400L))
        // A drag, or a long press (the trigger's own, such as copying), is not a click.
        assertFalse(raftPreviewCardIsPinClick(distance = 9f, heldMillis = 80L, touchSlop = 8f, longPressMillis = 400L))
        assertFalse(raftPreviewCardIsPinClick(distance = 0f, heldMillis = 400L, touchSlop = 8f, longPressMillis = 400L))
    }
}
