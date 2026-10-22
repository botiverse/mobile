package build.raft.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Metrics of [RaftBackButton]: the phone top bar's ghost `icon-lg` button. */
object RaftBackButtonDefaults {
    /** The touch target: raft-ui `icon-lg`, the 44dp minimum. */
    val Size: Dp = 44.dp

    /** The arrow, the `icon-lg` glyph size. */
    val IconSize: Dp = 22.dp

    /**
     * raft-ui 0.14 "ends of a header": the icon box, not the button, lands on the content
     * margin, 16dp from a phone's edge. The 44dp button centres its 22dp arrow 11dp in, so it
     * sits 5dp from the edge (was 3dp, which put the arrow 14dp in).
     */
    val EdgeInset: Dp = 5.dp
}

/**
 * raft-ui top-bar Back (and a leading Close): the phone top bar's ghost icon button
 * ([RaftIconButton] `variant = Ghost, size = Lg`), a 44dp target with a 22dp Lucide
 * `arrow-left`. Every page header, search bar, sheet and preview uses this one control;
 * do not draw a back arrow with a bare icon, a `clickable` box or a `RaftBrutal*` button.
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
    RaftIconButton(
        onClick = onClick,
        tooltip = label,
        modifier = modifier,
        variant = RaftButtonVariant.Ghost,
        size = RaftIconButtonSize.Lg,
        enabled = enabled,
    ) {
        RaftGlyphIcon(glyph, size = RaftBackButtonDefaults.IconSize, color = color)
    }
}
