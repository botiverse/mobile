package build.raft.app

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.nav.RaftStackPageActivity
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
 * dm #119 (Android 1.24): with the keyboard up, picking an image opened the image editor with the
 * keyboard back up over it. Composed the way ThreadPage does it: the app's [TextField] as the
 * composer in a stack page ([RaftStackPageActivity], whose lifecycle cap is what a page opening
 * over it changes), and [rememberThreadComposerKeyboardHandoff] in that page, its return focusing
 * the composer and showing the keyboard as the draft store's RequestFocus does. See AGENTS.md
 * "Compose UI tests".
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadComposerKeyboardHandoffUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private class Keyboard : SoftwareKeyboardController {
        var shown = 0
        var hidden = 0

        override fun show() {
            shown++
        }

        override fun hide() {
            hidden++
        }
    }

    private class Page {
        val composerFocus = FocusRequester()
        val keyboard = Keyboard()
        var covered by mutableStateOf(false)
        var handoff: ThreadComposerKeyboardHandoff? = null
        var returns = 0
        var focused = false
        val focusEvents = mutableListOf<Boolean>()

        fun focusChanged(isFocused: Boolean) {
            focused = isFocused
            focusEvents += isFocused
        }
    }

    private fun ComposeUiTest.showFocusedComposer(page: Page): ThreadComposerKeyboardHandoff {
        setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides page.keyboard) {
                RaftStackPageActivity(resumed = !page.covered) {
                    page.handoff =
                        rememberThreadComposerKeyboardHandoff(key = "thread") {
                            page.returns++
                            page.composerFocus.requestFocus()
                            page.keyboard.show()
                        }
                    // The page's other focusable content, ahead of the composer. Out of touch mode
                    // (Robolectric's) Android gives a cleared window focus straight back to the
                    // first focusable; on a phone, in touch mode, nothing takes it.
                    Box(Modifier.size(1.dp).focusable())
                    TextField(
                        modifier = Modifier.testTag(COMPOSER).fillMaxWidth().onFocusChanged { page.focusChanged(it.isFocused) },
                        value = "",
                        autoFocus = false,
                        onValueChange = {},
                        focusRequester = page.composerFocus,
                    )
                }
            }
        }
        onNodeWithTag(COMPOSER).performClick()
        waitForIdle()
        assertTrue(page.focused, "the composer is not focused")
        return assertNotNull(page.handoff)
    }

    @Test
    fun theKeyboardGoesDownForTheEditorAndComesBackWhenItCloses() =
        runRobolectricComposeUiTest {
            val page = Page()
            val handoff = showFocusedComposer(page)

            // The image button: down before the picker.
            runOnIdle { handoff.leave(keyboardOpen = true) }
            assertFalse(page.focused, "the composer kept the focus: ${page.focusEvents}")
            assertEquals(1, page.keyboard.hidden, "the keyboard stayed up for the picker")

            // The picker returns and the editor opens over the conversation.
            runOnIdle {
                handoff.pageOpened()
                handoff.settle()
                page.covered = true
            }
            waitForIdle()
            assertFalse(page.focused, "the composer kept the focus: ${page.focusEvents}")
            assertEquals(0, page.returns, "the keyboard came back over the editor")

            // The editor closes: the conversation page is resumed again.
            runOnIdle { page.covered = false }
            waitForIdle()
            assertEquals(1, page.returns)
            assertTrue(page.focused, "the composer is not focused")
            assertEquals(ThreadComposerKeyboardHandoff.Phase.Idle, handoff.phase)

            // Once: a later pause and resume of the page does not raise it again.
            runOnIdle { page.covered = true }
            waitForIdle()
            runOnIdle { page.covered = false }
            waitForIdle()
            assertEquals(1, page.returns)
        }

    @Test
    fun aCancelledPickerBringsTheKeyboardBackAtOnce() =
        runRobolectricComposeUiTest {
            val page = Page()
            val handoff = showFocusedComposer(page)

            runOnIdle { handoff.leave(keyboardOpen = true) }
            assertFalse(page.focused, "the composer kept the focus: ${page.focusEvents}")
            runOnIdle { handoff.settle() }
            waitForIdle()

            assertEquals(1, page.returns)
            assertTrue(page.focused, "the composer is not focused")
            assertEquals(1, page.keyboard.shown)
        }

    @Test
    fun aClosedKeyboardStaysClosed() =
        runRobolectricComposeUiTest {
            val page = Page()
            val handoff = showFocusedComposer(page)

            runOnIdle {
                handoff.leave(keyboardOpen = false)
                handoff.pageOpened()
                handoff.settle()
                page.covered = true
            }
            waitForIdle()
            runOnIdle { page.covered = false }
            waitForIdle()

            assertEquals(0, page.keyboard.hidden)
            assertEquals(0, page.returns, "a keyboard the user had put away came back")
        }

    private companion object {
        const val COMPOSER = "composer"
    }
}
