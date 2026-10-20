package build.raft.app

import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.data.SettingsServerSidebarOrderSnapshot
import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.model.ChannelActivityNotificationPreference
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MessageId
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.TaskStatus
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * activity_conversation_list_v0: list mapping and pinned ordering, mirroring the web ThreadsInbox
 * `layout="list"` branch and `isSidebarPinnedInboxItem` (slock packages/web/src/components/thread).
 */
class ActivityConversationListTest {

    private fun channel(key: String, channelId: String, unread: Int = 0, mention: Boolean = false) =
        InboxItem(
            key = key,
            kind = InboxItemKind.Channel,
            channelId = ChannelId(channelId),
            channelName = "#$channelId",
            unreadCount = unread,
            hasMention = mention,
            lastMessageId = MessageId("m-$key"),
            lastMessagePreview = "preview $key",
            lastMessageSenderName = "Cindy",
            lastMessageSenderId = "agent-cindy",
            lastMessageSenderKind = MemberKind.Agent,
            lastMessageSenderAvatarWireValue = "pixel:robot",
        )

    private fun dm(key: String, channelId: String, senderId: String, unread: Int = 0) =
        InboxItem(
            key = key,
            kind = InboxItemKind.DirectMessage,
            channelId = ChannelId(channelId),
            channelName = channelId,
            channelKind = ConversationKind.DirectMessage,
            unreadCount = unread,
            lastMessagePreview = "dm $key",
            lastMessageSenderName = "Sender $senderId",
            lastMessageSenderId = senderId,
            lastMessageSenderKind = MemberKind.Agent,
        )

    private val thread =
        InboxItem(
            key = "thread:t1",
            kind = InboxItemKind.Thread,
            channelId = ChannelId("t1"),
            channelName = "thread",
            unreadCount = 2,
            hasMention = true,
            threadChannelId = ChannelId("t1"),
            parentChannelId = ChannelId("design"),
            parentChannelName = "design",
            parentChannelKind = ConversationKind.Channel,
            parentMessageId = MessageId("p1"),
            parentMessagePreview = "Root message",
            latestActivityPreview = "Latest reply",
            latestActivitySenderName = "Mahua",
            latestActivitySenderId = "agent-mahua",
            latestActivitySenderKind = MemberKind.Agent,
            taskNumber = 5,
            taskStatus = TaskStatus.InProgress,
        )

    private val fablePeer =
        ActivityConversationDmPeer(
            peerId = "agent-fable",
            peerIsAgent = true,
            avatarWireValue = "pixel:robot",
            displayName = "@Fable",
            activityMuted = false,
        )

    private val context =
        ActivityConversationListContext(
            pinnedRefs =
                listOf(
                    SettingsServerSidebarPinnedItem(kind = "agent", id = "agent-fable"),
                    SettingsServerSidebarPinnedItem(kind = "channel", id = "android"),
                    // A thread's parent channel being pinned never pins the thread itself.
                    SettingsServerSidebarPinnedItem(kind = "channel", id = "design"),
                ),
            mutedChannelIds = setOf("random", "design"),
            dmPeersByChannelId = mapOf("dm-fable" to fablePeer),
        )

    @Test
    fun pinnedConversationsFloatFirstInLoadedOrderWithSectionLabels() {
        val items =
            listOf(
                thread,
                channel("channel:random", "random"),
                dm("dm:fable", "dm-fable", senderId = "agent-fable"),
                channel("channel:android", "android"),
            )
        val entries = activityConversationListEntries(items, context)
        assertEquals(
            listOf(
                "activity-list-section:Pinned",
                "dm:fable",
                "channel:android",
                "activity-list-section:AllConversations",
                "thread:t1",
                "channel:random",
            ),
            entries.map { it.key },
        )
        val rows = entries.filterIsInstance<ActivityConversationListEntry.Row>()
        // The hairline sits only between two adjacent rows, never under a section label.
        assertEquals(listOf(false, true, false, true), rows.map { it.divider })
        assertEquals(listOf(true, true, false, false), rows.map { it.pinned })
        assertTrue((entries.first() as ActivityConversationListEntry.Section).first)
        assertFalse((entries[3] as ActivityConversationListEntry.Section).first)
    }

    @Test
    fun withoutPinsTheLoadedOrderIsKeptAndNoSectionRenders() {
        for (pins in listOf(emptyList(), listOf(SettingsServerSidebarPinnedItem(kind = "channel", id = "elsewhere")))) {
            val items = listOf(channel("channel:a", "a"), thread, channel("channel:b", "b"))
            val entries = activityConversationListEntries(items, context.copy(pinnedRefs = pins))
            assertEquals(listOf("channel:a", "thread:t1", "channel:b"), entries.map { it.key }, "pins=$pins")
            assertEquals(
                listOf(false, true, true),
                entries.filterIsInstance<ActivityConversationListEntry.Row>().map { it.divider },
            )
        }
    }

    @Test
    fun onlyPinnedRowsStartWithThePinnedSectionAndNoAllSection() {
        val entries = activityConversationListEntries(listOf(channel("channel:android", "android")), context)
        assertEquals(listOf("activity-list-section:Pinned", "channel:android"), entries.map { it.key })
    }

    @Test
    fun directMessagesPinByPeerKindAndIdNotChannelId() {
        val item = dm("dm:fable", "dm-fable", senderId = "agent-fable")
        assertTrue(item.isSidebarPinnedActivityConversation(context))
        // A human pin with the agent's id is a different ref.
        val humanPinned = context.copy(pinnedRefs = listOf(SettingsServerSidebarPinnedItem(kind = "human", id = "agent-fable")))
        assertFalse(item.isSidebarPinnedActivityConversation(humanPinned))
        // The DM channel id itself is never a pin ref.
        val channelIdPinned = context.copy(pinnedRefs = listOf(SettingsServerSidebarPinnedItem(kind = "channel", id = "dm-fable")))
        assertFalse(item.isSidebarPinnedActivityConversation(channelIdPinned))
        // An unresolved DM peer is not pinned.
        assertFalse(dm("dm:x", "dm-unknown", senderId = "x").isSidebarPinnedActivityConversation(context))
    }

    @Test
    fun threadRowTitlesTheRootAndPrefixesThePreviewWithItsChannel() {
        val model = thread.activityConversationRowModel(context.copy(mutedChannelIds = emptySet()), InboxFilter.All)
        assertEquals(ActivityConversationLeadingKind.Thread, model.leadingKind)
        assertEquals("Root message", model.title)
        assertEquals("#design", model.threadChannelLabel)
        assertEquals("Latest reply", model.preview)
        val sender = assertIs<ActivityConversationSender.Named>(model.sender)
        assertEquals("Mahua", sender.name)
        assertEquals(MemberKind.Agent, sender.avatar?.senderKind)
        assertTrue(model.strong)
        assertTrue(model.showMentionMarker)
        assertEquals("2", model.unreadCountLabel())
        assertEquals(5, model.taskNumber)
        assertEquals(TaskStatus.InProgress, model.taskStatus)
    }

    @Test
    fun taskBadgeFollowsTheTaskStatusBadgeGate() {
        val model =
            thread.activityConversationRowModel(context, InboxFilter.All, activityTaskStatusBadgesEnabled = false)
        assertNull(model.taskNumber)
        assertNull(model.taskStatus)
    }

    @Test
    fun mutedConversationShowsNoCountAndStaysQuietButKeepsTheMention() {
        // A thread is muted through its parent channel.
        val model = thread.activityConversationRowModel(context, InboxFilter.All)
        assertTrue(model.muted)
        assertFalse(model.strong)
        assertFalse(model.showUnreadCount)
        assertNull(model.unreadCountLabel())
        assertTrue(model.showMentionMarker)
    }

    @Test
    fun mentionMarkerHidesUnderTheMentionsFilterAndWhenRead() {
        val unread = channel("channel:a", "a", unread = 1, mention = true)
        assertTrue(unread.activityConversationRowModel(context, InboxFilter.Unread).showMentionMarker)
        assertFalse(unread.activityConversationRowModel(context, InboxFilter.Mentions).showMentionMarker)
        assertFalse(unread.copy(unreadCount = 0).activityConversationRowModel(context, InboxFilter.All).showMentionMarker)
    }

    @Test
    fun unreadCountReadsAsRaftUiWritesIt() {
        assertEquals("99+", channel("channel:a", "a", unread = 120).activityConversationRowModel(context, InboxFilter.All).unreadCountLabel())
        assertNull(channel("channel:a", "a").activityConversationRowModel(context, InboxFilter.All).unreadCountLabel())
    }

    @Test
    fun directMessageLeadsWithThePeerAndDropsThePeerAsSender() {
        val fromPeer = dm("dm:fable", "dm-fable", senderId = "agent-fable", unread = 1)
            .activityConversationRowModel(context, InboxFilter.All)
        assertEquals(ActivityConversationLeadingKind.DirectMessage, fromPeer.leadingKind)
        assertEquals(fablePeer, fromPeer.dmPeer)
        assertEquals("Fable", fromPeer.title) // `@`/`#` prefix stripped like the web title.
        assertNull(fromPeer.sender)

        val fromMe = dm("dm:fable", "dm-fable", senderId = "user-me").activityConversationRowModel(context, InboxFilter.All)
        val sender = assertIs<ActivityConversationSender.Named>(fromMe.sender)
        assertEquals("Sender user-me", sender.name)
        // DM rows already lead with a face: no inline sender avatar.
        assertNull(sender.avatar)
    }

    @Test
    fun unresolvedDirectMessageFallsBackToTheDmTileAndChannelName() {
        val model = dm("dm:x", "dm-unknown", senderId = "x").activityConversationRowModel(context, InboxFilter.All)
        assertEquals(ActivityConversationLeadingKind.DirectMessage, model.leadingKind)
        assertNull(model.dmPeer)
        assertEquals("dm-unknown", model.title)
    }

    @Test
    fun systemSendersUseTheLocalizedSystemLabel() {
        val mention =
            InboxItem(
                key = "mention:1",
                kind = InboxItemKind.MentionAction,
                channelId = ChannelId("general"),
                channelName = "general",
                unreadCount = 1,
                lastMessagePreview = "@quill was not notified",
                lastMessageSenderName = "System",
            )
        assertEquals(ActivityConversationSender.System, mention.activityConversationRowModel(context, InboxFilter.All).sender)
        val broadcast = channel("channel:a", "a").copy(lastMessageSenderId = "system", lastMessageSenderKind = MemberKind.Human)
        assertEquals(ActivityConversationSender.System, broadcast.activityConversationRowModel(context, InboxFilter.All).sender)
    }

    @Test
    fun bodyTextPrefixesTheSenderOnlyWhenThereIsOne() {
        assertEquals("Cindy: hi", activityConversationBodyText("Cindy", "hi").text)
        assertEquals("hi", activityConversationBodyText(null, "hi").text)
        assertEquals("hi", activityConversationBodyText(" ", "hi").text)
    }

    @Test
    fun homeSnapshotProjectsPinsMutesAndDmPeersForTheSameServerOnly() {
        val snapshot =
            HomeSnapshot(
                server = ServerSummary(id = ServerId("server-1"), name = "Raft"),
                currentMember =
                    MemberProfile(
                        id = MemberId("me"),
                        displayName = "Me",
                        handle = "me",
                        kind = MemberKind.Human,
                        presence = Presence.Active,
                    ),
                channels =
                    listOf(
                        HomeChannelRow(
                            threadId = ThreadId("random"),
                            channelId = ChannelId("random"),
                            name = "random",
                            activityNotificationPreference = ChannelActivityNotificationPreference(activityMuted = true),
                        ),
                        HomeChannelRow(threadId = ThreadId("android"), channelId = ChannelId("android"), name = "android"),
                    ),
                directMessages =
                    listOf(
                        HomeDirectMessageRow(
                            threadId = ThreadId("dm-fable"),
                            channelId = ChannelId("dm-fable"),
                            name = "Fable",
                            directMessagePeerType = "agent",
                            directMessagePeerAvatarWireValue = "pixel:robot",
                            presenceMemberId = MemberId("agent-fable"),
                        ),
                    ),
                unreadTotal = 0,
                sidebarOrder =
                    SettingsServerSidebarOrderSnapshot(
                        pinned = listOf(SettingsServerSidebarPinnedItem(kind = "agent", id = "agent-fable")),
                    ),
            )
        val projected = snapshot.toActivityConversationListContext("server-1")
        assertEquals(listOf(SettingsServerSidebarPinnedItem(kind = "agent", id = "agent-fable")), projected.pinnedRefs)
        assertEquals(setOf("random"), projected.mutedChannelIds)
        assertEquals(
            ActivityConversationDmPeer(
                peerId = "agent-fable",
                peerIsAgent = true,
                avatarWireValue = "pixel:robot",
                displayName = "Fable",
                activityMuted = false,
            ),
            projected.dmPeersByChannelId["dm-fable"],
        )
        // Legacy pin fields still project (typedPinnedOrLegacy).
        val legacy = snapshot.copy(sidebarOrder = SettingsServerSidebarOrderSnapshot(pinnedChannelIds = listOf("android")))
        assertEquals(
            listOf(SettingsServerSidebarPinnedItem(kind = "channel", id = "android")),
            legacy.toActivityConversationListContext("server-1").pinnedRefs,
        )
        // Another server's Home snapshot is never a candidate for this Activity.
        assertEquals(ActivityConversationListContext.Empty, snapshot.toActivityConversationListContext("server-2"))
        assertEquals(ActivityConversationListContext.Empty, (null as HomeSnapshot?).toActivityConversationListContext("server-1"))
    }
}
