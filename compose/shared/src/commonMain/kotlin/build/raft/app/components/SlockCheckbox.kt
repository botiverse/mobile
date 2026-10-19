package build.raft.app.components

import build.raft.app.isAmbient
import build.raft.app.LocalSlockTheme
import build.raft.app.SlockColor
import build.raft.app.SlockLucideIcon
import build.raft.app.SlockLucideIconName
import build.raft.app.SlockRadius
import build.raft.app.SlockStroke
import build.raft.app.slockArgbHex
import build.raft.app.SlockThemeFamily
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.shape.RoundedCornerShape
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp

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
internal fun SlockCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 16.dp
) {
    val disabledAlpha = if (enabled) 1f else 0.50f
    val checkedFillColor = if (!enabled && checked) {
        SlockColor.AccentStone
    } else {
        SlockColor.TextBlack.copy(alpha = disabledAlpha)
    }
    val borderColor = if (!enabled && checked) {
        checkedFillColor
    } else {
        SlockColor.BorderBlack.copy(alpha = disabledAlpha)
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
    val theme = LocalSlockTheme.current
    if (theme.isAmbient) {
        val boxSize = if (size < SlockRaftMetrics.CheckboxSize) SlockRaftMetrics.CheckboxSize else size
        val shape = RoundedCornerShape(SlockRadius.Sticker)
        val fill =
            when {
                checked && enabled -> SlockColor.TextBlack
                checked -> SlockColor.ForegroundDisabled
                enabled -> SlockColor.Card
                else -> SlockColor.FillMuted
            }
        val edge =
            when {
                !enabled -> SlockColor.ForegroundDisabled
                checked -> SlockColor.TextBlack
                else -> SlockColor.BorderBlack
            }
        val checkInk = if (enabled) SlockColor.ForegroundInverse else SlockColor.Card
        Box(
            modifier = clickableModifier
                .size(boxSize)
                .background(fill, shape)
                .border(SlockStroke.Control, edge, shape),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                SlockLucideIcon(
                    SlockLucideIconName.Check,
                    modifier = Modifier.size((boxSize.value * 0.7f).dp),
                    color = slockArgbHex(checkInk),
                    strokeWidth = 2f
                )
            }
        }
        return
    }
    Box(
        modifier = clickableModifier
            .size(size)
            .border(SlockStroke.Default, borderColor)
            .background(
                when {
                    checked -> checkedFillColor
                    else -> SlockColor.SurfaceWhite
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            SlockLucideIcon(
                SlockLucideIconName.Check,
                modifier = Modifier.size((size.value * 0.7f).dp),
                color = "#FFFFFFFF",
                strokeWidth = 4f
            )
        }
    }
}
