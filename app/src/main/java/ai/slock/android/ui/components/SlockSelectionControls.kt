package ai.slock.android.ui.components

import ai.slock.android.ui.icons.LucideIcons
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockType
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun SlockCheckboxBox(
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 20.dp
) {
    val backgroundColor = when {
        !enabled && checked -> SlockColor.TextMutedGray
        !enabled -> SlockColor.EmptyStateGray
        checked -> SlockColor.TextBlack
        else -> SlockColor.SurfaceWhite
    }
    val borderColor = if (enabled) SlockColor.BorderBlack else SlockColor.TextMutedGray
    Box(
        modifier = modifier
            .size(size)
            .background(borderColor),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size - SlockStroke.Default * 2)
                .background(backgroundColor)
        )
        if (checked) {
            Icon(
                imageVector = LucideIcons.Check,
                contentDescription = null,
                tint = if (enabled) SlockColor.SurfaceWhite else Color.White.copy(alpha = 0.75f),
                modifier = Modifier.size(size * 0.68f)
            )
        }
    }
}

@Composable
fun SlockLabeledCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 20.dp,
    labelSpacing: Dp = SlockSpacing.Sm,
    labelStyle: TextStyle = SlockType.Button.copy(fontSize = 16.sp, lineHeight = 20.sp)
) {
    Row(
        modifier = modifier
            .semantics {
                this.selected = checked
            }
            .clickable(
                enabled = enabled,
                role = Role.Checkbox,
                onClick = { onCheckedChange(!checked) }
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(labelSpacing)
    ) {
        SlockCheckboxBox(
            checked = checked,
            enabled = enabled,
            size = size
        )
        Text(
            text = label,
            style = labelStyle,
            color = if (enabled) SlockColor.TextMutedGray else SlockColor.TextMutedGray.copy(alpha = 0.6f)
        )
    }
}
