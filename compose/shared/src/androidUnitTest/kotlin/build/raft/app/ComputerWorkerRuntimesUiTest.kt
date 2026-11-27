package build.raft.app

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsComputerControlState
import build.raft.app.data.SettingsComputerCreatorSnapshot
import build.raft.app.data.SettingsComputerDetailSnapshot
import build.raft.app.data.SettingsComputerOwnerRequest
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SettingsComputerWorkerRuntimeError
import build.raft.app.data.SettingsComputerWorkerRuntimesState
import build.raft.app.data.SharedSettingsIntent
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Worker v1 step 2 (botiverse/slock #17011 + #17012, web `computerWorkerRuntimes.behavior.test.tsx`):
 * Computer Detail › Runtimes for Workers UI contract on a real Robolectric Compose composition.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2400dp")
class ComputerWorkerRuntimesUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val alice = SettingsComputerCreatorSnapshot(id = "alice-id", name = "alice", displayName = "Alice")

    private fun computer(
        runtimes: List<String> = listOf("claude", "codex"),
        workerRuntimesTurnedOn: List<String>? = emptyList(),
        canChangeWorkerRuntimes: Boolean = true,
        workerRuntimesChangeBy: String? = "any_member",
        ownerUserId: String? = null,
        owner: SettingsComputerCreatorSnapshot? = null,
    ) = SettingsComputerSnapshot(
        id = "m-1",
        name = "Build Box",
        daemonVersion = "",
        computerVersion = "1.0.60",
        isComputer = true,
        online = true,
        runtimes = runtimes,
        ownerUserId = ownerUserId,
        owner = owner,
        workerRuntimesTurnedOn = workerRuntimesTurnedOn,
        canChangeWorkerRuntimes = canChangeWorkerRuntimes,
        workerRuntimesChangeBy = workerRuntimesChangeBy,
    )

    private fun detail(
        computer: SettingsComputerSnapshot,
        canClaimOwner: Boolean = false,
    ) = SettingsComputerDetailSnapshot(
        computer = computer,
        workspaces = emptyList(),
        agents = emptyList(),
        sections = emptyList(),
        canManageMachines = true,
        canEditMachine = false,
        canClaimOwner = canClaimOwner,
        currentUserId = "user-1",
    )

    private fun ComposeUiTest.showDetail(
        snapshot: SettingsComputerDetailSnapshot,
        workerV1Enabled: Boolean = true,
        workerRuntimesState: SettingsComputerWorkerRuntimesState = SettingsComputerWorkerRuntimesState(),
        intents: MutableList<SharedSettingsIntent> = mutableListOf(),
    ) {
        val model =
            raftComputerDetailModel(
                state = ScreenLoadState.Content(snapshot),
                machineId = "m-1",
                targetServerId = null,
                currentServerId = "server-1",
            )
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftComputersTheme {
                    RaftSettingsComputerDetailContent(
                        model = model,
                        actionState = null,
                        onRetry = {},
                        onIntent = { intents += it },
                        onOpenAgent = {},
                        computerControl = SettingsComputerControlState(workerRuntimes = workerRuntimesState),
                        workerV1Enabled = workerV1Enabled,
                    )
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun rowsHelperListsReportedRuntimesThenStillTurnedOn() {
        assertEquals(
            listOf("claude", "codex", "pi"),
            raftComputerWorkerRuntimeRows(listOf("claude", "codex"), listOf("pi", "codex")),
        )
        assertEquals(emptyList(), raftComputerWorkerRuntimeRows(emptyList(), null))
    }

    @Test
    fun publicComputerShowsSwitchesHelperCopyAndTogglesEmitIntent() = runRobolectricComposeUiTest {
        val intents = mutableListOf<SharedSettingsIntent>()
        showDetail(detail(computer()), intents = intents)

        onNodeWithTag(RAFT_COMPUTER_WORKER_RUNTIMES_TAG).assertIsDisplayed()
        onNodeWithText("Runtimes for Workers").assertIsDisplayed()
        onNodeWithText(
            "Workers can always use their agent’s own runtime. Turn on another runtime to let Workers of agents on this Computer use it too: they run on this Computer’s sign-in and quota for it."
        ).assertIsDisplayed()
        onNodeWithText("This Computer is Public: any member of this server can change this.").assertIsDisplayed()

        val codexSwitch = onNodeWithTag(raftWorkerRuntimeSwitchTag("codex"))
        codexSwitch.assertIsDisplayed().assertIsEnabled().assertIsOff()
        codexSwitch.performClick()
        waitForIdle()

        assertEquals(
            listOf<SharedSettingsIntent>(
                SharedSettingsIntent.SetComputerWorkerRuntimes(machineId = "m-1", runtime = "codex", enabled = true)
            ),
            intents,
        )
    }

    @Test
    fun refusalShowsServerSentenceUnderRowAndSavingDisablesSwitch() = runRobolectricComposeUiTest {
        val refusal =
            "Only an owner or admin of this server can change which runtimes Workers may use on this Computer."
        showDetail(
            detail(computer(workerRuntimesTurnedOn = emptyList())),
            workerRuntimesState =
                SettingsComputerWorkerRuntimesState(
                    machineId = "m-1",
                    savingRuntimes = setOf("claude"),
                    errorsByRuntime =
                        mapOf(
                            "codex" to
                                SettingsComputerWorkerRuntimeError(
                                    code = "worker_runtimes_forbidden",
                                    serverMessage = refusal,
                                )
                        ),
                ),
        )

        onNodeWithTag(raftWorkerRuntimeSwitchTag("claude")).assertIsNotEnabled()
        onNodeWithTag(raftWorkerRuntimeSwitchTag("codex")).assertIsEnabled().assertIsOff()
        onNodeWithText(refusal).assertIsDisplayed()
    }

    @Test
    fun ownedComputerViewerCannotChangeDisablesSwitchesNamesOwnerAndHidesMakeItMine() = runRobolectricComposeUiTest {
        showDetail(
            detail(
                computer(
                    workerRuntimesTurnedOn = listOf("codex"),
                    canChangeWorkerRuntimes = false,
                    workerRuntimesChangeBy = "owner_or_admin",
                    ownerUserId = "alice-id",
                    owner = alice,
                ),
                canClaimOwner = false,
            )
        )

        onNodeWithTag(raftWorkerRuntimeSwitchTag("codex")).assertIsNotEnabled().assertIsOn()
        onNodeWithTag(raftWorkerRuntimeSwitchTag("claude")).assertIsNotEnabled().assertIsOff()
        onNodeWithText("@alice, this Computer’s owner, or a server owner or admin can change this.").assertIsDisplayed()
        onNodeWithTag(RAFT_COMPUTER_WORKER_RUNTIMES_MAKE_MINE_TAG).assertDoesNotExist()
    }

    @Test
    fun publicComputerConnectorSeesMakeItMineAndClickingClaimsOwner() = runRobolectricComposeUiTest {
        val intents = mutableListOf<SharedSettingsIntent>()
        showDetail(detail(computer(), canClaimOwner = true), intents = intents)

        onNodeWithTag(RAFT_COMPUTER_WORKER_RUNTIMES_MAKE_MINE_TAG).assertIsDisplayed().performClick()
        waitForIdle()

        assertEquals(
            listOf<SharedSettingsIntent>(
                SharedSettingsIntent.SetComputerOwner(SettingsComputerOwnerRequest("m-1", "user-1"))
            ),
            intents,
        )
    }

    @Test
    fun computerReportingNoRuntimesShowsEmptyNotice() = runRobolectricComposeUiTest {
        showDetail(detail(computer(runtimes = emptyList(), workerRuntimesTurnedOn = emptyList())))

        onNodeWithTag(RAFT_COMPUTER_WORKER_RUNTIMES_NONE_TAG).assertIsDisplayed()
        onNodeWithText("This Computer reports no runtimes yet.").assertIsDisplayed()
    }

    @Test
    fun hiddenWhenWorkerV1OffOrServerOmitsWorkerRuntimes() = runRobolectricComposeUiTest {
        showDetail(detail(computer()), workerV1Enabled = false)
        onNodeWithTag(RAFT_COMPUTER_WORKER_RUNTIMES_TAG).assertDoesNotExist()
    }

    @Test
    fun hiddenWhenOlderServerOmitsWorkerRuntimesTurnedOn() = runRobolectricComposeUiTest {
        showDetail(detail(computer(workerRuntimesTurnedOn = null)), workerV1Enabled = true)
        onNodeWithTag(RAFT_COMPUTER_WORKER_RUNTIMES_TAG).assertDoesNotExist()
    }
}
