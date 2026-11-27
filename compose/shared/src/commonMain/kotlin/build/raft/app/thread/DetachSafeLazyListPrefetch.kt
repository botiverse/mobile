package build.raft.app.thread

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListPrefetchScope
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo

/**
 * Hands 9e3ca082 / 712d8386 (`IllegalStateException: LayoutNode should be attached to an owner`):
 * a [LazyListState] that outlives the LazyColumn node it drives keeps that node's pending
 * prefetch. Compose foundation (1.12.1, 1.13.0-rc01) only flags the disposed layout's
 * `PrefetchHandleProvider`; the default strategy still holds its `PrefetchHandle`, and the next
 * scroll that turns direction cancels it, which disposes a precomposed slot of the detached
 * `SubcomposeLayout` and throws in `root.requireOwner()`. Any scroll can do it: a user drag as
 * well as a programmatic one.
 *
 * This strategy is the default one ([LazyListPrefetchStrategy]) behind a delegate that
 * [onLayoutDetached] replaces: the pending handle of a detached layout is dropped, never
 * cancelled (its slots die with that layout). Pair it with [detachSafeLazyListPrefetch] on the
 * LazyColumn that uses the state, which calls [onLayoutDetached] when that node leaves its owner
 * for any reason (the LazyColumn left composition, or an ancestor detached it).
 */
@OptIn(ExperimentalFoundationApi::class)
@Stable
internal class DetachSafeLazyListPrefetchStrategy : LazyListPrefetchStrategy {
    private var delegate: LazyListPrefetchStrategy = LazyListPrefetchStrategy()

    /** Times a detached layout's pending prefetch was dropped; tests read it. */
    var droppedLayouts: Int = 0
        private set

    /** The LazyColumn this state drove left its owner: forget its prefetch without cancelling it. */
    fun onLayoutDetached() {
        delegate = LazyListPrefetchStrategy()
        droppedLayouts += 1
    }

    override fun LazyListPrefetchScope.onScroll(delta: Float, layoutInfo: LazyListLayoutInfo) {
        with(delegate) { onScroll(delta, layoutInfo) }
    }

    override fun LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: LazyListLayoutInfo) {
        with(delegate) { onVisibleItemsUpdated(layoutInfo) }
    }

    override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) {
        with(delegate) { onNestedPrefetch(firstVisibleItemIndex) }
    }
}

/** [rememberLazyListState] with a [DetachSafeLazyListPrefetchStrategy]; see [detachSafeLazyListPrefetch]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberDetachSafeLazyListState(
    prefetch: DetachSafeLazyListPrefetchStrategy,
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0,
): LazyListState =
    rememberLazyListState(
        initialFirstVisibleItemIndex = initialFirstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = initialFirstVisibleItemScrollOffset,
        prefetchStrategy = prefetch,
    )

/** A [DetachSafeLazyListPrefetchStrategy] for one list state's lifetime. */
@Composable
internal fun rememberDetachSafeLazyListPrefetch(): DetachSafeLazyListPrefetchStrategy =
    remember { DetachSafeLazyListPrefetchStrategy() }

/**
 * On the LazyColumn whose state uses [prefetch]: when this LazyColumn's node detaches from its
 * owner, [DetachSafeLazyListPrefetchStrategy.onLayoutDetached] drops the prefetch it scheduled.
 */
internal fun Modifier.detachSafeLazyListPrefetch(prefetch: DetachSafeLazyListPrefetchStrategy): Modifier =
    this then DetachSafeLazyListPrefetchElement(prefetch)

private class DetachSafeLazyListPrefetchElement(
    private val prefetch: DetachSafeLazyListPrefetchStrategy,
) : ModifierNodeElement<DetachSafeLazyListPrefetchNode>() {
    override fun create(): DetachSafeLazyListPrefetchNode = DetachSafeLazyListPrefetchNode(prefetch)

    override fun update(node: DetachSafeLazyListPrefetchNode) {
        node.prefetch = prefetch
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "detachSafeLazyListPrefetch"
    }

    override fun equals(other: Any?): Boolean =
        other is DetachSafeLazyListPrefetchElement && other.prefetch === prefetch

    override fun hashCode(): Int = prefetch.hashCode()
}

private class DetachSafeLazyListPrefetchNode(
    var prefetch: DetachSafeLazyListPrefetchStrategy,
) : Modifier.Node() {
    override fun onDetach() {
        prefetch.onLayoutDetached()
    }
}
