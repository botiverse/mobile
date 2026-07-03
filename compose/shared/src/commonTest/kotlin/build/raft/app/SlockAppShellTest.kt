package build.raft.app

import build.raft.app.data.HomeSnapshot
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthUser
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
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
                bottomTabBarContentInset = 88.dp,
                bottomTabBarOverlaysContent = false
            )

        assertEquals(24.dp, scope.topInset(includeTopSafeArea = true))
        assertEquals(0.dp, scope.topInset(includeTopSafeArea = false))
        assertEquals(18.dp, scope.bottomSafeArea)
        assertEquals(88.dp, scope.bottomTabBarContentInset)
        assertEquals(88.dp, scope.bottomInset(includeBottomTabBar = true))
        assertEquals(18.dp + SlockSpacing.Lg, scope.bottomInset(includeBottomTabBar = false))
        assertEquals(88.dp - SlockSpacing.Lg, scope.bottomTabBarHostInset(includeBottomTabBar = true))
        assertEquals(0.dp, scope.bottomTabBarHostInset(includeBottomTabBar = false))
        assertEquals(SlockSpacing.Lg, scope.contentBottomInset(includeBottomTabBar = true))
        assertEquals(18.dp + SlockSpacing.Lg, scope.contentBottomInset(includeBottomTabBar = false))
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
        assertEquals(1000f, SlockAppBottomTabBarHostZIndex)
        assertEquals(SlockFloatingOverlayHostZIndex, SlockAppBottomTabBarHostZIndex)
    }

    @Test
    fun androidFloatingBottomTabHostCoversOverlayTouchRegion() {
        assertEquals(92.dp, slockAndroidFloatingBottomTabBarHostHeight(0.dp))
        assertEquals(110.dp, slockAndroidFloatingBottomTabBarHostHeight(18.dp))
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
    fun appShellChildPageSelectedTabUsesOnlyChildTabs() {
        assertEquals("home", appShellChildPageSelectedTab("home"))
        assertEquals("tasks", appShellChildPageSelectedTab("tasks"))
        assertEquals("members", appShellChildPageSelectedTab("members"))
        assertEquals("settings", appShellChildPageSelectedTab("settings"))
        assertEquals("home", appShellChildPageSelectedTab("unknown"))
    }

    @Test
    fun appShellChildPageDataCarriesNormalizedInitialTab() {
        val homeData = appShellChildPageData(shellId = "shell-1", tab = "home")
        val settingsData = appShellChildPageData(shellId = "shell-1", tab = "settings")
        val unknownData = appShellChildPageData(shellId = "shell-1", tab = "unknown")

        assertEquals("shell-1", homeData.optString("shellId"))
        assertEquals("home", homeData.optString("tab"))
        assertEquals("settings", settingsData.optString("tab"))
        assertEquals("home", unknownData.optString("tab"))
    }

    @Test
    fun appShellChildPageDataTracksSelectedTabForSameShell() {
        val shellId = "shell-1"

        val homeData = appShellChildPageData(shellId = shellId, tab = "home")
        val tasksData = appShellChildPageData(shellId = shellId, tab = "tasks")
        val settingsData = appShellChildPageData(shellId = shellId, tab = "settings")

        assertEquals(shellId, homeData.optString("shellId"))
        assertEquals(shellId, tasksData.optString("shellId"))
        assertEquals(shellId, settingsData.optString("shellId"))
        assertEquals("home", homeData.optString("tab"))
        assertEquals("tasks", tasksData.optString("tab"))
        assertEquals("settings", settingsData.optString("tab"))
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
    fun appShellAuthGateViewModelKeyIsPageScoped() {
        assertEquals("shared-auth-gate-page-a", appShellAuthGateViewModelKey("page-a"))
        assertEquals("shared-auth-gate-page-b", appShellAuthGateViewModelKey("page-b"))
        assertEquals("shared-auth-gate-post-login-1", appShellAuthGateViewModelKey("page-a", "post-login-1"))
        assertEquals("shared-auth-gate-default", appShellAuthGateViewModelKey(""))
    }

    @Test
    fun appShellLaunchSelectionCanStartOnDiagnostics() {
        val params =
            JSONObject()
                .put("slockInitialTab", "settings")
                .put("slockInitialSettingsDestination", "diagnostics")

        assertEquals(
            AppShellLaunchSelection(
                selectedTab = "settings",
                selectedSettingsDestination = "diagnostics"
            ),
            params.appShellLaunchSelection()
        )
    }

    @Test
    fun appShellLaunchSelectionIgnoresSettingsDestinationOutsideSettingsTab() {
        val params =
            JSONObject()
                .put("slockInitialTab", "home")
                .put("slockInitialSettingsDestination", "diagnostics")

        assertEquals(
            AppShellLaunchSelection(selectedTab = "home", selectedSettingsDestination = "home"),
            params.appShellLaunchSelection()
        )
    }

    @Test
    fun diagnosticsDeepLinkRequestParsesControlFlagsOnly() {
        val params =
            JSONObject()
                .put("slockDiagnosticsDeepLinkId", "deep-link-1")
                .put("slockDiagnosticsDeepLinkEnableProfiler", true)
                .put("slockDiagnosticsDeepLinkResetScene", true)
                .put("slockDiagnosticsDeepLinkExportReport", true)
                .put("slockDiagnosticsDeepLinkAutoCloseMs", 15000L)

        assertEquals(
            DiagnosticsDeepLinkRequest(
                id = "deep-link-1",
                enableProfiler = true,
                resetScene = true,
                exportReport = true,
                autoCloseMs = 15000L
            ),
            params.diagnosticsDeepLinkRequest()
        )
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
    fun appShellLazyListPositionForServerIgnoresOtherServerSnapshot() {
        val saved =
            appShellServerScopedLazyListPosition(
                serverId = "server-a",
                listPosition = AppShellLazyListPosition(firstVisibleItemIndex = 5, firstVisibleItemScrollOffset = 24)
            )

        assertEquals(
            AppShellLazyListPosition(firstVisibleItemIndex = 5, firstVisibleItemScrollOffset = 24),
            appShellLazyListPositionForServer(currentServerId = "server-a", savedPosition = saved)
        )
        assertEquals(
            AppShellLazyListPosition(),
            appShellLazyListPositionForServer(currentServerId = "server-b", savedPosition = saved)
        )
        assertEquals(
            AppShellLazyListPosition(),
            appShellLazyListPositionForServer(currentServerId = "", savedPosition = saved)
        )
    }

    @Test
    fun appShellServerScopedLazyListPositionNormalizesBlankServerId() {
        assertEquals(
            AppShellServerScopedLazyListPosition(
                serverId = null,
                listPosition = AppShellLazyListPosition(firstVisibleItemIndex = 2, firstVisibleItemScrollOffset = 8)
            ),
            appShellServerScopedLazyListPosition(
                serverId = "  ",
                listPosition = AppShellLazyListPosition(firstVisibleItemIndex = 2, firstVisibleItemScrollOffset = 8)
            )
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
        assertEquals(true, sharedHomeShouldStartOnActive(active = true, sessionReady = true, state = SharedHomeState()))
        assertEquals(false, sharedHomeShouldStartOnActive(active = false, sessionReady = true, state = SharedHomeState()))
        assertEquals(false, sharedHomeShouldStartOnActive(active = true, sessionReady = false, state = SharedHomeState()))

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

        assertEquals(false, sharedHomeShouldStartOnActive(active = true, sessionReady = true, state = loadedState))
        assertEquals(true, sharedHomeShouldStartOnActive(active = true, sessionReady = true, state = loadedState.copy(homeRealtimeDirty = true)))
        assertEquals(false, sharedHomeShouldStartOnActive(active = true, sessionReady = false, state = loadedState.copy(homeRealtimeDirty = true)))
    }

    @Test
    fun homeSurfaceVisibilityDispatchDelaysVisibleProjectionOnly() {
        assertEquals(0L, homeSurfaceVisibilityDispatchDelayMs(false))
        assertEquals(48L, homeSurfaceVisibilityDispatchDelayMs(true))
    }

    @Test
    fun appShellPageDidAppearRefreshesOnlyAfterInitialAppearance() {
        assertEquals(false, appShellPageDidAppearShouldRefresh(0))
        assertEquals(true, appShellPageDidAppearShouldRefresh(1))
    }

    @Test
    fun searchSurfaceRefreshesOnLifecycleResume() {
        assertEquals(true, homeDestinationRefreshesOnLifecycleResume(null))
        assertEquals(true, homeDestinationRefreshesOnLifecycleResume("activity"))
        assertEquals(true, homeDestinationRefreshesOnLifecycleResume("saved"))
        assertEquals(true, homeDestinationRefreshesOnLifecycleResume("search"))
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
    fun appShellSelectedServerUpdatesReadySessionIdentity() {
        val session =
            StoredAuthSession(
                user = StoredAuthUser(id = "user-1", email = "artin@slock.ai"),
                accessToken = "token",
                refreshToken = "refresh",
                selectedServerId = "server-1",
                selectedServerSlug = "old",
                servers =
                    listOf(
                        StoredAuthServer(id = "server-1", name = "Old", slug = "old"),
                        StoredAuthServer(id = "server-2", name = "Server Two", slug = "server-two")
                    )
            )

        val selected = session.withAppShellSelectedServer("server-2")

        assertEquals("server-2", selected?.currentServerId)
        assertEquals("server-two", selected?.selectedServerSlug)
        assertNull(session.withAppShellSelectedServer("missing"))
        assertNull(session.withAppShellSelectedServer(""))
    }

    @Test
    fun authChooseServerRouteCarriesShellChromeIntentOnlyWhenRequested() {
        assertEquals(false, authChooseServerPageData().optBoolean("slockChooseServerFromShell", false))
        assertEquals(true, authChooseServerPageData(fromShell = true).optBoolean("slockChooseServerFromShell", false))
    }

    @Test
    fun authChooseServerRouteCanSkipInitialServerReloadForFreshNoServerSession() {
        assertEquals(false, authChooseServerPageData().optBoolean("slockAuthChooseServerSkipInitialLoad", false))
        assertEquals(
            true,
            authChooseServerPageData(skipInitialLoad = true)
                .optBoolean("slockAuthChooseServerSkipInitialLoad", false)
        )
    }

    @Test
    fun appShellRouteCanSuppressPostAuthRestoreLanding() {
        assertEquals(false, appShellPageData().optBoolean("slockSuppressRestoreLanding", false))
        assertEquals(
            true,
            appShellPageData(suppressRestoreLanding = true)
                .optBoolean("slockSuppressRestoreLanding", false)
        )
    }

    @Test
    fun postAuthRouteReturnsToAppShellForSessionRestore() {
        val destination = postAuthRouteDestination()

        assertEquals(SLOCK_APP_SHELL_PAGE, destination.pageName)
        assertEquals(SLOCK_APP_SHELL_PAGE, destination.pageData.optString("pageName"))
        assertEquals(true, destination.pageData.optBoolean("slockReplaceCurrentPage", false))
        assertEquals(true, destination.pageData.optBoolean("slockSuppressRestoreLanding", false))
        assertEquals(false, destination.pageData.optBoolean("slockAuthChooseServerSkipInitialLoad", false))
    }

    @Test
    fun postAuthSessionReadbackRequiresSameUserAndTokens() {
        val session =
            StoredAuthSession(
                user = StoredAuthUser(id = "user-1", email = "artin@raft.build"),
                accessToken = "access-1",
                refreshToken = "refresh-1"
            )

        assertEquals(true, session.isSamePostAuthSession(session.copy(servers = listOf(StoredAuthServer(id = "s1", name = "S1")))))
        assertEquals(false, session.copy(user = session.user.copy(id = "user-2")).isSamePostAuthSession(session))
        assertEquals(false, session.copy(accessToken = "access-2").isSamePostAuthSession(session))
        assertEquals(false, session.copy(refreshToken = "refresh-2").isSamePostAuthSession(session))
    }

    @Test
    fun loginRouteCarriesOnlyAuthIntent() {
        val pageData =
            loginPageData(
                replaceCurrent = true,
                message = "Sign in to continue.",
                socialProviders = "google,github"
            )

        assertEquals(LOGIN_PAGE, pageData.optString("pageName"))
        assertEquals(true, pageData.optBoolean("slockReplaceCurrentPage", false))
        assertEquals("Sign in to continue.", pageData.authRouteMessage())
        assertEquals("google,github", pageData.authRouteSocialProviders())
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
