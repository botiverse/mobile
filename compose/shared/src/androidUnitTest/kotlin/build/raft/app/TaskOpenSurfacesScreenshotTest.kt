package build.raft.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.designsystem.components.RaftDialogSheetSurface
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A task opened without a page of its own (slock web `useOpenTask`), the phone sheet's surface,
 * light and dark: `task.open.pending{,.refused,.not-joined}` — a pre-v2 task's conversation before
 * its first message (web `TaskConversationDialog` empty: "No discussion in this task yet" and where
 * its earlier discussion is) with the composer pinned at the bottom; after a refused first send
 * (the server's words, the text kept); for a reader outside the channel (web ThreadPanel "Join
 * Channel to Reply" instead of the composer); `task.open.legacy` — web `LegacyTaskPanel`.
 * Verify / record as [RaftRobolectricScreenshot]; reports land in `build/reports/home-screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class TaskOpenSurfacesScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun pendingLight() = capture("task.open.pending", RaftAppAppearance.Light) { Pending(joined = true, refused = false) }

    @Test
    fun pendingDark() = capture("task.open.pending.dark", RaftAppAppearance.Dark) { Pending(joined = true, refused = false) }

    @Test
    fun pendingRefusedLight() = capture("task.open.pending.refused", RaftAppAppearance.Light) { Pending(joined = null, refused = true) }

    @Test
    fun pendingRefusedDark() = capture("task.open.pending.refused.dark", RaftAppAppearance.Dark) { Pending(joined = null, refused = true) }

    @Test
    fun pendingNotJoinedLight() = capture("task.open.pending.not-joined", RaftAppAppearance.Light) { Pending(joined = false, refused = false) }

    @Test
    fun pendingNotJoinedDark() = capture("task.open.pending.not-joined.dark", RaftAppAppearance.Dark) { Pending(joined = false, refused = false) }

    @Test
    fun legacyLight() = capture("task.open.legacy", RaftAppAppearance.Light) { Legacy() }

    @Test
    fun legacyDark() = capture("task.open.legacy.dark", RaftAppAppearance.Dark) { Legacy() }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember { object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { RaftDesignSystemTheme { content() } }
        }

    /**
     * [joined] true: an empty composer at the bottom, as on the conversation page. null with
     * [refused]: membership not known where it opened (the Tasks tab), the first send was refused,
     * the server's words show and the typed text stays. false: the reader has not joined the
     * channel, so web's "Join Channel to Reply" replaces the composer.
     */
    @Composable
    private fun Pending(joined: Boolean?, refused: Boolean) {
        val task = sampleTask(hostMessageId = MessageId("msg-host"))
        RaftDialogSheetSurface(
            title = task.taskRefTitle(),
            commitLabel = null,
            onCommit = {},
            onClose = {},
            modifier = Modifier.fillMaxSize(),
        ) {
            TaskConversationPendingBody(
                task = task,
                sending = false,
                errorText = if (refused) "You must join this channel to reply in this task" else null,
                joined = joined,
                onSend = {},
                onJoin = {},
                modifier = Modifier.weight(1f),
                seedText = if (refused) "On it, testing on the emulator now" else "",
            )
        }
    }

    @Composable
    private fun Legacy() {
        val task = sampleTask(hostMessageId = null).copy(isLegacy = true, status = TaskStatus.Done, description = null)
        RaftDialogSheetSurface(
            title = taskLegacyPanelTitle(task),
            commitLabel = null,
            onCommit = {},
            onClose = {},
            modifier = Modifier.fillMaxSize(),
        ) {
            TaskLegacyPanelBody(task)
        }
    }

    private fun sampleTask(hostMessageId: MessageId?) =
        TaskSummary(
            id = TaskId("task-11"),
            title = "Ship the preview build and post the download link",
            status = TaskStatus.InReview,
            taskNumber = 11,
            messageId = hostMessageId,
            channelId = ChannelId("channel-1"),
            channelName = "wg-mobile",
            assignee = MemberProfile(id = MemberId("agent-codex"), displayName = "Codex-Android-DevOPS", handle = "codex", kind = MemberKind.Agent),
            creator = MemberProfile(id = MemberId("user-artin"), displayName = "artin", handle = "artin", kind = MemberKind.Human),
            updatedAtLabel = "2026-10-28T10:30:00Z",
            description = "Preview package for the 1.16 release; test each item on the simulator.",
            hostMessageId = hostMessageId,
        )
}
