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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedRealtimeDomainEventOwnerTest {

    @Test
    fun oneWireEventMutatesProcessOnceAndProjectsToTwoShells() = runBlocking {
        val authority =
            RealtimeAuthorityRegistry(
                authRevisionStore = KeyValueRealtimeAuthRevisionStore(InMemoryRaftKeyValueStore()),
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
            authRevisionStore = KeyValueRealtimeAuthRevisionStore(InMemoryRaftKeyValueStore()),
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
    fun acceptedMessageEventsAdvancePersistedCursorAfterSharedDedupe() {
        val cursorStore = RealtimeAppliedSeqCursorStore(InMemoryRaftKeyValueStore())
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
        val cursorStore = RealtimeAppliedSeqCursorStore(InMemoryRaftKeyValueStore())
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
        val cursorStore = RealtimeAppliedSeqCursorStore(InMemoryRaftKeyValueStore())
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

}
