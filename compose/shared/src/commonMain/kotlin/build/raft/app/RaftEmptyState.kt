package build.raft.app

import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonSize
import build.raft.app.components.RaftSlotIcon
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import build.raft.designsystem.components.raftSafeAreaHorizontalPadding

@Composable
internal fun RaftEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    iconName: RaftLucideIconName = RaftLucideIconName.Inbox,
    iconSize: Dp = 36.dp,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(RaftColor.SurfaceWhite)
                // The surface runs edge to edge; the message is centred in the safe area.
                .raftSafeAreaHorizontalPadding()
                .padding(RaftAppSpacing.Xl),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.widthIn(max = 280.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(RaftAppSpacing.Md)
        ) {
            RaftLucideIcon(
                iconName,
                modifier = Modifier.size(iconSize),
                color = RaftColor.TextMutedGrayArgb
            )
            Text(
                text = title,
                style = RaftType.PageTitle.copy(fontSize = 18.sp, lineHeight = 22.sp),
                color = RaftColor.TextMutedGray,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            detail?.takeIf { it.isNotBlank() }?.let { value ->
                Text(
                    text = value,
                    style = RaftType.Caption,
                    color = RaftColor.TextMutedGray,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (actionLabel != null && onAction != null) {
                RaftButton(
                    text = actionLabel,
                    onClick = onAction,
                    leadingIcon = { RaftSlotIcon(RaftLucideIconName.RefreshCw) },
                    size = RaftButtonSize.Lg
                )
            }
        }
    }
}
