package ai.slock.compose

import ai.slock.compose.components.SlockBrutalActionButton
import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.widthIn
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.text.style.TextAlign
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp

@Composable
internal fun SlockEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    iconName: String = "inbox",
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
                    leadingIcon = "refresh-cw",
                    backgroundColor = SlockColor.SurfaceWhite
                )
            }
        }
    }
}
