package build.raft.app.data

import build.raft.app.data.realtime.RealtimeAppliedSeqCursorStore
import build.raft.app.model.ChannelId
import build.raft.app.model.DirectMessageCreated
import build.raft.app.model.Heartbeat
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.Presence
import build.raft.app.model.RealtimeRefreshRequired
import build.raft.app.model.RealtimeRefreshScope
import build.raft.app.model.ServerId
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.TaskDeleted
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.TaskUpdated
import build.raft.app.model.ThreadId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedRealtimeDomainEventOwnerTest {
    @Test
    fun oneWireEventMutatesProcessOnceAndProjectsToTwoShells() = runBlocking {
        val authority =
            RealtimeAuthorityRegistry(
                authRevisionStore = KeyValueRealtimeAuthRevisionStore(InMemorySlockKeyValueStore()),
                processInstanceId = "two-shell-process",
            )
        authority.initialize()
        authority.advanceAuth(
            RealtimeAuthRevision(
                generation = 1L,
                session = RealtimeAuthoritySession("account-1", listOf("server-1"), environmentId = "env-test"),
            )
        )
        val processLease = authority.register("process-realtime-host")
        val shellALease = authority.register("shell-a")
        val shellBLease = authority.register("shell-b")
        val processGrant = requireNotNull(authority.currentGrant(processLease.id, "server-1"))
        val shellAGrant = requireNotNull(authority.currentGrant(shellALease.id, "server-1"))
        val shellBGrant = requireNotNull(authority.currentGrant(shellBLease.id, "server-1"))
        var processMutations = 0
        val processOwner =
            SharedRealtimeDomainEventOwner(runIfCurrentGrant = authority::runIfCurrentGrant).also { owner ->
                owner.installMemberPresenceSink { _, _ -> processMutations += 1 }
            }
        val shellA = SharedRealtimeDomainEventOwner(runIfCurrentGrant = authority::runIfCurrentGrant)
        val shellB = SharedRealtimeDomainEventOwner(runIfCurrentGrant = authority::runIfCurrentGrant)
        val projectedA = mutableListOf<MemberPresenceUpdated>()
        val projectedB = mutableListOf<MemberPresenceUpdated>()
        shellA.registerGuardedSinks(RealtimeGuardedDomainEventSinks(applyAgentActivity = { projectedA += it }))
        shellB.registerGuardedSinks(RealtimeGuardedDomainEventSinks(applyAgentActivity = { projectedB += it }))
        val event =
            MemberPresenceUpdated(
                memberId = MemberId("agent-1"),
                presence = Presence.Active,
                kind = MemberKind.Agent,
                serverSeq = 10L,
            )

        processOwner.acceptProcessMutation(processGrant, event, source = "shell-a-wire")
        processOwner.acceptProcessMutation(processGrant, event, source = "shell-b-wire")
        shellA.accept(shellAGrant, event, source = "shell-a")
        shellB.accept(shellBGrant, event, source = "shell-b")

        assertEquals(1, processMutations)
        assertEquals(listOf(event), projectedA)
        assertEquals(listOf(event), projectedB)
    }

    @Test
    fun separateShellOwnersDoNotCrossDeliverServerEvents() {
        val ownerA = SharedRealtimeDomainEventOwner()
        val ownerB = SharedRealtimeDomainEventOwner()
        val deliveredA = mutableListOf<String>()
        val deliveredB = mutableListOf<String>()
        ownerA.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeShell = { serverId, _ -> deliveredA += serverId }
            )
        )
        ownerB.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeShell = { serverId, _ -> deliveredB += serverId }
            )
        )

        ownerA.accept(
            serverId = "server-a",
            event = RealtimeRefreshRequired(RealtimeRefreshScope.Home, "test", ServerId("server-a"))
        )
        ownerB.accept(
            serverId = "server-b",
            event = RealtimeRefreshRequired(RealtimeRefreshScope.Home, "test", ServerId("server-b"))
        )

        assertEquals(listOf("server-a"), deliveredA)
        assertEquals(listOf("server-b"), deliveredB)
    }

    @Test
    fun appShellActivePagerStartsOpenAndThenFiltersInactivePagers() {
        val owner = AppShellActivePagerOwner()

        assertEquals(true, owner.isActive("pager-a"))
        assertEquals(true, owner.isActive("pager-b"))

        owner.markActive("pager-a")

        assertEquals(true, owner.isActive("pager-a"))
        assertEquals(false, owner.isActive("pager-b"))

        owner.markActive("pager-b")

        assertEquals(false, owner.isActive("pager-a"))
        assertEquals(true, owner.isActive("pager-b"))
    }

    @Test
    fun ignoresBlankServerIdsAndHeartbeats() {
        val owner = SharedRealtimeDomainEventOwner()
        val homeEvents = mutableListOf<RealtimeEventRecord>()
        owner.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeShell = { serverId, event -> homeEvents += RealtimeEventRecord(serverId, event) }
            )
        )

        owner.accept("", DirectMessageCreated(ChannelId("dm-1")))
        owner.accept("server-1", Heartbeat(seq = 10L))

        assertEquals(emptyList(), homeEvents)
    }

    @Test
    fun duplicateAcceptedEventsPatchDomainSinksOnce() {
        val owner = SharedRealtimeDomainEventOwner()
        val homeEvents = mutableListOf<RealtimeEventRecord>()
        val joinedChannels = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val sinks =
            RealtimeDomainEventSinks(
                applyHomeShell = { serverId, event -> homeEvents += RealtimeEventRecord(serverId, event) },
                joinChannel = { channelId -> joinedChannels += channelId },
                log = logs::add
            )
        val event = DirectMessageCreated(ChannelId("dm-1"))
        owner.registerSinks(sinks)

        owner.accept("server-1", event, source = "app_shell")
        owner.accept("server-1", event, source = "app_shell")

        assertEquals(listOf(RealtimeEventRecord("server-1", event)), homeEvents)
        assertEquals(listOf("dm-1"), joinedChannels)
        assertEquals(true, logs.any { it.contains("reason=duplicate") })
    }

    @Test
    fun backgroundUnreadAcceptUsesSharedDedupWithoutTouchingFullDomainSinks() {
        val owner = SharedRealtimeDomainEventOwner()
        val unreadEvents = mutableListOf<RealtimeEventRecord>()
        val homeEvents = mutableListOf<RealtimeEventRecord>()
        val event =
            MessageNew(
                Message(
                    id = MessageId("message-background"),
                    threadId = ThreadId("channel-b"),
                    channelId = ChannelId("channel-b"),
                    seq = 9,
                    author =
                        MemberProfile(
                            id = MemberId("member-2"),
                            displayName = "Cindy",
                            handle = "cindy",
                            kind = MemberKind.Human,
                            presence = Presence.Active
                        ),
                    timestampLabel = "12:00",
                    body = "background"
                )
            )
        owner.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeShell = { serverId, accepted -> homeEvents += RealtimeEventRecord(serverId, accepted) },
                applyHomeUnreadProjection = { serverId, accepted -> unreadEvents += RealtimeEventRecord(serverId, accepted) }
            )
        )

        owner.acceptHomeUnreadProjection("server-2", event, source = "background")
        owner.acceptHomeUnreadProjection("server-2", event, source = "background")

        assertEquals(listOf(RealtimeEventRecord("server-2", event)), unreadEvents)
        assertEquals(emptyList(), homeEvents)
    }

    @Test
    fun agentPresenceUpdatesActivitySinkWithoutRefreshingMemberDetail() {
        val owner = SharedRealtimeDomainEventOwner()
        val activityEvents = mutableListOf<MemberPresenceUpdated>()
        var refreshRequests = 0
        val event =
            MemberPresenceUpdated(
                memberId = MemberId("agent-1"),
                presence = Presence.Active,
                kind = MemberKind.Agent
            )
        owner.registerSinks(
            RealtimeDomainEventSinks(
                applyAgentActivity = activityEvents::add,
                requestMemberDetailRefresh = { refreshRequests += 1 }
            )
        )

        owner.accept("server-1", event, source = "app_shell")

        assertEquals(listOf(event), activityEvents)
        assertEquals(0, refreshRequests)
    }

    @Test
    fun agentPresenceDedupeKeepsPerMemberServerSeqIndependent() {
        val owner = SharedRealtimeDomainEventOwner()
        val activityEvents = mutableListOf<MemberPresenceUpdated>()
        val agentOne =
            MemberPresenceUpdated(
                memberId = MemberId("agent-1"),
                presence = Presence.Active,
                kind = MemberKind.Agent,
                serverSeq = 7L
            )
        val agentTwo =
            MemberPresenceUpdated(
                memberId = MemberId("agent-2"),
                presence = Presence.Active,
                kind = MemberKind.Agent,
                serverSeq = 7L
            )
        owner.registerSinks(
            RealtimeDomainEventSinks(
                applyAgentActivity = activityEvents::add
            )
        )

        owner.accept("server-1", agentOne, source = "app_shell")
        owner.accept("server-1", agentTwo, source = "app_shell")
        owner.accept("server-1", agentTwo, source = "app_shell")

        assertEquals(listOf(agentOne, agentTwo), activityEvents)
    }

    // ---- task #136 presence write-edge barriers (post-merge audit on PR
    // #1054): these drive the REAL SharedRealtimeDomainEventOwner admission
    // pipeline (grant-checked via a REAL RealtimeAuthorityRegistry) into the
    // PRODUCTION processMemberPresenceRealtimeSink — no re-made helper. ----

    private class PresenceHarness {
        val authority = RealtimeAuthorityRegistry(
            authRevisionStore = KeyValueRealtimeAuthRevisionStore(InMemorySlockKeyValueStore()),
            processInstanceId = "presence-harness",
        )
        val presenceRegistry = ProcessMemberPresenceRegistry()
        val presenceAuthContext = MutableStateFlow<ProcessPresenceAuthContext?>(null)
        val owner = SharedRealtimeDomainEventOwner(
            runIfCurrentGrant = authority::runIfCurrentGrant,
        ).also {
            // The exact production write edge the provider installs.
            it.installMemberPresenceSink(
                processMemberPresenceRealtimeSink(
                    currentPresenceAuthContext = { presenceAuthContext.value },
                    registry = presenceRegistry,
                )
            )
        }

        var boundEnvironmentId = "env-1"

        // The PRODUCTION binding transition — the same function the provider
        // installs as the authority hook.
        val bindingTransition =
            processPresenceBindingTransition(
                presenceAuthContext = presenceAuthContext,
                registry = presenceRegistry,
            ) { boundEnvironmentId }

        /** Production ordering: advance the revision, then run the SAME
         *  transition the provider hook runs (binding ready before grants
         *  would publish). */
        suspend fun authenticate(accountId: String, environmentId: String = "env-1") {
            boundEnvironmentId = environmentId
            authority.advanceAuthFromCurrentSession(
                RealtimeAuthoritySession(principalId = accountId, serverIds = listOf("server-1"), environmentId = "env-test"),
                force = true,
            )
            bindingTransition(authority.snapshot.value.authRevision)
        }

        fun signOut() {
            presenceAuthContext.value = null
            presenceRegistry.clearAll(reason = "cleared")
        }

        suspend fun currentGrant(leaseId: String): RealtimeGrantRecord =
            authority.currentGrant(leaseId, "server-1")
                ?: error("no current grant for server-1")
    }

    private fun presenceEvent(seq: Long, presence: Presence = Presence.Active) =
        MemberPresenceUpdated(
            memberId = MemberId("agent-1"),
            presence = presence,
            kind = MemberKind.Agent,
            serverSeq = seq
        )

    private fun presenceOwner(accountId: String) =
        ProcessServerMembersOwner("env-1", accountId, "server-1")

    @Test
    fun installedPresenceSinkReceivesAgentPresenceWithoutAnyPageSinks() = runBlocking {
        // task #136 SSOT lock: the presence intake is installed for the
        // owner's lifetime — agent presence must arrive even when NO page has
        // sinks registered (the page-scoped windows were how machine-offline
        // transitions got lost on the Members surface). Grant-admitted path.
        val harness = PresenceHarness()
        harness.authenticate("account-a")
        val lease = harness.authority.register("shell:test")
        val grant = harness.currentGrant(lease.id)

        assertEquals(true, harness.owner.accept(grant, presenceEvent(seq = 1L), source = "test"))

        assertEquals(
            1L,
            harness.presenceRegistry.factFor(presenceOwner("account-a"), "agent-1")?.serverSeq
        )
    }

    @Test
    fun heldOldAccountDeliveryCannotWriteTheNewAccountPartition() = runBlocking {
        // Barrier: held-A → clear/auth-B → release-A. The released delivery
        // must be rejected at grant admission; B's partition stays untouched
        // and A is not resurrected. A genuine B delivery then succeeds.
        val harness = PresenceHarness()
        harness.authenticate("account-a")
        val lease = harness.authority.register("shell:test")
        val heldGrantA = harness.currentGrant(lease.id)

        assertEquals(true, harness.owner.accept(heldGrantA, presenceEvent(seq = 1L), source = "test"))
        assertEquals(
            Presence.Active,
            harness.presenceRegistry.factFor(presenceOwner("account-a"), "agent-1")?.presence
        )

        harness.authenticate("account-b")

        val heldAccepted =
            harness.owner.accept(heldGrantA, presenceEvent(seq = 9L, Presence.Offline), source = "test")

        assertEquals(false, heldAccepted)
        assertNull(harness.presenceRegistry.factFor(presenceOwner("account-b"), "agent-1"))
        assertNull(harness.presenceRegistry.factFor(presenceOwner("account-a"), "agent-1"))
        assertEquals(emptyMap(), harness.presenceRegistry.factsByOwner)

        val grantB = harness.currentGrant(lease.id)
        assertEquals(true, grantB != heldGrantA)
        assertEquals(true, harness.owner.accept(grantB, presenceEvent(seq = 2L), source = "test"))
        assertEquals(
            Presence.Active,
            harness.presenceRegistry.factFor(presenceOwner("account-b"), "agent-1")?.presence
        )
        assertNull(harness.presenceRegistry.factFor(presenceOwner("account-a"), "agent-1"))
    }

    @Test
    fun samePrincipalRotationRejectsHeldDeliveryAtTheProductionSink() = runBlocking {
        // Post-merge audit round 2 (PR #1075 block): same account X re-auths
        // (authority revision + registry generation advance, environment also
        // changes) while the OLD session's grant is still current — the held
        // delivery passes runIfCurrentGrant, reaches the PRODUCTION sink, and
        // must be rejected there by the authorityGeneration equality (the
        // principal matches, so a principal-only check would let stale
        // old-environment presence into the new binding with the NEW registry
        // generation, which the CAS fence cannot catch).
        val harness = PresenceHarness()
        harness.authenticate("account-x", environmentId = "env-1")
        val lease = harness.authority.register("shell:test")
        val heldGrantX1 = harness.currentGrant(lease.id)

        assertEquals(true, harness.owner.accept(heldGrantX1, presenceEvent(seq = 1L), source = "test"))
        assertEquals(
            1L,
            harness.presenceRegistry.factFor(presenceOwner("account-x"), "agent-1")?.serverSeq
        )

        // Same-principal re-auth on the PRESENCE side only: registry retired
        // and context republished (new authorityGeneration, new environment),
        // but the authority grants have NOT rotated yet — the exact
        // collector-race window.
        val revision = harness.authority.snapshot.value.authRevision
        val rotatedWriteGeneration = harness.presenceRegistry.clearAll(reason = "authority_session_advanced")
        harness.presenceAuthContext.value =
            ProcessPresenceAuthContext(
                environmentId = "env-2",
                accountId = "account-x",
                authorityGeneration = revision.generation + 1L,
                writeGeneration = rotatedWriteGeneration,
            )

        // LiBai's precondition assertion: the old grant is STILL current —
        // acceptance succeeds, so a rejection can only come from the sink.
        val heldAccepted = harness.owner.accept(heldGrantX1, presenceEvent(seq = 7L), source = "test")
        assertEquals(true, heldAccepted)

        // The production sink refused the retired-session delivery: nothing
        // in the old binding, nothing in the new env-2 binding.
        assertEquals(emptyMap(), harness.presenceRegistry.factsByOwner)
        assertNull(
            harness.presenceRegistry.factFor(
                ProcessServerMembersOwner("env-2", "account-x", "server-1"),
                "agent-1"
            )
        )

        // After the authority side catches up (full re-auth transition), the
        // new session's grant writes normally into the new binding.
        harness.authenticate("account-x", environmentId = "env-2")
        val grantX2 = harness.currentGrant(lease.id)
        assertEquals(true, grantX2 != heldGrantX1)
        assertEquals(true, harness.owner.accept(grantX2, presenceEvent(seq = 2L), source = "test"))
        assertEquals(
            2L,
            harness.presenceRegistry.factFor(
                ProcessServerMembersOwner("env-2", "account-x", "server-1"),
                "agent-1"
            )?.serverSeq
        )
    }

    @Test
    fun bothProductionOwnersShareTheSameGuardedWriteEdge() = runBlocking {
        // Provider wiring proof shape: a second owner instance (scoped shell)
        // installed with the SAME production sink function writes through the
        // same admission gate into the same registry — and the same
        // authorityGeneration equality rejects retired-session deliveries on
        // it too.
        val harness = PresenceHarness()
        harness.authenticate("account-x")
        val scopedOwner = SharedRealtimeDomainEventOwner(
            runIfCurrentGrant = harness.authority::runIfCurrentGrant,
        ).also {
            it.installMemberPresenceSink(
                processMemberPresenceRealtimeSink(
                    currentPresenceAuthContext = { harness.presenceAuthContext.value },
                    registry = harness.presenceRegistry,
                )
            )
        }
        val lease = harness.authority.register("shell:scoped")
        val grant = harness.currentGrant(lease.id)

        assertEquals(true, scopedOwner.accept(grant, presenceEvent(seq = 3L), source = "test"))
        assertEquals(
            3L,
            harness.presenceRegistry.factFor(presenceOwner("account-x"), "agent-1")?.serverSeq
        )

        // Presence-side rotation only (old grant still current) — the scoped
        // owner's sink rejects exactly like the legacy one.
        val revision = harness.authority.snapshot.value.authRevision
        val rotated = harness.presenceRegistry.clearAll(reason = "authority_session_advanced")
        harness.presenceAuthContext.value =
            ProcessPresenceAuthContext(
                environmentId = "env-1",
                accountId = "account-x",
                authorityGeneration = revision.generation + 1L,
                writeGeneration = rotated,
            )
        assertEquals(true, scopedOwner.accept(grant, presenceEvent(seq = 8L), source = "test"))
        assertEquals(emptyMap(), harness.presenceRegistry.factsByOwner)
    }

    @Test
    fun admissionPrincipalMismatchDropsThePresenceWrite() = runBlocking {
        // Collector-race window: the presence context already moved to B while
        // the authority registry still holds A's grants current (independent
        // collectors may observe an auth transition in either order). The
        // event passes grant admission but the production sink refuses to
        // attribute an A-admitted delivery to the B binding.
        val harness = PresenceHarness()
        harness.authenticate("account-a")
        val lease = harness.authority.register("shell:test")
        val grantA = harness.currentGrant(lease.id)

        val generation = harness.presenceRegistry.clearAll(reason = "authenticated")
        harness.presenceAuthContext.value =
            ProcessPresenceAuthContext(
                environmentId = "env-1",
                accountId = "account-b",
                authorityGeneration = grantA.authGeneration,
                writeGeneration = generation,
            )

        assertEquals(true, harness.owner.accept(grantA, presenceEvent(seq = 3L), source = "test"))

        assertEquals(emptyMap(), harness.presenceRegistry.factsByOwner)
    }

    @Test
    fun signedOutWindowDropsPresenceWrites() = runBlocking {
        val harness = PresenceHarness()
        harness.authenticate("account-a")
        val lease = harness.authority.register("shell:test")
        val grantA = harness.currentGrant(lease.id)

        harness.signOut()

        assertEquals(true, harness.owner.accept(grantA, presenceEvent(seq = 4L), source = "test"))
        assertEquals(emptyMap(), harness.presenceRegistry.factsByOwner)
    }

    @Test
    fun unguardedLegacyAcceptNeverWritesPresenceButStillFeedsPageSinks() = runBlocking {
        // The legacy string-serverId overload has no grant, hence no admission
        // identity: it must NOT write the presence registry (a held old-account
        // callback released here after an account switch would be attributed
        // to whoever is signed in now). Page-scoped activity sinks still fan out.
        val harness = PresenceHarness()
        harness.authenticate("account-a")
        val pageEvents = mutableListOf<MemberPresenceUpdated>()
        harness.owner.registerSinks(
            RealtimeDomainEventSinks(applyAgentActivity = pageEvents::add)
        )
        val event = presenceEvent(seq = 5L)

        harness.owner.accept("server-1", event, source = "app_shell")

        assertEquals(listOf(event), pageEvents)
        assertEquals(emptyMap(), harness.presenceRegistry.factsByOwner)
    }

    @Test
    fun canonicalFinalSurfaceDeliveryNeverWritesPresence() = runBlocking {
        // The canonical-final surface entry receives gateway-admitted message
        // frames and carries no presence admission; a presence event arriving
        // there must never reach the registry, while guarded page sinks still
        // see it.
        val harness = PresenceHarness()
        harness.authenticate("account-a")
        val pageEvents = mutableListOf<MemberPresenceUpdated>()
        val sinks = RealtimeGuardedDomainEventSinks(applyAgentActivity = { pageEvents += it })
        val event = presenceEvent(seq = 6L)

        harness.owner.deliverGuardedAppShellDomainFinal(
            sinks = sinks,
            serverId = "server-1",
            event = event,
        )

        assertEquals(listOf(event), pageEvents)
        assertEquals(emptyMap(), harness.presenceRegistry.factsByOwner)
    }

    @Test
    fun repeatedAgentRefreshHintsAreNotSwallowedAsDuplicates() {
        // task #136 lock: refresh hints are idempotent triggers with no seq —
        // remembering them in the accepted-key ledger made the SECOND state
        // change for the same agent silently skip its refetch (observed on
        // device: accept_skip reason=duplicate key=refresh:…:Agents:agent:session:…).
        val owner = SharedRealtimeDomainEventOwner()
        val refreshes = mutableListOf<RealtimeRefreshRequired>()
        owner.registerSinks(
            RealtimeDomainEventSinks(
                applyServerMembersRefresh = { _, event -> refreshes += event }
            )
        )
        val hint =
            RealtimeRefreshRequired(
                scope = RealtimeRefreshScope.Agents,
                reason = "agent:session",
                serverId = ServerId("server-1"),
                subjectId = "agent-1"
            )

        owner.accept("server-1", hint, source = "app_shell")
        owner.accept("server-1", hint, source = "app_shell")

        assertEquals(2, refreshes.size)
    }

    @Test
    fun taskRealtimeEventsPatchSharedTaskProjectionStore() = runBlocking {
        val tasksDataStore = SharedTasksDataStore()
        val owner = SharedRealtimeDomainEventOwner(tasksDataStore)
        val task =
            TaskSummary(
                id = TaskId("task-1"),
                title = "Patch task",
                status = TaskStatus.InReview,
                channelId = ChannelId("channel-1"),
                updatedAtLabel = "now"
            )
        tasksDataStore.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Server,
            snapshot = TaskSnapshot(tasks = emptyList(), summaryCounts = emptyMap())
        )

        owner.deliverGuardedAppShellDomainFinal(
            sinks = RealtimeGuardedDomainEventSinks(),
            serverId = "server-1",
            event = TaskUpdated(task)
        )

        val projected =
            tasksDataStore.channelProjectionFlow("server-1", "channel-1", null).first()
        assertEquals(SharedTasksDataStoreStatus.Loaded, projected.status)
        assertEquals(listOf(task), projected.tasks)

        owner.deliverGuardedAppShellDomainFinal(
            sinks = RealtimeGuardedDomainEventSinks(),
            serverId = "server-1",
            event = TaskDeleted(task.id)
        )

        val afterDelete =
            tasksDataStore.channelProjectionFlow("server-1", "channel-1", null).first()
        assertEquals(emptyList(), afterDelete.tasks)
    }

    @Test
    fun hostAndPeerTaskRealtimeKeepBothLocalRoutesAndOneNewestFact() = runBlocking {
        val tasksDataStore = SharedTasksDataStore()
        val owner = SharedRealtimeDomainEventOwner(tasksDataStore)
        val host =
            TaskSummary(
                id = TaskId("task-joint"),
                title = "Joint task",
                status = TaskStatus.Todo,
                channelId = ChannelId("channel-host"),
                messageId = MessageId("message-joint"),
                revision = 8L,
                updatedAtLabel = "now"
            )
        val peer =
            host.copy(
                status = TaskStatus.InReview,
                channelId = ChannelId("channel-peer"),
                revision = 9L
            )

        owner.deliverGuardedAppShellDomainFinal(
            sinks = RealtimeGuardedDomainEventSinks(),
            serverId = "server-host",
            event = TaskUpdated(host)
        )
        owner.deliverGuardedAppShellDomainFinal(
            sinks = RealtimeGuardedDomainEventSinks(),
            serverId = "server-peer",
            event = TaskUpdated(peer)
        )

        val hostProjection = tasksDataStore.projection("server-host", SharedTasksScope.Server).tasks.single()
        val peerProjection = tasksDataStore.projection("server-peer", SharedTasksScope.Server).tasks.single()
        assertEquals(TaskStatus.InReview, hostProjection.status)
        assertEquals(TaskStatus.InReview, peerProjection.status)
        assertEquals(ChannelId("channel-host"), hostProjection.channelId)
        assertEquals(ChannelId("channel-peer"), peerProjection.channelId)
        assertEquals(MessageId("message-joint"), hostProjection.messageId)
        assertEquals(hostProjection.copy(channelId = null), peerProjection.copy(channelId = null))
    }

    @Test
    fun messageEventsUseStableIdentityForDedupe() {
        val owner = SharedRealtimeDomainEventOwner()
        val homeEvents = mutableListOf<RealtimeEventRecord>()
        owner.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeShell = { serverId, event -> homeEvents += RealtimeEventRecord(serverId, event) }
            )
        )
        val event =
            MessageNew(
                Message(
                    id = MessageId("m1"),
                    threadId = ThreadId("channel-1"),
                    channelId = ChannelId("channel-1"),
                    seq = 7L,
                    author = MemberProfile(MemberId("u1"), "Artin", "artin", MemberKind.Human),
                    timestampLabel = "now",
                    body = "hello"
                )
            )

        owner.accept("server-1", event, source = "app_shell")
        owner.accept("server-1", event, source = "app_shell")

        assertEquals(listOf(RealtimeEventRecord("server-1", event)), homeEvents)
    }

    @Test
    fun acceptedMessageEventsAdvancePersistedCursorAfterSharedDedupe() {
        val cursorStore = RealtimeAppliedSeqCursorStore(InMemorySlockKeyValueStore())
        val owner = SharedRealtimeDomainEventOwner(cursorStore = cursorStore)
        val homeEvents = mutableListOf<RealtimeEventRecord>()
        val logs = mutableListOf<String>()
        owner.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeShell = { serverId, event -> homeEvents += RealtimeEventRecord(serverId, event) },
                log = logs::add
            )
        )
        val first = MessageNew(message(id = "m1", seq = 1))
        val second = MessageUpdated(message(id = "m2", seq = 2, body = "edited"))

        owner.accept("server-1", first, source = "app_shell")
        owner.accept("server-1", second, source = "app_shell")
        owner.accept("server-1", second, source = "app_shell")

        assertEquals(
            listOf(
                RealtimeEventRecord("server-1", first),
                RealtimeEventRecord("server-1", second)
            ),
            homeEvents
        )
        assertEquals(2L, cursorStore.readAppliedLastSeq(ServerId("server-1")))
        assertEquals(true, logs.any { it.contains("reason=duplicate") })
    }

    @Test
    fun messageSeqGapRequestsRepairWithoutGatingMainFanout() {
        val cursorStore = RealtimeAppliedSeqCursorStore(InMemorySlockKeyValueStore())
        cursorStore.saveAppliedLastSeq(ServerId("server-1"), 1)
        val owner = SharedRealtimeDomainEventOwner(cursorStore = cursorStore)
        val delivered = mutableListOf<RealtimeEventRecord>()
        val homeEvents = mutableListOf<RealtimeEventRecord>()
        val logs = mutableListOf<String>()
        owner.registerActiveThreadSink { serverId, event ->
            delivered += RealtimeEventRecord(serverId, event)
        }
        owner.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeShell = { serverId, event -> homeEvents += RealtimeEventRecord(serverId, event) },
                log = logs::add
            )
        )
        val gap = MessageNew(message(id = "m3", seq = 3, body = "gap"))
        val repair =
            SyncResumeResponse(
                messages = listOf(
                    message(id = "m2", seq = 2, body = "repair"),
                    message(id = "m3", seq = 3, body = "gap")
                ),
                currentSeq = 3,
                hasMore = false
            )

        owner.accept("server-1", gap, source = "app_shell")
        owner.accept("server-1", repair, source = "app_shell")

        val expected =
            listOf(
                RealtimeEventRecord("server-1", gap),
                RealtimeEventRecord("server-1", repair)
            )
        assertEquals(expected, delivered)
        assertEquals(expected, homeEvents)
        assertEquals(3L, cursorStore.readAppliedLastSeq(ServerId("server-1")))
        assertEquals(true, logs.any { it.contains("reason=message_seq_gap") })
    }

    @Test
    fun sparseRepairBatchAdvancesCursorAndDoesNotLeaveFanoutStuck() {
        val cursorStore = RealtimeAppliedSeqCursorStore(InMemorySlockKeyValueStore())
        cursorStore.saveAppliedLastSeq(ServerId("server-1"), 1)
        val owner = SharedRealtimeDomainEventOwner(cursorStore = cursorStore)
        val homeEvents = mutableListOf<RealtimeEventRecord>()
        val logs = mutableListOf<String>()
        owner.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeShell = { serverId, event -> homeEvents += RealtimeEventRecord(serverId, event) },
                log = logs::add
            )
        )
        val gap = MessageNew(message(id = "m4", seq = 4, body = "gap"))
        val repair =
            SyncResumeResponse(
                messages = listOf(
                    message(id = "m2", seq = 2, body = "visible repair"),
                    message(id = "m4", seq = 4, body = "same visible message")
                ),
                currentSeq = 4,
                hasMore = false
            )
        val next = MessageNew(message(id = "m5", seq = 5, body = "next"))

        owner.accept("server-1", gap, source = "app_shell")
        owner.accept("server-1", repair, source = "app_shell")
        owner.accept("server-1", next, source = "app_shell")

        assertEquals(
            listOf(
                RealtimeEventRecord("server-1", gap),
                RealtimeEventRecord("server-1", repair),
                RealtimeEventRecord("server-1", next)
            ),
            homeEvents
        )
        assertEquals(5L, cursorStore.readAppliedLastSeq(ServerId("server-1")))
        assertEquals(true, logs.any { it.contains("reason=message_seq_gap") })
    }

    @Test
    fun emptySyncResumeAdvancesPersistedCursorAndReachesDomainSinks() {
        val cursorStore = RealtimeAppliedSeqCursorStore(InMemorySlockKeyValueStore())
        cursorStore.saveAppliedLastSeq(ServerId("server-1"), 3)
        val owner = SharedRealtimeDomainEventOwner(cursorStore = cursorStore)
        val homeEvents = mutableListOf<RealtimeEventRecord>()
        owner.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeShell = { serverId, event -> homeEvents += RealtimeEventRecord(serverId, event) }
            )
        )
        val resume = SyncResumeResponse(messages = emptyList(), currentSeq = 5, hasMore = false)

        owner.accept(
            "server-1",
            resume,
            source = "app_shell"
        )

        assertEquals(listOf(RealtimeEventRecord("server-1", resume)), homeEvents)
        assertEquals(5L, cursorStore.readAppliedLastSeq(ServerId("server-1")))
    }

    @Test
    fun activeThreadSinkReceivesDomainEventsAndHeartbeatsButNotBlankServer() {
        val owner = SharedRealtimeDomainEventOwner()
        val delivered = mutableListOf<RealtimeEventRecord>()
        owner.registerActiveThreadSink { serverId, event ->
            delivered += RealtimeEventRecord(serverId, event)
        }
        val dm = DirectMessageCreated(ChannelId("dm-1"))
        val heartbeat = Heartbeat(seq = 5L)

        owner.accept("", dm)
        owner.accept("server-1", dm, source = "app_shell")
        owner.accept("server-1", heartbeat, source = "app_shell")

        assertEquals(
            listOf(
                RealtimeEventRecord("server-1", dm),
                RealtimeEventRecord("server-1", heartbeat)
            ),
            delivered
        )
    }

    @Test
    fun activeThreadSinkSkipsDuplicateEventsViaSharedDedupLedger() {
        val owner = SharedRealtimeDomainEventOwner()
        val delivered = mutableListOf<RealtimeEventRecord>()
        owner.registerActiveThreadSink { serverId, event ->
            delivered += RealtimeEventRecord(serverId, event)
        }
        val dm = DirectMessageCreated(ChannelId("dm-1"))

        owner.accept("server-1", dm, source = "app_shell")
        owner.accept("server-1", dm, source = "thread_route")

        assertEquals(listOf(RealtimeEventRecord("server-1", dm)), delivered)
    }

    @Test
    fun unregisteredActiveThreadSinkStopsReceivingWhileOthersContinue() {
        val owner = SharedRealtimeDomainEventOwner()
        val first = mutableListOf<RealtimeEventRecord>()
        val second = mutableListOf<RealtimeEventRecord>()
        val firstSink =
            RealtimeActiveThreadSink { serverId, event -> first += RealtimeEventRecord(serverId, event) }
        val secondSink =
            RealtimeActiveThreadSink { serverId, event -> second += RealtimeEventRecord(serverId, event) }
        owner.registerActiveThreadSink(firstSink)
        owner.registerActiveThreadSink(secondSink)

        val dmOne = DirectMessageCreated(ChannelId("dm-1"))
        owner.accept("server-1", dmOne)
        owner.unregisterActiveThreadSink(firstSink)
        val dmTwo = DirectMessageCreated(ChannelId("dm-2"))
        owner.accept("server-1", dmTwo)

        assertEquals(listOf(RealtimeEventRecord("server-1", dmOne)), first)
        assertEquals(
            listOf(
                RealtimeEventRecord("server-1", dmOne),
                RealtimeEventRecord("server-1", dmTwo)
            ),
            second
        )
    }

    @Test
    fun activeThreadSinkDeliveryDoesNotRequireRegisteredDomainSinks() {
        val owner = SharedRealtimeDomainEventOwner()
        val delivered = mutableListOf<RealtimeEventRecord>()
        owner.registerActiveThreadSink { serverId, event ->
            delivered += RealtimeEventRecord(serverId, event)
        }

        owner.accept("server-1", Heartbeat(seq = 1L))

        assertEquals(1, delivered.size)
    }

    private data class RealtimeEventRecord(
        val serverId: String,
        val event: Any
    )

    private fun message(
        id: String,
        seq: Long,
        body: String = "hello"
    ): Message =
        Message(
            id = MessageId(id),
            threadId = ThreadId("channel-1"),
            channelId = ChannelId("channel-1"),
            seq = seq,
            author = MemberProfile(MemberId("u1"), "Artin", "artin", MemberKind.Human),
            timestampLabel = "now",
            body = body
        )
    @Test
    fun productApplyReceiptLogsOncePerConsumedDelivery() = runBlocking {
        // task #13 subscription audit: realtime_domain_apply must witness the guarded
        // domain-sink dispatch COMPLETING once — not merely the dedup/seq gate passing.
        // This test asserts against a real consuming sink callback (applyHomeShell),
        // the same sink kind SettingsHomePage wires to SharedHomeShellStore.acceptRealtimeEvent
        // (the home/activity product-store edge). SharedThreadMessageStore itself is fed by
        // Thread routes' activeThreadSinks which run on the same acceptGuarded fan-out.
        val logs = mutableListOf<String>()
        val consumed = mutableListOf<RealtimeEventRecord>()
        val owner = SharedRealtimeDomainEventOwner(
            runIfCurrentGrant = { _, block -> block(); true }
        )
        owner.registerGuardedSinks(
            RealtimeGuardedDomainEventSinks(
                applyHomeShell = { serverId, event -> consumed += RealtimeEventRecord(serverId, event) },
                log = { logs += it },
            )
        )
        val grant = RealtimeGrantRecord(
            slotKey = RealtimeSlotKey("shell-1", "server-1"),
            principalId = "user-1",
            environmentId = "env-test",
            authGeneration = 1L,
            connectionFamilyId = "family-1",
            connectionEpochId = "epoch-1",
        )
        val event =
            MessageNew(
                Message(
                    id = MessageId("msg-apply-1"),
                    threadId = ThreadId("channel-1"),
                    channelId = ChannelId("channel-1"),
                    seq = 42L,
                    author = MemberProfile(MemberId("u1"), "Artin", "artin", MemberKind.Human),
                    timestampLabel = "now",
                    body = "hello",
                )
            )

        owner.accept(grant, event, source = "app_shell")
        owner.accept(grant, event, source = "app_shell")

        // First delivery consumed exactly once by the product sink; redelivery consumed zero more times.
        assertEquals(listOf(RealtimeEventRecord("server-1", event)), consumed)
        val applyLines = logs.filter { it.startsWith("realtime_domain_apply ") }
        assertEquals(1, applyLines.size, "expected one product apply line, got $logs")
        assertEquals(true, applyLines[0].contains("kind=message_new"), applyLines[0])
        assertEquals(true, applyLines[0].contains("id=msg-apply-1"), applyLines[0])
        assertEquals(true, applyLines[0].contains("seq=42"), applyLines[0])
        assertEquals(true, applyLines[0].contains("serverId=server-1"), applyLines[0])
        assertEquals(true, !applyLines[0].contains("hello"), "log must not carry content: ${applyLines[0]}")
        assertEquals(true, logs.any { it.contains("realtime_domain_accept_skip") && it.contains("reason=duplicate") })
    }

    @Test
    fun productApplyReceiptNotWrittenWhenSinkDispatchThrows() = runBlocking {
        // The receipt is post-dispatch: if the consuming sink throws, the apply
        // line must not be written — it is not a pre-gate pass marker.
        val logs = mutableListOf<String>()
        val owner = SharedRealtimeDomainEventOwner(
            runIfCurrentGrant = { _, block -> block(); true }
        )
        owner.registerGuardedSinks(
            RealtimeGuardedDomainEventSinks(
                applyHomeShell = { _, _ -> throw IllegalStateException("sink blew up") },
                log = { logs += it },
            )
        )
        val grant = RealtimeGrantRecord(
            slotKey = RealtimeSlotKey("shell-1", "server-1"),
            principalId = "user-1",
            environmentId = "env-test",
            authGeneration = 1L,
            connectionFamilyId = "family-1",
            connectionEpochId = "epoch-1",
        )
        val event =
            MessageNew(
                Message(
                    id = MessageId("msg-apply-2"),
                    threadId = ThreadId("channel-1"),
                    channelId = ChannelId("channel-1"),
                    seq = 7L,
                    author = MemberProfile(MemberId("u1"), "Artin", "artin", MemberKind.Human),
                    timestampLabel = "now",
                    body = "hello",
                )
            )

        val threw = try {
            owner.accept(grant, event, source = "app_shell")
            false
        } catch (_: IllegalStateException) {
            true
        }

        assertEquals(true, threw, "sink failure must propagate through accept")
        assertEquals(emptyList(), logs.filter { it.startsWith("realtime_domain_apply ") })
    }

}
