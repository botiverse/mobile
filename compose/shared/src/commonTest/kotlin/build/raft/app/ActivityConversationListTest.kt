package build.raft.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.data.SettingsServerSidebarOrderSnapshot
import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.session
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
 * Activity conversation list: list mapping and pinned ordering, mirroring the web ThreadsInbox
 * `layout="list"` branch and `isSidebarPinnedInboxItem` (slock packages/web/src/components/thread).
 */
class ActivityConversationListTest {

    /** Compose foundation's `appendInlineContent` annotation tag. */
    private val inlineContentAnnotationTag = "androidx.compose.foundation.text.inlineContent"

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
            currentMemberId = "user-me",
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
        assertEquals("Root message", model.title?.text)
        assertEquals("#design", model.threadChannelLabel)
        assertEquals("Latest reply", model.preview?.text)
        val sender = assertIs<ActivityConversationSender.Named>(model.sender)
        assertEquals("Mahua", sender.name)
        assertEquals(MemberKind.Agent, sender.avatar?.senderKind)
        assertTrue(model.strong)
        assertTrue(model.showMentionMarker)
        assertEquals("2", model.unreadCountLabel())
        assertEquals(5, model.taskNumber)
        assertEquals(TaskStatus.InProgress, model.taskStatus)
    }

    /** xxchan task #67: the root and the latest message read without their Markdown syntax. */
    @Test
    fun threadRowReadsTitleAndPreviewWithoutMarkdownSyntax() {
        val model =
            thread.copy(parentMessagePreview = "**`#wg-rust-rewrite` 开场**", latestActivityPreview = "> see _this_")
                .activityConversationRowModel(context, InboxFilter.All)
        assertEquals("#wg-rust-rewrite 开场", model.title?.text)
        assertEquals("see this", model.preview?.text)
        // Markdown with no text left falls back like an empty preview.
        assertNull(thread.copy(parentMessagePreview = "<br>").activityConversationRowModel(context, InboxFilter.All).title)
    }

    @Test
    fun theCurrentMembersOwnMessageReadsAsSelfAndKeepsTheirFace() {
        val cases =
            listOf(
                Triple("channel", channel("channel:a", "a").copy(lastMessageSenderId = "user-me", lastMessageSenderName = "Me"), true),
                Triple("thread", thread.copy(latestActivitySenderId = "user-me", latestActivitySenderName = "Me"), true),
                // The thread falls back to the last message's sender like senderDisplayName does.
                Triple(
                    "thread via last message",
                    thread.copy(
                        latestActivitySenderId = null,
                        latestActivitySenderName = null,
                        latestActivitySenderKind = null,
                        lastMessageSenderId = "user-me",
                        lastMessageSenderName = "Me",
                        lastMessageSenderKind = MemberKind.Human,
                    ),
                    true,
                ),
                // DM rows already lead with the peer: no inline face, for "You" either.
                Triple("dm", dm("dm:fable", "dm-fable", senderId = "user-me"), false),
            )
        for ((name, item, faced) in cases) {
            val self = assertIs<ActivityConversationSender.Self>(item.activityConversationRowModel(context, InboxFilter.All).sender, name)
            assertEquals(faced, self.avatar != null, name)
            // Without a known current member the name stays.
            assertIs<ActivityConversationSender.Named>(
                item.activityConversationRowModel(context.copy(currentMemberId = null), InboxFilter.All).sender,
                name,
            )
        }
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
        // web: channels by `channels.find(...).activityMuted`, DMs by their `dmChannels` row; a thread
        // follows its parent channel, or the DM it lives in (`?? threadDmChannel`).
        val mutedPeerContext =
            context.copy(dmPeersByChannelId = mapOf("dm-fable" to fablePeer.copy(activityMuted = true)))
        val threadInDm =
            thread.copy(
                parentChannelId = ChannelId("dm-fable"),
                parentChannelName = "Fable",
                parentChannelKind = ConversationKind.DirectMessage,
            )
        data class Case(val name: String, val item: InboxItem, val context: ActivityConversationListContext, val muted: Boolean)
        val cases =
            listOf(
                Case("thread in a muted channel", thread, context, muted = true),
                Case("muted channel", channel("channel:random", "random", unread = 1, mention = true), context, muted = true),
                Case("muted DM peer", dm("dm:fable", "dm-fable", senderId = "agent-fable", unread = 1).copy(hasMention = true), mutedPeerContext, muted = true),
                Case("thread whose parent is a muted DM", threadInDm, mutedPeerContext, muted = true),
                Case("thread whose parent DM is not muted", threadInDm, context, muted = false),
                // A thread in a channel never takes a DM's mute, even with the same id.
                Case(
                    "channel thread beside a muted DM",
                    thread.copy(parentChannelId = ChannelId("dm-fable")),
                    mutedPeerContext,
                    muted = false,
                ),
                Case("unmuted channel", channel("channel:android", "android", unread = 1, mention = true), context, muted = false),
            )
        for (case in cases) {
            val model = case.item.activityConversationRowModel(case.context, InboxFilter.All)
            assertEquals(case.muted, model.muted, case.name)
            assertEquals(!case.muted, model.strong, case.name)
            assertEquals(!case.muted, model.showUnreadCount, case.name)
            assertEquals(!case.muted, model.unreadCountLabel() != null, case.name)
            // The mention marker survives the mute.
            assertTrue(model.showMentionMarker, case.name)
        }
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
        assertEquals("Fable", fromPeer.title?.text) // `@`/`#` prefix stripped like the web title.
        assertNull(fromPeer.sender)

        val fromOther = dm("dm:fable", "dm-fable", senderId = "user-other").activityConversationRowModel(context, InboxFilter.All)
        val sender = assertIs<ActivityConversationSender.Named>(fromOther.sender)
        assertEquals("Sender user-other", sender.name)
        // DM rows already lead with a face: no inline sender avatar.
        assertNull(sender.avatar)
    }

    private val threadInFableDm =
        thread.copy(
            parentChannelId = ChannelId("dm-fable"),
            parentChannelName = "fable-raw",
            parentChannelKind = ConversationKind.DirectMessage,
            latestActivitySenderName = "Fable",
            latestActivitySenderId = "agent-fable",
        )

    @Test
    fun threadRowsLeadWithTheirConversationAndDropOnlyWhatTheLeadingImageSays() {
        data class Case(
            val name: String,
            val item: InboxItem,
            val peer: ActivityConversationDmPeer?,
            val label: String,
            val labelVisible: Boolean,
            val dmTile: Boolean,
        )
        val cases =
            listOf(
                // A channel thread: the channel tile and "#design ·", as before.
                Case("channel thread", thread, peer = null, label = "#design", labelVisible = true, dmTile = false),
                // A DM thread: the peer's face; "@Fable" leaves the line (the face says it), named by Home.
                Case("DM thread", threadInFableDm, peer = fablePeer, label = "@Fable", labelVisible = false, dmTile = true),
                // The DM is not loaded yet: the DM tile (not `#`) and the visible label.
                Case(
                    "DM thread, DM not loaded",
                    threadInFableDm.copy(parentChannelId = ChannelId("dm-unknown")),
                    peer = null,
                    label = "@fable-raw",
                    labelVisible = true,
                    dmTile = true,
                ),
            )
        for (case in cases) {
            val model = case.item.activityConversationRowModel(context.copy(mutedChannelIds = emptySet()), InboxFilter.All)
            assertEquals(ActivityConversationLeadingKind.Thread, model.leadingKind, case.name)
            assertEquals(case.peer, model.dmPeer, case.name)
            assertEquals(case.label, model.threadChannelLabel, case.name)
            assertEquals(case.labelVisible, model.threadChannelLabelVisible, case.name)
            assertEquals(case.dmTile, model.threadInDirectMessage, case.name)
            // Both carry the thread badge in the corner, and a thread row never shows a presence light.
            assertTrue(model.leadingThreadBadge, case.name)
            assertFalse(model.leadingPresence, case.name)
            assertEquals("Root message", model.title?.text, case.name)
        }
    }

    @Test
    fun dmThreadKeepsTheSenderWithoutASecondFace() {
        // Either side may have replied last, so the DM thread keeps "Fable: …" even from the peer;
        // the leading face is the peer, so no inline face before the name.
        for (senderId in listOf("agent-fable", "user-other")) {
            val model =
                threadInFableDm.copy(latestActivitySenderId = senderId).activityConversationRowModel(context, InboxFilter.All)
            val sender = assertIs<ActivityConversationSender.Named>(model.sender, senderId)
            assertEquals("Fable", sender.name, senderId)
            assertNull(sender.avatar, senderId)
        }
        // A channel thread keeps its inline sender face.
        val channelSender = assertIs<ActivityConversationSender.Named>(thread.activityConversationRowModel(context, InboxFilter.All).sender)
        assertEquals(MemberKind.Agent, channelSender.avatar?.senderKind)
    }

    @Test
    fun onlyAnAgentDmRowShowsThePresenceLight() {
        val dmRow = dm("dm:fable", "dm-fable", senderId = "agent-fable").activityConversationRowModel(context, InboxFilter.All)
        assertTrue(dmRow.leadingPresence)
        assertFalse(dmRow.leadingThreadBadge)
        val humanDm = context.copy(dmPeersByChannelId = mapOf("dm-fable" to fablePeer.copy(peerIsAgent = false)))
        assertFalse(dm("dm:fable", "dm-fable", senderId = "x").activityConversationRowModel(humanDm, InboxFilter.All).leadingPresence)
        assertFalse(threadInFableDm.activityConversationRowModel(context, InboxFilter.All).leadingPresence)
    }

    @Test
    fun accessibilityBodyStillNamesTheDmTheLineHides() {
        val model = threadInFableDm.activityConversationRowModel(context, InboxFilter.All)
        val label = "${model.threadChannelLabel} ·"
        // Visible line: no "@Fable ·"; what a screen reader hears: with it.
        assertEquals(
            "Fable: Latest reply",
            activityConversationBodyText(
                sender = "Fable",
                preview = model.preview?.toAnnotatedString(linkColor = Color.Unspecified) ?: AnnotatedString(""),
                channelLabel = label.takeIf { model.threadChannelLabelVisible },
            ).text,
        )
        assertEquals("@Fable · Fable: Latest reply", activityConversationAccessibilityBody(label, "Fable", model.preview?.text.orEmpty()))
    }

    @Test
    fun unresolvedDirectMessageFallsBackToTheDmTileAndChannelName() {
        val model = dm("dm:x", "dm-unknown", senderId = "x").activityConversationRowModel(context, InboxFilter.All)
        assertEquals(ActivityConversationLeadingKind.DirectMessage, model.leadingKind)
        assertNull(model.dmPeer)
        assertEquals("dm-unknown", model.title?.text)
    }

    @Test
    fun systemSendersProjectToTheSystemSenderNotTheirWireName() {
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
        // The model carries the System marker, never the wire name ("System" / "system"); the row
        // resolves it to the localized activity_list_system_sender string when it renders.
        val cases =
            listOf(
                "mention action" to mention,
                "system sender id" to channel("channel:a", "a").copy(lastMessageSenderId = "system", lastMessageSenderKind = MemberKind.Human),
                "system sender kind" to channel("channel:b", "b").copy(lastMessageSenderName = "Raft", lastMessageSenderKind = MemberKind.System),
            )
        for ((name, item) in cases) {
            assertEquals(ActivityConversationSender.System, item.activityConversationRowModel(context, InboxFilter.All).sender, name)
        }
    }

    @Test
    fun bodyTextIsOneLineOfChannelLabelSenderFaceAndPreview() {
        // The channel label lives in the same string as the preview, so a long channel name ellipsizes
        // the whole line instead of squeezing the preview out (web InboxItemBody truncates as one line).
        data class Case(val channelLabel: String?, val sender: String?, val avatar: Boolean, val text: String, val faces: Int)
        val longChannel = "#" + "a-very-long-channel-name".repeat(4) + " ·"
        val cases =
            listOf(
                Case(channelLabel = null, sender = "Cindy", avatar = false, text = "Cindy: hi", faces = 0),
                Case(channelLabel = null, sender = null, avatar = false, text = "hi", faces = 0),
                Case(channelLabel = null, sender = " ", avatar = false, text = "hi", faces = 0),
                Case(channelLabel = "#design ·", sender = "Mahua", avatar = false, text = "#design · Mahua: hi", faces = 0),
                Case(channelLabel = longChannel, sender = null, avatar = false, text = "$longChannel hi", faces = 0),
                // The sender face is an inline placeholder between the label and the name.
                Case(channelLabel = "#design ·", sender = "Mahua", avatar = true, text = "#design ·  Mahua: hi", faces = 1),
                // No sender, no face.
                Case(channelLabel = "#design ·", sender = null, avatar = true, text = "#design · hi", faces = 0),
            )
        for (case in cases) {
            val body =
                activityConversationBodyText(
                    sender = case.sender,
                    preview = AnnotatedString("hi"),
                    channelLabel = case.channelLabel,
                    senderAvatar = case.avatar,
                )
            assertEquals(case.text, body.text, "$case")
            val faces =
                body.getStringAnnotations(inlineContentAnnotationTag, 0, body.length)
                    .filter { it.item == ActivityConversationSenderAvatarInlineId }
            assertEquals(case.faces, faces.size, "$case")
        }
        // The body wraps to a second line before it ellipsizes.
        assertEquals(2, ActivityConversationPreviewMaxLines)
    }

    private fun homeSnapshot(serverId: String = "server-1"): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(id = ServerId(serverId), name = "Raft"),
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

    @Test
    fun homeSnapshotProjectsPinsMutesAndDmPeersForTheSameServerOnly() {
        val snapshot = homeSnapshot()
        val projected = snapshot.toActivityConversationListContext("server-1")
        assertEquals("me", projected.currentMemberId)
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

    @Test
    fun channelTileUsesTheHomeSidebarGlyphOfTheChannelOrItsThreadsParent() {
        val home =
            homeSnapshot().let { snapshot ->
                snapshot.copy(
                    server = snapshot.server.copy(publicPageLive = true),
                    channels =
                        listOf(
                            HomeChannelRow(threadId = ThreadId("general"), channelId = ChannelId("general"), name = "general", channelType = "channel"),
                            HomeChannelRow(threadId = ThreadId("secret"), channelId = ChannelId("secret"), name = "secret", channelType = "private"),
                            HomeChannelRow(
                                threadId = ThreadId("joint-hands"),
                                channelId = ChannelId("joint-hands"),
                                name = "joint-hands",
                                channelType = "channel",
                                kind = ConversationKind.Joint,
                            ),
                            HomeChannelRow(
                                threadId = ThreadId("lobby"),
                                channelId = ChannelId("lobby"),
                                name = "lobby",
                                channelType = "channel",
                                guestVisible = true,
                            ),
                        ),
                )
            }
        val homeContext = home.toActivityConversationListContext("server-1")
        // The projection is the Home sidebar's own helper, row for row.
        assertEquals(
            mapOf("general" to "#", "secret" to "lock", "joint-hands" to "git-branch", "lobby" to HomeChannelSymbolHashGlobe),
            homeContext.channelSymbolsByChannelId,
        )
        // The public-page mark needs the server's page live, as on Home.
        assertEquals(
            "#",
            home.copy(server = home.server.copy(publicPageLive = false))
                .toActivityConversationListContext("server-1")
                .channelSymbolsByChannelId["lobby"],
        )

        fun threadIn(parentId: String, parentKind: ConversationKind = ConversationKind.Channel) =
            thread.copy(parentChannelId = ChannelId(parentId), parentChannelName = parentId, parentChannelKind = parentKind)

        data class Case(val name: String, val item: InboxItem, val context: ActivityConversationListContext, val symbol: String)
        val cases =
            listOf(
                Case("normal channel", channel("c:general", "general"), homeContext, "#"),
                Case("private channel", channel("c:secret", "secret"), homeContext, "lock"),
                Case("joint channel", channel("c:joint", "joint-hands"), homeContext, "git-branch"),
                Case("public-page channel", channel("c:lobby", "lobby"), homeContext, HomeChannelSymbolHashGlobe),
                // A thread row leads with its parent channel's glyph (and keeps the thread badge).
                Case("thread in a private channel", threadIn("secret"), homeContext, "lock"),
                Case("thread in a joint channel", threadIn("joint-hands", ConversationKind.Joint), homeContext, "git-branch"),
                Case("thread in a normal channel", threadIn("general"), homeContext, "#"),
                // Home does not list the channel: the item's own kind (joint) or `#`.
                Case("unknown channel", channel("c:x", "elsewhere"), homeContext, "#"),
                Case(
                    "unknown joint channel",
                    channel("c:j", "elsewhere").copy(channelKind = ConversationKind.Joint),
                    ActivityConversationListContext.Empty,
                    "git-branch",
                ),
                Case("unknown joint thread", threadIn("elsewhere", ConversationKind.Joint), ActivityConversationListContext.Empty, "git-branch"),
                Case("no type and no Home row", channel("c:secret", "secret"), ActivityConversationListContext.Empty, "#"),
                // The item's own wire type (web `channelType` / `parentChannelType`) needs no Home row.
                Case("wire private, Home not loaded", channel("c:p", "elsewhere").copy(channelType = "private"), ActivityConversationListContext.Empty, "lock"),
                Case("wire joint, Home not loaded", channel("c:j2", "elsewhere").copy(channelType = "joint"), ActivityConversationListContext.Empty, "git-branch"),
                Case(
                    "thread, wire private parent",
                    threadIn("elsewhere").copy(parentChannelType = "private"),
                    ActivityConversationListContext.Empty,
                    "lock",
                ),
                // The wire type wins over Home; the globe still comes from the Home row (guestVisible).
                Case("wire private over Home #", channel("c:g", "general").copy(channelType = "private"), homeContext, "lock"),
                Case("wire channel on the public page", channel("c:l", "lobby").copy(channelType = "channel"), homeContext, HomeChannelSymbolHashGlobe),
            )
        for (case in cases) {
            val model = case.item.activityConversationRowModel(case.context, InboxFilter.All)
            assertEquals(case.symbol, model.channelSymbol, case.name)
            assertEquals(case.item.kind == InboxItemKind.Thread, model.leadingThreadBadge, case.name)
        }
        // DM rows are unchanged: the DM tile / face, never a channel glyph.
        val dmRow = dm("dm:x", "dm-x", senderId = "agent-x").copy(channelType = "dm")
        assertEquals("#", dmRow.activityConversationRowModel(homeContext, InboxFilter.All).channelSymbol)
    }

    @Test
    fun pinsAndSectionsWaitForHomeContentOfThisServer() {
        val ready = homeSnapshot().toActivityConversationListContext("server-1")
        val content = SharedHomeContentState.Content(snapshot = homeSnapshot(), session = session())
        val otherServer = SharedHomeContentState.Content(snapshot = homeSnapshot("server-2"), session = session())
        data class Case(
            val name: String,
            val previous: ActivityConversationListContext?,
            val home: SharedHomeContentState?,
            val expected: ActivityConversationListContext?,
        )
        val cases =
            listOf(
                // Pending (null): the panel keeps its loading state instead of painting unpinned rows.
                Case("home loading", previous = null, home = SharedHomeContentState.Loading(), expected = null),
                Case("another server's home", previous = null, home = otherServer, expected = null),
                Case("home content", previous = null, home = content, expected = ready),
                // Once painted, a Home reload or failure never moves the rows again.
                Case("reload after content", previous = ready, home = SharedHomeContentState.Loading(), expected = ready),
                Case("error after content", previous = ready, home = SharedHomeContentState.Error("boom"), expected = ready),
                // No Home facts will come: the unpinned list, no section labels.
                Case("home error", previous = null, home = SharedHomeContentState.Error("boom"), expected = ActivityConversationListContext.Empty),
                Case("home empty", previous = null, home = SharedHomeContentState.Empty(session()), expected = ActivityConversationListContext.Empty),
                Case("no home store", previous = null, home = null, expected = ActivityConversationListContext.Empty),
                Case("content after error", previous = ActivityConversationListContext.Empty, home = content, expected = ready),
            )
        val items = listOf(channel("channel:random", "random"), dm("dm:fable", "dm-fable", senderId = "agent-fable"))
        for (case in cases) {
            val next = nextActivityConversationListContext(case.previous, case.home, "server-1")
            assertEquals(case.expected, next, case.name)
            if (next == ActivityConversationListContext.Empty) {
                // Error / no Home: loaded order, no "Pinned" / "All conversations" labels.
                assertEquals(listOf("channel:random", "dm:fable"), activityConversationListEntries(items, next).map { it.key }, case.name)
            }
        }
        // With Home content the pinned DM peer floats under "Pinned".
        assertEquals(
            listOf("activity-list-section:Pinned", "dm:fable", "activity-list-section:AllConversations", "channel:random"),
            activityConversationListEntries(items, ready).map { it.key },
        )
    }

    @Test
    fun prewarmAfterClickFollowsTheDisplayedOrderWithPins() {
        fun replyThread(key: String) =
            thread.copy(
                key = "thread:$key",
                channelId = ChannelId("reply-$key"),
                threadChannelId = ChannelId("reply-$key"),
                parentChannelId = ChannelId("parent-$key"),
                parentMessageId = MessageId("message-$key"),
            )
        // Loaded order: a, #android (pinned), b. Displayed: #android under "Pinned", then a, b.
        val loaded = listOf(replyThread("a"), channel("channel:android", "android"), replyThread("b"))
        val entries = activityConversationListEntries(loaded, context)
        assertEquals(listOf("channel:android", "thread:a", "thread:b"), activityRowsInDisplayOrder(entries).map { it.key })

        data class Case(val clickedKey: String, val prewarmed: List<String>)
        val cases =
            listOf(
                // The pinned row is shown first: the next row the person sees is a, not b.
                Case(clickedKey = "channel:android", prewarmed = listOf("reply-a")),
                Case(clickedKey = "thread:a", prewarmed = listOf("reply-b")),
                Case(clickedKey = "thread:b", prewarmed = emptyList()),
            )
        for (case in cases) {
            val order = activityRowsInDisplayOrder(entries)
            val prewarmed =
                activityReplyThreadPrewarmNextAfterClick(items = order, clickedKey = case.clickedKey, serverId = "server-1")
            assertEquals(case.prewarmed, prewarmed.map { it.key.threadChannelId }, "$case")
        }
    }
}
