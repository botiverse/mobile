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
                tasks = listOf(task),
                lastSeq = 7
            )

        store.writeThread(snapshot)

        val restored = assertNotNull(store.readThread(ThreadId("channel-1")))
        assertEquals("general", restored.conversation.name)
        assertEquals(3, restored.conversation.unreadCount)
        assertEquals(1, restored.conversation.mentionCount)
        assertEquals(7, restored.lastSeq)
        val message = restored.messages.single()
        assertEquals("cached body", message.body)
        assertEquals("test", message.metadata["source"])
        assertEquals("trace.txt", message.attachments.single().name)
        assertEquals("wire local-first thread cache", message.task?.title)
        assertEquals(TaskStatus.InReview, message.task?.status)
        assertEquals(false, message.task?.canClaim)

        val cachedMember = database.queries.selectMemberSnapshot(serverId.value, member.id.value).executeAsOne()
        assertEquals("Artin", cachedMember.displayName)
        assertEquals("human", cachedMember.kind)
        assertEquals(1234L, cachedMember.lastSyncedAtMs)
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
