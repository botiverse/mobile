package build.raft.app

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Owner, 1.23 release blocker: with the New Message Menu on, a finger long press on a message's text
 * selects it in place again (1.15 "long-press a message's text to select and copy it in place"):
 * the selection widens to the whole message and shows the Compact message menu in place of the
 * platform's text toolbar; the row's own long-press menu stays out of it. 1.23 (#4460) had made a
 * finger press on the body the row's, so no text could be selected. Drives the production
 * [ThreadInPlaceSelectableBody] inside a row whose long-press detector routes like the thread
 * row's ([threadMessageLongPressRoute] with [ThreadInPlaceSelectionController.bodyContainsRowOffset]).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
// A finger selection shows its handles' magnifier, which Robolectric cannot dismiss.
@Config(sdk = [34], shadows = [RaftFloatingToolbarAnchorGuardUiTest.NoSurfaceMagnifier::class])
class ThreadInPlaceFingerLongPressUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var controller: ThreadInPlaceSelectionController? = null
    private var messageMenuOpens = 0

    private fun ComposeUiTest.showRow() {
        // Compose string resources read the app context through the shared SQLDelight holder.
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        val host = ThreadInPlaceTextSelectionHost(enabled = true, copyText = {})
        host.selectionMenu.publish { _ -> Box(Modifier.size(40.dp).testTag(SELECTION_MENU)) }
        setContent {
            CompositionLocalProvider(LocalThreadInPlaceTextSelectionHost provides host) {
                val inPlace = rememberThreadInPlaceSelectionController(messageId = "m1", enabled = true)
                controller = inPlace
                Box(
                    Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { inPlace?.rowCoordinates = it }
                        .pointerInput(inPlace) {
                            detectTapGestures(
                                onLongPress = { offset ->
                                    val route =
                                        threadMessageLongPressRoute(
                                            inPlaceSelectionEnabled = inPlace != null,
                                            pressOnSelectableBody = inPlace?.bodyContainsRowOffset(offset) == true
                                        )
                                    if (route == ThreadMessageLongPressRoute.MessageMenu) messageMenuOpens++
                                }
                            )
                        }
                        .padding(top = 120.dp, start = 24.dp)
                ) {
                    ThreadInPlaceSelectableBody(
                        controller = inPlace,
                        fullMessageText = BODY,
                        wholeMessageText = BODY,
                        messageMenuOpen = false,
                        diagnosticsLogger = {},
                    ) {
                        BasicText(BODY, Modifier.width(360.dp).testTag(BODY_TAG))
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.selectionMenuShown(): Boolean =
        onAllNodes(hasTestTag(SELECTION_MENU), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.mouseToolbarShown(): Boolean =
        onAllNodes(hasTestTag(ThreadInPlaceSelectionToolbarTag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aFingerLongPressOnTheBodySelectsTheMessageInPlaceUnderTheMessageMenu() =
        runRobolectricComposeUiTest {
            showRow()
            onNodeWithTag(BODY_TAG).performTouchInput { longClick(Offset(width * 0.3f, centerY)) }
            waitForIdle()
            // The platform asks for the text toolbar a moment after the press (virtual clock; as
            // RaftFloatingToolbarAnchorGuardUiTest): without this the menu check raced it.
            mainClock.advanceTimeBy(500)
            waitForIdle()
            assertTrue(controller!!.hasSelection, "a finger long press selects the message's text in place")
            assertTrue(
                threadInPlaceSelectionCoversAll(controller!!.selectedTexts(), controller!!.selectableTexts()),
                "a finger's selection widens to the whole message"
            )
            assertTrue(selectionMenuShown(), "the Compact message menu shows for the selection")
            assertFalse(mouseToolbarShown(), "not the mouse's Quote | Copy toolbar")
            assertEquals(0, messageMenuOpens, "the row's long-press menu leaves the body to the selection")
        }

    @Test
    fun aMouseSelectsInPlaceAndOpensNoMessageMenu() =
        runRobolectricComposeUiTest {
            showRow()
            onNodeWithTag(BODY_TAG).performMouseInput {
                moveTo(Offset(2f, centerY))
                press()
                moveTo(Offset(width * 0.3f, centerY))
                moveTo(Offset(width * 0.5f, centerY))
                release()
            }
            waitForIdle()
            assertTrue(controller!!.hasSelection, "a mouse drag on the body still selects text")
            assertTrue(mouseToolbarShown(), "with its Quote | Copy toolbar")
            assertEquals(0, messageMenuOpens)
        }

    private companion object {
        const val BODY = "Ship the release notes before the launch review tomorrow"
        const val BODY_TAG = "thread_in_place_finger_long_press_test_body"
        const val SELECTION_MENU = "thread_in_place_finger_long_press_test_selection_menu"
    }
}
