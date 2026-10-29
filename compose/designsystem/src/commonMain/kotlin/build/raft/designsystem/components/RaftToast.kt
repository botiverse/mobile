package build.raft.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.LocalRaftContentColor
import build.raft.designsystem.theme.LocalRaftSurfaceColor
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftUiThemeColors

/** Which surface a [RaftToast] floats on. */
enum class RaftToastSurface {
    /** raft-ui Toast: the dark HUD, for the receipt of something the person just did. */
    Hud,

    /**
     * The app's foreground in-app banner (page hand-off §11, `RaftForegroundBannerHost`):
     * a message from elsewhere, on `layer-popover` with the `line-seam` ring.
     */
    Popover,
}

/** raft-ui Toast `intent` and its one icon (`TOAST_ICON`); the toast itself stays neutral. */
enum class RaftToastIntent(internal val glyph: RaftGlyph) {
    Info(RaftGlyph.Info),
    Success(RaftGlyph.CircleCheck),
    Warning(RaftGlyph.TriangleAlert),
    Error(RaftGlyph.TriangleAlert),
}

@Immutable
internal data class RaftToastLook(val fill: Color, val ring: Color, val ink: Color, val muted: Color)

/** HUD: `layer-hud`, `foreground-on-hud` (muted for the description), no ring. Never tinted. */
internal fun raftToastLook(surface: RaftToastSurface, colors: RaftUiThemeColors): RaftToastLook =
    when (surface) {
        RaftToastSurface.Hud -> RaftToastLook(colors.layerHud, Color.Transparent, colors.foregroundOnHud, colors.foregroundOnHudMuted)
        RaftToastSurface.Popover -> RaftToastLook(colors.layerPopover, colors.lineSeam, colors.foreground, colors.foregroundMuted)
    }

/**
 * raft-ui 0.8.0 Toast, the surface only: one sentence saying who did what and the result
 * ("Wug finished reviewing #25."), floating with `shadow-float`, container radius, at
 * most 384dp wide. 16 / 12dp padding, 12dp gaps, `text-callout` regular.
 *
 * - [intent]: leads with the intent's icon (raft-ui 0.22, #709): 16dp, in the toast's ink, in
 *   the 20dp column, centred on the first line; never a status light.
 * - [icon]: raft-ui `leading`, a 20dp slot for who did it (an avatar); replaces the
 *   [intent]'s icon.
 * - [description]: more detail in the muted ink.
 * - [actionLabel] / [onAction]: one bold text action ("View", "Undo"), never a button;
 *   underlined only under a mouse or trackpad and while pressed (raft-ui
 *   `not-disabled:hover:underline`); it takes touches 44dp round it.
 * - Title only: at least 44 x 112dp, the text centred.
 *
 * Neutral by rule: no intent tint and never yellow. The host (one toast at a time, the
 * timeout, swipe to dismiss, bottom-centre placement) is not part of this module yet.
 */
@Composable
fun RaftToast(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    surface: RaftToastSurface = RaftToastSurface.Hud,
    intent: RaftToastIntent? = null,
    icon: (@Composable () -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val look = raftToastLook(surface, RaftTheme.colors)
    val typography = RaftTheme.typography
    val leading: (@Composable () -> Unit)? =
        icon ?: intent?.let { { RaftGlyphIcon(it.glyph, size = 16.dp, color = look.ink) } }
    val titleOnly = leading == null && description == null && actionLabel == null
    CompositionLocalProvider(LocalRaftContentColor provides look.ink, LocalRaftSurfaceColor provides look.fill) {
        Row(
            modifier
                .widthIn(max = 384.dp)
                .defaultMinSize(minWidth = if (titleOnly) 112.dp else 0.dp, minHeight = if (titleOnly) 44.dp else 0.dp)
                .raftOverlaySurface(look.fill, look.ring, RaftTheme.isDark, RaftRadius.Container)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, if (titleOnly) Alignment.CenterHorizontally else Alignment.Start),
            verticalAlignment = if (description != null) Alignment.Top else Alignment.CenterVertically,
        ) {
            // `ToastIcon`: `w-5 h-lh`, on the first line however the title wraps.
            if (leading != null) Box(Modifier.size(20.dp).align(Alignment.Top), contentAlignment = Alignment.Center) { leading() }
            val hasAction = actionLabel != null && onAction != null
            // The text gives way to the action, but the toast still hugs its content.
            Column(if (hasAction) Modifier.weight(1f, fill = false) else Modifier) {
                RaftText(
                    title,
                    style = typography.callout,
                    color = look.ink,
                    maxLines = if (description == null) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = if (titleOnly) TextAlign.Center else null,
                )
                if (description != null) RaftText(description, style = typography.callout, color = look.muted)
            }
            if (hasAction) {
                val source = remember { MutableInteractionSource() }
                // `not-disabled:hover:underline`: clickable text, underlined under a mouse or
                // trackpad, and while pressed (touch has no hover); none at rest.
                val isPressed = raftIsPressed(source)
                val isHovered = raftIsHovered(source)
                val underlined = isPressed || isHovered
                RaftText(
                    actionLabel!!,
                    // The word draws at its size; touches 44dp round it count ([raftTouchTarget]).
                    Modifier
                        .raftTouchTarget(enabled = true, interactionSource = source, onClick = onAction!!)
                        .raftPointerHover(source)
                        .clickable(source, indication = null, role = Role.Button, onClick = onAction),
                    style =
                        typography.callout.copy(
                            fontWeight = FontWeight.Bold,
                            textDecoration = if (underlined) TextDecoration.Underline else null,
                        ),
                    color = look.ink,
                    maxLines = 1,
                )
            }
        }
    }
}
