package build.raft.app

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.LocalTextClassifierCoroutineContext
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import build.raft.app.base.BridgeModule
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IModuleCreator
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.PagerManager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.markdown.LocalRaftMarkdownCodeTapObserver
import build.raft.app.thread.LocalThreadInPlaceTextSelectionHost
import build.raft.app.thread.ThreadInPlaceSelectableBody
import build.raft.app.thread.ThreadInPlaceSelectionController
import build.raft.app.thread.ThreadInPlaceSelectionToolbarTag
import build.raft.app.thread.ThreadInPlaceTextSelectionHost
import build.raft.app.thread.ThreadMessageLongPressRoute
import build.raft.app.thread.raftIsTouchLike
import build.raft.app.thread.rememberThreadInPlaceSelectionController
import build.raft.app.thread.rememberThreadMessageLongPressGestureOwner
import build.raft.app.thread.threadInPlaceSelectionCoversAll
import build.raft.app.thread.threadMessageLongPressActions
import build.raft.app.thread.threadMessageLongPressRoute
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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
// Real text measurement so heading and code-block hit areas match production layout.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
// A finger selection shows its handles' magnifier, which Robolectric cannot dismiss.
// Phone width (411dp) ensures the 360dp body box inside the 24dp start-padded row isn't clamped to 296dp.
@Config(
    sdk = [34],
    qualifiers = "w411dp-h891dp-xxhdpi",
    shadows = [RaftFloatingToolbarAnchorGuardUiTest.NoSurfaceMagnifier::class],
)
class ThreadInPlaceFingerLongPressUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    // RaftMarkdownCodeBlockWithCopy reads LocalActivity + Utils.bridgeModule(pager.pagerId).
    private val livePager =
        object : Pager() {}.apply {
            pagerId = "thread-in-place-finger-long-press"
            pageData = PageData(JSONObject())
            registerModule(BridgeModule.MODULE_NAME, IModuleCreator { BridgeModule() })
        }

    @BeforeTest fun registerPager() = PagerManager.register(livePager)

    @AfterTest fun unregisterPager() = PagerManager.unregister(livePager)

    private var controller: ThreadInPlaceSelectionController? = null
    private var messageMenuOpens = 0

    private fun ComposeUiTest.showRow() {
        // Compose string resources read the app context through the shared SQLDelight holder.
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        val host = ThreadInPlaceTextSelectionHost(enabled = true, copyText = {})
        host.selectionMenu.publish { _ -> Box(Modifier.size(40.dp).testTag(SELECTION_MENU)) }
        setContent {
            CompositionLocalProvider(
                LocalThreadInPlaceTextSelectionHost provides host,
                // Before a touch selection's toolbar request, Compose runs smart-selection text
                // classification on this context (Dispatchers.IO by default): a real thread the
                // virtual clock and waitForIdle do not wait for, so on a busy test JVM the menu
                // check ran before the request. In place on the test's own dispatcher instead.
                LocalTextClassifierCoroutineContext provides EmptyCoroutineContext,
            ) {
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
            assertTrue(controller!!.hasSelection, "a finger long press selects the message's text in place")
            assertTrue(
                threadInPlaceSelectionCoversAll(controller!!.selectedTexts(), controller!!.selectableTexts()),
                "a finger's selection widens to the whole message"
            )
            assertTrue(selectionMenuShown(), "the Compact message menu shows for the selection")
            assertFalse(mouseToolbarShown(), "not the mouse's Quote | Copy toolbar")
            assertEquals(0, messageMenuOpens, "the row's long-press menu leaves the body to the selection")
        }

    private fun ComposeUiTest.showProductionGestureRow(
        bodyContent: @Composable (ThreadInPlaceSelectionController?) -> Unit,
    ) {
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        val host = ThreadInPlaceTextSelectionHost(enabled = true, copyText = {})
        host.selectionMenu.publish { _ -> Box(Modifier.size(40.dp).testTag(SELECTION_MENU)) }
        setContent {
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(livePager),
                LocalThreadInPlaceTextSelectionHost provides host,
                LocalTextClassifierCoroutineContext provides EmptyCoroutineContext,
            ) {
                RaftDesignSystemTheme {
                    val inPlace = rememberThreadInPlaceSelectionController(messageId = "m1", enabled = true)
                    controller = inPlace
                    val gestureOwner =
                        rememberThreadMessageLongPressGestureOwner(instanceKey = "in-place-prod", messageId = "m1")
                    CompositionLocalProvider(
                        LocalRaftMarkdownCodeTapObserver provides {
                            gestureOwner.tapMovementGate.rejectChildAction("code_block")
                        },
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .testTag(ROW_TAG)
                                .onGloballyPositioned { inPlace?.rowCoordinates = it }
                                .threadMessageLongPressActions(
                                    messageId = "m1",
                                    pointerSessionKey = 1,
                                    gestureOwner = gestureOwner,
                                    onShowActions = { messageMenuOpens++ },
                                    deferLongPressAt =
                                        inPlace?.let { selection ->
                                            { offset -> selection.bodyContainsRowOffset(offset) }
                                        },
                                    isInPlaceSelectionTaken =
                                        inPlace?.let { selection ->
                                            { selection.hasSelection || selection.linkLongPressOwnsGesture }
                                        },
                                    onPointerDown =
                                        inPlace?.let { selection ->
                                            { offset, pointerType ->
                                                selection.lastPointerDownFromTouch = pointerType.raftIsTouchLike()
                                                selection.linkLongPressOwnsGesture = false
                                                selection.dismissalGestureInFlight =
                                                    host.onRowPointerDown(
                                                        messageId = "m1",
                                                        insideBody = selection.bodyContainsRowOffset(offset)
                                                    )
                                            }
                                        },
                                )
                                .padding(top = 120.dp, start = 24.dp)
                        ) {
                            bodyContent(inPlace)
                        }
                    }
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun aFingerLongPressInNonTextBodyGapFallsBackToMessageMenuWhileTextStillSelectsInPlace() =
        runRobolectricComposeUiTest {
            showProductionGestureRow { inPlace ->
                ThreadInPlaceSelectableBody(
                    controller = inPlace,
                    fullMessageText = "$BODY\n\nSecond paragraph",
                    wholeMessageText = "$BODY\n\nSecond paragraph",
                    messageMenuOpen = false,
                    diagnosticsLogger = {},
                ) {
                    Column(Modifier.width(360.dp)) {
                        BasicText(BODY, Modifier.fillMaxWidth().testTag(BODY_TAG))
                        Spacer(Modifier.fillMaxWidth().height(80.dp).testTag(GAP_TAG))
                        BasicText("Second paragraph", Modifier.fillMaxWidth())
                    }
                }
            }
            // Press in the non-text gap inside SelectionContainer: no BasicText starts selection,
            // so the row falls back to opening the message menu instead of swallowing the press.
            onNodeWithTag(GAP_TAG).performTouchInput { longClick(center) }
            waitForIdle()
            assertFalse(controller!!.hasSelection, "gap has no selectable text")
            assertEquals(1, messageMenuOpens, "non-text gap falls back to the row message menu")

            // Press on actual text: selects in place and does not open a duplicate row menu.
            onNodeWithTag(BODY_TAG).performTouchInput { longClick(Offset(width * 0.3f, centerY)) }
            waitForIdle()
            assertTrue(controller!!.hasSelection, "pressing on text selects in place")
            assertTrue(selectionMenuShown(), "selection menu shows for text selection")
            assertEquals(1, messageMenuOpens, "successful in-place selection must not also emit row menu")
        }

    private companion object {
        const val BODY = "Ship the release notes before the launch review tomorrow"
        const val ROW_TAG = "thread_in_place_finger_long_press_test_row"
        const val BODY_TAG = "thread_in_place_finger_long_press_test_body"
        const val MARKDOWN_TAG = "thread_in_place_finger_long_press_test_markdown"
        const val GAP_TAG = "thread_in_place_finger_long_press_test_gap"
        const val FOOTER_TAG = "thread_in_place_finger_long_press_test_footer"
        const val SELECTION_MENU = "thread_in_place_finger_long_press_test_selection_menu"
    }
}
