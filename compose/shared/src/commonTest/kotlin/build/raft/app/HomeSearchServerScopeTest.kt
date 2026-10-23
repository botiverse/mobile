package build.raft.app

import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.SearchFrequentEntityRank
import build.raft.app.data.SearchPersonalizationEntityKey
import build.raft.app.data.SearchPersonalizationEntityKind
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import build.raft.app.data.authSessionFixture
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The Search tab's local candidates (Home channels/DMs) belong to the server Search is scoped to. */
class HomeSearchServerScopeTest {
    private val botiverseHome = homeState(serverId = "server-a", channelId = "channel-a", channelName = "proj-deploy")

    @Test
    fun previousServerHomeSnapshotContributesNoSearchRowsAfterTheSwitch() {
        val scoped = botiverseHome.scopedToSearchServer("server-b")

        assertTrue(scoped.content is SharedHomeContentState.Loading)
        assertEquals(emptyList(), entityKeys(scoped, "deploy"))
        assertEquals(emptyList(), frequentKeys(scoped, "channel-a"))
    }

    @Test
    fun matchingServerHomeSnapshotStillServesSearchRows() {
        val scoped = botiverseHome.scopedToSearchServer("server-a")

        assertSame(botiverseHome, scoped)
        assertEquals(listOf("conversation:channel-a"), entityKeys(scoped, "deploy"))
        assertEquals(listOf("conversation:channel-a"), frequentKeys(scoped, "channel-a"))
    }

    @Test
    fun unknownSearchServerShowsNoCandidates() {
        assertEquals(emptyList(), entityKeys(botiverseHome.scopedToSearchServer(null), "deploy"))
    }

    private fun entityKeys(state: SharedHomeState, query: String): List<String> =
        homeSearchEntityResults(
            query = query,
            homeState = state,
            senderFilters = emptyList(),
            channelLabel = "Channel",
            dmLabel = "DM",
            agentLabel = "Agent",
            personLabel = "Person",
            membersLabel = "Members",
        ).map { it.key }

    private fun frequentKeys(state: SharedHomeState, channelId: String): List<String> =
        homeSearchFrequentEntityResults(
            homeState = state,
            senderFilters = emptyList(),
            ranks =
                mapOf(
                    SearchPersonalizationEntityKey(SearchPersonalizationEntityKind.Channel, channelId) to
                        SearchFrequentEntityRank(score = 1.0, lastOpenedAtMillis = 1L)
                ),
            channelLabel = "Channel",
            dmLabel = "DM",
            agentLabel = "Agent",
            personLabel = "Person",
            membersLabel = "Members",
        ).map { it.key }

    private fun homeState(serverId: String, channelId: String, channelName: String): SharedHomeState =
        SharedHomeState(
            content =
                SharedHomeContentState.Content(
                    snapshot =
                        HomeSnapshot(
                            server = ServerSummary(ServerId(serverId), "Botiverse"),
                            currentMember =
                                MemberProfile(
                                    id = MemberId("user-1"),
                                    displayName = "Current User",
                                    handle = "current",
                                    kind = MemberKind.Human
                                ),
                            channels =
                                listOf(
                                    HomeChannelRow(
                                        threadId = ThreadId(channelId),
                                        channelId = ChannelId(channelId),
                                        name = channelName
                                    )
                                ),
                            directMessages = emptyList(),
                            unreadTotal = 0
                        ),
                    session = authSessionFixture(selectedServerId = serverId)
                )
        )
}
