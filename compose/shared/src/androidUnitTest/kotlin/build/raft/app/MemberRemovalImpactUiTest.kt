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
import build.raft.app.data.DataResult
import build.raft.app.data.MemberRemovalImpact
import build.raft.app.data.MemberRemovalImpactRepository
import build.raft.app.data.MemberRemovalImpactState
import build.raft.app.model.ServerId
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * botiverse/slock #13547 on a real composition: the Leave server and Remove member confirmations
 * say which Computers go offline, with the web's copy; the confirm waits for the read and stays
 * available when it fails.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class MemberRemovalImpactUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun ComposeUiTest.showLeave(impact: MemberRemovalImpactState, leaves: MutableList<Unit> = mutableListOf()) {
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                SettingsServerLeaveConfirmationDialog(
                    serverName = "Acme",
                    impact = impact,
                    leaveBusy = false,
                    errorMessage = null,
                    onDismiss = {},
                    onLeave = { leaves += Unit },
                )
            }
        }
        waitForIdle()
    }

    @Test
    fun leavingSaysWhichComputersYouAttachedGoOfflineAndWhoseAgentsGoToo() = runRobolectricComposeUiTest {
        val leaves = mutableListOf<Unit>()
        showLeave(MemberRemovalImpactState.Ready(MemberRemovalImpact(2, 3, 1, listOf("Alpha", "Beta"))), leaves)
        onNodeWithText("Leave server").assertIsDisplayed()
        onNodeWithText("You'll lose access to Acme and all of its channels. You can be re-invited later.").assertIsDisplayed()
        onNodeWithText(
            "Leaving takes the 2 Computers you attached offline: Alpha and Beta. 1 agent on them belongs to other people and goes offline too.",
            substring = true,
        ).assertIsDisplayed()
        onNodeWithText("To bring them back, someone who stays in this server sets up that machine again.", substring = true).assertIsDisplayed()
        onNodeWithTag(SETTINGS_SERVER_LEAVE_CONFIRM_TAG).assertIsEnabled().performClick()
        waitForIdle()
        assertEquals(1, leaves.size)
    }

    @Test
    fun nothingAttachedAddsNothing() = runRobolectricComposeUiTest {
        showLeave(MemberRemovalImpactState.Ready(MemberRemovalImpact(0, 0, 0, emptyList())))
        assertEquals(0, count("offline", substring = true))
        onNodeWithTag(SETTINGS_SERVER_LEAVE_CONFIRM_TAG).assertIsEnabled()
    }

    @Test
    fun theConfirmWaitsForTheReadAndAFailedReadLeavesItAvailable() = runRobolectricComposeUiTest {
        showLeave(MemberRemovalImpactState.Loading)
        onNodeWithText("Checking which Computers this takes offline…").assertIsDisplayed()
        onNodeWithTag(SETTINGS_SERVER_LEAVE_CONFIRM_TAG).assertIsNotEnabled()
    }

    @Test
    fun aFailedReadSaysSo() = runRobolectricComposeUiTest {
        showLeave(MemberRemovalImpactState.Failed)
        onNodeWithText("Couldn't check which Computers this takes offline.").assertIsDisplayed()
        onNodeWithTag(SETTINGS_SERVER_LEAVE_CONFIRM_TAG).assertIsEnabled()
    }

    @Test
    fun removingNamesTheMemberAndReadsTheRestAsOthers() = runRobolectricComposeUiTest {
        val removes = mutableListOf<Unit>()
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                MembersRemoveMemberConfirmDialog(
                    personName = "Bea",
                    impact = MemberRemovalImpactState.Ready(MemberRemovalImpact(5, 2, 0, listOf("Alpha", "Beta", "Gamma"))),
                    removing = false,
                    errorMessage = null,
                    onDismiss = {},
                    onConfirm = { removes += Unit },
                )
            }
        }
        waitForIdle()
        onNodeWithText("Remove member").assertIsDisplayed()
        onNodeWithTag(MEMBER_REMOVAL_IMPACT_TAG).assertIsDisplayed()
        onNodeWithText("Bea will be removed from every channel on this server.", substring = true).assertIsDisplayed()
        onNodeWithText("Removing Bea takes 5 Computers offline: Alpha, Beta, Gamma, and others.", substring = true).assertIsDisplayed()
        assertEquals(0, count("belong to other people", substring = true))
        onNodeWithTag(MEMBER_REMOVE_CONFIRM_TAG).performClick()
        waitForIdle()
        assertEquals(1, removes.size)
    }

    @Test
    fun theDialogReadsTheImpactWhenItOpens() = runRobolectricComposeUiTest {
        val answer = CompletableDeferred<DataResult<MemberRemovalImpact>>()
        val asked = mutableListOf<Pair<String, String>>()
        val repository =
            object : MemberRemovalImpactRepository {
                override suspend fun removalImpact(serverId: ServerId, memberId: String): DataResult<MemberRemovalImpact> {
                    asked += serverId.value to memberId
                    return answer.await()
                }
            }
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                MembersRemoveMemberConfirmDialog(
                    personName = "Bea",
                    impact = rememberMemberRemovalImpact(repository, serverId = "server-1", memberId = "user-2"),
                    removing = false,
                    errorMessage = null,
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }
        waitForIdle()
        assertEquals(listOf("server-1" to "user-2"), asked)
        onNodeWithTag(MEMBER_REMOVE_CONFIRM_TAG).assertIsNotEnabled()
        answer.complete(DataResult.Content(MemberRemovalImpact(1, 1, 1, listOf("Studio"))))
        waitForIdle()
        onNodeWithText(
            "Removing Bea takes 1 Computer offline: Studio. 1 agent on them belongs to other people and goes offline too.",
            substring = true,
        ).assertIsDisplayed()
        onNodeWithText("To bring it back,", substring = true).assertIsDisplayed()
        onNodeWithTag(MEMBER_REMOVE_CONFIRM_TAG).assertIsEnabled()
    }

    private fun ComposeUiTest.count(text: String, substring: Boolean = false): Int =
        onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().size
}
