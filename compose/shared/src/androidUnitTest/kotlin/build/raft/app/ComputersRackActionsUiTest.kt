package build.raft.app

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.data.COMPUTER_ACTION_DELETE
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.KeyValueComputersRackPreferenceRepository
import build.raft.app.data.ScreenError
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsComputerBroadcastPolicySnapshot
import build.raft.app.data.SettingsComputerCommandContext
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SettingsComputersSnapshot
import build.raft.app.data.SharedSettingsActionState
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A Computers card's actions on a real composition (#proj-mobile:#60, web RackRowMenu and
 * RackRowAction): ⋯ ends with Delete computer and its confirm names the computer (a computer with
 * agents gets the notice instead), a failed delete shows with its trace ID, a computer the app
 * can't upgrade offers the install command, the repair hint sits beside Upgrade, and an attention
 * entry that finds nothing says so.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2400dp")
class ComputersRackActionsUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun computer(id: String, name: String, block: SettingsComputerSnapshot.() -> SettingsComputerSnapshot = { this }) =
        SettingsComputerSnapshot(
            id = id,
            name = name,
            daemonVersion = "",
            computerVersion = "1.0.49",
            isComputer = true,
            online = true,
            platform = "linux x64",
            latestComputerVersion = "1.0.53",
            upgradeAvailable = true,
            remoteUpgradeSupported = true,
            broadcastPolicy = SettingsComputerBroadcastPolicySnapshot(eligibility = "eligible", targetVersion = "1.0.53"),
            canManage = true,
        ).block()

    private val fleet =
        listOf(
            computer("m-repair", "repair-box") { copy(installerRepairHint = "unrecovered_operation") },
            computer("m-manual", "manual-box") { copy(remoteUpgradeSupported = false) },
        )

    private fun ComposeUiTest.show(
        computers: List<SettingsComputerSnapshot> = fleet,
        actionError: SharedSettingsActionState? = null,
        onDelete: (String) -> Unit = {},
        toolbarStore: RaftComputersRackToolbarStore? = null,
    ) {
        val model =
            raftComputersListModel(
                ScreenLoadState.Content(
                    SettingsComputersSnapshot(
                        computers = computers,
                        commandContext = SettingsComputerCommandContext(serverSlug = "acme", deploymentEnv = "production"),
                    )
                )
            )
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftComputersTheme {
                    val toolbar = toolbarStore?.state?.collectAsState()?.value ?: RaftComputersRackToolbarState(serverId = "server-1")
                    RaftSettingsComputersContent(
                        model = model,
                        onRetry = {},
                        onAddComputer = {},
                        onOpenComputer = {},
                        onDeleteComputer = onDelete,
                        actionError = actionError,
                        toolbar = toolbar,
                        onToolbarIntent = { toolbarStore?.dispatch(it) },
                        serverId = "server-1",
                    )
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun theCardMenuEndsWithDeleteComputer() = runRobolectricComposeUiTest {
        var deleted: String? = null
        show(listOf(computer("m-1", "build-box")), onDelete = { deleted = it })
        onNodeWithContentDescription("More Actions").performClick()
        waitForIdle()
        onNodeWithTag(RaftComputersRackTags.MenuDelete).assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals("m-1", deleted)
    }

    @Test
    fun theDeleteConfirmNamesTheComputerAndAComputerWithAgentsGetsTheNotice() = runRobolectricComposeUiTest {
        var confirmed = false
        val agentsState = androidx.compose.runtime.mutableStateOf(0)
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftComputerDeleteDialog(name = "build-box", agentCount = agentsState.value, onConfirm = { confirmed = true }, onDismiss = {})
            }
        }
        waitForIdle()
        onNodeWithText("Are you sure you want to delete \"build-box\"?").assertIsDisplayed()
        onNodeWithTag(RaftComputersRackTags.DeleteConfirm).performClick()
        waitForIdle()
        assertTrue(confirmed)
        agentsState.value = 2
        waitForIdle()
        onNodeWithText("Remove all agents", substring = true).assertIsDisplayed()
        assertTrue(onAllNodes(hasTestTag(RaftComputersRackTags.DeleteConfirm)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aFailedDeleteShowsItsTraceId() = runRobolectricComposeUiTest {
        val traceId = "0123456789abcdef0123456789abcdef"
        show(
            actionError =
                SharedSettingsActionState(
                    action = COMPUTER_ACTION_DELETE,
                    itemId = "m-repair",
                    screenError = ScreenError(code = "network_error", traceId = traceId),
                )
        )
        onNodeWithTag(RaftComputersRackTags.ActionError).assertIsDisplayed()
        onNodeWithTag(RaftTraceIdNoteTestTag, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun aComputerUpgradedOnTheMachineOffersTheInstallCommand() = runRobolectricComposeUiTest {
        show()
        onNodeWithTag(RaftComputersRackTags.ManualUpgrade).performClick()
        waitForIdle()
        onNodeWithTag(RaftComputersRackTags.InstallSheet).assertIsDisplayed()
        onNodeWithText("can't be upgraded from the web", substring = true).assertIsDisplayed()
        assertTrue(onAllNodes(hasText("--setup /acme", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun theRepairHintSitsBesideUpgradeAndExplainsItself() = runRobolectricComposeUiTest {
        show()
        // Both cards say it: repair-box's one-click Upgrade (beside the ⚠) and manual-box's manual one.
        assertTrue(onAllNodes(hasText("Upgrade to v1.0.53"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        // The tag is on the tooltip anchor round the icon button, merged into the button node.
        onNodeWithTag(RaftComputersRackTags.UpgradeHint, useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithText("An earlier installation on this computer was not finished", substring = true).assertIsDisplayed()
    }

    @Test
    fun anAttentionEntryWithNothingToDoSaysSo() = runRobolectricComposeUiTest {
        val store = RaftComputersRackToolbarStore(KeyValueComputersRackPreferenceRepository(InMemoryRaftKeyValueStore()))
        store.onServer("server-1")
        store.dispatch(RaftComputersRackToolbarIntent.SetStatus(RaftComputersRackStatusFilter.Attention))
        show(listOf(computer("m-1", "build-box") { copy(computerVersion = "1.0.53", upgradeAvailable = false) }), toolbarStore = store)
        onNodeWithText("No computers need attention").assertIsDisplayed()
    }
}
