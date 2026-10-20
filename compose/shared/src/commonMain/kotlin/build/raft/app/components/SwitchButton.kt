package build.raft.app.components

import build.raft.app.RaftColor
import build.raft.app.RaftAppStroke
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun RaftSwitchButton(
    checked: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    RaftLookSwitch(checked = checked, enabled = enabled, modifier = modifier)
}

/**
 * raft-ui 0.6.0 Switch: a 50x30 pill with a 1.5dp `--line-strong` edge on `--fill-muted`
 * and 2.5dp inset; checked = the yellow `--brand` sticker with a sticker-ink edge.
 * The 22dp (+1.5dp ring) round thumb is the card layer ringed in `--line-strong` (checked: sticker paper
 * ringed in sticker ink). Disabled greys both to `--fill-muted` / `--foreground-disabled`.
 */
@Composable
private fun RaftLookSwitch(
    checked: Boolean,
    enabled: Boolean,
    modifier: Modifier
) {
    val trackShape = RoundedCornerShape(15.dp)
    val thumbShape = RoundedCornerShape(12.5.dp)
    val trackFill = if (checked && enabled) RaftColor.Brand else RaftColor.FillMuted
    val trackEdge =
        when {
            !enabled -> RaftColor.ForegroundDisabled
            checked -> RaftColor.StickerInk
            else -> RaftColor.BorderBlack
        }
    val thumbFill =
        when {
            !enabled -> RaftColor.FillMuted
            checked -> RaftColor.StickerPaper
            else -> RaftColor.Card
        }
    val thumbEdge =
        when {
            !enabled -> RaftColor.ForegroundDisabled
            checked -> RaftColor.StickerInk
            else -> RaftColor.BorderBlack
        }
    Box(
        modifier =
            modifier
                .size(width = 50.dp, height = 30.dp)
                .background(trackFill, trackShape)
                .border(RaftAppStroke.Control, trackEdge, trackShape)
                // Web: 1.5dp edge + 2.5dp inset around a 22dp thumb whose 1.5dp ring is an
                // outer box-shadow. Kuikly paints borders inside the box, so the thumb is drawn
                // 25dp (22 + ring) in a 2.5dp inset to land on the same pixels.
                .padding(2.5.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier =
                Modifier
                    .size(25.dp)
                    .background(thumbFill, thumbShape)
                    .border(RaftAppStroke.Control, thumbEdge, thumbShape)
        )
    }
}
