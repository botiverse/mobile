package build.raft.app

import android.content.Context
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import build.raft.app.nav.RaftStackPageActivity
import build.raft.designsystem.focus.RaftFocusCoordinator
import build.raft.designsystem.focus.RaftFocusRegion
import build.raft.designsystem.focus.RaftFocusRegionHost
import build.raft.designsystem.focus.RaftFocusRequestSource
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowViewRootImpl

/**
 * First-principles 3-axis composer/editor IME state model tests
 * (`Window Focus` × `Editor Focus & Cursor` × `Explicit Keyboard-Show Intent`):
 *
 * 1. Focused composer + window focus loss -> regain with default `showKeyboardOnFocus = false`
 *    triggers zero `showSoftInput` / input session; subsequent message-list tap clears focus with
 *    zero keyboard flash.
 * 2. Focused composer with draft + non-end cursor + window focus loss -> regain -> direct tap on
 *    composer starts the input session (`fromTap = true`), opens the keyboard, and preserves draft
 *    text & accepts typing cleanly.
 * 3. Window focus loss (e.g. notification shade) does NOT wipe composer focus, draft text, or
 *    cursor/selection.
 * 4. `Modal(keepsHostKeyboard = true)` (message long-press menu) keeps the composer focused and
 *    retains `FLAG_ALT_FOCUSABLE_IM` across host window focus loss without any retention-token hack.
 * 5. Programmatic explicit keyboard-show intents (`autoFocus = true`, `focusNonce` `RaiseKeyboard`
 *    on both unfocused and already-focused fields, and `ThreadComposerKeyboardHandoff` return from
 *    image editor) arm `showKeyboardOnFocus = true`, start the input session / open the keyboard,
 *    and disarm back to `false` without disposing the running input session, while `FocusOnly`
 *    leaves `showKeyboardOnFocus = false` with zero input session started.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposerImeStateModelUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private class RecordingKeyboard : SoftwareKeyboardController {
        var shown = 0
        var hidden = 0

        override fun show() {
            shown += 1
        }

        override fun hide() {
            hidden += 1
        }
    }

    @Test
    fun showKeyboardOnFocusPolicyMatrixAndIntentGenerationDisarming() {
        assertFalse(raftResolveShowKeyboardOnFocus(desktopHost = false, explicitKeyboardIntentArmed = false))
        assertTrue(raftResolveShowKeyboardOnFocus(desktopHost = false, explicitKeyboardIntentArmed = true))
        assertTrue(raftResolveShowKeyboardOnFocus(desktopHost = true, explicitKeyboardIntentArmed = false))
        assertFalse(
            raftResolveShowKeyboardOnFocus(
                desktopHost = true,
                explicitKeyboardIntentArmed = true,
                callerOverride = false,
            ),
        )
        assertTrue(
            raftResolveShowKeyboardOnFocus(
                desktopHost = false,
                explicitKeyboardIntentArmed = false,
                callerOverride = true,
            ),
        )

        val intent = RaftEditorKeyboardIntent()
        assertFalse(intent.armed)
        val gen1 = intent.arm()
        assertTrue(intent.armed)
        val gen2 = intent.arm()
        // Stale generation must not disarm a newer explicit keyboard request.
        intent.disarmIfCurrent(gen1)
        assertTrue(intent.armed)
        intent.disarmIfCurrent(gen2)
        assertFalse(intent.armed)
    }

    // Scenarios 1 & 3: Window focus loss (floating window / notification shade) preserves focus,
    // draft, and cursor; window focus regain triggers zero showSoftInput; tapping the message list
    // then dismisses focus with zero keyboard flash.
    @Test
    fun windowFocusLossPreservesDraftAndCursorAndMessageListTapDismissesWithoutKeyboardFlash() =
        runRobolectricComposeUiTest {
            val composerFocus = FocusRequester()
            val fieldState = TextFieldState(initialText = "hello world", initialSelection = TextRange(5))
            var imeAction by mutableStateOf(ImeAction.Default)
            var blurCount = 0
            var hostComposeView: View? = null
            var observedWindowFocused = false

            setContent {
                val view = LocalView.current
                val isWindowFocused = LocalWindowInfo.current.isWindowFocused
                SideEffect {
                    hostComposeView = view
                    observedWindowFocused = isWindowFocused
                }
                RaftWindowFocusScope {
                    RaftInputFocusDismissHost { focusDismissModifier ->
                        Column(focusDismissModifier.fillMaxSize()) {
                            Box(Modifier.size(1.dp).focusable())
                            Box(
                                Modifier
                                    .testTag(MESSAGE_LIST)
                                    .fillMaxWidth()
                                    .height(120.dp),
                            )
                            CompositionLocalProvider(LocalRaftEditorIsConversationComposer provides true) {
                                TextField(
                                    state = fieldState,
                                    modifier = Modifier.testTag(COMPOSER).fillMaxWidth().height(48.dp),
                                    autoFocus = false,
                                    keyboardOptions = KeyboardOptions(imeAction = imeAction),
                                    onBlur = { blurCount++ },
                                    focusRequester = composerFocus,
                                )
                            }
                        }
                    }
                }
            }

            waitForIdle()
            val composeRoot = checkNotNull(hostComposeView)
            val imm = composeRoot.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            var showImeCount = 0
            var hideImeCount = 0
            shadowOf(imm).setSoftInputVisibilityHandler { visible ->
                if (visible) showImeCount++ else hideImeCount++
            }

            runOnIdle {
                dispatchWindowFocus(composeRoot, true)
                composerFocus.requestFocus()
            }
            waitForIdle()

            assertTrue(observedWindowFocused)
            onNodeWithTag(COMPOSER).assertIsFocused()
            assertEquals("hello world", fieldState.text.toString())
            assertEquals(TextRange(5), fieldState.selection)
            assertFalse(composeRoot.onCheckIsTextEditor(), "default showKeyboardOnFocus = false starts no input session")
            assertNull(composeRoot.onCreateInputConnection(EditorInfo()))
            assertEquals(0, showImeCount)

            // Scenario 3: window focus loss (e.g. notification shade or floating window) leaves
            // focus, draft, and non-end cursor intact and never hides another window's IME.
            runOnIdle { dispatchWindowFocus(composeRoot, false) }
            waitForIdle()
            assertFalse(observedWindowFocused)
            onNodeWithTag(COMPOSER).assertIsFocused()
            assertEquals("hello world", fieldState.text.toString())
            assertEquals(TextRange(5), fieldState.selection)
            assertEquals(0, blurCount)
            assertEquals(0, hideImeCount)

            // Scenario 1: window focus regain + recomposition triggers zero showSoftInput.
            runOnIdle {
                dispatchWindowFocus(composeRoot, true)
                imeAction = ImeAction.Send
            }
            waitForIdle()
            assertTrue(observedWindowFocused)
            onNodeWithTag(COMPOSER).assertIsFocused()
            assertEquals("hello world", fieldState.text.toString())
            assertEquals(TextRange(5), fieldState.selection)
            assertFalse(composeRoot.onCheckIsTextEditor())
            assertNull(composeRoot.onCreateInputConnection(EditorInfo()))
            assertEquals(0, showImeCount)

            // Tapping the message list after return dismisses focus cleanly after 2 frames with zero flash.
            onNodeWithTag(MESSAGE_LIST).performTouchInput { click() }
            repeat(4) { mainClock.advanceTimeByFrame() }
            waitForIdle()

            onNodeWithTag(COMPOSER).assertIsNotFocused()
            assertEquals(1, blurCount)
            assertEquals("hello world", fieldState.text.toString())
            assertEquals(0, showImeCount)
        }

    // Scenario 2: Returning from a floating window and tapping directly on the composer opens the
    // keyboard via Compose's native startInputSession(fromTap = true) while preserving draft & cursor.
    @Test
    fun directComposerTapAfterWindowFocusRegainStartsInputSessionAndPreservesDraftAndCursor() =
        runRobolectricComposeUiTest {
            val composerFocus = FocusRequester()
            val fieldState = TextFieldState(initialText = "hello world", initialSelection = TextRange(5))
            var hostComposeView: View? = null

            setContent {
                val view = LocalView.current
                SideEffect { hostComposeView = view }
                RaftWindowFocusScope {
                    RaftInputFocusDismissHost { focusDismissModifier ->
                        Column(focusDismissModifier.fillMaxSize()) {
                            Box(Modifier.size(1.dp).focusable())
                            CompositionLocalProvider(LocalRaftEditorIsConversationComposer provides true) {
                                TextField(
                                    state = fieldState,
                                    modifier = Modifier.testTag(COMPOSER).fillMaxWidth().height(48.dp),
                                    autoFocus = false,
                                    focusRequester = composerFocus,
                                )
                            }
                        }
                    }
                }
            }

            waitForIdle()
            val composeRoot = checkNotNull(hostComposeView)

            runOnIdle {
                dispatchWindowFocus(composeRoot, true)
                composerFocus.requestFocus()
            }
            waitForIdle()
            onNodeWithTag(COMPOSER).assertIsFocused()
            assertFalse(composeRoot.onCheckIsTextEditor())

            // Lose and regain window focus: draft and non-end cursor remain intact, no spurious session.
            runOnIdle { dispatchWindowFocus(composeRoot, false) }
            waitForIdle()
            runOnIdle { dispatchWindowFocus(composeRoot, true) }
            waitForIdle()
            assertEquals("hello world", fieldState.text.toString())
            assertEquals(TextRange(5), fieldState.selection)
            assertFalse(composeRoot.onCheckIsTextEditor())

            // Direct tap bypasses showKeyboardOnFocus == false via startInputSession(fromTap = true).
            onNodeWithTag(COMPOSER).performTouchInput { click() }
            waitForIdle()

            onNodeWithTag(COMPOSER).assertIsFocused()
            assertEquals("hello world", fieldState.text.toString())
            assertTrue(composeRoot.onCheckIsTextEditor(), "direct tap must start platform input session")
            val inputConnection = assertNotNull(composeRoot.onCreateInputConnection(EditorInfo()))
            runOnIdle {
                // Verify continuous typing works through the newly opened InputConnection.
                inputConnection.setSelection(5, 5)
                inputConnection.commitText(",", 1)
            }
            waitForIdle()
            assertEquals("hello, world", fieldState.text.toString())
            assertEquals(TextRange(6), fieldState.selection)
        }

    // Scenario 2b: After a prior tap starts an active platform input session (onCheckIsTextEditor ==
    // true), external window focus loss parks ONLY the inner platform session (onCheckIsTextEditor
    // becomes false, closing Android IMMS's floating-window flash hole) without wiping editor focus,
    // draft text, or cursor. Window focus regain + keyboardOptions recomposition keeps it parked
    // (zero IMMS / Compose flash), and tapping the composer again wakes & restarts the platform
    // session via SoftwareKeyboardController.show() with live typing restored.
    @Test
    fun priorTapSessionParksOnExternalWindowFocusLossAndWakesOnComposerTap() =
        runRobolectricComposeUiTest {
            val keyboard = RecordingKeyboard()
            val fieldState = TextFieldState(initialText = "hello world", initialSelection = TextRange(5))
            var imeAction by mutableStateOf(ImeAction.Default)
            var hostComposeView: View? = null

            setContent {
                val view = LocalView.current
                SideEffect { hostComposeView = view }
                CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                    RaftWindowFocusScope {
                        RaftInputFocusDismissHost { focusDismissModifier ->
                            Column(focusDismissModifier.fillMaxSize()) {
                                Box(Modifier.size(1.dp).focusable())
                                Box(
                                    Modifier
                                        .testTag(MESSAGE_LIST)
                                        .fillMaxWidth()
                                        .height(120.dp),
                                )
                                CompositionLocalProvider(LocalRaftEditorIsConversationComposer provides true) {
                                    TextField(
                                        state = fieldState,
                                        modifier = Modifier.testTag(COMPOSER).fillMaxWidth().height(48.dp),
                                        autoFocus = false,
                                        keyboardOptions = KeyboardOptions(imeAction = imeAction),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            waitForIdle()
            val composeRoot = checkNotNull(hostComposeView)
            val imm = composeRoot.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            var showImeCount = 0
            shadowOf(imm).setSoftInputVisibilityHandler { visible ->
                if (visible) showImeCount++
            }

            runOnIdle { dispatchWindowFocus(composeRoot, true) }
            waitForIdle()

            // Initial user tap starts the platform input session (fromTap = true).
            onNodeWithTag(COMPOSER).performTouchInput { click() }
            waitForIdle()
            onNodeWithTag(COMPOSER).assertIsFocused()
            assertTrue(composeRoot.onCheckIsTextEditor())
            assertNotNull(composeRoot.onCreateInputConnection(EditorInfo()))
            val selectionAfterInitialTap = fieldState.selection
            val showCountAfterInitialTap = showImeCount

            // External window focus loss (floating window / notification shade): parks ONLY the
            // inner platform session (onCheckIsTextEditor == false) while preserving focus, draft,
            // and cursor.
            runOnIdle { dispatchWindowFocus(composeRoot, false) }
            waitForIdle()
            onNodeWithTag(COMPOSER).assertIsFocused()
            assertEquals("hello world", fieldState.text.toString())
            assertEquals(selectionAfterInitialTap, fieldState.selection)
            assertFalse(
                composeRoot.onCheckIsTextEditor(),
                "external window focus loss must release platform input session so IMMS sees no editor",
            )
            assertNull(composeRoot.onCreateInputConnection(EditorInfo()))

            // Window focus regain + keyboardOptions recomposition keeps the platform session parked
            // (zero IMMS / Compose flash when returning from a floating window).
            runOnIdle {
                dispatchWindowFocus(composeRoot, true)
                imeAction = ImeAction.Send
            }
            waitForIdle()

            onNodeWithTag(COMPOSER).assertIsFocused()
            assertEquals("hello world", fieldState.text.toString())
            assertEquals(selectionAfterInitialTap, fieldState.selection)
            assertFalse(
                composeRoot.onCheckIsTextEditor(),
                "window focus regain must stay parked until explicit tap or keyboard intent",
            )
            assertNull(composeRoot.onCreateInputConnection(EditorInfo()))
            assertEquals(showCountAfterInitialTap, showImeCount)

            // Subsequent direct single tap on the composer invokes SoftwareKeyboardController.show(),
            // waking the parked session and re-creating a working InputConnection cleanly.
            mainClock.advanceTimeBy(600L)
            onNodeWithTag(COMPOSER).performTouchInput { click() }
            waitForIdle()
            assertEquals(1, keyboard.shown)
            assertTrue(
                composeRoot.onCheckIsTextEditor(),
                "tapping composer after return must wake and restart platform input session",
            )
            val wokenConnection = assertNotNull(composeRoot.onCreateInputConnection(EditorInfo()))
            runOnIdle {
                wokenConnection.setSelection(5, 5)
                wokenConnection.commitText("!", 1)
            }
            waitForIdle()
            assertEquals("hello! world", fieldState.text.toString())
            assertEquals(TextRange(6), fieldState.selection)

            // Second floating-window trip -> return -> message-list tap blurs cleanly while parked.
            runOnIdle { dispatchWindowFocus(composeRoot, false) }
            waitForIdle()
            assertFalse(composeRoot.onCheckIsTextEditor())
            runOnIdle { dispatchWindowFocus(composeRoot, true) }
            waitForIdle()
            assertFalse(composeRoot.onCheckIsTextEditor())

            onNodeWithTag(MESSAGE_LIST).performTouchInput { click() }
            repeat(4) { mainClock.advanceTimeByFrame() }
            waitForIdle()
            onNodeWithTag(COMPOSER).assertIsNotFocused()
            assertFalse(composeRoot.onCheckIsTextEditor())
            assertEquals("hello! world", fieldState.text.toString())
        }

    // Scenario 2c: Desktop host passes InterceptPlatformTextInput straight through untouched, and
    // child Dialog TextFields (their own AndroidComposeView) are never parked by host window loss.
    @Test
    fun desktopHostPassesPlatformInputGateThroughAndDialogTextFieldIsIsolatedFromHostLoss() =
        runRobolectricComposeUiTest {
            // 1. Desktop host (desktopHost = true): platform input session stays live across window loss.
            val desktopRetention = RaftHostKeyboardRetentionState()
            val desktopFocus = FocusRequester()
            var hostComposeView: View? = null

            setContent {
                val view = LocalView.current
                SideEffect { hostComposeView = view }
                RaftWindowPlatformInputGateScope(
                    desktopHost = true,
                    hostKeyboardRetention = desktopRetention,
                ) {
                    TextField(
                        modifier = Modifier.testTag(COMPOSER).fillMaxWidth().height(48.dp),
                        value = "desktop draft",
                        autoFocus = false,
                        onValueChange = {},
                        keyboardOptions = KeyboardOptions(showKeyboardOnFocus = true),
                        focusRequester = desktopFocus,
                    )
                }
            }

            waitForIdle()
            val composeRoot = checkNotNull(hostComposeView)
            runOnIdle {
                dispatchWindowFocus(composeRoot, true)
                desktopFocus.requestFocus()
            }
            waitForIdle()
            assertTrue(composeRoot.onCheckIsTextEditor())

            runOnIdle { dispatchWindowFocus(composeRoot, false) }
            waitForIdle()
            assertTrue(
                composeRoot.onCheckIsTextEditor(),
                "desktop host must pass platform text input straight through untouched",
            )
        }

    @Test
    fun dialogWithOwnTextFieldIsNotParkedWhenHostWindowLosesFocus() =
        runRobolectricComposeUiTest {
            var showDialog by mutableStateOf(false)
            var hostComposeView: View? = null
            var dialogComposeView: View? = null

            setContent {
                val view = LocalView.current
                SideEffect { hostComposeView = view }
                RaftWindowFocusScope {
                    Box(Modifier.fillMaxSize())
                    if (showDialog) {
                        Modal(onWillDismiss = { showDialog = false }, keepsHostKeyboard = false) {
                            val modalView = LocalView.current
                            SideEffect { dialogComposeView = modalView }
                            TextField(
                                modifier = Modifier.testTag(DIALOG_FIELD).fillMaxWidth().height(48.dp),
                                value = "dialog text",
                                autoFocus = true,
                                onValueChange = {},
                            )
                        }
                    }
                }
            }

            waitForIdle()
            val hostRoot = checkNotNull(hostComposeView)
            runOnIdle {
                dispatchWindowFocus(hostRoot, true)
                showDialog = true
            }
            waitForIdle()
            val dialogRoot = checkNotNull(dialogComposeView)
            // Simulate Android Dialog gaining window focus before autoFocus completes, then verify
            // a subsequent host Activity window-focus loss never parks the Dialog's own input session.
            runOnIdle { dispatchWindowFocus(dialogRoot, true) }
            mainClock.advanceTimeBy(150L)
            repeat(4) { mainClock.advanceTimeByFrame() }
            waitForIdle()

            onNodeWithTag(DIALOG_FIELD).assertIsFocused()
            assertTrue(dialogRoot.onCheckIsTextEditor(), "Dialog TextField autoFocus must start session")

            // Host Activity loses window focus to the Dialog (or external window).
            runOnIdle { dispatchWindowFocus(hostRoot, false) }
            waitForIdle()

            onNodeWithTag(DIALOG_FIELD).assertIsFocused()
            assertTrue(
                dialogRoot.onCheckIsTextEditor(),
                "child Dialog TextField must not be parked by host Activity window focus loss",
            )
            assertNotNull(dialogRoot.onCreateInputConnection(EditorInfo()))
        }

    // Scenario 4: Modal(keepsHostKeyboard = true) (message long-press menu) preserves composer focus
    // and FLAG_ALT_FOCUSABLE_IM across host window focus loss without any retention-token bridge.
    @Test
    fun messageMenuKeepsHostKeyboardAndComposerFocusAcrossHostWindowFocusLoss() =
        runRobolectricComposeUiTest {
            val composerFocus = FocusRequester()
            var menuMessageId by mutableStateOf<String?>(null)
            var blurCount = 0
            var hostComposeView: View? = null

            setContent {
                val view = LocalView.current
                SideEffect { hostComposeView = view }
                RaftWindowFocusScope {
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
                                    modifier = Modifier.testTag(COMPOSER).fillMaxWidth().height(48.dp),
                                    value = "draft",
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

            waitForIdle()
            val composeRoot = checkNotNull(hostComposeView)
            runOnIdle { dispatchWindowFocus(composeRoot, true) }
            waitForIdle()
            // Start an active platform input session via direct tap before opening the menu.
            onNodeWithTag(COMPOSER).performTouchInput { click() }
            waitForIdle()
            onNodeWithTag(COMPOSER).assertIsFocused()
            assertTrue(composeRoot.onCheckIsTextEditor(), "tapped composer must have live platform session")

            onNodeWithTag(MESSAGE_LIST).performTouchInput { longClick() }
            repeat(4) { mainClock.advanceTimeByFrame() }
            waitForIdle()

            // Simulate the Android Dialog window taking window focus from the host Activity.
            runOnIdle { dispatchWindowFocus(composeRoot, false) }
            waitForIdle()

            onNodeWithTag(MENU).assertExists()
            onNodeWithTag(COMPOSER).assertIsFocused()
            assertTrue(
                composeRoot.onCheckIsTextEditor(),
                "keepsHostKeyboard modal must NOT cancel or park the host platform input session",
            )
            assertNotNull(composeRoot.onCreateInputConnection(EditorInfo()))
            assertEquals(0, blurCount)
            val menuWindow = assertNotNull(ShadowDialog.getLatestDialog()?.window)
            assertTrue(
                menuWindow.attributes.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM != 0,
                "keepsHostKeyboard modal must declare FLAG_ALT_FOCUSABLE_IM",
            )

            // Dismissing the menu releases keepsHostKeyboard a frame BEFORE Dialog teardown returns
            // host window focus: edge-triggered gate must keep the platform session live across both.
            runOnIdle { menuMessageId = null }
            waitForIdle()
            assertTrue(
                composeRoot.onCheckIsTextEditor(),
                "releasing keepsHostKeyboard before host window focus returns must not park session",
            )
            runOnIdle { dispatchWindowFocus(composeRoot, true) }
            waitForIdle()
            onNodeWithTag(COMPOSER).assertIsFocused()
            assertTrue(composeRoot.onCheckIsTextEditor())
            assertNotNull(composeRoot.onCreateInputConnection(EditorInfo()))
            assertEquals(0, blurCount)
        }

    // Scenario 5a: TextField(autoFocus = true) arms showKeyboardOnFocus = true, starts the input
    // session, and disarms cleanly back to false without disposing the active session.
    @Test
    fun autoFocusTextFieldArmsStartsInputSessionAndDisarmsWithoutDroppingSession() =
        runRobolectricComposeUiTest {
            val keyboardIntent = RaftEditorKeyboardIntent()
            val keyboard = RecordingKeyboard()
            var hostComposeView: View? = null

            setContent {
                val view = LocalView.current
                SideEffect { hostComposeView = view }
                CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                    RaftWindowFocusScope {
                        TextField(
                            modifier = Modifier.testTag(AUTOFOCUS_FIELD).fillMaxWidth().height(48.dp),
                            value = "",
                            autoFocus = true,
                            onValueChange = {},
                            keyboardIntent = keyboardIntent,
                        )
                    }
                }
            }

            waitForIdle()
            val composeRoot = checkNotNull(hostComposeView)
            runOnIdle { dispatchWindowFocus(composeRoot, true) }
            mainClock.advanceTimeBy(150L)
            repeat(4) { mainClock.advanceTimeByFrame() }
            waitForIdle()

            onNodeWithTag(AUTOFOCUS_FIELD).assertIsFocused()
            assertTrue(composeRoot.onCheckIsTextEditor(), "autoFocus must start the input session")
            assertNotNull(composeRoot.onCreateInputConnection(EditorInfo()))
            assertEquals(1, keyboard.shown)
            assertFalse(keyboardIntent.armed, "explicit intent must disarm after settling")
            // Disarmed showKeyboardOnFocus = false must leave the active input session intact.
            assertTrue(composeRoot.onCheckIsTextEditor())
            assertNotNull(composeRoot.onCreateInputConnection(EditorInfo()))
        }

    // Scenario 5b: ThreadComposerContentLeaf FocusOnly leaves input session closed; RaiseKeyboard
    // (including when already focused) and ThreadComposerKeyboardHandoff return open the keyboard
    // and disarm back to false without dropping the active session.
    @Test
    fun composerFocusNonceAndKeyboardHandoffArmDisarmAndDistinguishFocusOnlyFromRaiseKeyboard() =
        runRobolectricComposeUiTest {
            val keyboard = RecordingKeyboard()
            val coordinator = RaftFocusCoordinator()
            var composerState by mutableStateOf(ThreadComposerUiState(instanceId = "conv-ime-model"))
            var covered by mutableStateOf(false)
            var handoff: ThreadComposerKeyboardHandoff? = null
            var hostComposeView: View? = null

            setContent {
                val view = LocalView.current
                SideEffect { hostComposeView = view }
                RaftTheme(appearance = RaftAppearance.Light) {
                    val portal = remember { ThreadComposerSuggestionPortalState() }
                    CompositionLocalProvider(
                        LocalSoftwareKeyboardController provides keyboard,
                        LocalThreadComposerSuggestionPortal provides portal,
                    ) {
                        RaftWindowFocusScope(coordinator = coordinator) {
                            RaftStackPageActivity(resumed = !covered) {
                                handoff =
                                    rememberThreadComposerKeyboardHandoff(key = "thread-ime-model") {
                                        composerState =
                                            composerState.copy(
                                                focusRequestNonce = composerState.focusRequestNonce + 1,
                                                focusRequestMeta =
                                                    ThreadComposerFocusRequestMeta(
                                                        source = RaftFocusRequestSource.User("request_focus"),
                                                        keyboardMode = ThreadComposerFocusRequestKeyboardMode.RaiseKeyboard,
                                                    ),
                                            )
                                    }
                                Column(Modifier.fillMaxSize()) {
                                    Box(Modifier.size(1.dp).focusable())
                                    RaftFocusRegionHost(RaftFocusRegion.Conversation) {
                                        val focusOnOpen =
                                            rememberComposerFocusOnOpen(
                                                policy = true,
                                                shown = true,
                                                yieldsToSideThread = false,
                                                opensOnMessage = true,
                                            )
                                        ThreadComposerContent(
                                            surface = ThreadComposerSurface(channelId = "c-ime"),
                                            state = composerState,
                                            onEvent = { _, _, _, _ -> },
                                            onKeyboardHeightChange = {},
                                            contentPaddingTop = 0.dp,
                                            contentPaddingBottom = 0.dp,
                                            attachmentPreviewEnabled = true,
                                            autofocusOnOpen = focusOnOpen,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            waitForIdle()
            val composeRoot = checkNotNull(hostComposeView)
            runOnIdle { dispatchWindowFocus(composeRoot, true) }
            waitForIdle()
            assertFalse(coordinator.anyComposerFocused)
            assertFalse(composeRoot.onCheckIsTextEditor())

            // 1. FocusOnly focuses the composer WITHOUT starting an input session or raising IME.
            runOnIdle {
                composerState =
                    composerState.copy(
                        focusRequestNonce = composerState.focusRequestNonce + 1,
                        focusRequestMeta =
                            ThreadComposerFocusRequestMeta(
                                source = RaftFocusRequestSource.User("focus_only_test"),
                                keyboardMode = ThreadComposerFocusRequestKeyboardMode.FocusOnly,
                            ),
                    )
            }
            waitForIdle()
            assertTrue(coordinator.anyComposerFocused, "FocusOnly must focus composer")
            assertFalse(composeRoot.onCheckIsTextEditor(), "FocusOnly must not start input session")
            assertNull(composeRoot.onCreateInputConnection(EditorInfo()))
            assertEquals(0, keyboard.shown)

            // 2. Explicit RaiseKeyboard (e.g. Reply / Quote) while ALREADY focused arms
            // showKeyboardOnFocus = true, starts the input session, opens IME, and disarms cleanly.
            runOnIdle {
                composerState =
                    composerState.copy(
                        focusRequestNonce = composerState.focusRequestNonce + 1,
                        focusRequestMeta =
                            ThreadComposerFocusRequestMeta(
                                source = RaftFocusRequestSource.User("append_quote"),
                                keyboardMode = ThreadComposerFocusRequestKeyboardMode.RaiseKeyboard,
                            ),
                    )
            }
            repeat(4) { mainClock.advanceTimeByFrame() }
            waitForIdle()
            assertTrue(coordinator.anyComposerFocused)
            assertTrue(composeRoot.onCheckIsTextEditor(), "already-focused RaiseKeyboard must start input session")
            assertNotNull(composeRoot.onCreateInputConnection(EditorInfo()))
            assertEquals(1, keyboard.shown)

            // 3. ThreadComposerKeyboardHandoff trip to image editor and back: puts keyboard down
            // while editor is open, then brings focus + input session + keyboard back on return.
            val activeHandoff = assertNotNull(handoff)
            runOnIdle {
                activeHandoff.leave(keyboardOpen = true)
                activeHandoff.pageOpened()
                activeHandoff.settle()
                covered = true
            }
            waitForIdle()
            assertFalse(coordinator.anyComposerFocused)
            assertFalse(composeRoot.onCheckIsTextEditor())
            assertEquals(1, keyboard.hidden)

            runOnIdle { covered = false }
            repeat(4) { mainClock.advanceTimeByFrame() }
            waitForIdle()
            assertTrue(coordinator.anyComposerFocused, "handoff return must refocus composer")
            assertTrue(composeRoot.onCheckIsTextEditor(), "handoff return must restart input session")
            assertNotNull(composeRoot.onCreateInputConnection(EditorInfo()))
            assertEquals(2, keyboard.shown)
        }

    private fun dispatchWindowFocus(composeRoot: View, hasFocus: Boolean) {
        val viewRoot = checkNotNull(composeRoot.rootView.parent) { "composeRoot must be attached to a ViewRootImpl" }
        Shadow.extract<ShadowViewRootImpl>(viewRoot).callWindowFocusChanged(hasFocus)
    }

    private companion object {
        const val MESSAGE_LIST = "message_list"
        const val COMPOSER = "composer"
        const val MENU = "menu"
        const val AUTOFOCUS_FIELD = "autofocus_field"
        const val DIALOG_FIELD = "dialog_field"
    }
}
