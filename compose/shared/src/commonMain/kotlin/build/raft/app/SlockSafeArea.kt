package build.raft.app

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager

private const val KEY_NATIVE_SAFE_AREA_INSETS_ARE_PIXELS = "slockNativeSafeAreaInsetsArePixels"
private const val KEY_KEYBOARD_INSET_EXCLUDES_BOTTOM_SAFE_AREA =
    "slockKeyboardInsetExcludesBottomSafeArea"
private const val KEY_STATUS_BAR_HIDDEN = "slockStatusBarHidden"

@Composable
internal fun slockTopSafeAreaPadding(): Dp {
    val pageData = (LocalActivity.current.getPager() as Pager).pageData
    val params = pageData.params
    // Visual-testing captures compare against React baselines that have no
    // device status bar; the capture harness opts out of the top inset.
    if (params.optBoolean("slockVisualZeroSafeArea", false)) return 0.dp
    // When the host hides the status bar (immersive / fullscreen), the reported
    // bar height would reserve a strip of dead space; keep a small fixed
    // padding instead so page headers are not flush against the top edge.
    if (params.optBoolean(KEY_STATUS_BAR_HIDDEN, false)) return SlockSpacing.Md
    val nativeInsetDp =
        resolveSlockNativeSafeAreaDp(
            nativeInsetsArePixels = params.optBoolean(KEY_NATIVE_SAFE_AREA_INSETS_ARE_PIXELS, false),
            primary = pageData.safeAreaInsets.top,
            fallback = pageData.statusBarHeight,
            density = LocalDensity.current.density
        ).dp
    val hostInsetDp =
        with(LocalDensity.current) {
            resolveSlockSafeAreaPx(params.safeAreaPx("safeAreaTop"), params.safeAreaPx("statusBarHeight")).toDp()
        }
    return maxSafeAreaDp(nativeInsetDp, hostInsetDp)
}

@Composable
internal fun slockBottomSafeAreaPadding(): Dp {
    val pageData = (LocalActivity.current.getPager() as Pager).pageData
    val params = pageData.params
    val nativeInsetDp =
        resolveSlockNativeSafeAreaDp(
            nativeInsetsArePixels = params.optBoolean(KEY_NATIVE_SAFE_AREA_INSETS_ARE_PIXELS, false),
            primary = pageData.safeAreaInsets.bottom,
            fallback = pageData.androidBottomBavBarHeight,
            density = LocalDensity.current.density
        )
    val hostInsetDp =
        with(LocalDensity.current) {
            resolveSlockSafeAreaPx(params.safeAreaPx("safeAreaBottom"), params.safeAreaPx("navigationBarHeight")).toDp().value
        }
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
): Float =
    if (nativeInsetsArePixels) {
        resolveSlockSafeAreaPx(primary, fallback) / density.coerceAtLeast(1f)
    } else {
        resolveSlockSafeAreaDp(primary, fallback)
    }

internal fun resolveSlockBottomSafeAreaDp(hostExcludesNavigationBar: Boolean, nativeDp: Float, hostDp: Float): Float =
    if (hostExcludesNavigationBar) 0f else nativeDp.coerceAtLeast(hostDp).coerceAtLeast(0f)

private fun maxSafeAreaDp(primary: Dp, fallback: Dp): Dp =
    if (primary >= fallback) primary else fallback

private fun JSONObject.safeAreaPx(key: String): Float =
    optString(key).toFloatOrNull() ?: 0f
