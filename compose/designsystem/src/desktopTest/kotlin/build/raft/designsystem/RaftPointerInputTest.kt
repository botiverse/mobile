package build.raft.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftPointerInputState
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.theme.raftTrackPointerInput
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two axes of the scale (artin 2026-11-02): the layout follows the window width alone; the
 * component sizes take the desktop values on a wide window with a mouse or trackpad, as the Web's
 * desktop (raft-ui tokens.resolved.json). The input is read from real pointer events on an
 * offscreen scene.
 */
@OptIn(ExperimentalComposeUiApi::class)
class RaftPointerInputTest {
    @AfterTest
    fun reset() = RaftPointerInputState.reset()

    @Test
    fun typeScaleFollowsTheWindowWidthOnly() {
        assertEquals(RaftScale.Compact, RaftScale.of(390.dp))
        assertEquals(RaftScale.Compact, RaftScale.of(743.dp))
        assertEquals(RaftScale.Wide, RaftScale.of(744.dp))
        assertEquals(RaftScale.Wide, RaftScale.of(1024.dp))
    }

    /**
     * On a wide window a mouse switches the controls to the 34dp metrics as soon as it moves; a
     * finger switches them back only when it lifts, so a control never resizes under the finger
     * pressing it.
     */
    @Test
    fun controlsFollowTheLastInputWithoutResizingUnderAPress() {
        var height: Dp = 0.dp
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            RaftTheme(appearance = RaftAppearance.Light, systemDark = false, scale = RaftScale.Wide) {
                height = RaftTheme.controls.height
                Box(Modifier.fillMaxSize().raftTrackPointerInput())
            }
        }.use { scene ->
            val at = Offset(100f, 100f)
            scene.render()
            assertEquals(44.dp, height, "touch at rest")
            scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse)
            scene.render(16_000_000L)
            assertEquals(34.dp, height, "a mouse moved")
            assertEquals(RaftPointerInput.Fine, RaftPointerInputState.current)
            scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Touch)
            scene.render(32_000_000L)
            assertEquals(34.dp, height, "a finger is down: nothing resizes under it")
            scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Touch)
            scene.render(48_000_000L)
            assertEquals(44.dp, height, "the finger lifted")
            scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Stylus)
            scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Stylus)
            scene.render(64_000_000L)
            assertEquals(44.dp, height, "a pen changes nothing")
        }
    }

    /**
     * A mouse in a narrow window keeps the touch controls, as the Web below 768px; only the tokens
     * that follow the input alone change (`list-row.min-height`).
     */
    @Test
    fun aMouseInANarrowWindowKeepsTheTouchControls() {
        var height: Dp = 0.dp
        var listRowMinHeight: Dp = 0.dp
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            RaftTheme(appearance = RaftAppearance.Light, systemDark = false, scale = RaftScale.Compact, pointerInput = RaftPointerInput.Fine) {
                height = RaftTheme.controls.height
                listRowMinHeight = RaftTheme.sizes.listRowMinHeight
            }
        }.use { scene ->
            scene.render()
            assertEquals(44.dp, height)
            assertEquals(0.dp, listRowMinHeight)
        }
    }

    @Test
    fun withoutATouchScreenTheInputStaysFine() {
        RaftPointerInputState.assumeNoTouchScreen()
        assertEquals(RaftPointerInput.Fine, RaftPointerInputState.current, "fine before any event")
        RaftPointerInputState.report(PointerType.Touch, PointerEventType.Release)
        assertEquals(RaftPointerInput.Fine, RaftPointerInputState.current, "a click that arrives as a touch")
        RaftPointerInputState.reset()
        RaftPointerInputState.report(PointerType.Mouse, PointerEventType.Move)
        RaftPointerInputState.report(PointerType.Touch, PointerEventType.Release)
        assertEquals(RaftPointerInput.Touch, RaftPointerInputState.current, "a touch screen still flips back")
    }
}
