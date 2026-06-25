package ai.slock.compose.data

import ai.slock.compose.model.Attachment
import ai.slock.compose.model.AttachmentId
import ai.slock.compose.model.AgentStatus
import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Message
import ai.slock.compose.model.MessageDeliveryState
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.MessageReaction
import ai.slock.compose.model.Presence
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerSummary
import ai.slock.compose.model.TaskId
import ai.slock.compose.model.TaskStatus
import ai.slock.compose.model.TaskSummary
import ai.slock.compose.model.ThreadId
import ai.slock.compose.model.ThreadConversation
import ai.slock.compose.model.threadIdentity
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
                                        sizeLabel = "12 KB"
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
        assertEquals("message-1", restored.messages.single().task?.id?.value)
        assertEquals(TaskStatus.Todo, restored.messages.single().task?.status)
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
    fun writeHomePersistsConversationDirectoryRowsAndAliases() = runBlocking {
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
                        )
                    ),
                directMessages =
                    listOf(
                        ConversationSummary(
                            threadId = ThreadId("dm-1"),
                            channelId = ChannelId("dm-1"),
                            kind = ConversationKind.DirectMessage,
                            name = "Codex Agent",
                            directMessagePeerType = "agent",
                            presence = Presence.Active,
                            agentStatus = AgentStatus.Working,
                            presenceMemberId = MemberId("agent-1")
                        )
                    ),
                unreadTotal = 0
            )
        )

        val directory = SharedConversationDirectoryStore(database)
        assertEquals("general", assertNotNull(directory.resolveProjection(serverId, "channel-1")).title)
        val dmByMember = assertNotNull(directory.resolveProjection(serverId, "agent-1"))
        assertEquals("Codex Agent", dmByMember.title)
        assertEquals(AgentStatus.Working, dmByMember.agentStatus)
    }

    @Test
    fun genericThreadRefreshDoesNotDowngradeStoredJointChannelProjection() = runBlocking {
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
                            threadId = ThreadId("joint-1"),
                            channelId = ChannelId("joint-1"),
                            kind = ConversationKind.Joint,
                            name = "raft-mobile",
                            subtitle = "Mobile bridge"
                        )
                    )
            )
        )
        store.writeThread(
            ThreadSnapshot(
                conversation =
                    ConversationSummary(
                        threadId = ThreadId("joint-1"),
                        channelId = ChannelId("joint-1"),
                        kind = ConversationKind.Channel,
                        name = "Channel"
                    ),
                messages = emptyList()
            )
        )

        val projection = assertNotNull(SharedConversationDirectoryStore(database).resolveProjection(serverId, "joint-1"))
        assertEquals("joint", projection.channelKind)
        assertEquals("raft-mobile", projection.title)
        assertEquals("Mobile bridge", projection.subtitle)
    }

    @Test
    fun writeThreadRewritesStaleParentChannelAliasForReplyThread() = runBlocking {
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
        database.queries.upsertConversationAlias(
            serverId = serverId.value,
            aliasId = "parent-channel",
            aliasKind = SharedConversationAliasKind.Channel.name,
            canonicalChannelId = "reply-stream-1",
            lastSyncedAtMs = 1L
        )
        store.writeThread(snapshot)

        val directory = SharedConversationDirectoryStore(database)
        val state = directory.observeServer(serverId).first()
        assertEquals(emptyMap(), state.duplicateAliases)
        assertEquals(null, directory.resolveProjection(serverId, "parent-channel"))
        assertEquals("Thread", assertNotNull(directory.resolveProjection(serverId, "reply-stream-1")).title)
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
