package ai.slock.android.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.slock.android.ui.theme.SlockColor
import ai.slock.android.ui.theme.SlockSize
import ai.slock.android.ui.theme.SlockSpacing
import ai.slock.android.ui.theme.SlockStroke
import ai.slock.android.ui.theme.SlockType

data class SlockBottomTabItem(
    val key: String,
    val label: String,
    @DrawableRes val icon: Int,
    val contentDescription: String = label
)

@Composable
fun SlockBottomTabBar(
    items: List<SlockBottomTabItem>,
    selectedKey: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabLabelStyle = SlockType.Caption.copy(
        fontSize = 10.sp,
        lineHeight = 12.sp,
        fontWeight = FontWeight.Bold
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SlockSize.BottomBarHeight)
            .background(SlockColor.SurfaceWhite)
    ) {
        Row(modifier = Modifier.matchParentSize()) {
            items.forEachIndexed { index, item ->
                val selected = item.key == selectedKey
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite)
                        .clickable { onSelected(item.key) }
                        .padding(horizontal = SlockSpacing.Xs, vertical = SlockSpacing.Sm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
                ) {
                    Icon(
                        painter = painterResource(item.icon),
                        contentDescription = item.contentDescription,
                        tint = SlockColor.TextBlack,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = item.label.uppercase(),
                        style = tabLabelStyle,
                        color = SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (index < items.lastIndex) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(SlockStroke.Default)
                            .background(SlockColor.BorderBlack)
                    )
                }
            }
        }
        SlockBottomTabFrame(modifier = Modifier.matchParentSize())
    }
}

@Composable
fun SlockBottomTabFrame(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = SlockStroke.Default.toPx()
        drawLine(
            color = SlockColor.BorderBlack,
            start = Offset(0f, stroke / 2f),
            end = Offset(size.width, stroke / 2f),
            strokeWidth = stroke
        )
        drawLine(
            color = SlockColor.BorderBlack,
            start = Offset(0f, size.height - stroke / 2f),
            end = Offset(size.width, size.height - stroke / 2f),
            strokeWidth = stroke
        )
    }
}
