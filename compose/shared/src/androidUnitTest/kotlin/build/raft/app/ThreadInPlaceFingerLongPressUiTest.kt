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
 * artin dm:#99 (iOS, New Message Menu on): a finger long press on a message body first showed the
 * in-place selection's own menu ("Select All | Copy selection | Reply") and then the Compact menu
 * opened over it. Now a finger press on the body is the row's: its long press opens only the
 * message menu and no text selection starts; a mouse still selects in place. Drives the production
 * [ThreadInPlaceSelectableBody] inside a row whose long-press detector routes like the thread
 * row's ([threadMessageLongPressRoute] with [ThreadInPlaceSelectionController.ownsRowPressAt]).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadInPlaceFingerLongPressUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var controller: ThreadInPlaceSelectionController? = null
    private var messageMenuOpens = 0

    private fun ComposeUiTest.showRow() {
        // Compose string resources read the app context through the shared SQLDelight holder.
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        val host = ThreadInPlaceTextSelectionHost(enabled = true, copyText = {}, fingerPressOpensMessageMenu = true)
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
                                            pressOnSelectableBody = inPlace?.ownsRowPressAt(offset) == true
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
    fun aFingerLongPressOnTheBodyOpensOnlyTheMessageMenu() =
        runRobolectricComposeUiTest {
            showRow()
            onNodeWithTag(BODY_TAG).performTouchInput { longClick(Offset(width * 0.3f, centerY)) }
            waitForIdle()
            assertEquals(1, messageMenuOpens, "the row's long press opens the message menu")
            assertTrue(controller!!.bodyPressYieldedToRow)
            assertFalse(controller!!.hasSelection, "no text selection starts under the message menu")
            assertFalse(selectionMenuShown(), "no selection menu under the message menu")
            assertFalse(mouseToolbarShown())
        }

    @Test
    fun aMouseStillSelectsInPlaceAndOpensNoMessageMenu() =
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
            assertFalse(controller!!.bodyPressYieldedToRow)
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
