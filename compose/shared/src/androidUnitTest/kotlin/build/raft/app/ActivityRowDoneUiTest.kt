package build.raft.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.thread.key
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.unit.dp

/**
 * The Activity row's trailing Mark as Done button (web InboxRowDone; raft-design components/inbox-item.md):
 * with a mouse it shows, ghost, while the row is hovered or open beside the list; by touch there is none
 * at any width, phone or tablet (the swipe and the menu instead; xxchan 2026-11-22). Its click marks the
 * row done without opening it, and it is named after its row.
 * Drives [ActivityConversationListRow] on a real composition ([runRobolectricComposeUiTest]).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityRowDoneUiTest {
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

    private fun ComposeUiTest.showRow(
        calls: Calls,
        scale: RaftScale,
        pointerInput: RaftPointerInput,
        row: InboxItem = item,
        canMarkDone: Boolean = true,
        selected: Boolean = false,
    ) {
        // Compose string resources read the app context through the shared SQLDelight holder.
        build.raft.app.data.RaftAndroidSqlDatabaseContext.init(RuntimeEnvironment.getApplication())
        val context = ActivityConversationListContext(channelSymbolsByChannelId = mapOf("launch-week" to "#", "design" to "#"))
        setContent {
            RaftTheme(appearance = RaftAppearance.Light, scale = scale, pointerInput = pointerInput) {
                ActivityConversationListRow(
                    item = row,
                    model = row.activityConversationRowModel(context, InboxFilter.All),
                    divider = false,
                    selected = selected,
                    timeContext = null,
                    agentStatusResolver = EmptyRaftAgentStatusResolver,
                    canMarkDone = canMarkDone,
                    openGestureKey = row.key,
                    captureMenuAnchor = false,
                    onOpen = { calls.opens++ },
                    onOpenMenu = {},
                    modifier = Modifier.fillMaxWidth().testTag(ROW),
                    onSwipeDone = {},
                    onRowDone = { calls.dones++ },
                )
            }
        }
    }

    private fun look(expected: ActivityRowDoneButton) = SemanticsMatcher.expectValue(ActivityRowDoneLookKey, expected)

    @Test
    fun aMouseHoverShowsTheGhostButtonAndItsClickMarksDoneWithoutOpening() =
        runRobolectricComposeUiTest {
            val calls = Calls()
            showRow(calls, RaftScale.Wide, RaftPointerInput.Fine)
            onNodeWithTag(ActivityRowDoneTag).assertDoesNotExist()
            onNodeWithTag(ROW).performMouseInput { moveTo(center) }
            onNodeWithTag(ActivityRowDoneTag).assertExists().assert(look(ActivityRowDoneButton.Revealed))
            // web InboxRowDone under a fine pointer: the 28px bar button (RaftIconButtonSize.Row).
            onNodeWithTag(ActivityRowDoneTag).assertWidthIsEqualTo(28.dp).assertHeightIsEqualTo(28.dp)
            onNodeWithTag(ActivityRowDoneTag).performMouseInput { click(center) }
            mainClock.advanceTimeBy(1_000)
            assertEquals(1, calls.dones)
            assertEquals(0, calls.opens, "Done must not open the row")
            // The pointer leaves: the button goes.
            onNodeWithTag(ROW).performMouseInput { exit() }
            onNodeWithTag(ActivityRowDoneTag).assertDoesNotExist()
        }

    @Test
    fun aTabletByTouchHasNoDoneButtonOnTheRow() =
        runRobolectricComposeUiTest {
            showRow(Calls(), RaftScale.Wide, RaftPointerInput.Touch, selected = true)
            onNodeWithTag(ActivityRowDoneTag).assertDoesNotExist()
        }

    @Test
    fun visibilityRules() {
        fun done(pointer: RaftPointerInput, revealed: Boolean, canMarkDone: Boolean = true, hasAction: Boolean = true) =
            activityRowDoneButton(pointer, revealed, canMarkDone, hasAction)
        // Touch, phone or tablet: never, hover or not (the swipe and the press-and-hold menu).
        assertNull(done(RaftPointerInput.Touch, revealed = false))
        assertNull(done(RaftPointerInput.Touch, revealed = true))
        // A mouse or trackpad: only revealed (hovered, open, its menu open), at any width.
        assertEquals(ActivityRowDoneButton.Revealed, done(RaftPointerInput.Fine, revealed = true))
        assertNull(done(RaftPointerInput.Fine, revealed = false))
        // No Done on the row, or no action: never.
        assertNull(done(RaftPointerInput.Fine, revealed = true, canMarkDone = false))
        assertNull(done(RaftPointerInput.Fine, revealed = true, hasAction = false))
    }

    private companion object {
        const val ROW = "activity-row"
    }
}
