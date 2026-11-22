package build.raft.app

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftWindowDialog
import build.raft.designsystem.focus.LocalRaftAutofocusGuard
import build.raft.designsystem.focus.LocalRaftFocusModalToken
import build.raft.designsystem.focus.LocalRaftFocusRegion
import build.raft.designsystem.focus.RaftAutofocusGuard
import build.raft.designsystem.focus.RaftFocusCoordinator
import build.raft.designsystem.focus.RaftFocusDecision
import build.raft.designsystem.focus.RaftFocusRegion
import build.raft.designsystem.focus.RaftFocusRegionHost
import build.raft.designsystem.focus.RaftFocusRequestSource
import build.raft.designsystem.focus.currentRaftFocusCoordinator
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
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

    // 1.24.0 on a cloud phone (it reports a keyboard, and its IME still came up): opening a message
    // link focused the composer about a second after the list had scrolled to the message, and the
    // soft keyboard pushed the message off the screen. An open that lands on one message (a link,
    // Activity, a search result) leaves the composer alone on a phone or tablet.
    @Test
    @Config(qualifiers = "keysexposed-qwerty")
    fun openOnAMessageLeavesTheComposerUnfocusedEvenWithAHardwareKeyboard() =
        runRobolectricComposeUiTest {
            val outcomes = mutableListOf<ComposerAutofocusOutcome>()
            val seen = openConversationWithPlatformPolicy(opensOnMessage = true, outcomes = outcomes)
            assertTrue(seen.startsWith("keyboard=${Configuration.KEYBOARD_QWERTY} "), seen)
            onNodeWithTag(COMPOSER).assertIsNotFocused()
            assertEquals(listOf(ComposerAutofocusOutcome.OpensOnMessage), outcomes)
        }

    @Test
    @Config(qualifiers = "keyshidden-nokeys")
    fun touchOnlyLeavesTheComposerUnfocused() =
        runRobolectricComposeUiTest {
            openConversationWithPlatformPolicy()
            onNodeWithTag(COMPOSER).assertIsNotFocused()
        }

    @Test
    fun androidHardwareKeyboardSignal() {
        assertTrue(raftAndroidHardwareKeyboardConnected(Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_NO))
        assertTrue(raftAndroidHardwareKeyboardConnected(Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_UNDEFINED))
        assertFalse(raftAndroidHardwareKeyboardConnected(Configuration.KEYBOARD_NOKEYS, Configuration.HARDKEYBOARDHIDDEN_NO))
        assertFalse(raftAndroidHardwareKeyboardConnected(Configuration.KEYBOARD_UNDEFINED, Configuration.HARDKEYBOARDHIDDEN_UNDEFINED))
        // A keyboard folded away (or the emulator's hidden one) is not a keyboard the user can type on.
        assertFalse(raftAndroidHardwareKeyboardConnected(Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_YES))
    }

    @Test
    fun threadPanelTakesTheFocusAndGivesItBackWhenItCloses() =
        runRobolectricComposeUiTest {
            var panelOpen by mutableStateOf(false)
            setContent {
                Column {
                    // ThreadPage: the channel yields while its reply thread is shown beside it.
                    Composer(CHANNEL, conversation = "ch-1", focus = pageFocus(yieldsToSideThread = panelOpen))
                    if (panelOpen) Composer(THREAD, conversation = "th-1", focus = pageFocus())
                }
            }
            waitForIdle()
            onNodeWithTag(CHANNEL).assertIsFocused()

            runOnIdle { panelOpen = true }
            waitForIdle()
            onNodeWithTag(THREAD).assertIsFocused()
            onNodeWithTag(CHANNEL).assertIsNotFocused()

            runOnIdle { panelOpen = false }
            waitForIdle()
            onNodeWithTag(CHANNEL).assertIsFocused()
        }

    @Test
    fun switchingConversationsFocusesTheNewOne() =
        runRobolectricComposeUiTest {
            var conversation by mutableStateOf("ch-1")
            setContent {
                // Each conversation composes its own composer (ThreadComposerContent is keyed by instance).
                val focus = pageFocus()
                key(conversation) { Composer(conversation, conversation = conversation, focus = focus) }
            }
            waitForIdle()
            onNodeWithTag("ch-1").assertIsFocused()
            runOnIdle { conversation = "dm-2" }
            waitForIdle()
            onNodeWithTag("dm-2").assertIsFocused()
        }

    @Test
    fun anotherFocusedFieldKeepsItsFocus() =
        runRobolectricComposeUiTest {
            val searchRequester = FocusRequester()
            var opened by mutableStateOf(false)
            val outcomes = mutableListOf<ComposerAutofocusOutcome>()
            setContent {
                Column {
                    TextField(
                        modifier = Modifier.testTag(SEARCH),
                        value = "",
                        autoFocus = false,
                        onValueChange = {},
                        focusRequester = searchRequester,
                    )
                    if (opened) Composer(COMPOSER, conversation = "ch-1", focus = pageFocus(), outcomes = outcomes)
                }
            }
            runOnIdle { searchRequester.requestFocus() }
            onNodeWithTag(SEARCH).assertIsFocused()
            assertTrue(RaftAutofocusGuard.Default.otherFieldFocused, "search focus is reported to the guard")

            runOnIdle { opened = true }
            waitForIdle()
            onNodeWithTag(SEARCH).assertIsFocused()
            onNodeWithTag(COMPOSER).assertIsNotFocused()
            assertEquals(listOf(ComposerAutofocusOutcome.Blocked), outcomes)
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

    /** Android's app windows (one per server) each keep their own guard ([RaftWindowFocusScope]). */
    @Test
    fun eachWindowKeepsItsOwnAutofocusGuard() =
        runRobolectricComposeUiTest {
            var dialogInFirstWindow by mutableStateOf(true)
            var first: RaftAutofocusGuard? = null
            var second: RaftAutofocusGuard? = null
            setContent {
                Column {
                    RaftWindowFocusScope {
                        first = LocalRaftAutofocusGuard.current
                        LocalRaftAutofocusGuard.current.HoldOpenSurface(enabled = dialogInFirstWindow)
                    }
                    RaftWindowFocusScope { second = LocalRaftAutofocusGuard.current }
                }
            }
            waitForIdle()
            assertTrue(checkNotNull(first).blocksAutofocus, "the window with the dialog keeps its own composer from the focus")
            assertFalse(checkNotNull(second).blocksAutofocus, "another window's dialog does not")
            assertFalse(RaftAutofocusGuard.Default.blocksAutofocus, "nor does it reach the app-wide default")
            runOnIdle { dialogInFirstWindow = false }
            waitForIdle()
            assertFalse(checkNotNull(first).blocksAutofocus)
        }

    @Test
    fun aDialogInAnotherWindowDoesNotKeepTheComposerFromTheFocus() =
        runRobolectricComposeUiTest {
            var opened by mutableStateOf(false)
            val outcomes = mutableListOf<ComposerAutofocusOutcome>()
            setContent {
                Column {
                    RaftWindowFocusScope { LocalRaftAutofocusGuard.current.HoldOpenSurface() }
                    RaftWindowFocusScope {
                        if (opened) Composer(COMPOSER, conversation = "ch-1", focus = pageFocus(), outcomes = outcomes)
                    }
                }
            }
            runOnIdle { opened = true }
            waitForIdle()
            onNodeWithTag(COMPOSER).assertIsFocused()
            assertEquals(listOf(ComposerAutofocusOutcome.Focused), outcomes)
        }

    @Test
    fun aMenuClosingRightAfterTheOpenStillLetsTheComposerTakeFocus() =
        runRobolectricComposeUiTest {
            // The menu item that opened the conversation closes its menu a frame later.
            var menuOpen by mutableStateOf(true)
            var opened by mutableStateOf(false)
            setContent {
                LocalRaftAutofocusGuard.current.HoldOpenSurface(enabled = menuOpen)
                if (opened) Composer(COMPOSER, conversation = "ch-1", focus = pageFocus())
            }
            mainClock.autoAdvance = false
            runOnIdle { opened = true }
            mainClock.advanceTimeByFrame()
            runOnIdle { menuOpen = false }
            mainClock.autoAdvance = true
            waitForIdle()
            onNodeWithTag(COMPOSER).assertIsFocused()
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

    // The composer's instance id swaps from the page's to the conversation's when it finishes
    // loading (ThreadPage `instanceId.ifBlank { renderedPayload.instanceId }`), which rebuilds the
    // composer. The open belongs to the conversation, so a field the user went to keeps the focus
    // even when it does not report to the guard.
    @Test
    fun composerInstanceSwapOnLoadKeepsFocusInPaneSearch() =
        runRobolectricComposeUiTest {
            var instance by mutableStateOf("page-1")
            val search = FocusRequester()
            val conversation = ThreadComposerSurface(channelId = "ch-1").composerAutofocusConversationKey()!!
            setContent {
                val focus = pageFocus()
                Column {
                    BasicTextField(value = "", onValueChange = {}, modifier = Modifier.testTag(SEARCH).focusRequester(search))
                    key(instance) { Composer(COMPOSER, conversation = conversation, focus = focus) }
                }
            }
            waitForIdle()
            onNodeWithTag(COMPOSER).assertIsFocused()
            runOnIdle { search.requestFocus() }
            onNodeWithTag(SEARCH).assertIsFocused()

            runOnIdle { instance = "ch-1" }
            waitForIdle()
            onNodeWithTag(SEARCH).assertIsFocused()
            onNodeWithTag(COMPOSER).assertIsNotFocused()
        }

    // Rule 1: a design-system field (the Members search beside the conversation, Settings and
    // Artifacts search) holds its focus against the open request; inside a composer it opts out.
    @Test
    fun designSystemTextFieldReportsFocusToGuard() =
        runRobolectricComposeUiTest {
            val search = FocusRequester()
            val composerOwnField = FocusRequester()
            var opened by mutableStateOf(false)
            val outcomes = mutableListOf<ComposerAutofocusOutcome>()
            setContent {
                RaftTheme(appearance = RaftAppearance.Light) {
                    Column {
                        RaftTextField(value = "", onValueChange = {}, modifier = Modifier.testTag(SEARCH).focusRequester(search))
                        CompositionLocalProvider(LocalRaftEditorIsConversationComposer provides true) {
                            RaftTextField(value = "", onValueChange = {}, modifier = Modifier.focusRequester(composerOwnField))
                        }
                        if (opened) Composer(COMPOSER, conversation = "ch-1", focus = pageFocus(), outcomes = outcomes)
                    }
                }
            }
            runOnIdle { composerOwnField.requestFocus() }
            waitForIdle()
            assertFalse(RaftAutofocusGuard.Default.otherFieldFocused, "a field inside the composer never blocks the composer")

            runOnIdle { search.requestFocus() }
            onNodeWithTag(SEARCH).assertIsFocused()
            assertTrue(RaftAutofocusGuard.Default.otherFieldFocused, "the design-system field reports its focus")

            runOnIdle { opened = true }
            waitForIdle()
            onNodeWithTag(SEARCH).assertIsFocused()
            onNodeWithTag(COMPOSER).assertIsNotFocused()
            assertEquals(listOf(ComposerAutofocusOutcome.Blocked), outcomes)
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

    // artin's case: the search panel (a RaftWindowDialog, which holds the guard, with a field that
    // reports) is open while the conversation behind it loads and its composer is rebuilt.
    @Test
    fun searchOverlayOpenDuringConversationLoadKeepsSearchFocus() =
        runRobolectricComposeUiTest {
            var loaded by mutableStateOf(false)
            var instance by mutableStateOf("page-1")
            val search = FocusRequester()
            val outcomes = mutableListOf<ComposerAutofocusOutcome>()
            setContent {
                val focus = pageFocus()
                Column {
                    LocalRaftAutofocusGuard.current.HoldOpenSurface()
                    TextField(
                        modifier = Modifier.testTag(SEARCH),
                        value = "",
                        autoFocus = false,
                        onValueChange = {},
                        focusRequester = search,
                    )
                    if (loaded) key(instance) { Composer(COMPOSER, conversation = "ch-1", focus = focus, outcomes = outcomes) }
                }
            }
            runOnIdle { search.requestFocus() }
            onNodeWithTag(SEARCH).assertIsFocused()

            runOnIdle { loaded = true }
            waitForIdle()
            runOnIdle { instance = "ch-1" }
            waitForIdle()
            onNodeWithTag(SEARCH).assertIsFocused()
            onNodeWithTag(COMPOSER).assertIsNotFocused()
            assertEquals(listOf(ComposerAutofocusOutcome.Blocked), outcomes)
        }

    // Audit S6 / rule 2: keyboard Tab onto a non-text button or row in another region (Navigation,
    // TopBar, SupportingPane) keeps its focus when a passive default-landing conversation opens.
    @Test
    fun nonTextKeyboardFocusInAnotherRegionKeepsFocusWhenConversationOpens() =
        runRobolectricComposeUiTest {
            val navButton = FocusRequester()
            var opened by mutableStateOf(false)
            val outcomes = mutableListOf<ComposerAutofocusOutcome>()
            setContent {
                RaftWindowFocusScope {
                    Column {
                        RaftFocusRegionHost(RaftFocusRegion.Navigation) {
                            FocusableControl(CONTROL, navButton)
                        }
                        if (opened) {
                            RaftFocusRegionHost(RaftFocusRegion.Conversation) {
                                Composer(COMPOSER, conversation = "ch-1", focus = pageFocus(userInitiated = false), outcomes = outcomes)
                            }
                        }
                    }
                }
            }
            runOnIdle { navButton.requestFocus() }
            waitForIdle()
            onNodeWithTag(CONTROL).assertIsFocused()

            runOnIdle { opened = true }
            waitForIdle()
            onNodeWithTag(CONTROL).assertIsFocused()
            onNodeWithTag(COMPOSER).assertIsNotFocused()
            assertEquals(listOf(ComposerAutofocusOutcome.Blocked), outcomes)
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

    // Rule 3 + S6: focus moving to a non-text control in another pane while the open request is
    // settling cancels the open request and keeps focus on that control.
    @Test
    fun focusMoveToNonTextControlDuringSettleCancelsPendingComposerAutofocus() =
        runRobolectricComposeUiTest {
            var menuOpen by mutableStateOf(true)
            var opened by mutableStateOf(false)
            val initialControl = FocusRequester()
            val paneControl = FocusRequester()
            val outcomes = mutableListOf<ComposerAutofocusOutcome>()
            var coordinator: RaftFocusCoordinator? = null
            setContent {
                RaftWindowFocusScope {
                    coordinator = currentRaftFocusCoordinator()
                    Column {
                        LocalRaftAutofocusGuard.current.HoldOpenSurface(enabled = menuOpen)
                        // First focusable outside SupportingPane so Robolectric's non-touch default
                        // focus lands here, leaving CONTROL unfocused until focus explicitly moves.
                        Box(Modifier.size(1.dp).focusRequester(initialControl).focusable())
                        RaftFocusRegionHost(RaftFocusRegion.SupportingPane) {
                            FocusableControl(CONTROL, paneControl)
                        }
                        if (opened) {
                            RaftFocusRegionHost(RaftFocusRegion.Conversation) {
                                Composer(COMPOSER, conversation = "ch-1", focus = pageFocus(userInitiated = true), outcomes = outcomes)
                            }
                        }
                    }
                }
            }
            runOnIdle { initialControl.requestFocus() }
            waitForIdle()
            onNodeWithTag(CONTROL).assertIsNotFocused()
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
                "open request ticket must be queued before focus move interrupts settle",
            )
            assertTrue(outcomes.isEmpty(), "open request should still be settling while menu is open")
            val focusEpochBeforeMove = activeCoordinator.focusEpoch
            runOnIdle {
                paneControl.requestFocus()
                menuOpen = false
            }
            assertTrue(
                activeCoordinator.focusEpoch > focusEpochBeforeMove,
                "moving focus into SupportingPane should advance focusEpoch",
            )
            mainClock.autoAdvance = true
            waitForIdle()
            onNodeWithTag(CONTROL).assertIsFocused()
            onNodeWithTag(COMPOSER).assertIsNotFocused()
            assertEquals(listOf(ComposerAutofocusOutcome.Cancelled), outcomes)
        }

    // Rule 4 (positive + negative inside RaftWindowFocusScope + RaftFocusRegionHost): closing the
    // side thread restores focus to the channel composer when focus fell to nowhere, and leaves it
    // alone when focus moved to a non-text control in another pane on the same turn.
    @Test
    fun sideThreadCloseRestoresOnlyWhenLostInsideWindowFocusScope() =
        runRobolectricComposeUiTest {
            var panelOpen by mutableStateOf(false)
            val control = FocusRequester()
            setContent {
                RaftWindowFocusScope {
                    Column {
                        RaftFocusRegionHost(RaftFocusRegion.SupportingPane) {
                            FocusableControl(CONTROL, control)
                        }
                        RaftFocusRegionHost(RaftFocusRegion.Conversation) {
                            Composer(
                                tag = CHANNEL,
                                conversation = "ch-1",
                                focus = pageFocus(yieldsToSideThread = panelOpen, userInitiated = true),
                            )
                        }
                        if (panelOpen) {
                            RaftFocusRegionHost(RaftFocusRegion.SideThread) {
                                Composer(
                                    tag = THREAD,
                                    conversation = "th-1",
                                    focus = pageFocus(userInitiated = true),
                                )
                            }
                        }
                    }
                }
            }
            waitForIdle()
            onNodeWithTag(CHANNEL).assertIsFocused()

            // Positive restore: side thread closes while its composer held focus -> focus returns to channel.
            runOnIdle { panelOpen = true }
            waitForIdle()
            onNodeWithTag(THREAD).assertIsFocused()
            runOnIdle { panelOpen = false }
            waitForIdle()
            onNodeWithTag(CHANNEL).assertIsFocused()

            // Negative restore: focus moves to a non-text control on the same turn the thread closes -> stays on control.
            runOnIdle { panelOpen = true }
            waitForIdle()
            onNodeWithTag(THREAD).assertIsFocused()
            runOnIdle {
                control.requestFocus()
                panelOpen = false
            }
            waitForIdle()
            onNodeWithTag(CONTROL).assertIsFocused()
            onNodeWithTag(CHANNEL).assertIsNotFocused()
        }

    // Multi-window isolation: a focused non-text control or user interaction in window 1 never
    // blocks or cancels composer autofocus in window 2.
    @Test
    fun windowScopeIsolatesFocusCoordinatorEpochsAcrossWindows() =
        runRobolectricComposeUiTest {
            val firstWindowControl = FocusRequester()
            var secondOpened by mutableStateOf(false)
            val outcomes = mutableListOf<ComposerAutofocusOutcome>()
            var firstCoordinator: RaftFocusCoordinator? = null
            setContent {
                Column {
                    RaftWindowFocusScope {
                        firstCoordinator = currentRaftFocusCoordinator()
                        RaftFocusRegionHost(RaftFocusRegion.Navigation) {
                            FocusableControl(CONTROL, firstWindowControl)
                        }
                    }
                    RaftWindowFocusScope {
                        if (secondOpened) {
                            RaftFocusRegionHost(RaftFocusRegion.Conversation) {
                                Composer(COMPOSER, conversation = "ch-2", focus = pageFocus(), outcomes = outcomes)
                            }
                        }
                    }
                }
            }
            runOnIdle {
                firstWindowControl.requestFocus()
                checkNotNull(firstCoordinator).recordUserInteraction()
                secondOpened = true
            }
            waitForIdle()
            onNodeWithTag(COMPOSER).assertIsFocused()
            assertEquals(listOf(ComposerAutofocusOutcome.Focused), outcomes)
        }

    @Test
    @Config(qualifiers = "+keysexposed")
    fun systemFocusRequestInsideOpenModalDialogIsNotBlockedByHostConversationRegion() =
        runRobolectricComposeUiTest {
            val hostControl = FocusRequester()
            val modalField = FocusRequester()
            var dialogOpen by mutableStateOf(false)
            var activeCoordinator: RaftFocusCoordinator? = null
            var modalDecision: RaftFocusDecision? = null
            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    RaftWindowFocusScope {
                        activeCoordinator = currentRaftFocusCoordinator()
                        RaftFocusRegionHost(RaftFocusRegion.Conversation) {
                            FocusableControl(CONTROL, hostControl)
                        }
                        if (dialogOpen) {
                            RaftWindowDialog(onDismissRequest = { dialogOpen = false }) {
                                val coordinator = currentRaftFocusCoordinator()
                                val region = LocalRaftFocusRegion.current
                                val modalToken = LocalRaftFocusModalToken.current
                                var draft by remember { mutableStateOf("") }
                                LaunchedEffect(Unit) {
                                    val ticket =
                                        coordinator.request(
                                            targetKey = MODAL_FIELD,
                                            region = region,
                                            source = RaftFocusRequestSource.System("modal_open"),
                                            isText = true,
                                            modalToken = modalToken,
                                        )
                                    val decision = coordinator.evaluate(ticket, consume = true)
                                    modalDecision = decision
                                    if (decision.isAllowed) {
                                        modalField.requestFocus()
                                    }
                                }
                                BasicTextField(
                                    value = draft,
                                    onValueChange = { draft = it },
                                    modifier = Modifier.size(120.dp, 40.dp).focusRequester(modalField).testTag(MODAL_FIELD),
                                )
                            }
                        }
                    }
                }
            }
            runOnIdle { hostControl.requestFocus() }
            waitForIdle()
            assertTrue(RaftFocusRegion.Conversation in checkNotNull(activeCoordinator).activeFocusedRegions)

            runOnIdle { dialogOpen = true }
            waitForIdle()
            assertEquals(RaftFocusDecision.Allow, modalDecision)
            onNodeWithTag(MODAL_FIELD).assertIsFocused()
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
