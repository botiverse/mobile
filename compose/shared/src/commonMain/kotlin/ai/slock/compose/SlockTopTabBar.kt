package ai.slock.compose

import ai.slock.compose.markdown.traceMarkdownSection
import ai.slock.compose.markdown.traceMarkdownSectionNonComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.tencent.kuikly.compose.animation.core.FiniteAnimationSpec
import com.tencent.kuikly.compose.animation.core.animateFloatAsState
import com.tencent.kuikly.compose.animation.core.snap
import com.tencent.kuikly.compose.animation.core.tween
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.gestures.detectHorizontalDragGestures
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxHeight
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.foundation.lazy.LazyRow
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.draw.clipToBounds
import com.tencent.kuikly.compose.ui.graphics.graphicsLayer
import com.tencent.kuikly.compose.ui.input.pointer.pointerInput
import com.tencent.kuikly.compose.ui.input.pointer.positionChange
import com.tencent.kuikly.compose.ui.layout.onSizeChanged
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.text.style.TextOverflow
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.IntOffset
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class SlockSelectionChangeSource {
    Click,
    Swipe,
    Availability
}

internal data class SlockTabSpec<T>(
    val key: T,
    val label: String,
    val iconName: SlockLucideIconName,
    val width: Dp = SlockTopTabBarDefaults.TabWidth,
    val swipeEnabled: Boolean = true
)

internal data class SlockTopTabBarItem(
    val key: String,
    val label: String,
    val iconName: SlockLucideIconName,
    val width: Dp = SlockTopTabBarDefaults.TabWidth
)

internal object SlockTopTabBarDefaults {
    val Height = 32.dp
    val TabWidth = 98.dp
    val SwipePreviewMaxOffset = 156.dp
    const val SwipeSettleAnimationMs = 160
    const val SwipeEdgeResistance = 0.28f
}

@Composable
internal fun <T> SlockTabbedScaffold(
    tabs: List<SlockTabSpec<T>>,
    selectedKey: T,
    onSelectedKeyChange: (T, SlockSelectionChangeSource) -> Unit,
    modifier: Modifier = Modifier,
    itemKeyPrefix: String = "top-tab",
    swipeEnabled: Boolean = false,
    canSwipe: () -> Boolean = { true },
    content: @Composable (T, Modifier) -> Unit
) {
    Column(modifier = modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        val swipeTabs = remember(tabs) { tabs.filter { it.swipeEnabled } }
        val selectedSwipeIndex = swipeTabs.indexOfFirst { it.key == selectedKey }
        var dragDistancePx by remember(selectedKey) { mutableStateOf(0f) }
        var dragActive by remember(selectedKey) { mutableStateOf(false) }
        var settleTransition by remember { mutableStateOf<SlockSwipeSettleTransition<T>?>(null) }
        var contentWidthPx by remember { mutableStateOf(0f) }
        val density = LocalDensity.current
        val maxPreviewOffsetPx = with(density) { SlockTopTabBarDefaults.SwipePreviewMaxOffset.toPx() }
        val previewOffsetPx =
            slockSwipePreviewOffset(
                horizontalDistance = dragDistancePx,
                maxOffsetPx = maxPreviewOffsetPx,
                hasPrevious = selectedSwipeIndex > 0,
                hasNext = selectedSwipeIndex >= 0 && selectedSwipeIndex < swipeTabs.lastIndex,
                edgeResistance = SlockTopTabBarDefaults.SwipeEdgeResistance
            )
        val activeSettleTransition = settleTransition
        val settleTravelWidthPx = slockSwipeTravelWidth(
            containerWidthPx = contentWidthPx,
            fallbackWidthPx = maxPreviewOffsetPx
        )
        val visualTargetOffsetPx =
            activeSettleTransition?.let {
                slockSwipeSettleTargetOffset(
                    travelWidthPx = settleTravelWidthPx,
                    directionSign = it.directionSign
                )
            } ?: previewOffsetPx
        val settleSpec: FiniteAnimationSpec<Float> =
            if (dragActive) snap() else tween(durationMillis = SlockTopTabBarDefaults.SwipeSettleAnimationMs)
        val visualOffsetPx by key(selectedKey) {
            animateFloatAsState(
                targetValue = visualTargetOffsetPx,
                animationSpec = settleSpec,
                finishedListener = { finishedValue ->
                    val transition = settleTransition ?: return@animateFloatAsState
                    val targetOffset =
                        slockSwipeSettleTargetOffset(
                            travelWidthPx = slockSwipeTravelWidth(
                                containerWidthPx = contentWidthPx,
                                fallbackWidthPx = maxPreviewOffsetPx
                            ),
                            directionSign = transition.directionSign
                        )
                    if (abs(finishedValue - targetOffset) <= 0.5f) {
                        onSelectedKeyChange(transition.to, SlockSelectionChangeSource.Swipe)
                        settleTransition = null
                    }
                }
            )
        }
        val previousTab = swipeTabs.getOrNull(selectedSwipeIndex - 1)
        val nextTab = swipeTabs.getOrNull(selectedSwipeIndex + 1)
        SlockTopTabBar(
            items = tabs.map { it.toTopTabBarItem() },
            selectedKey = selectedKey.toString(),
            onSelectedKeyChange = { key ->
                tabs.firstOrNull { it.key.toString() == key }?.let { tab ->
                    settleTransition = null
                    onSelectedKeyChange(tab.key, SlockSelectionChangeSource.Click)
                }
            },
            itemKeyPrefix = itemKeyPrefix
        )
        val selectedTab = tabs.firstOrNull { it.key == selectedKey }
        val contentContainerModifier =
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .slockSwipeSelection(
                    items = swipeTabs.map { it.key },
                    selected = selectedKey,
                    enabled = swipeEnabled && selectedTab?.swipeEnabled == true,
                    canSwipe = canSwipe,
                    onDragActiveChange = { active ->
                        dragActive = active
                        if (!active) {
                            dragDistancePx = 0f
                        }
                    },
                    onDragDistanceChange = { dragDistancePx = it },
                    onSelectedChange = { next ->
                        val nextSwipeIndex = swipeTabs.indexOfFirst { it.key == next }
                        val directionSign =
                            slockSwipeDirectionSign(
                                currentIndex = selectedSwipeIndex,
                                nextIndex = nextSwipeIndex
                            )
                        if (directionSign == 0 || settleTravelWidthPx <= 0f) {
                            onSelectedKeyChange(next, SlockSelectionChangeSource.Swipe)
                        } else {
                            settleTransition =
                                SlockSwipeSettleTransition(
                                    from = selectedKey,
                                    to = next,
                                    directionSign = directionSign
                                )
                        }
                    }
                )
        Box(
            modifier =
                contentContainerModifier
                    .background(SlockColor.SurfaceWhite)
                    .clipToBounds()
                    .onSizeChanged { contentWidthPx = it.width.toFloat() }
        ) {
            if (activeSettleTransition == null) {
                SlockSwipeSelectionPreview(
                    previous = previousTab,
                    next = nextTab,
                    visualOffsetPx = visualOffsetPx,
                    thresholdOffsetPx = maxPreviewOffsetPx
                )
            }
            SlockSwipeSlidingContent(
                selected = selectedKey,
                transition = activeSettleTransition,
                visualOffsetPx = visualOffsetPx,
                travelWidthPx = settleTravelWidthPx,
                content = content
            )
        }
    }
}

internal data class SlockSwipeSettleTransition<T>(
    val from: T,
    val to: T,
    val directionSign: Int
)

@Composable
internal fun <T> Modifier.slockSwipeSelection(
    items: List<T>,
    selected: T,
    enabled: Boolean,
    swipeThreshold: Dp = 96.dp,
    horizontalDominanceRatio: Float = 1.45f,
    canSwipe: () -> Boolean = { true },
    onDragActiveChange: (Boolean) -> Unit = {},
    onDragDistanceChange: (Float) -> Unit = {},
    onSelectedChange: (T) -> Unit
): Modifier {
    if (!enabled || items.size < 2) return this
    val density = LocalDensity.current
    val thresholdPx = with(density) { swipeThreshold.toPx() }
    val itemsState = rememberUpdatedState(items)
    val selectedState = rememberUpdatedState(selected)
    val canSwipeState = rememberUpdatedState(canSwipe)
    val onDragActiveChangeState = rememberUpdatedState(onDragActiveChange)
    val onDragDistanceChangeState = rememberUpdatedState(onDragDistanceChange)
    val onSelectedChangeState = rememberUpdatedState(onSelectedChange)
    return this.pointerInput(enabled, thresholdPx, horizontalDominanceRatio) {
        var dragDistance = 0f
        var verticalDistance = 0f
        detectHorizontalDragGestures(
            onDragStart = {
                dragDistance = 0f
                verticalDistance = 0f
                onDragActiveChangeState.value(true)
                onDragDistanceChangeState.value(0f)
            },
            onDragEnd = {
                if (canSwipeState.value() && isClearHorizontalSwipe(dragDistance, verticalDistance, thresholdPx, horizontalDominanceRatio)) {
                    val currentItems = itemsState.value
                    val currentIndex = currentItems.indexOf(selectedState.value)
                    if (currentIndex >= 0) {
                        when {
                            dragDistance <= -thresholdPx ->
                                currentItems.getOrNull(currentIndex + 1)?.let(onSelectedChangeState.value)
                            dragDistance >= thresholdPx ->
                                currentItems.getOrNull(currentIndex - 1)?.let(onSelectedChangeState.value)
                        }
                    }
                }
                dragDistance = 0f
                verticalDistance = 0f
                onDragDistanceChangeState.value(0f)
                onDragActiveChangeState.value(false)
            },
            onDragCancel = {
                dragDistance = 0f
                verticalDistance = 0f
                onDragDistanceChangeState.value(0f)
                onDragActiveChangeState.value(false)
            }
        ) { change, dragAmount ->
            dragDistance += dragAmount
            verticalDistance += change.positionChange().y
            onDragDistanceChangeState.value(dragDistance)
        }
    }
}

internal fun isClearHorizontalSwipe(
    horizontalDistance: Float,
    verticalDistance: Float,
    thresholdPx: Float,
    horizontalDominanceRatio: Float
): Boolean {
    val horizontal = abs(horizontalDistance)
    val vertical = abs(verticalDistance)
    return horizontal >= thresholdPx && horizontal >= vertical * horizontalDominanceRatio
}

internal fun slockSwipePreviewOffset(
    horizontalDistance: Float,
    maxOffsetPx: Float,
    hasPrevious: Boolean,
    hasNext: Boolean,
    edgeResistance: Float = SlockTopTabBarDefaults.SwipeEdgeResistance
): Float {
    if (horizontalDistance == 0f || maxOffsetPx <= 0f) return 0f
    val hasTarget = if (horizontalDistance > 0f) hasPrevious else hasNext
    val resistance = if (hasTarget) 1f else edgeResistance
    val limited = (horizontalDistance * resistance).coerceIn(-maxOffsetPx, maxOffsetPx)
    return when {
        limited > 0f && !hasPrevious -> limited.coerceAtMost(maxOffsetPx * edgeResistance)
        limited < 0f && !hasNext -> limited.coerceAtLeast(-maxOffsetPx * edgeResistance)
        else -> limited
    }
}

internal fun slockSwipeDirectionSign(currentIndex: Int, nextIndex: Int): Int =
    when {
        currentIndex < 0 || nextIndex < 0 -> 0
        nextIndex > currentIndex -> -1
        nextIndex < currentIndex -> 1
        else -> 0
    }

internal fun slockSwipeTravelWidth(containerWidthPx: Float, fallbackWidthPx: Float): Float =
    when {
        containerWidthPx > 0f -> containerWidthPx
        fallbackWidthPx > 0f -> fallbackWidthPx
        else -> 0f
    }

internal fun slockSwipeSettleTargetOffset(travelWidthPx: Float, directionSign: Int): Float =
    travelWidthPx * directionSign.coerceIn(-1, 1)

internal fun slockSwipeIncomingOffset(
    currentOffsetPx: Float,
    travelWidthPx: Float,
    directionSign: Int
): Float = currentOffsetPx - slockSwipeSettleTargetOffset(travelWidthPx, directionSign)

@Composable
internal fun <T> SlockSwipeSlidingContent(
    selected: T,
    transition: SlockSwipeSettleTransition<T>?,
    visualOffsetPx: Float,
    travelWidthPx: Float,
    content: @Composable (T, Modifier) -> Unit
) {
    if (transition == null) {
        content(
            selected,
            Modifier
                .fillMaxSize()
                .offset { IntOffset(visualOffsetPx.roundToInt(), 0) }
                .background(SlockColor.SurfaceWhite)
        )
        return
    }
    content(
        transition.to,
        Modifier
            .fillMaxSize()
            .offset {
                IntOffset(
                    slockSwipeIncomingOffset(
                        currentOffsetPx = visualOffsetPx,
                        travelWidthPx = travelWidthPx,
                        directionSign = transition.directionSign
                    ).roundToInt(),
                    0
                )
            }
            .background(SlockColor.SurfaceWhite)
    )
    content(
        transition.from,
        Modifier
            .fillMaxSize()
            .offset { IntOffset(visualOffsetPx.roundToInt(), 0) }
            .background(SlockColor.SurfaceWhite)
    )
}

@Composable
internal fun <T> SlockSwipeSelectionPreview(
    previous: SlockTabSpec<T>?,
    next: SlockTabSpec<T>?,
    visualOffsetPx: Float,
    thresholdOffsetPx: Float
) {
    if (visualOffsetPx == 0f || thresholdOffsetPx <= 0f) return
    val target = (if (visualOffsetPx > 0f) previous else next) ?: return
    val icon = if (visualOffsetPx > 0f) SlockLucideIconName.ChevronLeft else SlockLucideIconName.ChevronRight
    val progress = (abs(visualOffsetPx) / thresholdOffsetPx).coerceIn(0.18f, 0.72f)
    Row(
        modifier =
            Modifier
                .fillMaxSize()
                .background(SlockColor.SurfaceWhite)
                .padding(horizontal = SlockSpacing.Lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (visualOffsetPx > 0f) Arrangement.Start else Arrangement.End
    ) {
        Row(
            modifier = Modifier
                .graphicsLayer(alpha = progress)
                .background(SlockColor.EmptyStateGray)
                .padding(horizontal = SlockSpacing.Md, vertical = SlockSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Xs, Alignment.CenterHorizontally)
        ) {
            SlockLucideIcon(icon, modifier = Modifier.size(16.dp), color = SlockColor.TextMutedGrayArgb)
            Text(
                text = target.label,
                style = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp, color = SlockColor.TextMutedGray),
                color = SlockColor.TextMutedGray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Box(
                modifier = Modifier
                    .width((progress * 18f).dp)
                    .height(1.dp)
                    .background(SlockColor.BorderBlack)
            )
        }
    }
}

internal fun <T> SlockTabSpec<T>.toTopTabBarItem(): SlockTopTabBarItem =
    SlockTopTabBarItem(
        key = key.toString(),
        label = label,
        iconName = iconName,
        width = width
    )

@Composable
internal fun SlockTopTabBar(
    items: List<SlockTopTabBarItem>,
    selectedKey: String,
    onSelectedKeyChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    itemKeyPrefix: String = "top-tab"
) {
    // #192 trace scope extension: top tab bar render. Switches between tabs
    // are a known jank source (`operation=scene_enter` perf log line); this
    // marker measures the actual LazyRow + Row composition + tab click path.
    traceMarkdownSection(SlockTabBarTraceNames.TOP_TAB_RENDER, items.size) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(SlockTopTabBarDefaults.Height)
            .background(SlockColor.SurfaceWhite)
    ) {
        LazyRow(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(items.size, key = { "$itemKeyPrefix-${items[it].key}" }) { index ->
                val item = items[index]
                val selected = item.key == selectedKey
                Row(
                    modifier = Modifier
                        .width(item.width)
                        .fillMaxHeight()
                        .background(if (selected) SlockColor.BrandYellow else SlockColor.SurfaceWhite)
                        .clickable { onSelectedKeyChange(item.key) }
                        .padding(horizontal = SlockSpacing.Sm + SlockSpacing.Xxs, vertical = SlockSpacing.Xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SlockSpacing.Sm - SlockSpacing.Xxs, Alignment.CenterHorizontally)
                ) {
                    SlockLucideIcon(item.iconName, modifier = Modifier.size(16.dp), color = "#FF000000")
                    Text(
                        text = item.label,
                        style = SlockType.ButtonBold.copy(fontSize = 12.sp, lineHeight = 14.sp, color = SlockColor.TextBlack),
                        color = SlockColor.TextBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    modifier = Modifier
                        .height(SlockTopTabBarDefaults.Height - SlockStroke.Default)
                        .width(SlockStroke.Default)
                        .background(SlockColor.BorderBlack)
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(SlockStroke.Default).background(SlockColor.BorderBlack))
    }
    }
}
