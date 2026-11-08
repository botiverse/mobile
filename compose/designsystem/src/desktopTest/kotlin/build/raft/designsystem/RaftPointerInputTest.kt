package build.raft.designsystem

import build.raft.designsystem.components.raftAppRailMetricsFor
import build.raft.designsystem.components.RaftAppRailMetrics
import build.raft.designsystem.components.RaftTopBarDefaults
import build.raft.designsystem.components.RaftBackButtonDefaults
import build.raft.designsystem.components.RaftDialogSheetDefaults
import build.raft.designsystem.components.RaftOptionHeight
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
 * The two axes of the scale (artin 2026-11-02): the type follows the window width alone, the
 * control metrics the input in use, read from real pointer events on an offscreen scene.
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

    @Test
    fun theOutermostThemeTakesTheWideTypeFromTheWindowWidthWithAFinger() {
        listOf(600 to RaftScale.Compact, 1024 to RaftScale.Wide).forEach { (width, expected) ->
            var scale: RaftScale? = null
            var input: RaftPointerInput? = null
            ImageComposeScene(width = width, height = 100, density = Density(1f)) {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    scale = RaftTheme.scale
                    input = RaftTheme.pointerInput
                }
            }.use { it.render() }
            assertEquals(expected, scale, "scale at $width wide")
            assertEquals(RaftPointerInput.Touch, input, "a headless JVM starts on touch")
        }
    }

    /**
     * A mouse switches the controls to the 34dp metrics as soon as it moves; a finger switches
     * them back only when it lifts, so a control never resizes under the finger pressing it.
     */
    @Test
    fun controlsFollowTheLastInputWithoutResizingUnderAPress() {
        var height: Dp = 0.dp
        ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
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
     * Sizing v12 (raft-ui 0.65 desktop density): what the components read follows the input. A
     * finger keeps the touch sizes; a fine pointer takes the 34dp rows, 44dp heads, the 28dp bar
     * button, the 44dp top bar and the compact rail at any window height.
     */
    @Test
    fun densityFollowsTheInput() {
        data class Read(val option: Dp, val head: Dp, val back: Dp, val topBar: Dp, val rail: RaftAppRailMetrics)
        fun read(input: RaftPointerInput): Read {
            var read: Read? = null
            ImageComposeScene(width = 1200, height = 900, density = Density(1f)) {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false, pointerInput = input) {
                    read =
                        Read(
                            option = RaftOptionHeight,
                            head = RaftDialogSheetDefaults.HeadHeight,
                            back = RaftBackButtonDefaults.Size,
                            topBar = RaftTopBarDefaults.height,
                            rail = raftAppRailMetricsFor(900.dp),
                        )
                }
            }.use { it.render() }
            return read!!
        }
        assertEquals(Read(36.dp, 56.dp, 44.dp, 48.dp, RaftAppRailMetrics.Regular), read(RaftPointerInput.Touch))
        assertEquals(Read(34.dp, 44.dp, 28.dp, 44.dp, RaftAppRailMetrics.Compact.copy(headerHeight = 44.dp)), read(RaftPointerInput.Fine))
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

    @Test
    fun aPinnedInputWinsOverTheLastOneUsed() {
        RaftPointerInputState.report(PointerType.Mouse, PointerEventType.Move)
        var height: Dp = 0.dp
        ImageComposeScene(width = 100, height = 100, density = Density(1f)) {
            RaftTheme(appearance = RaftAppearance.Light, systemDark = false, pointerInput = RaftPointerInput.Touch) {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) { height = RaftTheme.controls.height }
            }
        }.use { it.render() }
        assertEquals(44.dp, height, "a nested theme inherits the pinned touch metrics")
    }
}
