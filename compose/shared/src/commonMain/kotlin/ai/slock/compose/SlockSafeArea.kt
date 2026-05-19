package ai.slock.compose

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.pager.Pager

@Composable
internal fun slockTopSafeAreaPadding(): Dp {
    val pageData = (LocalActivity.current.getPager() as Pager).pageData
    val params = pageData.params
    val safeTop = pageData.safeAreaInsets.top.coerceAtLeast(params.safeAreaPx("safeAreaTop"))
    val statusTop = pageData.statusBarHeight.coerceAtLeast(params.safeAreaPx("statusBarHeight"))
    return with(LocalDensity.current) {
        resolveSlockSafeAreaPx(safeTop, statusTop).toDp()
    }
}

@Composable
internal fun slockBottomSafeAreaPadding(): Dp {
    val pageData = (LocalActivity.current.getPager() as Pager).pageData
    val params = pageData.params
    val safeBottom = pageData.safeAreaInsets.bottom.coerceAtLeast(params.safeAreaPx("safeAreaBottom"))
    val androidBottom = pageData.androidBottomBavBarHeight.coerceAtLeast(params.safeAreaPx("navigationBarHeight"))
    return with(LocalDensity.current) {
        resolveSlockSafeAreaPx(safeBottom, androidBottom).toDp()
    }
}

internal fun resolveSlockSafeAreaPx(primaryPx: Float, fallbackPx: Float): Float =
    primaryPx.coerceAtLeast(fallbackPx).coerceAtLeast(0f)

private fun JSONObject.safeAreaPx(key: String): Float =
    optString(key).toFloatOrNull() ?: 0f
