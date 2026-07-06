package build.raft.app

import build.raft.app.data.HomeSnapshot
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthUser
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageType
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
    fun overlayTabBarContentReservesFullClearanceForLastRow() {
        // Floating docks (Android floating, iOS liquid glass, OHOS HDS) overlay
        // content and the host pads nothing — scrollable content must reserve
        // the renderer's full clearance or its last row hides under the dock.
        val scope =
            SlockAppShellScope(
                topSafeArea = 24.dp,
                bottomSafeArea = 18.dp,
                bottomTabBarContentInset = 110.dp,
                bottomTabBarOverlaysContent = true
            )

        assertEquals(0.dp, scope.bottomTabBarHostInset(includeBottomTabBar = true))
        assertEquals(110.dp, scope.contentBottomInset(includeBottomTabBar = true))
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
    fun ohosHdsDockBlockerMatchesFloatingPillRect() {
        // Pill lives at [top+10 .. top+66] of the 92dp host (56dp bar lifted
        // 26dp off the bottom), inset 18dp per side — the Compose tap blocker
        // must cover exactly that rect so margins stay tap-through.
        assertEquals(10.dp, slockOhosHdsDockBlockerTopOffset())
        assertEquals(56.dp, slockOhosHdsDockBlockerHeight())
        assertEquals(18.dp, slockOhosHdsDockBlockerSideMargin())
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
    fun childHostAppShellPagerTabsKeepMountedChildTabsInFixedOrder() {
        assertEquals(
            listOf("home", "tasks"),
            childHostAppShellPagerTabs("tasks", mountedTabs = setOf("home", "tasks"))
        )
        assertEquals(
            listOf("home", "tasks", "members"),
            childHostAppShellPagerTabs("members", mountedTabs = setOf("home", "tasks", "members"))
        )
        assertEquals(
            listOf("home", "tasks", "settings"),
            childHostAppShellPagerTabs("settings", mountedTabs = setOf("home", "tasks", "settings"))
        )
        assertEquals(
            listOf("home", "tasks"),
            childHostAppShellPagerTabs("unknown", mountedTabs = setOf("tasks"))
        )
    }

    @Test
    fun childHostAppShellPagerTargetFollowsParentSelectedTab() {
        val pagerTabs = listOf("home", "tasks", "members")

        assertEquals("home", appShellActiveChildPagerTab("home"))
        assertEquals("tasks", appShellActiveChildPagerTab("tasks"))
        assertEquals("members", appShellActiveChildPagerTab("members"))
        assertEquals("settings", appShellActiveChildPagerTab("settings"))
        assertEquals("home", appShellActiveChildPagerTab("unknown"))

        assertEquals(0, appShellChildPagerTargetIndex("home", pagerTabs))
        assertEquals(1, appShellChildPagerTargetIndex("tasks", pagerTabs))
        assertEquals(2, appShellChildPagerTargetIndex("members", pagerTabs))
        assertNull(appShellChildPagerTargetIndex("settings", pagerTabs))
        assertEquals(3, appShellChildPagerTargetIndex("settings", pagerTabs + "settings"))
        assertNull(appShellChildPagerTargetIndex("members", listOf("home", "tasks")))
    }

    @Test
    fun childHostAppShellPagerTargetMovesBackToHomeWithTasksMounted() {
        val mountedTabs = setOf("home", "tasks")
        val tasksTabs = childHostAppShellPagerTabs("tasks", mountedTabs)
        val homeTabs = childHostAppShellPagerTabs("home", mountedTabs)

        assertEquals(tasksTabs, homeTabs)
        assertEquals(1, appShellChildPagerTargetIndex("tasks", tasksTabs))
        assertEquals(0, appShellChildPagerTargetIndex("home", homeTabs))
    }

    @Test
    fun appShellChildPagerSwitchEpochAdvancesOnlyForTabChanges() {
        assertEquals(0, appShellNextChildTabSwitchEpoch("home", "home", currentEpoch = 0))
        assertEquals(1, appShellNextChildTabSwitchEpoch("home", "tasks", currentEpoch = 0))
        assertEquals(2, appShellNextChildTabSwitchEpoch("tasks", "home", currentEpoch = 1))
        assertEquals(2, appShellNextChildTabSwitchEpoch("unknown", "home", currentEpoch = 2))
    }

    @Test
    fun childHostAppShellPagerFallbackDropsStaleEpoch() {
        assertEquals(
            true,
            appShellChildPagerScrollEpochCanApply(
                expectedTab = "home",
                currentTab = "home",
                expectedEpoch = 2,
                currentEpoch = 2,
                targetPage = 0
            )
        )
        assertEquals(
            false,
            appShellChildPagerScrollEpochCanApply(
                expectedTab = "tasks",
                currentTab = "home",
                expectedEpoch = 1,
                currentEpoch = 2,
                targetPage = 1
            )
        )
        assertEquals(
            false,
            appShellChildPagerScrollEpochCanApply(
                expectedTab = "home",
                currentTab = "home",
                expectedEpoch = 2,
                currentEpoch = 2,
                targetPage = null
            )
        )
    }

    @Test
    fun appShellActiveChildTabFollowsParentSelectedTab() {
        assertEquals("home", appShellActiveChildPagerTab("home"))
        assertEquals("tasks", appShellActiveChildPagerTab("tasks"))
        assertEquals("members", appShellActiveChildPagerTab("members"))
        assertEquals("settings", appShellActiveChildPagerTab("settings"))
        assertEquals("home", appShellActiveChildPagerTab("unknown"))
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
    fun appShellParentHomeStartRequiresReadyHomeRoot() {
        assertEquals(
            true,
            appShellParentShouldStartHome(
                selectedTab = "home",
                selectedHomeDestination = null,
                readyServerId = "server-1",
                state = SharedHomeState()
            )
        )
        assertEquals(
            false,
            appShellParentShouldStartHome(
                selectedTab = "tasks",
                selectedHomeDestination = null,
                readyServerId = "server-1",
                state = SharedHomeState()
            )
        )
        assertEquals(
            false,
            appShellParentShouldStartHome(
                selectedTab = "home",
                selectedHomeDestination = "activity",
                readyServerId = "server-1",
                state = SharedHomeState()
            )
        )
        assertEquals(
            false,
            appShellParentShouldStartHome(
                selectedTab = "home",
                selectedHomeDestination = null,
                readyServerId = null,
                state = SharedHomeState()
            )
        )
    }

    @Test
    fun homeSurfaceVisibilityDispatchDelaysVisibleProjectionOnly() {
        assertEquals(0L, homeSurfaceVisibilityDispatchDelayMs(false))
        assertEquals(48L, homeSurfaceVisibilityDispatchDelayMs(true))
    }

    @Test
    fun foregroundBannerProjectionSuppressesOwnMessage() {
        val banner =
            slockForegroundMessageBannerProjection(
                serverId = "server-1",
                currentServerId = "server-1",
                message = appShellForegroundTestMessage(authorId = "user-1"),
                currentMemberId = "user-1",
                visibleConversationKey = null
            )

        assertNull(banner)
    }

    @Test
    fun foregroundBannerProjectionSuppressesSystemMessage() {
        val banner =
            slockForegroundMessageBannerProjection(
                serverId = "server-1",
                currentServerId = "server-1",
                message =
                    appShellForegroundTestMessage(
                        authorId = "system",
                        authorKind = MemberKind.System,
                        messageType = MessageType.System,
                        body = "Cindy joined the channel"
                    ),
                currentMemberId = "user-1",
                visibleConversationKey = null
            )

        assertNull(banner)
    }

    @Test
    fun foregroundBannerProjectionShowsOnSettingsEvenWithSelectedHomeConversation() {
        val banner =
            slockForegroundMessageBannerProjection(
                serverId = "server-1",
                currentServerId = "server-1",
                message = appShellForegroundTestMessage(channelId = "channel-1"),
                currentMemberId = "user-1",
                visibleConversationKey =
                    appShellVisibleForegroundConversationKey(
                        selectedTab = "settings",
                        selectedHomeDestination = null,
                        selectedConversationId = "channel-1"
                    )
            )

        val resolved = assertNotNull(banner)
        assertEquals("foreground:server-1:message-1", resolved.id)
        assertEquals("message-1", resolved.focusedMessageId)
        assertEquals("channel-1", resolved.conversation.threadId.value)
        assertEquals("Cindy", resolved.title)
        assertEquals("", resolved.context)
        assertNull(resolved.threadContext)
        assertEquals(SlockForegroundMessageBannerIconKind.Channel, resolved.iconKind)
        assertEquals(SlockLucideIconName.Hash, resolved.iconName)
    }

    @Test
    fun foregroundBannerProjectionUsesDirectoryContextForChannelAndThread() {
        val directory =
            slockConversationDirectoryStateFromProjections(
                listOf(
                    SharedConversationDirectoryProjection(
                        serverId = "server-1",
                        canonicalChannelId = "parent-channel",
                        aliases = setOf("parent-channel"),
                        surfaceKind = KuiklyThreadSurfaceKind.Channel,
                        channelKind = "channel",
                        title = "team-ops"
                    ),
                    SharedConversationDirectoryProjection(
                        serverId = "server-1",
                        canonicalChannelId = "reply-stream",
                        aliases = setOf("reply-stream", "parent-channel", "parent-message"),
                        surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                        channelKind = "thread",
                        title = "deploy checklist",
                        subtitle = "Parent message preview",
                        parentChannelId = "parent-channel",
                        parentMessageId = "parent-message"
                    )
                )
            )
        val banner =
            slockForegroundMessageBannerProjection(
                serverId = "server-1",
                currentServerId = "server-1",
                message =
                    appShellForegroundTestMessage(
                        id = "reply-1",
                        channelId = "parent-channel",
                        threadChannelId = "reply-stream",
                        parentMessageId = "parent-message"
                    ),
                currentMemberId = "user-1",
                visibleConversationKey = null,
                conversationDirectory = directory
            )

        val resolved = assertNotNull(banner)
        assertEquals("Cindy", resolved.title)
        assertEquals("#team-ops - thread", resolved.context)
        assertEquals("Parent message preview", resolved.threadContext)
        assertEquals("Hello from another route", resolved.body)
        assertEquals(SlockForegroundMessageBannerIconKind.Channel, resolved.iconKind)
        assertEquals(SlockLucideIconName.MessageSquare, resolved.iconName)
        assertEquals(ConversationKind.Thread, resolved.openRequest.conversationIdentity.kind)
        assertEquals("parent-channel", resolved.openRequest.conversationIdentity.thread?.parentChannelId?.value)
        assertEquals("parent-message", resolved.openRequest.conversationIdentity.thread?.parentMessageId?.value)
        assertEquals("reply-stream", resolved.openRequest.conversationIdentity.thread?.streamChannelId?.value)
        assertEquals(KuiklyThreadSurfaceKind.Channel, resolved.openRequest.conversationIdentity.thread?.parentSurfaceKind)
        assertEquals(KuiklyThreadSurfaceKind.Channel, resolved.openRequest.conversationIdentity.thread?.parentChannelKind)
        assertEquals(null, resolved.openRequest.conversationIdentity.channelType)
        assertEquals("reply-1", resolved.openRequest.anchorMessageId)
        assertEquals("parent-channel", resolved.openRequest.originFallback?.parentChannelId)
        assertEquals("parent-message", resolved.openRequest.originFallback?.parentMessageId)
        assertEquals("Parent message preview", resolved.openRequest.originFallback?.preview)

        val launchPayload =
            buildAppShellThreadPayload(
                conversation = resolved.openRequest.conversationIdentity,
                anchorMessageId = resolved.openRequest.anchorMessageId,
                originFallback = resolved.openRequest.originFallback
            )
        assertEquals(KuiklyThreadSurfaceKind.Channel, launchPayload.parentSurfaceKind)
        assertEquals(KuiklyThreadSurfaceKind.Channel, launchPayload.parentChannelKind)
        assertEquals("Parent message preview", launchPayload.originPreview)
    }

    @Test
    fun foregroundBannerProjectionDoesNotRenderIdentityLikeChannelTitle() {
        val directory =
            slockConversationDirectoryStateFromProjections(
                listOf(
                    SharedConversationDirectoryProjection(
                        serverId = "server-1",
                        canonicalChannelId = "a8ebbc3c-a3d6-4b6e-8714-123456789abc",
                        aliases = setOf("a8ebbc3c-a3d6-4b6e-8714-123456789abc"),
                        surfaceKind = KuiklyThreadSurfaceKind.Channel,
                        channelKind = "channel",
                        title = "a8ebbc3c-a3d6-4b6e-8714-123456789abc"
                    )
                )
            )
        val banner =
            slockForegroundMessageBannerProjection(
                serverId = "server-1",
                currentServerId = "server-1",
                message =
                    appShellForegroundTestMessage(
                        channelId = "a8ebbc3c-a3d6-4b6e-8714-123456789abc",
                        threadId = "a8ebbc3c-a3d6-4b6e-8714-123456789abc"
                    ),
                currentMemberId = "user-1",
                visibleConversationKey = null,
                conversationDirectory = directory
            )

        val resolved = assertNotNull(banner)
        assertEquals("", resolved.context)
    }

    @Test
    fun foregroundBannerProjectionUsesSafeChannelNameInsteadOfId() {
        val directory =
            slockConversationDirectoryStateFromProjections(
                listOf(
                    SharedConversationDirectoryProjection(
                        serverId = "server-1",
                        canonicalChannelId = "a8ebbc3c-a3d6-4b6e-8714-123456789abc",
                        aliases = setOf("a8ebbc3c-a3d6-4b6e-8714-123456789abc"),
                        surfaceKind = KuiklyThreadSurfaceKind.Channel,
                        channelKind = "channel",
                        title = "home-fixes"
                    )
                )
            )
        val banner =
            slockForegroundMessageBannerProjection(
                serverId = "server-1",
                currentServerId = "server-1",
                message =
                    appShellForegroundTestMessage(
                        channelId = "a8ebbc3c-a3d6-4b6e-8714-123456789abc",
                        threadId = "a8ebbc3c-a3d6-4b6e-8714-123456789abc"
                    ),
                currentMemberId = "user-1",
                visibleConversationKey = null,
                conversationDirectory = directory
            )

        val resolved = assertNotNull(banner)
        assertEquals("#home-fixes", resolved.context)
    }

    @Test
    fun foregroundBannerProjectionUsesAvatarForDirectMessage() {
        val directory =
            slockConversationDirectoryStateFromProjections(
                listOf(
                    SharedConversationDirectoryProjection(
                        serverId = "server-1",
                        canonicalChannelId = "dm-1",
                        aliases = setOf("dm-1", "agent-1"),
                        surfaceKind = KuiklyThreadSurfaceKind.DirectMessage,
                        channelKind = "directmessage",
                        title = "Cindy",
                        avatarWireValue = "pixel:robot",
                        avatarLabel = "CI",
                        peerMemberId = "agent-1"
                    )
                )
            )
        val banner =
            slockForegroundMessageBannerProjection(
                serverId = "server-1",
                currentServerId = "server-1",
                message =
                    appShellForegroundTestMessage(
                        channelId = "dm-1",
                        threadId = "dm-1"
                    ),
                currentMemberId = "user-1",
                visibleConversationKey = null,
                conversationDirectory = directory
            )

        val resolved = assertNotNull(banner)
        assertEquals("@Cindy - direct message", resolved.context)
        assertNull(resolved.threadContext)
        assertEquals(SlockForegroundMessageBannerIconKind.DirectMessage, resolved.iconKind)
        assertNull(resolved.iconName)
        assertEquals("C", resolved.avatarLabel)
    }

    @Test
    fun foregroundBannerProjectionSuppressesVisibleHomeConversation() {
        val banner =
            slockForegroundMessageBannerProjection(
                serverId = "server-1",
                currentServerId = "server-1",
                message = appShellForegroundTestMessage(channelId = "channel-1"),
                currentMemberId = "user-1",
                visibleConversationKey =
                    appShellVisibleForegroundConversationKey(
                        selectedTab = "home",
                        selectedHomeDestination = null,
                        selectedConversationId = "channel-1"
                    )
            )

        assertNull(banner)
    }

    @Test
    fun foregroundBannerProjectionCarriesReplyThreadRouteIdentityOnly() {
        val banner =
            slockForegroundMessageBannerProjection(
                serverId = "server-1",
                currentServerId = "server-1",
                message =
                    appShellForegroundTestMessage(
                        id = "reply-1",
                        channelId = "parent-channel",
                        threadChannelId = "reply-stream",
                        parentMessageId = "parent-message"
                    ),
                currentMemberId = "user-1",
                visibleConversationKey = null
            )

        val resolved = assertNotNull(banner)
        assertEquals(ConversationKind.Thread, resolved.conversation.kind)
        assertEquals("parent-channel", resolved.conversation.thread?.parentChannelId?.value)
        assertEquals("parent-message", resolved.conversation.thread?.parentMessageId?.value)
        assertEquals("reply-stream", resolved.conversation.thread?.streamChannelId?.value)
        assertEquals("reply-1", resolved.focusedMessageId)
        assertEquals(KuiklyThreadSurfaceKind.Channel, resolved.openRequest.conversationIdentity.thread?.parentSurfaceKind)
        assertEquals(null, resolved.openRequest.conversationIdentity.channelType)

        val suppressed =
            slockForegroundMessageBannerProjection(
                serverId = "server-1",
                currentServerId = "server-1",
                message =
                    appShellForegroundTestMessage(
                        id = "reply-1",
                        channelId = "parent-channel",
                        threadChannelId = "reply-stream",
                        parentMessageId = "parent-message"
                    ),
                currentMemberId = "user-1",
                visibleConversationKey = null,
                visibleRouteIdentity = resolved.conversation.resolveThreadRouteIdentity()
            )

        assertNull(suppressed)
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
    fun postAuthRouteReturnsToServerChooserAfterFreshAuth() {
        val destination = postAuthRouteDestination()

        assertEquals(AUTH_CHOOSE_SERVER_PAGE, destination.pageName)
        assertEquals(AUTH_CHOOSE_SERVER_PAGE, destination.pageData.optString("pageName"))
        assertEquals(true, destination.pageData.optBoolean("slockReplaceCurrentPage", false))
        assertEquals(false, destination.pageData.optBoolean("slockSuppressRestoreLanding", false))
        assertEquals(true, destination.pageData.optBoolean("slockAuthChooseServerSkipInitialLoad", false))
    }

    @Test
    fun postAuthRouteReturnsToAppShellWhenServerWasRemembered() {
        val destination =
            postAuthRouteDestination(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "artin@raft.build"),
                    accessToken = "access",
                    refreshToken = "refresh",
                    selectedServerId = "server-1",
                    selectedServerSlug = "slock",
                    servers = listOf(StoredAuthServer(id = "server-1", name = "Slock", slug = "slock"))
                )
            )

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

private fun appShellForegroundTestMessage(
    id: String = "message-1",
    authorId: String = "agent-1",
    authorKind: MemberKind = MemberKind.Agent,
    channelId: String? = "channel-1",
    threadChannelId: String? = null,
    threadId: String = threadChannelId ?: channelId ?: "thread-1",
    parentMessageId: String? = null,
    body: String = "Hello from another route",
    messageType: MessageType = MessageType.Chat
): Message =
    Message(
        id = MessageId(id),
        threadId = ThreadId(threadId),
        channelId = channelId?.let(::ChannelId),
        threadChannelId = threadChannelId?.let(::ChannelId),
        author =
            MemberProfile(
                id = MemberId(authorId),
                displayName = "Cindy",
                handle = "cindy",
                kind = authorKind
            ),
        timestampLabel = "now",
        body = body,
        messageType = messageType,
        parentMessageId = parentMessageId?.let(::MessageId)
    )
