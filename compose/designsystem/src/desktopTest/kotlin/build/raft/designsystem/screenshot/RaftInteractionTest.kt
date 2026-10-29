package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCheckboxRow
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftSidebarChannelItem
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftUiThemeColors
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.skia.Image

/**
 * Real pointer input on an offscreen scene (density 1, so 1dp = 1px): what the forced
 * states of the screenshot fixtures stand in for.
 */
@OptIn(ExperimentalComposeUiApi::class)
class RaftInteractionTest {
    private val canvas = RaftUiThemeColors.RaftLight.layerCanvas
    private val ring = RaftUiThemeColors.RaftLight.lineStrong

    /** Input > Focus: a tap focuses the field and draws the ring (`focus:`, not `focus-visible`). */
    @Test
    fun aTapOnAnInputDrawsTheFocusRing() {
        ImageComposeScene(width = 240, height = 84, density = Density(1f)) {
            RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                Box(Modifier.background(RaftTheme.colors.layerCanvas).padding(20.dp)) {
                    RaftTextField("", {}, Modifier.width(200.dp), placeholder = "Wug")
                }
            }
        }.use { scene ->
            // The field spans x 20..220, y 20..64; the ring's ink is 2..4px outside it.
            assertTrue(pixelIs(scene.render(), 17, 42, canvas), "no ring before the tap")
            tap(scene, Offset(120f, 42f))
            val after = scene.render(500_000_000L)
            assertTrue(pixelIs(after, 17, 42, ring), "ink ring outside the border after the tap")
            assertTrue(pixelIs(after, 19, 42, canvas), "2dp gap in the surface colour between border and ring")
        }
    }

    /** The whole choice row toggles, not just the 20dp box. */
    @Test
    fun aTapOnACheckboxRowLabelTogglesIt() {
        var checked by mutableStateOf(false)
        ImageComposeScene(width = 300, height = 80, density = Density(1f)) {
            RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                RaftCheckboxRow("Make private", checked = checked, onCheckedChange = { checked = it }, modifier = Modifier.width(300.dp))
            }
        }.use { scene ->
            scene.render()
            assertFalse(checked)
            tap(scene, Offset(200f, 22f))
            scene.render(100_000_000L)
            assertTrue(checked)
            tap(scene, Offset(200f, 22f))
            scene.render(200_000_000L)
            assertEquals(false, checked)
        }
    }

    /**
     * A mouse over an icon button lays the press fill (`hover:bg-fill-pressed`) inside its
     * radius; leaving takes it away. No ripple, so nothing else changes.
     */
    @Test
    fun aMouseOverAnIconButtonDrawsThePressFill() {
        val pressedFill = RaftUiThemeColors.RaftLight.fillPressed.compositeOver(canvas)
        ImageComposeScene(width = 200, height = 120, density = Density(1f)) {
            RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                Box(Modifier.background(RaftTheme.colors.layerCanvas).padding(20.dp)) {
                    RaftIconButton(onClick = {}, tooltip = "Add Reaction", variant = RaftButtonVariant.Ghost) {}
                }
            }
        }.use { scene ->
            // The button spans 20..52; (36, 22) is inside its top edge, clear of the corners.
            assertTrue(pixelIs(scene.render(), 36, 22, canvas), "no fill at rest")
            scene.sendPointerEvent(PointerEventType.Move, Offset(36f, 36f), type = PointerType.Mouse)
            val hovered = scene.render(100_000_000L)
            assertTrue(pixelIs(hovered, 36, 22, pressedFill), "press fill while hovered")
            assertTrue(pixelIs(hovered, 21, 21, canvas), "the fill keeps the control radius")
            // Off the button and clear of its tooltip, which hover opens under it (room
            // for it is left below, so it doesn't cover the button).
            scene.sendPointerEvent(PointerEventType.Move, Offset(2f, 2f), type = PointerType.Mouse)
            assertTrue(pixelIs(scene.render(200_000_000L), 36, 22, canvas), "no fill after the pointer leaves")
        }
    }

    /**
     * A mouse over a menu item lays `brand-soft` (`hover:bg-brand-soft`), faded in over 100ms;
     * leaving fades it out.
     */
    @Test
    fun aMouseOverAMenuItemLaysBrandSoft() {
        val brandSoft = RaftUiThemeColors.RaftLight.brandSoft
        ImageComposeScene(width = 260, height = 100, density = Density(1f)) {
            RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                Box(Modifier.background(RaftTheme.colors.layerCanvas).padding(20.dp)) {
                    RaftMenuItem("Copy Link", {}, Modifier.width(200.dp))
                }
            }
        }.use { scene ->
            // The row spans x 20..220, y 20..56; (24, 38) is inside it, before its label.
            assertTrue(pixelIs(scene.render(), 24, 38, canvas), "no fill at rest")
            scene.sendPointerEvent(PointerEventType.Move, Offset(120f, 38f), type = PointerType.Mouse)
            assertTrue(pixelIs(settle(scene, fromMillis = 100), 24, 38, brandSoft), "brand-soft once the fade is done")
            scene.sendPointerEvent(PointerEventType.Move, Offset(250f, 95f), type = PointerType.Mouse)
            assertTrue(pixelIs(settle(scene, fromMillis = 1_000), 24, 38, canvas), "no fill after the pointer leaves")
        }
    }

    /** A finger never hovers: a tap shows the press, and nothing stays once it lifts. */
    @Test
    fun aTapLeavesNoHoverBehind() {
        ImageComposeScene(width = 260, height = 100, density = Density(1f)) {
            RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                Box(Modifier.background(RaftTheme.colors.layerCanvas).padding(20.dp)) {
                    RaftMenuItem("Copy Link", {}, Modifier.width(200.dp))
                }
            }
        }.use { scene ->
            scene.render()
            val at = Offset(120f, 38f)
            scene.sendPointerEvent(PointerEventType.Press, at, type = PointerType.Touch)
            scene.sendPointerEvent(PointerEventType.Release, at, type = PointerType.Touch)
            // Past the 120ms minimum press and its fade.
            assertTrue(pixelIs(settle(scene, fromMillis = 100), 24, 38, canvas), "no fill after a tap")
        }
    }

    /**
     * A sidebar row is a list cell: a mouse lays `fill-cell-hover` on its shape, which in dark
     * steps up past `fill-subtle` (7.5% paper).
     */
    @Test
    fun aMouseOverASidebarRowLaysTheCellFillInDark() {
        val dark = RaftUiThemeColors.RaftDark
        val cellHover = dark.fillCellHover.compositeOver(dark.layerCanvas)
        ImageComposeScene(width = 300, height = 100, density = Density(1f)) {
            RaftTheme(appearance = RaftAppearance.Dark, systemDark = true) {
                Box(Modifier.background(RaftTheme.colors.layerCanvas).padding(20.dp)) {
                    RaftSidebarChannelItem("general", Modifier.width(240.dp), onClick = {})
                }
            }
        }.use { scene ->
            // The row spans x 20..260, y ~20.5..64.5; (24, 42) is inside it, before its icon.
            assertTrue(pixelIs(scene.render(), 24, 42, dark.layerCanvas), "no fill at rest")
            scene.sendPointerEvent(PointerEventType.Move, Offset(140f, 42f), type = PointerType.Mouse)
            assertTrue(pixelIs(settle(scene, fromMillis = 100), 24, 42, cellHover), "fill-cell-hover once the 150ms fade is done")
        }
    }

    /** Renders frames 50ms apart from [fromMillis] until every 75-150ms fade has finished; the last. */
    private fun settle(scene: ImageComposeScene, fromMillis: Long): Image {
        var image = scene.render(fromMillis * 1_000_000L)
        for (step in 1..10) image = scene.render((fromMillis + step * 50L) * 1_000_000L)
        return image
    }

    private fun tap(scene: ImageComposeScene, at: Offset) {
        scene.sendPointerEvent(PointerEventType.Press, at)
        scene.sendPointerEvent(PointerEventType.Release, at)
    }

    private fun pixelIs(image: Image, x: Int, y: Int, color: androidx.compose.ui.graphics.Color): Boolean {
        val bitmap = org.jetbrains.skia.Bitmap.makeFromImage(image)
        val argb = bitmap.getColor(x, y)
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return abs(r - (color.red * 255).toInt()) <= 8 &&
            abs(g - (color.green * 255).toInt()) <= 8 &&
            abs(b - (color.blue * 255).toInt()) <= 8
    }
}
