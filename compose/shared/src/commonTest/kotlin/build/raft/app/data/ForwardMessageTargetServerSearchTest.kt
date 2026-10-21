package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import build.raft.app.raftForwardPickerProgressiveSearch
import build.raft.app.threadForwardSelectionKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** task #21: forward-target search is answered by the server; local DM/channel data is only the interim/fallback. */
@OptIn(ExperimentalCoroutinesApi::class)
class ForwardMessageTargetServerSearchTest {
    private val serverId = ServerId("server-1")

    @Test
    fun queryIsOneServerRequestWithQAndLimit20AndMapsEveryWireTargetType() = runTest {
        val network = SearchNetworkClient()
        network.searchResponse = { HomeNetworkResponse(code = 200, bodyText = ServerSearchBody) }
        val repository = repository(network)

        val targets =
            assertIs<DataResult.Content<List<ForwardMessageTarget>>>(
                repository.searchTargets(serverId, "  des ", limit = 60)
            ).data

        val search = network.calls.single { it.path == ForwardTargetSearchPath }
        assertEquals("GET", search.method)
        assertEquals(listOf("q" to "des", "limit" to "20"), search.query)
        assertEquals(serverId, search.serverId)
        assertEquals(1, network.calls.count { it.path == ForwardTargetSearchPath })

        // Server rank order is kept.
        assertEquals(
            listOf("ch-design", "ch-design-public", "ch-design-private", "ch-design-joint", "dm-dee", "user-desmond", "agent-designbot"),
            targets.map { it.id }
        )
        val joined = targets[0]
        assertEquals(ForwardMessageTargetKind.RecentChannel, joined.kind)
        assertEquals("#design", joined.title)
        assertEquals(ChannelId("ch-design"), joined.channelId)
        assertEquals("channel", joined.channelType)
        assertEquals(ConversationKind.Channel, joined.channelKind)
        assertEquals(ForwardMessageTargetRequiredAction.None, joined.requiredAction)
        assertTrue(joined.canForwardNow)

        val unjoined = targets[1]
        assertEquals(ForwardMessageTargetKind.PublicChannelUnjoined, unjoined.kind)
        assertEquals(ForwardMessageTargetRequiredAction.JoinChannel, unjoined.requiredAction)
        assertEquals(ChannelId("ch-design-public"), unjoined.channelId)
        assertFalse(unjoined.canForwardNow)

        assertEquals("private", targets[2].channelType)
        assertEquals(ConversationKind.Channel, targets[2].channelKind)
        assertEquals("joint", targets[3].channelType)
        assertEquals(ConversationKind.Joint, targets[3].channelKind)

        val dm = targets[4]
        assertEquals(ForwardMessageTargetKind.RecentDirectMessage, dm.kind)
        assertEquals("Dee Signer", dm.title)
        assertEquals(ChannelId("dm-dee"), dm.channelId)
        assertEquals(MemberId("user-dee"), dm.memberId)
        assertEquals(ConversationKind.DirectMessage, dm.channelKind)
        assertEquals("https://cdn.example/dee.png", dm.avatarWireValue)
        assertTrue(dm.canForwardNow)
        assertEquals(ForwardMessageTargetRequiredAction.None, dm.requiredAction)

        val human = targets[5]
        assertEquals(ForwardMessageTargetKind.HumanDirectMessageCandidate, human.kind)
        assertEquals("Desmond", human.title)
        assertEquals(MemberId("user-desmond"), human.memberId)
        assertEquals(null, human.channelId)
        assertEquals(ConversationKind.DirectMessage, human.channelKind)
        assertEquals(ForwardMessageTargetRequiredAction.CreateDirectMessage, human.requiredAction)
        assertFalse(human.canForwardNow)

        val agent = targets[6]
        assertEquals(ForwardMessageTargetKind.AgentDirectMessageCandidate, agent.kind)
        assertEquals("DesignBot", agent.title)
        assertEquals("", agent.subtitle)
        assertEquals(MemberId("agent-designbot"), agent.memberId)
        assertEquals(ForwardMessageTargetRequiredAction.CreateDirectMessage, agent.requiredAction)

        // The required actions drive the existing resolve flow unchanged.
        assertEquals(
            "forward_join_confirmation_required",
            assertIs<DataResult.Error>(repository.resolveTargetChannel(serverId, unjoined, allowJoin = false)).error.code
        )
        assertEquals(
            ChannelId("dm-dee"),
            assertIs<DataResult.Content<ForwardMessageDestination>>(repository.resolveTargetChannel(serverId, dm)).data.channelId
        )
        assertTrue(network.logs.any { it.startsWith("forward_target_search source=server q_len=3 results=7 durationMs=") })
    }

    @Test
    fun searchNeverLoadsTheMemberDirectory() = runTest {
        val network = SearchNetworkClient()
        network.searchResponse = { HomeNetworkResponse(code = 200, bodyText = ServerSearchBody) }
        val repository = repository(network)

        // Cold: interim may read DMs/channels once, the server answers — the member list is never read.
        repository.searchTargetsProgressively(serverId, "de", limit = 60).toList()
        repository.searchTargetsProgressively(serverId, "des", limit = 60).toList()

        assertEquals(0, network.memberDirectoryCalls(), "search must never call getMembers: ${network.calls}")
        assertEquals(1, network.calls.count { it.path == "channels" }, "the local interim index is reused across keystrokes")
        assertEquals(1, network.calls.count { it.path == "channels/dm" })
        assertEquals(2, network.calls.count { it.path == ForwardTargetSearchPath })
    }

    @Test
    fun progressiveSearchShowsLocalDmAndChannelMatchesThenTheServerList() = runTest {
        val network = SearchNetworkClient()
        val serverAnswer = CompletableDeferred<HomeNetworkResponse>()
        network.searchResponse = { serverAnswer.await() }
        val repository = repository(network)
        assertIs<DataResult.Content<ForwardMessageTargetDirectoryPage>>(
            repository.directoryTargets(serverId, sessionId = "picker", pageSize = 30)
        )
        val updates = mutableListOf<ForwardTargetSearchUpdate>()
        val job = launch { repository.searchTargetsProgressively(serverId, "de", limit = 60).collect { updates += it } }
        runCurrent()

        val interim = assertIs<ForwardTargetSearchUpdate.Interim>(updates.single())
        // Local data only: the loaded member candidate (Desmond) is left to the server.
        assertEquals(listOf("dm-dee", "ch-design"), interim.targets.map { it.id })
        assertTrue(network.logs.any { it.startsWith("forward_target_search source=local_interim q_len=2 results=2 durationMs=") })

        serverAnswer.complete(HomeNetworkResponse(code = 200, bodyText = ServerSearchBody))
        job.join()
        val final = assertIs<ForwardTargetSearchUpdate.Final>(updates.last())
        assertEquals(2, updates.size)
        val finalTargets = assertIs<DataResult.Content<List<ForwardMessageTarget>>>(final.result).data
        assertEquals(7, finalTargets.size)
        // Same identity as the interim rows, so a row keeps its selection key when the server list lands.
        val interimKeys = interim.targets.map { it.threadForwardSelectionKey() }.toSet()
        assertEquals(interimKeys, finalTargets.map { it.threadForwardSelectionKey() }.filter { it in interimKeys }.toSet())
    }

    @Test
    fun serverFailureKeepsTheLocalDmAndChannelMatchesWithoutRetrying() = runTest {
        val network = SearchNetworkClient()
        network.searchResponse = { HomeNetworkResponse(code = 503, bodyText = null, errorBodyText = "down") }
        val repository = repository(network)

        val updates = repository.searchTargetsProgressively(serverId, "de", limit = 60).toList()

        val final = assertIs<ForwardTargetSearchUpdate.Final>(updates.last())
        assertEquals(
            listOf("dm-dee", "ch-design"),
            assertIs<DataResult.Content<List<ForwardMessageTarget>>>(final.result).data.map { it.id }
        )
        assertEquals(1, network.calls.count { it.path == ForwardTargetSearchPath }, "no retry storm")
        assertTrue(
            network.logs.any {
                it.startsWith("forward_target_search source=server result=error code=server_unavailable status=503 q_len=2 fallback_results=2")
            },
            network.logs.joinToString("\n")
        )

        // Offline (transport throws): same local answer.
        network.searchResponse = { throw IllegalStateException("offline") }
        assertEquals(
            listOf("dm-dee", "ch-design"),
            assertIs<DataResult.Content<List<ForwardMessageTarget>>>(
                repository.searchTargets(serverId, "de", limit = 60)
            ).data.map { it.id }
        )
        assertTrue(network.logs.any { it.contains("result=error code=network_unavailable") })
        assertEquals(0, network.memberDirectoryCalls())
    }

    @Test
    fun fastTypingCancelsEarlierServerRequestsAndShowsOnlyTheLastQuery() = runTest {
        val network = SearchNetworkClient()
        val pending = mutableMapOf<String, CompletableDeferred<HomeNetworkResponse>>()
        val cancelled = mutableListOf<String>()
        network.searchResponse = { query ->
            val q = query.first { it.first == "q" }.second
            val answer = CompletableDeferred<HomeNetworkResponse>().also { pending[q] = it }
            try {
                answer.await()
            } catch (cancellation: CancellationException) {
                cancelled += q
                throw cancellation
            }
        }
        val repository = repository(network)
        var shown: List<String> = emptyList()
        val typed = MutableStateFlow("")
        // The picker effect restarts per keystroke, exactly like LaunchedEffect(query).
        val picker =
            launch {
                typed.collectLatest { query ->
                    if (query.isBlank()) return@collectLatest
                    raftForwardPickerProgressiveSearch(
                        repository = repository,
                        serverId = serverId,
                        query = query,
                        limit = 60,
                        isCurrent = { typed.value == query },
                        onInterim = { shown = it.map { target -> target.id } },
                        serverDebounceMillis = 0L
                    )?.let { result ->
                        shown = assertIs<DataResult.Content<List<ForwardMessageTarget>>>(result).data.map { it.id }
                    }
                }
            }
        for (query in listOf("d", "de", "des")) {
            typed.value = query
            runCurrent()
        }
        assertEquals(listOf("d", "de"), cancelled, "earlier in-flight server requests are cancelled")
        pending.getValue("des").complete(HomeNetworkResponse(code = 200, bodyText = searchBody("des-result")))
        advanceUntilIdle()
        assertEquals(listOf("des-result"), shown)
        picker.cancel()
    }

    @Test
    fun anOlderQueryAnsweringLateIsNeverShown() = runTest {
        val network = SearchNetworkClient()
        val pending = mutableMapOf<String, CompletableDeferred<HomeNetworkResponse>>()
        network.searchResponse = { query ->
            val q = query.first { it.first == "q" }.second
            CompletableDeferred<HomeNetworkResponse>().also { pending[q] = it }.await()
        }
        val repository = repository(network)
        var latestQuery = ""
        var shown: List<String> = emptyList()
        // No restart/cancellation here: only the isCurrent latest-wins guard can stop a stale answer.
        for (query in listOf("d", "de", "des")) {
            latestQuery = query
            launch {
                raftForwardPickerProgressiveSearch(
                    repository = repository,
                    serverId = serverId,
                    query = query,
                    limit = 60,
                    isCurrent = { latestQuery == query },
                    onInterim = { shown = it.map { target -> target.id } },
                    serverDebounceMillis = 0L
                )?.let { result ->
                    shown = assertIs<DataResult.Content<List<ForwardMessageTarget>>>(result).data.map { it.id }
                }
            }
            runCurrent()
        }
        pending.getValue("des").complete(HomeNetworkResponse(code = 200, bodyText = searchBody("des-result")))
        runCurrent()
        assertEquals(listOf("des-result"), shown)
        // Older queries answer after the latest one; they must not replace it.
        pending.getValue("de").complete(HomeNetworkResponse(code = 200, bodyText = searchBody("de-result")))
        pending.getValue("d").complete(HomeNetworkResponse(code = 200, bodyText = searchBody("d-result")))
        advanceUntilIdle()
        assertEquals(listOf("des-result"), shown)
    }

    @Test
    fun serverDebounceDelaysOnlyTheServerRequestNotTheInterim() = runTest {
        val network = SearchNetworkClient()
        network.searchResponse = { HomeNetworkResponse(code = 200, bodyText = searchBody("server-row")) }
        val repository = repository(network)
        val updates = mutableListOf<ForwardTargetSearchUpdate>()
        val job =
            launch {
                repository.searchTargetsProgressively(
                    serverId,
                    "de",
                    limit = 60,
                    serverDebounceMillis = ForwardTargetServerSearchDebounceMillis
                ).collect { updates += it }
            }
        runCurrent()
        assertIs<ForwardTargetSearchUpdate.Interim>(updates.single())
        assertEquals(0, network.calls.count { it.path == ForwardTargetSearchPath })
        job.join()
        assertEquals(1, network.calls.count { it.path == ForwardTargetSearchPath })
        assertIs<ForwardTargetSearchUpdate.Final>(updates.last())
    }

    private fun repository(network: SearchNetworkClient): RealForwardMessageTargetRepository {
        val store =
            InMemoryRaftAuthSessionStore(
                StoredAuthSession(
                    user = StoredAuthUser(id = "user-1", email = "user@example.com"),
                    accessToken = "access",
                    refreshToken = "refresh",
                    selectedServerId = serverId.value,
                    servers = listOf(StoredAuthServer(id = serverId.value, name = "Server"))
                )
            )
        return RealForwardMessageTargetRepository(
            sessionStore = store,
            networkClient = network,
            channelRepository = RealChannelRepository(store, network),
            memberRepository = RealMemberRepository(store, network),
            warningLogger = { network.logs += it }
        )
    }

    private fun searchBody(channelId: String): String =
        """{"targets":[{"type":"channel","channelType":"channel","id":"$channelId","title":"#$channelId","subtitle":"","avatarUrl":null,"channelId":"$channelId","joined":true,"dmExists":null,"canForwardNow":true,"requiredAction":null}]}"""
}

/** Real slock `GET /api/messages/forward/targets/search` response shape (score is stripped server-side). */
private val ServerSearchBody =
    """
        {"targets":[
          {"type":"channel","channelType":"channel","id":"ch-design","title":"#design","subtitle":"Design talk","avatarUrl":null,"channelId":"ch-design","joined":true,"dmExists":null,"canForwardNow":true,"requiredAction":null},
          {"type":"channel","channelType":"channel","id":"ch-design-public","title":"#design-public","subtitle":"","avatarUrl":null,"channelId":"ch-design-public","joined":false,"dmExists":null,"canForwardNow":false,"requiredAction":"join_channel"},
          {"type":"channel","channelType":"private","id":"ch-design-private","title":"#design-private","subtitle":"","avatarUrl":null,"channelId":"ch-design-private","joined":true,"dmExists":null,"canForwardNow":true,"requiredAction":null},
          {"type":"channel","channelType":"joint","id":"ch-design-joint","title":"#design-joint","subtitle":"","avatarUrl":null,"channelId":"ch-design-joint","joined":true,"dmExists":null,"canForwardNow":true,"requiredAction":null},
          {"type":"dm","channelType":"dm","id":"user-dee","title":"@Dee Signer","subtitle":"","avatarUrl":"https://cdn.example/dee.png","channelId":"dm-dee","joined":null,"dmExists":true,"canForwardNow":true,"requiredAction":null},
          {"type":"human","channelType":null,"id":"user-desmond","title":"@Desmond","subtitle":"","avatarUrl":null,"channelId":null,"joined":null,"dmExists":false,"canForwardNow":false,"requiredAction":"create_dm"},
          {"type":"agent","channelType":null,"id":"agent-designbot","title":"@DesignBot","subtitle":"agent","avatarUrl":null,"channelId":null,"joined":null,"dmExists":false,"canForwardNow":false,"requiredAction":"create_dm"}
        ]}
    """.trimIndent()

private data class SearchNetworkCall(
    val path: String,
    val method: String,
    val query: List<Pair<String, String>>,
    val serverId: ServerId?
)

private class SearchNetworkClient : RaftNetworkClient {
    val calls = mutableListOf<SearchNetworkCall>()
    val logs = mutableListOf<String>()
    var searchResponse: suspend (List<Pair<String, String>>) -> HomeNetworkResponse = {
        HomeNetworkResponse(code = 200, bodyText = """{"targets":[]}""")
    }

    fun memberDirectoryCalls(): Int =
        calls.count { it.path.endsWith("/members") || it.path == "agents" || it.path.endsWith("/machines") }

    override suspend fun request(
        priority: DataRequestPriority,
        path: String,
        method: String,
        query: List<Pair<String, String>>,
        serverIdOverride: ServerId?,
        bodyText: String?
    ): HomeNetworkResponse {
        calls += SearchNetworkCall(path, method, query, serverIdOverride)
        return when (path) {
            ForwardTargetSearchPath -> searchResponse(query)
            "channels" ->
                HomeNetworkResponse(
                    code = 200,
                    bodyText = """[{"id":"ch-design","name":"design","type":"channel","joined":true},{"id":"ch-ops","name":"ops","type":"channel","joined":true}]"""
                )
            "channels/dm" ->
                HomeNetworkResponse(
                    code = 200,
                    bodyText = """[{"id":"dm-dee","name":"dm-dee","type":"dm","peerType":"user","peerId":"user-dee","peerName":"Dee Signer"}]"""
                )
            "servers/${serverIdOverride?.value}/members" ->
                HomeNetworkResponse(
                    code = 200,
                    bodyText = """{"members":[{"userId":"user-desmond","name":"desmond","displayName":"Desmond"}]}"""
                )
            "agents", "servers/${serverIdOverride?.value}/machines" -> HomeNetworkResponse(code = 200, bodyText = "[]")
            else -> HomeNetworkResponse(code = 404, bodyText = null, errorBodyText = "not found")
        }
    }
}
