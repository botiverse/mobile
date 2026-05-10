package ai.slock.android.core.data.local

import ai.slock.android.core.data.repository.DataResult
import ai.slock.android.core.data.repository.SendMessageResult
import ai.slock.android.core.data.repository.ThreadSnapshot
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ConversationKind
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.RealtimeEvent
import ai.slock.android.core.model.TaskId
import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.model.TaskSummary
import ai.slock.android.core.model.ThreadId
import ai.slock.android.core.state.EmptyReason
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
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
    fun writesRealtimeMessagesAndTaskUpdates() {
        val store = createInMemoryStore()
        val writer = SlockLocalWriteThrough(store, clock = { 43L })
        val message = message("message-1", seq = 9).copy(task = task("task-1"))

        writer.writeRealtimeEvent(RealtimeEvent.MessageNew(message))
        writer.writeRealtimeEvent(
            RealtimeEvent.TaskUpdated(
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
            author =
                MemberProfile(
                    id = MemberId("user-1"),
                    displayName = "artin",
                    handle = "artin",
                    kind = MemberKind.Human
                ),
            timestampLabel = "19:35",
            body = "hello"
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
