package build.raft.app.components

import build.raft.app.raftDisplayUppercase
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
    val raft = raftActive()
    Text(
        text = if (raft) label else label.raftDisplayUppercase(),
        style = RaftType.SectionLabel
            .copy(
                fontSize = if (raft && fontSize == 12.sp) 13.sp else fontSize,
                lineHeight = lineHeight,
                letterSpacing = if (raft) 0.sp else RaftType.SectionLabel.letterSpacing
            )
            .withRaftFontWeight(FontWeight.Bold),
        color = if (raft && color == RaftColor.TextMutedGray) RaftColor.ForegroundHint else color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}
