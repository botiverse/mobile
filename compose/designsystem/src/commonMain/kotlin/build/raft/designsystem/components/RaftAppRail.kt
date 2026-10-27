package build.raft.designsystem.components

import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.theme.RaftThemeDecorSlot
import build.raft.designsystem.theme.raftThemeDecor
import build.raft.designsystem.theme.raftThemeDecorShown
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke
import build.raft.designsystem.tokens.RaftUiPalette

// raft-ui AppRail (packages/ui/src/components/app-rail): the leftmost column of a wide layout,
// on the frame, the destinations at the top and the tools at the bottom. Mirrors the parts:
// - AppRailRoot + AppRailHeader + AppRailNav + AppRailFooter -> RaftAppRail (its slots)
// - AppRailItem (+ ItemIcon, ItemLabel, ItemAttention)       -> RaftAppRailItem
// The app supplies the destinations, the header tile and what each one opens.

/** raft-ui AppRail metrics (icons-only variant, the one the product ships). */
object RaftAppRailDefaults {
    /** The rail, without its 1dp `line-seam` (raft-ui: 56px plus the seam). */
    val Width: Dp = 56.dp

    /** The header square; its seam lines up with the sidebar's and the panels' headers. */
    val HeaderHeight: Dp = 56.dp

    /** One destination's square (`size-10`). */
    val ItemSize: Dp = 40.dp

    /** Between items, in the nav and the footer (`gap-1.5`). */
    val ItemGap: Dp = 6.dp

    /** From both ends of the rail, and under the header's seam (`py-2`). */
    val Inset: Dp = 8.dp

    /** The icon inside an item (`[&_svg]:size-5`). */
    val IconSize: Dp = 20.dp
}

/**
 * raft-ui `AppRailRoot`: a [RaftAppRailDefaults.Width] column on `layer-frame` (the brand yellow
 * in light mode) with a full-height `line-seam` on its right. [header] (the workspace tile, in a
 * 56dp square under [topInset] with its own seam) is left out when something above the rail
 * holds the workspace. [content] is the destinations ([RaftAppRailItem]s, 6dp apart, 8dp under
 * the header's seam); [footer] pins the tools (settings, help) to the bottom, 8dp above
 * [bottomInset]. A decorated theme paints its pixel art under the destinations, just above the
 * footer ([RaftThemeDecorSlot.Rail], raft-ui `frame-decor-rail`).
 */
@Composable
fun RaftAppRail(
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = RaftTheme.colors
    Row(modifier.fillMaxHeight()) {
        Column(
            Modifier
                .fillMaxHeight()
                .background(colors.layerFrame)
                // On the island's side in landscape the frame grows to the screen edge and the
                // items keep their 56dp column past the safe area.
                .raftSafeAreaHorizontalPadding(WindowInsetsSides.Start)
                .width(RaftAppRailDefaults.Width),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CompositionLocalProvider(LocalRaftContentColor provides colors.foregroundOnFrame) {
                if (header != null) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = topInset)
                            .height(RaftAppRailDefaults.HeaderHeight - RaftStroke.Hairline),
                        contentAlignment = Alignment.Center,
                    ) { header() }
                    Box(Modifier.fillMaxWidth().height(RaftStroke.Hairline).background(colors.lineSeam))
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = RaftAppRailDefaults.Inset + if (header == null) topInset else 0.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(RaftAppRailDefaults.ItemGap),
                ) {
                    content()
                    RaftAppRailDecor()
                }
                if (footer != null) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = RaftAppRailDefaults.Inset, bottom = RaftAppRailDefaults.Inset + bottomInset),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(RaftAppRailDefaults.ItemGap),
                        content = footer,
                    )
                }
            }
        }
        Box(Modifier.width(RaftStroke.Hairline).fillMaxHeight().background(colors.lineSeam))
    }
}

/**
 * raft-ui AppRail nav's `after:` strip (`mt-auto h-28 min-h-0 w-12 shrink frame-decor-rail`): the
 * theme's 48x112 pixel art as the nav's last item, at its foot, in whatever room the
 * destinations leave (one item gap after the last), cut from the top when that is less than the
 * art. Nothing at all (no item, no gap) without decor to draw.
 */
@Composable
private fun ColumnScope.RaftAppRailDecor() {
    if (!raftThemeDecorShown(RaftTheme.themeId, RaftTheme.isDark, RaftTheme.decor, RaftThemeDecorSlot.Rail)) return
    Box(Modifier.fillMaxWidth().weight(1f).raftThemeDecor(RaftThemeDecorSlot.Rail))
}

/**
 * raft-ui `AppRailItem`: one destination, a 40dp square on the control radius holding its 20dp
 * [icon] (drawn in the `ink` it is given). Selected, it is the sticker on the frame:
 * `layer-frame-active` (white in light mode, yellow in dark) with a 1.5dp `line-frame-active`
 * edge and the die-cut `sticker-edge` ring; a press lays `fill-frame-medium` over an unselected
 * one. [attention] puts the pink pixel (`Status sm`, 8dp) on the icon's top-right corner; the
 * selected item never shows it. Screen readers read [label], plus [attentionDescription] while
 * the pixel shows ("Activity, new"). A rail never shows a count (raft-ui Badge rules).
 *
 * The touch target reaches 44dp around the 40dp square, over the 6dp gaps.
 */
@Composable
fun RaftAppRailItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable (ink: Color) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    attention: Boolean = false,
    attentionDescription: String? = null,
) {
    val colors = RaftTheme.colors
    val source = remember { MutableInteractionSource() }
    val pressed = raftIsPressed(source) && enabled && !selected
    val shape = RoundedCornerShape(RaftRadius.Control)
    val edge = colors.stickerEdge
    val ink =
        when {
            !enabled -> colors.foregroundDisabled
            selected -> colors.foregroundOnFrameActive
            else -> colors.foregroundOnFrame
        }
    val showsAttention = attention && !selected
    Box(
        modifier
            .size(RaftAppRailDefaults.ItemSize)
            .raftTouchTarget(enabled, source, onClick)
            .then(
                if (selected) {
                    Modifier.drawBehind {
                        // The die-cut ring outside the sticker: transparent in light, paper in dark.
                        if (edge.alpha > 0f) {
                            val w = RaftStroke.Control.toPx()
                            drawRoundRect(
                                color = edge,
                                topLeft = Offset(-w / 2f, -w / 2f),
                                size = Size(size.width + w, size.height + w),
                                cornerRadius = CornerRadius(RaftRadius.Control.toPx() + w / 2f),
                                style = Stroke(w),
                            )
                        }
                    }
                } else {
                    Modifier
                },
            )
            .background(
                when {
                    selected -> colors.layerFrameActive
                    pressed -> colors.fillFrameMedium
                    else -> Color.Transparent
                },
                shape,
            )
            .border(RaftStroke.Control, if (selected) colors.lineFrameActive else Color.Transparent, shape)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription =
                    if (showsAttention && attentionDescription != null) "$label, $attentionDescription" else label
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(RaftAppRailDefaults.IconSize), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalRaftContentColor provides ink) { icon(ink) }
        }
        if (showsAttention) {
            // raft-ui AppRailItemAttention: the `attention` pixel, `-top-1 -right-1`.
            RaftStatusDot(
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp),
                size = RaftStatusSize.Sm,
                color = RaftUiPalette.attention,
            )
        }
    }
}
