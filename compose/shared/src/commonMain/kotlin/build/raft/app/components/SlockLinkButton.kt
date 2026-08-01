package build.raft.app.components

import build.raft.app.Button as SlockButton
import build.raft.app.SlockColor
import build.raft.app.SlockType
import build.raft.app.withSlockFontWeight
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextDecoration
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.TextUnit
import com.tencent.kuikly.compose.ui.unit.sp

@Composable
internal fun SlockLinkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fontSize: TextUnit = 14.sp,
    lineHeight: TextUnit = 18.sp,
    color: Color = SlockColor.TextBlack.copy(alpha = 0.5f),
    disabledColor: Color = SlockColor.TextBlack.copy(alpha = 0.28f)
) {
    val resolvedColor = if (enabled) color else disabledColor
    SlockButton(
        onClick = if (enabled) onClick else ({}),
        enabled = enabled,
        pressFeedback = false,
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            style = SlockType.Body.withSlockFontWeight(FontWeight.Normal).copy(
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
