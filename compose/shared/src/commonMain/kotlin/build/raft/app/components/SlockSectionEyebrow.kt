package build.raft.app.components

import build.raft.app.slockDisplayUppercase
import build.raft.app.SlockColor
import build.raft.app.SlockType
import build.raft.app.withSlockFontWeight
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.TextUnit
import com.tencent.kuikly.compose.ui.unit.sp

@Composable
internal fun SlockSectionEyebrow(
    label: String,
    modifier: Modifier = Modifier,
    color: Color = SlockColor.TextMutedGray,
    fontSize: TextUnit = 12.sp,
    lineHeight: TextUnit = 18.sp
) {
    // raft-ui section title (`rf-section`): 13/18 bold hint ink in the source case, no
    // tracking; hierarchy by size and colour, never ALL CAPS. Explicit caller metrics stay.
    val raft = slockRaftActive()
    Text(
        text = if (raft) label else label.slockDisplayUppercase(),
        style = SlockType.SectionLabel
            .copy(
                fontSize = if (raft && fontSize == 12.sp) 13.sp else fontSize,
                lineHeight = lineHeight,
                letterSpacing = if (raft) 0.sp else SlockType.SectionLabel.letterSpacing
            )
            .withSlockFontWeight(FontWeight.Bold),
        color = if (raft && color == SlockColor.TextMutedGray) SlockColor.ForegroundHint else color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}
