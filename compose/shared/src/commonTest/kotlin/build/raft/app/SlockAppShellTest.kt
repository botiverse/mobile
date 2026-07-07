package build.raft.app

import build.raft.app.data.HomeSnapshot
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.InboxItem
import build.raft.app.data.InboxSnapshot
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.mergedSharedConversationDirectoryState
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageConversationContext
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
    fun appShellActiveChildTabFollowsParentSelectedTab() {
        assertEquals("home", appShellActiveChildPagerTab("home"))
        assertEquals("tasks", appShellActiveChildPagerTab("tasks"))
        assertEquals("members", appShellActiveChildPagerTab("members"))
        assertEquals("settings", appShellActiveChildPagerTab("settings"))
        assertEquals("home", appShellActiveChildPagerTab("unknown"))
    }

    @Test
    fun appShellChildPagerSwitchEpochAdvancesOnlyForTabChanges() {
        assertEquals(0, appShellNextChildTabSwitchEpoch("home", "home", currentEpoch = 0))
        assertEquals(1, appShellNextChildTabSwitchEpoch("home", "tasks", currentEpoch = 0))
        assertEquals(2, appShellNextChildTabSwitchEpoch("tasks", "home", currentEpoch = 1))
        assertEquals(2, appShellNextChildTabSwitchEpoch("unknown", "home", currentEpoch = 2))
    }

    @Test
    fun appShellRetainedChildTabsKeepMountedChildrenAliveInFixedOrder() {
        // Keepalive stack: every mounted child tab stays composed, ordered by the
        // canonical [home, tasks, members, settings], with the active tab always present.
        assertEquals(
            listOf("home", "tasks"),
            appShellRetainedChildTabs(activeChildTab = "tasks", mountedTabs = setOf("home", "tasks"))
        )
        assertEquals(
            listOf("home", "tasks", "members", "settings"),
            appShellRetainedChildTabs(
                activeChildTab = "home",
                mountedTabs = setOf("settings", "home", "members", "tasks")
            )
        )
        // Active tab is included even if mountedTabs is momentarily stale (no missing render).
        assertEquals(
            listOf("home", "tasks"),
            appShellRetainedChildTabs(activeChildTab = "tasks", mountedTabs = setOf("home"))
        )
        // No active child tab -> nothing to keep alive here (non-child surface renders directly).
        assertEquals(
            emptyList<String>(),
            appShellRetainedChildTabs(activeChildTab = null, mountedTabs = setOf("home", "tasks"))
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
    fun foregroundBannerProjectionSuppressesWhenCurrentServerMissing() {
        val result =
            slockForegroundMessageBannerProjectionResult(
                serverId = "server-1",
                currentServerId = null,
                message = appShellForegroundTestMessage(channelId = "channel-1"),
                currentMemberId = "user-1",
                visibleConversationKey = null
            )

        assertNull(result.banner)
        assertEquals("current_server_missing", result.suppressReason)
    }

    @Test
    fun foregroundBannerProjectionSuppressesMismatchedCurrentServer() {
        val result =
            slockForegroundMessageBannerProjectionResult(
                serverId = "server-2",
                currentServerId = "server-1",
                message = appShellForegroundTestMessage(channelId = "channel-1"),
                currentMemberId = "user-1",
                visibleConversationKey = null
            )

        assertNull(result.banner)
        assertEquals("server_mismatch", result.suppressReason)
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
                        conversationKind = ConversationKind.Channel,
                        surfaceKind = KuiklyThreadSurfaceKind.Channel,
                        channelKind = "channel",
                        title = "team-ops"
                    ),
                    SharedConversationDirectoryProjection(
                        serverId = "server-1",
                        canonicalChannelId = "reply-stream",
                        aliases = setOf("reply-stream", "parent-channel", "parent-message"),
                        conversationKind = ConversationKind.Thread,
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
    fun foregroundBannerProjectionUsesActivityEnrichForRawRealtimeReplyThread() {
        val directory =
            InboxSnapshot(
                server = ServerSummary(ServerId("server-1"), "Raft"),
                items =
                    listOf(
                        InboxItem(
                            key = "thread:reply-stream",
                            kind = InboxItemKind.Thread,
                            channelId = ChannelId("reply-stream"),
                            channelName = "home-fixes",
                            threadChannelId = ChannelId("reply-stream"),
                            parentChannelId = ChannelId("parent-channel"),
                            parentChannelName = "home-fixes",
                            parentChannelKind = ConversationKind.Channel,
                            parentMessageId = MessageId("parent-message"),
                            parentMessagePreview = "Original thread question",
                            latestActivityMessageId = MessageId("reply-1"),
                            latestActivityPreview = "The newest reply",
                            latestActivitySenderName = "KMP Expert",
                            latestActivitySenderId = "agent-kmp",
                            latestActivitySenderKind = MemberKind.Agent,
                            latestActivitySenderAvatarWireValue = "pixel:paint"
                        )
                    )
            ).toSharedConversationDirectoryState()
        val banner =
            slockForegroundMessageBannerProjection(
                serverId = "server-1",
                currentServerId = "server-1",
                message =
                    appShellForegroundTestMessage(
                        id = "reply-1",
                        channelId = "reply-stream",
                        threadId = "reply-stream",
                        body = "This should render as a reply-thread banner"
                    ),
                currentMemberId = "user-1",
                visibleConversationKey = null,
                conversationDirectory = directory
            )

        val resolved = assertNotNull(banner)
        assertEquals("#home-fixes - thread", resolved.context)
        assertEquals("Original thread question", resolved.threadContext)
        assertEquals("This should render as a reply-thread banner", resolved.body)
        assertEquals(ConversationKind.Thread, resolved.conversation.kind)
        assertEquals("parent-channel", resolved.conversation.thread?.parentChannelId?.value)
        assertEquals("parent-message", resolved.conversation.thread?.parentMessageId?.value)
        assertEquals("reply-stream", resolved.conversation.thread?.streamChannelId?.value)
        assertEquals("reply-1", resolved.openRequest.anchorMessageId)
        assertEquals("Original thread question", resolved.openRequest.originFallback?.preview)
        assertEquals("agent-kmp", resolved.senderMemberId)
        assertEquals("pixel:paint", resolved.avatarWireValue)
        assertEquals("KMP Expert", resolved.avatarLabel)
    }

    @Test
    fun foregroundBannerProjectionPreservesActivityDmThreadParentKind() {
        val activityItem =
            InboxItem(
                key = "thread:dm-reply-stream",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("dm-reply-stream"),
                channelName = "Cindy",
                threadChannelId = ChannelId("dm-reply-stream"),
                parentChannelId = ChannelId("dm-parent"),
                parentChannelName = "Cindy",
                parentChannelKind = ConversationKind.DirectMessage,
                parentMessageId = MessageId("parent-message"),
                parentMessagePreview = "Original DM question",
                latestActivityMessageId = MessageId("reply-1")
            )
        val directory =
            InboxSnapshot(
                server = ServerSummary(ServerId("server-1"), "Raft"),
                items = listOf(activityItem)
            ).toSharedConversationDirectoryState()
        val banner =
            slockForegroundMessageBannerProjection(
                serverId = "server-1",
                currentServerId = "server-1",
                message =
                    appShellForegroundTestMessage(
                        id = "reply-1",
                        channelId = "dm-reply-stream",
                        threadId = "dm-reply-stream",
                        body = "DM reply body"
                    ),
                currentMemberId = "user-1",
                visibleConversationKey = null,
                conversationDirectory = directory
            )

        val resolved = assertNotNull(banner)
        val activityOpenRequest = activityItem.toHomeMessageOpenRequestForTest()
        assertEquals(activityOpenRequest.conversationIdentity, resolved.openRequest.conversationIdentity)
        assertEquals(activityOpenRequest.anchorMessageId, resolved.openRequest.anchorMessageId)
        assertEquals("@Cindy - thread", resolved.context)
        assertEquals("Original DM question", resolved.threadContext)
        assertEquals(ConversationKind.Thread, resolved.openRequest.conversationIdentity.kind)
        assertEquals("dm-parent", resolved.openRequest.conversationIdentity.thread?.parentChannelId?.value)
        assertEquals("parent-message", resolved.openRequest.conversationIdentity.thread?.parentMessageId?.value)
        assertEquals("dm-reply-stream", resolved.openRequest.conversationIdentity.thread?.streamChannelId?.value)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, resolved.openRequest.conversationIdentity.thread?.parentSurfaceKind)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, resolved.openRequest.conversationIdentity.thread?.parentChannelKind)

        val launchPayload =
            buildAppShellThreadPayload(
                conversation = resolved.openRequest.conversationIdentity,
                anchorMessageId = resolved.openRequest.anchorMessageId,
                originFallback = resolved.openRequest.originFallback
            )
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, launchPayload.parentSurfaceKind)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, launchPayload.parentChannelKind)
    }

    @Test
    fun foregroundBannerReplyThreadWinsOverMergedDmAliasForDisplayAndOpenIdentity() {
        val homeState =
            HomeSnapshot(
                server = ServerSummary(ServerId("server-1"), "Raft"),
                currentMember =
                    MemberProfile(
                        id = MemberId("user-1"),
                        displayName = "Artin",
                        handle = "artin",
                        kind = MemberKind.Human
                    ),
                channels = emptyList(),
                directMessages =
                    listOf(
                        HomeDirectMessageRow(
                            threadId = ThreadId("reply-stream"),
                            channelId = ChannelId("dm-parent"),
                            kind = ConversationKind.DirectMessage,
                            name = "Cindy"
                        )
                    ),
                unreadTotal = 0
            ).toSharedConversationDirectoryState()
        val activityState =
            InboxSnapshot(
                server = ServerSummary(ServerId("server-1"), "Raft"),
                items =
                    listOf(
                        InboxItem(
                            key = "thread:reply-stream",
                            kind = InboxItemKind.Thread,
                            channelId = ChannelId("reply-stream"),
                            channelName = "home-fixes",
                            threadChannelId = ChannelId("reply-stream"),
                            parentChannelId = ChannelId("parent-channel"),
                            parentChannelName = "home-fixes",
                            parentChannelKind = ConversationKind.Channel,
                            parentMessageId = MessageId("parent-message"),
                            parentMessagePreview = "Original thread question",
                            latestActivityMessageId = MessageId("reply-1"),
                            latestActivityPreview = "Newest reply"
                        )
                    )
            ).toSharedConversationDirectoryState()

        listOf(
            listOf(homeState, activityState),
            listOf(activityState, homeState)
        ).forEach { states ->
            val result =
                slockForegroundMessageBannerProjectionResult(
                    serverId = "server-1",
                    currentServerId = "server-1",
                    message =
                        appShellForegroundTestMessage(
                            id = "reply-1",
                            channelId = "reply-stream",
                            threadChannelId = "reply-stream",
                            body = "This should not look like a DM"
                        ),
                    currentMemberId = "user-1",
                    visibleConversationKey = null,
                    conversationDirectory = states.mergedSharedConversationDirectoryState()
                )
            val banner = assertNotNull(result.banner)
            val projection = assertNotNull(result.sharedProjection)

            assertEquals(ConversationKind.Thread, projection.conversationKind)
            assertEquals(KuiklyThreadSurfaceKind.ReplyThread, projection.surfaceKind)
            assertEquals(ConversationKind.Thread, banner.conversation.kind)
            assertEquals(SlockForegroundMessageBannerIconKind.Channel, banner.iconKind)
            assertEquals(SlockLucideIconName.MessageSquare, banner.iconName)
            assertEquals("#home-fixes - thread", banner.context)
            assertEquals("Original thread question", banner.threadContext)
            assertEquals("This should not look like a DM", banner.body)
            assertEquals(ConversationKind.Thread, banner.openRequest.conversationIdentity.kind)
            assertEquals("parent-channel", banner.openRequest.conversationIdentity.thread?.parentChannelId?.value)
            assertEquals("parent-message", banner.openRequest.conversationIdentity.thread?.parentMessageId?.value)
            assertEquals("reply-stream", banner.openRequest.conversationIdentity.thread?.streamChannelId?.value)
            assertEquals("reply-1", banner.openRequest.anchorMessageId)
        }
    }

    // task #425 part 2: server-provided conversationContext short-circuits the
    // projection wait — routing correct immediately, labels best-effort.

    @Test
    fun foregroundBannerContextResolvesDmThreadWithoutDirectory() {
        val message =
            appShellForegroundTestMessage(
                id = "reply-1",
                channelId = "dm-reply-stream",
                threadId = "dm-reply-stream",
                body = "DM reply body",
                conversationContext =
                    MessageConversationContext(
                        channelKind = ConversationKind.Thread,
                        parentMessageId = MessageId("parent-message"),
                        parentChannelId = ChannelId("dm-parent"),
                        parentChannelKind = ConversationKind.DirectMessage
                    )
            )

        val banner =
            slockForegroundMessageBannerProjection(
                serverId = "server-1",
                currentServerId = "server-1",
                message = message,
                currentMemberId = "user-1",
                visibleConversationKey = null
            )
        val resolved = assertNotNull(banner)

        // Identity parity: the context-derived open request must equal the one
        // an enriched Activity row would build for the same ids.
        val activityItem =
            InboxItem(
                key = "thread:dm-reply-stream",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("dm-reply-stream"),
                channelName = "Cindy",
                threadChannelId = ChannelId("dm-reply-stream"),
                parentChannelId = ChannelId("dm-parent"),
                parentChannelName = "Cindy",
                parentChannelKind = ConversationKind.DirectMessage,
                parentMessageId = MessageId("parent-message"),
                latestActivityMessageId = MessageId("reply-1")
            )
        assertEquals(
            activityItem.toHomeMessageOpenRequestForTest().conversationIdentity,
            resolved.openRequest.conversationIdentity
        )
        assertEquals(ConversationKind.Thread, resolved.openRequest.conversationIdentity.kind)
        assertEquals(
            KuiklyThreadSurfaceKind.DirectMessage,
            resolved.openRequest.conversationIdentity.thread?.parentSurfaceKind
        )
        assertEquals(
            KuiklyThreadSurfaceKind.DirectMessage,
            resolved.openRequest.conversationIdentity.thread?.parentChannelKind
        )

        // Usable context means no defer: pending decision is Complete without
        // any directory projection.
        val rawResult =
            slockForegroundMessageBannerProjectionResult(
                serverId = "server-1",
                currentServerId = "server-1",
                message = message,
                currentMemberId = "user-1",
                visibleConversationKey = null
            )
        assertEquals(
            AppShellForegroundBannerPendingDecision.Complete,
            appShellForegroundBannerPendingDecision(
                projection = rawResult,
                message = message,
                refreshAttempts = 0
            )
        )
    }

    @Test
    fun foregroundBannerIncompleteThreadContextStillWaits() {
        // A context claiming Thread but missing its parent must not shortcut:
        // it cannot yield a safe identity, so the projection wait stays.
        val message =
            appShellForegroundTestMessage(
                id = "reply-2",
                channelId = "reply-stream",
                threadId = "reply-stream",
                conversationContext = MessageConversationContext(channelKind = ConversationKind.Thread)
            )
        val rawResult =
            slockForegroundMessageBannerProjectionResult(
                serverId = "server-1",
                currentServerId = "server-1",
                message = message,
                currentMemberId = "user-1",
                visibleConversationKey = null
            )
        assertEquals(
            AppShellForegroundBannerPendingDecision.Retry,
            appShellForegroundBannerPendingDecision(
                projection = rawResult,
                message = message,
                refreshAttempts = 0,
                maxAttempts = 2
            )
        )
    }

    @Test
    fun foregroundBannerDmContextShowsImmediatelyWhereLegacyWaited() {
        val withContext =
            appShellForegroundTestMessage(
                id = "dm-msg-1",
                channelId = "dm-1",
                threadId = "dm-1",
                conversationContext = MessageConversationContext(channelKind = ConversationKind.DirectMessage)
            )
        val contextResult =
            slockForegroundMessageBannerProjectionResult(
                serverId = "server-1",
                currentServerId = "server-1",
                message = withContext,
                currentMemberId = "user-1",
                visibleConversationKey = null
            )
        assertEquals(ConversationKind.DirectMessage, contextResult.banner?.conversation?.kind)
        assertEquals(
            AppShellForegroundBannerPendingDecision.Complete,
            appShellForegroundBannerPendingDecision(
                projection = contextResult,
                message = withContext,
                refreshAttempts = 0
            )
        )

        // Same message from an old server (no context): channel-shaped guess is
        // gated behind the wait machinery, unchanged.
        val withoutContext =
            appShellForegroundTestMessage(id = "dm-msg-2", channelId = "dm-1", threadId = "dm-1")
        val legacyResult =
            slockForegroundMessageBannerProjectionResult(
                serverId = "server-1",
                currentServerId = "server-1",
                message = withoutContext,
                currentMemberId = "user-1",
                visibleConversationKey = null
            )
        assertEquals(
            AppShellForegroundBannerPendingDecision.Retry,
            appShellForegroundBannerPendingDecision(
                projection = legacyResult,
                message = withoutContext,
                refreshAttempts = 0,
                maxAttempts = 2
            )
        )
    }

    @Test
    fun foregroundBannerProjectionResultTracksActivityThreadEnrich() {
        val message =
            appShellForegroundTestMessage(
                id = "reply-1",
                channelId = "reply-stream",
                threadId = "reply-stream",
                body = "This should wait for activity enrich"
            )
        val rawResult =
            slockForegroundMessageBannerProjectionResult(
                serverId = "server-1",
                currentServerId = "server-1",
                message = message,
                currentMemberId = "user-1",
                visibleConversationKey = null
            )
        assertEquals(null, rawResult.sharedProjection)
        // t434: the raw stage no longer fabricates a Channel identity — unproven means no
        // banner (and a deferred retry), not a guessed kind.
        assertEquals(null, rawResult.resolvedKind)
        assertNull(rawResult.banner)

        val enrichedDirectory =
            InboxSnapshot(
                server = ServerSummary(ServerId("server-1"), "Raft"),
                items =
                    listOf(
                        InboxItem(
                            key = "thread:reply-stream",
                            kind = InboxItemKind.Thread,
                            channelId = ChannelId("reply-stream"),
                            channelName = "home-fixes",
                            threadChannelId = ChannelId("reply-stream"),
                            parentChannelId = ChannelId("parent-channel"),
                            parentChannelName = "home-fixes",
                            parentChannelKind = ConversationKind.Channel,
                            parentMessageId = MessageId("parent-message"),
                            parentMessagePreview = "Original thread question",
                            latestActivityMessageId = MessageId("reply-1"),
                            latestActivityPreview = "The newest reply"
                        )
                    )
            ).toSharedConversationDirectoryState()
        val enrichedResult =
            slockForegroundMessageBannerProjectionResult(
                serverId = "server-1",
                currentServerId = "server-1",
                message = message,
                currentMemberId = "user-1",
                visibleConversationKey = null,
                conversationDirectory = enrichedDirectory
            )

        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, enrichedResult.sharedProjection?.surfaceKind)
        assertEquals(ConversationKind.Thread, enrichedResult.resolvedKind)
        assertEquals("Original thread question", enrichedResult.banner?.threadContext)
        assertEquals("parent-channel", enrichedResult.banner?.conversation?.thread?.parentChannelId?.value)
        assertEquals("parent-message", enrichedResult.banner?.conversation?.thread?.parentMessageId?.value)
    }

    @Test
    fun foregroundBannerPendingDecisionWaitsForActivityThreadEnrich() {
        val message =
            appShellForegroundTestMessage(
                id = "reply-1",
                channelId = "reply-stream",
                threadId = "reply-stream",
                body = "This should not flash as a plain channel"
            )
        val rawResult =
            slockForegroundMessageBannerProjectionResult(
                serverId = "server-1",
                currentServerId = "server-1",
                message = message,
                currentMemberId = "user-1",
                visibleConversationKey = null
            )
        assertEquals(
            AppShellForegroundBannerPendingDecision.Retry,
            appShellForegroundBannerPendingDecision(
                projection = rawResult,
                message = message,
                refreshAttempts = 0,
                maxAttempts = 2
            )
        )
        assertEquals(
            AppShellForegroundBannerPendingDecision.Drop,
            appShellForegroundBannerPendingDecision(
                projection = rawResult,
                message = message,
                refreshAttempts = 2,
                maxAttempts = 2
            )
        )

        val enrichedDirectory =
            InboxSnapshot(
                server = ServerSummary(ServerId("server-1"), "Raft"),
                items =
                    listOf(
                        InboxItem(
                            key = "thread:reply-stream",
                            kind = InboxItemKind.Thread,
                            channelId = ChannelId("reply-stream"),
                            channelName = "home-fixes",
                            threadChannelId = ChannelId("reply-stream"),
                            parentChannelId = ChannelId("parent-channel"),
                            parentChannelName = "home-fixes",
                            parentChannelKind = ConversationKind.Channel,
                            parentMessageId = MessageId("parent-message"),
                            parentMessagePreview = "Original thread question",
                            latestActivityMessageId = MessageId("reply-1")
                        )
                    )
            ).toSharedConversationDirectoryState()
        val enrichedResult =
            slockForegroundMessageBannerProjectionResult(
                serverId = "server-1",
                currentServerId = "server-1",
                message = message,
                currentMemberId = "user-1",
                visibleConversationKey = null,
                conversationDirectory = enrichedDirectory
            )
        assertEquals(
            AppShellForegroundBannerPendingDecision.Complete,
            appShellForegroundBannerPendingDecision(
                projection = enrichedResult,
                message = message,
                refreshAttempts = 0
            )
        )
    }

    @Test
    fun notificationLaunchUsesActivityEnrichForReplyThread() {
        val directory =
            InboxSnapshot(
                server = ServerSummary(ServerId("server-1"), "Raft"),
                items =
                    listOf(
                        InboxItem(
                            key = "thread:reply-stream",
                            kind = InboxItemKind.Thread,
                            channelId = ChannelId("reply-stream"),
                            channelName = "home-fixes",
                            threadChannelId = ChannelId("reply-stream"),
                            parentChannelId = ChannelId("parent-channel"),
                            parentChannelName = "home-fixes",
                            parentChannelKind = ConversationKind.Channel,
                            parentMessageId = MessageId("parent-message"),
                            parentMessagePreview = "Original thread question",
                            latestActivityMessageId = MessageId("reply-1")
                        )
                    )
            ).toSharedConversationDirectoryState()
        val openRequest =
            directory.notificationThreadLaunchRequest(
                AppShellNotificationThreadLaunchRequest(
                    threadId = "reply-stream",
                    messageId = "reply-1"
                )
            )

        val resolved = assertNotNull(openRequest)
        assertEquals(ConversationKind.Thread, resolved.conversationIdentity.kind)
        assertEquals("parent-channel", resolved.conversationIdentity.thread?.parentChannelId?.value)
        assertEquals("parent-message", resolved.conversationIdentity.thread?.parentMessageId?.value)
        assertEquals("reply-stream", resolved.conversationIdentity.thread?.streamChannelId?.value)
        assertEquals("reply-1", resolved.anchorMessageId)
        assertEquals("Original thread question", resolved.originFallback?.preview)
    }

    @Test
    fun activityPendingOpenPreservesOriginFallback() {
        // Pre-#419 this pinned the Pending->direct-builder fallback (which could navigate on
        // a guessed identity — the removed leak). Under the resolver-only contract a thread
        // row with no usable stream/channel id stays Pending(conversation_hydrating), and the
        // Pending's source identity must still carry the origin fallback so a later retry
        // (or hydrated resolution) loses nothing.
        val activityItem =
            InboxItem(
                key = "thread:pending-reply",
                kind = InboxItemKind.Thread,
                channelId = ChannelId(""),
                channelName = "home-fixes",
                parentChannelId = ChannelId("parent-channel"),
                parentChannelName = "home-fixes",
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("parent-message"),
                parentMessagePreview = "Original thread question",
                parentMessageSenderId = "agent-owner",
                parentMessageSenderKind = MemberKind.Agent,
                latestActivityMessageId = MessageId("reply-1")
            )

        val resolution =
            activityItem.toHomeMessageOpenRequest(
                serverId = "",
                conversationDirectory = SharedConversationDirectoryState()
            )

        val pending = assertNotNull(resolution as? ConversationOpenResolution.Pending)
        assertEquals("conversation_hydrating", pending.reason)
        val originFallback = pending.sourceMessageIdentity.originFallback
        assertEquals("parent-channel", originFallback?.parentChannelId)
        assertEquals("parent-message", originFallback?.parentMessageId)
        assertEquals("Original thread question", originFallback?.preview)
        assertEquals("agent-owner", originFallback?.senderId)
        assertEquals(MemberKind.Agent, originFallback?.senderKind)
    }

    @Test
    fun notificationLaunchPreservesActivityDmThreadParentKind() {
        val activityItem =
            InboxItem(
                key = "thread:dm-reply-stream",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("dm-reply-stream"),
                channelName = "Cindy",
                threadChannelId = ChannelId("dm-reply-stream"),
                parentChannelId = ChannelId("dm-parent"),
                parentChannelName = "Cindy",
                parentChannelKind = ConversationKind.DirectMessage,
                parentMessageId = MessageId("parent-message"),
                parentMessagePreview = "Original DM question",
                latestActivityMessageId = MessageId("reply-1")
            )
        val directory =
            InboxSnapshot(
                server = ServerSummary(ServerId("server-1"), "Raft"),
                items = listOf(activityItem)
            ).toSharedConversationDirectoryState()
        val openRequest =
            directory.notificationThreadLaunchRequest(
                AppShellNotificationThreadLaunchRequest(
                    threadId = "dm-reply-stream",
                    messageId = "reply-1"
                )
            )

        val resolved = assertNotNull(openRequest)
        val activityOpenRequest = activityItem.toHomeMessageOpenRequestForTest()
        assertEquals(activityOpenRequest.conversationIdentity, resolved.conversationIdentity)
        assertEquals(activityOpenRequest.anchorMessageId, resolved.anchorMessageId)
        assertEquals(ConversationKind.Thread, resolved.conversationIdentity.kind)
        assertEquals("dm-parent", resolved.conversationIdentity.thread?.parentChannelId?.value)
        assertEquals("parent-message", resolved.conversationIdentity.thread?.parentMessageId?.value)
        assertEquals("dm-reply-stream", resolved.conversationIdentity.thread?.streamChannelId?.value)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, resolved.conversationIdentity.thread?.parentSurfaceKind)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, resolved.conversationIdentity.thread?.parentChannelKind)

        val launchPayload =
            buildAppShellThreadPayload(
                conversation = resolved.conversationIdentity,
                anchorMessageId = resolved.anchorMessageId,
                originFallback = resolved.originFallback
            )
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, launchPayload.parentSurfaceKind)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, launchPayload.parentChannelKind)
    }

    @Test
    fun suppressedOwnMessageNeverEntersTheWaitLoop() {
        // t434 required fix (LiBai): suppression is a terminal decision. An own message —
        // which has a stream id — must not fall into the unproven-wait path (5 retries +
        // an Activity refresh each) now that the guessed-banner null-check is gone.
        val message =
            appShellForegroundTestMessage(
                id = "own-1",
                authorId = "user-1",
                authorKind = MemberKind.Human,
                channelId = "channel-1",
                body = "My own message"
            )
        val result =
            slockForegroundMessageBannerProjectionResult(
                serverId = "server-1",
                currentServerId = "server-1",
                message = message,
                currentMemberId = "user-1",
                visibleConversationKey = null
            )

        assertNull(result.banner)
        assertNotNull(result.suppressReason)
        assertEquals(
            AppShellForegroundBannerPendingDecision.Complete,
            appShellForegroundBannerPendingDecision(
                projection = result,
                message = message,
                refreshAttempts = 0,
                maxAttempts = 2
            )
        )
    }

    @Test
    fun plainChannelMessageDefersThenPopsWhenProjectionArrives() {
        // t434 key regression (LiBai): deleting the raw guess fallback must not turn "pop
        // after the directory catches up" into "never pop". Unproven -> Retry (no banner,
        // no guessed chrome); projection arrives -> Complete with the real channel banner.
        val message =
            appShellForegroundTestMessage(
                id = "message-9",
                channelId = "channel-9",
                threadId = "channel-9",
                body = "Plain channel message"
            )
        val rawResult =
            slockForegroundMessageBannerProjectionResult(
                serverId = "server-1",
                currentServerId = "server-1",
                message = message,
                currentMemberId = "user-1",
                visibleConversationKey = null
            )
        assertNull(rawResult.banner)
        assertEquals(
            AppShellForegroundBannerPendingDecision.Retry,
            appShellForegroundBannerPendingDecision(
                projection = rawResult,
                message = message,
                refreshAttempts = 0,
                maxAttempts = 2
            )
        )

        val enrichedResult =
            slockForegroundMessageBannerProjectionResult(
                serverId = "server-1",
                currentServerId = "server-1",
                message = message,
                currentMemberId = "user-1",
                visibleConversationKey = null,
                conversationDirectory =
                    InboxSnapshot(
                        server = ServerSummary(ServerId("server-1"), "Raft"),
                        items =
                            listOf(
                                InboxItem(
                                    key = "channel:channel-9",
                                    kind = InboxItemKind.Channel,
                                    channelId = ChannelId("channel-9"),
                                    channelName = "home-fixes",
                                    lastMessageId = MessageId("message-9")
                                )
                            )
                    ).toSharedConversationDirectoryState()
            )
        assertEquals(
            AppShellForegroundBannerPendingDecision.Complete,
            appShellForegroundBannerPendingDecision(
                projection = enrichedResult,
                message = message,
                refreshAttempts = 1,
                maxAttempts = 2
            )
        )
        val banner = assertNotNull(enrichedResult.banner)
        assertEquals(ConversationKind.Channel, banner.conversation.kind)
        assertEquals("channel-9", banner.conversation.channelId?.value)
    }

    @Test
    fun foregroundBannerDropsReplyThreadWhenParentKindUnresolved() {
        // t5/#419 A1: a reply thread whose parent kind can't be resolved (parentChannelKind
        // absent -> #249 drops the parent projection; the parent id only self-resolves the
        // thread via alias) must be suppressed, never silently opened on the channel surface.
        val message =
            appShellForegroundTestMessage(
                id = "reply-1",
                channelId = "reply-stream",
                threadId = "reply-stream",
                body = "Reply whose parent kind can't be resolved"
            )
        val directory =
            InboxSnapshot(
                server = ServerSummary(ServerId("server-1"), "Raft"),
                items =
                    listOf(
                        InboxItem(
                            key = "thread:reply-stream",
                            kind = InboxItemKind.Thread,
                            channelId = ChannelId("reply-stream"),
                            channelName = "home-fixes",
                            threadChannelId = ChannelId("reply-stream"),
                            parentChannelId = ChannelId("parent-channel"),
                            parentChannelName = "home-fixes",
                            // parentChannelKind intentionally omitted (null).
                            parentMessageId = MessageId("parent-message"),
                            latestActivityMessageId = MessageId("reply-1")
                        )
                    )
            ).toSharedConversationDirectoryState()
        val result =
            slockForegroundMessageBannerProjectionResult(
                serverId = "server-1",
                currentServerId = "server-1",
                message = message,
                currentMemberId = "user-1",
                visibleConversationKey = null,
                conversationDirectory = directory
            )

        assertNull(result.banner)
        assertEquals("conversation_missing", result.suppressReason)
    }

    @Test
    fun foregroundBannerContextRescuesReplyThreadWhenProjectionParentUnresolved() {
        // t430 acceptance ①: rescue fires ONLY on projection-hit + builder-null + context
        // available — same fixture as foregroundBannerDropsReplyThreadWhenParentKindUnresolved
        // (which pins the context-unavailable branch: conversation_missing), plus a trusted
        // server conversationContext -> the banner opens with the correct DM-thread identity.
        val message =
            appShellForegroundTestMessage(
                id = "reply-1",
                channelId = "reply-stream",
                threadId = "reply-stream",
                body = "Rescued by server context",
                conversationContext =
                    MessageConversationContext(
                        channelKind = ConversationKind.Thread,
                        parentMessageId = MessageId("parent-message"),
                        parentChannelId = ChannelId("dm-parent"),
                        parentChannelKind = ConversationKind.DirectMessage
                    )
            )
        val directory =
            InboxSnapshot(
                server = ServerSummary(ServerId("server-1"), "Raft"),
                items =
                    listOf(
                        InboxItem(
                            key = "thread:reply-stream",
                            kind = InboxItemKind.Thread,
                            channelId = ChannelId("reply-stream"),
                            channelName = "Cindy",
                            threadChannelId = ChannelId("reply-stream"),
                            parentChannelId = ChannelId("dm-parent"),
                            parentChannelName = "Cindy",
                            // parentChannelKind intentionally omitted -> parent projection
                            // dropped -> builder-null (the rescue precondition).
                            parentMessageId = MessageId("parent-message"),
                            latestActivityMessageId = MessageId("reply-1")
                        )
                    )
            ).toSharedConversationDirectoryState()
        val result =
            slockForegroundMessageBannerProjectionResult(
                serverId = "server-1",
                currentServerId = "server-1",
                message = message,
                currentMemberId = "user-1",
                visibleConversationKey = null,
                conversationDirectory = directory
            )

        val banner = assertNotNull(result.banner)
        assertNull(result.suppressReason)
        assertEquals(ConversationKind.Thread, banner.conversation.kind)
        assertEquals("dm-parent", banner.conversation.thread?.parentChannelId?.value)
        assertEquals("parent-message", banner.conversation.thread?.parentMessageId?.value)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, banner.conversation.thread?.parentSurfaceKind)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, banner.conversation.thread?.parentChannelKind)
    }

    @Test
    fun notificationLaunchDropsReplyThreadWhenParentKindUnresolved() {
        // t5/#419 A2: the notification source carries no typed parent kind, so an unresolvable
        // reply-thread parent yields a null open request (Pending -> null drop).
        val directory =
            InboxSnapshot(
                server = ServerSummary(ServerId("server-1"), "Raft"),
                items =
                    listOf(
                        InboxItem(
                            key = "thread:reply-stream",
                            kind = InboxItemKind.Thread,
                            channelId = ChannelId("reply-stream"),
                            channelName = "home-fixes",
                            threadChannelId = ChannelId("reply-stream"),
                            parentChannelId = ChannelId("parent-channel"),
                            parentChannelName = "home-fixes",
                            // parentChannelKind intentionally omitted (null).
                            parentMessageId = MessageId("parent-message"),
                            latestActivityMessageId = MessageId("reply-1")
                        )
                    )
            ).toSharedConversationDirectoryState()

        val openRequest =
            directory.notificationThreadLaunchRequest(
                AppShellNotificationThreadLaunchRequest(
                    threadId = "reply-stream",
                    messageId = "reply-1"
                )
            )

        assertNull(openRequest)
    }

    @Test
    fun resolveOpenRequestDoesNotRebuildThreadWithoutTrustedParentKind() {
        // t5/#419 regression: projection resolves but its reply-thread parent shape is
        // unresolvable, and the source has no typed parent kind -> must NOT raw-rebuild a
        // channel-shaped guess; stays Pending(thread_parent_unresolved), distinct from a plain
        // projection miss (conversation_hydrating).
        val directory =
            InboxSnapshot(
                server = ServerSummary(ServerId("server-1"), "Raft"),
                items =
                    listOf(
                        InboxItem(
                            key = "thread:reply-stream",
                            kind = InboxItemKind.Thread,
                            channelId = ChannelId("reply-stream"),
                            channelName = "home-fixes",
                            threadChannelId = ChannelId("reply-stream"),
                            parentChannelId = ChannelId("parent-channel"),
                            parentChannelName = "home-fixes",
                            // parentChannelKind intentionally omitted (null).
                            parentMessageId = MessageId("parent-message"),
                            latestActivityMessageId = MessageId("reply-1")
                        )
                    )
            ).toSharedConversationDirectoryState()
        val resolution =
            resolveOpenRequest(
                serverId = "server-1",
                sourceMessageIdentity =
                    AppShellNotificationThreadLaunchRequest(
                        threadId = "reply-stream",
                        messageId = "reply-1"
                    ).toConversationOpenSourceMessageIdentity(),
                conversationDirectory = directory
            )

        val pending = assertNotNull(resolution as? ConversationOpenResolution.Pending)
        assertEquals("thread_parent_unresolved", pending.reason)
    }

    @Test
    fun resolveOpenRequestRebuildsThreadFromTrustedDmParentKind() {
        // t5/#419 anti-over-drop: the directory's parent projection is dropped (unresolvable),
        // but the Activity source carries a trusted typed DM parent kind -> allow a raw rebuild
        // to Resolved, preserving the dm parent shape (Activity rows aren't over-dropped).
        val directory =
            InboxSnapshot(
                server = ServerSummary(ServerId("server-1"), "Raft"),
                items =
                    listOf(
                        InboxItem(
                            key = "thread:dm-reply-stream",
                            kind = InboxItemKind.Thread,
                            channelId = ChannelId("dm-reply-stream"),
                            channelName = "Cindy",
                            threadChannelId = ChannelId("dm-reply-stream"),
                            parentChannelId = ChannelId("dm-parent"),
                            parentChannelName = "Cindy",
                            // parentChannelKind intentionally omitted (null) so the directory
                            // drops the parent projection.
                            parentMessageId = MessageId("parent-message"),
                            latestActivityMessageId = MessageId("reply-1")
                        )
                    )
            ).toSharedConversationDirectoryState()
        val trustedSource =
            InboxItem(
                key = "thread:dm-reply-stream",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("dm-reply-stream"),
                channelName = "Cindy",
                threadChannelId = ChannelId("dm-reply-stream"),
                parentChannelId = ChannelId("dm-parent"),
                parentChannelName = "Cindy",
                parentChannelKind = ConversationKind.DirectMessage,
                parentMessageId = MessageId("parent-message"),
                latestActivityMessageId = MessageId("reply-1")
            ).toConversationOpenSourceMessageIdentity()
        val resolution =
            resolveOpenRequest(
                serverId = "server-1",
                sourceMessageIdentity = trustedSource,
                conversationDirectory = directory
            )

        val request = assertNotNull((resolution as? ConversationOpenResolution.Resolved)?.request)
        assertEquals(ConversationKind.Thread, request.conversationIdentity.kind)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, request.conversationIdentity.thread?.parentSurfaceKind)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, request.conversationIdentity.thread?.parentChannelKind)
    }

    @Test
    fun conversationOpenResolverKeepsActivityAndNotificationRoutesEquivalent() {
        val activityItem =
            InboxItem(
                key = "thread:dm-reply-stream",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("dm-reply-stream"),
                channelName = "Cindy",
                threadChannelId = ChannelId("dm-reply-stream"),
                parentChannelId = ChannelId("dm-parent"),
                parentChannelName = "Cindy",
                parentChannelKind = ConversationKind.DirectMessage,
                parentMessageId = MessageId("parent-message"),
                parentMessagePreview = "Original DM question",
                parentMessageSenderId = "agent-cindy",
                parentMessageSenderKind = MemberKind.Agent,
                latestActivityMessageId = MessageId("reply-1")
            )
        val directory =
            InboxSnapshot(
                server = ServerSummary(ServerId("server-1"), "Raft"),
                items = listOf(activityItem)
            ).toSharedConversationDirectoryState()
        val activityRequest =
            assertNotNull(
                (resolveOpenRequest(
                    serverId = "server-1",
                    sourceMessageIdentity = activityItem.toConversationOpenSourceMessageIdentity(),
                    conversationDirectory = directory
                ) as? ConversationOpenResolution.Resolved)?.request
            )
        val notificationRequest =
            assertNotNull(
                (resolveOpenRequest(
                    serverId = "server-1",
                    sourceMessageIdentity =
                        AppShellNotificationThreadLaunchRequest(
                            threadId = "dm-reply-stream",
                            messageId = "reply-1"
                        ).toConversationOpenSourceMessageIdentity(),
                    conversationDirectory = directory
                ) as? ConversationOpenResolution.Resolved)?.request
            )

        assertEquals(activityRequest.conversationIdentity, notificationRequest.conversationIdentity)
        assertEquals(activityRequest.anchorMessageId, notificationRequest.anchorMessageId)
        assertEquals(ConversationKind.Thread, activityRequest.conversationIdentity.kind)
        assertEquals("dm-parent", activityRequest.conversationIdentity.thread?.parentChannelId?.value)
        assertEquals("parent-message", activityRequest.conversationIdentity.thread?.parentMessageId?.value)
        assertEquals("dm-reply-stream", activityRequest.conversationIdentity.thread?.streamChannelId?.value)

        val activityPayload =
            buildAppShellThreadPayload(
                conversation = activityRequest.conversationIdentity,
                anchorMessageId = activityRequest.anchorMessageId,
                originFallback = activityRequest.originFallback
            )
        val notificationPayload =
            buildAppShellThreadPayload(
                conversation = notificationRequest.conversationIdentity,
                anchorMessageId = notificationRequest.anchorMessageId,
                originFallback = notificationRequest.originFallback
            )
        assertEquals(activityPayload.surfaceKind, notificationPayload.surfaceKind)
        assertEquals(activityPayload.sourceThreadId, notificationPayload.sourceThreadId)
        assertEquals(activityPayload.streamChannelId, notificationPayload.streamChannelId)
        assertEquals(activityPayload.parentChannelId, notificationPayload.parentChannelId)
        assertEquals(activityPayload.parentMessageId, notificationPayload.parentMessageId)
        assertEquals(activityPayload.parentSurfaceKind, notificationPayload.parentSurfaceKind)
        assertEquals(activityPayload.parentChannelKind, notificationPayload.parentChannelKind)
        assertEquals(activityPayload.focusedMessageId, notificationPayload.focusedMessageId)
        assertEquals(activityPayload.originParentMessageId, notificationPayload.originParentMessageId)
        assertEquals(activityPayload.originPreview, notificationPayload.originPreview)
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
                        conversationKind = ConversationKind.Channel,
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
                        conversationKind = ConversationKind.Channel,
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
                        conversationKind = ConversationKind.DirectMessage,
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
    fun authChooseServerDoesNotSkipInitialLoadForEmptyCachedServerList() {
        assertEquals(false, authChooseServerShouldSkipInitialLoad(skipInitialLoad = true, cachedServersCount = 0))
        assertEquals(true, authChooseServerShouldSkipInitialLoad(skipInitialLoad = true, cachedServersCount = 1))
        assertEquals(false, authChooseServerShouldSkipInitialLoad(skipInitialLoad = false, cachedServersCount = 1))
    }

    @Test
    fun authCreateServerSlugMirrorsWebToSlugRules() {
        // Web parity: ServerSelector.tsx toSlug = lowercase + every char
        // outside [a-z0-9-] replaced with "-", no collapsing or trimming.
        assertEquals("xxx", authCreateServerSlugFromName("xxx"))
        assertEquals("my-team", authCreateServerSlugFromName("My Team"))
        assertEquals("a-b-c", authCreateServerSlugFromName("A B!C"))
        assertEquals("raft-2", authCreateServerSlugFromName("Raft 2"))
        assertEquals("already-fine-123", authCreateServerSlugFromName("already-fine-123"))
        assertEquals("--", authCreateServerSlugFromName("工作"))
        assertEquals("a--b", authCreateServerSlugFromName("a  b"))
        assertEquals("", authCreateServerSlugFromName(""))
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
        assertEquals(false, destination.pageData.optBoolean("slockAuthChooseServerSkipInitialLoad", false))
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
    messageType: MessageType = MessageType.Chat,
    conversationContext: MessageConversationContext? = null
): Message =
    Message(
        id = MessageId(id),
        threadId = ThreadId(threadId),
        channelId = channelId?.let(::ChannelId),
        threadChannelId = threadChannelId?.let(::ChannelId),
        conversationContext = conversationContext,
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
