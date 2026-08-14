package build.raft.app

import build.raft.app.data.AUTHENTICATED_ROUTE_AUTHORITY_CHANGED
import build.raft.app.data.AuthenticatedRouteAuthorityGateway
import build.raft.app.data.AuthenticatedRouteAuthorityReceipt
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.ChannelActivityMuteSettings
import build.raft.app.data.ChannelProfileUpdateResult
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.InMemorySlockAuthSessionStore
import build.raft.app.data.SettingsServerSidebarOrderSnapshot
import build.raft.app.data.SettingsServerSidebarPinnedItem
import build.raft.app.data.SharedServerChannelMembersSlice
import build.raft.app.data.SharedServerMembersLoadStatus
import build.raft.app.data.SlockExpectedAuthBinding
import build.raft.app.data.SlockNetworkClient
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.model.ChannelActivityNotificationPreference
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChannelSettingsStoreTest {
    @Test
    fun mismatchedOwnerRejectsLoadAndEveryMutationBeforeNetworkClient() = runBlocking {
        val gateway = RejectingChannelSettingsGateway()
        val dataSource =
            RealChannelSettingsDataSource(
                route = route(),
                authStore = InMemorySlockAuthSessionStore(),
                owner = routeOwner(),
                gateway = gateway,
            )

        listOf(
            dataSource.load(),
            dataSource.setMuted(true),
            dataSource.togglePinned(),
            dataSource.updateProfile("renamed", "description"),
            dataSource.makePrivate(),
            dataSource.archive(),
            dataSource.delete(),
        ).forEach { result ->
            assertEquals(
                AUTHENTICATED_ROUTE_AUTHORITY_CHANGED,
                assertIs<DataResult.Error>(result).error.code,
            )
        }
        assertEquals(7, gateway.captureCalls)
        assertEquals(0, gateway.networkClientCalls)
    }

    @Test
    fun coldLoadProducesStandaloneCanonicalContent() = runBlocking {
        val dataSource = FakeChannelSettingsDataSource(loadResult = DataResult.Content(snapshot()))
        val store = ChannelSettingsStore(route(), dataSource, this)

        store.dispatch(ChannelSettingsIntent.Load)
        yield()

        assertEquals(1, dataSource.loadCalls)
        assertEquals(ChannelSettingsLoadStatus.Content, store.state.value.status)
        assertEquals("#canonical", store.state.value.channel?.name)
        assertTrue(store.state.value.isPinned("channel-1"))
        assertEquals(SharedServerMembersLoadStatus.Loaded, store.state.value.memberSlice.status)
        store.clear()
    }

    @Test
    fun refreshCannotObsoleteOrClearMutationInFlight() = runBlocking {
        val pinResult = CompletableDeferred<DataResult<SettingsServerSidebarOrderSnapshot>>()
        val dataSource =
            FakeChannelSettingsDataSource(
                loadResult = DataResult.Content(snapshot()),
                pinResult = pinResult,
            )
        val store = ChannelSettingsStore(route(), dataSource, this)
        store.dispatch(ChannelSettingsIntent.Load)
        yield()

        store.dispatch(ChannelSettingsIntent.TogglePin)
        yield()
        store.dispatch(ChannelSettingsIntent.Refresh)

        assertEquals(1, dataSource.loadCalls)
        assertEquals(ChannelSettingsAction.Pin, store.state.value.actionInFlight)
        pinResult.complete(DataResult.Content(SettingsServerSidebarOrderSnapshot()))
        yield()
        assertFalse(store.state.value.isPinned("channel-1"))
        assertEquals(ChannelSettingsEffect.Updated(ChannelSettingsAction.Pin), store.state.value.effect)
        store.clear()
    }

    @Test
    fun authorityInvalidationDropsLateLoadResult() = runBlocking {
        val loadGate = CompletableDeferred<DataResult<ChannelSettingsSnapshot>>()
        val dataSource = FakeChannelSettingsDataSource(loadGate = loadGate)
        val store = ChannelSettingsStore(route(), dataSource, this)

        store.dispatch(ChannelSettingsIntent.Load)
        yield()
        store.invalidateAuthority()
        loadGate.complete(DataResult.Content(snapshot()))
        yield()

        assertEquals(ChannelSettingsLoadStatus.AuthorityRejected, store.state.value.status)
        assertEquals(AUTHENTICATED_ROUTE_AUTHORITY_CHANGED, store.state.value.errorCode)
        assertEquals(null, store.state.value.channel)
        store.clear()
    }

    @Test
    fun visibilitySuccessUpdatesRouteLocalCanonicalState() = runBlocking {
        val dataSource = FakeChannelSettingsDataSource(loadResult = DataResult.Content(snapshot()))
        val store = ChannelSettingsStore(route(), dataSource, this)
        store.dispatch(ChannelSettingsIntent.Load)
        yield()

        store.dispatch(ChannelSettingsIntent.MakePrivate)
        yield()

        assertEquals("private", store.state.value.channel?.channelType)
        assertEquals(ChannelSettingsEffect.Updated(ChannelSettingsAction.Visibility), store.state.value.effect)
        assertEquals(null, store.state.value.actionInFlight)
        store.clear()
    }

    @Test
    fun realDataSourceColdLoadDoesNotDependOnHomeAndPinRereadsCanonicalOrder() = runBlocking {
        val network = RecordingChannelSettingsNetworkClient()
        val gateway = FixedChannelSettingsGateway(network)
        val dataSource =
            RealChannelSettingsDataSource(
                route = route(),
                authStore = authStore(),
                owner = routeOwner(),
                gateway = gateway,
            )

        val loaded = assertIs<DataResult.Content<ChannelSettingsSnapshot>>(dataSource.load()).data
        assertEquals("canonical", loaded.channel.name)
        assertEquals(listOf("channels/channel-1", "servers/server-1/sidebar-order", "channels/channel-1/members"), network.paths)
        assertTrue(network.paths.none { it == "home" || it.endsWith("/home") })

        network.paths.clear()
        network.methods.clear()
        network.bodies.clear()
        val pinned = assertIs<DataResult.Content<SettingsServerSidebarOrderSnapshot>>(dataSource.togglePinned()).data
        assertTrue(pinned.pinned.any { it.kind == "channel" && it.id == "channel-1" })
        assertEquals(listOf("servers/server-1/sidebar-order", "servers/server-1/sidebar-order"), network.paths)
        assertEquals(listOf("GET", "PATCH"), network.methods)
        assertTrue(network.bodies.last().orEmpty().contains("channel-1"))
    }

    private fun route(): ChannelSettingsRoute =
        ChannelSettingsRoute(ServerId("server-1"), ChannelId("channel-1"))

    private fun routeOwner(): AuthenticatedRouteOwner =
        AuthenticatedRouteOwner("https://api.raft.build/api", "user-1", "server-1")

    private fun authStore(): InMemorySlockAuthSessionStore =
        InMemorySlockAuthSessionStore(
            StoredAuthSession(
                user = StoredAuthUser(id = "user-1", email = "user@raft.build"),
                accessToken = "access",
                refreshToken = "refresh",
                selectedServerId = "server-1",
                servers = listOf(StoredAuthServer(id = "server-1", name = "Raft", role = "owner")),
            )
        )

    private fun snapshot(): ChannelSettingsSnapshot =
        ChannelSettingsSnapshot(
            channel =
                HomeChannelRow(
                    threadId = ThreadId("channel-1"),
                    channelId = ChannelId("channel-1"),
                    name = "#canonical",
                ),
            sidebarOrder =
                SettingsServerSidebarOrderSnapshot(
                    pinned = listOf(SettingsServerSidebarPinnedItem("channel", "channel-1"))
                ),
            canManage = true,
            memberSlice =
                SharedServerChannelMembersSlice(
                    serverId = "server-1",
                    channelId = "channel-1",
                    status = SharedServerMembersLoadStatus.Loaded,
                ),
        )
}

private class FakeChannelSettingsDataSource(
    private val loadResult: DataResult<ChannelSettingsSnapshot>? = null,
    private val loadGate: CompletableDeferred<DataResult<ChannelSettingsSnapshot>>? = null,
    private val pinResult: CompletableDeferred<DataResult<SettingsServerSidebarOrderSnapshot>>? = null,
) : ChannelSettingsDataSource {
    var loadCalls = 0
    var pinCalls = 0

    override suspend fun load(): DataResult<ChannelSettingsSnapshot> {
        loadCalls += 1
        return loadGate?.await() ?: requireNotNull(loadResult)
    }

    override suspend fun setMuted(muted: Boolean): DataResult<ChannelActivityMuteSettings> =
        DataResult.Content(
            ChannelActivityMuteSettings(
                channelId = ChannelId("channel-1"),
                activityNotificationPreference =
                    ChannelActivityNotificationPreference(activityMuteSupported = true),
            )
        )

    override suspend fun togglePinned(): DataResult<SettingsServerSidebarOrderSnapshot> {
        pinCalls += 1
        return pinResult?.await() ?: DataResult.Content(SettingsServerSidebarOrderSnapshot())
    }

    override suspend fun updateProfile(name: String, description: String): DataResult<ChannelProfileUpdateResult> =
        DataResult.Content(ChannelProfileUpdateResult(ChannelId("channel-1"), name, description))

    override suspend fun makePrivate(): DataResult<Unit> = DataResult.Content(Unit)
    override suspend fun archive(): DataResult<Unit> = DataResult.Content(Unit)
    override suspend fun delete(): DataResult<Unit> = DataResult.Content(Unit)
}

private class FixedChannelSettingsGateway(
    private val client: SlockNetworkClient,
) : AuthenticatedRouteAuthorityGateway {
    private val receipt =
        AuthenticatedRouteAuthorityReceipt(
            owner = AuthenticatedRouteOwner("https://api.raft.build/api", "user-1", "server-1"),
            authGeneration = 1L,
            selectionGeneration = 1L,
            canManageSettings = true,
            expectedAuthBinding =
                SlockExpectedAuthBinding(
                    accountId = "user-1",
                    accessToken = "access",
                    refreshToken = "refresh",
                    serverId = "server-1",
                    environmentId = "https://api.raft.build/api",
                ),
        )

    override suspend fun capture(owner: AuthenticatedRouteOwner): AuthenticatedRouteAuthorityReceipt? =
        receipt.takeIf { it.owner == owner }

    override suspend fun isCurrent(receipt: AuthenticatedRouteAuthorityReceipt): Boolean =
        receipt == this.receipt

    override fun networkClient(receipt: AuthenticatedRouteAuthorityReceipt): SlockNetworkClient = client
}

private class RejectingChannelSettingsGateway : AuthenticatedRouteAuthorityGateway {
    var captureCalls = 0
    var networkClientCalls = 0

    override suspend fun capture(owner: AuthenticatedRouteOwner): AuthenticatedRouteAuthorityReceipt? {
        captureCalls += 1
        return null
    }

    override suspend fun isCurrent(receipt: AuthenticatedRouteAuthorityReceipt): Boolean = false

    override fun networkClient(receipt: AuthenticatedRouteAuthorityReceipt): SlockNetworkClient {
        networkClientCalls += 1
        return object : SlockNetworkClient {
            override suspend fun request(
                priority: DataRequestPriority,
                path: String,
                method: String,
                query: List<Pair<String, String>>,
                serverIdOverride: ServerId?,
                bodyText: String?,
            ): HomeNetworkResponse = error("network must not run")
        }
    }
}

private class RecordingChannelSettingsNetworkClient : SlockNetworkClient {
    val paths = mutableListOf<String>()
    val methods = mutableListOf<String>()
    val bodies = mutableListOf<String?>()

    override suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?,
    ): HomeNetworkResponse {
        paths += path
        methods += method
        bodies += bodyText
        val response =
            when {
                path == "channels/channel-1" ->
                    """{"id":"channel-1","name":"canonical","type":"channel","memberCount":1}"""
                path == "channels/channel-1/members" ->
                    """{"humans":[{"id":"user-1","name":"artin"}],"agents":[]}"""
                path == "servers/server-1/sidebar-order" && method == "PATCH" ->
                    """{"pinned":[{"kind":"channel","id":"channel-1"}]}"""
                path == "servers/server-1/sidebar-order" -> """{"pinned":[]}"""
                else -> return HomeNetworkResponse(code = 404, bodyText = null)
            }
        return HomeNetworkResponse(code = 200, bodyText = response)
    }
}
