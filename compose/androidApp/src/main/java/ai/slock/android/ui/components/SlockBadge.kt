package ai.slock.android.ui.components

import ai.slock.android.ui.icons.LucideIcons

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockShadow
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockType
import ai.slock.android.ui.theme.slockHardShadow

enum class SlockLogoBadgeVariant {
    Compact,
    Full,
    Large
}

@Composable
fun SlockLogoBadge(
    text: String,
    modifier: Modifier = Modifier,
    variant: SlockLogoBadgeVariant = SlockLogoBadgeVariant.Full,
    statusColor: Color? = null,
    showDropdown: Boolean = false
) {
    val shadow = SlockShadow.Small
    val horizontalPadding = when (variant) {
        SlockLogoBadgeVariant.Compact -> SlockSpacing.Sm
        SlockLogoBadgeVariant.Full -> 12.dp
        SlockLogoBadgeVariant.Large -> 16.dp
    }
    val verticalPadding = when (variant) {
        SlockLogoBadgeVariant.Large -> SlockSpacing.Sm
        else -> SlockSpacing.Xs
    }
    val maxWidth = when (variant) {
        SlockLogoBadgeVariant.Compact -> 80.dp
        SlockLogoBadgeVariant.Full -> 220.dp
        SlockLogoBadgeVariant.Large -> 260.dp
    }
    val textStyle = when (variant) {
        SlockLogoBadgeVariant.Large -> SlockType.Button.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp)
        else -> SlockType.Button.copy(fontWeight = FontWeight.Bold)
    }
    Row(
        modifier = modifier
            .padding(end = shadow.offsetX, bottom = shadow.offsetY)
            .rotate(-2f)
            .slockHardShadow(shadow)
            .background(SlockColor.TextBlack)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .padding(
                horizontal = horizontalPadding,
                vertical = verticalPadding
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = SlockColor.BrandYellow,
            style = textStyle,
            modifier = Modifier.widthIn(max = maxWidth),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (showDropdown) {
            Spacer(modifier = Modifier.width(SlockSpacing.Xs))
            Icon(
                imageVector = LucideIcons.ChevronDown,
                contentDescription = null,
                tint = SlockColor.BrandYellow,
                modifier = Modifier.size(if (variant == SlockLogoBadgeVariant.Large) 18.dp else 16.dp)
            )
        }
        if (statusColor != null) {
            Spacer(modifier = Modifier.width(SlockSpacing.Sm))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(statusColor, CircleShape)
                    .border(1.dp, SlockColor.BorderBlack, CircleShape)
            )
        }
    }
}
