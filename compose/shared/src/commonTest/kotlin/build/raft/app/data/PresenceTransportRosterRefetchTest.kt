package build.raft.app.data

import build.raft.app.model.AgentStatus
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Hands 81ad5dae: switching back to a server visited before reconnects its socket, and that
 * connect took the reconnect jitter (0–20 s) while the rebaseline had already cleared presence, so
 * every agent showed offline for up to 20 s on each switch back.
 */
class PresenceTransportRosterRefetchTest {
    private val serverId = ServerId("server-1")
    private val owner = ProcessServerMembersOwner("local", "local", serverId.value)

    private class CountingRepository(private val agentId: MemberId) : MemberRepository by PresenceNoopMemberRepository {
        var memberCalls = 0
        var agentCalls = 0

        private fun agent(serverId: ServerId) =
            MemberProfile(
                id = agentId,
                displayName = "Agent One",
                handle = "agent-one",
                kind = MemberKind.Agent,
                presence = AgentStatus.Online.presence,
                agentStatus = AgentStatus.Online,
                serverId = serverId
            )

        override suspend fun getMembers(priority: DataRequestPriority, serverId: ServerId): DataResult<MemberSnapshot> {
            memberCalls += 1
            return DataResult.Content(MemberSnapshot(ServerSummary(id = serverId, name = "Raft"), listOf(agent(serverId))))
        }

        override suspend fun getServerAgents(priority: DataRequestPriority, serverId: ServerId): DataResult<List<MemberProfile>> {
            agentCalls += 1
            return DataResult.Content(listOf(agent(serverId)))
        }
    }

    private class Fixture(
        val repository: CountingRepository,
        val registry: ProcessMemberPresenceRegistry,
        val jitter: RaftReconnectRefetchJitter,
        val refetch: PresenceTransportRosterRefetch,
        val invalidations: MutableList<String>,
    )

    private fun TestScope.fixture(): Fixture {
        val repository = CountingRepository(MemberId("agent-1"))
        val registry = ProcessMemberPresenceRegistry()
        val store =
            SharedServerMembersStore(
                memberRepository = repository,
                processCore =
                    ProcessServerMembersCore(
                        fetchMembers = repository::getMembers,
                        fetchAgents = repository::getServerAgents,
                        scope = backgroundScope
                    ),
                presenceRegistry = registry
            )
        val jitter =
            RaftReconnectRefetchJitter(scope = backgroundScope, random = Random(3), timeSource = testScheduler.timeSource)
        val invalidations = mutableListOf<String>()
        val refetch =
            PresenceTransportRosterRefetch(
                jitter = jitter,
                invalidate = { invalidated, reason -> invalidations += "${invalidated.serverId}:$reason" },
                storesForServer = { listOf(store) },
                retryScope = backgroundScope
            )
        return Fixture(repository, registry, jitter, refetch, invalidations)
    }

    private suspend fun Fixture.connect(epoch: Long, agentsOnly: Boolean = false): RaftReconnectRefetchDecision {
        val transport = ProcessPresenceTransportEpoch("family-1", epoch)
        val result = registry.rebaselineTransport(owner, registry.writeGeneration, transport)
        return refetch.onTransportRebaselined(owner, transport, result, agentsOnlyOnReconnect = agentsOnly)
    }

    @Test
    fun connectRightAfterSwitchingBackToTheServerRefetchesAtOnce() = runTest {
        val fixture = fixture()
        assertEquals(RaftReconnectRefetchDecision.Immediate, fixture.connect(epoch = 1L))
        runCurrent()
        assertEquals(1, fixture.repository.memberCalls)

        // Switch away and back: the selection comes before the socket connects.
        fixture.jitter.onServerSelected(serverId.value, reason = "server_selected")
        advanceTimeBy(2_000L)
        val decision = fixture.connect(epoch = 2L)
        runCurrent()

        assertEquals(RaftReconnectRefetchDecision.ImmediateAfterSwitch, decision)
        assertEquals(2, fixture.repository.memberCalls, "the roster refill ran without the jitter")
        assertEquals(AgentStatus.Online, fixture.registry.factFor(owner, "agent-1")?.agentStatus)
        assertEquals(2, fixture.invalidations.size, "sync off: each connect retires the old roster")
    }

    @Test
    fun genuineReconnectWithoutASwitchKeepsTheJitter() = runTest {
        val fixture = fixture()
        fixture.connect(epoch = 1L)
        runCurrent()

        assertEquals(RaftReconnectRefetchDecision.Delayed, fixture.connect(epoch = 2L))
        runCurrent()
        assertEquals(1, fixture.repository.memberCalls)
        advanceTimeBy(RECONNECT_REFETCH_JITTER_MAX_MILLIS + 1L)
        assertEquals(2, fixture.repository.memberCalls)
    }

    @Test
    fun selectionOlderThanTheSwitchWindowNoLongerSkipsTheJitter() = runTest {
        val fixture = fixture()
        fixture.connect(epoch = 1L)
        fixture.jitter.onServerSelected(serverId.value, reason = "server_selected")
        advanceTimeBy((SERVER_SWITCH_CONNECT_WINDOW + 1.seconds).inWholeMilliseconds)

        assertEquals(RaftReconnectRefetchDecision.Delayed, fixture.connect(epoch = 2L))
    }

    @Test
    fun theSwitchIsConsumedByOneConnectSoTheNextReconnectIsJitteredAgain() = runTest {
        val fixture = fixture()
        fixture.connect(epoch = 1L)
        fixture.jitter.onServerSelected(serverId.value, reason = "server_selected")
        assertEquals(RaftReconnectRefetchDecision.ImmediateAfterSwitch, fixture.connect(epoch = 2L))

        assertEquals(RaftReconnectRefetchDecision.Delayed, fixture.connect(epoch = 3L))
    }

    @Test
    fun syncOnSwitchBackRefetchesOnlyAgentsAtOnceAndKeepsTheRoster() = runTest {
        val fixture = fixture()
        fixture.connect(epoch = 1L, agentsOnly = true)
        runCurrent()
        assertEquals(1, fixture.repository.memberCalls, "a first connect keeps the full refetch")
        assertEquals(1, fixture.invalidations.size)

        fixture.jitter.onServerSelected(serverId.value, reason = "server_selected")
        val decision = fixture.connect(epoch = 2L, agentsOnly = true)
        runCurrent()

        assertEquals(RaftReconnectRefetchDecision.ImmediateAfterSwitch, decision)
        assertEquals(1, fixture.repository.memberCalls, "no /members read")
        assertEquals(1, fixture.repository.agentCalls, "/agents read at once")
        assertEquals(1, fixture.invalidations.size, "the roster stays valid (#3773)")
        assertEquals(AgentStatus.Online, fixture.registry.factFor(owner, "agent-1")?.agentStatus)
    }
}
