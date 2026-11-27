package build.raft.app

import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.settings.SearchEntityResult
import build.raft.app.settings.SearchEntityType
import build.raft.app.settings.withoutAgentKindLabel
import build.raft.app.thread.key
import build.raft.app.thread.threadMembersRosterSubtitle
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun aBotsSearchSubtitleDropsTheAgentKindWord() {
        // As `toHomeSearchEntityResult` builds it: "Agent · @stamp", only "@stamp" marked.
        fun agent(subtitle: String, range: IntRange?) =
            searchResult(SearchEntityType.Agent, "agent-1", MemberKind.Agent).copy(subtitle = subtitle, subtitleDataRange = range)
        val handle = agent("Agent · @stamp", 8..13).withoutAgentKindLabel("Agent")
        assertEquals("@stamp", handle.subtitle)
        assertEquals(0..5, handle.subtitleDataRange)
        val members = agent("Agent · @stamp · Members", 8..13).withoutAgentKindLabel("Agent")
        assertEquals("@stamp · Members", members.subtitle)
        assertEquals(0..5, members.subtitleDataRange)
        val bare = agent("Agent", IntRange.EMPTY).withoutAgentKindLabel("Agent")
        assertEquals("", bare.subtitle)
        assertEquals(IntRange.EMPTY, bare.subtitleDataRange)
        // A description or another label is left alone; so is anything that is not an agent result.
        assertEquals("Agents helper", agent("Agents helper", null).withoutAgentKindLabel("Agent").subtitle)
        val dm = searchResult(SearchEntityType.DirectMessage, "agent-1", MemberKind.Agent).copy(subtitle = "Agent · x")
        assertEquals("Agent · x", dm.withoutAgentKindLabel("Agent").subtitle)
        // Localized label (zh-CN catalog value is irrelevant here: whatever the row passes).
        assertEquals("@stamp", agent("智能体 · @stamp", 6..11).withoutAgentKindLabel("智能体").subtitle)
    }

    // Channel members and Add Members.

    @Test
    fun aBotsRosterLineIsEmptyWithoutPresence() {
        // The roster row passes no live activity for a Bot, so it has no "Offline" line.
        assertEquals("", threadMembersRosterSubtitle(memberKind = "agent", liveActivityLabel = null, humanRoleLabel = ""))
        assertEquals("Offline", threadMembersRosterSubtitle(memberKind = "agent", liveActivityLabel = "Offline", humanRoleLabel = ""))
    }
}
