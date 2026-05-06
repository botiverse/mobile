package ai.slock.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockType

@Composable
fun SlockSectionHeader(
    label: String,
    count: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailingContent: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled) { onExpandedChange(!expanded) }
            .padding(horizontal = SlockSpacing.Sm, vertical = SlockSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs)
    ) {
        Icon(
            imageVector = SlockIcons.ChevronDown,
            contentDescription = null,
            tint = SlockColor.TextBlack,
            modifier = Modifier
                .size(16.dp)
                .rotate(if (expanded) 0f else -90f)
        )
        Text(
            text = label.uppercase(),
            style = SlockType.SectionLabel,
            color = SlockColor.TextBlack
        )
        Text(
            text = count.toString(),
            style = SlockType.Caption,
            color = SlockColor.TextMutedGray
        )
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.End
        ) {
            if (trailingContent != null) {
                trailingContent()
            }
        }
    }
}
