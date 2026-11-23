package build.raft.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.LocalRaftStrings
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
 * raft-ui top-bar Back: the head's bar button, with a finger the phone top bar's ghost icon
 * button ([RaftIconButton] `variant = Ghost, size = Lg`), a 44dp target with a 22dp Lucide
 * `arrow-left`; under a fine pointer the 28dp, radius-6 desktop bar button with a 16dp arrow
 * (sizing v12, raft-ui 0.65 desktop density). Every page header, search bar, sheet and preview
 * uses this one control; do not draw a back arrow with a bare icon, a `clickable` box or a
 * hand-drawn icon button. It is always the arrow: a head's × is [RaftCloseButton].
 *
 * [label] is the accessible name and the long-press tooltip ("Back"). The arrow takes the button
 * ink (`LocalRaftContentColor`, `foreground`) unless [color] is given: pass a light ink on a dark
 * overlay such as an image preview. Place it [RaftBackButtonDefaults.EdgeInset] from the leading
 * edge.
 */
@Composable
fun RaftBackButton(
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    enabled: Boolean = true,
) {
    RaftBarGlyphButton(onClick, label, RaftGlyph.ArrowLeft, modifier, color, enabled)
}

/** Which of raft-ui's two × a [RaftCloseButton] is. */
enum class RaftCloseButtonSize {
    /**
     * The head's bar button, as [RaftBackButton]: raft-ui `PanelAction` with `<X />` (a panel's,
     * a column's, a pushed page's head; the × at the start of a phone sheet's head, DialogSheet's
     * `DialogClose`). 44dp with a 22dp × with a finger, 28dp with a 16dp × under a fine pointer.
     */
    Bar,

    /**
     * raft-ui `DialogClose` (slock CloseButton): the ghost `icon-md`, 32dp (the row height under a
     * fine pointer) with an 18dp ×, in the head of a dialog card ([RaftDialogHeader]), a bottom
     * sheet and a side drawer (one close control for those overlays, see the README).
     */
    Dialog,
}

/**
 * The one × of a head (raft-design panel.md "Close placement", navigation.md "Closing"; web
 * PanelHeader, ThreadPanelHeader, ChannelSideCard, ArtifactViewer, ProfilePanel, DialogCard,
 * raft-ui DialogClose and DrawerClose). Every head that can be closed draws its × with this,
 * never with an icon button of its own (`RaftCloseButtonRulesTest` fails on one), so a close looks
 * and sits the same everywhere:
 *
 * - **Where:** the last control of the head, at its outer (end) edge, after the head's own
 *   actions, its ⋯ and its Expand: a side panel beside the main area (the reply thread, the
 *   profile and channel details column, the Artifact column, the Tasks and Files cards, the
 *   Tasks page's task), a dialog card, a bottom sheet, a side drawer. Its glyph lands on the
 *   content margin and its hit area hangs past it, mirroring Back at the start.
 * - **The one exception:** a phone sheet ([RaftDialogSheet], a dialog drawn as a sheet below
 *   768dp) has it at the start, where a pushed page has Back, and its commit at the end.
 * - **Never with Back for the same exit:** a panel that covers the page (a phone, a narrow
 *   window) and a full-screen page have [RaftBackButton] at the start and no ×. Inside a side
 *   panel ‹ shows only over a page pushed in that panel, and × still closes the panel.
 * - **Named** [label] ("Close" by default, [build.raft.designsystem.theme.RaftStrings.close]),
 *   which is also its tooltip (hover, long press).
 *
 * [size]: [RaftCloseButtonSize.Bar] in a panel's or page's head and a phone sheet's head;
 * [RaftCloseButtonSize.Dialog] in a dialog card's, a bottom sheet's and a side drawer's head. The
 * glyph takes the button ink unless [color] is given (a light ink on a dark overlay).
 */
@Composable
fun RaftCloseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = LocalRaftStrings.current.close,
    size: RaftCloseButtonSize = RaftCloseButtonSize.Bar,
    enabled: Boolean = true,
    color: Color = Color.Unspecified,
) {
    when (size) {
        RaftCloseButtonSize.Bar -> RaftBarGlyphButton(onClick, label, RaftGlyph.X, modifier, color, enabled)
        RaftCloseButtonSize.Dialog ->
            RaftIconButton(onClick = onClick, tooltip = label, modifier = modifier, variant = RaftButtonVariant.Ghost, enabled = enabled) {
                RaftGlyphIcon(RaftGlyph.X, size = RaftCloseButtonDialogGlyph, color = color)
            }
    }
}

/** raft-ui DialogClose's `size-4.5` ×. */
private val RaftCloseButtonDialogGlyph = 18.dp

/** Back and the bar-sized Close: the head's bar button holding [glyph]. */
@Composable
private fun RaftBarGlyphButton(
    onClick: () -> Unit,
    label: String,
    glyph: RaftGlyph,
    modifier: Modifier,
    color: Color,
    enabled: Boolean,
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
