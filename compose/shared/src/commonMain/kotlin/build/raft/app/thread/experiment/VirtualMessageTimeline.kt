package build.raft.app.thread.experiment

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.layout.LazyLayout
import androidx.compose.foundation.lazy.layout.LazyLayoutItemProvider
import androidx.compose.foundation.lazy.layout.LazyLayoutMeasurePolicy
import androidx.compose.foundation.lazy.layout.LazyLayoutPinnableItem
import androidx.compose.foundation.lazy.layout.LazyLayoutPinnedItemList
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Experimental `LazyLayout`-based virtual message timeline prototype for `#proj-mobile:#259`.
 *
 * Combines:
 * - Persistent key-indexed height cache + Fenwick tree (`VirtualTimelineHeightIndex`) with O(log N)
 *   zero-allocation visible-range lookup.
 * - Single-pass pre-draw jump convergence (`jumpToKey`) with `< 2px` alignment tolerance and
 *   pagination auto-load suppression while jumps settle.
 * - Explicit + Compose-native (`LazyLayoutPinnableItem`) off-viewport pinning so rows holding
 *   active in-place selection or focus are never disposed when scrolled out of the viewport.
 * - Native Compose scroll & fling physics (`Modifier.scrollable` + `ScrollableDefaults.flingBehavior()`
 *   + `rememberOverscrollEffect()`) with zero custom iOS bounce emulation on Android.
 */
@Composable
internal fun <T> VirtualMessageTimeline(
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    state: VirtualMessageTimelineState = rememberVirtualMessageTimelineState(),
    contentType: (T) -> Any? = { null },
    pinnedKeys: Set<Any> = emptySet(),
    userScrollEnabled: Boolean = true,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    edgeLoadThreshold: Dp = 600.dp,
    onNearStartEdge: (() -> Unit)? = null,
    onNearEndEdge: (() -> Unit)? = null,
    itemContent: @Composable (index: Int, item: T) -> Unit,
) {
    val itemKeys =
        remember(items, key) {
            List(items.size) { index -> key(items[index]) }
        }
    remember(state, itemKeys) {
        // Synchronize keys before measure without mutating snapshot state during composition;
        // itemProvider invalidation already triggers the measure pass for key list updates.
        state.syncKeys(itemKeys, triggerMeasure = false)
        itemKeys
    }
    SideEffect {
        state.updatePinnedKeys(pinnedKeys)
    }

    // Real user drag gestures cancel settling jumps and release the post-jump pagination barrier.
    LaunchedEffect(state) {
        state.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                state.notifyUserScrollGesture()
            }
        }
    }

    val currentItemContent = rememberUpdatedState(itemContent)
    val currentOnNearStartEdge = rememberUpdatedState(onNearStartEdge)
    val currentOnNearEndEdge = rememberUpdatedState(onNearEndEdge)
    val edgeLoadThresholdPx = with(LocalDensity.current) { edgeLoadThreshold.roundToPx().coerceAtLeast(0) }

    val itemProvider =
        remember(items, itemKeys, contentType, state.pinnedItemList) {
            VirtualTimelineItemProvider(
                items = items,
                keys = itemKeys,
                contentTypeOf = contentType,
                pinnedItemList = state.pinnedItemList,
                itemContentState = currentItemContent,
            )
        }
    val currentProvider = rememberUpdatedState(itemProvider)

    val measurePolicy =
        remember(state, edgeLoadThresholdPx) {
            LazyLayoutMeasurePolicy { constraints ->
                state.measure(
                    scope = this,
                    constraints = constraints,
                    itemProvider = currentProvider.value,
                    onNearStartEdge = currentOnNearStartEdge.value,
                    onNearEndEdge = currentOnNearEndEdge.value,
                    edgeLoadThresholdPx = edgeLoadThresholdPx,
                )
            }
        }

    val overscrollEffect = rememberOverscrollEffect()
    val reverseDirection =
        ScrollableDefaults.reverseDirection(
            layoutDirection = LocalLayoutDirection.current,
            orientation = Orientation.Vertical,
            reverseScrolling = false,
        )

    LazyLayout(
        itemProvider = { currentProvider.value },
        modifier =
            modifier
                .clipToBounds()
                .then(if (overscrollEffect != null) Modifier.overscroll(overscrollEffect) else Modifier)
                .scrollable(
                    state = state,
                    orientation = Orientation.Vertical,
                    overscrollEffect = overscrollEffect,
                    enabled = userScrollEnabled,
                    reverseDirection = reverseDirection,
                    flingBehavior = flingBehavior,
                    interactionSource = state.interactionSource,
                )
                .then(state.remeasurementModifier),
        prefetchState = state.prefetchState,
        measurePolicy = measurePolicy,
    )
}

private class VirtualTimelineItemProvider<T>(
    private val items: List<T>,
    private val keys: List<Any>,
    private val contentTypeOf: (T) -> Any?,
    private val pinnedItemList: LazyLayoutPinnedItemList,
    private val itemContentState: State<@Composable (index: Int, item: T) -> Unit>,
) : LazyLayoutItemProvider {
    private val keyToIndex: Map<Any, Int> =
        buildMap(keys.size) {
            for (i in keys.indices) {
                put(keys[i], i)
            }
        }

    override val itemCount: Int
        get() = items.size

    @Composable
    override fun Item(index: Int, key: Any) {
        if (index !in items.indices) return
        val item = items[index]
        LazyLayoutPinnableItem(
            key = key,
            index = index,
            pinnedItemList = pinnedItemList,
        ) {
            itemContentState.value.invoke(index, item)
        }
    }

    override fun getKey(index: Int): Any = keys[index]

    override fun getContentType(index: Int): Any? =
        if (index in items.indices) contentTypeOf(items[index]) else null

    override fun getIndex(key: Any): Int = keyToIndex[key] ?: -1
}
