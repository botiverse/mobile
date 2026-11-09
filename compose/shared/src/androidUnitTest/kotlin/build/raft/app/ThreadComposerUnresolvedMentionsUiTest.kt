package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.data.RaftAndroidSqlDatabaseContext
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Task #77: web MessageInput's "Not notified" banner above the composer, from a send's
 * `unresolvedMentionHandles` (`POST /api/v2/messages`). Real composer; the × goes through the
 * composer event the conversation page turns into [SharedThreadComposerDraftIntent.DismissUnresolvedMentions].
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
class ThreadComposerUnresolvedMentionsUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun theNoticeListsTheUnresolvedHandlesAboveTheComposerAndItsXDismissesIt() =
        runRobolectricComposeUiTest {
            RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
            val events = mutableListOf<String>()
            setContent {
                var handles by remember { mutableStateOf(listOf("@ghost", "@outside")) }
                Composer(ThreadComposerSurface(channelId = "c1"), handles) { event ->
                    events += event
                    if (event == ThreadComposerDismissUnresolvedMentionsEvent) handles = emptyList()
                }
            }

            onNodeWithText(NOTICE, useUnmergedTree = true).assertExists("the notice shows the server's handles")
            onNodeWithContentDescription(DISMISS, useUnmergedTree = true).performClick()
            waitForIdle()

            assertEquals(listOf(ThreadComposerDismissUnresolvedMentionsEvent), events)
            onNodeWithText(NOTICE, useUnmergedTree = true).assertDoesNotExist()
        }

    @Test
    fun aDmComposerNeverShowsTheNotice() =
        runRobolectricComposeUiTest {
            RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
            setContent {
                Composer(ThreadComposerSurface(kind = RaftThreadSurfaceKind.DirectMessage, channelId = "dm1"), listOf("@ghost", "@outside")) {}
            }

            onNodeWithText(NOTICE, useUnmergedTree = true).assertDoesNotExist()
        }

    @Test
    fun aThreadReplyComposerShowsTheNotice() =
        runRobolectricComposeUiTest {
            RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
            setContent {
                Composer(ThreadComposerSurface(kind = RaftThreadSurfaceKind.ReplyThread, channelId = "t1"), listOf("@ghost", "@outside")) {}
            }

            onNodeWithText(NOTICE, useUnmergedTree = true).assertExists()
        }

    @androidx.compose.runtime.Composable
    private fun Composer(surface: ThreadComposerSurface, handles: List<String>, onEvent: (String) -> Unit) {
        RaftTheme(appearance = RaftAppearance.Light) {
            val portal = remember { ThreadComposerSuggestionPortalState() }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                CompositionLocalProvider(LocalThreadComposerSuggestionPortal provides portal) {
                    ThreadComposerContent(
                        surface = surface,
                        state = ThreadComposerUiState(instanceId = "unresolved-mentions", unresolvedMentionHandles = handles),
                        onEvent = { event, _, _, _ -> onEvent(event) },
                        onKeyboardHeightChange = {},
                        contentPaddingTop = 0.dp,
                        contentPaddingBottom = 0.dp,
                        attachmentPreviewEnabled = true,
                    )
                }
            }
            ThreadComposerSuggestionPortalHost(portal)
        }
    }

    private companion object {
        const val NOTICE =
            "Not notified: @ghost, @outside. Select a person or agent from the mention picker and try again if needed."
        const val DISMISS = "Dismiss unnotified mention warning"
    }
}
