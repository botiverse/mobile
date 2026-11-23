package build.raft.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import build.raft.app.data.ComputersRackView
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsComputerBroadcastPolicySnapshot
import build.raft.app.data.SettingsComputerSizeStatusSnapshot
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SettingsComputersSnapshot
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Computers page's cards on a wide window, as on the web (#wg-mobile-sync:#255; slock
 * ComputersRackList: a CSS grid of cards at least 250 wide, `ComputerRackRow` card `h-full`, its
 * next step `mt-auto`): every card in a row is as tall as the row's tallest, and a card's next
 * step (Upgrade) sits at the card's bottom, as far from it as in a card at its own height.
 * Drives [RaftSettingsComputersContent] on a real composition, 4 cards a row.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1100dp-h2000dp")
class ComputersRackCardRowUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val gib = 1024.0 * 1024.0 * 1024.0

    private fun computer(id: String, runtimes: List<String>, outdated: Boolean = false) =
        SettingsComputerSnapshot(
            id = id,
            name = id.removePrefix("m-"),
            daemonVersion = "",
            computerVersion = if (outdated) "1.25.9" else "1.25.10",
            isComputer = true,
            online = true,
            platform = "linux x64",
            osRelease = "Ubuntu 26.04",
            latestComputerVersion = "1.25.10",
            upgradeAvailable = outdated,
            remoteUpgradeSupported = true,
            broadcastPolicy = SettingsComputerBroadcastPolicySnapshot(eligibility = "eligible", targetVersion = "1.25.10"),
            canManage = true,
            runtimes = runtimes,
            diskStatus = SettingsComputerSizeStatusSnapshot(availableBytes = 310 * gib, totalBytes = 994 * gib),
            memoryStatus = SettingsComputerSizeStatusSnapshot(availableBytes = 18 * gib, totalBytes = 64 * gib),
        )

    // The outdated one (Upgrade) leads the row (attention first); the studio, with every usage
    // runtime and other runtimes on more than one line, is the row's tallest.
    private val outdated = computer("m-build", listOf("claude"), outdated = true)
    private val row =
        listOf(
            outdated,
            computer("m-studio", listOf("claude", "codex", "kimi", "grok", "antigravity", "opencode", "gemini", "pi", "qwen", "copilot")),
            computer("m-quiet", emptyList()),
            computer("m-mid", listOf("claude", "codex")),
        )

    // Agents on two of them: their avatar strips are part of what the row measures.
    private val agents =
        mapOf(
            "m-build" to listOf(RaftComputerCardAgent("a-1", "Wug", null)),
            "m-studio" to (1..6).map { RaftComputerCardAgent("a-s$it", "Agent $it", null) },
        )

    @Test
    fun cardsInARowAreAsTallAsItsTallestAndTheNextStepSitsAtTheBottom() =
        runRobolectricComposeUiTest {
            build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
            val fleet = mutableStateOf(listOf(outdated))
            setContent {
                RaftTheme(appearance = RaftAppearance.Light) {
                    RaftComputersTheme {
                        RaftSettingsComputersContent(
                            model = raftComputersListModel(ScreenLoadState.Content(SettingsComputersSnapshot(computers = fleet.value))),
                            onRetry = {},
                            onAddComputer = {},
                            onOpenComputer = {},
                            rackView = ComputersRackView.Cards,
                            agentsByMachine = agents,
                            toolbar = RaftComputersRackToolbarState(serverId = "server-1"),
                            serverId = "server-1",
                        )
                    }
                }
            }
            // Each alone in its row is its own height; the outdated one's Upgrade sits this far from
            // its bottom.
            val own =
                row.associate { computer ->
                    fleet.value = listOf(computer)
                    waitForIdle()
                    computer.id to card(computer.id).let { it.bottom - it.top }
                }
            fleet.value = listOf(outdated)
            waitForIdle()
            val ownGap = card(outdated.id).bottom - upgrade(outdated.id).bottom
            val tallest = own.values.max()
            assertTrue(tallest > own.getValue(outdated.id) + 8.dp, "no card is taller than the outdated one on its own: $own")

            // Together, the row is as tall as its tallest card on its own: every card starts and ends
            // with it (none cut short), and Upgrade keeps its gap to the stretched card's bottom.
            fleet.value = row
            waitForIdle()
            val top = card(row.first().id).top
            row.forEach { computer ->
                val bounds = card(computer.id)
                assertClose(top, bounds.top, "${computer.id} doesn't start with its row")
                assertClose(tallest, bounds.bottom - bounds.top, "${computer.id} isn't as tall as the row's tallest card on its own ($own)")
            }
            assertClose(ownGap, card(outdated.id).bottom - upgrade(outdated.id).bottom, "Upgrade isn't at the bottom of its stretched card")
        }

    private fun androidx.compose.ui.test.ComposeUiTest.card(id: String): DpRect = onNodeWithTag("rack-row-$id").getBoundsInRoot()

    private fun androidx.compose.ui.test.ComposeUiTest.upgrade(id: String): DpRect =
        onNode(hasText("Upgrade to v1.25.10") and hasAnyAncestor(hasTestTag("rack-row-$id"))).getBoundsInRoot()

    private fun assertClose(expected: androidx.compose.ui.unit.Dp, actual: androidx.compose.ui.unit.Dp, message: String) =
        assertTrue(abs((expected - actual).value) <= 0.5f, "$message: expected $expected, was $actual")
}
