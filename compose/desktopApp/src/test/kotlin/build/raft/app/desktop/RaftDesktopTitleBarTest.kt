package build.raft.app.desktop

import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class RaftDesktopTitleBarTest {
    @Test fun `the custom title bar is as tall as the top bar and takes the runtime's controls width`() {
        assertEquals(
            RaftDesktopTitleBarMetrics(band = 44.dp, controlsStart = 77.dp),
            raftDesktopTitleBarMetrics(customTitleBar = true, fullscreen = false, reportedControlsWidth = 77.dp),
        )
    }

    @Test fun `an unreported controls width falls back to the traffic lights' default corner`() {
        assertEquals(
            RaftDesktopTitleBarMetrics(band = 44.dp, controlsStart = 70.dp),
            raftDesktopTitleBarMetrics(customTitleBar = true, fullscreen = false, reportedControlsWidth = 0.dp),
        )
    }

    @Test fun `the apple awt fallback keeps the standard 28pt band`() {
        assertEquals(
            RaftDesktopTitleBarMetrics(band = 28.dp, controlsStart = 70.dp),
            raftDesktopTitleBarMetrics(customTitleBar = false, fullscreen = false, reportedControlsWidth = 90.dp),
        )
    }

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

    @Test fun `exit and wheel events are not reported`() {
        hitTest.onBarEvent(PointerEventType.Exit, 1, consumed = false)
        hitTest.onWindowEvent(PointerEventType.Scroll, 2, consumed = false, inBand = true)
        assertEquals(emptyList(), forced)
    }

    @Test fun `the window leaves an event the bar decided, and drags only from the band`() {
        hitTest.onBarEvent(PointerEventType.Press, 1, consumed = true)
        hitTest.onWindowEvent(PointerEventType.Press, 1, consumed = true, inBand = true)
        hitTest.onBarEvent(PointerEventType.Release, 2, consumed = true)
        // A page without a top bar: its empty band drags, the content under it does not.
        hitTest.onWindowEvent(PointerEventType.Press, 3, consumed = false, inBand = true)
        hitTest.onWindowEvent(PointerEventType.Release, 4, consumed = false, inBand = true)
        hitTest.onWindowEvent(PointerEventType.Press, 5, consumed = false, inBand = false)
        assertEquals(listOf(true, true, false, false, true), forced)
    }

    private val sent = mutableListOf<Pair<String, JsonObject>>()
    private val publisher = RaftDesktopWindowInsetsPublisher { event, data -> sent += event to data }

    @Test fun `publishes the band as the status bar height in pixels`() {
        assertTrue(publisher.publish(48.dp, density = 2f))
        val (event, data) = sent.single()
        assertEquals("slockWindowInsetsChanged", event)
        assertEquals(JsonPrimitive(96f), data["statusBarHeightPx"])
        assertEquals(JsonPrimitive(0f), data["navigationBarHeightPx"])
        assertEquals(JsonPrimitive(false), data["statusBarHidden"])
    }

    @Test fun `no band and nothing sent before sends nothing, but a band going away sends zero`() {
        assertFalse(publisher.publish(0.dp, density = 2f))
        assertTrue(publisher.publish(48.dp, density = 2f))
        assertFalse(publisher.publish(48.dp, density = 2f))
        assertTrue(publisher.publish(48.dp, density = 2f, resend = true))
        assertTrue(publisher.publish(0.dp, density = 2f))
        assertFalse(publisher.publish(48.dp, density = 0f))
        assertEquals(listOf(96f, 96f, 0f), sent.map { (it.second["statusBarHeightPx"] as JsonPrimitive).content.toFloat() })
    }
}
