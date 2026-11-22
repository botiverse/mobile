package build.raft.app

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsComputerBroadcastPolicySnapshot
import build.raft.app.data.SettingsComputerCreatorSnapshot
import build.raft.app.data.SettingsComputerDetailSnapshot
import build.raft.app.data.SettingsComputerOwnerRequest
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.data.SettingsComputerUpgradeRequestSnapshot
import build.raft.app.data.SharedSettingsIntent
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Computers pages on a real composition, against the web's copy: Computer owner v1 (the Owner
 * row, Transfer owner, Make it mine, the list's owner line; botiverse/slock #13344) and an upgrade
 * that first waits for this server's agents (#13506).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2400dp")
class ComputersParityUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val bea = SettingsComputerCreatorSnapshot(id = "user-2", name = "bea", displayName = "Bea")

    private fun computer(
        ownerUserId: String? = null,
        owner: SettingsComputerCreatorSnapshot? = null,
        upgradeRequest: SettingsComputerUpgradeRequestSnapshot? = null,
    ) = SettingsComputerSnapshot(
        id = "m-1",
        name = "Studio",
        daemonVersion = "",
        computerVersion = "1.0.49",
        isComputer = true,
        online = true,
        upgradeAvailable = true,
        remoteUpgradeSupported = true,
        broadcastPolicy = SettingsComputerBroadcastPolicySnapshot(eligibility = "eligible", targetVersion = "1.0.53"),
        latestComputerVersion = "1.0.53",
        upgradeRequest = upgradeRequest,
        canManage = true,
        ownerUserId = ownerUserId,
        owner = owner,
    )

    private fun draining(vararg agents: String) =
        SettingsComputerUpgradeRequestSnapshot(
            id = "req-1",
            targetVersion = "1.0.53",
            requestedAt = "2026-11-08T00:00:00.000Z",
            state = "pending",
            phase = "draining",
            drainingAgentIds = agents.toList(),
        )

    private fun ComposeUiTest.showDetail(
        snapshot: SettingsComputerDetailSnapshot,
        intents: MutableList<SharedSettingsIntent> = mutableListOf(),
    ) {
        val model = raftComputerDetailModel(ScreenLoadState.Content(snapshot), machineId = "m-1", targetServerId = null, currentServerId = "server-1")
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftComputersTheme {
                    RaftSettingsComputerDetailContent(
                        model = model,
                        actionState = null,
                        onRetry = {},
                        onIntent = { intents += it },
                        onOpenAgent = {},
                    )
                }
            }
        }
        waitForIdle()
    }

    private fun detail(computer: SettingsComputerSnapshot, canTransfer: Boolean = false, canClaim: Boolean = false) =
        SettingsComputerDetailSnapshot(
            computer = computer,
            workspaces = emptyList(),
            agents = emptyList(),
            sections = emptyList(),
            canManageMachines = true,
            canEditMachine = true,
            canTransferOwner = canTransfer,
            canClaimOwner = canClaim,
            currentUserId = "user-1",
        )

    @Test
    fun aPublicComputerReadsPublicAndItsConnectorMayMakeItTheirs() = runRobolectricComposeUiTest {
        val intents = mutableListOf<SharedSettingsIntent>()
        showDetail(detail(computer(), canClaim = true), intents)
        onNodeWithTag(RAFT_COMPUTER_OWNER_PUBLIC_TAG, useUnmergedTree = true).assertExists()
        onNodeWithText("Public (no owner)").assertIsDisplayed()
        assertEquals(0, count("Transfer owner"))
        onNodeWithText("Make it mine").performClick()
        waitForIdle()
        assertEquals(listOf<SharedSettingsIntent>(SharedSettingsIntent.SetComputerOwner(SettingsComputerOwnerRequest("m-1", "user-1"))), intents)
    }

    @Test
    fun withoutRightsTheOwnerRowOnlyReads() = runRobolectricComposeUiTest {
        showDetail(detail(computer(ownerUserId = "user-2", owner = bea)))
        onNodeWithText("Bea").assertIsDisplayed()
        assertEquals(0, count("Transfer owner"))
        assertEquals(0, count("Make it mine"))
    }

    @Test
    fun transferHandsItToAPersonOrMakesItPublic() = runRobolectricComposeUiTest {
        val transfers = mutableListOf<String?>()
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                RaftTransferComputerOwnerDialog(
                    computerName = "Studio",
                    ownerUserId = "user-2",
                    candidates = listOf(RaftComputerOwnerCandidate("user-1", "Ann", isYou = true), RaftComputerOwnerCandidate("user-3", "Cy", isYou = false)),
                    saving = false,
                    failed = false,
                    onTransfer = { transfers += it },
                    onDismiss = {},
                )
            }
        }
        waitForIdle()
        onNodeWithText("Raft records one person as the owner of Studio. Choose who, or make it Public with no owner.").assertIsDisplayed()
        onNodeWithText("Claude and Codex logins and git credentials on this computer stay those of whoever is signed in on it. Transferring only changes who Raft records as its owner.").assertIsDisplayed()
        // Nobody picked yet: Transfer waits.
        onNodeWithTag(RAFT_TRANSFER_OWNER_CONFIRM_TAG).assertIsNotEnabled()
        onNodeWithText("Ann (you)").performClick()
        onNodeWithTag(RAFT_TRANSFER_OWNER_CONFIRM_TAG).assertIsEnabled().performClick()
        waitForIdle()
        onNodeWithText("Public (no owner)").performClick()
        waitForIdle()
        onNodeWithText("Make public").performClick()
        waitForIdle()
        assertEquals(listOf("user-1", null), transfers)
    }

    @Test
    fun theComputerCardSaysItWaitsForThisServersAgentsThenUpgrades() = runRobolectricComposeUiTest {
        showDetail(detail(computer(upgradeRequest = draining("a1", "a2"))))
        onNodeWithText("Giving 2 agents on this server a few seconds to finish, then upgrading to v1.0.53. Turns still running are interrupted.").assertIsDisplayed()
    }

    private fun ComposeUiTest.count(text: String, substring: Boolean = false): Int =
        onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().size
}
