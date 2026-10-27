package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.ActivityObjectRow
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberKind
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Workspace Artifacts rows in Activity, light and dark (web `ThreadsInbox.tsx` `ObjectInboxRow`,
 * Kirby's layout): the kind's tile, the title and time, "[face] who did what · #home", and the
 * unread / @ marks, beside a conversation row for scale. Rows: a mention, unread (with @); a
 * comment, unread; a published version, read, homed in a DM; an untitled one homed in the server
 * (no place on its line).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h560dp-xxhdpi")
class ActivityObjectRowsScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun activityObjectRowsLight() = capture("screens.activity.object-rows", RaftAppAppearance.Light)

    @Test
    fun activityObjectRowsDark() = capture("screens.activity.object-rows.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { ObjectRows(appearance) }
        }
    }
}

private fun objectItem(
    id: String,
    title: String?,
    kind: String,
    eventKind: String,
    reason: String,
    actor: String,
    actorType: String,
    homeKind: String,
    homeLabel: String?,
    unread: Int,
    mention: Boolean,
    at: String,
    version: Int? = null,
) = InboxItem(
    key = "object:artifact:$id",
    kind = InboxItemKind.Object,
    channelId = ChannelId("object:$id"),
    channelName = title.orEmpty(),
    unreadCount = unread,
    hasMention = mention,
    lastMessageAt = at,
    latestActivityAt = at,
    isFollowing = true,
    activityObject =
        ActivityObjectRow(
            objectKind = "artifact",
            objectId = id,
            title = title,
            artifactKind = kind,
            latestVersion = version,
            homeKind = homeKind,
            homeLabel = homeLabel,
            eventKind = eventKind,
            reason = reason,
            actorType = actorType,
            actorId = "member-$actor",
            actorName = actor,
            eventAt = at,
            targetVersion = version,
            doneFrontierSeq = "100",
        ),
)

@Composable
private fun ObjectRows(appearance: RaftAppAppearance) {
    val timeContext =
        RaftTimeContext(
            localOffsetMinutes = 8 * 60,
            todayEpochDay = raftEpochDayForDate(2026, 10, 27),
            nowEpochMillis = 1_793_066_400_000L,
        )
    val rows =
        listOf(
            objectItem("pt_a", "Q3 revenue report", "html", "comment", "mentioned", "kirby", "user", "channel", "proj-workspace", 2, true, "2026-10-27T01:20:00Z"),
            objectItem("pt_b", "Activity object rows spec", "markdown", "comment", "following", "Lagoon", "agent", "channel", "proj-workspace", 1, false, "2026-10-27T00:55:00Z"),
            objectItem("pt_c", "Launch metrics", "csv", "version", "owner", "AppFather", "agent", "dm", "artin", 0, false, "2026-10-26T09:10:00Z", version = 3),
            objectItem("pt_d", null, "image", "comment_edited", "following", "xxchan", "user", "server", null, 0, false, "2026-10-25T13:00:00Z"),
            InboxItem(
                key = "channel:release",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("release"),
                channelName = "release",
                lastMessageSenderName = "Volta",
                lastMessageSenderKind = MemberKind.Agent,
                lastMessagePreview = "Deployed, and checked it again just now.",
                lastMessageAt = "2026-10-25T08:17:00Z",
            ),
        )
    RaftTheme(appearance = if (appearance == RaftAppAppearance.Dark) RaftAppearance.Dark else RaftAppearance.Light) {
        Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
            rows.forEachIndexed { index, item ->
                ActivityConversationListRow(
                    item = item,
                    model = item.activityConversationRowModel(ActivityConversationListContext(), InboxFilter.All),
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
                )
            }
        }
    }
}
