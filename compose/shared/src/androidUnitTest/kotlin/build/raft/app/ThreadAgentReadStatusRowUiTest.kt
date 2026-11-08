package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The thread's agent read status line on a real composition (web `AgentReadStatusLine`,
 * slock staging ecc7b14): the line's words, and a tap revealing what the web's tooltip says — every
 * stopped-following name and "Mention them to reach them".
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h360dp")
class ThreadAgentReadStatusRowUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Composable
    private fun InPage(content: @Composable () -> Unit) {
        val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { RaftDesignSystemTheme(content = content) }
    }

    private val names = mapOf("a-niko" to "Niko", "a-ray" to "Ray", "a-cindy" to "Cindy", "a-tenny" to "Tenny", "a-kirby" to "Kirby")

    @Test
    fun stoppedFollowingLeadsTheLineAndATapRevealsEveryNameAndTheHint() = runRobolectricComposeUiTest {
        val status =
            ThreadAgentReadStatusUi(
                messageId = "m-3",
                entries =
                    listOf(
                        ThreadAgentReadEntry("a-niko", ThreadAgentReadState.StoppedFollowing),
                        ThreadAgentReadEntry("a-ray", ThreadAgentReadState.StoppedFollowing),
                        ThreadAgentReadEntry("a-cindy", ThreadAgentReadState.StoppedFollowing),
                        ThreadAgentReadEntry("a-tenny", ThreadAgentReadState.StoppedFollowing),
                        ThreadAgentReadEntry("a-kirby", ThreadAgentReadState.Read),
                    ),
                names = names,
            )
        setContent { InPage { ThreadAgentReadStatusRow(status) } }
        val folded = "Stopped following: Niko, Ray, Cindy +1 · +1"
        val explained = "Stopped following: Niko, Ray, Cindy, and Tenny · Mention them to reach them · Kirby read"
        onNodeWithText(folded, useUnmergedTree = true).assertExists()
        onNodeWithText(explained, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(ThreadAgentReadStatusTestTag).assertContentDescriptionEquals("Agents on this message: $explained")

        onNodeWithTag(ThreadAgentReadStatusTestTag).performClick()
        waitForIdle()
        onNodeWithText(explained, useUnmergedTree = true).assertExists()
        onNodeWithTag(ThreadAgentReadStatusTestTag).performClick()
        waitForIdle()
        onNodeWithText(folded, useUnmergedTree = true).assertExists()
    }

    @Test
    fun everyoneReadShowsTheLineAsIs() = runRobolectricComposeUiTest {
        val status =
            ThreadAgentReadStatusUi(
                messageId = "m-3",
                entries = listOf(ThreadAgentReadEntry("a-niko", ThreadAgentReadState.Replied), ThreadAgentReadEntry("a-ray", ThreadAgentReadState.Read)),
                names = names,
            )
        setContent { InPage { ThreadAgentReadStatusRow(status) } }
        onNodeWithText("Niko replied · Ray read", useUnmergedTree = true).assertExists()
        onNodeWithTag(ThreadAgentReadStatusTestTag).assertContentDescriptionEquals("Agents on this message: Niko replied · Ray read")
    }
}
