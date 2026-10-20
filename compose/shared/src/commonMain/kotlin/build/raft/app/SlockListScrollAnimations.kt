package build.raft.app

import androidx.compose.foundation.lazy.LazyListState

private const val SlockExpressiveTopSettleScreens = 2
private const val SlockExpressiveTopMinSettleItems = 8
private const val SlockExpressiveTopMaxSettleItems = 24

internal suspend fun LazyListState.animateScrollToTopExpressive() {
    slockExpressiveTopSettleAnchor(
        firstVisibleItemIndex = firstVisibleItemIndex,
        visibleItemCount = layoutInfo.visibleItemsInfo.size
    )?.let { anchor ->
        scrollToItem(anchor)
    }
    animateScrollToItem(0)
}

internal fun slockExpressiveTopSettleAnchor(
    firstVisibleItemIndex: Int,
    visibleItemCount: Int
): Int? {
    val normalizedVisibleCount = visibleItemCount.coerceAtLeast(1)
    val settleItemCount =
        (normalizedVisibleCount * SlockExpressiveTopSettleScreens)
            .coerceIn(SlockExpressiveTopMinSettleItems, SlockExpressiveTopMaxSettleItems)
    val farEnoughForFastPhase = firstVisibleItemIndex > settleItemCount + normalizedVisibleCount
    return if (farEnoughForFastPhase) settleItemCount else null
}
