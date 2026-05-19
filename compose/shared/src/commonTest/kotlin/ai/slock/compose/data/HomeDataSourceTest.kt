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
                    assertTrue(payload.isSuccessful)
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
    fun visibleLoadMapsThrownNetworkFailureToError() = runBlocking {
        val source =
            SharedHomeDataSource(
                sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                api = ThrowingHomeApi(),
                parseHome = { _, _ -> error("network failure should not parse") }
            )

        val result = source.loadHome(priority = DataRequestPriority.Visible)

        assertTrue(result is DataResult.Error)
        assertEquals("home_network", (result as DataResult.Error).error.code)
    }

    private class FakeHomeApi : SlockHomeApi {
        var calls: Int = 0

        override suspend fun channels(priority: DataRequestPriority): HomeNetworkResponse = ok().also { calls++ }
        override suspend fun directMessages(priority: DataRequestPriority): HomeNetworkResponse = ok().also { calls++ }
        override suspend fun channelUnreadCounts(priority: DataRequestPriority): HomeNetworkResponse = ok().also { calls++ }
        override suspend fun serverUnreadSummary(priority: DataRequestPriority): HomeNetworkResponse = ok().also { calls++ }

        private fun ok(): HomeNetworkResponse = HomeNetworkResponse(code = 200, bodyText = "{}")
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
