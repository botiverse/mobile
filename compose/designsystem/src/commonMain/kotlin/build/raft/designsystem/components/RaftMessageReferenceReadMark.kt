package build.raft.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import build.raft.designsystem.theme.RaftTheme

/**
 * raft-ui `MessageReferenceReadMark` (wip, raft-design #715, message-item.md "Read receipts on a
 * mention"): the read receipt on a mention of an agent. A muted Lucide `check` follows the name,
 * last inside the mention, once the agent has read the message; until then nothing is drawn.
 *
 * - The check is the size of an icon in a reference: 12px beside 14px text, so 0.857em of the
 *   mention (13.7dp beside the phone's 16sp body), in `foreground-hint`, Lucide's 2-unit stroke.
 * - No space of its own after the name; its end takes back the extra 2/24 Lucide leaves inside the
 *   check's box (`-mr-[calc(0.857em*2/24)]`), so the word space comes after it.
 * - Centred on the letters' cap height (0.7em / 2 above the baseline), not raised like a
 *   superscript; its box fits inside the line, so the line keeps its height.
 * - Not a status light: the lime square read as "online" (raft-ui Status, presence lights).
 *
 * The check is decorative; [label] is what a screen reader says instead ("Read").
 */
object RaftMessageReferenceReadMarkDefaults {
    /** The check's box, in ems of the mention (12 / 14). */
    const val SizeEm: Float = 0.857f

    /** The check's centre above the baseline: half the cap height (0.7em / 2). */
    const val CenterAboveBaselineEm: Float = 0.35f

    /** What the end takes back: Lucide draws the check 3/24 inside its box, other icons 1/24. */
    const val EndTrimEm: Float = SizeEm * 2f / 24f

    /** The box in a row of its own (`self-center`), beside 14px text. */
    val Size: Dp = 12.dp

    /** The placeholder in running text: the check's box less [EndTrimEm], sitting on the baseline. */
    val Placeholder: Placeholder =
        Placeholder(
            width = (SizeEm - EndTrimEm).em,
            height = SizeEm.em,
            placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline,
        )

    /**
     * The same placeholder with no width: nothing is drawn, the line is untouched, and a screen
     * reader still hears the state (raft-ui says "Not read yet" in the mention's hover card).
     */
    val SilentPlaceholder: Placeholder =
        Placeholder(
            width = 0.em,
            height = SizeEm.em,
            placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline,
        )
}

/** The check in a row (raft-ui `self-center`): a [size] box, the check centred in it. */
@Composable
fun RaftMessageReferenceReadMark(
    label: String?,
    modifier: Modifier = Modifier,
    size: Dp = RaftMessageReferenceReadMarkDefaults.Size,
) {
    val ink = RaftTheme.colors.foregroundHint
    Canvas(modifier.size(size).readMarkSemantics(label)) { drawReadCheck(ink, this.size.height) }
}

/**
 * Inline content for a mention in running text: the check on the cap-height line, in a
 * [RaftMessageReferenceReadMarkDefaults.Placeholder] box. Append it with
 * [appendRaftMessageReferenceReadMark] right after the mention's name.
 */
fun raftMessageReferenceReadMarkInlineContent(label: @Composable () -> String?): InlineTextContent =
    InlineTextContent(RaftMessageReferenceReadMarkDefaults.Placeholder) {
        val ink = RaftTheme.colors.foregroundHint
        Canvas(Modifier.fillMaxSize().readMarkSemantics(label())) {
            // The placeholder's bottom is the baseline and its height the check's box (0.857em):
            // move the check down so its centre sits half the cap height above the baseline.
            val box = size.height
            val em = box / RaftMessageReferenceReadMarkDefaults.SizeEm
            val down = box / 2f - RaftMessageReferenceReadMarkDefaults.CenterAboveBaselineEm * em
            translate(top = down) { drawReadCheck(ink, box) }
        }
    }

/**
 * Inline content that draws nothing and takes no width, for a mention that has not been read
 * yet: raft-ui shows nothing then, but the state is still said ([label], "Not read yet").
 */
fun raftMessageReferenceSilentReadStateInlineContent(label: @Composable () -> String?): InlineTextContent =
    InlineTextContent(RaftMessageReferenceReadMarkDefaults.SilentPlaceholder) {
        Box(Modifier.fillMaxSize().readMarkSemantics(label()))
    }

/** Appends the inline content [id] (one of the two above) after a mention's name. */
fun AnnotatedString.Builder.appendRaftMessageReferenceReadMark(id: String) {
    appendInlineContent(id, "￼")
}

private fun Modifier.readMarkSemantics(label: String?): Modifier =
    clearAndSetSemantics { if (label != null) contentDescription = label }

/** Lucide `check` in a [box] square at the top-left, 2-unit round stroke of the 24 grid. */
private fun DrawScope.drawReadCheck(ink: Color, box: Float) {
    val unit = box / 24f
    scale(unit, unit, pivot = Offset.Zero) {
        val stroke = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        RaftGlyph.Check.parsed.forEach { drawPath(it, ink, style = stroke) }
    }
}
