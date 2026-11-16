package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
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
import build.raft.designsystem.components.rememberRaftStablePaddingValues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hands iOS crash 8da5d67f: "visitSubtreeIf called on an unattached node" from
 * `InsetsPaddingModifierElement.update` while `CopyNodesToNewAnchorLocation` re-inserted movable
 * content. Movable content (a Nav3 scene's page, ThreadPage's movable parts) moves to a new parent
 * in the same frame the safe area changes (a rotation): the moved node gets its new modifier while
 * detached, `LayoutNode.attach` applies it before the modifier nodes are attached, and the insets
 * node's `update()` walks its descendants and throws.
 *
 * The app keeps every insets element equal across value changes (RaftStableWindowInsets.kt), so
 * `update()` never runs; the padding still follows the value. The first case pins the upstream
 * Compose behaviour this works around: when it stops throwing, Compose has fixed it.
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

    /** [moved] lives in one of two parents; [Frame.left] is the safe area it pads. */
    private fun ComposeUiTest.showMoving(frame: Frame, moved: @Composable () -> Unit) =
        setContent {
            val content = remember { movableContentOf(moved) }
            CompositionLocalProvider(LocalRaftSafeAreaInsets provides RaftSafeAreaInsets(left = frame.left)) {
                if (frame.inFirstParent) {
                    Box(Modifier.testTag(FIRST)) { content() }
                } else {
                    Column(Modifier.testTag(SECOND)) { content() }
                }
            }
        }

    /** Moves the content and changes the safe area in one frame, as the rotation during resume did. */
    private fun ComposeUiTest.moveWhileSafeAreaChanges(frame: Frame) {
        runOnIdle {
            frame.inFirstParent = false
            frame.left = 24.dp
        }
        waitForIdle()
    }

    private fun ComposeUiTest.innerLeft(): Dp = onNodeWithTag(INNER).getUnclippedBoundsInRoot().left

    @Test
    fun upstreamInsetsPaddingThrowsWhenItsInsetsChangeDuringAMove() {
        val failure =
            assertFailsWith<IllegalStateException> {
                runRobolectricComposeUiTest {
                    val frame = Frame()
                    showMoving(frame) {
                        // A new WindowInsets per value: what raftSafeAreaHorizontalPadding did before.
                        val insets = WindowInsets(left = LocalRaftSafeAreaInsets.current.left)
                        Box(Modifier.windowInsetsPadding(insets)) { Box(Modifier.size(10.dp).testTag(INNER)) }
                    }
                    onNodeWithTag(INNER).assertExists()
                    moveWhileSafeAreaChanges(frame)
                }
            }
        assertTrue(
            failure.message.orEmpty().contains("unattached"),
            "expected Compose's unattached-node failure, got: $failure",
        )
    }

    @Test
    fun safeAreaPaddingFollowsTheValueAcrossAMove() =
        runRobolectricComposeUiTest {
            val frame = Frame()
            showMoving(frame) {
                // Nested as in production (a row inside a padded header): the inner one pads nothing more.
                Box(Modifier.raftSafeAreaHorizontalPadding()) {
                    Box(Modifier.raftSafeAreaHorizontalPadding()) { Box(Modifier.size(10.dp).testTag(INNER)) }
                }
            }
            onNodeWithTag(FIRST).assertExists()
            assertEquals(0.dp, innerLeft())

            moveWhileSafeAreaChanges(frame)

            onNodeWithTag(SECOND).assertExists()
            assertEquals(24.dp, innerLeft(), "padded once by the new safe area after the move")
            runOnIdle { frame.left = 8.dp }
            assertEquals(8.dp, innerLeft(), "a later value re-measures without a new element")
        }

    @Test
    fun consumedPaddingFollowsTheValueAcrossAMove() =
        runRobolectricComposeUiTest {
            val frame = Frame()
            showMoving(frame) {
                // RaftHorizontalSafeAreaShell / RaftSideColumnScaffold: consume what the box padded.
                val consumed = rememberRaftStablePaddingValues(PaddingValues.Absolute(left = frame.left))
                Box(Modifier.consumeWindowInsets(consumed)) {
                    Box(Modifier.raftSafeAreaHorizontalPadding()) { Box(Modifier.size(10.dp).testTag(INNER)) }
                }
            }
            assertEquals(0.dp, innerLeft())

            moveWhileSafeAreaChanges(frame)

            assertEquals(0.dp, innerLeft(), "the consumed 24dp leaves nothing to pad")
        }

    private companion object {
        const val FIRST = "first"
        const val SECOND = "second"
        const val INNER = "inner"
    }
}
