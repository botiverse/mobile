package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.data.InboxTaskConversation
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageId
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.raftEpochDayForDate
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Task v2 Activity rows (web ThreadsInbox task row), light and dark: titled by the task's own title,
 * the body naming the task's address (`#channel:#7 ·`) before the latest reply, the `list-todo` mark
 * in the leading corner and the status badge (`sm`) in the marks; an unread followed row, a read one,
 * then a followed row swiped open on Done and Unfollow and an unread one open on Read. Verify / record
 * as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h420dp-xxhdpi")
class ActivityTaskConversationRowsScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun taskRowsLight() = capture("screens.activity.task-rows", RaftAppAppearance.Light)

    @Test
    fun taskRowsDark() = capture("screens.activity.task-rows.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftTheme(appearance = if (appearance == RaftAppAppearance.Dark) RaftAppearance.Dark else RaftAppearance.Light) {
                    TaskRows()
                }
            }
        }
}

private fun taskRow(
    key: String,
    title: String,
    number: Int,
    status: TaskStatus,
    unread: Int,
    preview: String,
    sender: String,
): InboxItem =
    InboxItem(
        key = "thread:$key",
        kind = InboxItemKind.Thread,
        channelId = ChannelId(key),
        channelName = "general",
        unreadCount = unread,
        firstUnreadMessageId = if (unread > 0) MessageId("first-$key") else null,
        threadChannelId = ChannelId(key),
        parentChannelId = ChannelId("mobile"),
        parentChannelName = "mobile",
        parentChannelKind = ConversationKind.Channel,
        parentMessagePreview = "#mobile:#$number $title",
        latestActivityPreview = preview,
        latestActivitySenderName = sender,
        latestActivitySenderId = "agent-${sender.lowercase()}",
        latestActivitySenderKind = MemberKind.Agent,
        latestActivityAt = "2026-10-21T15:40:00Z",
        isFollowing = true,
        taskNumber = number,
        taskStatus = status,
        taskConversation = InboxTaskConversation(taskId = TaskId("task-$key"), taskTitle = title),
    )

@androidx.compose.runtime.Composable
private fun TaskRows() {
    val timeContext =
        RaftTimeContext(
            localOffsetMinutes = 8 * 60,
            todayEpochDay = raftEpochDayForDate(2026, 10, 22),
            nowEpochMillis = 1_792_635_000_000L,
        )
    val unread = taskRow("conv-1", "Task v2 conversation header on mobile", 46, TaskStatus.InProgress, 2, "Header bar is in, goldens recorded.", "Lagoon")
    val read = taskRow("conv-2", "Reply count on task cards", 47, TaskStatus.Done, 0, "Shipped in 1.21.36.", "Volta")
    val swiped = taskRow("conv-3", "Follow from Activity", 48, TaskStatus.InReview, 0, "Unfollow is in the swipe now.", "Scout")
    val swipedRead = taskRow("conv-4", "Mark task rows read", 49, TaskStatus.Todo, 1, "Swipe right for Read.", "Lagoon")
    val context = ActivityConversationListContext(channelSymbolsByChannelId = mapOf("mobile" to "#"))
    // (row, open side)
    val rows = listOf(unread to null, read to null, swiped to ActivitySwipeSide.End, swipedRead to ActivitySwipeSide.Start)
    Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
        rows.forEachIndexed { index, (item, open) ->
            ActivityConversationListRow(
                item = item,
                model = item.activityConversationRowModel(context, InboxFilter.All),
                divider = index > 0,
                selected = false,
                timeContext = timeContext,
                agentStatusResolver = EmptyRaftAgentStatusResolver,
                canMarkDone = true,
                openGestureKey = item.key,
                captureMenuAnchor = false,
                onOpen = {},
                onOpenMenu = {},
                modifier = Modifier.fillMaxWidth(),
                onSwipeDone = {},
                swipeEndAction = item.activitySwipeEndAction(emptyMap()),
                onSwipeEnd = {},
                onSwipeToggleRead = {},
                swipeOpenSide = open,
            )
        }
    }
}
