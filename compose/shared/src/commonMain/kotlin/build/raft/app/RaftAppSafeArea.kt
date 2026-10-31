package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import build.raft.app.compat.pager.LocalActivity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.IPagerEventObserver
import build.raft.app.compat.pager.Pager
import build.raft.designsystem.components.LocalRaftSafeAreaInsets
import build.raft.designsystem.components.RaftSafeAreaInsets

private const val KEY_NATIVE_SAFE_AREA_INSETS_ARE_PIXELS = "slockNativeSafeAreaInsetsArePixels"
private const val KEY_KEYBOARD_INSET_EXCLUDES_BOTTOM_SAFE_AREA =
    "slockKeyboardInsetExcludesBottomSafeArea"
private const val KEY_STATUS_BAR_HIDDEN = "slockStatusBarHidden"
private const val KEY_HOST_WINDOW_ID = "slockWindowId"
private const val WINDOW_INSETS_PAGER_EVENT = "slockWindowInsetsChanged"
private const val DEFAULT_HOST_WINDOW_ID = "host:default"

/**
 * task #482: live host window-inset snapshots, keyed by the window id the host
 * injects into pageData (`slockWindowId`). Per-window — a second Activity or
 * display never leaks into this window's safe area; each authoritative
 * dispatch replaces the entry (zero included) so a shrinking or hiding taskbar
 * frees the reserved padding.
 *
 * Sources, by precedence:
 *   1. `slockWindowInsetsChanged` pager events — authoritative; they REPLACE
 *      the snapshot and are what child pagers receive via the existing
 *      parent-pager event forwarding in SlockKuiklyChildPageView.
 *   2. `slockPublishHostWindowInsetsSnapshot(params)` — attach-time seed only;
 *      used when no live event has been recorded for the window yet, and only
 *      when the params actually carry inset fields.
 *
 * The map is observable: readers invalidate when an entry is first inserted or
 * its state changes, so a pager composed before the first dispatch re-reads on
 * insert.
 */
internal class RaftHostWindowInsetSnapshot(values: RaftHostWindowInsetValues) {
    /** Null payload = no measurement yet (distinct from a real 0px value). */
    val state = androidx.compose.runtime.mutableStateOf(values)
}

/**
 * One host window-inset measurement, in pixels. [leftPx] / [rightPx] are PHYSICAL edges (the
 * iOS host's `safeAreaInsets.left/right`); the Android host does not send them and they stay 0
 * there, because Android pages keep their existing inset handling.
 */
internal data class RaftHostWindowInsetValues(
    val topPx: Float,
    val bottomPx: Float,
    val statusBarHidden: Boolean?,
    val leftPx: Float = 0f,
    val rightPx: Float = 0f,
    /**
     * The page is the content of a sheet (iOS page or form sheet): [topPx] is the sheet's own top
     * safe area, about 0, so [raftTopSafeAreaPadding] keeps [RaftSheetHeaderTopMargin] above the
     * header instead.
     */
    val presentedAsSheet: Boolean = false,
)

/**
 * The top margin a sheet-presented page keeps above its header: the sheet's top edge is not the
 * screen's, its own top safe area is about 0, and the header must not touch it. The same margin
 * as a page under a hidden status bar.
 */
internal val RaftSheetHeaderTopMargin: Dp = RaftAppSpacing.Md

/**
 * The top inset of a page from a live host [values] snapshot, in dp: [RaftSheetHeaderTopMargin]
 * at least for a sheet-presented page, otherwise the host value as is (0 means 0).
 */
internal fun resolveRaftLiveTopSafeArea(values: RaftHostWindowInsetValues, density: Float): Dp {
    val top = if (density > 0f) (values.topPx.coerceAtLeast(0f) / density).dp else 0.dp
    return if (values.presentedAsSheet) maxOf(top, RaftSheetHeaderTopMargin) else top
}

/**
 * Parses one `slockWindowInsetsChanged` payload: `statusBarHeightPx` (top),
 * `navigationBarHeightPx` (bottom), `safeAreaLeftPx`, `safeAreaRightPx` (physical edges) and
 * `statusBarHidden`. Missing, blank, negative or non-finite values read as 0.
 */
internal fun raftHostWindowInsetsFromEvent(eventData: JSONObject): RaftHostWindowInsetValues =
    RaftHostWindowInsetValues(
        topPx = eventData.insetPx("statusBarHeightPx"),
        bottomPx = eventData.insetPx("navigationBarHeightPx"),
        statusBarHidden =
            if (eventData.has("statusBarHidden")) eventData.optBoolean("statusBarHidden") else null,
        leftPx = eventData.insetPx("safeAreaLeftPx"),
        rightPx = eventData.insetPx("safeAreaRightPx"),
        presentedAsSheet = eventData.optBoolean("presentedAsSheet", false),
    )

private fun JSONObject.insetPx(key: String): Float =
    optString(key).toFloatOrNull()?.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f

internal object RaftHostWindowInsets {
    // Observable: inserting a missing window entry must invalidate readers that
    // previously saw null.
    private val snapshots = mutableStateMapOf<String, RaftHostWindowInsetSnapshot>()

    /**
     * Windows whose latest write came from a live pager event. A pageData
     * seed publish is ignored for those windows — params are attach-time
     * state and would be stale.
     */
    private val liveSeen = mutableSetOf<String>()

    fun publish(
        windowId: String,
        topPx: Float,
        bottomPx: Float,
        statusBarHidden: Boolean?,
        fromLiveEvent: Boolean = true,
        leftPx: Float = 0f,
        rightPx: Float = 0f,
    ) {
        publish(
            windowId,
            RaftHostWindowInsetValues(topPx, bottomPx, statusBarHidden, leftPx, rightPx),
            fromLiveEvent,
        )
    }

    fun publish(windowId: String, values: RaftHostWindowInsetValues, fromLiveEvent: Boolean = true) {
        val key = windowId.trim().ifEmpty { DEFAULT_HOST_WINDOW_ID }
        if (!fromLiveEvent && key in liveSeen) return
        if (fromLiveEvent) liveSeen.add(key)
        val entry = snapshots.getOrPut(key) { RaftHostWindowInsetSnapshot(values) }
        entry.state.value = values
    }

    /**
     * Forgets the window: its snapshot and its live-event mark. The iOS host keys snapshots per
     * page controller (`ios:page:<uuid>`) and calls this when the controller goes away, so the
     * map does not grow for the whole session. A page still composed for that id falls back to
     * its pageData, as before its first publish. Main thread only.
     */
    fun remove(windowId: String) {
        val key = windowId.trim().ifEmpty { DEFAULT_HOST_WINDOW_ID }
        liveSeen.remove(key)
        snapshots.remove(key)
    }

    /**
     * Current authoritative snapshot for the window, or null when nothing has
     * been published. Reading the map (not just a pre-created state) keeps the
     * lookup observable for readers composed before the first publish.
     */
    fun current(windowId: String): RaftHostWindowInsetValues? =
        snapshots[windowId]?.state?.value

    internal fun seedFromPageDataIfPresent(params: JSONObject) {
        val hasInsetField =
            params.has("safeAreaTop") || params.has("statusBarHeight") ||
                params.has("safeAreaBottom") || params.has("navigationBarHeight") ||
                params.has("safeAreaLeft") || params.has("safeAreaRight")
        if (!hasInsetField) return
        RaftHostWindowInsets.publish(
            windowId = params.optString(KEY_HOST_WINDOW_ID).ifBlank { DEFAULT_HOST_WINDOW_ID },
            values =
                RaftHostWindowInsetValues(
                    topPx = params.optString("safeAreaTop").ifBlank { params.optString("statusBarHeight") }
                        .toFloatOrNull() ?: 0f,
                    bottomPx = params.optString("safeAreaBottom").ifBlank { params.optString("navigationBarHeight") }
                        .toFloatOrNull() ?: 0f,
                    statusBarHidden = if (params.has(KEY_STATUS_BAR_HIDDEN)) {
                        params.optBoolean(KEY_STATUS_BAR_HIDDEN)
                    } else {
                        null
                    },
                    leftPx = params.insetPx("safeAreaLeft"),
                    rightPx = params.insetPx("safeAreaRight"),
                ),
            fromLiveEvent = false,
        )
    }
}

// One observer per composable call site, scoped to the pager that owns the
// pageData. Embedded child pagers observe their own pager — the
// SlockKuiklyChildPageView host forwards parent pager events down via
// sendPagerEvent, so a window inset dispatch reaches them live too. Event
// payloads REPLACE the window snapshot (no monotonic merge) so decreases and
// zero are honored; a pager that attached before the first event still sees
// the correct values because (a) its pageData already carries the latest
// insets the host writes back into the page spec, and (b) the seed above only
// fills windows that have not seen a live event.
@Composable
private fun raftHostWindowIdEnsuringObserver(): String {
    val pager = LocalActivity.current.getPager() as Pager
    val params = pager.pageData.params
    val windowId =
        params.optString(KEY_HOST_WINDOW_ID).ifBlank { DEFAULT_HOST_WINDOW_ID }
    DisposableEffect(pager, windowId) {
        RaftHostWindowInsets.seedFromPageDataIfPresent(params)
        val observer =
            object : IPagerEventObserver {
                override fun onPagerEvent(pagerEvent: String, eventData: JSONObject) {
                    if (pagerEvent != WINDOW_INSETS_PAGER_EVENT) return
                    RaftHostWindowInsets.publish(windowId, raftHostWindowInsetsFromEvent(eventData))
                }
            }
        pager.addPagerEventObserver(observer)
        onDispose { pager.removePagerEventObserver(observer) }
    }
    return windowId
}

/**
 * PROTOTYPE (native nav bar): the host's live top inset even while the status bar is hidden
 * (landscape iPhone), where it is the host bar's height; null without a live host snapshot.
 */
@Composable
internal fun raftNativeNavBarLiveTop(): Dp? {
    val windowId = raftHostWindowIdEnsuringObserver()
    val live = RaftHostWindowInsets.current(windowId) ?: return null
    return resolveRaftLiveTopSafeArea(live, LocalDensity.current.density)
}

@Composable
internal fun raftTopSafeAreaPadding(): Dp {
    val windowId = raftHostWindowIdEnsuringObserver()
    val pageData = (LocalActivity.current.getPager() as Pager).pageData
    val params = pageData.params
    // Visual-testing captures compare against React baselines that have no
    // device status bar; the capture harness opts out of the top inset.
    if (params.optBoolean("slockVisualZeroSafeArea", false)) return 0.dp
    val live = RaftHostWindowInsets.current(windowId)
    // When the host hides the status bar (immersive / fullscreen), the reported
    // bar height would reserve a strip of dead space; keep a small fixed
    // padding instead so page headers are not flush against the top edge.
    val statusBarHidden =
        live?.statusBarHidden ?: params.optBoolean(KEY_STATUS_BAR_HIDDEN, false)
    if (statusBarHidden) return RaftAppSpacing.Md
    // A live host snapshot is authoritative (it replaces, so 0 means 0), except that a
    // sheet-presented page keeps its header off the sheet's top edge. The renderer-native and
    // attach-time params are fallbacks for windows with no host measurement.
    if (live != null) return resolveRaftLiveTopSafeArea(live, LocalDensity.current.density)
    val hostInsetPx =
        maxOf(
                params.safeAreaPx("safeAreaTop"),
                params.safeAreaPx("statusBarHeight"),
            ).takeIf { it > 0f }
    val hostInsetDp =
        hostInsetPx?.let { with(LocalDensity.current) { it.coerceAtLeast(0f).toDp() } }
    val nativeInsetDp =
        resolveRaftNativeSafeAreaDp(
            nativeInsetsArePixels = params.optBoolean(KEY_NATIVE_SAFE_AREA_INSETS_ARE_PIXELS, false),
            primary = pageData.safeAreaInsets.top,
            fallback = pageData.statusBarHeight,
            density = LocalDensity.current.density
        ).dp
    return if (hostInsetDp != null) hostInsetDp else nativeInsetDp
}

@Composable
internal fun raftBottomSafeAreaPadding(): Dp {
    val windowId = raftHostWindowIdEnsuringObserver()
    val pageData = (LocalActivity.current.getPager() as Pager).pageData
    val params = pageData.params
    // Host that already consumed the navigation bar reserves nothing here —
    // unchanged original contract.
    if (params.optBoolean("slockHostExcludesNavigationBar", false)) return 0.dp
    val live = RaftHostWindowInsets.current(windowId)
    val hostInsetPx =
        live?.bottomPx
            ?: maxOf(
                params.safeAreaPx("safeAreaBottom"),
                params.safeAreaPx("navigationBarHeight"),
            ).takeIf { it > 0f }
    val hostInsetDp =
        hostInsetPx
            ?.let { with(LocalDensity.current) { it.coerceAtLeast(0f).toDp().value } }
            ?: 0f
    val nativeInsetDp =
        resolveRaftNativeSafeAreaDp(
            nativeInsetsArePixels = params.optBoolean(KEY_NATIVE_SAFE_AREA_INSETS_ARE_PIXELS, false),
            primary = pageData.safeAreaInsets.bottom,
            fallback = pageData.androidBottomBavBarHeight,
            density = LocalDensity.current.density
        )
    return if (live != null) hostInsetDp.dp else nativeInsetDp.coerceAtLeast(hostInsetDp).dp
}

/**
 * Left/right safe-area insets in dp, on PHYSICAL edges (what UIKit reports; the sensor housing
 * of an iPhone in landscape, or the iPhone Duo inner display's right-edge status bar).
 * [start] / [end] resolve them for a layout direction.
 */
internal data class RaftHorizontalSafeArea(val left: Dp = 0.dp, val right: Dp = 0.dp) {
    fun start(layoutDirection: LayoutDirection): Dp =
        if (layoutDirection == LayoutDirection.Ltr) left else right

    fun end(layoutDirection: LayoutDirection): Dp =
        if (layoutDirection == LayoutDirection.Ltr) right else left

    /** Per edge, the larger of the two. */
    fun union(other: RaftHorizontalSafeArea): RaftHorizontalSafeArea =
        RaftHorizontalSafeArea(maxOf(left, other.left), maxOf(right, other.right))

    val isZero: Boolean get() = left <= 0.dp && right <= 0.dp

    companion object {
        val Zero = RaftHorizontalSafeArea()
    }
}

/**
 * The horizontal safe area from the host contract ([RaftHostWindowInsetValues.leftPx] /
 * [RaftHostWindowInsetValues.rightPx]) converted with [density]. Only the host contract counts:
 * the renderer's own `safeAreaInsets.left/right` are not read, so the Android host (which sends
 * no horizontal values) keeps its layout unchanged.
 */
internal fun resolveRaftHorizontalSafeArea(
    values: RaftHostWindowInsetValues?,
    density: Float,
    visualZeroSafeArea: Boolean = false,
): RaftHorizontalSafeArea {
    if (values == null || visualZeroSafeArea || !(density > 0f)) return RaftHorizontalSafeArea.Zero
    return RaftHorizontalSafeArea(
        left = (values.leftPx.coerceAtLeast(0f) / density).dp,
        right = (values.rightPx.coerceAtLeast(0f) / density).dp,
    )
}

/** The current page window's horizontal safe area (see [resolveRaftHorizontalSafeArea]). */
@Composable
internal fun raftHorizontalSafeArea(): RaftHorizontalSafeArea {
    val windowId = raftHostWindowIdEnsuringObserver()
    val params = LocalActivity.current.pageData.params
    return resolveRaftHorizontalSafeArea(
        values = RaftHostWindowInsets.current(windowId),
        density = LocalDensity.current.density,
        visualZeroSafeArea = params.optBoolean("slockVisualZeroSafeArea", false),
    )
}

/** Start-edge safe area of the page's window (layout-direction aware). */
@Composable
internal fun raftStartSafeAreaPadding(): Dp = raftHorizontalSafeArea().start(LocalLayoutDirection.current)

/** End-edge safe area of the page's window (layout-direction aware). */
@Composable
internal fun raftEndSafeAreaPadding(): Dp = raftHorizontalSafeArea().end(LocalLayoutDirection.current)

/**
 * The window's horizontal safe area as the enclosing page shells know it (the union of their
 * [RaftHorizontalSafeAreaShell.required]). A [Modal] is its own full-window layer and pads all of
 * it itself.
 */
internal val LocalRaftWindowHorizontalSafeArea = compositionLocalOf { RaftHorizontalSafeArea.Zero }

/**
 * What the nearest [RaftHorizontalSafeAreaShell] padded itself: a full-bleed background inside it
 * ([raftBleedHorizontalSafeArea]) extends back out by this much. Zero inside a page (a page shell
 * does not pad); the safe area a [Modal] pads inside its window.
 */
internal val LocalRaftHorizontalSafeAreaBleed = compositionLocalOf { RaftHorizontalSafeArea.Zero }

/** The design-system form of the safe area ([LocalRaftSafeAreaInsets]). */
internal fun RaftHorizontalSafeArea.toDesignSystemInsets(): RaftSafeAreaInsets =
    RaftSafeAreaInsets(left = left.coerceAtLeast(0.dp), right = right.coerceAtLeast(0.dp))

/**
 * The horizontal safe area for [content] (Apple's rule: backgrounds run edge to edge, content gets
 * out of the way).
 *
 * A page ([windowRoot] false) is NOT padded: its backgrounds (the canvas, a header frame, a list's
 * selection wash, the composer surface) reach the screen edge. The safe area goes to descendants
 * as [LocalRaftSafeAreaInsets], and the shared building blocks pad only their content with it
 * (`raftSafeAreaHorizontalPadding`: the page header and top bar, list and sidebar rows, the
 * composer, empty and loading states). That padding consumes it like `WindowInsets`, so an
 * embedded child page inside an already padded container does not pad again, and a child page
 * shell nested in this one provides the same window inset.
 *
 * A [Modal] ([windowRoot] true) is a full-window layer that holds menus, dialogs and sheets: it
 * keeps padding the whole [required] and consumes it, and a scrim bleeds back over the strips
 * ([raftBleedHorizontalSafeArea] with [LocalRaftHorizontalSafeAreaBleed]).
 */
@Composable
internal fun RaftHorizontalSafeAreaShell(
    required: RaftHorizontalSafeArea,
    windowRoot: Boolean = false,
    content: @Composable () -> Unit,
) {
    val window = if (windowRoot) required else LocalRaftWindowHorizontalSafeArea.current.union(required)
    val padding = if (windowRoot) required else RaftHorizontalSafeArea.Zero
    val paddingValues = PaddingValues.Absolute(left = padding.left, right = padding.right)
    // Always the same node, whatever the insets: a rotation must not reset the page's state.
    Box(Modifier.fillMaxSize().padding(paddingValues).consumeWindowInsets(paddingValues)) {
        CompositionLocalProvider(
            LocalRaftWindowHorizontalSafeArea provides window,
            LocalRaftHorizontalSafeAreaBleed provides padding,
            LocalRaftSafeAreaInsets provides window.toDesignSystemInsets(),
        ) {
            content()
        }
    }
}

/**
 * The page host's shell: the window's horizontal safe area plus the band of a host tab bar laid
 * out on a side edge ([raftNativeTabBarEdgeInsets]).
 */
@Composable
internal fun RaftPageHorizontalSafeAreaShell(content: @Composable () -> Unit) {
    val tabBar = raftNativeTabBarEdgeInsets()
    RaftHorizontalSafeAreaShell(
        required = raftHorizontalSafeArea().union(RaftHorizontalSafeArea(tabBar.left, tabBar.right)),
        content = content,
    )
}

/**
 * Lays the element out [bleed] wider on each physical edge and shifts it back, so a full-bleed
 * background (a modal scrim) still covers the safe-area strip its shell padded.
 */
internal fun Modifier.raftBleedHorizontalSafeArea(bleed: RaftHorizontalSafeArea): Modifier =
    if (bleed.isZero) {
        this
    } else {
        layout { measurable, constraints ->
            if (!constraints.hasBoundedWidth) {
                val placeable = measurable.measure(constraints)
                return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            val left = bleed.left.roundToPx().coerceAtLeast(0)
            val right = bleed.right.roundToPx().coerceAtLeast(0)
            val placeable =
                measurable.measure(
                    constraints.copy(
                        minWidth = constraints.minWidth + left + right,
                        maxWidth = constraints.maxWidth + left + right,
                    )
                )
            val width = (placeable.width - left - right).coerceIn(constraints.minWidth, constraints.maxWidth)
            // place, not placeRelative: the bleed is on physical edges.
            layout(width, placeable.height) { placeable.place(-left, 0) }
        }
    }

@Composable
internal fun raftKeyboardInsetExcludesBottomSafeArea(): Boolean =
    ((LocalActivity.current.getPager() as Pager).pageData)
        .params
        .optBoolean(KEY_KEYBOARD_INSET_EXCLUDES_BOTTOM_SAFE_AREA, false)

/**
 * Returns the effective bottom obstruction reported by a keyboard watcher.
 *
 * This is deliberately a pure inset calculation. The caller owns the container
 * policy used to consume the obstruction (for example, viewport reduction or
 * scroll content padding). When a host says the native keyboard inset excludes
 * the bottom safe area, that area is appended only while the keyboard is open;
 * otherwise it would be double-counted or alter closed-keyboard layout.
 */
internal fun raftEffectiveKeyboardInsetDp(
    keyboardHeightDp: Float,
    bottomSafeArea: Dp,
    keyboardInsetExcludesBottomSafeArea: Boolean
): Dp {
    val keyboardInset = keyboardHeightDp.coerceAtLeast(0f).dp
    val safeAreaInset =
        if (keyboardHeightDp > 0f && keyboardInsetExcludesBottomSafeArea) {
            bottomSafeArea.value.coerceAtLeast(0f).dp
        } else {
            0.dp
        }
    return keyboardInset + safeAreaInset
}

internal fun resolveRaftSafeAreaDp(primaryDp: Float, fallbackDp: Float): Float =
    primaryDp.coerceAtLeast(fallbackDp).coerceAtLeast(0f)

internal fun resolveRaftSafeAreaPx(primaryPx: Float, fallbackPx: Float): Float =
    primaryPx.coerceAtLeast(fallbackPx).coerceAtLeast(0f)

internal fun resolveRaftNativeSafeAreaDp(
    nativeInsetsArePixels: Boolean,
    primary: Float,
    fallback: Float,
    density: Float
): Float {
    val raw = resolveRaftSafeAreaDp(primary, fallback)
    return if (nativeInsetsArePixels && density > 0f) raw / density else raw
}

/**
 * The original consumption contract: a host that already excludes the
 * navigation bar reserves 0 in the shared layer (no double padding). When it
 * does not exclude, the shared layer uses the larger of the renderer-reported
 * and host-parameter values — callers that already read a live window
 * snapshot pass it through [hostDp] and treat the result as authoritative.
 */
internal fun resolveRaftBottomSafeAreaDp(
    hostExcludesNavigationBar: Boolean,
    nativeDp: Float,
    hostDp: Float
): Float = if (hostExcludesNavigationBar) 0f else resolveRaftSafeAreaDp(nativeDp, hostDp)

internal fun maxSafeAreaDp(left: Dp, right: Dp): Dp = if (left >= right) left else right

internal fun JSONObject.safeAreaPx(key: String): Float =
    optString(key).toFloatOrNull() ?: 0f
