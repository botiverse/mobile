package build.raft.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius

/** raft-ui tooltip metrics (tooltip.tsx). */
internal object RaftTooltipMetrics {
    /** The arrow's triangle (`M1 0H11L6 5.25Z` in its 12 x 6 svg). */
    val CaretSize = DpSize(10.dp, 5.25.dp)

    /** `sideOffset` 8: anchor edge to bubble edge; the caret hangs into this gap. */
    val AnchorSpacing = 8.dp

    /**
     * `px-2.5`. Material's PlainTooltip already pads 8dp each side (and 4dp top and
     * bottom, the `py-1`); this is the remaining 2dp.
     */
    val ExtraPaddingX = 2.dp
}

/**
 * Opens every [RaftTooltip] in the subtree, persistent (screenshot fixtures only: an
 * offscreen frame can neither hover nor hold a long press).
 */
internal val LocalRaftTooltipPreview = staticCompositionLocalOf { false }

/**
 * raft-ui 0.8.0 Tooltip around [content]: a short label in the dark HUD bubble with a
 * small caret pointing at [content], above it (below when there is no room above).
 *
 * - Look (tooltip.tsx): `layer-hud` fill, `foreground-on-hud` type, `radius-chip` (6dp),
 *   10 x 4dp padding, `text-footnote` 13/18 in medium weight, `shadow-float`, a 10 x 5.25dp
 *   caret, 8dp from the anchor.
 * - Shown by a long press (touch), or while a mouse / trackpad pointer hovers [content];
 *   it dismisses itself shortly after. A long press that opens it does not also click.
 * - Not focusable: it never takes focus or swallows the next tap. Screen readers get the
 *   label from [content] itself (see [RaftIconButton], which passes it as the content
 *   description), not from the bubble.
 *
 * Material3 [TooltipBox] / [PlainTooltip] does the positioning and gestures; the bubble's
 * width is capped at Material's 200dp (raft-ui allows 320dp): keep labels short.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RaftTooltip(
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val preview = LocalRaftTooltipPreview.current
    val colors = RaftTheme.colors
    // TooltipBox puts [modifier] on an inner box, where parent-data modifiers (`align`,
    // `weight`) would be lost: the caller's modifier goes on this outer box instead.
    Box(modifier, propagateMinConstraints = true) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(RaftTooltipMetrics.AnchorSpacing),
            tooltip = {
                PlainTooltip(
                    modifier = Modifier.raftFloatShadow(RaftTheme.isDark, RaftRadius.Chip),
                    caretSize = RaftTooltipMetrics.CaretSize,
                    shape = RoundedCornerShape(RaftRadius.Chip),
                    contentColor = colors.foregroundOnHud,
                    containerColor = colors.layerHud,
                ) {
                    RaftText(
                        text = text,
                        modifier = Modifier.padding(horizontal = RaftTooltipMetrics.ExtraPaddingX),
                        style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.Medium),
                        color = colors.foregroundOnHud,
                    )
                }
            },
            state = rememberTooltipState(initialIsVisible = preview, isPersistent = preview),
            focusable = false,
            content = content,
        )
    }
}
