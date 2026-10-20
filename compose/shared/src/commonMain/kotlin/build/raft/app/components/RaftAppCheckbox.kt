package build.raft.app.components

import build.raft.app.isAmbient
import build.raft.app.LocalRaftTheme
import build.raft.app.RaftColor
import build.raft.app.RaftLucideIcon
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftAppRadius
import build.raft.app.RaftAppStroke
import build.raft.app.raftArgbHex
import build.raft.app.RaftThemeFamily
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Unified brutal-style checkbox used by `SharedCreateAgentDialog` (Fast mode),
 * `ThreadComposerPage` (As Task toggle), and any other checkbox surface.
 *
 * Visual contract:
 * - Box size [size] (default 16dp), 2dp black border, white fill when unchecked,
 *   black fill with white `Check` lucide icon when checked.
 * - Clicking the box toggles via [onCheckedChange] when [enabled].
 * - Disabled state: 2dp border, lighter background, no fill change on click.
 *
 * Mirrors the `ComposerAsTaskToggle` pattern that previously lived as a
 * private Composable in `ThreadComposerPage.kt`. The previous yellow-fill /
 * ✓-unicode variant in `SharedCreateAgentDialog.kt` was the only consumer
 * using a different visual style — we standardize on the black-fill / Check
 * icon style here so every checkbox in the app shares one look.
 */
@Composable
internal fun RaftAppCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 16.dp
) {
    val disabledAlpha = if (enabled) 1f else 0.50f
    val checkedFillColor = if (!enabled && checked) {
        RaftColor.AccentStone
    } else {
        RaftColor.TextBlack.copy(alpha = disabledAlpha)
    }
    val borderColor = if (!enabled && checked) {
        checkedFillColor
    } else {
        RaftColor.BorderBlack.copy(alpha = disabledAlpha)
    }
    val clickableModifier = if (enabled) {
        modifier.clickable { onCheckedChange(!checked) }
    } else {
        modifier
    }
    // raft-ui 0.6.0 Checkbox (`checkbox-indicator` recipe): a 20dp sticker-radius (4dp)
    // box with a 1.5dp `--line-strong` edge on the card layer; checked = `--foreground`
    // fill + edge with a 14dp `--foreground-inverse` check; disabled = `--foreground-disabled`
    // edge on `--fill-muted`, and disabled+checked fills with `--foreground-disabled` and a
    // card-coloured check. Opaque colours, no alpha fades. Smaller legacy sizes grow to 20dp.
    val theme = LocalRaftTheme.current
    if (theme.isAmbient) {
        val boxSize = if (size < RaftMetrics.CheckboxSize) RaftMetrics.CheckboxSize else size
        val shape = RoundedCornerShape(RaftAppRadius.Sticker)
        val fill =
            when {
                checked && enabled -> RaftColor.TextBlack
                checked -> RaftColor.ForegroundDisabled
                enabled -> RaftColor.Card
                else -> RaftColor.FillMuted
            }
        val edge =
            when {
                !enabled -> RaftColor.ForegroundDisabled
                checked -> RaftColor.TextBlack
                else -> RaftColor.BorderBlack
            }
        val checkInk = if (enabled) RaftColor.ForegroundInverse else RaftColor.Card
        Box(
            modifier = clickableModifier
                .size(boxSize)
                .background(fill, shape)
                .border(RaftAppStroke.Control, edge, shape),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                RaftLucideIcon(
                    RaftLucideIconName.Check,
                    modifier = Modifier.size((boxSize.value * 0.7f).dp),
                    color = raftArgbHex(checkInk),
                    strokeWidth = 2f
                )
            }
        }
        return
    }
    Box(
        modifier = clickableModifier
            .size(size)
            .border(RaftAppStroke.Default, borderColor)
            .background(
                when {
                    checked -> checkedFillColor
                    else -> RaftColor.SurfaceWhite
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            RaftLucideIcon(
                RaftLucideIconName.Check,
                modifier = Modifier.size((size.value * 0.7f).dp),
                color = "#FFFFFFFF",
                strokeWidth = 4f
            )
        }
    }
}
