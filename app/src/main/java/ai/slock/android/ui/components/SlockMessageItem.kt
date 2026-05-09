package ai.slock.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockRadius
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockType

enum class SlockMessageState {
    Sent,
    Pending,
    Error
}

@Composable
fun SlockMessageItem(
    authorName: String,
    role: String,
    time: String,
    body: String,
    modifier: Modifier = Modifier,
    avatarLabel: String = authorName.take(1).uppercase(),
    attachmentLabel: String? = null,
    state: SlockMessageState = SlockMessageState.Sent
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(SlockColor.SurfaceWhite)
            .padding(SlockSpacing.Md),
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)
    ) {
        PixelAvatar(label = avatarLabel)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
            ) {
                Text(
                    text = authorName,
                    style = SlockType.Button,
                    color = SlockColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    text = role,
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = time,
                    style = SlockType.Caption,
                    color = SlockColor.TextMutedGray
                )
            }
            Text(
                text = body,
                style = SlockType.Body,
                color = SlockColor.TextBlack
            )
            if (attachmentLabel != null || state != SlockMessageState.Sent) {
                Row(horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm)) {
                    if (attachmentLabel != null) {
                        MessageBadge(
                            label = attachmentLabel,
                            color = SlockColor.SurfaceCream
                        )
                    }
                    when (state) {
                        SlockMessageState.Pending -> MessageBadge("Sending", SlockColor.BrandYellow)
                        SlockMessageState.Error -> MessageBadge("Failed", SlockColor.AccentRed)
                        SlockMessageState.Sent -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun PixelAvatar(label: String) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(SlockColor.AccentLavender)
            .border(SlockStroke.Default, SlockColor.BorderBlack),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = SlockType.Button,
            color = SlockColor.TextBlack
        )
    }
}

@Composable
private fun MessageBadge(
    label: String,
    color: androidx.compose.ui.graphics.Color
) {
    Text(
        text = label,
        style = SlockType.Caption,
        color = SlockColor.TextBlack,
        modifier = Modifier
            .background(color)
            .border(SlockStroke.Thin, SlockColor.BorderBlack)
            .padding(horizontal = SlockSpacing.Sm, vertical = 2.dp)
    )
}
