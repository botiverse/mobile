package build.raft.app

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import build.raft.designsystem.focus.LocalRaftAutofocusGuard
import build.raft.designsystem.focus.RaftAutofocusGuard
import build.raft.designsystem.focus.RaftFocusCoordinator
import build.raft.designsystem.focus.RaftFocusRegion
import build.raft.designsystem.focus.RaftFocusRegionHost
import build.raft.designsystem.focus.currentRaftFocusCoordinator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Opening a conversation focuses its composer under the policy (ComposerAutofocusPolicy.kt) on a
 * real composition: the editors are the app's [TextField] (the composer's own editor, marked as a
 * conversation composer the way ThreadComposerContent marks it), the effect is the one the composer
 * leaf runs, and the Android hardware-keyboard signal comes from the Robolectric configuration
 * qualifiers. See AGENTS.md "Compose UI tests".
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposerAutofocusOnOpenUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    /**
     * ThreadComposerContentLeaf's editor + its open effect, reduced to the focus wiring. [focus] is
     * the page's (ThreadPage remembers it above the composer, so it outlives a rebuilt composer).
     */
    @Composable
    private fun Composer(tag: String, conversation: String, focus: ComposerFocusOnOpen, outcomes: MutableList<ComposerAutofocusOutcome>? = null) {
        val requester = remember { FocusRequester() }
        CompositionLocalProvider(LocalRaftEditorIsConversationComposer provides true) {
            TextField(
                modifier = Modifier.testTag(tag).composerReportsFocus(focus),
                value = "",
                autoFocus = false,
                onValueChange = {},
                focusRequester = requester,
            )
        }
        ComposerAutofocusOnOpenEffect(
            conversation = conversation,
            focus = focus,
            focusRequester = requester,
            onOutcome = { outcomes?.add(it) },
        )
    }

    /** A conversation page's focus state with the policy on and nothing beside it. */
    @Composable
    private fun pageFocus(
        policy: Boolean = true,
        yieldsToSideThread: Boolean = false,
        userInitiated: Boolean = false,
    ): ComposerFocusOnOpen =
        rememberComposerFocusOnOpen(
            policy = policy,
            shown = true,
            yieldsToSideThread = yieldsToSideThread,
            userInitiated = userInitiated,
        )

    /** Something the user can move the keyboard focus to that is not a text field (a row, a button). */
    @Composable
    private fun FocusableControl(tag: String, requester: FocusRequester) {
        Box(Modifier.testTag(tag).size(24.dp).focusRequester(requester).focusable())
    }

    /** A conversation page opened with the platform policy, as ThreadPage decides it. */
    private fun ComposeUiTest.openConversationWithPlatformPolicy(
        opensOnMessage: Boolean = false,
        outcomes: MutableList<ComposerAutofocusOutcome>? = null,
    ): String {
        var seen = ""
        setContent {
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            seen = "keyboard=${configuration.keyboard} hardKeyboardHidden=${configuration.hardKeyboardHidden}"
            val focus =
                rememberComposerFocusOnOpen(
                    policy = rememberComposerAutofocusOnOpen(),
                    shown = true,
                    yieldsToSideThread = false,
                    // As ThreadPage decides it: the route's focused message, on this (non-desktop) host.
                    opensOnMessage =
                        composerAutofocusSkipsMessageOpen(desktopHost = raftPlatformHasFinePointer(), opensOnMessage = opensOnMessage),
                )
            Composer(COMPOSER, conversation = "ch-1", focus = focus, outcomes = outcomes)
        }
        mainClock.advanceTimeByFrame()
        waitForIdle()
        return seen
    }

    @Test
    @Config(qualifiers = "keysexposed-qwerty")
    fun hardwareKeyboardFocusesTheComposerOnOpen() =
        runRobolectricComposeUiTest {
            val seen = openConversationWithPlatformPolicy()
            assertTrue(seen.startsWith("keyboard=${Configuration.KEYBOARD_QWERTY} "), seen)
            onNodeWithTag(COMPOSER).assertIsFocused()
        }

    @Test
    fun openDialogOrMenuKeepsTheFocus() =
        runRobolectricComposeUiTest {
            var surfaceOpen by mutableStateOf(true)
            var opened by mutableStateOf(false)
            val outcomes = mutableListOf<ComposerAutofocusOutcome>()
            setContent {
                LocalRaftAutofocusGuard.current.HoldOpenSurface(enabled = surfaceOpen)
                if (opened) Composer(COMPOSER, conversation = "ch-1", focus = pageFocus(), outcomes = outcomes)
            }
            runOnIdle { opened = true }
            waitForIdle()
            onNodeWithTag(COMPOSER).assertIsNotFocused()
            assertEquals(listOf(ComposerAutofocusOutcome.Blocked), outcomes)
            // Closing it later does not pull the focus in: the request belonged to the open.
            runOnIdle { surfaceOpen = false }
            waitForIdle()
            onNodeWithTag(COMPOSER).assertIsNotFocused()
            assertFalse(RaftAutofocusGuard.Default.blocksAutofocus)
        }

    // raft-design "Moving focus" (#1118), rule 2: only a person's action moves the focus to another
    // part of the page. A keyboard that sleeps and wakes (Magic Keyboard, a Folio folded back)
    // turns the policy off and on; that is not an open. The flip stands in for the configuration
    // change ([rememberRaftHardwareKeyboardConnected] recomposes the page with the new answer).
    @Test
    fun hardwareKeyboardReconnectDoesNotRefocusComposer() =
        runRobolectricComposeUiTest {
            var keyboardConnected by mutableStateOf(true)
            val control = FocusRequester()
            setContent {
                Column {
                    FocusableControl(CONTROL, control)
                    Composer(COMPOSER, conversation = "ch-1", focus = pageFocus(policy = keyboardConnected))
                }
            }
            waitForIdle()
            onNodeWithTag(COMPOSER).assertIsFocused()
            runOnIdle { control.requestFocus() }
            onNodeWithTag(CONTROL).assertIsFocused()

            runOnIdle { keyboardConnected = false }
            waitForIdle()
            runOnIdle { keyboardConnected = true }
            waitForIdle()
            onNodeWithTag(CONTROL).assertIsFocused()
            onNodeWithTag(COMPOSER).assertIsNotFocused()
        }

    // Rule 4: closing the reply thread gives the focus back to the channel's composer only when the
    // focus was in the thread's composer and went with it; moved anywhere else, it stays.
    @Test
    fun sideThreadCloseRestoresOnlyWhenFocusLost() =
        runRobolectricComposeUiTest {
            var panelOpen by mutableStateOf(false)
            val control = FocusRequester()
            setContent {
                Column {
                    FocusableControl(CONTROL, control)
                    Composer(CHANNEL, conversation = "ch-1", focus = pageFocus(yieldsToSideThread = panelOpen))
                    if (panelOpen) Composer(THREAD, conversation = "th-1", focus = pageFocus())
                }
            }
            waitForIdle()
            onNodeWithTag(CHANNEL).assertIsFocused()

            // The user moves on to something else, then closes the thread: the focus stays there.
            runOnIdle { panelOpen = true }
            waitForIdle()
            onNodeWithTag(THREAD).assertIsFocused()
            runOnIdle { control.requestFocus() }
            onNodeWithTag(CONTROL).assertIsFocused()
            runOnIdle { panelOpen = false }
            waitForIdle()
            onNodeWithTag(CONTROL).assertIsFocused()
            onNodeWithTag(CHANNEL).assertIsNotFocused()

            // The focus was in the thread: it went with it, so the channel takes it back.
            runOnIdle { panelOpen = true }
            waitForIdle()
            onNodeWithTag(THREAD).assertIsFocused()
            runOnIdle { panelOpen = false }
            waitForIdle()
            onNodeWithTag(CHANNEL).assertIsFocused()
        }

    // Rule 2 (user navigation): clicking or pressing Enter on a real clickable channel row in
    // ListPane (or clicking it while sticky non-text focus sat in SupportingPane) focuses the
    // newly opened conversation's composer.
    @Test
    fun userClickingOrPressingEnterOnListPaneRowFocusesOpenedConversationComposer() =
        runRobolectricComposeUiTest {
            val listRow = FocusRequester()
            val paneButton = FocusRequester()
            var conversation by mutableStateOf<String?>(null)
            val outcomes = mutableListOf<ComposerAutofocusOutcome>()
            setContent {
                RaftWindowFocusScope {
                    Column {
                        RaftFocusRegionHost(RaftFocusRegion.SupportingPane) {
                            FocusableControl(CONTROL, paneButton)
                        }
                        RaftFocusRegionHost(RaftFocusRegion.ListPane) {
                            Box(
                                Modifier
                                    .testTag(LIST_ROW)
                                    .size(48.dp)
                                    .focusRequester(listRow)
                                    .clickable { conversation = if (conversation == "ch-1") "ch-2" else "ch-1" },
                            )
                        }
                        conversation?.let { active ->
                            RaftFocusRegionHost(RaftFocusRegion.Conversation) {
                                key(active) {
                                    Composer(
                                        tag = COMPOSER,
                                        conversation = active,
                                        focus = pageFocus(userInitiated = true),
                                        outcomes = outcomes,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // Keyboard Enter on a focused ListPane row -> opens ch-1 and moves focus into composer.
            runOnIdle { listRow.requestFocus() }
            waitForIdle()
            onNodeWithTag(LIST_ROW).assertIsFocused()
            onNodeWithTag(LIST_ROW).performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            onNodeWithTag(COMPOSER).assertIsFocused()

            // Sticky non-text focus in SupportingPane + mouse click on ListPane row -> opens ch-2 and focuses composer.
            runOnIdle { paneButton.requestFocus() }
            waitForIdle()
            onNodeWithTag(CONTROL).assertIsFocused()
            onNodeWithTag(LIST_ROW).performTouchInput { click() }
            waitForIdle()
            onNodeWithTag(COMPOSER).assertIsFocused()
            assertEquals(listOf(ComposerAutofocusOutcome.Focused, ComposerAutofocusOutcome.Focused), outcomes)
        }

    // Rule 3: real pointer touch input while the open request is waiting for a closing menu/surface
    // to settle advances interactionEpoch via raftObserveFocusInteractions and cancels the open.
    @Test
    fun realPointerInteractionDuringSettleCancelsPendingComposerAutofocus() =
        runRobolectricComposeUiTest {
            var menuOpen by mutableStateOf(true)
            var opened by mutableStateOf(false)
            val outcomes = mutableListOf<ComposerAutofocusOutcome>()
            var coordinator: RaftFocusCoordinator? = null
            setContent {
                RaftWindowFocusScope {
                    coordinator = currentRaftFocusCoordinator()
                    Column {
                        LocalRaftAutofocusGuard.current.HoldOpenSurface(enabled = menuOpen)
                        Box(Modifier.testTag(TOUCH_TARGET).size(48.dp))
                        if (opened) {
                            RaftFocusRegionHost(RaftFocusRegion.Conversation) {
                                Composer(COMPOSER, conversation = "ch-1", focus = pageFocus(userInitiated = true), outcomes = outcomes)
                            }
                        }
                    }
                }
            }
            mainClock.autoAdvance = false
            runOnIdle { opened = true }
            val activeCoordinator = checkNotNull(coordinator)
            var warmup = 0
            while (activeCoordinator.pendingTicketCount == 0 && warmup < 6) {
                mainClock.advanceTimeByFrame()
                runOnIdle {}
                warmup += 1
            }
            assertTrue(
                activeCoordinator.pendingTicketCount > 0,
                "open request ticket must be queued before user interaction interrupts settle",
            )
            assertTrue(outcomes.isEmpty(), "open request should still be settling while menu is open")
            val epochBeforeClick = activeCoordinator.interactionEpoch
            onNodeWithTag(TOUCH_TARGET).performTouchInput { click() }
            assertTrue(
                activeCoordinator.interactionEpoch > epochBeforeClick,
                "real pointer down inside RaftWindowFocusScope should advance interactionEpoch",
            )
            runOnIdle { menuOpen = false }
            mainClock.autoAdvance = true
            waitForIdle()
            onNodeWithTag(COMPOSER).assertIsNotFocused()
            assertEquals(listOf(ComposerAutofocusOutcome.Cancelled), outcomes)
        }

    private companion object {
        const val COMPOSER = "composer"
        const val CHANNEL = "channel_composer"
        const val THREAD = "thread_composer"
        const val SEARCH = "search"
        const val CONTROL = "control"
        const val LIST_ROW = "list_row"
        const val TOUCH_TARGET = "touch_target"
        const val MODAL_FIELD = "modal_field"
    }
}
