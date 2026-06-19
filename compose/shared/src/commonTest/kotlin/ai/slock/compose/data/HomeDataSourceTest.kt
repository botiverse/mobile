package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Presence
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerSummary
import ai.slock.compose.model.ThreadId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeDataSourceTest {
    @Test
    fun loadHomeFetchesApiAndWritesLocalSnapshot() = runBlocking {
        val localStore = InMemoryComposeLocalDataStore()
        val writeThrough = ComposeLocalWriteThrough(localStore)
        val source =
            SharedHomeDataSource(
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                api = FakeHomeApi(),
                localWriteThrough = writeThrough,
                parseHome = { payload, session ->
                    assertEquals(emptyList(), payload.channels)
                    assertEquals(emptyList(), payload.directMessages)
                    assertEquals(emptyMap(), payload.unreadCounts)
                    assertEquals(emptyList(), payload.serverUnreadSummary)
                    DataResult.Content(sampleHome(ServerId(session.currentServerId!!)))
                }
            )

        val result = source.loadHome()

        assertEquals(1, (result as DataResult.Content).data.channels.size)
        assertEquals(1, localStore.readHome(ServerId("server-1"))?.channels?.size)
    }

    @Test
    fun backgroundLoadCanUseCachedHomeWithoutNetwork() = runBlocking {
        val serverId = ServerId("server-1")
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeHome(sampleHome(serverId))
        val api = FakeHomeApi()
        val source =
            SharedHomeDataSource(
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                api = api,
                localWriteThrough = ComposeLocalWriteThrough(localStore),
                parseHome = { _, _ -> error("cached background read should not parse network") }
            )

        val result = source.loadHome(priority = DataRequestPriority.Background)

        assertEquals(0, api.calls)
        assertEquals("general", (result as DataResult.Content).data.channels.single().name)
    }

    @Test
    fun cachedHomeReadUsesLocalSnapshotWithoutNetwork() = runBlocking {
        val serverId = ServerId("server-1")
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeHome(sampleHome(serverId))
        val api = FakeHomeApi()
        val source =
            SharedHomeDataSource(
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                api = api,
                localWriteThrough = ComposeLocalWriteThrough(localStore),
                parseHome = { _, _ -> error("cached read should not parse network") }
            )

        val result = source.readCachedHome(serverId)

        assertEquals(0, api.calls)
        assertEquals("general", (result as DataResult.Content).data.channels.single().name)
    }

    @Test
    fun visibleLoadMapsThrownNetworkFailureToError() = runBlocking {
        val logs = mutableListOf<String>()
        val source =
            SharedHomeDataSource(
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                api = ThrowingHomeApi(),
                parseHome = { _, _ -> error("network failure should not parse") },
                diagnosticsLogger = logs::add
            )

        val result = source.loadHome(priority = DataRequestPriority.Visible)

        assertTrue(result is DataResult.Error)
        assertEquals("home_network", result.error.code)
        assertContains(logs, "home_endpoint category=channels result=exception errorType=IllegalStateException")
        assertContains(logs, "home_bootstrap_exception errorType=IllegalStateException mapped=home_network")
    }

    @Test
    fun loadHomeLogsEndpointCategoryAndContractMismatch() = runBlocking {
        val logs = mutableListOf<String>()
        val source =
            SharedHomeDataSource(
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                api = FakeHomeApi(channelsBody = """{"channels":[]}"""),
                parseHome = { _, _ -> error("typed home api should reject wrapper before parser") },
                diagnosticsLogger = logs::add
            )

        val result = source.loadHome(priority = DataRequestPriority.Visible)

        assertTrue(result is DataResult.Error)
        assertEquals("home_channels_contract_mismatch", result.error.code)
        assertContains(
            logs,
            "home_endpoint category=channels code=200 result=error code=home_channels_contract_mismatch"
        )
        assertTrue(logs.any { it.contains("home_parse_start source=home_channels") && it.contains("dispatcher=Default") })
        assertTrue(
            logs.any {
                it.contains("home_parse_result source=home_channels result=error code=home_channels_contract_mismatch") &&
                    it.contains("dispatcher=Default")
            }
        )
        assertContains(
            logs,
            "home_bootstrap_result result=error code=home_channels_contract_mismatch stage=payload"
        )
    }

    @Test
    fun loadHomeRejectsGuessedHomeWrappersBeforeParser() = runBlocking {
        val source =
            SharedHomeDataSource(
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                api = FakeHomeApi(channelsBody = """{"channels":[]}"""),
                parseHome = { _, _ -> error("typed home api should reject wrapper before parser") }
            )

        val result = source.loadHome(priority = DataRequestPriority.Visible)

        assertTrue(result is DataResult.Error)
        assertEquals("home_channels_contract_mismatch", result.error.code)
    }

    @Test
    fun loadHomeRejectsGuessedDirectMessageWrappersBeforeParser() = runBlocking {
        val source =
            SharedHomeDataSource(
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                api = FakeHomeApi(directMessagesBody = """{"directMessages":[]}"""),
                parseHome = { _, _ -> error("typed home api should reject wrapper before parser") }
            )

        val result = source.loadHome(priority = DataRequestPriority.Visible)

        assertTrue(result is DataResult.Error)
        assertEquals("home_direct_messages_contract_mismatch", result.error.code)
    }

    @Test
    fun loadHomeRejectsGuessedUnreadCountWrappersBeforeParser() = runBlocking {
        val source =
            SharedHomeDataSource(
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                api = FakeHomeApi(unreadCountsBody = """[{"channelId":"channel-1","unreadCount":1}]"""),
                parseHome = { _, _ -> error("typed home api should reject unread array before parser") }
            )

        val result = source.loadHome(priority = DataRequestPriority.Visible)

        assertTrue(result is DataResult.Error)
        assertEquals("home_unread_counts_contract_mismatch", result.error.code)
    }

    @Test
    fun loadHomeRejectsGuessedServerUnreadSummaryWrapperBeforeParser() = runBlocking {
        val source =
            SharedHomeDataSource(
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                api = FakeHomeApi(serverUnreadSummaryBody = """{"unreadCount":24}"""),
                parseHome = { _, _ -> error("typed home api should reject summary object before parser") }
            )

        val result = source.loadHome(priority = DataRequestPriority.Visible)

        assertTrue(result is DataResult.Error)
        assertEquals("home_server_unread_summary_contract_mismatch", result.error.code)
    }

    @Test
    fun loadHomeUsesRequestedServerForEveryApiCallAndSessionSelection() = runBlocking {
        val sessionStore =
            InMemorySlockAuthSessionStore(
                sampleSession().copy(
                    selectedServerId = "server-1",
                    servers =
                        listOf(
                            StoredAuthServer(id = "server-1", name = "Server One"),
                            StoredAuthServer(id = "server-2", name = "Server Two", slug = "two")
                        )
                )
            )
        val api = RecordingHomeApi()
        val source =
            SharedHomeDataSource(
                sessionStore = sessionStore,
                api = api,
                parseHome = { payload, _ ->
                    DataResult.Content(sampleHome(payload.serverId))
                }
            )

        val result = source.loadHome(ServerId("server-2"))

        assertEquals(ServerId("server-2"), (result as DataResult.Content).data.server.id)
        assertEquals(listOf("channels:server-2", "dms:server-2", "unread:server-2", "summary:server-2"), api.calls)
    }

    @Test
    fun serverSwitchStateAcceptsOnlyLatestRequestedServer() {
        val initial = HomeServerSwitchState().select("server-a")
        val firstGeneration = initial.requestGeneration
        val latest = initial.select("server-b")
        val latestGeneration = latest.requestGeneration

        assertTrue(HomeServerSwitchState().isCurrent(0, ServerId("server-a")))
        assertTrue(!latest.isCurrent(firstGeneration, ServerId("server-a")))
        assertTrue(!latest.isCurrent(firstGeneration, ServerId("server-b")))
        assertTrue(latest.isCurrent(latestGeneration, ServerId("server-b")))
        assertEquals("server-b", latest.complete(latestGeneration, ServerId("server-b")).selectedServerId)
        assertEquals(null, latest.complete(latestGeneration, ServerId("server-b")).pendingServerId)
    }

    private class FakeHomeApi(
        private val channelsBody: String = "[]",
        private val directMessagesBody: String = "[]",
        private val unreadCountsBody: String = "{}",
        private val serverUnreadSummaryBody: String = "[]"
    ) : SlockHomeApi {
        var calls: Int = 0

        override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse = ok(channelsBody).also { calls++ }
        override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse = ok(directMessagesBody).also { calls++ }
        override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse = ok(unreadCountsBody).also { calls++ }
        override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse = ok(serverUnreadSummaryBody).also { calls++ }

        private fun ok(body: String): HomeNetworkResponse = HomeNetworkResponse(code = 200, bodyText = body)
    }

    private class ThrowingHomeApi : SlockHomeApi {
        override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse =
            throw IllegalStateException("offline")

        override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse =
            error("not reached")

        override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse =
            error("not reached")

        override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse =
            error("not reached")
    }

    private class RecordingHomeApi : SlockHomeApi {
        val calls = mutableListOf<String>()

        override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse =
            error("server-specific overload should be used")

        override suspend fun channels(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
            ok("channels", serverId)

        override suspend fun directMessages(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
            ok("dms", serverId)

        override suspend fun channelUnreadCounts(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
            ok("unread", serverId)

        override suspend fun serverUnreadSummary(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse =
            ok("summary", serverId)

        private fun ok(name: String, serverId: ServerId): HomeNetworkResponse {
            calls += "$name:${serverId.value}"
            val body =
                when (name) {
                    "channels", "dms", "summary" -> "[]"
                    else -> "{}"
                }
            return HomeNetworkResponse(code = 200, bodyText = body)
        }
    }

    private fun sampleSession(): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "artin@slock.ai", displayName = "Artin"),
            accessToken = "token-a",
            refreshToken = "token-r",
            selectedServerId = "server-1",
            servers = listOf(StoredAuthServer(id = "server-1", name = "Slock Community"))
        )

    private fun sampleHome(serverId: ServerId): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(serverId, "Slock Community"),
            currentMember =
                MemberProfile(
                    id = MemberId("user-1"),
                    displayName = "Artin",
                    handle = "artin@slock.ai",
                    kind = MemberKind.Human,
                    presence = Presence.Active
                ),
            channels =
                listOf(
                    ConversationSummary(
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        kind = ConversationKind.Channel,
                        name = "general"
                    )
                ),
            directMessages = emptyList(),
            unreadTotal = 0
        )
}
