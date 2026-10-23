package build.raft.app

import build.raft.app.MemberPickerTestFixtures.agent
import build.raft.app.MemberPickerTestFixtures.human
import build.raft.app.MemberPickerTestFixtures.roster
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ranking and selection of the shared [RaftMemberPicker] (web `usePeopleSuggestionSearch`). */
class RaftMemberPickerTest {
    private fun ids(query: String, members: List<MemberProfile> = roster): Pair<List<String>, List<String>> =
        rankRaftMemberPickerCandidates(query, members).let { sections ->
            sections.agents.map { it.id.value } to sections.humans.map { it.id.value }
        }

    @Test
    fun blankQueryKeepsEveryCandidateGroupedAgentsFirst() {
        val crowd = (1..45).map { human("user-$it", "Person $it", "person$it") } + agent("agent-late", "Late", "late")
        listOf("", "  ", "@").forEach { query ->
            val (agents, humans) = ids(query, crowd)
            assertEquals(listOf("agent-late"), agents, "query '$query'")
            // The web keeps the whole roster on a blank query; only a search is capped at 30.
            assertEquals(45, humans.size, "query '$query'")
            assertEquals("user-1", humans.first())
        }
    }

    @Test
    fun onlyAgentsAndHumansAreCandidates() {
        val system = MemberProfile(id = MemberId("sys"), displayName = "System", handle = "system", kind = MemberKind.System)
        val (agents, humans) = ids("", roster + system)
        assertEquals(6, agents.size + humans.size)
        assertTrue("sys" !in agents + humans)
    }

    @Test
    fun rankingOrdersExactThenPrefixThenWordPrefixThenSubstring() {
        val members =
            listOf(
                human("substring", "Ox", "xmab"),
                human("word-prefix", "Team Mab", "team-mab-lead"),
                human("prefix", "Mabelle", "mabelle"),
                human("exact", "Mab", "mab"),
            )
        assertEquals(listOf("exact", "prefix", "word-prefix", "substring"), ids("mab", members).second)
    }

    @Test
    fun handleOutranksDisplayNameAndDescriptionAndServerOutrankNothing() {
        val members =
            listOf(
                human("server", "Quinn", "quinn", serverName = "Nova Labs"),
                agent("description", "Helper", "helper", description = "nova release notes"),
                human("name", "Nova Jones", "jones"),
                human("handle", "Someone", "nova"),
            )
        val (agents, humans) = ids("nova", members)
        assertEquals(listOf("handle", "name", "server"), humans)
        assertEquals(listOf("description"), agents)
        assertEquals(emptyList<String>() to emptyList(), ids("zzz", members))
    }

    @Test
    fun searchIsCaseAndPunctuationInsensitiveAndMatchesPinyin() {
        val members = listOf(human("bo", "Bo Rivera", "bo-r"), human("zh", "张伟", "zhangwei-cn"), agent("ada", "Ada Bot", "ada"))
        assertEquals(listOf("bo"), ids("BO-R", members).second)
        assertEquals(listOf("zh"), ids("zw", members).second)
        assertEquals(listOf("zh"), ids("张", members).second)
        assertEquals(listOf("ada") to emptyList<String>(), ids("bot", members))
    }

    @Test
    fun aSearchKeepsTheThirtyBestAcrossBothGroups() {
        val crowd = (1..40).map { human("user-$it", "Sam $it", "sam$it") } + agent("agent-sam", "Sam", "sam")
        val (agents, humans) = ids("sam", crowd)
        assertEquals(listOf("agent-sam"), agents)
        assertEquals(29, humans.size)
    }

    @Test
    fun selectionTogglesInMultipleModeAndReplacesInSingleMode() {
        val cindy = RaftMemberPickerKey(MemberKind.Agent, "agent-cindy")
        val artin = RaftMemberPickerKey(MemberKind.Human, "user-artin")
        // An agent and a human may share an id; the kind keeps them apart.
        val sameIdHuman = RaftMemberPickerKey(MemberKind.Human, "agent-cindy")

        val multiple = RaftMemberPickerMode.Multiple
        assertEquals(setOf(cindy), raftMemberPickerToggle(emptySet(), cindy, multiple))
        assertEquals(setOf(cindy, artin), raftMemberPickerToggle(setOf(cindy), artin, multiple))
        assertEquals(setOf(artin), raftMemberPickerToggle(setOf(cindy, artin), cindy, multiple))
        assertEquals(setOf(cindy, sameIdHuman), raftMemberPickerToggle(setOf(cindy), sameIdHuman, multiple))

        val single = RaftMemberPickerMode.Single
        assertEquals(setOf(artin), raftMemberPickerToggle(setOf(cindy), artin, single))
        assertEquals(setOf(artin), raftMemberPickerToggle(setOf(artin), artin, single))
        assertEquals(setOf(cindy), raftMemberPickerToggle(emptySet(), cindy, single))
    }

    @Test
    fun memberKeysCarryKindAndId() {
        assertEquals(RaftMemberPickerKey(MemberKind.Agent, "agent-cindy"), roster.first().memberPickerKey)
        assertEquals(RaftMemberPickerKey(MemberKind.Human, "user-artin"), roster[3].memberPickerKey)
    }
}
