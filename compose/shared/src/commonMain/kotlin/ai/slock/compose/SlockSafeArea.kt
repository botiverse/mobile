package ai.slock.compose

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager

@Composable
internal fun slockTopSafeAreaPadding(): Dp {
    val pageData = (LocalActivity.current.getPager() as Pager).pageData
    val params = pageData.params
    val nativeInsetDp = resolveSlockSafeAreaDp(pageData.safeAreaInsets.top, pageData.statusBarHeight).dp
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
    val nativeInsetDp = resolveSlockSafeAreaDp(pageData.safeAreaInsets.bottom, pageData.androidBottomBavBarHeight).dp
    val hostInsetDp =
        with(LocalDensity.current) {
            resolveSlockSafeAreaPx(params.safeAreaPx("safeAreaBottom"), params.safeAreaPx("navigationBarHeight")).toDp()
        }
    return maxSafeAreaDp(nativeInsetDp, hostInsetDp)
}

internal fun resolveSlockSafeAreaDp(primaryDp: Float, fallbackDp: Float): Float =
    primaryDp.coerceAtLeast(fallbackDp).coerceAtLeast(0f)

internal fun resolveSlockSafeAreaPx(primaryPx: Float, fallbackPx: Float): Float =
    primaryPx.coerceAtLeast(fallbackPx).coerceAtLeast(0f)

private fun maxSafeAreaDp(primary: Dp, fallback: Dp): Dp =
    if (primary >= fallback) primary else fallback

private fun JSONObject.safeAreaPx(key: String): Float =
    optString(key).toFloatOrNull() ?: 0f
