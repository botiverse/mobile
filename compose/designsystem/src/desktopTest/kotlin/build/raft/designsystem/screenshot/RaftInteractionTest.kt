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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import build.raft.designsystem.components.RaftCheckboxRow
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
