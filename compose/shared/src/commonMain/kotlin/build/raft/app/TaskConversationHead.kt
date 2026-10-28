package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import build.raft.app.model.ServerId
import build.raft.app.model.ChannelId
import build.raft.app.data.TaskConversationDetailsViewModel
import build.raft.app.data.TaskConversationDetailsRepository
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.data.TaskConversationDetailsState
import build.raft.app.data.TaskConversationHeaderState
import build.raft.app.data.TaskDetailLoad
import build.raft.app.data.TaskHistoryDetail
import build.raft.app.data.TaskHistoryField
import build.raft.app.data.TaskHistoryKind
import build.raft.app.data.TaskHistoryRow
import build.raft.app.data.earlierDiscussionVisible
import build.raft.app.data.taskHeadSourceMessageId
import build.raft.app.data.toTaskHistoryRows
import build.raft.app.data.valueOrNull
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.avatarWireValueForRenderedAvatar
import build.raft.app.model.formatRaftTimestampLabel
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.resources.*
import build.raft.designsystem.components.RaftButtonVariant
import build.raft.designsystem.components.RaftDescriptionList
import build.raft.designsystem.components.RaftDescriptionListDirection
import build.raft.designsystem.components.RaftDescriptionValue
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftIconButton
import build.raft.designsystem.components.RaftMenuItem
import build.raft.designsystem.components.RaftPickerTriggerButton
import build.raft.designsystem.components.RaftPickerTriggerSize
import build.raft.designsystem.components.RaftSectionEyebrow
import build.raft.designsystem.components.RaftSkeleton
import build.raft.designsystem.components.RaftTaskStatusBadge
import build.raft.designsystem.components.RaftTaskStatusBadgeSize
import build.raft.designsystem.components.RaftTaskStatusPixel
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTimeline
import build.raft.designsystem.components.RaftTimelineDetail
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius

/** The Source row (web `TaskSourceProperty`): [preview] null = the message is not loaded (yet). */
@Immutable
internal data class TaskHeadSource(
    val authorName: String?,
    val authorKind: MemberKind?,
    val authorAvatarWireValue: String?,
    val preview: String?,
    val time: String?,
)

/** One History row as shown: [time] formatted, an assignee resolved to a display name. */
@Immutable
internal data class TaskHeadHistoryRow(
    val id: String,
    val kind: TaskHistoryKind,
    val actor: String,
    val time: String,
    val markerStatus: TaskStatus?,
    val detail: TaskHistoryDetail,
    /** [TaskHistoryDetail.Assigned]'s name (channel member display name); null = not a member any more. */
    val assigneeName: String? = null,
)

internal sealed interface TaskHeadHistory {
    data object Loading : TaskHeadHistory

    data object Failed : TaskHeadHistory

    data class Rows(val rows: List<TaskHeadHistoryRow>) : TaskHeadHistory
}

/**
 * Task v2: what the task conversation's head shows (web `TaskModalHead` + `TaskProperties` in the
 * task dialog): the title, the description, the facts (Status, Assignee, Source, Created by,
 * Created at, Earlier discussion) and the History. Derived purely by [taskConversationHeadModel].
 */
@Immutable
internal data class TaskConversationHeadModel(
    val bar: TaskConversationHeaderBarModel,
    val title: String,
    /** Null: no description (web shows no line for it). */
    val description: String?,
    /** Null: no Source row (no source message, or a pre-v2 task's host, led to by Earlier discussion). */
    val source: TaskHeadSource?,
    val createdBy: String?,
    val createdAt: String?,
    /** Null: no Earlier discussion row; [Int] the reply count, null inside = not known yet ("View"). */
    val earlierDiscussion: TaskHeadEarlierDiscussion?,
    val history: TaskHeadHistory,
)

@Immutable
internal data class TaskHeadEarlierDiscussion(val replyCount: Int?)

/** Web `displayNameFor(type, id)`: the channel member's display name, else null. */
internal fun List<MemberProfile>.taskHeadDisplayName(kind: MemberKind, id: String): String? =
    firstOrNull { it.kind == kind && it.id.value == id }?.displayName?.trim()?.takeIf { it.isNotBlank() }

internal fun TaskSummary.taskConversationHeadModel(
    state: TaskConversationHeaderState,
    details: TaskConversationDetailsState,
    serverRole: String?,
    statusUpdating: Boolean,
    members: List<MemberProfile>,
    timeContext: RaftTimeContext?,
): TaskConversationHeadModel {
    val sourceId = taskHeadSourceMessageId()
    val sourcePreview = details.source.valueOrNull?.takeIf { it.messageId == sourceId }
    val author = sourcePreview?.author
    val creatorName =
        creator?.let { member ->
            members.taskHeadDisplayName(member.kind, member.id.value)
                ?: member.displayName.trim().takeIf { it.isNotBlank() }?.let { "@$it" }
        }
    val history =
        when (val load = details.history) {
            is TaskDetailLoad.Loaded ->
                TaskHeadHistory.Rows(load.value.toTaskHistoryRows().map { it.toHeadRow(members, timeContext) })
            TaskDetailLoad.Failed -> TaskHeadHistory.Failed
            TaskDetailLoad.Idle,
            TaskDetailLoad.Loading -> TaskHeadHistory.Loading
        }
    val earlier =
        if (hostMessageId != null && details.hostMessageId == hostMessageId && details.earlierDiscussionVisible()) {
            TaskHeadEarlierDiscussion(details.earlierDiscussion.valueOrNull?.replyCount)
        } else if (hostMessageId != null && details.hostMessageId != hostMessageId) {
            TaskHeadEarlierDiscussion(null)
        } else {
            null
        }
    return TaskConversationHeadModel(
        bar = taskConversationHeaderBarModel(state, serverRole, statusUpdating),
        title = title,
        description = description?.trim()?.takeIf { it.isNotBlank() },
        source =
            sourceId?.let {
                TaskHeadSource(
                    authorName =
                        author?.let { members.taskHeadDisplayName(it.kind, it.id.value) ?: it.displayName.trim().takeIf(String::isNotBlank) },
                    authorKind = author?.kind,
                    authorAvatarWireValue = author?.avatarWireValueForRenderedAvatar(),
                    preview = sourcePreview?.firstLine,
                    time = sourcePreview?.createdAt?.let { formatRaftTimestampLabel(it, timeContext).takeIf(String::isNotBlank) },
                )
            },
        createdBy = creatorName,
        createdAt = createdAt?.let { formatRaftTimestampLabel(it, timeContext).takeIf(String::isNotBlank) },
        earlierDiscussion = earlier,
        history = history,
    )
}

private fun TaskHistoryRow.toHeadRow(members: List<MemberProfile>, timeContext: RaftTimeContext?): TaskHeadHistoryRow =
    TaskHeadHistoryRow(
        id = id,
        kind = kind,
        actor = actor,
        time = formatRaftTimestampLabel(createdAt, timeContext),
        markerStatus = markerStatus,
        detail = detail,
        assigneeName =
            (detail as? TaskHistoryDetail.Assigned)?.let { assigned ->
                members.taskHeadDisplayName(if (assigned.assigneeType == "agent") MemberKind.Agent else MemberKind.Human, assigned.assigneeId)
            },
    )

/** The description folds to three lines (web `DESCRIPTION_COLLAPSE_LINE_LIMIT`). */
private const val TaskHeadDescriptionCollapsedLines = 3

/**
 * Task v2: the head of a task conversation (web `TaskModalHead` in `TaskConversationDialog`). In the
 * order the questions arise: what is this (the title, `text-headline` 600, and the description,
 * `text-subhead` muted, folded to three lines with Show More), what state is it in (the inline
 * facts: Status and Assignee as the editors they were, Source, Created by, Created at, Earlier
 * discussion), and what happened to it (History, folded, as on web). Follow / Unfollow stays the
 * one icon button beside the title. The head never takes more than [maxHeightFraction] of the page:
 * past that it scrolls inside itself (web's pinned head, `max-h-[45%]`).
 */
@Composable
internal fun TaskConversationHead(
    model: TaskConversationHeadModel,
    onSelectStatus: (TaskStatus) -> Unit,
    onOpenAssignee: () -> Unit,
    onToggleFollow: () -> Unit,
    onOpenSource: () -> Unit,
    onOpenEarlierDiscussion: () -> Unit,
    modifier: Modifier = Modifier,
    initiallyHistoryOpen: Boolean = false,
    initiallyDescriptionExpanded: Boolean = false,
    maxHeightFraction: Float = 0.45f,
) {
    val colors = RaftTheme.colors
    BoxWithConstraints(modifier.fillMaxWidth().background(colors.layerCanvas)) {
        val cap = if (maxHeight.value.isFinite()) maxHeight * maxHeightFraction else maxHeight
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = cap)
                    .verticalScroll(rememberScrollState())
                    .raftSafeAreaHorizontalPadding()
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
            ) {
                TaskHeadContent(
                    model = model,
                    onSelectStatus = onSelectStatus,
                    onOpenAssignee = onOpenAssignee,
                    onToggleFollow = onToggleFollow,
                    onOpenSource = onOpenSource,
                    onOpenEarlierDiscussion = onOpenEarlierDiscussion,
                    initiallyHistoryOpen = initiallyHistoryOpen,
                    initiallyDescriptionExpanded = initiallyDescriptionExpanded,
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.lineHairline))
        }
    }
}

/**
 * The head's content without its frame: the conversation page's head, and the same title,
 * description, facts and History in the pending-conversation sheet (a task opened before its
 * conversation exists). Null navigation callbacks draw Source and Earlier discussion as plain
 * facts; a bar model with no status options and no editable assignee draws them as plain facts too.
 */
@Composable
internal fun TaskHeadContent(
    model: TaskConversationHeadModel,
    onSelectStatus: (TaskStatus) -> Unit = {},
    onOpenAssignee: () -> Unit = {},
    onToggleFollow: () -> Unit = {},
    onOpenSource: (() -> Unit)? = null,
    onOpenEarlierDiscussion: (() -> Unit)? = null,
    initiallyHistoryOpen: Boolean = false,
    initiallyDescriptionExpanded: Boolean = false,
) {
    Column {
        TaskHeadTitleRow(model, onToggleFollow)
        Column(Modifier.padding(end = 8.dp)) {
            model.description?.let { TaskHeadDescription(it, initiallyDescriptionExpanded) }
            TaskHeadFacts(model, onSelectStatus, onOpenAssignee, onOpenSource, onOpenEarlierDiscussion)
            TaskHeadHistorySection(model.history, initiallyHistoryOpen)
        }
    }
}

@Composable
private fun TaskHeadTitleRow(model: TaskConversationHeadModel, onToggleFollow: () -> Unit) {
    // The follow button sits on the title's first line; the title wraps up to three lines beside it.
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        RaftText(
            model.title,
            Modifier.weight(1f).padding(top = 10.dp, bottom = 8.dp).semantics { heading() },
            style = RaftTheme.typography.headline.copy(fontWeight = FontWeight.SemiBold),
            color = RaftTheme.colors.foreground,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        TaskHeadFollowButton(model.bar, onToggleFollow)
    }
}

@Composable
private fun TaskHeadFollowButton(bar: TaskConversationHeaderBarModel, onToggle: () -> Unit) {
    bar.followed?.let { followed ->
        // Web keeps Follow / Unfollow Task in the bar's More menu (ThreadOverflowMenu); here the
        // command stays the head's one icon button, with the web's words as its tooltip.
        val followLabel =
            stringResource(if (followed) Res.string.task_conversation_unfollow else Res.string.task_conversation_follow)
        RaftIconButton(
            onClick = onToggle,
            tooltip = followLabel,
            variant = RaftButtonVariant.Ghost,
            loading = bar.followPending,
        ) {
            RaftLucideIcon(
                if (followed) RaftLucideIconName.MessageCircleOff else RaftLucideIconName.MessageCirclePlus,
                Modifier.size(18.dp),
                color = raftArgbHex(RaftTheme.colors.foreground),
            )
        }
    }
}

@Composable
private fun TaskHeadDescription(description: String, initiallyExpanded: Boolean) {
    var expanded by remember(description) { mutableStateOf(initiallyExpanded) }
    var collapsible by remember(description) { mutableStateOf(false) }
    Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RaftText(
            description,
            style = RaftTheme.typography.subhead,
            color = RaftTheme.colors.foregroundMuted,
            maxLines = if (expanded) Int.MAX_VALUE else TaskHeadDescriptionCollapsedLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout ->
                if (!expanded && layout.hasVisualOverflow) collapsible = true
            },
        )
        if (collapsible) {
            RaftTextLink(
                text = stringResource(if (expanded) Res.string.thread_long_message_show_less else Res.string.thread_long_message_show_more),
                onClick = { expanded = !expanded },
                style = RaftTheme.typography.footnote,
            )
        }
    }
}

@Composable
private fun TaskHeadFacts(
    model: TaskConversationHeadModel,
    onSelectStatus: (TaskStatus) -> Unit,
    onOpenAssignee: () -> Unit,
    onOpenSource: (() -> Unit)?,
    onOpenEarlierDiscussion: (() -> Unit)?,
) {
    val bar = model.bar
    val statusLabel = bar.status.localizedLabel()
    val statusFieldLabel = stringResource(Res.string.tasks_label_status)
    val unassigned = stringResource(Res.string.tasks_assignee_unassigned)
    val unknown = stringResource(Res.string.task_head_unknown)
    var statusMenuOpen by remember { mutableStateOf(false) }
    RaftDescriptionList(direction = RaftDescriptionListDirection.Inline) {
        Item(stringResource(Res.string.task_head_status)) {
            Box {
                RaftTaskStatusBadge(
                    status = bar.status.raftTaskStatus(),
                    text = statusLabel,
                    onClick = if (bar.statusEditable) ({ statusMenuOpen = true }) else null,
                    enabled = !bar.statusUpdating,
                    contentDescription = "$statusFieldLabel: $statusLabel",
                )
                RaftDropdownMenu(
                    expanded = statusMenuOpen && bar.statusEditable,
                    onDismissRequest = { statusMenuOpen = false },
                    minWidth = 200.dp,
                ) {
                    bar.statusOptions.forEach { option ->
                        RaftMenuItem(
                            text =
                                if (option.reopensToTodo) {
                                    stringResource(Res.string.tasks_status_reopen_to_todo)
                                } else {
                                    option.status.localizedLabel()
                                },
                            checked = option.status == bar.status,
                            leadingIcon = { RaftTaskStatusPixel(option.status.raftTaskStatus()) },
                            onClick = {
                                statusMenuOpen = false
                                if (option.status != bar.status) onSelectStatus(option.status)
                            },
                        )
                    }
                }
            }
        }
        Item(stringResource(Res.string.task_head_assignee)) {
            // An assignee the reader may not change is a plain fact, not a disabled picker.
            if (!bar.assigneeEditable) {
                RaftDescriptionValue(bar.assigneeName?.let { "@$it" } ?: unassigned, copyable = false)
                return@Item
            }
            RaftPickerTriggerButton(
                text = bar.assigneeName?.let { "@$it" } ?: unassigned,
                onClick = onOpenAssignee,
                hasValue = bar.assigneeName != null,
                enabled = bar.assigneeEditable,
                showIndicator = bar.assigneeEditable,
                modifier = Modifier.widthIn(max = 200.dp),
                // The status badge's box (22dp chip): web InlineBadgeEditor draws both as the same chip.
                size = RaftPickerTriggerSize.Chip,
            )
        }
        model.source?.let { source ->
            Item(stringResource(Res.string.task_head_source)) {
                TaskHeadSourceValue(source, onOpenSource)
            }
        }
        Item(stringResource(Res.string.task_head_created_by), model.createdBy ?: unknown, copyable = false)
        model.createdAt?.let { Item(stringResource(Res.string.task_head_created_at), it, mono = true, copyable = false) }
        model.earlierDiscussion?.let { earlier ->
            Item(stringResource(Res.string.task_head_earlier_discussion)) {
                val label =
                    when (val count = earlier.replyCount) {
                        null -> stringResource(Res.string.task_head_earlier_discussion_view)
                        1 -> stringResource(Res.string.task_head_earlier_discussion_replies_one)
                        else -> stringResource(Res.string.task_head_earlier_discussion_replies_many, count)
                    }
                TaskHeadLinkValue(
                    contentDescription = stringResource(Res.string.task_head_earlier_discussion_open),
                    onClick = onOpenEarlierDiscussion,
                ) {
                    RaftText(label, style = RaftTheme.typography.callout, color = RaftTheme.colors.foreground, maxLines = 1)
                }
            }
        }
    }
}

/**
 * A fact that leads somewhere (web: a `rounded-control` button whose padding hangs outward, its
 * text on the facts' baseline, an arrow at the end): press shows `fill-pressed`.
 */
@Composable
private fun TaskHeadLinkValue(
    contentDescription: String,
    onClick: (() -> Unit)?,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(RaftRadius.Control)
    val pressed by source.collectIsPressedAsState()
    Row(
        Modifier
            // The padding hangs outward: the text starts where a plain fact's does.
            .offset(x = (-6).dp)
            .clip(shape)
            .then(if (pressed) Modifier.background(colors.fillPressed, shape) else Modifier)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .semantics(mergeDescendants = true) { if (onClick != null) this.contentDescription = contentDescription },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            content()
        }
        // Only a fact that leads somewhere carries the arrow.
        if (onClick != null) RaftGlyphIcon(RaftGlyph.ArrowRight, size = 14.dp, color = colors.foregroundHint)
    }
}

@Composable
private fun TaskHeadSourceValue(source: TaskHeadSource, onOpenSource: (() -> Unit)?) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    TaskHeadLinkValue(contentDescription = stringResource(Res.string.task_head_source_open), onClick = onOpenSource) {
        if (source.preview != null) {
            RaftAppAvatar(
                avatarWireValue = source.authorAvatarWireValue,
                label = source.authorName.orEmpty(),
                avatarSize = 18.dp,
                fallbackFill = raftAvatarFallbackFill(source.authorKind),
            )
        }
        source.authorName?.let {
            RaftText(it, style = typography.callout.copy(fontWeight = FontWeight.SemiBold), color = colors.foreground, maxLines = 1)
        }
        RaftText(
            source.preview ?: stringResource(Res.string.task_head_source_unloaded),
            Modifier.weight(1f, fill = false),
            style = typography.callout,
            color = colors.foregroundMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        source.time?.let { RaftText(it, style = typography.monoMeta, color = colors.foregroundHint, maxLines = 1) }
    }
}

@Composable
private fun TaskHeadHistorySection(history: TaskHeadHistory, initiallyOpen: Boolean) {
    var open by remember { mutableStateOf(initiallyOpen) }
    val colors = RaftTheme.colors
    Column(Modifier.padding(top = 16.dp)) {
        // Web SectionEyebrow as a button: the History heading folds the timeline (closed at first).
        Row(
            Modifier
                .clip(RoundedCornerShape(RaftRadius.Sticker))
                .clickable(role = Role.Button) { open = !open },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftSectionEyebrow(stringResource(Res.string.task_history_title))
            RaftGlyphIcon(if (open) RaftGlyph.ChevronDown else RaftGlyph.ChevronRight, size = 14.dp, color = colors.foregroundHint)
        }
        if (open) {
            Box(Modifier.padding(top = 12.dp).fillMaxWidth()) {
                when (history) {
                    TaskHeadHistory.Loading -> RaftSkeleton(Modifier.fillMaxWidth().height(16.dp))
                    TaskHeadHistory.Failed ->
                        RaftText(stringResource(Res.string.task_history_error), style = RaftTheme.typography.caption, color = colors.warningStrong)
                    is TaskHeadHistory.Rows ->
                        if (history.rows.isEmpty()) {
                            RaftText(stringResource(Res.string.task_history_empty), style = RaftTheme.typography.caption, color = colors.foregroundHint)
                        } else {
                            TaskHeadHistoryTimeline(history.rows)
                        }
                }
            }
        }
    }
}

@Composable
private fun TaskHeadHistoryTimeline(rows: List<TaskHeadHistoryRow>) {
    val titles = rows.map { it.kind.localizedTitle() }
    val details = rows.map { it.localizedDetailText() }
    RaftTimeline {
        rows.forEachIndexed { index, row ->
            val detail = row.detail
            item(
                title = titles[index],
                time = "${row.actor} · ${row.time}",
                titleBold = true,
                taskStatus = row.markerStatus?.raftTaskStatus(),
                content =
                    if (detail is TaskHistoryDetail.Transition) {
                        { TaskHeadTransition(detail.from, detail.to) }
                    } else {
                        details[index]?.let { text -> { RaftTimelineDetail(text) } }
                    },
            )
        }
    }
}

@Composable
private fun TaskHeadTransition(from: TaskStatus, to: TaskStatus) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        RaftTaskStatusBadge(from.raftTaskStatus(), from.localizedLabel(), size = RaftTaskStatusBadgeSize.Sm)
        RaftTimelineDetail("→")
        RaftTaskStatusBadge(to.raftTaskStatus(), to.localizedLabel(), size = RaftTaskStatusBadgeSize.Sm)
    }
}

@Composable
private fun TaskHistoryKind.localizedTitle(): String =
    when (this) {
        TaskHistoryKind.Created -> stringResource(Res.string.task_history_created)
        TaskHistoryKind.Amended -> stringResource(Res.string.task_history_amended)
        TaskHistoryKind.StatusChanged -> stringResource(Res.string.task_history_status_changed)
        TaskHistoryKind.AssigneeChanged -> stringResource(Res.string.task_history_assignee_changed)
        TaskHistoryKind.Reopened -> stringResource(Res.string.task_history_reopened)
        TaskHistoryKind.Closed -> stringResource(Res.string.task_history_closed)
        is TaskHistoryKind.Other -> eventType
    }

/** The detail line as text (web `historyDetail`); null = no line. A status transition draws badges instead. */
@Composable
private fun TaskHeadHistoryRow.localizedDetailText(): String? =
    when (val d = detail) {
        is TaskHistoryDetail.Created ->
            stringResource(Res.string.task_history_created_detail, d.taskNumber, (d.status ?: TaskStatus.Todo).localizedLabel())
        is TaskHistoryDetail.Transition -> null
        is TaskHistoryDetail.TransitionText -> "${d.from} → ${d.to}"
        is TaskHistoryDetail.FieldChanges -> {
            val title = stringResource(Res.string.task_history_field_title)
            val description = stringResource(Res.string.task_history_field_description)
            val template = stringResource(Res.string.task_history_field_change, "\u0000", "\u0001", "\u0002")
            d.changes.joinToString(" · ") { change ->
                val field =
                    when (val f = change.field) {
                        TaskHistoryField.Title -> title
                        TaskHistoryField.Description -> description
                        is TaskHistoryField.Other -> f.key
                    }
                template.replace("\u0000", field).replace("\u0001", change.from).replace("\u0002", change.to)
            }
        }
        is TaskHistoryDetail.Assigned ->
            stringResource(Res.string.task_history_assigned_to, assigneeName ?: stringResource(Res.string.task_head_unknown))
        TaskHistoryDetail.Unassigned -> stringResource(Res.string.task_history_unassigned)
        is TaskHistoryDetail.Raw -> d.text.takeIf { it.isNotBlank() }
    }

/**
 * The head with its assignee sheet, for one task conversation page: [task] from the task channel's
 * projection, [state] the page's header state, [details] the head's own reads. Writes and
 * navigation go out through the callbacks.
 */
@Composable
internal fun TaskConversationHeadRoute(
    task: TaskSummary,
    state: TaskConversationHeaderState,
    details: TaskConversationDetailsState,
    serverRole: String?,
    statusUpdating: Boolean,
    assigneeCandidates: List<MemberProfile>,
    timeContext: RaftTimeContext?,
    onUpdateStatus: (TaskStatus) -> Unit,
    onAssign: (MemberProfile?) -> Unit,
    onToggleFollow: (currentlyFollowed: Boolean) -> Unit,
    onOpenSource: () -> Unit,
    onOpenEarlierDiscussion: () -> Unit,
) {
    var assigneeSheetOpen by remember(task.id) { mutableStateOf(false) }
    var assigneeQuery by remember(task.id) { mutableStateOf("") }
    val model = task.taskConversationHeadModel(state, details, serverRole, statusUpdating, assigneeCandidates, timeContext)
    RaftDesignSystemTheme {
        TaskConversationHead(
            model = model,
            onSelectStatus = onUpdateStatus,
            onOpenAssignee = {
                if (model.bar.assigneeEditable) {
                    assigneeQuery = ""
                    assigneeSheetOpen = true
                }
            },
            onToggleFollow = { model.bar.followed?.let(onToggleFollow) },
            onOpenSource = onOpenSource,
            onOpenEarlierDiscussion = onOpenEarlierDiscussion,
        )
        if (assigneeSheetOpen && model.bar.assigneeEditable) {
            TaskConversationAssigneeSheet(
                current = task.assignee,
                candidates = taskConversationAssigneeCandidates(assigneeCandidates),
                query = assigneeQuery,
                onQueryChange = { assigneeQuery = it },
                onSelect = { member ->
                    val currentId = task.assignee?.let { it.kind to it.id }
                    val nextId = member?.let { it.kind to it.id }
                    if (currentId != nextId) onAssign(member)
                },
                onDismiss = { assigneeSheetOpen = false },
            )
        }
    }
}

/**
 * The head of a task opened before its conversation exists (the pending-conversation sheet, web
 * `TaskConversationDialog` with no conversation): the same title, description, facts and History,
 * read-only. Status, assignee and Follow wait for the conversation page that the first send opens.
 */
internal fun TaskSummary.taskPendingHeadModel(
    details: TaskConversationDetailsState,
    members: List<MemberProfile>,
    timeContext: RaftTimeContext?,
): TaskConversationHeadModel {
    val head =
        taskConversationHeadModel(
            state = TaskConversationHeaderState(conversationChannelId = conversationChannelId ?: ChannelId("")),
            details = details,
            serverRole = null,
            statusUpdating = false,
            members = members,
            timeContext = timeContext,
        )
    return head.copy(bar = head.bar.copy(statusOptions = emptyList(), assigneeEditable = false, followed = null))
}

/**
 * The head's reads ([TaskConversationDetailsViewModel]) for [task] outside a conversation page:
 * the pending-conversation sheet. Nothing is read without a [repository] and a [serverId].
 */
@Composable
internal fun rememberTaskConversationDetails(
    task: TaskSummary,
    repository: TaskConversationDetailsRepository?,
    serverId: String?,
): TaskConversationDetailsState {
    if (repository == null || serverId.isNullOrBlank()) return TaskConversationDetailsState()
    val scope = rememberCoroutineScope()
    val server = ServerId(serverId)
    val viewModel =
        remember(task.id, repository, serverId) {
            TaskConversationDetailsViewModel(
                loadHistory = { taskId -> repository.loadHistory(server, taskId) },
                loadSource = { channelId, messageId -> repository.loadSourceMessage(server, channelId, messageId) },
                loadEarlierDiscussion = { channelId, hostId -> repository.loadEarlierDiscussion(server, channelId, hostId) },
                scopeOverride = scope,
            )
        }
    LaunchedEffect(viewModel, task) { viewModel.onTask(task) }
    return viewModel.state.collectAsState().value
}
