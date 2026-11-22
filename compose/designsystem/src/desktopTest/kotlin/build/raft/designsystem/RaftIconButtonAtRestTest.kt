package build.raft.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftEmbeddedControls
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonAtRest
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [RaftIconButtonAtRest] is a [RaftIconButton] at rest as a picture: the conversation composer is
 * drawn with it while its page slides in and swapped for the live composer once the slide settles
 * (1.28), so the two must not differ by a pixel. And it is no button: nothing to click.
 */
@OptIn(ExperimentalTestApi::class)
class RaftIconButtonAtRestTest {
    private data class Case(
        val variant: RaftButtonVariant,
        val enabled: Boolean,
        val embedded: Boolean,
        val pointer: RaftPointerInput = RaftPointerInput.Touch,
    )

    private val cases =
        listOf(
            // The composer's gallery, camera and file buttons, and the same off.
            Case(RaftButtonVariant.Ghost, enabled = true, embedded = true),
            Case(RaftButtonVariant.Ghost, enabled = false, embedded = true),
            // Its Send: off at rest (nothing to send), the pink sticker once there is.
            Case(RaftButtonVariant.Primary, enabled = false, embedded = true),
            Case(RaftButtonVariant.Primary, enabled = true, embedded = true),
            // Outside a frame, under a mouse (the desktop's 34dp row height).
            Case(RaftButtonVariant.Default, enabled = true, embedded = false, pointer = RaftPointerInput.Fine),
        )

    @Test
    fun thePictureIsTheButtonAtRestPixelForPixel() {
        listOf(RaftAppearance.Light, RaftAppearance.Dark).forEach { appearance ->
            cases.forEach { case -> assertSamePicture(appearance, case) }
        }
    }

    private fun assertSamePicture(appearance: RaftAppearance, case: Case) =
        runComposeUiTest {
            setContent {
                RaftTheme(appearance = appearance, systemDark = appearance == RaftAppearance.Dark, pointerInput = case.pointer) {
                    Row(
                        Modifier.background(RaftTheme.colors.layerCard).padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val pair: @Composable () -> Unit = {
                            Box(Modifier.testTag(ButtonTag)) {
                                RaftIconButton(onClick = {}, tooltip = "Send", variant = case.variant, enabled = case.enabled) { Glyph() }
                            }
                            Box(Modifier.testTag(PictureTag)) {
                                RaftIconButtonAtRest(variant = case.variant, enabled = case.enabled) { Glyph() }
                            }
                        }
                        if (case.embedded) RaftEmbeddedControls(pair) else pair()
                    }
                }
            }
            val button = onNodeWithTag(ButtonTag).pixels()
            val picture = onNodeWithTag(PictureTag).pixels()
            assertEquals(button.size, picture.size, "$appearance $case: the picture's box is the button's")
            assertEquals(button.width, picture.width, "$appearance $case: the picture's box is the button's")
            assertTrue(button.pixels.contentEquals(picture.pixels), "$appearance $case: the picture draws the button at rest")
            onAllNodesWithTag(PictureTag, useUnmergedTree = true).assertCountEquals(1)
            // The real button is the one thing to click.
            onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(1)
        }

    /** The icon slot: a square in the ink the button gives its icon. */
    @Composable
    private fun Glyph() {
        Box(Modifier.size(10.dp).background(LocalRaftContentColor.current))
    }

    private class Pixels(val width: Int, val pixels: IntArray) {
        val size: Int get() = pixels.size
    }

    private fun SemanticsNodeInteraction.pixels(): Pixels {
        val map = captureToImage().toPixelMap()
        return Pixels(map.width, IntArray(map.width * map.height) { i -> map[i % map.width, i / map.width].hashCode() })
    }

    private companion object {
        const val ButtonTag = "button"
        const val PictureTag = "picture"
    }
}
