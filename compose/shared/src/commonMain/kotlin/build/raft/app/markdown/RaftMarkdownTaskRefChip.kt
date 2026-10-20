package build.raft.app.markdown

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.app.RaftAppRadius
import build.raft.app.RaftAppThemeState
import build.raft.app.designtokens.RaftUiPalette
import build.raft.app.designtokens.RaftUiThemeColors
import build.raft.app.model.TaskStatus
import build.raft.app.raftSpanStyleForWeight

/**
 * Status of every task the current surface knows, keyed by task number, for inline `#N` task
 * refs in Markdown. Mirrors web `taskByNumber` (MessageItem.tsx): the surface provides server
 * tasks overlaid by channel tasks (channel wins); [RaftMarkdownOwnTaskStatusScope] then layers the
 * message's own linked task on top for that message's body only.
 *
 * `null` means the surface has no task source at all (search previews, fixtures, …): task refs keep
 * the legacy uniform chip there. A non-null map switches to web parity: a known task renders the
 * raft-ui inline `TaskChip` in its status colours; an unknown one renders the neutral muted badge
 * that claims no status.
 */
internal val LocalRaftMarkdownTaskStatuses = compositionLocalOf<Map<Int, TaskStatus>?> { null }

/**
 * The status map with a message's own linked task layered on top (web: "the message's own linked
 * task is always the freshest source for a ref in that message's body"). A `null` base stays
 * `null`: the surface has no (loaded) task source, so refs keep the legacy chip.
 */
internal fun Map<Int, TaskStatus>?.withRaftMarkdownOwnTaskStatus(
    taskNumber: Int?,
    taskStatus: TaskStatus?,
): Map<Int, TaskStatus>? {
    val base = this ?: return null
    val number = taskNumber?.takeIf { it > 0 } ?: return base
    val status = taskStatus ?: return base
    if (base[number] == status) return base
    return base + (number to status)
}

/** Overrides [LocalRaftMarkdownTaskStatuses] with the message's own linked task for [content]. */
@Composable
internal fun RaftMarkdownOwnTaskStatusScope(
    taskNumber: Int?,
    taskStatus: String?,
    content: @Composable () -> Unit,
) {
    val base = LocalRaftMarkdownTaskStatuses.current
    val statuses =
        remember(base, taskNumber, taskStatus) {
            base.withRaftMarkdownOwnTaskStatus(taskNumber, TaskStatus.fromWireValue(taskStatus))
        }
    if (statuses === base) {
        content()
    } else {
        CompositionLocalProvider(LocalRaftMarkdownTaskStatuses provides statuses, content = content)
    }
}

/** The pixel raft-ui draws for a status (`TaskStatusPixel`): a filled square with an ink edge. */
@Immutable
internal data class RaftTaskStatusPixelStyle(
    val fill: Color,
    val stroke: Color,
)

/**
 * Inline task-ref chip geometry and colours.
 *
 * Known status (raft-ui `TaskChip variant="inline"`): `bg-task-status-*-soft`, `rounded-sticker`,
 * no edge, `pl-[3px] pr-1.5 gap-1.5`, a 9px status pixel, `font-semibold text-foreground`,
 * `leading-[1.2em]`.
 * Unknown task (web `<Badge variant="muted">`, solid appearance): `bg-inactive`, 1.5px sticker-ink
 * edge, `rounded-sticker`, `px-1.5`, bold sticker-ink text, no pixel.
 *
 * Horizontal padding is carried by placeholders ([leadingGap], [trailingGap]) inside the boxed
 * range (the pixel lives in the leading one); the box's margin is reserved outside the chip by
 * [withRaftInlineChipGaps].
 */
@Immutable
internal data class RaftMarkdownTaskChipStyle(
    val status: TaskStatus?,
    val box: RaftInlineBoxStyle,
    val pixel: RaftTaskStatusPixelStyle?,
    val textColor: Color,
    val fontWeight: FontWeight,
    val leadingGap: Dp,
    val trailingGap: Dp,
)

internal const val RaftMarkdownTaskChipWordJoiner = "\u2060"
internal val RaftMarkdownTaskChipPixelSize = 9.dp
private val RaftMarkdownTaskChipPixelStroke = 1.5.dp
private val RaftMarkdownTaskChipPixelRadius = 1.25.dp
private val RaftMarkdownTaskChipPaddingStart = 3.dp
private val RaftMarkdownTaskChipIconGap = 6.dp
private val RaftMarkdownTaskChipPaddingEnd = 6.dp
private val RaftMarkdownTaskBadgePaddingX = 6.dp
private val RaftMarkdownTaskBadgeEdge = 1.5.dp
private val RaftMarkdownTaskChipVerticalPadding = 1.dp
// Outer gap reserved by withRaftInlineChipGaps so adjacent chips / CJK punctuation never touch the
// chip (web `mx-0.5`-sized breathing room; the chip padding itself lives in the lead/trail gaps).
private val RaftMarkdownTaskChipMargin = 2.dp

internal fun raftMarkdownTaskChipStyle(
    status: TaskStatus?,
    colors: RaftUiThemeColors = RaftAppThemeState.colors,
): RaftMarkdownTaskChipStyle {
    if (status == null) {
        return RaftMarkdownTaskChipStyle(
            status = null,
            box =
                RaftInlineBoxStyle(
                    backgroundColor = RaftUiPalette.inactive,
                    borderColor = RaftUiPalette.stickerInk,
                    borderWidth = RaftMarkdownTaskBadgeEdge,
                    paddingTop = RaftMarkdownTaskChipVerticalPadding,
                    paddingBottom = RaftMarkdownTaskChipVerticalPadding,
                    cornerRadius = RaftAppRadius.Sticker,
                marginStart = RaftMarkdownTaskChipMargin,
                marginEnd = RaftMarkdownTaskChipMargin,
                ),
            pixel = null,
            textColor = RaftUiPalette.inactiveForeground,
            fontWeight = FontWeight.Bold,
            leadingGap = RaftMarkdownTaskBadgePaddingX,
            trailingGap = RaftMarkdownTaskBadgePaddingX,
        )
    }
    return RaftMarkdownTaskChipStyle(
        status = status,
        box =
            RaftInlineBoxStyle(
                backgroundColor = status.raftTaskStatusSoft(colors),
                paddingTop = RaftMarkdownTaskChipVerticalPadding,
                paddingBottom = RaftMarkdownTaskChipVerticalPadding,
                cornerRadius = RaftAppRadius.Sticker,
                marginStart = RaftMarkdownTaskChipMargin,
                marginEnd = RaftMarkdownTaskChipMargin,
            ),
        pixel =
            RaftTaskStatusPixelStyle(
                fill = status.raftTaskStatusColor(colors),
                stroke = RaftUiPalette.stickerInk,
            ),
        textColor = colors.foreground,
        fontWeight = FontWeight.SemiBold,
        leadingGap = RaftMarkdownTaskChipPaddingStart + RaftMarkdownTaskChipPixelSize + RaftMarkdownTaskChipIconGap,
        trailingGap = RaftMarkdownTaskChipPaddingEnd,
    )
}

/** `--task-status-*`: the pixel fill. */
internal fun TaskStatus.raftTaskStatusColor(colors: RaftUiThemeColors): Color =
    when (this) {
        TaskStatus.Todo -> colors.taskStatusTodo
        // `--task-status-in-progress` is one value in both themes, so it lives on the palette.
        TaskStatus.InProgress -> RaftUiPalette.taskStatusInProgress
        TaskStatus.InReview -> colors.taskStatusInReview
        TaskStatus.Done -> colors.taskStatusDone
        TaskStatus.Closed -> colors.taskStatusClosed
    }

/** `--task-status-*-soft`: the chip fill. */
internal fun TaskStatus.raftTaskStatusSoft(colors: RaftUiThemeColors): Color =
    when (this) {
        TaskStatus.Todo -> colors.taskStatusTodoSoft
        TaskStatus.InProgress -> colors.taskStatusInProgressSoft
        TaskStatus.InReview -> colors.taskStatusInReviewSoft
        TaskStatus.Done -> colors.taskStatusDoneSoft
        TaskStatus.Closed -> colors.taskStatusClosedSoft
    }

internal fun RaftMarkdownTaskChipStyle.textSpanStyle(): SpanStyle =
    raftSpanStyleForWeight(fontWeight).copy(color = textColor)

internal fun RaftMarkdownTaskChipStyle.linkStyles(): TextLinkStyles =
    TextLinkStyles(
        style = textSpanStyle(),
        // raft-ui `press-overlay` on the chip; the span wash stands in for it.
        pressedStyle = textSpanStyle().copy(background = RaftAppThemeState.colors.fillPressed),
    )

internal fun RaftMarkdownTaskChipStyle.leadingInlineId(): String =
    "raft-task-chip-lead:${status?.toWireValue() ?: "unknown"}"

internal fun RaftMarkdownTaskChipStyle.trailingInlineId(): String =
    "raft-task-chip-trail:${status?.toWireValue() ?: "unknown"}"

internal fun taskNumberFromRefLabel(label: String): Int? =
    label.trim().removePrefix("#").toIntOrNull()?.takeIf { it > 0 }

/**
 * Appends one task-ref chip: `[lead/pixel] #N [trail]`, all inside the boxed range so the painted
 * chip covers its own padding. The caller wraps this in the link annotation.
 */
internal fun AnnotatedString.Builder.appendRaftMarkdownTaskChip(
    style: RaftMarkdownTaskChipStyle,
    label: String,
    trailing: AnnotatedString.Builder.() -> Unit = {},
) {
    // Word joiners keep the pixel, label and trailing pad on one line: a chip
    // never wraps between its status pixel and its number.
    withRaftInlineBox(style.box) {
        appendInlineContent(style.leadingInlineId(), "\uFFFC")
        append(RaftMarkdownTaskChipWordJoiner)
        withStyle(style.textSpanStyle()) { append(label) }
        trailing()
        append(RaftMarkdownTaskChipWordJoiner)
        appendInlineContent(style.trailingInlineId(), "\uFFFC")
    }
}

private val RaftMarkdownTaskChipInlineContentLight: Map<String, InlineTextContent> by lazy {
    raftMarkdownTaskChipInlineContentForColors(RaftUiThemeColors.RaftLight) // raft-color: light half of the per-theme cache (dark sibling below)
}
private val RaftMarkdownTaskChipInlineContentDark: Map<String, InlineTextContent> by lazy {
    raftMarkdownTaskChipInlineContentForColors(RaftUiThemeColors.RaftDark)
}

/**
 * Inline content for every chip style (each status plus the unknown badge) in one theme. Built
 * once per theme: the placeholders and pixel colours depend only on status and theme.
 */
internal fun raftMarkdownTaskChipInlineContent(dark: Boolean): Map<String, InlineTextContent> =
    if (dark) RaftMarkdownTaskChipInlineContentDark else RaftMarkdownTaskChipInlineContentLight

private fun raftMarkdownTaskChipInlineContentForColors(colors: RaftUiThemeColors): Map<String, InlineTextContent> =
    raftMarkdownTaskChipInlineContent(
        (TaskStatus.entries + listOf<TaskStatus?>(null)).map { status -> raftMarkdownTaskChipStyle(status, colors) }
    )

/** Inline content for every chip style used in a document (shared ids per status). */
internal fun raftMarkdownTaskChipInlineContent(
    styles: Collection<RaftMarkdownTaskChipStyle>
): Map<String, InlineTextContent> =
    buildMap {
        styles.forEach { style ->
            put(style.leadingInlineId(), style.leadingInlineContent())
            put(style.trailingInlineId(), raftMarkdownTaskChipSpacer(style.trailingGap))
        }
    }

private fun RaftMarkdownTaskChipStyle.leadingInlineContent(): InlineTextContent {
    val pixel = pixel ?: return raftMarkdownTaskChipSpacer(leadingGap)
    return InlineTextContent(
        placeholder =
            Placeholder(
                width = leadingGap.value.sp,
                height = RaftMarkdownTaskChipPixelSize.value.sp,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            )
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            RaftTaskStatusPixel(
                style = pixel,
                modifier = Modifier.padding(start = RaftMarkdownTaskChipPaddingStart),
            )
        }
    }
}

private fun raftMarkdownTaskChipSpacer(width: Dp): InlineTextContent =
    InlineTextContent(
        placeholder =
            Placeholder(
                width = width.value.sp,
                height = 1.sp,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            )
    ) {}

/** raft-ui `TaskStatusPixel`: 7.5px rect, 1.5px sticker-ink stroke, 1.25px radius (9px outer). */
@Composable
internal fun RaftTaskStatusPixel(
    style: RaftTaskStatusPixelStyle,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(RaftMarkdownTaskChipPixelSize)) {
        val strokePx = RaftMarkdownTaskChipPixelStroke.toPx()
        val inset = strokePx / 2f
        val rectSize = Size(size.width - strokePx, size.height - strokePx)
        val radius = CornerRadius(RaftMarkdownTaskChipPixelRadius.toPx())
        drawRoundRect(color = style.fill, topLeft = Offset(inset, inset), size = rectSize, cornerRadius = radius)
        drawRoundRect(
            color = style.stroke,
            topLeft = Offset(inset, inset),
            size = rectSize,
            cornerRadius = radius,
            style = Stroke(width = strokePx),
        )
    }
}

/**
 * Memo revision entries for the task chips of one document: the vendor AnnotatedString memo must
 * rebuild when a referenced task changes status or the theme flips (see annotationRevision in
 * RaftMarkdownBody).
 */
internal fun raftMarkdownTaskChipRevision(
    actions: List<RaftRichTextAction>,
    taskStatuses: Map<Int, TaskStatus>,
    dark: Boolean,
): List<String> {
    val taskActions = actions.filter { action -> action.refKind == RaftRichTextRefKind.Task }
    if (taskActions.isEmpty()) return emptyList()
    return buildList {
        add("taskTheme:${if (dark) "dark" else "light"}")
        taskActions.forEach { action ->
            val number = taskNumberFromRefLabel(action.label)
            add("task:${action.label}=${number?.let(taskStatuses::get)?.toWireValue() ?: "unknown"}")
        }
    }
}
