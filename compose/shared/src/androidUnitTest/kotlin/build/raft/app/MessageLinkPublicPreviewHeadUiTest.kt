package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.RaftPublicMessage
import build.raft.app.thread.RaftMessageLinkPublicPreviewScreen
import build.raft.app.thread.RaftPublicPreviewState
import build.raft.designsystem.components.LocalRaftSpinnerStillStep
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The public preview's head on a real composition: "Public preview" and "You haven't joined this
 * server. This is its public preview." only with the conversation the public routes returned.
 * While it loads, and on the Retry state, it is the plain "Message link" page: nothing has said yet
 * that the server is public, and it may well not be.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class MessageLinkPublicPreviewHeadUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun ComposeUiTest.shown(text: String): Boolean =
        onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun thePublicPreviewHeadAndNoticeShowOnlyWithTheConversation() =
        runRobolectricComposeUiTest {
            var state by mutableStateOf<RaftPublicPreviewState>(RaftPublicPreviewState.Loading)
            setContent {
                val pager =
                    remember {
                        object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                    }
                // A still spinner: the Loading state's own would keep the composition from idling.
                CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager), LocalRaftSpinnerStillStep provides 0) {
                    RaftMessageLinkPublicPreviewScreen(state = state, onBack = {}, onRetry = {})
                }
            }
            for (waiting in listOf(RaftPublicPreviewState.Loading, RaftPublicPreviewState.Unavailable)) {
                runOnIdle { state = waiting }
                waitForIdle()
                assertTrue(shown(MessageLinkTitle), "state=$waiting")
                assertFalse(shown(PublicPreviewTitle), "state=$waiting")
                assertFalse(shown(PublicPreviewNotice), "state=$waiting")
            }
            // With the conversation: its channel's name as the title (else "Public preview"), and the notice.
            for (channelName in listOf("release-notes", null)) {
                runOnIdle { state = content(channelName) }
                waitForIdle()
                assertTrue(shown(channelName?.let { "#$it" } ?: PublicPreviewTitle), "channelName=$channelName")
                assertTrue(shown(PublicPreviewNotice), "channelName=$channelName")
                assertFalse(shown(MessageLinkTitle), "channelName=$channelName")
            }
        }

    private fun content(channelName: String?) =
        RaftPublicPreviewState.Content(
            channelName = channelName,
            serverName = "Open Source Club",
            messages = listOf(RaftPublicMessage("m1", "Sam", "The crash rate is back to normal.", null, null, 0)),
            highlightMessageId = null,
            thread = false,
        )

    private companion object {
        const val MessageLinkTitle = "Message link"
        const val PublicPreviewTitle = "Public preview"
        const val PublicPreviewNotice = "You haven't joined this server. This is its public preview."
    }
}
