package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.model.ChannelId
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.raftEpochDayForDate
import build.raft.app.model.topLevelThreadId
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Drafts page (drafts_section_v0, web DraftsPanel), light and dark, and its empty state: Activity's
 * rows with a channel, a DM, a thread and a task draft, each led by the pencil before the text's first
 * line, a task's `#channel:#7 ·` before its text, the last edit at the end of the title; a draft
 * without a time last. Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h560dp-xxhdpi")
class RaftDraftsPanelScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun draftsLight() = capture("screens.drafts.list", RaftAppAppearance.Light) { DraftsList() }

    @Test
    fun draftsDark() = capture("screens.drafts.list.dark", RaftAppAppearance.Dark) { DraftsList() }

    @Test
    fun draftsEmpty() = capture("screens.drafts.empty", RaftAppAppearance.Light) { DraftsPanel(emptyList()) }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftTheme(appearance = if (appearance == RaftAppAppearance.Dark) RaftAppearance.Dark else RaftAppearance.Light) {
                    content()
                }
            }
        }
}

private val timeContext =
    RaftTimeContext(
        localOffsetMinutes = 8 * 60,
        todayEpochDay = raftEpochDayForDate(2026, 10, 31),
        nowEpochMillis = 1_793_440_000_000L,
    )

@Composable
private fun DraftsList() {
    val directory =
        RaftDraftDirectory(
            serverId = "s1",
            channels = mapOf("mobile" to HomeChannelRow(threadId = topLevelThreadId(ChannelId("mobile")), channelId = ChannelId("mobile"), name = "mobile")),
            directMessages =
                mapOf(
                    "dm-volta" to HomeDirectMessageRow(threadId = topLevelThreadId(ChannelId("dm-volta")), channelId = ChannelId("dm-volta"), name = "Volta", directMessagePeerType = "agent"),
                ),
            channelSymbols = mapOf("mobile" to "#"),
            tasks =
                listOf(
                    TaskSummary(
                        id = TaskId("task-46"),
                        title = "Drafts section on mobile",
                        status = TaskStatus.InProgress,
                        taskNumber = 46,
                        channelId = ChannelId("mobile"),
                        conversationChannelId = ChannelId("conv-46"),
                        updatedAtLabel = "",
                    ),
                ),
        )
    val now = 1_793_440_000_000L
    val entries =
        listOf(
            RaftDraftEntry("k1", "The goldens are in, I'll post them\nafter lunch.", now - 2 * 60_000L, RaftDraftPlace.Conversation("mobile", directMessage = false)),
            RaftDraftEntry("k2", "Can you look at the Drafts row count?", now - 45 * 60_000L, RaftDraftPlace.Conversation("dm-volta", directMessage = true)),
            RaftDraftEntry("k3", "Agreed, the thread opens on its parent channel.", now - 5 * 3_600_000L, RaftDraftPlace.Thread("mobile", "msg-1", "thread-1")),
            RaftDraftEntry("k4", "Tests pass locally; recording the screenshots next, then the PR.", now - 26 * 3_600_000L, RaftDraftPlace.Task("conv-46", "mobile", "task-46")),
            RaftDraftEntry("k5", "A draft from before edit times were kept", null, RaftDraftPlace.Conversation("mobile", directMessage = false)),
        )
    val labels = rememberRaftDraftRowLabels()
    DraftsPanel(entries.map { it.toRaftDraftRowModel(directory, labels) })
}

@Composable
private fun DraftsPanel(rows: List<RaftDraftRowModel>) {
    RaftDraftsPanel(
        rows = rows,
        onClose = {},
        onOpen = {},
        bottomContentPadding = 0.dp,
        timeContext = timeContext,
        agentStatusResolver = EmptyRaftAgentStatusResolver,
    )
}
