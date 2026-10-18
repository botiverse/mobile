package build.raft.app

import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.HomeServerDrawerLoadStatus
import build.raft.app.data.HomeServerDrawerConversationSection
import build.raft.app.data.HomeServerDrawerNormalizedContent
import build.raft.app.data.HomeServerDrawerNormalizedRow
import build.raft.app.data.HomeServerDrawerSectionState
import build.raft.app.data.HomeServerDrawerState
import build.raft.app.data.HomeServerActivityUnreadProjection
import build.raft.app.data.ReadReceiptOwner
import build.raft.app.data.homeServerDrawerNormalizedContentForSnapshot
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.data.ScreenError
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.APP_UPDATE_HOME_NOTIFICATION_ID
import build.raft.app.data.TasksViewMode
import build.raft.app.data.toHomeDirectMessageRow
import build.raft.app.model.AgentStatus
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SlockHomeComponentsTest {
    private val errorStrings = englishErrorPresentationStringsForTest()

    @Test
    fun homeServerIdentityChevronUsesCompactNameBoundSpacing() {
        assertEquals(4.dp, HomeServerIdentityNameChevronGap)
        assertEquals(18.dp, HomeServerIdentityChevronSize)
    }

    @Test
    fun homeTitlebarAndHostedDrawerShareAppShellSafeAreaAndPageHeaderHeight() {
        assertEquals(SlockSize.PageHeaderHeight, homeShellHeaderHeight(0.dp))
        assertEquals(86.dp, homeShellHeaderHeight(24.dp))

        val appShellSafeArea = 24.dp
        val laterResolvedSafeArea = 22.dp
        val sharedSafeArea =
            homeShellTopSafeArea(
                applyTopSafeArea = true,
                resolvedTopSafeArea = laterResolvedSafeArea,
                topSafeAreaOverride = appShellSafeArea,
            )
        assertEquals(appShellSafeArea, sharedSafeArea)
        assertEquals(86.dp, homeShellHeaderHeight(sharedSafeArea))
        assertEquals(
            laterResolvedSafeArea,
            homeShellTopSafeArea(
                applyTopSafeArea = true,
                resolvedTopSafeArea = laterResolvedSafeArea,
                topSafeAreaOverride = null,
            ),
        )
        assertEquals(
            0.dp,
            homeShellTopSafeArea(
                applyTopSafeArea = false,
                resolvedTopSafeArea = laterResolvedSafeArea,
                topSafeAreaOverride = null,
            ),
        )
    }

    @Test
    fun homeServerDrawerHeaderSpinsOnlyWhileLoadingOrRefreshing() {
        listOf(HomeServerDrawerLoadStatus.Loading, HomeServerDrawerLoadStatus.Refreshing).forEach { status ->
            assertTrue(homeServerDrawerHeaderSpinning(status))
        }

        listOf(
            HomeServerDrawerLoadStatus.Unloaded,
            HomeServerDrawerLoadStatus.Cached,
            HomeServerDrawerLoadStatus.Loaded,
            HomeServerDrawerLoadStatus.Empty,
            HomeServerDrawerLoadStatus.Unavailable,
            HomeServerDrawerLoadStatus.ErrorWithCache,
            HomeServerDrawerLoadStatus.ErrorEmpty,
        ).forEach { status ->
            assertFalse(homeServerDrawerHeaderSpinning(status))
        }
    }

    @Test
    fun homeInlineAndDrawerErrorsKeepDiagnosticCodesOutOfVisibleCopy() {
        val inlineError =
            SlockHomeInlineError(
                title = "Could not refresh Home",
                detail = "The last workspace remains available.",
                code = "home_network",
                actionLabel = "Retry"
            )
        val inlineVisibleCopy = inlineError.toVisibleCopy()
        val drawerError = SlockHomeServerDrawerRow.Error("server-1", "home_network", hasCache = true)

        assertEquals("home_network", inlineError.code)
        assertEquals("Could not refresh Home", inlineVisibleCopy.title)
        assertEquals("The last workspace remains available.", inlineVisibleCopy.detail)
        assertEquals("Retry", inlineVisibleCopy.actionLabel)
        assertFalse("home_network" in inlineVisibleCopy.toString())
        assertEquals("home_network", drawerError.code)
        assertEquals("Retry", drawerError.visibleRetryLabel("Retry"))
        assertFalse("home_network" in drawerError.visibleRetryLabel("Retry"))
    }

    @Test
    fun directMessageOpenFailureKeepsContextAndShowsTheServerReason() {
        assertEquals(
            "Could not open direct message: The member is no longer available",
            homeDirectMessageOpenFailureMessage(
                prefix = "Could not open direct message",
                error =
                    ScreenError(
                        code = "http_409",
                        serverMessage = "The member is no longer available"
                    ),
                errorStrings = errorStrings
            )
        )
    }

    @Test
    fun directMessageOpenFailureNeverFallsBackToABareCode() {
        val message =
            homeDirectMessageOpenFailureMessage(
                prefix = "Could not open direct message",
                error = ScreenError(code = "direct_message_unknown"),
                errorStrings = errorStrings
            )

        assertEquals(
            "Could not open direct message: Something went wrong. Please try again.",
            message
        )
        assertFalse("direct_message_unknown" in message)
    }

    @Test
    fun hostedDrawerProjectionRetainsTenThousandNormalizedRowsByIdentity() {
        val serverId = ServerId("server-scale")
        val rows =
            List(10_000) { index ->
                HomeServerDrawerNormalizedRow.Conversation(
                    section = HomeServerDrawerConversationSection.Channel,
                    conversation =
                        HomeChannelRow(
                            threadId = ThreadId("channel-$index"),
                            channelId = ChannelId("channel-$index"),
                            name = "Channel $index"
                        )
                )
            }
        val normalized = HomeServerDrawerNormalizedContent(rows, firstConversationIndex = 0)
        val drawer =
            HomeServerDrawerState(
                expanded = true,
                expandedServerIds = setOf(serverId),
                sections =
                    mapOf(
                        serverId to
                            HomeServerDrawerSectionState(
                                server = ServerSummary(serverId, "Scale"),
                                normalizedContent = normalized,
                                status = HomeServerDrawerLoadStatus.Loaded
                            )
                    )
            )

        val projected = drawer.toSlockHomeServerDrawerHostedSections()

        assertSame(normalized, projected.single().normalizedContent)
        assertSame(rows, projected.single().normalizedContent.rows)
    }

    @Test
    fun sameSnapshotDrawerToggleDoesNotRenormalizeCachedRows() {
        val snapshot = testHomeSnapshot()
        val previous =
            HomeServerDrawerNormalizedContent(
                rows =
                    List(10_000) { index ->
                        HomeServerDrawerNormalizedRow.Conversation(
                            section = HomeServerDrawerConversationSection.Channel,
                            conversation =
                                HomeChannelRow(
                                    threadId = ThreadId("cached-$index"),
                                    channelId = ChannelId("cached-$index"),
                                    name = "Cached $index"
                                )
                        )
                    },
                firstConversationIndex = 0
            )
        var normalizeCalls = 0

        val retained =
            homeServerDrawerNormalizedContentForSnapshot(snapshot, snapshot, previous) {
                normalizeCalls += 1
                HomeServerDrawerNormalizedContent.Empty
            }

        assertSame(previous, retained)
        assertEquals(0, normalizeCalls)
    }

    @Test
    fun homeServerDrawerFlattensMultipleExpandedServersIntoOneKeyedRowList() {
        val sharedId = "shared-conversation"
        val sections =
            listOf(
                SlockHomeServerDrawerSection(
                    server = SlockServerSwitcherItem(id = "server-1", name = "One", slug = "one"),
                    expanded = true,
                    status = HomeServerDrawerLoadStatus.Loaded,
                    channels = listOf(SlockHomeConversationItem(id = sharedId, name = "General", kind = "channel")),
                    pinnedChannelIds = listOf(sharedId),
                    pinnedOrder = listOf(sharedId)
                ),
                SlockHomeServerDrawerSection(
                    server = SlockServerSwitcherItem(id = "server-2", name = "Two", slug = "two"),
                    expanded = true,
                    status = HomeServerDrawerLoadStatus.Loaded,
                    channels = listOf(SlockHomeConversationItem(id = sharedId, name = "Other", kind = "channel"))
                )
            )

        val rows = homeServerDrawerRows(sections)
        val keys = rows.map { it.key }

        assertEquals(keys.size, keys.toSet().size)
        assertEquals("server:server-1", keys.first())
        assertTrue("section:server-1:pinned" in keys)
        assertTrue("conversation:server-1:pinned:$sharedId" in keys)
        assertTrue("conversation:server-1:channel:$sharedId" in keys)
        assertTrue("server:server-2" in keys)
        assertTrue("conversation:server-2:channel:$sharedId" in keys)
    }

    @Test
    fun homeServerDrawerHeaderRoutesRowAndChevronToDisjointActions() {
        val openedServerIds = mutableListOf<String>()
        val toggledServerIds = mutableListOf<String>()
        val onServerClick: (String) -> Unit = openedServerIds::add
        val onSectionToggle: (String) -> Unit = toggledServerIds::add

        dispatchHomeServerDrawerHeaderAction(
            action = HomeServerDrawerHeaderAction.OpenServerHome,
            serverId = "server-2",
            onServerClick = onServerClick,
            onSectionToggle = onSectionToggle,
        )
        assertEquals(listOf("server-2"), openedServerIds)
        assertTrue(toggledServerIds.isEmpty())

        dispatchHomeServerDrawerHeaderAction(
            action = HomeServerDrawerHeaderAction.ToggleSection,
            serverId = "server-2",
            onServerClick = onServerClick,
            onSectionToggle = onSectionToggle,
        )
        assertEquals(listOf("server-2"), openedServerIds)
        assertEquals(listOf("server-2"), toggledServerIds)
    }

    @Test
    fun homeServerDrawerShowsSkeletonAndCachePreservingErrorRowsBySectionState() {
        val rows =
            homeServerDrawerRows(
                listOf(
                    SlockHomeServerDrawerSection(
                        server = SlockServerSwitcherItem(id = "loading", name = "Loading", slug = "loading"),
                        expanded = true,
                        status = HomeServerDrawerLoadStatus.Loading
                    ),
                    SlockHomeServerDrawerSection(
                        server = SlockServerSwitcherItem(id = "cached", name = "Cached", slug = "cached"),
                        expanded = true,
                        status = HomeServerDrawerLoadStatus.ErrorWithCache,
                        errorCode = "home_network",
                        directMessages =
                            listOf(
                                SlockHomeConversationItem(
                                    id = "dm-1",
                                    name = "Cindy",
                                    kind = KuiklyThreadSurfaceKind.DirectMessage
                                )
                            )
                    ),
                    SlockHomeServerDrawerSection(
                        server = SlockServerSwitcherItem(id = "collapsed", name = "Collapsed", slug = "collapsed"),
                        expanded = false,
                        status = HomeServerDrawerLoadStatus.Loaded,
                        channels = listOf(SlockHomeConversationItem(id = "hidden", name = "Hidden", kind = "channel"))
                    )
                )
            )

        assertEquals(3, rows.filterIsInstance<SlockHomeServerDrawerRow.Skeleton>().size)
        assertTrue(rows.any { it.key == "conversation:cached:directmessage:dm-1" })
        assertEquals(
            SlockHomeServerDrawerRow.Error("cached", "home_network", hasCache = true),
            rows.filterIsInstance<SlockHomeServerDrawerRow.Error>().single()
        )
        assertFalse(rows.any { it.key.contains("hidden") })
    }

    @Test
    fun homeServerDrawerWidthAndSwipeThresholdFollowModalContract() {
        assertEquals(336.dp, homeServerDrawerWidth(maxWidth = 400.dp, maxHeight = 800.dp))
        assertEquals(360.dp, homeServerDrawerWidth(maxWidth = 800.dp, maxHeight = 1_000.dp))
        assertEquals(300.dp, homeServerDrawerWidth(maxWidth = 600.dp, maxHeight = 400.dp))
        assertEquals(360.dp, homeServerDrawerWidth(maxWidth = 800.dp, maxHeight = 400.dp))
        assertFalse(shouldDismissHomeServerDrawerOnSwipe(horizontalDragDp = -71f))
        assertTrue(shouldDismissHomeServerDrawerOnSwipe(horizontalDragDp = -72f))
        assertFalse(shouldDismissHomeServerDrawerOnSwipe(horizontalDragDp = 120f))
    }

    @Test
    fun homeServerDrawerContentGestureRequiresUncontestedHomeRightSwipe() {
        val allowed =
            HomeServerDrawerOpenGestureAdmission(
                homeSurfaceActive = true,
                pointerOwnerActive = true
            )

        assertTrue(
            shouldOpenHomeServerDrawerFromContentGesture(
                admission = allowed,
                horizontalDragDp = 80f,
                verticalDragDp = 8f
            )
        )
        assertFalse(
            shouldOpenHomeServerDrawerFromContentGesture(
                admission = allowed,
                horizontalDragDp = -100f,
                verticalDragDp = 0f
            )
        )
        assertFalse(
            shouldOpenHomeServerDrawerFromContentGesture(
                admission = allowed,
                horizontalDragDp = 80f,
                verticalDragDp = 90f
            )
        )
        assertFalse(
            shouldOpenHomeServerDrawerFromContentGesture(
                admission = allowed.copy(descendantConsumed = true),
                horizontalDragDp = 100f,
                verticalDragDp = 0f
            )
        )
        assertFalse(
            shouldOpenHomeServerDrawerFromContentGesture(
                admission = allowed.copy(inputFocused = true),
                horizontalDragDp = 100f,
                verticalDragDp = 0f
            )
        )
        assertFalse(
            shouldOpenHomeServerDrawerFromContentGesture(
                admission = allowed.copy(modalPresented = true),
                horizontalDragDp = 100f,
                verticalDragDp = 0f
            )
        )
        assertFalse(
            shouldOpenHomeServerDrawerFromContentGesture(
                admission = allowed.copy(multiplePointers = true),
                horizontalDragDp = 100f,
                verticalDragDp = 0f
            )
        )
        assertFalse(
            shouldOpenHomeServerDrawerFromContentGesture(
                admission = allowed.copy(homeSurfaceActive = false),
                horizontalDragDp = 100f,
                verticalDragDp = 0f
            )
        )
        assertFalse(
            shouldOpenHomeServerDrawerFromContentGesture(
                admission = allowed.copy(pointerOwnerActive = false),
                horizontalDragDp = 100f,
                verticalDragDp = 0f
            )
        )
    }

    @Test
    fun appUpdateNotificationMovesToDedicatedBanner() {
        val update =
            SlockHomeNotificationItem(
                id = build.raft.app.data.APP_UPDATE_HOME_NOTIFICATION_ID,
                title = "Update available"
            )
        val machine = SlockHomeNotificationItem(id = "machine-offline:1", title = "Offline")
        val notifications = listOf(machine, update)

        // artin 2026-08-06 ruling (task #113): app update keeps its independent
        // top banner and does not go through the notification center.
        assertEquals(update, notifications.homeAppUpdateBannerNotification())
        assertEquals(listOf(machine), notifications.nonAppUpdateHomeNotifications())
    }

    @Test
    fun homeSearchErrorDisplayUsesUnifiedRetryModel() {
        val serverError = sharedHomeSearchErrorDisplay("server_unavailable", errorStrings)

        assertEquals("Could not load Search", serverError.title)
        assertEquals("The server is unavailable right now. Please try again shortly.", serverError.detail)
        assertEquals("server_unavailable", serverError.code)
        assertEquals("Retry", serverError.actionLabel)

        val permissionError = sharedHomeSearchErrorDisplay("search_permission_denied", errorStrings)

        assertEquals("Search is unavailable", permissionError.title)
        assertEquals("You do not have access to search this workspace right now.", permissionError.detail)
        assertEquals("search_permission_denied", permissionError.code)
        assertEquals("Retry", permissionError.actionLabel)
    }

    @Test
    fun homeActivitySidebarUnreadCountUsesActivityUnreadOnly() {
        assertEquals(
            14,
            homeActivitySidebarUnreadCount(activityUnreadCount = 14)
        )
        assertEquals(
            0,
            homeActivitySidebarUnreadCount(activityUnreadCount = 0)
        )
        assertEquals(
            0,
            homeActivitySidebarUnreadCount(activityUnreadCount = -1)
        )
        assertEquals(
            3,
            homeActivitySidebarUnreadCount(activityUnreadCount = 3)
        )
    }

    @Test
    fun homeActivitySidebarTotalCountOnlyShowsWhenUnreadIsZero() {
        assertEquals(
            40,
            homeActivitySidebarTotalCount(activityUnreadCount = 0, activityTotalCount = 40)
        )
        assertEquals(
            0,
            homeActivitySidebarTotalCount(activityUnreadCount = 14, activityTotalCount = 40)
        )
        assertEquals(
            0,
            homeActivitySidebarTotalCount(activityUnreadCount = 0, activityTotalCount = 0)
        )
        assertEquals(
            0,
            homeActivitySidebarTotalCount(activityUnreadCount = -1, activityTotalCount = -1)
        )
    }

    @Test
    fun homeServerSwitcherUnreadDotOnlyTracksOtherServers() {
        assertFalse(
            homeServerSwitcherHasOtherUnread(
                currentServerId = "server-1",
                servers =
                    listOf(
                        SlockServerSwitcherItem(id = "server-1", name = "Current", slug = "current", broaderUnreadCount = 8),
                        SlockServerSwitcherItem(id = "server-2", name = "Other", slug = "other", broaderUnreadCount = 0)
                    )
            )
        )
        assertTrue(
            homeServerSwitcherHasOtherUnread(
                currentServerId = "server-1",
                servers =
                    listOf(
                        SlockServerSwitcherItem(id = "server-1", name = "Current", slug = "current", broaderUnreadCount = 0),
                        SlockServerSwitcherItem(id = "server-2", name = "Other", slug = "other", broaderUnreadCount = 3)
                    )
            )
        )
    }

    @Test
    fun switcherProjectsExactActivityCountsForEveryServerFromOneOwnerFencedBatch() {
        val expectedOwner = testActivityOwner()
        val projection =
            HomeServerActivityUnreadProjection(
                requestOwner = expectedOwner,
                countsByServer = mapOf("server-1" to 0, "server-2" to 78),
                serverPushMutedByServer = mapOf("server-1" to false, "server-2" to true),
            )

        val compact =
            sharedHomeSwitcherItems(
                session = testServerSwitcherSession(),
                selectedServer = ServerSummary(ServerId("server-1"), "Current", slug = "current"),
                serverUnreadCounts = mapOf("server-1" to 21, "server-2" to 6),
                activityExpectedOwner = expectedOwner,
                activityProjection = projection,
            )
        val expanded =
            HomeServerDrawerState(
                sections =
                    linkedMapOf(
                        ServerId("server-1") to
                            HomeServerDrawerSectionState(
                                server = ServerSummary(ServerId("server-1"), "Current", slug = "current")
                            ),
                        ServerId("server-2") to
                            HomeServerDrawerSectionState(
                                server = ServerSummary(ServerId("server-2"), "Other", slug = "other")
                            ),
                    )
            ).toSlockHomeServerDrawerSections(
                serverUnreadCounts = mapOf("server-1" to 21, "server-2" to 6),
                activityExpectedOwner = expectedOwner,
                activityProjection = projection,
            ).map { it.server }

        assertEquals(compact.map(::homeServerSwitcherBadgePresentation), expanded.map(::homeServerSwitcherBadgePresentation))
        assertEquals(HomeServerSwitcherBadgeKind.None, homeServerSwitcherBadgePresentation(compact[0]).kind)
        assertEquals(
            HomeServerSwitcherBadgePresentation(
                kind = HomeServerSwitcherBadgeKind.ActivityNumber,
                label = "78",
                muted = true,
            ),
            homeServerSwitcherBadgePresentation(compact[1]),
        )
        assertTrue(homeServerSwitcherHasOtherUnread("server-1", compact))
    }

    @Test
    fun activityProjectionRejectsOldEnvironmentAccountAndWrongServerValue() {
        val owner = testActivityOwner()
        val projection =
            HomeServerActivityUnreadProjection(
                requestOwner = owner,
                countsByServer = mapOf("server-1" to 9),
            )

        assertNull(projection.countFor(owner.copy(environmentId = "https://other.example")))
        assertNull(projection.countFor(owner.copy(accountId = "other-account")))
        assertNull(projection.countFor(owner.copy(serverId = "server-2")))
        assertEquals(9L, projection.countFor(owner))
    }

    @Test
    fun muteNeverChangesActivityCountAuthorityOrSuppressesItsNumber() {
        val muted =
            SlockServerSwitcherItem(
                id = "server-2",
                activityUnreadCount = 3,
                serverPushMuted = true,
            )

        assertEquals(3L, muted.activityUnreadCount)
        assertEquals(HomeServerSwitcherBadgeKind.ActivityNumber, homeServerSwitcherBadgePresentation(muted).kind)
        assertEquals("3", homeServerSwitcherBadgePresentation(muted).label)
        assertTrue(homeServerSwitcherBadgePresentation(muted).muted)
    }

    @Test
    fun homeServerBadgeTiltUsesFinalVectorGeometry() {
        val corners = homeServerBadgeTiltCorners(widthPx = 300f, heightPx = 80f)

        assertEquals(4, corners.size)
        assertTrue(corners.all { it.x.isFinite() && it.y.isFinite() })
        assertTrue(corners.minOf { it.x } in -0.01f..0.01f)
        assertTrue(corners.maxOf { it.x } in 299.99f..300.01f)
        assertTrue(corners.minOf { it.y } in -0.01f..0.01f)
        assertTrue(corners.maxOf { it.y } in 79.99f..80.01f)
        assertTrue(corners[0].y > corners[1].y)
        assertTrue(corners[3].y > corners[2].y)
    }

    @Test
    fun homeServerBadgeChromeReservesShadowWithoutTruncatingMainPath() {
        val layers = homeServerBadgeChromeLayers(
            widthPx = 306f,
            heightPx = 86f,
            shadowOffsetPx = 6f
        )

        assertTrue(layers.mainCorners.minOf { it.x } in -0.01f..0.01f)
        assertTrue(layers.mainCorners.maxOf { it.x } in 299.99f..300.01f)
        assertTrue(layers.mainCorners.minOf { it.y } in -0.01f..0.01f)
        assertTrue(layers.mainCorners.maxOf { it.y } in 79.99f..80.01f)
        assertTrue(layers.shadowCorners.minOf { it.x } in 5.99f..6.01f)
        assertTrue(layers.shadowCorners.maxOf { it.x } in 305.99f..306.01f)
        assertTrue(layers.shadowCorners.minOf { it.y } in 5.99f..6.01f)
        assertTrue(layers.shadowCorners.maxOf { it.y } in 85.99f..86.01f)
    }

    @Test
    fun homeServerSwitcherPanelTopOffsetLeavesGapBelowHeader() {
        assertEquals(
            SlockSize.PageHeaderHeight + 4.dp,
            homeServerSwitcherPanelTopOffset(topSafeArea = 0.dp)
        )
        assertEquals(
            24.dp + SlockSize.PageHeaderHeight + 4.dp,
            homeServerSwitcherPanelTopOffset(topSafeArea = 24.dp)
        )
    }

    @Test
    fun homeServerSwitcherServerListHeightOnlyCapsScrollableLists() {
        assertNull(homeServerSwitcherServerListMaxHeight(serverCount = 0))
        assertNull(homeServerSwitcherServerListMaxHeight(serverCount = 1))
        assertNull(homeServerSwitcherServerListMaxHeight(serverCount = 2))
        assertNull(homeServerSwitcherServerListMaxHeight(serverCount = 6))
        assertEquals(
            312.dp,
            homeServerSwitcherServerListMaxHeight(serverCount = 7)
        )
        assertEquals(
            312.dp,
            homeServerSwitcherServerListMaxHeight(serverCount = 30)
        )
    }

    @Test
    fun homeServerSwitcherDragHandleUsesWebGripIcon() {
        assertEquals("grip-vertical", HomeServerSwitcherDragHandleIconName.wireName)
    }

    @Test
    fun homeServerSwitcherUsesServerAvatarFallbackForUrlAndBlankSources() {
        val withUrl =
            SlockServerSwitcherItem(
                id = "server-1",
                name = "Raft",
                avatarUrl = "https://cdn.raft.build/server.png"
            ).toServerSwitcherAvatarPresentation()
        val withoutUrl =
            SlockServerSwitcherItem(
                id = "server-2",
                name = "Botiverse",
                avatarUrl = " "
            ).toServerSwitcherAvatarPresentation()

        assertEquals("https://cdn.raft.build/server.png", withUrl.avatarWireValue)
        assertEquals("R", withUrl.fallbackLabel)
        assertEquals(null, withoutUrl.avatarWireValue)
        assertEquals("B", withoutUrl.fallbackLabel)
    }

    @Test
    fun homeHeaderIdentityUsesTheCurrentServerItemById() {
        val current =
            SlockServerSwitcherItem(
                id = "server-current",
                name = "Current workspace",
                slug = "current",
                avatarUrl = "https://cdn.raft.build/current.png"
            )

        val resolved =
            homeHeaderServerIdentity(
                currentServerId = current.id,
                currentServerName = "Canonical name",
                servers =
                    listOf(
                        SlockServerSwitcherItem(
                            id = "server-other",
                            name = "Other workspace",
                            slug = "other",
                            avatarUrl = "https://cdn.raft.build/other.png"
                        ),
                        current
                    )
            )

        assertEquals(current, resolved)
        assertEquals(current.toServerSwitcherAvatarPresentation(), resolved.toServerSwitcherAvatarPresentation())
    }

    @Test
    fun homeHeaderIdentityDoesNotBorrowAnotherServerAvatarWhenCurrentIdIsMissing() {
        val resolved =
            homeHeaderServerIdentity(
                currentServerId = "server-current",
                currentServerName = "Canonical name",
                servers =
                    listOf(
                        SlockServerSwitcherItem(
                            id = "server-other",
                            name = "Canonical name",
                            slug = "other",
                            avatarUrl = "https://cdn.raft.build/other.png"
                        )
                    )
            )

        assertEquals("server-current", resolved.id)
        assertEquals("Canonical name", resolved.name)
        assertEquals(null, resolved.avatarUrl)
        assertEquals(
            ServerSwitcherAvatarPresentation(avatarWireValue = null, fallbackLabel = "C"),
            resolved.toServerSwitcherAvatarPresentation()
        )
    }

    @Test
    fun homeHeaderIdentityUsesCanonicalNameForBlankMatchedName() {
        val resolved =
            homeHeaderServerIdentity(
                currentServerId = "server-current",
                currentServerName = "Canonical name",
                servers =
                    listOf(
                        SlockServerSwitcherItem(
                            id = "server-current",
                            name = " ",
                            slug = "current",
                            avatarUrl = "https://cdn.raft.build/current.png"
                        )
                    )
            )

        assertEquals("Canonical name", resolved.name)
        assertEquals("https://cdn.raft.build/current.png", resolved.avatarUrl)
        assertEquals("C", resolved.toServerSwitcherAvatarPresentation().fallbackLabel)
    }

    @Test
    fun homeServerSwitcherCommunityActionsFollowJoinedSlugsIndependently() {
        val labels = SlockHomeShellLabels()

        assertEquals(
            listOf(
                SlockServerSwitcherCommunityAction("community-cn", labels.joinChineseCommunityServer),
                SlockServerSwitcherCommunityAction("community", labels.joinCommunityServer)
            ),
            homeServerSwitcherJoinCommunityActions(
                servers = emptyList(),
                labels = labels,
                includeChineseCommunity = true
            )
        )
        assertEquals(
            listOf(SlockServerSwitcherCommunityAction("community-cn", labels.joinChineseCommunityServer)),
            homeServerSwitcherJoinCommunityActions(
                servers = listOf(SlockServerSwitcherItem(id = "server-1", name = "Community", slug = "community")),
                labels = labels,
                includeChineseCommunity = true
            )
        )
        assertEquals(
            listOf(SlockServerSwitcherCommunityAction("community", labels.joinCommunityServer)),
            homeServerSwitcherJoinCommunityActions(
                servers = listOf(SlockServerSwitcherItem(id = "server-cn", name = "Community CN", slug = "community-cn")),
                labels = labels,
                includeChineseCommunity = true
            )
        )
        assertEquals(
            emptyList(),
            homeServerSwitcherJoinCommunityActions(
                servers =
                    listOf(
                        SlockServerSwitcherItem(id = "server-cn", name = "Community CN", slug = "community-cn"),
                        SlockServerSwitcherItem(id = "server-1", name = "Community", slug = "community")
                    ),
                labels = labels,
                includeChineseCommunity = true
            )
        )
    }

    @Test
    fun homeServerSwitcherChineseCommunityActionRequiresChinesePreference() {
        val labels = SlockHomeShellLabels()

        assertEquals(
            listOf(SlockServerSwitcherCommunityAction("community", labels.joinCommunityServer)),
            homeServerSwitcherJoinCommunityActions(
                servers = emptyList(),
                labels = labels,
                includeChineseCommunity = false
            )
        )
    }

    @Test
    fun expressiveTopScrollUsesNearbyAnchorOnlyForFarDistances() {
        assertEquals(
            null,
            slockExpressiveTopSettleAnchor(firstVisibleItemIndex = 12, visibleItemCount = 8)
        )
        assertEquals(
            16,
            slockExpressiveTopSettleAnchor(firstVisibleItemIndex = 40, visibleItemCount = 8)
        )
    }

    @Test
    fun expressiveTopScrollKeepsSettleWindowBounded() {
        assertEquals(
            8,
            slockExpressiveTopSettleAnchor(firstVisibleItemIndex = 30, visibleItemCount = 1)
        )
        assertEquals(
            24,
            slockExpressiveTopSettleAnchor(firstVisibleItemIndex = 80, visibleItemCount = 30)
        )
    }

    @Test
    fun homeSavedCountFormatsOverflow() {
        assertEquals(null, formatHomeSavedCount(0))
        assertEquals(null, formatHomeSavedCount(-1))
        assertEquals("20", formatHomeSavedCount(20))
        assertEquals("99+", formatHomeSavedCount(100))
        assertEquals("99+", formatHomeSavedCount(101))
    }

    @Test
    fun homeConversationBadgeProjectionFormatsAndPreservesAxes() {
        assertEquals(
            HomeSidebarBadgeProjection(label = null, hasUnread = false, hasAttention = false),
            homeConversationBadgeProjection(HomeConversationBadgeInput(unreadCount = 0))
        )
        assertEquals(
            HomeSidebarBadgeProjection(label = "99+", tone = HomeSidebarBadgeTone.Loud, hasUnread = true, hasAttention = true),
            homeConversationBadgeProjection(HomeConversationBadgeInput(unreadCount = 100))
        )
        assertEquals(
            HomeSidebarBadgeProjection(label = "4", tone = HomeSidebarBadgeTone.Quiet, hasUnread = true, hasAttention = false),
            homeConversationBadgeProjection(HomeConversationBadgeInput(unreadCount = 4, joined = false))
        )
        assertEquals(
            HomeSidebarBadgeProjection(label = "5", tone = HomeSidebarBadgeTone.Quiet, hasUnread = true, hasAttention = false),
            homeConversationBadgeProjection(HomeConversationBadgeInput(unreadCount = 5, muted = true))
        )
        assertEquals(
            HomeSidebarBadgeProjection(label = "5", tone = HomeSidebarBadgeTone.Loud, hasUnread = true, hasAttention = true),
            homeConversationBadgeProjection(HomeConversationBadgeInput(unreadCount = 5, muted = true, mentionPierced = true))
        )
        assertEquals(
            HomeSidebarBadgeProjection(label = "5", tone = HomeSidebarBadgeTone.Loud, hasUnread = true, hasAttention = true),
            homeConversationBadgeProjection(HomeConversationBadgeInput(unreadCount = 5, muted = true, followed = true))
        )
    }

    @Test
    fun homeConversationItemBadgeProjectionUsesActivityMutedAxis() {
        val mutedConversation =
            SlockHomeConversationItem(
                id = "channel-muted",
                name = "muted",
                unreadCount = 6,
                joined = true,
                activityMuted = true
            )
        val unmutedConversation =
            SlockHomeConversationItem(
                id = "channel-loud",
                name = "loud",
                unreadCount = 6,
                joined = true,
                activityMuted = false
            )

        assertEquals(
            HomeSidebarBadgeProjection(label = "6", tone = HomeSidebarBadgeTone.Quiet, hasUnread = true, hasAttention = false),
            mutedConversation.homeConversationBadgeProjection()
        )
        assertEquals(
            HomeSidebarBadgeProjection(label = "6", tone = HomeSidebarBadgeTone.Loud, hasUnread = true, hasAttention = true),
            unmutedConversation.homeConversationBadgeProjection()
        )
    }

    @Test
    fun homeConversationTitleProjectionUsesActivityMutedAxis() {
        assertEquals(
            SlockColor.TextMutedGray,
            homeConversationTitleColor(muted = true, joined = true)
        )
        assertEquals(
            SlockColor.TextMutedGray,
            homeConversationTitleColor(muted = true, joined = false)
        )
        assertEquals(
            SlockColor.TextBlack,
            homeConversationTitleColor(muted = false, joined = true)
        )
        assertEquals(
            SlockColor.TextBlack.copy(alpha = 0.46f),
            homeConversationTitleColor(muted = false, joined = false)
        )
    }

    @Test
    fun homeConversationMutedIndicatorUsesBellOffOnlyWhenMuted() {
        assertEquals(SlockLucideIconName.BellOff, homeConversationMutedIndicatorIcon(muted = true))
        assertNull(homeConversationMutedIndicatorIcon(muted = false))
    }

    @Test
    fun homeConversationMenuAddsMuteActionOnlyWhenSupported() {
        val unsupported =
            SlockHomeConversationItem(
                id = "channel-unsupported",
                name = "unsupported",
                activityMuted = false,
                activityMuteSupported = false
            )
        val unmuted =
            SlockHomeConversationItem(
                id = "channel-unmuted",
                name = "unmuted",
                activityMuted = false,
                activityMuteSupported = true
            )
        val muted =
            SlockHomeConversationItem(
                id = "channel-muted",
                name = "muted",
                activityMuted = true,
                activityMuteSupported = true
            )

        assertFalse(
            slockHomeConversationMenuItems(
                conversation = unsupported,
                pinTargetIsPinned = false,
                sortableFromMenu = false,
                activeIsDirectMessage = false,
                activeCanArchive = false
            ).any { it.id == "mute-channel" || it.id == "unmute-channel" }
        )
        assertEquals(
            SlockBaseMenuItem(id = "mute-channel", label = "Mute", icon = SlockLucideIconName.BellOff),
            slockHomeConversationMenuItems(
                conversation = unmuted,
                pinTargetIsPinned = false,
                sortableFromMenu = false,
                activeIsDirectMessage = false,
                activeCanArchive = false
            ).single { it.id == "mute-channel" }
        )
        assertEquals(
            SlockBaseMenuItem(id = "unmute-channel", label = "Unmute", icon = SlockLucideIconName.Bell),
            slockHomeConversationMenuItems(
                conversation = muted,
                pinTargetIsPinned = false,
                sortableFromMenu = false,
                activeIsDirectMessage = false,
                activeCanArchive = false
            ).single { it.id == "unmute-channel" }
        )
    }

    @Test
    fun homeContextAndSortMenusUseInjectedLocalizedLabels() {
        val labels =
            SlockHomeShellLabels(
                closeChat = "关闭私聊",
                archive = "归档",
                sortChannels = "排序",
                sortManual = "手动",
                sortRecent = "最近",
                sortAz = "A-Z"
            )
        val conversation =
            SlockHomeConversationItem(
                id = "dm-1",
                name = "Cindy",
                activityMuteSupported = false
            )

        val directMessageMenu =
            slockHomeConversationMenuItems(
                conversation = conversation,
                labels = labels,
                pinTargetIsPinned = false,
                sortableFromMenu = true,
                activeIsDirectMessage = true,
                activeCanArchive = false
            )
        assertEquals("排序", directMessageMenu.single { it.id == "sort-channels" }.label)
        assertEquals("关闭私聊", directMessageMenu.single { it.id == "close-dm" }.label)

        val channelMenu =
            slockHomeConversationMenuItems(
                conversation = conversation,
                labels = labels,
                pinTargetIsPinned = false,
                sortableFromMenu = false,
                activeIsDirectMessage = false,
                activeCanArchive = true
            )
        assertEquals("归档", channelMenu.single { it.id == "archive-channel" }.label)
        assertEquals(
            listOf("手动", "最近", "A-Z"),
            slockHomeSortMenuItems(labels).map { it.label }
        )
    }

    @Test
    fun homeActivitySidebarProjectionUsesSharedBadgeFormatting() {
        assertEquals(
            HomeActivitySidebarProjection(unreadLabel = "99+", totalLabel = null),
            homeActivitySidebarProjection(activityUnreadCount = 140, activityTotalCount = 200)
        )
        assertEquals(
            HomeActivitySidebarProjection(unreadLabel = null, totalLabel = "99+"),
            homeActivitySidebarProjection(activityUnreadCount = 0, activityTotalCount = 200)
        )
    }

    @Test
    fun homeSidebarSelectionPrefersTopActionOverConversation() {
        assertEquals(
            SlockHomeSidebarSelection(topAction = SlockHomeSidebarTopSelection.Activity),
            homeSidebarSelection(
                selectedChannelId = "channel-1",
                selectedConversationSection = null,
                channels = listOf(SlockHomeConversationItem(id = "channel-1", name = "home")),
                directMessages = emptyList(),
                searchSelected = false,
                activitySelected = true,
                savedSelected = false
            )
        )
        assertEquals(
            SlockHomeSidebarSelection(topAction = SlockHomeSidebarTopSelection.Search),
            homeSidebarSelection(
                selectedChannelId = "channel-1",
                selectedConversationSection = null,
                channels = listOf(SlockHomeConversationItem(id = "channel-1", name = "home")),
                directMessages = emptyList(),
                searchSelected = true,
                activitySelected = false,
                savedSelected = false
            )
        )
        assertEquals(
            SlockHomeSidebarSelection(topAction = SlockHomeSidebarTopSelection.Saved),
            homeSidebarSelection(
                selectedChannelId = "channel-1",
                selectedConversationSection = null,
                channels = listOf(SlockHomeConversationItem(id = "channel-1", name = "home")),
                directMessages = emptyList(),
                searchSelected = false,
                activitySelected = false,
                savedSelected = true
            )
        )
    }

    @Test
    fun homeSidebarSelectionDistinguishesConversationSections() {
        assertEquals(
            SlockHomeSidebarSelection(conversationId = "channel-1", conversationSection = SlockHomeConversationSection.Channel),
            homeSidebarSelection(
                selectedChannelId = "channel-1",
                selectedConversationSection = null,
                channels = listOf(
                    SlockHomeConversationItem(id = "channel-1", name = "home"),
                    SlockHomeConversationItem(id = "joint-1", name = "partner", kind = "joint")
                ),
                directMessages = listOf(SlockHomeConversationItem(id = "dm-1", name = "artin")),
                searchSelected = false,
                activitySelected = false,
                savedSelected = false
            )
        )
        assertEquals(
            SlockHomeSidebarSelection(conversationId = "joint-1", conversationSection = SlockHomeConversationSection.JointChannel),
            homeSidebarSelection(
                selectedChannelId = "joint-1",
                selectedConversationSection = null,
                channels = listOf(SlockHomeConversationItem(id = "joint-1", name = "partner", kind = "joint")),
                directMessages = emptyList(),
                searchSelected = false,
                activitySelected = false,
                savedSelected = false
            )
        )
        assertEquals(
            SlockHomeSidebarSelection(conversationId = "dm-1", conversationSection = SlockHomeConversationSection.DirectMessage),
            homeSidebarSelection(
                selectedChannelId = "dm-1",
                selectedConversationSection = null,
                channels = emptyList(),
                directMessages = listOf(SlockHomeConversationItem(id = "dm-1", name = "artin")),
                searchSelected = false,
                activitySelected = false,
                savedSelected = false
            )
        )
    }

    @Test
    fun homeChannelSymbolUsesLockForPrivateChannels() {
        assertEquals("#", SlockHomeConversationItem(kind = "channel").homeChannelSymbol())
        assertEquals("lock", SlockHomeConversationItem(kind = "channel", channelType = "private").homeChannelSymbol())
        assertEquals("git-branch", SlockHomeConversationItem(kind = "channel", channelType = "joint").homeChannelSymbol())
        assertEquals("git-branch", SlockHomeConversationItem(kind = "joint", channelType = "channel").homeChannelSymbol())
    }

    @Test
    fun homeConversationItemKeepsDmKindAheadOfChannelType() {
        val item =
            SlockHomeConversationItem(
                id = "dm-cindy",
                name = "Cindy",
                kind = KuiklyThreadSurfaceKind.DirectMessage,
                channelType = KuiklyThreadSurfaceKind.Channel
            )

        assertTrue(item.isDirectMessageConversation())
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, item.homeChannelKind())
    }

    @Test
    fun homeChannelTitleMatchesReactSelectedAndUnreadWeightRules() {
        assertEquals(
            SlockType.ListItem,
            homeChannelTitleTextStyle(
                selected = false,
                hasUnread = false,
                badgeTone = HomeSidebarBadgeTone.Loud,
            ),
        )
        assertEquals(
            SlockType.ListItemBold,
            homeChannelTitleTextStyle(
                selected = true,
                hasUnread = false,
                badgeTone = HomeSidebarBadgeTone.Loud,
            ),
        )
        assertEquals(
            SlockType.ListItemBold,
            homeChannelTitleTextStyle(
                selected = false,
                hasUnread = true,
                badgeTone = HomeSidebarBadgeTone.Loud,
            ),
        )
        assertEquals(
            SlockType.ListItem,
            homeChannelTitleTextStyle(
                selected = false,
                hasUnread = true,
                badgeTone = HomeSidebarBadgeTone.Quiet,
            ),
        )
        assertEquals(
            SlockType.ListItemBold,
            homeChannelTitleTextStyle(
                selected = true,
                hasUnread = true,
                badgeTone = HomeSidebarBadgeTone.Quiet,
            ),
        )
    }

    @Test
    fun homeConversationItemRecognizesLegacyDirectMessageKind() {
        val item = SlockHomeConversationItem(id = "dm-cindy", name = "Cindy", kind = "directmessage")

        assertTrue(item.isDirectMessageConversation())
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, item.homeChannelKind())
    }

    @Test
    fun localComposerDraftProjectionClearsStaleSnapshotDraftFlags() {
        val staleSnapshotItem = SlockHomeConversationItem(id = "channel-1", name = "home", hasDraft = true)
        val projection = emptySet<SharedThreadComposerDraftRecord>().toSharedThreadComposerDraftProjection()

        assertFalse(projection.hasRootDraft(staleSnapshotItem.id))
    }

    @Test
    fun localComposerDraftProjectionMarksOnlyMatchingRootConversations() {
        val projected =
            setOf(
                SharedThreadComposerDraftRecord(
                    key = "send=channel-1|parent=_|mention=channel-1",
                    sendChannelId = "channel-1",
                    parentMessageId = null,
                    mentionChannelId = "channel-1"
                ),
                SharedThreadComposerDraftRecord(
                    key = "send=reply-thread-1|parent=message-1|mention=channel-1",
                    sendChannelId = "reply-thread-1",
                    parentMessageId = "message-1",
                    mentionChannelId = "channel-1"
                )
            ).toSharedThreadComposerDraftProjection()

        assertTrue(projected.hasRootDraft("channel-1"))
        assertFalse(projected.hasRootDraft("reply-thread-1"))
    }

    @Test
    fun homeConversationItemUsesDirectoryProjectionForDisplayFields() {
        val seed =
            SharedHomeConversationRowSeed(
                id = "channel-1",
                kind = ConversationKind.Channel,
                unreadCount = 7,
                fallbackName = "stale-name",
                fallbackChannelType = "channel",
                fallbackDescription = "stale-subtitle",
                fallbackJoined = false,
                fallbackArchived = false
            )
        val directoryProjection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "channel-1",
                aliases = setOf("channel-1"),
                conversationKind = ConversationKind.Channel,
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                channelKind = "private",
                title = "fresh-name",
                subtitle = "fresh-subtitle",
                avatarWireValue = "pixel:fresh",
                joined = true,
                archived = true
            )
        val draftProjection =
            setOf(
                SharedThreadComposerDraftRecord(
                    key = "send=channel-1|parent=_|mention=channel-1",
                    sendChannelId = "channel-1"
                )
            ).toSharedThreadComposerDraftProjection()

        val item =
            SharedHomeConversationRowProjector(
                directoryProjections = mapOf("channel-1" to directoryProjection),
                draftProjection = draftProjection
            ).project(seed)

        assertEquals("fresh-name", item.name)
        assertEquals("private", item.channelType)
        assertEquals(7, item.unreadCount)
        assertEquals("fresh-subtitle", item.description)
        assertTrue(item.hasDraft)
        assertTrue(item.joined)
        assertTrue(item.archived)
    }

    @Test
    fun homeConversationItemKeepsSeedKindWhenDirectoryProjectionDisagrees() {
        val seed =
            SharedHomeConversationRowSeed(
                id = "dm-cindy",
                kind = ConversationKind.DirectMessage,
                fallbackName = "Cindy",
                fallbackPresenceMemberId = "user-cindy",
                fallbackChannelType = "dm"
            )
        val wrongProjection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "channel-cindy",
                aliases = setOf("dm-cindy", "channel-cindy"),
                conversationKind = ConversationKind.Channel,
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                channelKind = "channel",
                title = "Cindy",
                peerIdentity =
                    MemberDetailIdentity(
                        memberId = MemberId("user-cindy"),
                        kind = MemberKind.Human,
                        sourceServerId = ServerId("server-1")
                    ),
                joined = true
            )

        val item =
            SharedHomeConversationRowProjector(
                directoryProjections = mapOf("dm-cindy" to wrongProjection)
            ).project(seed)

        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, item.kind)
        assertEquals("dm", item.channelType)
        assertEquals("dm", item.homeChannelKind())
        assertEquals("user-cindy", item.presenceMemberId)
    }

    // task #444: the row kind must serialize through the lossless wire kind — the surface
    // projection folds Joint into "channel", which emptied the Joint Channels section.
    @Test
    fun homeConversationItemKeepsJointKindDistinctFromChannelSurface() {
        val jointItem =
            SharedHomeConversationRowProjector().project(
                SharedHomeConversationRowSeed(
                    id = "joint-1",
                    kind = ConversationKind.Joint,
                    fallbackName = "raft-mobile",
                    fallbackChannelType = "joint"
                )
            )
        val channelItem =
            SharedHomeConversationRowProjector().project(
                SharedHomeConversationRowSeed(
                    id = "channel-1",
                    kind = ConversationKind.Channel,
                    fallbackName = "general"
                )
            )

        // The Joint section filter is `kind == JointChannelKindWireValue` — a joint row
        // must carry the distinct wire kind, and a regular channel must not.
        assertEquals(JointChannelKindWireValue, jointItem.kind)
        assertEquals(ConversationKind.Joint, parseConversationKindOrNull(jointItem.kind))
        assertEquals(JointChannelKindWireValue, jointItem.homeChannelKind())
        assertEquals(KuiklyThreadSurfaceKind.Channel, channelItem.kind)
    }

    @Test
    fun homeConversationItemDoesNotLetGenericDirectoryTitleReplaceFallbackName() {
        val seed =
            SharedHomeConversationRowSeed(
                id = "channel-1",
                kind = ConversationKind.Channel,
                fallbackName = "raft-mobile"
            )
        val directoryProjection =
            SharedConversationDirectoryProjection(
                serverId = "server-1",
                canonicalChannelId = "channel-1",
                aliases = setOf("channel-1"),
                conversationKind = ConversationKind.Channel,
                surfaceKind = KuiklyThreadSurfaceKind.Channel,
                channelKind = "channel",
                title = "channel-1"
            )

        val item =
            SharedHomeConversationRowProjector(
                directoryProjections = mapOf("channel-1" to directoryProjection)
            ).project(seed)

        assertEquals("raft-mobile", item.name)
    }

    @Test
    fun homeConversationItemKeepsDmMemberIdentityWithoutAvatarFallback() {
        val conversation =
            ConversationSummary(
                threadId = ThreadId("dm-agent-1"),
                channelId = ChannelId("dm-agent-1"),
                kind = ConversationKind.DirectMessage,
                name = "KMP-Expert",
                directMessagePeerType = "agent",
                presenceMemberId = MemberId("agent-1")
            ).toHomeDirectMessageRow()

        val item = SharedHomeConversationRowProjector().project(conversation)

        assertEquals("agent-1", item.presenceMemberId)
        assertEquals("agent", item.peerType)
    }

    @Test
    fun sidebarSelectionRequiresMatchingConversationSection() {
        val pinnedSelection =
            SlockHomeSidebarSelection(
                conversationId = "channel-1",
                conversationSection = SlockHomeConversationSection.Pinned
            )
        val pinnedSectionSelection = pinnedSelection.sectionSelection(SlockHomeConversationSection.Pinned)
        val channelSectionSelection = pinnedSelection.sectionSelection(SlockHomeConversationSection.Channel)

        assertEquals(SlockHomeConversationSection.Pinned, pinnedSectionSelection.section)
        assertTrue(pinnedSectionSelection.selects("channel-1"))
        assertFalse(channelSectionSelection.selects("channel-1"))
        assertFalse(pinnedSectionSelection.selects("channel-2"))
    }

    @Test
    fun homeSidebarSelectionPrefersPinnedSectionForPinnedIdEvenWhenInChannelOrDm() {
        // #211: a pinned id that is still listed in `channels` /
        // `directMessages` (the server returns the full set; the visual
        // layer hides the original copies) must resolve to the Pinned
        // section. Otherwise the original row would be highlighted while
        // the Pinned row is not.
        assertEquals(
            SlockHomeSidebarSelection(conversationId = "channel-1", conversationSection = SlockHomeConversationSection.Pinned),
            homeSidebarSelection(
                selectedChannelId = "channel-1",
                selectedConversationSection = null,
                channels = listOf(SlockHomeConversationItem(id = "channel-1", name = "home")),
                directMessages = emptyList(),
                searchSelected = false,
                activitySelected = false,
                savedSelected = false,
                pinnedChannelIds = setOf("channel-1")
            )
        )
        assertEquals(
            SlockHomeSidebarSelection(conversationId = "dm-1", conversationSection = SlockHomeConversationSection.Pinned),
            homeSidebarSelection(
                selectedChannelId = "dm-1",
                selectedConversationSection = null,
                channels = emptyList(),
                directMessages = listOf(SlockHomeConversationItem(id = "dm-1", name = "artin")),
                searchSelected = false,
                activitySelected = false,
                savedSelected = false,
                pinnedChannelIds = setOf("dm-1")
            )
        )
    }

    @Test
    fun pinnedItemsResolvePinnedAgentIdsThroughAgentDirectMessages() {
        val agentDm =
            SlockHomeConversationItem(
                id = "dm-agent-1",
                name = "Cindy",
                kind = KuiklyThreadSurfaceKind.DirectMessage,
                channelType = "dm",
                peerType = "agent",
                presenceMemberId = "agent-1"
            )
        val channel =
            SlockHomeConversationItem(
                id = "channel-1",
                name = "general",
                kind = KuiklyThreadSurfaceKind.Channel,
                channelType = "channel"
            )

        val pinned =
            pinnedItemsForSidebar(
                channels = listOf(channel),
                directMessages = listOf(agentDm),
                pinnedIds = listOf("channel-1"),
                pinnedAgentIds = listOf("agent-1"),
                pinnedOrder = listOf("agent-1", "channel-1"),
                mode = SlockSidebarSortMode.Manual
            )

        assertEquals(listOf("dm-agent-1", "channel-1"), pinned.map { it.id })
    }

    @Test
    fun pinnedItemsDropPinnedAgentIdsWithoutDirectMessageRows() {
        val channel =
            SlockHomeConversationItem(
                id = "channel-1",
                name = "general",
                kind = KuiklyThreadSurfaceKind.Channel,
                channelType = "channel"
            )

        val pinned =
            pinnedItemsForSidebar(
                channels = listOf(channel),
                directMessages = emptyList(),
                pinnedIds = listOf("channel-1"),
                pinnedAgentIds = listOf("agent-missing"),
                pinnedOrder = listOf("agent-missing", "channel-1"),
                mode = SlockSidebarSortMode.Manual
            )

        assertEquals(listOf("channel-1"), pinned.map { it.id })
    }

    @Test
    fun pinnedItemsDeduplicateSameAgentDirectMessageAcrossPinFamiliesInAzMode() {
        val agentDm =
            SlockHomeConversationItem(
                id = "dm-agent-1",
                name = "Cindy",
                kind = KuiklyThreadSurfaceKind.DirectMessage,
                channelType = "dm",
                peerType = "agent",
                presenceMemberId = "agent-1"
            )
        val otherDm =
            SlockHomeConversationItem(
                id = "dm-human-1",
                name = "Alice",
                kind = KuiklyThreadSurfaceKind.DirectMessage,
                channelType = "dm",
                peerType = "human",
                presenceMemberId = "user-1"
            )

        val pinned =
            pinnedItemsForSidebar(
                channels = emptyList(),
                directMessages = listOf(agentDm, otherDm),
                pinnedIds = listOf("dm-agent-1", "dm-human-1"),
                pinnedAgentIds = listOf("agent-1"),
                pinnedOrder = listOf("dm-agent-1", "agent-1", "dm-human-1"),
                mode = SlockSidebarSortMode.Az
            )

        assertEquals(listOf("dm-human-1", "dm-agent-1"), pinned.map { it.id })
    }

    @Test
    fun pinnedRowsRenderTypedAgentWithoutExistingDirectMessageFromMemberProfile() {
        val pinned =
            pinnedRowsForSidebar(
                channels = emptyList(),
                directMessages = emptyList(),
                pinnedIds = emptyList(),
                pinnedOrder = emptyList(),
                typedPinnedItems = listOf(SettingsServerSidebarPinnedItem(kind = "agent", id = "agent-1")),
                pinnedMemberProfileResolver = { memberId ->
                    if (memberId == "agent-1") {
                        SlockHomePinnedMemberItem(
                            identity = MemberDetailIdentity(MemberId("agent-1"), MemberKind.Agent),
                            name = "Cindy",
                            description = "@cindy",
                            avatarLabel = "C"
                        )
                    } else {
                        null
                    }
                },
                mode = SlockSidebarSortMode.Manual
            )

        assertEquals(listOf("agent:agent-1"), pinned.map { it.key })
        assertEquals(listOf("Cindy"), pinned.map { it.member?.name })
        assertEquals(listOf(null), pinned.map { it.conversation?.id })
    }

    @Test
    fun pinnedMemberRowsRemainLongPressableAndParticipateInManualSort() {
        val member =
            SlockHomePinnedMemberItem(
                identity = MemberDetailIdentity(MemberId("agent-1"), MemberKind.Agent),
                name = "Cindy",
                description = "@cindy",
                avatarLabel = "C"
            )
        val memberRow = SlockHomePinnedRow(key = "agent:agent-1", member = member)
        val conversation =
            SlockHomeConversationItem(
                id = "channel-1",
                name = "general",
                kind = KuiklyThreadSurfaceKind.Channel,
                channelType = "channel"
            )
        val conversationRow = SlockHomePinnedRow(key = "channel:channel-1", conversation = conversation)

        assertTrue(memberRow.supportsLongPress())
        assertEquals(
            listOf("agent-1", "channel-1"),
            listOf(memberRow, conversationRow).mapNotNull { it.toSidebarSortItem()?.id }
        )
        assertEquals("dm", memberRow.toSidebarSortItem()?.channelType)
        assertEquals("agent", memberRow.toSidebarSortItem()?.peerType)
    }

    @Test
    fun pinnedMemberLongPressMenuOffersSectionSortWithoutConversationActions() {
        assertEquals(
            listOf(
                SlockBaseMenuItem(
                    id = "sort-channels",
                    label = "Sort",
                    icon = SlockLucideIconName.ArrowUpDown
                )
            ),
            slockHomePinnedMemberMenuItems()
        )
    }

    @Test
    fun pinnedRowsResolveTypedAgentThroughExistingDirectMessage() {
        val agentDm =
            SlockHomeConversationItem(
                id = "dm-agent-1",
                name = "Cindy",
                kind = KuiklyThreadSurfaceKind.DirectMessage,
                channelType = "dm",
                peerType = "agent",
                presenceMemberId = "agent-1"
            )

        val pinned =
            pinnedRowsForSidebar(
                channels = emptyList(),
                directMessages = listOf(agentDm),
                pinnedIds = emptyList(),
                pinnedOrder = emptyList(),
                typedPinnedItems = listOf(SettingsServerSidebarPinnedItem(kind = "agent", id = "agent-1")),
                pinnedMemberProfileResolver = {
                    SlockHomePinnedMemberItem(
                        identity = MemberDetailIdentity(MemberId("agent-1"), MemberKind.Agent),
                        name = "Stale Cindy",
                        avatarLabel = "S"
                    )
                },
                mode = SlockSidebarSortMode.Manual
            )

        assertEquals(listOf("dm-agent-1"), pinned.map { it.conversation?.id })
        assertEquals(listOf(null), pinned.map { it.member?.identity?.memberId })
    }

    @Test
    fun pinnedRowsDeduplicateTypedMemberAndDirectMessageInAzMode() {
        val agentDm =
            SlockHomeConversationItem(
                id = "dm-agent-1",
                name = "Cindy",
                kind = KuiklyThreadSurfaceKind.DirectMessage,
                channelType = "dm",
                peerType = "agent",
                presenceMemberId = "agent-1"
            )
        val humanDm =
            SlockHomeConversationItem(
                id = "dm-human-1",
                name = "Alice",
                kind = KuiklyThreadSurfaceKind.DirectMessage,
                channelType = "dm",
                peerType = "human",
                presenceMemberId = "user-1"
            )

        val pinned =
            pinnedRowsForSidebar(
                channels = emptyList(),
                directMessages = listOf(agentDm, humanDm),
                pinnedIds = listOf("dm-agent-1", "dm-human-1"),
                pinnedAgentIds = listOf("agent-1"),
                pinnedOrder = listOf("dm-agent-1", "agent-1", "dm-human-1"),
                typedPinnedItems =
                    listOf(
                        SettingsServerSidebarPinnedItem(kind = "channel", id = "dm-agent-1"),
                        SettingsServerSidebarPinnedItem(kind = "agent", id = "agent-1"),
                        SettingsServerSidebarPinnedItem(kind = "human", id = "user-1")
                    ),
                mode = SlockSidebarSortMode.Az
            )

        assertEquals(listOf("dm-human-1", "dm-agent-1"), pinned.map { it.conversation?.id })
    }

    @Test
    fun homeLazyItemCountIncludesVisibleSectionsAndRows() {
        assertEquals(
            15,
            homeLazyItemCount(
                channelsCount = 3,
                jointCount = 0,
                directMessagesCount = 2,
                channelsExpanded = true,
                jointChannelsExpanded = true,
                directMessagesExpanded = true
            )
        )
        assertEquals(
            10,
            homeLazyItemCount(
                channelsCount = 3,
                jointCount = 0,
                directMessagesCount = 2,
                channelsExpanded = false,
                jointChannelsExpanded = false,
                directMessagesExpanded = false
            )
        )
        assertEquals(
            9,
            homeLazyItemCount(
                channelsCount = 3,
                jointCount = 0,
                directMessagesCount = 2,
                channelsExpanded = false,
                jointChannelsExpanded = false,
                directMessagesExpanded = false,
                hasPullToRefreshItem = false
            )
        )
        assertEquals(
            18,
            homeLazyItemCount(
                channelsCount = 0,
                jointCount = 0,
                directMessagesCount = 0,
                channelsExpanded = false,
                jointChannelsExpanded = false,
                directMessagesExpanded = false,
                hasInlineError = true,
                channelSkeletonCount = 4,
                directMessageSkeletonCount = 3
            )
        )
    }

    @Test
    fun homeLazyItemCountTracksChannelAndJointSectionsIndependently() {
        assertEquals(
            16,
            homeLazyItemCount(
                channelsCount = 3,
                jointCount = 2,
                directMessagesCount = 2,
                channelsExpanded = false,
                jointChannelsExpanded = true,
                directMessagesExpanded = true
            )
        )
        assertEquals(
            17,
            homeLazyItemCount(
                channelsCount = 3,
                jointCount = 2,
                directMessagesCount = 2,
                channelsExpanded = true,
                jointChannelsExpanded = false,
                directMessagesExpanded = true
            )
        )
    }

    @Test
    fun homeChannelSectionsPutJointChannelsBeforeRegularChannels() {
        fun order(hasJointChannels: Boolean) =
            homeSidebarSectionOrder(
                sectionOrder = emptyList(),
                customSections = emptyList(),
                customSectionRows = emptyList(),
                hasPinned = false,
                hasJointChannels = hasJointChannels
            )
        assertEquals(
            listOf(SlockHomeSidebarSection.JointChannels, SlockHomeSidebarSection.Channels, SlockHomeSidebarSection.DirectMessages),
            order(hasJointChannels = true)
        )
        assertEquals(
            listOf(SlockHomeSidebarSection.Channels, SlockHomeSidebarSection.DirectMessages),
            order(hasJointChannels = false)
        )
    }

    @Test
    fun sharedListBeyondBoundsNeverExceedsActualItemsOrGrowsUnbounded() {
        assertEquals(0, sharedListBeyondBoundsItemCount(0))
        assertEquals(2, sharedListBeyondBoundsItemCount(2))
        assertEquals(12, sharedListBeyondBoundsItemCount(12))
        assertEquals(96, sharedListBeyondBoundsItemCount(400))
    }

    @Test
    fun homeListBeyondBoundsTracksOneViewportWithBounds() {
        assertEquals(0, homeListInitialBeyondBoundsItemCount())
        assertEquals(0, homeListBeyondBoundsItemCount(itemCount = 0, viewportHeightDp = 0f))
        assertEquals(8, homeListBeyondBoundsItemCount(itemCount = 8, viewportHeightDp = 720f))
        assertEquals(18, homeListBeyondBoundsItemCount(itemCount = 40, viewportHeightDp = 600f))
        assertEquals(22, homeListBeyondBoundsItemCount(itemCount = 400, viewportHeightDp = 720f))
        assertEquals(24, homeListBeyondBoundsItemCount(itemCount = 400, viewportHeightDp = 2_000f))
        assertFalse(homeListShouldRampBeyondBounds(contentRefreshing = true))
        assertTrue(homeListShouldRampBeyondBounds(contentRefreshing = false))
        assertEquals(6, homeListNextServerSwitchBeyondBoundsItemCount(current = 0, target = 24))
        assertEquals(4, homeListNextServerSwitchBeyondBoundsItemCount(current = 0, target = 4))
        assertEquals(
            14,
            homeListBeyondBoundsItemCount(
                itemCount = 400,
                viewportHeightDp = 720f,
                representativeRowStepDp = 52f
            )
        )
    }

    @Test
    fun viewportBeyondBoundsFallsBackSafelyForInvalidMetricsAndBounds() {
        assertEquals(
            0,
            slockViewportBeyondBoundsItemCount(
                itemCount = -1,
                viewportHeightDp = Float.NaN,
                representativeRowStepDp = 0f,
                minimumItems = -3,
                maximumItems = -1
            )
        )
        assertEquals(
            6,
            slockViewportBeyondBoundsItemCount(
                itemCount = 20,
                viewportHeightDp = Float.POSITIVE_INFINITY,
                representativeRowStepDp = 34f,
                minimumItems = 6,
                maximumItems = 24
            )
        )
        assertEquals(
            8,
            slockViewportBeyondBoundsItemCount(
                itemCount = 20,
                viewportHeightDp = 720f,
                representativeRowStepDp = Float.NaN,
                minimumItems = 8,
                maximumItems = 4
            )
        )
        assertEquals(
            5,
            slockViewportBeyondBoundsItemCount(
                itemCount = 5,
                viewportHeightDp = 720f,
                representativeRowStepDp = 34f,
                minimumItems = 6,
                maximumItems = 24,
                viewportFraction = Float.NEGATIVE_INFINITY
            )
        )
    }

    @Test
    fun homeServerSwitcherKeepsSmallFixedPremeasureWindow() {
        assertEquals(0, homeServerSwitcherBeyondBoundsItemCount(0))
        assertEquals(2, homeServerSwitcherBeyondBoundsItemCount(2))
        assertEquals(6, homeServerSwitcherBeyondBoundsItemCount(40))
    }

    @Test
    fun homePullToRefreshFollowsPayloadRefreshCapability() {
        assertTrue(homePullToRefreshEnabled(contentRefreshEnabled = true, platformSupportsPullToRefresh = true))
        assertFalse(homePullToRefreshEnabled(contentRefreshEnabled = true, platformSupportsPullToRefresh = false))
        assertFalse(homePullToRefreshEnabled(contentRefreshEnabled = false))
    }

    @Test
    fun homeHeaderRefreshIndicatorStaysVisibleDuringPullRefresh() {
        assertTrue(homeHeaderRefreshIndicatorVisible(contentRefreshing = true, pullRefreshing = false))
        assertTrue(homeHeaderRefreshIndicatorVisible(contentRefreshing = true, pullRefreshing = true))
        assertTrue(homeHeaderRefreshIndicatorVisible(contentRefreshing = false, pullRefreshing = true))
        assertFalse(homeHeaderRefreshIndicatorVisible(contentRefreshing = false, pullRefreshing = false))
    }

    @Test
    fun pullRefreshIndicatorShowsDragProgressBeforeRefreshing() {
        assertFalse(slockPullRefreshIndicatorVisible(pullProgress = 0f, isRefreshing = false))
        assertTrue(slockPullRefreshIndicatorVisible(pullProgress = 0.1f, isRefreshing = false))
        assertTrue(slockPullRefreshIndicatorVisible(pullProgress = 0f, isRefreshing = true))

        assertEquals(0f, slockPullRefreshIndicatorRotationDegrees(pullProgress = 0f, isRefreshing = false))
        assertEquals(180f, slockPullRefreshIndicatorRotationDegrees(pullProgress = 0.5f, isRefreshing = false))
        assertEquals(360f, slockPullRefreshIndicatorRotationDegrees(pullProgress = 2f, isRefreshing = false))
        assertEquals(0f, slockPullRefreshIndicatorRotationDegrees(pullProgress = 0.5f, isRefreshing = true))
    }

    @Test
    fun sharedHomeHeaderRefreshingTracksLoadedContentRefreshState() {
        val refreshing =
            SharedHomeState(
                content =
                    SharedHomeContentState.Content(
                        snapshot = testHomeSnapshot(),
                        session = testAuthSession(),
                        contentRefreshing = true
                    )
            )
        val idle =
            SharedHomeState(
                content =
                    SharedHomeContentState.Content(
                        snapshot = testHomeSnapshot(),
                        session = testAuthSession(),
                        contentRefreshing = false
                    )
            )

        assertTrue(sharedHomeHeaderRefreshing(refreshing))
        assertFalse(sharedHomeHeaderRefreshing(idle))
        assertFalse(sharedHomeHeaderRefreshing(SharedHomeState(content = SharedHomeContentState.Loading())))
    }

    @Test
    fun sidebarSortModesMatchReactOrdering() {
        val manual =
            listOf(
                SlockHomeConversationItem(id = "b", name = "Beta", lastMessageAt = "2026-05-19T10:00:00Z"),
                SlockHomeConversationItem(id = "a", name = "Alpha", lastMessageAt = "2026-05-20T10:00:00Z"),
                SlockHomeConversationItem(id = "c", name = "gamma", lastMessageAt = "2026-05-18T10:00:00Z")
            )

        assertEquals(listOf("b", "a", "c"), manual.sortedForSidebar(SlockSidebarSortMode.Manual) { it.name }.map { it.id })
        assertEquals(listOf("a", "b", "c"), manual.sortedForSidebar(SlockSidebarSortMode.Recent) { it.name }.map { it.id })
        assertEquals(listOf("a", "b", "c"), manual.sortedForSidebar(SlockSidebarSortMode.Az) { it.name }.map { it.id })
    }

    @Test
    fun sidebarRegularChannelsPinAllBeforeServerOrderAndSortModes() {
        val channels =
            listOf(
                SlockHomeConversationItem(id = "visual", name = "visual-testing", lastMessageAt = "2026-05-20T10:00:00Z"),
                SlockHomeConversationItem(id = "all", name = "all", lastMessageAt = "2026-05-18T10:00:00Z"),
                SlockHomeConversationItem(id = "artifacts", name = "artifacts", lastMessageAt = "2026-05-19T10:00:00Z")
            )

        val manual =
            channels
                .orderedBySidebarIds(listOf("visual", "artifacts"), pinnedFirstId = "all")
                .sortedForSidebar(SlockSidebarSortMode.Manual, pinnedFirstId = "all") { it.name }
        val recent =
            channels
                .orderedBySidebarIds(listOf("visual", "artifacts"), pinnedFirstId = "all")
                .sortedForSidebar(SlockSidebarSortMode.Recent, pinnedFirstId = "all") { it.name }
        val az =
            channels
                .orderedBySidebarIds(listOf("visual", "artifacts"), pinnedFirstId = "all")
                .sortedForSidebar(SlockSidebarSortMode.Az, pinnedFirstId = "all") { it.name }

        assertEquals(listOf("all", "visual", "artifacts"), manual.map { it.id })
        assertEquals(listOf("all", "visual", "artifacts"), recent.map { it.id })
        assertEquals(listOf("all", "artifacts", "visual"), az.map { it.id })
    }

    @Test
    fun sidebarChannelSortOverlayExcludesPinnedAllChannel() {
        val displayedChannels =
            listOf(
                SlockHomeConversationItem(id = "all", name = "all"),
                SlockHomeConversationItem(id = "visual", name = "visual-testing"),
                SlockHomeConversationItem(id = "artifacts", name = "artifacts")
            )

        val sortableChannels = displayedChannels.withoutSidebarPinnedItem("all")

        assertEquals(listOf("visual", "artifacts"), sortableChannels.map { it.id })
    }

    @Test
    fun sidebarDmManualOrderFollowsServerOrderAndAppendsNewRows() {
        val directMessages =
            listOf(
                SlockHomeConversationItem(id = "dm-a", name = "Alpha"),
                SlockHomeConversationItem(id = "dm-b", name = "Beta"),
                SlockHomeConversationItem(id = "dm-c", name = "Charlie"),
                SlockHomeConversationItem(id = "dm-d", name = "Delta")
            )

        val ordered =
            directMessages
                .orderedBySidebarIds(listOf("dm-c", "missing", "dm-a", "dm-c"))
                .sortedForSidebar(SlockSidebarSortMode.Manual) { it.name }

        assertEquals(listOf("dm-c", "dm-a", "dm-b", "dm-d"), ordered.map { it.id })
    }

    @Test
    fun resolveSortTargetIndexPicksRowUnderPointer() {
        val rows =
            listOf(
                SlockSortRowBounds(index = 0, top = 0f, size = 32f),
                SlockSortRowBounds(index = 1, top = 34f, size = 32f),
                SlockSortRowBounds(index = 2, top = 68f, size = 32f),
            )

        assertEquals(0, resolveSortTargetIndex(10f, rows, lastIndex = 2))
        assertEquals(1, resolveSortTargetIndex(45f, rows, lastIndex = 2))
        assertEquals(2, resolveSortTargetIndex(80f, rows, lastIndex = 2))
    }

    @Test
    fun resolveSortTargetIndexTreatsRowGapsAsTheNextRow() {
        // Rows are laid out with spacing (Arrangement.spacedBy): 32..34 and
        // 66..68 are gaps. A pointer inside a gap must resolve to the next row
        // down — the old strict-containment check fell back to the LAST visible
        // row, snapping the insertion indicator to the end whenever the finger
        // crossed a gap (task #356 device feedback).
        val rows =
            listOf(
                SlockSortRowBounds(index = 0, top = 0f, size = 32f),
                SlockSortRowBounds(index = 1, top = 34f, size = 32f),
                SlockSortRowBounds(index = 2, top = 68f, size = 32f),
                SlockSortRowBounds(index = 3, top = 102f, size = 32f),
            )

        assertEquals(1, resolveSortTargetIndex(33f, rows, lastIndex = 3))
        assertEquals(2, resolveSortTargetIndex(67f, rows, lastIndex = 3))
        assertEquals(3, resolveSortTargetIndex(101f, rows, lastIndex = 3))
    }

    @Test
    fun resolveSortTargetIndexClampsBeyondVisibleRows() {
        // Visible window is only indices 3..4 (list scrolled); pointer above the
        // first visible row clamps to it, below the last clamps to it, and the
        // result never exceeds lastIndex.
        val rows =
            listOf(
                SlockSortRowBounds(index = 3, top = 0f, size = 32f),
                SlockSortRowBounds(index = 4, top = 34f, size = 32f),
            )

        assertEquals(3, resolveSortTargetIndex(-50f, rows, lastIndex = 9))
        assertEquals(4, resolveSortTargetIndex(500f, rows, lastIndex = 9))
        assertEquals(0, resolveSortTargetIndex(10f, emptyList(), lastIndex = 9))
    }

    @Test
    fun resolveSortAutoScrollDeltaScrollsNearViewportEdges() {
        assertEquals(-10f, resolveSortAutoScrollDelta(pointerY = 15f, viewportStart = 0f, viewportEnd = 320f, edgeScrollZone = 56f, edgeScrollStep = 10f, canScrollBackward = true, canScrollForward = true))
        assertEquals(10f, resolveSortAutoScrollDelta(pointerY = 280f, viewportStart = 0f, viewportEnd = 320f, edgeScrollZone = 56f, edgeScrollStep = 10f, canScrollBackward = true, canScrollForward = true))
        assertEquals(0f, resolveSortAutoScrollDelta(pointerY = 160f, viewportStart = 0f, viewportEnd = 320f, edgeScrollZone = 56f, edgeScrollStep = 10f, canScrollBackward = true, canScrollForward = true))
    }

    @Test
    fun resolveSortAutoScrollDeltaIgnoresInvalidViewportOrStep() {
        assertEquals(0f, resolveSortAutoScrollDelta(pointerY = 10f, viewportStart = 100f, viewportEnd = 100f, edgeScrollZone = 56f, edgeScrollStep = 10f, canScrollBackward = true, canScrollForward = true))
        assertEquals(0f, resolveSortAutoScrollDelta(pointerY = 10f, viewportStart = 0f, viewportEnd = 320f, edgeScrollZone = 0f, edgeScrollStep = 10f, canScrollBackward = true, canScrollForward = true))
        assertEquals(0f, resolveSortAutoScrollDelta(pointerY = 10f, viewportStart = 0f, viewportEnd = 320f, edgeScrollZone = 56f, edgeScrollStep = 0f, canScrollBackward = true, canScrollForward = true))
    }

    @Test
    fun resolveSortAutoScrollDeltaStopsAtContentBoundaries() {
        assertEquals(0f, resolveSortAutoScrollDelta(pointerY = 15f, viewportStart = 0f, viewportEnd = 320f, edgeScrollZone = 56f, edgeScrollStep = 10f, canScrollBackward = false, canScrollForward = true))
        assertEquals(0f, resolveSortAutoScrollDelta(pointerY = 280f, viewportStart = 0f, viewportEnd = 320f, edgeScrollZone = 56f, edgeScrollStep = 10f, canScrollBackward = true, canScrollForward = false))
    }

    @Test
    fun resolveSortDraggedRowTopClampsTheFloatingRowInsideTheViewport() {
        assertEquals(0f, resolveSortDraggedRowTop(pointerY = -100f, viewportStart = 0f, viewportEnd = 320f, rowSize = 52f))
        assertEquals(134f, resolveSortDraggedRowTop(pointerY = 160f, viewportStart = 0f, viewportEnd = 320f, rowSize = 52f))
        assertEquals(268f, resolveSortDraggedRowTop(pointerY = 600f, viewportStart = 0f, viewportEnd = 320f, rowSize = 52f))
        assertEquals(10f, resolveSortDraggedRowTop(pointerY = 60f, viewportStart = 10f, viewportEnd = 100f, rowSize = 120f))
    }

    @Test
    fun moveItemKeepsOrderWhenTargetIsUnchangedOrMissing() {
        val order = listOf("a", "b", "c")

        assertEquals(listOf("b", "c", "a"), order.moveItem("a", 2))
        assertEquals(order, order.moveItem("missing", 1))
        assertEquals(order, order.moveItem("b", 1))
    }

    @Test
    fun visibleHomeNotificationsDropBlankItems() {
        val notifications =
            listOf(
                SlockHomeNotificationItem(id = "blank", title = "   ", body = ""),
                SlockHomeNotificationItem(id = "body", title = "", body = "Visible body"),
                SlockHomeNotificationItem(id = "title", title = "Visible title", body = "")
            )

        assertEquals(listOf("body", "title"), notifications.visibleHomeNotifications().map { it.id })
    }

    @Test
    fun notificationIconUsesMachineSpecificIconWhenProvided() {
        assertEquals(
            SlockLucideIconName.TriangleAlert,
            SlockHomeNotificationIcon.WifiOff.notificationIconName(SlockHomeNotificationKind.Warning)
        )
        assertEquals(
            SlockLucideIconName.CircleArrowUp,
            SlockHomeNotificationIcon.CircleArrowUp.notificationIconName(SlockHomeNotificationKind.Warning)
        )
        assertEquals(
            SlockLucideIconName.TriangleAlert,
            SlockHomeNotificationIcon.Default.notificationIconName(SlockHomeNotificationKind.Warning)
        )
    }

    @Test
    fun notificationPopoverTopOffsetLeavesHeaderClippingBounds() {
        assertEquals(66.dp, homeNotificationPopoverTopOffset(topSafeArea = 0.dp))
        assertEquals(90.dp, homeNotificationPopoverTopOffset(topSafeArea = 24.dp))
    }

    @Test
    fun notificationPopoverDismissesWhenSelectingAnotherBottomTab() {
        assertTrue(
            shouldDismissHomeNotificationCenterForTabSelection(
                expanded = true,
                selectedTabKey = "home",
                targetTabKey = "tasks"
            )
        )
        assertFalse(
            shouldDismissHomeNotificationCenterForTabSelection(
                expanded = true,
                selectedTabKey = "home",
                targetTabKey = "home"
            )
        )
        assertFalse(
            shouldDismissHomeNotificationCenterForTabSelection(
                expanded = false,
                selectedTabKey = "home",
                targetTabKey = "tasks"
            )
        )
    }

    @Test
    fun notificationActionButtonsUseBoldFontFamily() {
        assertEquals(slockFontFamilyForWeight(FontWeight.Bold), HomeNotificationActionButtonTextStyle.fontFamily)
        assertEquals(SlockType.ButtonBold.fontWeight, HomeNotificationActionButtonTextStyle.fontWeight)
    }

    @Test
    fun appUpdateNotificationActionUsesActiveShadowAfterDownloadStarts() {
        assertEquals(
            2.dp,
            SlockHomeNotificationItem(
                id = APP_UPDATE_HOME_NOTIFICATION_ID,
                actionLabel = "Download"
            ).primaryActionShadowOffset()
        )
        assertEquals(
            4.dp,
            SlockHomeNotificationItem(
                id = APP_UPDATE_HOME_NOTIFICATION_ID,
                actionLabel = "Downloading"
            ).primaryActionShadowOffset()
        )
        assertEquals(
            4.dp,
            SlockHomeNotificationItem(
                id = APP_UPDATE_HOME_NOTIFICATION_ID,
                actionLabel = "Install"
            ).primaryActionShadowOffset()
        )
        assertEquals(
            2.dp,
            SlockHomeNotificationItem(
                id = "machine-offline:1",
                actionLabel = "Install"
            ).primaryActionShadowOffset()
        )
    }

    @Test
    fun membersLazyItemCountIncludesAgentGroupsAndVisibleHumans() {
        val agents =
            listOf(
                testMember("agent-1", MemberKind.Agent, permissionBadges = listOf("computer:mac")),
                testMember("agent-2", MemberKind.Agent, permissionBadges = listOf("computer:mac")),
                testMember("agent-3", MemberKind.Agent, permissionBadges = listOf("computer:linux"))
            )
        assertEquals(
            12,
            membersLazyItemCount(
                agents = agents,
                humansCount = 2,
                agentsExpanded = true,
                humansExpanded = true
            )
        )
        assertEquals(
            5,
            membersLazyItemCount(
                agents = agents,
                humansCount = 2,
                agentsExpanded = false,
                humansExpanded = false
            )
        )
        assertEquals(
            4,
            membersLazyItemCount(
                agents = agents,
                humansCount = 2,
                agentsExpanded = false,
                humansExpanded = false,
                hasPullToRefreshItem = false
            )
        )
        assertEquals(
            4,
            membersLazyItemCount(
                agents = agents,
                humansCount = 2,
                agentsExpanded = false,
                humansExpanded = true,
                showHumanDirectory = false
            )
        )
    }

    @Test
    fun tasksLazyItemCountTracksCurrentModeAndErrors() {
        assertEquals(
            6,
            sharedTasksLazyItemCount(
                mode = TasksViewMode.Board,
                filteredTasksCount = 20,
                boardColumnCount = 3,
                hasNavigationError = true,
                hasStatusUpdateError = false
            )
        )
        assertEquals(
            3,
            sharedTasksLazyItemCount(
                mode = TasksViewMode.List,
                filteredTasksCount = 0,
                boardColumnCount = 3,
                hasNavigationError = false,
                hasStatusUpdateError = false
            )
        )
    }

    @Test
    fun homeConversationLaunchCarriesOnlyTopLevelConversationIdentity() {
        val payload =
            SlockHomePayload(
                instanceId = "home-page",
                channels =
                    listOf(
                        SlockHomeConversationItem(
                            id = "channel-1",
                            name = "Display title must stay out",
                            kind = "channel"
                        )
                    )
            )

        val launch = buildHomeConversationThreadLaunchPayload(payload, "channel-1")

        assertEquals("home-page:channel-1", launch?.instanceId)
        assertEquals(KuiklyThreadSurfaceKind.Channel, launch?.surfaceKind)
        assertEquals(null, launch?.threadId)
        assertEquals("channel-1", launch?.sourceThreadId)
        assertEquals("channel-1", launch?.streamChannelId)
        assertEquals(null, launch?.parentChannelId)
        assertEquals(null, launch?.parentMessageId)
        assertEquals(null, launch?.parentSurfaceKind)
        assertEquals(null, launch?.parentChannelKind)
        assertEquals(null, launch?.originParentMessageId)
    }

    @Test
    fun homeConversationLaunchDropsReplyThreadWithoutTypedParentIdentity() {
        val payload =
            SlockHomePayload(
                instanceId = "home-page",
                channels =
                    listOf(
                        SlockHomeConversationItem(
                            id = "thread-1",
                            name = "Display title must stay out",
                            kind = "thread"
                        )
                    )
            )

        assertEquals(null, buildHomeConversationThreadLaunchPayload(payload, "thread-1"))
    }

    @Test
    fun homeConversationLaunchDropsUnknownKindInsteadOfDefaultingChannel() {
        val payload =
            SlockHomePayload(
                instanceId = "home-page",
                channels =
                    listOf(
                        SlockHomeConversationItem(
                            id = "mystery-1",
                            name = "Mystery",
                            kind = "mystery"
                        )
                    )
            )

        assertEquals(null, buildHomeConversationThreadLaunchPayload(payload, "mystery-1"))
    }

    @Test
    fun homeDirectMessageLaunchDoesNotInventParentPair() {
        val payload =
            SlockHomePayload(
                instanceId = "home-page",
                directMessages =
                    listOf(
                        SlockHomeConversationItem(
                            id = "dm-agent-1",
                            name = "Product-UX-Designer",
                            kind = "dm",
                            peerType = "agent",
                            presenceMemberId = "agent-1"
                        )
                    )
            )

        val launch = buildHomeConversationThreadLaunchPayload(payload, "dm-agent-1")

        assertEquals("home-page:dm-agent-1", launch?.instanceId)
        assertEquals(KuiklyThreadSurfaceKind.DirectMessage, launch?.surfaceKind)
        assertEquals("dm-agent-1", launch?.threadId)
        assertEquals("dm-agent-1", launch?.sourceThreadId)
        assertEquals("dm-agent-1", launch?.streamChannelId)
        assertEquals(null, launch?.parentChannelId)
        assertEquals(null, launch?.parentMessageId)
        assertEquals("agent-1", launch?.dmPeerIdentity?.memberId?.value)
        assertEquals(MemberKind.Agent, launch?.dmPeerIdentity?.kind)
        assertEquals("agent-1", launch?.toInitialPayload("fallback")?.dmPeerIdentity?.memberId?.value)
    }

    @Test
    fun homeJointChannelLaunchCarriesChannelKind() {
        val payload =
            SlockHomePayload(
                instanceId = "home-page",
                channels =
                    listOf(
                        SlockHomeConversationItem(
                            id = "joint-1",
                            name = "partner",
                            kind = "joint",
                            channelType = "channel"
                        )
                    )
            )

        val launch = buildHomeConversationThreadLaunchPayload(payload, "joint-1")

        assertEquals(KuiklyThreadSurfaceKind.Channel, launch?.surfaceKind)
        assertEquals("joint", launch?.channelKind)
        assertEquals("joint-1", launch?.sourceThreadId)
        assertEquals("joint-1", launch?.streamChannelId)
    }

    @Test
    fun homeConversationOpenBarrierDropsRapidDuplicateOpen() {
        var now = 1_000L
        val barrier = HomeConversationOpenBarrier(windowMillis = 800L, nowMillis = { now })

        assertTrue(barrier.tryAcquire("home-page:channel-1"))
        assertFalse(barrier.tryAcquire("home-page:channel-1"))

        now += 799L
        assertFalse(barrier.tryAcquire("home-page:channel-1"))

        now += 1L
        assertTrue(barrier.tryAcquire("home-page:channel-1"))
    }

    @Test
    fun homeConversationOpenBarrierAllowsDifferentRows() {
        var now = 1_000L
        val barrier = HomeConversationOpenBarrier(windowMillis = 800L, nowMillis = { now })

        assertTrue(barrier.tryAcquire("home-page:channel-1"))
        assertTrue(barrier.tryAcquire("home-page:dm-1"))

        now += 100L
        assertFalse(barrier.tryAcquire("home-page:channel-1"))
        assertFalse(barrier.tryAcquire("home-page:dm-1"))
    }

    private fun testMember(
        id: String,
        kind: MemberKind,
        roleDescription: String? = null,
        permissionBadges: List<String> = emptyList()
    ): MemberProfile =
        MemberProfile(
            id = MemberId(id),
            displayName = id,
            handle = id,
            kind = kind,
            roleDescription = roleDescription,
            permissionBadges = permissionBadges
        )

    private fun testHomeSnapshot(): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(ServerId("server-1"), "Slock Community"),
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
        )

    private fun testActivityOwner(): ReadReceiptOwner =
        ReadReceiptOwner(
            environmentId = "https://raft.example",
            accountId = "account-1",
            serverId = "server-1",
        )

    private fun testServerSwitcherSession(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "account-1", email = "artin@example.com", displayName = "Artin"),
            accessToken = "token",
            refreshToken = "refresh",
            selectedServerId = "server-1",
            servers =
                listOf(
                    StoredAuthServer(id = "server-1", name = "Current", slug = "current"),
                    StoredAuthServer(id = "server-2", name = "Other", slug = "other"),
                ),
        )

    private fun testAuthSession(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "artin@example.com", displayName = "Artin"),
            accessToken = "token",
            refreshToken = "refresh",
            selectedServerId = "server-1"
        )
}
