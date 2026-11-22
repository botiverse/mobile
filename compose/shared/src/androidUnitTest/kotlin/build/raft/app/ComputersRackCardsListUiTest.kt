package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import build.raft.app.data.ComputersRackSort
import build.raft.app.data.ComputersRackSortDirection
import build.raft.app.data.ComputersRackSortKey
import build.raft.app.data.ComputersRackView
import build.raft.app.data.RuntimeAccountUsageAccount
import build.raft.app.data.RuntimeAccountUsageHealth
import build.raft.app.data.RuntimeAccountUsageLoad
import build.raft.app.data.RuntimeAccountUsageProvider
import build.raft.app.data.RuntimeAccountUsageRead
import build.raft.app.data.RuntimeAccountUsageSnapshot
import build.raft.app.data.RuntimeAccountUsageWindow
import build.raft.app.data.RuntimeAccountUsageWindowStatus
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsComputerSizeStatusSnapshot
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SettingsComputersSnapshot
import build.raft.app.model.RaftTimeContext
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Computers page's cards and phone list on a real composition (#proj-mobile:#60, web
 * ComputerRackRow): the metric the list is sorted by leads a card's meters, a used-up usage line
 * says when it is back (slock #14058), and the List view is the web's phone blocks under the
 * runtime header, with the runtime cells online and "Last seen" offline.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2400dp")
class ComputersRackCardsListUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val gib = 1024.0 * 1024.0 * 1024.0

    // 2026-11-10T12:00:00Z
    private val now = 1_794_312_000_000L

    private val fleet =
        listOf(
            SettingsComputerSnapshot(
                id = "m-studio",
                name = "studio",
                daemonVersion = "",
                computerVersion = "1.0.53",
                isComputer = true,
                online = true,
                latestComputerVersion = "1.0.53",
                runtimes = listOf("claude", "codex", "opencode"),
                diskStatus = SettingsComputerSizeStatusSnapshot(availableBytes = 310 * gib, totalBytes = 994 * gib),
                memoryStatus = SettingsComputerSizeStatusSnapshot(availableBytes = 18 * gib, totalBytes = 64 * gib),
                canViewRuntimeUsage = true,
            ),
            SettingsComputerSnapshot(
                id = "m-laptop",
                name = "laptop",
                daemonVersion = "",
                computerVersion = "1.0.53",
                isComputer = true,
                online = false,
                lastHeartbeat = "2026-11-08T12:00:00Z",
                latestComputerVersion = "1.0.53",
                runtimes = listOf("claude"),
            ),
        )

    private fun usage(): Map<String, Map<RuntimeAccountUsageProvider, RuntimeAccountUsageLoad>> {
        fun loaded(provider: String, window: RuntimeAccountUsageWindow) =
            RuntimeAccountUsageLoad.Loaded(
                RuntimeAccountUsageRead.Available(
                    stale = false,
                    snapshot =
                        RuntimeAccountUsageSnapshot(
                            provider,
                            null,
                            listOf(RuntimeAccountUsageAccount("a", "Max", null, RuntimeAccountUsageHealth.Ok, listOf(window))),
                        ),
                ),
            )
        return mapOf(
            "m-studio" to
                mapOf(
                    // Used up, back 3h 30m from now.
                    RuntimeAccountUsageProvider.Claude to
                        loaded("claude", RuntimeAccountUsageWindow("w", "5h", RuntimeAccountUsageWindowStatus.LimitReached, null, "2026-11-10T15:30:00Z")),
                    RuntimeAccountUsageProvider.Codex to
                        loaded("codex", RuntimeAccountUsageWindow("w", "5h", RuntimeAccountUsageWindowStatus.Ok, 0.42, null)),
                ),
        )
    }

    private fun ComposeUiTest.show(view: ComputersRackView, sort: ComputersRackSort? = null, computers: List<SettingsComputerSnapshot> = fleet) {
        val model = raftComputersListModel(ScreenLoadState.Content(SettingsComputersSnapshot(computers = computers)))
        val time = RaftTimeContext(localOffsetMinutes = 0, nowEpochMillis = now)
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(time)) {
                    RaftComputersTheme {
                        RaftSettingsComputersContent(
                            model = model,
                            onRetry = {},
                            onAddComputer = {},
                            onOpenComputer = {},
                            usageLoads = usage(),
                            rackView = view,
                            toolbar = RaftComputersRackToolbarState(serverId = "server-1", storedSort = sort),
                            serverId = "server-1",
                        )
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.topIn(tag: String, text: String): Float =
        onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.top

    @Test
    fun aCardLeadsWithTheMetricTheListIsSortedBy() = runRobolectricComposeUiTest {
        val meters = "${RaftComputersRackTags.CardMeters}-m-studio"
        show(ComputersRackView.Cards, ComputersRackSort(ComputersRackSortKey.Remaining(RuntimeAccountUsageProvider.Codex), ComputersRackSortDirection.Desc))
        assertTrue(topIn(meters, "Codex") < topIn(meters, "Disk"), "Codex remaining: the Codex line leads")
        assertTrue(topIn(meters, "Disk") < topIn(meters, "Claude"), "the other runtimes stay under the meters")
    }

    @Test
    fun aUsedUpLineSaysWhenItIsBack() = runRobolectricComposeUiTest {
        show(ComputersRackView.Cards)
        onNodeWithText("Back in 3h", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(onAllNodes(hasText("Used up"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun anOfflineBlockSaysWhenItWasLastSeenAndHasNoCells() = runRobolectricComposeUiTest {
        show(ComputersRackView.List)
        // The Offline section is open by default, even while studio is online.
        onNodeWithTag("rack-row-m-laptop").assertIsDisplayed()
        assertTrue(onAllNodes(hasTestTag("rack-metrics-m-laptop"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(), "an offline computer reads no usage")
        assertTrue(onAllNodes(hasText("Last seen", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    // Free of total (botiverse/slock staging 7a631c0ffe, #14306 RackResource / machine.diskLow.chipTitle):
    // a disk in decimal units (310 GiB = 332.9 GB of 994 GiB = 1067.3 GB), memory in binary ones.

    @Test
    fun aCardsMetersSayWhatIsFreeOfTheWholeDiskInDecimalUnits() = runRobolectricComposeUiTest {
        show(ComputersRackView.Cards)
        onNode(hasContentDescription("Disk 69% used, 332.9 GB free of 1067.3 GB"), useUnmergedTree = true).assertExists()
        onNode(hasContentDescription("Memory 72% used, 18.0 GB free of 64.0 GB"), useUnmergedTree = true).assertExists()
    }
}
