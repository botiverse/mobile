package build.raft.app

import build.raft.app.data.AgentLoungeSummary
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.InboxFilter
import build.raft.app.data.InboxItem
import build.raft.app.model.InboxItemKind
import build.raft.app.data.RaftAgentLounge
import build.raft.app.data.RaftAgentLounges
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MessageId
import build.raft.app.model.ThreadId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Agent Lounge v0 rules the conversation page, Home, Activity and the composer strip follow (web
 * slock-fb 2026-11-07: features/channel/tests/agentLoungeChatPanel, features/sidebar/tests/
 * agentLoungeSidebar, features/thread/tests/agentLoungeThread + activityLoungeRows,
 * tests/agentLoungePendingMention).
 */
class AgentLoungeRulesTest {
    @AfterTest
    fun forgetLounges() = RaftAgentLounges.clear()

    private val lounge = RaftAgentLounge(ChannelId("lounge-bugen"), "agent-bugen", agentName = "Bugen", channelName = "bugen-lounge")

    private fun agentDm(summary: AgentLoungeSummary?, peerType: String = "agent") =
        HomeDirectMessageRow(
            threadId = ThreadId("dm-bugen"),
            channelId = ChannelId("dm-bugen"),
            name = "Bugen",
            directMessagePeerType = peerType,
            presenceMemberId = MemberId("agent-bugen"),
            lounge = summary,
        )

    @Test
    fun theSidebarSubRowNeedsTheGateAnAgentAndJoinedOrMentioned() {
        val joined = AgentLoungeSummary(ChannelId("lounge-bugen"), joined = true, unreadCount = 3)
        assertEquals(
            RaftHomeAgentLoungeRow("lounge-bugen", unreadCount = 3, mentioned = false),
            agentDm(joined).agentLoungeSidebarRow(enabled = true, serverId = "server-1"),
        )
        assertNull(agentDm(joined).agentLoungeSidebarRow(enabled = false, serverId = "server-1"), "flag off or a Guest")
        assertNull(agentDm(joined, peerType = "human").agentLoungeSidebarRow(enabled = true, serverId = "server-1"))
        assertNull(agentDm(null).agentLoungeSidebarRow(enabled = true, serverId = "server-1"), "no Lounge yet")
        assertNull(
            agentDm(AgentLoungeSummary(ChannelId("lounge-bugen"), joined = false, unreadCount = 4))
                .agentLoungeSidebarRow(enabled = true, serverId = "server-1"),
            "not joined and not mentioned",
        )
        assertEquals(
            true,
            agentDm(AgentLoungeSummary(ChannelId("lounge-bugen"), joined = false, unreadCount = 1, mentionCount = 1))
                .agentLoungeSidebarRow(enabled = true, serverId = "server-1")
                ?.mentioned,
        )
    }

    @Test
    fun aLoungePageHasNoJoinAndPostsWhileItMay() {
        assertFalse(threadFooterShowsJoin(joined = false, conversationLounge = lounge), "the first post joins")
        assertTrue(threadFooterShowsJoin(joined = false, conversationLounge = null), "an ordinary channel keeps Join")
        assertNull(agentLoungeFooterKind(lounge, lounge), "the composer before joining")
        assertEquals(AgentLoungeFooterKind.ReadOnly, agentLoungeFooterKind(lounge, lounge.copy(canPost = false)))
        assertEquals(AgentLoungeFooterKind.Archived, agentLoungeFooterKind(lounge.copy(archived = true), lounge.copy(archived = true)))
        // A thread in a Lounge you may only read: read-only, never a Join.
        assertEquals(AgentLoungeFooterKind.ReadOnly, agentLoungeFooterKind(null, lounge.copy(canPost = false)))

        val unjoined =
            RaftThreadScreenPayload(instanceId = "lounge", surfaceKind = RaftThreadSurfaceKind.Channel, streamChannelId = "lounge-bugen")
                .copy(joined = false, canSend = false)
        val opened = unjoined.withAgentLoungeAccess(lounge)
        assertTrue(opened.joined && opened.canSend && opened.composer.enabled)
        assertFalse(unjoined.withAgentLoungeAccess(lounge.copy(canPost = false)).canSend)
        assertEquals(unjoined, unjoined.withAgentLoungeAccess(null))
        assertEquals("lounge-bugen", unjoined.agentLoungeLookupChannelId())
        assertEquals(
            "lounge-bugen",
            RaftThreadScreenPayload(instanceId = "t", surfaceKind = RaftThreadSurfaceKind.ReplyThread, parentChannelId = "lounge-bugen")
                .agentLoungeLookupChannelId(),
            "a thread looks its parent up",
        )
    }

    @Test
    fun onlyAnAgentDmOffersTheLoungeToggle() {
        val agentDm =
            RaftThreadScreenPayload(
                instanceId = "dm",
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                dmPeerIdentity = MemberDetailIdentity(MemberId("agent-bugen"), MemberKind.Agent),
            )
        assertEquals("agent-bugen", agentDm.agentLoungeDmAgentId())
        assertNull(agentDm.copy(dmPeerIdentity = MemberDetailIdentity(MemberId("u"), MemberKind.Human)).agentLoungeDmAgentId())
    }

    @Test
    fun aLoungeKnownOnlyFromItsDmRowOpensById() {
        val identity = agentLoungeConversationIdentityOrNull("server-1", "lounge-bugen", listOf(agentDm(AgentLoungeSummary(ChannelId("lounge-bugen")))))
        assertEquals("lounge-bugen", identity?.channelId?.value)
        assertEquals(ConversationKind.Channel, identity?.kind)
        assertNull(agentLoungeConversationIdentityOrNull("server-1", "general", emptyList()))
    }

    @Test
    fun activityRowsOfALoungeCarryItsAgent() {
        val context =
            ActivityConversationListContext(
                loungesByChannelId = mapOf("lounge-bugen" to ActivityConversationLounge("agent-bugen", "Bugen")),
            )
        val channelRow =
            InboxItem(
                key = "channel:lounge-bugen",
                kind = InboxItemKind.Channel,
                channelId = ChannelId("lounge-bugen"),
                channelName = "bugen-lounge",
                lastMessagePreview = "lounge preview",
            )
        assertEquals("Bugen", channelRow.activityConversationRowModel(context, InboxFilter.All).lounge?.agentName)
        val threadRow =
            InboxItem(
                key = "thread:t1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("t1"),
                channelName = "thread",
                threadChannelId = ChannelId("t1"),
                parentChannelId = ChannelId("lounge-bugen"),
                parentChannelName = "bugen-lounge",
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("p1"),
                parentMessagePreview = "lounge topic",
            )
        assertEquals("agent-bugen", threadRow.activityConversationRowModel(context, InboxFilter.All).lounge?.agentId)
        assertNull(
            channelRow.copy(channelId = ChannelId("general"), channelName = "general")
                .activityConversationRowModel(context, InboxFilter.All).lounge,
            "an ordinary channel keeps its # tile",
        )
    }

    @Test
    fun anAgentLeftOutOfALoungeOffersNoNotifyOrAdd() {
        val excluded =
            RaftPendingMentionActionItem(
                resolutionId = "resolution-wug",
                targetType = "agent",
                targetHandle = "Wug",
                reason = "lounge_agent_excluded",
                availableActions = listOf("notify", "add"),
            )
        assertFalse(excluded.supportsMentionAction("notify"))
        assertFalse(excluded.supportsMentionAction("invite"))
        assertEquals("@Wug won't be notified: other agents can only read this chat.", excluded.pendingMentionActionDescription("#bugen-lounge"))
        val ordinary = excluded.copy(reason = "not in channel", availableActions = listOf("notify"))
        assertTrue(ordinary.supportsMentionAction("notify"))
    }
}
