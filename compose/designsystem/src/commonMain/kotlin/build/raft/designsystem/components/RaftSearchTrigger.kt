package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke

/** Metrics of [RaftSearchTrigger] (raft-ui `SearchTrigger`). */
object RaftSearchTriggerDefaults {
    val Height: Dp = 36.dp

    /** `w-[clamp(360px,32vw,520px)]`. */
    val MinWidth: Dp = 360.dp
    val MaxWidth: Dp = 520.dp
    const val WindowFraction: Float = 0.32f

    internal val PaddingX: Dp = 12.dp
    internal val Gap: Dp = 8.dp
    internal val GlyphSize: Dp = 18.dp

    /** The trigger's width in a window [windowWidth] wide: clamp(360, 32% of it, 520). */
    fun width(windowWidth: Dp): Dp = (windowWidth * WindowFraction).coerceIn(MinWidth, MaxWidth)
}

/**
 * raft-ui `SearchTrigger`, the web top bar's search box: a button drawn as a field. It is
 * [RaftSearchTriggerDefaults.Height] tall with the field's 1.5dp `line-field` edge on
 * `layer-card`, an 18dp search glyph, [label] (the placeholder, "Search") in `foreground-hint`,
 * and an optional [shortcut] (raft-ui `Kbd`) at its end. It opens search rather than taking
 * input. Like every field it doesn't hover; a press lays the medium overlay (`fill-pressed`).
 *
 * Its width is [RaftSearchTriggerDefaults.width] of [windowWidth] (raft-ui
 * `clamp(360px, 32vw, 520px)`), never more than the room it is given.
 */
@Composable
fun RaftSearchTrigger(
    label: String,
    onClick: () -> Unit,
    windowWidth: Dp,
    modifier: Modifier = Modifier,
    shortcut: String? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val colors = RaftTheme.colors
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed = raftIsPressed(source)
    val focused = raftIsFocused(source)
    val shape = RoundedCornerShape(RaftRadius.Control)
    Box(
        modifier
            .width(RaftSearchTriggerDefaults.width(windowWidth))
            .height(RaftSearchTriggerDefaults.Height)
            .raftFocusRing(focused, RaftRadius.Control, colors)
            .background(colors.layerCard, shape)
            .raftPressOverlay(pressed, raftPressOverlayColor(RaftPressSurface.Paper, colors), RaftRadius.Control)
            .border(RaftStroke.Control, colors.lineField, shape)
            .clickable(source, indication = null, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                this.onClick(label = null) { onClick(); true }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.padding(horizontal = RaftSearchTriggerDefaults.PaddingX),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RaftGlyphIcon(RaftGlyph.Search, size = RaftSearchTriggerDefaults.GlyphSize, color = colors.foregroundHint)
            Spacer(Modifier.width(RaftSearchTriggerDefaults.Gap))
            RaftText(
                text = label,
                style = RaftTheme.typography.body,
                color = colors.foregroundHint,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (shortcut != null) {
                Spacer(Modifier.width(RaftSearchTriggerDefaults.Gap))
                RaftKbd(shortcut)
            }
        }
    }
}

/**
 * raft-ui `Kbd`: a keyboard shortcut, `text-mono-meta` in `foreground-hint` inside a 1dp
 * `line-hairline` ring on a 3dp radius, at least 16dp wide, 4dp each side.
 */
@Composable
fun RaftKbd(text: String, modifier: Modifier = Modifier) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier
            .widthIn(min = 16.dp)
            .border(RaftStroke.Hairline, colors.lineHairline, shape)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        RaftText(text = text, style = RaftTheme.typography.monoMeta, color = colors.foregroundHint, maxLines = 1, softWrap = false)
    }
}
