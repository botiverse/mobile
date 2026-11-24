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

    private fun loungeRows(vararg dms: HomeDirectMessageRow, enabled: Boolean = true) =
        homeSidebarAgentLoungeItems(
            enabled = enabled,
            directMessages = dms.toList(),
            known = RaftAgentLounges.lounges.value["server-1"].orEmpty(),
            markedUnreadIds = emptySet(),
            title = { "$it's Lounge" },
        )

    @Test
    fun theLoungeRowNeedsTheGateAnAgentAndJoinedOrMentioned() {
        val joined = AgentLoungeSummary(ChannelId("lounge-bugen"), joined = true, unreadCount = 3)
        val row = loungeRows(agentDm(joined)).single()
        assertEquals("lounge-bugen", row.id)
        assertEquals("Bugen's Lounge", row.name)
        assertEquals(3, row.unreadCount)
        assertEquals(RaftHomeAgentLoungeRow("lounge-bugen", 3, false, agentId = "agent-bugen", agentName = "Bugen", joined = true), row.agentLounge)
        assertTrue(loungeRows(agentDm(joined), enabled = false).isEmpty(), "flag off or a Guest")
        assertTrue(loungeRows(agentDm(joined, peerType = "human")).isEmpty())
        assertTrue(loungeRows(agentDm(null)).isEmpty(), "no Lounge yet")
        assertTrue(
            loungeRows(agentDm(AgentLoungeSummary(ChannelId("lounge-bugen"), joined = false, unreadCount = 4))).isEmpty(),
            "not joined and not mentioned",
        )
        assertEquals(
            true,
            loungeRows(agentDm(AgentLoungeSummary(ChannelId("lounge-bugen"), joined = false, unreadCount = 1, mentionCount = 1)))
                .single()
                .mentioned,
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
        // Web canWriteTasksInChannel reads the Lounge's canPost: a reader gets no task writes either.
        val readOnly = unjoined.withAgentLoungeAccess(lounge.copy(canPost = false))
        assertFalse(readOnly.showTaskStatusActions, "no message-menu task items")
        assertFalse(readOnly.composer.showAsTaskToggle, "no send as task")
        assertTrue(readOnly.showTaskOpenActions, "its tasks still open")
        assertTrue(opened.showTaskStatusActions)
        // A terminal access state the store closed stays closed: a Lounge never reopens it.
        for (closed in listOf(ThreadAccessResolution.NoPermission, ThreadAccessResolution.Error)) {
            assertEquals(unjoined, unjoined.withAgentLoungeAccess(lounge, closed))
        }
        val failed = unjoined.copy(errorCode = "http_500")
        assertEquals(failed, failed.withAgentLoungeAccess(lounge))
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
    fun theHomeLoungeRowAppearsOnceTheFirstPostJoins() {
        val notJoined = AgentLoungeSummary(ChannelId("lounge-bugen"), joined = false)
        RaftAgentLounges.rememberDirectMessages("server-1", listOf(agentDm(notJoined)))
        assertTrue(loungeRows(agentDm(notJoined)).isEmpty())
        RaftAgentLounges.markJoined("server-1", "lounge-bugen")
        assertEquals("lounge-bugen", loungeRows(agentDm(notJoined)).single().id)
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
        assertEquals(
            "@Wug won't be notified: a message posted here goes only to this Lounge's agent. Mention them in a thread to notify them.",
            excluded.pendingMentionActionDescription("#bugen-lounge"),
        )
        val ordinary = excluded.copy(reason = "not in channel", availableActions = listOf("notify"))
        assertTrue(ordinary.supportsMentionAction("notify"))
    }

    @Test
    fun underHeaderConversationSwitchVisibilityFollowsSurfaceAndEligibilityRules() {
        assertTrue(
            shouldShowAgentLoungeConversationSwitch(
                enabled = true,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                isLoungeConversation = true,
                agentId = "agent-bugen",
                personalAssistantAgentIds = emptySet(),
            ),
            "top-level Agent Lounge shows switch",
        )
        assertTrue(
            shouldShowAgentLoungeConversationSwitch(
                enabled = true,
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                isLoungeConversation = false,
                agentId = "agent-bugen",
                personalAssistantAgentIds = emptySet(),
            ),
            "eligible Agent DM shows switch",
        )
        assertFalse(
            shouldShowAgentLoungeConversationSwitch(
                enabled = false,
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                isLoungeConversation = false,
                agentId = "agent-bugen",
                personalAssistantAgentIds = emptySet(),
            ),
            "flag off / Guest hides switch",
        )
        assertFalse(
            shouldShowAgentLoungeConversationSwitch(
                enabled = true,
                surfaceKind = RaftThreadSurfaceKind.Channel,
                isLoungeConversation = false,
                agentId = "agent-bugen",
                personalAssistantAgentIds = emptySet(),
            ),
            "regular channel hides switch",
        )
        assertFalse(
            shouldShowAgentLoungeConversationSwitch(
                enabled = true,
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                isLoungeConversation = true,
                agentId = "agent-bugen",
                personalAssistantAgentIds = emptySet(),
            ),
            "reply thread hides switch",
        )
        assertFalse(
            shouldShowAgentLoungeConversationSwitch(
                enabled = true,
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                isLoungeConversation = false,
                agentId = null,
                personalAssistantAgentIds = emptySet(),
            ),
            "human DM hides switch",
        )
        assertFalse(
            shouldShowAgentLoungeConversationSwitch(
                enabled = true,
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                isLoungeConversation = false,
                agentId = "agent-assistant",
                personalAssistantAgentIds = setOf("agent-assistant"),
            ),
            "personal assistant hides switch",
        )
        assertFalse(
            shouldShowAgentLoungeConversationSwitch(
                enabled = true,
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                isLoungeConversation = false,
                agentId = "agent-bot",
                personalAssistantAgentIds = emptySet(),
                isAppBot = true,
            ),
            "App Bot hides switch",
        )
        assertFalse(
            shouldShowAgentLoungeConversationSwitch(
                enabled = true,
                surfaceKind = RaftThreadSurfaceKind.DirectMessage,
                isLoungeConversation = false,
                agentId = "agent-deleted",
                personalAssistantAgentIds = emptySet(),
                isDeletedAgent = true,
            ),
            "deleted agent hides switch",
        )
    }

    @Test
    fun underHeaderSwitchOtherSideUnreadRespectsMuteMarkedUnreadAndLiveFollowedThreads() {
        val dmWithUnread =
            agentDm(AgentLoungeSummary(ChannelId("lounge-bugen"), joined = true, unreadCount = 4, mentionCount = 1, listed = true))
                .copy(unreadCount = 2)
        // On Lounge -> shows DM unread count; Lounge side is active (empty).
        val onLounge =
            agentLoungeSwitchUnread(
                activeSide = AgentLoungeConversationSide.Lounge,
                agentId = "agent-bugen",
                directMessages = listOf(dmWithUnread),
            )
        assertEquals(AgentLoungeSwitchUnread() to AgentLoungeSwitchUnread(count = 2), onLounge)
        assertEquals("2", formatAgentLoungeSwitchBadge(onLounge.second))

        // Muted DM -> 0 unread on Private tab.
        val mutedDm =
            agentLoungeSwitchUnread(
                activeSide = AgentLoungeConversationSide.Lounge,
                agentId = "agent-bugen",
                directMessages =
                    listOf(
                        dmWithUnread.copy(
                            activityNotificationPreference =
                                build.raft.app.model.ChannelActivityNotificationPreference(
                                    activityMuted = true,
                                    activityMuteSupported = true,
                                ),
                        ),
                    ),
            )
        assertEquals(AgentLoungeSwitchUnread(), mutedDm.second)

        // DM marked unread with 0 count -> dot.
        val markedDm =
            agentLoungeSwitchUnread(
                activeSide = AgentLoungeConversationSide.Lounge,
                agentId = "agent-bugen",
                directMessages = listOf(dmWithUnread.copy(unreadCount = 0)),
                markedUnreadIds = setOf("dm-bugen"),
            )
        assertEquals(AgentLoungeSwitchUnread(dot = true), markedDm.second)
        assertEquals("•", formatAgentLoungeSwitchBadge(markedDm.second))

        // On Private DM -> shows Lounge unread count; Private side is active (empty).
        val onPrivate =
            agentLoungeSwitchUnread(
                activeSide = AgentLoungeConversationSide.Private,
                agentId = "agent-bugen",
                directMessages = listOf(dmWithUnread),
            )
        assertEquals(AgentLoungeSwitchUnread(count = 4) to AgentLoungeSwitchUnread(), onPrivate)

        // Muted Lounge -> 0 unread on Lounge tab.
        val mutedLoungeKnown =
            lounge.copy(
                activityNotificationPreference =
                    build.raft.app.model.ChannelActivityNotificationPreference(activityMuted = true, activityMuteSupported = true),
            )
        val mutedLounge =
            agentLoungeSwitchUnread(
                activeSide = AgentLoungeConversationSide.Private,
                agentId = "agent-bugen",
                directMessages = listOf(dmWithUnread),
                knownLounges = mapOf("lounge-bugen" to mutedLoungeKnown),
            )
        assertEquals(AgentLoungeSwitchUnread(), mutedLounge.first)

        // Live followed-thread unread drives non-member Lounge count when unread enrichment is live.
        val liveFollowed =
            agentLoungeSwitchUnread(
                activeSide = AgentLoungeConversationSide.Private,
                agentId = "agent-bugen",
                directMessages =
                    listOf(
                        agentDm(AgentLoungeSummary(ChannelId("lounge-bugen"), joined = false, unreadCount = 9, listed = true)),
                    ),
                followedThreadUnread =
                    mapOf(
                        "t-1" to build.raft.app.data.HomeFollowedThreadUnread(ChannelId("lounge-bugen"), unreadCount = 3, hasMention = true),
                    ),
                useLiveFollowedThreadUnread = true,
            )
        assertEquals(AgentLoungeSwitchUnread(count = 3), liveFollowed.first)
        assertEquals("99+", formatAgentLoungeSwitchBadge(AgentLoungeSwitchUnread(count = 120)))
    }
}
