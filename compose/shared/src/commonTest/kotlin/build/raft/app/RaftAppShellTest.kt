package build.raft.app

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import build.raft.app.data.APP_UPDATE_HOME_NOTIFICATION_ID

import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.AuthAuthorityChangedMessage
import build.raft.app.data.AuthSessionEvent
import build.raft.app.data.AuthSessionTerminalSnapshot
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.InboxItem
import build.raft.app.data.InboxSnapshot
import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.SharedConversationDirectoryState
import build.raft.app.data.SharedAuthGateState
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.normalizedOrNull
import build.raft.app.data.RaftAuthSessionTerminalSource
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RaftAppShellTest {
    @Test
    fun handsFeedbackPageDataKeepsOnlyRouteIdentityAndShellScope() {
        val pageData =
            settingsHandsFeedbackPageData(
                RaftShellStoreScope.create(
                    shellInstanceId = "shell-settings",
                    initialServerId = "server-settings"
                )
            )

        assertEquals(SETTINGS_HANDS_FEEDBACK_PAGE, pageData.optString("pageName"))
        assertEquals("shell-settings", pageData.optString(RAFT_SHELL_INSTANCE_ID))
        assertEquals("server-settings", pageData.optString(RAFT_SHELL_SERVER_ID))
        assertFalse(pageData.has("accountId"))
        assertFalse(pageData.has("draft"))
    }

    @Test
    fun appShellRendererUpdatesStayIsolatedToTheSelectedChildSlot() {
        val initialHomeRenderer: AppShellChildTabRenderer = { _, _ -> }
        val entry =
            AppShellChildTabEntry(
                debugId = 1,
                initialSelectedTab = "home",
                initialRenderer = initialHomeRenderer
            )
        val updatedTasksRenderer: AppShellChildTabRenderer = { _, _ -> }

        entry.updateRenderer("tasks", updatedTasksRenderer)

        assertSame(initialHomeRenderer, entry.rendererFor("home"))
        assertSame(updatedTasksRenderer, entry.rendererFor("tasks"))
    }

    @Test
    fun appShellInactivePrewarmActivatesWithTheLatestRenderer() {
        val initialRenderer: AppShellChildTabRenderer = { _, _ -> }
        val latestTasksRenderer: AppShellChildTabRenderer = { _, _ -> }
        val entry =
            AppShellChildTabEntry(
                debugId = 1,
                initialSelectedTab = "home",
                initialRetainedTabs = setOf("home"),
                initialRenderer = initialRenderer
            )

        entry.updateSelection(
            selectedTab = "tasks",
            retainedTabs = setOf("home", "tasks"),
            renderer = latestTasksRenderer
        )

        assertEquals("tasks", entry.selectedTab)
        assertTrue("tasks" in entry.retainedTabs)
        assertSame(latestTasksRenderer, entry.rendererFor("tasks"))
        assertSame(initialRenderer, entry.rendererFor("home"))
    }

    @Test
    fun appShellVisibilitySignalsCoalesceLifecycleThenPageDidAppear() {
        val gate = AppShellVisibleRefreshGate()

        assertTrue(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.Lifecycle,
                appearanceGeneration = 4,
                target = "home:"
            )
        )
        assertFalse(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.PageDidAppear,
                appearanceGeneration = 5,
                target = "home:"
            )
        )
        gate.onLifecycleHidden(appearanceGeneration = 5)
        assertTrue(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.Lifecycle,
                appearanceGeneration = 5,
                target = "home:"
            )
        )
    }

    @Test
    fun appShellVisibilitySignalsCoalesceInitialAppearanceThenFirstResume() {
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
        gate.onLifecycleHidden(appearanceGeneration = 1)
        assertTrue(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.Lifecycle,
                appearanceGeneration = 1,
                target = "home:"
            )
        )
        assertFalse(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.PageDidAppear,
                appearanceGeneration = 2,
                target = "home:"
            )
        )
    }

    @Test
    fun appShellVisibilitySignalsCoalescePageDidAppearThenLifecycle() {
        val gate = AppShellVisibleRefreshGate()

        assertTrue(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.PageDidAppear,
                appearanceGeneration = 1,
                target = "home:"
            )
        )
        assertFalse(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.Lifecycle,
                appearanceGeneration = 1,
                target = "home:"
            )
        )
    }

    @Test
    fun appShellCompletedPageFirstGenerationOpensTheNextLifecycleGeneration() {
        val gate = AppShellVisibleRefreshGate()

        assertTrue(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.PageDidAppear,
                appearanceGeneration = 1,
                target = "home:"
            )
        )
        assertFalse(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.Lifecycle,
                appearanceGeneration = 1,
                target = "home:"
            )
        )
        gate.onLifecycleHidden(appearanceGeneration = 1)
        assertTrue(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.Lifecycle,
                appearanceGeneration = 1,
                target = "home:"
            )
        )
        assertFalse(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.PageDidAppear,
                appearanceGeneration = 2,
                target = "home:"
            )
        )
    }

    @Test
    fun appShellHiddenFrontierAllowsNextPageToArriveBeforeLifecycle() {
        val gate = AppShellVisibleRefreshGate()

        assertTrue(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.PageDidAppear,
                appearanceGeneration = 1,
                target = "home:"
            )
        )
        assertFalse(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.Lifecycle,
                appearanceGeneration = 1,
                target = "home:"
            )
        )
        gate.onLifecycleHidden(appearanceGeneration = 1)
        assertTrue(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.PageDidAppear,
                appearanceGeneration = 2,
                target = "home:"
            )
        )
        assertFalse(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.Lifecycle,
                appearanceGeneration = 2,
                target = "home:"
            )
        )
    }

    @Test
    fun appShellVisibilitySignalsCoalesceDuplicateLifecycleEventsForOneAppearance() {
        val gate = AppShellVisibleRefreshGate()

        assertTrue(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.Lifecycle,
                appearanceGeneration = 4,
                target = "home:"
            )
        )
        assertFalse(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.Lifecycle,
                appearanceGeneration = 4,
                target = "home:"
            )
        )
        assertFalse(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.PageDidAppear,
                appearanceGeneration = 5,
                target = "home:"
            )
        )
    }

    @Test
    fun appShellVisibilitySignalsCoalesceInterleavedAppearanceGenerations() {
        val gate = AppShellVisibleRefreshGate()

        assertTrue(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.Lifecycle,
                appearanceGeneration = 4,
                target = "home:"
            )
        )
        assertFalse(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.PageDidAppear,
                appearanceGeneration = 5,
                target = "home:"
            )
        )
        gate.onLifecycleHidden(appearanceGeneration = 5)
        assertTrue(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.Lifecycle,
                appearanceGeneration = 5,
                target = "home:"
            )
        )
        assertFalse(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.PageDidAppear,
                appearanceGeneration = 6,
                target = "home:"
            )
        )
    }

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
    fun appShellEvictedPageFirstGenerationNeedsHiddenBeforeOpeningNext() {
        val gate = AppShellVisibleRefreshGate()

        assertTrue(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.PageDidAppear,
                appearanceGeneration = 1,
                target = "home:"
            )
        )
        (2..18).forEach { generation ->
            assertTrue(
                gate.shouldRefresh(
                    AppShellVisibleRefreshSignal.PageDidAppear,
                    appearanceGeneration = generation,
                    target = "other-$generation"
                )
            )
        }

        assertFalse(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.Lifecycle,
                appearanceGeneration = 1,
                target = "home:"
            )
        )
        gate.onLifecycleHidden(appearanceGeneration = 1)
        assertTrue(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.Lifecycle,
                appearanceGeneration = 1,
                target = "home:"
            )
        )
        assertFalse(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.PageDidAppear,
                appearanceGeneration = 2,
                target = "home:"
            )
        )
    }

    @Test
    fun appShellVisibilitySignalsDoNotReopenAnEvictedAppearance() {
        val gate = AppShellVisibleRefreshGate()

        (1..17).forEach { generation ->
            assertTrue(
                gate.shouldRefresh(
                    AppShellVisibleRefreshSignal.PageDidAppear,
                    appearanceGeneration = generation,
                    target = "target-$generation"
                )
            )
        }

        assertFalse(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.Lifecycle,
                appearanceGeneration = 0,
                target = "target-1"
            )
        )
        assertTrue(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.PageDidAppear,
                appearanceGeneration = 1,
                target = "late-independent-target"
            )
        )
        assertTrue(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.PageDidAppear,
                appearanceGeneration = 18,
                target = "target-18"
            )
        )
    }

    @Test
    fun appShellVisibilitySignalsPreserveAdjacentGenerationsAndDifferentTargets() {
        val gate = AppShellVisibleRefreshGate()

        assertTrue(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.Lifecycle,
                appearanceGeneration = 4,
                target = "home:"
            )
        )
        gate.onLifecycleHidden(appearanceGeneration = 5)
        assertTrue(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.Lifecycle,
                appearanceGeneration = 5,
                target = "home:"
            )
        )
        assertTrue(
            gate.shouldRefresh(
                AppShellVisibleRefreshSignal.PageDidAppear,
                appearanceGeneration = 6,
                target = "tasks:"
            )
        )
    }

    @Test
    fun appShellInternalActivityHandoffDoesNotRefreshAgainInTheSameProcessEpoch() {
        val gate = AppShellVisibleRefreshGate()

        assertTrue(
            gate.shouldRefresh(
                signal = AppShellVisibleRefreshSignal.Lifecycle,
                appearanceGeneration = 0,
                target = "home:",
                foregroundEpoch = 1L
            )
        )

        gate.onLifecycleHidden(appearanceGeneration = 1, foregroundEpoch = 1L)
        gate.observeLifecycleForegroundEpoch(1L)

        assertFalse(
            gate.shouldRefresh(
                signal = AppShellVisibleRefreshSignal.Lifecycle,
                appearanceGeneration = 1,
                target = "home:",
                foregroundEpoch = 1L
            )
        )
        assertFalse(
            gate.shouldRefresh(
                signal = AppShellVisibleRefreshSignal.PageDidAppear,
                appearanceGeneration = 2,
                target = "home:"
            )
        )
    }

    @Test
    fun appShellTrueProcessForegroundRefreshesExactlyOnceForTheNewEpoch() {
        val gate = AppShellVisibleRefreshGate()

        assertTrue(
            gate.shouldRefresh(
                signal = AppShellVisibleRefreshSignal.Lifecycle,
                appearanceGeneration = 0,
                target = "home:",
                foregroundEpoch = 1L
            )
        )
        gate.onLifecycleHidden(appearanceGeneration = 1, foregroundEpoch = 1L)

        assertTrue(
            gate.shouldRefresh(
                signal = AppShellVisibleRefreshSignal.Lifecycle,
                appearanceGeneration = 1,
                target = "home:",
                foregroundEpoch = 2L
            )
        )
        assertFalse(
            gate.shouldRefresh(
                signal = AppShellVisibleRefreshSignal.PageDidAppear,
                appearanceGeneration = 2,
                target = "home:"
            )
        )
    }

    @Test
    fun appShellPageAppearanceConsumesANewProcessEpochMissedWhileHidden() {
        val gate = AppShellVisibleRefreshGate()

        assertTrue(
            gate.shouldRefresh(
                signal = AppShellVisibleRefreshSignal.Lifecycle,
                appearanceGeneration = 0,
                target = "home:",
                foregroundEpoch = 1L
            )
        )
        gate.onLifecycleHidden(appearanceGeneration = 1, foregroundEpoch = 1L)
        gate.observeLifecycleForegroundEpoch(2L)

        assertTrue(
            gate.shouldRefresh(
                signal = AppShellVisibleRefreshSignal.PageDidAppear,
                appearanceGeneration = 2,
                target = "home:"
            )
        )
        assertFalse(
            gate.shouldRefresh(
                signal = AppShellVisibleRefreshSignal.Lifecycle,
                appearanceGeneration = 2,
                target = "home:",
                foregroundEpoch = 2L
            )
        )
    }

    @Test
    fun appTabRendererUsesIosPlatformDefaultWhenRoutePageDataOmitsRenderer() {
        assertEquals(
            RaftAppTabBarRenderer.IosLiquidGlass,
            resolveRaftAppTabBarRenderer(
                explicitRenderer = null,
                platformDefaultRenderer = "iosLiquidGlass"
            )
        )
    }

    @Test
    fun appTabRendererKeepsExplicitHostCapabilityAheadOfPlatformDefault() {
        assertEquals(
            RaftAppTabBarRenderer.SharedCompose,
            resolveRaftAppTabBarRenderer(
                explicitRenderer = "sharedCompose",
                platformDefaultRenderer = "iosLiquidGlass"
            )
        )
    }

    @Test
    fun legacyAndroidFloatingValuesKeepTheirSharedKuiklyPresentation() {
        assertEquals(
            RaftAppTabBarRenderer.AndroidFloating,
            resolveRaftAppTabBarRenderer(
                explicitRenderer = "androidFloating",
                platformDefaultRenderer = null
            )
        )
        assertEquals(
            RaftAppTabBarRenderer.AndroidFloating,
            resolveRaftAppTabBarRenderer(
                explicitRenderer = "androidNativeFloating",
                platformDefaultRenderer = null
            )
        )
    }

    @Test
    fun everyBottomTabRendererKeepsFullBleedChildPageSeamSquare() {
        assertNull(appShellChildPageGlassStyle(bottomTabBarOverlaysContent = false))
        assertNull(appShellChildPageGlassStyle(bottomTabBarOverlaysContent = true))
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
    fun notificationEnvelopeKeepsItsTargetServerWhenBecomingWindowNavigationIntent() {
        val intent =
            assertNotNull(
                JSONObject()
                    .put("slockNotificationServerId", "server-b")
                    .put("slockNotificationThreadId", "channel-b")
                    .put("slockNotificationMessageId", "message-b")
                    .appShellWindowNavigationIntent(fallbackServerId = "server-a")
            )

        assertEquals("server-b", intent.serverId)
        assertEquals("system_notification", intent.source)
        assertEquals("channel-b", intent.sourceIdentity.threadChannelId)
        assertEquals("message-b", intent.sourceIdentity.messageId)
    }

    @Test
    fun legacyNotificationEnvelopeMayUseOnlyItsWindowLaunchServerAsFallback() {
        val intent =
            assertNotNull(
                JSONObject()
                    .put("slockNotificationThreadId", "channel-a")
                    .put("slockNotificationMessageId", "message-a")
                    .appShellWindowNavigationIntent(fallbackServerId = "server-a")
            )

        assertEquals("server-a", intent.serverId)
        assertNull(
            JSONObject()
                .put("slockNotificationThreadId", "channel-unknown")
                .put("slockNotificationMessageId", "message-unknown")
                .appShellWindowNavigationIntent(fallbackServerId = null)
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
    fun shellScopeProvidesExplicitPanelInsets() {
        val scope =
            RaftAppShellScope(
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
        assertEquals(18.dp + RaftAppSpacing.Lg, scope.bottomInset(includeBottomTabBar = false))
        assertEquals(88.dp - RaftAppSpacing.Lg, scope.bottomTabBarHostInset(includeBottomTabBar = true))
        assertEquals(0.dp, scope.bottomTabBarHostInset(includeBottomTabBar = false))
        assertEquals(88.dp, scope.globalDialogBottomInset(includeBottomTabBar = true))
        assertEquals(0.dp, scope.globalDialogBottomInset(includeBottomTabBar = false))
        assertEquals(RaftAppSpacing.Lg, scope.contentBottomInset(includeBottomTabBar = true))
        assertEquals(18.dp + RaftAppSpacing.Lg, scope.contentBottomInset(includeBottomTabBar = false))
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
    fun embeddedChildClearsFloatingDockWithoutDoubleCountingSafeArea() {
        // Embedded thread pages already pad the bottom safe area internally; the
        // slot only lifts the remaining dock band above it.
        val scope =
            RaftAppShellScope(
                topSafeArea = 24.dp,
                bottomSafeArea = 18.dp,
                bottomTabBarContentInset = 110.dp,
                bottomTabBarOverlaysContent = true
            )

        assertEquals(92.dp, scope.embeddedChildBottomInset(includeBottomTabBar = true))
        assertEquals(0.dp, scope.embeddedChildBottomInset(includeBottomTabBar = false))
    }

    @Test
    fun embeddedChildNeedsNoInsetWhenHostAlreadyReservesTheBand() {
        val scope =
            RaftAppShellScope(
                topSafeArea = 24.dp,
                bottomSafeArea = 18.dp,
                bottomTabBarContentInset = 88.dp,
                bottomTabBarOverlaysContent = false
            )

        assertEquals(0.dp, scope.embeddedChildBottomInset(includeBottomTabBar = true))
        assertEquals(0.dp, scope.embeddedChildBottomInset(includeBottomTabBar = false))
    }

    @Test
    fun embeddedChildInsetNeverGoesNegativeWhenDockBandShrinksBelowSafeArea() {
        val scope =
            RaftAppShellScope(
                topSafeArea = 24.dp,
                bottomSafeArea = 24.dp,
                bottomTabBarContentInset = 10.dp,
                bottomTabBarOverlaysContent = true
            )

        assertEquals(0.dp, scope.embeddedChildBottomInset(includeBottomTabBar = true))
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
    fun androidBottomSafeAreaKeepsGestureNavigationInsetUnlessHostExcludesIt() {
        assertEquals(0f, resolveRaftBottomSafeAreaDp(hostExcludesNavigationBar = true, nativeDp = 48f, hostDp = 48f))
        assertEquals(24f, resolveRaftBottomSafeAreaDp(hostExcludesNavigationBar = false, nativeDp = 24f, hostDp = 0f))
        assertEquals(48f, resolveRaftBottomSafeAreaDp(hostExcludesNavigationBar = false, nativeDp = 24f, hostDp = 48f))
    }

    @Test
    fun sharedBottomTabHostKeepsSafeAreaOutsideTabContent() {
        // stdrc 2026-10-21: the 58dp edge bar, then the bottom safe area below it.
        assertEquals(58.dp, raftSharedBottomTabBarHostHeight(0.dp))
        assertEquals(76.dp, raftSharedBottomTabBarHostHeight(18.dp))
        assertEquals(
            raftSharedBottomTabBarHostHeight(18.dp) + RaftAppSpacing.Lg,
            raftSharedBottomTabBarContentInset(18.dp)
        )
    }

    @Test
    fun appShellLayerZOrderKeepsEnvironmentIndicatorAboveEveryInteractiveLayer() {
        assertTrue(RaftEnvironmentIndicatorZIndex > RaftFloatingOverlayHostZIndex)
        assertTrue(RaftFloatingOverlayHostZIndex > RaftGlobalModalHostZIndex)
        assertTrue(RaftGlobalModalHostZIndex > RaftAppBottomTabBarHostZIndex)
        assertTrue(RaftAppBottomTabBarHostZIndex > RaftAppContentHostZIndex)
    }

    @Test
    fun everyComposeRendererReservesTheEdgeBarBand() {
        // stdrc 2026-10-21: no floating docks; every Compose renderer draws the edge bar.
        val bottomSafeArea = 18.dp
        listOf(
            RaftAppTabBarRenderer.AndroidFloating,
            RaftAppTabBarRenderer.IosLiquidGlass,
            RaftAppTabBarRenderer.SharedCompose,
        ).forEach { renderer ->
            assertEquals(
                raftSharedBottomTabBarContentInset(bottomSafeArea),
                raftAppBottomTabBarContentInset(renderer, bottomSafeArea),
                "renderer=$renderer"
            )
        }
    }

    @Test
    fun createAgentDialogRegistersGlobalHostRoute() {
        assertTrue(SharedCreateAgentDialogHostRoute.isNotBlank())
        assertTrue(SharedCreateAgentDialogHostRoute != RaftUnifiedDialogGenericHostRoute)

        val hostState = RaftUnifiedDialogHostState()
        val key = RaftUnifiedDialogHostKey()
        hostState.upsert(key, SharedCreateAgentDialogHostRoute) {}

        assertEquals(1, hostState.entries.size)
        assertEquals(SharedCreateAgentDialogHostRoute, hostState.entries.single().route)
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
    fun appShellChildPagePrewarmSkipsActiveRetainedAndAlreadyPrewarmedTabs() {
        assertEquals(
            listOf("settings", "tasks", "members"),
            appShellChildPagePrewarmOrder(
                activeChildTab = "home",
                retainedTabs = setOf("home"),
                prewarmedTabs = emptySet()
            )
        )
        assertEquals(
            listOf("members"),
            appShellChildPagePrewarmOrder(
                activeChildTab = "tasks",
                retainedTabs = setOf("home", "settings"),
                prewarmedTabs = setOf("tasks")
            )
        )
    }

    @Test
    fun appShellChildPagePrewarmUsesPlaceholderUntilTabIsActiveOrRetained() {
        assertFalse(appShellChildPageRendersContent(active = false, retained = false))
        assertTrue(appShellChildPageRendersContent(active = true, retained = false))
        assertTrue(appShellChildPageRendersContent(active = false, retained = true))
    }

    @Test
    fun appShellChildTabContentActiveFollowsPagerPlacementNotLaggingSelection() {
        // Switch frame: the pager already places the new tab while the registry entry still
        // holds the previous selection. Content activity must follow the placement.
        val laggingSelection = "home"
        assertFalse(appShellChildTabIsActive(parentVisible = true, selectedTab = laggingSelection, tab = "members"))
        assertTrue(appShellChildTabContentActive(parentVisible = true, placedActive = true))
        assertFalse(appShellChildTabContentActive(parentVisible = true, placedActive = false))
        // A covered shell keeps the selected tab placed but its content inactive.
        assertFalse(appShellChildTabContentActive(parentVisible = false, placedActive = true))
        // A newly placed tab renders content on its first frame even before it is retained.
        assertTrue(
            appShellChildPageRendersContent(
                active = appShellChildTabContentActive(parentVisible = true, placedActive = true),
                retained = false
            )
        )
    }

    @Test
    fun appShellChildPagePrewarmPolicyLimitsCanaryToOneController() {
        assertEquals(
            0,
            appShellChildPagePrewarmPolicy(AppShellChildPagePrewarmMode.Disabled).maxControllers
        )
        assertEquals(
            1,
            appShellChildPagePrewarmPolicy(AppShellChildPagePrewarmMode.Canary).maxControllers
        )
        assertEquals(
            3,
            appShellChildPagePrewarmPolicy(AppShellChildPagePrewarmMode.Enabled).maxControllers
        )
    }

    @Test
    fun appShellPrewarmLeaseCannotCommitAfterForegroundInvalidation() {
        val gate = PrewarmAdmissionGate()
        val lease = assertNotNull(gate.tryAcquire(gate.generation()))
        var committed = false

        gate.invalidate("input")

        assertFalse(lease.commit { committed = true })
        assertFalse(committed)
        lease.release()
        assertEquals("input", gate.latestInvalidationReason())
    }

    @Test
    fun appShellPrewarmGateAllowsOnlyOneSpeculativeLease() {
        val gate = PrewarmAdmissionGate()
        val generation = gate.generation()
        val first = assertNotNull(gate.tryAcquire(generation))

        assertNull(gate.tryAcquire(generation))
        assertTrue(first.commit { })
        assertFalse(first.commit { error("a lease must commit at most once") })
        first.release()
        assertNotNull(gate.tryAcquire(generation))
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
    fun appShellTabSelectionIgnoresNativeDuplicateAndCurrentTabTaps() {
        assertFalse(shouldHandleAppShellTabSelection(currentTab = "settings", nextTab = "settings"))
        assertFalse(shouldHandleAppShellTabSelection(currentTab = "unknown", nextTab = "home"))
        assertTrue(shouldHandleAppShellTabSelection(currentTab = "home", nextTab = "settings"))
    }

    @Test
    fun bottomTabDoubleTapScrollsOnlyTheCurrentHomeTasksOrMembersSurface() {
        val tracker = AppShellBottomTabTapTracker(doubleTapWindowMs = 400L)

        assertEquals(
            AppShellBottomTabTapAction.ArmCurrent,
            tracker.onTap(selectedTab = "home", tappedTab = "home", nowMs = 100L),
        )
        assertEquals(
            AppShellBottomTabTapAction.ScrollCurrentToTop,
            tracker.onTap(selectedTab = "home", tappedTab = "home", nowMs = 500L),
        )
        assertEquals(
            AppShellBottomTabTapAction.ArmCurrent,
            tracker.onTap(selectedTab = "home", tappedTab = "home", nowMs = 501L),
        )

        assertEquals(
            AppShellBottomTabTapAction.Select,
            tracker.onTap(selectedTab = "home", tappedTab = "tasks", nowMs = 1_000L),
        )
        assertEquals(
            AppShellBottomTabTapAction.ScrollCurrentToTop,
            tracker.onTap(selectedTab = "tasks", tappedTab = "tasks", nowMs = 1_250L),
        )

        assertEquals(
            AppShellBottomTabTapAction.Select,
            tracker.onTap(selectedTab = "tasks", tappedTab = "settings", nowMs = 2_000L),
        )
        assertEquals(
            AppShellBottomTabTapAction.ArmCurrent,
            tracker.onTap(selectedTab = "settings", tappedTab = "settings", nowMs = 2_100L),
        )
    }

    @Test
    fun bottomTabDoubleTapExpiresAndDifferentTabRetiresTheArmedIntent() {
        val tracker = AppShellBottomTabTapTracker(doubleTapWindowMs = 400L)

        assertEquals(
            AppShellBottomTabTapAction.ArmCurrent,
            tracker.onTap(selectedTab = "members", tappedTab = "members", nowMs = 100L),
        )
        assertEquals(
            AppShellBottomTabTapAction.ArmCurrent,
            tracker.onTap(selectedTab = "members", tappedTab = "members", nowMs = 501L),
        )
        assertEquals(
            AppShellBottomTabTapAction.Select,
            tracker.onTap(selectedTab = "members", tappedTab = "home", nowMs = 550L),
        )
        assertEquals(
            AppShellBottomTabTapAction.ArmCurrent,
            tracker.onTap(selectedTab = "members", tappedTab = "members", nowMs = 600L),
        )
    }

    @Test
    fun bottomTabDoubleTapOnActivityScrollsItsListToTop() {
        val tracker = AppShellBottomTabTapTracker(doubleTapWindowMs = 400L)

        assertEquals(
            AppShellBottomTabTapAction.Select,
            tracker.onTap(selectedTab = "home", tappedTab = "activity", nowMs = 100L),
        )
        assertEquals(
            AppShellBottomTabTapAction.ScrollCurrentToTop,
            tracker.onTap(selectedTab = "activity", tappedTab = "activity", nowMs = 300L),
        )
        assertEquals(
            AppShellBottomTabTapAction.ArmCurrent,
            tracker.onTap(selectedTab = "activity", tappedTab = "activity", nowMs = 1_000L),
        )
        assertEquals(
            AppShellBottomTabTapAction.ScrollCurrentToTop,
            tracker.onTap(selectedTab = "activity", tappedTab = "activity", nowMs = 1_200L),
        )
    }

    @Test
    fun tabScrollRequestsAreMonotonicPerSurfaceAndSettingsIsExcluded() {
        val initial = AppShellTabScrollToTopRequests()
        val requested =
            initial
                .request("home")
                .request("activity")
                .request("dms")
                .request("tasks")
                .request("members")
                .request("settings")

        assertEquals(1, requested.forTab("home"))
        assertEquals(1, requested.forTab("dms"))
        assertEquals(1, requested.forTab("tasks"))
        assertEquals(1, requested.forTab("members"))
        assertEquals(0, requested.forTab("settings"))
        assertEquals(1, requested.forTab("activity"))
        assertEquals(0, requested.forTab("more"))
    }

    @Test
    fun scrollRequestFenceDoesNotReplayAnIntentAfterListOwnerRemount() {
        val fence = AppShellScrollToTopRequestFence(initialRequest = 7)

        assertFalse(fence.consume(7))
        assertTrue(fence.consume(8))
        assertFalse(fence.consume(8))
        assertFalse(fence.consume(6))
        assertTrue(fence.consume(9))
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
    fun appShellAuthGateViewModelKeyIsPageScoped() {
        assertEquals("shared-auth-gate-page-a", appShellAuthGateViewModelKey("page-a"))
        assertEquals("shared-auth-gate-page-b", appShellAuthGateViewModelKey("page-b"))
        assertEquals("shared-auth-gate-post-login-1", appShellAuthGateViewModelKey("page-a", "post-login-1"))
        assertEquals("shared-auth-gate-default", appShellAuthGateViewModelKey(""))
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
    fun appShellLaunchSelectionIgnoresLegacyInlineSettingsDestination() {
        val params =
            JSONObject()
                .put("slockInitialTab", "settings")
                .put("slockInitialSettingsDestination", "diagnostics")

        assertEquals(
            AppShellLaunchSelection(selectedTab = "settings"),
            params.appShellLaunchSelection()
        )
    }

    @Test
    fun appShellLaunchSelectionStillNormalizesUnknownTabs() {
        val params =
            JSONObject()
                .put("slockInitialTab", "unknown")
                .put("slockInitialSettingsDestination", "diagnostics")

        assertEquals(
            AppShellLaunchSelection(selectedTab = "home"),
            params.appShellLaunchSelection()
        )
    }

    @Test
    fun diagnosticsDeepLinkRequestParsesControlFlagsOnly() {
        val params =
            JSONObject()
                .put("slockDiagnosticsDeepLinkId", "deep-link-1")
                .put("slockDiagnosticsDeepLinkAutoCloseMs", 15000L)

        assertEquals(
            DiagnosticsDeepLinkRequest(
                id = "deep-link-1",
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
    fun appShellHomeListServerFollowsRenderedContentUntilTargetArrives() {
        assertEquals(
            "server-a",
            appShellHomeListServerId(
                renderedServerId = "server-a",
                readyServerId = "server-b"
            )
        )
        assertEquals(
            "server-b",
            appShellHomeListServerId(
                renderedServerId = null,
                readyServerId = "server-b"
            )
        )
        assertEquals(null, appShellHomeListServerId(renderedServerId = " ", readyServerId = ""))
    }

    @Test
    fun homeServerSwitchPremeasureRampsInSmallFrameSizedSteps() {
        assertEquals(9, homeListNextServerSwitchBeyondBoundsItemCount(current = 6, target = 24))
        assertEquals(15, homeListNextServerSwitchBeyondBoundsItemCount(current = 12, target = 15))
        assertEquals(4, homeListNextServerSwitchBeyondBoundsItemCount(current = 0, target = 4))
    }

    @Test
    fun homeSidebarLazyKeysReuseSlotsAcrossServersAndFollowIdentityWithinServer() {
        val registry = HomeSidebarLazyKeyRegistry()

        assertEquals(
            listOf("home-channel-slot-0", "home-channel-slot-1"),
            registry.keysFor("server-a", "channel", listOf("a", "b"))
        )
        assertEquals(
            listOf("home-channel-slot-0", "home-channel-slot-1"),
            registry.keysFor("server-b", "channel", listOf("x", "y"))
        )
        assertEquals(
            listOf("home-channel-identity-z", "home-channel-slot-1", "home-channel-slot-0"),
            registry.keysFor("server-b", "channel", listOf("z", "y", "x"))
        )
        assertEquals(
            listOf("home-channel-slot-0", "home-channel-identity-z"),
            registry.keysFor("server-b", "channel", listOf("x", "z"))
        )
        assertNotEquals(homeSidebarSlotKey("channel", 0), homeSidebarSlotKey("dm", 0))
    }

    @Test
    fun homeListStateSnapshotOnlyPersistsForCurrentOwner() {
        assertTrue(homeListStateOwnerMatches("server-a", "server-a"))
        assertFalse(homeListStateOwnerMatches("server-a", "server-b"))
        assertFalse(homeListStateOwnerMatches("server-a", null))
    }

    @Test
    fun homeListPositionRestoreRequiresUncontestedViewportOwnership() {
        assertTrue(
            shouldRestoreHomeListPosition(
                listStateServerId = "server-a",
                listPositionServerId = "server-b",
                restoreStartUserScrollGeneration = 4,
                currentUserScrollGeneration = 4,
                isScrollInProgress = false
            )
        )
        assertFalse(
            shouldRestoreHomeListPosition(
                listStateServerId = "server-a",
                listPositionServerId = "server-b",
                restoreStartUserScrollGeneration = 4,
                currentUserScrollGeneration = 5,
                isScrollInProgress = false
            )
        )
        assertFalse(
            shouldRestoreHomeListPosition(
                listStateServerId = "server-a",
                listPositionServerId = "server-b",
                restoreStartUserScrollGeneration = 4,
                currentUserScrollGeneration = 4,
                isScrollInProgress = true
            )
        )
        assertFalse(
            shouldRestoreHomeListPosition(
                listStateServerId = "server-a",
                listPositionServerId = "server-a",
                restoreStartUserScrollGeneration = 4,
                currentUserScrollGeneration = 4,
                isScrollInProgress = false
            )
        )
    }

    @Test
    fun appShellServerLazyListPositionsIgnoreBlankServerId() {
        val positions = AppShellServerLazyListPositions()
        assertEquals(
            positions,
            positions.withPosition(
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
        assertEquals("kuikly_dms", appShellBottomTabPerformanceRoute("dms"))
        assertEquals("kuikly_activity", appShellBottomTabPerformanceRoute("activity"))
        assertEquals("kuikly_more", appShellBottomTabPerformanceRoute("more"))
        assertEquals("kuikly_home", appShellBottomTabPerformanceRoute("unknown"))
        assertEquals("bottom_tab_home", appShellBottomTabPerformancePhase("unknown"))
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
        assertEquals(listOf("home", "activity", "tasks", AppShellSearchActionKey, "more"), tabs.map { it.key })
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
        // A stale/unknown selection (and the Search action key) never selects anything but Home.
        assertEquals(listOf(true, false, false, false, false), build(AppShellSearchActionKey).map { it.selected })
    }

    @Test
    fun searchSlotIsAnActionAndNeverSelected() {
        assertEquals(listOf("home", "activity", "tasks", "more"), AppShellPhoneBottomTabKeys)
        assertTrue(AppShellSearchActionKey in AppShellPhoneBottomBarKeys)
        assertFalse(AppShellSearchActionKey in AppShellPhoneBottomTabKeys)
        // Even asked to, the builder never marks the Search slot selected.
        val tabs = appShellBottomTabs(
            selectedKey = AppShellSearchActionKey,
            orderedTabKeys = AppShellPhoneBottomBarKeys,
            labelFor = { it }
        )
        assertTrue(tabs.none { it.selected })
        // It is not a shell tab key: a stray selection clamps to Home.
        assertEquals("home", retainedAppShellSelectedTab(AppShellSearchActionKey))
    }

    @Test
    fun membersGoesBackToMoreOnlyOnThePhoneLayout() {
        assertEquals("more", appShellMoreChildBackTarget("members", navRailActive = false))
        // The wide rail lists Members as a root.
        assertNull(appShellMoreChildBackTarget("members", navRailActive = true))
        // Settings is More's root on the phone, not a page pushed from it.
        listOf("home", "activity", "tasks", "more", "settings", "unknown").forEach { tab ->
            assertNull(appShellMoreChildBackTarget(tab, navRailActive = false), "tab=$tab")
        }
    }

    @Test
    fun settingsHasOneRootPerLayout() {
        // Phone: a settings deep link / diagnostics link lands on the More tab (Settings root).
        assertEquals("more", appShellSettingsRootTab("settings", navRailActive = false))
        // Wide rail: no More tab; the pinned Settings entry is the root.
        assertEquals("settings", appShellSettingsRootTab("more", navRailActive = true))
        listOf("home", "activity", "tasks", "members").forEach { tab ->
            assertNull(appShellSettingsRootTab(tab, navRailActive = false), "phone tab=$tab")
            assertNull(appShellSettingsRootTab(tab, navRailActive = true), "rail tab=$tab")
        }
        assertNull(appShellSettingsRootTab("more", navRailActive = false))
        assertNull(appShellSettingsRootTab("settings", navRailActive = true))
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
        assertEquals(false, build("home", both)[AppShellSearchActionKey])
    }

    @Test
    fun moreRootSettingsLeadsWithMembersAndComputersAndDotsAttentionRows() {
        val items =
            listOf(
                SettingsHomePageLinkPayload(key = "account", title = "Account", section = "Personal"),
                SettingsHomePageLinkPayload(key = "server", title = "Server", section = "Workspace"),
                SettingsHomePageLinkPayload(key = "computers", title = "Computers", section = "Workspace"),
                SettingsHomePageLinkPayload(key = "about", title = "About", section = "About"),
            )
        // The wide rail keeps its Settings page unchanged.
        assertEquals(items, settingsHomeItemsForMoreRoot(items, moreRoot = null))

        val root = SettingsMoreRoot(title = "More", membersTitle = "Members", attentionRowKeys = setOf("computers", "about"))
        val phone = settingsHomeItemsForMoreRoot(items, root)
        assertEquals(listOf("members", "computers", "account", "server", "about"), phone.map { it.key })
        // Members and Computers form the first group (no section heading) ahead of the sections.
        assertEquals(listOf(null, null, "Personal", "Workspace", "About"), phone.map { it.section })
        assertEquals(SettingsMoreMembersRowKey, phone.first().key)
        assertEquals("members", appShellActiveChildPagerTab(SettingsMoreMembersRowKey))
        assertEquals(listOf(false, true, false, false, true), phone.map { it.attentionDot })
        assertEquals(
            listOf(false, false, false, false, false),
            settingsHomeItemsForMoreRoot(items, root.copy(attentionRowKeys = emptySet())).map { it.attentionDot }
        )
    }

    @Test
    fun homeListDropsSearchAndActivityRowsOnlyWhereThePhoneBarShows() {
        assertFalse(homeSidebarShowsSearchAndActivityRows(searchAndActivityInBottomBar = true))
        assertTrue(homeSidebarShowsSearchAndActivityRows(searchAndActivityInBottomBar = false))
    }

    @Test
    fun bottomBarHidesWhileKeyboardIsOpen() {
        assertTrue(raftAppShellBottomTabsShown(showBottomTabs = true, imeVisible = false))
        assertFalse(raftAppShellBottomTabsShown(showBottomTabs = true, imeVisible = true))
        assertFalse(raftAppShellBottomTabsShown(showBottomTabs = false, imeVisible = false))
    }

    @Test
    fun bottomBarComesBackAnimatedWhenKeyboardCloses() {
        // raft-design #620: back with a ~200ms slide/fade up, not an instant jump; gone at once.
        assertEquals(200, RaftAppShellBottomBarReturnMillis)
        assertNotEquals(EnterTransition.None, RaftAppShellBottomBarReturn)
        assertEquals(ExitTransition.None, RaftAppShellBottomBarLeave)
    }

    @Test
    fun railTabsKeepWebLeftRailEntries() {
        val tabs =
            appShellBottomTabs(
                selectedKey = "members",
                orderedTabKeys = AppShellRailTabKeys,
                labelFor = { it }
            )
        assertEquals(listOf("home", "activity", "tasks", "members"), tabs.map { it.key })
        assertEquals(listOf(false, false, false, true), tabs.map { it.selected })
    }

    @Test
    fun appShellBottomTabsFollowOrderedKeysWithSlackIconsAndLabels() {
        // task #94: the ordered overload drives the Slack-aligned bar; order, labels,
        // selection and the preview-fixture icon set are all pinned.
        val tabs =
            appShellBottomTabs(
                selectedKey = "dms",
                orderedTabKeys = listOf("more", "dms", "activity", "home"),
                labelFor = { key -> "label-$key" }
            )
        assertEquals(listOf("more", "dms", "activity", "home"), tabs.map { it.key })
        assertEquals(
            listOf("label-more", "label-dms", "label-activity", "label-home"),
            tabs.map { it.label }
        )
        assertEquals(listOf(false, true, false, false), tabs.map { it.selected })
        assertEquals(
            listOf(
                RaftLucideIconName.Ellipsis,
                RaftLucideIconName.MessageCircle,
                RaftLucideIconName.Activity,
                RaftLucideIconName.House
            ),
            tabs.map { it.iconName }
        )
    }

    @Test
    fun appShellActiveChildTabFollowsSlackOrderedKeys() {
        // task #94: with Slack-aligned ordered keys, the Slack tabs are child pages and the
        // legacy hidden keys are not; the legacy default still applies without the param.
        val slackKeys = listOf("home", "dms", "activity", "more")
        assertEquals("home", appShellActiveChildPagerTab("home", slackKeys))
        assertEquals("dms", appShellActiveChildPagerTab("dms", slackKeys))
        assertEquals("activity", appShellActiveChildPagerTab("activity", slackKeys))
        assertEquals("more", appShellActiveChildPagerTab("more", slackKeys))
        assertNull(appShellActiveChildPagerTab("tasks", slackKeys))
        assertNull(appShellActiveChildPagerTab("members", slackKeys))
        assertNull(appShellActiveChildPagerTab("settings", slackKeys))
        // Unknown keys clamp to home, which is a valid child key in both modes.
        assertEquals("home", appShellActiveChildPagerTab("unknown", slackKeys))
    }

    @Test
    fun appShellRetainedChildTabsRespectSlackOrderedKeys() {
        val slackKeys = listOf("dms", "home", "activity", "more")
        assertEquals(
            listOf("dms", "home", "activity", "more"),
            appShellRetainedChildTabs(
                activeChildTab = "dms",
                mountedTabs = setOf("home", "activity", "more", "dms", "tasks"),
                childPageTabKeys = slackKeys
            )
        )
        // Legacy keys present in mountedTabs are NOT retained while Slack order is active.
        assertEquals(
            listOf("dms"),
            appShellRetainedChildTabs(
                activeChildTab = "dms",
                mountedTabs = setOf("dms", "tasks", "members"),
                childPageTabKeys = slackKeys
            )
        )
    }

    @Test
    fun retainedAppShellSelectedTabAcceptsSlackKeys() {
        assertEquals("dms", retainedAppShellSelectedTab("dms"))
        assertEquals("activity", retainedAppShellSelectedTab("activity"))
        assertEquals("more", retainedAppShellSelectedTab("more"))
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

    @Test
    fun appShellBottomTabsStayMountedBehindNativeHomeChildPages() {
        assertEquals(
            true,
            appShellBottomTabsVisible(
                selectedTab = "home",
                selectedHomeDestination = null,
            )
        )
        assertEquals(
            true,
            appShellBottomTabsVisible(
                selectedTab = "home",
                selectedHomeDestination = "activity",
            )
        )
        assertEquals(
            false,
            appShellBottomTabsVisible(
                selectedTab = "home",
                selectedHomeDestination = "unknown",
            )
        )
        assertEquals(
            true,
            appShellBottomTabsVisible(
                selectedTab = "settings",
                selectedHomeDestination = null,
            )
        )
        assertEquals(
            true,
            appShellBottomTabsVisible(
                selectedTab = "settings",
                selectedHomeDestination = null,
            )
        )
        assertEquals(
            true,
            appShellBottomTabsVisible(
                selectedTab = "tasks",
                selectedHomeDestination = "activity",
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
    fun childNavigationKeepsRenderedHomeServerWhileSelectedServerRestores() {
        assertEquals(
            "server-rendered",
            appShellNavigationServerId(
                selectedServerId = null,
                renderedHomeServerId = "server-rendered",
                scopeInitialServerId = null
            )
        )
        assertEquals(
            "server-selected",
            appShellNavigationServerId(
                selectedServerId = "server-selected",
                renderedHomeServerId = "server-rendered",
                scopeInitialServerId = "server-initial"
            )
        )
        assertEquals(
            "server-initial",
            appShellNavigationServerId(
                selectedServerId = " ",
                renderedHomeServerId = null,
                scopeInitialServerId = "server-initial"
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
    fun foregroundBannerProjectionSuppressesSystemMessage() {
        val banner =
            raftForegroundMessageBannerProjection(
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
            raftForegroundMessageBannerProjectionResult(
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
            raftForegroundMessageBannerProjectionResult(
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
    fun foregroundBannerVisibleHomeConversationIgnoresRetainedHomeStoreSelection() {
        val visibleConversation =
            appShellVisibleForegroundConversationKey(
                selectedTab = "settings",
                selectedHomeDestination = null,
                selectedConversationId =
                    appShellForegroundVisibleHomeConversationId(
                        homeShellSelectedConversationId = null,
                        homeStoreSelectedConversationId = "channel-1"
                    )
            )
        val banner =
            raftNotificationPushBannerOrNull(
                event =
                    NotificationPushReceived(
                        serverId = ServerId("server-1"),
                        kind = ConversationKind.Channel,
                        channelId = ChannelId("channel-1"),
                        messageId = MessageId("message-1"),
                        title = "#all - Raft",
                        body = "Ada: visible from settings",
                        tag = "message:message-1"
                    ),
                currentServerId = "server-1",
                visibleConversationKey = visibleConversation
            )

        val resolved = assertNotNull(banner)
        assertEquals("push:server-1:message-1", resolved.id)
        assertEquals("channel-1", resolved.conversation.threadId.value)
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
        assertEquals(RaftLucideIconName.MessageSquare, resolved.iconName)
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
            raftForegroundMessageBannerProjection(
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
            raftForegroundMessageBannerProjection(
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
        assertEquals(RaftThreadSurfaceKind.DirectMessage, resolved.openRequest.conversationIdentity.thread?.parentSurfaceKind)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, resolved.openRequest.conversationIdentity.thread?.parentChannelKind)

        val launchPayload =
            requireNotNull(buildAppShellThreadPayload(
                conversation = resolved.openRequest.conversationIdentity,
                anchorMessageId = resolved.openRequest.anchorMessageId,
                originFallback = resolved.openRequest.originFallback
            ))
        assertEquals(RaftThreadSurfaceKind.DirectMessage, launchPayload.parentSurfaceKind)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, launchPayload.parentChannelKind)
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
                raftForegroundMessageBannerProjectionResult(
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
            assertEquals(RaftThreadSurfaceKind.ReplyThread, projection.surfaceKind)
            assertEquals(ConversationKind.Thread, banner.conversation.kind)
            assertEquals(RaftForegroundMessageBannerIconKind.Channel, banner.iconKind)
            assertEquals(RaftLucideIconName.MessageSquare, banner.iconName)
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
    fun foregroundBannerContextThreadLabelsFromParentChannelRow() {
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
            raftForegroundMessageBannerProjection(
                serverId = "server-1",
                currentServerId = "server-1",
                message =
                    appShellForegroundTestMessage(
                        id = "reply-1",
                        channelId = "reply-stream",
                        threadId = "reply-stream",
                        conversationContext =
                            MessageConversationContext(
                                channelKind = ConversationKind.Thread,
                                parentMessageId = MessageId("parent-message"),
                                parentChannelId = ChannelId("parent-channel"),
                                parentChannelKind = ConversationKind.Channel
                            )
                    ),
                currentMemberId = "user-1",
                visibleConversationKey = null,
                conversationDirectory = directory
            )

        val resolved = assertNotNull(banner)
        assertEquals("#home-fixes - thread", resolved.context)
        // No parent-preview data without the stream row — the reply row stays absent
        // rather than faked.
        assertEquals(null, resolved.threadContext)
        assertEquals(RaftLucideIconName.MessageSquare, resolved.iconName)
        assertEquals(ConversationKind.Thread, resolved.openRequest.conversationIdentity.kind)
    }

    // task #437: same SSOT chain, DM-parented thread — the parent row's kind drives
    // the "@" prefix.
    @Test
    fun foregroundBannerContextThreadLabelsFromDmParentRow() {
        val directory =
            raftConversationDirectoryStateFromProjections(
                listOf(
                    SharedConversationDirectoryProjection(
                        serverId = "server-1",
                        canonicalChannelId = "dm-parent",
                        aliases = setOf("dm-parent"),
                        conversationKind = ConversationKind.DirectMessage,
                        surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                        channelKind = "dm",
                        title = "Cindy"
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
                        channelId = "dm-reply-stream",
                        threadId = "dm-reply-stream",
                        conversationContext =
                            MessageConversationContext(
                                channelKind = ConversationKind.Thread,
                                parentMessageId = MessageId("parent-message"),
                                parentChannelId = ChannelId("dm-parent"),
                                parentChannelKind = ConversationKind.DirectMessage
                            )
                    ),
                currentMemberId = "user-1",
                visibleConversationKey = null,
                conversationDirectory = directory
            )

        val resolved = assertNotNull(banner)
        assertEquals("@Cindy - thread", resolved.context)
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
            raftForegroundMessageBannerProjectionResult(
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
            raftForegroundMessageBannerProjectionResult(
                serverId = "server-1",
                currentServerId = "server-1",
                message = message,
                currentMemberId = "user-1",
                visibleConversationKey = null,
                conversationDirectory = enrichedDirectory
            )

        assertEquals(RaftThreadSurfaceKind.ReplyThread, enrichedResult.sharedProjection?.surfaceKind)
        assertEquals(ConversationKind.Thread, enrichedResult.resolvedKind)
        assertEquals("Original thread question", enrichedResult.banner?.threadContext)
        assertEquals("parent-channel", enrichedResult.banner?.conversation?.thread?.parentChannelId?.value)
        assertEquals("parent-message", enrichedResult.banner?.conversation?.thread?.parentMessageId?.value)
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
    fun activityReplyOpenCanResolveBeforeStreamHydrationAndPreservesOriginFallback() {
        // Reply stream identity may hydrate after navigation. The typed parent tuple is enough
        // to establish the route without inventing a stream id, and display fallback remains
        // separate from that identity.
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
                serverId = "server-1",
                conversationDirectory = SharedConversationDirectoryState()
            )

        val request = assertNotNull((resolution as? ConversationOpenResolution.Resolved)?.request)
        assertEquals(ConversationKind.Thread, request.conversationIdentity.kind)
        assertEquals("parent-channel", request.conversationIdentity.thread?.parentChannelId?.value)
        assertEquals("parent-message", request.conversationIdentity.thread?.parentMessageId?.value)
        assertEquals(null, request.conversationIdentity.thread?.streamChannelId)
        val originFallback = request.originFallback
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
        assertEquals(RaftThreadSurfaceKind.DirectMessage, resolved.conversationIdentity.thread?.parentSurfaceKind)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, resolved.conversationIdentity.thread?.parentChannelKind)

        val launchPayload =
            requireNotNull(buildAppShellThreadPayload(
                conversation = resolved.conversationIdentity,
                anchorMessageId = resolved.anchorMessageId,
                originFallback = resolved.originFallback
            ))
        assertEquals(RaftThreadSurfaceKind.DirectMessage, launchPayload.parentSurfaceKind)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, launchPayload.parentChannelKind)
    }

    @Test
    fun foregroundBannerOpensReplyThreadWhenParentKindUnresolved() {
        // #304/#307: a reply thread whose parent kind can't be resolved (parentChannelKind
        // absent) still carries a trustworthy source tuple — the banner is given and the
        // thread opens with the parent kind left null rather than being suppressed.
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
            raftForegroundMessageBannerProjectionResult(
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
        assertEquals("parent-channel", banner.conversation.thread?.parentChannelId?.value)
        assertEquals("parent-message", banner.conversation.thread?.parentMessageId?.value)
        assertNull(banner.conversation.thread?.parentSurfaceKind)
        assertNull(banner.conversation.thread?.parentChannelKind)
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
            raftForegroundMessageBannerProjectionResult(
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
        assertEquals(RaftThreadSurfaceKind.DirectMessage, banner.conversation.thread?.parentSurfaceKind)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, banner.conversation.thread?.parentChannelKind)
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
    fun activityDirectMessageOpenCarriesPeerIdentityIntoLoadingHeader() {
        val identity =
            MemberDetailIdentity(
                memberId = MemberId("agent-1"),
                kind = MemberKind.Agent,
                sourceServerId = ServerId("server-1")
            )
        val directory =
            raftConversationDirectoryStateFromProjections(
                listOf(
                    SharedConversationDirectoryProjection(
                        serverId = "server-1",
                        canonicalChannelId = "dm-1",
                        aliases = setOf("dm-1", "agent-1"),
                        conversationKind = ConversationKind.DirectMessage,
                        surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                        channelKind = "dm",
                        title = "CC-Wow2",
                        peerIdentity = identity
                    )
                )
            )
        val source =
            InboxItem(
                key = "dm:dm-1",
                kind = InboxItemKind.DirectMessage,
                channelId = ChannelId("dm-1"),
                channelName = "CC-Wow2"
            ).toConversationOpenSourceMessageIdentity()

        val resolution = resolveOpenRequest("server-1", source, directory)
        val request = assertNotNull((resolution as? ConversationOpenResolution.Resolved)?.request)
        val loadingPayload = assertNotNull(buildAppShellThreadPayload(request.conversationIdentity))

        assertEquals(identity, request.conversationIdentity.dmPeerIdentity)
        assertEquals(identity, loadingPayload.dmPeerIdentity)
        assertEquals(null, loadingPayload.dmPeerName)
        assertEquals(null, loadingPayload.dmPeerAvatarUrl)
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
        assertEquals(RaftThreadSurfaceKind.DirectMessage, request.conversationIdentity.thread?.parentSurfaceKind)
        assertEquals(RaftThreadSurfaceKind.DirectMessage, request.conversationIdentity.thread?.parentChannelKind)
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
                        title = "Legacy notification title",
                        body = "Legacy notification body",
                        tag = "message:reply-1",
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
        assertEquals("Thread in #home-fixes · Raft", resolved.title)
        assertEquals("", resolved.context)
        assertEquals("", resolved.inlineContextLabel())
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
    fun notificationPushBannerRejectsPeerJointReplyIdsInsteadOfRebuildingLocalProjection() {
        val directory =
            raftConversationDirectoryStateFromProjections(
                listOf(
                    SharedConversationDirectoryProjection(
                        serverId = "server-local",
                        canonicalChannelId = "parent-local",
                        aliases = setOf("parent-local", "parent-peer"),
                        conversationKind = ConversationKind.Joint,
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = "joint",
                        title = "raft-mobile-reconcile"
                    ),
                    SharedConversationDirectoryProjection(
                        serverId = "server-local",
                        canonicalChannelId = "thread-local",
                        aliases = setOf("thread-local"),
                        conversationKind = ConversationKind.Thread,
                        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                        channelKind = "thread",
                        title = "raft-mobile-reconcile",
                        parentChannelId = "parent-local",
                        parentMessageId = "parent-message"
                    )
                )
            )

        val suppressed = mutableListOf<String>()
        val banner =
            raftNotificationPushBannerOrNull(
                event =
                    NotificationPushReceived(
                        serverId = ServerId("server-local"),
                        kind = ConversationKind.Thread,
                        channelId = ChannelId("parent-peer"),
                        threadId = ChannelId("thread-local"),
                        parentChannelId = ChannelId("parent-peer"),
                        parentMessageId = MessageId("parent-message"),
                        messageId = MessageId("reply-peer"),
                        title = "Direct message · Raft",
                        body = "Cindy: hello",
                        tag = "message:reply-peer"
                    ),
                currentServerId = "server-local",
                visibleConversationKey = null,
                conversationDirectory = directory,
                onSuppressed = suppressed::add
            )

        assertNull(banner)
        assertEquals(listOf("identity_incomplete"), suppressed)
    }

    @Test
    fun resolveOpenRequestRejectsNotificationThreadAliasAcrossJointProjection() {
        val directory =
            raftConversationDirectoryStateFromProjections(
                listOf(
                    SharedConversationDirectoryProjection(
                        serverId = "server-local",
                        canonicalChannelId = "parent-local",
                        aliases = setOf("parent-local"),
                        conversationKind = ConversationKind.Joint,
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = "joint",
                        title = "raft-mobile-reconcile"
                    ),
                    SharedConversationDirectoryProjection(
                        serverId = "server-local",
                        canonicalChannelId = "thread-local",
                        aliases = setOf("thread-local", "thread-peer"),
                        conversationKind = ConversationKind.Thread,
                        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                        channelKind = "thread",
                        title = "raft-mobile-reconcile",
                        parentChannelId = "parent-local",
                        parentMessageId = "parent-message"
                    )
                )
            )
        val logs = mutableListOf<String>()

        val resolution =
            resolveOpenRequest(
                serverId = "server-local",
                sourceMessageIdentity =
                    AppShellNotificationThreadLaunchRequest(
                        threadId = "thread-peer",
                        messageId = "reply-peer",
                        parentChannelId = "parent-local",
                        parentMessageId = "parent-message",
                        conversationKind = ConversationKind.Thread
                    ).toConversationOpenSourceMessageIdentity(),
                conversationDirectory = directory,
                diagnosticsLogger = logs::add
            )

        val pending = assertNotNull(resolution as? ConversationOpenResolution.Pending)
        assertEquals("thread_projection_mismatch", pending.reason)
        val log = assertNotNull(logs.firstOrNull())
        assertTrue(log.contains("notificationThreadProjectionMismatch=true"), log)
        assertTrue(log.contains("pending:thread_projection_mismatch"), log)
    }

    @Test
    fun resolveOpenRequestKeepsActivityLocalParentWhenDirectoryThreadUsesStorageParent() {
        val directory =
            raftConversationDirectoryStateFromProjections(
                listOf(
                    SharedConversationDirectoryProjection(
                        serverId = "server-local",
                        canonicalChannelId = "joint-storage-parent",
                        aliases = setOf("joint-storage-parent", "joint-local-parent"),
                        conversationKind = ConversationKind.Joint,
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = "joint",
                        title = "joint-storage-parent"
                    ),
                    SharedConversationDirectoryProjection(
                        serverId = "server-local",
                        canonicalChannelId = "reply-stream",
                        aliases = setOf("reply-stream"),
                        conversationKind = ConversationKind.Thread,
                        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                        channelKind = "thread",
                        title = "joint-storage-parent",
                        parentChannelId = "joint-storage-parent",
                        parentMessageId = "parent-message"
                    )
                )
            )
        val logs = mutableListOf<String>()
        val source =
            InboxItem(
                key = "thread:reply-stream",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("reply-stream"),
                channelName = "member-visible-joint",
                threadChannelId = ChannelId("reply-stream"),
                parentChannelId = ChannelId("joint-local-parent"),
                parentChannelName = "member-visible-joint",
                parentChannelKind = ConversationKind.Joint,
                parentMessageId = MessageId("parent-message"),
                latestActivityMessageId = MessageId("reply-1")
            ).toConversationOpenSourceMessageIdentity()

        val resolution =
            resolveOpenRequest(
                serverId = "server-local",
                sourceMessageIdentity = source,
                conversationDirectory = directory,
                diagnosticsLogger = logs::add
            )

        val request = assertNotNull((resolution as? ConversationOpenResolution.Resolved)?.request)
        assertEquals(ConversationKind.Thread, request.conversationIdentity.kind)
        assertEquals("reply-stream", request.conversationIdentity.thread?.streamChannelId?.value)
        assertEquals("joint-local-parent", request.conversationIdentity.thread?.parentChannelId?.value)
        assertEquals("parent-message", request.conversationIdentity.thread?.parentMessageId?.value)
        assertFalse(
            request.conversationIdentity.thread?.parentChannelId?.value.orEmpty().startsWith("joint-storage-")
        )
        assertEquals("reply-1", request.anchorMessageId)
        val launchPayload =
            assertNotNull(
                buildAppShellThreadPayload(
                    conversation = request.conversationIdentity,
                    anchorMessageId = request.anchorMessageId,
                    originFallback = request.originFallback
                )
            )
        assertEquals("reply-stream", launchPayload.sourceThreadId)
        assertEquals("reply-stream", launchPayload.streamChannelId)
        assertEquals("joint-local-parent", launchPayload.parentChannelId)
        assertEquals("parent-message", launchPayload.parentMessageId)
        assertEquals("joint-local-parent", request.originFallback?.parentChannelId)
        assertEquals("parent-message", request.originFallback?.parentMessageId)
        val log = assertNotNull(logs.firstOrNull())
        assertTrue(log.contains("activityThreadProjectionMismatch=true"), log)
        assertTrue(log.contains("result=resolved:Thread"), log)
    }

    @Test
    fun resolveOpenRequestOpensMismatchedActivityWithoutTypedSourceParentKind() {
        val directory =
            raftConversationDirectoryStateFromProjections(
                listOf(
                    SharedConversationDirectoryProjection(
                        serverId = "server-local",
                        canonicalChannelId = "joint-storage-parent",
                        aliases = setOf("joint-storage-parent", "joint-local-parent"),
                        conversationKind = ConversationKind.Joint,
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = "joint",
                        title = "joint-storage-parent"
                    ),
                    SharedConversationDirectoryProjection(
                        serverId = "server-local",
                        canonicalChannelId = "reply-stream",
                        aliases = setOf("reply-stream"),
                        conversationKind = ConversationKind.Thread,
                        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                        channelKind = "thread",
                        title = "joint-storage-parent",
                        parentChannelId = "joint-storage-parent",
                        parentMessageId = "parent-message"
                    )
                )
            )
        val logs = mutableListOf<String>()
        val source =
            InboxItem(
                key = "thread:reply-stream",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("reply-stream"),
                channelName = "member-visible-joint",
                threadChannelId = ChannelId("reply-stream"),
                parentChannelId = ChannelId("joint-local-parent"),
                parentChannelName = "member-visible-joint",
                // task #304: even though the mismatched directory cannot supply the
                // missing parent kind, the source tuple alone is trusted to open.
                parentChannelKind = null,
                parentMessageId = MessageId("parent-message"),
                latestActivityMessageId = MessageId("reply-1")
            ).toConversationOpenSourceMessageIdentity()

        val resolution =
            resolveOpenRequest(
                serverId = "server-local",
                sourceMessageIdentity = source,
                conversationDirectory = directory,
                diagnosticsLogger = logs::add
            )

        val request = assertNotNull((resolution as? ConversationOpenResolution.Resolved)?.request)
        assertEquals(ConversationKind.Thread, request.conversationIdentity.kind)
        val thread = assertNotNull(request.conversationIdentity.thread)
        assertEquals("joint-local-parent", thread.parentChannelId.value)
        assertEquals("parent-message", thread.parentMessageId.value)
        assertNull(thread.parentSurfaceKind)
        assertNull(thread.parentChannelKind)
        assertEquals("reply-1", request.anchorMessageId)
        val launchPayload =
            assertNotNull(
                buildAppShellThreadPayload(
                    conversation = request.conversationIdentity,
                    anchorMessageId = request.anchorMessageId,
                    originFallback = request.originFallback
                )
            )
        assertEquals("reply-stream", launchPayload.sourceThreadId)
        assertEquals("reply-stream", launchPayload.streamChannelId)
        assertEquals("joint-local-parent", launchPayload.parentChannelId)
        assertEquals("parent-message", launchPayload.parentMessageId)
        val log = assertNotNull(logs.firstOrNull())
        assertTrue(log.contains("activityThreadProjectionMismatch=true"), log)
        assertTrue(log.contains("result=resolved:Thread"), log)
    }

    @Test
    fun resolveOpenRequestRefusesActivityRowWhoseOwnTypedJointParentIsInternalStorage() {
        val unsafe =
            InboxItem(
                key = "thread:reply-stream",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("reply-stream"),
                channelName = "joint-storage-parent",
                threadChannelId = ChannelId("reply-stream"),
                parentChannelId = ChannelId("joint-storage-parent"),
                parentChannelName = "joint-storage-parent",
                parentChannelKind = ConversationKind.Joint,
                parentMessageId = MessageId("parent-message"),
                latestActivityMessageId = MessageId("reply-1")
            )
        val logs = mutableListOf<String>()

        val resolution =
            resolveOpenRequest(
                serverId = "server-local",
                sourceMessageIdentity = unsafe.toConversationOpenSourceMessageIdentity(),
                conversationDirectory = SharedConversationDirectoryState(),
                diagnosticsLogger = logs::add
            )

        val pending = assertNotNull(resolution as? ConversationOpenResolution.Pending)
        assertEquals("activity_internal_parent_identity", pending.reason)
        val log = assertNotNull(logs.firstOrNull())
        assertTrue(log.contains("activityInternalJointParentSource=true"), log)
        assertTrue(log.contains("pending:activity_internal_parent_identity"), log)
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
    fun resolveOpenRequestRejectsLocalThreadWithDifferentParentMessage() {
        val directory =
            raftConversationDirectoryStateFromProjections(
                listOf(
                    SharedConversationDirectoryProjection(
                        serverId = "server-local",
                        canonicalChannelId = "thread-local",
                        aliases = setOf("thread-local"),
                        conversationKind = ConversationKind.Thread,
                        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                        channelKind = "thread",
                        title = "joint-local",
                        parentChannelId = "parent-local",
                        parentMessageId = "parent-message-local"
                    )
                )
            )

        val resolution =
            resolveOpenRequest(
                serverId = "server-local",
                sourceMessageIdentity =
                    AppShellNotificationThreadLaunchRequest(
                        threadId = "thread-local",
                        messageId = "reply-local",
                        parentChannelId = "parent-local",
                        parentMessageId = "parent-message-peer",
                        conversationKind = ConversationKind.Thread
                    ).toConversationOpenSourceMessageIdentity(),
                conversationDirectory = directory
            )

        val pending = assertNotNull(resolution as? ConversationOpenResolution.Pending)
        assertEquals("thread_projection_mismatch", pending.reason)
    }

    @Test
    fun notificationPushBannerAcceptsCompleteLocalJointReplyTupleAndServerTitle() {
        val directory =
            raftConversationDirectoryStateFromProjections(
                listOf(
                    SharedConversationDirectoryProjection(
                        serverId = "server-local",
                        canonicalChannelId = "parent-local",
                        aliases = setOf("parent-local"),
                        conversationKind = ConversationKind.Joint,
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = "joint",
                        title = "raft-mobile-reconcile"
                    ),
                    SharedConversationDirectoryProjection(
                        serverId = "server-local",
                        canonicalChannelId = "thread-local",
                        aliases = setOf("thread-local"),
                        conversationKind = ConversationKind.Thread,
                        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                        channelKind = "thread",
                        title = "raft-mobile-reconcile",
                        parentChannelId = "parent-local",
                        parentMessageId = "parent-message"
                    )
                )
            )

        val banner =
            raftNotificationPushBannerOrNull(
                event =
                    NotificationPushReceived(
                        serverId = ServerId("server-local"),
                        kind = ConversationKind.Thread,
                        threadId = ChannelId("thread-local"),
                        parentChannelId = ChannelId("parent-local"),
                        parentMessageId = MessageId("parent-message"),
                        messageId = MessageId("reply-local"),
                        title = "Thread in #raft-mobile-reconcile · Raft",
                        body = "Cindy: hello",
                        tag = "message:reply-local"
                    ),
                currentServerId = "server-local",
                visibleConversationKey = null,
                conversationDirectory = directory
            )

        val resolved = assertNotNull(banner)
        assertEquals("thread-local", resolved.conversation.thread?.streamChannelId?.value)
        assertEquals("parent-local", resolved.conversation.thread?.parentChannelId?.value)
        assertEquals("parent-message", resolved.conversation.thread?.parentMessageId?.value)
        assertEquals("Thread in #raft-mobile-reconcile · Raft", resolved.title)
    }

    @Test
    fun resolveOpenRequestKeepsColdTypedForegroundThreadRawOpenWithEmptyDirectory() {
        val resolution =
            resolveOpenRequest(
                serverId = "server-local",
                sourceMessageIdentity =
                    ConversationOpenSourceMessageIdentity(
                        source = ConversationOpenSource.ForegroundBanner,
                        messageId = "reply-local",
                        channelId = "thread-local",
                        threadChannelId = "thread-local",
                        parentChannelId = "parent-local",
                        parentMessageId = "parent-message",
                        conversationKind = ConversationKind.Thread,
                        parentConversationKind = ConversationKind.Joint
                    )
            )

        val request = assertNotNull((resolution as? ConversationOpenResolution.Resolved)?.request)
        assertEquals("thread-local", request.conversationIdentity.thread?.streamChannelId?.value)
        assertEquals("parent-local", request.conversationIdentity.thread?.parentChannelId?.value)
        assertEquals("parent-message", request.conversationIdentity.thread?.parentMessageId?.value)
        assertEquals(ConversationKind.Joint, request.conversationIdentity.resolveThreadRouteIdentity().parentConversationKind)
    }

    @Test
    fun notificationPushBannerDoesNotNormalizeReplyAcrossMismatchedParent() {
        val directory =
            raftConversationDirectoryStateFromProjections(
                listOf(
                    SharedConversationDirectoryProjection(
                        serverId = "server-local",
                        canonicalChannelId = "parent-local-a",
                        aliases = setOf("parent-local-a", "parent-peer"),
                        conversationKind = ConversationKind.Joint,
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = "joint",
                        title = "joint-a"
                    ),
                    SharedConversationDirectoryProjection(
                        serverId = "server-local",
                        canonicalChannelId = "parent-local-b",
                        aliases = setOf("parent-local-b"),
                        conversationKind = ConversationKind.Joint,
                        surfaceKind = RaftThreadSurfaceKind.Channel,
                        channelKind = "joint",
                        title = "joint-b"
                    ),
                    SharedConversationDirectoryProjection(
                        serverId = "server-local",
                        canonicalChannelId = "thread-local",
                        aliases = setOf("thread-local"),
                        conversationKind = ConversationKind.Thread,
                        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                        channelKind = "thread",
                        title = "joint-b",
                        parentChannelId = "parent-local-b",
                        parentMessageId = "parent-message"
                    )
                )
            )
        val suppressed = mutableListOf<String>()

        val banner =
            raftNotificationPushBannerOrNull(
                event =
                    NotificationPushReceived(
                        serverId = ServerId("server-local"),
                        kind = ConversationKind.Thread,
                        threadId = ChannelId("thread-peer"),
                        parentChannelId = ChannelId("parent-peer"),
                        parentMessageId = MessageId("parent-message"),
                        messageId = MessageId("reply-peer"),
                        title = "Thread",
                        body = "hello",
                        tag = "message:reply-peer"
                    ),
                currentServerId = "server-local",
                visibleConversationKey = null,
                conversationDirectory = directory,
                onSuppressed = suppressed::add
            )

        assertNull(banner)
        assertEquals(listOf("identity_incomplete"), suppressed)
    }

    @Test
    fun notificationPushBannerSuppressedForOtherServerAndVisibleConversation() {
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

        assertNull(crossServer)
        assertEquals(listOf("server_mismatch"), suppressed)

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

    @Test
    fun notificationPushBannerSuppressesTheVisibleThreadRouteIdentity() {
        val event =
            NotificationPushReceived(
                serverId = ServerId("server-1"),
                kind = ConversationKind.Thread,
                threadId = ChannelId("reply-stream"),
                parentChannelId = ChannelId("parent-channel"),
                parentMessageId = MessageId("parent-message"),
                messageId = MessageId("reply-1"),
                title = "#home-fixes · Thread",
                body = "Ada: visible reply",
                tag = "message:reply-1"
            )
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
                    ),
                    SharedConversationDirectoryProjection(
                        serverId = "server-1",
                        canonicalChannelId = "reply-stream",
                        aliases = setOf("reply-stream", "parent-message"),
                        conversationKind = ConversationKind.Thread,
                        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                        channelKind = "thread",
                        title = "home-fixes",
                        parentChannelId = "parent-channel",
                        parentMessageId = "parent-message"
                    )
                )
            )
        val visibleRoute =
            assertNotNull(
                raftNotificationPushBannerOrNull(
                    event = event,
                    currentServerId = "server-1",
                    visibleConversationKey = null,
                    conversationDirectory = directory
                )
            ).conversation.resolveThreadRouteIdentity()
        val suppressed = mutableListOf<String>()

        val banner =
            raftNotificationPushBannerOrNull(
                event = event,
                currentServerId = "server-1",
                visibleConversationKey = null,
                visibleRouteIdentity = visibleRoute,
                conversationDirectory = directory,
                onSuppressed = suppressed::add
            )

        assertNull(banner)
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

    // task #441 companion: the source does not carry a typed parent kind, but the parent
    // directory row is authoritative and may qualify it without claiming the child identity.
    @Test
    fun resolveOpenRequestThreadSourceUsesAuthoritativeParentDirectoryKind() {
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
                        // parentChannelKind intentionally omitted (null).
                        parentMessageId = MessageId("parent-message"),
                        latestActivityMessageId = MessageId("reply-1")
                    ).toConversationOpenSourceMessageIdentity(),
                conversationDirectory = directory
            )

        val request = assertNotNull((resolution as? ConversationOpenResolution.Resolved)?.request)
        assertEquals(ConversationKind.Thread, request.conversationIdentity.kind)
        assertEquals("parent-channel", request.conversationIdentity.thread?.parentChannelId?.value)
        assertEquals("parent-message", request.conversationIdentity.thread?.parentMessageId?.value)
        assertEquals("reply-stream", request.conversationIdentity.thread?.streamChannelId?.value)
        assertEquals(ConversationKind.Channel, request.conversationIdentity.resolveThreadRouteIdentity().parentConversationKind)
    }

    // task #441 notification source (三源合一): a thread notification's channelId can name
    // the PARENT channel. The self-lookup hit on that channel row must be refused for a
    // thread-shaped source (parentMessageId present), and with no typed parent kind the
    // resolution is an honest ineligible Pending — the notification must not open the
    // parent channel stream.
    @Test
    fun resolveOpenRequestNotificationThreadShapeRefusesParentChannelIdentity() {
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
                    AppShellNotificationThreadLaunchRequest(
                        threadId = "reply-stream",
                        messageId = "reply-1",
                        channelId = "parent-channel",
                        parentChannelId = "parent-channel",
                        parentMessageId = "parent-message"
                    ).toConversationOpenSourceMessageIdentity(),
                conversationDirectory = directory,
                diagnosticsLogger = logs::add
            )

        val pending = assertNotNull(resolution as? ConversationOpenResolution.Pending)
        assertEquals("thread_identity_projection_ineligible", pending.reason)
        val log = assertNotNull(logs.firstOrNull())
        assertTrue(log.contains("hitVia=parent-channel"), log)
        assertTrue(log.contains("hitEligible=false"), log)
    }

    @Test
    fun resolveOpenRequestNotificationThreadUsesAuthoritativeParentDirectoryKind() {
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
        val resolution =
            resolveOpenRequest(
                serverId = "server-1",
                sourceMessageIdentity =
                    AppShellNotificationThreadLaunchRequest(
                        threadId = "reply-stream",
                        messageId = "reply-1",
                        channelId = "reply-stream",
                        parentChannelId = "parent-channel",
                        parentMessageId = "parent-message",
                        conversationKind = ConversationKind.Thread
                    ).toConversationOpenSourceMessageIdentity(),
                conversationDirectory = directory
            )

        val request = assertNotNull((resolution as? ConversationOpenResolution.Resolved)?.request)
        assertEquals(ConversationKind.Thread, request.conversationIdentity.kind)
        assertEquals("parent-channel", request.conversationIdentity.thread?.parentChannelId?.value)
        assertEquals("parent-message", request.conversationIdentity.thread?.parentMessageId?.value)
        assertEquals("reply-stream", request.conversationIdentity.thread?.streamChannelId?.value)
        assertEquals("reply-1", request.anchorMessageId)
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
    fun foregroundBannerProjectionUsesSafeChannelNameInsteadOfId() {
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
                        title = "home-fixes"
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
        assertEquals("#home-fixes", resolved.context)
    }

    @Test
    fun foregroundBannerProjectionUsesAvatarForDirectMessage() {
        val directory =
            raftConversationDirectoryStateFromProjections(
                listOf(
                    SharedConversationDirectoryProjection(
                        serverId = "server-1",
                        canonicalChannelId = "dm-1",
                        aliases = setOf("dm-1", "agent-1"),
                        conversationKind = ConversationKind.DirectMessage,
                        surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                        channelKind = "directmessage",
                        title = "Cindy",
                        avatarWireValue = "pixel:robot",
                        avatarLabel = "CI",
                        peerIdentity =
                            MemberDetailIdentity(
                                memberId = MemberId("agent-1"),
                                kind = MemberKind.Agent,
                                sourceServerId = ServerId("server-1")
                            )
                    )
                )
            )
        val banner =
            raftForegroundMessageBannerProjection(
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
        assertEquals(RaftForegroundMessageBannerIconKind.DirectMessage, resolved.iconKind)
        assertNull(resolved.iconName)
        assertEquals("C", resolved.avatarLabel)
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
    fun foregroundBannerProjectionCarriesReplyThreadRouteIdentityOnly() {
        // t434 migration (criterion (a)): route-identity carriage needs a PROVEN reply
        // thread — the fixture supplies the thread + parent directory evidence the raw
        // guess used to fake; the route assertions are unchanged.
        val replyDirectory =
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
                            latestActivityMessageId = MessageId("reply-1")
                        )
                    )
            ).toSharedConversationDirectoryState()
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
                conversationDirectory = replyDirectory
            )

        val resolved = assertNotNull(banner)
        assertEquals(ConversationKind.Thread, resolved.conversation.kind)
        assertEquals("parent-channel", resolved.conversation.thread?.parentChannelId?.value)
        assertEquals("parent-message", resolved.conversation.thread?.parentMessageId?.value)
        assertEquals("reply-stream", resolved.conversation.thread?.streamChannelId?.value)
        assertEquals("reply-1", resolved.focusedMessageId)
        assertEquals(RaftThreadSurfaceKind.Channel, resolved.openRequest.conversationIdentity.thread?.parentSurfaceKind)
        assertEquals(null, resolved.openRequest.conversationIdentity.channelType)

        val suppressed =
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
                visibleRouteIdentity = resolved.conversation.resolveThreadRouteIdentity(),
                conversationDirectory = replyDirectory
            )

        // Proven banner, visible route: suppressed for visibility (not conversation_missing).
        assertNull(suppressed)
    }

    @Test
    fun appShellAppearanceGenerationIncludesInitialAndResumeAppearances() {
        assertEquals(
            0,
            appShellAppearanceGeneration(pageVisibleTick = 0, initialAuthRestoreTick = 0)
        )
        assertEquals(
            1,
            appShellAppearanceGeneration(pageVisibleTick = 0, initialAuthRestoreTick = 1)
        )
        assertEquals(
            2,
            appShellAppearanceGeneration(pageVisibleTick = 1, initialAuthRestoreTick = 1)
        )
    }

    @Test
    fun appShellInitialAppearanceRefreshesOnlyRetainedHomeContent() {
        assertFalse(
            appShellAppearanceShouldRefresh(
                appearanceGeneration = 0,
                pageVisibleTick = 0,
                selectedTab = "home",
                selectedHomeDestination = null,
                hasRetainedHomeContent = true
            )
        )
        assertTrue(
            appShellAppearanceShouldRefresh(
                appearanceGeneration = 1,
                pageVisibleTick = 0,
                selectedTab = "home",
                selectedHomeDestination = null,
                hasRetainedHomeContent = true
            )
        )
        assertFalse(
            appShellAppearanceShouldRefresh(
                appearanceGeneration = 1,
                pageVisibleTick = 0,
                selectedTab = "home",
                selectedHomeDestination = null,
                hasRetainedHomeContent = false
            )
        )
        assertFalse(
            appShellAppearanceShouldRefresh(
                appearanceGeneration = 1,
                pageVisibleTick = 0,
                selectedTab = "tasks",
                selectedHomeDestination = null,
                hasRetainedHomeContent = true
            )
        )
        assertFalse(
            appShellAppearanceShouldRefresh(
                appearanceGeneration = 1,
                pageVisibleTick = 0,
                selectedTab = "home",
                selectedHomeDestination = "activity",
                hasRetainedHomeContent = true
            )
        )
        assertTrue(
            appShellAppearanceShouldRefresh(
                appearanceGeneration = 2,
                pageVisibleTick = 1,
                selectedTab = "tasks",
                selectedHomeDestination = null,
                hasRetainedHomeContent = false
            )
        )
    }

    @Test
    fun rootLifecycleRefreshCoversActivityButNotSelfRefreshingDestinations() {
        assertEquals(true, homeDestinationRefreshesOnLifecycleResume(null))
        // task #383: the Activity destination does not self-refresh on app
        // foreground, so the shell resume path must cover it.
        assertEquals(true, homeDestinationRefreshesOnLifecycleResume("activity"))
        assertEquals(false, homeDestinationRefreshesOnLifecycleResume("saved"))
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
        val legacyPageData = authChooseServerPageData()
        val shellPageData =
            authChooseServerPageData(
                fromShell = true,
                storeScope = RaftShellStoreScope.create("shell-server-picker")
            )

        assertEquals(false, legacyPageData.optBoolean("slockChooseServerFromShell", false))
        assertEquals(false, authChooseServerUsesScopedStore(legacyPageData))
        assertEquals(true, shellPageData.optBoolean("slockChooseServerFromShell", false))
        assertEquals(true, authChooseServerUsesScopedStore(shellPageData))
    }

    @Test
    fun authChooseServerRouteCarriesOriginatingDocumentIdentity() {
        val pageData =
            authChooseServerPageData(
                fromShell = true,
                storeScope = RaftShellStoreScope.create("server-shell:server-a", "server-a"),
                documentShell = true
            )

        assertEquals("server-shell:server-a", pageData.optString("slockShellInstanceId"))
        assertEquals("server-a", pageData.optString(RAFT_SHELL_SERVER_ID))
        assertEquals("", pageData.optString("serverId"))
        assertEquals(true, pageData.optBoolean("slockDocumentShell", false))
    }

    @Test
    fun legacyDocumentChooserCrossServerSelectionKeepsSingleShellIdentity() {
        val pageData =
            appShellPageDataForServerSelection(
                sourceStoreScope = RaftShellStoreScope.create("server-shell:server-a", "server-a"),
                selectedServerId = "server-b",
                suppressRestoreLanding = true
            )

        assertEquals("server-b", pageData.optString(RAFT_SHELL_SERVER_ID))
        assertEquals("", pageData.optString("serverId"))
        assertEquals("server-shell:server-a", pageData.optString("slockShellInstanceId"))
        assertEquals(false, pageData.optBoolean("slockDocumentShell", false))
        assertEquals(true, pageData.optBoolean("slockReplaceCurrentPage", false))
    }

    @Test
    fun enabledMultiShellCrossServerSelectionRequestsNewDocumentShell() {
        val routeOwner =
            AuthenticatedRouteOwner(
                environmentId = "https://api.test/",
                accountId = "user-1",
                serverId = "server-b",
            ).normalizedOrNull()
        val pageData =
            appShellPageDataForServerSelection(
                sourceStoreScope = RaftShellStoreScope.create("android-default-app-shell", "server-a"),
                selectedServerId = "server-b",
                multiShellHostEnabled = true,
                suppressRestoreLanding = true,
                routeOwner = routeOwner
            )

        assertEquals("server-b", pageData.optString(RAFT_SHELL_SERVER_ID))
        assertEquals("", pageData.optString("slockShellInstanceId"))
        assertEquals(true, pageData.optBoolean("slockDocumentShell", false))
        assertEquals("https://api.test", pageData.optString("slockRouteEnvironmentId"))
        assertEquals("user-1", pageData.optString("slockRouteAccountId"))
        assertEquals("server-b", pageData.optString("slockRouteServerId"))
        assertEquals(true, pageData.optBoolean("slockReplaceCurrentPage", false))
    }

    @Test
    fun enabledMultiShellCrossServerSelectionWithoutOwnerKeepsSourceShell() {
        val pageData =
            appShellPageDataForServerSelection(
                sourceStoreScope = RaftShellStoreScope.create("android-default-app-shell", "server-a"),
                selectedServerId = "server-b",
                multiShellHostEnabled = true,
                suppressRestoreLanding = true
            )

        assertEquals("server-b", pageData.optString(RAFT_SHELL_SERVER_ID))
        assertEquals("android-default-app-shell", pageData.optString("slockShellInstanceId"))
        assertEquals(false, pageData.optBoolean("slockDocumentShell", false))
        assertEquals("", pageData.optString("slockRouteEnvironmentId"))
        assertEquals(true, pageData.optBoolean("slockReplaceCurrentPage", false))
    }

    @Test
    fun enabledMultiShellSameServerSelectionReusesCurrentDocumentShell() {
        val pageData =
            appShellPageDataForServerSelection(
                sourceStoreScope = RaftShellStoreScope.create("server-shell:server-a", "server-a"),
                sourceDocumentShell = true,
                selectedServerId = "server-a",
                multiShellHostEnabled = true
            )

        assertEquals("server-shell:server-a", pageData.optString("slockShellInstanceId"))
        assertEquals(true, pageData.optBoolean("slockDocumentShell", false))
    }

    @Test
    fun sameServerLegacyDocumentSelectionDisablesDocumentMode() {
        val pageData =
            appShellPageDataForServerSelection(
                sourceStoreScope = RaftShellStoreScope.create("server-shell:server-a", "server-a"),
                selectedServerId = "server-a"
            )

        assertEquals("server-a", pageData.optString(RAFT_SHELL_SERVER_ID))
        assertEquals("", pageData.optString("serverId"))
        assertEquals("server-shell:server-a", pageData.optString("slockShellInstanceId"))
        assertEquals(false, pageData.optBoolean("slockDocumentShell", false))
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
    fun postAuthRouteUsesAppShellGateForPendingProfileBeforeServerChoice() {
        val destination =
            postAuthRouteDestination(
                StoredAuthSession(
                    user = StoredAuthUser(
                        id = "user-1",
                        email = "artin@raft.build",
                        emailVerified = true,
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
    fun postAuthRouteUsesServerChooserForCompletedProfileWithoutServer() {
        val destination =
            postAuthRouteDestination(
                StoredAuthSession(
                    user =
                        StoredAuthUser(
                            id = "user-1",
                            email = "artin@raft.build",
                            emailVerified = true,
                            profileSetupCompletedAt = "2026-07-21T00:00:00Z"
                        ),
                    accessToken = "access",
                    refreshToken = "refresh"
                )
            )

        assertEquals(AUTH_CHOOSE_SERVER_PAGE, destination.pageName)
        assertEquals(false, destination.pageData.optBoolean("slockSuppressRestoreLanding", false))
        assertEquals(RaftShellStoreScope.Legacy.shellInstanceId, destination.pageData.optString(RAFT_SHELL_INSTANCE_ID))
        assertEquals(false, destination.pageData.optBoolean(AUTH_CHOOSE_SERVER_FROM_SHELL, false))
        assertTrue(authChooseServerUsesScopedStore(destination.pageData))
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
                message = "Sign in to continue."
            )

        assertEquals(LOGIN_PAGE, pageData.optString("pageName"))
        assertEquals(true, pageData.optBoolean("slockReplaceCurrentPage", false))
        assertEquals("Sign in to continue.", pageData.authRouteMessage())
        assertEquals(false, pageData.has(AUTH_SOCIAL_PROVIDERS_KEY))
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
    fun authLossTerminalRevisionRejectsSuccessorInstalledAfterSnapshotBeforeEffect() = runBlocking {
        val terminalSource = AppShellTerminalRevisionTestSource()
        val admitted = terminalSource.readAuthSessionTerminalSnapshot()
        val captured =
            StoredAuthSession(
                user = StoredAuthUser(id = "user-a", email = "a@example.com"),
                accessToken = "access-a",
                refreshToken = "refresh-a",
            )
        var resetCount = 0
        var signedOutCount = 0

        terminalSource.installSuccessor()
        val committedGeneration =
            appShellRunIfAuthSessionTerminalOrAuthorityRevision(
                terminalSource = terminalSource,
                expectedGeneration = admitted.generation,
                expectedSession = captured,
            ) {
                resetCount += 1
                signedOutCount += 1
            }

        assertNull(committedGeneration)
        assertEquals(0, resetCount)
        assertEquals(0, signedOutCount)
        assertNotNull(terminalSource.readAuthSessionTerminalSnapshot().session)
        Unit
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
    fun authLossTerminalCommitReturnsItsOwnGenerationWhenSuccessorLandsBeforeReturn() = runBlocking {
        val captured =
            StoredAuthSession(
                user = StoredAuthUser(id = "user-a", email = "a@example.com"),
                accessToken = "access-a",
                refreshToken = "refresh-a",
            )
        val terminalSource = AppShellTerminalRevisionTestSource(initialSession = captured)
        val admitted = terminalSource.readAuthSessionTerminalSnapshot()
        terminalSource.installSuccessorBeforeNextCommitReturns()
        var firstTerminalEffectCount = 0

        val acceptedGeneration =
            appShellRunIfAuthSessionTerminalOrAuthorityRevision(
                terminalSource = terminalSource,
                expectedGeneration = admitted.generation,
                expectedSession = captured,
            ) {
                firstTerminalEffectCount += 1
            }

        val successorSnapshot = terminalSource.readAuthSessionTerminalSnapshot()
        val successor = assertNotNull(successorSnapshot.session)
        assertNotNull(acceptedGeneration)
        assertEquals(admitted.generation + 1, acceptedGeneration)
        assertEquals(admitted.generation + 2, successorSnapshot.generation)
        assertEquals(1, firstTerminalEffectCount)

        var successorTerminalEffectCount = 0
        val successorAcceptedGeneration =
            appShellRunIfAuthSessionTerminalOrAuthorityRevision(
                terminalSource = terminalSource,
                expectedGeneration = successorSnapshot.generation,
                expectedSession = successor,
            ) {
                successorTerminalEffectCount += 1
            }

        assertNotNull(successorAcceptedGeneration)
        assertEquals(successorSnapshot.generation + 1, successorAcceptedGeneration)
        assertEquals(1, successorTerminalEffectCount)
        assertNull(terminalSource.readAuthSessionTerminalSnapshot().session)
    }

    @Test
    fun createServerAuthorityDriftUsesLocalizedStaticMessage() {
        assertTrue(
            serverGateActionFailureMessage("__create__", AuthAuthorityChangedMessage) is
                AuthGateMessage.Static
        )
        assertTrue(
            serverGateActionFailureMessage("__create__", "network_timeout") is
                AuthGateMessage.Dynamic
        )
        assertTrue(
            serverGateActionFailureMessage("server-1", AuthAuthorityChangedMessage) is
                AuthGateMessage.Dynamic
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

    fun installSuccessorBeforeNextCommitReturns() {
        installSuccessorBeforeCommitReturns = true
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
