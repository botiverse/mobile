package build.raft.app.data

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
import build.raft.app.model.Presence
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
}
