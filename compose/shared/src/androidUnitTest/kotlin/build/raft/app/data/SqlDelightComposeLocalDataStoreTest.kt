package build.raft.app.data

import build.raft.app.model.Attachment
import build.raft.app.model.AttachmentId
import build.raft.app.model.AgentStatus
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageDeliveryState
import build.raft.app.model.MessageId
import build.raft.app.model.MessageReaction
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.ThreadId
import build.raft.app.model.ThreadConversation
import build.raft.app.model.threadIdentity
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SqlDelightComposeLocalDataStoreTest {
    @Test
    fun writesAndReadsThreadSnapshotFromNormalizedTables() = runBlocking {
        val database = createDatabase()
        val fallback = InMemoryComposeLocalDataStore()
        val store =
            SqlDelightComposeLocalDataStore(
                database = database,
                fallback = fallback,
                currentServerId = { serverId },
                clock = { 1234L }
            )
        val task =
            TaskSummary(
                id = TaskId("task-1"),
                title = "wire local-first thread cache",
                status = TaskStatus.InReview,
                taskNumber = 109,
                messageId = MessageId("message-1"),
                channelId = ChannelId("channel-1"),
                assignee = member,
                updatedAtLabel = "21:00",
                canClaim = false
            )
        val channelTask =
            TaskSummary(
                id = TaskId("task-channel-1"),
                title = "channel-level task",
                status = TaskStatus.Todo,
                taskNumber = 110,
                channelId = ChannelId("channel-1"),
                updatedAtLabel = "21:02",
                canClaim = true
            )
        val snapshot =
            ThreadSnapshot(
                conversation =
                    ConversationSummary(
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        kind = ConversationKind.Channel,
                        name = "general",
                        lastMessageId = MessageId("message-1"),
                        lastMessagePreview = "cached body",
                        unreadCount = 3,
                        mentionCount = 1
                    ),
                messages =
                    listOf(
                        Message(
                            id = MessageId("message-1"),
                            threadId = ThreadId("channel-1"),
                            channelId = ChannelId("channel-1"),
                            seq = 7,
                            author = member,
                            timestampLabel = "21:00",
                            body = "cached body",
                            metadata = mapOf("source" to "test"),
                            reactions =
                                listOf(
                                    MessageReaction(
                                        emoji = "👍",
                                        count = 1,
                                        reactorIds = listOf("user-1")
                                    )
                                ),
                            attachments =
                                listOf(
                                    Attachment(
                                        id = AttachmentId("attachment-1"),
                                        name = "trace.txt",
                                        mimeType = "text/plain",
                                        sizeLabel = "",
                                        sizeBytes = 12_288L,
                                    )
                                ),
                            deliveryState = MessageDeliveryState.Sent,
                            task = task,
                            updatedAtLabel = "21:01"
                        )
                    ),
                tasks = listOf(task, channelTask),
                canLoadMoreBefore = true,
                canLoadMoreAfter = true,
                historyLimited = true,
                lastSeq = 7
            )

        store.writeThread(snapshot)

        val restored = assertNotNull(store.readThread(ThreadId("channel-1")))
        assertEquals("general", restored.conversation.name)
        assertEquals(3, restored.conversation.unreadCount)
        assertEquals(1, restored.conversation.mentionCount)
        assertEquals(true, restored.canLoadMoreBefore)
        assertEquals(true, restored.canLoadMoreAfter)
        assertEquals(true, restored.historyLimited)
        assertEquals(7, restored.lastSeq)
        val message = restored.messages.single()
        assertEquals("cached body", message.body)
        assertEquals("test", message.metadata["source"])
        val reaction = message.reactions?.single()
        assertEquals("👍", reaction?.emoji)
        assertEquals(1, reaction?.count)
        assertEquals(listOf("user-1"), reaction?.reactorIds)
        assertEquals("trace.txt", message.attachments.single().name)
        assertEquals(12_288L, message.attachments.single().sizeBytes)
        assertEquals("", message.attachments.single().sizeLabel)
        assertEquals("wire local-first thread cache", message.task?.title)
        assertEquals(TaskStatus.InReview, message.task?.status)
        assertEquals(false, message.task?.canClaim)
        assertEquals(listOf("task-1", "task-channel-1"), restored.tasks.map { it.id.value })
        assertEquals("channel-level task", restored.tasks[1].title)
        assertEquals(TaskStatus.Todo, restored.tasks[1].status)
        assertEquals(true, restored.tasks[1].canClaim)

        val cachedMember = database.queries.selectMemberSnapshot(serverId.value, member.id.value).executeAsOne()
        assertEquals("Artin", cachedMember.displayName)
        assertEquals("human", cachedMember.kind)
        assertEquals(1234L, cachedMember.lastSyncedAtMs)
    }

    @Test
    fun readsMessageTaskWhenTaskIdMatchesMessageId() = runBlocking {
        val database = createDatabase()
        val store =
            SqlDelightComposeLocalDataStore(
                database = database,
                fallback = InMemoryComposeLocalDataStore(),
                currentServerId = { serverId },
                clock = { 1234L }
            )
        val task =
            TaskSummary(
                id = TaskId("message-1"),
                title = "created as task",
                status = TaskStatus.Todo,
                taskNumber = 111,
                channelId = ChannelId("channel-1"),
                updatedAtLabel = "21:03"
            )

        store.writeThread(
            ThreadSnapshot(
                conversation =
                    ConversationSummary(
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        kind = ConversationKind.Channel,
                        name = "general"
                    ),
                messages =
                    listOf(
                        Message(
                            id = MessageId("message-1"),
                            threadId = ThreadId("channel-1"),
                            channelId = ChannelId("channel-1"),
                            seq = 8,
                            author = member,
                            timestampLabel = "21:03",
                            body = "created as task"
                        )
                    ),
                tasks = listOf(task),
                lastSeq = 8
            )
        )

        val restored = assertNotNull(store.readThread(ThreadId("channel-1")))
        assertEquals(null, restored.messages.single().task)
        assertEquals(listOf("message-1"), restored.tasks.map { it.id.value })
    }

    @Test
    fun readsReplyThreadByStableThreadIdentityFromNormalizedTables() = runBlocking {
        val database = createDatabase()
        val store =
            SqlDelightComposeLocalDataStore(
                database = database,
                fallback = InMemoryComposeLocalDataStore(),
                currentServerId = { serverId }
            )
        val parentMessage =
            Message(
                id = MessageId("parent-1"),
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                threadChannelId = ChannelId("thread-channel-1"),
                seq = 1,
                author = member,
                timestampLabel = "20:00",
                body = "parent",
                replyCount = 1
            )
        val threadId = threadIdentity(ChannelId("channel-1"), parentMessage.id)

        store.writeThread(
            ThreadSnapshot(
                conversation =
                    ConversationSummary(
                        threadId = threadId,
                        channelId = ChannelId("channel-1"),
                        thread =
                            ThreadConversation(
                                parentChannelId = ChannelId("channel-1"),
                                parentMessageId = parentMessage.id,
                                streamChannelId = ChannelId("thread-channel-1")
                            ),
                        kind = ConversationKind.Thread,
                        name = "general"
                    ),
                parentMessage = parentMessage,
                messages =
                    listOf(
                        Message(
                            id = MessageId("reply-1"),
                            threadId = ThreadId("thread-channel-1"),
                            channelId = ChannelId("thread-channel-1"),
                            parentMessageId = parentMessage.id,
                            seq = 2,
                            author = member,
                            timestampLabel = "20:01",
                            body = "reply"
                        )
                    ),
                lastSeq = 2
            )
        )

        val restored = assertNotNull(store.readThread(threadId))
        assertEquals(threadId, restored.conversation.threadId)
        assertEquals(ChannelId("thread-channel-1"), restored.conversation.thread?.streamChannelId)
        assertEquals("parent", restored.parentMessage?.body)
        assertEquals("reply", restored.messages.single().body)
    }

    @Test
    fun writeThreadPreservesReactionOrderAcrossNormalizedReadback() = runBlocking {
        val database = createDatabase()
        val store =
            SqlDelightComposeLocalDataStore(
                database = database,
                fallback = InMemoryComposeLocalDataStore(),
                currentServerId = { serverId },
                clock = { 42L }
            )
        val reactions =
            listOf(
                MessageReaction(
                    emoji = "👍",
                    count = 1,
                    reactorIds = listOf("member-1")
                ),
                MessageReaction(
                    emoji = "🔥",
                    count = 1,
                    reactorIds = listOf("member-2")
                ),
                MessageReaction(
                    emoji = "✅",
                    count = 1,
                    reactorIds = listOf("member-3")
                )
            )

        store.writeThread(
            ThreadSnapshot(
                conversation =
                    ConversationSummary(
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        kind = ConversationKind.Channel,
                        name = "general"
                    ),
                messages =
                    listOf(
                        Message(
                            id = MessageId("message-1"),
                            threadId = ThreadId("channel-1"),
                            channelId = ChannelId("channel-1"),
                            seq = 7,
                            author = member,
                            timestampLabel = "21:00",
                            body = "cached body",
                            reactions = reactions
                        )
                    ),
                lastSeq = 7
            )
        )

        val restored = assertNotNull(store.readThread(ThreadId("channel-1")))
        assertEquals(listOf("👍", "🔥", "✅"), restored.messages.single().reactions?.map { it.emoji })
    }

    @Test
    fun writeHomeDoesNotPersistConversationDirectoryRowsAndAliases() = runBlocking {
        val database = createDatabase()
        val store =
            SqlDelightComposeLocalDataStore(
                database = database,
                fallback = InMemoryComposeLocalDataStore(),
                currentServerId = { serverId },
                clock = { 42L }
            )

        store.writeHome(
            HomeSnapshot(
                server = ServerSummary(serverId, name = "Slock"),
                currentMember = member,
                channels =
                    listOf(
                        ConversationSummary(
                            threadId = ThreadId("channel-1"),
                            channelId = ChannelId("channel-1"),
                            kind = ConversationKind.Channel,
                            name = "general"
                        ).toHomeChannelRow()
                    ),
                directMessages =
                    listOf(
                        ConversationSummary(
                            threadId = ThreadId("dm-1"),
                            channelId = ChannelId("dm-1"),
                            kind = ConversationKind.DirectMessage,
                            name = "Codex Agent",
                            directMessagePeerType = "agent",
                            presenceMemberId = MemberId("agent-1")
                        ).toHomeDirectMessageRow()
                    ),
                unreadTotal = 0
            )
        )

        // writeHome is a no-op since the durable home snapshot blob was removed
        // (fb845a19 "fix(home): remove home snapshot blob cache"): nothing is
        // persisted or readable back, and no directory rows or aliases appear.
        assertNull(store.readHome(serverId))
        assertNull(store.readThread(ThreadId("channel-1")))
        assertNull(store.readThread(ThreadId("dm-1")))
        assertEquals(emptyList(), database.queries.selectConversationDirectory(serverId.value).executeAsList())
    }

    @Test
    fun homeChannelReplyMetadataDoesNotPersistReplyThreadAliases() = runBlocking {
        val database = createDatabase()
        val store =
            SqlDelightComposeLocalDataStore(
                database = database,
                fallback = InMemoryComposeLocalDataStore(),
                currentServerId = { serverId },
                clock = { 42L }
            )

        store.writeHome(
            HomeSnapshot(
                server = ServerSummary(serverId, name = "Slock"),
                currentMember = member,
                channels =
                    listOf(
                        ConversationSummary(
                            threadId = ThreadId("channel-1"),
                            channelId = ChannelId("channel-1"),
                            kind = ConversationKind.Channel,
                            name = "general",
                            thread =
                                ThreadConversation(
                                    parentChannelId = ChannelId("channel-1"),
                                    parentMessageId = MessageId("parent-1"),
                                    streamChannelId = ChannelId("reply-thread-1")
                                )
                        ).toHomeChannelRow()
                    ),
                directMessages = emptyList(),
                unreadTotal = 0
            )
        )

        // writeHome is a no-op since the durable home snapshot blob was removed
        // (fb845a19): reply-thread metadata on a home channel row can no longer
        // leak thread aliases or conversation-directory rows into SQLDelight.
        assertNull(store.readHome(serverId))
        assertNull(store.readThread(ThreadId("reply-thread-1")))
        assertEquals(emptyList(), database.queries.selectConversationDirectory(serverId.value).executeAsList())
    }

    @Test
    fun writeThreadDoesNotWriteConversationDirectoryRowsForReplyThread() = runBlocking {
        val database = createDatabase()
        val store =
            SqlDelightComposeLocalDataStore(
                database = database,
                fallback = InMemoryComposeLocalDataStore(),
                currentServerId = { serverId },
                clock = { 42L }
            )
        val parentMessage =
            Message(
                id = MessageId("message-1"),
                threadId = ThreadId("parent-channel"),
                channelId = ChannelId("parent-channel"),
                seq = 1,
                author = member,
                timestampLabel = "20:00",
                body = "parent"
            )
        val threadId = threadIdentity(ChannelId("parent-channel"), parentMessage.id)
        val snapshot =
            ThreadSnapshot(
                conversation =
                    ConversationSummary(
                        threadId = threadId,
                        channelId = ChannelId("parent-channel"),
                        thread =
                            ThreadConversation(
                                parentChannelId = ChannelId("parent-channel"),
                                parentMessageId = parentMessage.id,
                                streamChannelId = ChannelId("reply-stream-1")
                            ),
                        kind = ConversationKind.Thread,
                        name = "Thread"
                    ),
                parentMessage = parentMessage,
                messages = emptyList(),
                lastSeq = 1
            )

        store.writeThread(snapshot)
        store.writeThread(snapshot)

        val restored = assertNotNull(store.readThread(threadId))
        assertEquals("Thread", restored.conversation.name)
        assertEquals(emptyList(), database.queries.selectConversationDirectory(serverId.value).executeAsList())
    }

    private fun createDatabase(): SlockSqlDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SlockSqlDatabase.Schema.create(driver).value
        return SlockSqlDatabase(driver)
    }

    private companion object {
        val serverId = ServerId("server-1")
        val member =
            MemberProfile(
                id = MemberId("member-1"),
                displayName = "Artin",
                handle = "artin",
                kind = MemberKind.Human,
                presence = Presence.Active
            )
    }
}
