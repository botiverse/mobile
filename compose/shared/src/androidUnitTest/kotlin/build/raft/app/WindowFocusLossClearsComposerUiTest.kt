package build.raft.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import build.raft.designsystem.focus.RaftFocusCoordinator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hands b0d1ed9f (#wg-mobile-feedback:#310): verifies the shared Compose focus bridge between
 * [RaftWindowFocusScope], [RaftInputFocusDismissHost], [TextField], and [Modal] (`keepsHostKeyboard`):
 *
 * - Clearing host Compose input focus on external window-focus loss unregisters the composer from
 *   [RaftInputFocusOwnerRegistry] and [RaftWindowInputFocusController], fires `onBlur`, leaves
 *   tapping back on the message list without stale focus (Path A), and allows tapping directly back
 *   onto the composer to type cleanly (Path B).
 * - An in-app message menu (`Modal(keepsHostKeyboard = true)`) holds a window-focus-loss retention
 *   token so `RaftCmpActivity.onWindowFocusChanged(false)` preserves the composer's focus and IME.
 * - A regular `Modal(keepsHostKeyboard = false)` with its own [TextField] neither holds a retention
 *   token nor pollutes the host window's focused text-input tracking.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WindowFocusLossClearsComposerUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun clearingHostComposeFocusLeavesMessageListCleanAndAllowsDirectComposerTyping() =
        runRobolectricComposeUiTest {
            val coordinator = RaftFocusCoordinator()
            val inputFocusController = RaftWindowInputFocusController()
            val composerFocus = FocusRequester()
            var draft by mutableStateOf("")
            var blurCount = 0
            var focusCount = 0
            var observedRegistry: RaftInputFocusOwnerRegistry? = null

            setContent {
                RaftWindowFocusScope(
                    coordinator = coordinator,
                    inputFocusController = inputFocusController,
                ) {
                    RaftInputFocusDismissHost { focusDismissModifier ->
                        observedRegistry = LocalRaftInputFocusOwnerRegistry.current
                        Column(focusDismissModifier.fillMaxSize()) {
                            // Non-editor focusable ahead of the composer (outside touch mode in
                            // Robolectric, clearing Compose focus otherwise falls back to the first
                            // focusable sibling).
                            Box(Modifier.size(1.dp).focusable())
                            Box(
                                Modifier
                                    .testTag(MESSAGE_LIST)
                                    .fillMaxWidth()
                                    .height(120.dp),
                            )
                            CompositionLocalProvider(LocalRaftEditorIsConversationComposer provides true) {
                                TextField(
                                    modifier = Modifier.testTag(COMPOSER).fillMaxWidth(),
                                    value = draft,
                                    autoFocus = false,
                                    onValueChange = { draft = it },
                                    onFocus = { focusCount++ },
                                    onBlur = { blurCount++ },
                                    focusRequester = composerFocus,
                                )
                            }
                        }
                    }
                }
            }

            val registry = assertNotNull(observedRegistry)
            runOnIdle { composerFocus.requestFocus() }
            waitForIdle()
            onNodeWithTag(COMPOSER).assertIsFocused()
            assertEquals(1, focusCount)
            assertEquals(0, blurCount)
            assertTrue(inputFocusController.hasFocusedTextInput)
            assertTrue(registry.hasActiveOwner)

            // Simulate RaftCmpActivity.onWindowFocusChanged(false) when switching to a floating window.
            runOnIdle { assertTrue(inputFocusController.clearComposeInputFocus()) }
            waitForIdle()

            onNodeWithTag(COMPOSER).assertIsNotFocused()
            assertEquals(1, blurCount)
            assertFalse(inputFocusController.hasFocusedTextInput)
            assertFalse(registry.hasActiveOwner)

            // Path A: tapping back on the message list keeps the composer unfocused with no extra blur.
            onNodeWithTag(MESSAGE_LIST).performTouchInput { click() }
            repeat(4) { mainClock.advanceTimeByFrame() }
            waitForIdle()
            onNodeWithTag(COMPOSER).assertIsNotFocused()
            assertEquals(1, blurCount)
            assertFalse(inputFocusController.hasFocusedTextInput)

            // Path B: tapping directly on the composer focuses cleanly and accepts continuous typing.
            onNodeWithTag(COMPOSER).performClick()
            waitForIdle()
            onNodeWithTag(COMPOSER).assertIsFocused()
            assertEquals(2, focusCount)
            assertTrue(inputFocusController.hasFocusedTextInput)
            assertTrue(registry.hasActiveOwner)

            onNodeWithTag(COMPOSER).performTextInput("hello world")
            waitForIdle()
            assertEquals("hello world", draft)
        }

    @Test
    fun messageMenuWithKeepsHostKeyboardHoldsRetentionTokenUntilDismissed() =
        runRobolectricComposeUiTest {
            val coordinator = RaftFocusCoordinator()
            val inputFocusController = RaftWindowInputFocusController()
            val composerFocus = FocusRequester()
            var menuMessageId by mutableStateOf<String?>(null)
            var blurCount = 0

            setContent {
                RaftWindowFocusScope(
                    coordinator = coordinator,
                    inputFocusController = inputFocusController,
                ) {
                    RaftInputFocusDismissHost { focusDismissModifier ->
                        Column(focusDismissModifier.fillMaxSize()) {
                            Box(Modifier.size(1.dp).focusable())
                            Box(
                                Modifier
                                    .testTag(MESSAGE_LIST)
                                    .fillMaxWidth()
                                    .height(120.dp)
                                    .combinedClickable(onClick = {}, onLongClick = { menuMessageId = "m-1" }),
                            )
                            CompositionLocalProvider(LocalRaftEditorIsConversationComposer provides true) {
                                TextField(
                                    modifier = Modifier.testTag(COMPOSER).fillMaxWidth(),
                                    value = "",
                                    autoFocus = false,
                                    onValueChange = {},
                                    onBlur = { blurCount++ },
                                    focusRequester = composerFocus,
                                )
                            }
                        }
                        menuMessageId?.let { messageId ->
                            ThreadMessageMenuKeepsComposerFocus(messageId)
                            RaftModalAnchoredPopover(
                                anchor = remember { RaftPopoverAnchor.forRootOffset(Offset(40f, 60f)) },
                                contentSize = Size(200f, 120f),
                                onDismiss = { menuMessageId = null },
                                dismissActivationDelayMillis = 0,
                                keepsHostKeyboard = true,
                            ) {
                                Box(Modifier.testTag(MENU).size(100.dp, 60.dp))
                            }
                        }
                    }
                }
            }

            runOnIdle { composerFocus.requestFocus() }
            waitForIdle()
            onNodeWithTag(COMPOSER).assertIsFocused()
            assertFalse(inputFocusController.isRetainingInputFocusOnWindowFocusLoss)

            onNodeWithTag(MESSAGE_LIST).performTouchInput { longClick() }
            repeat(4) { mainClock.advanceTimeByFrame() }
            waitForIdle()

            onNodeWithTag(MENU).assertExists()
            onNodeWithTag(COMPOSER).assertIsFocused()
            assertTrue(
                inputFocusController.isRetainingInputFocusOnWindowFocusLoss,
                "Modal(keepsHostKeyboard = true) must mark retention before Dialog takes window focus",
            )
            assertEquals(0, blurCount)

            // Dismissing the menu releases retention while leaving the composer focused.
            runOnIdle { menuMessageId = null }
            waitForIdle()
            assertFalse(inputFocusController.isRetainingInputFocusOnWindowFocusLoss)
            onNodeWithTag(COMPOSER).assertIsFocused()
        }

    @Test
    fun regularModalTextFieldDoesNotRetainHostKeyboardOrPolluteHostTextInputTracking() =
        runRobolectricComposeUiTest {
            val coordinator = RaftFocusCoordinator()
            val inputFocusController = RaftWindowInputFocusController()
            val modalFieldFocus = FocusRequester()
            var modalOpen by mutableStateOf(false)

            setContent {
                RaftWindowFocusScope(
                    coordinator = coordinator,
                    inputFocusController = inputFocusController,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        if (modalOpen) {
                            Modal(
                                onWillDismiss = { modalOpen = false },
                                keepsHostKeyboard = false,
                            ) {
                                TextField(
                                    modifier = Modifier.testTag(MODAL_FIELD).fillMaxWidth(),
                                    value = "",
                                    autoFocus = false,
                                    onValueChange = {},
                                    focusRequester = modalFieldFocus,
                                )
                            }
                        }
                    }
                }
            }

            runOnIdle { modalOpen = true }
            waitForIdle()
            runOnIdle { modalFieldFocus.requestFocus() }
            waitForIdle()

            onNodeWithTag(MODAL_FIELD).assertIsFocused()
            assertFalse(inputFocusController.isRetainingInputFocusOnWindowFocusLoss)
            assertFalse(
                inputFocusController.hasFocusedTextInput,
                "a TextField inside a Modal dialog belongs to the dialog window, not the host Activity",
            )
        }

    private companion object {
        const val MESSAGE_LIST = "message_list"
        const val COMPOSER = "composer"
        const val MENU = "menu"
        const val MODAL_FIELD = "modal_field"
    }
}
