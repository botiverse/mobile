package build.raft.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftPullDownPanel
import build.raft.designsystem.motion.RaftMotion
import build.raft.designsystem.motion.rememberRaftReducedMotion
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import build.raft.designsystem.components.raftClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalDensity
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import build.raft.designsystem.components.raftTouchTarget
import androidx.compose.ui.composed
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.Stable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.app.data.TaskConversationDetailsState
import build.raft.app.data.formatTaskConversationRef
import build.raft.app.data.taskConversationRefParts
import kotlinx.coroutines.flow.onStart
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersStore
import build.raft.app.data.TaskConversationHeaderState
import build.raft.app.data.TaskAmendPatch
import build.raft.app.data.TaskAmendResult
import build.raft.app.data.taskStatusPickAsksBacklogReason
import build.raft.app.data.TaskDetailLoad
import build.raft.app.data.TaskHistoryDetail
import build.raft.app.data.TaskHistoryField
import build.raft.app.data.TaskHistoryKind
import build.raft.app.data.TaskHistoryRow
import build.raft.app.data.TaskHeadSourceDisplay
import build.raft.app.data.earlierDiscussionVisible
import build.raft.app.data.taskHeadSourceDisplay
import build.raft.app.data.toTaskHistoryRows
import build.raft.app.data.valueOrNull
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MillisPerDay
import build.raft.app.model.MillisPerHour
import build.raft.app.model.MillisPerMinute
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.avatarWireValueForRenderedAvatar
import build.raft.app.model.civilDateFromEpochDay
import build.raft.app.model.floorDiv
import build.raft.app.model.floorMod
import build.raft.app.model.formatRaftClock
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
import build.raft.designsystem.components.RaftSectionEyebrow
import build.raft.designsystem.components.RaftSkeleton
import build.raft.designsystem.components.RaftTaskStatusBadge
import build.raft.designsystem.components.RaftTaskStatusBadgeSize
import build.raft.designsystem.components.RaftTaskStatusPixel
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.components.RaftTimeline
import build.raft.designsystem.components.RaftTimelineDetail
import build.raft.designsystem.components.RaftTooltip
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding
import build.raft.designsystem.components.raftComboboxPickerInPlace
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.theme.RaftScale
import build.raft.designsystem.tokens.RaftRadius

/** The Source row (web `TaskSourceProperty`): [preview] null = the message is not loaded (yet); [notRecorded] = `sourceRecorded == false`. */
@Immutable
internal data class TaskHeadSource(
    val authorName: String?,
    val authorKind: MemberKind?,
    /** The avatar the source message carried; the face resolves by [authorId] first ([RaftMemberAvatar]). */
    val authorAvatarWireValue: String?,
    val preview: String?,
    val time: String?,
    val authorId: String? = null,
    val notRecorded: Boolean = false,
) {
    companion object {
        val NotRecorded =
            TaskHeadSource(
                authorName = null,
                authorKind = null,
                authorAvatarWireValue = null,
                preview = null,
                time = null,
                authorId = null,
                notRecorded = true,
            )
    }
}

/** Assignee in the head's inline facts (web `TaskProperties` with `assigneeAsPerson = true`). */
@Immutable
internal data class TaskHeadAssignee(
    val kind: MemberKind,
    val id: String,
    val displayName: String,
    val avatarWireValue: String? = null,
    val isViewer: Boolean = false,
    val isJointChannel: Boolean = false,
)

/** A person referenced by the head (avatar/profile target). */
@Immutable
internal data class TaskHeadPerson(val kind: MemberKind, val id: String, val avatarWireValue: String? = null)

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

    data class Failed(val traceId: String? = null) : TaskHeadHistory

    data class Rows(val rows: List<TaskHeadHistoryRow>) : TaskHeadHistory
}

/**
 * Task v2: what the task conversation's head shows (web `TaskModalHead` + `TaskProperties` in the
 * task dialog): the title, the description, the facts (Status, Assignee, Source, Created by with
 * when it was created, Earlier discussion) and the History. Derived purely by [taskConversationHeadModel].
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
    /** Null: no face beside [createdBy] (no creator on the task). */
    val creator: TaskHeadPerson? = null,
    /** The server the people in the head belong to: their faces read its member directory. */
    val avatarServerId: String? = null,
    /**
     * Web `formatMediumDateTime24` ("Oct 28, 2026, 09:14", localized): no row of its own, it rides
     * on Created by as "Created at …" (web slock #15639; [TaskHeadCreatedByValue]).
     */
    val createdAt: TaskHeadDateTime?,
    /** Null: no Earlier discussion row; [Int] the reply count, null inside = not known yet ("View"). */
    val earlierDiscussion: TaskHeadEarlierDiscussion?,
    val history: TaskHeadHistory,
    /** Assigned person/agent for the inline Assignee fact (avatar + name / "You"); null when unassigned. */
    val assignee: TaskHeadAssignee? = null,
)

@Immutable
internal data class TaskHeadEarlierDiscussion(val replyCount: Int?)

/** Web `displayNameFor(type, id)`: the channel member's display name, else null. */
internal fun List<MemberProfile>.taskHeadDisplayName(kind: MemberKind, id: String): String? =
    firstOrNull { it.kind == kind && it.id.value == id }?.displayName?.trim()?.takeIf { it.isNotBlank() }

internal fun TaskSummary.taskConversationHeadModel(
    state: TaskConversationHeaderState,
    details: TaskConversationDetailsState,
    statusUpdating: Boolean,
    members: List<MemberProfile>,
    timeContext: RaftTimeContext?,
    /** False: no store to write to here (or it holds the offline stand-in): status and assignee are plain facts. */
    editable: Boolean = true,
    /** The task's server, whose member directory the head's faces read. */
    serverId: String? = null,
    /** False: the reader may not write the task's channel ([build.raft.app.data.canWriteTasksInChannel]). */
    canWriteChannel: Boolean = true,
    /** Signed-in user's id so self-assigned tasks display "You" (web `task.sidePanel.you`). */
    viewerUserId: String? = null,
): TaskConversationHeadModel {
    val sourceDisplay = taskHeadSourceDisplay()
    val sourceId = (sourceDisplay as? TaskHeadSourceDisplay.Message)?.messageId
    val sourcePreview = details.source.valueOrNull?.takeIf { it.messageId == sourceId }
    val author = sourcePreview?.author
    val normalizedViewerId = viewerUserId?.trim()?.takeIf { it.isNotEmpty() }
    val isJointChannel = parseConversationKindOrNull(channelType) == ConversationKind.Joint
    val assigneeHead =
        assignee?.let { member ->
            val resolvedName =
                members.taskHeadDisplayName(member.kind, member.id.value)
                    ?: member.displayName.trim().takeIf { it.isNotBlank() }
            val id = member.id.value.trim()
            if (id.isNotEmpty() && resolvedName != null) {
                val channelMember = members.firstOrNull { it.kind == member.kind && it.id.value == member.id.value }
                TaskHeadAssignee(
                    kind = member.kind,
                    id = id,
                    displayName = resolvedName,
                    avatarWireValue =
                        channelMember?.avatar?.let { channelMember.avatarWireValueForRenderedAvatar() }
                            ?: member.avatarWireValueForRenderedAvatar(),
                    isViewer = member.kind == MemberKind.Human && normalizedViewerId != null && id == normalizedViewerId,
                    isJointChannel = isJointChannel,
                )
            } else {
                null
            }
        }
    val creatorName =
        creator?.let { member ->
            members.taskHeadDisplayName(member.kind, member.id.value)
                ?: member.displayName.trim().takeIf { it.isNotBlank() }?.let { "@$it" }
        }
    val history =
        when (val load = details.history) {
            is TaskDetailLoad.Loaded ->
                TaskHeadHistory.Rows(load.value.toTaskHistoryRows().map { it.toHeadRow(members, timeContext) })
            is TaskDetailLoad.Failed -> TaskHeadHistory.Failed(load.traceId)
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
            taskConversationHeaderBarModel(state, statusUpdating, canWriteChannel).let { bar ->
                val resolvedBar = if (assigneeHead != null) bar.copy(assigneeName = assigneeHead.displayName) else bar
                if (editable) resolvedBar else resolvedBar.copy(statusOptions = emptyList(), assigneeEditable = false)
            },
        title = title,
        description = description?.trim()?.takeIf { it.isNotBlank() },
        source =
            when (sourceDisplay) {
                TaskHeadSourceDisplay.NotRecorded -> TaskHeadSource.NotRecorded
                is TaskHeadSourceDisplay.Message ->
                    TaskHeadSource(
                        authorName =
                            author?.let { members.taskHeadDisplayName(it.kind, it.id.value) ?: it.displayName.trim().takeIf(String::isNotBlank) },
                        authorKind = author?.kind,
                        authorAvatarWireValue = author?.avatarWireValueForRenderedAvatar(),
                        preview = sourcePreview?.firstLine,
                        time = sourcePreview?.createdAt?.let { formatRaftTimestampLabel(it, timeContext).takeIf(String::isNotBlank) },
                        authorId = author?.id?.value,
                    )
                null -> null
            },
        createdBy = creatorName,
        creator =
            creator?.let { member ->
                member.id.value.trim().takeIf { it.isNotEmpty() }?.let { id ->
                    val channelMember = members.firstOrNull { it.kind == member.kind && it.id.value == member.id.value }
                    TaskHeadPerson(
                        member.kind,
                        id,
                        channelMember?.avatar?.let { channelMember.avatarWireValueForRenderedAvatar() }
                            ?: member.avatarWireValueForRenderedAvatar(),
                    )
                }
            },
        avatarServerId = serverId?.trim()?.takeIf { it.isNotEmpty() },
        createdAt = taskHeadDateTime(createdAt, timeContext),
        earlierDiscussion = earlier,
        history = history,
        assignee = assigneeHead,
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
 * Where the head of a phone's task conversation is (web `mobile-modal`, artin 2026-11-06,
 * #proj-mobile:ec53b56a): the conversation's first row, above where it begins ("Beginning of
 * conversation", or the older messages still to load), so it moves with the conversation like
 * any message: opening a short conversation shows it above the messages, a long one opens at the
 * newest message with it out of view above. Nothing holds it or slides it on its own.
 *
 * While the row is out of view ([headInView] false, from the list's layout:
 * [taskHeadRowInView]) the top bar names the task's state ([collapsed]) and pulls the same head
 * down over the messages ([floatOpen], web `TaskHeadPullDown`, slock #11722): [toggleFloat] on the
 * bar. It closes on the bar again, a tap outside it, a swipe up on it, Back / Escape, focus in the
 * composer or the keyboard coming up ([closeFloat]; not for the search of a picker hanging from
 * it, [floatPickerOpen]), as soon as the reader scrolls the messages
 * ([connection]) and when the row comes back in view. It never opens by itself.
 *
 * Every page lays the head out this way, a tablet's and a desktop window's too (artin 2026-11-19:
 * pinned above the conversation, the head took most of a landscape tablet's height).
 */
@Stable
internal class TaskHeadScrollState {
    /**
     * The title / description edit (slock #14114), kept with the page rather than the head's row, so
     * it outlives the row leaving the message list while the keyboard is up.
     */
    internal val edit = TaskHeadEditState()

    /** Delete task's confirmation is open (the ⋯ menu's Delete task, slock #14115). */
    internal var deleteConfirming by mutableStateOf(false)

    /**
     * A picker hangs from the pulled-down head (a wide window's assignee popup, in place): the
     * keyboard its search brings up leaves the head down, or the popup would go with it.
     */
    internal var floatPickerOpen by mutableStateOf(false)

    /**
     * Some of the head's row is on screen (web: the row intersects the timeline). True until the
     * list says otherwise, and again once the row is no longer in a list (web unmount).
     */
    var headInView by mutableStateOf(true)
        private set

    /** The height of the head in its row, in px (the rest of the row is where the conversation begins). */
    internal var rowHeadHeightPx by mutableIntStateOf(0)

    /** The head's row is out of view: the top bar summarizes the task. */
    val collapsed: Boolean
        get() = !headInView

    private var floatRequested by mutableStateOf(false)

    /** The bottom edge of the pulled-down head in root coordinates (a press below it is outside). */
    internal var floatBottomInRootPx: Float = 0f

    /** The head pulled down from the top bar over the messages: only while its row is out of view. */
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

    /** The head's row came into view or went out of it. Back in view, the head needs no panel (web). */
    internal fun updateHeadInView(inView: Boolean) {
        if (headInView != inView) headInView = inView
        if (inView) floatRequested = false
    }

    /** On an ancestor of the messages: the reader starts scrolling them, so the pulled-down head goes back up (web). */
    val connection: NestedScrollConnection =
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) floatRequested = false
                return Offset.Zero
            }
        }
}

/**
 * Whether any of the head is on screen (web: `IntersectionObserver`, any part of the row): the head
 * sits at the top of its row, [rowOffsetPx] from the viewport's start in forward (top-down) terms
 * ([ThreadListViewport.layoutInfo]), [headHeightPx] tall (unmeasured: the whole row, [rowSizePx]).
 * Null [rowOffsetPx]: the row is not laid out (scrolled far away), so out of view.
 */
internal fun taskHeadRowInView(
    rowOffsetPx: Int?,
    rowSizePx: Int,
    headHeightPx: Int,
    viewportStartPx: Int,
    viewportEndPx: Int,
): Boolean {
    if (rowOffsetPx == null) return false
    val headHeight = if (headHeightPx > 0) headHeightPx else rowSizePx
    if (headHeight <= 0) return false
    return rowOffsetPx + headHeight > viewportStartPx && rowOffsetPx < viewportEndPx
}

/**
 * Task v2 on a phone: the head as the conversation's first row, for the message list to draw
 * ([LocalTaskConversationHeadRow]). [Content] is the page's head ([TaskHeadPlacement.FirstRow]),
 * kept current without changing this holder, so the list does not recompose for it.
 */
@Stable
internal class TaskConversationHeadRow(
    val scrollState: TaskHeadScrollState,
    private val content: State<@Composable () -> Unit>,
) {
    @Composable
    fun Content() {
        content.value()
    }
}

@Composable
internal fun rememberTaskConversationHeadRow(scrollState: TaskHeadScrollState, content: @Composable () -> Unit): TaskConversationHeadRow {
    val current = rememberUpdatedState(content)
    return remember(scrollState, current) { TaskConversationHeadRow(scrollState, current) }
}

/** The task head the conversation's message list draws as its first row (null: not a task conversation). */
internal val LocalTaskConversationHeadRow = compositionLocalOf<TaskConversationHeadRow?> { null }

/**
 * The head in its row: measured, so the list can tell when it is out of view ([taskHeadRowInView]).
 * The row ends where the conversation begins: no rule under it (web: the first message follows it
 * as the messages follow one another, Kirby).
 */
@Composable
internal fun TaskConversationHeadRowContent(row: TaskConversationHeadRow, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().onSizeChanged { row.scrollState.rowHeadHeightPx = it.height }) {
        row.Content()
    }
}

/**
 * A task conversation with no messages to list yet (loading, or empty): the head is still its
 * first row, above [state] (web: the parent slot over the replies' placeholder). In view for as
 * long as it is drawn here. Without a [row], [state] alone.
 */
@Composable
internal fun TaskConversationHeadAboveState(row: TaskConversationHeadRow?, state: @Composable () -> Unit) {
    if (row == null) {
        state()
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        SideEffect { row.scrollState.updateHeadInView(true) }
        val pageHeight = maxHeight
        // At least the page's height, so the state fills what the head leaves; a taller head scrolls.
        val fill = if (pageHeight.value.isFinite()) Modifier.heightIn(min = pageHeight) else Modifier
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).then(fill)) {
            TaskConversationHeadRowContent(row)
            Box(Modifier.fillMaxWidth().weight(1f)) { state() }
        }
    }
}

/** Where [TaskConversationHeadRoute] draws the head. */
internal enum class TaskHeadPlacement {
    /**
     * The page's own placement: it takes no room above the conversation (the head is never pinned
     * there) and holds the page's effects ([TaskConversationHeadPageEffects], Delete task's confirmation).
     */
    Page,

    /** The conversation's first row ([TaskConversationHeadRowContent]). */
    FirstRow,

    /** Pulled down from the top bar over the messages ([TaskHeadFloat]). */
    PullDown,
}

/**
 * Task v2: the page's side of a task conversation's head ([TaskHeadPlacement.Page]). The head
 * itself (web `TaskModalHead`: the title and description, the inline facts, History) is never
 * pinned above the conversation, at any width or height (artin 2026-11-19): it is the
 * conversation's first row ([TaskConversationHeadRowContent]) and is pulled down from the top bar
 * while that row is out of view ([TaskHeadFloat], [TaskHeadScrollState]). This takes no room; the
 * keyboard coming up ([keyboardUp]) puts the pulled-down head back up.
 */
@Composable
internal fun TaskConversationHeadPageEffects(scrollState: TaskHeadScrollState, keyboardUp: Boolean) {
    LaunchedEffect(scrollState, keyboardUp) {
        // Typing: the composer has focus, so the pulled-down head goes back up; not for the search
        // of a picker that hangs from the head ([TaskHeadScrollState.floatPickerOpen]).
        if (keyboardUp && !scrollState.floatPickerOpen) scrollState.closeFloat()
    }
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
    onOpenCreator: ((TaskHeadPerson) -> Unit)? = null,
    initiallyHistoryOpen: Boolean = false,
    initiallyDescriptionExpanded: Boolean = false,
    /** Null: no Retry after a failed History read. */
    onRetryHistory: (() -> Unit)? = null,
    /** Null: the title and description are plain text (no store to write to, or a read-only task). */
    editing: TaskHeadEditing? = null,
    /** Composed in the assignee chip's box: the assignee picker hanging from it (a wide window's popup). */
    assigneePicker: @Composable () -> Unit = {},
    /** Resolves an agent assignee's live status dot beside its face (null: no dot). */
    agentStatusResolver: RaftAgentStatusResolver? = null,
    /** Opens the assignee's profile when tapping their avatar (null: avatar is non-interactive). */
    onOpenAssigneeProfile: ((TaskHeadPerson) -> Unit)? = null,
) {
    val editingField = editing?.state?.field
    val buttons =
        taskHeadEditButtons(
            canEdit = editing != null,
            descriptionLoaded = editing?.descriptionEditable == true,
            hasDescription = model.description != null,
            editingField = editingField,
        )
    Column {
        if (editing != null && editingField == TaskHeadEditField.Title) {
            TaskHeadFieldEditor(editing)
        } else {
            TaskHeadTitleRow(model, editing, buttons)
        }
        Column {
            // No description: nothing here (Add a description is beside the title's pencil).
            when {
                editing != null && editingField == TaskHeadEditField.Description -> TaskHeadFieldEditor(editing)
                model.description != null ->
                    TaskHeadDescription(
                        model.description,
                        initiallyDescriptionExpanded,
                        editing?.takeIf { buttons.editDescription },
                    )
            }
            TaskHeadFacts(
                model = model,
                onSelectStatus = onSelectStatus,
                onOpenAssignee = onOpenAssignee,
                onOpenSource = onOpenSource,
                onOpenEarlierDiscussion = onOpenEarlierDiscussion,
                onOpenAssigneeProfile = onOpenAssigneeProfile,
                agentStatusResolver = agentStatusResolver,
                assigneePicker = assigneePicker,
            )
            TaskHeadHistorySection(model.history, initiallyHistoryOpen, onRetryHistory)
        }
    }
}

@Composable
private fun TaskHeadTitleRow(model: TaskConversationHeadModel, editing: TaskHeadEditing?, buttons: TaskHeadEditButtons) {
    // Follow / Unfollow Task is the header More menu's, as on web (ThreadOverflowMenu).
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        RaftText(
            model.title,
            Modifier.weight(1f).semantics { heading() },
            style = RaftTheme.typography.headline.copy(fontWeight = FontWeight.SemiBold),
            color = RaftTheme.colors.foreground,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (editing != null) {
            // The pencil beside the title (web: shown on hover or focus, always by touch).
            if (buttons.editTitle) {
                TaskHeadEditButton(stringResource(Res.string.task_amend_edit_title), TaskHeadTitleEditTestTag) {
                    editing.state.open(editing.task, TaskHeadEditField.Title)
                }
            }
            // No description: Add a description beside it, shown as it is (web slock #15639).
            if (buttons.addDescription) {
                TaskHeadAddDescriptionButton { editing.state.open(editing.task, TaskHeadEditField.Description) }
            }
        }
    }
}

@Composable
private fun TaskHeadDescription(description: String, initiallyExpanded: Boolean, editing: TaskHeadEditing? = null) {
    var expanded by remember(description) { mutableStateOf(initiallyExpanded) }
    var collapsible by remember(description) { mutableStateOf(false) }
    // With a toggle, its 44dp target is the gap to the facts; without one, 12dp.
    Column(Modifier.padding(bottom = if (collapsible) 0.dp else 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RaftText(
                description,
                Modifier.weight(1f),
                style = RaftTheme.typography.subhead,
                color = RaftTheme.colors.foregroundMuted,
                maxLines = if (expanded) Int.MAX_VALUE else TaskHeadDescriptionCollapsedLines,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { layout ->
                    if (!expanded && layout.hasVisualOverflow) collapsible = true
                },
            )
            if (editing != null) {
                TaskHeadEditButton(stringResource(Res.string.task_amend_edit_description), TaskHeadDescriptionEditTestTag) {
                    editing.state.open(editing.task, TaskHeadEditField.Description)
                }
            }
        }
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
    onOpenAssigneeProfile: ((TaskHeadPerson) -> Unit)?,
    agentStatusResolver: RaftAgentStatusResolver?,
    assigneePicker: @Composable () -> Unit,
) {
    val bar = model.bar
    val statusLabel = bar.status.localizedLabel()
    val statusFieldLabel = stringResource(Res.string.tasks_label_status)
    val unknown = stringResource(Res.string.task_head_unknown)
    var statusMenuOpen by remember { mutableStateOf(false) }
    RaftDescriptionList(direction = RaftDescriptionListDirection.Inline) {
        Item(stringResource(Res.string.task_head_status)) {
            val anchor = LocalTaskHeadStatusMenuAnchor.current
            Box(TaskHeadFactValueModifier.then(if (anchor != null) Modifier.onGloballyPositioned(anchor) else Modifier), contentAlignment = Alignment.CenterStart) {
                // Web InlineBadgeEditor: the chip opens the status menu; a status the reader may not
                // change is the plain badge, with no chevron.
                RaftTaskStatusBadge(
                    status = bar.status.raftTaskStatus(),
                    text = statusLabel,
                    // Web `-ml-[7px]`: hangs leading padding outward and reduces layout width so Status + Assignee stay on one row.
                    modifier = Modifier.hangStart(TaskHeadChipHang).testTag(TaskHeadStatusTestTag),
                    onClick = if (bar.statusEditable) ({ statusMenuOpen = true }) else null,
                    enabled = !bar.statusUpdating,
                    contentDescription = "$statusFieldLabel: $statusLabel",
                )
                RaftDropdownMenu(
                    expanded = statusMenuOpen && bar.statusEditable,
                    onDismissRequest = { statusMenuOpen = false },
                    minWidth = TaskHeadStatusMenuMinWidth,
                ) {
                    TaskHeadStatusMenuRows(bar) { status ->
                        statusMenuOpen = false
                        if (status != bar.status) onSelectStatus(status)
                    }
                }
            }
        }
        Item(stringResource(Res.string.task_head_assignee)) {
            TaskHeadFactValue {
                TaskHeadAssigneeValue(
                    assignee = model.assignee,
                    fallbackName = bar.assigneeName,
                    serverId = model.avatarServerId,
                    editable = bar.assigneeEditable,
                    agentStatusResolver = agentStatusResolver,
                    onOpenAssignee = onOpenAssignee,
                    onOpenAssigneeProfile = onOpenAssigneeProfile,
                )
                // In the assignee's box, as the status menu is in its own: a popup in place anchors to it.
                if (bar.assigneeEditable) assigneePicker()
            }
        }
        model.source?.let { source ->
            Item(stringResource(Res.string.task_head_source)) {
                TaskHeadFactValue { TaskHeadSourceValue(source, model.avatarServerId, onOpenSource) }
            }
        }
        // When it was created rides on Created by, not a row of its own (web slock #15639).
        val timeFormat = currentRaftEffectiveDateTimeContext().timeFormat
        val createdAt = model.createdAt?.let { stringResource(Res.string.task_head_created_at_value, it.localized(timeFormat)) }
        Item(stringResource(Res.string.task_head_created_by)) {
            TaskHeadFactValue { TaskHeadCreatedByValue(model.createdBy ?: unknown, createdAt) }
        }
        // Where it cannot open (the pending task page) it is a plain count, and hidden while unknown.
        model.earlierDiscussion?.takeIf { onOpenEarlierDiscussion != null || it.replyCount != null }?.let { earlier ->
            Item(stringResource(Res.string.task_head_earlier_discussion)) {
                val label =
                    when (val count = earlier.replyCount) {
                        null -> stringResource(Res.string.task_head_earlier_discussion_view)
                        1 -> stringResource(Res.string.task_head_earlier_discussion_replies_one)
                        else -> stringResource(Res.string.task_head_earlier_discussion_replies_many, count)
                    }
                TaskHeadFactValue {
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
}

/**
 * Web `-ml-[7px]` / `-ml-1.5` on shaped inline fact controls: pulls the control's leading padding
 * outward and shrinks its measured width by [hang] so the next fact on the same wrapping line (e.g.
 * Assignee beside Status on a 360dp phone) reclaims that horizontal space.
 *
 * Does NOT query `placeable[FirstBaseline]` during `measure` (doing so inside a `LazyColumn`
 * forces pre-placement of reused children and crashes Compose 1.12's `RectManager`, see
 * `RaftDescriptionListReuseTest`): omitting `alignmentLines` lets Compose's
 * `LayoutModifierNodeCoordinator` delegate `FirstBaseline` reads safely after placement.
 */
private fun Modifier.hangStart(hang: Dp): Modifier =
    layout { measurable, constraints ->
        val hangPx = hang.roundToPx().coerceAtLeast(0)
        val childMaxWidth =
            if (constraints.hasBoundedWidth) {
                (constraints.maxWidth.toLong() + hangPx.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            } else {
                Constraints.Infinity
            }
        val childMinWidth =
            if (constraints.minWidth > 0) {
                (constraints.minWidth.toLong() + hangPx.toLong())
                    .coerceAtMost(if (childMaxWidth == Constraints.Infinity) Int.MAX_VALUE.toLong() else childMaxWidth.toLong())
                    .toInt()
            } else {
                0
            }
        val placeable = measurable.measure(constraints.copy(minWidth = childMinWidth, maxWidth = childMaxWidth))
        val width = (placeable.width - hangPx).coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, placeable.height) {
            placeable.placeRelative(x = -hangPx, y = 0)
        }
    }

/**
 * Assignee in the head's inline facts (web `TaskProperties` with `assigneeAsPerson = true` and
 * `bg-transparent text-foreground`): shows the assignee's avatar (`19dp`) + display name (or
 * semibold `"You"` when assigned to the signed-in reader) + chevron when editable, rather than a
 * tinted pill chip.
 */
@Composable
private fun TaskHeadAssigneeValue(
    assignee: TaskHeadAssignee?,
    fallbackName: String?,
    serverId: String?,
    editable: Boolean,
    agentStatusResolver: RaftAgentStatusResolver?,
    onOpenAssignee: () -> Unit,
    onOpenAssigneeProfile: ((TaskHeadPerson) -> Unit)?,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val unassigned = stringResource(Res.string.tasks_assignee_unassigned)
    val shape = RoundedCornerShape(RaftRadius.Control)
    val triggerSource = remember { MutableInteractionSource() }
    val avatarSource = remember { MutableInteractionSource() }
    if (assignee == null) {
        val text = fallbackName ?: unassigned
        if (!editable) {
            RaftDescriptionValue(text, copyable = false, maxLines = 1)
            return
        }
        Row(
            Modifier
                .hangStart(6.dp)
                .raftTouchTarget(enabled = true, interactionSource = triggerSource, onClick = onOpenAssignee)
                .clip(shape)
                .raftClickable(onClick = onOpenAssignee, shape = shape, interactionSource = triggerSource)
                .padding(horizontal = 6.dp, vertical = 1.dp)
                .widthIn(max = 200.dp)
                .testTag(TaskHeadAssigneeTestTag),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftText(
                text = text,
                modifier = Modifier.weight(1f, fill = false),
                style = typography.callout,
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RaftGlyphIcon(RaftGlyph.ChevronDown, size = 12.dp, color = colors.foregroundMuted)
        }
        return
    }
    val jointSurface = assignee.isJointChannel || LocalRaftJointActorSurface.current
    val avatarResolver = rememberRaftMemberAvatarResolver(serverId)
    val jointAvatarResolver = remember(avatarResolver, jointSurface) { avatarResolver.forJointSurface(jointSurface) }
    val jointActor =
        rememberRaftJointActor(
            memberAvatarResolver = jointAvatarResolver,
            kind = assignee.kind,
            memberId = assignee.id,
            enabled = jointSurface && !assignee.isViewer,
        )
    val viewerLabel = stringResource(Res.string.thread_author_you)
    val label =
        threadMessageSourceTaskAssigneeName(
            assigneeIsViewer = assignee.isViewer,
            viewerLabel = viewerLabel,
            assigneeName = assignee.displayName,
            jointActor = jointActor,
        )
    // Web TaskProperties: profile button accessibility label always uses the person's display name,
    // never "Open You's profile".
    val profileName =
        threadMessageSourceTaskAssigneeName(
            assigneeIsViewer = false,
            viewerLabel = "",
            assigneeName = assignee.displayName,
            jointActor = jointActor,
        )
    val agentStatus =
        agentStatusResolver?.let { resolver ->
            rememberRaftAgentStatusIndicatorState(
                agentStatusResolver = resolver,
                memberId = if (assignee.kind == MemberKind.Agent) assignee.id else null,
            )
        }
    val nameStyle = if (assignee.isViewer) typography.callout.copy(fontWeight = FontWeight.SemiBold) else typography.callout
    val openProfile = onOpenAssigneeProfile?.let { open -> { open(TaskHeadPerson(assignee.kind, assignee.id, assignee.avatarWireValue)) } }
    val openProfileLabel = stringResource(Res.string.task_head_open_assignee_profile, profileName)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val avatarBoxModifier =
            if (openProfile != null) {
                Modifier
                    .raftTouchTarget(enabled = true, interactionSource = avatarSource, onClick = openProfile)
                    .raftClickable(onClick = openProfile, shape = shape, interactionSource = avatarSource)
                    .semantics(mergeDescendants = true) { contentDescription = openProfileLabel }
                    .testTag(TaskHeadAssigneeAvatarTestTag)
            } else {
                Modifier.testTag(TaskHeadAssigneeAvatarTestTag)
            }
        Box(avatarBoxModifier, contentAlignment = Alignment.Center) {
            val face: @Composable () -> Unit = {
                RaftMemberAvatar(
                    identity = RaftMemberAvatarIdentity(assignee.kind, assignee.id, serverId),
                    label = label,
                    avatarSize = TaskHeadFactAvatarSize,
                    carriedAvatarWireValue = assignee.avatarWireValue,
                    resolver = jointAvatarResolver,
                )
            }
            if (agentStatus != null) {
                RaftAvatarWithStatus(
                    statusColor = agentStatus.color,
                    avatarSize = TaskHeadFactAvatarSize,
                    cutoutColor = colors.layerCanvas,
                ) { face() }
            } else {
                face()
            }
        }
        if (editable) {
            Row(
                Modifier
                    .raftTouchTarget(enabled = true, interactionSource = triggerSource, onClick = onOpenAssignee, expandStart = false)
                    .clip(shape)
                    .raftClickable(onClick = onOpenAssignee, shape = shape, interactionSource = triggerSource)
                    .padding(vertical = 1.dp)
                    .widthIn(max = 180.dp)
                    .testTag(TaskHeadAssigneeTestTag),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RaftText(
                    text = label,
                    modifier = Modifier.weight(1f, fill = false),
                    style = nameStyle,
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                RaftGlyphIcon(RaftGlyph.ChevronDown, size = 12.dp, color = colors.foregroundMuted)
            }
        } else {
            RaftText(
                text = label,
                style = nameStyle,
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Every fact's value has one height, its content centred in it: the status and assignee chips
 * (22dp), a fact that leads somewhere ([TaskHeadLinkValue], its text line and 1dp above and
 * below), a face and name, a plain value. Facts of different kinds on one line of the wrapping
 * row then stand at one height with their labels on one line (artin 2026-11-19: Source and
 * Earlier discussion sat lower than the facts beside them; a 48dp touch box made them taller).
 */
private val TaskHeadFactValueHeight = 24.dp

private val TaskHeadFactValueModifier = Modifier.heightIn(min = TaskHeadFactValueHeight)

/** A fact's value in its [TaskHeadFactValueHeight] box. */
@Composable
private fun TaskHeadFactValue(content: @Composable () -> Unit) {
    Box(TaskHeadFactValueModifier, contentAlignment = Alignment.CenterStart) { content() }
}

internal const val TaskHeadStatusTestTag = "task-head-status"
internal const val TaskHeadAssigneeTestTag = "task-head-assignee"
internal const val TaskHeadAssigneeAvatarTestTag = "task-head-assignee-avatar"
internal const val TaskBarSummaryTestTag = "task-bar-summary"
internal val TaskHeadStatusMenuMinWidth = 200.dp

/** Screenshots only: the status menu's anchor (the chip's box), so a capture can draw the popup's surface in place. */
internal val LocalTaskHeadStatusMenuAnchor = staticCompositionLocalOf<((LayoutCoordinates) -> Unit)?> { null }

/**
 * The status menu's rows (web InlineBadgeEditor's options): every status, the current one checked,
 * each with its status pixel; Closed -> Todo reads "Reopen to Todo". [onPick] gets the row's status.
 */
@Composable
internal fun TaskHeadStatusMenuRows(bar: TaskConversationHeaderBarModel, onPick: (TaskStatus) -> Unit) {
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
            onClick = { onPick(option.status) },
        )
    }
}

/**
 * A fact that leads somewhere (web: a `rounded-control` button whose padding hangs outward, its
 * text on the facts' baseline, an arrow at the end): hover and press through [raftClickable].
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
    Row(
        Modifier
            // No 48dp box: it would make this fact taller than the others on its line, so it sat
            // lower than them. The row keeps its size; the platform widens a small target's touch area.
            // The padding hangs outward and shrinks layout width: the text starts where a plain fact's does.
            .hangStart(6.dp)
            .clip(shape)
            .then(
                if (onClick != null) {
                    Modifier.raftClickable(onClick = onClick, shape = shape, interactionSource = source)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 6.dp, vertical = 1.dp)
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
private fun TaskHeadSourceValue(source: TaskHeadSource, serverId: String?, onOpenSource: (() -> Unit)?) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    if (source.notRecorded) {
        RaftText(
            stringResource(Res.string.task_head_source_not_recorded),
            style = typography.callout,
            color = colors.foregroundMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    TaskHeadLinkValue(contentDescription = stringResource(Res.string.task_head_source_open), onClick = onOpenSource) {
        if (source.preview != null) {
            RaftMemberAvatar(
                identity = RaftMemberAvatarIdentity(source.authorKind, source.authorId, serverId),
                label = source.authorName.orEmpty(),
                avatarSize = TaskHeadFactAvatarSize,
                modifier = Modifier.testTag(TaskHeadSourceFaceTestTag),
                carriedAvatarWireValue = source.authorAvatarWireValue,
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

/** The faces in the facts (Assignee, Source): web `AvatarSlot` `compact-list` (`19px`). */
private val TaskHeadFactAvatarSize = RaftAvatarSize.Xs.edge

/** Tests only: the Source face. */
internal const val TaskHeadSourceFaceTestTag = "task-head-source-face"
internal const val TaskHeadCreatorFaceTestTag = "task-head-creator-face"

/**
 * Created by: plain text name, matching web `TaskProperties.tsx` (no avatar, no link button).
 *
 * [createdAt] ("Created at Oct 28, 2026, 09:14"; null: not known) rides on it, as on the web (slock
 * #15639): its tooltip under a mouse or trackpad pointer, and read after the name by screen readers
 * (web: an sr-only time in the name; here its state description, on the one node they stop at).
 */
@Composable
private fun TaskHeadCreatedByValue(
    name: String,
    createdAt: String?,
) {
    // Screen readers: the time after the name, on the node they stop at.
    val time = if (createdAt != null) Modifier.semantics(mergeDescendants = true) { stateDescription = createdAt } else Modifier
    TaskHeadCreatedAtTooltip(createdAt) {
        RaftDescriptionValue(name, time, copyable = false, maxLines = 1)
    }
}

/**
 * [content] with [createdAt] as its tooltip, under a mouse or trackpad pointer only: a long press
 * stays the platform's, as it is on the rest of the facts. Null [createdAt]: [content] alone.
 */
@Composable
private fun TaskHeadCreatedAtTooltip(createdAt: String?, content: @Composable () -> Unit) {
    if (createdAt != null) {
        RaftTooltip(text = createdAt, openOnLongPress = false, content = content)
    } else {
        content()
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
                    is TaskHeadHistory.Failed ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                            RaftTraceIdNote(history.traceId)
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
            // raw-click: a 48dp target round a small text label; a press overlay would fill the whole
            // target past the label.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                if (expanded) collapse { onToggle(); true } else expand { onToggle(); true }
            },
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}

/** raft-ui inline facts: a leading chip hangs 7dp outward via [hangStart]. */
private val TaskHeadChipHang = 7.dp

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
                        {
                            TaskHeadTransition(detail.from, detail.to)
                            // What would bring it back, under the move (web TimelineDetail).
                            detail.reason?.let { reason -> RaftTimelineDetail(reason) }
                        }
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
    statusUpdating: Boolean,
    assigneeCandidates: List<MemberProfile>,
    timeContext: RaftTimeContext?,
    onUpdateStatus: (TaskStatus) -> Unit,
    onAssign: (MemberProfile?) -> Unit,
    /** Null (also [onOpenEarlierDiscussion]): a plain fact (the pending task page). */
    onOpenSource: (() -> Unit)?,
    onOpenEarlierDiscussion: (() -> Unit)?,
    /** Null: the creator is a plain fact. */
    onOpenCreator: ((TaskHeadPerson) -> Unit)? = null,
    scrollState: TaskHeadScrollState,
    /** The keyboard is up: the pulled-down head goes back up. */
    keyboardUp: Boolean = false,
    onRetryHistory: (() -> Unit)? = null,
    /** False: status and assignee are plain facts ([taskConversationHeadModel]). */
    editable: Boolean = true,
    /** The page's effects (no room above the conversation), its first row, or pulled down from the bar. */
    placement: TaskHeadPlacement = TaskHeadPlacement.Page,
    /** The task's server: the head's faces read its member directory. */
    serverId: String? = null,
    /**
     * A move into Backlog, after its reason was asked (web TaskBacklogReasonPopover): the reason, null
     * for none. Null: Backlog goes through [onUpdateStatus] without asking.
     */
    onMoveToBacklog: ((reason: String?) -> Unit)? = null,
    /**
     * Saves an edit of the title or description (`PATCH /tasks/:id` through the task channel's
     * store, [build.raft.app.data.SharedTasksViewModel.amendTask]). Null: they are plain text.
     */
    onAmend: (suspend (TaskAmendPatch, Long?) -> TaskAmendResult)? = null,
    /** Deletes the task once confirmed: null when deleted, else the refusal. Null: no Delete task. */
    onDelete: (suspend () -> build.raft.app.data.ScreenError?)? = null,
    /**
     * False: the reader may not write the task's channel (not joined, archived;
     * [build.raft.app.data.canWriteTasksInChannel]): status, assignee, title and description are plain.
     */
    canWriteChannel: Boolean = true,
    /** Signed-in user's id so self-assigned tasks display "You". */
    viewerUserId: String? = null,
    /** Resolves an agent assignee's live status dot beside its face (null: no dot). */
    agentStatusResolver: RaftAgentStatusResolver? = null,
    /** Opens the assignee's profile when tapping their avatar (null: avatar is non-interactive). */
    onOpenAssigneeProfile: ((TaskHeadPerson) -> Unit)? = null,
) {
    var assigneeSheetOpen by remember(task.id) { mutableStateOf(false) }
    var backlogReasonOpen by remember(task.id) { mutableStateOf(false) }
    val onSelectStatus: (TaskStatus) -> Unit = { status ->
        if (onMoveToBacklog != null && taskStatusPickAsksBacklogReason(task.status, status)) {
            backlogReasonOpen = true
        } else {
            onUpdateStatus(status)
        }
    }
    var assigneeQuery by remember(task.id) { mutableStateOf("") }
    val effectiveTimeContext = currentRaftDateTimeContext(timeContext)
    val model =
        task.taskConversationHeadModel(
            state = state,
            details = details,
            statusUpdating = statusUpdating,
            members = assigneeCandidates,
            timeContext = effectiveTimeContext,
            editable = editable,
            serverId = serverId,
            canWriteChannel = canWriteChannel,
            viewerUserId = viewerUserId,
        )
    // The pulled-down head closes when the keyboard comes up, so the text is edited in the head's
    // row, not there.
    val editing =
        onAmend?.takeIf { editable && task.canAmendText(canWriteChannel) && placement != TaskHeadPlacement.PullDown }?.let { send ->
            TaskHeadEditing(scrollState.edit, task, send)
        }
    // Another task in this page's head: an edit of the last one does not carry over (web keys it by task).
    LaunchedEffect(task.id) { if (scrollState.edit.field != null && scrollState.edit.taskId != task.id) scrollState.edit.cancel() }
    val onOpenAssignee = {
        if (model.bar.assigneeEditable) {
            assigneeQuery = ""
            assigneeSheetOpen = true
        }
    }
    RaftDesignSystemTheme {
        // On a wide window the picker opens in place, from the assignee chip of the head that was
        // tapped; on a phone it is the bottom sheet, held here rather than in the head, so it stays
        // when the keyboard of its search puts a pulled-down head back up.
        val assigneeInPlace = raftComboboxPickerInPlace()
        val assigneePicker: @Composable () -> Unit = {
            TaskConversationAssigneePicker(
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
        val assigneeOpen = assigneeSheetOpen && model.bar.assigneeEditable
        if (placement == TaskHeadPlacement.PullDown) {
            // A popup hanging from the pulled-down head keeps it down while the keyboard is up.
            val hanging = assigneeInPlace && assigneeOpen
            DisposableEffect(scrollState, hanging) {
                scrollState.floatPickerOpen = hanging
                onDispose { if (hanging) scrollState.floatPickerOpen = false }
            }
        }
        val content: @Composable () -> Unit = {
            TaskHeadContent(
                model = model,
                onSelectStatus = onSelectStatus,
                onOpenAssignee = onOpenAssignee,
                onOpenSource = onOpenSource,
                onOpenEarlierDiscussion = onOpenEarlierDiscussion,
                onOpenCreator = onOpenCreator,
                onRetryHistory = onRetryHistory,
                editing = editing,
                assigneePicker = { if (assigneeInPlace && assigneeOpen) assigneePicker() },
                agentStatusResolver = agentStatusResolver,
                onOpenAssigneeProfile = onOpenAssigneeProfile,
            )
        }
        when (placement) {
            TaskHeadPlacement.PullDown -> {
                val label = task.taskNumber?.let { stringResource(Res.string.tasks_detail_task_identifier, it) } ?: task.title
                TaskHeadFloat(scrollState = scrollState, label = label, content = content)
            }
            // The panel's frame without the panel: the head reads the same in the row as pulled down.
            TaskHeadPlacement.FirstRow -> TaskHeadFrame(content)
            TaskHeadPlacement.Page -> TaskConversationHeadPageEffects(scrollState, keyboardUp)
        }
        // A phone's sheet (in place on a wide window: composed in the head above).
        if (assigneeOpen && !assigneeInPlace) assigneePicker()
        // The page's own placement holds the confirmation (the ⋯ menu asks through [scrollState]).
        if (placement == TaskHeadPlacement.Page && scrollState.deleteConfirming && onDelete != null) {
            TaskDeleteConfirmDialog(
                taskNumber = task.taskNumber,
                onDismiss = { scrollState.deleteConfirming = false },
                onConfirm = { onDelete().also { if (it == null) scrollState.deleteConfirming = false } },
            )
        }
        // Held by the placement whose menu asked (each placement is its own route), beside the assignee sheet.
        if (backlogReasonOpen && onMoveToBacklog != null) {
            TaskBacklogReasonDialog(
                taskNumber = task.taskNumber,
                onDismiss = { backlogReasonOpen = false },
                onConfirm = { reason ->
                    backlogReasonOpen = false
                    // Someone else may have parked it meanwhile: then there is nothing left to write.
                    if (task.status != TaskStatus.Backlog) onMoveToBacklog(reason)
                },
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
    val local = epochMillis + offsetMinutes.toLong() * MillisPerMinute
    val days = floorDiv(local, MillisPerDay)
    val millisOfDay = floorMod(local, MillisPerDay)
    val date = civilDateFromEpochDay(days)
    return TaskHeadDateTime(
        year = date.year,
        month = date.month,
        day = date.day,
        hour = (millisOfDay / MillisPerHour).toInt(),
        minute = ((millisOfDay % MillisPerHour) / MillisPerMinute).toInt(),
    )
}

@Composable
internal fun TaskHeadDateTime.localized(timeFormat: String): String {
    val time = formatRaftClock(hour, minute, null, timeFormat)
    return stringResource(Res.string.task_head_medium_date_time, raftLocalizedShortMonth(month), day, year, time)
}

/**
 * The task conversation's top bar (web `TaskConversationBar`): the task's ref (`#channel:#7`, Space
 * Mono, muted) over "Task #7". On a phone, while the head's row in the conversation is out of view
 * ([summarized]), the second line is "Task #7", the status badge, the assignee's face and a chevron
 * (slock #11722): tapping the bar pulls the head down over the messages ([headFloatOpen]) and
 * tapping it again puts it back. The
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
    val assigneeId: String? = null,
    val assigneeIsViewer: Boolean = false,
    /** The task's server: the assignee's face reads its member directory. */
    val avatarServerId: String? = null,
    /** Task lives in a joint channel (`task.channelType == "joint"`): remote assignees fall back to actors/resolve. */
    val isJointChannel: Boolean = false,
    val summarized: Boolean,
    val headFloatOpen: Boolean = false,
    /** Null: not known yet (no Follow row). */
    val followed: Boolean?,
    val onToggleHeadFloat: () -> Unit,
    val onViewInChannel: () -> Unit,
    val onToggleFollow: () -> Unit,
    /** Delete task (the ⋯ menu's last row); null for a reader who may not delete it. */
    val onDeleteTask: (() -> Unit)? = null,
)

internal fun TaskSummary.taskConversationHeaderChrome(
    state: TaskConversationHeaderState,
    summarized: Boolean,
    headFloatOpen: Boolean,
    onToggleHeadFloat: () -> Unit,
    onViewInChannel: () -> Unit,
    onToggleFollow: (currentlyFollowed: Boolean) -> Unit,
    serverId: String? = null,
    onDeleteTask: (() -> Unit)? = null,
    viewerUserId: String? = null,
): TaskConversationHeaderChrome {
    val followed = state.followed(conversationFollowed)
    val normalizedViewerId = viewerUserId?.trim()?.takeIf { it.isNotEmpty() }
    return TaskConversationHeaderChrome(
        ref = taskNumber?.let { formatTaskConversationRef(it, channelName, parseConversationKindOrNull(channelType)) } ?: title,
        taskNumber = taskNumber,
        status = status,
        assigneeName = assignee?.displayName?.trim()?.takeIf { it.isNotBlank() },
        assigneeKind = assignee?.kind,
        assigneeAvatarWireValue = assignee?.avatarWireValueForRenderedAvatar(),
        assigneeId = assignee?.id?.value,
        assigneeIsViewer =
            assignee?.kind == MemberKind.Human &&
                normalizedViewerId != null &&
                assignee?.id?.value?.trim() == normalizedViewerId,
        avatarServerId = serverId?.trim()?.takeIf { it.isNotEmpty() },
        isJointChannel = parseConversationKindOrNull(channelType) == ConversationKind.Joint,
        summarized = summarized,
        headFloatOpen = headFloatOpen && summarized,
        followed = followed,
        onToggleHeadFloat = onToggleHeadFloat,
        onViewInChannel = onViewInChannel,
        onToggleFollow = { followed?.let(onToggleFollow) },
        onDeleteTask = onDeleteTask,
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
    // The whole bar is the toggle while the head is out of view (web: the summary button; here the bar's
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
                .raftClickable(onClick = onToggle, shape = RoundedCornerShape(RaftRadius.Control))
                .semantics(mergeDescendants = true) {
                    if (open) collapse { onToggle(); true } else expand { onToggle(); true }
                }
                .testTag(TaskBarSummaryTestTag)
        } else {
            Modifier
        }
    // The ref names the task's channel: its place goes there, to the task (artin, task #35).
    val refLink: @Composable (Modifier) -> Unit = { refModifier -> TaskConversationBarRef(chrome, refModifier) }
    if (RaftTheme.scale == RaftScale.Wide && !chrome.summarized) {
        // The wide layout: one line, as every head from 768 (raft-design desktop-density rule 2;
        // web taskConversationBarClasses BAR_NAME_CLASS): "Task #7", then the ref after it on its
        // baseline, the ref giving up its room first (`shrink-[1000]`).
        Row(modifier.then(toggle), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RaftText(
                label,
                modifier = Modifier.alignByBaseline(),
                style = typography.subhead.copy(fontWeight = FontWeight.Bold),
                color = colors.foreground,
                maxLines = 1,
            )
            if (refLine) {
                refLink(Modifier.weight(1f, fill = false).alignByBaseline())
            }
        }
        return
    }
    Column(modifier.then(toggle), verticalArrangement = Arrangement.Top) {
        if (refLine) {
            refLink(Modifier)
        }
        if (chrome.summarized) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                RaftText(label, style = typography.subhead.copy(fontWeight = FontWeight.Bold), color = colors.foreground, maxLines = 1)
                RaftTaskStatusBadge(chrome.status.raftTaskStatus(), chrome.status.localizedLabel(), size = RaftTaskStatusBadgeSize.Sm)
                // The face only (Kirby: a cut name says less than the face), named for screen readers.
                chrome.assigneeName?.let { name ->
                    val jointSurface = chrome.isJointChannel || LocalRaftJointActorSurface.current
                    val avatarResolver = rememberRaftMemberAvatarResolver(chrome.avatarServerId)
                    val jointAvatarResolver =
                        remember(avatarResolver, jointSurface) { avatarResolver.forJointSurface(jointSurface) }
                    val jointActor =
                        rememberRaftJointActor(
                            memberAvatarResolver = jointAvatarResolver,
                            kind = chrome.assigneeKind,
                            memberId = chrome.assigneeId,
                            enabled = jointSurface,
                        )
                    val viewerLabel = stringResource(Res.string.thread_author_you)
                    val assigneeLabel =
                        threadMessageSourceTaskAssigneeName(
                            assigneeIsViewer = chrome.assigneeIsViewer,
                            viewerLabel = viewerLabel,
                            assigneeName = name,
                            jointActor = jointActor,
                        )
                    RaftMemberAvatar(
                        identity = RaftMemberAvatarIdentity(chrome.assigneeKind, chrome.assigneeId, chrome.avatarServerId),
                        label = assigneeLabel,
                        modifier = Modifier.semantics { contentDescription = assigneeLabel },
                        avatarSize = RaftAvatarSize.Xs.edge,
                        carriedAvatarWireValue = chrome.assigneeAvatarWireValue,
                        resolver = jointAvatarResolver,
                    )
                }
                TaskBarChevron(open = chrome.headFloatOpen)
            }
        } else {
            RaftText(label, style = typography.subhead.copy(fontWeight = FontWeight.Bold), color = colors.foreground, maxLines = 1)
        }
    }
}

/**
 * The bar's ref (web TaskConversationBar, slock #16258): its place (`#channel`, `dm:@peer`) does
 * View in Channel as a reply thread head's channel name does ([ThreadHeaderViewInChannelLink]); the
 * number after it (`:#7`) stays text and gives up its room first. A ref with no place (a bare `#7`,
 * or the title of a task without a number) is text only.
 */
@Composable
private fun TaskConversationBarRef(chrome: TaskConversationHeaderChrome, modifier: Modifier) {
    val style = RaftTheme.typography.monoMeta
    val color = RaftTheme.colors.foregroundMuted
    val parts = chrome.taskNumber?.let { taskConversationRefParts(chrome.ref) }
    if (parts == null) {
        RaftText(chrome.ref, modifier = modifier, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    Row(modifier) {
        ThreadHeaderViewInChannelLink(label = parts.first, onViewInChannel = chrome.onViewInChannel, style = style)
        RaftText(
            parts.second,
            modifier = Modifier.weight(1f, fill = false),
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
 * on a phone, while the head's row in the conversation is out of view ([TaskHeadScrollState]). The
 * same title, description, facts and History as that row, editable the same way, without losing
 * the reader's place. Composed at the top of the box over the messages.
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
        TaskHeadFrame(content)
    }
}

/** The head's insets on a phone, in the conversation's first row and pulled down from the bar alike. */
@Composable
internal fun TaskHeadFrame(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().raftSafeAreaHorizontalPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
        content()
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
