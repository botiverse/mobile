package build.raft.app

import build.raft.app.components.SlockBrutalActionButton
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

@Composable
internal fun SlockEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    iconName: SlockLucideIconName = SlockLucideIconName.Inbox,
    iconSize: Dp = 36.dp,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(SlockColor.SurfaceWhite)
                .padding(SlockSpacing.Xl),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.widthIn(max = 280.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Md)
        ) {
            SlockLucideIcon(
                iconName,
                modifier = Modifier.size(iconSize),
                color = SlockColor.TextMutedGrayArgb
            )
            Text(
                text = title,
                style = SlockType.PageTitle.copy(fontSize = 18.sp, lineHeight = 22.sp),
                color = SlockColor.TextMutedGray,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            detail?.takeIf { it.isNotBlank() }?.let { value ->
                Text(
                    text = value,
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (actionLabel != null && onAction != null) {
                SlockBrutalActionButton(
                    label = actionLabel,
                    onClick = onAction,
                    leadingIcon = SlockLucideIconName.RefreshCw,
                    backgroundColor = SlockColor.SurfaceWhite
                )
            }
        }
    }
}
