package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import build.raft.app.data.RaftLongMessageCollapseConversationPreference
import build.raft.app.data.RaftLongMessageCollapseDetailKind
import build.raft.app.data.raftLongMessageCollapseDetailKind
import build.raft.app.data.RaftLongMessageCollapsePreferenceRevision
import build.raft.app.data.RaftLongMessageCollapsePreferenceStore
import build.raft.app.data.raftSharedPreferenceStore
import build.raft.app.resources.MR
import build.raft.designsystem.components.RaftTextLink
import build.raft.designsystem.theme.RaftTheme
import com.tencent.tmm.kmmresource.compose.stringResource
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Long-message collapse ("Collapse long messages" / 折叠长消息).
 *
 * Every message renders in full (markdown, code blocks, images, tables). The body is measured
 * with an unbounded max height; when its real height is taller than the threshold, it is
 * *visually* clipped to the threshold with a bottom fade and a "Show More" link. Nothing is
 * truncated or re-laid out: copy, selection, search and links all keep operating on the full
 * content. Web parity: CollapsibleMessageContent.tsx (fixed 320px, 48px fade, "Show More" /
 * 展开全文). The threshold is the web's 320dp, capped at a share of the thread viewport so a
 * short viewport (landscape, split screen) still shows the toggle on screen.
 */

/** Height a collapsed body is clipped to (dp); web: fixed 320px. */
internal const val RaftLongMessageCollapseMaxHeightDp = 320

/** Share of the thread viewport height a collapsed body may take at most (short viewports). */
internal const val RaftLongMessageCollapseViewportFraction = 0.8f

/**
 * A body must exceed the threshold by at least this much before it collapses, so "Show More"
 * never reveals only a line or two (dp).
 */
internal const val RaftLongMessageCollapseMinHiddenDp = 48

/** Height of the bottom fade on a collapsed body (dp); web: `h-12`. */
internal const val RaftLongMessageCollapseFadeDp = 48

/** Fixed height of the Show More / Show Less row, so its space can be reserved in the same pass (dp). */
internal const val RaftLongMessageCollapseToggleRowDp = 28

/** Width x height of the thread viewport that caps the threshold (px). */
internal data class RaftLongMessageViewport(val width: Int, val height: Int)

/**
 * The viewport the threshold follows. The keyboard only shrinks the height, so while the width is
 * unchanged the tallest height seen is kept (opening the IME must not re-collapse what the user is
 * reading); a width change (rotation, split screen) starts over from the new measurement.
 */
internal fun raftLongMessageStableViewport(
    previous: RaftLongMessageViewport?,
    measured: RaftLongMessageViewport
): RaftLongMessageViewport =
    if (previous == null || previous.width != measured.width) {
        measured
    } else {
        RaftLongMessageViewport(measured.width, max(previous.height, measured.height))
    }

/**
 * The collapsed height: [maxThresholdPx] (the web's fixed 320), or [fraction] of the viewport when
 * that is smaller. Without a viewport yet there is no threshold (nothing collapses).
 */
internal fun raftLongMessageCollapseThresholdPx(
    viewportHeightPx: Int,
    maxThresholdPx: Int,
    fraction: Float = RaftLongMessageCollapseViewportFraction
): Int =
    if (viewportHeightPx <= 0) {
        0
    } else {
        minOf(maxThresholdPx, (viewportHeightPx * fraction).roundToInt()).coerceAtLeast(0)
    }

/**
 * Whether a body is collapsible after a measurement. The body must exceed the threshold by
 * [minHiddenPx] to start collapsing; once it has collapsed ([wasCollapsible]) it stays so while it
 * is still taller than the threshold at all. That hysteresis absorbs small shrinks (a placeholder
 * swapped for a loaded image) without the toggle flapping, while a threshold that grows past the
 * content (rotation, a taller viewport) drops the toggle instead of leaving one that does nothing.
 * Without a threshold yet nothing collapses.
 */
internal fun raftLongMessageCollapsible(
    wasCollapsible: Boolean,
    contentHeightPx: Int,
    thresholdPx: Int,
    minHiddenPx: Int
): Boolean {
    if (thresholdPx <= 0) return false
    val slack = if (wasCollapsible) 0 else minHiddenPx.coerceAtLeast(0)
    return contentHeightPx > thresholdPx + slack
}

/** The height of the body's visible window. */
internal fun raftLongMessageVisibleBodyHeight(
    contentHeightPx: Int,
    thresholdPx: Int,
    collapsible: Boolean,
    expanded: Boolean
): Int =
    if (collapsible && !expanded && thresholdPx > 0) {
        minOf(contentHeightPx, thresholdPx)
    } else {
        contentHeightPx
    }

/** A search hit inside a collapsed body expands it; a hit on the author name alone does not. */
internal fun raftLongMessageSearchHitInBody(body: String, query: String): Boolean {
    val needle = query.trim()
    return needle.isNotEmpty() && body.contains(needle, ignoreCase = true)
}

/**
 * Where an expand/collapse leaves the list, in the thread's forward terms (offsets from the
 * viewport top, see [ThreadListViewport]).
 *
 * - Expand keeps the message top where it is and grows downward, pushing only the rows below.
 * - Collapse keeps the top too, unless the top had scrolled above the viewport: then the top is
 *   brought to the viewport top so the collapsed message (and its Show More) stays in view.
 *
 * [rawScrollRequest] is the same-frame `requestScrollToItem` on the raw state that realises it, or
 * null when the plain lazy layout already keeps that position: a top-down list keeps its first
 * visible row's offset, so its rows below the top only need help when a collapse would leave the
 * top above the viewport. A reversed (bottom-up) list keeps its bottom-most row, so a height change
 * moves the message top and must be compensated by moving the toggled row's raw offset.
 */
internal data class RaftLongMessageToggleScrollPlan(
    val desiredTop: Int,
    val rawScrollRequest: RaftLongMessageRawScrollRequest?
)

internal data class RaftLongMessageRawScrollRequest(val rawIndex: Int, val scrollOffset: Int)

internal fun raftLongMessageToggleScrollPlan(
    reverseLayout: Boolean,
    expanding: Boolean,
    forwardIndex: Int,
    totalItemsCount: Int,
    itemTopBefore: Int,
    rawItemOffsetBefore: Int,
    heightDelta: Int
): RaftLongMessageToggleScrollPlan {
    val desiredTop = if (expanding) itemTopBefore else max(itemTopBefore, 0)
    if (!reverseLayout) {
        val request =
            if (desiredTop != itemTopBefore) {
                RaftLongMessageRawScrollRequest(rawIndex = forwardIndex, scrollOffset = -desiredTop)
            } else {
                null
            }
        return RaftLongMessageToggleScrollPlan(desiredTop, request)
    }
    // Reversed: the raw offset is the distance from the bottom edge to the row's bottom. The new
    // bottom is the desired top plus the new height, i.e. it moves down by heightDelta plus any
    // correction of the top.
    val bottomShift = heightDelta + (desiredTop - itemTopBefore)
    if (bottomShift == 0) return RaftLongMessageToggleScrollPlan(desiredTop, null)
    val rawOffsetAfter = rawItemOffsetBefore - bottomShift
    return RaftLongMessageToggleScrollPlan(
        desiredTop = desiredTop,
        rawScrollRequest =
            RaftLongMessageRawScrollRequest(
                rawIndex = reversedThreadListIndex(forwardIndex, totalItemsCount),
                scrollOffset = -rawOffsetAfter
            )
    )
}

/**
 * Per-thread collapse state, remembered for the life of one thread open: the expanded message ids
 * (kept while rows scroll away and back), the one-way collapsible latch per rendered content, the
 * last measured body heights (for scroll anchoring) and the threshold.
 */
@Stable
internal class RaftLongMessageCollapseController {
    // One MutableState per key: a row reads only its own entry, so a toggle or a newly collapsible
    // body recomposes/remeasures that row alone (a SnapshotStateMap read subscribes to the map).
    private val expandedIds = HashMap<String, MutableState<Boolean>>()
    private val collapsibleKeys = HashMap<String, MutableState<Boolean>>()
    private val contentHeights = HashMap<String, Int>()
    private var viewport: RaftLongMessageViewport? = null

    var thresholdPx by mutableIntStateOf(0)
        private set

    /**
     * Called from the list container's measure pass, so the first frame already has a threshold.
     * [maxThresholdPx] is [RaftLongMessageCollapseMaxHeightDp] in px.
     */
    fun onViewportMeasured(width: Int, height: Int, maxThresholdPx: Int) {
        if (width <= 0 || height <= 0) return
        val next = raftLongMessageStableViewport(viewport, RaftLongMessageViewport(width, height))
        viewport = next
        val threshold = raftLongMessageCollapseThresholdPx(next.height, maxThresholdPx)
        if (threshold != thresholdPx) thresholdPx = threshold
    }

    private fun expandedState(messageId: String): MutableState<Boolean> =
        expandedIds.getOrPut(messageId) { mutableStateOf(false) }

    private fun collapsibleState(contentKey: String): MutableState<Boolean> =
        collapsibleKeys.getOrPut(contentKey) { mutableStateOf(false) }

    fun isExpanded(messageId: String): Boolean = expandedState(messageId).value

    fun setExpanded(messageId: String, expanded: Boolean) {
        val state = expandedState(messageId)
        if (state.value != expanded) state.value = expanded
    }

    /** Jump-to-message and in-body search hits open the message fully. */
    fun autoExpand(messageId: String?) {
        val id = messageId?.takeIf { it.isNotBlank() } ?: return
        setExpanded(id, true)
    }

    fun isCollapsible(contentKey: String): Boolean = collapsibleState(contentKey).value

    /** Records a measurement and returns whether the body is collapsible (see [raftLongMessageCollapsible]). */
    fun recordMeasurement(messageId: String, contentKey: String, contentHeightPx: Int, minHiddenPx: Int): Boolean {
        contentHeights[messageId] = contentHeightPx
        val state = collapsibleState(contentKey)
        val now = raftLongMessageCollapsible(state.value, contentHeightPx, thresholdPx, minHiddenPx)
        if (now != state.value) state.value = now
        return now
    }

    /** Height change of [messageId]'s body when it expands (positive) or collapses (negative). */
    fun toggleHeightDelta(messageId: String, expanding: Boolean): Int {
        val content = contentHeights[messageId] ?: return 0
        val threshold = thresholdPx
        if (threshold <= 0 || content <= threshold) return 0
        val hidden = content - threshold
        return if (expanding) hidden else -hidden
    }
}

/**
 * Feeds the list container's own constraints (minus its content padding) to [controller] in the
 * measure pass, before any row is measured: the threshold's viewport cap follows the visible list
 * area and is known on the first frame.
 */
@Composable
internal fun Modifier.raftLongMessageCollapseViewport(
    controller: RaftLongMessageCollapseController,
    verticalContentPaddingPx: Int
): Modifier =
    this then
        remember(controller, verticalContentPaddingPx) {
            raftLongMessageCollapseViewportModifier(controller, verticalContentPaddingPx)
        }

// Remembered by the caller: a fresh `layout {}` lambda per recomposition would make the
// LazyColumn's modifier chain unequal and re-run its layout node update every time.
private fun raftLongMessageCollapseViewportModifier(
    controller: RaftLongMessageCollapseController,
    verticalContentPaddingPx: Int
): Modifier =
    Modifier.layout { measurable, constraints ->
        if (constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
            controller.onViewportMeasured(
                constraints.maxWidth,
                (constraints.maxHeight - verticalContentPaddingPx).coerceAtLeast(0),
                RaftLongMessageCollapseMaxHeightDp.dp.roundToPx()
            )
        }
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

private class RaftLongMessageBodyLayoutState {
    var collapsible: Boolean = false
    var clipped by mutableStateOf(false)
}

/**
 * Wraps a message body. [controller] null = collapse off for this row: the content renders
 * unwrapped. [contentKey] identifies the rendered content (message id + rendered body) for the
 * one-way latch, so an edit or a translation toggle is measured afresh.
 */
@Composable
internal fun RaftLongMessageCollapsibleBody(
    controller: RaftLongMessageCollapseController?,
    messageId: String,
    contentKey: String,
    alignEnd: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    // The wrapper stays in the tree when collapse is off (controller null) so switching the
    // setting does not rebuild the message body; it then measures and places the content as is.
    val density = LocalDensity.current
    val minHiddenPx = with(density) { RaftLongMessageCollapseMinHiddenDp.dp.roundToPx() }
    val fadePx = with(density) { RaftLongMessageCollapseFadeDp.dp.toPx() }
    val toggleRowPx = with(density) { RaftLongMessageCollapseToggleRowDp.dp.roundToPx() }
    val expanded = controller?.isExpanded(messageId) == true
    val collapsible = controller?.isCollapsible(contentKey) == true
    val layoutState = remember(contentKey) { RaftLongMessageBodyLayoutState() }
    val fadePaint = remember { Paint() }
    Layout(
        content = {
            Layout(
                content = content,
                modifier =
                    Modifier
                        // Hit testing: content below the visible window must not take taps meant
                        // for rows underneath. Drawing is clipped in the same frame below.
                        .then(if (collapsible && !expanded) Modifier.clipToBounds() else Modifier)
                        .drawWithContent {
                            if (!layoutState.clipped) {
                                drawContent()
                                return@drawWithContent
                            }
                            val width = size.width
                            val height = size.height
                            clipRect(0f, 0f, width, height) {
                                val canvas = drawContext.canvas
                                canvas.saveLayer(Rect(0f, 0f, width, height), fadePaint)
                                this@drawWithContent.drawContent()
                                val fade = minOf(fadePx, height)
                                drawRect(
                                    brush =
                                        Brush.verticalGradient(
                                            0f to Color.Black, // raft-color: DstIn alpha mask, not a visible colour
                                            1f to Color.Transparent,
                                            startY = height - fade,
                                            endY = height
                                        ),
                                    topLeft = Offset(0f, height - fade),
                                    size = Size(width, fade),
                                    blendMode = BlendMode.DstIn
                                )
                                canvas.restore()
                            }
                        }
            ) { measurables, constraints ->
                val unbounded = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
                val placeables = measurables.map { it.measure(unbounded) }
                val width =
                    (placeables.maxOfOrNull { it.width } ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
                val contentHeight = placeables.maxOfOrNull { it.height } ?: 0
                val nowCollapsible =
                    controller?.recordMeasurement(messageId, contentKey, contentHeight, minHiddenPx) == true
                val visibleHeight =
                    raftLongMessageVisibleBodyHeight(
                        contentHeight,
                        controller?.thresholdPx ?: 0,
                        nowCollapsible,
                        expanded
                    )
                layoutState.collapsible = nowCollapsible
                val clipped = visibleHeight < contentHeight
                if (layoutState.clipped != clipped) layoutState.clipped = clipped
                layout(width, visibleHeight) {
                    placeables.forEach { it.place(0, 0) }
                }
            }
            if (collapsible) {
                RaftLongMessageToggle(expanded = expanded, alignEnd = alignEnd, onClick = onToggle)
            }
        }
    ) { measurables, constraints ->
        val body = measurables[0].measure(constraints.copy(minHeight = 0))
        val showToggle = layoutState.collapsible
        val toggle =
            if (showToggle) {
                measurables.getOrNull(1)?.measure(constraints.copy(minWidth = 0, minHeight = 0))
            } else {
                null
            }
        // The toggle row's space is reserved as soon as the body measures collapsible, even in
        // the frame before the toggle is composed, so the row height changes only once.
        val toggleHeight = if (showToggle) toggleRowPx else 0
        val width = max(body.width, toggle?.width ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, body.height + toggleHeight) {
            body.place(0, 0)
            toggle?.let { placeable ->
                val x = if (alignEnd) width - placeable.width else 0
                placeable.place(x, body.height)
            }
        }
    }
}

@Composable
private fun RaftLongMessageToggle(
    expanded: Boolean,
    alignEnd: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier.height(RaftLongMessageCollapseToggleRowDp.dp),
        contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        RaftTextLink(
            text =
                stringResource(
                    if (expanded) MR.strings.thread_long_message_show_less else MR.strings.thread_long_message_show_more
                ),
            onClick = onClick,
            style = RaftTheme.typography.footnote
        )
    }
}

/**
 * The conversation whose "Collapse long messages" override applies to a thread route: the channel
 * or DM itself, and for a reply thread its parent conversation. Same id the channel settings page
 * is opened with (`sourceThreadId`).
 */
internal fun RaftThreadScreenPayload.longMessageCollapseConversationId(): String? =
    if (surfaceKind == RaftThreadSurfaceKind.ReplyThread) {
        parentChannelId?.trim()?.takeIf { it.isNotEmpty() }
    } else {
        (sourceThreadId?.trim()?.takeIf { it.isNotEmpty() } ?: streamChannelId?.trim()?.takeIf { it.isNotEmpty() })
    }

/** One conversation's collapse switch as a settings row and the thread read it. */
@Stable
internal class RaftLongMessageCollapseConversationSetting(
    val preference: RaftLongMessageCollapseConversationPreference,
    val available: Boolean,
    val onSetEnabled: (Boolean) -> Unit
) {
    val effective: Boolean get() = preference.effective
}

@Composable
internal fun rememberRaftLongMessageCollapseConversationSetting(
    serverId: String?,
    conversationId: String?
): RaftLongMessageCollapseConversationSetting {
    val store = remember { RaftLongMessageCollapsePreferenceStore(raftSharedPreferenceStore()) }
    val revision by RaftLongMessageCollapsePreferenceRevision.revision.collectAsState()
    val preference =
        remember(store, serverId, conversationId, revision) {
            store.readConversation(serverId, conversationId)
        }
    LaunchedEffect(store, serverId, conversationId, revision) {
        store.pruneVoidOverride(serverId, conversationId)
    }
    return remember(store, preference, serverId, conversationId) {
        RaftLongMessageCollapseConversationSetting(
            preference = preference,
            available = !serverId.isNullOrBlank() && !conversationId.isNullOrBlank(),
            onSetEnabled = { enabled -> store.setConversationEnabled(serverId, conversationId, enabled) }
        )
    }
}

@Composable
internal fun RaftLongMessageCollapseConversationPreference.localizedDetail(): String =
    stringResource(
        when (raftLongMessageCollapseDetailKind(globalEnabled, channelOverride)) {
            RaftLongMessageCollapseDetailKind.FollowsGlobalOn -> MR.strings.conversation_long_message_collapse_follows_global_on
            RaftLongMessageCollapseDetailKind.FollowsGlobalOff -> MR.strings.conversation_long_message_collapse_follows_global_off
            RaftLongMessageCollapseDetailKind.OverrideOn -> MR.strings.conversation_long_message_collapse_override_on
            RaftLongMessageCollapseDetailKind.OverrideOff -> MR.strings.conversation_long_message_collapse_override_off
        }
    )
