package ai.slock.compose

import ai.slock.compose.data.HomeSnapshot
import ai.slock.compose.data.SharedHomeContentState
import ai.slock.compose.data.SharedHomeState
import ai.slock.compose.data.StoredAuthSession
import ai.slock.compose.data.StoredAuthServer
import ai.slock.compose.data.StoredAuthUser
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerSummary
import com.tencent.kuikly.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun androidBottomSafeAreaKeepsGestureNavigationInsetUnlessHostExcludesIt() {
        assertEquals(0f, resolveSlockBottomSafeAreaDp(hostExcludesNavigationBar = true, nativeDp = 48f, hostDp = 48f))
        assertEquals(24f, resolveSlockBottomSafeAreaDp(hostExcludesNavigationBar = false, nativeDp = 24f, hostDp = 0f))
        assertEquals(48f, resolveSlockBottomSafeAreaDp(hostExcludesNavigationBar = false, nativeDp = 24f, hostDp = 48f))
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
    fun retainedAppShellComposedTabsCanDescribeMountedInactiveTabs() {
        assertEquals(
            listOf("home", "tasks"),
            retainedAppShellComposedTabs("tasks", mountedTabs = setOf("home", "tasks"))
        )
        assertEquals(listOf("tasks"), activeOnlyAppShellComposedTabs("tasks"))
        assertEquals(listOf("home"), activeOnlyAppShellComposedTabs("unknown"))
    }

    @Test
    fun homeWarmAppShellComposedTabsKeepsOnlyHomeInactive() {
        assertEquals(
            listOf("home", "tasks"),
            homeWarmAppShellComposedTabs("tasks", mountedTabs = setOf("home", "tasks"))
        )
        assertEquals(
            listOf("tasks"),
            homeWarmAppShellComposedTabs("tasks", mountedTabs = setOf("tasks"))
        )
        assertEquals(
            listOf("home", "settings"),
            homeWarmAppShellComposedTabs("settings", mountedTabs = setOf("home", "tasks", "settings"))
        )
        assertEquals(
            listOf("home"),
            homeWarmAppShellComposedTabs("unknown", mountedTabs = setOf("home", "tasks"))
        )
    }

    @Test
    fun warmPrimaryAppShellComposedTabsKeepsVisitedTasksWarm() {
        assertEquals(
            listOf("home", "tasks"),
            warmPrimaryAppShellComposedTabs("tasks", mountedTabs = setOf("home", "tasks"))
        )
        assertEquals(
            listOf("tasks", "home"),
            warmPrimaryAppShellComposedTabs("home", mountedTabs = setOf("home", "tasks"))
        )
        assertEquals(
            listOf("home", "tasks", "settings"),
            warmPrimaryAppShellComposedTabs("settings", mountedTabs = setOf("home", "tasks", "settings"))
        )
        assertEquals(
            listOf("tasks", "home"),
            warmPrimaryAppShellComposedTabs("unknown", mountedTabs = setOf("home", "tasks"))
        )
    }

    @Test
    fun retainedAppShellInactivePaneKeepsFullLayoutOnlyForHome() {
        assertEquals(true, retainedAppShellInactivePaneKeepsLayout("home"))
        assertEquals(false, retainedAppShellInactivePaneKeepsLayout("tasks"))
        assertEquals(false, retainedAppShellInactivePaneKeepsLayout("settings"))
    }

    @Test
    fun childHostAppShellInactivePaneDoesNotKeepNativeSurfaceLayout() {
        assertEquals(false, childHostAppShellInactivePaneKeepsLayout("home"))
        assertEquals(false, childHostAppShellInactivePaneKeepsLayout("tasks"))
        assertEquals(false, childHostAppShellInactivePaneKeepsLayout("members"))
        assertEquals(false, childHostAppShellInactivePaneKeepsLayout("settings"))
    }

    @Test
    fun retainedHomeSecondaryDestinationKeepsOnlyActiveKnownDestination() {
        assertEquals(null, retainedHomeSecondaryDestination(null))
        assertEquals("search", retainedHomeSecondaryDestination("search"))
        assertEquals("activity", retainedHomeSecondaryDestination("activity"))
        assertEquals("saved", retainedHomeSecondaryDestination("saved"))
        assertEquals(null, retainedHomeSecondaryDestination("unknown"))
    }

    @Test
    fun homeSecondaryPerformanceRouteUsesDestinationIdentity() {
        assertEquals("kuikly_home_search", homeSecondaryPerformanceRoute("search"))
        assertEquals("kuikly_home_activity", homeSecondaryPerformanceRoute("activity"))
        assertEquals("kuikly_home_saved", homeSecondaryPerformanceRoute("saved"))
        assertEquals("kuikly_home_secondary", homeSecondaryPerformanceRoute("unknown"))
    }

    @Test
    fun retainedAppShellSelectedTabFallsBackToHome() {
        assertEquals("home", retainedAppShellSelectedTab("unknown"))
    }

    @Test
    fun appShellLazyListPositionClampsNegativeValues() {
        assertEquals(
            AppShellLazyListPosition(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0),
            appShellLazyListPosition(firstVisibleItemIndex = -4, firstVisibleItemScrollOffset = -20)
        )
        assertEquals(
            AppShellLazyListPosition(firstVisibleItemIndex = 3, firstVisibleItemScrollOffset = 18),
            appShellLazyListPosition(firstVisibleItemIndex = 3, firstVisibleItemScrollOffset = 18)
        )
    }

    @Test
    fun appShellMembersListStateDefaultsToExpandedTop() {
        assertEquals(
            AppShellMembersListState(),
            AppShellMembersListState(
                listPosition = AppShellLazyListPosition(),
                agentsExpanded = true,
                humansExpanded = true
            )
        )
    }

    @Test
    fun bottomTabPerformanceMarkerUsesRetainedTabIdentity() {
        assertEquals("kuikly_home", appShellBottomTabPerformanceRoute("home"))
        assertEquals("kuikly_tasks", appShellBottomTabPerformanceRoute("tasks"))
        assertEquals("kuikly_members", appShellBottomTabPerformanceRoute("members"))
        assertEquals("kuikly_settings", appShellBottomTabPerformanceRoute("settings"))
        assertEquals("kuikly_home", appShellBottomTabPerformanceRoute("unknown"))
        assertEquals("bottom_tab_home", appShellBottomTabPerformancePhase("unknown"))
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
    fun sharedHomeStartOnActiveSkipsFreshContentWithoutRealtimeDirty() {
        assertEquals(true, sharedHomeShouldStartOnActive(active = true, state = SharedHomeState()))
        assertEquals(false, sharedHomeShouldStartOnActive(active = false, state = SharedHomeState()))

        val loadedState =
            SharedHomeState(
                content =
                    SharedHomeContentState.Content(
                        snapshot =
                            HomeSnapshot(
                                server = ServerSummary(ServerId("server-1"), "Slock"),
                                currentMember =
                                    MemberProfile(
                                        id = MemberId("user-1"),
                                        displayName = "Artin",
                                        handle = "artin",
                                        kind = MemberKind.Human
                                    ),
                                channels = emptyList(),
                                directMessages = emptyList(),
                                unreadTotal = 0
                            ),
                        session =
                            StoredAuthSession(
                                user = StoredAuthUser(id = "user-1", email = "artin@slock.ai", displayName = "Artin"),
                                accessToken = "token",
                                refreshToken = "refresh",
                                selectedServerId = "server-1",
                                servers = listOf(StoredAuthServer(id = "server-1", name = "Slock", slug = "slock"))
                            )
                    )
            )

        assertEquals(false, sharedHomeShouldStartOnActive(active = true, state = loadedState))
        assertEquals(true, sharedHomeShouldStartOnActive(active = true, state = loadedState.copy(homeRealtimeDirty = true)))
    }

    @Test
    fun homeSurfaceVisibilityDispatchDelaysVisibleProjectionOnly() {
        assertEquals(0L, homeSurfaceVisibilityDispatchDelayMs(false))
        assertEquals(48L, homeSurfaceVisibilityDispatchDelayMs(true))
    }

    @Test
    fun searchSurfaceDoesNotRefreshOnLifecycleResume() {
        assertEquals(true, homeDestinationRefreshesOnLifecycleResume(null))
        assertEquals(true, homeDestinationRefreshesOnLifecycleResume("activity"))
        assertEquals(true, homeDestinationRefreshesOnLifecycleResume("saved"))
        assertEquals(false, homeDestinationRefreshesOnLifecycleResume("search"))
        assertEquals(true, homeDestinationRefreshesOnLifecycleResume("unknown"))
    }

    @Test
    fun membersCachedContentSeedsOnlyMatchingServer() {
        val content =
            SharedMembersUiState.Content(
                server = ServerSummary(ServerId("server-1"), "Slock"),
                members = emptyList(),
                currentUserId = "user-1"
            )

        assertEquals(content, sharedMembersCachedContentForServer(content, "server-1"))
        assertNull(sharedMembersCachedContentForServer(content, "server-2"))
        assertNull(sharedMembersCachedContentForServer(content, null))
        assertNull(sharedMembersCachedContentForServer(content, ""))
    }

    @Test
    fun authChooseServerRouteCarriesShellChromeIntentOnlyWhenRequested() {
        assertEquals(false, authChooseServerPageData().optBoolean("slockChooseServerFromShell", false))
        assertEquals(true, authChooseServerPageData(fromShell = true).optBoolean("slockChooseServerFromShell", false))
    }

    @Test
    fun serverGatePinsSignOutForSessionRefreshRecovery() {
        assertEquals(
            true,
            serverGateShouldPinSignOut(
                "Your session needs to be refreshed before choosing a server. Try again, or sign out and sign in again."
            )
        )
        assertEquals(false, serverGateShouldPinSignOut(null))
        assertEquals(false, serverGateShouldPinSignOut("Could not load servers."))
    }

}
