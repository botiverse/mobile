package build.raft.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
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
            // The button's own semantics bounds include its hit overhang, so read its layout box
            // from a wrapper that takes exactly its place.
            var box = Rect.Zero
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false, pointerInput = pointer) {
                    Box(Modifier.padding(40.dp)) {
                        Box(Modifier.onGloballyPositioned { box = it.boundsInRoot() }) {
                            RaftIconButton(onClick = { clicks++ }, tooltip = null, contentDescription = "Close", variant = RaftButtonVariant.Ghost, size = size) {
                                Text("x")
                            }
                        }
                    }
                }
            }
            waitForIdle()
            assertEquals(with(density) { size.layoutBox().toPx() }, box.width, "the layout keeps the box")
            val at = Offset(box.left - with(density) { outsideDp.dp.toPx() }, box.center.y)
            if (mouse) {
                onRoot().performMouseInput { click(at) }
            } else {
                onRoot().performTouchInput { click(at) }
            }
            waitForIdle()
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
