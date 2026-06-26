package ai.slock.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.padding
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

    fun bottomInset(includeBottomTabBar: Boolean): Dp =
        if (includeBottomTabBar) bottomTabBarContentInset else bottomSafeArea + SlockSpacing.Lg

    fun bottomTabBarHostInset(includeBottomTabBar: Boolean): Dp =
        if (includeBottomTabBar) bottomTabBarContentInset - SlockSpacing.Lg else 0.dp

    fun contentBottomInset(includeBottomTabBar: Boolean): Dp =
        if (includeBottomTabBar) SlockSpacing.Lg else bottomSafeArea + SlockSpacing.Lg
}

@Composable
internal fun rememberSlockAppShellScope(): SlockAppShellScope {
    val topSafeArea = slockTopSafeAreaPadding()
    val bottomSafeArea = slockBottomSafeAreaPadding()
    val bottomTabBarContentInset = slockAppBottomTabBarContentInset(bottomSafeArea)
    return remember(topSafeArea, bottomSafeArea, bottomTabBarContentInset) {
        SlockAppShellScope(
            topSafeArea = topSafeArea,
            bottomSafeArea = bottomSafeArea,
            bottomTabBarContentInset = bottomTabBarContentInset
        )
    }
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
    showBottomTabs: Boolean = true,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(bottom = shellScope.bottomTabBarHostInset(showBottomTabs))
        ) {
            content()
        }
        if (showBottomTabs) {
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
}
