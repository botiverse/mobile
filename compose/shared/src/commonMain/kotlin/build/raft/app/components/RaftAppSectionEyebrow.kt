package build.raft.app.components

import build.raft.app.RaftColor
import build.raft.app.RaftType
import build.raft.app.withRaftFontWeight
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

@Composable
internal fun RaftAppSectionEyebrow(
    label: String,
    modifier: Modifier = Modifier,
    color: Color = RaftColor.TextMutedGray,
    fontSize: TextUnit = 12.sp,
    lineHeight: TextUnit = 18.sp
) {
    // raft-ui section title (`rf-section`): 13/18 bold hint ink in the source case, no
    // tracking; hierarchy by size and colour, never ALL CAPS. Explicit caller metrics stay.
    Text(
        text = label,
        style = RaftType.SectionLabel
            .copy(
                fontSize = if (fontSize == 12.sp) 13.sp else fontSize,
                lineHeight = lineHeight,
                letterSpacing = 0.sp
            )
            .withRaftFontWeight(FontWeight.Bold),
        color = if (color == RaftColor.TextMutedGray) RaftColor.ForegroundHint else color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}
