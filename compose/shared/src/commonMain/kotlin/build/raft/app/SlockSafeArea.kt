package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
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
 * task #482: live host window-inset snapshots, keyed by the window the host
 * injected into pageData (`slockWindowId`). Per-window — a second Activity or
 * display never pollutes another window's safe area; each authoritative event
 * replaces the snapshot (zero included) so a shrinking/hiding taskbar frees the
 * reserved padding instead of keeping a historical maximum.
 *
 * Producers: `slockWindowInsetsChanged` pager events (live dispatch) and
 * `slockPublishHostWindowInsetsSnapshot` (host writing the same values into
 * pageData for attach-time and late-mounting children).
 */
internal class SlockHostWindowInsetSnapshot(
    topPx: Float,
    bottomPx: Float,
    statusBarHidden: Boolean?,
) {
    val state: MutableState<Triple<Float, Float, Boolean?>> =
        mutableStateOf(Triple(topPx, bottomPx, statusBarHidden))
}

internal object SlockHostWindowInsets {
    private val snapshots = mutableMapOf<String, SlockHostWindowInsetSnapshot>()
    // Windows whose last write came from a live pager event. A pageData
    // snapshot publish is stale once a live event landed (params reflect the
    // attach-time state), so it is ignored for those windows.
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

    fun snapshotState(windowId: String): MutableState<Triple<Float, Float, Boolean?>>? =
        snapshots[windowId]?.state
}

/** Non-composable entry for hosts writing the same snapshot into pageData. */
internal fun slockPublishHostWindowInsetsSnapshot(params: JSONObject) {
    val top = params.optString("safeAreaTop").ifBlank { params.optString("statusBarHeight") }
        .toFloatOrNull() ?: 0f
    val bottom = params.optString("safeAreaBottom").ifBlank { params.optString("navigationBarHeight") }
        .toFloatOrNull() ?: 0f
    SlockHostWindowInsets.publish(
        windowId = params.optString(KEY_HOST_WINDOW_ID).ifBlank { DEFAULT_HOST_WINDOW_ID },
        topPx = top,
        bottomPx = bottom,
        statusBarHidden = if (params.has(KEY_STATUS_BAR_HIDDEN)) {
            params.optBoolean(KEY_STATUS_BAR_HIDDEN)
        } else {
            null
        },
        fromLiveEvent = false,
    )
}

// One observer per composable call site, scoped to the pager that owns the
// pageData (children included — they carry the same `slockWindowId`). Event
// payloads are applied as-is (no monotonic merge) so decreases and zero are
// honored; observers registered after the first dispatch still converge via the
// pageData snapshot path.
@Composable
private fun slockHostWindowIdEnsuringObserver(): String {
    val pager = LocalActivity.current.getPager() as Pager
    val params = pager.pageData.params
    val windowId =
        params.optString(KEY_HOST_WINDOW_ID).ifBlank { DEFAULT_HOST_WINDOW_ID }
    DisposableEffect(pager, windowId) {
        // pageData already holds the latest snapshot the host injected —
        // publish it so observers that attached after the live event converge.
        slockPublishHostWindowInsetsSnapshot(params)
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
    // When the host hides the status bar (immersive / fullscreen), the reported
    // bar height would reserve a strip of dead space; keep a small fixed
    // padding instead so page headers are not flush against the top edge.
    val live = SlockHostWindowInsets.snapshotState(windowId)?.value
    val statusBarHidden =
        live?.third ?: params.optBoolean(KEY_STATUS_BAR_HIDDEN, false)
    if (statusBarHidden) return SlockSpacing.Md
    val nativeInsetDp =
        resolveSlockNativeSafeAreaDp(
            nativeInsetsArePixels = params.optBoolean(KEY_NATIVE_SAFE_AREA_INSETS_ARE_PIXELS, false),
            primary = pageData.safeAreaInsets.top,
            fallback = pageData.statusBarHeight,
            density = LocalDensity.current.density
        ).dp
    // Once a live host snapshot exists for this window it is authoritative
    // (it already absorbs decreases/zero); fall back to the pageData snapshot
    // only before the first authoritative read.
    val hostInsetPx = live?.first ?: params.safeAreaPx("safeAreaTop").let { param ->
        maxOf(param, params.safeAreaPx("statusBarHeight"))
    }
    val hostInsetDp = with(LocalDensity.current) { hostInsetPx.coerceAtLeast(0f).toDp() }
    return maxSafeAreaDp(nativeInsetDp, hostInsetDp)
}

@Composable
internal fun slockBottomSafeAreaPadding(): Dp {
    val windowId = slockHostWindowIdEnsuringObserver()
    val pageData = (LocalActivity.current.getPager() as Pager).pageData
    val params = pageData.params
    val nativeInsetDp =
        resolveSlockNativeSafeAreaDp(
            nativeInsetsArePixels = params.optBoolean(KEY_NATIVE_SAFE_AREA_INSETS_ARE_PIXELS, false),
            primary = pageData.safeAreaInsets.bottom,
            fallback = pageData.androidBottomBavBarHeight,
            density = LocalDensity.current.density
        )
    val live = SlockHostWindowInsets.snapshotState(windowId)?.value
    val hostInsetPx = live?.second ?: run {
        maxOf(
            params.safeAreaPx("safeAreaBottom"),
            params.safeAreaPx("navigationBarHeight"),
        )
    }
    val hostInsetDp =
        with(LocalDensity.current) { hostInsetPx.coerceAtLeast(0f).toDp().value }
    return resolveSlockBottomSafeAreaDp(
        hostExcludesNavigationBar = params.optBoolean("slockHostExcludesNavigationBar", false),
        nativeDp = nativeInsetDp,
        hostDp = hostInsetDp
    ).dp
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

internal fun resolveSlockBottomSafeAreaDp(
    hostExcludesNavigationBar: Boolean,
    nativeDp: Float,
    hostDp: Float
): Float = if (hostExcludesNavigationBar) nativeDp else resolveSlockSafeAreaDp(nativeDp, hostDp)

internal fun maxSafeAreaDp(left: Dp, right: Dp): Dp = if (left >= right) left else right

internal fun JSONObject.safeAreaPx(key: String): Float =
    optString(key).toFloatOrNull() ?: 0f
