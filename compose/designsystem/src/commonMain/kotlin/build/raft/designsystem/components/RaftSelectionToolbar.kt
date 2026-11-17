package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
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
// pointer they take its head and bar-button sizes; the web's labelled `md` buttons, its `gap-1`
// and its fit-toolbar (actions moving into More as the row narrows) are not ported.

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

    /** Their corners (`rounded-(--bar-button-radius)`): 8dp. */
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
 * raft-ui `SelectionToolbar`: the floating toolbar for selected items (messages, tasks). One row,
 * as wide as its content, a head tall ([RaftSelectionToolbarDefaults.Height], 56dp by touch) on a
 * card: `layer-popover`, the container radius, a 1dp `line-seam` ring round its edge (`ring-1`,
 * outside it), `shadow-float`. [RaftSelectionToolbarDefaults.PaddingX] (the header air, 6dp) at
 * each end, nothing above or below, and nothing between its parts (`gap-0`):
 *
 * - [leading], optional: a thread's select-all [RaftCheckbox] (raft-ui `SelectionToolbarItem`),
 *   centred in a bar button's square so it lines up with the buttons and keeps a 44dp target.
 * - [count] ("3 selected", `SelectionToolbarCount`): `text-callout` bold `foreground` with
 *   tabular figures, 8dp after what precedes it, nothing after it, one line, announced politely
 *   when it changes. [countValue] is the number inside it (`SelectionToolbarCountValue`), drawn
 *   in Space Mono; its first occurrence in [count] is.
 * - [hint], optional (`SelectionToolbarHint`, "1 will be skipped"): `text-caption`
 *   `foreground-hint`, 4dp before what follows. The web sets it flush after the count; here
 *   8dp separate them.
 * - [actions], in order: a [RaftSelectionToolbarAction] per action, then
 *   [RaftSelectionToolbarMore] where some live in a menu, then [RaftSelectionToolbarClose] last.
 *
 * Place it with [RaftSelectionToolbarDock], no wider than the dock's room. [label] names the
 * toolbar for screen readers ("Selected message actions").
 */
@Composable
fun RaftSelectionToolbar(
    count: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    countValue: String? = null,
    hint: String? = null,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit,
) {
    val d = RaftSelectionToolbarDefaults
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val radius = RaftRadius.Container
    val fill = colors.layerPopover
    val ring = colors.lineSeam
    Row(
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
                .height(d.Height)
                .padding(PaddingValues(horizontal = d.PaddingX)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalRaftSurfaceColor provides fill) {
            if (leading != null) {
                Box(Modifier.size(d.ButtonSize), contentAlignment = Alignment.Center) { leading() }
            }
            val countStyle = typography.callout.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
            val value = countValue.orEmpty()
            val valueAt = if (value.isEmpty()) -1 else count.indexOf(value)
            val countText =
                buildAnnotatedString {
                    if (valueAt < 0) {
                        append(count)
                    } else {
                        append(count.substring(0, valueAt))
                        withStyle(SpanStyle(fontFamily = typography.monoMeta.fontFamily)) { append(value) }
                        append(count.substring(valueAt + value.length))
                    }
                }
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
            if (hint != null) {
                RaftText(
                    text = hint,
                    style = typography.caption,
                    color = colors.foregroundHint,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.padding(start = d.CountPaddingStart, end = d.HintPaddingEnd),
                )
            }
            actions()
        }
    }
}

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
