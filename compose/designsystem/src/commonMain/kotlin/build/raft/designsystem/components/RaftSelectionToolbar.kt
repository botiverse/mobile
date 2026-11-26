package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftSurfaceColor
import build.raft.designsystem.theme.RaftControlMetrics
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftSpacing
import build.raft.designsystem.tokens.RaftStroke

// raft-ui SelectionToolbar (raft-design packages/ui/src/components/selection-toolbar/
// selection-toolbar.recipe.ts; raft-ui 0.101): the floating toolbar for selected items (messages,
// tasks). raft-ui 0.101 removed MessageMultiSelectToolbar: selected messages use this one, the
// same toolbar as selected tasks.
//
// Ported at the touch values (the recipe's base classes, not its `md:pointer-fine:` ones), which
// is what a phone and a touch screen draw: icon-only 44dp bar buttons, the label only the
// accessible name and the tooltip. The metrics read the theme's control metrics, so under a fine
// pointer they take its head and bar-button sizes; the web's labelled `md` buttons and its
// `gap-1` are not ported. Its fit is: actions move into More as the row narrows, in the web's
// order (raftSelectionToolbarFit).

/**
 * raft-ui SelectionToolbar metrics (selection-toolbar.recipe.ts), from the theme's control
 * metrics ([RaftControlMetrics]); read them in composition. Touch values in the docs.
 */
object RaftSelectionToolbarDefaults {
    /** The toolbar's height, a head's (`h-(--header-height)`): 56dp. */
    val Height: Dp
        @Composable @ReadOnlyComposable
        get() = RaftTheme.controls.headHeight

    /**
     * From the card's edges to the first and last control (`px-(--header-air)`, `py-0`): the
     * header air, `(--header-height - --control-height) / 2`, 6dp.
     */
    val PaddingX: Dp
        @Composable @ReadOnlyComposable
        get() = RaftTheme.controls.let { (it.headHeight - it.height) / 2 }

    /** An action's, More's and ×'s square (`size-(--bar-button-size)`): the head's 44dp bar button. */
    val ButtonSize: Dp
        @Composable @ReadOnlyComposable
        get() = RaftTheme.controls.barButtonSize

    /** Their corners (`rounded-(--bar-button-radius)`): 10dp, 6 under a fine pointer. */
    val ButtonRadius: Dp
        @Composable @ReadOnlyComposable
        get() = RaftTheme.controls.barButtonRadius

    /** An action's and More's icon (`size-control-icon` by touch): a head's bar-button icon, 22dp. */
    val IconSize: Dp
        @Composable @ReadOnlyComposable
        get() = RaftTheme.controls.barIconSize

    /** ×'s icon (`size-control-icon` at every pointer): `--control-icon-size`, 22dp. */
    val CloseIconSize: Dp
        @Composable @ReadOnlyComposable
        get() = RaftTheme.controls.iconSize

    /** Before a leading checkbox (`ml-[calc((var(--control-height)-var(--control-icon-size))/2)]`): 11dp. */
    val LeadingInset: Dp
        @Composable @ReadOnlyComposable
        get() = RaftTheme.controls.let { (it.height - it.iconSize) / 2 }

    /** Before the count (`pl-2`); none after it (`pr-0`), and none between the actions (`gap-0`). */
    val CountPaddingStart: Dp = RaftSpacing.Sm

    /** After the hint (`pr-1`). */
    val HintPaddingEnd: Dp = RaftSpacing.Xs

    /** SelectionToolbarDock: from the sides of what it floats over (`px-5`). */
    val EdgeInset: Dp = 20.dp

    /** SelectionToolbarDock: above its bottom (`pb-[calc(--spacing(3)+env(safe-area-inset-bottom))]`), plus the safe area. */
    val BottomOffset: Dp = 12.dp
}

/**
 * raft-ui `SelectionToolbarDock`: where [RaftSelectionToolbar] floats, centred over the bottom of
 * the list or conversation it sits in, [RaftSelectionToolbarDefaults.EdgeInset] from its sides
 * (inside the horizontal safe area) and [RaftSelectionToolbarDefaults.BottomOffset] plus
 * [bottomSafeArea] above its bottom. Nothing behind the toolbar: no full-width bar, no divider.
 * It fills its width; put it at the bottom of the conversation, over or in place of the composer.
 */
@Composable
fun RaftSelectionToolbarDock(
    modifier: Modifier = Modifier,
    bottomSafeArea: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val d = RaftSelectionToolbarDefaults
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .raftSafeAreaHorizontalPadding()
                .padding(start = d.EdgeInset, end = d.EdgeInset, bottom = d.BottomOffset + bottomSafeArea),
        contentAlignment = Alignment.BottomCenter,
        content = content,
    )
}

/**
 * One action on the selection for [RaftSelectionToolbar]: drawn as a [RaftSelectionToolbarAction]
 * while it fits the row, else handed to the toolbar's More menu. [id] tells the caller which one
 * a More menu item is. [secondary] actions give way into More first (raft-ui
 * `SelectionToolbarAction secondary`).
 */
@Immutable
class RaftSelectionToolbarActionSpec(
    val id: String,
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val secondary: Boolean = false,
    val open: Boolean = false,
    val modifier: Modifier = Modifier,
    val icon: @Composable () -> Unit,
)

/**
 * Which actions (one [secondary] flag each) give way into More when the row has [room] px: the web's fit
 * order (raft-ui SelectionToolbar: the hint first, then the secondary actions into More, the last
 * first, then the others, the last first; the count and × never). By touch the labels are already
 * hidden, so the web's label step changes nothing here. [fixed] is everything that always shows
 * (the padding, the leading control, the count, ×); every action and More are [button] px wide.
 * Returns whether the hint shows and the indices of the folded actions, in their order.
 */
internal fun raftSelectionToolbarFit(
    room: Int,
    fixed: Int,
    hint: Int,
    button: Int,
    secondary: List<Boolean>,
): Pair<Boolean, List<Int>> {
    val all = secondary.indices.toList()
    if (fixed + hint + button * all.size <= room) return true to emptyList()
    if (fixed + button * all.size <= room) return false to emptyList()
    // The order the actions move into More: the secondary ones from the end, then the others from the end.
    val order = all.filter { secondary[it] }.reversed() + all.filter { !secondary[it] }.reversed()
    val folded = mutableListOf<Int>()
    for (index in order) {
        folded += index
        val shown = all.size - folded.size
        if (fixed + button * (shown + 1) <= room) break
    }
    return false to folded.sorted()
}

/**
 * raft-ui `SelectionToolbar`: the floating toolbar for selected items (messages, tasks). One row,
 * as wide as its content, a head tall ([RaftSelectionToolbarDefaults.Height], 56dp by touch) on a
 * card: `layer-popover`, the container radius, a 1dp `line-seam` ring round its edge (`ring-1`,
 * outside it), `shadow-float`. [RaftSelectionToolbarDefaults.PaddingX] (the header air, 6dp) at
 * each end, nothing above or below, and nothing between its parts (`gap-0`):
 *
 * - [leading], optional: a thread's select-all [RaftCheckbox] (raft-ui `SelectionToolbarItem`),
 *   as far from the frame as ×'s icon is at the other end: the air plus half of what a button
 *   holds around its icon ([RaftSelectionToolbarDefaults.LeadingInset], raft-ui 0.102.12), 17dp
 *   by touch and 13 under a fine pointer. Its touch target reaches 44dp around it.
 * - [count] ("3 selected", `SelectionToolbarCount`): `text-callout` bold `foreground` with
 *   tabular figures, 8dp after what precedes it, nothing after it, one line, announced politely
 *   when it changes. [countValue] is the number inside it (`SelectionToolbarCountValue`), drawn
 *   in Space Mono; its first occurrence in [count] is.
 * - [hint], optional (`SelectionToolbarHint`, "1 will be skipped"): `text-caption`
 *   `foreground-hint`, 4dp before what follows. The web sets it flush after the count; here
 *   8dp separate them.
 * - [actions], each a [RaftSelectionToolbarAction] (a 44dp icon-only bar button), in order.
 * - [more], only while some actions are out of the row: a [RaftSelectionToolbarMore] whose menu
 *   (opening upward) holds the folded actions it is given, in their order.
 * - [close], last: a [RaftSelectionToolbarClose].
 *
 * One row at any width, never wrapping: measured against the width it floats in (the dock's
 * room, its max width constraint), what doesn't fit gives way in the web's order
 * ([raftSelectionToolbarFit]): the hint first, then the secondary actions into More (the last
 * first), then the others (the last first). The count and × never give way. More shows only then.
 *
 * Place it with [RaftSelectionToolbarDock]. [label] names the toolbar for screen readers
 * ("Selected message actions").
 */
@Composable
fun RaftSelectionToolbar(
    count: String,
    actions: List<RaftSelectionToolbarActionSpec>,
    more: @Composable (folded: List<RaftSelectionToolbarActionSpec>) -> Unit,
    close: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    countValue: String? = null,
    hint: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val d = RaftSelectionToolbarDefaults
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val radius = RaftRadius.Container
    val fill = colors.layerPopover
    val ring = colors.lineSeam
    val toolbarHeight = d.Height
    val paddingX = d.PaddingX
    val leadingInset = d.LeadingInset
    val buttonSize = d.ButtonSize
    val countStyle = typography.callout.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
    val value = countValue.orEmpty()
    val valueAt = if (value.isEmpty()) -1 else count.indexOf(value)
    val monoFamily = typography.monoMeta.fontFamily
    val countText =
        remember(count, value, valueAt, monoFamily) {
            buildAnnotatedString {
                if (valueAt < 0) {
                    append(count)
                } else {
                    append(count.substring(0, valueAt))
                    withStyle(SpanStyle(fontFamily = monoFamily)) { append(value) }
                    append(count.substring(valueAt + value.length))
                }
            }
        }
    CompositionLocalProvider(LocalRaftSurfaceColor provides fill) {
        SubcomposeLayout(
            modifier =
                modifier
                    .raftFloatShadow(RaftTheme.isDark, radius)
                    // `ring-1`: a box-shadow ring outside the edge, under the content.
                    .drawBehind {
                        val w = RaftStroke.Hairline.toPx()
                        drawRoundRect(
                            color = ring,
                            topLeft = Offset(-w / 2f, -w / 2f),
                            size = Size(size.width + w, size.height + w),
                            cornerRadius = CornerRadius(radius.toPx() + w / 2f),
                            style = Stroke(width = w),
                        )
                    }
                    .background(fill, RoundedCornerShape(radius))
                    .semantics {
                        isTraversalGroup = true
                        if (label != null) contentDescription = label
                    }
                    .height(toolbarHeight),
        ) { constraints ->
            val loose = Constraints(maxHeight = constraints.maxHeight)
            val pad = paddingX.roundToPx()
            val button = buttonSize.roundToPx()
            val leadingPlaceables =
                if (leading == null) {
                    emptyList()
                } else {
                    subcompose(SelectionToolbarSlot.Leading) {
                        Box(Modifier.padding(start = leadingInset)) { leading() }
                    }.map { it.measure(loose) }
                }
            val countPlaceables =
                subcompose(SelectionToolbarSlot.Count) {
                    RaftText(
                        text = countText,
                        style = countStyle,
                        color = colors.foreground,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        modifier =
                            Modifier
                                .padding(start = d.CountPaddingStart)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }.map { it.measure(loose) }
            val hintPlaceables =
                if (hint == null) {
                    emptyList()
                } else {
                    subcompose(SelectionToolbarSlot.Hint) {
                        RaftText(
                            text = hint,
                            style = typography.caption,
                            color = colors.foregroundHint,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.padding(start = d.CountPaddingStart, end = d.HintPaddingEnd),
                        )
                    }.map { it.measure(loose) }
                }
            val closePlaceables = subcompose(SelectionToolbarSlot.Close, close).map { it.measure(loose) }
            val fixed =
                pad * 2 + leadingPlaceables.sumOf { it.width } + countPlaceables.sumOf { it.width } +
                    closePlaceables.sumOf { it.width }
            val (hintShown, foldedIndices) =
                raftSelectionToolbarFit(
                    room = constraints.maxWidth,
                    fixed = fixed,
                    hint = hintPlaceables.sumOf { it.width },
                    button = button,
                    secondary = actions.map { it.secondary },
                )
            val shown = actions.filterIndexed { index, _ -> index !in foldedIndices }
            val folded = foldedIndices.map { actions[it] }
            val actionPlaceables =
                subcompose(SelectionToolbarSlot.Actions) {
                    shown.forEach { action ->
                        key(action.id) {
                            RaftSelectionToolbarAction(
                                label = action.label,
                                onClick = action.onClick,
                                modifier = action.modifier,
                                enabled = action.enabled,
                                open = action.open,
                                icon = action.icon,
                            )
                        }
                    }
                }.map { it.measure(loose) }
            val morePlaceables =
                if (folded.isEmpty()) emptyList() else subcompose(SelectionToolbarSlot.More) { more(folded) }.map { it.measure(loose) }
            val row =
                leadingPlaceables + countPlaceables + (if (hintShown) hintPlaceables else emptyList()) +
                    actionPlaceables + morePlaceables + closePlaceables
            val width = constraints.constrainWidth(pad * 2 + row.sumOf { it.width })
            val rowHeight = constraints.constrainHeight(row.maxOfOrNull { it.height } ?: 0)
            layout(width, rowHeight) {
                var x = pad
                row.forEach { placeable ->
                    placeable.placeRelative(x, (rowHeight - placeable.height) / 2)
                    x += placeable.width
                }
            }
        }
    }
}

private enum class SelectionToolbarSlot { Leading, Count, Hint, Actions, More, Close }

/**
 * raft-ui `SelectionToolbarAction` by touch: one action on the selection, a ghost bar button
 * ([RaftSelectionToolbarDefaults.ButtonSize] square, 44dp, `--bar-button-radius` corners) showing
 * its [icon] alone ([RaftSelectionToolbarDefaults.IconSize], 22dp, in the button's ink). [label]
 * is its accessible name and its tooltip (under a mouse or a long press). Every action looks
 * the same: the web gives none of them a primary look. [open] keeps the press overlay while a
 * menu it opened shows.
 */
@Composable
fun RaftSelectionToolbarAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    open: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
    icon: @Composable () -> Unit,
) {
    RaftSelectionToolbarBarButton(
        onClick = onClick,
        tooltip = label,
        contentDescription = label,
        glyph = RaftSelectionToolbarDefaults.IconSize,
        modifier = modifier,
        enabled = enabled,
        open = open,
        interactionSource = interactionSource,
        icon = icon,
    )
}

/**
 * raft-ui `SelectionToolbarMore`: More (⋯), after the actions, the bar button that opens the menu
 * of the actions not in the row; open that menu upward. [label] is its accessible name ("More
 * actions") and [tooltip] its tooltip. Same metrics as [RaftSelectionToolbarAction]; [icon]
 * defaults to Lucide `ellipsis`.
 */
@Composable
fun RaftSelectionToolbarMore(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    tooltip: String = label,
    enabled: Boolean = true,
    open: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
    icon: @Composable () -> Unit = { RaftGlyphIcon(RaftGlyph.Ellipsis, size = RaftSelectionToolbarDefaults.IconSize) },
) {
    RaftSelectionToolbarBarButton(
        onClick = onClick,
        tooltip = tooltip,
        contentDescription = label,
        glyph = RaftSelectionToolbarDefaults.IconSize,
        modifier = modifier,
        enabled = enabled,
        open = open,
        interactionSource = interactionSource,
        icon = icon,
    )
}

/**
 * raft-ui `SelectionToolbarClose`: ×, last, ends the selection (as Back and Escape do). The same
 * bar button with a [RaftSelectionToolbarDefaults.CloseIconSize] (`--control-icon-size`, 22dp)
 * Lucide `x`. [label] is its accessible name and tooltip ("Cancel").
 */
@Composable
fun RaftSelectionToolbarClose(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
) {
    val glyph = RaftSelectionToolbarDefaults.CloseIconSize
    RaftSelectionToolbarBarButton(
        onClick = onClick,
        tooltip = label,
        contentDescription = label,
        glyph = glyph,
        modifier = modifier,
        enabled = true,
        open = false,
        interactionSource = interactionSource,
    ) {
        RaftGlyphIcon(RaftGlyph.X, size = glyph)
    }
}

@Composable
private fun RaftSelectionToolbarBarButton(
    onClick: () -> Unit,
    tooltip: String,
    contentDescription: String,
    glyph: Dp,
    modifier: Modifier,
    enabled: Boolean,
    open: Boolean,
    interactionSource: MutableInteractionSource?,
    icon: @Composable () -> Unit,
) {
    val d = RaftSelectionToolbarDefaults
    // Not an embedded control: the bar button keeps its own corners, not `rounded-chip`.
    CompositionLocalProvider(LocalRaftEmbeddedControl provides false) {
        RaftSizedIconButton(
            onClick = onClick,
            tooltip = tooltip,
            box = d.ButtonSize,
            glyph = glyph,
            modifier = modifier,
            variant = RaftButtonVariant.Ghost,
            enabled = enabled,
            interactionSource = interactionSource,
            contentDescription = contentDescription,
            open = open && enabled,
            cornerRadius = d.ButtonRadius,
            icon = icon,
        )
    }
}
