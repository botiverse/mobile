package build.raft.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Activity row's trailing Mark as Done button (web ThreadsInbox `InboxItemActions reveal="hover"`):
 * a mouse hovering the row shows it, a finger never does, and its click marks the row done without
 * opening it. Drives [ActivityConversationListRow] on a real composition ([runRobolectricComposeUiTest]).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityRowHoverDoneUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private class Calls {
        var opens = 0
        var dones = 0
    }

    private val item =
        InboxItem(
            key = "channel:launch",
            kind = InboxItemKind.Channel,
            channelId = ChannelId("launch-week"),
            channelName = "launch-week",
            unreadCount = 2,
            lastMessageSenderName = "Lagoon",
            lastMessageSenderKind = MemberKind.Agent,
            lastMessagePreview = "Hover the row for Done.",
            lastMessageAt = "2026-10-21T14:02:00Z",
        )

    private fun ComposeUiTest.showRow(calls: Calls, canMarkDone: Boolean = true) {
        // Compose string resources read the app context through the shared SQLDelight holder.
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        val context = ActivityConversationListContext(channelSymbolsByChannelId = mapOf("launch-week" to "#"))
        setContent {
            RaftTheme(appearance = RaftAppearance.Light) {
                ActivityConversationListRow(
                    item = item,
                    model = item.activityConversationRowModel(context, InboxFilter.All),
                    divider = false,
                    selected = false,
                    timeContext = null,
                    agentStatusResolver = EmptyRaftAgentStatusResolver,
                    canMarkDone = canMarkDone,
                    openGestureKey = item.key,
                    captureMenuAnchor = false,
                    onOpen = { calls.opens++ },
                    onOpenMenu = {},
                    modifier = Modifier.fillMaxWidth().testTag(ROW),
                    onSwipeDone = {},
                    onHoverDone = { calls.dones++ },
                )
            }
        }
    }

    @Test
    fun touchNeverShowsTheDoneButton() =
        runRobolectricComposeUiTest {
            val calls = Calls()
            showRow(calls)
            onNodeWithTag(ActivityRowHoverDoneTag).assertDoesNotExist()
            // A finger held on the row, then a tap: no hover, no button; the tap opens the row.
            onNodeWithTag(ROW).performTouchInput { down(center) }
            onNodeWithTag(ActivityRowHoverDoneTag).assertDoesNotExist()
            onNodeWithTag(ROW).performTouchInput { up() }
            onNodeWithTag(ROW).performTouchInput { click(center) }
            mainClock.advanceTimeBy(1_000)
            onNodeWithTag(ActivityRowHoverDoneTag).assertDoesNotExist()
            assertEquals(0, calls.dones)
            assertTrue(calls.opens >= 1, "a tap still opens the row")
        }

    @Test
    fun mouseHoverShowsTheButtonAndItsClickMarksDoneWithoutOpening() =
        runRobolectricComposeUiTest {
            val calls = Calls()
            showRow(calls)
            onNodeWithTag(ROW).performMouseInput { moveTo(center) }
            onNodeWithTag(ActivityRowHoverDoneTag).assertExists()
            // A mouse click on the button.
            onNodeWithTag(ActivityRowHoverDoneTag).performMouseInput { click(center) }
            mainClock.advanceTimeBy(1_000)
            assertEquals(1, calls.dones)
            assertEquals(0, calls.opens, "Done must not open the row")
            // The pointer leaves: the button goes.
            onNodeWithTag(ROW).performMouseInput { exit() }
            onNodeWithTag(ActivityRowHoverDoneTag).assertDoesNotExist()
        }

    @Test
    fun aRowWithoutDoneShowsNoButtonOnHover() =
        runRobolectricComposeUiTest {
            showRow(Calls(), canMarkDone = false)
            onNodeWithTag(ROW).performMouseInput { moveTo(center) }
            onNodeWithTag(ActivityRowHoverDoneTag).assertDoesNotExist()
        }

    @Test
    fun showsOnlyForAHoveringPointerOnARowThatOffersDone() {
        assertTrue(activityRowShowsHoverDone(pointerHovering = true, canMarkDone = true, hasAction = true))
        assertFalse(activityRowShowsHoverDone(pointerHovering = false, canMarkDone = true, hasAction = true))
        assertFalse(activityRowShowsHoverDone(pointerHovering = true, canMarkDone = false, hasAction = true))
        assertFalse(activityRowShowsHoverDone(pointerHovering = true, canMarkDone = true, hasAction = false))
    }

    private companion object {
        const val ROW = "activity-row"
    }
}
