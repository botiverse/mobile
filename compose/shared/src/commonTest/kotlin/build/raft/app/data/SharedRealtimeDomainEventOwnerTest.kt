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
import build.raft.app.model.RealtimeEvent
import build.raft.app.model.ServerId
import build.raft.app.model.SyncResumeResponse
import build.raft.app.model.TaskDeleted
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.TaskUpdated
import build.raft.app.model.ThreadId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class SharedRealtimeDomainEventOwnerTest {
    private val testOwners = mutableMapOf<SharedRealtimeDomainEventOwner, RealtimeOwnerEpoch>()

    @Test
    fun separateShellOwnersDoNotCrossDeliverServerEvents() {
        val ownerA = testRealtimeDomainEventOwner()
        val ownerB = testRealtimeDomainEventOwner()
        val deliveredA = mutableListOf<String>()
        val deliveredB = mutableListOf<String>()
        ownerA.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeShell = { envelope -> deliveredA += envelope.owner.serverId }
            )
        )
        ownerB.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeShell = { envelope -> deliveredB += envelope.owner.serverId }
            )
        )

        val tokenA = ownerA.rotateOwner("server-a", "member-a")
        val tokenB = ownerB.rotateOwner("server-b", "member-b")
        ownerA.accept(
            ownedRealtimeEvent(
                RealtimeRefreshRequired(RealtimeRefreshScope.Home, "test", ServerId("server-a")),
                tokenA,
            )
        )
        ownerB.accept(
            ownedRealtimeEvent(
                RealtimeRefreshRequired(RealtimeRefreshScope.Home, "test", ServerId("server-b")),
                tokenB,
            )
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
        val owner = testRealtimeDomainEventOwner()
        val homeEvents = mutableListOf<RealtimeEventRecord>()
        val settingsEvents = mutableListOf<RealtimeEventRecord>()
        owner.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeShell = { envelope -> homeEvents += RealtimeEventRecord(envelope.owner.serverId, envelope.payload) },
                applySettings = { envelope -> settingsEvents += RealtimeEventRecord(envelope.owner.serverId, envelope.payload) }
            )
        )

        owner.acceptOwned("", DirectMessageCreated(ChannelId("dm-1")))
        owner.acceptOwned("server-1", Heartbeat(seq = 10L))

        assertEquals(emptyList(), homeEvents)
        assertEquals(emptyList(), settingsEvents)
    }

    @Test
    fun duplicateAcceptedEventsPatchDomainSinksOnce() {
        val owner = testRealtimeDomainEventOwner()
        val homeEvents = mutableListOf<RealtimeEventRecord>()
        val settingsEvents = mutableListOf<RealtimeEventRecord>()
        val joinedChannels = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val sinks =
            RealtimeDomainEventSinks(
                applyHomeShell = { envelope -> homeEvents += RealtimeEventRecord(envelope.owner.serverId, envelope.payload) },
                applySettings = { envelope -> settingsEvents += RealtimeEventRecord(envelope.owner.serverId, envelope.payload) },
                joinChannel = { channelId -> joinedChannels += channelId },
                log = logs::add
            )
        val event = DirectMessageCreated(ChannelId("dm-1"))
        owner.registerSinks(sinks)

        owner.acceptOwned("server-1", event, source = "app_shell")
        owner.acceptOwned("server-1", event, source = "app_shell")

        assertEquals(listOf(RealtimeEventRecord("server-1", event)), homeEvents)
        assertEquals(listOf(RealtimeEventRecord("server-1", event)), settingsEvents)
        assertEquals(listOf("dm-1"), joinedChannels)
        assertEquals(true, logs.any { it.contains("reason=duplicate") })
    }

    @Test
    fun backgroundUnreadAcceptUsesSharedDedupWithoutTouchingFullDomainSinks() {
        val owner = testRealtimeDomainEventOwner()
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
                applyHomeShell = { envelope -> homeEvents += RealtimeEventRecord(envelope.owner.serverId, envelope.payload) },
                applyHomeUnreadProjection = { envelope -> unreadEvents += RealtimeEventRecord(envelope.owner.serverId, envelope.payload) },
                applySettings = { envelope -> settingsEvents += RealtimeEventRecord(envelope.owner.serverId, envelope.payload) }
            )
        )

        val ownerToken = owner.rotateOwner("server-2", "member-1")
        owner.acceptHomeUnreadProjection(ownedRealtimeEvent(event, ownerToken), source = "background")
        owner.acceptHomeUnreadProjection(ownedRealtimeEvent(event, ownerToken), source = "background")

        assertEquals(listOf(RealtimeEventRecord("server-2", event)), unreadEvents)
        assertEquals(emptyList(), homeEvents)
        assertEquals(emptyList(), settingsEvents)
    }

    @Test
    fun agentPresenceUpdatesActivitySinkWithoutRefreshingMemberDetail() {
        val owner = testRealtimeDomainEventOwner()
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

        owner.acceptOwned("server-1", event, source = "app_shell")

        assertEquals(listOf(event), activityEvents)
        assertEquals(0, refreshRequests)
    }

    @Test
    fun agentPresenceDedupeKeepsPerMemberServerSeqIndependent() {
        val owner = testRealtimeDomainEventOwner()
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

        owner.acceptOwned("server-1", agentOne, source = "app_shell")
        owner.acceptOwned("server-1", agentTwo, source = "app_shell")
        owner.acceptOwned("server-1", agentTwo, source = "app_shell")

        assertEquals(listOf(agentOne, agentTwo), activityEvents)
    }

    @Test
    fun taskRealtimeEventsPatchSharedTaskProjectionStore() = runBlocking {
        val tasksDataStore = SharedTasksDataStore()
        val owner = testRealtimeDomainEventOwner(tasksDataStore)
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

        owner.acceptOwned("server-1", TaskUpdated(task), source = "app_shell")

        val projected =
            tasksDataStore.channelProjectionFlow("server-1", "channel-1", null).first()
        assertEquals(SharedTasksDataStoreStatus.Loaded, projected.status)
        assertEquals(listOf(task), projected.tasks)

        owner.acceptOwned("server-1", TaskDeleted(task.id), source = "app_shell")

        val afterDelete =
            tasksDataStore.channelProjectionFlow("server-1", "channel-1", null).first()
        assertEquals(emptyList(), afterDelete.tasks)
    }

    @Test
    fun messageEventsUseStableIdentityForDedupe() {
        val owner = testRealtimeDomainEventOwner()
        val homeEvents = mutableListOf<RealtimeEventRecord>()
        val settingsEvents = mutableListOf<RealtimeEventRecord>()
        owner.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeShell = { envelope -> homeEvents += RealtimeEventRecord(envelope.owner.serverId, envelope.payload) },
                applySettings = { envelope -> settingsEvents += RealtimeEventRecord(envelope.owner.serverId, envelope.payload) }
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

        owner.acceptOwned("server-1", event, source = "app_shell")
        owner.acceptOwned("server-1", event, source = "app_shell")

        assertEquals(listOf(RealtimeEventRecord("server-1", event)), homeEvents)
        assertEquals(listOf(RealtimeEventRecord("server-1", event)), settingsEvents)
    }

    @Test
    fun acceptedMessageEventsAdvancePersistedCursorAfterSharedDedupe() {
        val cursorStore = RealtimeAppliedSeqCursorStore(InMemorySlockKeyValueStore())
        val owner = testRealtimeDomainEventOwner(cursorStore = cursorStore)
        val homeEvents = mutableListOf<RealtimeEventRecord>()
        val logs = mutableListOf<String>()
        owner.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeShell = { envelope -> homeEvents += RealtimeEventRecord(envelope.owner.serverId, envelope.payload) },
                log = logs::add
            )
        )
        val first = MessageNew(message(id = "m1", seq = 1))
        val second = MessageUpdated(message(id = "m2", seq = 2, body = "edited"))

        owner.acceptOwned("server-1", first, source = "app_shell")
        owner.acceptOwned("server-1", second, source = "app_shell")
        owner.acceptOwned("server-1", second, source = "app_shell")

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
        val owner = testRealtimeDomainEventOwner(cursorStore = cursorStore)
        val delivered = mutableListOf<RealtimeEventRecord>()
        val homeEvents = mutableListOf<RealtimeEventRecord>()
        val logs = mutableListOf<String>()
        owner.registerActiveThreadSink { envelope ->
            delivered += RealtimeEventRecord(envelope.owner.serverId, envelope.payload)
        }
        owner.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeShell = { envelope -> homeEvents += RealtimeEventRecord(envelope.owner.serverId, envelope.payload) },
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

        owner.acceptOwned("server-1", gap, source = "app_shell")
        owner.acceptOwned("server-1", repair, source = "app_shell")

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
        val owner = testRealtimeDomainEventOwner(cursorStore = cursorStore)
        val homeEvents = mutableListOf<RealtimeEventRecord>()
        val logs = mutableListOf<String>()
        owner.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeShell = { envelope -> homeEvents += RealtimeEventRecord(envelope.owner.serverId, envelope.payload) },
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

        owner.acceptOwned("server-1", gap, source = "app_shell")
        owner.acceptOwned("server-1", repair, source = "app_shell")
        owner.acceptOwned("server-1", next, source = "app_shell")

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
        val owner = testRealtimeDomainEventOwner(cursorStore = cursorStore)
        val homeEvents = mutableListOf<RealtimeEventRecord>()
        owner.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeShell = { envelope -> homeEvents += RealtimeEventRecord(envelope.owner.serverId, envelope.payload) }
            )
        )
        val resume = SyncResumeResponse(messages = emptyList(), currentSeq = 5, hasMore = false)

        owner.acceptOwned(
            "server-1",
            resume,
            source = "app_shell"
        )

        assertEquals(listOf(RealtimeEventRecord("server-1", resume)), homeEvents)
        assertEquals(5L, cursorStore.readAppliedLastSeq(ServerId("server-1")))
    }

    @Test
    fun activeThreadSinkReceivesDomainEventsAndHeartbeatsButNotBlankServer() {
        val owner = testRealtimeDomainEventOwner()
        val delivered = mutableListOf<RealtimeEventRecord>()
        owner.registerActiveThreadSink { envelope ->
            delivered += RealtimeEventRecord(envelope.owner.serverId, envelope.payload)
        }
        val dm = DirectMessageCreated(ChannelId("dm-1"))
        val heartbeat = Heartbeat(seq = 5L)

        owner.acceptOwned("", dm)
        owner.acceptOwned("server-1", dm, source = "app_shell")
        owner.acceptOwned("server-1", heartbeat, source = "app_shell")

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
        val owner = testRealtimeDomainEventOwner()
        val delivered = mutableListOf<RealtimeEventRecord>()
        owner.registerActiveThreadSink { envelope ->
            delivered += RealtimeEventRecord(envelope.owner.serverId, envelope.payload)
        }
        val dm = DirectMessageCreated(ChannelId("dm-1"))

        owner.acceptOwned("server-1", dm, source = "app_shell")
        owner.acceptOwned("server-1", dm, source = "thread_route")

        assertEquals(listOf(RealtimeEventRecord("server-1", dm)), delivered)
    }

    @Test
    fun unregisteredActiveThreadSinkStopsReceivingWhileOthersContinue() {
        val owner = testRealtimeDomainEventOwner()
        val first = mutableListOf<RealtimeEventRecord>()
        val second = mutableListOf<RealtimeEventRecord>()
        val firstSink =
            RealtimeActiveThreadSink { envelope ->
                first += RealtimeEventRecord(envelope.owner.serverId, envelope.payload)
            }
        val secondSink =
            RealtimeActiveThreadSink { envelope ->
                second += RealtimeEventRecord(envelope.owner.serverId, envelope.payload)
            }
        owner.registerActiveThreadSink(firstSink)
        owner.registerActiveThreadSink(secondSink)

        val dmOne = DirectMessageCreated(ChannelId("dm-1"))
        owner.acceptOwned("server-1", dmOne)
        owner.unregisterActiveThreadSink(firstSink)
        val dmTwo = DirectMessageCreated(ChannelId("dm-2"))
        owner.acceptOwned("server-1", dmTwo)

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
        val owner = testRealtimeDomainEventOwner()
        val delivered = mutableListOf<RealtimeEventRecord>()
        owner.registerActiveThreadSink { envelope ->
            delivered += RealtimeEventRecord(envelope.owner.serverId, envelope.payload)
        }

        owner.acceptOwned("server-1", Heartbeat(seq = 1L))

        assertEquals(1, delivered.size)
    }

    @Test
    fun ownerRotationBetweenValidationAndSideEffectsDropsOldGenerationEverywhere() {
        val cursorStore = RealtimeAppliedSeqCursorStore(InMemorySlockKeyValueStore())
        lateinit var owner: SharedRealtimeDomainEventOwner
        lateinit var ownerB: RealtimeOwnerEpoch
        var rotateAtSeam = true
        owner =
            testRealtimeDomainEventOwner(
                cursorStore = cursorStore,
                beforeOwnerSideEffects = {
                    if (rotateAtSeam) {
                        rotateAtSeam = false
                        ownerB = owner.rotateOwner("server-1", "member-b")
                    }
                }
            )
        val homeEvents = mutableListOf<RealtimeEventRecord>()
        val settingsEvents = mutableListOf<RealtimeEventRecord>()
        val threadEvents = mutableListOf<RealtimeEventRecord>()
        val callbackSideEffects = mutableListOf<String>()
        owner.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeShell = { envelope ->
                    homeEvents += RealtimeEventRecord(envelope.owner.serverId, envelope.payload)
                },
                applySettings = { envelope ->
                    settingsEvents += RealtimeEventRecord(envelope.owner.serverId, envelope.payload)
                }
            )
        )
        owner.registerActiveThreadSink { envelope ->
            threadEvents += RealtimeEventRecord(envelope.owner.serverId, envelope.payload)
        }
        val ownerA = owner.rotateOwner("server-1", "member-a")
        val event = MessageNew(message(id = "m-owner-fence", seq = 1))

        val acceptedA = owner.acceptOwned("server-1", event, ownerToken = ownerA, source = "test")
        if (acceptedA) {
            callbackSideEffects +=
                listOf(
                    "realtime_last_seq",
                    "membership_disconnect",
                    "auth_refresh",
                    "announcement_http",
                    "foreground_banner",
                )
        }

        assertEquals(emptyList(), homeEvents)
        assertEquals(emptyList(), settingsEvents)
        assertEquals(emptyList(), threadEvents)
        assertEquals(emptyList(), callbackSideEffects)
        assertEquals(0L, cursorStore.readAppliedLastSeq(ServerId("server-1")))

        val acceptedB = owner.acceptOwned("server-1", event, ownerToken = ownerB, source = "test")
        if (acceptedB) callbackSideEffects += "current_callback"

        assertEquals(listOf(RealtimeEventRecord("server-1", event)), homeEvents)
        assertEquals(listOf(RealtimeEventRecord("server-1", event)), settingsEvents)
        assertEquals(listOf(RealtimeEventRecord("server-1", event)), threadEvents)
        assertEquals(listOf("current_callback"), callbackSideEffects)
        assertEquals(1L, cursorStore.readAppliedLastSeq(ServerId("server-1")))
    }

    @Test
    fun backgroundUnreadRequiresExactCurrentOwnerBeforeDedupRecoveryOrCursor() {
        val cursorStore = RealtimeAppliedSeqCursorStore(InMemorySlockKeyValueStore())
        val owner = testRealtimeDomainEventOwner(cursorStore = cursorStore)
        val unreadEvents = mutableListOf<RealtimeEventRecord>()
        owner.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeUnreadProjection = { envelope ->
                    unreadEvents += RealtimeEventRecord(envelope.owner.serverId, envelope.payload)
                }
            )
        )
        val event = MessageNew(message(id = "background-owner", seq = 7))

        assertEquals(
            false,
            owner.acceptHomeUnreadProjection(
                envelope = ownedRealtimeEvent(event),
                source = "background",
            )
        )

        assertEquals(emptyList(), unreadEvents)
        assertEquals(0L, cursorStore.readAppliedLastSeq(ServerId("server-1")))
    }

    @Test
    fun ownerRotationAtValidationSeamDropsTaskProjection() = runBlocking {
        val tasksDataStore = SharedTasksDataStore()
        lateinit var owner: SharedRealtimeDomainEventOwner
        lateinit var ownerB: RealtimeOwnerEpoch
        var rotateAtSeam = true
        owner =
            testRealtimeDomainEventOwner(
                tasksDataStore = tasksDataStore,
                beforeOwnerSideEffects = {
                    if (rotateAtSeam) {
                        rotateAtSeam = false
                        ownerB = owner.rotateOwner("server-1", "member-b")
                    }
                }
            )
        tasksDataStore.publishSnapshot(
            serverId = "server-1",
            scope = SharedTasksScope.Server,
            snapshot = TaskSnapshot(tasks = emptyList(), summaryCounts = emptyMap())
        )
        val ownerA = owner.rotateOwner("server-1", "member-a")
        val task =
            TaskSummary(
                id = TaskId("task-owner-fence"),
                title = "Owner fence",
                status = TaskStatus.InProgress,
                channelId = ChannelId("channel-1"),
                updatedAtLabel = "now"
            )
        val event = TaskUpdated(task)

        owner.acceptOwned("server-1", event, ownerToken = ownerA, source = "test")
        assertEquals(
            emptyList(),
            tasksDataStore.channelProjectionFlow("server-1", "channel-1", null).first().tasks
        )

        owner.acceptOwned("server-1", event, ownerToken = ownerB, source = "test")
        assertEquals(
            listOf(task),
            tasksDataStore.channelProjectionFlow("server-1", "channel-1", null).first().tasks
        )
    }

    @Test
    fun revokeBurnsGenerationAndReadOnlyLookupCannotCreateOwner() {
        val owner = testRealtimeDomainEventOwner()
        val delivered = mutableListOf<RealtimeEventRecord>()
        owner.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeShell = { envelope ->
                    delivered += RealtimeEventRecord(envelope.owner.serverId, envelope.payload)
                }
            )
        )
        val ownerA = owner.rotateOwner("server-1", "member-a")
        val event = DirectMessageCreated(ChannelId("dm-reconnect"))

        owner.revokeOwner()

        owner.acceptOwned("server-1", event, ownerToken = ownerA, source = "test")
        assertEquals(emptyList(), delivered)
        val ownerAReconnect = owner.rotateOwner("server-1", "member-a")
        assertNotEquals(ownerA.connectionEpoch, ownerAReconnect.connectionEpoch)
        assertEquals(true, ownerAReconnect.connectionEpoch > ownerA.connectionEpoch)
        owner.acceptOwned("server-1", event, ownerToken = ownerA, source = "stale-generation")
        owner.acceptOwned("server-1", event, ownerToken = ownerAReconnect, source = "current-generation")
        assertEquals(listOf(RealtimeEventRecord("server-1", event)), delivered)
    }

    @Test
    fun queuedThreadMutationRechecksExactOwnerGeneration() {
        val owner = testRealtimeDomainEventOwner()
        val queued = mutableListOf<() -> Unit>()
        val applied = mutableListOf<String>()
        owner.registerActiveThreadSink { envelope ->
            queued += {
                owner.runIfCurrentOwner(envelope.owner) {
                    applied += (envelope.payload as DirectMessageCreated).channelId.value
                }
            }
        }
        val ownerA = owner.rotateOwner("server-1", "member-a")
        val event = DirectMessageCreated(ChannelId("dm-owner-fence"))
        owner.acceptOwned("server-1", event, ownerToken = ownerA, source = "test")
        val staleMutation = queued.removeFirst()

        val ownerB = owner.rotateOwner("server-1", "member-b")
        staleMutation()
        owner.acceptOwned("server-1", event, ownerToken = ownerB, source = "test")
        queued.removeFirst().invoke()

        assertEquals(listOf("dm-owner-fence"), applied)
    }

    private data class RealtimeEventRecord(
        val serverId: String,
        val event: Any
    )

    private fun SharedRealtimeDomainEventOwner.acceptOwned(
        serverId: String,
        event: RealtimeEvent,
        ownerToken: RealtimeOwnerEpoch? = null,
        source: String = "test",
    ): Boolean {
        val token =
            ownerToken
                ?: testOwners[this]?.takeIf { it.serverId == serverId }
                ?: rotateOwner(serverId, TestOwnerMemberId)
        testOwners[this] = token
        return accept(ownedRealtimeEvent(event, token, serverId), source = source)
    }

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

    private companion object {
        const val TestOwnerMemberId = "test-owner"
    }
}
