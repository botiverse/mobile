package build.raft.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.theme.RaftTypography
import kotlin.math.max
import kotlin.math.min

/** raft-ui DescriptionList `direction`. */
enum class RaftDescriptionListDirection {
    /** The label above the value: a side panel, a phone. */
    Vertical,

    /**
     * Two columns, the label on the left, while every value fits beside its label. Once one
     * doesn't, the whole list stacks into one column on its own (raft-ui 0.75
     * `useLabelValueFit`): each value 2dp under its label and 10dp above the next label. See
     * [raftLabelsGoAbove] for the rule; it reads the list's own width and its content, not
     * the window's, so short facts stay beside their labels on a phone.
     */
    Horizontal,

    /**
     * raft-ui `direction="inline"` (the task dialog's facts row): every fact on one wrapping
     * row, its label and value side by side on one first baseline 12dp apart, 24dp between
     * facts and 8dp between lines. A label never shrinks; a value takes what is left of its
     * line and wraps inside it.
     */
    Inline,
}

/** The narrowest a two-column label/value list's label column gets: `minmax(88px, max-content)`. */
internal val RaftLabelColumnMin: Dp = 88.dp

/** The widest a label gets beside its value (`max-w-40`). */
internal val RaftLabelColumnMax: Dp = 160.dp

/**
 * The narrowest the value column may be beside its labels: as wide as a label may grow. A value
 * column narrower than its label column reads as the label's footnote, not its answer.
 */
internal val RaftValueColumnMin: Dp = 160.dp

/**
 * raft-ui 0.75 `labelsGoAbove`: whether a two-column label/value list ([RaftDescriptionList]
 * `Horizontal`, [RaftMessageActionCardProperties]) [width] wide puts its labels above their
 * values. [labelColumn] is the label column (the widest label, each at most
 * [RaftLabelColumnMax], at least [RaftLabelColumnMin]) and [gap] the space after it;
 * [valueWidths] are the values' one-line widths. The labels go above when the value column
 * would be under [minValueColumn], or when some value doesn't fit on one line beside the
 * labels but would on a line of its own. A value too long for a line of its own (a paragraph)
 * wraps either way and doesn't decide. It depends only on the width and the content, so it
 * can't flap at the boundary; the whole list goes at once, so labels never alternate.
 */
internal fun raftLabelsGoAbove(width: Int, labelColumn: Int, gap: Int, minValueColumn: Int, valueWidths: List<Int>): Boolean {
    val valueColumn = width - labelColumn - gap
    if (valueColumn < minValueColumn) return true
    return valueWidths.any { it > valueColumn && it <= width }
}

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

    /**
     * A fact with a text value; [mono] for machine values (IDs, emails, paths); null is "—".
     * The value is long-press copyable unless [copyable] is false.
     */
    @Composable
    fun Item(term: String, value: String?, mono: Boolean = false, copyable: Boolean = true) {
        Item(term) { RaftDescriptionValue(value, mono = mono, copyable = copyable) }
    }
}

/**
 * raft-ui 0.8.0 DescriptionList: a few facts about one item, each a label and a value.
 * Labels are `text-footnote` medium `foreground-hint`. [RaftDescriptionListDirection.Vertical]
 * puts 8dp between facts and gives each value at least 28dp; `Horizontal`, while every value
 * fits beside its label ([raftLabelsGoAbove]), puts a label column `minmax(88, max-content)`
 * capped at 160dp 16dp before the values (each at least 32dp, 2dp between rows), label and
 * value on one baseline, and once one doesn't, stacks each value 2dp under its label with no
 * minimum height, 10dp from the next label. With no width limit (in a horizontal scroll)
 * `Horizontal` keeps two columns and sizes the values to their content. A horizontal list
 * measures its labels and values in subcompositions first, so it has no intrinsic size:
 * don't put it under `IntrinsicSize`.
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
        RaftDescriptionListDirection.Inline ->
            // `flex flex-wrap items-baseline gap-x-6 gap-y-2`.
            FlowRow(
                modifier,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { InlineScope(this).content() }
        RaftDescriptionListDirection.Horizontal -> {
            val slots = remember(content) { HorizontalSlots(content) }
            SubcomposeLayout(modifier) { constraints ->
                val bounded = constraints.hasBoundedWidth
                // The label column is as wide as the widest label (88..160dp): measure the
                // labels alone first, then lay out each fact (one node, for screen readers) on it.
                val cap = RaftLabelColumnMax.roundToPx()
                val labelColumn =
                    subcompose(HorizontalSlot.Terms, slots.terms)
                        .maxOfOrNull { it.measure(Constraints(maxWidth = cap)).width }
                        .let { (it ?: 0).coerceIn(RaftLabelColumnMin.roundToPx(), cap) }
                val gapX = RaftDescriptionColumnGap.roundToPx()
                // The labels go above their values once a value doesn't fit beside them: each
                // value measured on one line, never placed.
                val stacked =
                    bounded &&
                        raftLabelsGoAbove(
                            width = constraints.maxWidth,
                            labelColumn = labelColumn,
                            gap = gapX,
                            minValueColumn = RaftValueColumnMin.roundToPx(),
                            valueWidths = subcompose(HorizontalSlot.Values, slots.values).map { it.measure(Constraints()).width },
                        )
                // Stacked, a value is 10dp above the next label: the 2dp row gap plus `mt-2`.
                val gapY = (if (stacked) 10.dp else 2.dp).roundToPx()
                val column = if (stacked) 0 else labelColumn
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

private enum class HorizontalSlot { Terms, Values, Items }

/** Between the label column and the values (`gap-x-4`). */
private val RaftDescriptionColumnGap = 16.dp

/**
 * The two subcompositions of a horizontal list, kept as the same lambdas while the
 * content and the layout don't change, so a measure pass doesn't recompose them.
 */
private class HorizontalSlots(private val content: @Composable RaftDescriptionListScope.() -> Unit) {
    val terms: @Composable () -> Unit = { TermsScope.content() }
    val values: @Composable () -> Unit = { ValuesScope.content() }
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

/**
 * A text value: `text-callout` `foreground`, Space Mono for machine values, "—" in hint ink when
 * empty. A value is long-press copyable ([raftCopyable], the value only) unless [copyable] is false.
 */
@Composable
fun RaftDescriptionValue(
    text: String?,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    copyable: Boolean = true,
    /** One line cut with an ellipsis (the inline list); unlimited wraps. */
    maxLines: Int = Int.MAX_VALUE,
) {
    val typography = RaftTheme.typography
    val colors = RaftTheme.colors
    if (text.isNullOrEmpty()) {
        RaftText("—", modifier = modifier, style = typography.callout, color = colors.foregroundHint)
    } else {
        RaftText(
            text,
            modifier = modifier.raftCopyable(text, enabled = copyable),
            style = if (mono) typography.monoMeta else typography.callout,
            color = colors.foreground,
            maxLines = maxLines,
            overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
        )
    }
}

/** The label's type: `text-footnote font-medium`. */
internal fun raftDescriptionTermStyle(typography: RaftTypography): TextStyle = typography.footnote.copy(fontWeight = FontWeight.Medium)

@Composable
private fun DescriptionTerm(
    term: String,
    accessory: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    lines: HorizontalFactLines? = null,
) {
    val textModifier = remember(lines) { if (lines != null) Modifier.textLineInto(lines.termBaseline, lines.termTextHeight) else Modifier }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        RaftText(
            term,
            modifier = textModifier,
            style = raftDescriptionTermStyle(RaftTheme.typography),
            color = RaftTheme.colors.foregroundHint,
        )
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
private fun DescriptionDetails(
    minHeight: Dp,
    content: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    lines: HorizontalFactLines? = null,
) {
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
            if (lines != null) {
                // Where the first line sits, for the label beside it: one line is the whole row;
                // wrapped content starts a text line's height below the inset.
                lines.valueLineTop.intValue = top
                lines.valueLineHeight.intValue =
                    if (row.height > min && lineHeight.isSp) lineHeight.roundToPx() else row.height
            }
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

/**
 * One fact of an inline list: the label, then 12dp on its value (`items-baseline gap-3`), on the
 * value's first baseline; the item sits on its line's first baseline too. The value has no
 * 28dp row to centre in: a chip or a frameless trigger lines its text up with the label's.
 */
private class InlineScope(private val row: FlowRowScope) : RaftDescriptionListScope {
    /** raft-ui inline facts truncate: a text value is one line, cut with an ellipsis. */
    @Composable
    override fun Item(term: String, value: String?, mono: Boolean, copyable: Boolean) {
        val lines = remember { HorizontalFactLines() }
        val valueModifier = remember(lines) { Modifier.textLineInto(lines.valueBaseline) }
        Fact(term, null, lines) { RaftDescriptionValue(value, valueModifier, mono = mono, copyable = copyable, maxLines = 1) }
    }

    @Composable
    override fun Item(term: String, termAccessory: (@Composable () -> Unit)?, details: @Composable RowScope.() -> Unit) {
        val lines = remember { HorizontalFactLines() }
        Fact(term, termAccessory, lines, details)
    }

    /**
     * As [HorizontalScope]'s fact, the inline fact reads no alignment line of its label's or its
     * value's subtree while it is measured (crash 69fdce17): the label's and a text value's
     * baselines come from the text nodes ([textLineInto]); any other value's is read once placed.
     */
    @Composable
    private fun Fact(
        term: String,
        termAccessory: (@Composable () -> Unit)?,
        lines: HorizontalFactLines,
        details: @Composable RowScope.() -> Unit,
    ) {
        val termLineHeight = raftDescriptionTermStyle(RaftTheme.typography).lineHeight
        with(row) {
            Layout(
                content = {
                    DescriptionTerm(term, termAccessory, lines = lines)
                    DescriptionDetails(0.dp, details, lines = lines)
                },
                // On its line's first baseline, read from what the fact measured (not by querying
                // its alignment lines, which would lay out its value while the row measures).
                modifier = Modifier.alignBy { lines.factBaseline.intValue }.semantics(mergeDescendants = true) {},
            ) { measurables, constraints ->
                val (termMeasurable, detailsMeasurable) = measurables
                val gapX = 12.dp.roundToPx()
                // A fact is at most 24rem wide (web `max-w-[min(100%,24rem)]`): on a tablet a long
                // value wraps instead of running across the panel.
                val maxW =
                    if (constraints.hasBoundedWidth) min(constraints.maxWidth, RaftDescriptionInlineMaxWidth.roundToPx()) else RaftDescriptionInlineMaxWidth.roundToPx()
                // The label never takes more than the line (large text). A value is cut to the
                // room beside its label (raft-ui truncates; text values are one line with an
                // ellipsis); only when the label leaves under 96dp does the value go under it.
                val t = termMeasurable.measure(Constraints(maxWidth = maxW))
                // The label's first baseline: its text is centred in its row (beside a help button).
                val termTextHeight = lines.termTextHeight.intValue
                val termTextTop = Alignment.CenterVertically.align(termTextHeight, t.height)
                val tb = termTextTop + (lines.termBaseline.intValue.takeIf { it != AlignmentLine.Unspecified } ?: 0)
                val beside = (maxW - t.width - gapX).coerceAtLeast(0)
                val stacked = beside < RaftDescriptionInlineMinValueWidth.roundToPx()
                if (stacked) {
                    val gapY = 2.dp.roundToPx()
                    val v = detailsMeasurable.measure(Constraints(maxWidth = maxW))
                    lines.factBaseline.intValue = tb
                    layout(max(t.width, v.width), t.height + gapY + v.height, mapOf(FirstBaseline to tb)) {
                        t.place(0, 0)
                        v.place(0, t.height + gapY)
                    }
                } else {
                    val v = detailsMeasurable.measure(Constraints(maxWidth = beside))
                    // The value's first baseline: a text value's own; any other value's as read when
                    // it was last placed; before that, the label's first line centred on the value's.
                    val lineTop = lines.valueLineTop.intValue
                    val textValue = lines.valueBaseline.intValue != AlignmentLine.Unspecified
                    val vb =
                        when {
                            textValue -> lineTop + lines.valueBaseline.intValue
                            lines.placedValueBaseline.intValue != AlignmentLine.Unspecified -> lines.placedValueBaseline.intValue
                            else -> {
                                val labelLine = if (termLineHeight.isSp) min(termLineHeight.roundToPx(), termTextHeight) else termTextHeight
                                lineTop + Alignment.CenterVertically.align(labelLine, lines.valueLineHeight.intValue) - termTextTop + tb
                            }
                        }
                    val baseline = max(tb, vb)
                    val vTop = baseline - vb
                    lines.factBaseline.intValue = baseline
                    layout(t.width + gapX + v.width, max(baseline - tb + t.height, vTop + v.height), mapOf(FirstBaseline to baseline)) {
                        // The value goes first, so its baseline can be read once it and its content
                        // are placed (safe, unlike while measuring); zIndex keeps the label first for
                        // screen readers. A baseline that moved from the measured one lays it out again.
                        v.place(t.width + gapX, vTop, zIndex = 1f)
                        val placedVb =
                            if (textValue) {
                                vb
                            } else {
                                (v[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: tb).also { lines.placedValueBaseline.intValue = it }
                            }
                        t.place(0, (vTop + placedVb - tb).coerceAtLeast(0))
                    }
                }
            }
        }
    }
}

/** Below this beside its label, an inline value goes under the label. */
private val RaftDescriptionInlineMinValueWidth = 96.dp

/** web inline facts: `max-w-[min(100%,24rem)]`. */
private val RaftDescriptionInlineMaxWidth = 384.dp

/** Measures the labels for the column width; never placed, hidden from accessibility. */
private object TermsScope : RaftDescriptionListScope {
    @Composable
    override fun Item(term: String, termAccessory: (@Composable () -> Unit)?, details: @Composable RowScope.() -> Unit) {
        DescriptionTerm(term, termAccessory, Modifier.clearAndSetSemantics { })
    }
}

/**
 * Measures the values on one line for [raftLabelsGoAbove] (a value's items in a row, 6dp apart,
 * as [DescriptionDetails] lays them out with nothing wrapping); never placed, hidden from
 * accessibility.
 */
private object ValuesScope : RaftDescriptionListScope {
    @Composable
    override fun Item(term: String, termAccessory: (@Composable () -> Unit)?, details: @Composable RowScope.() -> Unit) {
        Row(Modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(6.dp)) { details() }
    }
}

/**
 * One fact of a horizontal list, label and value in one node. Side by side on [column]
 * (label on the value's first baseline, `items-baseline`), or [stacked] 2dp apart with no
 * minimum value height.
 *
 * Side by side, the fact never reads its label's or its value's alignment lines while it is
 * measured: in a `LazyColumn` that made Compose 1.12 place the chips of a reused value before
 * the value itself, and RectManager threw (crash 69fdce17, fixed upstream in Compose 1.13,
 * androidx fd550bed79). The label's baseline and a text value's come from the text nodes
 * themselves ([textLineInto]); any other value's is read once it is placed.
 */
private class HorizontalScope(private val stacked: Boolean, private val column: Int) : RaftDescriptionListScope {
    @Composable
    override fun Item(term: String, value: String?, mono: Boolean, copyable: Boolean) {
        val lines = remember { HorizontalFactLines() }
        val valueModifier = remember(lines) { Modifier.textLineInto(lines.valueBaseline) }
        Fact(term, null, lines) { RaftDescriptionValue(value, valueModifier, mono = mono, copyable = copyable) }
    }

    @Composable
    override fun Item(term: String, termAccessory: (@Composable () -> Unit)?, details: @Composable RowScope.() -> Unit) {
        val lines = remember { HorizontalFactLines() }
        Fact(term, termAccessory, lines, details)
    }

    @Composable
    private fun Fact(
        term: String,
        termAccessory: (@Composable () -> Unit)?,
        lines: HorizontalFactLines,
        details: @Composable RowScope.() -> Unit,
    ) {
        val termLineHeight = raftDescriptionTermStyle(RaftTheme.typography).lineHeight
        Layout(
            content = {
                DescriptionTerm(term, termAccessory, lines = lines)
                // Stacked, the value has no 32dp row to centre in (`min-h-0`).
                DescriptionDetails(if (stacked) 0.dp else 32.dp, details, lines = lines)
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
                val gapX = RaftDescriptionColumnGap.roundToPx()
                val t = termMeasurable.measure(Constraints(maxWidth = RaftLabelColumnMax.roundToPx()))
                val valueMax = if (bounded) (constraints.maxWidth - column - gapX).coerceAtLeast(0) else Constraints.Infinity
                val v = detailsMeasurable.measure(Constraints(maxWidth = valueMax))
                // The label's first baseline: its text is centred in its row (beside a help button).
                val termTextHeight = lines.termTextHeight.intValue
                val termTextTop = Alignment.CenterVertically.align(termTextHeight, t.height)
                val tb = termTextTop + (lines.termBaseline.intValue.takeIf { it != AlignmentLine.Unspecified } ?: 0)
                // The value's first baseline: a text value's own (its text starts the value's
                // first line); any other value's as read when it was last placed; before that, the
                // label's first line centred on the value's first line.
                val lineTop = lines.valueLineTop.intValue
                val textValue = lines.valueBaseline.intValue != AlignmentLine.Unspecified
                val vb =
                    when {
                        textValue -> lineTop + lines.valueBaseline.intValue
                        lines.placedValueBaseline.intValue != AlignmentLine.Unspecified -> lines.placedValueBaseline.intValue
                        else -> {
                            val labelLine = if (termLineHeight.isSp) min(termLineHeight.roundToPx(), termTextHeight) else termTextHeight
                            lineTop + Alignment.CenterVertically.align(labelLine, lines.valueLineHeight.intValue) - termTextTop + tb
                        }
                    }
                // The label on the value's first baseline; a label whose baseline is lower than the
                // value's pushes the value down instead.
                val baseline = max(tb, vb)
                val vTop = baseline - vb
                val width = if (bounded) constraints.maxWidth else column + gapX + v.width
                layout(width, max(baseline - tb + t.height, vTop + v.height)) {
                    // The value goes first, so its baseline can be read once it and its content are
                    // placed (safe, unlike while measuring); zIndex keeps the label first for screen
                    // readers. A baseline that moved from what was measured lays the fact out again.
                    v.place(column + gapX, vTop, zIndex = 1f)
                    val placedVb =
                        if (textValue) {
                            vb
                        } else {
                            (v[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: tb).also { lines.placedValueBaseline.intValue = it }
                        }
                    t.place(0, (vTop + placedVb - tb).coerceAtLeast(0))
                }
            }
        }
    }
}

/**
 * What a side-by-side fact needs to line its label up with its value, written by the label's
 * and the value's own nodes as they are measured (so the fact reads no alignment line of a
 * subtree). States, so a baseline that moves without a size change still lays the fact out again.
 */
private class HorizontalFactLines {
    /** The label text's first baseline and height, within the text. */
    val termBaseline: MutableIntState = mutableIntStateOf(AlignmentLine.Unspecified)
    val termTextHeight: MutableIntState = mutableIntStateOf(0)

    /** A text value's first baseline within the text; unspecified for any other value. */
    val valueBaseline: MutableIntState = mutableIntStateOf(AlignmentLine.Unspecified)

    /** A value that isn't one text: its first baseline as read when it was last placed. */
    val placedValueBaseline: MutableIntState = mutableIntStateOf(AlignmentLine.Unspecified)

    /** An inline fact's own first baseline, for the row that lines facts up on it. */
    val factBaseline: MutableIntState = mutableIntStateOf(0)

    /** The value's first line: its top in the value and its height. */
    val valueLineTop: MutableIntState = mutableIntStateOf(0)
    val valueLineHeight: MutableIntState = mutableIntStateOf(0)
}

/**
 * Writes a text's first baseline (and height) as it is measured. A text node has no children, so
 * reading its own line is safe while measuring, unlike a subtree's (crash 69fdce17).
 */
private fun Modifier.textLineInto(baseline: MutableIntState, height: MutableIntState? = null): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        baseline.intValue = placeable[FirstBaseline]
        height?.intValue = placeable.height
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
