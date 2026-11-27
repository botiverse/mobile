package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.DataResult
import build.raft.app.data.MEMBER_HAS_COMPUTER_REFUSAL_CODE
import build.raft.app.data.MemberAttachedComputer
import build.raft.app.data.MemberHandedOverComputer
import build.raft.app.data.MemberRemovalImpact
import build.raft.app.data.MemberRemovalImpactRepository
import build.raft.app.data.MemberRemovalImpactState
import build.raft.app.data.SERVER_ACTION_LEAVE
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.members.MEMBER_REMOVAL_IMPACT_COMPUTER_TAG
import build.raft.app.members.MEMBER_REMOVAL_IMPACT_DELETE_COMPUTER_TAG
import build.raft.app.members.MEMBER_REMOVAL_IMPACT_HANDED_OVER_TAG
import build.raft.app.members.MEMBER_REMOVE_CONFIRM_TAG
import build.raft.app.members.MembersRemoveMemberConfirmDialog
import build.raft.app.members.rememberMemberRemovalImpact
import build.raft.app.model.ServerId
import build.raft.app.settings.RaftSharedSettingsPayload
import build.raft.app.settings.SETTINGS_SERVER_LEAVE_CONFIRM_TAG
import build.raft.app.settings.SettingsServerLeaveConfirmationDialog
import build.raft.app.settings.SettingsServerLeaveImpactBinding
import build.raft.app.settings.SharedSettingsServerContent
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * botiverse/slock #13547, #16386, #16435 on real compositions:
 * - Leave server lists blocking Computers with "Delete computer" and disables Leave, shows
 *   non-blocking handed-over Computers reminder, keeps legacy offline warning, and re-reads
 *   removal impact on `409 member_has_computer`.
 * - Remove member lists blocking Computers (no link, "Can't be reconnected") and offers one-step
 *   "Disconnect these computers and remove" sending those Computer ids, and re-reads on 409.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class MemberRemovalImpactUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val blockingOne =
        MemberRemovalImpact(
            computers = 1,
            agents = 3,
            othersAgents = 2,
            computerNames = listOf("studio-mac"),
            blocking = true,
            attachedComputers = listOf(MemberAttachedComputer("c-1", "m-1", "studio-mac", agentCount = 3, othersAgentCount = 2)),
        )

    private val blockingTwo =
        MemberRemovalImpact(
            computers = 2,
            agents = 3,
            othersAgents = 2,
            computerNames = listOf("studio-mac", "new-box"),
            blocking = true,
            attachedComputers =
                listOf(
                    MemberAttachedComputer("c-1", "m-1", "studio-mac", agentCount = 3, othersAgentCount = 2),
                    MemberAttachedComputer("c-2", "m-2", "new-box", agentCount = 0, othersAgentCount = 0),
                ),
        )

    private val noImpact =
        MemberRemovalImpact(
            computers = 0,
            agents = 0,
            othersAgents = 0,
            computerNames = emptyList(),
            blocking = false,
            attachedComputers = emptyList(),
        )

    private fun ComposeUiTest.showLeave(
        impact: MemberRemovalImpactState,
        leaves: MutableList<Unit> = mutableListOf(),
        openedMachines: MutableList<String> = mutableListOf(),
        errorMessage: String? = null,
    ) {
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                SettingsServerLeaveConfirmationDialog(
                    serverName = "Acme",
                    impact = impact,
                    leaveBusy = false,
                    errorMessage = errorMessage,
                    onDeleteComputer = { openedMachines += it },
                    onDismiss = {},
                    onLeave = { leaves += Unit },
                )
            }
        }
        waitForIdle()
    }

    @Test
    fun leavingSaysWhichLegacyMachinesGoOfflineAndWhoseAgentsGoToo() = runRobolectricComposeUiTest {
        val leaves = mutableListOf<Unit>()
        showLeave(MemberRemovalImpactState.Ready(MemberRemovalImpact(2, 3, 1, listOf("Alpha", "Beta"))), leaves)
        onNodeWithText("Leave server").assertIsDisplayed()
        onNodeWithText("You'll lose access to Acme and all of its channels. You can be re-invited later.").assertIsDisplayed()
        onNodeWithText(
            "Leaving takes the 2 Computers you attached offline: Alpha and Beta. 1 agent on them belongs to other people and goes offline too.",
            substring = true,
        ).assertIsDisplayed()
        onNodeWithText("To get their agents running again, move them to another computer.", substring = true).assertIsDisplayed()
        onNodeWithTag(SETTINGS_SERVER_LEAVE_CONFIRM_TAG).assertIsEnabled().performClick()
        waitForIdle()
        assertEquals(1, leaves.size)
    }

    @Test
    fun leavingWithBlockingComputersListsThemWithDeleteLinkAndDisablesLeave() = runRobolectricComposeUiTest {
        val leaves = mutableListOf<Unit>()
        val opened = mutableListOf<String>()
        showLeave(MemberRemovalImpactState.Ready(blockingOne), leaves, opened)
        onNodeWithText("You attached 1 Computer here: studio-mac (3 agents in all). Delete it before you can leave.").assertIsDisplayed()
        onNodeWithText("studio-mac").assertIsDisplayed()
        onNodeWithText("3 agents, 2 of them other people's").assertIsDisplayed()
        onNodeWithTag(SETTINGS_SERVER_LEAVE_CONFIRM_TAG).assertIsNotEnabled()
        onNodeWithTag(MEMBER_REMOVAL_IMPACT_DELETE_COMPUTER_TAG).assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(listOf("m-1"), opened)
        assertEquals(0, leaves.size)
    }

    @Test
    fun leavingWithHandedOverComputersShowsNonBlockingSignOutReminder() = runRobolectricComposeUiTest {
        val leaves = mutableListOf<Unit>()
        val impact =
            noImpact.copy(
                handedOverComputers = listOf(MemberHandedOverComputer("c-9", "m-9", "old-mac")),
            )
        showLeave(MemberRemovalImpactState.Ready(impact), leaves)
        onNodeWithTag(MEMBER_REMOVAL_IMPACT_HANDED_OVER_TAG).assertIsDisplayed()
        onNodeWithText(
            "You handed over old-mac here. Your Claude and Codex sign-ins on it stay in use: sign out of them on that machine.",
        ).assertIsDisplayed()
        onNodeWithTag(SETTINGS_SERVER_LEAVE_CONFIRM_TAG).assertIsEnabled().performClick()
        waitForIdle()
        assertEquals(1, leaves.size)
    }

    @Test
    fun leaving409MemberHasComputerShowsRefusalAndRereadsBlockingComputers() = runRobolectricComposeUiTest {
        var reads = 0
        val repository =
            object : MemberRemovalImpactRepository {
                override suspend fun removalImpact(serverId: ServerId, memberId: String): DataResult<MemberRemovalImpact> {
                    reads += 1
                    return DataResult.Content(if (reads == 1) noImpact else blockingOne)
                }
            }
        val refusal = "You attached 1 Computer on this server. Delete it before leaving."
        setContent {
            var actionState by remember { mutableStateOf<SharedSettingsActionState?>(null) }
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftTheme(appearance = RaftAppearance.Light) {
                    SharedSettingsServerContent(
                        payload = RaftSharedSettingsPayload(instanceId = "leave-409-test", heroLabel = "Acme"),
                        profileName = "Acme",
                        onProfileNameChange = {},
                        serverSnapshot = null,
                        serverRole = "member",
                        actionState = actionState,
                        showProfileCard = false,
                        leaveImpact =
                            SettingsServerLeaveImpactBinding(
                                repository = repository,
                                serverId = "server-1",
                                currentUserId = "user-1",
                            ),
                        onAction = { action, _ ->
                            if (action == SERVER_ACTION_LEAVE) {
                                actionState =
                                    SharedSettingsActionState(
                                        action = SERVER_ACTION_LEAVE,
                                        inProgress = false,
                                        errorCode = "http_409",
                                        screenError =
                                            ScreenError(
                                                code = "http_409",
                                                retryable = true,
                                                serverMessage = refusal,
                                                serverCode = MEMBER_HAS_COMPUTER_REFUSAL_CODE,
                                            ),
                                    )
                            }
                        },
                    )
                }
            }
        }
        waitForIdle()
        // Open Leave server dialog from the Danger Zone card.
        onNode(hasText("Leave server") and hasClickAction()).performClick()
        waitForIdle()
        assertEquals(1, reads)
        // Initially no blocking Computers: Leave confirm is enabled.
        onNodeWithTag(SETTINGS_SERVER_LEAVE_CONFIRM_TAG).assertIsEnabled().performClick()
        waitForIdle()
        // 409 member_has_computer shows refusal AND re-reads removal impact -> now lists studio-mac and disables Leave.
        assertEquals(2, reads)
        onNodeWithText(refusal).assertIsDisplayed()
        onNodeWithText("studio-mac").assertIsDisplayed()
        onNodeWithTag(SETTINGS_SERVER_LEAVE_CONFIRM_TAG).assertIsNotEnabled()
    }

    @Test
    fun removeMemberWithBlockingComputersOffersOneStepDisconnectAndSendsIds() = runRobolectricComposeUiTest {
        val confirmedIds = mutableListOf<List<String>>()
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                MembersRemoveMemberConfirmDialog(
                    personName = "Dana",
                    impact = MemberRemovalImpactState.Ready(blockingOne),
                    removing = false,
                    errorMessage = null,
                    onDismiss = {},
                    onConfirm = { confirmedIds += it },
                )
            }
        }
        waitForIdle()
        onNodeWithText("Dana attached 1 Computer here: studio-mac (3 agents in all).").assertIsDisplayed()
        onNodeWithText("studio-mac").assertIsDisplayed()
        onNodeWithText("3 agents, 2 of them other people's").assertIsDisplayed()
        onNodeWithText("Can't be reconnected afterwards: setting it up again adds a new Computer.").assertIsDisplayed()
        onAllNodesWithTag(MEMBER_REMOVAL_IMPACT_DELETE_COMPUTER_TAG).assertCountEquals(0)
        onNodeWithText(
            "Confirming disconnects the Computers listed above, then removes Dana. Their agents stay on them, offline, and have to be moved to another computer.",
        ).assertIsDisplayed()
        onNodeWithText("Disconnect these computers and remove").assertIsDisplayed()
        onNodeWithTag(MEMBER_REMOVE_CONFIRM_TAG).assertIsEnabled().performClick()
        waitForIdle()
        assertEquals(listOf(listOf("c-1")), confirmedIds)
    }

    @Test
    fun removeMemberWithoutBlockingComputersSendsEmptyRevokeList() = runRobolectricComposeUiTest {
        val confirmedIds = mutableListOf<List<String>>()
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                MembersRemoveMemberConfirmDialog(
                    personName = "Dana",
                    impact = MemberRemovalImpactState.Ready(noImpact),
                    removing = false,
                    errorMessage = null,
                    onDismiss = {},
                    onConfirm = { confirmedIds += it },
                )
            }
        }
        waitForIdle()
        onNodeWithText("Remove").assertIsDisplayed()
        onNodeWithTag(MEMBER_REMOVE_CONFIRM_TAG).assertIsEnabled().performClick()
        waitForIdle()
        assertEquals(listOf(emptyList()), confirmedIds)
    }

    @Test
    fun removeMember409MemberHasComputerRefreshesBlockingListAndShowsRefusal() = runRobolectricComposeUiTest {
        var reads = 0
        val repository =
            object : MemberRemovalImpactRepository {
                override suspend fun removalImpact(serverId: ServerId, memberId: String): DataResult<MemberRemovalImpact> {
                    reads += 1
                    return DataResult.Content(if (reads == 1) blockingOne else blockingTwo)
                }
            }
        val refusal = "This member attached 2 Computers on this server. Hand them over or delete them before removing them."
        setContent {
            var revision by remember { mutableStateOf(0) }
            var errorMessage by remember { mutableStateOf<String?>(null) }
            val impact =
                rememberMemberRemovalImpact(
                    repository = repository,
                    serverId = "server-1",
                    memberId = "member-1",
                    revision = revision,
                )
            RaftTheme(appearance = RaftAppearance.Light) {
                MembersRemoveMemberConfirmDialog(
                    personName = "Dana",
                    impact = impact,
                    removing = false,
                    errorMessage = errorMessage,
                    onDismiss = {},
                    onConfirm = {
                        errorMessage = refusal
                        revision += 1
                    },
                )
            }
        }
        waitForIdle()
        assertEquals(1, reads)
        onAllNodesWithTag(MEMBER_REMOVAL_IMPACT_COMPUTER_TAG).assertCountEquals(1)
        onNodeWithTag(MEMBER_REMOVE_CONFIRM_TAG).performClick()
        waitForIdle()
        assertEquals(2, reads)
        onNodeWithText(refusal).assertIsDisplayed()
        onAllNodesWithTag(MEMBER_REMOVAL_IMPACT_COMPUTER_TAG).assertCountEquals(2)
        onNodeWithText("new-box").assertIsDisplayed()
        onNodeWithText("No agents").assertIsDisplayed()
    }
}
