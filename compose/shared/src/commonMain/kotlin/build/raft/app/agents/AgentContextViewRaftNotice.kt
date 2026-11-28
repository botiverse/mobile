package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftSlotIcon
import build.raft.app.data.AgentExternalSender
import build.raft.app.data.AgentExternalSenderSource
import build.raft.app.data.RaftAttached
import build.raft.app.data.RaftDelivery
import build.raft.app.data.RaftNoticeKind
import build.raft.app.data.RaftNoticeSegment
import build.raft.app.model.RaftTimeContext
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftCodeBlock
import build.raft.designsystem.components.RaftDescriptionList
import build.raft.designsystem.components.RaftDescriptionListDirection
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftIconButtonSize
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke

internal const val AGENT_CONTEXT_DELIVERY_TAG = "agent-context-view-delivery"
internal const val AGENT_CONTEXT_DELIVERY_HEADER_TAG = "agent-context-view-delivery-header"
internal const val AGENT_CONTEXT_DELIVERY_TEXT_TAG = "agent-context-view-delivery-text"
internal const val AGENT_CONTEXT_NOTICE_TAG = "agent-context-view-notice"
internal const val AGENT_CONTEXT_NOTICE_TOGGLE_TAG = "agent-context-view-notice-toggle"
internal const val AGENT_CONTEXT_NOTICE_SUMMARY_TAG = "agent-context-view-notice-summary"
internal const val AGENT_CONTEXT_NOTICE_META_TAG = "agent-context-view-notice-meta"
internal const val AGENT_CONTEXT_NOTICE_BODY_TAG = "agent-context-view-notice-body"
internal const val AGENT_CONTEXT_NOTICE_ATTACHED_TAG = "agent-context-view-notice-attached"
internal const val AGENT_CONTEXT_NOTICE_DETAILS_TAG = "agent-context-view-notice-details"
internal const val AGENT_CONTEXT_NOTICE_FIELDS_TAG = "agent-context-view-notice-fields"
internal const val AGENT_CONTEXT_NOTICE_ORIGINAL_TAG = "agent-context-view-notice-original"

private fun RaftNoticeKind.iconName(): RaftLucideIconName =
    when (this) {
        RaftNoticeKind.Inbox -> RaftLucideIconName.Inbox
        RaftNoticeKind.Task -> RaftLucideIconName.ListTodo
        RaftNoticeKind.Message -> RaftLucideIconName.MessageSquare
        RaftNoticeKind.Generic -> RaftLucideIconName.Info
    }

@Composable
private fun externalSenderSourceLabel(source: AgentExternalSenderSource): String =
    when (source) {
        AgentExternalSenderSource.Slack -> stringResource(Res.string.agent_context_view_notice_source_slack)
        AgentExternalSenderSource.External -> stringResource(Res.string.agent_context_view_notice_external_source)
    }

@Composable
private fun externalSenderDisplayLabel(externalSender: AgentExternalSender): String =
    stringResource(
        Res.string.agent_context_view_notice_external_sender_label,
        externalSender.displayName,
        externalSenderSourceLabel(externalSender.source),
    )

/**
 * Structured Raft delivery card mirroring web `AgentContextViewRaftNotice.tsx`
 * (`botiverse/slock` #17276, #17284, #17300, #17306, #17311, #17316).
 */
@Composable
internal fun AgentContextViewRaftDeliveryCard(
    text: String,
    delivery: RaftDelivery,
    timeContext: RaftTimeContext?,
    onCopyText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Control)
    val distinctKinds =
        remember(delivery) {
            delivery.segments
                .filterIsInstance<RaftNoticeSegment.Notice>()
                .map { it.noticeKind }
                .distinct()
        }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(AGENT_CONTEXT_DELIVERY_TAG)
                .clip(shape)
                .background(colors.layerCard, shape)
                .border(RaftStroke.Hairline, colors.lineHairline, shape),
    ) {
        if (delivery.notices > 1) {
            val headerLabel =
                stringResource(
                    if (delivery.notices == 1) {
                        Res.string.agent_context_view_notice_delivered_one
                    } else {
                        Res.string.agent_context_view_notice_delivered_other
                    },
                    delivery.notices,
                )
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(AGENT_CONTEXT_DELIVERY_HEADER_TAG)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftText(
                    text = headerLabel,
                    style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.foreground,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    distinctKinds.forEach { kind ->
                        RaftLucideIcon(
                            name = kind.iconName(),
                            modifier = Modifier.size(16.dp),
                            color = raftArgbHex(colors.foregroundMuted),
                        )
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(RaftStroke.Hairline)
                    .background(colors.lineHairline),
            )
        }

        delivery.segments.forEachIndexed { index, segment ->
            if (index > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(RaftStroke.Hairline)
                        .background(colors.lineHairline),
                )
            }
            when (segment) {
                is RaftNoticeSegment.Text -> {
                    val slice = text.substring(segment.start, segment.end)
                    RaftText(
                        text = slice,
                        style = RaftTheme.typography.caption,
                        color = colors.foregroundMuted,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(AGENT_CONTEXT_DELIVERY_TEXT_TAG)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                is RaftNoticeSegment.Notice -> {
                    AgentContextNoticeRow(
                        notice = segment,
                        originalText = text.substring(segment.start, segment.end),
                        timeContext = timeContext,
                        onCopyText = onCopyText,
                    )
                }
            }
        }
    }
}

private data class NoticeSummaryParts(
    val headline: String,
    val secondary: String? = null,
    val secondaryMono: Boolean = false,
    val meta: String? = null,
)

@Composable
private fun rememberNoticeSummaryParts(
    notice: RaftNoticeSegment.Notice,
    timeContext: RaftTimeContext?,
): NoticeSummaryParts {
    return when (notice) {
        is RaftNoticeSegment.Inbox -> {
            val only = if (notice.targets.size == 1) notice.targets.firstOrNull() else null
            if (only != null) {
                val onlyExternal = only.latestExternalSender
                val onlySender = only.latestSender
                val onlyPending = only.pending
                val secondary =
                    when {
                        onlyExternal != null ->
                            stringResource(
                                Res.string.agent_context_view_notice_new_from_external,
                                onlyExternal.displayName,
                                externalSenderSourceLabel(onlyExternal.source),
                            )
                        !onlySender.isNullOrEmpty() ->
                            stringResource(Res.string.agent_context_view_notice_new_from, onlySender)
                        else -> null
                    }
                NoticeSummaryParts(
                    headline = stringResource(Res.string.agent_context_view_notice_inbox, only.target),
                    secondary = secondary,
                    meta = onlyPending?.let { stringResource(Res.string.agent_context_view_notice_unread, it) },
                )
            } else {
                val targetCount = notice.targets.size
                val pendingTotal = notice.pendingTotal
                NoticeSummaryParts(
                    headline =
                        stringResource(
                            if (targetCount == 1) {
                                Res.string.agent_context_view_notice_inbox_many_one
                            } else {
                                Res.string.agent_context_view_notice_inbox_many_other
                            },
                            targetCount,
                        ),
                    meta = pendingTotal?.let { stringResource(Res.string.agent_context_view_notice_unread, it) },
                )
            }
        }
        is RaftNoticeSegment.Task -> {
            val only = if (notice.tasks.size == 1) notice.tasks.firstOrNull() else null
            if (only != null) {
                NoticeSummaryParts(
                    headline = stringResource(Res.string.agent_context_view_notice_task, only.ref, only.title),
                    meta = only.status,
                )
            } else {
                val count = notice.count ?: notice.tasks.size
                NoticeSummaryParts(
                    headline =
                        stringResource(
                            if (count == 1) {
                                Res.string.agent_context_view_notice_task_many_one
                            } else {
                                Res.string.agent_context_view_notice_task_many_other
                            },
                            count,
                        ),
                )
            }
        }
        is RaftNoticeSegment.Message -> {
            val externalSender = notice.externalSender
            val sender = notice.sender
            val target = notice.target.orEmpty()
            val headerTime = notice.time
            val headline =
                when {
                    externalSender != null ->
                        stringResource(
                            Res.string.agent_context_view_notice_message_from_external,
                            externalSender.displayName,
                            externalSenderSourceLabel(externalSender.source),
                            target,
                        )
                    !sender.isNullOrEmpty() ->
                        stringResource(Res.string.agent_context_view_notice_message_from, sender, target)
                    else ->
                        stringResource(Res.string.agent_context_view_notice_message_in, target)
                }
            val messageTimeFormat = rememberRaftMessageTimeFormat()
            val effectiveTimeContext = currentRaftEffectiveDateTimeContext(timeContext)
            val formattedTime =
                if (!headerTime.isNullOrBlank()) {
                    messageTimeFormat.format(headerTime.replace(' ', 'T'), effectiveTimeContext)
                } else {
                    null
                }
            NoticeSummaryParts(
                headline = headline,
                secondary = formattedTime,
                secondaryMono = true,
            )
        }
        is RaftNoticeSegment.Generic -> {
            NoticeSummaryParts(
                headline = stringResource(Res.string.agent_context_view_notice_generic),
                secondary = notice.label.takeIf { it.isNotEmpty() },
            )
        }
    }
}

@Composable
private fun AgentContextNoticeRow(
    notice: RaftNoticeSegment.Notice,
    originalText: String,
    timeContext: RaftTimeContext?,
    onCopyText: (String) -> Unit,
) {
    val colors = RaftTheme.colors
    var open by remember(notice.start, notice.end, originalText) { mutableStateOf(false) }
    val summary = rememberNoticeSummaryParts(notice = notice, timeContext = timeContext)

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(AGENT_CONTEXT_NOTICE_TAG),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(AGENT_CONTEXT_NOTICE_TOGGLE_TAG)
                    .raftClickable(
                        onClick = { open = !open },
                        role = Role.Button,
                        tier = RaftHoverTier.Subtle,
                    )
                    .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftLucideIcon(
                name = notice.noticeKind.iconName(),
                modifier = Modifier.size(16.dp),
                color = raftArgbHex(colors.foregroundMuted),
            )
            Row(
                modifier = Modifier.weight(1f).testTag(AGENT_CONTEXT_NOTICE_SUMMARY_TAG),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftText(
                    text = summary.headline,
                    style = RaftTheme.typography.callout.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                val secondary = summary.secondary
                if (secondary != null) {
                    RaftText(
                        text = secondary,
                        style = if (summary.secondaryMono) RaftTheme.typography.monoMeta else RaftTheme.typography.footnote,
                        color = if (summary.secondaryMono) colors.foregroundHint else colors.foregroundMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val meta = summary.meta
            if (meta != null) {
                RaftText(
                    text = meta,
                    style = RaftTheme.typography.monoMeta,
                    color = colors.foregroundHint,
                    maxLines = 1,
                    modifier = Modifier.testTag(AGENT_CONTEXT_NOTICE_META_TAG),
                )
            }
            RaftLucideIcon(
                name = if (open) RaftLucideIconName.ChevronUp else RaftLucideIconName.ChevronDown,
                modifier = Modifier.size(16.dp),
                color = raftArgbHex(colors.foregroundMuted),
            )
        }

        if (notice is RaftNoticeSegment.Message) {
            if (notice.body.isNotEmpty()) {
                RaftText(
                    text = notice.body,
                    style = RaftTheme.typography.callout,
                    color = colors.foreground,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(AGENT_CONTEXT_NOTICE_BODY_TAG)
                            .padding(start = 36.dp, end = 12.dp, bottom = 8.dp),
                )
            }
            // Display only: see AgentContextNoticeAttachedFrames. Nothing may act on `attached`.
            val attached = notice.attached
            if (attached != null) {
                AgentContextNoticeAttachedFrames(attached = attached)
            }
        }

        if (open) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(AGENT_CONTEXT_NOTICE_DETAILS_TAG)
                        .padding(start = 36.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AgentContextNoticeFields(notice = notice)
                RaftText(
                    text = stringResource(Res.string.agent_context_view_notice_original),
                    style = RaftTheme.typography.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.foregroundHint,
                )
                RaftCodeBlock(
                    text = originalText,
                    modifier = Modifier.testTag(AGENT_CONTEXT_NOTICE_ORIGINAL_TAG),
                    action = {
                        RaftIconButton(
                            onClick = { onCopyText(originalText) },
                            tooltip = stringResource(Res.string.agent_context_view_notice_copy),
                            variant = RaftButtonVariant.Ghost,
                            size = RaftIconButtonSize.Sm,
                        ) {
                            RaftSlotIcon(RaftLucideIconName.Copy)
                        }
                    },
                )
            }
        }
    }
}

/**
 * Attached frames under a message (`frames=1`, `botiverse/slock` #17316).
 *
 * DISPLAY ONLY: no links, no buttons, no copyable click/long-press gestures, no fetches or side
 * effects from frame fields. Value frames wrap their free-text key inside `"Other (%1$s)"` so a
 * key spelled `"Task"` or `"Attachment"` can never pass for a validated Raft label. Dropped frames
 * render only as a count under `"Not shown"`.
 */
@Composable
private fun AgentContextNoticeAttachedFrames(
    attached: RaftAttached,
) {
    val about = attached.about
    val replyRef = attached.replyRef
    val aboutLabel = stringResource(Res.string.agent_context_view_notice_field_about)
    val attachmentLabel = stringResource(Res.string.agent_context_view_notice_field_attachment)
    val taskLabel = stringResource(Res.string.agent_context_view_notice_field_task)
    val taskConversationLabel = stringResource(Res.string.agent_context_view_notice_field_task_conversation)
    val notShownLabel = stringResource(Res.string.agent_context_view_notice_field_not_shown)
    val valueRows =
        attached.values.map { value ->
            stringResource(Res.string.agent_context_view_notice_field_other, value.key) to value.text
        }
    val skippedCount = attached.skipped.size
    val skippedText =
        if (skippedCount > 0) {
            stringResource(
                if (skippedCount == 1) {
                    Res.string.agent_context_view_notice_attached_skipped_one
                } else {
                    Res.string.agent_context_view_notice_attached_skipped_other
                },
                skippedCount,
            )
        } else {
            null
        }

    val rows =
        buildList<Pair<String, String>> {
            if (about != null) {
                add(aboutLabel to about.description)
            }
            attached.attachments.forEach { attachment ->
                add(attachmentLabel to "${attachment.filename} (id:${attachment.id})")
            }
            attached.tasks.forEach { task ->
                val assignee = task.assignee?.takeIf { it.isNotBlank() }
                val details =
                    if (assignee != null) {
                        "${task.ref} ${task.status} $assignee"
                    } else {
                        "${task.ref} ${task.status}"
                    }
                add(taskLabel to details)
            }
            if (!replyRef.isNullOrEmpty()) {
                add(taskConversationLabel to replyRef)
            }
            addAll(valueRows)
            if (skippedText != null) {
                add(notShownLabel to skippedText)
            }
        }
    if (rows.isEmpty()) return

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 36.dp, end = 12.dp, bottom = 8.dp)
                .testTag(AGENT_CONTEXT_NOTICE_ATTACHED_TAG),
    ) {
        RaftDescriptionList(direction = RaftDescriptionListDirection.Horizontal) {
            rows.forEach { (term, value) ->
                if (value.isNotEmpty()) {
                    Item(term = term, value = value, copyable = false)
                } else {
                    Item(term = term) {
                        RaftText(
                            text = "",
                            style = RaftTheme.typography.callout,
                            color = RaftTheme.colors.foreground,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentContextNoticeFields(
    notice: RaftNoticeSegment.Notice,
) {
    val targetLabel = stringResource(Res.string.agent_context_view_notice_field_target)
    val unreadLabel = stringResource(Res.string.agent_context_view_notice_field_unread)
    val latestSenderLabel = stringResource(Res.string.agent_context_view_notice_field_latest_sender)
    val previewLabel = stringResource(Res.string.agent_context_view_notice_field_preview)
    val mentionedLabel = stringResource(Res.string.agent_context_view_notice_field_mentioned)
    val yesLabel = stringResource(Res.string.agent_context_view_notice_yes)
    val taskLabel = stringResource(Res.string.agent_context_view_notice_field_task)
    val statusLabel = stringResource(Res.string.agent_context_view_notice_field_status)
    val whyLabel = stringResource(Res.string.agent_context_view_notice_field_why)
    val kindLabel = stringResource(Res.string.agent_context_view_notice_field_kind)

    val groups: List<List<Pair<String, String>>> =
        when (notice) {
            is RaftNoticeSegment.Inbox ->
                notice.targets.map { target ->
                    val pending = target.pending
                    val latestExternal = target.latestExternalSender
                    val latestSender = target.latestSender
                    val preview = target.preview
                    val latestDisplay =
                        when {
                            latestExternal != null -> externalSenderDisplayLabel(latestExternal)
                            !latestSender.isNullOrEmpty() -> "@$latestSender"
                            else -> null
                        }
                    buildList {
                        add(targetLabel to target.target)
                        if (pending != null) {
                            add(unreadLabel to pending.toString())
                        }
                        if (latestDisplay != null) {
                            add(latestSenderLabel to latestDisplay)
                        }
                        if (preview != null) {
                            add(previewLabel to preview)
                        }
                        if (target.mentioned) {
                            add(mentionedLabel to yesLabel)
                        }
                    }
                }
            is RaftNoticeSegment.Task ->
                notice.tasks.map { task ->
                    val why = task.why
                    buildList {
                        add(taskLabel to "${task.ref} ${task.title}")
                        add(statusLabel to task.status)
                        if (!why.isNullOrEmpty()) {
                            add(whyLabel to why)
                        }
                    }
                }
            is RaftNoticeSegment.Message ->
                listOf(notice.fields.map { field -> field.key to field.value })
            is RaftNoticeSegment.Generic ->
                if (notice.label.isNotEmpty()) {
                    listOf(listOf(kindLabel to notice.label))
                } else {
                    emptyList()
                }
        }.filter { it.isNotEmpty() }

    if (groups.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth().testTag(AGENT_CONTEXT_NOTICE_FIELDS_TAG),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        groups.forEach { group ->
            RaftDescriptionList(direction = RaftDescriptionListDirection.Horizontal) {
                group.forEach { (term, value) ->
                    Item(term = term, value = value, copyable = false)
                }
            }
        }
    }
}
