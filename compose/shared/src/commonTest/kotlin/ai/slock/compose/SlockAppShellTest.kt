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
    fun retainedAppShellComposedTabsCanDescribeMountedInactiveTabs() {
        assertEquals(
            listOf("home", "tasks"),
            retainedAppShellComposedTabs("tasks", mountedTabs = setOf("home", "tasks"))
        )
        assertEquals(listOf("tasks"), activeOnlyAppShellComposedTabs("tasks"))
        assertEquals(listOf("home"), activeOnlyAppShellComposedTabs("thread"))
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
            homeWarmAppShellComposedTabs("thread", mountedTabs = setOf("home", "tasks"))
        )
    }

    @Test
    fun retainedAppShellSelectedTabFallsBackToHome() {
        assertEquals("home", retainedAppShellSelectedTab("thread"))
    }

    @Test
    fun bottomTabPerformanceMarkerUsesRetainedTabIdentity() {
        assertEquals("kuikly_home", appShellBottomTabPerformanceRoute("home"))
        assertEquals("kuikly_tasks", appShellBottomTabPerformanceRoute("tasks"))
        assertEquals("kuikly_members", appShellBottomTabPerformanceRoute("members"))
        assertEquals("kuikly_settings", appShellBottomTabPerformanceRoute("settings"))
        assertEquals("kuikly_home", appShellBottomTabPerformanceRoute("thread"))
        assertEquals("bottom_tab_home", appShellBottomTabPerformancePhase("thread"))
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
