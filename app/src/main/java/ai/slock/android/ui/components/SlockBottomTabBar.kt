package ai.slock.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.vector.ImageVector
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
    val icon: ImageVector,
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

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(SlockSize.BottomBarHeight)
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
    ) {
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
                    imageVector = item.icon,
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
}
