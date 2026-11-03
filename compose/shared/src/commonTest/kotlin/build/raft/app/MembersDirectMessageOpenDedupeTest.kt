package build.raft.app

import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.data.ChannelRepository
import build.raft.app.data.DataResult
import build.raft.app.data.HomeDirectMessageRow
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.OpenDirectMessageResult
import build.raft.app.data.RaftDirectMessageOpenKey
import build.raft.app.data.RaftDirectMessageOpens
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.isOpening
import build.raft.app.model.ServerSummary
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * artin 11-03 (Android 1.19.0): the agent profile's Message button waited on a slow
 * `POST /channels/dm` with no sign of it; 18 taps sent 18 POSTs and Back cancelled them all.
 * A DM Home already lists opens at once; taps while a POST is out join it and open one page.
 */
class MembersDirectMessageOpenDedupeTest {
    private val opened = mutableListOf<String>()
    private val transport = HostBridgeTransport { _, _, _ -> }
    private val navigator =
        object : RaftCmpNavigator {
            override fun openPage(pageName: String, pageData: JsonObject) {
                opened += pageName
            }

            override fun closePage() = Unit
        }

    private fun pager(): Pager =
        object : Pager() {}.also { pager ->
            pager.pagerId = "members-dm-dedupe-test"
            pager.pageData = PageData(JSONObject())
            pager.host = RaftCmpHost(BridgeWireHostCapabilities(transport), navigator, bridgeTransport = transport)
        }

    private val server = ServerId("server-1")
    private val agent =
        MemberProfile(id = MemberId("agent-lagoon"), displayName = "Lagoon", handle = "Lagoon", kind = MemberKind.Agent, serverId = server)
    private val human =
        MemberProfile(id = MemberId("user-tenny"), displayName = "Tenny", handle = "tenny", kind = MemberKind.Human, serverId = server)

    private fun dmRow(id: String, peerType: String, peerId: String) =
        HomeDirectMessageRow(
            threadId = ThreadId(id),
            channelId = ChannelId(id),
            name = id,
            directMessagePeerType = peerType,
            presenceMemberId = MemberId(peerId)
        )

    private fun homeWith(vararg rows: HomeDirectMessageRow) =
        HomeSnapshot(
            server = ServerSummary(id = server, name = "Raft"),
            currentMember = MemberProfile(id = MemberId("user-artin"), displayName = "artin", handle = "artin", kind = MemberKind.Human),
            channels = emptyList(),
            directMessages = rows.toList(),
            unreadTotal = 0
        )

    /** Counts `POST /channels/dm`; each call waits on [gate] (the slow network). */
    private class CountingDmRepository(
        private val gate: CompletableDeferred<Unit>? = null
    ) : ChannelRepository by FakeChannelRepository() {
        var agentCalls = 0
        var userCalls = 0

        override suspend fun openAgentDirectMessage(agentId: MemberId): DataResult<OpenDirectMessageResult> {
            agentCalls += 1
            gate?.await()
            return DataResult.Content(
                OpenDirectMessageResult(
                    conversation = HomeDirectMessageRow(
                        threadId = ThreadId("dm-new"),
                        channelId = ChannelId("dm-new"),
                        name = "Lagoon",
                        directMessagePeerType = "agent",
                        presenceMemberId = agentId
                    ),
                    directMessages = emptyList()
                )
            )
        }

        override suspend fun openUserDirectMessage(userId: MemberId): DataResult<OpenDirectMessageResult> {
            userCalls += 1
            error("network must not be called")
        }
    }

    private fun DataResult<OpenDirectMessageResult>.openPage(member: MemberProfile, unavailable: MutableList<String>) =
        openMembersDirectMessageResult(
            pager = pager(),
            storeScope = RaftShellStoreScope.create("shell-members-dm", "server-1"),
            openInShell = null,
            member = member,
            timeContext = RaftTimeContext(localOffsetMinutes = 0),
            unavailableMessage = "Unable to open direct message",
            errorTemplate = "%s",
            permissionMessage = "no permission",
            errorStrings = englishErrorPresentationStringsForTest(),
            onUnavailableAction = { unavailable += it },
        )

    @Test
    fun aDmHomeAlreadyListsOpensWithoutTheNetwork() = runBlocking {
        val repository = CountingDmRepository()
        val opens = RaftDirectMessageOpens()
        val home = homeWith(dmRow("dm-agent", "agent", "agent-lagoon"), dmRow("dm-human", "user", "user-tenny"))
        val unavailable = mutableListOf<String>()

        val agentResult = assertIs<DataResult.Content<OpenDirectMessageResult>>(
            opens.open(repository, server, MemberKind.Agent, agent.id, home)
        )
        val humanResult = assertIs<DataResult.Content<OpenDirectMessageResult>>(
            opens.open(repository, server, MemberKind.Human, human.id, home)
        )
        agentResult.openPage(agent, unavailable)

        assertEquals("dm-agent", agentResult.data.conversation.channelId.value)
        assertEquals("dm-human", humanResult.data.conversation.channelId.value)
        assertEquals(0, repository.agentCalls)
        assertEquals(0, repository.userCalls)
        assertEquals(listOf(THREAD_PAGE), opened)
        assertTrue(unavailable.isEmpty())
    }

    @Test
    fun aDmOfAnotherServerOrPeerKindStillAsksTheServer() = runBlocking {
        val repository = CountingDmRepository()
        val opens = RaftDirectMessageOpens()
        // Same id under the user peer type, and the agent's DM on another server's Home.
        val home = homeWith(dmRow("dm-wrong-kind", "user", "agent-lagoon"))

        val result = opens.open(repository, server, MemberKind.Agent, agent.id, home)
        val otherServer = opens.open(
            repository,
            ServerId("server-2"),
            MemberKind.Agent,
            agent.id,
            homeWith(dmRow("dm-agent", "agent", "agent-lagoon"))
        )

        assertEquals("dm-new", assertIs<DataResult.Content<OpenDirectMessageResult>>(result).data.conversation.channelId.value)
        assertEquals("dm-new", assertIs<DataResult.Content<OpenDirectMessageResult>>(otherServer).data.conversation.channelId.value)
        assertEquals(2, repository.agentCalls)
    }

    @Test
    fun rapidTapsShareOneRequestAndOpenOnePage() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val repository = CountingDmRepository(gate)
        val opens = RaftDirectMessageOpens()
        val unavailable = mutableListOf<String>()
        val key = RaftDirectMessageOpenKey("server-1", MemberKind.Agent, "agent-lagoon")

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            opens.open(repository, server, MemberKind.Agent, agent.id, homeWith())?.openPage(agent, unavailable)
        }
        // The first tap's POST is out: the button shows its loading state.
        assertEquals(setOf(key), opens.pending.value)
        assertTrue(opens.pending.value.isOpening(MemberKind.Agent, agent.id))

        val second = opens.open(repository, server, MemberKind.Agent, agent.id, homeWith())
        assertNull(second)

        gate.complete(Unit)
        first.await()
        yield()

        assertEquals(1, repository.agentCalls)
        assertEquals(listOf(THREAD_PAGE), opened)
        assertTrue(unavailable.isEmpty())
        assertEquals(emptySet(), opens.pending.value)

        // Once settled, a later tap asks again (nothing stays stuck).
        assertIs<DataResult.Content<OpenDirectMessageResult>>(
            opens.open(repository, server, MemberKind.Agent, agent.id, homeWith())
        )
        assertEquals(2, repository.agentCalls)
    }
}
