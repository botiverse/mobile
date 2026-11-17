package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.raftClickable

/*
 * Adjacent system messages fold into one row (web `systemMessageGrouping.ts` +
 * `SystemMessageGroupDisclosure`, raft-ui `SystemMessageGroup`): a run of two or more event lines
 * in a row ("Otter moved #12 … to Done", reminders, other system notices) shows as one summary,
 * "There are 4 task updates", collapsed by default; a tap opens or closes it. Render-only: the
 * message list keeps every message as its own lazy item (keys and indexes unchanged), so history,
 * scroll anchoring and jump targets stay exact; a folded member of a closed group draws nothing.
 *
 * A run is broken only by a message that does not fold: a normal message, or a system line drawn
 * as a card (an action card, a task handed off with a reason). A day boundary does not break it
 * (web neither); the timeline has no unread marker row to break it.
 */

internal enum class ThreadSystemMessageCategory { TaskUpdate, Reminder, System }

/** Where a message sits in a folded run; null when it is not in one. */
internal sealed interface ThreadSystemMessageFold {
    val groupId: String

    /** The run's first message: its row draws the summary (and, opened, its own line under it). */
    data class Summary(
        override val groupId: String,
        val categories: List<ThreadSystemMessageCategory>,
        val memberIds: List<String>,
    ) : ThreadSystemMessageFold

    /** A later message of the run: drawn only while the group is open. */
    data class Member(override val groupId: String) : ThreadSystemMessageFold
}

private val ThreadSystemTaskEventPattern =
    Regex("""^(.+?) (?:converted a message to task #\d+|claimed #\d+|released #\d+|moved #\d+|deleted #\d+)\s+"[^"]*"""")
private val ThreadSystemTaskMentionPattern =
    Regex("""\b(?:new tasks? created|converted a message to task #\d+|claimed #\d+|released #\d+|moved #\d+|deleted #\d+)\b""", RegexOption.IGNORE_CASE)
private val ThreadSystemReminderPattern =
    Regex("""^(?:Reminder\s+#\w+|Reminder(?: \([^)]+\))?:|.+?\s+(?:scheduled|canceled|cancelled)\s+(?:a\s+)?reminder\b)""", RegexOption.IGNORE_CASE)

/** Web `stripLeadingStatusGlyph`: drops a leading emoji/symbol token ("✅ Otter moved …"). */
private fun String.stripLeadingStatusGlyph(): String {
    val trimmed = trim()
    val firstSpace = trimmed.indexOf(' ')
    if (firstSpace <= 0) return trimmed
    val firstToken = trimmed.substring(0, firstSpace)
    val symbolsOnly = firstToken.none { it.isLetterOrDigit() || it == '@' || it == '#' }
    return if (symbolsOnly) trimmed.substring(firstSpace + 1).trim() else trimmed
}

/** Web `classifySystemMessage`; null for a message that never folds. */
internal fun RaftThreadMessageItem.threadSystemMessageCategory(): ThreadSystemMessageCategory? {
    if (!isSystemMessage()) return null
    // Drawn as cards, not event lines (web: delegated receipt, WeChat notice, hand-off with a reason).
    if (threadActionCard() != null) return null
    if (taskHandoffNotice() != null) return null
    if (isAssistantDmCardLine()) return null
    val content = body.stripLeadingStatusGlyph()
    if (ThreadSystemTaskEventPattern.containsMatchIn(content)) return ThreadSystemMessageCategory.TaskUpdate
    if (ThreadSystemTaskMentionPattern.containsMatchIn(content)) return ThreadSystemMessageCategory.TaskUpdate
    if (ThreadSystemReminderPattern.containsMatchIn(content)) return ThreadSystemMessageCategory.Reminder
    return ThreadSystemMessageCategory.System
}

/** Web `buildSystemMessageRenderStates`: one entry per message (oldest first), runs of 2+ fold. */
internal fun threadSystemMessageFolds(messages: List<RaftThreadMessageItem>): List<ThreadSystemMessageFold?> {
    val categories = messages.map { it.threadSystemMessageCategory() }
    val folds = arrayOfNulls<ThreadSystemMessageFold>(messages.size)
    var i = 0
    while (i < messages.size) {
        if (categories[i] == null) {
            i += 1
            continue
        }
        var end = i + 1
        while (end < messages.size && categories[end] != null) end += 1
        if (end - i > 1) {
            val groupId = "system-group:${messages[i].id}:${messages[end - 1].id}"
            folds[i] =
                ThreadSystemMessageFold.Summary(
                    groupId = groupId,
                    categories = categories.subList(i, end).filterNotNull(),
                    memberIds = messages.subList(i, end).map { it.id },
                )
            for (j in i + 1 until end) folds[j] = ThreadSystemMessageFold.Member(groupId)
        }
        i = end
    }
    return folds.toList()
}

/** The group a message folds into; null when it is not in one. */
internal fun List<ThreadSystemMessageFold?>.threadSystemGroupOf(
    messages: List<RaftThreadMessageItem>,
    messageId: String?,
): String? {
    if (messageId == null) return null
    val index = messages.indexOfFirst { it.id == messageId }
    return getOrNull(index)?.groupId
}

@Composable
private fun threadSystemCountPart(
    count: Int,
    one: org.jetbrains.compose.resources.StringResource,
    other: org.jetbrains.compose.resources.StringResource,
): String = if (count == 1) stringResource(one, count) else stringResource(other, count)

/** Web `formatSystemMessageBlockSummary`. */
@Composable
internal fun threadSystemMessageGroupSummary(categories: List<ThreadSystemMessageCategory>): String {
    val taskUpdates = categories.count { it == ThreadSystemMessageCategory.TaskUpdate }
    val reminders = categories.count { it == ThreadSystemMessageCategory.Reminder }
    val systemMessages = categories.count { it == ThreadSystemMessageCategory.System }
    val parts =
        listOfNotNull(
            if (taskUpdates > 0) {
                threadSystemCountPart(
                    taskUpdates,
                    Res.string.thread_system_group_task_update_count_one,
                    Res.string.thread_system_group_task_update_count_other,
                )
            } else {
                null
            },
            if (reminders > 0) {
                threadSystemCountPart(
                    reminders,
                    Res.string.thread_system_group_reminder_update_count_one,
                    Res.string.thread_system_group_reminder_update_count_other,
                )
            } else {
                null
            },
            if (systemMessages > 0) {
                threadSystemCountPart(
                    systemMessages,
                    Res.string.thread_system_group_system_message_count_one,
                    Res.string.thread_system_group_system_message_count_other,
                )
            } else {
                null
            },
        )
    // Web `listSeparator` join: "1 task update, 2 reminder updates" ("、" in ja / zh-TW).
    var joined = parts.firstOrNull().orEmpty()
    for (part in parts.drop(1)) joined = stringResource(Res.string.thread_system_group_list_join, joined, part)
    return when {
        parts.size == 1 ->
            if (categories.size == 1) {
                stringResource(Res.string.thread_system_group_single_part_one, parts[0])
            } else {
                stringResource(Res.string.thread_system_group_single_part_other, parts[0])
            }
        systemMessages == 0 -> stringResource(Res.string.thread_system_group_multi_parts, joined)
        else -> stringResource(Res.string.thread_system_group_system_updates, categories.size, joined)
    }
}

/**
 * The group's summary row (raft-ui `SystemMessageGroupHeader` + `SystemMessageGroupDisclosure`):
 * centered caption at 50% ink, a chevron at 35% pointing down while closed and up while open.
 */
@Composable
internal fun ThreadSystemMessageGroupHeader(
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val textColor = RaftColor.TextBlack.copy(alpha = 0.5f)
    val chevronColor = RaftColor.TextBlack.copy(alpha = 0.35f)
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(top = ThreadMessageTimelineTurn, bottom = ThreadMessageTimelineRowInset)
                .padding(horizontal = RaftAppSpacing.Md),
        horizontalArrangement = Arrangement.Center,
    ) {
        Row(
            modifier =
                Modifier.testTag("system-message-group-toggle")
                    .raftClickable(onClick = onToggle, shape = RoundedCornerShape(6.dp))
                    .padding(horizontal = RaftAppSpacing.Sm, vertical = ThreadMessageTimelineRowInset),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = summary,
                style = RaftType.Caption.copy(color = textColor),
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            RaftGlyphIcon(
                RaftGlyph.ChevronRight,
                Modifier.rotate(if (expanded) -90f else 90f),
                size = 12.dp,
                color = chevronColor,
            )
        }
    }
}
