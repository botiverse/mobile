package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutIdParentData
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.data.RaftSharedStoreRegistry
import build.raft.app.data.formatTaskConversationRef
import build.raft.app.data.tasksAssigneeDisplayName
import build.raft.app.localizedLabel
import build.raft.app.model.MemberKind
import build.raft.app.model.MillisPerDay
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.avatarWireValueForRenderedAvatar
import build.raft.app.model.parseRaftTimestampEpochMillisOrNull
import build.raft.app.resources.*
import build.raft.app.thread.localizedLabel
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.components.RaftCheckboxIndicator
import build.raft.designsystem.components.RaftGlyph
import build.raft.designsystem.components.RaftGlyphIcon
import build.raft.designsystem.components.RaftHoverTier
import build.raft.designsystem.components.RaftTaskCompactAssignee
import build.raft.designsystem.components.RaftTaskStatusBadge
import build.raft.designsystem.components.RaftTaskStatusPixel
import build.raft.designsystem.components.outlineColor
import build.raft.designsystem.components.raftClickable
import build.raft.designsystem.components.raftSlot
import build.raft.designsystem.components.softColor
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import kotlin.math.max
import kotlin.math.min

/** Web `@container/tasks` `@max-md/tasks` breakpoint (`28rem` = `448px`). */
internal val SharedTasksCompactNarrowBreakpoint: Dp = 448.dp

/** Web `--surface-inset` (`20px`) on containers at or above [SharedTasksCompactNarrowBreakpoint]. */
internal val SharedTasksWideSurfaceInset: Dp = 20.dp

/** Web `TASKS_PAGE_TOOLBAR_CLASS` / `VIEWPORT_CONTENT_CLASS`: 16dp narrow, 20dp wide. */
@Composable
internal fun sharedTasksSurfaceInset(availableWidth: Dp): Dp =
    if (availableWidth < SharedTasksCompactNarrowBreakpoint) RaftTheme.sizes.pageInset else SharedTasksWideSurfaceInset

/**
 * Compact task list surface variant (web `TaskChipList` `variant` / `compactRow.ts`):
 * - [Page]: grouped rounded `layerCard` box (`COMPACT_LIST_PAGE`) with 1dp hairline border and
 *   full-bleed hairlines between rows (Tasks page List view).
 * - [Plain]: edge-to-edge rows (`COMPACT_LIST_BLEED`) with inset hairlines and a trailing chevron
 *   (Channel Tasks side card).
 */
internal enum class SharedTasksCompactRowVariant {
    Page,
    Plain,
}

/** Test tags for compact Tasks list rows (parallel to web `TaskChipList` / `TasksListView` test ids). */
internal object SharedTasksCompactRowTags {
    const val Row = "tasks-compact-row"
    const val Number = "tasks-compact-row-number"
    const val Title = "tasks-compact-row-title"
    const val Meta = "tasks-compact-row-meta"
    const val Channel = "tasks-compact-row-channel"
    const val Assignee = "tasks-compact-row-assignee"
    const val Status = "tasks-compact-row-status"
    const val Time = "tasks-compact-row-time"
    const val Replies = "tasks-compact-row-replies"
}

/** Leading ref for a compact row: `#channel:#N` on wide cross-channel rows, `#N` on narrow or single-channel rows. */
internal fun TaskSummary.tasksCompactLeadingRef(
    showChannel: Boolean,
    narrow: Boolean,
): String {
    val number = taskNumber ?: return ""
    return if (showChannel && !narrow) {
        formatTaskConversationRef(number, channelName, parseConversationKindOrNull(channelType))
    } else {
        "#$number"
    }
}

/** Narrow cross-channel meta tag (`#channel`, web `TaskChipList` `@min-md/tasks:hidden`). */
internal fun TaskSummary.tasksCompactChannelTag(
    showChannel: Boolean,
    narrow: Boolean,
): String? {
    if (!showChannel || !narrow) return null
    val clean = channelName?.trim()?.removePrefix("#")?.takeIf { it.isNotBlank() } ?: return null
    return "#$clean"
}

/** Web `tasksPageModel.isStaleTask`: an open task with no activity for more than 3 days. */
internal fun TaskSummary.isTasksCompactStale(timeContext: RaftTimeContext?): Boolean {
    if (status != TaskStatus.Todo && status != TaskStatus.InProgress && status != TaskStatus.InReview) return false
    val now = timeContext?.nowEpochMillis ?: return false
    val activity = activityAt ?: updatedAtLabel
    val at = parseRaftTimestampEpochMillisOrNull(activity, timeContext.localOffsetMinutes) ?: return false
    return now - at > SharedTasksStaleAfterMillis
}

/**
 * Web `TaskChipList` compact task row (`variant="page"` on the Tasks page, `variant="plain"` in a
 * channel's Tasks side card).
 *
 * Replaces bulky cards with inline status/assignee dropdown buttons on Tasks list rows:
 * - On phones / narrow containers (`< 448dp`): `#N` + title (+ inline reply count) on Line 1, and
 *   a single concise trailing metadata line (`#channel`, assignee or `Unassigned`, icon-only soft
 *   status chip, short relative time) on Line 2 — fitting ~6+ tasks per phone screen like web.
 * - On wide containers (`>= 448dp`): `#channel:#N` + title + trailing group (assignee, full
 *   read-only status badge, short relative time) on one compact row.
 * - Whole row is the tap target to open the task conversation; long-press opens the anchored
 *   status menu when [onLongPress] is provided.
 *
 * Layout never reads `[FirstBaseline]` of composite children during measurement (avoiding Compose
 * 1.12 LazyColumn RectManager recycling crashes; see `RaftTaskCompactList.kt`).
 */
@Composable
internal fun SharedTasksCompactRow(
    task: TaskSummary,
    variant: SharedTasksCompactRowVariant,
    narrow: Boolean,
    firstInGroup: Boolean,
    lastInGroup: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showChannel: Boolean = true,
    showStatus: Boolean = true,
    showAssignee: Boolean = true,
    showUnassigned: Boolean = true,
    showTime: Boolean = true,
    hasDraft: Boolean = false,
    surfaceInset: Dp = SharedTasksCompactRowPageInset,
    timeContext: RaftTimeContext? = null,
    viewerId: String? = null,
    serverId: String? = null,
    captureLongPressAnchor: Boolean = true,
    onLongPress: ((RaftPopoverAnchor) -> Unit)? = null,
    selecting: Boolean = false,
    selected: Boolean = false,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val current = LocalSharedTasksOpenTaskId.current == task.id.value
    val highlighted = current || selected
    val rootAnchor =
        remember(task.id.value) { RaftLiveRootAnchorRef(identity = tasksStatusMenuOwnerIdentity(task.id.value)) }
    val rowInset = if (variant == SharedTasksCompactRowVariant.Page) SharedTasksCompactRowPageInset else surfaceInset
    val pageTopRadius = if (firstInGroup) RaftRadius.Container else 0.dp
    val pageBottomRadius = if (lastInGroup) RaftRadius.Container else 0.dp
    val rowShape =
        if (variant == SharedTasksCompactRowVariant.Page) {
            RoundedCornerShape(
                topStart = pageTopRadius,
                topEnd = pageTopRadius,
                bottomEnd = pageBottomRadius,
                bottomStart = pageBottomRadius,
            )
        } else {
            RectangleShape
        }
    val rowFill =
        when {
            highlighted -> colors.fillCellSelected
            variant == SharedTasksCompactRowVariant.Page -> colors.layerCard
            else -> Color.Transparent
        }
    val effectiveTimeContext = currentRaftTimeContext(timeContext)
    val activityTimestamp = task.activityAt ?: task.updatedAtLabel
    val timeLabel =
        if (showTime && activityTimestamp.isNotBlank()) {
            raftShortRelativeTimestampLabel(activityTimestamp, effectiveTimeContext).takeIf { it.isNotBlank() }
        } else {
            null
        }
    val stale = showTime && task.isTasksCompactStale(effectiveTimeContext)
    val leadingRef = task.tasksCompactLeadingRef(showChannel = showChannel, narrow = narrow)
    val channelTag = task.tasksCompactChannelTag(showChannel = showChannel, narrow = narrow)
    val replyCount = task.taskCardReplyCount() ?: 0
    val statusLabel = task.status.localizedLabel()
    val assigneeName = task.tasksAssigneeDisplayName()
    val assignee = task.assignee?.takeIf { showAssignee && assigneeName != null }
    val showUnassignedText = showAssignee && showUnassigned && assignee == null
    val iconOnlyStatus = showStatus && showChannel && narrow
    val showChevron = variant == SharedTasksCompactRowVariant.Plain
    val hasMeta = channelTag != null || assignee != null || showUnassignedText || showStatus || timeLabel != null || showChevron
    val avatarCutout = if (highlighted) colors.fillCellSelected else if (variant == SharedTasksCompactRowVariant.Page) colors.layerCard else colors.layerCanvas
    val density = LocalDensity.current
    val calloutLineHeightPx = with(density) { typography.callout.lineHeight.roundToPx() }
    val minHeightPx = with(density) { RaftTheme.sizes.controlHeight.roundToPx() }
    val longPressAction: (() -> Unit)? =
        onLongPress?.let { handler ->
            {
                rootAnchor
                    .resolve(RaftPopoverLocalPoint.BottomStart)
                    ?.let(handler)
            }
        }

    val rowModifier =
        Modifier
            .fillMaxWidth()
            .testTag(SharedTasksCompactRowTags.Row)
            .then(if (variant == SharedTasksCompactRowVariant.Page) Modifier.clip(rowShape) else Modifier)
            .background(rowFill, rowShape)
            .raftClickable(
                onClick = onClick,
                role = if (selecting) Role.Checkbox else Role.Button,
                shape = rowShape,
                tier = RaftHoverTier.Subtle,
                selected = highlighted,
                onLongClick = longPressAction,
            )
            .sharedTasksCompactRowBorders(
                variant = variant,
                firstInGroup = firstInGroup,
                lastInGroup = lastInGroup,
                hairline = colors.lineHairline,
                dividerInset = rowInset,
            )
            .padding(horizontal = rowInset)

    val wrapTitle = variant == SharedTasksCompactRowVariant.Page

    // Keep `selected` on an outer wrapper (matching SharedTaskCard) so `isSelected() and
    // hasAnyDescendant(hasText(title))` stays true even though `raftClickable` merges row children
    // into a single accessible button node.
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (highlighted) Modifier.semantics { this.selected = true } else Modifier)
                .then(if (captureLongPressAnchor && onLongPress != null) Modifier.raftCaptureLiveRootAnchor(rootAnchor) else Modifier)
                .raftSlot("task-compact-row"),
    ) {
        SharedTasksCompactRowLayout(
            modifier = rowModifier,
            narrow = narrow,
            wrapTitle = wrapTitle,
            hasCheck = selecting,
            hasLeadingRef = leadingRef.isNotEmpty(),
            hasMeta = hasMeta,
            minHeightPx = minHeightPx,
            calloutLineHeightPx = calloutLineHeightPx,
            leadingCheck = {
                if (selecting) {
                    RaftCheckboxIndicator(
                        checked = selected,
                        enabled = !task.isLegacy,
                        modifier = Modifier.testTag(SharedTasksBulkTags.RowCheckbox),
                    )
                }
            },
            leadingRef = {
                if (leadingRef.isNotEmpty()) {
                    RaftText(
                        text = leadingRef,
                        modifier = Modifier.testTag(SharedTasksCompactRowTags.Number),
                        style = typography.monoMeta,
                        color = colors.foregroundHint,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            },
            titleGroup = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    RaftText(
                        text = task.tasksMeaningfulDisplayTitle(),
                        modifier = Modifier.weight(1f, fill = false).testTag(SharedTasksCompactRowTags.Title),
                        style = typography.callout,
                        color = colors.foreground,
                        maxLines = if (wrapTitle) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (replyCount > 0) {
                        SharedTasksCompactReplyCount(
                            count = replyCount,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (hasDraft) {
                        RaftLucideIcon(
                            RaftLucideIconName.Pencil,
                            modifier = Modifier.padding(top = 3.dp).size(12.dp),
                            color = raftArgbHex(colors.foregroundHint),
                        )
                    }
                }
            },
            metaGroup = {
                if (hasMeta) {
                    SharedTasksCompactMetaRow(
                        modifier = Modifier.testTag(SharedTasksCompactRowTags.Meta),
                        channel =
                            channelTag?.let { tag ->
                                {
                                    RaftText(
                                        text = tag,
                                        modifier = Modifier.testTag(SharedTasksCompactRowTags.Channel),
                                        style = typography.monoMeta,
                                        color = colors.foregroundHint,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            },
                        assignee =
                            when {
                                assignee != null -> {
                                    {
                                        SharedTasksCompactAssignee(
                                            task = task,
                                            displayName = assigneeName.orEmpty(),
                                            viewerId = viewerId,
                                            serverId = serverId,
                                            cutoutColor = avatarCutout,
                                        )
                                    }
                                }
                                showUnassignedText -> {
                                    {
                                        RaftText(
                                            text = stringResource(Res.string.tasks_assignee_unassigned),
                                            modifier = Modifier.testTag(SharedTasksCompactRowTags.Assignee),
                                            style = typography.caption,
                                            color = colors.foregroundHint,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                else -> null
                            },
                        status =
                            if (showStatus) {
                                {
                                    if (iconOnlyStatus) {
                                        SharedTasksCompactStatusDotChip(
                                            status = task.status,
                                            label = statusLabel,
                                            modifier = Modifier.testTag(SharedTasksCompactRowTags.Status),
                                        )
                                    } else {
                                        RaftTaskStatusBadge(
                                            status = task.status.raftTaskStatus(),
                                            text = statusLabel,
                                            modifier = Modifier.testTag(SharedTasksCompactRowTags.Status),
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                        time =
                            timeLabel?.let { label ->
                                {
                                    val staleNote = if (stale) stringResource(Res.string.tasks_activity_stale_three_days) else null
                                    RaftText(
                                        text = label,
                                        modifier =
                                            Modifier
                                                .testTag(SharedTasksCompactRowTags.Time)
                                                .then(
                                                    if (staleNote != null) {
                                                        Modifier.semantics { contentDescription = "$label $staleNote" }
                                                    } else {
                                                        Modifier
                                                    },
                                                ),
                                        style = typography.monoMeta,
                                        color = if (stale) colors.warningStrong else colors.foregroundHint,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                            },
                        chevron =
                            if (showChevron) {
                                {
                                    RaftGlyphIcon(
                                        RaftGlyph.ChevronRight,
                                        size = 14.dp,
                                        color = colors.foregroundHint,
                                    )
                                }
                            } else {
                                null
                            },
                    )
                }
            },
        )
    }
}

/**
 * Icon-only soft status chip for narrow cross-channel Tasks rows (web `TaskChipList`
 * `<StatusBadge status={task.status}><span className="@max-md/tasks:sr-only">{statusLabel}</span></StatusBadge>`).
 */
@Composable
private fun SharedTasksCompactStatusDotChip(
    status: TaskStatus,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val raftStatus = status.raftTaskStatus()
    val shape = RoundedCornerShape(RaftRadius.Chip)
    val outline = raftStatus.outlineColor(colors)
    Box(
        modifier =
            modifier
                .size(22.dp)
                .background(raftStatus.softColor(colors), shape)
                .then(if (outline != null) Modifier.border(RaftStroke.Hairline, outline, shape) else Modifier)
                .clearAndSetSemantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        RaftTaskStatusPixel(raftStatus)
    }
}

@Composable
private fun SharedTasksCompactAssignee(
    task: TaskSummary,
    displayName: String,
    viewerId: String?,
    serverId: String?,
    cutoutColor: Color,
) {
    val assignee = task.assignee ?: return
    val isViewer = viewerId != null && assignee.kind == MemberKind.Human && assignee.id.value == viewerId
    val label = if (isViewer) stringResource(Res.string.thread_author_you) else displayName
    val cleanServerId = serverId?.trim()?.takeIf { it.isNotEmpty() }
    val membersStore = remember { RaftSharedStoreRegistry.currentOrNull()?.serverMembersStore }
    val memberAvatarResolver = rememberRaftMemberAvatarResolver(cleanServerId, membersStore)
    val agentStatusResolver =
        remember(membersStore, cleanServerId) {
            raftScopedAgentStatusResolver(membersStore, cleanServerId)
        }
    val agentStatus =
        if (assignee.kind == MemberKind.Agent) {
            rememberRaftAgentStatusIndicatorState(agentStatusResolver, assignee.id.value)
        } else {
            null
        }
    val avatarSize = RaftAvatarSize.Xs.edge
    RaftTaskCompactAssignee(
        name = label,
        viewer = isViewer,
        modifier = Modifier.testTag(SharedTasksCompactRowTags.Assignee),
        avatar = {
            RaftAvatarWithStatus(
                statusColor = agentStatus?.color,
                avatarSize = avatarSize,
                cutoutColor = cutoutColor,
            ) {
                RaftMemberAvatar(
                    identity = RaftMemberAvatarIdentity(assignee.kind, assignee.id.value, serverId = cleanServerId),
                    label = label,
                    avatarSize = avatarSize,
                    carriedAvatarWireValue = assignee.avatarWireValueForRenderedAvatar(),
                    resolver = memberAvatarResolver,
                )
            }
        },
    )
}

@Composable
private fun SharedTasksCompactReplyCount(
    count: Int,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val description =
        if (count == 1) {
            stringResource(Res.string.tasks_source_list_reply_count_one)
        } else {
            stringResource(Res.string.tasks_source_list_reply_count_many, count)
        }
    Row(
        modifier =
            modifier
                .testTag(SharedTasksCompactRowTags.Replies)
                .clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RaftGlyphIcon(RaftGlyph.Thread, size = 12.dp, color = colors.foregroundHint)
        RaftText(
            text = count.toString(),
            style = RaftTheme.typography.monoMeta,
            color = colors.foregroundHint,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Web `TasksListView` empty Mine section box (`TaskBoardColumnEmpty`): quiet rounded hairline box
 * with muted copy ("No tasks").
 */
@Composable
internal fun SharedTasksEmptySectionBox(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Container)
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.layerCard, shape)
                .border(RaftStroke.Hairline, colors.lineHairline, shape)
                .padding(horizontal = 12.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        RaftText(
            text = text,
            style = RaftTheme.typography.footnote,
            color = colors.foregroundHint,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Web `ChannelTasksPanel.tsx` `CompactStatusGroup` header for a channel's Tasks side card:
 * status pixel, status label, mono count, and collapse chevron, flush with [horizontalPadding].
 */
@Composable
internal fun SharedTasksCompactChannelSectionHeader(
    status: TaskStatus,
    count: Int,
    collapsed: Boolean,
    onClick: () -> Unit,
    horizontalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .raftSlot("task-section-trigger")
                .raftClickable(onClick = onClick, tier = RaftHoverTier.Subtle)
                .heightIn(min = 36.dp)
                .padding(horizontal = horizontalPadding, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RaftTaskStatusPixel(status.raftTaskStatus())
        RaftText(
            text = status.localizedLabel(),
            style = typography.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = colors.foregroundMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        RaftText(
            text = count.toString(),
            style = typography.monoMeta,
            color = colors.foregroundHint,
            maxLines = 1,
            softWrap = false,
        )
        RaftGlyphIcon(
            RaftGlyph.ChevronDown,
            modifier = Modifier.rotate(taskSectionChevronRotation(collapsed)),
            size = 14.dp,
            color = colors.foregroundHint,
        )
    }
}

/**
 * Draws the grouped `COMPACT_LIST_PAGE` rounded border and full-bleed hairlines across virtualized
 * `LazyColumn` rows (or the inset top hairline for `COMPACT_LIST_BLEED` plain rows).
 */
private fun Modifier.sharedTasksCompactRowBorders(
    variant: SharedTasksCompactRowVariant,
    firstInGroup: Boolean,
    lastInGroup: Boolean,
    hairline: Color,
    dividerInset: Dp,
): Modifier =
    drawWithContent {
        drawContent()
        val stroke = RaftStroke.Hairline.toPx()
        val half = stroke / 2f
        val w = size.width
        val h = size.height
        if (w <= stroke || h <= stroke) return@drawWithContent
        if (variant == SharedTasksCompactRowVariant.Plain) {
            if (!firstInGroup) {
                val inset = dividerInset.toPx().coerceAtMost(w / 2f)
                drawLine(hairline, Offset(inset, half), Offset(w - inset, half), strokeWidth = stroke)
            }
            return@drawWithContent
        }
        val r = RaftRadius.Container.toPx().coerceAtMost(min(w - stroke, h - stroke) / 2f).coerceAtLeast(0f)
        when {
            firstInGroup && lastInGroup -> {
                drawRoundRect(
                    color = hairline,
                    topLeft = Offset(half, half),
                    size = Size(w - stroke, h - stroke),
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(width = stroke),
                )
            }
            firstInGroup -> {
                val path =
                    Path().apply {
                        moveTo(half, h)
                        lineTo(half, half + r)
                        if (r > 0f) arcTo(Rect(half, half, half + 2 * r, half + 2 * r), 180f, 90f, false)
                        lineTo(w - half - r, half)
                        if (r > 0f) arcTo(Rect(w - half - 2 * r, half, w - half, half + 2 * r), 270f, 90f, false)
                        lineTo(w - half, h)
                    }
                drawPath(path, hairline, style = Stroke(width = stroke))
            }
            lastInGroup -> {
                drawLine(hairline, Offset(stroke, half), Offset(w - stroke, half), strokeWidth = stroke)
                val path =
                    Path().apply {
                        moveTo(half, 0f)
                        lineTo(half, h - half - r)
                        if (r > 0f) arcTo(Rect(half, h - half - 2 * r, half + 2 * r, h - half), 180f, -90f, false)
                        lineTo(w - half - r, h - half)
                        if (r > 0f) arcTo(Rect(w - half - 2 * r, h - half - 2 * r, w - half, h - half), 90f, -90f, false)
                        lineTo(w - half, 0f)
                    }
                drawPath(path, hairline, style = Stroke(width = stroke))
            }
            else -> {
                drawLine(hairline, Offset(stroke, half), Offset(w - stroke, half), strokeWidth = stroke)
                drawLine(hairline, Offset(half, 0f), Offset(half, h), strokeWidth = stroke)
                drawLine(hairline, Offset(w - half, 0f), Offset(w - half, h), strokeWidth = stroke)
            }
        }
    }

/**
 * Adaptive 1-line / 2-line compact task row layout (web `COMPACT_ROW` flex-wrap):
 * - Single line when leading ref + title + trailing meta fit on one line, or on wide containers
 *   when the title keeps at least `160dp` beside the trailing meta.
 * - Two lines on phones / narrow containers when title + trailing meta don't both fit on one line:
 *   Line 1 = leading ref + full-width title group, Line 2 = right-aligned trailing meta group.
 * Never reads `[FirstBaseline]` of composite children during measure.
 */
@Composable
private fun SharedTasksCompactRowLayout(
    modifier: Modifier,
    narrow: Boolean,
    wrapTitle: Boolean,
    hasCheck: Boolean,
    hasLeadingRef: Boolean,
    hasMeta: Boolean,
    minHeightPx: Int,
    calloutLineHeightPx: Int,
    leadingCheck: @Composable () -> Unit,
    leadingRef: @Composable () -> Unit,
    titleGroup: @Composable () -> Unit,
    metaGroup: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier,
        content = {
            Box(Modifier.layoutId(CompactSlotCheck)) { leadingCheck() }
            Box(Modifier.layoutId(CompactSlotRef)) { leadingRef() }
            Box(Modifier.layoutId(CompactSlotTitle)) { titleGroup() }
            Box(Modifier.layoutId(CompactSlotMeta)) { metaGroup() }
        },
    ) { measurables, constraints ->
        val gapX = 8.dp.roundToPx()
        val checkGapX = 10.dp.roundToPx()
        val gapY = 4.dp.roundToPx()
        val padY = SharedTasksCompactRowVerticalPadding.roundToPx()
        val titleBasis = SharedTasksCompactRowTitleBasis.roundToPx()
        val checkM = measurables.first { it.layoutId == CompactSlotCheck }
        val refM = measurables.first { it.layoutId == CompactSlotRef }
        val titleM = measurables.first { it.layoutId == CompactSlotTitle }
        val metaM = measurables.first { it.layoutId == CompactSlotMeta }

        val loose = Constraints()
        val check = checkM.measure(loose)
        val checkW = if (hasCheck) check.width else 0
        val checkGap = if (checkW > 0) checkGapX else 0
        val checkOffset = checkW + checkGap
        val ref = refM.measure(loose)
        val refW = if (hasLeadingRef) ref.width else 0
        val refGap = if (refW > 0) gapX else 0
        val metaNatural = if (hasMeta) metaM.maxIntrinsicWidth(Constraints.Infinity) else 0
        val titleNatural = titleM.maxIntrinsicWidth(Constraints.Infinity)
        val width =
            if (constraints.hasBoundedWidth) {
                constraints.maxWidth
            } else {
                checkOffset + refW + refGap + titleNatural + if (hasMeta) gapX + metaNatural else 0
            }
        val afterRef = max(0, width - checkOffset - refW - refGap)
        val roomForTitleOnOneLine = max(0, afterRef - gapX - metaNatural)
        val fitsBothOnOneLine = !hasMeta || roomForTitleOnOneLine >= titleNatural
        val fitsWideWrappedOnOneLine = !narrow && wrapTitle && roomForTitleOnOneLine >= titleBasis
        val fitsPlainCompactOnOneLine = !wrapTitle && roomForTitleOnOneLine >= min(titleNatural, titleBasis)
        val oneLine = fitsBothOnOneLine || fitsWideWrappedOnOneLine || fitsPlainCompactOnOneLine

        val meta = metaM.measure(Constraints(maxWidth = max(0, width - checkOffset)))
        val titleMaxWidth =
            if (oneLine && hasMeta) {
                max(0, afterRef - gapX - meta.width)
            } else {
                afterRef
            }
        val title = titleM.measure(Constraints(maxWidth = titleMaxWidth))

        val leadingBox = max(ref.height, if (hasCheck) check.height else 0)
        val firstLineRefBox = min(title.height, calloutLineHeightPx).coerceAtLeast(leadingBox)
        val line1Height =
            if (oneLine && hasMeta) {
                max(max(leadingBox, title.height), meta.height)
            } else {
                max(leadingBox, title.height)
            }
        val contentHeight = line1Height + if (!oneLine && hasMeta) gapY + meta.height else 0
        val height = max(minHeightPx, contentHeight + padY * 2).coerceIn(constraints.minHeight, constraints.maxHeight)
        val top = (height - contentHeight) / 2
        val singleLineTitle = title.height <= calloutLineHeightPx + 4

        layout(width, height) {
            val titleY =
                if (oneLine && singleLineTitle) {
                    top + (line1Height - title.height) / 2
                } else {
                    top
                }
            val checkY =
                if (oneLine && singleLineTitle) {
                    top + (line1Height - check.height) / 2
                } else {
                    top + max(0, (firstLineRefBox - check.height) / 2)
                }
            val refY =
                if (oneLine && singleLineTitle) {
                    top + (line1Height - ref.height) / 2
                } else {
                    top + max(0, (firstLineRefBox - ref.height) / 2)
                }
            if (hasCheck) {
                check.placeRelative(0, checkY)
            }
            if (hasLeadingRef) {
                ref.placeRelative(checkOffset, refY)
            }
            title.placeRelative(checkOffset + refW + refGap, titleY)
            if (hasMeta) {
                if (oneLine) {
                    val metaY =
                        if (singleLineTitle) {
                            top + (line1Height - meta.height) / 2
                        } else {
                            top + max(0, (firstLineRefBox - meta.height) / 2)
                        }
                    meta.placeRelative(width - meta.width, metaY)
                } else {
                    meta.placeRelative(width - meta.width, top + line1Height + gapY)
                }
            }
        }
    }
}

/**
 * Single-line trailing metadata row where fixed tokens (status chip, relative time, chevron) never
 * truncate and shrinkable tokens (`#channel`, assignee) only ellipsize if their combined width
 * exceeds the remaining row budget.
 */
@Composable
private fun SharedTasksCompactMetaRow(
    modifier: Modifier = Modifier,
    channel: (@Composable () -> Unit)? = null,
    assignee: (@Composable () -> Unit)? = null,
    status: (@Composable () -> Unit)? = null,
    time: (@Composable () -> Unit)? = null,
    chevron: (@Composable () -> Unit)? = null,
) {
    val gapPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    val assigneeCapPx = with(LocalDensity.current) { SharedTasksCompactAssigneeMaxWidth.roundToPx() }
    val measurePolicy =
        remember(gapPx, assigneeCapPx) {
            object : MeasurePolicy {
                override fun MeasureScope.measure(
                    measurables: List<Measurable>,
                    constraints: Constraints,
                ): MeasureResult {
                    if (measurables.isEmpty()) return layout(0, 0) {}
                    val totalGaps = gapPx * (measurables.size - 1)
                    val loose = Constraints()
                    val placeables = arrayOfNulls<Placeable>(measurables.size)
                    var fixedSum = 0
                    val shrinkIndices = mutableListOf<Int>()
                    measurables.forEachIndexed { index, measurable ->
                        val id = measurable.layoutId
                        if (id == MetaSlotChannel || id == MetaSlotAssignee) {
                            shrinkIndices += index
                        } else {
                            val placed = measurable.measure(loose)
                            placeables[index] = placed
                            fixedSum += placed.width
                        }
                    }
                    if (shrinkIndices.isNotEmpty()) {
                        val remaining =
                            if (constraints.hasBoundedWidth) {
                                max(0, constraints.maxWidth - fixedSum - totalGaps)
                            } else {
                                Int.MAX_VALUE
                            }
                        val naturals =
                            shrinkIndices.map { index ->
                                val raw = measurables[index].maxIntrinsicWidth(Constraints.Infinity)
                                if (measurables[index].layoutId == MetaSlotAssignee) min(raw, assigneeCapPx) else raw
                            }
                        val naturalSum = naturals.sum()
                        val budgets =
                            if (naturalSum <= remaining) {
                                naturals
                            } else if (shrinkIndices.size == 1) {
                                listOf(remaining)
                            } else {
                                // Two shrinkable items (channel + assignee): give the shorter one
                                // what it needs up to half, and let the other take the rest.
                                val half = remaining / 2
                                val a = naturals[0]
                                val b = naturals[1]
                                when {
                                    a <= half -> listOf(a, max(0, remaining - a))
                                    b <= half -> listOf(max(0, remaining - b), b)
                                    else -> listOf(half, max(0, remaining - half))
                                }
                            }
                        shrinkIndices.forEachIndexed { i, index ->
                            val cap = budgets[i].coerceAtLeast(0)
                            placeables[index] = measurables[index].measure(Constraints(maxWidth = cap))
                        }
                    }
                    val resolved = placeables.map { it!! }
                    val width = (resolved.sumOf { it.width } + totalGaps).coerceIn(constraints.minWidth, constraints.maxWidth)
                    val height = resolved.maxOf { it.height }.coerceIn(constraints.minHeight, constraints.maxHeight)
                    return layout(width, height) {
                        var x = 0
                        resolved.forEach { placeable ->
                            placeable.placeRelative(x, (height - placeable.height) / 2)
                            x += placeable.width + gapPx
                        }
                    }
                }

                override fun IntrinsicMeasureScope.maxIntrinsicWidth(
                    measurables: List<IntrinsicMeasurable>,
                    height: Int,
                ): Int {
                    if (measurables.isEmpty()) return 0
                    val sum =
                        measurables.sumOf { measurable ->
                            val raw = measurable.maxIntrinsicWidth(height)
                            val id = (measurable.parentData as? LayoutIdParentData)?.layoutId
                            if (id == MetaSlotAssignee) min(raw, assigneeCapPx) else raw
                        }
                    return sum + gapPx * (measurables.size - 1)
                }
            }
        }
    Layout(
        modifier = modifier,
        measurePolicy = measurePolicy,
        content = {
            channel?.let { Box(Modifier.layoutId(MetaSlotChannel)) { it() } }
            assignee?.let { Box(Modifier.layoutId(MetaSlotAssignee).widthIn(max = SharedTasksCompactAssigneeMaxWidth)) { it() } }
            status?.let { Box(Modifier.layoutId(MetaSlotStatus)) { it() } }
            time?.let { Box(Modifier.layoutId(MetaSlotTime)) { it() } }
            chevron?.let { Box(Modifier.layoutId(MetaSlotChevron)) { it() } }
        },
    )
}

private const val CompactSlotCheck = "check"
private const val CompactSlotRef = "ref"
private const val CompactSlotTitle = "title"
private const val CompactSlotMeta = "meta"

private const val MetaSlotChannel = "channel"
private const val MetaSlotAssignee = "assignee"
private const val MetaSlotStatus = "status"
private const val MetaSlotTime = "time"
private const val MetaSlotChevron = "chevron"

// not a token: web COMPACT_LIST_PAGE `--compact-row-inset: 0.75rem`
private val SharedTasksCompactRowPageInset: Dp = 12.dp
private val SharedTasksCompactRowVerticalPadding: Dp = 6.dp
private val SharedTasksCompactRowTitleBasis: Dp = 160.dp
private val SharedTasksCompactAssigneeMaxWidth: Dp = 192.dp
private const val SharedTasksStaleAfterMillis: Long = 3L * MillisPerDay
