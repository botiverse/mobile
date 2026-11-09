package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.RaftLiveAgentActivityItem
import build.raft.app.model.AgentStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The line above a channel's composer (web AgentTypingLine, slock #13551) on a real composition: in
 * a channel it says who is working here, typing included, and is a button that opens Working now
 * for those agents, where the typer reads "Typing…" and a row opens the agent. Outside a channel
 * (a DM, a Lounge, a thread) it is today's typing line, which opens nothing.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h700dp")
class ThreadWorkingLineUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val niko = RaftLiveAgentActivityItem("agent-niko", "Niko", null, AgentStatus.Working, startedAt = 1)
    private val kirby = RaftLiveAgentActivityItem("agent-kirby", "Kirby", null, AgentStatus.Working, startedAt = 2)
    private val tenny = RaftLiveAgentActivityItem("agent-tenny", "Tenny", null, AgentStatus.Thinking, startedAt = 3)

    private fun source(opened: MutableList<String>) =
        RaftWorkingNowSource(
            serverId = "srv-1",
            items = MutableStateFlow(listOf(niko, kirby, tenny)),
            agentStatusResolver = EmptyRaftAgentStatusResolver,
            subscriptions = null,
            onOpenAgent = { opened += it },
        )

    private fun ComposeUiTest.showLine(typing: ThreadAgentTypingUi) {
        setContent {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize()) {
                        ThreadAgentTypingLine(typing = typing, modifier = Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.shown(text: String): Boolean =
        onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun nobodyTypingSaysHowManyWorkHereAndTheLineOpensWorkingNow() =
        runRobolectricComposeUiTest {
            val opened = mutableListOf<String>()
            showLine(ThreadAgentTypingUi("srv-1", agents = emptyList(), working = listOf(niko, kirby, tenny), workingNow = source(opened)))
            val line = onNode(hasContentDescription("3 agents working here"))
            val config = line.fetchSemanticsNode().config
            assertEquals(androidx.compose.ui.semantics.Role.Button, config[SemanticsProperties.Role])
            assertTrue(SemanticsActions.OnClick in config, "the line is a button")
            assertFalse(shown("Working now"))
            line.performClick()
            waitForIdle()
            assertTrue(shown("Working now"), "tapping the line opens Working now")
            assertTrue(shown("Niko") && shown("Kirby") && shown("Tenny"), "one row per agent working here")
            assertFalse(shown("Typing…"), "nobody types")
            onNode(hasText("Kirby"), useUnmergedTree = true).performClick()
            waitForIdle()
            assertEquals(listOf("agent-kirby"), opened, "a row opens the agent")
            assertFalse(shown("Working now"), "and closes Working now")
        }

    @Test
    fun aTyperAmongThemIsNamedFirstAndReadsTypingInWorkingNow() =
        runRobolectricComposeUiTest {
            showLine(
                ThreadAgentTypingUi(
                    "srv-1",
                    agents = listOf(ThreadTypingAgent("agent-kirby", "Kirby", null), ThreadTypingAgent("agent-mo", "Mo", null)),
                    working = listOf(niko, kirby, tenny),
                    workingNow = source(mutableListOf()),
                )
            )
            // Only the typing sentence is announced; the button is named by the whole line.
            assertTrue(named("Kirby and Mo are typing…"))
            onNode(hasContentDescription("Kirby and Mo are typing… · 2 others working")).performClick()
            waitForIdle()
            assertTrue(shown("Working now"))
            // Kirby works and types; Mo types without a live-activity row: both read "Typing…".
            assertEquals(2, onAllNodes(hasText("Typing…"), useUnmergedTree = true).fetchSemanticsNodes().size)
            assertTrue(shown("Mo"), "a typer not reported working still has a row")
        }

    @Test
    fun everyoneWorkingTypesSoTheLineSaysTheTypingSentence() =
        runRobolectricComposeUiTest {
            showLine(
                ThreadAgentTypingUi(
                    "srv-1",
                    agents = listOf(ThreadTypingAgent("agent-niko", "Niko", null)),
                    working = listOf(niko),
                    workingNow = source(mutableListOf()),
                )
            )
            // The live region and the button both say the typing sentence, and nothing more.
            assertEquals(2, onAllNodes(hasContentDescription("Niko is typing…")).fetchSemanticsNodes().size)
            assertFalse(onAllNodes(hasContentDescription("others working", substring = true)).fetchSemanticsNodes().isNotEmpty())
        }

    @Test
    fun outsideAChannelTheLineOnlySaysWhoTypesAndOpensNothing() =
        runRobolectricComposeUiTest {
            showLine(ThreadAgentTypingUi("srv-1", agents = listOf(ThreadTypingAgent("agent-niko", "Niko", null))))
            val line = onNode(hasContentDescription("Niko is typing…"))
            assertFalse(SemanticsActions.OnClick in line.fetchSemanticsNode().config, "a DM's typing line is no button")
        }

    private fun ComposeUiTest.named(description: String): Boolean =
        onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty()
}
