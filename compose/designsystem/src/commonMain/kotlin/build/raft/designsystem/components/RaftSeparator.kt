package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftStroke

enum class RaftSeparatorOrientation {
    Horizontal,
    Vertical,
}

/**
 * raft-ui 0.8.0 Separator: a 1px `line-hairline` between two groups of content in one
 * region. Horizontal takes the full width; vertical stretches to the height of its row,
 * so the row needs a fixed height.
 */
@Composable
fun RaftSeparator(
    modifier: Modifier = Modifier,
    orientation: RaftSeparatorOrientation = RaftSeparatorOrientation.Horizontal,
) {
    val sized =
        when (orientation) {
            RaftSeparatorOrientation.Horizontal -> modifier.fillMaxWidth().height(RaftStroke.Hairline)
            RaftSeparatorOrientation.Vertical -> modifier.fillMaxHeight().width(RaftStroke.Hairline)
        }
    Box(sized.background(RaftTheme.colors.lineHairline))
}

/**
 * raft-ui 0.8.0 Separator with a word (horizontal only): `grid-cols-[1fr_auto_1fr]
 * items-center gap-3 py-3`, the word in `text-footnote` semibold `foreground-hint`
 * between two hairlines. The lines are hidden from accessibility; the word is read.
 */
@Composable
fun RaftSeparator(
    label: String,
    modifier: Modifier = Modifier,
) {
    val hairline = RaftTheme.colors.lineHairline
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(RaftStroke.Hairline).background(hairline).clearAndSetSemantics { })
        RaftText(
            text = label,
            style = RaftTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = RaftTheme.colors.foregroundHint,
            maxLines = 1,
        )
        Box(Modifier.weight(1f).height(RaftStroke.Hairline).background(hairline).clearAndSetSemantics { })
    }
}
