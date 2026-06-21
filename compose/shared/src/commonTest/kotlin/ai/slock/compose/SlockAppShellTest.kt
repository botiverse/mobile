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
        assertEquals(88.dp, scope.bottomInset(includeBottomTabBar = true))
        assertEquals(18.dp + SlockSpacing.Lg, scope.bottomInset(includeBottomTabBar = false))
    }

    @Test
    fun sharedBottomTabHostKeepsSafeAreaOutsideTabContent() {
        assertEquals(58.dp, slockSharedBottomTabBarHostHeight(0.dp))
        assertEquals(76.dp, slockSharedBottomTabBarHostHeight(18.dp))
        assertEquals(
            slockSharedBottomTabBarHostHeight(18.dp) + SlockSpacing.Lg,
            slockSharedBottomTabBarContentInset(18.dp)
        )
    }

    @Test
    fun retainedAppShellTabOrderKeepsActiveTabOnTop() {
        assertEquals(listOf("tasks", "members", "settings", "home"), retainedAppShellTabOrder("home"))
        assertEquals(listOf("home", "members", "settings", "tasks"), retainedAppShellTabOrder("tasks"))
        assertEquals(listOf("home", "tasks", "settings", "members"), retainedAppShellTabOrder("members"))
        assertEquals(listOf("home", "tasks", "members", "settings"), retainedAppShellTabOrder("settings"))
    }

    @Test
    fun retainedAppShellSelectedTabFallsBackToHome() {
        assertEquals("home", retainedAppShellSelectedTab("thread"))
    }

    @Test
    fun appShellBottomTabsOnlyShowOnPrimarySurfaces() {
        assertEquals(
            true,
            appShellBottomTabsVisible(
                selectedTab = "home",
                selectedHomeDestination = null,
                selectedSettingsDestination = "home"
            )
        )
        assertEquals(
            false,
            appShellBottomTabsVisible(
                selectedTab = "home",
                selectedHomeDestination = "activity",
                selectedSettingsDestination = "home"
            )
        )
        assertEquals(
            true,
            appShellBottomTabsVisible(
                selectedTab = "settings",
                selectedHomeDestination = null,
                selectedSettingsDestination = "home"
            )
        )
        assertEquals(
            false,
            appShellBottomTabsVisible(
                selectedTab = "settings",
                selectedHomeDestination = null,
                selectedSettingsDestination = "computerDetail"
            )
        )
        assertEquals(
            true,
            appShellBottomTabsVisible(
                selectedTab = "tasks",
                selectedHomeDestination = "activity",
                selectedSettingsDestination = "computerDetail"
            )
        )
    }

    @Test
    fun retainedThreadRouteEntryKeyUsesRouteIdentity() {
        val base =
            KuiklyThreadScreenPayload(
                instanceId = "shared-shell-thread-C123",
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                sourceThreadId = "C123",
                streamChannelId = "C123"
            )

        assertEquals(
            retainedThreadRouteEntryKey(base),
            retainedThreadRouteEntryKey(base.copy(status = "content", title = "general"))
        )
    }

    @Test
    fun retainedThreadRouteEntriesAreReleasedAfterFinalPopToMain() {
        val payload =
            KuiklyThreadScreenPayload(
                instanceId = "shared-shell-thread-C123",
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                sourceThreadId = "C123",
                streamChannelId = "C123"
            )
        val entries =
            listOf(
                RetainedThreadRouteEntry(
                    key = retainedThreadRouteEntryKey(payload),
                    payload = payload
                )
            )

        assertEquals(emptyList(), retainedThreadRouteEntriesAfterPopToMain(entries))
    }
}
