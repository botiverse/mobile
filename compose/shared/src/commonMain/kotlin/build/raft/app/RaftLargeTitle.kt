package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The large title of a phone tab root (Raft Design System › Page structure › Phone; web
 * `LargeTitle.tsx`). At scroll top the page name sits in `text-display` (34/40 bold) under
 * the 56dp frame bar, and the bar and the title read as one frame-coloured header with no
 * seam between them. Scrolling up shrinks that header: the title shrinks and rises until it
 * lands on the bar's own title, which then takes over, and the bar's seam appears. A scroll
 * that stops part-way finishes to fully open or fully collapsed, whichever is nearer.
 *
 * The bar's title is the one heading screen readers hear; the moving copy is decoration.
 */
internal object RaftLargeTitleMetrics {
    /** `px-4 pt-1 pb-2` around the title, shared by the block and the moving title. */
    val PaddingHorizontal = 16.dp
    val PaddingTop = 4.dp
    val PaddingBottom = 8.dp

    /** `text-display`: 34/40, 700. */
    val TitleLineHeight = 40.dp

    /** The block in the list: the title's line plus its padding, closed by a 1dp seam. */
    val BlockHeight = PaddingTop + TitleLineHeight + PaddingBottom + RaftAppStroke.Thin
}

internal val RaftLargeTitleStyle: TextStyle
    get() = RaftType.PageTitle.copy(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)

/**
 * How far the header has collapsed: 0 at the top of [listState], 1 once the large-title block
 * (its first item) has scrolled under the bar.
 */
internal fun raftLargeTitleProgress(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int, blockHeightPx: Float): Float =
    when {
        firstVisibleItemIndex > 0 || blockHeightPx <= 0f -> 1f
        else -> (firstVisibleItemScrollOffset / blockHeightPx).coerceIn(0f, 1f)
    }

/**
 * Where a scroll that came to rest at [offsetPx] into the block settles: back to the top
 * (0) or to the block's full height, whichever is nearer. Null when it's already at one end.
 */
internal fun raftLargeTitleSnapTarget(firstVisibleItemIndex: Int, offsetPx: Int, blockHeightPx: Int): Int? =
    when {
        firstVisibleItemIndex != 0 || offsetPx <= 0 || offsetPx >= blockHeightPx -> null
        offsetPx < blockHeightPx / 2 -> 0
        else -> blockHeightPx
    }

/**
 * A phone tab root with a large title: the frame bar (titled [title], no icon), then a list
 * whose first item is the frame-coloured large-title block, then [content].
 */
@Composable
internal fun RaftLargeTitlePage(
    title: String,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    applyTopSafeArea: Boolean = true,
    bottomContentPadding: Dp = RaftAppSpacing.Lg,
    content: LazyListScope.() -> Unit,
) {
    val density = LocalDensity.current
    val topSafeArea = if (applyTopSafeArea) raftTopSafeAreaPadding().coerceAtLeast(0.dp) else 0.dp
    val barHeight = topSafeArea + RaftAppSize.PageHeaderHeight
    val blockHeightPx = with(density) { RaftLargeTitleMetrics.BlockHeight.toPx() }
    val progress by remember(listState, blockHeightPx) {
        derivedStateOf {
            raftLargeTitleProgress(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, blockHeightPx)
        }
    }
    // Only the switch recomposes; the moving title reads the progress while drawing.
    val collapsed by remember(listState, blockHeightPx) { derivedStateOf { progress >= 1f } }
    LaunchedEffect(listState, blockHeightPx) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling) return@collect
                val target =
                    raftLargeTitleSnapTarget(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, blockHeightPx.toInt())
                        ?: return@collect
                listState.animateScrollBy((target - listState.firstVisibleItemScrollOffset).toFloat())
            }
    }
    val barTitleStyle = RaftMainTabHeaderTitleStyle
    // The moving title shrinks to the bar title's size and rises onto its line.
    val scale = barTitleStyle.fontSize.value / RaftLargeTitleStyle.fontSize.value
    val barTitleCentre = topSafeArea + RaftAppSize.PageHeaderContentHeight / 2
    val restingTop = barHeight + RaftLargeTitleMetrics.PaddingTop
    val landedTop = barTitleCentre - RaftLargeTitleMetrics.TitleLineHeight * scale / 2
    val riseY = with(density) { (landedTop - restingTop).toPx() }

    Box(modifier.fillMaxSize().background(RaftColor.Canvas)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(top = barHeight),
        ) {
            item(key = "large-title") {
                Column(Modifier.fillMaxWidth().height(RaftLargeTitleMetrics.BlockHeight).background(RaftColor.Frame)) {
                    Box(Modifier.weight(1f))
                    Box(
                        Modifier.fillMaxWidth().height(RaftAppStroke.Thin)
                            .background(RaftColor.BorderSeam.compositeOver(RaftColor.Frame))
                    )
                }
            }
            content()
            item(key = "large-title-bottom") { Box(Modifier.height(bottomContentPadding)) }
        }
        // The bar: frame colour, its title shown once the large title has landed on it.
        Box(Modifier.fillMaxWidth().height(barHeight).background(RaftColor.Frame)) {
            Box(
                Modifier
                    .padding(top = topSafeArea)
                    .fillMaxWidth()
                    .height(RaftAppSize.PageHeaderContentHeight)
                    .padding(horizontal = RaftLargeTitleMetrics.PaddingHorizontal),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = title,
                    style = barTitleStyle,
                    color = RaftColor.TextBlack,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(if (collapsed) 1f else 0f).semantics { heading() },
                )
            }
            Box(
                Modifier.fillMaxWidth().height(RaftAppStroke.Thin).align(Alignment.BottomCenter)
                    .alpha(if (collapsed) 1f else 0f)
                    .background(RaftColor.BorderSeam.compositeOver(RaftColor.Frame))
            )
        }
        // The moving title, over the block; clipped at the yellow still left under the bar so
        // it never draws over the list, open at the top so it can rise into the bar.
        if (!collapsed) {
            Text(
                text = title,
                style = RaftLargeTitleStyle,
                color = RaftColor.TextBlack,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .padding(top = restingTop, start = RaftLargeTitleMetrics.PaddingHorizontal, end = RaftLargeTitleMetrics.PaddingHorizontal)
                        .clearAndSetSemantics {}
                        .drawWithContent {
                            val bottom = blockHeightPx * (1f - progress) - RaftLargeTitleMetrics.PaddingTop.toPx()
                            clipRect(top = -size.height * 4, bottom = bottom) { this@drawWithContent.drawContent() }
                        }
                        .graphicsLayer {
                            transformOrigin = TransformOrigin(0f, 0f)
                            val s = 1f - progress * (1f - scale)
                            scaleX = s
                            scaleY = s
                            translationY = progress * riseY
                        },
            )
        }
    }
}
