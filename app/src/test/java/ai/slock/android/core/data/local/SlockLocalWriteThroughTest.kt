package ai.slock.android.core.data.local

import ai.slock.android.core.data.repository.DataResult
import ai.slock.android.core.data.repository.HomeSnapshot
import ai.slock.android.core.data.repository.SendMessageResult
import ai.slock.android.core.data.repository.ThreadSnapshot
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ConversationKind
import ai.slock.android.core.model.ConversationPermissions
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.MemberPresenceUpdated
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.MessageNew
import ai.slock.android.core.model.RealtimeEvent
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.ServerSummary
import ai.slock.android.core.model.TaskId
import ai.slock.android.core.model.TaskDeleted
import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.model.TaskSummary
import ai.slock.android.core.model.TaskUpdated
import ai.slock.android.core.model.ThreadId
import ai.slock.android.core.model.ThreadUpdated
import ai.slock.android.core.state.EmptyReason
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlockLocalWriteThroughTest {
    @Test
    fun writesThreadSnapshotMessagesTasksAndCursor() {
        val store = createInMemoryStore()
        val logs = mutableListOf<String>()
        val writer = SlockLocalWriteThrough(store, clock = { 42L }, logger = logs::add)
        val snapshot =
            ThreadSnapshot(
                conversation = conversation("thread-1"),
                parentMessage = message("parent-1", seq = 1),
                messages = listOf(message("message-1", seq = 2).copy(task = task("task-1"))),
                tasks = listOf(task("task-2").copy(messageId = MessageId("message-2"))),
                canLoadMoreBefore = false,
                canLoadMoreAfter = true
            )

        writer.writeThreadResult(DataResult.Content(snapshot), source = "test_snapshot")

        assertEquals("general", store.getConversation(ThreadId("thread-1"))?.name)
        assertEquals(listOf("parent-1", "message-1"), store.getMessagesForThread(ThreadId("thread-1")).map { it.id.value })
        assertEquals(382, store.getTaskReferencesForMessage(MessageId("message-1")).single().taskNumber)
        assertEquals(2L, store.getSyncCursor("thread-1")?.latestSeq)
        assertEquals(1L, store.getSyncCursor("thread-1")?.oldestLoadedSeq)
        assertEquals(false, store.getSyncCursor("thread-1")?.hasLoadedNewest)
        assertTrue(logs.any { it.contains("write_thread source=test_snapshot") })
    }

    @Test
    fun writesAndReadsHomeSnapshotConversations() {
        val store = createInMemoryStore()
        val logs = mutableListOf<String>()
        val writer = SlockLocalWriteThrough(store, clock = { 42L }, logger = logs::add)
        val server = ServerSummary(id = ServerId("server-1"), name = "Slock")
        val currentMember = memberProfile("user-1", MemberKind.Human)
        val channel =
            conversation("channel-1").copy(
                name = "all",
                unreadCount = 3,
                joined = false,
                permissions = ConversationPermissions(canRead = false, canSend = false)
            )
        val dm =
            ConversationSummary(
                threadId = ThreadId("dm-1"),
                channelId = ChannelId("dm-1"),
                kind = ConversationKind.DirectMessage,
                name = "Cindy",
                unreadCount = 2
            )

        writer.writeHomeResult(
            DataResult.Content(
                HomeSnapshot(
                    server = server,
                    currentMember = currentMember,
                    channels = listOf(channel),
                    directMessages = listOf(dm),
                    unreadTotal = 5
                )
            ),
            source = "test_home"
        )

        val cached =
            writer.readHomeSnapshot(
                serverId = ServerId("server-1"),
                serverFallback = server,
                currentMemberFallback = currentMember
            )

        assertEquals(listOf("all"), cached?.channels?.map { it.name })
        assertEquals(listOf("Cindy"), cached?.directMessages?.map { it.name })
        assertEquals(false, cached?.channels?.single()?.joined)
        assertEquals(false, cached?.channels?.single()?.permissions?.canRead)
        assertEquals(false, cached?.channels?.single()?.permissions?.canSend)
        assertEquals(5, cached?.unreadTotal)
        assertEquals(5, cached?.server?.unreadCount)
        assertTrue(logs.any { it.contains("write_home source=test_home") })
        assertTrue(logs.any { it.contains("read_home serverId=server-1 hit=true") })
    }

    @Test
    fun writeHomeResultPreservesDirectMessagePresenceFallback() {
        val store = createInMemoryStore()
        val writer = SlockLocalWriteThrough(store, clock = { 42L })
        val server = ServerSummary(id = ServerId("server-1"), name = "Slock")
        val currentMember = memberProfile("user-1", MemberKind.Human)
        val seededDm =
            ConversationSummary(
                threadId = ThreadId("dm-1"),
                channelId = ChannelId("dm-1"),
                kind = ConversationKind.DirectMessage,
                name = "Cindy",
                presence = ai.slock.android.core.model.Presence.Active,
                presenceMemberId = MemberId("agent-1")
            )

        writer.writeHomeResult(
            DataResult.Content(
                HomeSnapshot(
                    server = server,
                    currentMember = currentMember,
                    channels = emptyList(),
                    directMessages = listOf(seededDm),
                    unreadTotal = 0
                )
            ),
            source = "seed_home"
        )

        writer.writeHomeResult(
            DataResult.Content(
                HomeSnapshot(
                    server = server,
                    currentMember = currentMember,
                    channels = emptyList(),
                    directMessages =
                        listOf(
                            seededDm.copy(
                                presence = null,
                                presenceMemberId = null
                            )
                        ),
                    unreadTotal = 0
                )
            ),
            source = "refresh_home"
        )

        val cached =
            writer.readHomeSnapshot(
                serverId = ServerId("server-1"),
                serverFallback = server,
                currentMemberFallback = currentMember
            )

        assertEquals(
            ai.slock.android.core.model.Presence.Active,
            cached?.directMessages?.single()?.presence
        )
        assertEquals("agent-1", cached?.directMessages?.single()?.presenceMemberId?.value)
    }

    @Test
    fun clearConversationUnreadUpdatesHomeCache() {
        val store = createInMemoryStore()
        val writer = SlockLocalWriteThrough(store, clock = { 45L })
        val server = ServerSummary(id = ServerId("server-1"), name = "Slock")
        val currentMember = memberProfile("user-1", MemberKind.Human)
        writer.writeHomeResult(
            DataResult.Content(
                HomeSnapshot(
                    server = server,
                    currentMember = currentMember,
                    channels =
                        listOf(
                            conversation("thread-1").copy(
                                unreadCount = 3,
                                mentionCount = 1,
                                firstUnreadMessageId = MessageId("message-1")
                            )
                        ),
                    directMessages = emptyList(),
                    unreadTotal = 3
                )
            ),
            source = "seed_home"
        )

        writer.clearConversationUnread(ChannelId("thread-1"), source = "test_read")

        val cached =
            writer.readHomeSnapshot(
                serverId = ServerId("server-1"),
                serverFallback = server,
                currentMemberFallback = currentMember
            )

        assertEquals(0, cached?.channels?.single()?.unreadCount)
        assertEquals(0, cached?.channels?.single()?.mentionCount)
        assertEquals(null, cached?.channels?.single()?.firstUnreadMessageId)
        assertEquals(0, cached?.unreadTotal)
    }

    @Test
    fun writesRealtimeMessagesAndTaskUpdates() {
        val store = createInMemoryStore()
        val writer = SlockLocalWriteThrough(store, clock = { 43L })
        val message = message("message-1", seq = 9).copy(task = task("task-1"))

        writer.writeRealtimeEvent(ServerId("server-1"), MessageNew(message))
        writer.writeRealtimeEvent(
            ServerId("server-1"),
            TaskUpdated(
                task("task-1").copy(status = TaskStatus.Done, updatedAtLabel = "Now")
            )
        )

        val messages = store.getMessagesForThread(ThreadId("thread-1"))
        val task = store.getTaskReferencesForMessage(MessageId("message-1")).single()

        assertEquals("message-1", messages.single().id.value)
        assertEquals(9L, store.getSyncCursor("thread-1")?.latestSeq)
        assertEquals(TaskStatus.Done, task.status)
        assertEquals(382, task.taskNumber)
    }

    @Test
    fun writesRealtimeThreadMetadataAndTaskDeletes() {
        val store = createInMemoryStore()
        val writer = SlockLocalWriteThrough(store, clock = { 43L })
        writer.writeThreadResult(
            DataResult.Content(
                ThreadSnapshot(
                    conversation = conversation("thread-1"),
                    messages = listOf(message("message-1", seq = 9).copy(task = task("task-1")))
                )
            ),
            source = "seed_thread"
        )

        writer.writeRealtimeEvent(
            ServerId("server-1"),
            ThreadUpdated(
                parentMessageId = MessageId("message-1"),
                threadChannelId = ChannelId("thread-replies-1"),
                replyCount = 4,
                lastReplyAt = "Now",
                participantIds = emptyList()
            )
        )
        writer.writeRealtimeEvent(ServerId("server-1"), TaskDeleted(TaskId("task-1")))

        val threadMessage = store.getMessagesForThread(ThreadId("thread-1")).single()
        val threadState = writer.readThreadUiState(ThreadId("thread-1"))
        val content =
            threadState?.loadState as ai.slock.android.core.state.ScreenLoadState.Content

        assertEquals("thread-replies-1", threadMessage.threadChannelId?.value)
        assertEquals(4, threadMessage.replyCount)
        assertEquals("Now", threadMessage.updatedAtLabel)
        assertTrue(store.getTaskReferencesForMessage(MessageId("message-1")).isEmpty())
        assertTrue(content.data.tasks.isEmpty())
        assertEquals(null, content.data.messages.single().task)
    }

    @Test
    fun realtimeHomeUpdatesAreReadBackFromCache() {
        val store = createInMemoryStore()
        val writer = SlockLocalWriteThrough(store, clock = { 43L })
        val server = ServerSummary(id = ServerId("server-1"), name = "Slock")
        val currentMember = memberProfile("user-1", MemberKind.Human)
        writer.writeHomeResult(
            DataResult.Content(
                HomeSnapshot(
                    server = server,
                    currentMember = currentMember,
                    channels = listOf(conversation("thread-1").copy(name = "all")),
                    directMessages =
                        listOf(
                            ConversationSummary(
                                threadId = ThreadId("dm-1"),
                                channelId = ChannelId("dm-1"),
                                kind = ConversationKind.DirectMessage,
                                name = "Agent",
                                presenceMemberId = MemberId("agent-1")
                            )
                        ),
                    unreadTotal = 0
                )
            ),
            source = "seed_home"
        )

        writer.writeRealtimeEvent(
            ServerId("server-1"),
            MessageNew(
                message("message-1", seq = 9).copy(
                    body = "fresh update",
                    author = memberProfile("user-2", MemberKind.Human)
                )
            )
        )
        writer.writeRealtimeEvent(
            ServerId("server-1"),
            MemberPresenceUpdated(
                memberId = MemberId("agent-1"),
                presence = ai.slock.android.core.model.Presence.Active,
                kind = MemberKind.Agent
            )
        )

        val cached =
            writer.readHomeSnapshot(
                serverId = ServerId("server-1"),
                serverFallback = server,
                currentMemberFallback = currentMember
            )

        assertEquals(1, cached?.channels?.single()?.unreadCount)
        assertEquals("fresh update", cached?.channels?.single()?.lastMessagePreview)
        assertEquals("user-2", cached?.channels?.single()?.lastMessageSenderName)
        assertEquals(
            ai.slock.android.core.model.Presence.Active,
            cached?.directMessages?.single()?.presence
        )
    }

    @Test
    fun writesSendResultMessage() {
        val store = createInMemoryStore()
        val writer = SlockLocalWriteThrough(store, clock = { 44L })

        writer.writeSendResult(
            DataResult.Content(SendMessageResult(message("local-sent", seq = 10))),
            source = "send"
        )

        assertEquals("local-sent", store.getMessagesForThread(ThreadId("thread-1")).single().id.value)
    }

    @Test
    fun sendResultWithoutChannelIdIsLoggedAndSkipped() {
        val store = createInMemoryStore()
        val logs = mutableListOf<String>()
        val writer = SlockLocalWriteThrough(store, clock = { 44L }, logger = logs::add)

        writer.writeSendResult(
            DataResult.Content(
                SendMessageResult(message("local-sent", seq = 10).copy(channelId = null))
            ),
            source = "send"
        )

        assertEquals(emptyList<Message>(), store.getMessagesForThread(ThreadId("thread-1")))
        assertTrue(
            logs.any {
                it.contains("write_message_skip source=send reason=missing_stream_channel_id")
            }
        )
    }

    @Test
    fun readsThreadUiStateFromLocalTimeline() {
        val store = createInMemoryStore()
        val writer = SlockLocalWriteThrough(store, clock = { 44L })
        writer.writeThreadResult(
            DataResult.Content(
                ThreadSnapshot(
                    conversation = conversation("thread-1"),
                    messages = listOf(message("message-1", seq = 10).copy(task = task("task-1"))),
                    tasks = listOf(task("task-1")),
                    canLoadMoreBefore = true,
                    canLoadMoreAfter = false
                )
            ),
            source = "seed"
        )

        val state = writer.readThreadUiState(ThreadId("thread-1"))
        val content =
            state?.loadState as ai.slock.android.core.state.ScreenLoadState.Content

        assertEquals("general", content.data.header.name)
        assertEquals(listOf("message-1"), content.data.messages.map { it.id.value })
        assertEquals(382, content.data.tasks.single().taskNumber)
        assertEquals(true, content.data.canLoadMoreBefore)
        assertEquals(false, content.data.canLoadMoreAfter)
    }

    @Test
    fun emptyResultsAreLoggedAndSkipped() {
        val store = createInMemoryStore()
        val logs = mutableListOf<String>()
        val writer = SlockLocalWriteThrough(store, clock = { 45L }, logger = logs::add)

        writer.writeThreadResult(DataResult.Empty(EmptyReason.NoMessages), source = "empty")

        assertEquals(emptyList<Message>(), store.getMessagesForThread(ThreadId("thread-1")))
        assertTrue(logs.any { it.contains("write_thread_skip source=empty reason=empty:NoMessages") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownThreadSnapshotIds() {
        val store = createInMemoryStore()
        val writer = SlockLocalWriteThrough(store, clock = { 46L })

        writer.writeThreadResult(
            DataResult.Content(
                ThreadSnapshot(
                    conversation =
                        conversation("unknown-channel").copy(
                            channelId = ChannelId("unknown-channel")
                        ),
                    messages = listOf(message("message-1", seq = 1))
                )
            ),
            source = "broken_snapshot"
        )
    }

    @Test
    fun realtimeMessageNewTriggersObserveThreadTimelineFlow() = runBlocking {
        val store = createInMemoryStore()
        val logs = mutableListOf<String>()
        val writer = SlockLocalWriteThrough(store, clock = { 42L }, logger = logs::add)
        val serverId = ServerId("server-1")
        val threadId = ThreadId("thread-1")
        val channelId = ChannelId("thread-1")

        // Setup: Write initial thread snapshot
        writer.writeThreadResult(
            DataResult.Content(
                ThreadSnapshot(
                    conversation = conversation("thread-1").copy(name = "test-channel"),
                    messages = listOf(message("message-1", seq = 1))
                )
            ),
            source = "test_initial"
        )

        // Start observing the timeline
        val flow = store.observeThreadTimeline(threadId)

        // Verify initial emission
        val initial = withTimeout(1000) { flow.first() }
        assertEquals(1, initial?.messages?.size)
        assertEquals("message-1", initial?.messages?.first()?.id?.value)

        // Simulate realtime MessageNew event
        val newMessage = message("message-2", seq = 2).copy(body = "realtime message")
        writer.writeRealtimeEvent(serverId, MessageNew(newMessage))

        // Collect next 2 emissions (initial + after realtime event)
        val emissions = withTimeout(2000) { flow.take(2).toList() }

        // Verify we got 2 emissions
        assertEquals(2, emissions.size)

        // Verify second emission contains both messages
        val updated = emissions[1]
        assertEquals(2, updated?.messages?.size)
        assertEquals(
            listOf("message-1", "message-2"),
            updated?.messages?.map { it.id.value }
        )
        assertEquals("realtime message", updated?.messages?.last()?.body)

        // Verify log contains realtime write
        assertTrue(logs.any { it.contains("write_messages source=socket_message_new") })
    }

    private fun createInMemoryStore(): SlockLocalStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SlockDatabase.Schema.create(driver)
        return SqlDelightSlockLocalStore(SlockDatabase(driver))
    }

    private fun conversation(id: String): ConversationSummary =
        ConversationSummary(
            threadId = ThreadId(id),
            channelId = ChannelId(id),
            kind = ConversationKind.Channel,
            name = "general"
        )

    private fun message(id: String, seq: Long): Message =
        Message(
            id = MessageId(id),
            threadId = ThreadId("thread-1"),
            channelId = ChannelId("thread-1"),
            seq = seq,
            author = memberProfile("user-1", MemberKind.Human),
            timestampLabel = "19:35",
            body = "hello"
        )

    private fun memberProfile(id: String, kind: MemberKind): MemberProfile =
        MemberProfile(
            id = MemberId(id),
            displayName = id,
            handle = id,
            kind = kind
        )

    private fun task(id: String): TaskSummary =
        TaskSummary(
            id = TaskId(id),
            title = "Task title",
            status = TaskStatus.InProgress,
            taskNumber = 382,
            messageId = MessageId("message-1"),
            updatedAtLabel = "Earlier"
        )
}
