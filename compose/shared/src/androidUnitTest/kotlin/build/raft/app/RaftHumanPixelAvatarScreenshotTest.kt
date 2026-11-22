package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
