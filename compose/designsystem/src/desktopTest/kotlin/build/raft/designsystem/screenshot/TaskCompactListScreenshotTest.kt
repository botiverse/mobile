package build.raft.designsystem.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.LocalRaftPressedPreview
import build.raft.designsystem.components.RaftAvatar
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftAvatarType
import build.raft.designsystem.components.RaftPresence
import build.raft.designsystem.components.RaftTaskCompactAssignee
import build.raft.designsystem.components.RaftTaskCompactList
import build.raft.designsystem.components.RaftTaskCompactRow
import build.raft.designsystem.components.RaftTaskStatus
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * The tasks under a message (task v2; slock web `TaskChipList` boxed, `compactRow.ts`), light and
 * dark, in a 343dp message column (a 390dp phone): one task (the right group wraps under a long
 * title), several tasks (one line where they fit), a long title cut with an ellipsis, a task with
 * replies, an unassigned task, the reader as "You", each of the five statuses, and a pressed row.
 */
class TaskCompactListScreenshotTest {
    private val appearances = listOf(RaftAppearance.Light, RaftAppearance.Dark)

    @Test
    fun taskCompactList() =
        appearances.forEach { appearance ->
            RaftScreenshot.capture("task_compact_list_${appearance.name.lowercase()}", appearance) {
                Column(Modifier.width(343.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Caption("One task, long title: the right group wraps")
                    RaftTaskCompactList {
                        Row(11, "@Codex-Android-DevOPS artin agreed, ship the preview build in this order", RaftTaskStatus.InReview, "In Review", agent("Codex-Android-DevOPS"))
                    }
                    Caption("Several tasks, short titles: one line each")
                    RaftTaskCompactList {
                        Row(12, "Ship the preview build", RaftTaskStatus.InProgress, "In Progress", agent("Lagoon"), replies = 8)
                        Row(13, "Smoke-test on iOS", RaftTaskStatus.Todo, "Todo", null, divider = true)
                        Row(14, "Write the changelog", RaftTaskStatus.Done, "Done", you(), divider = true)
                    }
                    Caption("Each status; unassigned; pressed")
                    RaftTaskCompactList {
                        Row(1, "Todo", RaftTaskStatus.Todo, "Todo", null)
                        Row(2, "In progress", RaftTaskStatus.InProgress, "In Progress", human("Artin"), divider = true)
                        Row(3, "In review", RaftTaskStatus.InReview, "In Review", agent("Hanxin"), divider = true)
                        Row(4, "Done", RaftTaskStatus.Done, "Done", agent("Linzhou"), divider = true)
                        Row(5, "Closed", RaftTaskStatus.Closed, "Closed", null, divider = true)
                        CompositionLocalProvider(LocalRaftPressedPreview provides true) {
                            Row(6, "Pressed row", RaftTaskStatus.Todo, "Todo", null, divider = true)
                        }
                    }
                    Caption("A very long title with replies is cut before its count")
                    RaftTaskCompactList {
                        Row(
                            127,
                            "Make the message task list match the web exactly: borders, radius, type and the status chip",
                            RaftTaskStatus.InProgress,
                            "In Progress",
                            null,
                            replies = 23,
                        )
                    }
                }
            }
        }

    private fun agent(name: String): @Composable () -> Unit = {
        RaftTaskCompactAssignee(name = name, avatar = {
            RaftAvatar(RaftAvatarType.Agent, RaftAvatarSize.Xs, initials = name.take(1), presence = RaftPresence.Busy)
        })
    }

    private fun human(name: String): @Composable () -> Unit = {
        RaftTaskCompactAssignee(name = name, avatar = { RaftAvatar(RaftAvatarType.Human, RaftAvatarSize.Xs, initials = name.take(1)) })
    }

    private fun you(): @Composable () -> Unit = {
        RaftTaskCompactAssignee(name = "You", viewer = true, avatar = { RaftAvatar(RaftAvatarType.Human, RaftAvatarSize.Xs, initials = "A") })
    }

    @Composable
    private fun Row(
        number: Int,
        title: String,
        status: RaftTaskStatus,
        statusText: String,
        assignee: (@Composable () -> Unit)?,
        replies: Int = 0,
        divider: Boolean = false,
    ) {
        RaftTaskCompactRow(
            number = "#$number",
            title = title,
            onClick = {},
            status = status,
            statusText = statusText,
            assignee = assignee,
            replyCount = replies,
            divider = divider,
        )
    }

    @Composable
    private fun Caption(text: String) {
        RaftText(text = text, style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foregroundHint)
    }
}
