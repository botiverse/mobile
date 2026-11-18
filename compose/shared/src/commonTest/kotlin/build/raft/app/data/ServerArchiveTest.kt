package build.raft.app.data

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings › Server's Archived channels and Archived agents. Shapes are the server's, botiverse/slock
 * staging 33f441958f82ce0178cc7f48232d41b20907e50c: GET /api/channels?archived=include rows
 * (routes/channels.ts), GET /api/agents/archived (routes/agents.ts `listArchivedAgents`,
 * web ArchivedAgentsSection `ArchivedAgentEntry`), POST /api/channels/:id/unarchive.
 */
class ServerArchiveTest {
    private val channels =
        """[
            {"id":"c-live","name":"general","type":"channel","archivedAt":null},
            {"id":"c-old","name":"old-plans","type":"private","archivedAt":"2026-03-01T08:00:00.000Z"},
            {"id":"c-new","name":"launch","type":"channel","archivedAt":"2026-10-01T08:00:00.000Z","hasExternalRelation":true},
            {"id":"c-same","name":"alpha","type":"joint","archivedAt":"2026-10-01T08:00:00.000Z"},
            {"id":"c-dm","name":"dm","type":"dm","archivedAt":"2026-10-02T08:00:00.000Z"},
            {"id":"c-lounge","name":"lounge","type":"channel","archivedAt":"2026-10-03T08:00:00.000Z","loungeAgentId":"agent-1"}
        ]"""
    private val agents =
        """{"agents":[
            {"agentId":"a-1","name":"scout","displayName":"Scout","avatarUrl":null,"creatorType":"user","creatorId":"u-1","createdAt":"2026-01-01T00:00:00.000Z","archivedAt":"2026-11-01T08:00:00.000Z","archivedByUserId":"u-1","workspaceState":"on_computer","pendingOp":null,"machineName":"Studio Mac"},
            {"agentId":"a-2","name":"helper","displayName":null,"avatarUrl":null,"creatorType":null,"creatorId":null,"createdAt":"2026-01-01T00:00:00.000Z","archivedAt":"2026-10-01T08:00:00.000Z","archivedByUserId":null,"workspaceState":"on_computer","pendingOp":"delete","machineName":"Studio Mac"},
            {"agentId":"a-3","name":"gone","displayName":"","avatarUrl":null,"creatorType":null,"creatorId":null,"createdAt":"2026-01-01T00:00:00.000Z","archivedAt":"2026-09-01T08:00:00.000Z","archivedByUserId":null,"workspaceState":"deleted","pendingOp":null,"machineName":null}
        ]}"""

    @Test
    fun keepsTheArchivedChannelsNewestFirst() {
        val parsed = parseSharedArchivedChannels(channels)!!
        assertEquals(listOf("c-same", "c-new", "c-old"), parsed.map { it.id }, "newest first, then by name; no DM, no Lounge, no live one")
        assertTrue(parsed[1].hasExternalRelation)
        assertNull(parseSharedArchivedChannels("""{"channels":[]}"""))
    }

    @Test
    fun readsWhereEachWorkspaceIs() {
        val parsed = parseSharedArchivedAgents(agents)!!
        assertEquals(listOf("Scout", "helper", "gone"), parsed.map { it.name })
        assertEquals(
            listOf(ArchivedAgentWorkspace.OnComputer, ArchivedAgentWorkspace.Deleting, ArchivedAgentWorkspace.Deleted),
            parsed.map { it.workspace }
        )
        assertEquals("Studio Mac", parsed[0].machineName)
    }

    @Test
    fun onlyOwnersAndAdminsSeeArchivedChannels() {
        assertTrue(serverRoleCanArchiveChannels("owner"))
        assertTrue(serverRoleCanArchiveChannels("admin"))
        assertFalse(serverRoleCanArchiveChannels("member"))
        assertFalse(serverRoleCanArchiveChannels("guest"))
        assertFalse(serverRoleCanArchiveChannels(null))
    }

    @Test
    fun aMemberReadsOnlyTheAgentsAndUnarchiveRemovesTheChannel() = runTest {
        val network =
            ScriptedAgentControlsNetworkClient().apply {
                respond("GET", "channels", 200, channels)
                respond("GET", "agents/archived", 200, agents)
                respond("POST", "channels/c-old/unarchive", 403, """{"error":"You can't unarchive this channel"}""")
                respond("POST", "channels/c-old/unarchive", 200, """{"id":"c-old","name":"old-plans","type":"private","archivedAt":null}""")
            }
        val store = SharedServerArchiveStore(NetworkServerArchiveDataSource(network), this)
        store.dispatch(SharedServerArchiveIntent.Load("server-1", canArchiveChannels = false))
        runCurrent()
        assertEquals(emptyList(), store.state.value.channels)
        assertEquals(3, store.state.value.agents.size)
        assertEquals(0, network.requests.count { it.path == "channels" })

        store.dispatch(SharedServerArchiveIntent.Load("server-1", canArchiveChannels = true))
        runCurrent()
        assertEquals(3, store.state.value.channels.size)
        assertEquals(listOf("archived" to "include"), CHANNELS_INCLUDING_ARCHIVED_QUERY)

        store.dispatch(SharedServerArchiveIntent.Unarchive("c-old"))
        runCurrent()
        assertEquals("You can't unarchive this channel", store.state.value.unarchiveError)
        assertEquals(3, store.state.value.channels.size)

        store.dispatch(SharedServerArchiveIntent.Unarchive("c-old"))
        runCurrent()
        assertNull(store.state.value.unarchiveError)
        assertNull(store.state.value.unarchivingChannelId)
        assertEquals(listOf("c-same", "c-new"), store.state.value.channels.map { it.id })
    }

    @Test
    fun anotherServerStartsEmptyAndAFailedReadStaysHidden() = runTest {
        val network = ScriptedAgentControlsNetworkClient().apply { respond("GET", "agents/archived", 403, """{"code":"archived_agents_guest_forbidden"}""") }
        val store = SharedServerArchiveStore(NetworkServerArchiveDataSource(network), this)
        store.dispatch(SharedServerArchiveIntent.Load("server-2", canArchiveChannels = false))
        runCurrent()
        assertEquals(emptyList(), store.state.value.agents)
        assertEquals("server-2", store.state.value.serverId)
    }
}
