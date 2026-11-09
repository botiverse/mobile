package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme

// raft-ui TextSelectionToolbar (raft-design packages/ui/src/components/text-selection-toolbar/
// text-selection-toolbar.tsx, text-selection-toolbar.recipe.ts, text-selection-toolbar.md;
// raft-ui 0.57.0). Built like the message hover toolbar (RaftMessageItemToolbar), so the two
// read as one kind of thing; its buttons have text labels.

/** raft-ui TextSelectionToolbar metrics. */
object RaftTextSelectionToolbarDefaults {
    /** The toolbar's height: 28dp buttons 4dp inside the frame, the hairline drawn inside. */
    val Height: Dp = 36.dp

    /** A button's height (`h-7`). */
    val ButtonHeight: Dp = 28.dp

    /** A button's side padding (`px-2.5`). */
    val ButtonPaddingX: Dp = 10.dp

    /** From a button's icon to its label (`gap-1.5`). */
    val IconGap: Dp = 6.dp

    /** A button's icon (`[&_svg]:size-4`). */
    val IconSize: Dp = 16.dp

    /** The separator's height (`h-5`); it is 1dp wide, with the toolbar's 4dp gap each side. */
    val SeparatorHeight: Dp = 20.dp

    /** From the selection's focus line to the toolbar (the web's Popover `sideOffset={8}`). */
    val AnchorGap: Dp = 8.dp
}

/**
 * raft-ui `TextSelectionToolbar`: the toolbar that floats over text someone selects, with
 * actions on that text such as Quote and Copy, in a message, an Artifact or any other text
 * people read. A 36dp card like [RaftMessageItemToolbar]'s (`layer-popover`, the container
 * radius, a `line-seam` hairline inside its edge, `shadow-float`) holding [content]:
 * [RaftTextSelectionToolbarButton]s, with a [RaftTextSelectionToolbarSeparator] between groups,
 * 4dp from the frame and 4dp apart.
 *
 * It only draws the card. The caller places it over the line where the selection's focus end
 * is (where the person let go, not the middle of the whole range), centred on it and
 * [RaftTextSelectionToolbarDefaults.AnchorGap] above it; below when there is no room, and
 * shifted along the line to stay inside the window (8dp from its edges).
 *
 * [label] names the toolbar for screen readers ("Text Selection Actions").
 */
@Composable
fun RaftTextSelectionToolbar(
    modifier: Modifier = Modifier,
    label: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    RaftFloatingToolbarFrame(modifier = modifier, label = label, content = content)
}

/**
 * raft-ui `TextSelectionToolbarButton`: one action on the selected text. 28dp tall with the
 * chip radius, 10dp each side, an optional 16dp [icon] in `foreground-muted` 6dp before the
 * [label] in `text-control` bold (13/18 on a phone, 14/22 on the desktop scale) in
 * `foreground`. A mouse hover and a press lay `fill-medium` inside its radius (a toolbar's
 * actions are buttons, not menu rows: no `brand-soft`); keyboard focus draws the focus ring.
 *
 * Write [label] in Title Case ("Quote", "Copy"). It never wraps (a Chinese label may break
 * between any two characters) and never shrinks: the toolbar grows to fit it. Disabled, the
 * label and icon grey and nothing hovers.
 */
@Composable
fun RaftTextSelectionToolbarButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    icon: (@Composable () -> Unit)? = null,
) {
    val colors = RaftTheme.colors
    val ink = if (enabled) colors.foreground else colors.foregroundDisabled
    val iconInk = if (enabled) colors.foregroundMuted else colors.foregroundDisabled
    val look =
        RaftButtonLook(
            fill = Color.Transparent,
            ink = ink,
            edge = Color.Transparent,
            outerRing = Color.Transparent,
            pressOverlay = if (enabled) colors.fillMedium else Color.Transparent,
        )
    val style = RaftTheme.typography.control.copy(fontWeight = FontWeight.Bold)
    // `rounded-chip`: RaftButtonBox takes the chip radius inside a frame.
    CompositionLocalProvider(LocalRaftEmbeddedControl provides true) {
        RaftButtonBox(
            look = look,
            onClick = onClick,
            modifier = modifier.height(RaftTextSelectionToolbarDefaults.ButtonHeight),
            enabled = enabled,
            loading = false,
            loadingLabel = "",
            interactionSource = interactionSource,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = RaftTextSelectionToolbarDefaults.ButtonPaddingX),
                horizontalArrangement = Arrangement.spacedBy(RaftTextSelectionToolbarDefaults.IconGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    CompositionLocalProvider(LocalRaftContentColor provides iconInk) {
                        Box(Modifier.size(RaftTextSelectionToolbarDefaults.IconSize), contentAlignment = Alignment.Center) { icon() }
                    }
                }
                RaftText(
                    text = label,
                    style = style,
                    color = ink,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

/** raft-ui `TextSelectionToolbarSeparator`: a 1 x 20dp `line-hairline` between groups of actions, the toolbar's 4dp gap each side. */
@Composable
fun RaftTextSelectionToolbarSeparator(modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(1.dp)
            .height(RaftTextSelectionToolbarDefaults.SeparatorHeight)
            .background(RaftTheme.colors.lineHairline),
    )
}
