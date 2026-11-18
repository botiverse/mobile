package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeChannelWireDto
import build.raft.app.data.HomeNetworkPayload
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.SharedHomeContentState
import build.raft.app.data.SharedHomeState
import build.raft.app.data.authSessionFixture
import build.raft.app.data.parseRaftHome
import build.raft.app.data.withHomeChannelArchived
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Archived channels are found by global search like on the web (`GET /channels?archived=include`,
 * `searchEntities`: ranked last, "Archived" badge), while the sidebar still leaves them out.
 */
class HomeSearchArchivedChannelsTest {
    @Test
    fun anArchivedChannelIsSearchableRankedLastWithItsBadgeAndNotInTheSidebar() {
        val snapshot =
            parsedHome(
                HomeChannelWireDto(id = "c-live", name = "infra-live", type = "channel"),
                HomeChannelWireDto(id = "c-old", name = "infra-old", type = "channel", archivedAt = "2026-11-01T00:00:00.000Z"),
                HomeChannelWireDto(id = "c-other", name = "general", type = "channel"),
            )

        // The sidebar's list keeps only live channels; the archived one is kept apart for search.
        assertEquals(listOf("c-live", "c-other"), snapshot.channels.map { it.channelId.value })
        assertEquals(listOf("c-old"), snapshot.archivedChannels.map { it.channelId.value })
        assertTrue(snapshot.archivedChannels.single().archived)

        // A live match comes before the archived one of comparable strength...
        val results = search("infra", snapshot)
        assertEquals(listOf("conversation:c-live", "conversation:c-old"), results.map { it.key })
        // ...and the archived row carries the flag the row renders as the "Archived" badge.
        assertFalse(results.first().archived)
        assertTrue(results.last().archived)
        assertEquals(SearchEntityType.Channel, results.last().type)
        // It opens the archived channel itself (its read-only page), like any channel row.
        assertEquals("c-old", (results.last().destinationOrNull() as HomeSearchEntityDestination.Conversation).identity.threadId.value)
    }

    @Test
    fun aChannelBothListsHoldAppearsOnceAsTheLiveRow() {
        // Unarchived since the last load: the live row wins, so search never shows it twice.
        val live = HomeChannelRow(threadId = ThreadId("c-1"), channelId = ChannelId("c-1"), name = "ops")
        val snapshot = baseSnapshot(channels = listOf(live), archivedChannels = listOf(live.copy(archived = true)))

        val results = search("ops", snapshot)

        assertEquals(listOf("conversation:c-1"), results.map { it.key })
        assertFalse(results.single().archived)
    }

    @Test
    fun archivingKeepsTheSearchListConsistentLocallyAndAfterTheRealtimeReload() {
        val ops = HomeChannelRow(threadId = ThreadId("c-ops"), channelId = ChannelId("c-ops"), name = "ops-alpha")
        val opsLive = HomeChannelRow(threadId = ThreadId("c-ops-live"), channelId = ChannelId("c-ops-live"), name = "ops-bravo")
        val before = baseSnapshot(channels = listOf(ops, opsLive))

        // Row menu Archive: the row leaves the sidebar at once and search still finds it, archived.
        val archivedLocally = before.withHomeChannelArchived("c-ops")
        assertEquals(listOf("c-ops-live"), archivedLocally.channels.map { it.channelId.value })
        val localResults = search("ops", archivedLocally)
        assertEquals(listOf("conversation:c-ops-live", "conversation:c-ops"), localResults.map { it.key })
        assertTrue(localResults.last().archived)

        // `channel:updated` with `archivedAt` reloads Home: the fetched list agrees with the local step.
        val reloaded =
            parsedHome(
                HomeChannelWireDto(id = "c-ops", name = "ops-alpha", type = "channel", archivedAt = "2026-11-18T00:00:00.000Z"),
                HomeChannelWireDto(id = "c-ops-live", name = "ops-bravo", type = "channel"),
            )
        assertEquals(archivedLocally.channels.map { it.channelId }, reloaded.channels.map { it.channelId })
        assertEquals(localResults.map { it.key to it.archived }, search("ops", reloaded).map { it.key to it.archived })

        // Unarchive (reload without `archivedAt`): back in the sidebar, and a live search result again.
        val unarchived =
            parsedHome(
                HomeChannelWireDto(id = "c-ops", name = "ops-alpha", type = "channel"),
                HomeChannelWireDto(id = "c-ops-live", name = "ops-bravo", type = "channel"),
            )
        assertTrue(unarchived.archivedChannels.isEmpty())
        assertEquals(listOf("c-ops", "c-ops-live"), unarchived.channels.map { it.channelId.value })
        val unarchivedResults = search("ops", unarchived)
        assertEquals(setOf("conversation:c-ops", "conversation:c-ops-live"), unarchivedResults.map { it.key }.toSet())
        assertTrue(unarchivedResults.none { it.archived })
    }

    @Test
    fun recentConversationsStillLeaveArchivedChannelsOut() {
        val archived = HomeChannelRow(threadId = ThreadId("c-old"), channelId = ChannelId("c-old"), name = "old", archived = true)
        val snapshot = baseSnapshot(channels = emptyList(), archivedChannels = listOf(archived))
        val candidates =
            homeSearchEntityCandidates(
                homeState = homeState(snapshot),
                senderFilters = emptyList(),
                channelLabel = "Channel",
                dmLabel = "DM",
                agentLabel = "Agent",
                personLabel = "Person",
                membersLabel = "Members",
            ).map { it.result }

        val recent =
            homeSearchOverlayRecentConversations(
                visitedThreadIds = listOf("c-old"),
                candidates = candidates,
                fill = emptyList(),
                excludeThreadId = null,
            )

        assertTrue(recent.isEmpty())
    }

    private fun parsedHome(vararg channels: HomeChannelWireDto): HomeSnapshot {
        val result =
            parseRaftHome(
                payload =
                    HomeNetworkPayload(
                        serverId = ServerId("server-1"),
                        channels = channels.toList(),
                        directMessages = emptyList(),
                        unreadCounts = emptyMap(),
                        serverUnreadSummary = emptyList(),
                    ),
                session = authSessionFixture(),
            )
        return (result as DataResult.Content).data
    }

    private fun baseSnapshot(channels: List<HomeChannelRow>, archivedChannels: List<HomeChannelRow> = emptyList()): HomeSnapshot =
        parsedHome(HomeChannelWireDto(id = "placeholder", name = "placeholder", type = "channel"))
            .copy(channels = channels, archivedChannels = archivedChannels)

    private fun homeState(snapshot: HomeSnapshot): SharedHomeState =
        SharedHomeState(content = SharedHomeContentState.Content(snapshot = snapshot, session = authSessionFixture()))

    private fun search(query: String, snapshot: HomeSnapshot): List<SearchEntityResult> =
        homeSearchEntityResults(
            query = query,
            homeState = homeState(snapshot),
            senderFilters = emptyList(),
            channelLabel = "Channel",
            dmLabel = "DM",
            agentLabel = "Agent",
            personLabel = "Person",
            membersLabel = "Members",
        )
}
