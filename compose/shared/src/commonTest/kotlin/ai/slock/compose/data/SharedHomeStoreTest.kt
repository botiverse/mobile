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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedHomeStoreTest {
    @Test
    fun startLoadsHomeThroughCommonStateFlow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource = source(api = RecordingHomeApi()),
            scope = scope
        )

        store.accept(SharedHomeIntent.Start)

        val content = store.state.value.content
        assertTrue(content is SharedHomeContentState.Content)
        assertEquals("server-1", content.snapshot.server.id.value)
        assertEquals(false, content.contentRefreshing)
        scope.cancel()
    }

    @Test
    fun selectServerPersistsSelectionAndClearsPendingAfterLoad() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessionStore = InMemorySlockAuthSessionStore(sampleSession())
        val api = RecordingHomeApi()
        val store = SharedHomeStore(
            authStore = sessionStore,
            dataSource = source(api = api),
            scope = scope
        )

        store.accept(SharedHomeIntent.SelectServer("server-2"))

        val state = store.state.value
        val content = state.content
        assertTrue(content is SharedHomeContentState.Content)
        assertEquals("server-2", content.snapshot.server.id.value)
        assertEquals("server-2", sessionStore.readSession()?.selectedServerId)
        assertEquals(null, state.serverSwitchState.pendingServerId)
        assertEquals(false, state.serverSwitcherExpanded)
        assertEquals(listOf("channels:server-2", "dms:server-2", "unread:server-2", "summary:server-2"), api.calls)
        scope.cancel()
    }

    @Test
    fun unauthenticatedSessionBecomesErrorState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(),
            dataSource = source(api = RecordingHomeApi()),
            scope = scope
        )

        store.accept(SharedHomeIntent.Start)

        val content = store.state.value.content
        assertTrue(content is SharedHomeContentState.Error)
        assertEquals("unauthenticated", content.code)
        scope.cancel()
    }

    @Test
    fun startUsesCachedHomeAndKeepsContentOnVisibleNetworkError() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeHome(sampleHome(ServerId("server-1")))
        val store = SharedHomeStore(
            authStore = InMemorySlockAuthSessionStore(sampleSession()),
            dataSource =
                SharedHomeDataSource(
                    sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
                    api = ThrowingHomeApi(),
                    localWriteThrough = ComposeLocalWriteThrough(localStore),
                    parseHome = { _, _ -> error("network failure should not parse") }
                ),
            scope = scope
        )

        store.accept(SharedHomeIntent.Start)

        val content = store.state.value.content
        assertTrue(content is SharedHomeContentState.Content)
        assertEquals("general", content.snapshot.channels.single().name)
        assertEquals(false, content.contentRefreshing)
        scope.cancel()
    }

    private fun source(api: SlockHomeApi): SharedHomeDataSource =
        SharedHomeDataSource(
            sessionStore = InMemorySlockAuthSessionStore(sampleSession()),
            api = api,
            parseHome = { payload, _ -> DataResult.Content(sampleHome(payload.serverId)) }
        )

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
            return HomeNetworkResponse(code = 200, bodyText = "{}")
        }
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
            servers =
                listOf(
                    StoredAuthServer(id = "server-1", name = "Slock Community", slug = "slock"),
                    StoredAuthServer(id = "server-2", name = "Work", slug = "work")
                )
        )

    private fun sampleHome(serverId: ServerId): HomeSnapshot =
        HomeSnapshot(
            server = ServerSummary(serverId, if (serverId.value == "server-2") "Work" else "Slock Community"),
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
