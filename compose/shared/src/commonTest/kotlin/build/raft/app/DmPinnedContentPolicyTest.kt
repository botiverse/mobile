package build.raft.app

import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.channelTypeSupportsAnnouncement
import build.raft.app.model.ChannelCapabilities
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.toThreadTimelineSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DM pinned content parity rules (botiverse/slock 05ad55edb / #16563):
 * - Supported conversation types (public, private, joint channels and DMs; never reply threads or tasks).
 * - DM vs channel permission rules for pin / replace / remove (either DM participant while not
 *   archived; channel requires `editChannelMetadata` and not archived).
 * - DM surface classification for untitled pinned-content notices (DMs and DM reply threads).
 */
class DmPinnedContentPolicyTest {
    private val serverId = ServerId("srv-1")

    private fun homeSnapshot(
        channels: List<HomeChannelRow> = emptyList(),
        directMessages: List<HomeDirectMessageRow> = emptyList(),
    ): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(id = serverId, name = "HQ"),
            currentMember = MemberProfile(id = MemberId("u-1"), displayName = "Ada", handle = "ada", kind = MemberKind.Human),
            channels = channels,
            directMessages = directMessages,
            unreadTotal = 0,
        )

    @Test
    fun threadAnnouncementChannelTypeAllowsChannelsAndDmsAndExcludesThreadsAndTasks() {
        assertEquals("dm", threadAnnouncementChannelType(RaftThreadSurfaceKind.DirectMessage, "dm", isTaskConversation = false))
        assertEquals("dm", threadAnnouncementChannelType(RaftThreadSurfaceKind.DirectMessage, "channel", isTaskConversation = false))
        assertEquals("channel", threadAnnouncementChannelType(RaftThreadSurfaceKind.Channel, "channel", isTaskConversation = false))
        assertEquals("private", threadAnnouncementChannelType(RaftThreadSurfaceKind.Channel, "private", isTaskConversation = false))
        assertEquals("joint", threadAnnouncementChannelType(RaftThreadSurfaceKind.Channel, "joint", isTaskConversation = false))
        assertNull(threadAnnouncementChannelType(RaftThreadSurfaceKind.Channel, "task", isTaskConversation = true))
        assertNull(threadAnnouncementChannelType(RaftThreadSurfaceKind.ReplyThread, "dm", isTaskConversation = false))
        assertNull(threadAnnouncementChannelType(RaftThreadSurfaceKind.ReplyThread, "channel", isTaskConversation = false))

        assertTrue(channelTypeSupportsAnnouncement(threadAnnouncementChannelType(RaftThreadSurfaceKind.DirectMessage, "dm", isTaskConversation = false)))
        assertFalse(channelTypeSupportsAnnouncement(threadAnnouncementChannelType(RaftThreadSurfaceKind.ReplyThread, "dm", isTaskConversation = false)))
        assertFalse(channelTypeSupportsAnnouncement(threadAnnouncementChannelType(RaftThreadSurfaceKind.Channel, "task", isTaskConversation = true)))
    }

    @Test
    fun canEditAllowsHumanAndAgentDmParticipantsUnlessArchived() {
        val snapshot =
            homeSnapshot(
                directMessages =
                    listOf(
                        HomeDirectMessageRow(
                            threadId = ThreadId("dm-human"),
                            channelId = ChannelId("dm-human"),
                            channelType = "dm",
                            name = "Alice",
                            directMessagePeerType = "user",
                            archived = false,
                        ),
                        HomeDirectMessageRow(
                            threadId = ThreadId("dm-agent"),
                            channelId = ChannelId("dm-agent"),
                            channelType = "dm",
                            name = "Tenny",
                            directMessagePeerType = "agent",
                            archived = false,
                        ),
                        HomeDirectMessageRow(
                            threadId = ThreadId("dm-archived"),
                            channelId = ChannelId("dm-archived"),
                            channelType = "dm",
                            name = "Old peer",
                            directMessagePeerType = "user",
                            archived = true,
                        ),
                    ),
            )

        assertTrue(canEditChannelAnnouncement("dm", snapshot, serverId.value, "dm-human"))
        assertTrue(canEditChannelAnnouncement("dm", snapshot, serverId.value, "dm-agent"))
        assertFalse(canEditChannelAnnouncement("dm", snapshot, serverId.value, "dm-archived"))
        // Cold-opened DM not yet in HomeSnapshot falls back to payload archived flag.
        assertTrue(canEditChannelAnnouncement("dm", snapshot, serverId.value, "dm-cold", channelArchivedFallback = false))
        assertFalse(canEditChannelAnnouncement("dm", snapshot, serverId.value, "dm-cold", channelArchivedFallback = true))
    }

    @Test
    fun canEditRequiresEditChannelMetadataAndActiveChannelOutsideDms() {
        val snapshot =
            homeSnapshot(
                channels =
                    listOf(
                        HomeChannelRow(
                            threadId = ThreadId("ch-admin"),
                            channelId = ChannelId("ch-admin"),
                            channelType = "channel",
                            name = "ops",
                            archived = false,
                            channelCapabilities = ChannelCapabilities(editChannelMetadata = true),
                        ),
                        HomeChannelRow(
                            threadId = ThreadId("ch-member"),
                            channelId = ChannelId("ch-member"),
                            channelType = "private",
                            name = "secret",
                            archived = false,
                            channelCapabilities = ChannelCapabilities(editChannelMetadata = false),
                        ),
                        HomeChannelRow(
                            threadId = ThreadId("ch-archived"),
                            channelId = ChannelId("ch-archived"),
                            channelType = "joint",
                            kind = ConversationKind.Joint,
                            name = "partner",
                            archived = true,
                            channelCapabilities = ChannelCapabilities(editChannelMetadata = true),
                        ),
                    ),
            )

        assertTrue(canEditChannelAnnouncement("channel", snapshot, serverId.value, "ch-admin"))
        assertFalse(canEditChannelAnnouncement("private", snapshot, serverId.value, "ch-member"))
        assertFalse(canEditChannelAnnouncement("joint", snapshot, serverId.value, "ch-archived"))
        assertFalse(canEditChannelAnnouncement("channel", snapshot, "srv-other", "ch-admin"))
        assertFalse(canEditChannelAnnouncement("thread", snapshot, serverId.value, "ch-admin"))
        assertFalse(canEditChannelAnnouncement("task", snapshot, serverId.value, "ch-admin"))
        assertFalse(canEditChannelAnnouncement(null, snapshot, serverId.value, "ch-admin"))
    }

    @Test
    fun timelineSliceMarksDirectMessagesAndDmReplyThreadsAsDmSurface() {
        val dmSlice =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                channelKind = "dm",
            ).toThreadTimelineSlice()
        val dmReplySlice =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                channelKind = "thread",
                parentSurfaceKind = RaftThreadSurfaceKind.DirectMessage,
                parentChannelKind = "dm",
            ).toThreadTimelineSlice()
        val channelSlice =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "channel",
            ).toThreadTimelineSlice()
        val channelReplySlice =
            RaftThreadScreenPayload(
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                channelKind = "thread",
                parentSurfaceKind = RaftThreadSurfaceKind.Channel,
                parentChannelKind = "channel",
            ).toThreadTimelineSlice()

        assertTrue(dmSlice.directMessageSurface)
        assertTrue(dmReplySlice.directMessageSurface)
        assertFalse(channelSlice.directMessageSurface)
        assertFalse(channelReplySlice.directMessageSurface)
    }
}
