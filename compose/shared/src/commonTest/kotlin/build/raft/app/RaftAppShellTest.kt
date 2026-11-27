package build.raft.app

import build.raft.app.data.APP_UPDATE_HOME_NOTIFICATION_ID

import build.raft.app.data.HomeSnapshot
import build.raft.app.data.SharedAuthResult
import build.raft.app.data.SharedAuthErrorCode
import build.raft.app.resources.*
import build.raft.app.settings.AUTH_CHOOSE_SERVER_FROM_SHELL
import build.raft.app.settings.AUTH_CHOOSE_SERVER_PAGE
import build.raft.app.settings.AppShellChildPageTabKeys
import build.raft.app.settings.AppShellNotificationThreadLaunchRequest
import build.raft.app.settings.AppShellVisibleRefreshGate
import build.raft.app.settings.AppShellVisibleRefreshSignal
import build.raft.app.settings.AuthGateMessage
import build.raft.app.settings.RAFT_APP_SHELL_PAGE
import build.raft.app.settings.RaftSettingsHomePayload
import build.raft.app.settings.SettingsHomePageLinkPayload
import build.raft.app.settings.SharedMembersUiState
import build.raft.app.settings.appShellActiveChildPagerTab
import build.raft.app.settings.appShellAuthLossEffectShouldApply
import build.raft.app.settings.appShellBottomTabPerformanceRoute
import build.raft.app.settings.appShellBottomTabs
import build.raft.app.settings.appShellBottomTabsVisible
import build.raft.app.settings.appShellParentShouldStartHome
import build.raft.app.settings.appShellPhoneBottomTabs
import build.raft.app.settings.appShellPreferredTimezone
import build.raft.app.settings.appShellRetainedChildTabs
import build.raft.app.settings.appShellRunIfAuthSessionTerminalOrAuthorityRevision
import build.raft.app.settings.appShellSystemNotificationNavigationIntent
import build.raft.app.settings.appShellThreadTimeFormat
import build.raft.app.settings.authChooseServerShouldSkipInitialLoad
import build.raft.app.settings.authChooseServerUsesScopedStore
import build.raft.app.settings.authCreateServerSlugFromName
import build.raft.app.settings.buildAppShellThreadPayload
import build.raft.app.settings.iconName
import build.raft.app.settings.notificationLaunchHomeContentReady
import build.raft.app.settings.notificationThreadLaunchRequest
import build.raft.app.settings.retainedAppShellSelectedTab
import build.raft.app.settings.serverGateActionFailureMessage
import build.raft.app.settings.serverGateShouldPinSignOut
import build.raft.app.settings.serverGateUnauthenticatedMessage
import build.raft.app.settings.sharedMembersCachedContentForServer
import build.raft.app.settings.shouldDismissHomeNotificationCenterForAppShellTabSelection
import build.raft.app.settings.shouldHandleAppShellTabSelection
import build.raft.app.settings.withAttentionRows
import build.raft.app.shell.AppShellBottomTabTapAction
import build.raft.app.shell.AppShellLazyListPosition
import build.raft.app.shell.AppShellMoreChildTabKeys
import build.raft.app.shell.AppShellPhoneBottomBarKeys
import build.raft.app.shell.AppShellPhoneBottomTabKeys
import build.raft.app.shell.AppShellSearchTabKey
import build.raft.app.shell.AppShellServerLazyListPositions
import build.raft.app.shell.AppShellServerTabKey
import build.raft.app.shell.RAFT_SHELL_INSTANCE_ID
import build.raft.app.shell.RaftAppShellScope
import build.raft.app.shell.RaftForegroundMessageBannerIconKind
import build.raft.app.shell.RaftMoreAttention
import build.raft.app.shell.RaftServerMenuEntry
import build.raft.app.shell.appShellBottomTabTapAction
import build.raft.app.shell.appShellLazyListPositionForServer
import build.raft.app.shell.appShellMoreChildBackTarget
import build.raft.app.shell.appShellMoreTabShowsDot
import build.raft.app.shell.appShellPhoneBottomBarSelectedKey
import build.raft.app.shell.appShellRailFooterTabs
import build.raft.app.shell.appShellRailTabs
import build.raft.app.shell.appShellSearchTabMovesToRailSearch
import build.raft.app.shell.appShellVisibleForegroundConversationKey
import build.raft.app.shell.inlineContextLabel
import build.raft.app.shell.raftAdaptiveNavRailActive
import build.raft.app.shell.raftAppShellBottomTabsShown
import build.raft.app.shell.raftConversationDirectoryStateFromProjections
import build.raft.app.shell.raftForegroundMessageBannerProjection
import build.raft.app.shell.raftMoreAttention
import build.raft.app.shell.raftNotificationPushBannerOrNull
import build.raft.app.shell.serverMenuEntry
import build.raft.app.shell.settingsWideAttentionRowKeys
import build.raft.app.shell.withPosition
import build.raft.app.thread.key
import build.raft.app.thread.toConversationOpenSourceMessageIdentity
import build.raft.app.toConversationOpenSourceMessageIdentity
import build.raft.app.data.AuthSessionEvent
import build.raft.app.data.AuthSessionTerminalSnapshot
import build.raft.app.data.InboxItem
import build.raft.app.data.InboxSnapshot
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedAuthGateState
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RaftAuthSessionTerminalSource
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.toSharedConversationDirectoryState
import build.raft.app.iconName
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
import build.raft.app.model.NotificationPushReceived
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftAppShellTest {
    @Test
    fun appShellVisibilitySignalsRefreshEveryForegroundGeneration() {
        val gate = AppShellVisibleRefreshGate()

        assertTrue(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.Lifecycle,
                appearanceGeneration = 0,
                target = "home:"
            )
        )
        assertFalse(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.PageDidAppear,
                appearanceGeneration = 1,
                target = "home:"
            )
        )

        (1..3).forEach { generation ->
            gate.onLifecycleHidden(appearanceGeneration = generation)
            assertTrue(
                gate.shouldRefresh(
                    AppShellVisibleRefreshSignal.Lifecycle,
                    appearanceGeneration = generation,
                    target = "home:"
                ),
                "foreground generation $generation must not reuse a stale lifecycle generation"
            )
            assertFalse(
                gate.shouldRefresh(
                    AppShellVisibleRefreshSignal.PageDidAppear,
                    appearanceGeneration = generation + 1,
                    target = "home:"
                )
            )
        }
    }

    @Test
    fun appShellSecondForegroundOfTheSameAppearanceRefreshesAgain() {
        // Hands f6c3f3a6 (iOS 1.17.0): show (epoch 6) and hide 130 ms later, then the app stayed
        // suspended for 1h44m; the next show (epoch 7) logged `same_appearance_or_foreground_epoch`
        // and Activity kept the old rows. No page appearance happened between the two shows.
        var now = 0L
        val gate = AppShellVisibleRefreshGate(nowEpochMillis = { now })
        assertTrue(
            gate.shouldRefresh(AppShellVisibleRefreshSignal.Lifecycle, appearanceGeneration = 3, target = "activity:", foregroundEpoch = 5L)
        )
        gate.onLifecycleHidden(appearanceGeneration = 3, foregroundEpoch = 5L)
        now += 1_000L
        assertTrue(
            gate.shouldRefresh(AppShellVisibleRefreshSignal.Lifecycle, appearanceGeneration = 3, target = "activity:", foregroundEpoch = 6L)
        )
        gate.onLifecycleHidden(appearanceGeneration = 3, foregroundEpoch = 6L)
        now += 130L

        assertTrue(
            gate.shouldRefresh(AppShellVisibleRefreshSignal.Lifecycle, appearanceGeneration = 3, target = "activity:", foregroundEpoch = 7L),
            "the second process foreground over the same appearance refreshes"
        )
        assertEquals("repeated_foreground_same_appearance", gate.lastDecisionCause)
        // The same foreground's duplicate show / pageDidAppear still coalesce.
        assertFalse(
            gate.shouldRefresh(AppShellVisibleRefreshSignal.Lifecycle, appearanceGeneration = 3, target = "activity:", foregroundEpoch = 7L)
        )
        assertFalse(gate.shouldRefresh(AppShellVisibleRefreshSignal.PageDidAppear, appearanceGeneration = 4, target = "activity:"))
    }

    @Test
    fun appShellConnectedForegroundWithin60sSkipsUntilSocketDropsOrMinutePasses() {
        var now = 10_000L
        var liveToken: Long? = 100L
        val gate =
            AppShellVisibleRefreshGate(
                nowEpochMillis = { now },
                liveConnectionToken = { liveToken },
            )
        assertTrue(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.Lifecycle,
                appearanceGeneration = 1,
                target = "home:",
                foregroundEpoch = 1L,
                serverId = "server-1",
            )
        )
        gate.onLifecycleHidden(appearanceGeneration = 1, foregroundEpoch = 1L)
        now += 15_000L
        assertFalse(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.Lifecycle,
                appearanceGeneration = 1,
                target = "home:",
                foregroundEpoch = 2L,
                serverId = "server-1",
            ),
            "unbroken socket within 60 s skips redundant foreground refresh",
        )
        assertEquals("connected_within_60s", gate.lastDecisionCause)
        // Once the socket drops/reconnects with a new token, foreground refreshes immediately inside 60 s.
        gate.onLifecycleHidden(appearanceGeneration = 1, foregroundEpoch = 2L)
        now += 10_000L
        liveToken = 101L
        assertTrue(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.Lifecycle,
                appearanceGeneration = 1,
                target = "home:",
                foregroundEpoch = 3L,
                serverId = "server-1",
            ),
            "socket drop inside 60 s refreshes on foreground",
        )
        // After 60 s on the same live token, foreground refreshes again.
        gate.onLifecycleHidden(appearanceGeneration = 1, foregroundEpoch = 3L)
        now += 60_000L
        assertTrue(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.Lifecycle,
                appearanceGeneration = 1,
                target = "home:",
                foregroundEpoch = 4L,
                serverId = "server-1",
            ),
            "foreground refreshes again after 60 s even if socket stayed connected",
        )
    }

    @Test
    fun notificationLaunchWaitsForTargetServerHomeContentAfterServerSwitch() {
        fun content(snapshotServerId: String, sessionServerId: String): SharedHomeContentState.Content =
            SharedHomeContentState.Content(
                snapshot =
                    HomeSnapshot(
                        server = ServerSummary(ServerId(snapshotServerId), "Raft"),
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
                        user = StoredAuthUser(id = "user-1", email = "artin@raft.build", displayName = "Artin"),
                        accessToken = "token",
                        refreshToken = "refresh",
                        selectedServerId = sessionServerId,
                        servers =
                            listOf(
                                StoredAuthServer(id = "server-a", name = "A", slug = "a"),
                                StoredAuthServer(id = "server-b", name = "B", slug = "b")
                            )
                    )
            )

        assertFalse(notificationLaunchHomeContentReady(null, "server-b", selectedServerId = "server-b"))
        assertFalse(
            notificationLaunchHomeContentReady(
                content("server-a", "server-b"),
                "server-b",
                selectedServerId = "server-b"
            )
        )
        assertFalse(
            notificationLaunchHomeContentReady(
                content("server-b", "server-a"),
                "server-b",
                selectedServerId = "server-a"
            )
        )
        assertTrue(
            notificationLaunchHomeContentReady(
                content("server-b", "server-b"),
                "server-b",
                selectedServerId = "server-b"
            )
        )
    }

    @Test
    fun dynamicSystemNotificationEnvelopeRequiresServerQualifiedIdentity() {
        val intent =
            assertNotNull(
                JSONObject()
                    .put("slockNotificationServerId", "server-b")
                    .put("slockNotificationThreadId", "thread-b")
                    .put("slockNotificationChannelId", "thread-b")
                    .put("slockNotificationMessageId", "reply-b")
                    .put("slockNotificationParentChannelId", "channel-b")
                    .put("slockNotificationParentMessageId", "parent-b")
                    .put("slockNotificationKind", "thread")
                    .appShellSystemNotificationNavigationIntent()
            )

        assertEquals("server-b", intent.serverId)
        assertEquals("thread-b", intent.sourceIdentity.threadChannelId)
        assertEquals("channel-b", intent.sourceIdentity.parentChannelId)
        assertEquals("parent-b", intent.sourceIdentity.parentMessageId)
        assertEquals("reply-b", intent.sourceIdentity.messageId)
        assertNull(
            JSONObject()
                .put("slockNotificationServerId", "server-b")
                .put("slockNotificationThreadId", "thread-b")
                .put("slockNotificationMessageId", "reply-b")
                .appShellSystemNotificationNavigationIntent()
        )
        assertNull(
            JSONObject()
                .put("slockNotificationServerId", "server-b")
                .put("slockNotificationThreadId", "thread-b")
                .put("slockNotificationMessageId", "reply-b")
                .put("slockNotificationKind", "unknown")
                .appShellSystemNotificationNavigationIntent()
        )
    }

    @Test
    fun overlayTabBarContentReservesFullClearanceForLastRow() {
        // Floating docks (Android floating, iOS liquid glass) overlay
        // content and the host pads nothing — scrollable content must reserve
        // the renderer's full clearance or its last row hides under the dock.
        val scope =
            RaftAppShellScope(
                topSafeArea = 24.dp,
                bottomSafeArea = 18.dp,
                bottomTabBarContentInset = 110.dp,
                bottomTabBarOverlaysContent = true
            )

        assertEquals(0.dp, scope.bottomTabBarHostInset(includeBottomTabBar = true))
        assertEquals(110.dp, scope.globalDialogBottomInset(includeBottomTabBar = true))
        assertEquals(0.dp, scope.globalDialogBottomInset(includeBottomTabBar = false))
        assertEquals(110.dp, scope.contentBottomInset(includeBottomTabBar = true))
        assertEquals(18.dp + RaftAppSpacing.Lg, scope.contentBottomInset(includeBottomTabBar = false))
    }

    @Test
    fun navRailActivatesOnlyForFlatDualPaneWithDesktopMode() {
        val dualPane =
            RaftAdaptivePaneState(
                widthPx = 1680,
                heightPx = 1118,
                density = 2f,
                posture = RaftAdaptivePanePosture.Flat,
                layout = RaftAdaptivePaneLayout.DualPane
            )
        val compact = dualPane.copy(layout = RaftAdaptivePaneLayout.Compact)

        assertTrue(
            raftAdaptiveNavRailActive(
                desktopModeEnabled = true,
                paneState = dualPane
            )
        )
        // Desktop mode off keeps the bottom bar even in a wide flat layout.
        assertFalse(
            raftAdaptiveNavRailActive(
                desktopModeEnabled = false,
                paneState = dualPane
            )
        )
        // Compact windows always keep the bottom bar.
        assertFalse(
            raftAdaptiveNavRailActive(
                desktopModeEnabled = true,
                paneState = compact
            )
        )

    }

    @Test
    fun retainedAppShellSelectedTabFallsBackToHome() {
        assertEquals("home", retainedAppShellSelectedTab("unknown"))
    }

    @Test
    fun reTappingTheSelectedTabScrollsHomeActivityTasksAndMoreToTop() {
        for (tab in listOf("home", "activity", "tasks", "more", "members")) {
            assertEquals(
                AppShellBottomTabTapAction.ScrollCurrentToTop,
                appShellBottomTabTapAction(selectedTab = tab, tappedTab = tab),
                "re-tap $tab",
            )
        }
        // A single re-tap is enough; no double-tap window.
        assertEquals(
            AppShellBottomTabTapAction.ScrollCurrentToTop,
            appShellBottomTabTapAction(selectedTab = "activity", tappedTab = "activity"),
        )
    }

    @Test
    fun reTappingTheSelectedSearchTabFocusesItsFieldAndSwitchingToItDoesNot() {
        assertEquals(
            AppShellBottomTabTapAction.FocusSearchField,
            appShellBottomTabTapAction(selectedTab = AppShellSearchTabKey, tappedTab = AppShellSearchTabKey),
        )
        assertEquals(
            AppShellBottomTabTapAction.Select,
            appShellBottomTabTapAction(selectedTab = "home", tappedTab = AppShellSearchTabKey),
        )
    }

    @Test
    fun tappingAnotherTabSelectsIt() {
        assertEquals(AppShellBottomTabTapAction.Select, appShellBottomTabTapAction("home", "tasks"))
        // Members is pushed from More: tapping More goes back to the More root.
        assertEquals(AppShellBottomTabTapAction.Select, appShellBottomTabTapAction("members", "more"))
    }

    @Test
    fun appShellTabSelectionDismissesHomeNotificationCenterOnlyWhenLeavingHome() {
        assertTrue(shouldDismissHomeNotificationCenterForAppShellTabSelection("home", "tasks"))
        assertTrue(shouldDismissHomeNotificationCenterForAppShellTabSelection("home", "settings"))
        assertFalse(shouldDismissHomeNotificationCenterForAppShellTabSelection("home", "home"))
        assertFalse(shouldDismissHomeNotificationCenterForAppShellTabSelection("tasks", "members"))
        assertFalse(shouldDismissHomeNotificationCenterForAppShellTabSelection("settings", "home"))
    }

    @Test
    fun appShellThreadTimeFormatReadsOnlyCanonicalSessionMetadata() {
        val session =
            StoredAuthSession(
                user =
                    StoredAuthUser(
                        id = "user-1",
                        email = "user@slock.ai",
                        preferredTimeFormat = "12h",
                        timeFormat = "24h",
                    ),
                accessToken = "access",
                refreshToken = "refresh",
            )

        assertEquals("12h", session.appShellThreadTimeFormat())
        assertEquals(
            "24h",
            session.copy(
                user = session.user.copy(preferredTimeFormat = null, timeFormat = "24h")
            ).appShellThreadTimeFormat(),
        )
        assertNull((null as StoredAuthSession?).appShellThreadTimeFormat())
    }

    @Test
    fun appShellPreferredTimezoneReadsCanonicalAndRejectsInvalid() {
        val session =
            StoredAuthSession(
                user =
                    StoredAuthUser(
                        id = "user-1",
                        email = "user@slock.ai",
                        preferredTimezone = "America/New_York",
                        timezone = "Asia/Shanghai",
                    ),
                accessToken = "access",
                refreshToken = "refresh",
            )

        assertEquals("America/New_York", session.appShellPreferredTimezone())
        assertEquals(
            "Asia/Shanghai",
            session.copy(
                user = session.user.copy(preferredTimezone = null, timezone = "Asia/Shanghai")
            ).appShellPreferredTimezone(),
        )
        assertNull(
            session.copy(
                user = session.user.copy(preferredTimezone = "Mars/Olympus", timezone = null)
            ).appShellPreferredTimezone(),
        )
        assertNull((null as StoredAuthSession?).appShellPreferredTimezone())
    }

    @Test
    fun appShellLazyListPositionsKeepServersIndependent() {
        val serverAPosition =
            AppShellLazyListPosition(firstVisibleItemIndex = 5, firstVisibleItemScrollOffset = 24)
        val serverBPosition =
            AppShellLazyListPosition(firstVisibleItemIndex = 11, firstVisibleItemScrollOffset = 7)
        val saved =
            AppShellServerLazyListPositions()
                .withPosition(serverId = "server-a", listPosition = serverAPosition)
                .withPosition(serverId = "server-b", listPosition = serverBPosition)

        assertEquals(
            serverAPosition,
            appShellLazyListPositionForServer(currentServerId = "server-a", savedPositions = saved)
        )
        assertEquals(
            serverBPosition,
            appShellLazyListPositionForServer(currentServerId = "server-b", savedPositions = saved)
        )
        assertEquals(
            AppShellLazyListPosition(),
            appShellLazyListPositionForServer(currentServerId = "", savedPositions = saved)
        )
    }

    @Test
    fun appShellPhoneBottomTabsUseLocalizedLabelsAndKeepMoreSelectedForItsPages() {
        val build = { selectedTab: String ->
            appShellPhoneBottomTabs(
                selectedTab = selectedTab,
                homeLabel = "localized-home",
                activityLabel = "localized-activity",
                searchLabel = "localized-search",
                tasksLabel = "localized-tasks",
                moreLabel = "localized-more",
                activityUnreadCount = 7
            )
        }
        // stdrc 2026-10-21: Home · Activity · Tasks · Search · More in one edge bar.
        val tabs = build("home")
        assertEquals(listOf("home", "activity", "tasks", AppShellSearchTabKey, "more"), tabs.map { it.key })
        assertEquals(
            listOf("localized-home", "localized-activity", "localized-tasks", "localized-search", "localized-more"),
            tabs.map { it.label }
        )
        assertEquals(RaftLucideIconName.Search, tabs[3].iconName)
        assertEquals(RaftLucideIconName.Ellipsis, tabs[4].iconName)
        assertEquals(listOf(0, 0, 0, 0, 0), tabs.map { it.badgeCount })
        // Settings is More's root and Members is pushed from it: More stays the selected tab.
        listOf("members", "settings", "more").forEach { selected ->
            assertEquals(listOf(false, false, false, false, true), build(selected).map { it.selected }, "selected=$selected")
        }
        assertEquals(listOf(false, false, true, false, false), build("tasks").map { it.selected })
        // Search is a tab: selected like the others (stdrc option B).
        assertEquals(listOf(false, false, false, true, false), build(AppShellSearchTabKey).map { it.selected })
        // A stale/unknown selection selects Home.
        assertEquals(listOf(true, false, false, false, false), build("bogus").map { it.selected })
    }

    // stdrc 2026-10-21 (option B): Search is a phone tab.
    @Test
    fun searchIsAPhoneTabRootWithTheBarAndNoBack() {
        assertEquals(listOf("home", "activity", "tasks", AppShellSearchTabKey, "more"), AppShellPhoneBottomTabKeys)
        assertEquals(AppShellSearchTabKey, retainedAppShellSelectedTab(AppShellSearchTabKey))
        assertEquals(AppShellSearchTabKey, appShellPhoneBottomBarSelectedKey(AppShellSearchTabKey))
        val tabs = appShellBottomTabs(
            selectedKey = AppShellSearchTabKey,
            orderedTabKeys = AppShellPhoneBottomBarKeys,
            activityUnreadCount = 3,
            moreAttention = setOf(RaftMoreAttention.AppUpdate),
            labelFor = { it }
        )
        assertEquals(listOf(AppShellSearchTabKey), tabs.filter { it.selected }.map { it.key })
        // Search never wears a dot.
        assertFalse(tabs.single { it.key == AppShellSearchTabKey }.attentionDot)
        // Root: no Back, so the bar shows; tapping another tab leaves it.
        assertNull(appShellMoreChildBackTarget(AppShellSearchTabKey, navRailActive = false))
        assertTrue(appShellBottomTabsVisible(selectedTab = AppShellSearchTabKey, selectedHomeDestination = null))
        assertTrue(shouldHandleAppShellTabSelection(AppShellSearchTabKey, "tasks"))
        assertEquals("kuikly_search", appShellBottomTabPerformanceRoute(AppShellSearchTabKey))
        // It is kept alive as a child tab like the others.
        assertEquals(AppShellSearchTabKey, appShellActiveChildPagerTab(AppShellSearchTabKey))
        assertEquals(
            listOf("home", AppShellSearchTabKey),
            appShellRetainedChildTabs(AppShellSearchTabKey, mountedTabs = setOf("home", AppShellSearchTabKey))
        )
    }

    @Test
    fun searchTabMovesToTheRailSearchEntryOnTheWideLayout() {
        assertTrue(appShellSearchTabMovesToRailSearch(AppShellSearchTabKey, navRailActive = true))
        assertFalse(appShellSearchTabMovesToRailSearch(AppShellSearchTabKey, navRailActive = false))
        assertFalse(appShellSearchTabMovesToRailSearch("home", navRailActive = true))
    }

    @Test
    fun everyInShellPageWithoutTheBarHasBack() {
        // Navigation rule: a pushed page always has Back. An in-shell page that hides the bar must
        // carry a Back target (Members from More was a dead end with neither).
        (AppShellChildPageTabKeys + "unknown").forEach { tab ->
            val barVisible = appShellBottomTabsVisible(tab, selectedHomeDestination = null, navRailActive = false)
            if (!barVisible) {
                assertEquals("home", appShellMoreChildBackTarget(tab, navRailActive = false), "tab=$tab")
            }
        }
        assertFalse(appShellBottomTabsVisible("members", selectedHomeDestination = null, navRailActive = false))
    }

    @Test
    fun moreTabAndSettingsRowsDotFromHomeNotices() {
        assertEquals(emptySet(), raftMoreAttention(emptyList()))
        assertEquals(emptySet(), raftMoreAttention(listOf("announcement:1")))
        assertEquals(setOf(RaftMoreAttention.AppUpdate), raftMoreAttention(listOf(APP_UPDATE_HOME_NOTIFICATION_ID)))
        assertEquals(
            setOf(RaftMoreAttention.Computers),
            raftMoreAttention(listOf("machine-offline:m1", "machine-outdated:1.2.0:m2"))
        )
        val both = raftMoreAttention(listOf("machine-offline:m1", APP_UPDATE_HOME_NOTIFICATION_ID))
        assertEquals(setOf(RaftMoreAttention.AppUpdate, RaftMoreAttention.Computers), both)
        // Each signal dots its Settings row: the update -> About, a machine -> Computers.
        assertEquals("about", RaftMoreAttention.AppUpdate.settingsRowKey)
        assertEquals("computers", RaftMoreAttention.Computers.settingsRowKey)

        val build = { selectedTab: String, attention: Set<RaftMoreAttention> ->
            appShellPhoneBottomTabs(
                selectedTab = selectedTab,
                homeLabel = "h",
                activityLabel = "a",
                searchLabel = "s",
                tasksLabel = "t",
                moreLabel = "m",
                moreAttention = attention
            ).associate { it.key to it.attentionDot }
        }
        assertEquals(true, build("home", both)["more"])
        assertEquals(false, build("home", emptySet())["more"])
        // Never on the selected tab: More, Settings and the pages pushed from it keep More selected.
        listOf("more", "settings", "members").forEach { tab ->
            assertEquals(false, build(tab, both)["more"], "tab=$tab")
        }
        // Only the More tab picks the signal up.
        assertEquals(false, build("home", both)["activity"])
        assertEquals(false, build("home", both)[AppShellSearchTabKey])
    }

    @Test
    fun bottomBarHidesWhileKeyboardIsOpen() {
        assertTrue(raftAppShellBottomTabsShown(showBottomTabs = true, imeVisible = false))
        assertFalse(raftAppShellBottomTabsShown(showBottomTabs = true, imeVisible = true))
        assertFalse(raftAppShellBottomTabsShown(showBottomTabs = false, imeVisible = false))
    }

    @Test
    fun anUnreadFeedbackReplyDotsSettingsAndItsFeedbackRow() {
        // web useSettingsPageAttention / LeftRail Settings `showDot={feedbackUnread > 0}` /
        // SettingsSidebarList FeedbackUnreadDot.
        assertEquals(emptySet(), raftMoreAttention(emptyList(), feedbackUnreadTotal = 0))
        val feedback = raftMoreAttention(emptyList(), feedbackUnreadTotal = 2)
        assertEquals(setOf(RaftMoreAttention.FeedbackReplies), feedback)
        assertEquals("hands-feedback", RaftMoreAttention.FeedbackReplies.settingsRowKey)
        assertEquals(RaftServerMenuEntry.Settings, RaftMoreAttention.FeedbackReplies.serverMenuEntry())
        // The phone: the server tab, and Settings in its panel.
        assertTrue(appShellMoreTabShowsDot(feedback, selected = false))
        // The wide rail: its Settings entry, never while Settings is open.
        fun railDots(selectedTab: String) =
            (appShellRailTabs(selectedTab, labelFor = { it }, moreAttention = feedback) +
                appShellRailFooterTabs(selectedTab, labelFor = { it }, helpOpen = false, moreAttention = feedback))
                .filter { it.attentionDot }
                .map { it.key }
        assertEquals(listOf("settings"), railDots("home"))
        assertEquals(emptyList<String>(), railDots("settings"))
        // The wide Settings list dots Feedback only (the web's wide list has no update dot).
        assertEquals(setOf("hands-feedback"), settingsWideAttentionRowKeys(feedback + RaftMoreAttention.AppUpdate))
        assertEquals(emptySet(), settingsWideAttentionRowKeys(setOf(RaftMoreAttention.AppUpdate, RaftMoreAttention.Computers)))
        val rows =
            RaftSettingsHomePayload(items = listOf(SettingsHomePageLinkPayload("about", "About"), SettingsHomePageLinkPayload("hands-feedback", "Feedback")))
                .withAttentionRows(settingsWideAttentionRowKeys(feedback))
                .items
        assertEquals(listOf(false, true), rows.map { it.attentionDot })
    }

    @Test
    fun appShellParentShouldStartHomeTreatsDmsAsHomeLikeInSlackMode() {
        // task #94: the DMs tab renders the same home store, so the parent home start must
        // not be gated on the literal "home" tab while Slack nav is active.
        val state = SharedHomeState()
        assertTrue(
            appShellParentShouldStartHome(
                selectedTab = "dms",
                selectedHomeDestination = null,
                readyServerId = "server-a",
                state = state,
                homeLikeTabs = setOf("dms")
            )
        )
        // Without the home-like set (legacy mode), the same state does not start home.
        assertFalse(
            appShellParentShouldStartHome(
                selectedTab = "dms",
                selectedHomeDestination = null,
                readyServerId = "server-a",
                state = state
            )
        )
        // Non-home-like Slack tabs still do not start home.
        assertFalse(
            appShellParentShouldStartHome(
                selectedTab = "activity",
                selectedHomeDestination = null,
                readyServerId = "server-a",
                state = state,
                homeLikeTabs = setOf("dms")
            )
        )
    }

    // stdrc 2026-10-21: a page with Back at the top shows no bottom tab bar; a tab root shows it.
    @Test
    fun appShellBottomTabsShowOnTabRootsAndHideOnInShellPagesWithBack() {
        // Phone bar roots: no Back, bar shows (the home destination of another tab is ignored).
        // The server tab (`more`) holds no page: its Settings page is pushed (raft-design #712).
        for (root in AppShellPhoneBottomTabKeys - AppShellServerTabKey) {
            assertEquals(true, appShellBottomTabsVisible(selectedTab = root, selectedHomeDestination = null), root)
            assertEquals(true, appShellBottomTabsVisible(selectedTab = root, selectedHomeDestination = "activity"), root)
        }
        // Settings resolves to the More root on the phone; DMs is a legacy root.
        assertEquals(true, appShellBottomTabsVisible(selectedTab = "settings", selectedHomeDestination = null))
        assertEquals(true, appShellBottomTabsVisible(selectedTab = "dms", selectedHomeDestination = null))
        // Members and Settings are pushed server pages with Back: no bar.
        for (child in AppShellMoreChildTabKeys) {
            assertEquals("home", appShellMoreChildBackTarget(child, navRailActive = false), child)
            assertEquals(false, appShellBottomTabsVisible(selectedTab = child, selectedHomeDestination = null), child)
        }
        // On the wide rail Members is a rail root without Back (the rail, not the bar, navigates).
        assertEquals(
            true,
            appShellBottomTabsVisible(selectedTab = "members", selectedHomeDestination = null, navRailActive = true)
        )
        // Unknown tab keys fall back to the Home root.
        assertEquals(true, appShellBottomTabsVisible(selectedTab = "bogus", selectedHomeDestination = null))
    }

    @Test
    fun foregroundBannerProjectionSuppressesOwnMessage() {
        val banner =
            raftForegroundMessageBannerProjection(
                serverId = "server-1",
                currentServerId = "server-1",
                message = appShellForegroundTestMessage(authorId = "user-1"),
                currentMemberId = "user-1",
                visibleConversationKey = null
            )

        assertNull(banner)
    }

    @Test
    fun foregroundBannerProjectionShowsOnSettingsEvenWithSelectedHomeConversation() {
        // t434 migration (criterion (a)): the visibility logic under test needs a PROVEN
        // banner — the raw guess that used to conjure one is gone, so the fixture supplies
        // directory evidence; the visibility assertions are unchanged.
        val banner =
            raftForegroundMessageBannerProjection(
                serverId = "server-1",
                currentServerId = "server-1",
                message = appShellForegroundTestMessage(channelId = "channel-1"),
                currentMemberId = "user-1",
                visibleConversationKey =
                    appShellVisibleForegroundConversationKey(
                        selectedTab = "settings",
                        selectedHomeDestination = null,
                        selectedConversationId = "channel-1"
                    ),
                conversationDirectory =
                    InboxSnapshot(
                        server = ServerSummary(ServerId("server-1"), "Raft"),
                        items =
                            listOf(
                                InboxItem(
                                    key = "channel:channel-1",
                                    kind = InboxItemKind.Channel,
                                    channelId = ChannelId("channel-1"),
                                    channelName = "home-fixes"
                                )
                            )
                    ).toSharedConversationDirectoryState()
            )

        val resolved = assertNotNull(banner)
        assertEquals("foreground:server-1:message-1", resolved.id)
        assertEquals("message-1", resolved.focusedMessageId)
        assertEquals("channel-1", resolved.conversation.threadId.value)
        assertEquals("Cindy", resolved.title)
        assertEquals("#home-fixes", resolved.context)
        assertNull(resolved.threadContext)
        assertEquals(RaftForegroundMessageBannerIconKind.Channel, resolved.iconKind)
        assertEquals(RaftLucideIconName.Hash, resolved.iconName)
    }

    @Test
    fun foregroundBannerProjectionUsesDirectoryContextForChannelAndThread() {
        val directory =
            raftConversationDirectoryStateFromProjections(
                listOf(
                    SharedConversationDirectoryProjection(
                        serverId = "server-1",
                        canonicalChannelId = "parent-channel",
                        aliases = setOf("parent-channel"),
                        conversationKind = ConversationKind.Channel,
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = "channel",
                        title = "team-ops"
                    ),
                    SharedConversationDirectoryProjection(
                        serverId = "server-1",
                        canonicalChannelId = "reply-stream",
                        aliases = setOf("reply-stream", "parent-channel", "parent-message"),
                        conversationKind = ConversationKind.Thread,
                        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                        channelKind = "thread",
                        title = "deploy checklist",
                        subtitle = "Parent message preview",
                        parentChannelId = "parent-channel",
                        parentMessageId = "parent-message"
                    )
                )
            )
        val banner =
            raftForegroundMessageBannerProjection(
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
        assertEquals(RaftForegroundMessageBannerIconKind.Channel, resolved.iconKind)
        assertEquals(RaftLucideIconName.Thread, resolved.iconName)
        assertEquals(ConversationKind.Thread, resolved.openRequest.conversationIdentity.kind)
        assertEquals("parent-channel", resolved.openRequest.conversationIdentity.thread?.parentChannelId?.value)
        assertEquals("parent-message", resolved.openRequest.conversationIdentity.thread?.parentMessageId?.value)
        assertEquals("reply-stream", resolved.openRequest.conversationIdentity.thread?.streamChannelId?.value)
        assertEquals(RaftThreadSurfaceKind.Channel, resolved.openRequest.conversationIdentity.thread?.parentSurfaceKind)
        assertEquals(RaftThreadSurfaceKind.Channel, resolved.openRequest.conversationIdentity.thread?.parentChannelKind)
        assertEquals(null, resolved.openRequest.conversationIdentity.channelType)
        assertEquals("reply-1", resolved.openRequest.anchorMessageId)
        assertEquals("parent-channel", resolved.openRequest.originFallback?.parentChannelId)
        assertEquals("parent-message", resolved.openRequest.originFallback?.parentMessageId)
        assertEquals("Parent message preview", resolved.openRequest.originFallback?.preview)

        val launchPayload =
            requireNotNull(buildAppShellThreadPayload(
                conversation = resolved.openRequest.conversationIdentity,
                anchorMessageId = resolved.openRequest.anchorMessageId,
                originFallback = resolved.openRequest.originFallback
            ))
        assertEquals(RaftThreadSurfaceKind.Channel, launchPayload.parentSurfaceKind)
        assertEquals(RaftThreadSurfaceKind.Channel, launchPayload.parentChannelKind)
        assertEquals("Parent message preview", launchPayload.originPreview)
    }

    // task #425 part 2: server-provided conversationContext short-circuits the
    // projection wait — routing correct immediately, labels best-effort.

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

    // task #459: banner from the server-qualified notification stream.
    @Test
    fun notificationPushBannerBuildsThreadIdentityWithDirectoryParentEnrichment() {
        val directory =
            raftConversationDirectoryStateFromProjections(
                listOf(
                    SharedConversationDirectoryProjection(
                        serverId = "server-1",
                        canonicalChannelId = "parent-channel",
                        aliases = setOf("parent-channel"),
                        conversationKind = ConversationKind.Channel,
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = "channel",
                        title = "home-fixes"
                    )
                )
            )
        val banner =
            raftNotificationPushBannerOrNull(
                event =
                    NotificationPushReceived(
                        serverId = ServerId("server-1"),
                        kind = ConversationKind.Thread,
                        threadId = ChannelId("reply-stream"),
                        parentChannelId = ChannelId("parent-channel"),
                        parentMessageId = MessageId("parent-message"),
                        messageId = MessageId("reply-1"),
                        // The socket still joins the context into the title (slock #12277); the
                        // banner shows it on its own (contract §1.5).
                        title = "Thread in #home-fixes · Other Server",
                        body = "Cindy: hello",
                        tag = "message:reply-1",
                        subtitle = "Other Server",
                        serverName = "Raft",
                        channelName = "home-fixes",
                        parentChannelKind = ConversationKind.Channel,
                        senderId = MemberId("agent-cindy"),
                        senderKind = MemberKind.Agent,
                        senderName = "Cindy",
                        messagePreview = "hello",
                        mentioned = false
                    ),
                currentServerId = "server-1",
                visibleConversationKey = null,
                conversationDirectory = directory
            )

        val resolved = assertNotNull(banner)
        assertEquals(ConversationKind.Thread, resolved.conversation.kind)
        assertEquals("parent-channel", resolved.conversation.thread?.parentChannelId?.value)
        assertEquals("parent-message", resolved.conversation.thread?.parentMessageId?.value)
        assertEquals("reply-stream", resolved.conversation.thread?.streamChannelId?.value)
        // Parent kind enriched from the directory row (data-only, §5).
        assertEquals(RaftThreadSurfaceKind.Channel, resolved.conversation.thread?.parentSurfaceKind)
        assertEquals("reply-1", resolved.openRequest.anchorMessageId)
        assertEquals("agent-cindy", resolved.senderMemberId)
        assertEquals("Thread in #home-fixes", resolved.title)
        assertEquals("Other Server", resolved.context)
        assertEquals("Other Server", resolved.inlineContextLabel())
        assertEquals("Cindy: hello", resolved.body)
    }

    @Test
    fun notificationPushBannerFallsBackToLegacyTitleAndBodyWhenStructuredDisplayFieldsAreMissing() {
        val banner =
            raftNotificationPushBannerOrNull(
                event =
                    NotificationPushReceived(
                        serverId = ServerId("server-1"),
                        kind = ConversationKind.Channel,
                        channelId = ChannelId("channel-1"),
                        messageId = MessageId("message-1"),
                        title = "#native · Raft Android",
                        body = "Artin: ping",
                        tag = "message:message-1"
                    ),
                currentServerId = "server-1",
                visibleConversationKey = null
            )

        val resolved = assertNotNull(banner)
        assertEquals("#native · Raft Android", resolved.title)
        assertEquals("Artin: ping", resolved.body)
        assertNull(resolved.senderMemberId)
        assertEquals("", resolved.context)
        assertEquals("", resolved.inlineContextLabel())
    }

    @Test
    fun resolveOpenRequestRejectsLocalThreadWithDifferentLocalParent() {
        val directory =
            raftConversationDirectoryStateFromProjections(
                listOf(
                    SharedConversationDirectoryProjection(
                        serverId = "server-local",
                        canonicalChannelId = "parent-local-a",
                        aliases = setOf("parent-local-a"),
                        conversationKind = ConversationKind.Joint,
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = "joint",
                        title = "joint-a"
                    ),
                    SharedConversationDirectoryProjection(
                        serverId = "server-local",
                        canonicalChannelId = "thread-local-b",
                        aliases = setOf("thread-local-b"),
                        conversationKind = ConversationKind.Thread,
                        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                        channelKind = "thread",
                        title = "joint-b",
                        parentChannelId = "parent-local-b",
                        parentMessageId = "parent-message"
                    )
                )
            )

        val resolution =
            resolveOpenRequest(
                serverId = "server-local",
                sourceMessageIdentity =
                    AppShellNotificationThreadLaunchRequest(
                        threadId = "thread-local-b",
                        messageId = "reply-local",
                        parentChannelId = "parent-local-a",
                        parentMessageId = "parent-message",
                        conversationKind = ConversationKind.Thread
                    ).toConversationOpenSourceMessageIdentity(),
                conversationDirectory = directory
            )

        val pending = assertNotNull(resolution as? ConversationOpenResolution.Pending)
        assertEquals("thread_projection_mismatch", pending.reason)
    }

    @Test
    fun notificationPushBannerShowsOtherServersAndSuppressesTheVisibleConversation() {
        val suppressed = mutableListOf<String>()
        val crossServer =
            raftNotificationPushBannerOrNull(
                event =
                    NotificationPushReceived(
                        serverId = ServerId("server-2"),
                        kind = ConversationKind.Channel,
                        channelId = ChannelId("channel-1"),
                        messageId = MessageId("m-1"),
                        title = "#general · Other",
                        body = "x",
                        tag = "message:m-1"
                    ),
                currentServerId = "server-1",
                visibleConversationKey = null,
                onSuppressed = suppressed::add
            )

        // Contract §4.5: another workspace's notification is shown like any other; its tap switches
        // workspace through window navigation.
        assertEquals("server-2", crossServer?.serverId)
        assertEquals("channel-1", crossServer?.crossServerSource?.channelId)
        assertEquals(emptyList(), suppressed)

        suppressed.clear()
        val visible =
            raftNotificationPushBannerOrNull(
                event =
                    NotificationPushReceived(
                        serverId = ServerId("server-1"),
                        kind = ConversationKind.Channel,
                        channelId = ChannelId("channel-1"),
                        messageId = MessageId("m-2"),
                        title = "#general · Raft",
                        body = "y",
                        tag = "message:m-2"
                    ),
                currentServerId = "server-1",
                visibleConversationKey = "channel-1",
                onSuppressed = suppressed::add
            )

        assertNull(visible)
        assertEquals(listOf("visible_conversation"), suppressed)
    }

    // task #441 (Hands feedback 20d596f9): the thread's own row is not in the directory
    // and the parent CHANNEL row is. With the parent/anchor lookup tail deleted, the parent
    // row can no longer be hit at all — the Activity source rebuilds a correct Thread
    // identity from its trusted typed parent kind.
    @Test
    fun resolveOpenRequestNeverLooksUpParentChannelForThreadSource() {
        val directory =
            raftConversationDirectoryStateFromProjections(
                listOf(
                    SharedConversationDirectoryProjection(
                        serverId = "server-1",
                        canonicalChannelId = "parent-channel",
                        aliases = setOf("parent-channel"),
                        conversationKind = ConversationKind.Channel,
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = "channel",
                        title = "home-fixes"
                    )
                )
            )
        val logs = mutableListOf<String>()
        val resolution =
            resolveOpenRequest(
                serverId = "server-1",
                sourceMessageIdentity =
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
                        unreadCount = 1,
                        firstUnreadMessageId = MessageId("reply-1"),
                        latestActivityMessageId = MessageId("reply-1")
                    ).toConversationOpenSourceMessageIdentity(),
                conversationDirectory = directory,
                diagnosticsLogger = logs::add
            )

        val request = assertNotNull((resolution as? ConversationOpenResolution.Resolved)?.request)
        assertEquals(ConversationKind.Thread, request.conversationIdentity.kind)
        assertEquals("parent-channel", request.conversationIdentity.thread?.parentChannelId?.value)
        assertEquals("parent-message", request.conversationIdentity.thread?.parentMessageId?.value)
        assertEquals("reply-stream", request.conversationIdentity.thread?.streamChannelId?.value)
        assertEquals(RaftThreadSurfaceKind.Channel, request.conversationIdentity.thread?.parentSurfaceKind)
        assertEquals("reply-1", request.anchorMessageId)
        val log = assertNotNull(logs.firstOrNull())
        // The parent row is in the directory but must never even be HIT.
        assertTrue(log.contains("hitVia=_"), log)
        assertTrue(log.contains("result=resolved:Thread"), log)
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
                unreadCount = 1,
                firstUnreadMessageId = MessageId("reply-1"),
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
            requireNotNull(buildAppShellThreadPayload(
                conversation = activityRequest.conversationIdentity,
                anchorMessageId = activityRequest.anchorMessageId,
                originFallback = activityRequest.originFallback
            ))
        val notificationPayload =
            requireNotNull(buildAppShellThreadPayload(
                conversation = notificationRequest.conversationIdentity,
                anchorMessageId = notificationRequest.anchorMessageId,
                originFallback = notificationRequest.originFallback
            ))
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
            raftConversationDirectoryStateFromProjections(
                listOf(
                    SharedConversationDirectoryProjection(
                        serverId = "server-1",
                        canonicalChannelId = "a8ebbc3c-a3d6-4b6e-8714-123456789abc",
                        aliases = setOf("a8ebbc3c-a3d6-4b6e-8714-123456789abc"),
                        conversationKind = ConversationKind.Channel,
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = "channel",
                        title = "a8ebbc3c-a3d6-4b6e-8714-123456789abc"
                    )
                )
            )
        val banner =
            raftForegroundMessageBannerProjection(
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
    fun foregroundBannerProjectionSuppressesVisibleHomeConversation() {
        val banner =
            raftForegroundMessageBannerProjection(
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
    fun postAuthRouteReturnsToServerChooserAfterFreshAuth() {
        val destination = postAuthRouteDestination()

        assertEquals(AUTH_CHOOSE_SERVER_PAGE, destination.pageName)
        assertEquals(AUTH_CHOOSE_SERVER_PAGE, destination.pageData.optString("pageName"))
        assertEquals(true, destination.pageData.optBoolean("slockReplaceCurrentPage", false))
        assertEquals(false, destination.pageData.optBoolean(AUTH_CHOOSE_SERVER_FROM_SHELL, false))
        assertEquals(RaftShellStoreScope.Legacy.shellInstanceId, destination.pageData.optString(RAFT_SHELL_INSTANCE_ID))
        assertTrue(authChooseServerUsesScopedStore(destination.pageData))
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

        assertEquals(RAFT_APP_SHELL_PAGE, destination.pageName)
        assertEquals(RAFT_APP_SHELL_PAGE, destination.pageData.optString("pageName"))
        assertEquals(true, destination.pageData.optBoolean("slockReplaceCurrentPage", false))
        assertEquals(true, destination.pageData.optBoolean("slockSuppressRestoreLanding", false))
        assertEquals(false, destination.pageData.optBoolean("slockAuthChooseServerSkipInitialLoad", false))
    }

    @Test
    fun postAuthRouteUsesAppShellGateForUnverifiedPendingProfile() {
        val destination =
            postAuthRouteDestination(
                StoredAuthSession(
                    user =
                        StoredAuthUser(
                            id = "user-1",
                            email = "artin@raft.build",
                            emailVerified = false,
                            profileSetupCompletedAt = null
                        ),
                    accessToken = "access",
                    refreshToken = "refresh"
                )
            )

        assertEquals(RAFT_APP_SHELL_PAGE, destination.pageName)
        assertEquals(true, destination.pageData.optBoolean("slockSuppressRestoreLanding", false))
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
    fun serverGatePinsSignOutForSessionRefreshRecovery() {
        assertTrue(serverGateShouldPinSignOut(serverGateUnauthenticatedMessage()))
        assertFalse(serverGateShouldPinSignOut(null))
        assertFalse(serverGateShouldPinSignOut(AuthGateMessage.Dynamic("Could not load servers.")))
    }

    @Test
    fun authLossEffectSkipsAStaleAuthorityAfterSuccessorLogin() {
        val captured =
            StoredAuthSession(
                user = StoredAuthUser(id = "user-a", email = "a@example.com"),
                accessToken = "access-a",
                refreshToken = "refresh-a",
            )
        val successor =
            captured.copy(
                user = StoredAuthUser(id = "user-b", email = "b@example.com"),
                accessToken = "access-b",
                refreshToken = "refresh-b",
            )

        assertTrue(appShellAuthLossEffectShouldApply(captured, null))
        assertTrue(appShellAuthLossEffectShouldApply(captured, captured.copy(selectedServerId = "server-2")))
        assertFalse(appShellAuthLossEffectShouldApply(captured, successor))
        assertFalse(appShellAuthLossEffectShouldApply(null, successor))
    }

    @Test
    fun authLossTerminalRevisionClearsExactCapturedAuthorityAfterEarlierClearFailure() = runBlocking {
        val captured =
            StoredAuthSession(
                user = StoredAuthUser(id = "user-a", email = "a@example.com"),
                accessToken = "access-a",
                refreshToken = "refresh-a",
            )
        val terminalSource = AppShellTerminalRevisionTestSource(initialSession = captured)
        val admitted = terminalSource.readAuthSessionTerminalSnapshot()
        var resetCount = 0
        var signedOutCount = 0

        val committedGeneration =
            appShellRunIfAuthSessionTerminalOrAuthorityRevision(
                terminalSource = terminalSource,
                expectedGeneration = admitted.generation,
                expectedSession = captured,
            ) {
                resetCount += 1
                signedOutCount += 1
            }

        assertNotNull(committedGeneration)
        assertEquals(1, resetCount)
        assertEquals(1, signedOutCount)
        assertNull(terminalSource.readAuthSessionTerminalSnapshot().session)
    }

    @Test
    fun serverGateShowsTheClientsLocalizedCopyOverItsLogMessage() {
        // The client's own copy (display) wins; a server's words without it show as sent.
        val localized =
            SharedAuthResult.Failure(
                SharedAuthErrorCode.Validation,
                "Server name is required.",
                display = Res.string.auth_validation_server_name_required.desc()
            )
        assertEquals(
            AuthGateMessage.Static(Res.string.auth_validation_server_name_required.desc()),
            serverGateActionFailureMessage("__create__", localized)
        )
        assertEquals(
            AuthGateMessage.Dynamic("Slug already taken"),
            serverGateActionFailureMessage("__create__", SharedAuthResult.Failure(SharedAuthErrorCode.Validation, "Slug already taken"))
        )
    }

}

private class AppShellTerminalRevisionTestSource(
    initialSession: StoredAuthSession? = null,
) : RaftAuthSessionTerminalSource {
    private val events = MutableSharedFlow<AuthSessionEvent>(extraBufferCapacity = 1)
    private var generation = 1L
    private var session: StoredAuthSession? = initialSession
    private var installSuccessorBeforeCommitReturns = false

    override val authSessionTerminalEvents: SharedFlow<AuthSessionEvent> = events

    override suspend fun readAuthSessionTerminalSnapshot(): AuthSessionTerminalSnapshot =
        AuthSessionTerminalSnapshot(generation = generation, session = session)

    override suspend fun runIfAuthSessionTerminalRevision(
        expectedGeneration: Long,
        action: () -> Unit,
    ): Boolean {
        if (generation != expectedGeneration || session != null) return false
        action()
        return true
    }

    override suspend fun runIfAuthSessionTerminalOrAuthorityRevision(
        expectedGeneration: Long,
        expectedSession: StoredAuthSession?,
        action: () -> Unit,
    ): Long? {
        val current = session
        val committedGeneration =
            if (current == null) {
                if (generation != expectedGeneration) return null
                action()
                generation
            } else {
                if (
                    expectedSession == null ||
                    current.user.id != expectedSession.user.id ||
                    current.accessToken != expectedSession.accessToken ||
                    current.refreshToken != expectedSession.refreshToken
                ) {
                    return null
                }
                session = null
                generation += 1
                action()
                generation
            }
        if (installSuccessorBeforeCommitReturns) {
            installSuccessorBeforeCommitReturns = false
            installSuccessor()
        }
        return committedGeneration
    }

    fun installSuccessor() {
        generation += 1
        session =
            StoredAuthSession(
                user = StoredAuthUser(id = "user-b", email = "b@example.com"),
                accessToken = "access-b",
                refreshToken = "refresh-b",
            )
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
