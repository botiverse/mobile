package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A right-click on a message in the wide layout's thread pane opened the message menu a pane-width
 * to the right of the pointer (artin, macOS). The pane is a [RaftChildPage], which CMP composes
 * inline: the row's live root anchor resolves straight to window pixels, and the shell overlay
 * path then added the page's measured origin a second time.
 *
 * This lays a "child page" out at an x offset (rail + list), measures its origin the way
 * [RaftChildPage] does, right-clicks a row inside it at a known point, and checks that the anchor
 * handed to the shell is the pointer's window position -- not shifted by the page origin.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h600dp-mdpi")
class RaftChildPageWindowAnchorUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun aRightClickInAChildPagePaneAnchorsAtThePointerInWindowSpace() =
        runRobolectricComposeUiTest {
            val pageOrigin = RaftChildPageOriginState()
            val anchorRef = RaftLiveRootAnchorRef(identity = "message:1")
            var contextAnchor: RaftPopoverAnchor? = null
            setContent {
                Box(Modifier.fillMaxSize()) {
                    // The thread pane (child page host), right of the rail and the list.
                    Box(
                        Modifier
                            .offset { IntOffset(PANE_X, PANE_Y) }
                            .onGloballyPositioned { pageOrigin.updateOrigin(it.positionInRoot()) }
                            .size(200.dp)
                    ) {
                        // A message row inside the page, with the same capture + press-position
                        // resolution as the thread row's "context_click" detector.
                        Box(
                            Modifier
                                .offset { IntOffset(ROW_X, ROW_Y) }
                                .size(100.dp)
                                .testTag(ROW_TAG)
                                .raftCaptureLiveRootAnchor(anchorRef)
                                .pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            if (event.buttons.isSecondaryPressed && contextAnchor == null) {
                                                val press = event.changes.first()
                                                contextAnchor =
                                                    anchorRef.resolve(RaftPopoverLocalPoint.Exact(press.position))
                                            }
                                        }
                                    }
                                }
                        )
                    }
                }
            }
            waitForIdle()
            assertEquals(Offset(PANE_X.toFloat(), PANE_Y.toFloat()), pageOrigin.windowOrigin)

            onNodeWithTag(ROW_TAG).performMouseInput {
                moveTo(POINTER_IN_ROW)
                press(MouseButton.Secondary)
                release(MouseButton.Secondary)
            }
            waitForIdle()

            val anchor = assertNotNull(contextAnchor, "the right-click resolves an anchor")
            val pointerInWindow =
                Offset((PANE_X + ROW_X).toFloat(), (PANE_Y + ROW_Y).toFloat()) + POINTER_IN_ROW
            assertEquals(
                pointerInWindow,
                raftChildPageWindowAnchor(anchor),
                "the shell anchor is the pointer in window pixels; adding the page origin " +
                    "(${pageOrigin.windowOrigin}) again would open the menu that far right"
            )
        }

    private companion object {
        const val ROW_TAG = "row"
        const val PANE_X = 320
        const val PANE_Y = 0
        const val ROW_X = 30
        const val ROW_Y = 50
        val POINTER_IN_ROW = Offset(17f, 23f)
    }
}
