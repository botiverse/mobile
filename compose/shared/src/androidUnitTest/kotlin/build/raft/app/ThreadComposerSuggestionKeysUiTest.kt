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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import build.raft.app.data.RaftAndroidSqlDatabaseContext
import build.raft.app.data.SharedMembersLoadStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The composer's @ list on a hardware keyboard, as on the web (useAutocomplete `handleKeyDown`):
 * ↓ moves the highlight and Enter inserts the highlighted member, as a click on it would, sending
 * nothing; Esc closes the list until the text changes. Real composer, real portal, with Return
 * sending (the desktop default), so a send would show.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi-keysexposed-qwerty")
class ThreadComposerSuggestionKeysUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun downThenEnterInsertsTheSecondMemberAndSendsNothing() =
        runRobolectricComposeUiTest {
            val events = showComposer()
            val editor = onNode(hasSetTextAction())
            editor.performTextInput("@")
            waitForIdle()
            onNodeWithText(GRACE.displayName, useUnmergedTree = true).assertExists("typing @ opens the member list")

            editor.performKeyInput { pressKey(Key.DirectionDown) }
            waitForIdle()
            editor.performKeyInput { pressKey(Key.Enter) }
            waitForIdle()

            assertEquals("@grace ", events.valueOf("change"), "Enter inserts the highlighted (second) member")
            assertEquals(GRACE.memberId, decodeThreadComposerMentionEvent(events.valueOf(ThreadComposerSelectMentionEvent))?.id)
            assertTrue(events.none { (action, _) -> action == "send" }, "Enter on the list must not send: $events")
            onNodeWithText(GRACE.displayName, useUnmergedTree = true).assertDoesNotExist()
        }

    @Test
    fun escClosesTheListUntilTheTextChanges() =
        runRobolectricComposeUiTest {
            val events = showComposer()
            val editor = onNode(hasSetTextAction())
            editor.performTextInput("@")
            waitForIdle()
            onNodeWithText(ADA.displayName, useUnmergedTree = true).assertExists("typing @ opens the member list")

            editor.performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
            onNodeWithText(ADA.displayName, useUnmergedTree = true).assertDoesNotExist()
            assertEquals("@", events.valueOf("change"), "Esc keeps the text")

            editor.performTextReplacement("")
            editor.performTextInput("@")
            waitForIdle()
            onNodeWithText(ADA.displayName, useUnmergedTree = true).assertExists("the next edit opens the list again")
        }

    /** The composer at the window's bottom (a root (0, 0) anchor counts as unplaced); returns its events. */
    private fun ComposeUiTest.showComposer(): List<Pair<String, String?>> {
        RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        val events = mutableListOf<Pair<String, String?>>()
        setContent {
            val portal = remember { ThreadComposerSuggestionPortalState() }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                CompositionLocalProvider(LocalThreadComposerSuggestionPortal provides portal) {
                    ThreadComposerContent(
                        surface = ThreadComposerSurface(channelId = "c1"),
                        state =
                            ThreadComposerUiState(
                                instanceId = "mention-keys",
                                memberSuggestions = listOf(ADA, GRACE),
                                memberSuggestionsStatus = SharedMembersLoadStatus.Loaded,
                            ),
                        onEvent = { action, value, _, _ -> events += action to value },
                        onKeyboardHeightChange = {},
                        contentPaddingTop = 0.dp,
                        contentPaddingBottom = 0.dp,
                        composerSendKeyEnabled = true,
                        attachmentPreviewEnabled = true,
                    )
                }
            }
            ThreadComposerSuggestionPortalHost(portal)
        }
        return events
    }

    /** The value of the last [action] event. */
    private fun List<Pair<String, String?>>.valueOf(action: String): String? = lastOrNull { it.first == action }?.second

    private companion object {
        // Structured mentions carry only UUID ids (slock UUID_RE; composerPersonOrAgentStructuredMention).
        val ADA = RaftComposerMemberItem(displayName = "Ada Lovelace", handle = "ada", memberId = "0a0a0a0a-0000-4000-8000-000000000001")
        val GRACE = RaftComposerMemberItem(displayName = "Grace Hopper", handle = "grace", memberId = "06ace000-0000-4000-8000-000000000002")
    }
}
