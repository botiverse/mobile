package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import build.raft.app.data.RaftAndroidSqlDatabaseContext
import build.raft.app.data.SharedMembersLoadStatus
import build.raft.app.nav.RaftMobilePageStackDisplay
import build.raft.app.nav.RaftPageStack
import kotlin.test.Test
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * artin (Hands e98456ba): after coming back from a page opened over a conversation (an artifact),
 * typing `@` drew no panel at all until he left the conversation and came back. Navigation 3 moves
 * a stack entry between scenes as movable content, so the push and pop reattach the composer's
 * nodes while its remembered anchor resolver stays. Real composer, real portal, real stack.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xhdpi")
class ThreadComposerSuggestionStackUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun theMentionPanelStillDrawsAfterAPageWasPushedOverTheConversationAndPopped() =
        runRobolectricComposeUiTest {
            RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
            val stack = RaftPageStack()
            setContent {
                RaftMobilePageStackDisplay(stack) { page ->
                    if (page.args.pageName == CHAT) {
                        val portal = remember { ThreadComposerSuggestionPortalState() }
                        // At the window's bottom as in a conversation: a root (0, 0) anchor counts as unplaced.
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                            CompositionLocalProvider(LocalThreadComposerSuggestionPortal provides portal) {
                                ThreadComposerContent(
                                    surface = ThreadComposerSurface(channelId = "c1"),
                                    state =
                                        ThreadComposerUiState(
                                            instanceId = "mention-panel",
                                            seedText = "cc @",
                                            suggestionsOpen = true,
                                            memberSuggestions = listOf(RaftComposerMemberItem(MEMBER, "ada")),
                                            memberSuggestionsStatus = SharedMembersLoadStatus.Loaded,
                                        ),
                                    onEvent = { _, _, _, _ -> },
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
            }
            runOnIdle { stack.open(CHAT, JsonObject(emptyMap()), from = stack.pages.first()) }
            waitForIdle()
            onNodeWithText(MEMBER, useUnmergedTree = true).assertExists("the panel opens on a fresh page")

            runOnIdle { stack.open(COVERING_PAGE, JsonObject(emptyMap()), from = stack.pages.last()) }
            waitForIdle()
            runOnIdle { stack.back() }
            waitForIdle()

            onNodeWithText(MEMBER, useUnmergedTree = true).assertExists("the panel still opens after the pop")
        }

    private companion object {
        const val CHAT = "compose_thread"
        const val COVERING_PAGE = "compose_artifact_viewer"
        const val MEMBER = "Ada Lovelace"
    }
}
