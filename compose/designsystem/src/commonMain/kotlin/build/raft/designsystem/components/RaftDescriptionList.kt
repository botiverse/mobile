package build.raft.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import kotlin.math.max

/** raft-ui DescriptionList `direction`. */
enum class RaftDescriptionListDirection {
    /** The label above the value: a side panel, a phone. */
    Vertical,

    /**
     * Two columns, the label on the left, at 640dp and wider. Narrower than that it stacks
     * into one column on its own, as raft-ui does below 640px (`max-sm`), so on a phone
     * it is a single column with 2dp between the lines.
     */
    Horizontal,
}

/** The width below which [RaftDescriptionListDirection.Horizontal] stacks (Tailwind `sm`). */
val RaftDescriptionListStackBelow: Dp = 640.dp

/** Builds the facts of a [RaftDescriptionList]. */
interface RaftDescriptionListScope {
    /**
     * One fact: [term] (a short noun, sentence case) and its value. [termAccessory] follows
     * the label 6dp after it (a small ghost help button). [details] is laid out in a row,
     * 6dp apart, centred, in `text-callout` `foreground`.
     */
    @Composable
    fun Item(
        term: String,
        termAccessory: (@Composable () -> Unit)? = null,
        details: @Composable RowScope.() -> Unit,
    )

    /** A fact with a text value; [mono] for machine values (IDs, emails, paths); null is "—". */
    @Composable
    fun Item(term: String, value: String?, mono: Boolean = false) {
        Item(term) { RaftDescriptionValue(value, mono = mono) }
    }
}

/**
 * raft-ui 0.8.0 DescriptionList: a few facts about one item, each a label and a value.
 * Labels are `text-footnote` medium `foreground-hint`. [RaftDescriptionListDirection.Vertical]
 * puts 8dp between facts and gives each value at least 28dp; `Horizontal` gives values
 * 32dp, 2dp between lines, and at 640dp and wider a label column `minmax(88, max-content)`
 * capped at 160dp, 16dp from the values, label and value on one baseline.
 *
 * A value that can be changed is a frameless trigger opening a picker; a fact that can't
 * be changed must not look clickable.
 */
@Composable
fun RaftDescriptionList(
    modifier: Modifier = Modifier,
    direction: RaftDescriptionListDirection = RaftDescriptionListDirection.Vertical,
    content: @Composable RaftDescriptionListScope.() -> Unit,
) {
    when (direction) {
        RaftDescriptionListDirection.Vertical ->
            Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) { VerticalScope.content() }
        RaftDescriptionListDirection.Horizontal ->
            Layout(content = { HorizontalScope.content() }, modifier = modifier) { measurables, constraints ->
                // Children come in pairs: term, details.
                val gapX = 16.dp.roundToPx()
                val gapY = 2.dp.roundToPx()
                val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
                val stacked = maxWidth < RaftDescriptionListStackBelow.roundToPx()
                val loose = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
                if (stacked) {
                    val placeables = measurables.map { it.measure(loose) }
                    val width = placeables.maxOfOrNull { it.width } ?: 0
                    val height = placeables.sumOf { it.height } + gapY * (placeables.size - 1).coerceAtLeast(0)
                    layout(max(width, constraints.minWidth), height) {
                        var y = 0
                        placeables.forEach {
                            it.place(0, y)
                            y += it.height + gapY
                        }
                    }
                } else {
                    val terms = measurables.filterIndexed { i, _ -> i % 2 == 0 }
                    val values = measurables.filterIndexed { i, _ -> i % 2 == 1 }
                    val termPlaceables = terms.map { it.measure(loose.copy(maxWidth = 160.dp.roundToPx())) }
                    val column = (termPlaceables.maxOfOrNull { it.width } ?: 0).coerceIn(88.dp.roundToPx(), 160.dp.roundToPx())
                    val valueWidth = (maxWidth - column - gapX).coerceAtLeast(0)
                    val valuePlaceables = values.map { it.measure(loose.copy(maxWidth = valueWidth)) }
                    // `items-baseline`: each label sits on its value's first baseline.
                    val rows =
                        termPlaceables.indices.map { i ->
                            val t = termPlaceables[i]
                            val v = valuePlaceables.getOrNull(i)
                            val tb = t[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: 0
                            val vb = v?.get(FirstBaseline)?.takeIf { it != AlignmentLine.Unspecified } ?: tb
                            val baseline = max(tb, vb)
                            val tTop = baseline - tb
                            val vTop = baseline - vb
                            Triple(tTop, vTop, max(tTop + t.height, vTop + (v?.height ?: 0)))
                        }
                    val height = rows.sumOf { it.third } + gapY * (rows.size - 1).coerceAtLeast(0)
                    layout(maxWidth, height) {
                        var y = 0
                        rows.forEachIndexed { i, (tTop, vTop, h) ->
                            termPlaceables[i].place(0, y + tTop)
                            valuePlaceables.getOrNull(i)?.place(column + gapX, y + vTop)
                            y += h + gapY
                        }
                    }
                }
            }
    }
}

/** A text value: `text-callout` `foreground`, Space Mono for machine values, "—" in hint ink when empty. */
@Composable
fun RaftDescriptionValue(text: String?, modifier: Modifier = Modifier, mono: Boolean = false) {
    val typography = RaftTheme.typography
    val colors = RaftTheme.colors
    if (text.isNullOrEmpty()) {
        RaftText("—", modifier = modifier, style = typography.body, color = colors.foregroundHint)
    } else {
        RaftText(text, modifier = modifier, style = if (mono) typography.monoMeta else typography.body, color = colors.foreground)
    }
}

@Composable
private fun DescriptionTerm(term: String, accessory: (@Composable () -> Unit)?, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        RaftText(term, style = RaftTheme.typography.footnote, color = RaftTheme.colors.foregroundHint)
        if (accessory != null) {
            // `*:data-[slot=button]:ml-[-5px]`: a ghost icon button pulls in towards the label.
            Row(Modifier.offset(x = (-5).dp)) { accessory() }
        }
    }
}

@Composable
private fun DescriptionDetails(minHeight: Dp, content: @Composable RowScope.() -> Unit, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalRaftContentColor provides RaftTheme.colors.foreground) {
        Row(
            modifier.heightIn(min = minHeight),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

private object VerticalScope : RaftDescriptionListScope {
    @Composable
    override fun Item(term: String, termAccessory: (@Composable () -> Unit)?, details: @Composable RowScope.() -> Unit) {
        // `[&+&]:pt-2` is the list's 8dp spacing.
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            DescriptionTerm(term, termAccessory)
            DescriptionDetails(28.dp, details)
        }
    }
}

private object HorizontalScope : RaftDescriptionListScope {
    @Composable
    override fun Item(term: String, termAccessory: (@Composable () -> Unit)?, details: @Composable RowScope.() -> Unit) {
        DescriptionTerm(term, termAccessory)
        DescriptionDetails(32.dp, details)
    }
}
