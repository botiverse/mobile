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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedRealtimeDomainEventOwnerTest {
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
}
