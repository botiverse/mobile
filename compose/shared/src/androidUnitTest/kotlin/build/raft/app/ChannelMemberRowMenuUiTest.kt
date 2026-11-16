package build.raft.app

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
 * A channel member row's one ⋯ (task #triage:#416, mobile only): its menu holds the role item
 * (when the row has one) and Remove from channel (when the viewer may remove), each read by its
 * full sentence; a tap closes the menu and runs that action (Remove opens the page's confirm).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class ChannelMemberRowMenuUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private class Calls {
        var role = 0
        var remove = 0
    }

    private fun ComposeUiTest.show(menu: ChannelMemberRowMenu, calls: Calls) {
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                ChannelMemberRowMenuButton(
                    menu = menu,
                    memberName = "ada",
                    removeLabel = "Remove Ada",
                    busy = false,
                    onRoleAction = { calls.role++ },
                    onRemove = { calls.remove++ },
                )
            }
        }
        waitForIdle()
        onNodeWithTag(ChannelMemberRoleMenuTestTag).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.shown(tag: String): Boolean = onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theMenuHoldsTheRoleItemThenRemoveAndRemoveRunsOnce() = runRobolectricComposeUiTest {
        val calls = Calls()
        show(ChannelMemberRowMenu(ChannelMemberRoleAction.MakeAdmin, canRemove = true), calls)
        onNodeWithContentDescription("Make ada a channel admin").assertIsDisplayed()
        onNodeWithContentDescription("Remove Ada").assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(1, calls.remove)
        assertEquals(0, calls.role)
        assertTrue(!shown(ChannelMemberRemoveActionTestTag), "the menu closes after Remove")
    }

    @Test
    fun aRowWithOnlyRemoveStillUsesTheMenu() = runRobolectricComposeUiTest {
        val calls = Calls()
        show(ChannelMemberRowMenu(roleAction = null, canRemove = true), calls)
        assertTrue(shown(ChannelMemberRemoveActionTestTag), "Remove from channel is in the menu")
        assertTrue(!shown(ChannelMemberRoleActionTestTag), "no role item without a role action")
    }

    @Test
    fun aRowWithOnlyTheRoleActionHasNoRemove() = runRobolectricComposeUiTest {
        val calls = Calls()
        show(ChannelMemberRowMenu(ChannelMemberRoleAction.RemoveAdmin, canRemove = false), calls)
        onNodeWithContentDescription("Remove channel admin from ada").assertIsDisplayed().performClick()
        waitForIdle()
        assertEquals(1, calls.role)
        assertTrue(!shown(ChannelMemberRemoveActionTestTag), "no Remove when the viewer may not remove")
    }
}
