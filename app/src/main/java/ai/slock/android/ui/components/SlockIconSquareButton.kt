package ai.slock.android.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockSize

@Composable
fun SlockIconSquareButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true
) {
    val backgroundColor = if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite

    SlockKeySurface(
        onClick = onClick,
        modifier = modifier,
        surfaceModifier = Modifier.size(SlockSize.IconButton),
        enabled = enabled,
        backgroundColor = backgroundColor
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = SlockColor.TextBlack,
            modifier = Modifier.size(20.dp)
        )
    }
}
