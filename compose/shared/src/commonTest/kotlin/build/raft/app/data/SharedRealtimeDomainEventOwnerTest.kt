package build.raft.app.data

import build.raft.app.canonical.message.CanonicalMessageRow
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
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ServerId
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.TaskDeleted
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.TaskUpdated
import build.raft.app.model.ThreadId
import build.raft.app.network.sync.FoldPersistFrame
import build.raft.app.network.sync.FoldPersistenceAdapter
import build.raft.app.network.sync.MessageReadStateCheckpoint
import build.raft.app.network.sync.MessageReceiverKind
import build.raft.app.network.sync.RestoredFoldState
import build.raft.app.network.sync.SyncCoreMessageShadowFoldLane
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.SyncIngestOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SharedRealtimeDomainEventOwnerTest {
    @Test
    fun canonicalMissInvalidatesCoverageWithoutBlockingLegacySinks() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val flags =
            SharedServerFeatureFlags(
                api =
                    object : SlockFeatureFlagsApi {
                        override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
                            error("flag lookup is not expected")
                    }
            )
        val adapter =
            object : FoldPersistenceAdapter {
                override suspend fun applyFrame(frame: FoldPersistFrame) = Unit
                override suspend fun restore(scope: SyncScopeKey): RestoredFoldState =
                    RestoredFoldState(facts = emptyList(), cursor = null)
            }
        val lane = SyncCoreMessageShadowFoldLane(flags, adapter, scope, logger = {})
        val owner = SharedRealtimeDomainEventOwner().also { it.installMessageShadowFoldLane(lane) }
        var legacyDeliveries = 0
        owner.registerSinks(RealtimeDomainEventSinks(applyHomeShell = { _, _ -> legacyDeliveries += 1 }))
        val token = owner.beginAuthoritativeRebaseline("server-1", "user-1", "channel-1")!!
        owner.completeAuthoritativeRebaseline(
            token = token,
            memberId = "user-1",
            receiverKind = MessageReceiverKind.User,
            rows = listOf(canonicalRow("m-10", 10)),
            coveredAfterSeq = 9,
            coveredFromSeq = 10,
            coveredThroughSeq = 10,
            remoteHighWaterSeq = 10,
            hasGap = false,
            hasNewer = false,
        )
        val checkpoint = MessageReadStateCheckpoint(readStateVersion = 1, maxReadSeq = 10)
        assertTrue(owner.projectReadState("server-1", "user-1", MessageReceiverKind.User, "channel-1", checkpoint).complete)

        owner.accept(
            "server-1",
            MessageNew(message("m-11", 11), decodedCanonical = null),
            memberId = "user-1",
        )

        assertEquals(1, legacyDeliveries)
        assertFalse(owner.projectReadState("server-1", "user-1", MessageReceiverKind.User, "channel-1", checkpoint).complete)
        scope.cancel()
    }

    @Test
    fun missingRealtimePrincipalInvalidatesCurrentOwnerCoverageWithoutGuessingAttribution() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val lane = SyncCoreMessageShadowFoldLane(flags(), RecordingAdapter(), scope, logger = {})
        val owner = SharedRealtimeDomainEventOwner().also { it.installMessageShadowFoldLane(lane) }
        owner.rotateOwner("server-1", "user-1")
        completeCoverage(owner, "channel-1", canonicalRow("m-10", 10))
        val checkpoint = MessageReadStateCheckpoint(readStateVersion = 1, maxReadSeq = 10)
        assertTrue(owner.projectReadState("server-1", "user-1", MessageReceiverKind.User, "channel-1", checkpoint).complete)

        owner.accept(
            "server-1",
            MessageNew(message("m-11", 11), decodedCanonical = null),
            ownerToken = null,
            source = "test",
        )

        assertFalse(owner.projectReadState("server-1", "user-1", MessageReceiverKind.User, "channel-1", checkpoint).complete)

        completeCoverage(owner, "channel-1", canonicalRow("m-10", 10))
        assertTrue(owner.projectReadState("server-1", "user-1", MessageReceiverKind.User, "channel-1", checkpoint).complete)
        owner.accept(
            "server-1",
            MessageNew(message("m-11", 11), decodedCanonical = null),
            ownerToken = null,
            source = "test_duplicate",
        )

        assertFalse(owner.projectReadState("server-1", "user-1", MessageReceiverKind.User, "channel-1", checkpoint).complete)
        scope.cancel()
    }

    @Test
    fun ownerRotationClearsDedupAndRejectsLatePriorGenerationBeforeFanout() {
        val owner = SharedRealtimeDomainEventOwner()
        val delivered = mutableListOf<RealtimeEvent>()
        owner.registerSinks(
            RealtimeDomainEventSinks(applyHomeShell = { _, event -> delivered += event })
        )
        val repeated = MessageNew(message("same-key", 11))
        val lateUnique = MessageNew(message("late-a", 12))
        val tokenA = owner.rotateOwner("server-1", "user-a")

        owner.accept("server-1", repeated, ownerToken = tokenA, source = "test")
        val tokenB = owner.rotateOwner("server-1", "user-b")
        owner.accept("server-1", repeated, ownerToken = tokenB, source = "test")
        owner.accept("server-1", lateUnique, ownerToken = tokenA, source = "test")

        assertEquals(listOf<RealtimeEvent>(repeated, repeated), delivered)
    }

    @Test
    fun ownerRotationAfterInitialValidationBlocksEveryStatefulBoundaryAndAllowsNewOwnerSameKey() = runBlocking {
        val cursorStore = RealtimeAppliedSeqCursorStore(InMemorySlockKeyValueStore())
        val tasksDataStore = SharedTasksDataStore()
        tasksDataStore.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Server,
            snapshot = TaskSnapshot(tasks = emptyList(), summaryCounts = emptyMap()),
        )
        var beforeSideEffects: () -> Unit = {}
        lateinit var owner: SharedRealtimeDomainEventOwner
        owner =
            SharedRealtimeDomainEventOwner(
                tasksDataStore = tasksDataStore,
                cursorStore = cursorStore,
                beforeOwnerSideEffects = { beforeSideEffects() },
            )
        val threadEvents = mutableListOf<RealtimeEvent>()
        val homeEvents = mutableListOf<RealtimeEvent>()
        owner.registerActiveThreadSink { _, _, event -> threadEvents += event }
        owner.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeShell = { _, event -> homeEvents += event },
            )
        )
        val repeated = MessageNew(message("same-key", 11))
        val tokenA = owner.rotateOwner("server-1", "user-a")
        lateinit var tokenB: RealtimeIngressOwnerToken
        beforeSideEffects = {
            beforeSideEffects = {}
            tokenB = owner.rotateOwner("server-1", "user-b")
        }

        owner.accept("server-1", repeated, ownerToken = tokenA, source = "test_late_a")

        assertEquals(0L, cursorStore.readAppliedLastSeq(ServerId("server-1")))
        assertEquals(emptyList(), threadEvents)
        assertEquals(emptyList(), homeEvents)

        owner.accept("server-1", repeated, ownerToken = tokenB, source = "test_current_b")

        assertEquals(11L, cursorStore.readAppliedLastSeq(ServerId("server-1")))
        assertEquals(listOf<RealtimeEvent>(repeated), threadEvents)
        assertEquals(listOf<RealtimeEvent>(repeated), homeEvents)

        val task =
            TaskSummary(
                id = TaskId("task-race"),
                title = "Must not cross owner boundary",
                status = TaskStatus.InReview,
                channelId = ChannelId("channel-1"),
                updatedAtLabel = "now",
            )
        val tokenC = owner.rotateOwner("server-1", "user-c")
        beforeSideEffects = {
            beforeSideEffects = {}
            owner.rotateOwner("server-1", "user-d")
        }

        owner.accept("server-1", TaskUpdated(task), ownerToken = tokenC, source = "test_late_task")

        assertEquals(
            emptyList(),
            tasksDataStore.projection("server-1", SharedTasksScope.Server).tasks,
        )
        assertEquals(listOf<RealtimeEvent>(repeated), threadEvents)
        assertEquals(listOf<RealtimeEvent>(repeated), homeEvents)
    }

    @Test
    fun queuedThreadConsumerRechecksOwnerAtActualMutationTime() {
        val owner = SharedRealtimeDomainEventOwner()
        val queued = mutableListOf<Pair<RealtimeIngressOwnerToken?, RealtimeEvent>>()
        owner.registerActiveThreadSink { token, _, event -> queued += token to event }
        val eventA = DirectMessageCreated(ChannelId("dm-a"))
        val tokenA = owner.rotateOwner("server-1", "user-a")

        owner.accept("server-1", eventA, ownerToken = tokenA, source = "test_a")
        val tokenB = owner.rotateOwner("server-1", "user-b")

        var applied = 0
        queued.forEach { (token, _) ->
            owner.runIfCurrentOwnerToken(token) { applied += 1 }
        }
        assertEquals(0, applied)

        val eventB = DirectMessageCreated(ChannelId("dm-b"))
        owner.accept("server-1", eventB, ownerToken = tokenB, source = "test_b")
        owner.runIfCurrentOwnerToken(queued.last().first) { applied += 1 }

        assertEquals(1, applied)
    }

    @Test
    fun samePrincipalReconnectGenerationRejectsOldFrameAndLateSnapshot() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val lane = SyncCoreMessageShadowFoldLane(flags(), RecordingAdapter(), scope, logger = {})
        val owner = SharedRealtimeDomainEventOwner().also { it.installMessageShadowFoldLane(lane) }
        val token1 = owner.rotateOwner("server-1", "user-1")
        val first = owner.beginAuthoritativeRebaseline("server-1", "user-1", "channel-1")!!
        owner.completeAuthoritativeRebaseline(
            token = first,
            memberId = "user-1",
            receiverKind = MessageReceiverKind.User,
            rows = listOf(canonicalRow("m-10", 10)),
            coveredAfterSeq = 9,
            coveredFromSeq = 10,
            coveredThroughSeq = 10,
            remoteHighWaterSeq = 10,
            hasGap = false,
            hasNewer = false,
        )
        val lateSnapshot = owner.beginAuthoritativeRebaseline("server-1", "user-1", "channel-1")!!
        val token2 = owner.rotateOwner("server-1", "user-1")

        owner.accept(
            "server-1",
            MessageNew(message("old-connection", 11), decodedCanonical = null),
            ownerToken = token1,
            source = "test",
        )
        assertIs<SyncIngestOutcome.DuplicateDropped>(
            owner.completeAuthoritativeRebaseline(
                token = lateSnapshot,
                memberId = "user-1",
                receiverKind = MessageReceiverKind.User,
                rows = listOf(canonicalRow("m-10", 10), canonicalRow("late-snapshot", 12)),
                coveredAfterSeq = 9,
                coveredFromSeq = 10,
                coveredThroughSeq = 12,
                remoteHighWaterSeq = 12,
                hasGap = false,
                hasNewer = false,
            )
        )

        val current = owner.beginAuthoritativeRebaseline("server-1", "user-1", "channel-1")!!
        assertEquals(token2.generation, current.ownerGeneration)
        owner.completeAuthoritativeRebaseline(
            token = current,
            memberId = "user-1",
            receiverKind = MessageReceiverKind.User,
            rows = listOf(canonicalRow("m-10", 10)),
            coveredAfterSeq = 9,
            coveredFromSeq = 10,
            coveredThroughSeq = 10,
            remoteHighWaterSeq = 10,
            hasGap = false,
            hasNewer = false,
        )
        val projection =
            owner.projectReadState(
                "server-1",
                "user-1",
                MessageReceiverKind.User,
                "channel-1",
                MessageReadStateCheckpoint(readStateVersion = 2, maxReadSeq = 9),
            )
        assertTrue(projection.complete)
        assertEquals(1, projection.unreadCount)
        scope.cancel()
    }

    @Test
    fun resumeBatchInvalidatesAffectedCoverageWithoutBlockingLegacyFanout() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val lane = SyncCoreMessageShadowFoldLane(flags(), RecordingAdapter(), scope, logger = {})
        val owner = SharedRealtimeDomainEventOwner().also { it.installMessageShadowFoldLane(lane) }
        var legacyDeliveries = 0
        owner.registerSinks(RealtimeDomainEventSinks(applyHomeShell = { _, _ -> legacyDeliveries += 1 }))
        completeCoverage(owner, "channel-1", canonicalRow("m-10", 10))
        val checkpoint = MessageReadStateCheckpoint(readStateVersion = 1, maxReadSeq = 10)
        assertTrue(owner.projectReadState("server-1", "user-1", MessageReceiverKind.User, "channel-1", checkpoint).complete)

        owner.accept(
            "server-1",
            SyncResumeResponse(
                messages = listOf(message("m-11", 11), message("m-12", 12)),
                currentSeq = 12,
                hasMore = false,
            ),
            memberId = "user-1",
        )

        assertEquals(1, legacyDeliveries)
        assertFalse(owner.projectReadState("server-1", "user-1", MessageReceiverKind.User, "channel-1", checkpoint).complete)
        scope.cancel()
    }

    @Test
    fun mixedUnscopedResumeInvalidatesAllOwnerCoverageWhileEmptyResumePreservesIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val lane = SyncCoreMessageShadowFoldLane(flags(), RecordingAdapter(), scope, logger = {})
        val owner = SharedRealtimeDomainEventOwner().also { it.installMessageShadowFoldLane(lane) }
        completeCoverage(owner, "channel-1", canonicalRow("m-10", 10))
        completeCoverage(owner, "channel-2", canonicalRow("m-20", 20, channelId = "channel-2"))
        val checkpoint1 = MessageReadStateCheckpoint(readStateVersion = 1, maxReadSeq = 10)
        val checkpoint2 = MessageReadStateCheckpoint(readStateVersion = 1, maxReadSeq = 20)

        owner.accept(
            "server-1",
            SyncResumeResponse(messages = emptyList(), currentSeq = 20, hasMore = false),
            memberId = "user-1",
        )
        assertTrue(owner.projectReadState("server-1", "user-1", MessageReceiverKind.User, "channel-1", checkpoint1).complete)
        assertTrue(owner.projectReadState("server-1", "user-1", MessageReceiverKind.User, "channel-2", checkpoint2).complete)

        owner.accept(
            "server-1",
            SyncResumeResponse(
                messages = listOf(message("m-11", 11), message("unknown", 21).copy(channelId = null)),
                currentSeq = 21,
                hasMore = false,
            ),
            memberId = "user-1",
        )

        assertFalse(owner.projectReadState("server-1", "user-1", MessageReceiverKind.User, "channel-1", checkpoint1).complete)
        assertFalse(owner.projectReadState("server-1", "user-1", MessageReceiverKind.User, "channel-2", checkpoint2).complete)
        scope.cancel()
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
        val settingsEvents = mutableListOf<RealtimeEventRecord>()
        owner.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeShell = { serverId, event -> homeEvents += RealtimeEventRecord(serverId, event) },
                applySettings = { serverId, event -> settingsEvents += RealtimeEventRecord(serverId, event) }
            )
        )

        owner.accept("", DirectMessageCreated(ChannelId("dm-1")))
        owner.accept("server-1", Heartbeat(seq = 10L))

        assertEquals(emptyList(), homeEvents)
        assertEquals(emptyList(), settingsEvents)
    }

    @Test
    fun duplicateAcceptedEventsPatchDomainSinksOnce() {
        val owner = SharedRealtimeDomainEventOwner()
        val homeEvents = mutableListOf<RealtimeEventRecord>()
        val settingsEvents = mutableListOf<RealtimeEventRecord>()
        val joinedChannels = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val sinks =
            RealtimeDomainEventSinks(
                applyHomeShell = { serverId, event -> homeEvents += RealtimeEventRecord(serverId, event) },
                applySettings = { serverId, event -> settingsEvents += RealtimeEventRecord(serverId, event) },
                joinChannel = { channelId -> joinedChannels += channelId },
                log = logs::add
            )
        val event = DirectMessageCreated(ChannelId("dm-1"))
        owner.registerSinks(sinks)

        owner.accept("server-1", event, source = "app_shell")
        owner.accept("server-1", event, source = "app_shell")

        assertEquals(listOf(RealtimeEventRecord("server-1", event)), homeEvents)
        assertEquals(listOf(RealtimeEventRecord("server-1", event)), settingsEvents)
        assertEquals(listOf("dm-1"), joinedChannels)
        assertEquals(true, logs.any { it.contains("reason=duplicate") })
    }

    @Test
    fun backgroundUnreadAcceptUsesSharedDedupWithoutTouchingFullDomainSinks() {
        val owner = SharedRealtimeDomainEventOwner()
        val unreadEvents = mutableListOf<RealtimeEventRecord>()
        val homeEvents = mutableListOf<RealtimeEventRecord>()
        val settingsEvents = mutableListOf<RealtimeEventRecord>()
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
                applyHomeUnreadProjection = { serverId, accepted -> unreadEvents += RealtimeEventRecord(serverId, accepted) },
                applySettings = { serverId, accepted -> settingsEvents += RealtimeEventRecord(serverId, accepted) }
            )
        )

        owner.acceptHomeUnreadProjection("server-2", event, source = "background")
        owner.acceptHomeUnreadProjection("server-2", event, source = "background")

        assertEquals(listOf(RealtimeEventRecord("server-2", event)), unreadEvents)
        assertEquals(emptyList(), homeEvents)
        assertEquals(emptyList(), settingsEvents)
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

        owner.accept("server-1", TaskUpdated(task), source = "app_shell")

        val projected =
            tasksDataStore.channelProjectionFlow("server-1", "channel-1", null).first()
        assertEquals(SharedTasksDataStoreStatus.Loaded, projected.status)
        assertEquals(listOf(task), projected.tasks)

        owner.accept("server-1", TaskDeleted(task.id), source = "app_shell")

        val afterDelete =
            tasksDataStore.channelProjectionFlow("server-1", "channel-1", null).first()
        assertEquals(emptyList(), afterDelete.tasks)
    }

    @Test
    fun messageEventsUseStableIdentityForDedupe() {
        val owner = SharedRealtimeDomainEventOwner()
        val homeEvents = mutableListOf<RealtimeEventRecord>()
        val settingsEvents = mutableListOf<RealtimeEventRecord>()
        owner.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeShell = { serverId, event -> homeEvents += RealtimeEventRecord(serverId, event) },
                applySettings = { serverId, event -> settingsEvents += RealtimeEventRecord(serverId, event) }
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
        assertEquals(listOf(RealtimeEventRecord("server-1", event)), settingsEvents)
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
        owner.registerActiveThreadSink { _, serverId, event ->
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
        owner.registerActiveThreadSink { _, serverId, event ->
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
        owner.registerActiveThreadSink { _, serverId, event ->
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
            RealtimeActiveThreadSink { _, serverId, event -> first += RealtimeEventRecord(serverId, event) }
        val secondSink =
            RealtimeActiveThreadSink { _, serverId, event -> second += RealtimeEventRecord(serverId, event) }
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
        owner.registerActiveThreadSink { _, serverId, event ->
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

    private fun completeCoverage(
        owner: SharedRealtimeDomainEventOwner,
        scopeId: String,
        row: CanonicalMessageRow,
    ) {
        val token = owner.beginAuthoritativeRebaseline("server-1", "user-1", scopeId)!!
        owner.completeAuthoritativeRebaseline(
            token = token,
            memberId = "user-1",
            receiverKind = MessageReceiverKind.User,
            rows = listOf(row),
            coveredAfterSeq = row.seq - 1,
            coveredFromSeq = row.seq,
            coveredThroughSeq = row.seq,
            remoteHighWaterSeq = row.seq,
            hasGap = false,
            hasNewer = false,
        )
    }

    private fun flags(): SharedServerFeatureFlags =
        SharedServerFeatureFlags(
            api =
                object : SlockFeatureFlagsApi {
                    override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
                        error("flag lookup is not expected")
                }
        )

    private class RecordingAdapter : FoldPersistenceAdapter {
        override suspend fun applyFrame(frame: FoldPersistFrame) = Unit

        override suspend fun restore(scope: SyncScopeKey): RestoredFoldState =
            RestoredFoldState(facts = emptyList(), cursor = null)
    }

    private fun canonicalRow(id: String, seq: Long, channelId: String = "channel-1"): CanonicalMessageRow =
        CanonicalMessageRow(
            id = id,
            channelId = channelId,
            seq = seq,
            senderType = "user",
            senderId = "u1",
            senderName = "Artin",
            content = "message $seq",
            messageType = "chat",
            createdAt = "2026-07-14T00:00:00Z",
        )
}
