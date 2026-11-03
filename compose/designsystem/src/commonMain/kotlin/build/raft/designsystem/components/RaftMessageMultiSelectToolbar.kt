package build.raft.designsystem.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftStroke

// raft-ui MessageMultiSelectToolbar / MessageMultiSelectToolbarActions (raft-design
// packages/ui/src/components/message-item/message-multi-select.recipe.ts `toolbar` /
// `toolbarActions`; raft-ui 0.58). The toolbar for selected messages: the same floating card
// as the hover toolbar (RaftMessageItemToolbar), centred over the bottom of the conversation
// in the composer's place while messages are selected.

/** raft-ui MessageMultiSelectToolbar metrics, and where the web's SelectModeToolbar places it. */
object RaftMessageMultiSelectToolbarDefaults {
    /** The toolbar's height: 28dp buttons 4dp inside the frame (`py-1`), the hairline drawn inside. */
    val Height: Dp = 36.dp

    /** A button's height and an icon button's square (`**:data-[slot=button]:h-7 min-w-7`). */
    val ButtonSize: Dp = 28.dp

    /** An icon button's icon (Button `icon-md`, `size-[18px]`). */
    val IconButtonGlyph: Dp = 18.dp

    /** A text button's icon (Button `md`, `size-4`), 8dp (`gap-2`) before its label. */
    val TextButtonIcon: Dp = 16.dp
    val TextButtonIconGap: Dp = 8.dp

    /** A text button's side padding (Button `md` `px-3`), inside its 1.5dp border. */
    val TextButtonPaddingX: Dp = 12.dp

    /** From the frame to the count (`pl-2.5`), and to the last button (`pr-1`, `py-1`). */
    val PaddingStart: Dp = 10.dp
    val PaddingEnd: Dp = 4.dp
    val PaddingY: Dp = 4.dp

    /** From the count to the actions (`gap-2`), and between the actions (`gap-1`). */
    val CountGap: Dp = 8.dp
    val ActionGap: Dp = 4.dp

    /**
     * Where the web's SelectModeToolbar floats it: 20dp from the conversation's sides (the
     * composer's edge, `px-5`) and 12dp above its bottom, plus the bottom safe area.
     */
    val EdgeInset: Dp = 20.dp
    val BottomOffset: Dp = 12.dp
}

/**
 * raft-ui `MessageMultiSelectToolbar`: the toolbar for messages someone has selected (to
 * forward, copy or share them). A 36dp card like the hover toolbar's (`layer-popover`, the
 * container radius, a `line-seam` hairline inside its edge, `shadow-float`), as wide as its
 * content: [count] ("2 selected", `text-control` bold `foreground`, one line, announced
 * politely when it changes) 10dp in from its start, then 8dp later the [actions] 4dp apart and
 * 4dp from its end: [RaftMessageMultiSelectToolbarButton]s and
 * [RaftMessageMultiSelectToolbarIconButton]s, usually Copy Link · Forward (the one primary) ·
 * More · × (Cancel, last).
 *
 * It only draws the card. The caller centres it over the bottom of the conversation in the
 * composer's place, [RaftMessageMultiSelectToolbarDefaults.EdgeInset] from the sides and
 * `BottomOffset` (plus the safe area) above the bottom, no wider than that room.
 *
 * [label] names the toolbar for screen readers ("Selected message actions").
 */
@Composable
fun RaftMessageMultiSelectToolbar(
    count: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    actions: @Composable RowScope.() -> Unit,
) {
    val d = RaftMessageMultiSelectToolbarDefaults
    RaftFloatingToolbarFrame(
        modifier = modifier,
        label = label,
        contentPadding = PaddingValues(start = d.PaddingStart, top = d.PaddingY, end = d.PaddingEnd, bottom = d.PaddingY),
        gap = d.CountGap,
    ) {
        RaftText(
            text = count,
            style = RaftTheme.typography.control.copy(fontWeight = FontWeight.Bold),
            color = RaftTheme.colors.foreground,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(d.ActionGap, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // `rounded-chip`: the buttons are embedded in the toolbar's frame.
            CompositionLocalProvider(LocalRaftEmbeddedControl provides true) { actions() }
        }
    }
}

/**
 * A text action on [RaftMessageMultiSelectToolbar]: a raft-ui Button `md` the toolbar makes
 * 28dp tall with the chip radius ([variant] [RaftButtonVariant.Primary] for Forward, the one
 * primary action; [RaftButtonVariant.Ghost] for the rest), 12dp each side inside its 1.5dp
 * border, an optional 16dp [icon] 8dp before the [label] in `text-control` bold. Its touch
 * target reaches 44dp tall without changing its layout.
 */
@Composable
fun RaftMessageMultiSelectToolbarButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: RaftButtonVariant = RaftButtonVariant.Ghost,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    icon: (@Composable () -> Unit)? = null,
) {
    val d = RaftMessageMultiSelectToolbarDefaults
    val look = raftButtonLook(variant, enabled, frameless = false, colors = RaftTheme.colors)
    val style = raftButtonTextStyle(RaftButtonSize.Md, variant, RaftTheme.typography)
    CompositionLocalProvider(LocalRaftEmbeddedControl provides true) {
        RaftButtonBox(
            look = look,
            onClick = onClick,
            modifier = modifier.height(d.ButtonSize),
            enabled = enabled,
            loading = false,
            loadingLabel = "",
            interactionSource = interactionSource,
            widenTouchTarget = true,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = d.TextButtonPaddingX + RaftStroke.Control),
                horizontalArrangement = Arrangement.spacedBy(d.TextButtonIconGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Box(Modifier.size(d.TextButtonIcon), contentAlignment = Alignment.Center) { icon() }
                }
                RaftText(
                    text = label,
                    style = style,
                    color = look.ink,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

/**
 * An icon action on [RaftMessageMultiSelectToolbar]: a raft-ui ghost Button `icon-md` the
 * toolbar makes a 28dp square with the chip radius and an 18dp [icon] in `foreground`
 * (Copy Link, More, ×). [tooltip] names it (its tooltip under a mouse or a long press);
 * [contentDescription] is its screen-reader name when that says more ("More selected message
 * actions"). [open] keeps the press overlay while the menu it opened shows. Its touch target
 * reaches 44dp without changing its layout.
 */
@Composable
fun RaftMessageMultiSelectToolbarIconButton(
    onClick: () -> Unit,
    tooltip: String,
    modifier: Modifier = Modifier,
    contentDescription: String = tooltip,
    enabled: Boolean = true,
    open: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
    icon: @Composable () -> Unit,
) {
    val d = RaftMessageMultiSelectToolbarDefaults
    CompositionLocalProvider(LocalRaftEmbeddedControl provides true) {
        RaftSizedIconButton(
            onClick = onClick,
            tooltip = tooltip,
            box = d.ButtonSize,
            glyph = d.IconButtonGlyph,
            modifier = modifier,
            variant = RaftButtonVariant.Ghost,
            enabled = enabled,
            interactionSource = interactionSource,
            contentDescription = contentDescription,
            open = open && enabled,
            icon = icon,
        )
    }
}
