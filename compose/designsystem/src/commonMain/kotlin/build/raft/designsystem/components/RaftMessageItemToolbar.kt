package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.LocalRaftMisuse
import build.raft.designsystem.theme.LocalRaftSurfaceColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke

// raft-ui MessageItem > hover toolbar (raft-design packages/ui/src/components/message-item/
// message-item-toolbar.tsx `MessageItemToolbar` / `MessageItemToolbarButton`,
// message-item.recipe.ts `toolbar` / `toolbarButton` / `root`, message-item.md "The hover
// toolbar"; raft-ui 0.57.0). The small card of icon buttons that floats over the top right
// corner of the message under the pointer, and the row fill under it.

/** raft-ui MessageItem metrics for the hover toolbar and the row it floats over. */
object RaftMessageItemToolbarDefaults {
    /** The toolbar's height: 28dp buttons 4dp inside the frame, the hairline drawn inside (message-item.md). */
    val Height: Dp = 36.dp

    /** A button's square (`size-7`). Not a Button size, and not on the desktop scale: the web's is 28px everywhere. */
    val ButtonSize: Dp = 28.dp

    /** A button's icon (`[&_svg]:size-4`). */
    val IconSize: Dp = 16.dp

    /** From the frame to the buttons (`p-1`), and between buttons (`gap-1`). */
    val Padding: Dp = 4.dp
    val Gap: Dp = 4.dp

    /**
     * Where the caller puts it on the row (`absolute top-[-18px] right-3`): its top 18dp above
     * the top edge of the row's highlight (half its height, so it rides that edge), its end
     * 12dp in from the row's end (4dp inside the header's end, the row having `px-2`).
     * On a group's first row the highlight starts under the 8dp turn gap, so the toolbar
     * reaches only 10dp past the row's box.
     */
    val TopOffset: Dp = (-18).dp
    val EndInset: Dp = 12.dp

    /** Room to leave above the first message of a scroll area and a thread's root, so the toolbar is never clipped. */
    val TopRoom: Dp = 18.dp

    /**
     * While the toolbar shows, the row's header fades out its end under it: transparent from
     * the toolbar's width plus [HeaderMaskGap] (8dp: the 4dp it sits inside the header's end
     * and a 4dp gap), opaque [HeaderMaskFade] (12dp) further in. Nothing on the row moves
     * or keeps room for it.
     */
    val HeaderMaskGap: Dp = 8.dp
    val HeaderMaskFade: Dp = 12.dp

    /** Five buttons' width, which the web fades by until the toolbar has measured itself. */
    val FallbackWidth: Dp = 164.dp
}

/**
 * raft-ui MessageItem row fill (message-item.recipe.ts `root`, `--mi-row-fill`; message-item.md
 * "States"). A message has no press: hovered by a mouse or trackpad, or while one of its
 * popups is open (its context menu, the toolbar's ⋯, a long press on a phone), the row lays
 * the subtle tier, `fill-subtle`, the same for both so they never stack. A search hit or a
 * checked message (multi-select) keeps `info-soft`, hover or not. A located row keeps its own
 * highlight and does not answer hover while it lasts: pass `hovered = false` meanwhile.
 *
 * The fill covers the row's padding box (`bg-clip-padding`): not the transparent 8dp turn gap
 * above a group's first row.
 */
object RaftMessageItemDefaults {
    /** The row under a mouse or trackpad pointer: `fill-subtle`. */
    val HoverFill: Color
        @Composable get() = RaftTheme.colors.fillSubtle

    /** The row while one of its popups is open: `fill-subtle`, the same tier as [HoverFill]. */
    val PopupOpenFill: Color
        @Composable get() = RaftTheme.colors.fillSubtle

    /** A search hit or a checked message: `info-soft`, also on hover. */
    val HighlightFill: Color
        @Composable get() = RaftTheme.colors.infoSoft

    /** The row's fill for its state: [HighlightFill], else [HoverFill] when hovered or a popup is open, else none. */
    @Composable
    fun rowFill(hovered: Boolean, popupOpen: Boolean = false, highlighted: Boolean = false): Color =
        when {
            highlighted -> HighlightFill
            hovered || popupOpen -> HoverFill
            else -> Color.Transparent
        }
}

/**
 * The floating frame the hover toolbar and the text-selection toolbar share (raft-ui
 * `rounded-container p-1 gap-1 bg-layer-popover ring-1 ring-line-seam ring-inset
 * shadow-float`): `shadow-float` under a `layer-popover` fill with the container radius,
 * the 1dp `line-seam` hairline inside the box (so the box is the visible edge), and no clip,
 * so a button's focus ring is not cut. Provides `layer-popover` as [LocalRaftSurfaceColor].
 */
@Composable
internal fun RaftFloatingToolbarFrame(
    modifier: Modifier,
    label: String?,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    val radius = RaftRadius.Container
    val fill = colors.layerPopover
    val ring = colors.lineSeam
    Row(
        modifier =
            modifier
                .raftFloatShadow(RaftTheme.isDark, radius)
                .background(fill, RoundedCornerShape(radius))
                // `ring-inset`: under the content (an inset box-shadow), not over it as `border` draws.
                .drawBehind {
                    val w = RaftStroke.Hairline.toPx()
                    drawRoundRect(
                        color = ring,
                        topLeft = Offset(w / 2f, w / 2f),
                        size = Size(size.width - w, size.height - w),
                        cornerRadius = CornerRadius(radius.toPx() - w / 2f),
                        style = Stroke(width = w),
                    )
                }
                .semantics {
                    isTraversalGroup = true
                    if (label != null) contentDescription = label
                }
                .padding(RaftMessageItemToolbarDefaults.Padding),
        horizontalArrangement = Arrangement.spacedBy(RaftMessageItemToolbarDefaults.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalRaftSurfaceColor provides fill) { content() }
    }
}

/**
 * raft-ui `MessageItemToolbar`: the hover toolbar of a message on a wide screen. A 36dp card
 * (`layer-popover`, the container radius, a `line-seam` hairline inside its edge,
 * `shadow-float`) holding [content], its [RaftMessageItemToolbarButton]s 4dp from the frame
 * and 4dp apart, such as Add Reaction, Reply in Thread, Forward, Save and ⋯.
 *
 * It only draws the card. The caller shows it while a mouse or trackpad hovers the row, while
 * focus is inside the row and while one of its popups is open, and places it over the row's
 * top right corner ([RaftMessageItemToolbarDefaults.TopOffset], `EndInset`) without making
 * room for it: hover never moves the message. The row's fill is [RaftMessageItemDefaults].
 * Touch screens show no hover toolbar.
 *
 * [label] names the group for screen readers ("Message Actions").
 */
@Composable
fun RaftMessageItemToolbar(
    modifier: Modifier = Modifier,
    label: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    RaftFloatingToolbarFrame(modifier = modifier, label = label, content = content)
}

/**
 * raft-ui `MessageItemToolbarButton`: one action on the hover toolbar, not a [RaftIconButton].
 * A 28dp square with the chip radius (a control embedded in the toolbar's frame) and a 16dp
 * [icon] in `foreground-muted`; [active] (`data-active`, such as Save on a saved message)
 * draws it in `foreground`. A mouse hover and a press lay `fill-medium` inside its radius,
 * and so does [open] (`data-popup-open`): pass it while the menu or picker it opened is
 * showing, so the button stays marked as the one it came from. Keyboard focus draws the focus
 * ring. 28dp everywhere: the toolbar keeps its size on the desktop scale.
 *
 * [tooltip] names the action in Title Case ("Add Reaction", "Forward", "Save", "More
 * Actions"): the [RaftTooltip] under a mouse hover and the button's screen-reader name.
 * Disabled, the icon greys and nothing hovers.
 */
@Composable
fun RaftMessageItemToolbarButton(
    onClick: () -> Unit,
    tooltip: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    open: Boolean = false,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    icon: @Composable () -> Unit,
) {
    val colors = RaftTheme.colors
    val misuse = LocalRaftMisuse.current
    if (tooltip.isBlank()) misuse("RaftMessageItemToolbarButton needs a tooltip")
    val look =
        RaftButtonLook(
            fill = Color.Transparent,
            ink =
                when {
                    !enabled -> colors.foregroundDisabled
                    active -> colors.foreground
                    else -> colors.foregroundMuted
                },
            edge = Color.Transparent,
            outerRing = Color.Transparent,
            pressOverlay = if (enabled) colors.fillMedium else Color.Transparent,
        )
    RaftTooltip(text = tooltip, modifier = modifier) {
        // `rounded-chip`: RaftButtonBox takes the chip radius inside a frame.
        CompositionLocalProvider(LocalRaftEmbeddedControl provides true) {
            RaftButtonBox(
                look = look,
                onClick = onClick,
                modifier =
                    Modifier
                        .size(RaftMessageItemToolbarDefaults.ButtonSize)
                        .semantics { contentDescription = tooltip },
                enabled = enabled,
                loading = false,
                loadingLabel = "",
                interactionSource = interactionSource,
                open = open && enabled,
            ) {
                Box(Modifier.size(RaftMessageItemToolbarDefaults.IconSize), contentAlignment = Alignment.Center) { icon() }
            }
        }
    }
}
