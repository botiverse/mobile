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
import build.raft.app.compat.ui.LocalRaftPageActive
import build.raft.designsystem.components.RaftSideAction

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

/** Where a bar button goes. */
enum class RaftNativeNavBarPlacement { Leading, Trailing, Overflow }

/**
 * One bar button (or ⋯ / title-menu row): [systemSymbol] is an SF Symbol name (null: a text
 * button, or a menu row without an image); [badge] a count on the button; [attention] the "new"
 * dot (the bell); [checked] a menu row's checkmark (the current server); [children] makes it a
 * submenu (a button whose tap opens the menu).
 */
data class RaftNativeNavBarAction(
    val id: String,
    val label: String,
    val systemSymbol: String?,
    val onClick: () -> Unit,
    val badge: Int? = null,
    val attention: Boolean = false,
    val enabled: Boolean = true,
    val checked: Boolean = false,
    val destructive: Boolean = false,
    val placement: RaftNativeNavBarPlacement = RaftNativeNavBarPlacement.Trailing,
    val children: List<RaftNativeNavBarAction> = emptyList(),
    /** Ranks for the trailing buttons: the higher stay out of the ⋯ longer (Duo column rule). */
    val priority: Int = 0,
)

/** At most this many trailing buttons beside the ⋯ (the Duo column's two-action rule). */
internal const val RaftNativeNavBarMaxTrailing = 2

/**
 * The trailing buttons as the bar shows them: up to [RaftNativeNavBarMaxTrailing] buttons by
 * [RaftNativeNavBarAction.priority] (ties: the one listed first), the rest in the ⋯, both in listed
 * order. Leading and explicit ⋯ rows keep their placement.
 */
internal fun raftNativeNavBarSplit(actions: List<RaftNativeNavBarAction>): List<RaftNativeNavBarAction> {
    val trailing = actions.withIndex().filter { it.value.placement == RaftNativeNavBarPlacement.Trailing }
    val forcedOverflow = actions.any { it.placement == RaftNativeNavBarPlacement.Overflow }
    val room = if (forcedOverflow || trailing.size > RaftNativeNavBarMaxTrailing) RaftNativeNavBarMaxTrailing else trailing.size
    val kept =
        trailing.sortedWith(compareByDescending<IndexedValue<RaftNativeNavBarAction>> { it.value.priority }.thenBy { it.index })
            .take(room)
            .map { it.index }
            .toSet()
    return actions.mapIndexed { index, action ->
        if (action.placement == RaftNativeNavBarPlacement.Trailing && index !in kept) {
            action.copy(placement = RaftNativeNavBarPlacement.Overflow)
        } else {
            action
        }
    }
}

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

/** Everything one head publishes to the host bar. */
internal data class RaftNativeNavBarHead(
    val title: String,
    val subtitle: String?,
    val actions: List<RaftNativeNavBarAction>,
    val extras: RaftNativeNavBarTitleExtras,
    /** The title's menu (Home's server switcher): a chevron beside the title opens it. */
    val titleMenu: List<RaftNativeNavBarAction> = emptyList(),
    /** The head's own Back (an in-page sub-view's); null: the stack's Back (or none at its root). */
    val onBack: (() -> Unit)? = null,
    /** A tap on the title (scroll to top, the Settings developer unlock). */
    val onTitleClick: (() -> Unit)? = null,
)

/** Implemented by the host (Swift). Called on the main thread. */
interface RaftNativeNavBarListener {
    /**
     * Show [title] / [subtitle], the bar buttons of [actionsJson] (`[{"id","label","systemSymbol",
     * "badge","attention","enabled","checked","destructive","placement":"leading"|"trailing"|
     * "overflow","children":[..]}]`, in order), the title view extras of [extrasJson]
     * (`{"leading":{"kind":"channel"|"avatar",..},"badge":..,"refreshing":..}`) and the title menu
     * rows of [titleMenuJson] (same row shape; `[]` for none). [hasBack]: the head has a Back of
     * its own (`actionTapped("back")`); [nested]: the head is not the page's first (an in-page
     * sub-view over it). A pushed page pops with the stack's Back unless the head is [nested] with
     * a Back; at the stack's root the host shows the head's Back as a leading button.
     */
    fun onNativeNavBarChanged(
        title: String,
        subtitle: String?,
        actionsJson: String,
        extrasJson: String,
        titleMenuJson: String,
        hasBack: Boolean,
        nested: Boolean,
    )

    /** No head on screen publishes (a tab with its own Compose head): hide the bar. */
    fun onNativeNavBarCleared()

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

    /** One publishing head; the one activated last that is still on screen is the bar's. */
    internal class Entry {
        var head: RaftNativeNavBarHead? = null
    }

    private val entries = mutableListOf<Entry>()
    private var listener: RaftNativeNavBarListener? = null
    private var lastWire: List<Any?>? = null
    private var handlers: Map<String, () -> Unit> = emptyMap()

    /** Sets the single host listener and replays what the page published so far. */
    fun attachListener(listener: RaftNativeNavBarListener) {
        this.listener = listener
        lastWire = null
        // Nothing published yet: the host keeps its bar until the page's head arrives.
        if (entries.any { it.head != null }) send()
        reportScroll()
    }

    fun detachListener() {
        listener = null
    }

    /** Host -> page: the bar button / menu row [id] (or `back`) was tapped. False when there is none. */
    fun actionTapped(id: String): Boolean {
        val handler = handlers[id] ?: return false
        handler()
        return true
    }

    internal fun activate(entry: Entry) {
        entries.remove(entry)
        entries.add(entry)
        // A head that has not published yet sends from its first publish.
        if (entry.head != null) send()
    }

    internal fun deactivate(entry: Entry) {
        if (!entries.remove(entry)) return
        send()
    }

    internal fun publish(entry: Entry, head: RaftNativeNavBarHead) {
        entry.head = head
        if (entry in entries && entries.lastOrNull { it.head != null } === entry) send()
    }

    private fun send() {
        val entry = entries.lastOrNull { it.head != null }
        val head = entry?.head
        if (head == null) {
            handlers = emptyMap()
            if (lastWire != null && lastWire?.isEmpty() == true) return
            lastWire = emptyList()
            listener?.onNativeNavBarCleared()
            return
        }
        val actions = raftNativeNavBarSplit(head.actions)
        val hasBack = head.onBack != null
        val nested = entries.firstOrNull { it.head != null } !== entry
        handlers =
            buildMap {
                fun addAll(list: List<RaftNativeNavBarAction>) {
                    list.forEach { action ->
                        put(action.id, action.onClick)
                        addAll(action.children)
                    }
                }
                addAll(actions)
                addAll(head.titleMenu)
                head.onBack?.let { put(RaftNativeNavBarBackId, it) }
                head.onTitleClick?.let { put(RaftNativeNavBarTitleId, it) }
            }
        val actionsJson = actions.toJsonArray().toString()
        val titleMenuJson = head.titleMenu.toJsonArray().toString()
        val extrasJson =
            head.extras.toJson().let { extras ->
                if (head.onTitleClick == null) extras else JsonObject(extras + ("titleTap" to kotlinx.serialization.json.JsonPrimitive(true)))
            }.toString()
        val subtitle = head.subtitle?.takeIf { it.isNotBlank() }
        val wire = listOf(head.title, subtitle, actionsJson, extrasJson, titleMenuJson, hasBack, nested)
        if (wire == lastWire) return
        lastWire = wire
        listener?.onNativeNavBarChanged(head.title, subtitle, actionsJson, extrasJson, titleMenuJson, hasBack, nested)
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

/** The handler id of a head's own Back ([RaftNativeNavBarHead.onBack]). */
internal const val RaftNativeNavBarBackId = "back"

/** The handler id of a title tap ([RaftNativeNavBarHead.onTitleClick]); extras carry `"titleTap":true`. */
internal const val RaftNativeNavBarTitleId = "title"

private fun List<RaftNativeNavBarAction>.toJsonArray(): kotlinx.serialization.json.JsonArray =
    buildJsonArray {
        forEach { action ->
            addJsonObject {
                put("id", action.id)
                put("label", action.label)
                action.systemSymbol?.let { put("systemSymbol", it) }
                action.badge?.let { put("badge", it) }
                if (action.attention) put("attention", true)
                if (!action.enabled) put("enabled", false)
                if (action.checked) put("checked", true)
                if (action.destructive) put("destructive", true)
                put("placement", action.placement.name.lowercase())
                if (action.children.isNotEmpty()) put("children", action.children.toJsonArray())
            }
        }
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
 * then draws only [RaftNativeNavBarHeadSpacer]). Only while the enclosing page is active (a
 * kept-alive shell tab publishes when it is selected); the head published last that is still on
 * screen is the bar's, so an in-page sub-view's head covers its page's and gives it back.
 */
@Composable
internal fun raftNativeNavBarPublish(
    title: String,
    subtitle: String?,
    actions: List<RaftNativeNavBarAction>,
    extras: RaftNativeNavBarTitleExtras = RaftNativeNavBarTitleExtras(),
    titleMenu: List<RaftNativeNavBarAction> = emptyList(),
    onBack: (() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
): Boolean {
    val drawn = LocalRaftNativeNavBar.current
    // Capture only: with the prototype off the page still registers its actions in the script
    // bridge (nothing is drawn by the host), so one capture script drives both modes alike.
    val bridge = drawn ?: LocalRaftNativeNavBarScript.current ?: return false
    val active = LocalRaftPageActive.current
    val entry = remember(bridge) { RaftNativeNavBarBridge.Entry() }
    DisposableEffect(bridge, entry, active) {
        if (active) bridge.activate(entry)
        onDispose { bridge.deactivate(entry) }
    }
    val head = RaftNativeNavBarHead(title, subtitle, actions, extras, titleMenu, onBack, onTitleClick)
    SideEffect { if (active) bridge.publish(entry, head) }
    return drawn != null
}

/**
 * The Duo side column's actions ([RaftSideAction], the one source of a head's buttons) as bar
 * buttons: the same ids, labels, enabled / attention states and the same two-visible rule (a
 * negative [RaftSideAction.priority] goes to the ⋯ first). An action without an SF Symbol gets
 * the one [raftNativeNavBarSymbolFor] knows for its id, else none (a text button / a plain row).
 */
internal fun List<RaftSideAction>.toRaftNativeNavBarActions(): List<RaftNativeNavBarAction> =
    map { action ->
        RaftNativeNavBarAction(
            id = action.id,
            label = action.label,
            systemSymbol = action.systemSymbol ?: raftNativeNavBarSymbolFor(action.id),
            onClick = action.onClick,
            attention = action.attention,
            enabled = action.enabled,
            priority = if (action.keepVisible) Int.MAX_VALUE else action.priority,
        )
    }

/** SF Symbols for the side-column action ids whose column draws a Lucide glyph (prototype table). */
internal fun raftNativeNavBarSymbolFor(id: String): String? =
    when (id) {
        "home-bell" -> "bell"
        "thread-search", SEARCH_IN_TASK_MENU_ITEM_ID -> "magnifyingglass"
        "thread-settings" -> "gearshape"
        "thread-open-in-channel", VIEW_TASK_IN_CHANNEL_MENU_ITEM_ID -> "mappin.and.ellipse"
        FOCUS_MODE_MENU_ITEM_ID -> "arrow.up.left.and.arrow.down.right"
        MARK_THREAD_UNREAD_MENU_ITEM_ID -> "message.badge"
        FOLLOW_THREAD_MENU_ITEM_ID -> "bell.badge"
        else -> null
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
