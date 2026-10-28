package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
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
import build.raft.app.model.MemberKind
import build.raft.app.model.TaskStatus
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Task v2 message rows, light and dark, on a 390dp phone (slock web MessageItem + `TaskChipList`):
 * - `thread.message-task-list`: a message with one task under it (artin's #11, a long title, so
 *   the assignee and the In Review status wrap to the second line), then a message with several
 *   (one with replies, one unassigned, one assigned to the reader, a done one).
 * - `thread.message-task-chip`: the same first message with task v2 off — the single footer chip
 *   it keeps (unchanged from before task v2).
 * Reports land in `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h640dp-xxhdpi")
class ThreadMessageTaskListScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun taskListLight() = capture("thread.message-task-list", RaftAppAppearance.Light) { TaskListRows(chip = false) }

    @Test
    fun taskListDark() = capture("thread.message-task-list.dark", RaftAppAppearance.Dark) { TaskListRows(chip = false) }

    @Test
    fun taskChipLight() = capture("thread.message-task-chip", RaftAppAppearance.Light) { TaskListRows(chip = true) }

    @Test
    fun taskChipDark() = capture("thread.message-task-chip.dark", RaftAppAppearance.Dark) { TaskListRows(chip = true) }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme { content() }
            }
        }
}

private val request =
    RaftThreadMessageItem(
        id = "msg-1",
        authorId = "user-artin",
        authorName = "artin",
        authorKind = "human",
        avatarLabel = "A",
        timestampLabel = "2026-10-28T10:30:00Z",
        body = "preview 包好了把下载方式贴给我，我在模拟器上逐项测，结果贴在这里。",
    )

private val oneTask =
    listOf(
        ThreadMessageSourceTaskItem(
            taskId = "t-11",
            taskNumber = 11,
            title = "@Codex-Android-DevOPS artin 同意，请按这个顺序发 preview 包",
            status = TaskStatus.InReview,
            replyCount = 0,
            assigneeId = "agent-codex",
            assigneeKind = MemberKind.Agent,
            assigneeName = "Codex-Android-DevOPS",
            assigneeIsViewer = false,
        )
    )

private val plan =
    RaftThreadMessageItem(
        id = "msg-2",
        authorId = "agent-lagoon",
        authorName = "Lagoon",
        authorKind = "agent",
        avatarLabel = "L",
        timestampLabel = "2026-10-28T10:39:00Z",
        body = "Split into four tasks.",
    )

private val severalTasks =
    listOf(
        ThreadMessageSourceTaskItem("t-12", 12, "Ship the preview build", TaskStatus.InProgress, 8, "agent-lagoon", MemberKind.Agent, "Lagoon", false),
        ThreadMessageSourceTaskItem("t-13", 13, "Smoke-test on the iOS simulator", TaskStatus.Todo, 0, null, null, null, false),
        ThreadMessageSourceTaskItem("t-14", 14, "Post the results here", TaskStatus.Todo, 0, "user-artin", MemberKind.Human, "artin", true),
        ThreadMessageSourceTaskItem("t-15", 15, "Write the changelog", TaskStatus.Done, 2, "agent-hanxin", MemberKind.Agent, "HanXin", false),
    )

@Composable
private fun TaskListRows(chip: Boolean) {
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        if (chip) {
            // Task v2 off: the pre-v2 projection onto the message (web footer chip).
            Row(
                request.copy(
                    taskId = "t-11",
                    taskNumber = 11,
                    taskStatus = "in_review",
                    taskAssigneeKind = "agent",
                    taskAssigneeId = "agent-codex",
                    taskAssigneeName = "Codex-Android-DevOPS",
                ),
                emptyList(),
                assigneeName = "Codex-Android-DevOPS",
            )
        } else {
            Row(request, oneTask)
            Row(plan, severalTasks)
        }
    }
}

@Composable
private fun Row(message: RaftThreadMessageItem, tasks: List<ThreadMessageSourceTaskItem>, assigneeName: String? = null) {
    ThreadMessageCard(
        message = message,
        messageBodyTextStyle = raftMessageBodyTextStyle("md"),
        sourceTasks = tasks,
        resolvedTaskAssigneeName = assigneeName,
        timestampLabel = "10:${message.timestampLabel.substring(14, 16)}",
        onShowActions = {},
        onOpenMember = {},
        onOpenThread = {},
        onOpenTask = {},
        onOpenTaskStatusMenu = {},
        onToggleReaction = {},
        onOpenAttachment = {},
        onOpenMarkdownTag = { _, _ -> },
        onOpenMarkdownUrl = {},
    )
}
