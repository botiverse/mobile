package ai.slock.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.tencent.kuikly.compose.ComposeContainer
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.PaddingValues
import com.tencent.kuikly.compose.foundation.layout.Spacer
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.lazy.LazyRow
import com.tencent.kuikly.compose.foundation.lazy.rememberLazyListState
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page
import kotlinx.coroutines.launch

const val REVERSE_LAZY_COLUMN_DEMO_PAGE = "compose_reverse_lazy_column_demo"

@Page(REVERSE_LAZY_COLUMN_DEMO_PAGE, supportInLocal = true)
internal class ReverseLazyColumnDemoPage : ComposeContainer() {
    override fun willInit() {
        super.willInit()
        setContent { ReverseLazyColumnDemoScreen() }
    }
}

@Composable
private fun ReverseLazyColumnDemoScreen() {
    val statusBarHeight = LocalActivity.current.pageData.statusBarHeight
    val columnState = rememberLazyListState()
    val rowState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val itemCount = 20

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SlockColor.SurfaceCream)
            .padding(start = 16.dp, top = (statusBarHeight + 16).dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "LazyColumn reverseLayout",
            style = SlockType.PageTitle.copy(fontSize = 22.sp, lineHeight = 28.sp),
            color = SlockColor.TextBlack
        )
        Text(
            text = "这是接到 Slock KMP render demo 里的上游 Kuikly reverseLayout 验收页。初始会从视觉底部开始显示，手势仍然是正常上下滑动。",
            style = SlockType.Body.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = SlockColor.TextMutedGray
        )
        Text(
            text = "Column firstVisibleItemIndex=${columnState.firstVisibleItemIndex} / Row firstVisibleItemIndex=${rowState.firstVisibleItemIndex}",
            style = SlockType.Caption.copy(fontSize = 11.sp, lineHeight = 14.sp),
            color = SlockColor.TextMutedGray
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ReverseLazyDemoButton(
                label = "滚到视觉顶部",
                modifier = Modifier.fillMaxWidth()
            ) {
                scope.launch {
                    columnState.animateScrollToItem(itemCount - 1)
                }
            }
            ReverseLazyDemoButton(
                label = "滚到尾部(index 0)",
                modifier = Modifier.fillMaxWidth()
            ) {
                scope.launch {
                    columnState.animateScrollToItem(0)
                }
            }
        }

        LazyColumn(
            state = columnState,
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(itemCount) { index ->
                ReverseLazyDemoCard(index, modifier = Modifier.fillMaxWidth().height(56.dp))
            }
        }

        Text(
            text = "LazyRow reverseLayout",
            style = SlockType.SectionLabel.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = SlockColor.TextMutedGray
        )
        LazyRow(
            state = rowState,
            modifier = Modifier
                .fillMaxWidth()
                .height(116.dp)
                .background(SlockColor.SurfaceWhite)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            reverseLayout = true,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            items(itemCount) { index ->
                ReverseLazyDemoCard(index, modifier = Modifier.width(120.dp).height(72.dp))
            }
        }
    }
}

@Composable
private fun ReverseLazyDemoButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .background(SlockColor.SurfaceWhite)
            .border(SlockStroke.Default, SlockColor.BorderBlack)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = SlockColor.TextBlack
        )
    }
}

@Composable
private fun ReverseLazyDemoCard(index: Int, modifier: Modifier) {
    val cardColor =
        when (index % 4) {
            0 -> Color(0xFF4C6EF5)
            1 -> Color(0xFF12B886)
            2 -> Color(0xFFF59F00)
            else -> Color(0xFFE03131)
        }
    Box(
        modifier = modifier
            .background(cardColor)
            .border(SlockStroke.Default, SlockColor.BorderBlack),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Message $index",
            style = SlockType.ButtonBold.copy(fontSize = 14.sp, lineHeight = 18.sp),
            color = SlockColor.SurfaceWhite
        )
    }
}
