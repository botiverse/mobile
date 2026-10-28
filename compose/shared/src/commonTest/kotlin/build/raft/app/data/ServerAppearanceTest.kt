package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.RaftServerThemeLook
import build.raft.app.model.ServerAppearanceUpdated
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.themeLook
import build.raft.designsystem.tokens.RaftThemeId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Per-member, per-server theme data layer, pinned to botiverse/slock@aed2ba714cea38507191289c3c17669b46b25b6f:
 * routes/servers.ts:785-800 (GET /api/servers), :1971-2017 (GET/PATCH appearance-settings,
 * `appearance:updated`), services/serverService.ts:291-309 and :1724-1763, routes/servers.api.test.ts:276-310.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerAppearanceTest {
    private val serverA = "11111111-1111-4111-8111-111111111111"
    private val serverB = "22222222-2222-4222-8222-222222222222"

    /** Same settings as the auth data source's server-list decoder (AuthDataSource.kt `authJson`). */
    private val serverListJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    private data class RecordedRequest(
        val method: String,
        val path: String,
        val priority: DataRequestPriority,
        val serverId: ServerId?,
        val body: JsonObject?,
    )

    private class RecordingNetworkClient(
        private val responses: Map<String, HomeNetworkResponse>,
    ) : RaftNetworkClient {
        val requests = mutableListOf<RecordedRequest>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse {
            requests += RecordedRequest(
                method = method,
                path = path,
                priority = priority,
                serverId = serverIdOverride,
                body = bodyText?.let { Json.parseToJsonElement(it).jsonObject },
            )
            return responses["$method $path"] ?: HomeNetworkResponse(404, """{"error":"Server not found"}""")
        }
    }

    private class FailingNetworkClient : RaftNetworkClient {
        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse = throw IllegalStateException("offline")
    }

    private val appearancePath = "servers/$serverA/appearance-settings"

    private fun session(
        userId: String = "user-1",
        selectedServerId: String? = serverA,
        serverATheme: String? = null,
        serverAPlan: String? = "pro",
        serverBTheme: String? = null,
    ): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = userId, email = "$userId@raft.test"),
            accessToken = "token-a",
            refreshToken = "token-r",
            selectedServerId = selectedServerId,
            servers = listOf(
                StoredAuthServer(id = serverA, name = "Acme", slug = "acme", plan = serverAPlan, theme = serverATheme),
                StoredAuthServer(id = serverB, name = "Side", slug = "side", plan = "free", theme = serverBTheme),
            ),
        )

    private suspend fun RaftAuthSessionStore.storedServer(id: String): StoredAuthServer =
        readSession()!!.servers.first { it.id == id }

    // --- Server list JSON -------------------------------------------------------------------

    @Test
    fun serverListRowsCarryThemeAndDecorations() {
        // GET /api/servers row: getUserServers' columns plus serverOrderVersion and publicPageLive.
        val body =
            """
            [
              {"id":"$serverA","name":"Acme","avatarUrl":null,"slug":"acme","ownerId":"u-1","onboardingAgentId":null,
               "hideHumansFromMembers":false,"plan":"pro","planDowngradedAt":null,"role":"owner","serverPushMuted":false,
               "theme":"boring","themeDecor":false,"createdAt":"2026-10-01T00:00:00.000Z","serverOrderVersion":3,
               "publicPageLive":false},
              {"id":"$serverB","name":"Side","slug":"side","plan":"free","role":"member","theme":null,"themeDecor":true}
            ]
            """.trimIndent()

        val servers = serverListJson.decodeFromString(ListSerializer(StoredAuthServer.serializer()), body)

        assertEquals("boring", servers[0].theme)
        assertEquals(RaftThemeId.Boring, servers[0].raftTheme)
        assertFalse(servers[0].themeDecor)
        val summary = servers[0].toServerSummary()
        assertEquals(RaftThemeId.Boring, summary.theme)
        assertFalse(summary.themeDecor)
        assertEquals(RaftServerThemeLook(RaftThemeId.Boring, decor = false), summary.themeLook())

        assertNull(servers[1].theme)
        assertNull(servers[1].raftTheme)
        assertTrue(servers[1].themeDecor)
        assertEquals(RaftServerThemeLook.Default, servers[1].themeLook())
    }

    @Test
    fun serverRowsWithoutThemeFieldsAreRaftYellowWithDecorations() {
        // An older server, or a create answer: no theme fields at all.
        val body = """[{"id":"$serverA","name":"Acme","slug":"acme","plan":"pro","role":"owner"}]"""

        val server = serverListJson.decodeFromString(ListSerializer(StoredAuthServer.serializer()), body).single()

        assertNull(server.theme)
        assertTrue(server.themeDecor)
        assertNull(server.toServerSummary().theme)
        assertTrue(server.toServerSummary().themeDecor)
        assertEquals(RaftServerThemeLook.Default, server.themeLook())
    }

    @Test
    fun unknownThemeIdFallsBackToRaftYellow() {
        val body = """[{"id":"$serverA","name":"Acme","plan":"pro","theme":"neon","themeDecor":true}]"""

        val server = serverListJson.decodeFromString(ListSerializer(StoredAuthServer.serializer()), body).single()

        // The raw id survives on disk (a later build may know it); this build reads it as Raft Yellow.
        assertEquals("neon", server.theme)
        assertNull(server.raftTheme)
        assertNull(server.toServerSummary().theme)
        assertEquals(RaftServerThemeLook.Default, server.themeLook())
        assertNull(parseSharedServerAppearance("""{"theme":"neon","themeDecor":true}""")!!.theme)
        assertNull(parseSharedServerAppearance("""{"theme":"raft","themeDecor":true}""")!!.theme)
    }

    @Test
    fun proThemeOnAFreeServerShowsAsRaftYellow() {
        val boring = StoredAuthServer(id = serverA, name = "Acme", theme = "boring", themeDecor = false)

        assertEquals(RaftServerThemeLook(RaftThemeId.Raft, decor = false), boring.copy(plan = "free").themeLook())
        assertEquals(RaftServerThemeLook(RaftThemeId.Raft, decor = false), boring.copy(plan = null).themeLook())
        assertEquals(RaftServerThemeLook(RaftThemeId.Boring, decor = false), boring.copy(plan = "pro").themeLook())
        // A non-Pro theme shows on Free too; the stored choice is never rewritten.
        val space = boring.copy(plan = "free", theme = "space")
        assertEquals(RaftThemeId.Space, space.themeLook().theme)
        assertEquals(RaftThemeId.Boring, boring.copy(plan = "free").toServerSummary().theme)
    }

    @Test
    fun serverSummaryJsonKeepsTheThemeAsItsWireId() {
        val summary = ServerSummary(id = ServerId(serverA), name = "Acme", theme = RaftThemeId.Space, themeDecor = false)

        val encoded = Json.encodeToString(ServerSummary.serializer(), summary)

        assertTrue("\"theme\":\"space\"" in encoded, encoded)
        assertEquals(summary, Json.decodeFromString(ServerSummary.serializer(), encoded))
        val legacy = Json.decodeFromString(ServerSummary.serializer(), """{"id":{"value":"$serverA"},"name":"Acme"}""")
        assertNull(legacy.theme)
        assertTrue(legacy.themeDecor)
    }

    // --- Cold start ------------------------------------------------------------------------

    @Test
    fun coldStartReadsThePersistedTheme() = runTest {
        val keyValue = InMemoryRaftKeyValueStore()
        KeyValueAuthSessionStore(keyValue).replaceAuthenticatedSession(session(serverATheme = "space"))
        KeyValueAuthSessionStore(keyValue).applyStoredServerAppearance("user-1", serverA, "space", themeDecor = false)

        // A new process: a fresh store over the same record, no network.
        val restartedKeyValue = InMemoryRaftKeyValueStore(
            mapOf(RAFT_AUTH_SESSION_RECORD_KEY to keyValue.getString(RAFT_AUTH_SESSION_RECORD_KEY)!!)
        )
        val restarted = KeyValueAuthSessionStore(restartedKeyValue).readSession()!!

        val server = restarted.servers.first { it.id == serverA }
        assertEquals(RaftThemeId.Space, server.raftTheme)
        assertFalse(server.themeDecor)
        assertEquals(
            RaftServerThemeLook(RaftThemeId.Space, decor = false),
            restarted.toSessionSnapshot()!!.availableServers.first { it.id.value == serverA }.themeLook(),
        )
    }

    @Test
    fun coldStartOfARecordWrittenBeforeThemesIsRaftYellow() = runTest {
        // A session record persisted by a build without the theme fields.
        val legacyRecord =
            """
            {"user":{"id":"user-1","email":"user-1@raft.test"},"accessToken":"token-a","refreshToken":"token-r",
             "selectedServerId":"$serverA","servers":[{"id":"$serverA","name":"Acme","slug":"acme","plan":"pro"}]}
            """.trimIndent()
        val store = KeyValueAuthSessionStore(InMemoryRaftKeyValueStore(mapOf(RAFT_AUTH_SESSION_RECORD_KEY to legacyRecord)))

        val server = store.readSession()!!.servers.single()

        assertNull(server.theme)
        assertTrue(server.themeDecor)
        assertEquals(RaftServerThemeLook.Default, server.themeLook())
    }

    // --- PATCH / GET appearance-settings ----------------------------------------------------

    @Test
    fun patchSendsRaftForTheDefaultNeverNull() = runTest {
        assertEquals("""{"theme":"raft","themeDecor":true}""", encodeServerAppearancePatchBody(null, themeDecor = true))
        assertEquals("""{"theme":"bots","themeDecor":false}""", encodeServerAppearancePatchBody(RaftThemeId.Bots, themeDecor = false))

        val store = InMemoryRaftAuthSessionStore(session(serverATheme = "space"))
        val client = RecordingNetworkClient(
            mapOf("PATCH $appearancePath" to HomeNetworkResponse(200, """{"theme":null,"themeDecor":false}"""))
        )

        val result = NetworkServerAppearanceRepository(client, store).update(ServerId(serverA), theme = null, themeDecor = false)

        assertEquals(ServerAppearanceUpdateResult.Saved(ServerAppearance(theme = null, themeDecor = false)), result)
        val request = client.requests.single()
        assertEquals("PATCH", request.method)
        assertEquals(appearancePath, request.path)
        assertEquals(DataRequestPriority.Foreground, request.priority)
        assertEquals(ServerId(serverA), request.serverId)
        assertEquals("raft", request.body!!["theme"]!!.jsonPrimitive.content)
        assertFalse(request.body["themeDecor"]!!.jsonPrimitive.boolean)
        // The stored server follows the saved answer.
        assertNull(store.storedServer(serverA).theme)
        assertFalse(store.storedServer(serverA).themeDecor)
    }

    @Test
    fun updateWritesTheSavedThemeOntoTheStoredServerOnly() = runTest {
        val store = InMemoryRaftAuthSessionStore(session())
        val client = RecordingNetworkClient(
            mapOf("PATCH $appearancePath" to HomeNetworkResponse(200, """{"theme":"space","themeDecor":true}"""))
        )

        val result = NetworkServerAppearanceRepository(client, store).update(ServerId(serverA), RaftThemeId.Space, themeDecor = true)

        assertEquals(ServerAppearanceUpdateResult.Saved(ServerAppearance(RaftThemeId.Space, themeDecor = true)), result)
        assertEquals("space", store.storedServer(serverA).theme)
        assertNull(store.storedServer(serverB).theme)
    }

    @Test
    fun invalidThemeMapsToItsTypedResult() = runTest {
        val store = InMemoryRaftAuthSessionStore(session(serverATheme = "space"))
        val client = RecordingNetworkClient(
            mapOf("PATCH $appearancePath" to HomeNetworkResponse(400, null, errorBodyText = """{"error":"invalid_theme"}"""))
        )

        val result = NetworkServerAppearanceRepository(client, store).update(ServerId(serverA), RaftThemeId.Food, themeDecor = true)

        assertEquals(ServerAppearanceUpdateResult.InvalidTheme, result)
        assertEquals("space", store.storedServer(serverA).theme, "a refused save changes nothing")
    }

    @Test
    fun otherFailuresMapToTypedResults() = runTest {
        val store = InMemoryRaftAuthSessionStore(session())
        val id = ServerId(serverA)
        fun repository(response: HomeNetworkResponse) =
            NetworkServerAppearanceRepository(RecordingNetworkClient(mapOf("PATCH $appearancePath" to response)), store)

        assertEquals(
            ServerAppearanceUpdateResult.NotFound,
            repository(HomeNetworkResponse(404, """{"error":"Server not found"}""")).update(id, RaftThemeId.Space, true),
        )
        assertEquals(
            ServerAppearanceUpdateResult.Failed(400, "themeDecor must be a boolean"),
            repository(HomeNetworkResponse(400, """{"error":"themeDecor must be a boolean"}""")).update(id, RaftThemeId.Space, true),
        )
        assertEquals(
            ServerAppearanceUpdateResult.Failed(500, "Failed to update appearance settings"),
            repository(HomeNetworkResponse(500, """{"error":"Failed to update appearance settings"}""")).update(id, RaftThemeId.Space, true),
        )
        assertEquals(
            ServerAppearanceUpdateResult.Failed(200, null),
            repository(HomeNetworkResponse(200, "not json")).update(id, RaftThemeId.Space, true),
        )
        assertEquals(
            ServerAppearanceUpdateResult.Failed(0, null),
            NetworkServerAppearanceRepository(FailingNetworkClient(), store).update(id, RaftThemeId.Space, true),
        )
        assertNull(store.storedServer(serverA).theme)
    }

    @Test
    fun proThemeOnAFreeServerIsRefusedBeforeAnyRequest() = runTest {
        val freeStore = InMemoryRaftAuthSessionStore(session(serverAPlan = "free"))
        val client = RecordingNetworkClient(
            mapOf("PATCH $appearancePath" to HomeNetworkResponse(200, """{"theme":"boring","themeDecor":true}"""))
        )

        assertEquals(
            ServerAppearanceUpdateResult.ProRequired,
            NetworkServerAppearanceRepository(client, freeStore).update(ServerId(serverA), RaftThemeId.Boring, themeDecor = true),
        )
        assertTrue(client.requests.isEmpty())
        // A free theme on the same server goes through; so does Boring on a paid plan.
        val paidStore = InMemoryRaftAuthSessionStore(session(serverAPlan = "pro"))
        assertIs<ServerAppearanceUpdateResult.Saved>(
            NetworkServerAppearanceRepository(client, paidStore).update(ServerId(serverA), RaftThemeId.Boring, themeDecor = true)
        )
        assertEquals("boring", paidStore.storedServer(serverA).theme)
    }

    @Test
    fun loadReadsTheAppearanceAndRefreshesTheStoredServer() = runTest {
        val store = InMemoryRaftAuthSessionStore(session())
        val client = RecordingNetworkClient(
            mapOf("GET $appearancePath" to HomeNetworkResponse(200, """{"theme":"plants","themeDecor":false}"""))
        )

        val result = NetworkServerAppearanceRepository(client, store).load(ServerId(serverA))

        assertEquals(ServerAppearanceLoadResult.Loaded(ServerAppearance(RaftThemeId.Plants, themeDecor = false)), result)
        assertEquals(DataRequestPriority.Visible, client.requests.single().priority)
        assertEquals("plants", store.storedServer(serverA).theme)
        assertFalse(store.storedServer(serverA).themeDecor)
        assertEquals(
            ServerAppearanceLoadResult.NotFound,
            NetworkServerAppearanceRepository(RecordingNetworkClient(emptyMap()), store).load(ServerId(serverA)),
        )
    }

    // --- Realtime ---------------------------------------------------------------------------

    @Test
    fun mapperReadsAppearanceUpdated() {
        val mapper = RealtimeEventMapper()

        assertEquals("appearance:updated", RealtimeSocketEvents.AppearanceUpdated)
        assertTrue(RealtimeSocketEvents.AppearanceUpdated in RealtimeSocketEvents.ServerWireEvents)
        // routes/servers.api.test.ts:291-295: `{ serverId, theme, themeDecor }`.
        assertEquals(
            ServerAppearanceUpdated(ServerId(serverA), theme = "space", themeDecor = false),
            mapper.map(RealtimeSocketEvents.AppearanceUpdated, """{"serverId":"$serverA","theme":"space","themeDecor":false}"""),
        )
        assertEquals(
            ServerAppearanceUpdated(ServerId(serverA), theme = null, themeDecor = true),
            mapper.map(RealtimeSocketEvents.AppearanceUpdated, """{"serverId":"$serverA","theme":null,"themeDecor":true}"""),
        )
        assertNull(mapper.map(RealtimeSocketEvents.AppearanceUpdated, """{"theme":"space","themeDecor":true}"""))
        assertNull(mapper.map(RealtimeSocketEvents.AppearanceUpdated, """{"serverId":"","theme":"space"}"""))
    }

    @Test
    fun realtimeAppearanceUpdatesTheStoredServerOfTheGrantsAccount() = runTest {
        val store = InMemoryRaftAuthSessionStore(session())
        val owner = SharedRealtimeDomainEventOwner(runIfCurrentGrant = { _, block -> block(); true })
        owner.installServerAppearanceSink { principalId, event -> store.applyRealtimeServerAppearance(principalId, event) }
        fun grant(serverId: String, principalId: String) =
            RealtimeGrantRecord(
                slotKey = RealtimeSlotKey("process-realtime-host", serverId),
                principalId = principalId,
                environmentId = "env-test",
                authGeneration = 1L,
                connectionFamilyId = "family-1",
                connectionEpochId = "epoch-1",
            )

        owner.acceptProcessMutation(
            grant(serverA, "user-1"),
            ServerAppearanceUpdated(ServerId(serverA), theme = "space", themeDecor = false),
        )
        assertEquals("space", store.storedServer(serverA).theme)
        assertFalse(store.storedServer(serverA).themeDecor)
        assertNull(store.storedServer(serverB).theme)

        // Another account's grant never writes this account's servers.
        owner.acceptProcessMutation(
            grant(serverA, "user-2"),
            ServerAppearanceUpdated(ServerId(serverA), theme = "bots", themeDecor = true),
        )
        assertEquals("space", store.storedServer(serverA).theme)

        // The event goes to the member's user room, so it arrives on the socket of whichever
        // server is open: a change on another of their servers lands on that stored server.
        owner.acceptProcessMutation(
            grant(serverA, "user-1"),
            ServerAppearanceUpdated(ServerId(serverB), theme = "food", themeDecor = true),
        )
        assertEquals("food", store.storedServer(serverB).theme)
        assertEquals("space", store.storedServer(serverA).theme)

        // Back to Raft Yellow; a payload without themeDecor keeps the stored decorations.
        owner.acceptProcessMutation(
            grant(serverA, "user-1"),
            ServerAppearanceUpdated(ServerId(serverA), theme = null, themeDecor = null),
        )
        assertNull(store.storedServer(serverA).theme)
        assertFalse(store.storedServer(serverA).themeDecor)
    }

    // --- Observable look ---------------------------------------------------------------------

    @Test
    fun lookFollowsTheSelectedServerAndStoredChanges() = runTest {
        val sessions = MutableStateFlow<StoredAuthSession?>(session(serverATheme = "space", serverBTheme = "quest"))
        val selected = MutableStateFlow<String?>(null)
        val looks = mutableListOf<RaftServerThemeLook>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            raftServerThemeLookFlow(sessions, selected).collect { looks += it }
        }

        // No shell selection yet: the session's own current server.
        assertEquals(RaftServerThemeLook(RaftThemeId.Space, decor = true), looks.last())
        selected.value = serverB
        runCurrent()
        assertEquals(RaftServerThemeLook(RaftThemeId.Quest, decor = true), looks.last())
        sessions.value = session(serverATheme = "space", serverBTheme = "weather")
        runCurrent()
        assertEquals(RaftServerThemeLook(RaftThemeId.Weather, decor = true), looks.last())
        selected.value = serverA
        runCurrent()
        assertEquals(RaftServerThemeLook(RaftThemeId.Space, decor = true), looks.last())
        sessions.value = null
        runCurrent()
        assertEquals(RaftServerThemeLook.Default, looks.last())
        selected.value = "unknown-server"
        runCurrent()
        assertEquals(
            listOf(RaftThemeId.Space, RaftThemeId.Quest, RaftThemeId.Weather, RaftThemeId.Space, RaftThemeId.Raft),
            looks.map { it.theme },
            "repeats are not emitted",
        )
    }

    @Test
    fun storedSessionFlowReadsOnStartAndAfterEveryWake() = runTest {
        val store = InMemoryRaftAuthSessionStore(session(serverATheme = "desk"))
        val wakes = MutableSharedFlow<Unit>()
        val selected = MutableStateFlow<String?>(serverA)
        val looks = mutableListOf<RaftServerThemeLook>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            raftServerThemeLookFlow(raftStoredSessionFlow(wakes) { store.readSession() }, selected).collect { looks += it }
        }

        // The persisted value first, before anything is fetched.
        assertEquals(RaftServerThemeLook(RaftThemeId.Desk, decor = true), looks.single())
        store.applyStoredServerAppearance("user-1", serverA, "spooky", themeDecor = false)
        runCurrent()
        wakes.emit(Unit)
        runCurrent()
        assertEquals(RaftServerThemeLook(RaftThemeId.Spooky, decor = false), looks.last())
        selected.value = serverB
        runCurrent()
        assertEquals(RaftServerThemeLook.Default, looks.last())
    }
}
