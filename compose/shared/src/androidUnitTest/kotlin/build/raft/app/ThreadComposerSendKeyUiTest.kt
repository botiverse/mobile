package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import build.raft.app.data.RaftAndroidSqlDatabaseContext
import build.raft.app.data.RaftComposerSendKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Hardware-keyboard composer Enter vs Ctrl+Enter / ⌘Enter send preference wired end-to-end into
 * [ThreadComposerContent]:
 * - In [RaftComposerSendKey.Enter] mode: Enter sends, Shift+Enter inserts a newline, no send hint.
 * - In [RaftComposerSendKey.ModEnter] mode: plain Enter inserts a newline, Ctrl+Enter sends, and
 *   the quiet send-key hint renders while composing.
 * - On touch-only mobile without a hardware keyboard (`hardwareKeyboardConnected = false`,
 *   `composerSendKeyEnabled = false`): plain Enter does not send and no send-key hint renders.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi-keysexposed-qwerty")
class ThreadComposerSendKeyUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun hardwareKeyboardEnterModeSendsOnEnterAndNotOnShiftEnter() =
        runRobolectricComposeUiTest {
            val events =
                showComposer(
                    composerSendKey = RaftComposerSendKey.Enter,
                    hardwareKeyboardConnected = true,
                    composerSendKeyEnabled = false,
                )
            val editor = onNode(hasSetTextAction())
            editor.performTextInput("hello")
            waitForIdle()

            onNodeWithTag(ThreadComposerSendKeyHintTestTag, useUnmergedTree = true).assertDoesNotExist()

            editor.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
            waitForIdle()
            assertTrue(events.none { it.first == "send" }, "Shift+Enter must not send: $events")

            editor.performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            assertEquals(1, events.count { it.first == "send" }, "Plain Enter must send in Enter mode: $events")
        }

    @Test
    fun hardwareKeyboardModEnterModeNewlinesOnEnterSendsOnCtrlEnterAndShowsHint() =
        runRobolectricComposeUiTest {
            val events =
                showComposer(
                    composerSendKey = RaftComposerSendKey.ModEnter,
                    hardwareKeyboardConnected = true,
                    composerSendKeyEnabled = false,
                )
            val editor = onNode(hasSetTextAction())
            // Empty composer hides the send hint.
            onNodeWithTag(ThreadComposerSendKeyHintTestTag, useUnmergedTree = true).assertDoesNotExist()

            editor.performTextInput("hello")
            waitForIdle()
            // Composing in ModEnter mode shows the quiet send-key hint.
            onNodeWithTag(ThreadComposerSendKeyHintTestTag, useUnmergedTree = true).assertExists()

            // Plain Enter inserts a newline instead of sending.
            editor.performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            assertTrue(events.none { it.first == "send" }, "Plain Enter must not send in ModEnter mode: $events")

            // Ctrl+Enter sends.
            editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
            waitForIdle()
            assertEquals(1, events.count { it.first == "send" }, "Ctrl+Enter must send in ModEnter mode: $events")
        }

    @Test
    fun touchOnlyMobileDoesNotSendOnPlainEnterWhenSoftEnterIsOff() =
        runRobolectricComposeUiTest {
            val events =
                showComposer(
                    composerSendKey = RaftComposerSendKey.Enter,
                    hardwareKeyboardConnected = false,
                    composerSendKeyEnabled = false,
                )
            val editor = onNode(hasSetTextAction())
            editor.performTextInput("hello")
            waitForIdle()

            onNodeWithTag(ThreadComposerSendKeyHintTestTag, useUnmergedTree = true).assertDoesNotExist()

            editor.performKeyInput { pressKey(Key.Enter) }
            waitForIdle()
            assertTrue(events.none { it.first == "send" }, "Touch-only Enter with softEnterSends=false must not send: $events")
        }

    private fun ComposeUiTest.showComposer(
        composerSendKey: RaftComposerSendKey,
        hardwareKeyboardConnected: Boolean,
        composerSendKeyEnabled: Boolean,
    ): List<Pair<String, String?>> {
        RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        val events = mutableListOf<Pair<String, String?>>()
        setContent {
            val portal = remember { ThreadComposerSuggestionPortalState() }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                CompositionLocalProvider(LocalThreadComposerSuggestionPortal provides portal) {
                    ThreadComposerContent(
                        surface = ThreadComposerSurface(channelId = "c1"),
                        state = ThreadComposerUiState(instanceId = "send-key-test"),
                        onEvent = { action, value, _, _ -> events += action to value },
                        onKeyboardHeightChange = {},
                        contentPaddingTop = 0.dp,
                        contentPaddingBottom = 0.dp,
                        composerSendKeyEnabled = composerSendKeyEnabled,
                        attachmentPreviewEnabled = true,
                        composerSendKey = composerSendKey,
                        hardwareKeyboardConnected = hardwareKeyboardConnected,
                    )
                }
            }
            ThreadComposerSuggestionPortalHost(portal)
        }
        return events
    }
}
