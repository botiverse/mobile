package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.DirectMessageCreated
import ai.slock.compose.model.Heartbeat
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberPresenceUpdated
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Message
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.MessageNew
import ai.slock.compose.model.Presence
import ai.slock.compose.model.TaskDeleted
import ai.slock.compose.model.TaskId
import ai.slock.compose.model.TaskStatus
import ai.slock.compose.model.TaskSummary
import ai.slock.compose.model.TaskUpdated
import ai.slock.compose.model.ThreadId
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
    fun agentPresenceUpdatesActivityAndMemberDetailSinks() {
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
        assertEquals(1, refreshRequests)
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

    private data class RealtimeEventRecord(
        val serverId: String,
        val event: Any
    )
}
