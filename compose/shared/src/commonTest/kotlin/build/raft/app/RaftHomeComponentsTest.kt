package build.raft.app

import build.raft.app.data.SharedConversationDirectoryProjection
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.HomeServerDrawerLoadStatus
import build.raft.app.data.HomeServerDrawerSectionState
import build.raft.app.data.HomeServerDrawerState
import build.raft.app.data.HomeServerActivityUnreadProjection
import build.raft.app.data.ReadReceiptOwner
import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.data.ScreenError
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.APP_UPDATE_HOME_NOTIFICATION_ID
import build.raft.app.model.AgentStatus
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.settings.SharedHomeConversationRowProjector
import build.raft.app.settings.SharedHomeConversationRowSeed
import build.raft.app.shell.HomeServerDrawerOpenGestureAdmission
import build.raft.app.shell.shouldOpenHomeServerDrawerFromContentGesture
import build.raft.app.thread.icon
import build.raft.app.thread.key
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftHomeComponentsTest {
    private val errorStrings = englishErrorPresentationStringsForTest()

    @Test
    fun homeInlineAndDrawerErrorsKeepDiagnosticCodesOutOfVisibleCopy() {
        val inlineError =
            RaftHomeInlineError(
                title = "Could not refresh Home",
                detail = "The last workspace remains available.",
                code = "home_network",
                actionLabel = "Retry"
            )
        val inlineVisibleCopy = inlineError.toVisibleCopy()
        val drawerError = RaftHomeServerDrawerRow.Error("server-1", "home_network", hasCache = true)

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
                    RaftHomeServerDrawerSection(
                        server = RaftServerSwitcherItem(id = "loading", name = "Loading", slug = "loading"),
                        expanded = true,
                        status = HomeServerDrawerLoadStatus.Loading
                    ),
                    RaftHomeServerDrawerSection(
                        server = RaftServerSwitcherItem(id = "cached", name = "Cached", slug = "cached"),
                        expanded = true,
                        status = HomeServerDrawerLoadStatus.ErrorWithCache,
                        errorCode = "home_network",
                        directMessages =
                            listOf(
                                RaftHomeConversationItem(
                                    id = "dm-1",
                                    name = "Cindy",
                                    kind = RaftThreadSurfaceKind.DirectMessage
                                )
                            )
                    ),
                    RaftHomeServerDrawerSection(
                        server = RaftServerSwitcherItem(id = "collapsed", name = "Collapsed", slug = "collapsed"),
                        expanded = false,
                        status = HomeServerDrawerLoadStatus.Loaded,
                        channels = listOf(RaftHomeConversationItem(id = "hidden", name = "Hidden", kind = "channel"))
                    )
                )
            )

        assertEquals(3, rows.filterIsInstance<RaftHomeServerDrawerRow.Skeleton>().size)
        assertTrue(rows.any { it.key == "conversation:cached:directmessage:dm-1" })
        assertEquals(
            RaftHomeServerDrawerRow.Error("cached", "home_network", hasCache = true),
            rows.filterIsInstance<RaftHomeServerDrawerRow.Error>().single()
        )
        assertFalse(rows.any { it.key.contains("hidden") })
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
            RaftHomeNotificationItem(
                id = build.raft.app.data.APP_UPDATE_HOME_NOTIFICATION_ID,
                title = "Update available"
            )
        val machine = RaftHomeNotificationItem(id = "machine-offline:1", title = "Offline")
        val notifications = listOf(machine, update)

        // artin 2026-08-06 ruling (task #113): app update keeps its independent
        // top banner and does not go through the notification center.
        assertEquals(update, notifications.homeAppUpdateBannerNotification())
        assertEquals(listOf(machine), notifications.nonAppUpdateHomeNotifications())
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
                        RaftServerSwitcherItem(id = "server-1", name = "Current", slug = "current", broaderUnreadCount = 8),
                        RaftServerSwitcherItem(id = "server-2", name = "Other", slug = "other", broaderUnreadCount = 0)
                    )
            )
        )
        assertTrue(
            homeServerSwitcherHasOtherUnread(
                currentServerId = "server-1",
                servers =
                    listOf(
                        RaftServerSwitcherItem(id = "server-1", name = "Current", slug = "current", broaderUnreadCount = 0),
                        RaftServerSwitcherItem(id = "server-2", name = "Other", slug = "other", broaderUnreadCount = 3)
                    )
            )
        )
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
            RaftServerSwitcherItem(
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
    fun homeHeaderIdentityUsesTheCurrentServerItemById() {
        val current =
            RaftServerSwitcherItem(
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
                        RaftServerSwitcherItem(
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
    fun homeServerSwitcherCommunityActionsFollowJoinedSlugsIndependently() {
        val labels = RaftHomeShellLabels()

        assertEquals(
            listOf(
                RaftServerSwitcherCommunityAction("community-cn", labels.joinChineseCommunityServer),
                RaftServerSwitcherCommunityAction("community", labels.joinCommunityServer)
            ),
            homeServerSwitcherJoinCommunityActions(
                servers = emptyList(),
                labels = labels,
                includeChineseCommunity = true
            )
        )
        assertEquals(
            listOf(RaftServerSwitcherCommunityAction("community-cn", labels.joinChineseCommunityServer)),
            homeServerSwitcherJoinCommunityActions(
                servers = listOf(RaftServerSwitcherItem(id = "server-1", name = "Community", slug = "community")),
                labels = labels,
                includeChineseCommunity = true
            )
        )
        assertEquals(
            listOf(RaftServerSwitcherCommunityAction("community", labels.joinCommunityServer)),
            homeServerSwitcherJoinCommunityActions(
                servers = listOf(RaftServerSwitcherItem(id = "server-cn", name = "Community CN", slug = "community-cn")),
                labels = labels,
                includeChineseCommunity = true
            )
        )
        assertEquals(
            emptyList(),
            homeServerSwitcherJoinCommunityActions(
                servers =
                    listOf(
                        RaftServerSwitcherItem(id = "server-cn", name = "Community CN", slug = "community-cn"),
                        RaftServerSwitcherItem(id = "server-1", name = "Community", slug = "community")
                    ),
                labels = labels,
                includeChineseCommunity = true
            )
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
    fun pinnedConversationsLeaveTheirSystemSectionLikeWeb() {
        // Web Sidebar.tsx: sortableChannels / orderedDms drop everything the typed Pinned
        // section shows, so "Channels 38" there is "41 minus 3 pinned" here.
        val channels =
            listOf(
                RaftHomeConversationItem(id = "c-1", name = "proj-mobile", kind = "channel"),
                RaftHomeConversationItem(id = "c-2", name = "proj-apps", kind = "channel"),
                RaftHomeConversationItem(id = "c-3", name = "proj-deploy", kind = "channel")
            )
        val pinned =
            pinnedRowsForSidebar(
                channels = channels,
                directMessages = emptyList(),
                pinnedIds = emptyList(),
                pinnedOrder = emptyList(),
                typedPinnedItems =
                    listOf(
                        SettingsServerSidebarPinnedItem(kind = "channel", id = "c-2"),
                        // A stale pin the Pinned section cannot render must not hide anything.
                        SettingsServerSidebarPinnedItem(kind = "channel", id = "gone")
                    ),
                mode = RaftSidebarSortMode.Manual
            )

        val pinnedIds = pinned.sidebarPinnedConversationIds()

        assertEquals(setOf("c-2"), pinnedIds)
        assertEquals(listOf("c-1", "c-3"), channels.filter { it.id !in pinnedIds }.map { it.id })
    }

    @Test
    fun homeConversationMenuAddsMuteActionOnlyWhenSupported() {
        val unsupported =
            RaftHomeConversationItem(
                id = "channel-unsupported",
                name = "unsupported",
                activityMuted = false,
                activityMuteSupported = false
            )
        val unmuted =
            RaftHomeConversationItem(
                id = "channel-unmuted",
                name = "unmuted",
                activityMuted = false,
                activityMuteSupported = true
            )
        val muted =
            RaftHomeConversationItem(
                id = "channel-muted",
                name = "muted",
                activityMuted = true,
                activityMuteSupported = true
            )

        assertFalse(
            raftHomeConversationMenuItems(
                conversation = unsupported,
                pinTargetIsPinned = false,
                sortableFromMenu = false,
                activeIsDirectMessage = false,
                activeCanArchive = false
            ).any { it.id == "mute-channel" || it.id == "unmute-channel" }
        )
        assertEquals(
            RaftBaseMenuItem(id = "mute-channel", label = "Mute", icon = RaftLucideIconName.BellOff),
            raftHomeConversationMenuItems(
                conversation = unmuted,
                pinTargetIsPinned = false,
                sortableFromMenu = false,
                activeIsDirectMessage = false,
                activeCanArchive = false
            ).single { it.id == "mute-channel" }
        )
        assertEquals(
            RaftBaseMenuItem(id = "unmute-channel", label = "Unmute", icon = RaftLucideIconName.Bell),
            raftHomeConversationMenuItems(
                conversation = muted,
                pinTargetIsPinned = false,
                sortableFromMenu = false,
                activeIsDirectMessage = false,
                activeCanArchive = false
            ).single { it.id == "unmute-channel" }
        )
    }

    @Test
    fun homeSidebarSelectionPrefersTopActionOverConversation() {
        assertEquals(
            RaftHomeSidebarSelection(topAction = RaftHomeSidebarTopSelection.Activity),
            homeSidebarSelection(
                selectedChannelId = "channel-1",
                selectedConversationSection = null,
                channels = listOf(RaftHomeConversationItem(id = "channel-1", name = "home")),
                directMessages = emptyList(),
                searchSelected = false,
                activitySelected = true,
                savedSelected = false
            )
        )
        assertEquals(
            RaftHomeSidebarSelection(topAction = RaftHomeSidebarTopSelection.Search),
            homeSidebarSelection(
                selectedChannelId = "channel-1",
                selectedConversationSection = null,
                channels = listOf(RaftHomeConversationItem(id = "channel-1", name = "home")),
                directMessages = emptyList(),
                searchSelected = true,
                activitySelected = false,
                savedSelected = false
            )
        )
        assertEquals(
            RaftHomeSidebarSelection(topAction = RaftHomeSidebarTopSelection.Saved),
            homeSidebarSelection(
                selectedChannelId = "channel-1",
                selectedConversationSection = null,
                channels = listOf(RaftHomeConversationItem(id = "channel-1", name = "home")),
                directMessages = emptyList(),
                searchSelected = false,
                activitySelected = false,
                savedSelected = true
            )
        )
    }

    @Test
    fun localComposerDraftProjectionClearsStaleSnapshotDraftFlags() {
        val staleSnapshotItem = RaftHomeConversationItem(id = "channel-1", name = "home", hasDraft = true)
        val projection = emptySet<SharedThreadComposerDraftRecord>().toSharedThreadComposerDraftProjection()

        assertFalse(projection.hasRootDraft(staleSnapshotItem.id))
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
                surfaceKind = RaftThreadSurfaceKind.Channel,
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
        assertEquals(RaftThreadSurfaceKind.Channel, channelItem.kind)
    }

    @Test
    fun homeSidebarSelectionPrefersPinnedSectionForPinnedIdEvenWhenInChannelOrDm() {
        // #211: a pinned id that is still listed in `channels` /
        // `directMessages` (the server returns the full set; the visual
        // layer hides the original copies) must resolve to the Pinned
        // section. Otherwise the original row would be highlighted while
        // the Pinned row is not.
        assertEquals(
            RaftHomeSidebarSelection(conversationId = "channel-1", conversationSection = RaftHomeConversationSection.Pinned),
            homeSidebarSelection(
                selectedChannelId = "channel-1",
                selectedConversationSection = null,
                channels = listOf(RaftHomeConversationItem(id = "channel-1", name = "home")),
                directMessages = emptyList(),
                searchSelected = false,
                activitySelected = false,
                savedSelected = false,
                pinnedChannelIds = setOf("channel-1")
            )
        )
        assertEquals(
            RaftHomeSidebarSelection(conversationId = "dm-1", conversationSection = RaftHomeConversationSection.Pinned),
            homeSidebarSelection(
                selectedChannelId = "dm-1",
                selectedConversationSection = null,
                channels = emptyList(),
                directMessages = listOf(RaftHomeConversationItem(id = "dm-1", name = "artin")),
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
            RaftHomeConversationItem(
                id = "dm-agent-1",
                name = "Cindy",
                kind = RaftThreadSurfaceKind.DirectMessage,
                channelType = "dm",
                peerType = "agent",
                presenceMemberId = "agent-1"
            )
        val channel =
            RaftHomeConversationItem(
                id = "channel-1",
                name = "general",
                kind = RaftThreadSurfaceKind.Channel,
                channelType = "channel"
            )

        val pinned =
            pinnedItemsForSidebar(
                channels = listOf(channel),
                directMessages = listOf(agentDm),
                pinnedIds = listOf("channel-1"),
                pinnedAgentIds = listOf("agent-1"),
                pinnedOrder = listOf("agent-1", "channel-1"),
                mode = RaftSidebarSortMode.Manual
            )

        assertEquals(listOf("dm-agent-1", "channel-1"), pinned.map { it.id })
    }

    @Test
    fun pinnedItemsDeduplicateSameAgentDirectMessageAcrossPinFamiliesInAzMode() {
        val agentDm =
            RaftHomeConversationItem(
                id = "dm-agent-1",
                name = "Cindy",
                kind = RaftThreadSurfaceKind.DirectMessage,
                channelType = "dm",
                peerType = "agent",
                presenceMemberId = "agent-1"
            )
        val otherDm =
            RaftHomeConversationItem(
                id = "dm-human-1",
                name = "Alice",
                kind = RaftThreadSurfaceKind.DirectMessage,
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
                mode = RaftSidebarSortMode.Az
            )

        assertEquals(listOf("dm-human-1", "dm-agent-1"), pinned.map { it.id })
    }

    @Test
    fun pinnedMemberRowsRemainLongPressableAndParticipateInManualSort() {
        val member =
            RaftHomePinnedMemberItem(
                identity = MemberDetailIdentity(MemberId("agent-1"), MemberKind.Agent),
                name = "Cindy",
                description = "@cindy",
                avatarLabel = "C"
            )
        val memberRow = RaftHomePinnedRow(key = "agent:agent-1", member = member)
        val conversation =
            RaftHomeConversationItem(
                id = "channel-1",
                name = "general",
                kind = RaftThreadSurfaceKind.Channel,
                channelType = "channel"
            )
        val conversationRow = RaftHomePinnedRow(key = "channel:channel-1", conversation = conversation)

        assertTrue(memberRow.supportsLongPress())
        assertEquals(
            listOf("agent-1", "channel-1"),
            listOf(memberRow, conversationRow).mapNotNull { it.toSidebarSortItem()?.id }
        )
        assertEquals("dm", memberRow.toSidebarSortItem()?.channelType)
        assertEquals("agent", memberRow.toSidebarSortItem()?.peerType)
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
            listOf(RaftHomeSidebarSection.JointChannels, RaftHomeSidebarSection.Channels, RaftHomeSidebarSection.DirectMessages),
            order(hasJointChannels = true)
        )
        assertEquals(
            listOf(RaftHomeSidebarSection.Channels, RaftHomeSidebarSection.DirectMessages),
            order(hasJointChannels = false)
        )
    }

    @Test
    fun homeHeaderRefreshIndicatorHidesWhilePullIndicatorShows() {
        // Non-pull refreshes (open, resume, realtime) keep the header spinner.
        assertTrue(homeHeaderRefreshIndicatorVisible(contentRefreshing = true, pullRefreshing = false))
        // artin task #22: a pulled refresh shows the pull indicator only.
        assertFalse(homeHeaderRefreshIndicatorVisible(contentRefreshing = true, pullRefreshing = true))
        assertFalse(homeHeaderRefreshIndicatorVisible(contentRefreshing = false, pullRefreshing = true))
        assertFalse(homeHeaderRefreshIndicatorVisible(contentRefreshing = false, pullRefreshing = false))
    }

    @Test
    fun sidebarSortModesMatchReactOrdering() {
        val manual =
            listOf(
                RaftHomeConversationItem(id = "b", name = "Beta", lastMessageAt = "2026-05-19T10:00:00Z"),
                RaftHomeConversationItem(id = "a", name = "Alpha", lastMessageAt = "2026-05-20T10:00:00Z"),
                RaftHomeConversationItem(id = "c", name = "gamma", lastMessageAt = "2026-05-18T10:00:00Z")
            )

        assertEquals(listOf("b", "a", "c"), manual.sortedForSidebar(RaftSidebarSortMode.Manual) { it.name }.map { it.id })
        assertEquals(listOf("a", "b", "c"), manual.sortedForSidebar(RaftSidebarSortMode.Recent) { it.name }.map { it.id })
        assertEquals(listOf("a", "b", "c"), manual.sortedForSidebar(RaftSidebarSortMode.Az) { it.name }.map { it.id })
    }

    @Test
    fun sidebarRegularChannelsPinAllBeforeServerOrderAndSortModes() {
        val channels =
            listOf(
                RaftHomeConversationItem(id = "visual", name = "visual-testing", lastMessageAt = "2026-05-20T10:00:00Z"),
                RaftHomeConversationItem(id = "all", name = "all", lastMessageAt = "2026-05-18T10:00:00Z"),
                RaftHomeConversationItem(id = "artifacts", name = "artifacts", lastMessageAt = "2026-05-19T10:00:00Z")
            )

        val manual =
            channels
                .orderedBySidebarIds(listOf("visual", "artifacts"), pinnedFirstId = "all")
                .sortedForSidebar(RaftSidebarSortMode.Manual, pinnedFirstId = "all") { it.name }
        val recent =
            channels
                .orderedBySidebarIds(listOf("visual", "artifacts"), pinnedFirstId = "all")
                .sortedForSidebar(RaftSidebarSortMode.Recent, pinnedFirstId = "all") { it.name }
        val az =
            channels
                .orderedBySidebarIds(listOf("visual", "artifacts"), pinnedFirstId = "all")
                .sortedForSidebar(RaftSidebarSortMode.Az, pinnedFirstId = "all") { it.name }

        assertEquals(listOf("all", "visual", "artifacts"), manual.map { it.id })
        assertEquals(listOf("all", "visual", "artifacts"), recent.map { it.id })
        assertEquals(listOf("all", "artifacts", "visual"), az.map { it.id })
    }

    @Test
    fun sidebarDmManualOrderFollowsServerOrderAndAppendsNewRows() {
        val directMessages =
            listOf(
                RaftHomeConversationItem(id = "dm-a", name = "Alpha"),
                RaftHomeConversationItem(id = "dm-b", name = "Beta"),
                RaftHomeConversationItem(id = "dm-c", name = "Charlie"),
                RaftHomeConversationItem(id = "dm-d", name = "Delta")
            )

        val ordered =
            directMessages
                .orderedBySidebarIds(listOf("dm-c", "missing", "dm-a", "dm-c"))
                .sortedForSidebar(RaftSidebarSortMode.Manual) { it.name }

        assertEquals(listOf("dm-c", "dm-a", "dm-b", "dm-d"), ordered.map { it.id })
    }

    @Test
    fun resolveSortTargetIndexPicksRowUnderPointer() {
        val rows =
            listOf(
                RaftSortRowBounds(index = 0, top = 0f, size = 32f),
                RaftSortRowBounds(index = 1, top = 34f, size = 32f),
                RaftSortRowBounds(index = 2, top = 68f, size = 32f),
            )

        assertEquals(0, resolveSortTargetIndex(10f, rows, lastIndex = 2))
        assertEquals(1, resolveSortTargetIndex(45f, rows, lastIndex = 2))
        assertEquals(2, resolveSortTargetIndex(80f, rows, lastIndex = 2))
    }

    @Test
    fun resolveSortAutoScrollDeltaScrollsNearViewportEdges() {
        assertEquals(-10f, resolveSortAutoScrollDelta(pointerY = 15f, viewportStart = 0f, viewportEnd = 320f, edgeScrollZone = 56f, edgeScrollStep = 10f, canScrollBackward = true, canScrollForward = true))
        assertEquals(10f, resolveSortAutoScrollDelta(pointerY = 280f, viewportStart = 0f, viewportEnd = 320f, edgeScrollZone = 56f, edgeScrollStep = 10f, canScrollBackward = true, canScrollForward = true))
        assertEquals(0f, resolveSortAutoScrollDelta(pointerY = 160f, viewportStart = 0f, viewportEnd = 320f, edgeScrollZone = 56f, edgeScrollStep = 10f, canScrollBackward = true, canScrollForward = true))
    }

    @Test
    fun homeConversationLaunchDropsUnknownKindInsteadOfDefaultingChannel() {
        val payload =
            RaftHomePayload(
                instanceId = "home-page",
                channels =
                    listOf(
                        RaftHomeConversationItem(
                            id = "mystery-1",
                            name = "Mystery",
                            kind = "mystery"
                        )
                    )
            )

        assertEquals(null, buildHomeConversationThreadLaunchPayload(payload, "mystery-1"))
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

    @Test
    fun homeHeadShowsTheIdentityClusterBeforeTheServerIdentityLoads() {
        val fallback = homeTitlebarServerIdentity(serverIdentity = null, serverName = "Lagoon", serverPublicPageLive = true)
        assertEquals("Lagoon", fallback.name)
        assertEquals(true, fallback.publicPageLive)
        val loaded = RaftServerSwitcherItem(id = "s1", name = "Loaded", avatarUrl = "https://a/b.png")
        assertEquals(loaded, homeTitlebarServerIdentity(loaded, serverName = "Lagoon", serverPublicPageLive = false))
    }
}
