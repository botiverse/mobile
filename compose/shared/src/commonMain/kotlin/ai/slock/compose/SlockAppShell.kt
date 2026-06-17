package ai.slock.compose

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp

internal data class SlockAppShellScope(
    val topSafeArea: Dp,
    val bottomSafeArea: Dp,
    val bottomTabBarContentInset: Dp
) {
    fun topInset(includeTopSafeArea: Boolean): Dp =
        if (includeTopSafeArea) topSafeArea else 0.dp
}

@Composable
internal fun rememberSlockAppShellScope(): SlockAppShellScope {
    val topSafeArea = slockTopSafeAreaPadding()
    val bottomSafeArea = slockBottomSafeAreaPadding()
    return SlockAppShellScope(
        topSafeArea = topSafeArea,
        bottomSafeArea = bottomSafeArea,
        bottomTabBarContentInset = slockAppBottomTabBarContentInset(bottomSafeArea)
    )
}

@Composable
internal fun SlockAppShellRootBackHandler(
    onLifecycleLog: ((String) -> Unit)? = null,
    onBack: () -> Unit
) {
    SlockBackHandler(
        enabled = true,
        label = "app_shell_root",
        onLifecycleLog = onLifecycleLog,
        onBack = onBack
    )
}

@Composable
internal fun SlockAppShellSurface(
    shellScope: SlockAppShellScope,
    bottomTabs: List<SlockBottomTabItemPayload>,
    onBottomTabSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
        }
        SlockAppBottomTabBarHost(
            items = bottomTabs,
            onSelect = onBottomTabSelected,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
            bottomSafeArea = shellScope.bottomSafeArea
        )
    }
}
