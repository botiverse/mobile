package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftPointerInput
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Activity rows in the wide layout's 380dp list column, as the web draws them from 768
 * (ThreadsInbox `RaftInboxItem density="compact"`, a list cell; raft-design patterns/lists.md):
 * the title line and one excerpt line, the state on one rounded shape 8dp in from both sides,
 * 8dp above and below the content, rows 2dp apart and no line between them. The second row is
 * selected (the open conversation), so its shape shows.
 *  - `screens.activity.wide.rows.touch` / `.dark`: by touch, content 20dp from the column's edge, rows at least 56dp;
 *    every row shows its ink-outline Mark as Done at rest (web InboxRowDone), 12dp past its time and marks;
 *  - `screens.activity.wide.rows.fine`: a mouse or trackpad, content 16dp in, the third row hovered: the
 *    selected row and the hovered one show the ghost Mark as Done, 8dp past their time and marks.
 * The phone takes the same cell and the same one excerpt line: [ActivityConversationRowsScreenshotTest].
 * Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w944dp-h560dp-xhdpi")
class ActivityWideRowsScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun touch() = capture("screens.activity.wide.rows.touch", RaftAppAppearance.Light, RaftPointerInput.Touch)

    private fun capture(name: String, appearance: RaftAppAppearance, pointerInput: RaftPointerInput) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftTheme(
                    appearance = if (appearance == RaftAppAppearance.Dark) RaftAppearance.Dark else RaftAppearance.Light,
                    pointerInput = pointerInput,
                ) {
                    RaftDesignSystemTheme { WideRows(hoverThird = pointerInput == RaftPointerInput.Fine) }
                }
            }
        }
}

private val wideRowsTime =
    RaftTimeContext(
        localOffsetMinutes = 8 * 60,
        todayEpochDay = raftEpochDayForDate(2026, 10, 22),
        nowEpochMillis = 1_792_635_000_000L,
    )

private val wideRows =
    listOf(
        InboxItem(
            key = "channel:long-press",
            kind = InboxItemKind.Channel,
            channelId = ChannelId("long-press"),
            channelName = "那个长按选中那个功能现在还有问题吗",
            unreadCount = 1,
            hasMention = true,
            lastMessageSenderName = "林舟",
            lastMessageSenderKind = MemberKind.Agent,
            lastMessagePreview = "长按选中按你说的改好了，PR #3411，等 Lagoon 审完会合进去，下一个测试包就有。",
            lastMessageAt = "2026-10-22T02:07:00Z",
        ),
        InboxItem(
            key = "thread:channel",
            kind = InboxItemKind.Thread,
            channelId = ChannelId("thread-channel"),
            channelName = "thread",
            unreadCount = 2,
            parentChannelId = ChannelId("mobile"),
            parentChannelName = "mobile",
            parentChannelKind = ConversationKind.Channel,
            parentMessagePreview = "Activity 里私聊的 thread 怎么显示",
            latestActivityPreview = "按 Jasper 的规则来，头像跟着会话走。",
            latestActivitySenderName = "Scout",
            latestActivitySenderId = "agent-scout",
            latestActivitySenderKind = MemberKind.Agent,
            latestActivityAt = "2026-10-22T01:40:00Z",
        ),
        InboxItem(
            key = "channel:release",
            kind = InboxItemKind.Channel,
            channelId = ChannelId("release"),
            channelName = "release",
            lastMessageSenderName = "Volta",
            lastMessageSenderKind = MemberKind.Agent,
            lastMessagePreview = "Deployed, and I just tested it again to confirm it still works.",
            lastMessageAt = "2026-10-21T08:17:00Z",
        ),
        InboxItem(
            key = "channel:joint-hands",
            kind = InboxItemKind.Channel,
            channelId = ChannelId("joint-hands"),
            channelName = "joint-hands",
            channelKind = ConversationKind.Joint,
            channelType = "joint",
            unreadCount = 6,
            lastMessageSenderName = "System",
            lastMessageSenderKind = MemberKind.System,
            lastMessagePreview = "1 new task created: task #50 \"Upgrade admin vite to 6.4.4+ (security)\"",
            lastMessageAt = "2026-10-21T07:26:00Z",
        ),
    )

@Composable
private fun WideRows(hoverThird: Boolean) {
    val context =
        ActivityConversationListContext(
            channelSymbolsByChannelId = mapOf("long-press" to "#", "joint-hands" to "git-branch", "release" to "#", "mobile" to "#"),
        )
    val layout = activityRowLayout
    Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
        Column(
            Modifier.width(380.dp).fillMaxHeight().padding(top = layout.listTop),
            verticalArrangement = Arrangement.spacedBy(layout.rowGap),
        ) {
            activityConversationListEntries(wideRows, wideRowsTime).forEach { entry ->
                if (entry is ActivityConversationListEntry.DayHeading) {
                    ActivityDayHeading(entry.label, first = entry.first, background = RaftTheme.colors.layerCanvas, horizontalPadding = layout.contentEdge)
                    return@forEach
                }
                val item = (entry as ActivityConversationListEntry.Row).item
                ActivityConversationListRow(
                    item = item,
                    model = item.activityConversationRowModel(context, InboxFilter.All),
                    divider = entry.divider,
                    selected = item.key == "thread:channel",
                    timeContext = wideRowsTime,
                    agentStatusResolver = EmptyRaftAgentStatusResolver,
                    canMarkDone = true,
                    openGestureKey = item.key,
                    captureMenuAnchor = false,
                    onOpen = {},
                    onOpenMenu = {},
                    modifier = Modifier.fillMaxWidth(),
                    onRowDone = {},
                    hoverPreview = hoverThird && item.key == "channel:release",
                )
            }
        }
    }
}
