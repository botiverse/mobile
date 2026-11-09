package build.raft.app.assistant

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Web AssistantOtter's popover (`side="top" align="end" sideOffset={8} collisionPadding={8}`,
 * `w-[380px] h-[540px] max-h-(--available-height) max-w-(--available-width)`), in pixels at 1x.
 */
class ThreadAssistantPanelPopoverBoundsTest {
    private fun bounds(rest: Rect?, window: IntSize = IntSize(1280, 800)) =
        threadAssistantPanelPopoverBounds(rest, window, width = 380f, height = 540f, sideOffset = 8f, padding = 8f)

    @Test
    fun aboveTheOtterItsEndOnTheOttersEnd() {
        // The otter resting on a composer near the window's bottom end.
        val rest = Rect(1150f, 700f, 1190f, 730f)
        assertEquals(Rect(810f, 152f, 1190f, 692f), bounds(rest))
    }

    @Test
    fun aShortRoomAboveMakesItShorter() {
        val rest = Rect(1150f, 300f, 1190f, 330f)
        // 300 - 8 (offset) - 8 (padding) = 284 tall, from the window's padding down.
        assertEquals(Rect(810f, 8f, 1190f, 292f), bounds(rest))
    }

    @Test
    fun keptInsideTheWindowAtItsStart() {
        val rest = Rect(100f, 700f, 140f, 730f)
        assertEquals(8f, bounds(rest).left)
        assertEquals(388f, bounds(rest).right)
    }

    @Test
    fun withoutTheOtterAtTheWindowsBottomEnd() {
        assertEquals(Rect(892f, 252f, 1272f, 792f), bounds(rest = null))
    }
}
