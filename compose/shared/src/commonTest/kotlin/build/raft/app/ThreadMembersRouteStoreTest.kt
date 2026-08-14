package build.raft.app

import build.raft.app.data.AUTHENTICATED_ROUTE_AUTHORITY_CHANGED
import build.raft.app.data.AddChannelMemberRequest
import build.raft.app.data.AuthenticatedRouteAuthorityGateway
import build.raft.app.data.AuthenticatedRouteAuthorityReceipt
import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.ChannelMemberActionResult
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.InMemorySlockAuthSessionStore
import build.raft.app.data.RemoveChannelMemberRequest
import build.raft.app.data.SlockExpectedAuthBinding
import build.raft.app.data.SlockNetworkClient
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.SlockTimeFormat24Hour
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ThreadMembersRouteStoreTest {
    @Test
    fun mismatchedOwnerRejectsLoadAddAndRemoveBeforeNetworkClient() = runBlocking {
        val gateway = RejectingThreadMembersGateway()
        val dataSource =
            RealThreadMembersRouteDataSource(
                route = route(),
                authStore = InMemorySlockAuthSessionStore(),
                owner = owner(),
                gateway = gateway,
            )
        val add = AddChannelMemberRequest(ChannelId("channel-1"), MemberId("user-2"), MemberKind.Human)
        val remove = RemoveChannelMemberRequest(ChannelId("channel-1"), MemberId("user-2"), MemberKind.Human)

        listOf(dataSource.load(), dataSource.add(add), dataSource.remove(remove)).forEach { result ->
            assertEquals(
                AUTHENTICATED_ROUTE_AUTHORITY_CHANGED,
                assertIs<DataResult.Error>(result).error.code,
            )
        }
        assertEquals(3, gateway.captureCalls)
        assertEquals(0, gateway.networkClientCalls)
    }

    @Test
    fun routeStoreOwnsRosterCandidateAddAndRemoveState() = runBlocking {
        val joined = member("user-1", "Joined")
        val candidate = member("user-2", "Candidate")
        val dataSource =
            FakeThreadMembersRouteDataSource(
                loadResult =
                    DataResult.Content(
                        ThreadMembersRouteSnapshot(
                            channelName = "canonical",
                            channelMembers = listOf(joined),
                            serverMembers = listOf(joined, candidate),
                            canAddMembers = true,
                        )
                    )
            )
        val store = ThreadMembersRouteStore(route(), dataSource, this)

        store.dispatch(ThreadMembersRouteIntent.Load)
        yield()
        assertEquals("canonical", store.state.value.channelName)
        assertEquals(listOf(joined), store.state.value.channelMembers)
        assertEquals(listOf(joined, candidate), store.state.value.serverMembers)

        store.dispatch(ThreadMembersRouteIntent.Add(candidate))
        yield()
        assertEquals(listOf(joined, candidate), store.state.value.channelMembers)
        assertEquals(ThreadMembersRouteEffect.Added(candidate), store.state.value.effect)

        store.dispatch(ThreadMembersRouteIntent.ConsumeEffect)
        store.dispatch(
            ThreadMembersRouteIntent.Remove(
                RemoveChannelMemberRequest(ChannelId("channel-1"), candidate.id, candidate.kind)
            )
        )
        yield()
        assertEquals(listOf(joined), store.state.value.channelMembers)
        assertEquals(ThreadMembersRouteEffect.Removed("user-2"), store.state.value.effect)
        store.clear()
    }

    @Test
    fun refreshCannotClearActionAndAuthorityInvalidationDropsLateResult() = runBlocking {
        val member = member("user-2", "Candidate")
        val addGate = CompletableDeferred<DataResult<ChannelMemberActionResult>>()
        val dataSource =
            FakeThreadMembersRouteDataSource(
                loadResult =
                    DataResult.Content(
                        ThreadMembersRouteSnapshot(
                            channelName = "canonical",
                            channelMembers = emptyList(),
                            serverMembers = listOf(member),
                            canAddMembers = true,
                        )
                    ),
                addGate = addGate,
            )
        val store = ThreadMembersRouteStore(route(), dataSource, this)
        store.dispatch(ThreadMembersRouteIntent.Load)
        yield()

        store.dispatch(ThreadMembersRouteIntent.Add(member))
        yield()
        store.dispatch(ThreadMembersRouteIntent.Refresh)
        assertEquals(1, dataSource.loadCalls)
        assertEquals(ThreadMembersRouteAction.Add, store.state.value.actionInFlight)

        store.invalidateAuthority()
        addGate.complete(
            DataResult.Content(
                ChannelMemberActionResult(ChannelId("channel-1"), member.id, member = member)
            )
        )
        yield()
        assertEquals(ThreadMembersRouteStatus.AuthorityRejected, store.state.value.status)
        assertEquals(AUTHENTICATED_ROUTE_AUTHORITY_CHANGED, store.state.value.errorCode)
        assertTrue(store.state.value.channelMembers.isEmpty())
        store.clear()
    }

    @Test
    fun realDataSourceLoadsCanonicalChannelNameAndCandidatesForEmptyRoster() = runBlocking {
        val network = RecordingThreadMembersNetworkClient()
        val dataSource =
            RealThreadMembersRouteDataSource(
                route = route(),
                authStore = authenticatedStore(),
                owner = owner(),
                gateway = FixedThreadMembersGateway(network),
            )

        val snapshot = assertIs<DataResult.Content<ThreadMembersRouteSnapshot>>(dataSource.load()).data

        assertEquals("canonical-name", snapshot.channelName)
        assertTrue(snapshot.channelMembers.isEmpty())
        assertEquals(listOf("user-2"), snapshot.serverMembers.map { it.id.value })
        assertTrue(snapshot.canAddMembers)
        assertEquals("channels/channel-1", network.paths.first())
        assertTrue("channels/channel-1/members" in network.paths)
        assertTrue("servers/server-1/members" in network.paths)
        assertTrue(network.paths.none { it == "home" || it.endsWith("/home") })
    }

    private fun route(): ThreadMembersPageRoute =
        ThreadMembersPageRoute(
            instanceId = "members-1",
            serverId = ServerId("server-1"),
            channelId = ChannelId("channel-1"),
            localOffsetMinutes = 0,
            todayEpochDay = null,
            nowEpochMillis = null,
            timeFormat = SlockTimeFormat24Hour,
        )

    private fun owner(): AuthenticatedRouteOwner =
        AuthenticatedRouteOwner("https://api.raft.build/api", "user-1", "server-1")

    private fun authenticatedStore(): InMemorySlockAuthSessionStore =
        InMemorySlockAuthSessionStore(
            StoredAuthSession(
                user = StoredAuthUser(id = "user-1", email = "user@raft.build"),
                accessToken = "access",
                refreshToken = "refresh",
                selectedServerId = "server-1",
                servers = listOf(StoredAuthServer(id = "server-1", name = "Raft")),
            )
        )

    private fun member(id: String, displayName: String): MemberProfile =
        MemberProfile(
            id = MemberId(id),
            displayName = displayName,
            handle = id,
            kind = MemberKind.Human,
        )
}

private class FixedThreadMembersGateway(
    private val client: SlockNetworkClient,
) : AuthenticatedRouteAuthorityGateway {
    private val receipt =
        AuthenticatedRouteAuthorityReceipt(
            owner = AuthenticatedRouteOwner("https://api.raft.build/api", "user-1", "server-1"),
            authGeneration = 1L,
            selectionGeneration = 1L,
            canManageSettings = false,
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

private class RecordingThreadMembersNetworkClient : SlockNetworkClient {
    val paths = mutableListOf<String>()

    override suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?,
    ): HomeNetworkResponse {
        paths += path
        val body =
            when (path) {
                "channels/channel-1" ->
                    """{"id":"channel-1","name":"canonical-name","type":"channel"}"""
                "channels/channel-1/members" -> """{"humans":[],"agents":[]}"""
                "servers/server-1/members" ->
                    """[{"userId":"user-2","name":"candidate","displayName":"Candidate"}]"""
                else -> return HomeNetworkResponse(code = 404, bodyText = null)
            }
        return HomeNetworkResponse(code = 200, bodyText = body)
    }
}

private class RejectingThreadMembersGateway : AuthenticatedRouteAuthorityGateway {
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

private class FakeThreadMembersRouteDataSource(
    private val loadResult: DataResult<ThreadMembersRouteSnapshot>,
    private val addGate: CompletableDeferred<DataResult<ChannelMemberActionResult>>? = null,
) : ThreadMembersRouteDataSource {
    var loadCalls = 0
    var addCalls = 0

    override suspend fun load(): DataResult<ThreadMembersRouteSnapshot> {
        loadCalls += 1
        return loadResult
    }

    override suspend fun add(request: AddChannelMemberRequest): DataResult<ChannelMemberActionResult> {
        addCalls += 1
        return addGate?.await()
            ?: DataResult.Content(
                ChannelMemberActionResult(
                    channelId = request.channelId,
                    memberId = request.memberId,
                    member = null,
                    added = true,
                )
            )
    }

    override suspend fun remove(request: RemoveChannelMemberRequest): DataResult<ChannelMemberActionResult> =
        DataResult.Content(
            ChannelMemberActionResult(
                channelId = request.channelId,
                memberId = request.memberId,
                member = null,
                added = false,
            )
        )
}
