package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import build.raft.designsystem.tokens.RaftUiPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Host-drawn app-shell tab bar (iOS: a system UITabBarController, Liquid Glass on iOS 26+).
 *
 * The shared shell stays the single source of truth for the tabs: the same
 * [RaftBottomTabItemPayload] list that feeds the Compose bar (order, keys, localized labels,
 * icons, badges, selection) and the same visibility rule (`showBottomTabs`) are published
 * through a [RaftNativeTabBarBridge]. The host only renders that state and reports taps back
 * through [RaftNativeTabBarBridge.selectTab], which runs the shell's ordinary tab handler
 * (select, re-tap scroll-to-top, the Search action, ...). Programmatic navigation (deep links,
 * notification opens) changes the shell's selected tab and therefore the published state.
 *
 * A host opts in per page: it registers a bridge in [RaftNativeTabBarRegistry] and passes the
 * id in the page's pageData under [RaftNativeTabBarHostIdPageDataKey] (identity only). Pages
 * without a registered bridge — Android, visual fixtures, smoke pages — keep the Compose bar.
 *
 * The host draws the bar above the whole Compose page and does not move with it, so a
 * full-height panel the page draws itself (the server drawer, which slides the shell and its
 * Compose bar aside) would sit under it; such a panel hides the bar through
 * [RaftNativeTabBarCover]. Compose `Dialog`s need nothing: CMP puts their layers above the whole
 * window hierarchy, bar included.
 *
 * Main thread only: the shell composes on the main thread and the iOS host calls in from UIKit.
 */

/** pageData key carrying the id of the host's registered [RaftNativeTabBarBridge]. */
const val RaftNativeTabBarHostIdPageDataKey = "slockNativeTabBarHostId"

/** One tab as the host renders it. Exported to Swift. */
data class RaftNativeTabBarItem(
    /** Stable shell tab key ("home", "activity", "tasks", ...); returned by [RaftNativeTabBarBridge.selectTab]. */
    val key: String,
    /** Localized label; also the accessibility label. */
    val title: String,
    /** SF Symbol for the unselected state. */
    val systemImageName: String,
    /** SF Symbol for the selected state (the host falls back to [systemImageName]). */
    val selectedSystemImageName: String,
    /** Raw badge count from the shell (0 = no badge). */
    val badgeCount: Int,
    /** Display badge text, already capped like the rest of the app ("99+"); null = no badge. */
    val badgeValue: String?,
    val enabled: Boolean,
    /**
     * Attention dot instead of a number (raft-design MobileNav / Badge); never on the selected
     * tab. The host draws a dot-only badge when [badgeValue] is null.
     */
    val attentionDot: Boolean = false,
    /**
     * Always false: Search is an ordinary tab since stdrc 2026-10-21 (option B). Kept so the iOS
     * host's item mapping stays source-compatible.
     */
    val searchAction: Boolean = false,
    /**
     * The tab's Lucide icon as an iOS asset-catalog name ("lucide-house"), the same glyph the
     * web and the Compose bar draw (artin 2026-10-21: the SF Symbols did not match the web).
     * The host prefers it and falls back to [systemImageName] when the asset is missing.
     */
    val imageAssetName: String? = null,
    /**
     * Fill and text colour (0xAARRGGBB) of a numbered badge: the raft-ui unread count's pink
     * `attention` sticker with ink figures, as on the Compose bar, rail and Home sidebar. The
     * host tints `UITabBarItem.badgeColor` / badge text with them; the dot-only badge keeps the
     * system look.
     */
    val countBadgeFillArgb: Long = RaftNativeTabBarCountBadgeFillArgb,
    val countBadgeTextArgb: Long = RaftNativeTabBarCountBadgeTextArgb,
)

internal val RaftNativeTabBarCountBadgeFillArgb: Long = RaftUiPalette.attention.toArgb().toLong() and 0xFFFFFFFFL
internal val RaftNativeTabBarCountBadgeTextArgb: Long = RaftUiPalette.stickerInk.toArgb().toLong() and 0xFFFFFFFFL

/** Everything the host needs to render the bar. Exported to Swift. */
data class RaftNativeTabBarState(
    /** Tabs in display order. */
    val items: List<RaftNativeTabBarItem>,
    /** Key of the selected tab, or null when none of [items] is selected. */
    val selectedKey: String?,
    /** False while the shell hides its bottom bar (pushed home destinations, nav rail, ...). */
    val visible: Boolean,
) {
    companion object {
        val Hidden = RaftNativeTabBarState(items = emptyList(), selectedKey = null, visible = false)
    }
}

/** Implemented by the host (Swift `RaftIosTabBarController`). Called on the main thread. */
interface RaftNativeTabBarListener {
    fun onNativeTabBarStateChanged(state: RaftNativeTabBarState)
}

/**
 * Per-page channel between the shared app shell and the host's native tab bar. Created and
 * registered by the host before the page is created; the shell publishes into it while its
 * bottom bar is composed.
 */
class RaftNativeTabBarBridge {
    private val mutableState = MutableStateFlow(RaftNativeTabBarState.Hidden)
    private var shellState = RaftNativeTabBarState.Hidden
    private val covers = mutableSetOf<Any>()
    private val mutableBottomInsetPoints = MutableStateFlow(0.0)
    private var listener: RaftNativeTabBarListener? = null
    private var publisherToken: Any? = null
    private var onSelect: ((String) -> Unit)? = null

    /** The published tab bar state (single source of truth for the host). */
    val state: StateFlow<RaftNativeTabBarState> = mutableState.asStateFlow()

    /** Swift convenience: the current value of [state]. */
    val currentState: RaftNativeTabBarState
        get() = mutableState.value

    /**
     * Height (points == dp on iOS) of the band the native bar covers at the bottom of the page,
     * measured from the bar's top edge to the page bottom (includes the home-indicator area).
     * 0 until the host has laid the bar out.
     */
    internal val bottomInsetPoints: StateFlow<Double> = mutableBottomInsetPoints.asStateFlow()

    /** Sets the single host listener and replays the current state to it. */
    fun attachListener(listener: RaftNativeTabBarListener) {
        this.listener = listener
        listener.onNativeTabBarStateChanged(mutableState.value)
    }

    fun detachListener() {
        listener = null
    }

    /**
     * Host -> shell: the user tapped the tab [key] (including a re-tap of the selected tab).
     * Runs the shell's tab handler; the resulting selection comes back through [state].
     * Returns false when no shell is currently publishing (the host must not change selection).
     */
    fun selectTab(key: String): Boolean {
        val handler = onSelect ?: return false
        if (mutableState.value.items.none { it.key == key && it.enabled }) return false
        handler(key)
        return true
    }

    /** Host -> shell: see [bottomInsetPoints]. Non-finite or negative values are ignored. */
    fun updateBottomInset(points: Double) {
        if (!points.isFinite() || points < 0.0) return
        mutableBottomInsetPoints.value = points
    }

    internal fun publish(token: Any, state: RaftNativeTabBarState, onSelect: (String) -> Unit) {
        publisherToken = token
        this.onSelect = onSelect
        shellState = state
        emit()
    }

    /** Hides the bar when [token] is still the active publisher; keeps the items for re-show. */
    internal fun retract(token: Any) {
        if (publisherToken !== token) return
        publisherToken = null
        onSelect = null
        shellState = shellState.copy(visible = false)
        emit()
    }

    /** A full-height panel drawn in the page covers the bar's place while [token] holds it. */
    internal fun cover(token: Any) {
        if (covers.add(token)) emit()
    }

    internal fun uncover(token: Any) {
        if (covers.remove(token)) emit()
    }

    private fun emit() {
        val state = if (covers.isEmpty()) shellState else shellState.copy(visible = false)
        if (mutableState.value == state) return
        mutableState.value = state
        listener?.onNativeTabBarStateChanged(state)
    }
}

/** Host-owned bridges by host id. Exported to Swift as `RaftNativeTabBarRegistry.shared`. */
object RaftNativeTabBarRegistry {
    private val bridges = mutableMapOf<String, RaftNativeTabBarBridge>()

    fun register(hostId: String, bridge: RaftNativeTabBarBridge) {
        if (hostId.isBlank()) return
        bridges[hostId] = bridge
    }

    /** Removes [hostId] only while it still maps to [bridge]. */
    fun unregister(hostId: String, bridge: RaftNativeTabBarBridge) {
        if (bridges[hostId] === bridge) bridges.remove(hostId)
    }

    internal fun bridgeFor(hostId: String?): RaftNativeTabBarBridge? =
        hostId?.takeIf { it.isNotBlank() }?.let { bridges[it] }
}

/**
 * The native tab bar bridge the host registered for the current page, if any. Provided by the
 * page host from the page's [RaftNativeTabBarHostIdPageDataKey]; null outside a hosting page
 * (Android, visual fixtures, previews), where every caller keeps its Compose behaviour.
 */
internal val LocalRaftNativeTabBarBridge = staticCompositionLocalOf<RaftNativeTabBarBridge?> { null }

@Composable
internal fun raftNativeTabBarBridge(): RaftNativeTabBarBridge? = LocalRaftNativeTabBarBridge.current

/**
 * Hides the host-drawn bar while this call is composed and [active]: the page is drawing a
 * full-height panel where the bar sits. A no-op without a host-drawn bar.
 */
@Composable
internal fun RaftNativeTabBarCover(active: Boolean = true) {
    val bridge = raftNativeTabBarBridge() ?: return
    val token = remember(bridge) { Any() }
    DisposableEffect(bridge, active) {
        if (active) bridge.cover(token) else bridge.uncover(token)
        onDispose { }
    }
    DisposableEffect(bridge) {
        onDispose { bridge.uncover(token) }
    }
}

/**
 * Publishes the shell's bottom tabs to the host while this call is composed; leaving
 * composition (the shell hid its bottom bar) hides the native bar.
 */
@Composable
internal fun RaftNativeTabBarPublisher(
    bridge: RaftNativeTabBarBridge,
    items: List<RaftBottomTabItemPayload>,
    onSelect: (String) -> Unit,
) {
    val token = remember(bridge) { Any() }
    val currentOnSelect by rememberUpdatedState(onSelect)
    val selectHandler = remember(bridge) { { key: String -> currentOnSelect(key) } }
    val state = remember(items) { raftNativeTabBarState(items, visible = true) }
    DisposableEffect(bridge, state) {
        bridge.publish(token, state, selectHandler)
        onDispose { }
    }
    DisposableEffect(bridge, token) {
        onDispose { bridge.retract(token) }
    }
}

internal fun raftNativeTabBarState(
    items: List<RaftBottomTabItemPayload>,
    visible: Boolean,
): RaftNativeTabBarState =
    RaftNativeTabBarState(
        items = items.map(::raftNativeTabBarItem),
        selectedKey = items.firstOrNull { it.selected }?.key,
        visible = visible,
    )

internal fun raftNativeTabBarItem(item: RaftBottomTabItemPayload): RaftNativeTabBarItem {
    val symbol = raftNativeTabBarSymbol(item.iconName)
    val badgeCount = item.badgeCount.coerceAtLeast(0)
    return RaftNativeTabBarItem(
        key = item.key,
        title = item.label,
        systemImageName = symbol.name,
        selectedSystemImageName = symbol.selectedName,
        badgeCount = badgeCount,
        badgeValue = raftNativeTabBarBadgeValue(badgeCount),
        enabled = item.enabled,
        attentionDot = item.attentionDot && badgeCount == 0,
        imageAssetName = raftNativeTabBarImageAssetName(item.iconName),
    )
}

/** iOS asset-catalog name of a tab's Lucide icon (`Assets.xcassets/lucide-<wire name>.imageset`). */
internal fun raftNativeTabBarImageAssetName(icon: RaftLucideIconName): String = "lucide-${icon.wireName}"

/** Same cap as the Home sidebar badges: null at 0, "99+" from 100. */
internal fun raftNativeTabBarBadgeValue(count: Int): String? = formatHomeSidebarBadgeCount(count)

internal data class RaftNativeTabBarSymbol(val name: String, val selectedName: String)

/**
 * Lucide tab icon -> SF Symbol. Every icon `appShellTabIcon` can return has an explicit
 * mapping; anything else falls back to a circle so a new tab still renders.
 */
internal fun raftNativeTabBarSymbol(icon: RaftLucideIconName): RaftNativeTabBarSymbol =
    when (icon) {
        RaftLucideIconName.House -> RaftNativeTabBarSymbol("house", "house.fill")
        RaftLucideIconName.MessageCircle -> RaftNativeTabBarSymbol("message", "message.fill")
        RaftLucideIconName.Bell -> RaftNativeTabBarSymbol("bell", "bell.fill")
        // Lucide "activity" is a pulse line. The bare `waveform.path.ecg` is a wide, flat glyph
        // whose ink sits on the mid line with thin peaks, so in a UITabBar it reads smaller and
        // higher than the boxed house / checkmark.square neighbours (stdrc 2026-10-21: "floats").
        // The rectangle variant carries the same pulse in a rounded square on the symbol
        // baseline, the same footprint as checkmark.square, and has a filled selected state.
        RaftLucideIconName.Activity ->
            RaftNativeTabBarSymbol("waveform.path.ecg.rectangle", "waveform.path.ecg.rectangle.fill")
        RaftLucideIconName.Ellipsis -> RaftNativeTabBarSymbol("ellipsis", "ellipsis")
        RaftLucideIconName.SquareCheckBig -> RaftNativeTabBarSymbol("checkmark.square", "checkmark.square.fill")
        RaftLucideIconName.Users -> RaftNativeTabBarSymbol("person.2", "person.2.fill")
        RaftLucideIconName.Settings -> RaftNativeTabBarSymbol("gearshape", "gearshape.fill")
        RaftLucideIconName.Search -> RaftNativeTabBarSymbol("magnifyingglass", "magnifyingglass")
        RaftLucideIconName.Inbox -> RaftNativeTabBarSymbol("tray", "tray.fill")
        RaftLucideIconName.Bookmark -> RaftNativeTabBarSymbol("bookmark", "bookmark.fill")
        else -> RaftNativeTabBarSymbol("circle", "circle.fill")
    }
