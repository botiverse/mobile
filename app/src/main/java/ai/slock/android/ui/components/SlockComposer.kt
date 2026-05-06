package ai.slock.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockType

@Composable
fun SlockComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    imageIcon: ImageVector,
    attachmentIcon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    sending: Boolean = false,
    errorText: String? = null,
    noPermissionReason: String? = null,
    onImageClick: () -> Unit = {},
    onAttachmentClick: () -> Unit = {}
) {
    val canSend = enabled && !sending && noPermissionReason == null && value.isNotBlank()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(SlockSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        if (noPermissionReason != null) {
            Text(
                text = noPermissionReason,
                style = SlockType.Caption,
                color = SlockColor.TextMutedGray
            )
        }
        if (errorText != null) {
            Text(
                text = errorText,
                style = SlockType.Caption,
                color = SlockColor.AccentRed
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm),
            verticalAlignment = Alignment.Bottom
        ) {
            SlockIconSquareButton(
                icon = imageIcon,
                contentDescription = "Add image",
                onClick = onImageClick,
                enabled = enabled && noPermissionReason == null
            )
            SlockIconSquareButton(
                icon = attachmentIcon,
                contentDescription = "Attach file",
                onClick = onAttachmentClick,
                enabled = enabled && noPermissionReason == null
            )
            SlockTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = "Message",
                enabled = enabled && noPermissionReason == null,
                reserveErrorSpace = false,
                singleLine = false,
                minLines = 1,
                maxLines = 4
            )
            SlockButton(
                text = "Send",
                onClick = onSend,
                enabled = canSend,
                loading = sending
            )
        }
    }
}
