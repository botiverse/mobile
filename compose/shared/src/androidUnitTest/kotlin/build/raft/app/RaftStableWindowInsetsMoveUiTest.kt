package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftSafeAreaInsets
import build.raft.designsystem.components.RaftSafeAreaInsets
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hands iOS crash 8da5d67f: "visitSubtreeIf called on an unattached node" from
 * `InsetsPaddingModifierElement.update` while `CopyNodesToNewAnchorLocation` re-inserted movable
 * content. Compose runs an insets node's `update()` only when its element compares unequal, and that
 * `update()` throws on a node that is not attached yet.
 *
 * The app keeps every insets element equal across value changes (RaftStableWindowInsets.kt), so
 * `update()` never runs; the padding still follows the value. These cases pin both halves: the
 * element stays equal when the safe area changes, and the layout is the one a new `WindowInsets`
 * per value gave.
 *
 * The throw itself does not reproduce here: under Robolectric (androidx foundation-layout 1.12.1)
 * movable content moved to a new parent is not recomposed with new values in the move's frame, so
 * the detached `update()` never runs, with or without the fix. The move case below therefore only
 * checks that a moved node keeps padding by the current safe area.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaftStableWindowInsetsMoveUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private class Frame {
        var inFirstParent by mutableStateOf(true)
        var left by mutableStateOf(0.dp)
    }

    /** [content] under a safe area of [Frame.left], in one of two parents. */
    private fun ComposeUiTest.show(frame: Frame, content: @Composable () -> Unit) =
        setContent {
            val moving = remember { movableContentOf(content) }
            CompositionLocalProvider(LocalRaftSafeAreaInsets provides RaftSafeAreaInsets(left = frame.left)) {
                if (frame.inFirstParent) {
                    Box { moving() }
                } else {
                    Column { moving() }
                }
            }
        }

    private fun ComposeUiTest.innerLeft(): Dp = onNodeWithTag(INNER).getUnclippedBoundsInRoot().left

    /** The insets-padding element on the node tagged [PADDED] (compared, never its class or text). */
    private fun ComposeUiTest.insetsPaddingElement(): Modifier =
        onNodeWithTag(PADDED).fetchSemanticsNode().layoutInfo.getModifierInfo()
            .map { it.modifier }
            .single { it.javaClass.simpleName == "InsetsPaddingModifierElement" }

    private fun ComposeUiTest.elementAcrossASafeAreaChange(frame: Frame): Pair<Modifier, Modifier> {
        val before = insetsPaddingElement()
        runOnIdle { frame.left = 24.dp }
        return before to insetsPaddingElement()
    }

    @Test
    fun safeAreaPaddingElementStaysEqualWhenTheSafeAreaChanges() =
        runRobolectricComposeUiTest {
            val frame = Frame()
            show(frame) {
                Box(Modifier.testTag(PADDED).raftSafeAreaHorizontalPadding()) { Box(Modifier.size(10.dp).testTag(INNER)) }
            }
            val (before, after) = elementAcrossASafeAreaChange(frame)
            assertEquals(before, after, "a new safe area must not make the element unequal (update() would run)")
            assertEquals(24.dp, innerLeft(), "the padding still follows the safe area")
        }

    @Test
    fun movedSafeAreaPaddingKeepsFollowingTheValue() =
        runRobolectricComposeUiTest {
            val frame = Frame()
            show(frame) {
                Box(Modifier.raftSafeAreaHorizontalPadding()) { Box(Modifier.size(10.dp).testTag(INNER)) }
            }
            // The crash's frame: the content moves while the safe area changes.
            runOnIdle {
                frame.inFirstParent = false
                frame.left = 24.dp
            }
            runOnIdle { frame.left = 8.dp }
            assertEquals(8.dp, innerLeft(), "the moved node pads by the current safe area")
        }

    private companion object {
        const val PADDED = "padded"
        const val INNER = "inner"
    }
}
