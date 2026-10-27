package build.raft.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftTheme
import build.raft.designsystem.tokens.RaftRadius
import build.raft.designsystem.tokens.RaftStroke

/**
 * The composer's stand-in where nobody can write (botiverse/slock web `ReadOnlyComposer`): one
 * disabled line, `min-h-11` (44dp), `rounded-container`, a `line-hairline` border on
 * `fill-muted`, 12dp each side, and [text] ("Chat is unavailable") in `text-body`
 * `foreground-disabled`. No typing, attachments, As Task or send. Screen readers hear [text] as a
 * disabled field.
 */
@Composable
fun RaftReadOnlyComposer(text: String, modifier: Modifier = Modifier) {
    val colors = RaftTheme.colors
    val shape = RoundedCornerShape(RaftRadius.Container)
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .background(colors.fillMuted, shape)
                .border(RaftStroke.Hairline, colors.lineHairline, shape)
                .clearAndSetSemantics {
                    contentDescription = text
                    disabled()
                }
                .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        RaftText(
            text = text,
            style = RaftTheme.typography.body,
            color = colors.foregroundDisabled,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
