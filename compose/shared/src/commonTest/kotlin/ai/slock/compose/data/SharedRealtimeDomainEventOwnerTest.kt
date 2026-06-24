package ai.slock.compose.data

import ai.slock.compose.model.AgentStatus
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.DirectMessageCreated
import ai.slock.compose.model.Heartbeat
import ai.slock.compose.model.Message
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.MessageNew
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberPresenceUpdated
import ai.slock.compose.model.MemberProfile
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
import kotlin.test.assertNull
import kotlin.test.assertSame

class SharedRealtimeDomainEventOwnerTest {
    @Test
    fun ignoresBlankServerIdsAndNonThreadProjectionEvents() {
        val owner = SharedRealtimeDomainEventOwner()

        owner.accept("", DirectMessageCreated(ChannelId("dm-1")))
        assertNull(owner.threadProjections.value)

        owner.accept("server-1", Heartbeat(seq = 10L))
        assertNull(owner.threadProjections.value)
    }

    @Test
    fun projectsThreadRelevantAndPresenceEventsWithSequence() {
        val owner = SharedRealtimeDomainEventOwner()
        val messageRouteEvent = DirectMessageCreated(ChannelId("dm-1"))
        val presenceEvent =
            MemberPresenceUpdated(
                memberId = MemberId("agent-1"),
                presence = Presence.Active,
                kind = MemberKind.Agent,
                agentStatus = AgentStatus.Working,
                statusLabel = "Running tests",
                serverSeq = 7L
            )

        owner.accept("server-1", messageRouteEvent)

        val first = owner.threadProjections.value
        assertEquals(1L, first?.sequence)
        assertEquals("server-1", first?.serverId)
        assertSame(messageRouteEvent, first?.event)

        owner.accept("server-1", presenceEvent)

        val second = owner.threadProjections.value
        assertEquals(2L, second?.sequence)
        assertEquals("server-1", second?.serverId)
        assertSame(presenceEvent, second?.event)
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
        assertEquals(1L, owner.threadProjections.value?.sequence)
        assertEquals(true, logs.any { it.contains("reason=duplicate") })
    }

    @Test
    fun heartbeatDoesNotPatchDomainSinks() {
        val owner = SharedRealtimeDomainEventOwner()
        var homeEvents = 0
        var settingsEvents = 0
        owner.registerSinks(
            RealtimeDomainEventSinks(
                applyHomeShell = { _, _ -> homeEvents += 1 },
                applySettings = { _, _ -> settingsEvents += 1 }
            )
        )

        owner.accept("server-1", Heartbeat(seq = 42L), source = "app_shell")

        assertEquals(0, homeEvents)
        assertEquals(0, settingsEvents)
        assertNull(owner.threadProjections.value)
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
    fun messageEventsUseStableIdentityForDedupeButStillProjectThread() {
        val owner = SharedRealtimeDomainEventOwner()
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

        assertEquals(1L, owner.threadProjections.value?.sequence)
        assertSame(event, owner.threadProjections.value?.event)
    }

    private data class RealtimeEventRecord(
        val serverId: String,
        val event: Any
    )
}
