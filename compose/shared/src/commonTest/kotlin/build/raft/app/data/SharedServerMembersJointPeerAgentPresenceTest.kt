package build.raft.app.data

import build.raft.app.model.AgentStatus
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.MemberProfile
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
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
    fun guestProjectionWritesNoFact() = runBlocking {
        // Real guest shape (routes/channels.ts): serverId is the VIEWED server for every agent,
        // lifecycle status only, no activity.
        val guestRow =
            """{"id":"peer-agent","serverId":"server-1","name":"peer-agent","displayName":"peer-agent",""" +
                """"avatarUrl":null,"status":"stopped","profileProjection":"channel_summary",""" +
                """"effectiveChannelRole":"member","canChangeChannelRole":false}"""
        val (registry, _, store) = fixture(body(guestRow))
        registry.rebaselineTransport(owner, registry.writeGeneration, transport1)

        assertIs<DataResult.Content<*>>(store.loadChannelMembers(serverId, channelId))

        assertNull(registry.factFor(owner, "peer-agent"))
        assertEquals(neutral, store.memberStatusFlow(serverId, "peer-agent").first())
    }

    @Test
    fun peerRowWithoutActivityWritesNoFact() = runBlocking {
        // Lifecycle "stopped" alone would map to Offline; without live activity it must not write.
        val (registry, _, store) =
            fixture(body(agentJson("peer-agent", "server-2", activity = null, status = "stopped")))
        registry.rebaselineTransport(owner, registry.writeGeneration, transport1)

        store.loadChannelMembers(serverId, channelId)

        assertNull(registry.factFor(owner, "peer-agent"))
        assertEquals(neutral, store.memberStatusFlow(serverId, "peer-agent").first())
    }

    @Test
    fun activePeerAndGuestRowsWithoutActivityStayNeutralRatherThanLikelyOnline() = runBlocking {
        // slock #17017: joint-channel peer agents and guest channel_summary rows without live
        // activity are not on the viewer's local /agents roster, so they stay neutral ("—") rather
        // than guessing "Likely online" from lifecycle status="active".
        val activePeer = agentJson("peer-active", "server-2", activity = null, status = "active")
        val activeGuest =
            """{"id":"guest-active","serverId":"server-1","name":"guest-active","displayName":"guest-active",""" +
                """"avatarUrl":null,"status":"active","profileProjection":"channel_summary",""" +
                """"effectiveChannelRole":"member","canChangeChannelRole":false}"""
        val (registry, _, store) = fixture(body(activePeer, activeGuest))
        registry.rebaselineTransport(owner, registry.writeGeneration, transport1)

        assertIs<DataResult.Content<*>>(store.loadChannelMembers(serverId, channelId))

        assertEquals(neutral, store.memberStatusFlow(serverId, "peer-active").first())
        assertEquals(neutral, store.memberStatusFlow(serverId, "guest-active").first())

        // Positive twin: a local roster agent with lifecycle status="active" still emits
        // likelyOnline = true through memberStatusFlow (scopedMemberProfileResolutionFlow keeps fromRoster = true).
        store.seed(
            MemberSnapshot(
                server = ServerSummary(id = serverId, name = "Raft"),
                members =
                    listOf(
                        MemberProfile(
                            id = MemberId("roster-active"),
                            kind = MemberKind.Agent,
                            handle = "roster-active",
                            displayName = "Roster Active",
                            serverId = serverId,
                            agentLifecycleStatus = "active",
                            agentStatus = null,
                        )
                    )
            )
        )
        assertTrue(store.memberStatusFlow(serverId, "roster-active").first()?.likelyOnline == true)
    }

    @Test
    fun rebaselineDuringRequestSelfHealsOnNextLoad() = runBlocking {
        val (registry, repo, store) = fixture(body(peerWorking))
        registry.rebaselineTransport(owner, registry.writeGeneration, transport1)
        repo.hold = CompletableDeferred()
        val load = async { store.loadChannelMembers(serverId, channelId) }
        repo.started.await()
        registry.rebaselineTransport(owner, registry.writeGeneration, transport2)
        repo.hold!!.complete(Unit)
        load.await()
        assertNull(registry.factFor(owner, "peer-agent"))

        store.loadChannelMembers(serverId, channelId)
        store.loadChannelMembers(serverId, channelId)

        assertEquals(2, repo.fetches, "one refetch, then cached under the current transport")
        assertEquals(AgentStatus.Working, registry.factFor(owner, "peer-agent")?.agentStatus)
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
    fun externalPeerAgentKeepsExternalToken() = runBlocking {
        val (registry, _, store) =
            fixture(body(agentJson("peer-agent", "server-2", activity = "online", external = true)))
        registry.rebaselineTransport(owner, registry.writeGeneration, transport1)

        store.loadChannelMembers(serverId, channelId)

        val status = store.memberStatusFlow(serverId, "peer-agent").first()
        assertEquals(AgentStatus.Online, status?.status)
        assertTrue(status?.external == true)
    }

    /** Two peer-bearing slices; each fetch is logged, the first one can be held. */
    private class TwoSliceRepo(private val bodyFor: (ChannelId) -> String) :
        MemberRepository by PresenceNoopMemberRepository {
        val log = mutableListOf<String>()
        var holdFirst: CompletableDeferred<Unit>? = null
        val firstStarted = CompletableDeferred<Unit>()

        override suspend fun getMembers(
            priority: DataRequestPriority,
            serverId: ServerId
        ): DataResult<MemberSnapshot> {
            log += "roster:start"
            kotlinx.coroutines.yield()
            log += "roster:end"
            return DataResult.Content(
                MemberSnapshot(server = build.raft.app.model.ServerSummary(id = serverId, name = "Raft"), members = emptyList())
            )
        }

        override suspend fun getChannelMembers(
            priority: DataRequestPriority,
            channelId: ChannelId
        ): DataResult<ChannelMemberSnapshot> {
            log += "channel:${channelId.value}"
            val hold = holdFirst
            if (hold != null) {
                // Holds only the next fetch after arming (setup loads are never held).
                holdFirst = null
                firstStarted.complete(Unit)
                hold.await()
            }
            return DataResult.Content(parseSharedChannelMemberSnapshot(bodyFor(channelId), channelId)!!)
        }
    }

    private suspend fun twoPeerSlices(): Triple<ProcessMemberPresenceRegistry, TwoSliceRepo, SharedServerMembersStore> {
        val registry = ProcessMemberPresenceRegistry()
        val repo = TwoSliceRepo { body(peerWorking) }
        val store = SharedServerMembersStore(repo, presenceRegistry = registry)
        registry.rebaselineTransport(owner, registry.writeGeneration, transport1)
        store.loadChannelMembers(serverId, ChannelId("joint-a"))
        store.loadChannelMembers(serverId, ChannelId("joint-b"))
        repo.log.clear()
        return Triple(registry, repo, store)
    }

    private fun TwoSliceRepo.channelFetches(channel: String) = log.count { it == "channel:$channel" }

    @Test
    fun staleTriggerArrivingAfterNewerOneCannotDisplaceTheNewestRepair() = runBlocking {
        val (registry, repo, store) = twoPeerSlices()
        val transport3 = ProcessPresenceTransportEpoch("family-1", 3L)
        val release = CompletableDeferred<Unit>()
        repo.holdFirst = release
        val first = async { store.refreshPeerAgentChannelPresence(serverId, transport1, reason = "run-1") }
        repo.firstStarted.await()
        registry.rebaselineTransport(owner, registry.writeGeneration, transport2)
        registry.rebaselineTransport(owner, registry.writeGeneration, transport3)
        // Connect handlers are concurrent: T3's trigger lands first, T2's after it.
        val later =
            listOf(transport3, transport2).map { transport ->
                async { store.refreshPeerAgentChannelPresence(serverId, transport, reason = "out-of-order") }
            }
        release.complete(Unit)
        first.await()
        later.forEach { it.await() }

        // joint-a: the held T1 fetch + exactly one T3 repair; joint-b: exactly one T3 repair.
        assertEquals(2, repo.channelFetches("joint-a"), repo.log.toString())
        assertEquals(1, repo.channelFetches("joint-b"), repo.log.toString())
        assertEquals(AgentStatus.Working, registry.factFor(owner, "peer-agent")?.agentStatus)
    }

    // --- botiverse/slock #11201: a joint channel's external peer agents (agent:seen + roster lastSeenAt) ---

    /** Real `GET /channels/:id/members` row for an external agent since #11201 (routes/channels.ts). */
    private fun externalPeerJson(lastSeenAt: String?, activity: String? = "offline"): String =
        buildString {
            append("""{"id":"peer-ext","serverId":"server-2","name":"peer-ext","displayName":"peer-ext",""")
            append(""""status":"active","runtime":"external","external":true""")
            append(""","lastSeenAt":${lastSeenAt?.let { "\"$it\"" } ?: "null"}""")
            if (activity != null) append(""","activity":"$activity","activityDetail":""""")
            append("}")
        }

    private fun realtime(event: String, json: String): MemberPresenceUpdated =
        assertIs(RealtimeEventMapper().map(event, json))

    private fun seen(at: String) =
        realtime(RealtimeSocketEvents.AgentSeen, """{"agentId":"peer-ext","lastSeenAt":"$at"}""")

    @Test
    fun externalPeerAgentPresenceFollowsRosterThenAgentSeenPastActivity() = runBlocking {
        val (registry, _, store) = fixture(body(externalPeerJson("2026-10-25T10:00:00.000Z")))
        registry.rebaselineTransport(owner, registry.writeGeneration, transport1)
        store.loadChannelMembers(serverId, channelId)

        val fromRoster = store.memberStatusFlow(serverId, "peer-ext").first()
        assertEquals(true, fromRoster?.external)
        assertEquals("2026-10-25T10:00:00.000Z", fromRoster?.lastSeenAt)

        // agent:activity (seq-carrying) lands first, then agent:seen pushes to the projection room.
        registry.apply(
            owner,
            realtime(RealtimeSocketEvents.AgentActivity, """{"agentId":"peer-ext","activity":"working","detail":"Run","serverSeq":7}"""),
            registry.writeGeneration,
            transport1
        )
        registry.apply(owner, seen("2026-10-25T10:05:00.000Z"), registry.writeGeneration, transport1)

        val live = store.memberStatusFlow(serverId, "peer-ext").first()
        assertEquals("2026-10-25T10:05:00.000Z", live?.lastSeenAt, "agent:seen after agent:activity must land")
        assertEquals(AgentStatus.Working, live?.status, "agent:seen keeps the activity status")
        assertEquals(7L, registry.factFor(owner, "peer-ext")?.serverSeq, "seq watermark survives the merge")

        // Forward only (web applyAgentSeen): an older push changes nothing.
        registry.apply(owner, seen("2026-10-25T10:01:00.000Z"), registry.writeGeneration, transport1)
        assertEquals("2026-10-25T10:05:00.000Z", store.memberStatusFlow(serverId, "peer-ext").first()?.lastSeenAt)
        // The kept watermark still rejects an older agent:activity.
        registry.apply(
            owner,
            realtime(RealtimeSocketEvents.AgentActivity, """{"agentId":"peer-ext","activity":"offline","serverSeq":6}"""),
            registry.writeGeneration,
            transport1
        )
        assertEquals(AgentStatus.Working, registry.factFor(owner, "peer-ext")?.agentStatus)
    }

    @Test
    fun agentSeenWithoutPriorFactStillCreatesOne() {
        val registry = ProcessMemberPresenceRegistry()
        registry.rebaselineTransport(owner, registry.writeGeneration, transport1)

        registry.apply(owner, seen("2026-10-25T10:05:00.000Z"), registry.writeGeneration, transport1)

        val fact = registry.factFor(owner, "peer-ext")
        assertEquals("2026-10-25T10:05:00.000Z", fact?.lastSeenAt)
        assertEquals(Presence.Active, fact?.presence)
        assertNull(fact?.serverSeq)
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
