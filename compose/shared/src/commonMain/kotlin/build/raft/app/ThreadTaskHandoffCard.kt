package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.app.data.TaskAssignmentNotice
import build.raft.app.data.TaskAssignmentNoticeParty
import build.raft.app.data.TaskAssignmentPartyType
import build.raft.app.data.TaskAssignmentTimelineLine
import build.raft.app.data.TaskHandoffReturn
import build.raft.app.data.asTaskHandoff
import build.raft.app.data.sameParty
import build.raft.app.data.taskAssignmentNoticeOf
import build.raft.app.data.taskHandoffReturns
import build.raft.app.model.MemberKind
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftMessageActionCard
import build.raft.designsystem.components.RaftMessageActionCardHeaderDescription
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme

/*
 * A task handed to someone with a reason, in the task's conversation (web `TaskHandoffCard.tsx`,
 * botiverse/slock #13927, #14007, #14091): who handed it to whom, "[avatar] @Otter → [avatar]
 * @xxchan", the two alike, when, and what it waits on them for as the card's body, instead of the
 * one-line system notice (the line's text stays the record). When the task later comes back to
 * whoever handed it off, the card says so and fades: "Otter took it back · 10:51" when they took
 * it, "xxchan handed it back to Otter · 10:51" when someone else gave it back.
 */

/** The hand-off a system line's projected metadata carries (web `readTaskHandoffNotice`); null for any other line. */
internal fun RaftThreadMessageItem.taskHandoffNotice(): TaskAssignmentNotice? =
    if (isSystemMessage()) taskAssignmentNoticeOf(metadata).asTaskHandoff() else null

/** Web `taskHandoffReturns` over a conversation's lines, oldest first. */
internal fun threadTaskHandoffReturns(messages: List<RaftThreadMessageItem>): Map<String, TaskHandoffReturn> =
    taskHandoffReturns(
        messages.mapNotNull { message ->
            if (!message.isSystemMessage()) return@mapNotNull null
            val notice = taskAssignmentNoticeOf(message.metadata) ?: return@mapNotNull null
            TaskAssignmentTimelineLine(message.id, message.timestampLabel, notice)
        }
    )

/**
 * A system line that is a hand-off with a reason, as its card in the timeline; null when [message]
 * is any other line (the caller draws the one-line notice). [timeline] is the conversation's
 * messages, oldest first, to see whether the task came back since.
 */
@Composable
internal fun ThreadTaskHandoffRow(
    message: RaftThreadMessageItem,
    timeline: List<RaftThreadMessageItem>,
): Boolean {
    val notice = remember(message.metadata, message.messageType) { message.taskHandoffNotice() } ?: return false
    val returned = remember(timeline, message.id) { threadTaskHandoffReturns(timeline)[message.id] }
    // raft-ui MessageItem compact: the card on the text column (past the 35dp avatar gutter and its 12dp gap).
    ThreadTaskHandoffCard(
        notice = notice,
        returned = returned,
        timestampValue = message.timestampLabel,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = RaftAppSpacing.Sm + 35.dp + 12.dp,
                    end = RaftAppSpacing.Sm,
                    top = ThreadMessageTimelineRowInset + RaftAppSpacing.Xs,
                    bottom = ThreadMessageTimelineRowInset + RaftAppSpacing.Xs,
                ),
    )
    return true
}

/** A party's avatar and the name people read: the viewer's store first, then the line's own names (web `usePartyIdentity`). */
@Composable
private fun taskHandoffPartyName(party: TaskAssignmentNoticeParty): String {
    val names = LocalThreadActionCardNames.current
    val kind = if (party.type == TaskAssignmentPartyType.Agent) ThreadActionCardEntityKind.Agent else ThreadActionCardEntityKind.Human
    return names.name(kind, party.id)?.takeIf { it.isNotBlank() } ?: party.displayName ?: party.name ?: ""
}

/** Web `partyHandle`: `@handle`, else the name. */
internal fun taskHandoffPartyHandle(party: TaskAssignmentNoticeParty, name: String): String =
    party.name?.let { "@$it" } ?: name

@Composable
internal fun ThreadTaskHandoffCard(
    notice: TaskAssignmentNotice,
    returned: TaskHandoffReturn?,
    timestampValue: String,
    modifier: Modifier = Modifier,
) {
    val assignee = notice.assignee ?: return
    val fromName = taskHandoffPartyName(notice.actor)
    val toName = taskHandoffPartyName(assignee)
    val returnerName = taskHandoffPartyName(returned?.by ?: notice.actor)
    val fromHandle = taskHandoffPartyHandle(notice.actor, fromName)
    val toHandle = taskHandoffPartyHandle(assignee, toName)
    val timeContext = currentRaftTimeContextProvider?.current
    val label = stringResource(Res.string.task_handoff_label, fromHandle, notice.taskNumber, toHandle)
    val meta =
        when {
            returned == null -> formatThreadTimeRevealLabel(timestampValue, timeContext)
            !returned.by.sameParty(notice.actor) ->
                stringResource(Res.string.task_handoff_handed_back, returnerName, fromName, formatThreadTimeRevealLabel(returned.at, timeContext))
            else -> stringResource(Res.string.task_handoff_returned, fromName, formatThreadTimeRevealLabel(returned.at, timeContext))
        }
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    RaftMessageActionCard(
        title = "",
        showIcon = false,
        modifier =
            modifier
                .then(if (returned != null) Modifier.alpha(0.6f) else Modifier)
                .semantics { contentDescription = label }
                .testTag(if (returned != null) TaskHandoffCardReturnedTag else TaskHandoffCardTag),
        description = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TaskHandoffTitle(
                    from = notice.actor,
                    fromName = fromName,
                    fromHandle = fromHandle,
                    to = assignee,
                    toName = toName,
                    toHandle = toHandle,
                    style = typography.subhead.copy(fontWeight = FontWeight.Bold),
                )
                if (meta.isNotBlank()) RaftMessageActionCardHeaderDescription(meta, Modifier.testTag(TaskHandoffCardMetaTag))
            }
        },
    ) {
        RaftText(
            notice.reason.orEmpty(),
            Modifier.testTag(TaskHandoffCardReasonTag),
            style = typography.callout.copy(localeList = RaftUserContentLocaleList),
            color = colors.foreground,
        )
    }
}

/** "[avatar] @from → [avatar] @to": each party's avatar at the size of a name in text (raft-ui Avatar xs), then their handle. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskHandoffTitle(
    from: TaskAssignmentNoticeParty,
    fromName: String,
    fromHandle: String,
    to: TaskAssignmentNoticeParty,
    toName: String,
    toHandle: String,
    style: TextStyle,
) {
    val color = RaftTheme.colors.foreground
    FlowRow(
        Modifier.semantics { heading() }.testTag(TaskHandoffCardTitleTag),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TaskHandoffPartyName(from, fromName, fromHandle, style)
        RaftText("→", Modifier.align(Alignment.CenterVertically), style = style, color = color)
        TaskHandoffPartyName(to, toName, toHandle, style)
    }
}

@Composable
private fun TaskHandoffPartyName(party: TaskAssignmentNoticeParty, name: String, handle: String, style: TextStyle) {
    val serverId = LocalThreadViewerServerId.current
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        RaftMemberAvatar(
            identity =
                RaftMemberAvatarIdentity(
                    kind = if (party.type == TaskAssignmentPartyType.Agent) MemberKind.Agent else MemberKind.Human,
                    memberId = party.id,
                    serverId = serverId,
                ),
            label = name.ifBlank { handle },
            avatarSize = RaftAvatarSize.Xs.edge,
        )
        RaftText(handle, style = style.copy(localeList = RaftUserContentLocaleList), color = RaftTheme.colors.foreground)
    }
}

internal const val TaskHandoffCardTag = "task-handoff-card"
internal const val TaskHandoffCardReturnedTag = "task-handoff-card-returned"
internal const val TaskHandoffCardTitleTag = "task-handoff-title"
internal const val TaskHandoffCardMetaTag = "task-handoff-meta"
internal const val TaskHandoffCardReasonTag = "task-handoff-reason"
