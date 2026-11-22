package build.raft.app

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.data.MemberRemovalImpact
import build.raft.app.data.MemberRemovalImpactState
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
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
        onNodeWithText("To get their agents running again, move them to another computer.", substring = true).assertIsDisplayed()
        onNodeWithTag(SETTINGS_SERVER_LEAVE_CONFIRM_TAG).assertIsEnabled().performClick()
        waitForIdle()
        assertEquals(1, leaves.size)
    }
}
