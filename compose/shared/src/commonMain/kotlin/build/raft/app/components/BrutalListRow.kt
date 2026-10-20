package build.raft.app.components

import build.raft.app.Button as RaftAppButton
import build.raft.app.RaftColor
import build.raft.app.RaftAppRadius
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import build.raft.app.RaftType
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun RaftBrutalListRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    // raft-ui card row: container radius, card layer, 1dp hairline; no ink frame or offset shadow.
    Box(modifier = modifier.fillMaxWidth().height(62.dp)) {
        RaftAppButton(
            onClick = if (enabled) onClick else ({}),
            enabled = enabled,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(RaftAppRadius.Container))
                .raftCardSurface()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = title,
                    style = RaftType.Button.copy(color = RaftColor.TextBlack),
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = RaftType.Caption.copy(color = RaftColor.TextMuted),
                    color = RaftColor.TextMuted,
                    maxLines = 1
                )
            }
        }
    }
}
