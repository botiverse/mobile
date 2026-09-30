package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.IPagerEventObserver
import com.tencent.kuikly.core.pager.Pager

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
internal class SlockHostWindowInsetSnapshot(
    topPx: Float,
    bottomPx: Float,
    statusBarHidden: Boolean?,
) {
    /** Null payload = no measurement yet (distinct from a real 0px value). */
    val state =
        androidx.compose.runtime.mutableStateOf(
            Triple(topPx, bottomPx, statusBarHidden)
        )
}

internal object SlockHostWindowInsets {
    // Observable: inserting a missing window entry must invalidate readers that
    // previously saw null.
    private val snapshots = mutableStateMapOf<String, SlockHostWindowInsetSnapshot>()

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
    ) {
        val key = windowId.trim().ifEmpty { DEFAULT_HOST_WINDOW_ID }
        if (!fromLiveEvent && key in liveSeen) return
        if (fromLiveEvent) liveSeen.add(key)
        val entry = snapshots.getOrPut(key) {
            SlockHostWindowInsetSnapshot(topPx, bottomPx, statusBarHidden)
        }
        entry.state.value = Triple(topPx, bottomPx, statusBarHidden)
    }

    /**
     * Current authoritative snapshot for the window, or null when nothing has
     * been published. Reading the map (not just a pre-created state) keeps the
     * lookup observable for readers composed before the first publish.
     */
    fun current(windowId: String): Triple<Float, Float, Boolean?>? =
        snapshots[windowId]?.state?.value

    internal fun seedFromPageDataIfPresent(params: JSONObject) {
        val hasInsetField =
            params.has("safeAreaTop") || params.has("statusBarHeight") ||
                params.has("safeAreaBottom") || params.has("navigationBarHeight")
        if (!hasInsetField) return
        SlockHostWindowInsets.publish(
            windowId = params.optString(KEY_HOST_WINDOW_ID).ifBlank { DEFAULT_HOST_WINDOW_ID },
            topPx = params.optString("safeAreaTop").ifBlank { params.optString("statusBarHeight") }
                .toFloatOrNull() ?: 0f,
            bottomPx = params.optString("safeAreaBottom").ifBlank { params.optString("navigationBarHeight") }
                .toFloatOrNull() ?: 0f,
            statusBarHidden = if (params.has(KEY_STATUS_BAR_HIDDEN)) {
                params.optBoolean(KEY_STATUS_BAR_HIDDEN)
            } else {
                null
            },
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
private fun slockHostWindowIdEnsuringObserver(): String {
    val pager = LocalActivity.current.getPager() as Pager
    val params = pager.pageData.params
    val windowId =
        params.optString(KEY_HOST_WINDOW_ID).ifBlank { DEFAULT_HOST_WINDOW_ID }
    DisposableEffect(pager, windowId) {
        SlockHostWindowInsets.seedFromPageDataIfPresent(params)
        val observer =
            object : IPagerEventObserver {
                override fun onPagerEvent(pagerEvent: String, eventData: JSONObject) {
                    if (pagerEvent != WINDOW_INSETS_PAGER_EVENT) return
                    SlockHostWindowInsets.publish(
                        windowId = windowId,
                        topPx = eventData.optString("statusBarHeightPx").toFloatOrNull() ?: 0f,
                        bottomPx =
                            eventData.optString("navigationBarHeightPx").toFloatOrNull() ?: 0f,
                        statusBarHidden =
                            if (eventData.has("statusBarHidden")) {
                                eventData.optBoolean("statusBarHidden")
                            } else {
                                null
                            },
                    )
                }
            }
        pager.addPagerEventObserver(observer)
        onDispose { pager.removePagerEventObserver(observer) }
    }
    return windowId
}

@Composable
internal fun slockTopSafeAreaPadding(): Dp {
    val windowId = slockHostWindowIdEnsuringObserver()
    val pageData = (LocalActivity.current.getPager() as Pager).pageData
    val params = pageData.params
    // Visual-testing captures compare against React baselines that have no
    // device status bar; the capture harness opts out of the top inset.
    if (params.optBoolean("slockVisualZeroSafeArea", false)) return 0.dp
    val live = SlockHostWindowInsets.current(windowId)
    // When the host hides the status bar (immersive / fullscreen), the reported
    // bar height would reserve a strip of dead space; keep a small fixed
    // padding instead so page headers are not flush against the top edge.
    val statusBarHidden =
        live?.third ?: params.optBoolean(KEY_STATUS_BAR_HIDDEN, false)
    if (statusBarHidden) return SlockSpacing.Md
    // A live host snapshot is authoritative (it replaces, so 0 means 0). The
    // renderer-native and attach-time params are fallbacks for windows with no
    // host measurement.
    val hostInsetPx =
        live?.first
            ?: maxOf(
                params.safeAreaPx("safeAreaTop"),
                params.safeAreaPx("statusBarHeight"),
            ).takeIf { it > 0f }
    val hostInsetDp =
        hostInsetPx?.let { with(LocalDensity.current) { it.coerceAtLeast(0f).toDp() } }
    val nativeInsetDp =
        resolveSlockNativeSafeAreaDp(
            nativeInsetsArePixels = params.optBoolean(KEY_NATIVE_SAFE_AREA_INSETS_ARE_PIXELS, false),
            primary = pageData.safeAreaInsets.top,
            fallback = pageData.statusBarHeight,
            density = LocalDensity.current.density
        ).dp
    return if (hostInsetDp != null) hostInsetDp else nativeInsetDp
}

@Composable
internal fun slockBottomSafeAreaPadding(): Dp {
    val windowId = slockHostWindowIdEnsuringObserver()
    val pageData = (LocalActivity.current.getPager() as Pager).pageData
    val params = pageData.params
    // Host that already consumed the navigation bar reserves nothing here —
    // unchanged original contract.
    if (params.optBoolean("slockHostExcludesNavigationBar", false)) return 0.dp
    val live = SlockHostWindowInsets.current(windowId)
    val hostInsetPx =
        live?.second
            ?: maxOf(
                params.safeAreaPx("safeAreaBottom"),
                params.safeAreaPx("navigationBarHeight"),
            ).takeIf { it > 0f }
    val hostInsetDp =
        hostInsetPx
            ?.let { with(LocalDensity.current) { it.coerceAtLeast(0f).toDp().value } }
            ?: 0f
    val nativeInsetDp =
        resolveSlockNativeSafeAreaDp(
            nativeInsetsArePixels = params.optBoolean(KEY_NATIVE_SAFE_AREA_INSETS_ARE_PIXELS, false),
            primary = pageData.safeAreaInsets.bottom,
            fallback = pageData.androidBottomBavBarHeight,
            density = LocalDensity.current.density
        )
    return if (live != null) hostInsetDp.dp else nativeInsetDp.coerceAtLeast(hostInsetDp).dp
}

@Composable
internal fun slockKeyboardInsetExcludesBottomSafeArea(): Boolean =
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
internal fun slockEffectiveKeyboardInsetDp(
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

internal fun resolveSlockSafeAreaDp(primaryDp: Float, fallbackDp: Float): Float =
    primaryDp.coerceAtLeast(fallbackDp).coerceAtLeast(0f)

internal fun resolveSlockSafeAreaPx(primaryPx: Float, fallbackPx: Float): Float =
    primaryPx.coerceAtLeast(fallbackPx).coerceAtLeast(0f)

internal fun resolveSlockNativeSafeAreaDp(
    nativeInsetsArePixels: Boolean,
    primary: Float,
    fallback: Float,
    density: Float
): Float {
    val raw = resolveSlockSafeAreaDp(primary, fallback)
    return if (nativeInsetsArePixels && density > 0f) raw / density else raw
}

/**
 * The original consumption contract: a host that already excludes the
 * navigation bar reserves 0 in the shared layer (no double padding). When it
 * does not exclude, the shared layer uses the larger of the renderer-reported
 * and host-parameter values — callers that already read a live window
 * snapshot pass it through [hostDp] and treat the result as authoritative.
 */
internal fun resolveSlockBottomSafeAreaDp(
    hostExcludesNavigationBar: Boolean,
    nativeDp: Float,
    hostDp: Float
): Float = if (hostExcludesNavigationBar) 0f else resolveSlockSafeAreaDp(nativeDp, hostDp)

internal fun maxSafeAreaDp(left: Dp, right: Dp): Dp = if (left >= right) left else right

internal fun JSONObject.safeAreaPx(key: String): Float =
    optString(key).toFloatOrNull() ?: 0f
