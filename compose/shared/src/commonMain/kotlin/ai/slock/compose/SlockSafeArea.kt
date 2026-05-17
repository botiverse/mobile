package ai.slock.compose

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.ui.platform.LocalActivity
import com.tencent.kuikly.compose.ui.platform.LocalDensity
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.core.pager.Pager

@Composable
internal fun slockTopSafeAreaPadding(): Dp {
    val pageData = (LocalActivity.current.getPager() as Pager).pageData
    val safeTop = pageData.safeAreaInsets.top
    val statusTop = pageData.statusBarHeight
    return with(LocalDensity.current) {
        safeTop.coerceAtLeast(statusTop).coerceAtLeast(0f).toDp()
    }
}

@Composable
internal fun slockBottomSafeAreaPadding(): Dp {
    val pageData = (LocalActivity.current.getPager() as Pager).pageData
    val safeBottom = pageData.safeAreaInsets.bottom
    val androidBottom = pageData.androidBottomBavBarHeight
    return with(LocalDensity.current) {
        safeBottom.coerceAtLeast(androidBottom).coerceAtLeast(0f).toDp()
    }
}
