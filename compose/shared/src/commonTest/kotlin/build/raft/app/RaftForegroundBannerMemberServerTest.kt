package build.raft.app

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Architecture review B, site 8: the banner host read its server from
 * `currentSessionFlow.collectAsState(null)`, so on the first frame (and whenever the host had no
 * override) the sender's profile was looked up on no server, and a cross-server banner looked it up
 * on the wrong one. The banner names its own server; that is the roster its sender belongs to.
 */
class RaftForegroundBannerMemberServerTest {
    @Test
    fun senderProfileIsResolvedOnTheBannersOwnServerBeforeTheSessionIsRead() {
        assertEquals(ServerId("server-a"), foregroundBannerMemberServerId(banner("server-a"), hostServerId = null))
    }

    @Test
    fun crossServerBannerResolvesItsSenderOnItsOwnServer() {
        assertEquals(ServerId("server-b"), foregroundBannerMemberServerId(banner("server-b"), hostServerId = ServerId("server-a")))
    }

    @Test
    fun bannerWithoutAServerFallsBackToTheHostsServer() {
        assertEquals(ServerId("server-a"), foregroundBannerMemberServerId(banner(" "), hostServerId = ServerId("server-a")))
    }

    private fun banner(serverId: String): RaftForegroundMessageBanner {
        val conversation =
            ThreadLaunchConversationIdentity(
                threadId = ThreadId("thread-1"),
                channelId = ChannelId("channel-1"),
                kind = ConversationKind.Channel
            )
        return RaftForegroundMessageBanner(
            id = "banner-1",
            serverId = serverId,
            focusedMessageId = "message-1",
            conversation = conversation,
            openRequest = HomeMessageOpenRequest(conversation, "message-1"),
            title = "title",
            context = "context",
            threadContext = null,
            body = "body",
            iconKind = RaftForegroundMessageBannerIconKind.Channel,
            iconName = null,
            senderMemberId = "member-1",
            avatarWireValue = null,
            avatarLabel = "A"
        )
    }
}
