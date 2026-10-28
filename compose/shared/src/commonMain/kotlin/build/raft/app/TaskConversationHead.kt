package build.raft.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftPullDownPanel
import build.raft.designsystem.motion.RaftMotion
import build.raft.designsystem.motion.rememberRaftReducedMotion
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
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import build.raft.designsystem.tokens.RaftStroke
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.composed
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.SideEffect
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
import build.raft.designsystem.components.RaftButtonSize
import build.raft.designsystem.components.RaftButton
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.data.TaskConversationDetailsState
import build.raft.app.data.formatTaskConversationRef
import kotlinx.coroutines.flow.onStart
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersStore
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
    /** Web `formatMediumDateTime24`: shown as "Oct 28, 2026, 09:14" (localized). */
    val createdAt: TaskHeadDateTime?,
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
    /** False: no store to write to here (or it holds the offline stand-in): status and assignee are plain facts. */
    editable: Boolean = true,
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
        bar =
            taskConversationHeaderBarModel(state, serverRole, statusUpdating).let { bar ->
                if (editable) bar else bar.copy(statusOptions = emptyList(), assigneeEditable = false)
            },
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
        createdAt = taskHeadDateTime(createdAt, timeContext),
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

/** On a phone the head shows at most this much of the page at once (the rest comes as it scrolls). */
private const val TaskHeadPhoneMaxFraction = 0.6f

/** Narrower than this the head scrolls with the conversation (web `mobile-modal`); wider it stays pinned. */
internal val TaskHeadPinnedMinWidth = 600.dp

/**
 * Where the head of a phone's task conversation is: web puts the head in the conversation as its
 * first row, so it scrolls away with it (Artea, #wg-task-v2 #7: pinned, it left the replies a
 * sliver). Here the head sits above the conversation and moves with the conversation's scroll
 * ([connection], on an ancestor of the message list): a drag towards newer messages slides it away
 * before the list scrolls; a drag back down brings it back only once the list is at its oldest
 * message, as the first row would. [collapsed]: out of view (the top bar then names the task's
 * state). Off a phone it stays pinned and none of this applies.
 *
 * While it is out of view, the top bar pulls it down over the messages ([floatOpen], web
 * `TaskHeadPullDown`, slock #11722): [toggleFloat] on the bar; it closes on the bar again, a tap
 * outside it, a swipe up on it, Back / Escape, focus in the composer ([closeFloat]) and as soon as
 * the reader scrolls the messages ([connection]) or the head comes back in view.
 */
@Stable
internal class TaskHeadScrollState {
    var heightPx by mutableFloatStateOf(0f)
        internal set
    var offsetPx by mutableFloatStateOf(0f)
        private set
    var scrollsWithConversation by mutableStateOf(false)
        internal set

    val collapsed: Boolean
        get() = scrollsWithConversation && heightPx > 0f && offsetPx >= heightPx - 0.5f

    private var floatRequested by mutableStateOf(false)

    /** The bottom edge of the pulled-down head in root coordinates (a press below it is outside). */
    internal var floatBottomInRootPx: Float = 0f

    /** The head pulled down from the top bar over the messages: only while it is out of view. */
    val floatOpen: Boolean
        get() = floatRequested && collapsed

    /** The top bar's summary was tapped: pull the head down, or put it back up. */
    fun toggleFloat() {
        floatRequested = !floatOpen && collapsed
    }

    /** Puts the pulled-down head back up; true when it was open (Back took it). */
    fun closeFloat(): Boolean {
        val wasOpen = floatOpen
        floatRequested = false
        return wasOpen
    }

    /** Back in view, the head needs no panel (and one must not come back with the next scroll away). */
    private fun dropFloatIfInView() {
        if (!collapsed) floatRequested = false
    }

    /** Moves the head by a drag [delta] (negative: up, away); returns the part it took. */
    fun consume(delta: Float): Float {
        if (!scrollsWithConversation) return 0f
        val old = offsetPx
        val next = (old - delta).coerceIn(0f, heightPx)
        offsetPx = next
        dropFloatIfInView()
        return old - next
    }

    /** Out of view: the keyboard came up (the conversation keeps the room above it). */
    fun collapse() {
        // Before the head is measured, "all of it": [updateHeight] clamps it to the height.
        if (scrollsWithConversation) offsetPx = if (heightPx > 0f) heightPx else Float.POSITIVE_INFINITY
    }

    /** Back in view. */
    fun expand() {
        offsetPx = 0f
        floatRequested = false
    }

    internal fun updateHeight(height: Float) {
        if (height == heightPx) return
        val wasCollapsed = collapsed
        heightPx = height
        offsetPx = if (wasCollapsed) height else offsetPx.coerceIn(0f, height)
        dropFloatIfInView()
    }

    val connection: NestedScrollConnection =
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // The reader starts scrolling the messages: the pulled-down head goes back up (web).
                if (source == NestedScrollSource.UserInput && available.y != 0f) floatRequested = false
                return if (available.y < 0f) Offset(0f, consume(available.y)) else Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (available.y > 0f) Offset(0f, consume(available.y)) else Offset.Zero
        }
}

/**
 * Task v2: the head of a task conversation (web `TaskModalHead` in `TaskConversationDialog`). In the
 * order the questions arise: what is this (the title, `text-headline` 600, and the description,
 * `text-subhead` muted, folded to three lines with Show More), what state is it in (the inline
 * facts: Status and Assignee as the editors they were, Source, Created by, Created at, Earlier
 * discussion), and what happened to it (History, folded, as on web).
 *
 * On a phone (narrower than [TaskHeadPinnedMinWidth]) it scrolls with the conversation
 * ([TaskHeadScrollState]) and folds away while the keyboard is up ([keyboardUp]); wider it stays
 * pinned and never takes more than [maxHeightFraction] of the page: past that it scrolls inside
 * itself (web's pinned head, `max-h-[45%]`).
 */
@Composable
internal fun TaskConversationHead(
    model: TaskConversationHeadModel,
    onSelectStatus: (TaskStatus) -> Unit,
    onOpenAssignee: () -> Unit,
    /** Null (also [onOpenEarlierDiscussion]): a plain fact, as on the pending task page. */
    onOpenSource: (() -> Unit)?,
    onOpenEarlierDiscussion: (() -> Unit)?,
    modifier: Modifier = Modifier,
    scrollState: TaskHeadScrollState = remember { TaskHeadScrollState() },
    keyboardUp: Boolean = false,
    initiallyHistoryOpen: Boolean = false,
    initiallyDescriptionExpanded: Boolean = false,
    maxHeightFraction: Float = 0.45f,
    onRetryHistory: (() -> Unit)? = null,
) {
    val colors = RaftTheme.colors
    val content: @Composable () -> Unit = {
        TaskHeadContent(
            model = model,
            onSelectStatus = onSelectStatus,
            onOpenAssignee = onOpenAssignee,
            onOpenSource = onOpenSource,
            onOpenEarlierDiscussion = onOpenEarlierDiscussion,
            initiallyHistoryOpen = initiallyHistoryOpen,
            initiallyDescriptionExpanded = initiallyDescriptionExpanded,
            onRetryHistory = onRetryHistory,
        )
    }
    val padding = Modifier.raftSafeAreaHorizontalPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)
    BoxWithConstraints(modifier.fillMaxWidth().background(colors.layerCanvas)) {
        val pageHeight = maxHeight
        val phone = maxWidth < TaskHeadPinnedMinWidth
        SideEffect { scrollState.scrollsWithConversation = phone }
        LaunchedEffect(phone, keyboardUp) {
            if (phone && keyboardUp) {
                scrollState.collapse()
                // Typing: the composer has focus, so the pulled-down head goes back up.
                scrollState.closeFloat()
            }
        }
        Column(Modifier.fillMaxWidth()) {
            if (phone) {
                // The head at its full height, shown from [offsetPx] down: it slides up under the top bar.
                Layout(
                    content = {
                        Column(Modifier.fillMaxWidth().onSizeChanged { scrollState.updateHeight(it.height.toFloat()) }.then(padding)) {
                            content()
                        }
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clipToBounds()
                            .draggable(
                                orientation = Orientation.Vertical,
                                state = rememberDraggableState { delta -> scrollState.consume(delta) },
                            ),
                ) { measurables, constraints ->
                    val placeable = measurables.single().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                    val offset = scrollState.offsetPx.roundToInt().coerceIn(0, placeable.height)
                    // Never more than [TaskHeadPhoneMaxFraction] of the page: the conversation and the
                    // composer keep the rest; a taller head shows its top, the rest comes with the drag.
                    val visibleCap =
                        if (pageHeight.value.isFinite()) (pageHeight * TaskHeadPhoneMaxFraction).roundToPx() else placeable.height
                    layout(placeable.width, (placeable.height - offset).coerceAtMost(visibleCap)) { placeable.place(0, -offset) }
                }
                if (!scrollState.collapsed) TaskHeadDivider()
            } else {
                val cap = if (pageHeight.value.isFinite()) pageHeight * maxHeightFraction else pageHeight
                Column(Modifier.fillMaxWidth().heightIn(max = cap).verticalScroll(rememberScrollState()).then(padding)) {
                    content()
                }
                TaskHeadDivider()
            }
        }
    }
}

/** The top bar's seam (raft-ui panel head: `line-seam` flattened on the canvas), so both rules match. */
@Composable
private fun TaskHeadDivider() {
    val colors = RaftTheme.colors
    Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(colors.lineSeam.compositeOver(colors.layerCanvas)))
}

/**
 * The head's content without its frame (the conversation page's head, and the pending task
 * page's, a task opened before its conversation exists). Null navigation callbacks draw Source and Earlier discussion as plain
 * facts; a bar model with no status options and no editable assignee draws them as plain facts too.
 */
@Composable
internal fun TaskHeadContent(
    model: TaskConversationHeadModel,
    onSelectStatus: (TaskStatus) -> Unit = {},
    onOpenAssignee: () -> Unit = {},
    onOpenSource: (() -> Unit)? = null,
    onOpenEarlierDiscussion: (() -> Unit)? = null,
    initiallyHistoryOpen: Boolean = false,
    initiallyDescriptionExpanded: Boolean = false,
    /** Null: no Retry after a failed History read. */
    onRetryHistory: (() -> Unit)? = null,
) {
    Column {
        TaskHeadTitleRow(model)
        Column {
            model.description?.let { TaskHeadDescription(it, initiallyDescriptionExpanded) }
            TaskHeadFacts(model, onSelectStatus, onOpenAssignee, onOpenSource, onOpenEarlierDiscussion)
            TaskHeadHistorySection(model.history, initiallyHistoryOpen, onRetryHistory)
        }
    }
}

@Composable
private fun TaskHeadTitleRow(model: TaskConversationHeadModel) {
    // Follow / Unfollow Task is the header More menu's, as on web (ThreadOverflowMenu).
    RaftText(
        model.title,
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp).semantics { heading() },
        style = RaftTheme.typography.headline.copy(fontWeight = FontWeight.SemiBold),
        color = RaftTheme.colors.foreground,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun TaskHeadDescription(description: String, initiallyExpanded: Boolean) {
    var expanded by remember(description) { mutableStateOf(initiallyExpanded) }
    var collapsible by remember(description) { mutableStateOf(false) }
    // With a toggle, its 44dp target is the gap to the facts; without one, 12dp.
    Column(Modifier.padding(bottom = if (collapsible) 0.dp else 12.dp)) {
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
            val label = stringResource(if (expanded) Res.string.thread_long_message_show_less else Res.string.thread_long_message_show_more)
            TaskHeadToggleTarget(label = label, expanded = expanded, onToggle = { expanded = !expanded }) {
                RaftTextLink(
                    text = label,
                    onClick = { expanded = !expanded },
                    style = RaftTheme.typography.footnote,
                    // The 44dp target around it is the one control screen readers see.
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
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
                    // raft-ui inline: a leading chip hangs its padding outward (the text lines up).
                    modifier = Modifier.offset(x = TaskHeadChipHang),
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
                RaftDescriptionValue(bar.assigneeName ?: unassigned, copyable = false, maxLines = 1)
                return@Item
            }
            // The assignee by display name, as web's v2 dialog shows it (`assigneeAsPerson`).
            RaftPickerTriggerButton(
                text = bar.assigneeName ?: unassigned,
                onClick = onOpenAssignee,
                hasValue = bar.assigneeName != null,
                enabled = bar.assigneeEditable,
                showIndicator = bar.assigneeEditable,
                modifier = Modifier.offset(x = TaskHeadChipHang).widthIn(max = 200.dp),
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
        model.createdAt?.let { Item(stringResource(Res.string.task_head_created_at), it.localized(), mono = true, copyable = false) }
        // Where it cannot open (the pending task page) it is a plain count, and hidden while unknown.
        model.earlierDiscussion?.takeIf { onOpenEarlierDiscussion != null || it.replyCount != null }?.let { earlier ->
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
            // A 48dp touch target with no visible padding (it overhangs the row).
            .then(if (onClick != null) Modifier.minimumInteractiveComponentSize() else Modifier)
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
private fun TaskHeadHistorySection(history: TaskHeadHistory, initiallyOpen: Boolean, onRetry: (() -> Unit)?) {
    var open by remember { mutableStateOf(initiallyOpen) }
    val colors = RaftTheme.colors
    val title = stringResource(Res.string.task_history_title)
    Column(Modifier.padding(top = 4.dp)) {
        // Web SectionEyebrow as a button: the History heading folds the timeline (closed at first).
        TaskHeadToggleTarget(label = title, expanded = open, onToggle = { open = !open }) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                RaftSectionEyebrow(title)
                RaftGlyphIcon(if (open) RaftGlyph.ChevronDown else RaftGlyph.ChevronRight, size = 14.dp, color = colors.foregroundHint)
            }
        }
        if (open) {
            Box(Modifier.padding(top = 4.dp).fillMaxWidth()) {
                when (history) {
                    TaskHeadHistory.Loading -> RaftSkeleton(Modifier.fillMaxWidth().height(16.dp))
                    TaskHeadHistory.Failed ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RaftText(
                                stringResource(Res.string.task_history_error),
                                Modifier.weight(1f, fill = false),
                                style = RaftTheme.typography.caption,
                                color = colors.warningStrong,
                            )
                            onRetry?.let { retry ->
                                RaftButton(
                                    text = stringResource(Res.string.common_retry),
                                    onClick = retry,
                                    variant = RaftButtonVariant.Ghost,
                                    size = RaftButtonSize.Md,
                                )
                            }
                        }
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

/**
 * A fold toggle (History, Show More) with a 48dp touch target around its small visible label: a
 * button for screen readers, named [label], that says whether it is expanded and offers expand /
 * collapse.
 */
@Composable
private fun TaskHeadToggleTarget(label: String, expanded: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                if (expanded) collapse { onToggle(); true } else expand { onToggle(); true }
            },
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}

/** raft-ui inline facts: a leading chip / picker trigger hangs 7dp outward. */
private val TaskHeadChipHang = (-7).dp

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
    /** Null (also [onOpenEarlierDiscussion]): a plain fact (the pending task page). */
    onOpenSource: (() -> Unit)?,
    onOpenEarlierDiscussion: (() -> Unit)?,
    scrollState: TaskHeadScrollState,
    /** The keyboard is up: on a phone the head folds away. */
    keyboardUp: Boolean = false,
    onRetryHistory: (() -> Unit)? = null,
    /** False: status and assignee are plain facts whatever the role ([taskConversationHeadModel]). */
    editable: Boolean = true,
    /** The head pulled down from the top bar over the messages ([TaskHeadFloat]), not the one in the conversation. */
    floating: Boolean = false,
) {
    var assigneeSheetOpen by remember(task.id) { mutableStateOf(false) }
    var assigneeQuery by remember(task.id) { mutableStateOf("") }
    val model = task.taskConversationHeadModel(state, details, serverRole, statusUpdating, assigneeCandidates, timeContext, editable)
    val onOpenAssignee = {
        if (model.bar.assigneeEditable) {
            assigneeQuery = ""
            assigneeSheetOpen = true
        }
    }
    RaftDesignSystemTheme {
        if (floating) {
            val label = task.taskNumber?.let { stringResource(Res.string.tasks_detail_task_identifier, it) } ?: task.title
            TaskHeadFloat(scrollState = scrollState, label = label) {
                TaskHeadContent(
                    model = model,
                    onSelectStatus = onUpdateStatus,
                    onOpenAssignee = onOpenAssignee,
                    onOpenSource = onOpenSource,
                    onOpenEarlierDiscussion = onOpenEarlierDiscussion,
                    onRetryHistory = onRetryHistory,
                )
            }
        } else {
            TaskConversationHead(
                model = model,
                onSelectStatus = onUpdateStatus,
                onOpenAssignee = onOpenAssignee,
                onOpenSource = onOpenSource,
                onOpenEarlierDiscussion = onOpenEarlierDiscussion,
                scrollState = scrollState,
                keyboardUp = keyboardUp,
                onRetryHistory = onRetryHistory,
            )
        }
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

/** The pending task page's head reads and their Retry. */
internal class TaskHeadDetailsHandle(val state: TaskConversationDetailsState, val retryHistory: (() -> Unit)?)

/**
 * The head's reads ([TaskConversationDetailsViewModel]) for [task] outside a conversation page:
 * the pending task page. Nothing is read without a [repository] and a [serverId].
 */
@Composable
internal fun rememberTaskConversationDetails(
    task: TaskSummary,
    repository: TaskConversationDetailsRepository?,
    serverId: String?,
): TaskHeadDetailsHandle {
    if (repository == null || serverId.isNullOrBlank()) return TaskHeadDetailsHandle(TaskConversationDetailsState(), null)
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
    return TaskHeadDetailsHandle(viewModel.state.collectAsState().value, viewModel::retryHistory)
}

/**
 * The task channel's people and agents (web `useChannelMembers(task.channelId)`), so the pending
 * task page names creators and offers assignees as the conversation page does. Empty without a store.
 */
@Composable
internal fun rememberTaskChannelMembers(
    store: SharedServerMembersStore?,
    serverId: String?,
    channelId: ChannelId?,
): List<MemberProfile> {
    val server = serverId?.takeIf { it.isNotBlank() }?.let(::ServerId)
    if (store == null || server == null || channelId == null) return emptyList()
    val flow =
        remember(store, server, channelId) {
            store.channelMemberSliceFlow(server, channelId)
                .onStart { store.loadChannelMembers(server, channelId, DataRequestPriority.Visible) }
        }
    return flow.collectAsState(SharedServerChannelMembersSlice(serverId = server.value, channelId = channelId.value)).value.members
}

/** A moment in the reader's time zone, for web `formatMediumDateTime24` (medium date, 24-hour time). */
@Immutable
internal data class TaskHeadDateTime(val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int)

/** [iso] in the reader's offset ([timeContext]; UTC without one); null when it is not a timestamp. */
internal fun taskHeadDateTime(iso: String?, timeContext: RaftTimeContext?): TaskHeadDateTime? {
    val offsetMinutes = timeContext?.localOffsetMinutes ?: 0
    val epochMillis = parseRaftTimestampEpochMillisOrNull(iso, offsetMinutes) ?: return null
    val local = epochMillis + offsetMinutes * 60_000L
    val dayMillis = 86_400_000L
    val days = local.floorDiv(dayMillis)
    val millisOfDay = local.mod(dayMillis)
    // Days since 1970-01-01 to a civil date (H. Hinnant, `civil_from_days`).
    val z = days + 719_468
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
    return TaskHeadDateTime(year, month, day, (millisOfDay / 3_600_000L).toInt(), ((millisOfDay / 60_000L) % 60).toInt())
}

@Composable
private fun TaskHeadDateTime.localized(): String {
    val months =
        listOf(
            Res.string.task_head_month_1, Res.string.task_head_month_2, Res.string.task_head_month_3,
            Res.string.task_head_month_4, Res.string.task_head_month_5, Res.string.task_head_month_6,
            Res.string.task_head_month_7, Res.string.task_head_month_8, Res.string.task_head_month_9,
            Res.string.task_head_month_10, Res.string.task_head_month_11, Res.string.task_head_month_12,
        )
    val time = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
    return stringResource(Res.string.task_head_medium_date_time, stringResource(months[month - 1]), day, year, time)
}

/**
 * The task conversation's top bar (web `TaskConversationBar`): the task's ref (`#channel:#7`, Space
 * Mono, muted) over "Task #7". While the head is scrolled away ([summarized]) the second line is
 * "Task #7", the status badge, the assignee's face and a chevron (slock #11722): tapping the bar
 * pulls the head down over the messages ([headFloatOpen]) and tapping it again puts it back. The
 * More menu's View in Channel and Follow / Unfollow Task go with it.
 */
@Immutable
internal data class TaskConversationHeaderChrome(
    val ref: String,
    val taskNumber: Int?,
    val status: TaskStatus,
    /** The assignee's name: the face's label for screen readers (the bar shows the face only). */
    val assigneeName: String?,
    val assigneeKind: MemberKind? = null,
    val assigneeAvatarWireValue: String? = null,
    val summarized: Boolean,
    val headFloatOpen: Boolean = false,
    /** Null: not known yet (no Follow row). */
    val followed: Boolean?,
    val onToggleHeadFloat: () -> Unit,
    val onViewInChannel: () -> Unit,
    val onToggleFollow: () -> Unit,
)

internal fun TaskSummary.taskConversationHeaderChrome(
    state: TaskConversationHeaderState,
    summarized: Boolean,
    headFloatOpen: Boolean,
    onToggleHeadFloat: () -> Unit,
    onViewInChannel: () -> Unit,
    onToggleFollow: (currentlyFollowed: Boolean) -> Unit,
): TaskConversationHeaderChrome {
    val followed = state.followed(conversationFollowed)
    return TaskConversationHeaderChrome(
        ref = taskNumber?.let { formatTaskConversationRef(it, channelName, parseConversationKindOrNull(channelType)) } ?: title,
        taskNumber = taskNumber,
        status = status,
        assigneeName = assignee?.displayName?.trim()?.takeIf { it.isNotBlank() },
        assigneeKind = assignee?.kind,
        assigneeAvatarWireValue = assignee?.avatarWireValueForRenderedAvatar(),
        summarized = summarized,
        headFloatOpen = headFloatOpen && summarized,
        followed = followed,
        onToggleHeadFloat = onToggleHeadFloat,
        onViewInChannel = onViewInChannel,
        onToggleFollow = { followed?.let(onToggleFollow) },
    )
}

/** The top bar's title block for [chrome] (in place of the conversation title). */
@Composable
internal fun TaskConversationBarTitle(chrome: TaskConversationHeaderChrome, modifier: Modifier = Modifier) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val label =
        chrome.taskNumber?.let { stringResource(Res.string.tasks_detail_task_identifier, it) } ?: chrome.ref
    // At large text the bar has room for one line: the task line keeps it.
    val refLine = LocalDensity.current.fontScale <= 1.3f
    // The whole bar is the toggle while the head is away (web: the summary button; here the bar's
    // title block, the ref line included, so a tap anywhere on it opens the head).
    val focusManager = LocalFocusManager.current
    val toggle =
        if (chrome.summarized) {
            val open = chrome.headFloatOpen
            val onToggle = {
                // Opening it takes focus off the composer (web moves focus into the panel).
                if (!open) focusManager.clearFocus()
                chrome.onToggleHeadFloat()
            }
            Modifier
                .clip(RoundedCornerShape(RaftRadius.Control))
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics(mergeDescendants = true) {
                    if (open) collapse { onToggle(); true } else expand { onToggle(); true }
                }
        } else {
            Modifier
        }
    Column(modifier.then(toggle), verticalArrangement = Arrangement.Top) {
        if (refLine) {
            RaftText(chrome.ref, style = typography.monoMeta, color = colors.foregroundMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (chrome.summarized) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                RaftText(label, style = typography.subhead.copy(fontWeight = FontWeight.Bold), color = colors.foreground, maxLines = 1)
                RaftTaskStatusBadge(chrome.status.raftTaskStatus(), chrome.status.localizedLabel(), size = RaftTaskStatusBadgeSize.Sm)
                // The face only (Kirby: a cut name says less than the face), named for screen readers.
                chrome.assigneeName?.let { name ->
                    RaftAppAvatar(
                        avatarWireValue = chrome.assigneeAvatarWireValue,
                        label = name,
                        modifier = Modifier.semantics { contentDescription = name },
                        avatarSize = RaftAvatarSize.Xs.edge,
                        fallbackFill = raftAvatarFallbackFill(chrome.assigneeKind),
                    )
                }
                TaskBarChevron(open = chrome.headFloatOpen)
            }
        } else {
            RaftText(label, style = typography.subhead.copy(fontWeight = FontWeight.Bold), color = colors.foreground, maxLines = 1)
        }
    }
}

/** The bar's chevron: down, and up while the head is pulled down (turned with `ease-slide`). */
@Composable
private fun TaskBarChevron(open: Boolean) {
    val reducedMotion = rememberRaftReducedMotion()
    val turn by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = if (reducedMotion) snap() else tween(RaftMotion.SlideMillis, easing = RaftMotion.EaseSlide),
        label = "task-bar-chevron",
    )
    RaftGlyphIcon(RaftGlyph.ChevronDown, Modifier.rotate(turn), size = 14.dp, color = RaftTheme.colors.foregroundHint)
}

/**
 * The head pulled down from the top bar over the messages (web `TaskHeadPullDown`, slock #11722):
 * the same title, description, facts and History as the head in the conversation, editable the
 * same way, without losing the reader's place. Composed at the top of the box over the messages.
 */
@Composable
internal fun TaskHeadFloat(
    scrollState: TaskHeadScrollState,
    label: String,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = rememberRaftReducedMotion(),
    content: @Composable () -> Unit,
) {
    RaftPullDownPanel(
        open = scrollState.floatOpen,
        onDismiss = { scrollState.closeFloat() },
        modifier = modifier.onGloballyPositioned { scrollState.floatBottomInRootPx = it.boundsInRoot().bottom },
        paneTitle = label,
        reducedMotion = reducedMotion,
    ) {
        Column(Modifier.fillMaxWidth().raftSafeAreaHorizontalPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
            content()
        }
    }
}

/**
 * On the whole task page: a press anywhere outside the pulled-down head puts it back up, the
 * composer and its buttons included (web: a tap anywhere outside closes it). The press still goes
 * where the reader meant it. The top bar sits above the head, so a press there is not outside: the
 * bar's own tap toggles the head.
 */
internal fun Modifier.taskHeadFloatOutsidePress(scrollState: TaskHeadScrollState): Modifier =
    composed {
        val coordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
        onGloballyPositioned { coordinates[0] = it }
            .pointerInput(scrollState) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val layout = coordinates[0]?.takeIf { it.isAttached } ?: return@awaitEachGesture
                    if (scrollState.floatOpen &&
                        taskHeadFloatPressIsOutside(layout.localToRoot(down.position).y, scrollState.floatBottomInRootPx)
                    ) {
                        scrollState.closeFloat()
                    }
                }
            }
    }

/** A press at [rootY] misses the pulled-down head whose bottom edge is at [floatBottomInRootPx]. */
internal fun taskHeadFloatPressIsOutside(rootY: Float, floatBottomInRootPx: Float): Boolean = rootY > floatBottomInRootPx
