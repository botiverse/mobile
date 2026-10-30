package build.raft.app

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.MutatePriority
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.launch

/*
 * PROTOTYPE (agent/lagoon/ios-native-nav-proto, not for merge): the page's top bar drawn by the
 * host's real UINavigationBar instead of a Compose header.
 *
 * A host opts a page in like the native tab bar: it registers a [RaftNativeNavBarBridge] in
 * [RaftNativeNavBarRegistry] and passes the id in pageData under [RaftNativeNavBarHostIdPageDataKey].
 * [RaftTopPageHeader] (and so every head built on it, the conversation head included) then
 * publishes its title, subtitle, actions and title extras to the bridge and draws only a spacer
 * for the host bar's height. Without a bridge (Android, desktop, any page the host does not opt
 * in) nothing changes.
 *
 * Large-title collapse (phase 1): Compose lists are not UIScrollViews, so UIKit cannot see them
 * scroll. The host puts a hidden UIScrollView (the "mirror") behind the page and makes it the
 * navigation controller's content scroll view; the page's primary list
 * ([raftNativeNavBarPrimaryList]) reports its scroll position, which the host writes into the
 * mirror's contentOffset, so UIKit collapses / expands the large title as the list scrolls.
 * To keep content from jumping while the bar changes height, the page does not follow the live
 * safe area during the collapse: a nested-scroll connection consumes the first
 * [RaftNativeNavBarBridge.collapseRange] points of an upward scroll as "collapse" (the list stays
 * put, the head's spacer shrinks by exactly that amount, the bar shrinks by the same amount),
 * and gives them back on the way down once the list is at its top — UIKit's own large-title
 * behaviour. A release in between snaps to collapsed / expanded, like UIKit.
 */

/** pageData key carrying the id of the host's registered [RaftNativeNavBarBridge]. */
const val RaftNativeNavBarHostIdPageDataKey = "slockNativeNavBarHostId"

/** One trailing bar button: [systemSymbol] is an SF Symbol name; [badge] a count on the button. */
data class RaftNativeNavBarAction(
    val id: String,
    val label: String,
    val systemSymbol: String,
    val onClick: () -> Unit,
    val badge: Int? = null,
)

/** The leading image of the host's title view. */
sealed interface RaftNativeNavBarTitleLeading {
    /** The conversation's channel glyph tile: `channel` / `private` / `joint`, or on the public page. */
    data class Channel(val channelKind: String?, val onPublicPage: Boolean) : RaftNativeNavBarTitleLeading

    /** A DM peer's avatar: [imageUrl] when it is a plain http(s) image, else [label]'s initials on [fillArgb]. */
    data class Avatar(val label: String, val imageUrl: String?, val fillArgb: String) : RaftNativeNavBarTitleLeading
}

/** What the old Compose head carried beside the title: drawn by the host's title view. */
data class RaftNativeNavBarTitleExtras(
    val leading: RaftNativeNavBarTitleLeading? = null,
    /** A count next to the title (the members page's roster count). */
    val badge: String? = null,
    /** The head's refresh spinner. */
    val refreshing: Boolean = false,
)

/** Implemented by the host (Swift). Called on the main thread. */
interface RaftNativeNavBarListener {
    /**
     * Show [title] / [subtitle], the trailing buttons of [actionsJson]
     * (`[{"id":..,"label":..,"systemSymbol":..,"badge":..}]`, in order) and the title view extras of
     * [extrasJson] (`{"leading":{"kind":"channel"|"avatar",..},"badge":..,"refreshing":..}`).
     */
    fun onNativeNavBarChanged(title: String, subtitle: String?, actionsJson: String, extrasJson: String)

    /**
     * The primary list scrolled: [offset] points from the expanded top (collapse first, then the
     * list's own offset), [contentHeight] the list's estimated content height in points.
     */
    fun onNativeNavBarScroll(offset: Double, contentHeight: Double, dragging: Boolean)
}

class RaftNativeNavBarBridge {
    /**
     * False: the host does not draw the bar (the prototype switched off) and registered the
     * bridge only so the capture script can scroll the page's primary list the same way in both
     * modes. Set before the page is created.
     */
    var hostDrawsBar: Boolean = true

    private var listener: RaftNativeNavBarListener? = null
    private var title: String? = null
    private var subtitle: String? = null
    private var actionsJson: String = "[]"
    private var extrasJson: String = "{}"
    private var handlers: Map<String, () -> Unit> = emptyMap()

    /** Sets the single host listener and replays what the page published so far. */
    fun attachListener(listener: RaftNativeNavBarListener) {
        this.listener = listener
        title?.let { listener.onNativeNavBarChanged(it, subtitle, actionsJson, extrasJson) }
        reportScroll()
    }

    fun detachListener() {
        listener = null
    }

    /** Host -> page: the bar button [id] was tapped. False when the page has no such action. */
    fun actionTapped(id: String): Boolean {
        val handler = handlers[id] ?: return false
        handler()
        return true
    }

    internal fun publish(
        title: String,
        subtitle: String?,
        actions: List<RaftNativeNavBarAction>,
        extras: RaftNativeNavBarTitleExtras,
    ) {
        handlers = actions.associate { it.id to it.onClick }
        val json =
            buildJsonArray {
                actions.forEach { action ->
                    addJsonObject {
                        put("id", action.id)
                        put("label", action.label)
                        put("systemSymbol", action.systemSymbol)
                        action.badge?.let { put("badge", it) }
                    }
                }
            }.toString()
        val extrasJson = extras.toJson().toString()
        if (title == this.title && subtitle == this.subtitle && json == actionsJson && extrasJson == this.extrasJson) return
        this.title = title
        this.subtitle = subtitle
        actionsJson = json
        this.extrasJson = extrasJson
        listener?.onNativeNavBarChanged(title, subtitle, json, extrasJson)
    }

    // --- Large-title collapse -------------------------------------------------------------------

    /** Host: the page's top safe area (points) with the large title expanded; 0 = unknown. */
    internal var expandedTop by mutableFloatStateOf(0f)
        private set

    /** Host: how many points the large title collapses by; 0 = no large title (inline). */
    internal var collapseRange by mutableFloatStateOf(0f)
        private set

    /** Page: how far the large title is collapsed (0..[collapseRange]), in points. */
    internal var collapse by mutableFloatStateOf(0f)

    /** Page: the primary list's own offset past the top and its estimated content height (points). */
    internal var listOffset = 0f
    internal var listContentHeight = 0f

    /** Page: a finger (or the capture script) is on the list, or its fling / snap is running. */
    internal var dragging = false

    internal val collapseActive: Boolean get() = expandedTop > 0f && collapseRange > 0f

    /**
     * Host -> page: the large-title geometry, measured on the host with the title expanded.
     * [collapseRange] 0 switches the collapse off (the page then follows the live safe area).
     */
    fun setLargeTitleMetrics(expandedTop: Double, collapseRange: Double) {
        this.expandedTop = expandedTop.toFloat().coerceAtLeast(0f)
        this.collapseRange = collapseRange.toFloat().coerceAtLeast(0f)
        collapse = collapse.coerceIn(0f, this.collapseRange)
        reportScroll()
    }

    internal fun reportScroll() {
        listener?.onNativeNavBarScroll((collapse + listOffset).toDouble(), listContentHeight.toDouble(), dragging)
    }

    internal var scriptedScrollHandler: ((Float) -> Unit)? = null
    internal var scriptedScrollEndHandler: (() -> Unit)? = null

    /**
     * Capture script only: scrolls the primary list by [dy] points (positive = the finger moves up)
     * through the same nested-scroll chain a drag uses. False when the page has no primary list.
     */
    fun scriptedScroll(dy: Double): Boolean {
        val handler = scriptedScrollHandler ?: return false
        handler(dy.toFloat())
        return true
    }

    /** Capture script only: the scripted drag was released (zero velocity: a mid-collapse snaps). */
    fun scriptedScrollEnd() {
        scriptedScrollEndHandler?.invoke()
    }
}

private fun RaftNativeNavBarTitleExtras.toJson(): JsonObject =
    buildJsonObject {
        when (val lead = leading) {
            is RaftNativeNavBarTitleLeading.Channel ->
                put(
                    "leading",
                    buildJsonObject {
                        put("kind", "channel")
                        put("channelKind", lead.channelKind?.lowercase() ?: "channel")
                        put("publicPage", lead.onPublicPage)
                        put("tileArgb", raftArgbHex(RaftColor.Brand))
                        put("inkArgb", raftArgbHex(RaftColor.StickerInk))
                    }
                )
            is RaftNativeNavBarTitleLeading.Avatar ->
                put(
                    "leading",
                    buildJsonObject {
                        put("kind", "avatar")
                        put("label", lead.label)
                        lead.imageUrl?.let { put("imageUrl", it) }
                        put("fillArgb", lead.fillArgb)
                    }
                )
            null -> Unit
        }
        badge?.let { put("badge", it) }
        if (refreshing) put("refreshing", true)
    }

/** Host-owned bridges by host id. Exported to Swift as `RaftNativeNavBarRegistry.shared`. */
object RaftNativeNavBarRegistry {
    private val bridges = mutableMapOf<String, RaftNativeNavBarBridge>()

    fun register(hostId: String, bridge: RaftNativeNavBarBridge) {
        if (hostId.isBlank()) return
        bridges[hostId] = bridge
    }

    fun unregister(hostId: String, bridge: RaftNativeNavBarBridge) {
        if (bridges[hostId] === bridge) bridges.remove(hostId)
    }

    internal fun bridgeFor(hostId: String?): RaftNativeNavBarBridge? =
        hostId?.takeIf { it.isNotBlank() }?.let { bridges[it] }
}

/** The host bar of the current page, if the host draws one (null: the page draws its head). */
val LocalRaftNativeNavBar = staticCompositionLocalOf<RaftNativeNavBarBridge?> { null }

/** The page's registered bridge even when the host does not draw the bar (capture scripting). */
val LocalRaftNativeNavBarScript = staticCompositionLocalOf<RaftNativeNavBarBridge?> { null }

/**
 * Publishes the head to the host bar when there is one. True when the host draws it (the caller
 * then draws only [RaftNativeNavBarHeadSpacer]).
 */
@Composable
internal fun raftNativeNavBarPublish(
    title: String,
    subtitle: String?,
    actions: List<RaftNativeNavBarAction>,
    extras: RaftNativeNavBarTitleExtras = RaftNativeNavBarTitleExtras(),
): Boolean {
    val bridge = LocalRaftNativeNavBar.current ?: return false
    SideEffect { bridge.publish(title, subtitle?.takeIf { it.isNotBlank() }, actions, extras) }
    return true
}

/**
 * The space the host bar takes above the page. With a collapsing large title it is the page's own
 * `expandedTop - collapse` (read at layout, so a scroll frame only re-lays the spacer out), never
 * the live safe area: the safe area follows the bar a frame later through the insets event, which
 * would make the content jump. Otherwise the live top safe area.
 */
@Composable
internal fun RaftNativeNavBarHeadSpacer(bridge: RaftNativeNavBarBridge, modifier: Modifier = Modifier, liveTop: Dp) {
    androidx.compose.foundation.layout.Box(
        modifier =
            modifier.layout { measurable, constraints ->
                val heightPt = if (bridge.collapseActive) (bridge.expandedTop - bridge.collapse).coerceAtLeast(0f) else liveTop.value
                val height = heightPt.dp.roundToPx().coerceAtLeast(0)
                val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                layout(constraints.maxWidth, height) { placeable.place(0, 0) }
            }
    )
}

private fun LazyListState.isAtTop(): Boolean = firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0

/** UIKit's large-title rule: collapse before the list scrolls up, expand once it is back at its top. */
private class RaftNativeNavBarCollapseConnection(
    private val bridge: RaftNativeNavBarBridge,
    private val state: LazyListState,
    private val density: Float,
) : NestedScrollConnection {
    private fun take(dyPt: Float): Float {
        // dyPt < 0: content moving up (collapse); > 0: moving down (expand).
        val before = bridge.collapse
        bridge.collapse = (before - dyPt).coerceIn(0f, bridge.collapseRange)
        val used = bridge.collapse - before
        if (used != 0f) bridge.reportScroll()
        return -used
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && !bridge.dragging) {
            bridge.dragging = true
            bridge.reportScroll()
        }
        if (!bridge.collapseActive) return Offset.Zero
        val dyPt = available.y / density
        val used =
            when {
                dyPt < 0f && bridge.collapse < bridge.collapseRange -> take(dyPt)
                dyPt > 0f && bridge.collapse > 0f && state.isAtTop() -> take(dyPt)
                else -> 0f
            }
        return Offset(0f, used * density)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (!bridge.collapseActive) return Offset.Zero
        val dyPt = available.y / density
        // The list reached its top within this delta: the rest expands the title.
        if (dyPt > 0f && bridge.collapse > 0f) return Offset(0f, take(dyPt) * density)
        return Offset.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        snap()
        if (bridge.dragging) {
            bridge.dragging = false
            bridge.reportScroll()
        }
        return Velocity.Zero
    }

    suspend fun snap() {
        val range = bridge.collapseRange
        val from = bridge.collapse
        if (!bridge.collapseActive || from <= 0f || from >= range) return
        val target = if (from > range / 2f) range else 0f
        animate(from, target, animationSpec = tween(durationMillis = 220)) { value, _ ->
            bridge.collapse = value
            bridge.reportScroll()
        }
    }
}

/**
 * Marks [state] as the page's primary list for the host bar: its scroll drives the large-title
 * collapse (see the file comment) and its offset is mirrored into the host's hidden scroll view.
 * No-op without a host bar (Android, desktop, pages the host does not opt in).
 */
@Composable
internal fun Modifier.raftNativeNavBarPrimaryList(state: LazyListState): Modifier {
    val bridge = LocalRaftNativeNavBarScript.current ?: return this
    val drawsBar = LocalRaftNativeNavBar.current != null
    val density = LocalDensity.current.density
    val connection = remember(bridge, state, density) { RaftNativeNavBarCollapseConnection(bridge, state, density) }
    val dispatcher = remember { NestedScrollDispatcher() }
    val scope = rememberCoroutineScope()
    val innerConnection = remember { object : NestedScrollConnection {} }
    DisposableEffect(bridge, state, dispatcher) {
        bridge.scriptedScrollHandler = { dyPt ->
            // What Compose's scrollable does for one drag delta, inside the list's scroll scope:
            // pre-scroll up the chain, the list takes what it can, post-scroll the rest.
            scope.launch {
                state.scroll(MutatePriority.UserInput) {
                    val delta = Offset(0f, -dyPt * density)
                    val pre = dispatcher.dispatchPreScroll(delta, NestedScrollSource.UserInput)
                    val left = delta - pre
                    var listConsumed = -scrollBy(-left.y)
                    if (listConsumed == 0f && left.y != 0f) {
                        // Capture diagnostics: the scope's scrollBy moved nothing; try the raw path.
                        listConsumed = -state.dispatchRawDelta(-left.y)
                    }
                    println(
                        "RAFT_PROTO_SCRIPT_SCROLL dy=$dyPt pre=${pre.y} left=${left.y} consumed=$listConsumed " +
                            "first=${state.firstVisibleItemIndex}/${state.firstVisibleItemScrollOffset} " +
                            "fwd=${state.canScrollForward} items=${state.layoutInfo.totalItemsCount} " +
                            "viewport=${state.layoutInfo.viewportSize}"
                    )
                    dispatcher.dispatchPostScroll(
                        Offset(0f, listConsumed),
                        Offset(0f, left.y - listConsumed),
                        NestedScrollSource.UserInput
                    )
                }
            }
        }
        bridge.scriptedScrollEndHandler = {
            // The release: a zero-velocity fling through the chain (a mid-collapse snaps).
            scope.launch {
                val pre = dispatcher.dispatchPreFling(Velocity.Zero)
                dispatcher.dispatchPostFling(Velocity.Zero - pre, Velocity.Zero)
            }
        }
        onDispose {
            bridge.scriptedScrollHandler = null
            bridge.scriptedScrollEndHandler = null
        }
    }
    if (!drawsBar) return this.nestedScroll(innerConnection, dispatcher)
    LaunchedEffect(bridge, state, density) {
        snapshotFlow {
            val info = state.layoutInfo
            val items = info.visibleItemsInfo
            val average = if (items.isEmpty()) 0f else items.sumOf { it.size }.toFloat() / items.size
            val offsetPx =
                if (state.firstVisibleItemIndex == 0) {
                    state.firstVisibleItemScrollOffset.toFloat()
                } else {
                    // Past the first item: an estimate is enough (UIKit only needs "scrolled").
                    state.firstVisibleItemIndex * average + state.firstVisibleItemScrollOffset
                }
            Pair(offsetPx / density, info.totalItemsCount * average / density)
        }.collect { (offset, height) ->
            bridge.listOffset = offset
            bridge.listContentHeight = height
            bridge.reportScroll()
        }
    }
    return this.nestedScroll(connection).nestedScroll(innerConnection, dispatcher)
}
