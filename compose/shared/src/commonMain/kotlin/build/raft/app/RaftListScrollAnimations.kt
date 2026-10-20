package build.raft.app

import androidx.compose.foundation.lazy.LazyListState

private const val RaftExpressiveTopSettleScreens = 2
private const val RaftExpressiveTopMinSettleItems = 8
private const val RaftExpressiveTopMaxSettleItems = 24

internal suspend fun LazyListState.animateScrollToTopExpressive() {
    raftExpressiveTopSettleAnchor(
        firstVisibleItemIndex = firstVisibleItemIndex,
        visibleItemCount = layoutInfo.visibleItemsInfo.size
    )?.let { anchor ->
        scrollToItem(anchor)
    }
    animateScrollToItem(0)
}

internal fun raftExpressiveTopSettleAnchor(
    firstVisibleItemIndex: Int,
    visibleItemCount: Int
): Int? {
    val normalizedVisibleCount = visibleItemCount.coerceAtLeast(1)
    val settleItemCount =
        (normalizedVisibleCount * RaftExpressiveTopSettleScreens)
            .coerceIn(RaftExpressiveTopMinSettleItems, RaftExpressiveTopMaxSettleItems)
    val farEnoughForFastPhase = firstVisibleItemIndex > settleItemCount + normalizedVisibleCount
    return if (farEnoughForFastPhase) settleItemCount else null
}
