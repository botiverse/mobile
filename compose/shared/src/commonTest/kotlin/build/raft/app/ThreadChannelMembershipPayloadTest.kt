package build.raft.app

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What a conversation and Home take from leaving or joining the channel on screen. */
class ThreadChannelMembershipPayloadTest {
    private fun channelPage() =
        RaftThreadScreenPayload(
            sourceThreadId = "channel-1",
            title = "#design",
            subtitle = "Reviews",
            surfaceKind = RaftThreadSurfaceKind.Channel,
            channelKind = "channel",
        )

    @Test
    fun leavingThisChannelShowsTheJoinFooterState() {
        val left = channelPage().withChannelLeft("channel-1")

        assertFalse(left.joined)
        assertFalse(left.canSend)
        assertFalse(left.composer.enabled)
    }

    @Test
    fun leavingAnotherChannelChangesNothing() {
        val page = channelPage()

        assertSame(page, page.withChannelLeft("channel-2"))
    }

    @Test
    fun aJoinOnTheChannelsOwnPageCarriesARowForHome() {
        val intent = channelJoinedHomeIntent(channelPage().withChannelLeft("channel-1"), "server-1", ChannelId("channel-1"))

        assertEquals("server-1", intent.serverId)
        assertEquals(ChannelId("channel-1"), intent.channelId)
        val row = intent.rowIfMissing!!
        assertEquals("design", row.name)
        assertEquals("Reviews", row.subtitle)
        assertEquals(ConversationKind.Channel, row.kind)
        assertTrue(row.joined)
    }

    @Test
    fun aJoinElsewhereCarriesNoRowForHome() {
        assertNull(channelJoinedHomeIntent(channelPage(), "server-1", ChannelId("channel-2")).rowIfMissing)
        assertNull(channelJoinedHomeIntent(channelPage().copy(title = " "), "server-1", ChannelId("channel-1")).rowIfMissing)
        assertNull(
            channelJoinedHomeIntent(
                channelPage().copy(surfaceKind = RaftThreadSurfaceKind.ReplyThread, parentChannelId = "channel-1"),
                "server-1",
                ChannelId("channel-1"),
            ).rowIfMissing,
        )
    }
}
