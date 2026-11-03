package build.raft.app

import android.view.WindowManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * artin 2026-11-03: with the keyboard up, long-pressing a message opened its menu and closed the
 * keyboard. The page's outside-gesture dismissal ([RaftInputFocusDismissHost]) cleared the
 * composer's focus at the long-press's release, and on Android the menu's dialog window took the
 * keyboard. Composed the way ThreadPage does it: the page host, the app's [TextField] as the
 * composer, a message row that opens the menu on long-press, and the menu with
 * [ThreadMessageMenuKeepsComposerFocus] in the page and [RaftModalAnchoredPopover] keeping the
 * host keyboard. See AGENTS.md "Compose UI tests".
 */
@OptIn(ExperimentalTestApi::class, ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadMessageMenuKeepsKeyboardUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun longPressingAMessageKeepsTheComposerFocusedUnderItsMenu() =
        runRobolectricComposeUiTest {
            val composerFocus = FocusRequester()
            var menuMessageId by mutableStateOf<String?>(null)
            setContent {
                RaftInputFocusDismissHost { focusDismissModifier ->
                    Column(focusDismissModifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .testTag(MESSAGE)
                                .fillMaxWidth()
                                .height(120.dp)
                                .combinedClickable(onClick = {}, onLongClick = { menuMessageId = "m-1" })
                        )
                        TextField(
                            modifier = Modifier.testTag(COMPOSER).fillMaxWidth(),
                            value = "",
                            autoFocus = false,
                            onValueChange = {},
                            focusRequester = composerFocus,
                        )
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
            runOnIdle { composerFocus.requestFocus() }
            waitForIdle()
            onNodeWithTag(COMPOSER).assertIsFocused()

            onNodeWithTag(MESSAGE).performTouchInput { longClick() }
            // The page's dismissal runs a couple of frames after the release.
            repeat(4) { mainClock.advanceTimeByFrame() }
            waitForIdle()

            assertEquals("m-1", menuMessageId)
            onNodeWithTag(MENU).assertExists()
            onNodeWithTag(COMPOSER).assertIsFocused()
            val menuWindow = assertNotNull(ShadowDialog.getLatestDialog()?.window)
            assertTrue(
                menuWindow.attributes.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM != 0,
                "the menu's window leaves the keyboard to the composer",
            )
        }

    private companion object {
        const val COMPOSER = "composer"
        const val MESSAGE = "message"
        const val MENU = "menu"
    }
}
