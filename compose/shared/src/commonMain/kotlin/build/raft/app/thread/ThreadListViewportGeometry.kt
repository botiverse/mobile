package build.raft.app.thread

internal fun ThreadListViewport.isThreadItemVisible(index: Int): Boolean {
    if (index < 0) return false
    return layoutInfo.visibleItemsInfo.any { it.index == index }
}

// task #567: the target's current offset inside the viewport, or null when it
// is not among the visible items (= fully drifted).
internal fun ThreadListViewport.focusedAnchorTargetOffset(targetIndex: Int): Int? =
    layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }?.offset

internal fun ThreadListViewport.canScrollToThreadItem(index: Int): Boolean =
    index >= 0 && layoutInfo.totalItemsCount > index

internal fun ThreadListViewport.threadViewportGeometryDiagnostics(targetIndex: Int): String {
    val info = layoutInfo
    val visibleItems = info.visibleItemsInfo
    val first = visibleItems.firstOrNull()
    val last = visibleItems.lastOrNull()
    val target = visibleItems.firstOrNull { item -> item.index == targetIndex }
    return "viewport=${info.viewportStartOffset}..${info.viewportEndOffset} " +
        "first=${first?.index ?: -1}:${first?.offset ?: 0}+${first?.size ?: 0} " +
        "last=${last?.index ?: -1}:${last?.offset ?: 0}+${last?.size ?: 0} " +
        "target=${target?.index ?: -1}:${target?.offset ?: 0}+${target?.size ?: 0}"
}

internal fun ThreadListViewport.isLatestItemAtRenderedBottom(
    latestItemIndex: Int,
    trailingPaddingSize: Int = 0,
    allowSparseTopFit: Boolean = false,
): Boolean = threadItemAtRenderedBottom(layoutInfo, latestItemIndex, trailingPaddingSize, allowSparseTopFit)

/**
 * The list rests on its end in the last layout: the end row under the newest message is laid out
 * with its bottom on the rendered bottom, within the tolerance a settled drag counts as following
 * ([isLatestItemAtRenderedBottom]). Found by its key, so a row appended in the same composition (an
 * agent's reply landing as its typing line goes) does not hide it behind a stale index.
 */
internal fun ThreadListViewport.restsOnListEnd(allowSparseTopFit: Boolean = false): Boolean {
    val info = layoutInfo
    val end = info.visibleItemsInfo.firstOrNull { it.key == ThreadBottomScrollPaddingListItemKey } ?: return false
    val endBottom = end.offset + end.size
    if (kotlin.math.abs(endBottom - info.viewportEndOffset) <= ThreadBackToBottomTolerancePx) return true
    if (!allowSparseTopFit) return false
    val firstVisible = info.visibleItemsInfo.firstOrNull() ?: return false
    return firstVisible.index == 0 &&
        firstVisible.offset >= 0 &&
        endBottom <= info.viewportEndOffset + ThreadBackToBottomTolerancePx
}

/**
 * Some of the end row under the newest message shows in the last layout: the newest message is
 * whole on screen, above it. Found by its key, as [restsOnListEnd].
 */
internal fun ThreadListViewport.listEndInView(): Boolean {
    val info = layoutInfo
    val end = info.visibleItemsInfo.firstOrNull { it.key == ThreadBottomScrollPaddingListItemKey } ?: return false
    return end.offset < info.viewportEndOffset
}
