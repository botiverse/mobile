package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.LocalRaftSurfaceColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius

/**
 * raft-ui 0.51 ModeBar (stable since #882; first shipped in 0.45 from `/wip`, #867): the dark
 * strip across a panel, directly under its header, while the panel is in a mode the person
 * turned on and turns off with one action, such as commenting on an Artifact ("Commenting ·
 * 1 draft." / "Select text to comment on it." / Done).
 *
 * - The HUD surface (`layer-hud`), not an intent tint: nothing is wrong, so it is not a
 *   [RaftPanelNotice]. No icon, and no seam under it (the dark fill is its edge).
 * - [RaftPanelNotice]'s edges and one-line height: 16dp from the panel's sides (raft-ui
 *   `max-md:px-4`, the phone size, as `RaftPanelNotice` uses), 10dp above and below the
 *   text, at least 40dp. Content and action 16dp apart.
 * - [title] (the mode and its state, with its period) in bold `foreground-on-hud`, then
 *   [description] (what to do in the mode) in `foreground-on-hud-muted` on the same line,
 *   after a space, so the two read and copy as one sentence. `text-callout` (14/20); the
 *   words wrap and take the width left. They are a polite live region, so a change to the
 *   title (the draft count) is read out; the action stays outside it.
 * - [actionLabel] / [onAction]: the one text action that ends the mode ("Done", never
 *   Cancel or ×), at the end of the first line. raft-ui `hudTextAction`, shared with the
 *   toast: bold `foreground-on-hud`, underlined only under a mouse or trackpad (touch never
 *   hovers), a `foreground-on-hud` 15% veil while pressed on a hit area 8dp past the word's
 *   sides and 12dp past its line (44dp tall), the HUD ink as its 2dp keyboard outline,
 *   2dp out. It takes touches 44dp round it.
 *
 * Show at most one in a panel, and remove it when the mode ends. Lays out with start / end,
 * so it mirrors right-to-left.
 */
@Composable
fun RaftModeBar(
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    val colors = RaftTheme.colors
    val typography = RaftTheme.typography
    val ink = colors.foregroundOnHud
    val muted = colors.foregroundOnHudMuted
    CompositionLocalProvider(LocalRaftContentColor provides ink, LocalRaftSurfaceColor provides colors.layerHud) {
        Row(
            modifier
                .fillMaxWidth()
                .background(colors.layerHud)
                .heightIn(min = 40.dp)
                .raftSafeAreaHorizontalPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // `ModeBarContent` (`role="status"`): `ModeBarTitle` bold, then `ModeBarDescription`
            // muted, which brings the space before it.
            val words =
                remember(title, description, muted) {
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(title) }
                        if (!description.isNullOrEmpty()) {
                            withStyle(SpanStyle(color = muted)) { append(" "); append(description) }
                        }
                    }
                }
            RaftText(
                words,
                Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                style = typography.callout,
                color = ink,
            )
            // At the end of the first line, however the words wrap.
            Box(Modifier.height(20.dp), contentAlignment = Alignment.Center) {
                RaftHudTextAction(actionLabel, onAction)
            }
        }
    }
}

/**
 * raft-ui `lib/hud-text-action` (a toast's action, a mode bar's Done): a text action on the
 * dark HUD. Bold `text-callout` `foreground-on-hud`; `hover:underline` (1.5px, 3px under the
 * baseline on the web; the font's own underline here, as [RaftToast]); the pressed veil
 * `active:after:bg-foreground-on-hud/15` on `after:-inset-x-2 after:-inset-y-3
 * after:rounded-control`; `focus-visible:keyboard:outline-2 outline-foreground-on-hud
 * outline-offset-2`. A button's fill and focus ring would not read on the dark surface.
 */
@Composable
internal fun RaftHudTextAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = RaftTheme.colors
    val ink = colors.foregroundOnHud
    val source = remember { MutableInteractionSource() }
    val pressed = raftIsPressed(source)
    val hovered = raftIsHovered(source)
    val focused = raftIsFocused(source)
    val veil = ink.copy(alpha = 0.15f)
    RaftText(
        label,
        modifier
            .drawBehind {
                val dx = 8.dp.toPx()
                val dy = 12.dp.toPx()
                if (pressed) {
                    drawRoundRect(
                        veil,
                        topLeft = Offset(-dx, -dy),
                        size = Size(size.width + 2 * dx, size.height + 2 * dy),
                        cornerRadius = CornerRadius(RaftRadius.Control.toPx()),
                    )
                }
                if (focused) {
                    val w = 2.dp.toPx()
                    // `outline-offset-2`: the 2dp outline starts 2dp outside the box.
                    val o = 2.dp.toPx() + w / 2f
                    drawRect(ink, topLeft = Offset(-o, -o), size = Size(size.width + 2 * o, size.height + 2 * o), style = Stroke(w))
                }
            }
            // The word draws at its size; touches 44dp round it count ([raftTouchTarget]).
            .raftTouchTarget(enabled = true, interactionSource = source, onClick = onClick)
            .raftPointerHover(source)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick),
        style =
            RaftTheme.typography.callout.copy(
                fontWeight = FontWeight.Bold,
                textDecoration = if (hovered) TextDecoration.Underline else null,
            ),
        color = ink,
        maxLines = 1,
    )
}
