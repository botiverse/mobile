package build.raft.app.data

import build.raft.app.model.AgentStatus
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Joint channel peer-server agents (mobile 1.15.2): they are not on this server's roster, so after
 * a reconnect rebaseline only the channel-members response can repopulate their presence facts.
 * Responses are real wire JSON through [parseSharedChannelMemberSnapshot] so the live-activity
 * signal comes from the producer shape, not a hand-built snapshot.
 */
class SharedServerMembersJointPeerAgentPresenceTest {
    private val serverId = ServerId("server-1")
    private val channelId = ChannelId("joint-1")
    private val owner =
        ProcessServerMembersOwner(
            environmentId = "local",
            accountId = "local",
            serverId = serverId.value
        )
    private val transport1 = ProcessPresenceTransportEpoch("family-1", 1L)
    private val transport2 = ProcessPresenceTransportEpoch("family-1", 2L)
    private val neutral =
        SharedServerMemberStatus(status = null, presence = Presence.Offline, statusLabel = "—", external = false)

    private class Repo(var body: String) : MemberRepository by PresenceNoopMemberRepository {
        var fetches = 0
        var hold: CompletableDeferred<Unit>? = null
        val started = CompletableDeferred<Unit>()

        override suspend fun getChannelMembers(
            priority: DataRequestPriority,
            channelId: ChannelId
        ): DataResult<ChannelMemberSnapshot> {
            fetches += 1
            val captured = body
            started.complete(Unit)
            hold?.await()
            return DataResult.Content(parseSharedChannelMemberSnapshot(captured, channelId)!!)
        }
    }

    private fun agentJson(
        id: String,
        server: String,
        activity: String?,
        status: String = "active",
        external: Boolean? = null
    ): String =
        buildString {
            append("""{"id":"$id","serverId":"$server","name":"$id","displayName":"$id","status":"$status"""")
            if (activity != null) append(""","activity":"$activity","activityDetail":"Doing $id"""")
            if (external != null) append(""","external":$external""")
            append("}")
        }

    private fun body(vararg agents: String): String =
        """{"humans":[{"id":"human-1","name":"h","displayName":"H"}],"agents":[${agents.joinToString(",")}]}"""

    private val peerWorking = agentJson("peer-agent", "server-2", activity = "working")
    private val localWorking = agentJson("local-agent", "server-1", activity = "working")

    private fun fixture(body: String): Triple<ProcessMemberPresenceRegistry, Repo, SharedServerMembersStore> {
        val registry = ProcessMemberPresenceRegistry()
        val repo = Repo(body)
        val store = SharedServerMembersStore(repo, presenceRegistry = registry)
        return Triple(registry, repo, store)
    }

    @Test
    fun peerAgentActivityFromChannelMembersRepairsStatusAfterRebaseline() = runBlocking {
        val (registry, _, store) = fixture(body(peerWorking, localWorking))
        registry.rebaselineTransport(owner, registry.writeGeneration, transport1)

        assertIs<DataResult.Content<*>>(store.loadChannelMembers(serverId, channelId))

        val peer = store.memberStatusFlow(serverId, "peer-agent").first()
        assertEquals(AgentStatus.Working, peer?.status)
        assertEquals(Presence.Active, peer?.presence)
        assertEquals(AgentStatus.Working.label, peer?.statusLabel)
        assertEquals(AgentStatus.Working, registry.factFor(owner, "peer-agent")?.agentStatus)
        // Negative twin, same response: a current-server agent (not on the roster here) keeps
        // today's behaviour — the roster refill owns it, the channel response writes nothing.
        assertNull(registry.factFor(owner, "local-agent"))
        assertEquals(neutral, store.memberStatusFlow(serverId, "local-agent").first())
        // Humans never get presence facts.
        assertNull(registry.factFor(owner, "human-1"))
    }

    @Test
    fun responseWithoutPeerAgentLeavesStatusUnchanged() = runBlocking {
        val (registry, _, store) = fixture(body(localWorking))
        registry.rebaselineTransport(owner, registry.writeGeneration, transport1)

        store.loadChannelMembers(serverId, channelId)

        assertTrue(registry.current(owner).isEmpty())
        assertEquals(neutral, store.memberStatusFlow(serverId, "peer-agent").first())
    }

    @Test
    fun guestProjectionWithoutActivityWritesNoFact() = runBlocking {
        // Lifecycle "stopped" alone would map to Offline; without live activity it must not write.
        val (registry, _, store) =
            fixture(body(agentJson("peer-agent", "server-2", activity = null, status = "stopped")))
        registry.rebaselineTransport(owner, registry.writeGeneration, transport1)

        store.loadChannelMembers(serverId, channelId)

        assertNull(registry.factFor(owner, "peer-agent"))
        assertEquals(neutral, store.memberStatusFlow(serverId, "peer-agent").first())
    }

    @Test
    fun lifecycleActiveWithoutResolvableActivityMatchesRosterRule() = runBlocking {
        // `active` in the activity slot is lifecycle, not liveness: the roster refill writes no
        // fact for it (normalizedAgentRuntimeStatus -> null), and neither does this path.
        val (registry, _, store) = fixture(body(agentJson("peer-agent", "server-2", activity = "active")))
        registry.rebaselineTransport(owner, registry.writeGeneration, transport1)

        store.loadChannelMembers(serverId, channelId)

        assertNull(registry.factFor(owner, "peer-agent"))
    }

    @Test
    fun responseCapturedBeforeRebaselineDoesNotWrite() = runBlocking {
        val (registry, repo, store) = fixture(body(peerWorking))
        registry.rebaselineTransport(owner, registry.writeGeneration, transport1)
        repo.hold = CompletableDeferred()

        val load = async { store.loadChannelMembers(serverId, channelId) }
        repo.started.await()
        registry.rebaselineTransport(owner, registry.writeGeneration, transport2)
        repo.hold!!.complete(Unit)
        load.await()

        assertNull(registry.factFor(owner, "peer-agent"))
        assertEquals(neutral, store.memberStatusFlow(serverId, "peer-agent").first())
    }

    @Test
    fun responseCapturedBeforeAuthClearDoesNotWrite() = runBlocking {
        val (registry, repo, store) = fixture(body(peerWorking))
        repo.hold = CompletableDeferred()

        val load = async { store.loadChannelMembers(serverId, channelId) }
        repo.started.await()
        registry.clearAll(reason = "test_account_switch")
        repo.hold!!.complete(Unit)
        load.await()

        assertNull(registry.factFor(owner, "peer-agent"))
    }

    @Test
    fun realtimeDuringChannelMembersRequestWins() = runBlocking {
        val (registry, repo, store) = fixture(body(peerWorking))
        registry.rebaselineTransport(owner, registry.writeGeneration, transport1)
        repo.hold = CompletableDeferred()

        val load = async { store.loadChannelMembers(serverId, channelId) }
        repo.started.await()
        registry.apply(owner, peerPresence(AgentStatus.Offline, serverSeq = 3L), registry.writeGeneration, transport1)
        repo.hold!!.complete(Unit)
        load.await()

        assertEquals(AgentStatus.Offline, registry.factFor(owner, "peer-agent")?.agentStatus)
        assertEquals(3L, registry.factFor(owner, "peer-agent")?.serverSeq)
    }

    @Test
    fun presenceOwnerThatIsNotTheAdmittedChannelOwnerIsNeverWritten() = runBlocking {
        val registry = ProcessMemberPresenceRegistry()
        val otherAccount = owner.copy(accountId = "other")
        val store =
            SharedServerMembersStore(
                Repo(body(peerWorking)),
                presenceRegistry = registry,
                presenceWrite = { ProcessPresenceWrite(otherAccount, registry.writeGeneration) },
                channelMembersOwner = { server ->
                    SharedServerChannelMembersOwnerBinding(
                        environmentId = "local",
                        accountId = "local",
                        serverId = server.value,
                        writeGeneration = registry.writeGeneration
                    )
                }
            )

        assertIs<DataResult.Content<*>>(store.loadChannelMembers(serverId, channelId))

        assertNull(registry.factFor(otherAccount, "peer-agent"))
        assertNull(registry.factFor(owner, "peer-agent"))
    }

    @Test
    fun laterRealtimeActivityOverridesChannelMembersFact() = runBlocking {
        val (registry, _, store) = fixture(body(peerWorking))
        registry.rebaselineTransport(owner, registry.writeGeneration, transport1)
        store.loadChannelMembers(serverId, channelId)
        assertEquals(AgentStatus.Working, store.memberStatusFlow(serverId, "peer-agent").first()?.status)

        registry.apply(owner, peerPresence(AgentStatus.Online, serverSeq = 1L), registry.writeGeneration, transport1)

        val live = store.memberStatusFlow(serverId, "peer-agent").first()
        assertEquals(AgentStatus.Online, live?.status)
        assertEquals("Idle now", live?.statusLabel)
    }

    @Test
    fun externalPeerAgentKeepsExternalToken() = runBlocking {
        val (registry, _, store) =
            fixture(body(agentJson("peer-agent", "server-2", activity = "online", external = true)))
        registry.rebaselineTransport(owner, registry.writeGeneration, transport1)

        store.loadChannelMembers(serverId, channelId)

        val status = store.memberStatusFlow(serverId, "peer-agent").first()
        assertEquals(AgentStatus.Online, status?.status)
        assertTrue(status?.external == true)
    }

    @Test
    fun cachedPeerSliceRefetchesAfterRebaselineButOtherSlicesStayCached() = runBlocking {
        val (registry, repo, store) = fixture(body(peerWorking))
        registry.rebaselineTransport(owner, registry.writeGeneration, transport1)
        store.loadChannelMembers(serverId, channelId)
        store.loadChannelMembers(serverId, channelId)
        assertEquals(1, repo.fetches, "same transport: cache hit")

        registry.rebaselineTransport(owner, registry.writeGeneration, transport2)
        assertNull(registry.factFor(owner, "peer-agent"))
        store.loadChannelMembers(serverId, channelId)

        assertEquals(2, repo.fetches, "rebaselined transport: peer slice refetches")
        assertEquals(AgentStatus.Working, registry.factFor(owner, "peer-agent")?.agentStatus)

        // Twin: a slice without peer agents keeps the cache across a rebaseline.
        val (localRegistry, localRepo, localStore) = fixture(body(localWorking))
        localRegistry.rebaselineTransport(owner, localRegistry.writeGeneration, transport1)
        localStore.loadChannelMembers(serverId, channelId)
        localRegistry.rebaselineTransport(owner, localRegistry.writeGeneration, transport2)
        localStore.loadChannelMembers(serverId, channelId)
        assertEquals(1, localRepo.fetches)
    }

    @Test
    fun reconnectRefreshReloadsOnlyPeerBearingSlices() = runBlocking {
        val registry = ProcessMemberPresenceRegistry()
        val fetchedChannels = mutableListOf<String>()
        val repo =
            object : MemberRepository by PresenceNoopMemberRepository {
                override suspend fun getChannelMembers(
                    priority: DataRequestPriority,
                    channelId: ChannelId
                ): DataResult<ChannelMemberSnapshot> {
                    fetchedChannels += channelId.value
                    val json = if (channelId == this@SharedServerMembersJointPeerAgentPresenceTest.channelId) {
                        body(peerWorking)
                    } else {
                        body(localWorking)
                    }
                    return DataResult.Content(parseSharedChannelMemberSnapshot(json, channelId)!!)
                }
            }
        val store = SharedServerMembersStore(repo, presenceRegistry = registry)
        registry.rebaselineTransport(owner, registry.writeGeneration, transport1)
        store.loadChannelMembers(serverId, channelId)
        store.loadChannelMembers(serverId, ChannelId("local-1"))
        registry.rebaselineTransport(owner, registry.writeGeneration, transport2)
        fetchedChannels.clear()

        store.refreshPeerAgentChannelPresence(serverId, reason = "test_reconnect")

        assertEquals(listOf(channelId.value), fetchedChannels)
        assertEquals(AgentStatus.Working, registry.factFor(owner, "peer-agent")?.agentStatus)
    }

    private fun peerPresence(status: AgentStatus, serverSeq: Long) =
        MemberPresenceUpdated(
            memberId = MemberId("peer-agent"),
            presence = status.presence,
            kind = MemberKind.Agent,
            agentStatus = status,
            statusLabel = if (status == AgentStatus.Online) "Idle now" else status.label,
            serverSeq = serverSeq
        )
}
