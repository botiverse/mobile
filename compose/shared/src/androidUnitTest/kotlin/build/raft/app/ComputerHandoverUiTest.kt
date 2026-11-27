package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.data.COMPUTER_HANDOVER_MIN_COMPUTER_VERSION
import build.raft.app.data.ComputerHandoverPerson
import build.raft.app.data.ComputerHandoverState
import build.raft.app.data.ComputerHandoverView
import build.raft.app.data.DataResult
import build.raft.app.data.MemberHandedOverComputer
import build.raft.app.data.MemberRemovalImpact
import build.raft.app.data.MemberRemovalImpactState
import build.raft.app.data.ScreenError
import build.raft.app.data.ScreenLoadState
import build.raft.app.data.SettingsComputerCommandContext
import build.raft.app.data.SettingsComputerCreatorSnapshot
import build.raft.app.data.SettingsComputerDetailSnapshot
import build.raft.app.data.SettingsComputerSnapshot
import build.raft.app.members.MEMBER_REMOVAL_IMPACT_HANDED_OVER_TAG
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.settings.SETTINGS_SERVER_LEAVE_CONFIRM_TAG
import build.raft.app.settings.SettingsServerLeaveConfirmationDialog
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric Compose UI parity tests for botiverse/slock #16435 / #16445 / #16452
 * (web `computerHandover.behavior.test.tsx`, `MachineInfoSection.tsx`, `HandOverComputerDialog.tsx`,
 * `ComputerHandoverNotice.tsx`, `HandedOverComputersNote`):
 * - "Attached by" / "No one" copy on the Computer detail page (#16452).
 * - Hand over dialog: owners & admins only, sign-ins warning (yours vs theirs), registrant moves
 *   banner, accept command with server slug, failure retention, and successful designate.
 * - Upgrade-first banner naming `1.25.16` (#16619) when below the handover version.
 * - Pending handover notice for plain viewer (no cancel), attacher ("Cancel handover"), and
 *   designee ("Handing over to you", accept command, "Decline").
 * - Leave server dialog non-blocking warning naming multiple handed-over Computers.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h2400dp")
class ComputerHandoverUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val attacherId = "user-attacher"
    private val adminId = "user-admin"
    private val memberId = "user-member"
    private val ownerId = "user-owner"

    private val attacherSnapshot = SettingsComputerCreatorSnapshot(id = attacherId, name = "attacher", displayName = "Attacher")

    private val members =
        listOf(
            MemberProfile(id = MemberId(attacherId), displayName = "Attacher", handle = "attacher", kind = MemberKind.Human, serverRole = "member"),
            MemberProfile(id = MemberId(adminId), displayName = "Ada Admin", handle = "ada", kind = MemberKind.Human, serverRole = "admin"),
            MemberProfile(id = MemberId(memberId), displayName = "Plain Member", handle = "pat", kind = MemberKind.Human, serverRole = "member"),
            MemberProfile(id = MemberId(ownerId), displayName = "Olga Owner", handle = "olga", kind = MemberKind.Human, serverRole = "owner"),
            MemberProfile(id = MemberId("user-guest"), displayName = "Gus Guest", handle = "gus", kind = MemberKind.Human, serverRole = "guest"),
        )

    private val readyState =
        ComputerHandoverState(
            handover = null,
            canDesignate = true,
            canCancel = false,
            upgradeRequired = false,
            minimumComputerVersion = COMPUTER_HANDOVER_MIN_COMPUTER_VERSION,
        )

    private val pendingState =
        readyState.copy(
            handover =
                ComputerHandoverView(
                    id = "h-1",
                    to = ComputerHandoverPerson(id = adminId, name = "ada", displayName = "Ada Admin"),
                    fromUserId = attacherId,
                    createdBy = attacherId,
                    createdAt = "2026-11-23T10:00:00Z",
                    expiresAt = "2026-11-24T10:00:00Z",
                ),
        )

    private val fixedTimeContext =
        RaftTimeContext(
            localOffsetMinutes = 0,
            todayEpochDay = raftEpochDayForDate(2026, 11, 23),
            nowEpochMillis = 1_795_428_000_000L,
        )

    private fun detailSnapshot(
        currentUserId: String,
        creator: SettingsComputerCreatorSnapshot? = attacherSnapshot,
        creatorUserId: String? = attacherId,
    ): SettingsComputerDetailSnapshot =
        SettingsComputerDetailSnapshot(
            computer =
                SettingsComputerSnapshot(
                    id = "m-1",
                    name = "studio-mac",
                    daemonVersion = "",
                    computerVersion = COMPUTER_HANDOVER_MIN_COMPUTER_VERSION,
                    isComputer = true,
                    online = true,
                    creatorUserId = creatorUserId,
                    creator = creator,
                    canManage = true,
                ),
            workspaces = emptyList(),
            agents = emptyList(),
            sections = emptyList(),
            canManageMachines = true,
            canEditMachine = true,
            commandContext = SettingsComputerCommandContext(serverSlug = "home", deploymentEnv = "production"),
            currentUserId = currentUserId,
        )

    private fun ComposeUiTest.showDetail(
        snapshot: SettingsComputerDetailSnapshot,
        handoverState: ComputerHandoverState?,
        onDesignate: (suspend (String) -> DataResult<ComputerHandoverState>)? = { DataResult.Content(pendingState) },
        onCancel: (suspend () -> DataResult<ComputerHandoverState>)? = { DataResult.Content(readyState) },
    ) {
        val model =
            raftComputerDetailModel(
                ScreenLoadState.Content(snapshot),
                machineId = "m-1",
                targetServerId = null,
                currentServerId = "server-1",
            )
        setContent {
            CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(fixedTimeContext)) {
                RaftTheme(appearance = RaftAppearance.Light) {
                    RaftComputersTheme {
                        RaftSettingsComputerDetailContent(
                            model = model,
                            actionState = null,
                            onRetry = {},
                            onIntent = {},
                            onOpenAgent = {},
                            ownerMembers = members,
                            handoverState = handoverState,
                            onDesignateHandover = onDesignate,
                            onCancelHandover = onCancel,
                        )
                    }
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun computerInfoUsesAttachedByAndNoOneCopy() = runRobolectricComposeUiTest {
        showDetail(detailSnapshot(currentUserId = memberId, creator = null), handoverState = null)
        onNodeWithText("Attached by").assertIsDisplayed()
        onNodeWithText("No one").assertIsDisplayed()
        assertEquals(0, count("Creator"))
        assertEquals(0, count("No creator"))
    }

    @Test
    fun attacherHandsOverToAdminWithRegistrantAndSignInWarnings() = runRobolectricComposeUiTest {
        val designated = mutableListOf<String>()
        showDetail(
            snapshot = detailSnapshot(currentUserId = attacherId, creatorUserId = attacherId),
            handoverState = readyState,
            onDesignate = { userId ->
                designated += userId
                DataResult.Content(pendingState)
            },
        )
        onNodeWithText("Attached by").assertIsDisplayed()
        onNodeWithTag(RAFT_COMPUTER_HANDOVER_BUTTON_TAG).assertIsDisplayed().performClick()
        waitForIdle()

        onNodeWithTag(RAFT_HANDOVER_DIALOG_TAG).assertIsDisplayed()
        onNodeWithText("Hand over this Computer").assertIsDisplayed()
        onNodeWithTag(RAFT_HANDOVER_HOW_TAG).assertIsDisplayed()
        onNodeWithText("raft-computer handover accept /home on that machine", substring = true).assertIsDisplayed()
        onNodeWithTag(RAFT_HANDOVER_REGISTRANT_TAG).assertIsDisplayed()
        onNodeWithText("registered to the person who attached it, so it becomes registered to the new person too", substring = true)
            .assertIsDisplayed()
        onNodeWithTag(RAFT_HANDOVER_SIGN_INS_TAG).assertIsDisplayed()
        onNodeWithText("Your Claude and Codex sign-ins on studio-mac stay in use there after the handover: sign out of them on that machine.")
            .assertIsDisplayed()

        // Only owners and admins other than the attacher are offered.
        onNodeWithTag(raftHandOverPersonTag(adminId)).assertIsDisplayed()
        onNodeWithTag(raftHandOverPersonTag(ownerId)).assertIsDisplayed()
        onAllNodesWithTag(raftHandOverPersonTag(memberId)).assertCountEquals(0)
        onAllNodesWithTag(raftHandOverPersonTag(attacherId)).assertCountEquals(0)
        onAllNodesWithTag(raftHandOverPersonTag("user-guest")).assertCountEquals(0)

        // Confirm stays disabled until someone is chosen.
        onNodeWithTag(RAFT_HANDOVER_CONFIRM_TAG).assertIsNotEnabled()
        onNodeWithTag(raftHandOverPersonTag(adminId)).performClick()
        waitForIdle()
        onNodeWithTag(RAFT_HANDOVER_CONFIRM_TAG).assertIsEnabled().performClick()
        waitForIdle()

        assertEquals(listOf(adminId), designated)
        onAllNodesWithTag(RAFT_HANDOVER_DIALOG_TAG).assertCountEquals(0)
    }

    @Test
    fun ownerHandingOverSomeoneElsesComputerSeesTheirsSignInWarningAndCanPickSelf() = runRobolectricComposeUiTest {
        showDetail(
            snapshot = detailSnapshot(currentUserId = ownerId, creatorUserId = "legacy-registrant"),
            handoverState = readyState,
            onDesignate = { DataResult.Error(ScreenError("handover_designee_ineligible")) },
        )
        onNodeWithTag(RAFT_COMPUTER_HANDOVER_BUTTON_TAG).performClick()
        waitForIdle()

        onNodeWithText("Attacher's Claude and Codex sign-ins on studio-mac stay in use there after the handover. Raft tells Attacher to sign out of them on that machine.")
            .assertIsDisplayed()
        // Machine row registrant differs from attacher -> registrant banner hidden.
        onAllNodesWithTag(RAFT_HANDOVER_REGISTRANT_TAG).assertCountEquals(0)
        // Admin/owner may designate themselves.
        onNodeWithText("Olga Owner (you)").assertIsDisplayed().performClick()
        waitForIdle()
        onNodeWithTag(RAFT_HANDOVER_CONFIRM_TAG).performClick()
        waitForIdle()
        // Refusal keeps the dialog open with the error banner.
        onNodeWithTag(RAFT_HANDOVER_DIALOG_TAG).assertIsDisplayed()
        onNodeWithTag(RAFT_HANDOVER_FAILED_TAG).assertIsDisplayed()
        onNodeWithText("Couldn't hand over this Computer. Check who you chose and try again.").assertIsDisplayed()
    }

    @Test
    fun belowHandoverVersionHidesButtonAndShowsUpgradeFirstWithFloor12516() = runRobolectricComposeUiTest {
        showDetail(
            snapshot = detailSnapshot(currentUserId = attacherId),
            handoverState = readyState.copy(upgradeRequired = true),
        )
        onAllNodesWithTag(RAFT_COMPUTER_HANDOVER_BUTTON_TAG).assertCountEquals(0)
        onNodeWithTag(RAFT_COMPUTER_HANDOVER_UPGRADE_FIRST_TAG).assertIsDisplayed()
        onNodeWithText("Upgrade this Computer first: handing it over needs Raft Computer 1.25.16 or later.")
            .assertIsDisplayed()
    }

    @Test
    fun pendingHandoverShowsViewerNoticeWithoutCancel() = runRobolectricComposeUiTest {
        showDetail(
            snapshot = detailSnapshot(currentUserId = memberId),
            handoverState = pendingState.copy(canDesignate = false, canCancel = false),
        )
        onNodeWithTag(RAFT_COMPUTER_HANDOVER_PENDING_TAG).assertIsDisplayed()
        onNodeWithText("Handing over to Ada Admin").assertIsDisplayed()
        onNodeWithText("Ada Admin has until Nov 24, 2026, 10:00 to sign in on this machine; then it is attached by them.")
            .assertIsDisplayed()
        onAllNodesWithTag(RAFT_COMPUTER_HANDOVER_CANCEL_TAG).assertCountEquals(0)
    }

    @Test
    fun pendingHandoverAllowsAttacherToCancel() = runRobolectricComposeUiTest {
        var cancelCalls = 0
        showDetail(
            snapshot = detailSnapshot(currentUserId = attacherId),
            handoverState = pendingState.copy(canCancel = true),
            onCancel = {
                cancelCalls += 1
                DataResult.Content(readyState)
            },
        )
        onNodeWithText("Handing over to Ada Admin").assertIsDisplayed()
        onNodeWithTag(RAFT_COMPUTER_HANDOVER_CANCEL_TAG).assertIsDisplayed()
        onNodeWithText("Cancel handover").performClick()
        waitForIdle()
        assertEquals(1, cancelCalls)
    }

    @Test
    fun pendingHandoverShowsDesigneeInstructionsAndDecline() = runRobolectricComposeUiTest {
        var cancelCalls = 0
        showDetail(
            snapshot = detailSnapshot(currentUserId = adminId),
            handoverState = pendingState.copy(canDesignate = true, canCancel = true),
            onCancel = {
                cancelCalls += 1
                DataResult.Content(readyState)
            },
        )
        onNodeWithText("Handing over to you").assertIsDisplayed()
        onNodeWithText("raft-computer handover accept /home on this machine", substring = true).assertIsDisplayed()
        onNodeWithText("Claude and Codex sign-ins to your own", substring = true).assertIsDisplayed()
        onNodeWithTag(RAFT_COMPUTER_HANDOVER_CANCEL_TAG).assertIsDisplayed()
        onNodeWithText("Decline").performClick()
        waitForIdle()
        assertEquals(1, cancelCalls)
    }

    @Test
    fun leaveDialogListsMultipleHandedOverComputersAndDoesNotBlockLeave() = runRobolectricComposeUiTest {
        val leaves = mutableListOf<Unit>()
        val impact =
            MemberRemovalImpactState.Ready(
                MemberRemovalImpact(
                    computers = 0,
                    agents = 0,
                    othersAgents = 0,
                    computerNames = emptyList(),
                    blocking = false,
                    attachedComputers = emptyList(),
                    handedOverComputers =
                        listOf(
                            MemberHandedOverComputer("c-1", "m-1", "studio-mac"),
                            MemberHandedOverComputer("c-2", "m-2", "build-box"),
                        ),
                )
            )
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                SettingsServerLeaveConfirmationDialog(
                    serverName = "Home",
                    impact = impact,
                    leaveBusy = false,
                    errorMessage = null,
                    onDismiss = {},
                    onLeave = { leaves += Unit },
                )
            }
        }
        waitForIdle()
        onNodeWithTag(MEMBER_REMOVAL_IMPACT_HANDED_OVER_TAG).assertIsDisplayed()
        onNodeWithText(
            "You handed over studio-mac and build-box here. Your Claude and Codex sign-ins on them stay in use: sign out of them on each machine.",
        ).assertIsDisplayed()
        onNodeWithTag(SETTINGS_SERVER_LEAVE_CONFIRM_TAG).assertIsEnabled().performClick()
        waitForIdle()
        assertEquals(1, leaves.size)
    }

    private fun ComposeUiTest.count(text: String, substring: Boolean = false): Int =
        onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().size
}
