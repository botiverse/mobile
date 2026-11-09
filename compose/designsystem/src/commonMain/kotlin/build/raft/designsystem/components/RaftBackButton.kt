package build.raft.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import build.raft.designsystem.theme.RaftControlMetrics
import build.raft.designsystem.theme.RaftTheme

/**
 * Metrics of [RaftBackButton]: the head's bar button ([RaftControlMetrics.barButtonSize]). With a
 * finger it is the phone top bar's ghost `icon-lg`; under a fine pointer the 28dp desktop bar
 * button (sizing v12, raft-ui 0.65 desktop density). Each follows the input in use, so read them
 * in composition.
 */
object RaftBackButtonDefaults {
    /** The target: raft-ui `icon-lg`, the 44dp minimum, with a finger; 28 under a fine pointer. */
    val Size: Dp
        @Composable @ReadOnlyComposable
        get() = RaftTheme.controls.barButtonSize

    /** The arrow: the `icon-lg` glyph size (22) with a finger; 16 under a fine pointer. */
    val IconSize: Dp
        @Composable @ReadOnlyComposable
        get() = RaftTheme.controls.barIconSize

    /**
     * raft-ui 0.14 "ends of a header": the icon box, not the button, lands on the content
     * margin, 16dp from a phone's edge. The 44dp button centres its 22dp arrow 11dp in, so it
     * sits 5dp from the edge (was 3dp, which put the arrow 14dp in). Under a fine pointer the
     * 28dp button centres its 16dp arrow 6dp in, so it sits 10dp from the edge (sizing v12).
     */
    val EdgeInset: Dp
        @Composable @ReadOnlyComposable
        get() = RaftTheme.controls.barEdgeInset
}

/**
 * raft-ui top-bar Back (and a leading Close): the head's bar button, with a finger the phone top
 * bar's ghost icon button ([RaftIconButton] `variant = Ghost, size = Lg`), a 44dp target with a
 * 22dp Lucide `arrow-left`; under a fine pointer the 28dp, radius-6 desktop bar button with a
 * 16dp arrow (sizing v12, raft-ui 0.65 desktop density). Every page header, search bar, sheet
 * and preview uses this one control; do not draw a back arrow with a bare icon, a `clickable`
 * box or a hand-drawn icon button.
 *
 * [label] is the accessible name and the long-press tooltip ("Back", or "Close" with
 * [glyph] = [RaftGlyph.X]). The arrow takes the button ink (`LocalRaftContentColor`,
 * `foreground`) unless [color] is given: pass a light ink on a dark overlay such as an
 * image preview. Place it [RaftBackButtonDefaults.EdgeInset] from the leading edge.
 */
@Composable
fun RaftBackButton(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    glyph: RaftGlyph = RaftGlyph.ArrowLeft,
    color: Color = Color.Unspecified,
    enabled: Boolean = true,
) {
    // The bar button's size and glyph, not `--control-height`: [RaftBackButtonDefaults.EdgeInset]
    // is worked out from this geometry, and callers place the button by it.
    val controls = RaftTheme.controls
    RaftSizedIconButton(
        onClick = onClick,
        tooltip = label,
        box = controls.barButtonSize,
        glyph = controls.barIconSize,
        modifier = modifier,
        variant = RaftButtonVariant.Ghost,
        enabled = enabled,
        cornerRadius = controls.barButtonRadius,
    ) {
        RaftGlyphIcon(glyph, size = controls.barIconSize, color = color)
    }
}
