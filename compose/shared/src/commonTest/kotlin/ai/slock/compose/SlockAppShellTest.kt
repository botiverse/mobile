package ai.slock.compose

import com.tencent.kuikly.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class SlockAppShellTest {
    @Test
    fun shellScopeProvidesExplicitPanelInsets() {
        val scope =
            SlockAppShellScope(
                topSafeArea = 24.dp,
                bottomSafeArea = 18.dp,
                bottomTabBarContentInset = 88.dp
            )

        assertEquals(24.dp, scope.topInset(includeTopSafeArea = true))
        assertEquals(0.dp, scope.topInset(includeTopSafeArea = false))
        assertEquals(18.dp, scope.bottomSafeArea)
        assertEquals(88.dp, scope.bottomTabBarContentInset)
    }
}
