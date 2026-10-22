package build.raft.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.theme.RaftTypography
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
     * the label 6dp after it (a small ghost help button). [details] is laid out in a row
     * that wraps, 6dp apart both ways, centred on each line, in `text-callout`
     * `foreground`: several chips, or an avatar and a long name, wrap instead of clipping.
     * Screen readers read the term and its value as one item.
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
 * capped at 160dp, 16dp from the values, label and value on one baseline. With no width
 * limit (in a horizontal scroll) `Horizontal` keeps two columns and sizes the values to
 * their content. A horizontal list measures its labels in a subcomposition first, so it
 * has no intrinsic size: don't put it under `IntrinsicSize`.
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
        RaftDescriptionListDirection.Horizontal -> {
            val slots = remember(content) { HorizontalSlots(content) }
            SubcomposeLayout(modifier) { constraints ->
                val gapY = 2.dp.roundToPx()
                val bounded = constraints.hasBoundedWidth
                val stacked = bounded && constraints.maxWidth < RaftDescriptionListStackBelow.roundToPx()
                // The label column is as wide as the widest label (88..160dp): measure the
                // labels alone first, then lay out each fact (one node, for screen readers) on it.
                val column =
                    if (stacked) {
                        0
                    } else {
                        val cap = 160.dp.roundToPx()
                        subcompose(HorizontalSlot.Terms, slots.terms)
                            .maxOfOrNull { it.measure(Constraints(maxWidth = cap)).width }
                            .let { (it ?: 0).coerceIn(88.dp.roundToPx(), cap) }
                    }
                val itemConstraints = Constraints(maxWidth = if (bounded) constraints.maxWidth else Constraints.Infinity)
                val items =
                    subcompose(HorizontalSlot.Items, slots.items(stacked, column))
                        .map { it.measure(itemConstraints) }
                val natural = items.maxOfOrNull { it.width } ?: 0
                val width = (if (bounded && !stacked) constraints.maxWidth else natural).coerceAtLeast(constraints.minWidth)
                val height = items.sumOf { it.height } + gapY * (items.size - 1).coerceAtLeast(0)
                layout(width, height.coerceAtLeast(constraints.minHeight)) {
                    var y = 0
                    items.forEach {
                        it.place(0, y)
                        y += it.height + gapY
                    }
                }
            }
        }
    }
}

private enum class HorizontalSlot { Terms, Items }

/**
 * The two subcompositions of a horizontal list, kept as the same lambdas while the
 * content and the layout don't change, so a measure pass doesn't recompose them.
 */
private class HorizontalSlots(private val content: @Composable RaftDescriptionListScope.() -> Unit) {
    val terms: @Composable () -> Unit = { TermsScope.content() }
    private var key: Pair<Boolean, Int>? = null
    private var itemsContent: @Composable () -> Unit = {}

    fun items(stacked: Boolean, column: Int): @Composable () -> Unit {
        val next = stacked to column
        if (key != next) {
            key = next
            val scope = HorizontalScope(stacked, column)
            itemsContent = { scope.content() }
        }
        return itemsContent
    }
}

/** A text value: `text-callout` `foreground`, Space Mono for machine values, "—" in hint ink when empty. */
@Composable
fun RaftDescriptionValue(text: String?, modifier: Modifier = Modifier, mono: Boolean = false) {
    val typography = RaftTheme.typography
    val colors = RaftTheme.colors
    if (text.isNullOrEmpty()) {
        RaftText("—", modifier = modifier, style = typography.callout, color = colors.foregroundHint)
    } else {
        RaftText(text, modifier = modifier, style = if (mono) typography.monoMeta else typography.callout, color = colors.foreground)
    }
}

/** The label's type: `text-footnote font-medium`. */
internal fun raftDescriptionTermStyle(typography: RaftTypography): TextStyle = typography.footnote.copy(fontWeight = FontWeight.Medium)

@Composable
private fun DescriptionTerm(term: String, accessory: (@Composable () -> Unit)?, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        RaftText(term, style = raftDescriptionTermStyle(RaftTheme.typography), color = RaftTheme.colors.foregroundHint)
        if (accessory != null) {
            // `*:data-[slot=button]:ml-[-5px]`: a ghost icon button pulls in towards the label.
            Row(Modifier.offset(x = (-5).dp)) { accessory() }
        }
    }
}

/**
 * The value: one line is centred in [minHeight]. Content taller than that (wrapped chips)
 * keeps the inset a text line has in it above and below, so the next fact's label stays as
 * far from the last line as from a one-line value.
 */
@Composable
private fun DescriptionDetails(minHeight: Dp, content: @Composable RowScope.() -> Unit, modifier: Modifier = Modifier) {
    val lineHeight = RaftTheme.typography.callout.lineHeight
    CompositionLocalProvider(LocalRaftContentColor provides RaftTheme.colors.foreground) {
        Layout(
            content = {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) { content() }
            },
            modifier = modifier,
        ) { measurables, constraints ->
            val min = minHeight.roundToPx()
            val row = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
            val inset = if (lineHeight.isSp) ((min - lineHeight.roundToPx()) / 2).coerceAtLeast(0) else 0
            val height = if (row.height > min) row.height + inset * 2 else min
            val top = if (row.height > min) inset else (min - row.height) / 2
            layout(max(row.width, constraints.minWidth), height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
                row.place(0, top)
            }
        }
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

/** Measures the labels for the column width; never placed, hidden from accessibility. */
private object TermsScope : RaftDescriptionListScope {
    @Composable
    override fun Item(term: String, termAccessory: (@Composable () -> Unit)?, details: @Composable RowScope.() -> Unit) {
        DescriptionTerm(term, termAccessory, Modifier.clearAndSetSemantics { })
    }
}

/**
 * One fact of a horizontal list, label and value in one node. Side by side on [column]
 * (label on the value's first baseline, `items-baseline`), or [stacked] 2dp apart.
 */
private class HorizontalScope(private val stacked: Boolean, private val column: Int) : RaftDescriptionListScope {
    @Composable
    override fun Item(term: String, termAccessory: (@Composable () -> Unit)?, details: @Composable RowScope.() -> Unit) {
        Layout(
            content = {
                DescriptionTerm(term, termAccessory)
                DescriptionDetails(32.dp, details)
            },
            modifier = Modifier.semantics(mergeDescendants = true) {},
        ) { measurables, constraints ->
            val (termMeasurable, detailsMeasurable) = measurables
            val bounded = constraints.hasBoundedWidth
            val loose = Constraints(maxWidth = constraints.maxWidth)
            if (stacked) {
                val gapY = 2.dp.roundToPx()
                val t = termMeasurable.measure(loose)
                val v = detailsMeasurable.measure(loose)
                layout(max(t.width, v.width).coerceAtLeast(constraints.minWidth), t.height + gapY + v.height) {
                    t.place(0, 0)
                    v.place(0, t.height + gapY)
                }
            } else {
                val gapX = 16.dp.roundToPx()
                val t = termMeasurable.measure(Constraints(maxWidth = 160.dp.roundToPx()))
                val valueMax = if (bounded) (constraints.maxWidth - column - gapX).coerceAtLeast(0) else Constraints.Infinity
                val v = detailsMeasurable.measure(Constraints(maxWidth = valueMax))
                val tb = t[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: 0
                val vb = v[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: tb
                val baseline = max(tb, vb)
                val tTop = baseline - tb
                val vTop = baseline - vb
                val width = if (bounded) constraints.maxWidth else column + gapX + v.width
                layout(width, max(tTop + t.height, vTop + v.height)) {
                    t.place(0, tTop)
                    v.place(column + gapX, vTop)
                }
            }
        }
    }
}
