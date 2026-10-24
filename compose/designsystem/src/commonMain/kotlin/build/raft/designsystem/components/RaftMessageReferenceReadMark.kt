package build.raft.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftUiPalette

/**
 * raft-ui `MessageReferenceReadMark` (raft-design #794, raft-ui 0.34.0, message-item.md "Read
 * receipts on a mention"): the read receipt on a mention of an agent. A bare Lucide `check`, 10dp,
 * stroke 2.5 on the 24 grid, with no light and no box, in [RaftMessageReferenceReadMarkDefaults.color].
 * Draw it only once the agent has read the message; until then nothing is drawn.
 *
 * It is decorative (no semantics): the caller names the mention "Read" for a screen reader.
 */
@Composable
fun RaftMessageReferenceReadMark(modifier: Modifier = Modifier) {
    RaftGlyphIcon(
        glyph = RaftGlyph.Check,
        modifier = modifier,
        size = RaftMessageReferenceReadMarkDefaults.Size,
        color = RaftMessageReferenceReadMarkDefaults.color,
        strokeWidth = RaftMessageReferenceReadMarkDefaults.StrokeWidth,
    )
}

object RaftMessageReferenceReadMarkDefaults {
    /** The check's box. */
    val Size: Dp = 10.dp

    /** Lucide stroke width on the 24 grid. */
    const val StrokeWidth: Float = 2.5f

    /** Clear space between the name's last glyph and the box's left edge. */
    val GapAfterName: Dp = 2.dp

    /** What the mention reserves after its text, before the following space (the icon's own 2dp padding spills into the space). */
    val Reserve: Dp = 10.dp

    /**
     * `--read-mark`: `palette-lime-700` in light (6.2:1 on white), the online lime
     * (`presence-online`) in dark (10.5:1 on the dark canvas). The generated tokens are pinned to
     * raft-ui 0.22.1 and do not have it yet, so the two palette steps are named here.
     */
    val color: Color
        @Composable @ReadOnlyComposable
        get() = if (RaftTheme.isDark) RaftUiPalette.presenceOnline else RaftUiPalette.paletteLime700
}
