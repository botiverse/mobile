package ai.slock.android.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class SlockHardShadow(
    val offsetX: Dp,
    val offsetY: Dp,
    val color: Color = SlockColor.ShadowBlack
)

object SlockShadow {
    val None = SlockHardShadow(offsetX = 0.dp, offsetY = 0.dp, color = Color.Transparent)
    val Small = SlockHardShadow(offsetX = 2.dp, offsetY = 2.dp)
    val Default = SlockHardShadow(offsetX = 4.dp, offsetY = 4.dp)
    val Large = SlockHardShadow(offsetX = 6.dp, offsetY = 6.dp)
}

fun Modifier.slockHardShadow(
    shadow: SlockHardShadow = SlockShadow.Default
): Modifier = drawBehind {
    drawRect(
        color = shadow.color,
        topLeft = Offset(
            x = shadow.offsetX.toPx(),
            y = shadow.offsetY.toPx()
        ),
        size = size
    )
}
