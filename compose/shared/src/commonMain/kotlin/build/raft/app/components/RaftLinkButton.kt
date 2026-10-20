package build.raft.app.components

import build.raft.app.Button as RaftAppButton
import build.raft.app.RaftColor
import build.raft.app.RaftType
import build.raft.app.withRaftFontWeight
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

@Composable
internal fun RaftLinkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fontSize: TextUnit = 14.sp,
    lineHeight: TextUnit = 18.sp,
    color: Color = RaftColor.TextBlack.copy(alpha = 0.5f),
    disabledColor: Color = RaftColor.TextBlack.copy(alpha = 0.28f)
) {
    val resolvedColor = if (enabled) color else disabledColor
    RaftAppButton(
        onClick = if (enabled) onClick else ({}),
        enabled = enabled,
        pressFeedback = false,
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            style = RaftType.Body.withRaftFontWeight(FontWeight.Normal).copy(
                fontSize = fontSize,
                lineHeight = lineHeight,
                color = resolvedColor,
                textDecoration = TextDecoration.Underline
            ),
            color = resolvedColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
