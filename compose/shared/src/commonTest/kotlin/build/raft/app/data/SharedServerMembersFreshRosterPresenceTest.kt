package build.raft.app.data

import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Hands c76f162d: an agent's Home DM row said "Online" while it was working; opening the agent
 * showed "Working". A missed coarse `agent:activity_state` left a seq-carrying "online" fact, and
 * the `/agents` refetch could never replace it within the same socket (only the agent detail
 * could). Like web `loadAgents`, a `/agents` read this caller started on the network now repairs
 * it; a push during the read still wins, and a cached or joined read keeps filling gaps only.
 */
class SharedServerMembersFreshRosterPresenceTest {
    private val serverId = ServerId("server-1")
    private val agentId = MemberId("agent-1")
    private val owner = ProcessServerMembersOwner(environmentId = "local", accountId = "local", serverId = serverId.value)
    private val transport = ProcessPresenceTransportEpoch("family-1", 1L)

    private class Repository(private val agentId: MemberId) : MemberRepository by PresenceNoopMemberRepository {
        var agentsStatus = AgentStatus.Working
        var agentsStarted = CompletableDeferred<Unit>()
        var agentsRelease: CompletableDeferred<Unit>? = null
        var agentCalls = 0

        fun agent(serverId: ServerId, status: AgentStatus) =
            MemberProfile(
                id = agentId,
                displayName = "Agent One",
                handle = "agent-one",
                kind = MemberKind.Agent,
                presence = status.presence,
                agentStatus = status,
                serverId = serverId
            )

        override suspend fun getMembers(priority: DataRequestPriority, serverId: ServerId): DataResult<MemberSnapshot> =
            DataResult.Content(
                MemberSnapshot(ServerSummary(id = serverId, name = "Raft"), listOf(agent(serverId, AgentStatus.Online)))
            )

        override suspend fun getServerAgents(priority: DataRequestPriority, serverId: ServerId): DataResult<List<MemberProfile>> {
            agentCalls += 1
            agentsStarted.complete(Unit)
            agentsRelease?.await()
            return DataResult.Content(listOf(agent(serverId, agentsStatus)))
        }
    }

    private suspend fun loadedStore(repository: Repository, registry: ProcessMemberPresenceRegistry): SharedServerMembersStore {
        val store =
            SharedServerMembersStore(
                memberRepository = repository,
                processCore =
                    ProcessServerMembersCore(
                        fetchMembers = repository::getMembers,
                        fetchAgents = repository::getServerAgents
                    ),
                presenceRegistry = registry
            )
        registry.rebaselineTransport(owner, registry.writeGeneration, transport)
        assertEquals(DataResult.Content(Unit), store.refreshMembers(serverId, force = true, reason = "presence_transport_connected"))
        return store
    }

    private fun ProcessMemberPresenceRegistry.push(status: AgentStatus, serverSeq: Long) =
        apply(
            owner = owner,
            event =
                MemberPresenceUpdated(
                    memberId = agentId,
                    presence = status.presence,
                    kind = MemberKind.Agent,
                    agentStatus = status,
                    serverSeq = serverSeq,
                    coarseState = true
                ),
            writeGeneration = writeGeneration,
            transport = transport
        )

    @Test
    fun freshAgentsReadRepairsAStuckOnlineFactWithinTheSameTransport() = runBlocking {
        val registry = ProcessMemberPresenceRegistry()
        val repository = Repository(agentId)
        val store = loadedStore(repository, registry)
        // The last push this socket saw said online; the busy one that followed was missed.
        registry.push(AgentStatus.Online, serverSeq = 10L)
        assertEquals(AgentStatus.Online, store.currentMemberStatus(serverId, agentId.value)?.status)

        store.refreshAgents(serverId, reason = "foreground_reconcile")

        val fact = registry.factFor(owner, agentId.value)
        assertEquals(AgentStatus.Working, fact?.agentStatus)
        assertEquals(10L, fact?.serverSeq, "the transport's seq floor stays")
        assertEquals(AgentStatus.Working, store.currentMemberStatus(serverId, agentId.value)?.status)

        // A later push still applies on top of the repair; an older one does not roll it back.
        registry.push(AgentStatus.Online, serverSeq = 9L)
        assertEquals(AgentStatus.Working, registry.factFor(owner, agentId.value)?.agentStatus)
        registry.push(AgentStatus.Online, serverSeq = 11L)
        assertEquals(AgentStatus.Online, registry.factFor(owner, agentId.value)?.agentStatus)
    }

    @Test
    fun pushDuringTheAgentsReadStillWins() = runBlocking {
        val registry = ProcessMemberPresenceRegistry()
        val repository = Repository(agentId)
        val store = loadedStore(repository, registry)
        registry.push(AgentStatus.Working, serverSeq = 10L)
        repository.agentsStatus = AgentStatus.Working
        repository.agentsRelease = CompletableDeferred()

        val refresh = async { store.refreshAgents(serverId, reason = "foreground_reconcile") }
        repository.agentsStarted.await()
        // The agent finished while `/agents` was in flight: the read answers from before that.
        registry.push(AgentStatus.Online, serverSeq = 20L)
        repository.agentsRelease?.complete(Unit)
        refresh.await()

        val fact = registry.factFor(owner, agentId.value)
        assertEquals(AgentStatus.Online, fact?.agentStatus)
        assertEquals(20L, fact?.serverSeq)
    }

    @Test
    fun joinedAgentsReadStartedBeforeAPushDoesNotReplaceIt() = runBlocking {
        val registry = ProcessMemberPresenceRegistry()
        val repository = Repository(agentId)
        val store = loadedStore(repository, registry)
        registry.push(AgentStatus.Working, serverSeq = 10L)
        repository.agentsRelease = CompletableDeferred()

        val first = async { store.refreshAgents(serverId, reason = "foreground_reconcile") }
        repository.agentsStarted.await()
        registry.push(AgentStatus.Online, serverSeq = 20L)
        // Captured after the push, but joins the read that started before it.
        val joined = async { store.refreshAgents(serverId, reason = "realtime_agent:updated") }
        repeat(5) { yield() }
        repository.agentsRelease?.complete(Unit)
        first.await()
        joined.await()

        assertEquals(1, repository.agentCalls, "the second refresh joined the first read")
        assertEquals(AgentStatus.Online, registry.factFor(owner, agentId.value)?.agentStatus)
        assertEquals(20L, registry.factFor(owner, agentId.value)?.serverSeq)
    }

    @Test
    fun cachedRosterReadIsNotFreshAndDoesNotReplaceASequencedFact() = runBlocking {
        val repository = Repository(agentId)
        val core = ProcessServerMembersCore(fetchMembers = repository::getMembers, fetchAgents = repository::getServerAgents)
        val started = core.load(owner, DataRequestPriority.Visible, force = false, reason = "first")
        assertTrue(started.startedByCaller)
        val cached = core.load(owner, DataRequestPriority.Visible, force = false, reason = "second")
        assertTrue(cached.cacheHit)
        assertFalse(cached.startedByCaller)

        val registry = ProcessMemberPresenceRegistry()
        registry.rebaselineTransport(owner, registry.writeGeneration, transport)
        registry.push(AgentStatus.Online, serverSeq = 10L)
        val rosterRequest = checkNotNull(registry.captureRosterProjectionRequest(owner, registry.writeGeneration))
        registry.applyProjection(
            request = checkNotNull(rosterRequest.memberRequest(agentId.value, freshRoster = cached.startedByCaller)),
            event =
                MemberPresenceUpdated(
                    memberId = agentId,
                    presence = AgentStatus.Working.presence,
                    kind = MemberKind.Agent,
                    agentStatus = AgentStatus.Working,
                    serverSeq = null
                )
        )

        assertEquals(AgentStatus.Online, registry.factFor(owner, agentId.value)?.agentStatus)
    }
}
