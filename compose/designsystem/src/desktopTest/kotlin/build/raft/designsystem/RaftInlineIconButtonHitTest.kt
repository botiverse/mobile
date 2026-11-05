package build.raft.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The inline icon button sizes keep their layout box (24dp / 28dp) but not their hit area: a
 * finger reaches 44dp, a mouse 28dp (raft-ui `before:-inset-1` on the attachment action).
 */
@OptIn(ExperimentalTestApi::class)
class RaftInlineIconButtonHitTest {
    private fun hits(size: RaftIconButtonSize, pointer: RaftPointerInput, outsideDp: Float, mouse: Boolean): Int {
        var clicks = 0
        runComposeUiTest {
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false, pointerInput = pointer) {
                    Box(Modifier.padding(40.dp)) {
                        RaftIconButton(onClick = { clicks++ }, tooltip = null, contentDescription = "Close", variant = RaftButtonVariant.Ghost, size = size) {
                            Text("x")
                        }
                    }
                }
            }
            val node = onNodeWithContentDescription("Close")
            val bounds = node.getUnclippedBoundsInRoot()
            assertEquals(size.layoutBox(), bounds.right - bounds.left, "the layout keeps the box")
            if (mouse) {
                node.performMouseInput { click(Offset(-outsideDp.dp.toPx(), centerY)) }
            } else {
                node.performTouchInput { click(Offset(-outsideDp.dp.toPx(), centerY)) }
            }
        }
        return clicks
    }

    private fun RaftIconButtonSize.layoutBox() = if (this == RaftIconButtonSize.Xs) 24.dp else 28.dp

    @Test
    fun aMouseHitsTheExtraSmallButtonUpTo2dpOutside() {
        assertEquals(1, hits(RaftIconButtonSize.Xs, RaftPointerInput.Fine, outsideDp = 1.5f, mouse = true))
        assertEquals(0, hits(RaftIconButtonSize.Xs, RaftPointerInput.Fine, outsideDp = 3f, mouse = true))
    }

    @Test
    fun aFingerHitsTheInlineButtonsUpTo44dp() {
        assertEquals(1, hits(RaftIconButtonSize.Xs, RaftPointerInput.Touch, outsideDp = 9f, mouse = false))
        assertEquals(1, hits(RaftIconButtonSize.Sm, RaftPointerInput.Touch, outsideDp = 7f, mouse = false))
    }
}
