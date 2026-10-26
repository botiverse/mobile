package build.raft.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette
import build.raft.designsystem.tokens.RaftUiThemeColors

/** raft-ui `StepStatus`: [Wait] is ahead, [Process] in progress, [Finish] done and [Error] failed. */
enum class RaftStepStatus { Wait, Process, Finish, Error }

/**
 * One step of [RaftSteps]: its [title] (a short noun phrase), an optional [description] under
 * it, and an optional [icon] that replaces the number of a step that is waiting or in
 * progress (raft-ui `Step icon`), drawn in the marker's ink ([LocalRaftContentColor]) at
 * most 14dp: a [RaftSpinner] for a step that is running now.
 */
@Immutable
class RaftStep(
    val title: String,
    val description: String? = null,
    val icon: (@Composable () -> Unit)? = null,
)

/** Metrics of [RaftSteps] (raft-ui 0.24 `steps.css`). */
object RaftStepsDefaults {
    /** The square marker. */
    val IndicatorSize = 20.dp

    /** The finish / error mark inside the marker. */
    val IconSize = 14.dp

    /** Between the marker and the text. */
    val Gap = 8.dp

    /** Between steps in a column (`--r-steps-vertical-item-gap`). */
    val VerticalItemGap = 16.dp
}

/**
 * The status a step at [index] (from 1) gets from [current] (from 1) and the steps' [status]:
 * steps before it are finished, after it waiting, the current one takes [status] (raft-ui
 * `stepStatus`).
 */
fun raftStepStatus(index: Int, current: Int, status: RaftStepStatus): RaftStepStatus =
    when {
        index < current -> RaftStepStatus.Finish
        index > current -> RaftStepStatus.Wait
        else -> status
    }

/** Resolved colours of one step status (raft-ui `--r-steps-*-color-*`). */
internal data class RaftStepLook(
    val fill: Color,
    val edge: Color,
    val edgeWidth: Dp,
    val ink: Color,
    val title: Color,
    val description: Color,
)

internal fun raftStepLook(status: RaftStepStatus, colors: RaftUiThemeColors): RaftStepLook =
    when (status) {
        // Ahead: a seam outline on paper, the number and the title in hint ink.
        RaftStepStatus.Wait ->
            RaftStepLook(colors.layerCard, colors.lineSeam, RaftStroke.Hairline, colors.foregroundHint, colors.foregroundHint, colors.foregroundHint)
        // Current: the `selected` sticker (steps.css `--r-steps-indicator-color-process`), 1.5dp
        // ink edge, an ink number.
        RaftStepStatus.Process ->
            RaftStepLook(RaftUiPalette.selected, RaftUiPalette.stickerInk, RaftStroke.Control, RaftUiPalette.stickerInk, colors.foreground, colors.foregroundMuted)
        // Finished: solid ink with a paper check.
        RaftStepStatus.Finish ->
            RaftStepLook(colors.foreground, colors.foreground, RaftStroke.Control, colors.layerCanvas, colors.foregroundMuted, colors.foregroundHint)
        // Failed: the orange sticker with an ink cross.
        RaftStepStatus.Error ->
            RaftStepLook(RaftUiPalette.warning, RaftUiPalette.stickerInk, RaftStroke.Control, RaftUiPalette.stickerInk, colors.foreground, colors.foregroundMuted)
    }

/**
 * raft-ui 0.24 Steps in a column (`vertical`): where someone is in a short flow of 3 to 5
 * [steps]. Each step is a 20dp square marker (the dialog's sticker radius) with its number in
 * Space Mono 12 bold, the title in `text-footnote` semibold 8dp after it, and an optional
 * `text-caption` description 2dp under the title; steps are 16dp apart, and a 1dp `line-seam`
 * runs down from 4dp under each marker to 4dp above the next.
 *
 * [current] (from 1) is the current step: steps before it are [RaftStepStatus.Finish] (solid
 * ink with a check), after it [RaftStepStatus.Wait] (a seam outline, hint ink), and the current
 * one takes [status]: [RaftStepStatus.Process] the yellow sticker, `Finish`, `Error` the orange
 * sticker with a cross. A step's [RaftStep.icon] replaces its number while waiting or in
 * progress.
 *
 * Screen readers read each step as one item: its title, then [finishLabel] or [errorLabel]
 * when its mark says that only by look.
 *
 * Not ported: the row layout (raft-ui's default, which stacks on a narrow region anyway) and
 * clickable steps.
 */
@Composable
fun RaftSteps(
    steps: List<RaftStep>,
    current: Int,
    modifier: Modifier = Modifier,
    status: RaftStepStatus = RaftStepStatus.Process,
    finishLabel: String = "Completed",
    errorLabel: String = "Failed",
) {
    val colors = RaftTheme.colors
    Column(modifier) {
        steps.forEachIndexed { offset, step ->
            val index = offset + 1
            val stepStatus = raftStepStatus(index, current, status)
            val last = index == steps.size
            RaftStepRow(
                step = step,
                index = index,
                look = raftStepLook(stepStatus, colors),
                status = stepStatus,
                connector = if (last) null else colors.lineSeam,
                statusLabel =
                    when (stepStatus) {
                        RaftStepStatus.Finish -> finishLabel
                        RaftStepStatus.Error -> errorLabel
                        else -> null
                    },
                modifier = if (last) Modifier else Modifier.padding(bottom = RaftStepsDefaults.VerticalItemGap),
            )
        }
    }
}

@Composable
private fun RaftStepRow(
    step: RaftStep,
    index: Int,
    look: RaftStepLook,
    status: RaftStepStatus,
    connector: Color?,
    statusLabel: String?,
    modifier: Modifier,
) {
    val typography = RaftTheme.typography
    val indicator = RaftStepsDefaults.IndicatorSize
    // The connector hangs from 4dp under the marker to 4dp above the next one, which starts
    // where this step's bottom padding ends: a Layout so its height follows the text.
    Layout(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { statusLabel?.let { stateDescription = it } },
        content = {
            Row(horizontalArrangement = Arrangement.spacedBy(RaftStepsDefaults.Gap)) {
                RaftStepMarker(step, index, look, status)
                // `padding-top: (20 - 18) / 2`: the title's first line is centred on the marker.
                Column(Modifier.weight(1f).padding(top = (indicator - 18.dp) / 2)) {
                    RaftText(
                        step.title,
                        style = typography.footnote.copy(fontWeight = FontWeight.SemiBold),
                        color = look.title,
                    )
                    if (step.description != null) {
                        RaftText(
                            step.description,
                            Modifier.padding(top = 2.dp),
                            style = typography.caption.copy(fontWeight = FontWeight.Normal),
                            color = look.description,
                        )
                    }
                }
            }
            if (connector != null) Box(Modifier.width(RaftStroke.Hairline).background(connector))
        },
    ) { measurables, constraints ->
        val main = measurables[0].measure(constraints.copy(minHeight = 0))
        val height = main.height
        val line =
            measurables.getOrNull(1)?.let {
                // To 4dp above the next marker, which sits under this step's bottom padding.
                val top = (indicator + 4.dp).roundToPx()
                val padding = RaftStepsDefaults.VerticalItemGap.roundToPx()
                val length = (height + padding - top - 4.dp.roundToPx()).coerceAtLeast(0)
                it.measure(Constraints.fixed(RaftStroke.Hairline.roundToPx().coerceAtLeast(1), length)) to top
            }
        layout(main.width, height) {
            main.place(0, 0)
            line?.let { (placeable, top) ->
                placeable.place(((indicator.toPx() - placeable.width) / 2).toInt(), top)
            }
        }
    }
}

@Composable
private fun RaftStepMarker(step: RaftStep, index: Int, look: RaftStepLook, status: RaftStepStatus) {
    val shape = RoundedCornerShape(RaftRadius.Sticker)
    Box(
        Modifier
            .size(RaftStepsDefaults.IndicatorSize)
            .background(look.fill, shape)
            .border(look.edgeWidth, look.edge, shape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalRaftContentColor provides look.ink) {
            when {
                status == RaftStepStatus.Finish -> RaftStepMark(RaftStepFinishMark, look.ink)
                status == RaftStepStatus.Error -> RaftStepMark(RaftStepErrorMark, look.ink)
                step.icon != null -> step.icon.invoke()
                else ->
                    RaftText(
                        index.toString(),
                        style = RaftTheme.typography.monoMeta.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 12.sp),
                        color = look.ink,
                        textAlign = TextAlign.Center,
                    )
            }
        }
    }
}

/** raft-ui's marks, in a 16-unit box: the check `M3.5 8.5l3 3 6-7`, the cross `M4.5 4.5l7 7M11.5 4.5l-7 7`. */
private val RaftStepFinishMark =
    Path().apply {
        moveTo(3.5f, 8.5f)
        lineTo(6.5f, 11.5f)
        lineTo(12.5f, 4.5f)
    }

private val RaftStepErrorMark =
    Path().apply {
        moveTo(4.5f, 4.5f)
        lineTo(11.5f, 11.5f)
        moveTo(11.5f, 4.5f)
        lineTo(4.5f, 11.5f)
    }

@Composable
private fun RaftStepMark(path: Path, ink: Color) {
    Canvas(Modifier.size(RaftStepsDefaults.IconSize)) {
        val unit = size.minDimension / 16f
        scale(unit, unit, pivot = Offset.Zero) {
            drawPath(path, ink, style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
