package build.raft.app.components

import build.raft.app.Button as SlockButton
import build.raft.app.SlockColor
import build.raft.app.SlockStroke
import build.raft.app.SlockType
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.dp

@Composable
internal fun SlockBrutalListRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(modifier = modifier.fillMaxWidth().height(62.dp).padding(end = 2.dp, bottom = 2.dp)) {
        Box(Modifier.fillMaxSize().offset(x = 2.dp, y = 2.dp).background(SlockColor.ShadowBlack))
        SlockButton(
            onClick = if (enabled) onClick else ({}),
            enabled = enabled,
            modifier = Modifier
                .fillMaxSize()
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = title,
                    style = SlockType.Button.copy(color = SlockColor.TextBlack),
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = SlockType.Caption.copy(color = SlockColor.TextBlack.copy(alpha = 0.4f)),
                    color = SlockColor.TextBlack.copy(alpha = 0.4f),
                    maxLines = 1
                )
            }
        }
    }
}
