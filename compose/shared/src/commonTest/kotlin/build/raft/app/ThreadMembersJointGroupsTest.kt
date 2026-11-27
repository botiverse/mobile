package build.raft.app

import build.raft.app.model.ChannelExternalMemberProfile
import build.raft.app.thread.RaftThreadMemberItem
import build.raft.app.thread.ThreadMembersRosterGroup
import build.raft.app.thread.ThreadMembersRosterSection
import build.raft.app.thread.key
import build.raft.app.thread.threadMembersGroupsByViewerServer
import build.raft.app.thread.threadMembersRosterSections
import build.raft.app.thread.threadMembersSplitByViewerServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The members page of a joint channel, grouped by the viewed server (botiverse/slock #12853,
 * packages/web/src/features/channel/jointMemberGroups.ts and ChannelMembers.tsx at 4be606f7fd):
 * "Members of {server}" then "Other servers", people before agents in each, an empty group left
 * out, Slack participants in their own section after the groups. Every other roster keeps Humans,
 * Agents, then Slack participants.
 */
class ThreadMembersJointGroupsTest {
    private fun human(id: String, inViewerServer: Boolean?, role: String? = null) =
        RaftThreadMemberItem(id = id, displayName = id, kind = "human", effectiveChannelRole = role, inViewerServer = inViewerServer)

    private fun agent(id: String, inViewerServer: Boolean?) =
        RaftThreadMemberItem(id = id, displayName = id, kind = "agent", inViewerServer = inViewerServer)

    // The web's jointMemberListGroups.behavior test roster (#12853), interleaved as a roster may
    // come: a guest of the viewed server stays in its group; another server's agent does not.
    private val ebby = human("ebby", inViewerServer = true)
    private val alphaBot = agent("alpha-bot", inViewerServer = false)
    private val gus = human("gus", inViewerServer = true, role = "guest")
    private val tenny = agent("tenny", inViewerServer = true)
    private val dana = human("dana", inViewerServer = false)
    private val roster = listOf(ebby, alphaBot, gus, tenny, dana)

    private val sam = ChannelExternalMemberProfile(id = "slack-sam", provider = "slack", displayName = "Sam", handles = listOf("sam"))

    /** Each section as its list key and the ids it lists, in page order. */
    private fun sections(
        channelKind: String?,
        members: List<RaftThreadMemberItem>,
        visibleMembers: List<RaftThreadMemberItem> = members,
        slack: List<ChannelExternalMemberProfile> = emptyList(),
    ): List<Pair<String, List<String>>> =
        threadMembersRosterSections(channelKind, members, visibleMembers, slack).map { section ->
            when (section) {
                is ThreadMembersRosterSection.Members -> section.key to section.members.map { it.id }
                is ThreadMembersRosterSection.SlackParticipants -> section.key to section.participants.map { it.id }
            }
        }

    @Test
    fun aJointRosterListsTheViewedServerThenOtherServersWithPeopleBeforeAgents() {
        assertTrue(threadMembersGroupsByViewerServer(JointChannelKindWireValue, roster))
        assertEquals(
            listOf(
                "section:viewer-server" to listOf("ebby", "gus", "tenny"),
                "section:other-servers" to listOf("dana", "alpha-bot"),
            ),
            sections(JointChannelKindWireValue, roster),
        )
        // The split alone (web `splitByViewerServer`): the same two groups, viewed server first.
        assertEquals(
            listOf(ThreadMembersRosterGroup.ViewerServer, ThreadMembersRosterGroup.OtherServers),
            threadMembersSplitByViewerServer(roster).map { it.group },
        )
    }

    @Test
    fun anyOtherChannelOrARosterWithoutTheFieldKeepsHumansThenAgents() {
        val unchanged =
            listOf(
                "section:humans" to listOf("ebby", "gus", "dana"),
                "section:agents" to listOf("alpha-bot", "tenny"),
                "section:external" to listOf("slack-sam"),
            )
        for (kind in listOf(null, "channel", "private", "dm", "thread")) {
            assertFalse(threadMembersGroupsByViewerServer(kind, roster), "kind=$kind")
            assertEquals(unchanged, sections(kind, roster, slack = listOf(sam)), "kind=$kind")
        }
        // A joint channel on an older server, whose rows carry no `inViewerServer`.
        val olderServer = roster.map { it.copy(inViewerServer = null) }
        assertFalse(threadMembersGroupsByViewerServer(JointChannelKindWireValue, olderServer))
        assertEquals(unchanged, sections(JointChannelKindWireValue, olderServer, slack = listOf(sam)))
    }
}
