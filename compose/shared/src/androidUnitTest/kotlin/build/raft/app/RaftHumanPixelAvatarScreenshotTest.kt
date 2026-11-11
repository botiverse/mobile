package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.model.MemberKind
import build.raft.app.model.avatarWireValueForAvatarSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * People's built pixel avatars (`pixel-human:v1:...`, web PersonAvatar → HumanPixelAvatar) as
 * [RaftAppAvatar] draws them, light and dark: one row per value, at 19dp (sidebar, chips), 35dp
 * (messages, lists) and 51dp (profile cards). The rows: the default look, a look using every
 * kind of layer (headphones over glasses, curly ginger hair, grin), a Server-picked `/auto` one,
 * a bun under a beanie, a value of a later version (draws the default look, as on the web) and
 * one not of the scheme (the person placeholder). Each value goes through the person avatar slot
 * first, as every person's face in the app does.
 * Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w220dp-h440dp-xxhdpi")
class RaftHumanPixelAvatarScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun avatarsLight() = capture("human-pixel-avatar.sizes", RaftAppAppearance.Light)

    @Test
    fun avatarsDark() = capture("human-pixel-avatar.sizes.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            RaftDesignSystemTheme {
                Column(
                    Modifier.fillMaxSize().background(RaftColor.SurfaceWhite).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    for (value in ScreenshotValues) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            for (size in listOf(19.dp, 35.dp, 51.dp)) {
                                RaftAppAvatar(
                                    kind = RaftAvatarKind.Human,
                                    avatarWireValue = avatarWireValueForAvatarSlot(MemberKind.Human, "person", value),
                                    label = "",
                                    avatarSize = size,
                                )
                            }
                        }
                    }
                }
            }
        }

    /**
     * Pixel-exact: at a whole-number scale every cell of the 112x112 picture is a solid block of
     * its colour, and at any other scale every drawn pixel is one of the picture's colours —
     * nearest-neighbour, never smoothed.
     */
    @Test
    fun theFaceScalesNearestNeighbour() {
        val value = "pixel-human:v1:pink.4.curly.ginger.grin.headphones.glasses"
        val picture = requireNotNull(raftHumanPixelAvatarPicture(value))
        val raster = requireNotNull(raftHumanPixelAvatarRaster(value))
        RaftRobolectricScreenshot.render(
            RaftAppAppearance.Light,
            activities,
            content = { activity ->
                val density = activity.resources.displayMetrics.density
                Box(Modifier.fillMaxSize()) {
                    // 336px: three device pixels per cell; 250px: a fractional scale.
                    Box(Modifier.size((336 / density).dp)) { RaftNativeHumanPixelAvatar(picture) }
                    Box(Modifier.padding(top = (400 / density).dp).size((250 / density).dp)) { RaftNativeHumanPixelAvatar(picture) }
                }
            },
        ) { _, bitmap ->
            val size = raster.size
            for (y in 0 until 336) {
                for (x in 0 until 336) {
                    val want = raster.argb[(y / 3) * size + x / 3]
                    assertEquals(want, bitmap.getPixel(x, y), "pixel ($x,$y) of cell (${x / 3},${y / 3})")
                }
            }
            val colours = raster.argb.toSet()
            for (y in 400 until 650) {
                for (x in 0 until 250) {
                    assertTrue(bitmap.getPixel(x, y) in colours, "pixel ($x,$y) is one of the picture's colours")
                }
            }
        }
    }

    private companion object {
        val ScreenshotValues =
            listOf(
                "pixel-human:v1:yellow.2.short.ink.wink.cap.none",
                "pixel-human:v1:pink.4.curly.ginger.grin.headphones.glasses",
                "pixel-human:v1:sky.5.long.blonde.blush.none.none/auto",
                "pixel-human:v1:mint.1.bun.grey.happy.beanie.none",
                "pixel-human:v2:a.later.version",
                "pixel-human:v1:Not.Of.The.Scheme",
            )
    }
}
