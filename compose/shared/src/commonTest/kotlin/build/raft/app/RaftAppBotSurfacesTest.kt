package build.raft.app

import build.raft.app.data.ForwardMessageTarget
import build.raft.app.data.ForwardMessageTargetKind
import build.raft.app.data.ForwardMessageTargetRequiredAction
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which row on each App Bot surface asks the directory "is this an App Bot?"
 * (`managedByAppInstallId`, [appBotIdentityOrNull]), and what a Bot row leaves out. Web
 * (botiverse/slock staging): `Sidebar.tsx` `DmRow` / `AgentDmRow`, `EntityResultRow`,
 * `MessageInput` `MentionCandidateBody`, `ForwardComposerTargetList` / `ChannelPicker`,
 * `ChannelMemberList` / `ChannelMembers` / `CreateChannelDialog`.
 */
class RaftAppBotSurfacesTest {
    // Home DM list: no runtime / activity meta and no presence for a Bot.

    @Test
    fun homeDmRowOfAnAppBotHasNoAgentMeta() {
        assertEquals(
            listOf(RaftHomeDmTrailingSlot.AgentMeta),
            raftHomeDmTrailingSlots(badge = null, hasDraft = false, isAgent = true),
        )
        assertEquals(
            emptyList(),
            raftHomeDmTrailingSlots(badge = null, hasDraft = false, isAgent = true, isAppBot = true),
        )
        // The draft mark and the unread count are the row's, not the agent's: they stay.
        assertEquals(
            listOf(RaftHomeDmTrailingSlot.Draft),
            raftHomeDmTrailingSlots(badge = null, hasDraft = true, isAgent = true, isAppBot = true),
        )
        assertEquals(
            listOf(RaftHomeDmTrailingSlot.UnreadBadge),
            raftHomeDmTrailingSlots(badge = "3", hasDraft = true, isAgent = true, isAppBot = true),
        )
    }

    @Test
    fun homeDmRowShowsPresenceOnlyForOrdinaryAgents() {
        assertEquals("agent-1", raftHomeDmPresenceAgentId("agent-1", isAgent = true, isAppBot = false))
        assertNull(raftHomeDmPresenceAgentId("agent-1", isAgent = true, isAppBot = true))
        assertNull(raftHomeDmPresenceAgentId("human-1", isAgent = false, isAppBot = false))
        assertNull(raftHomeDmPresenceAgentId(" ", isAgent = true, isAppBot = false))
    }

    // Search: agent results and DMs with an agent; never people, channels or computers.

    private fun searchResult(type: SearchEntityType, memberId: String?, kind: MemberKind?) =
        SearchEntityResult(
            key = "k",
            type = type,
            title = "Stamp",
            subtitle = "Agent · @stamp",
            threadId = null,
            memberIdentity = if (memberId != null && kind != null) MemberDetailIdentity(MemberId(memberId), kind) else null,
            conversationIdentity = null,
        )

    @Test
    fun searchAsksAboutAgentsAndAgentDmsOnly() {
        assertEquals("agent-1", searchResult(SearchEntityType.Agent, "agent-1", MemberKind.Agent).appBotLookupMemberId())
        assertEquals("agent-1", searchResult(SearchEntityType.DirectMessage, "agent-1", MemberKind.Agent).appBotLookupMemberId())
        assertNull(searchResult(SearchEntityType.DirectMessage, "human-1", MemberKind.Human).appBotLookupMemberId())
        assertNull(searchResult(SearchEntityType.Human, "human-1", MemberKind.Human).appBotLookupMemberId())
        assertNull(searchResult(SearchEntityType.Channel, null, null).appBotLookupMemberId())
        assertNull(searchResult(SearchEntityType.DirectMessage, null, null).appBotLookupMemberId())
    }

    // Mentions.

    @Test
    fun mentionAsksAboutAgentCandidatesOnly() {
        assertEquals(
            "agent-1",
            RaftComposerMemberItem(displayName = "Stamp", handle = "stamp", kind = MemberKind.Agent, memberId = "agent-1")
                .mentionAppBotLookupMemberId(),
        )
        assertNull(
            RaftComposerMemberItem(displayName = "Ada", handle = "ada", kind = MemberKind.Human, memberId = "human-1")
                .mentionAppBotLookupMemberId(),
        )
        assertNull(RaftComposerMemberItem(displayName = "Stamp", handle = "stamp", kind = MemberKind.Agent).mentionAppBotLookupMemberId())
    }

    // Forward: open DMs and new-DM candidates; channels never.

    private val server = ServerId("srv-1")

    @Test
    fun forwardAsksAboutDmTargetsByTheirPeer() {
        val openDm =
            ForwardMessageTarget(
                serverId = server,
                kind = ForwardMessageTargetKind.RecentDirectMessage,
                id = "dm-1",
                title = "Stamp",
                channelId = ChannelId("dm-1"),
                memberId = MemberId("agent-1"),
            )
        val newDm =
            ForwardMessageTarget(
                serverId = server,
                kind = ForwardMessageTargetKind.AgentDirectMessageCandidate,
                id = "agent-2",
                title = "Release helper",
                memberId = MemberId("agent-2"),
                requiredAction = ForwardMessageTargetRequiredAction.CreateDirectMessage,
            )
        val channel =
            ForwardMessageTarget(
                serverId = server,
                kind = ForwardMessageTargetKind.RecentChannel,
                id = "ch-1",
                title = "general",
                channelId = ChannelId("ch-1"),
            )
        assertEquals("agent-1", openDm.toRaftForwardTargetPickerItem().forwardAppBotLookupMemberId())
        assertEquals("agent-2", newDm.toRaftForwardTargetPickerItem().forwardAppBotLookupMemberId())
        assertNull(channel.toRaftForwardTargetPickerItem().forwardAppBotLookupMemberId())
    }

    // Channel members and Add Members.

    @Test
    fun channelMemberRowsAskAboutAgentsOnly() {
        assertEquals("agent-1", RaftThreadMemberItem(id = "agent-1", displayName = "Stamp", kind = "agent").threadMemberAppBotLookupId())
        assertNull(RaftThreadMemberItem(id = "human-1", displayName = "Ada", kind = "human").threadMemberAppBotLookupId())
    }

    @Test
    fun aBotsRosterLineIsEmptyWithoutPresence() {
        // The roster row passes no live activity for a Bot, so it has no "Offline" line.
        assertEquals("", threadMembersRosterSubtitle(memberKind = "agent", liveActivityLabel = null, humanRoleLabel = ""))
        assertEquals("Offline", threadMembersRosterSubtitle(memberKind = "agent", liveActivityLabel = "Offline", humanRoleLabel = ""))
    }
}
