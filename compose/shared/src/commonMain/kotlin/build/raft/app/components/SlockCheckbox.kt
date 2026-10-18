package build.raft.app.components

import build.raft.app.isAmbient
import build.raft.app.LocalSlockTheme
import build.raft.app.SlockColor
import build.raft.app.SlockLucideIcon
import build.raft.app.SlockLucideIconName
import build.raft.app.SlockStroke
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
    // Elegant register: a softly-rounded control — real 4dp rounded corners + 1px #CACACE hairline
    // (unchecked) and a #0A0A0A elegant-ink fill (checked) with the white Check icon, vs the brutal
    // square black-border / black-fill below. Disabled fades via the shared disabledAlpha. The
    // borderRadius-prop path does not round at runtime, so the rounded shape is applied straight to
    // background/border, mirroring the elegant button/card/input. The brutal branch is unchanged.
    val theme = LocalSlockTheme.current
    if (theme.isAmbient) {
        val shape = RoundedCornerShape(4.dp)
        val elegantCheckedFill = theme.ink.copy(alpha = disabledAlpha)
        val elegantBorderColor = if (checked) elegantCheckedFill else theme.line.copy(alpha = disabledAlpha)
        Box(
            modifier = clickableModifier
                .size(size)
                .background(if (checked) elegantCheckedFill else theme.surface, shape)
                .border(theme.lineWidth, elegantBorderColor, shape),
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
