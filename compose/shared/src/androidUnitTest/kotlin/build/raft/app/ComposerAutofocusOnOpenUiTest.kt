package build.raft.app

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import build.raft.designsystem.focus.RaftAutofocusGuard
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

    /** ThreadComposerContentLeaf's editor + its open effect, reduced to the focus wiring. */
    @Composable
    private fun Composer(tag: String, conversation: String, autofocusOnOpen: Boolean, outcomes: MutableList<ComposerAutofocusOutcome>? = null) {
        val requester = remember { FocusRequester() }
        CompositionLocalProvider(LocalRaftEditorIsConversationComposer provides true) {
            TextField(
                modifier = Modifier.testTag(tag),
                value = "",
                autoFocus = false,
                onValueChange = {},
                focusRequester = requester,
            )
        }
        ComposerAutofocusOnOpenEffect(
            key = conversation,
            enabled = autofocusOnOpen,
            focusRequester = requester,
            onOutcome = { outcomes?.add(it) },
        )
    }

    /** A conversation page opened with the platform policy, as ThreadPage decides it. */
    private fun ComposeUiTest.openConversationWithPlatformPolicy(): String {
        var seen = ""
        setContent {
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            seen = "keyboard=${configuration.keyboard} hardKeyboardHidden=${configuration.hardKeyboardHidden}"
            Composer(COMPOSER, conversation = "ch-1", autofocusOnOpen = rememberComposerAutofocusOnOpen())
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
                    Composer(CHANNEL, conversation = "ch-1", autofocusOnOpen = !panelOpen)
                    if (panelOpen) Composer(THREAD, conversation = "th-1", autofocusOnOpen = true)
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
                key(conversation) { Composer(conversation, conversation = conversation, autofocusOnOpen = true) }
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
                    if (opened) Composer(COMPOSER, conversation = "ch-1", autofocusOnOpen = true, outcomes = outcomes)
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
                if (opened) Composer(COMPOSER, conversation = "ch-1", autofocusOnOpen = true, outcomes = outcomes)
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
                if (opened) Composer(COMPOSER, conversation = "ch-1", autofocusOnOpen = true)
            }
            mainClock.autoAdvance = false
            runOnIdle { opened = true }
            mainClock.advanceTimeByFrame()
            runOnIdle { menuOpen = false }
            mainClock.autoAdvance = true
            waitForIdle()
            onNodeWithTag(COMPOSER).assertIsFocused()
        }

    private companion object {
        const val COMPOSER = "composer"
        const val CHANNEL = "channel_composer"
        const val THREAD = "thread_composer"
        const val SEARCH = "search"
    }
}
