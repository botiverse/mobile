package build.raft.app

import android.content.res.Configuration
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.focus.RaftAutofocusGuard
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
    private fun pageFocus(policy: Boolean = true, yieldsToSideThread: Boolean = false): ComposerFocusOnOpen =
        rememberComposerFocusOnOpen(policy = policy, shown = true, yieldsToSideThread = yieldsToSideThread)

    /** Something the user can move the keyboard focus to that is not a text field (a row, a button). */
    @Composable
    private fun FocusableControl(tag: String, requester: FocusRequester) {
        Box(Modifier.testTag(tag).size(24.dp).focusRequester(requester).focusable())
    }

    /** A conversation page opened with the platform policy, as ThreadPage decides it. */
    private fun ComposeUiTest.openConversationWithPlatformPolicy(): String {
        var seen = ""
        setContent {
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            seen = "keyboard=${configuration.keyboard} hardKeyboardHidden=${configuration.hardKeyboardHidden}"
            Composer(COMPOSER, conversation = "ch-1", focus = pageFocus(policy = rememberComposerAutofocusOnOpen()))
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
            assertTrue(RaftAutofocusGuard.otherFieldFocused, "search focus is reported to the guard")

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
                RaftAutofocusGuard.HoldOpenSurface(enabled = surfaceOpen)
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
            assertFalse(RaftAutofocusGuard.blocksAutofocus)
        }

    @Test
    fun aMenuClosingRightAfterTheOpenStillLetsTheComposerTakeFocus() =
        runRobolectricComposeUiTest {
            // The menu item that opened the conversation closes its menu a frame later.
            var menuOpen by mutableStateOf(true)
            var opened by mutableStateOf(false)
            setContent {
                RaftAutofocusGuard.HoldOpenSurface(enabled = menuOpen)
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
            assertFalse(RaftAutofocusGuard.otherFieldFocused, "a field inside the composer never blocks the composer")

            runOnIdle { search.requestFocus() }
            onNodeWithTag(SEARCH).assertIsFocused()
            assertTrue(RaftAutofocusGuard.otherFieldFocused, "the design-system field reports its focus")

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
                    RaftAutofocusGuard.HoldOpenSurface()
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

    private companion object {
        const val COMPOSER = "composer"
        const val CHANNEL = "channel_composer"
        const val THREAD = "thread_composer"
        const val SEARCH = "search"
        const val CONTROL = "control"
    }
}
