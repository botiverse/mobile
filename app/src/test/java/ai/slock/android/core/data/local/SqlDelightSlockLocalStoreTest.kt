package ai.slock.android.core.data.local

import ai.slock.android.core.model.Attachment
import ai.slock.android.core.model.AttachmentId
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ConversationKind
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageDeliveryState
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.TaskId
import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.model.TaskSummary
import ai.slock.android.core.model.ThreadId
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SqlDelightSlockLocalStoreTest {
    @Test
    fun migrationCreatesVersionOneSchema() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SlockDatabase.Schema.migrate(driver, 0, SlockDatabase.Schema.version)
        val database = SlockDatabase(driver)

        database.slockDatabaseQueries.upsertConversation(
            id = "thread-1",
            channel_id = "channel-1",
            thread_id = "thread-1",
            kind = "Channel",
            name = "general",
            parent_channel_id = null,
            parent_message_id = null,
            avatar_url = null,
            unread_count = 0,
            updated_at_label = null,
            last_synced_at_ms = 1L
        )

        assertEquals(
            "general",
            database.slockDatabaseQueries
                .selectConversation("thread-1", "thread-1", "thread-1", "thread-1", "thread-1")
                .executeAsOne()
                .name
        )
    }

    @Test
    fun upsertsAndQueriesThreadTimelineRows() {
        val store = createInMemoryStore()
        val task = task("task-1").copy(
            messageId = MessageId("message-1"),
            assignee = member("agent-1", MemberKind.Agent)
        )
        val message = message("message-1", seq = 2).copy(
            attachments = listOf(
                Attachment(
                    id = AttachmentId("attachment-1"),
                    name = "image.png",
                    mimeType = "image/png",
                    sizeLabel = "42 KB",
                    thumbnailUrl = "https://example.test/image.png"
                )
            ),
            deliveryState = MessageDeliveryState.Sent,
            task = task
        )

        store.upsertConversation(
            ConversationSummary(
                threadId = ThreadId("thread-1"),
                channelId = ChannelId("channel-1"),
                kind = ConversationKind.Channel,
                name = "general",
                unreadCount = 3,
                lastMessageAt = "18:05"
            ),
            syncedAtMs = 10L
        )
        store.upsertMessage(message, syncedAtMs = 11L)

        val conversation = store.getConversation(ThreadId("thread-1"))
        val messages = store.getMessagesForThread(ThreadId("thread-1"))

        assertEquals("general", conversation?.name)
        assertEquals(3, conversation?.unreadCount)
        assertEquals(listOf("message-1"), messages.map { it.id.value })
        assertEquals("image.png", messages.single().attachments.single().name)
        assertEquals(382, messages.single().task?.taskNumber)
        assertEquals("agent-1", messages.single().task?.assignee?.id?.value)
    }

    @Test
    fun sparseTaskReferenceUpdatePreservesReferenceFields() {
        val store = createInMemoryStore()
        store.upsertTaskReference(
            task("task-1").copy(
                messageId = MessageId("message-1"),
                parentMessageId = MessageId("parent-1"),
                channelId = ChannelId("channel-1"),
                threadId = ThreadId("thread-1"),
                taskNumber = 382,
                assignee = member("agent-1", MemberKind.Agent),
                channelName = "native"
            ),
            syncedAtMs = 1L
        )

        store.upsertTaskReference(
            TaskSummary(
                id = TaskId("task-1"),
                title = "Done task",
                status = TaskStatus.Done,
                updatedAtLabel = "Now"
            ),
            syncedAtMs = 2L
        )

        val task = store.getTaskReferencesForMessage(MessageId("message-1")).single()

        assertEquals(TaskStatus.Done, task.status)
        assertEquals(382, task.taskNumber)
        assertEquals(MessageId("parent-1"), task.parentMessageId)
        assertEquals("agent-1", task.assignee?.id?.value)
        assertEquals("native", task.channelName)
    }

    @Test
    fun storesSyncCursorAndOutboxRows() {
        val store = createInMemoryStore()

        store.upsertSyncCursor(
            LocalSyncCursor(
                scopeId = "thread-1",
                scopeKind = LocalSyncScopeKind.Thread,
                latestSeq = 42L,
                updatedAtMs = 1L
            )
        )
        store.upsertSyncCursor(
            LocalSyncCursor(
                scopeId = "thread-1",
                scopeKind = LocalSyncScopeKind.Thread,
                oldestLoadedSeq = 10L,
                updatedAtMs = 2L
            )
        )
        store.enqueueOutboxMessage(
            LocalOutboxMessage(
                localId = "local-1",
                channelId = ChannelId("channel-1"),
                threadId = ThreadId("thread-1"),
                body = "Hello",
                attachmentIds = listOf(AttachmentId("attachment-1")),
                asTask = true,
                createdAtMs = 3L
            )
        )
        store.updateOutboxStatus("local-1", LocalOutboxStatus.Failed, "server_unavailable")

        val cursor = store.getSyncCursor("thread-1")
        val outbox = store.getPendingOutboxMessages().single()

        assertEquals(42L, cursor?.latestSeq)
        assertEquals(10L, cursor?.oldestLoadedSeq)
        assertEquals(LocalOutboxStatus.Failed, outbox.status)
        assertEquals("server_unavailable", outbox.lastErrorCode)
        assertEquals(listOf(AttachmentId("attachment-1")), outbox.attachmentIds)
    }

    @Test
    fun missingRowsReturnNullOrEmptyLists() {
        val store = createInMemoryStore()

        assertNull(store.getConversation(ThreadId("missing")))
        assertEquals(emptyList<Message>(), store.getMessagesForThread(ThreadId("missing")))
        assertEquals(emptyList<TaskSummary>(), store.getTaskReferencesForMessage(MessageId("missing")))
    }

    @Test
    fun conversationCanBeReadByChannelAlias() {
        val store = createInMemoryStore()
        store.upsertConversation(
            ConversationSummary(
                threadId = ThreadId("thread-1"),
                channelId = ChannelId("channel-1"),
                kind = ConversationKind.Channel,
                name = "all"
            ),
            syncedAtMs = 1L
        )

        val conversation = store.getConversation(ThreadId("channel-1"))

        assertEquals("all", conversation?.name)
        assertEquals(ThreadId("thread-1"), conversation?.threadId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownChannelTimelineIds() {
        val store = createInMemoryStore()

        store.upsertConversation(
            ConversationSummary(
                threadId = ThreadId("unknown-channel"),
                channelId = ChannelId("unknown-channel"),
                kind = ConversationKind.Channel,
                name = "broken"
            ),
            syncedAtMs = 1L
        )
    }

    private fun createInMemoryStore(): SlockLocalStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SlockDatabase.Schema.create(driver)
        return SqlDelightSlockLocalStore(SlockDatabase(driver))
    }

    private fun message(id: String, seq: Long): Message =
        Message(
            id = MessageId(id),
            threadId = ThreadId("thread-1"),
            channelId = ChannelId("channel-1"),
            seq = seq,
            author = member("user-1", MemberKind.Human),
            timestampLabel = "18:05",
            body = "Hello"
        )

    private fun member(id: String, kind: MemberKind): MemberProfile =
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
            updatedAtLabel = "Earlier"
        )
}
