package ai.slock.compose

import ai.slock.compose.data.HomeSnapshot
import ai.slock.compose.data.SharedHomeContentState
import ai.slock.compose.data.SharedHomeState
import ai.slock.compose.data.StoredAuthSession
import ai.slock.compose.data.StoredAuthUser
import ai.slock.compose.data.TasksViewMode
import ai.slock.compose.model.AgentStatus
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlockHomeComponentsTest {
    @Test
    fun homeDmStatusFallsBackToOfflineWhenPresenceIsMissing() {
        assertEquals(SlockPresence.Offline, homeDmStatusPresence(null))
    }

    @Test
    fun homeDmStatusKeepsKnownPresence() {
        assertEquals(SlockPresence.Online, homeDmStatusPresence(SlockPresence.Online))
        assertEquals(SlockPresence.Unknown, homeDmStatusPresence(SlockPresence.Unknown))
        assertEquals(SlockPresence.Offline, homeDmStatusPresence(SlockPresence.Offline))
    }

    @Test
    fun homeDmStatusIndicatorUsesAgentStatusBeforePresence() {
        val working = homeDmStatusIndicatorState(AgentStatus.Working.name, SlockPresence.Online)
        val idle = homeDmStatusIndicatorState(AgentStatus.Idle.name, SlockPresence.Offline)
        val fallback = homeDmStatusIndicatorState(null, SlockPresence.Unknown)

        assertEquals(AgentStatus.Working, working.status)
        assertEquals(SlockColor.BrandYellow, working.color)
        assertTrue(working.pulsing)
        assertEquals(AgentStatus.Idle, idle.status)
        assertEquals(SlockColor.AccentLime, idle.color)
        assertFalse(idle.pulsing)
        assertEquals(null, fallback.status)
        assertEquals(SlockColor.BrandYellow, fallback.color)
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
    }

    @Test
    fun localComposerDraftOverlayClearsStaleSnapshotDraftFlags() {
        val items =
            listOf(
                SlockHomeConversationItem(id = "channel-1", name = "home", hasDraft = true),
                SlockHomeConversationItem(id = "channel-2", name = "random", hasDraft = false)
            )

        val cleared = items.withAuthoritativeLocalComposerDrafts(emptySet())

        assertFalse(cleared.first { it.id == "channel-1" }.hasDraft)
        assertFalse(cleared.first { it.id == "channel-2" }.hasDraft)
    }

    @Test
    fun localComposerDraftOverlayMarksOnlyMatchingRootConversations() {
        val items =
            listOf(
                SlockHomeConversationItem(id = "channel-1", name = "home", hasDraft = false),
                SlockHomeConversationItem(id = "reply-thread-1", name = "reply", hasDraft = true)
            )

        val projected =
            items.withAuthoritativeLocalComposerDrafts(
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
                )
            )

        assertTrue(projected.first { it.id == "channel-1" }.hasDraft)
        assertFalse(projected.first { it.id == "reply-thread-1" }.hasDraft)
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
    fun homeLazyItemCountIncludesVisibleSectionsAndRows() {
        assertEquals(
            13,
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
            8,
            homeLazyItemCount(
                channelsCount = 3,
                jointCount = 0,
                directMessagesCount = 2,
                channelsExpanded = false,
                jointChannelsExpanded = false,
                directMessagesExpanded = false
            )
        )
    }

    @Test
    fun homeLazyItemCountTracksChannelAndJointSectionsIndependently() {
        assertEquals(
            14,
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
            15,
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
    fun sharedListBeyondBoundsPremeasuresShortListsWithoutGrowingUnbounded() {
        assertEquals(3, sharedListBeyondBoundsItemCount(0))
        assertEquals(12, sharedListBeyondBoundsItemCount(12))
        assertEquals(96, sharedListBeyondBoundsItemCount(400))
    }

    @Test
    fun homeListBeyondBoundsKeepsSceneEnterPremeasureSmall() {
        assertEquals(3, homeListBeyondBoundsItemCount(0))
        assertEquals(6, homeListBeyondBoundsItemCount(40))
        assertEquals(6, homeListBeyondBoundsItemCount(400))
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
    fun membersLazyItemCountIncludesAgentGroupsAndVisibleHumans() {
        val agents =
            listOf(
                testMember("agent-1", MemberKind.Agent, permissionBadges = listOf("computer:mac")),
                testMember("agent-2", MemberKind.Agent, permissionBadges = listOf("computer:mac")),
                testMember("agent-3", MemberKind.Agent, permissionBadges = listOf("computer:linux"))
            )
        assertEquals(
            11,
            membersLazyItemCount(
                agents = agents,
                humansCount = 2,
                agentsExpanded = true,
                humansExpanded = true
            )
        )
        assertEquals(
            4,
            membersLazyItemCount(
                agents = agents,
                humansCount = 2,
                agentsExpanded = false,
                humansExpanded = false
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
    fun homeConversationLaunchCarriesOnlyConversationIdentity() {
        val payload =
            SlockHomePayload(
                instanceId = "home-page",
                channels =
                    listOf(
                        SlockHomeConversationItem(
                            id = "thread-1",
                            name = "Display title must stay out",
                            kind = "thread",
                            avatarWireValue = "pixel:tree",
                            gravatarUrl = "https://www.gravatar.com/avatar/thread",
                            agentStatus = AgentStatus.Working.name
                        )
                    )
            )

        val launch = buildHomeConversationThreadLaunchPayload(payload, "thread-1")

        assertEquals("home-page:thread-1", launch?.instanceId)
        assertEquals(KuiklyThreadSurfaceKind.ReplyThread, launch?.surfaceKind)
        assertEquals("thread-1", launch?.threadId)
        assertEquals("thread-1", launch?.sourceThreadId)
        assertEquals("thread-1", launch?.streamChannelId)
        assertEquals(null, launch?.parentChannelId)
        assertEquals(null, launch?.parentMessageId)
        assertEquals(null, launch?.originParentMessageId)
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
                            avatarWireValue = "pixel:tree",
                            gravatarUrl = "https://www.gravatar.com/avatar/agent",
                            agentStatus = AgentStatus.Working.name
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

    private fun testAuthSession(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "artin@example.com", displayName = "Artin"),
            accessToken = "token",
            refreshToken = "refresh",
            selectedServerId = "server-1"
        )
}
