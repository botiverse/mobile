package build.raft.app.desktop

import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftDesktopTitleBarTest {
    @Test fun `fullscreen hides the traffic lights, so no band and no inset`() {
        val none = RaftDesktopTitleBarMetrics(band = 0.dp, controlsStart = 0.dp)
        assertEquals(none, raftDesktopTitleBarMetrics(customTitleBar = true, fullscreen = true, reportedControlsWidth = 77.dp))
        assertEquals(none, raftDesktopTitleBarMetrics(customTitleBar = false, fullscreen = true, reportedControlsWidth = 0.dp))
    }

    private val forced = mutableListOf<Boolean>()
    private val hitTest = RaftDesktopTitleBarHitTest { forced += it }

    @Test fun `an unconsumed press on the bar drags the window`() {
        hitTest.onBarEvent(PointerEventType.Move, 1, consumed = false)
        hitTest.onBarEvent(PointerEventType.Press, 2, consumed = false)
        hitTest.onBarEvent(PointerEventType.Move, 3, consumed = false)
        hitTest.onBarEvent(PointerEventType.Release, 4, consumed = false)
        assertEquals(listOf(false, false, false, false), forced)
    }

    @Test fun `a press a control took stays the app's until its release, then the bar drags again`() {
        hitTest.onBarEvent(PointerEventType.Press, 1, consumed = true)
        hitTest.onBarEvent(PointerEventType.Move, 2, consumed = false)
        hitTest.onBarEvent(PointerEventType.Release, 3, consumed = false)
        hitTest.onBarEvent(PointerEventType.Press, 4, consumed = false)
        assertEquals(listOf(true, true, true, false), forced)
    }
}
