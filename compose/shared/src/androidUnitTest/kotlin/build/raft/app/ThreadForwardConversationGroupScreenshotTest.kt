package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ForwardMessageTarget
import build.raft.app.data.ForwardMessageTargetKind
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ServerId
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The message Forward picker with the "Threads & tasks" group above Recent (slock #12621
 * ForwardComposerTargetList layout A @ b088f8467e), light and dark: the current thread first
 * with its soft muted "Current" badge, a task row (list-todo tile), a long thread title cut to
 * one line, then the Recent title and its rows. The bar's own "Recent" eyebrow is hidden.
 * Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h720dp-xxhdpi")
class ThreadForwardConversationGroupScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test fun groupLight() = capture("thread.forward.conversation-group", RaftAppAppearance.Light)

    @Test fun groupDark() = capture("thread.forward.conversation-group.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
                        RaftForwardTargetPicker(
                            messageCount = 1,
                            skippedCount = 0,
                            nestedForwardCount = 0,
                            sourceLabel = "#android",
                            singleTapContinues = true,
                            state =
                                RaftForwardTargetPickerUiState(
                                    sessionKey = RaftForwardPickerSessionKey("screenshot", "server-a", 1),
                                    items = recentRows.map { it.toRaftForwardTargetPickerItem() },
                                    conversationItems = conversationRows.map { it.toRaftForwardTargetPickerItem() },
                                    loadState = RaftForwardPickerLoadState.Loaded
                                ),
                            onIntent = {}
                        )
                    }
                }
            }
        }
}

private val screenshotServer = ServerId("server-a")

private fun conversationRow(id: String, title: String, task: Boolean = false, current: Boolean = false) =
    ForwardMessageTarget(
        serverId = screenshotServer,
        kind = if (task) ForwardMessageTargetKind.ConversationTask else ForwardMessageTargetKind.ConversationThread,
        id = id,
        title = title,
        channelId = ChannelId(id),
        channelKind = if (task) ConversationKind.Task else ConversationKind.Thread,
        channelType = if (task) "task" else "thread",
        parentChannelId = ChannelId("ch-android"),
        parentChannelName = "android",
        parentChannelType = "channel",
        current = current
    )

private val conversationRows =
    listOf(
        conversationRow("thread-release", "#android › Release plan for 1.20", current = true),
        conversationRow("task-7", "#android:#7 Ship the forward picker", task = true),
        conversationRow(
            "thread-long",
            "#design › A very long first line of the parent message that does not fit on one row of the picker"
        ),
        conversationRow("thread-dm", "dm:@Cindy › Can you check the dark mode badge?")
    )

private val recentRows =
    listOf(
        ForwardMessageTarget(
            serverId = screenshotServer,
            kind = ForwardMessageTargetKind.RecentChannel,
            id = "ch-android",
            title = "#android",
            channelId = ChannelId("ch-android"),
            channelType = "public"
        ),
        ForwardMessageTarget(
            serverId = screenshotServer,
            kind = ForwardMessageTargetKind.RecentChannel,
            id = "ch-design",
            title = "#design",
            channelId = ChannelId("ch-design"),
            channelType = "private"
        ),
        ForwardMessageTarget(
            serverId = screenshotServer,
            kind = ForwardMessageTargetKind.RecentDirectMessage,
            id = "dm-cindy",
            title = "Cindy",
            channelId = ChannelId("dm-cindy"),
            channelKind = ConversationKind.DirectMessage
        )
    )
