package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BasicWidgetTouchSequenceTest {
    @Test
    fun changedPositionOnReleaseEmitsUpAndRearmsTheNextPointer() {
        val tracker = RaftTouchSequenceTracker<Long>()

        assertEquals(TouchType.Down, tracker.onChange(pointerId = 1L, pressed = true, positionChanged = false))
        assertEquals(TouchType.Move, tracker.onChange(pointerId = 1L, pressed = true, positionChanged = true))
        assertEquals(TouchType.Up, tracker.onChange(pointerId = 1L, pressed = false, positionChanged = true))
        assertEquals(TouchType.Down, tracker.onChange(pointerId = 2L, pressed = true, positionChanged = false))
    }

    @Test
    fun untrackedPointerCannotStealOrEndTheActiveGesture() {
        val tracker = RaftTouchSequenceTracker<Long>()

        assertEquals(TouchType.Down, tracker.onChange(pointerId = 1L, pressed = true, positionChanged = false))
        assertNull(tracker.onChange(pointerId = 2L, pressed = true, positionChanged = true))
        assertNull(tracker.onChange(pointerId = 2L, pressed = false, positionChanged = true))
        assertEquals(TouchType.Move, tracker.onChange(pointerId = 1L, pressed = true, positionChanged = true))
        assertEquals(TouchType.Up, tracker.onChange(pointerId = 1L, pressed = false, positionChanged = false))
    }
}
