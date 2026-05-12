package ai.slock.android.core.data.local

import ai.slock.android.core.model.Attachment
import ai.slock.android.core.model.AttachmentId
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ConversationKind
import ai.slock.android.core.model.ConversationPermissions
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageDeliveryState
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.TaskId
import ai.slock.android.core.model.TaskStatus
import ai.slock.android.core.model.TaskSummary
import ai.slock.android.core.model.ThreadId
import ai.slock.android.core.model.ThreadConversation
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
            stream_channel_id = "channel-1",
            server_id = "server-1",
            kind = "Channel",
            name = "general",
            subtitle = null,
            parent_channel_id = null,
            parent_message_id = null,
            avatar_url = null,
            unread_count = 0,
            mention_count = 0,
            last_message_id = null,
            first_unread_message_id = null,
            last_message_preview = null,
            last_message_sender_name = null,
            presence = null,
            agent_status = null,
            presence_member_id = null,
            has_draft = 0L,
            joined = 1L,
            archived = 0L,
            member_count = null,
            can_read = 1L,
            can_send = 1L,
            can_attach = 1L,
            can_create_task = 1L,
            updated_at_label = null,
            last_synced_at_ms = 1L
        )

        assertEquals(
            "general",
            database.slockDatabaseQueries
                .selectConversation(
                    "thread-1",
                    "thread-1",
                    "thread-1",
                    "thread-1",
                    "thread-1",
                    "thread-1",
                    "thread-1"
                )
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
        store.upsertMessage(message, syncedAtMs = 11L, streamChannelId = ChannelId("channel-1"))

        val conversation = store.getConversation(ThreadId("thread-1"))
        val messages = store.getMessagesForThread(ThreadId("channel-1"))

        assertEquals("general", conversation?.name)
        assertEquals(3, conversation?.unreadCount)
        assertEquals(listOf("message-1"), messages.map { it.id.value })
        assertEquals("image.png", messages.single().attachments.single().name)
        assertEquals(382, messages.single().task?.taskNumber)
        assertEquals("agent-1", messages.single().task?.assignee?.id?.value)
    }

    @Test
    fun taskContextJoinsTaskParentMessageSourceAndReplyConversation() {
        val store = createInMemoryStore()
        val parentMessage =
            message("parent-message", seq = 1).copy(
                threadChannelId = ChannelId("reply-channel"),
                replyCount = 2,
                body = "Original task request"
            )
        val task =
            task("task-1").copy(
                messageId = MessageId("task-message"),
                parentMessageId = parentMessage.id,
                channelId = null,
                threadId = null,
                isThreadTask = true,
                channelName = "KMP"
            )
        store.upsertConversation(
            ConversationSummary(
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                kind = ConversationKind.Channel,
                name = "KMP"
            ),
            syncedAtMs = 10L
        )
        store.upsertConversation(
            ConversationSummary(
                threadId = ThreadId("channel-1:parent-message"),
                channelId = ChannelId("channel-1"),
                thread =
                    ThreadConversation(
                        parentChannelId = ChannelId("channel-1"),
                        parentMessageId = parentMessage.id,
                        streamChannelId = ChannelId("reply-channel")
                    ),
                kind = ConversationKind.Thread,
                name = "KMP"
            ),
            syncedAtMs = 11L
        )
        store.upsertMessage(parentMessage, syncedAtMs = 12L, streamChannelId = ChannelId("channel-1"))
        store.upsertTaskReference(task, syncedAtMs = 13L)

        val context =
            store.getTaskContext(
                TaskSummary(id = task.id, title = "", status = TaskStatus.Todo, updatedAtLabel = "")
            )

        assertEquals(parentMessage.id, context.parentMessage?.id)
        assertEquals(ChannelId("channel-1"), context.task.channelId)
        assertEquals(ThreadId("reply-channel"), context.task.threadId)
        assertEquals(true, context.task.isThreadTask)
        assertEquals("KMP", context.sourceConversation?.name)
        assertEquals(ChannelId("reply-channel"), context.replyConversation?.thread?.streamChannelId)
    }

    @Test
    fun homeConversationsAreScopedByServer() {
        val store = createInMemoryStore()
        store.upsertConversation(
            ConversationSummary(
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                kind = ConversationKind.Channel,
                name = "all",
                unreadCount = 3
            ),
            syncedAtMs = 10L,
            serverId = ServerId("server-1")
        )
        store.upsertConversation(
            ConversationSummary(
                threadId = ThreadId("dm-1"),
                channelId = ChannelId("dm-1"),
                kind = ConversationKind.DirectMessage,
                name = "Cindy",
                unreadCount = 2
            ),
            syncedAtMs = 11L,
            serverId = ServerId("server-1")
        )
        store.upsertConversation(
            ConversationSummary(
                threadId = ThreadId("other-channel"),
                channelId = ChannelId("other-channel"),
                kind = ConversationKind.Channel,
                name = "other"
            ),
            syncedAtMs = 12L,
            serverId = ServerId("server-2")
        )

        val home = store.getHomeConversations(ServerId("server-1"))

        assertEquals(listOf("all"), home.channels.map { it.name })
        assertEquals(listOf("Cindy"), home.directMessages.map { it.name })
    }

    @Test
    fun conversationUpsertPreservesExistingHomeServerScope() {
        val store = createInMemoryStore()
        store.upsertConversation(
            ConversationSummary(
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                kind = ConversationKind.Channel,
                name = "all"
            ),
            syncedAtMs = 10L,
            serverId = ServerId("server-1")
        )

        store.upsertConversation(
            ConversationSummary(
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                kind = ConversationKind.Channel,
                name = "all refreshed"
            ),
            syncedAtMs = 11L
        )

        val home = store.getHomeConversations(ServerId("server-1"))

        assertEquals(listOf("all refreshed"), home.channels.map { it.name })
    }

    @Test
    fun homeConversationOrderDoesNotChangeWhenOpenedChannelIsResynced() {
        val store = createInMemoryStore()
        store.upsertConversation(
            ConversationSummary(
                threadId = ThreadId("channel-beta"),
                channelId = ChannelId("channel-beta"),
                kind = ConversationKind.Channel,
                name = "beta"
            ),
            syncedAtMs = 10L,
            serverId = ServerId("server-1")
        )
        store.upsertConversation(
            ConversationSummary(
                threadId = ThreadId("channel-alpha"),
                channelId = ChannelId("channel-alpha"),
                kind = ConversationKind.Channel,
                name = "alpha"
            ),
            syncedAtMs = 11L,
            serverId = ServerId("server-1")
        )

        assertEquals(listOf("alpha", "beta"), store.getHomeConversations(ServerId("server-1")).channels.map { it.name })

        store.upsertConversation(
            ConversationSummary(
                threadId = ThreadId("channel-beta"),
                channelId = ChannelId("channel-beta"),
                kind = ConversationKind.Channel,
                name = "beta"
            ),
            syncedAtMs = 99L
        )

        assertEquals(listOf("alpha", "beta"), store.getHomeConversations(ServerId("server-1")).channels.map { it.name })
    }

    @Test
    fun homeConversationReadDedupesSameChannelAcrossAliasThreadIds() {
        val store = createInMemoryStore()
        store.upsertConversation(
            ConversationSummary(
                threadId = ThreadId("thread-alias-1"),
                channelId = ChannelId("channel-1"),
                kind = ConversationKind.Channel,
                name = "all",
                joined = false,
                permissions = ConversationPermissions(canRead = false, canSend = false)
            ),
            syncedAtMs = 10L,
            serverId = ServerId("server-1")
        )
        store.upsertConversation(
            ConversationSummary(
                threadId = ThreadId("thread-alias-2"),
                channelId = ChannelId("channel-1"),
                kind = ConversationKind.Channel,
                name = "all latest",
                joined = false,
                permissions = ConversationPermissions(canRead = false, canSend = false)
            ),
            syncedAtMs = 11L,
            serverId = ServerId("server-1")
        )

        val home = store.getHomeConversations(ServerId("server-1"))

        assertEquals(1, home.channels.size)
        assertEquals("all latest", home.channels.single().name)
        assertEquals(false, home.channels.single().joined)
        assertEquals(false, home.channels.single().permissions.canRead)
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

    @Test
    fun observeThreadTimelineEmitsWhenNewMessageInserted() = runBlocking {
        val store = createInMemoryStore()
        val threadId = ThreadId("thread-1")
        val channelId = ChannelId("channel-1")

        // Setup: Create conversation
        store.upsertConversation(
            ConversationSummary(
                threadId = threadId,
                channelId = channelId,
                kind = ConversationKind.Channel,
                name = "general",
                unreadCount = 0
            ),
            syncedAtMs = 10L
        )

        // Insert initial message
        store.upsertMessage(
            message("message-1", seq = 1),
            syncedAtMs = 11L,
            streamChannelId = channelId
        )

        // Start observing the timeline
        val flow = store.observeThreadTimeline(threadId)

        // Verify initial emission
        val initial = withTimeout(1000) { flow.first() }
        assertEquals(1, initial?.messages?.size)
        assertEquals("message-1", initial?.messages?.first()?.id?.value)

        // Insert a new message while observing
        store.upsertMessage(
            message("message-2", seq = 2),
            syncedAtMs = 12L,
            streamChannelId = channelId
        )

        // Collect next 2 emissions (initial + after insert)
        val emissions = withTimeout(2000) { flow.take(2).toList() }

        // Verify we got 2 emissions
        assertEquals(2, emissions.size)

        // Verify second emission contains both messages
        val updated = emissions[1]
        assertEquals(2, updated?.messages?.size)
        assertEquals(listOf("message-1", "message-2"), updated?.messages?.map { it.id.value })
    }

    @Test
    fun observeThreadTimelineEmitsInitialValueEvenWhenEmpty() = runBlocking {
        val store = createInMemoryStore()
        val threadId = ThreadId("thread-empty")
        val channelId = ChannelId("channel-empty")

        // Setup: Create conversation with no messages
        store.upsertConversation(
            ConversationSummary(
                threadId = threadId,
                channelId = channelId,
                kind = ConversationKind.Channel,
                name = "empty",
                unreadCount = 0
            ),
            syncedAtMs = 10L
        )

        // Start observing - should emit immediately even with no messages
        val flow = store.observeThreadTimeline(threadId)
        val initial = withTimeout(1000) { flow.first() }

        // Should emit a timeline with 0 messages, not null
        assertEquals(0, initial?.messages?.size ?: -1)
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
