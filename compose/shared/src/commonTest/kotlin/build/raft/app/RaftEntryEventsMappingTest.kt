package build.raft.app

import build.raft.app.data.InboxItem
import build.raft.app.data.RaftActivityAction
import build.raft.app.data.RaftConversationHeaderAction
import build.raft.app.data.RaftConversationHeaderKind
import build.raft.app.data.RaftConversationHeaderSurface
import build.raft.app.data.RaftNotificationLevelSetting
import build.raft.app.data.RaftProductEvent
import build.raft.app.data.RaftProductEvents
import build.raft.app.data.RaftSidebarAction
import build.raft.app.data.RaftSidebarRowKind
import build.raft.app.data.SettingsServerSidebarCustomSection
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.designsystem.components.RaftSideAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Unit tests for the pure entry-event mapping rules (`#proj-mobile:#260`, slock #16056 + #16067). */
class RaftEntryEventsMappingTest {
    @Test
    fun threadHeaderConversationKindExcludesTaskConversations() {
        assertEquals(RaftConversationHeaderKind.Channel, threadHeaderConversationKind(RaftThreadSurfaceKind.Channel, taskConversation = false))
        assertEquals(RaftConversationHeaderKind.Dm, threadHeaderConversationKind(RaftThreadSurfaceKind.DirectMessage, taskConversation = false))
        assertEquals(RaftConversationHeaderKind.Thread, threadHeaderConversationKind(RaftThreadSurfaceKind.ReplyThread, taskConversation = false))
        assertNull(threadHeaderConversationKind(RaftThreadSurfaceKind.Channel, taskConversation = true))
        assertNull(threadHeaderConversationKind(RaftThreadSurfaceKind.ReplyThread, taskConversation = true))
    }

    @Test
    fun threadHeaderOwnAndOverflowActionsMapRegisteredValuesOnly() {
        assertEquals(RaftConversationHeaderAction.Search, threadHeaderOwnActionProductEvent("thread-search", settingsOpen = false, focusModeActive = false))
        assertEquals(RaftConversationHeaderAction.OpenLounge, threadHeaderOwnActionProductEvent("lounge", settingsOpen = false, focusModeActive = false))
        assertEquals(RaftConversationHeaderAction.OpenDetails, threadHeaderOwnActionProductEvent("thread-settings", settingsOpen = false, focusModeActive = false))
        assertEquals(RaftConversationHeaderAction.CloseDetails, threadHeaderOwnActionProductEvent("thread-settings", settingsOpen = true, focusModeActive = false))
        assertEquals(RaftConversationHeaderAction.ViewInChannel, threadHeaderOwnActionProductEvent("thread-open-in-channel", settingsOpen = false, focusModeActive = false))
        assertEquals(RaftConversationHeaderAction.Expand, threadHeaderOwnActionProductEvent(FOCUS_MODE_MENU_ITEM_ID, settingsOpen = false, focusModeActive = false))
        assertEquals(RaftConversationHeaderAction.Restore, threadHeaderOwnActionProductEvent(FOCUS_MODE_MENU_ITEM_ID, settingsOpen = false, focusModeActive = true))
        assertEquals(RaftConversationHeaderAction.Close, threadHeaderOwnActionProductEvent("thread-pane-close", settingsOpen = false, focusModeActive = false))
        assertNull(threadHeaderOwnActionProductEvent("thread-side-card-tasks", settingsOpen = false, focusModeActive = false))
        assertNull(threadHeaderOwnActionProductEvent("thread-side-card-files", settingsOpen = false, focusModeActive = false))

        assertEquals(RaftConversationHeaderAction.Follow, threadHeaderOverflowMenuProductEvent(FOLLOW_THREAD_MENU_ITEM_ID, followed = false, taskConversation = false))
        assertEquals(RaftConversationHeaderAction.Unfollow, threadHeaderOverflowMenuProductEvent(FOLLOW_THREAD_MENU_ITEM_ID, followed = true, taskConversation = false))
        assertNull(threadHeaderOverflowMenuProductEvent(FOLLOW_THREAD_MENU_ITEM_ID, followed = null, taskConversation = false))
        assertEquals(RaftConversationHeaderAction.MarkUnread, threadHeaderOverflowMenuProductEvent(MARK_THREAD_UNREAD_MENU_ITEM_ID, followed = true, taskConversation = false))
        assertEquals(RaftConversationHeaderAction.Search, threadHeaderOverflowMenuProductEvent(SEARCH_IN_TASK_MENU_ITEM_ID, followed = true, taskConversation = false))
        assertEquals(RaftConversationHeaderAction.OpenTimeline, threadHeaderOverflowMenuProductEvent(THREAD_TIMELINE_MENU_ITEM_ID, followed = true, taskConversation = false))
        assertEquals(RaftConversationHeaderAction.ViewInChannel, threadHeaderOverflowMenuProductEvent(VIEW_TASK_IN_CHANNEL_MENU_ITEM_ID, followed = true, taskConversation = false))
        assertNull(threadHeaderOverflowMenuProductEvent(DELETE_TASK_MENU_ITEM_ID, followed = true, taskConversation = false))
        assertNull(threadHeaderOverflowMenuProductEvent(NOTIFICATION_LEVEL_MENU_ITEM_ID, followed = true, taskConversation = false))
        assertNull(threadHeaderOverflowMenuProductEvent(FOLLOW_THREAD_MENU_ITEM_ID, followed = true, taskConversation = true))
        assertNull(threadHeaderOverflowMenuProductEvent(SEARCH_IN_TASK_MENU_ITEM_ID, followed = true, taskConversation = true))
    }

    @Test
    fun threadHeaderTitleClickAndBackRulesMatchNovaDecisions() {
        assertEquals(
            RaftConversationHeaderAction.ScrollToStart,
            threadHeaderTitleClickProductEvent(RaftThreadSurfaceKind.ReplyThread, taskConversation = false, isLounge = false, settingsOpen = false),
        )
        assertEquals(
            RaftConversationHeaderAction.OpenDetails,
            threadHeaderTitleClickProductEvent(RaftThreadSurfaceKind.Channel, taskConversation = false, isLounge = false, settingsOpen = false),
        )
        assertEquals(
            RaftConversationHeaderAction.CloseDetails,
            threadHeaderTitleClickProductEvent(RaftThreadSurfaceKind.Channel, taskConversation = false, isLounge = false, settingsOpen = true),
        )
        assertNull(threadHeaderTitleClickProductEvent(RaftThreadSurfaceKind.Channel, taskConversation = false, isLounge = true, settingsOpen = false))
        assertNull(threadHeaderTitleClickProductEvent(RaftThreadSurfaceKind.DirectMessage, taskConversation = false, isLounge = false, settingsOpen = false))
        assertNull(threadHeaderTitleClickProductEvent(RaftThreadSurfaceKind.ReplyThread, taskConversation = true, isLounge = false, settingsOpen = false))

        assertEquals(
            RaftConversationHeaderAction.Close,
            threadHeaderBackProductEvent(RaftThreadSurfaceKind.ReplyThread, taskConversation = false, wideLayout = true),
        )
        assertNull(threadHeaderBackProductEvent(RaftThreadSurfaceKind.ReplyThread, taskConversation = false, wideLayout = true, inSidePane = true))
        assertNull(threadHeaderBackProductEvent(RaftThreadSurfaceKind.ReplyThread, taskConversation = false, wideLayout = false))
        assertNull(threadHeaderBackProductEvent(RaftThreadSurfaceKind.ReplyThread, taskConversation = true, wideLayout = true))
        assertNull(threadHeaderBackProductEvent(RaftThreadSurfaceKind.Channel, taskConversation = false, wideLayout = true))
    }

    @Test
    fun sideColumnAndNativeNavBarActionsRecordHeaderSurfaceAndSkipSideCards() {
        val recorded = mutableListOf<RaftProductEvent<*>>()
        val previous = RaftProductEvents.testEntryEventObserver
        RaftProductEvents.testEntryEventObserver = { recorded += it }
        try {
            var clicks = 0
            val raw =
                listOf(
                    RaftSideAction(id = "thread-search", label = "Search", icon = {}, onClick = { clicks++ }),
                    RaftSideAction(id = "thread-side-card-tasks", label = "Tasks", icon = {}, onClick = { clicks++ }),
                    RaftSideAction(id = FOLLOW_THREAD_MENU_ITEM_ID, label = "Unfollow", icon = {}, onClick = { clicks++ }),
                )
            val wrapped =
                wrapThreadHeaderSideColumnActionsForAnalytics(
                    actions = raw,
                    conversation = RaftConversationHeaderKind.Thread,
                    compact = true,
                    settingsOpen = false,
                    focusModeActive = false,
                    threadFollowed = true,
                    taskConversation = false,
                )
            wrapped.forEach { it.onClick() }
            assertEquals(3, clicks)
            assertEquals(2, recorded.size)
            val events = recorded.filterIsInstance<RaftProductEvent.ConversationHeaderActionSelected>().map { it.properties }
            assertEquals(RaftConversationHeaderAction.Search, events[0].action)
            assertEquals(RaftConversationHeaderSurface.Header, events[0].surface)
            assertEquals(RaftConversationHeaderKind.Thread, events[0].conversation)
            assertEquals(true, events[0].compact)
            assertEquals(RaftConversationHeaderAction.Unfollow, events[1].action)
            assertEquals(RaftConversationHeaderSurface.Header, events[1].surface)
        } finally {
            RaftProductEvents.testEntryEventObserver = previous
        }
    }

    @Test
    fun sidebarRowKindsAndMenusFollowRegistryRules() {
        assertEquals(RaftSidebarRowKind.Channel, RaftHomeConversationItem(id = "c1", kind = "public").sidebarRowKind())
        assertEquals(RaftSidebarRowKind.Channel, RaftHomeConversationItem(id = "c2", kind = "joint").sidebarRowKind())
        assertEquals(RaftSidebarRowKind.Dm, RaftHomeConversationItem(id = "d1", kind = "dm", peerType = "human").sidebarRowKind())
        assertEquals(RaftSidebarRowKind.AgentDm, RaftHomeConversationItem(id = "d2", kind = "dm", peerType = "agent").sidebarRowKind())
        assertEquals(
            RaftSidebarRowKind.Lounge,
            RaftHomeConversationItem(id = "l1", kind = "public", agentLounge = RaftHomeAgentLoungeRow("l1", agentName = "A")).sidebarRowKind(),
        )
        assertEquals(
            RaftSidebarRowKind.Dm,
            RaftHomePinnedMemberItem(identity = MemberDetailIdentity(MemberId("h1"), MemberKind.Human), name = "H", avatarLabel = "H").sidebarRowKind(),
        )
        assertEquals(
            RaftSidebarRowKind.AgentDm,
            RaftHomePinnedMemberItem(identity = MemberDetailIdentity(MemberId("a1"), MemberKind.Agent), name = "A", avatarLabel = "A").sidebarRowKind(),
        )

        assertEquals(RaftSidebarAction.MarkRead, homeConversationMenuSidebarAction("mark-read", pinTargetIsPinned = false))
        assertEquals(RaftSidebarAction.MarkRead, homeConversationMenuSidebarAction("mark-all-read", pinTargetIsPinned = false))
        assertEquals(RaftSidebarAction.MarkUnread, homeConversationMenuSidebarAction("mark-unread", pinTargetIsPinned = false))
        assertEquals(RaftSidebarAction.Pin, homeConversationMenuSidebarAction("toggle-pin", pinTargetIsPinned = false))
        assertEquals(RaftSidebarAction.Unpin, homeConversationMenuSidebarAction("toggle-pin", pinTargetIsPinned = true))
        assertEquals(RaftSidebarAction.Mute, homeConversationMenuSidebarAction("mute-channel", pinTargetIsPinned = false))
        assertEquals(RaftSidebarAction.Unmute, homeConversationMenuSidebarAction("unmute-channel", pinTargetIsPinned = false))
        assertEquals(RaftSidebarAction.Close, homeConversationMenuSidebarAction("close-dm", pinTargetIsPinned = false))
        assertEquals(RaftSidebarAction.Archive, homeConversationMenuSidebarAction("archive-channel", pinTargetIsPinned = false))
        assertEquals(RaftSidebarAction.Leave, homeConversationMenuSidebarAction("leave-channel", pinTargetIsPinned = false))
        assertEquals(RaftSidebarAction.OpenReorder, homeConversationMenuSidebarAction("sort-channels", pinTargetIsPinned = false))
        assertNull(homeConversationMenuSidebarAction("move-to-section", pinTargetIsPinned = false))
        assertNull(homeConversationMenuSidebarAction("notification-level", pinTargetIsPinned = false))

        assertEquals(RaftSidebarAction.Mute, homeNotificationChoiceSidebarAction(RaftNotificationChoice.Mute))
        assertEquals(RaftSidebarAction.NotifyAll, homeNotificationChoiceSidebarAction(RaftNotificationChoice.Level(RaftNotificationLevelSetting.All)))
        assertEquals(RaftSidebarAction.NotifyMentions, homeNotificationChoiceSidebarAction(RaftNotificationChoice.Level(RaftNotificationLevelSetting.Mentions)))
        assertEquals(RaftSidebarAction.NotifyNone, homeNotificationChoiceSidebarAction(RaftNotificationChoice.Level(RaftNotificationLevelSetting.None)))
        assertNull(homeNotificationChoiceSidebarAction(RaftNotificationChoice.Level(RaftNotificationLevelSetting.Default)))
    }

    @Test
    fun moveToSectionAndSectionMenuFollowWebRules() {
        val sections = listOf(SettingsServerSidebarCustomSection(id = "sec-1", name = "Work"), SettingsServerSidebarCustomSection(id = "sec-2", name = "Ops"))
        val inHome = ChannelSectionPlacement(customSections = sections, pinned = false, placementSectionId = null)
        val inPinned = ChannelSectionPlacement(customSections = sections, pinned = true, placementSectionId = null)
        val inCustom = ChannelSectionPlacement(customSections = sections, pinned = false, placementSectionId = "sec-1")

        // Home always records move_to_home; Pinned and custom section skip when already current.
        assertEquals(RaftSidebarAction.MoveToHome, homeMoveToSectionSidebarAction("home", inHome))
        assertEquals(RaftSidebarAction.MoveToHome, homeMoveToSectionSidebarAction("home", inPinned))
        assertEquals(RaftSidebarAction.MoveToPinned, homeMoveToSectionSidebarAction("pinned", inHome))
        assertNull(homeMoveToSectionSidebarAction("pinned", inPinned))
        assertEquals(RaftSidebarAction.MoveToSection, homeMoveToSectionSidebarAction("section:sec-1", inHome))
        assertNull(homeMoveToSectionSidebarAction("section:sec-1", inCustom))
        assertEquals(RaftSidebarAction.MoveToSection, homeMoveToSectionSidebarAction("section:sec-2", inCustom))
        assertNull(homeMoveToSectionSidebarAction("section:missing", inHome))
        assertEquals(RaftSidebarAction.NewSection, homeMoveToSectionSidebarAction("create", inHome))

        assertEquals(RaftSidebarAction.SortManual, homeSectionMenuSidebarAction("sort:manual", hideEmptySections = false))
        assertEquals(RaftSidebarAction.SortRecent, homeSectionMenuSidebarAction("sort:recent", hideEmptySections = false))
        assertEquals(RaftSidebarAction.SortAlpha, homeSectionMenuSidebarAction("sort:az", hideEmptySections = false))
        assertEquals(RaftSidebarAction.HideEmptyOn, homeSectionMenuSidebarAction("toggle-hide-empty", hideEmptySections = false))
        assertEquals(RaftSidebarAction.HideEmptyOff, homeSectionMenuSidebarAction("toggle-hide-empty", hideEmptySections = true))
        assertEquals(RaftSidebarAction.NewSection, homeSectionMenuSidebarAction("create", hideEmptySections = false))
        assertEquals(RaftSidebarAction.CreateChannel, homeSectionMenuSidebarAction("create-channel", hideEmptySections = false))
        assertEquals(RaftSidebarAction.CreateExternalChannel, homeSectionMenuSidebarAction("create-external-channel", hideEmptySections = false))
        assertEquals(RaftSidebarAction.RenameSection, homeSectionMenuSidebarAction("rename", hideEmptySections = false))
        assertEquals(RaftSidebarAction.DeleteSection, homeSectionMenuSidebarAction("delete", hideEmptySections = false))
    }

    @Test
    fun activityRowMenuActionMapsAllMenuItemsIncludingArtifactUnfollow() {
        val unreadRow = InboxItem(key = "k1", kind = InboxItemKind.Thread, channelId = ChannelId("c1"), channelName = "general", unreadCount = 2)
        val readRow = InboxItem(key = "k2", kind = InboxItemKind.Thread, channelId = ChannelId("c1"), channelName = "general", unreadCount = 0)
        val markedUnreadRow = InboxItem(key = "k3", kind = InboxItemKind.Channel, channelId = ChannelId("c2"), channelName = "ops", unreadCount = 0, markedUnread = true)

        assertEquals(RaftActivityAction.MarkRead, activityRowMenuProductAction(unreadRow, "toggle-read"))
        assertEquals(RaftActivityAction.MarkRead, activityRowMenuProductAction(markedUnreadRow, "toggle-read"))
        assertEquals(RaftActivityAction.MarkUnread, activityRowMenuProductAction(readRow, "toggle-read"))
        assertEquals(RaftActivityAction.Done, activityRowMenuProductAction(unreadRow, "done"))
        assertEquals(RaftActivityAction.Follow, activityRowMenuProductAction(readRow, "follow-thread"))
        assertEquals(RaftActivityAction.Unfollow, activityRowMenuProductAction(unreadRow, "unfollow-thread"))
        assertEquals(RaftActivityAction.Mute, activityRowMenuProductAction(markedUnreadRow, "mute-activity"))
        assertEquals(RaftActivityAction.Unmute, activityRowMenuProductAction(markedUnreadRow, "unmute-activity"))
        assertEquals(RaftActivityAction.UnfollowArtifact, activityRowMenuProductAction(readRow, "unfollow-object"))
        assertNull(activityRowMenuProductAction(readRow, "unknown"))
    }
}
