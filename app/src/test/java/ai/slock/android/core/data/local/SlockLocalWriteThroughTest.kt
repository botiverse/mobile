package ai.slock.android.core.data.local

import ai.slock.compose.data.DataResult
import ai.slock.android.core.data.repository.ChannelUnreadSnapshot
import ai.slock.android.core.data.repository.HomeSnapshot
import ai.slock.android.core.data.repository.SendMessageResult
import ai.slock.android.core.data.repository.ThreadSnapshot
import ai.slock.android.core.model.Attachment
import ai.slock.android.core.model.AttachmentId
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
import ai.slock.android.core.model.MessageType
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
import ai.slock.compose.model.AvatarReference
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
        val server = ServerSummary(id = ServerId("server-1"), name = "Slock", unreadCount = 7)
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
        assertEquals(7, cached?.server?.unreadCount)
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
    fun writeAgentDirectMessagePresenceUpdatesHomeProjection() {
        val store = createInMemoryStore()
        val writer = SlockLocalWriteThrough(store, clock = { 42L })
        val server = ServerSummary(id = ServerId("server-1"), name = "Slock")
        val currentMember = memberProfile("user-1", MemberKind.Human)
        writer.writeHomeResult(
            DataResult.Content(
                HomeSnapshot(
                    server = server,
                    currentMember = currentMember,
                    channels = emptyList(),
                    directMessages =
                        listOf(
                            ConversationSummary(
                                threadId = ThreadId("dm-1"),
                                channelId = ChannelId("dm-1"),
                                kind = ConversationKind.DirectMessage,
                                name = "Android-Developer",
                                presenceMemberId = MemberId("agent-android")
                            )
                        ),
                    unreadTotal = 0
                )
            ),
            source = "seed_home"
        )

        val changed =
            writer.writeAgentDirectMessagePresence(
                serverId = ServerId("server-1"),
                members =
                    listOf(
                        memberProfile("agent-android", MemberKind.Agent).copy(
                            displayName = "Android-Developer",
                            handle = "Android-Developer",
                            presence = ai.slock.android.core.model.Presence.Active,
                            agentStatus = ai.slock.android.core.model.AgentStatus.Thinking
                        )
                    ),
                source = "members_presence_home"
            )

        val cached =
            writer.readHomeSnapshot(
                serverId = ServerId("server-1"),
                serverFallback = server,
                currentMemberFallback = currentMember
            )

        assertEquals(true, changed)
        assertEquals(
            ai.slock.android.core.model.Presence.Active,
            cached?.directMessages?.single()?.presence
        )
        assertEquals(
            ai.slock.android.core.model.AgentStatus.Thinking,
            cached?.directMessages?.single()?.agentStatus
        )
        assertEquals("agent-android", cached?.directMessages?.single()?.presenceMemberId?.value)
    }

    @Test
    fun writeAgentDirectMessagePresenceCopiesAvatarIntoHomeProjection() {
        val store = createInMemoryStore()
        val writer = SlockLocalWriteThrough(store, clock = { 42L })
        val server = ServerSummary(id = ServerId("server-1"), name = "Slock")
        val currentMember = memberProfile("user-1", MemberKind.Human)
        writer.writeHomeResult(
            DataResult.Content(
                HomeSnapshot(
                    server = server,
                    currentMember = currentMember,
                    channels = emptyList(),
                    directMessages =
                        listOf(
                            ConversationSummary(
                                threadId = ThreadId("dm-1"),
                                channelId = ChannelId("dm-1"),
                                kind = ConversationKind.DirectMessage,
                                name = "Android-Developer",
                                presenceMemberId = MemberId("agent-android")
                            )
                        ),
                    unreadTotal = 0
                )
            ),
            source = "seed_home"
        )

        writer.writeAgentDirectMessagePresence(
            serverId = ServerId("server-1"),
            members =
                listOf(
                    memberProfile("agent-android", MemberKind.Agent).copy(
                        displayName = "Android-Developer",
                        handle = "Android-Developer",
                        avatarUrl = "pixel:robot",
                        avatar = AvatarReference.Pixel("robot")
                    )
                ),
            source = "members_presence_home"
        )

        val cached =
            writer.readHomeSnapshot(
                serverId = ServerId("server-1"),
                serverFallback = server,
                currentMemberFallback = currentMember
            )

        assertEquals("pixel:robot", cached?.directMessages?.single()?.avatarUrl)
        assertEquals("pixel:robot", cached?.directMessages?.single()?.avatar?.wireValue)
    }

    @Test
    fun writeHomeResultPreservesUnreadWhenRefreshOmitsUnreadFields() {
        val store = createInMemoryStore()
        val writer = SlockLocalWriteThrough(store, clock = { 42L })
        val server = ServerSummary(id = ServerId("server-1"), name = "Slock")
        val currentMember = memberProfile("user-1", MemberKind.Human)
        val unreadChannel =
            conversation("channel-1").copy(
                name = "all",
                unreadCount = 3,
                unreadCountKnown = true,
                mentionCount = 1,
                mentionCountKnown = true,
                firstUnreadMessageId = MessageId("message-1")
            )
        writer.writeHomeResult(
            DataResult.Content(
                HomeSnapshot(
                    server = server,
                    currentMember = currentMember,
                    channels = listOf(unreadChannel),
                    directMessages = emptyList(),
                    unreadTotal = 3
                )
            ),
            source = "seed_home"
        )

        writer.writeHomeResult(
            DataResult.Content(
                HomeSnapshot(
                    server = server,
                    currentMember = currentMember,
                    channels =
                        listOf(
                            conversation("channel-1").copy(
                                name = "all refreshed without unread fields"
                            )
                        ),
                    directMessages = emptyList(),
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

        assertEquals(3, cached?.channels?.single()?.unreadCount)
        assertEquals(1, cached?.channels?.single()?.mentionCount)
        assertEquals("message-1", cached?.channels?.single()?.firstUnreadMessageId?.value)
        assertEquals(3, cached?.unreadTotal)
    }

    @Test
    fun writeUnreadCountsUpdatesHomeProjectionAndClearsMissingRows() {
        val store = createInMemoryStore()
        val writer = SlockLocalWriteThrough(store, clock = { 42L })
        val server = ServerSummary(id = ServerId("server-1"), name = "Slock", unreadCount = 24)
        val currentMember = memberProfile("user-1", MemberKind.Human)
        val channel =
            conversation("channel-1").copy(
                unreadCount = 3,
                unreadCountKnown = true,
                firstUnreadMessageId = MessageId("message-1")
            )
        val dm =
            ConversationSummary(
                threadId = ThreadId("dm-1"),
                channelId = ChannelId("dm-1"),
                kind = ConversationKind.DirectMessage,
                name = "Cindy",
                unreadCount = 2,
                unreadCountKnown = true,
                firstUnreadMessageId = MessageId("dm-message-1")
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
            source = "seed_home"
        )

        writer.writeUnreadCounts(
            DataResult.Content(
                ChannelUnreadSnapshot(
                    server = server,
                    unreadCounts = mapOf(ChannelId("channel-1") to 0)
                )
            ),
            source = "reconcile"
        )

        val cached =
            writer.readHomeSnapshot(
                serverId = ServerId("server-1"),
                serverFallback = server,
                currentMemberFallback = currentMember
            )

        assertEquals(0, cached?.channels?.single()?.unreadCount)
        assertEquals(null, cached?.channels?.single()?.firstUnreadMessageId)
        assertEquals(0, cached?.directMessages?.single()?.unreadCount)
        assertEquals(null, cached?.directMessages?.single()?.firstUnreadMessageId)
        assertEquals(0, cached?.unreadTotal)
        assertEquals(24, cached?.server?.unreadCount)
    }

    @Test
    fun readHomeSnapshotDoesNotPromoteChannelUnreadTotalToServerUnread() {
        val store = createInMemoryStore()
        val writer = SlockLocalWriteThrough(store, clock = { 42L })
        val server = ServerSummary(id = ServerId("server-1"), name = "Slock", unreadCount = 24)
        val currentMember = memberProfile("user-1", MemberKind.Human)
        writer.writeHomeResult(
            DataResult.Content(
                HomeSnapshot(
                    server = server,
                    currentMember = currentMember,
                    channels =
                        listOf(
                            conversation("channel-1").copy(unreadCount = 60),
                            conversation("channel-2").copy(unreadCount = 1)
                        ),
                    directMessages = emptyList(),
                    unreadTotal = 61
                )
            ),
            source = "seed_home"
        )

        val cached =
            writer.readHomeSnapshot(
                serverId = ServerId("server-1"),
                serverFallback = server,
                currentMemberFallback = currentMember
            )

        assertEquals(61, cached?.unreadTotal)
        assertEquals(24, cached?.server?.unreadCount)
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

        writer.clearConversationUnread(ChannelId("thread-1"), source = "test_read", serverId = ServerId("server-1"))

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

        val messages = store.getMessagesForThread(ThreadId("thread-1"), ServerId("server-1"))
        val task = store.getTaskReferencesForMessage(MessageId("message-1"), ServerId("server-1")).single()

        assertEquals("message-1", messages.single().id.value)
        assertEquals(9L, store.getSyncCursor("thread-1", ServerId("server-1"))?.latestSeq)
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
            source = "seed_thread",
            serverId = ServerId("server-1")
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

        val threadMessage = store.getMessagesForThread(ThreadId("thread-1"), ServerId("server-1")).single()
        val threadState = writer.readThreadUiState(ThreadId("thread-1"), serverId = ServerId("server-1"))
        val content =
            threadState?.loadState as ai.slock.android.core.state.ScreenLoadState.Content

        assertEquals("thread-replies-1", threadMessage.threadChannelId?.value)
        assertEquals(4, threadMessage.replyCount)
        assertEquals("Now", threadMessage.updatedAtLabel)
        assertTrue(store.getTaskReferencesForMessage(MessageId("message-1"), ServerId("server-1")).isEmpty())
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
    fun threadRefreshPreservesStoredAttachmentsWhenSnapshotOmitsThem() {
        val store = createInMemoryStore()
        val writer = SlockLocalWriteThrough(store, clock = { 44L })
        val attachment =
            Attachment(
                id = AttachmentId("attachment-1"),
                name = "screenshot.jpg",
                mimeType = "image/jpeg",
                sizeLabel = "42 KB",
                thumbnailUrl = "https://cdn.example/screenshot.jpg"
            )
        writer.writeSendResult(
            DataResult.Content(
                SendMessageResult(
                    message("message-with-image", seq = 10).copy(attachments = listOf(attachment))
                )
            ),
            source = "send"
        )

        writer.writeThreadResult(
            DataResult.Content(
                ThreadSnapshot(
                    conversation = conversation("thread-1"),
                    messages =
                        listOf(
                            message("message-with-image", seq = 10).copy(
                                body = "refreshed",
                                attachments = emptyList()
                            )
                        )
                )
            ),
            source = "refresh"
        )

        val cached = store.getMessagesForThread(ThreadId("thread-1")).single()

        assertEquals("refreshed", cached.body)
        assertEquals(listOf("attachment-1"), cached.attachments.map { it.id.value })
        assertEquals("screenshot.jpg", cached.attachments.single().name)
        assertEquals("https://cdn.example/screenshot.jpg", cached.attachments.single().thumbnailUrl)
    }

    @Test
    fun threadRefreshPreservesReplyMetadataWhenSnapshotOmitsIt() {
        val store = createInMemoryStore()
        val writer = SlockLocalWriteThrough(store, clock = { 44L })
        writer.writeThreadResult(
            DataResult.Content(
                ThreadSnapshot(
                    conversation = conversation("thread-1"),
                    messages =
                        listOf(
                            message("message-with-task-replies", seq = 10).copy(
                                task = task("task-1").copy(
                                    messageId = MessageId("message-with-task-replies")
                                ),
                                threadChannelId = ChannelId("thread-replies-1"),
                                replyCount = 3
                            )
                        )
                )
            ),
            source = "initial"
        )

        writer.writeThreadResult(
            DataResult.Content(
                ThreadSnapshot(
                    conversation = conversation("thread-1"),
                    messages =
                        listOf(
                            message("message-with-task-replies", seq = 10).copy(
                                task = task("task-1").copy(
                                    status = TaskStatus.Done,
                                    messageId = MessageId("message-with-task-replies")
                                ),
                                replyCount = 0,
                                threadChannelId = null
                            )
                        )
                )
            ),
            source = "task_status_refresh"
        )

        val cached = store.getMessagesForThread(ThreadId("thread-1")).single()

        assertEquals(TaskStatus.Done, cached.task?.status)
        assertEquals("thread-replies-1", cached.threadChannelId?.value)
        assertEquals(3, cached.replyCount)
    }

    @Test
    fun threadRefreshPreservesStoredMessageContentWhenSnapshotOmitsFields() {
        val store = createInMemoryStore()
        val writer = SlockLocalWriteThrough(store, clock = { 44L })
        val storedMessage =
            message("message-rich", seq = 10).copy(
                author =
                    memberProfile("user-real", MemberKind.Human).copy(
                        displayName = "Real User",
                        handle = "real-user",
                        description = "Original author"
                    ),
                timestampLabel = "2026-05-13T11:30:00Z",
                body = "important body",
                messageType = MessageType.System,
                systemEventType = "task:updated",
                metadata = mapOf("taskId" to "task-1", "to" to "done"),
                parentMessageId = MessageId("parent-1"),
                updatedAtLabel = "2026-05-13T11:31:00Z"
            )
        writer.writeThreadResult(
            DataResult.Content(
                ThreadSnapshot(
                    conversation = conversation("thread-1"),
                    messages = listOf(storedMessage)
                )
            ),
            source = "seed_rich_message"
        )

        writer.writeThreadResult(
            DataResult.Content(
                ThreadSnapshot(
                    conversation = conversation("thread-1"),
                    messages =
                        listOf(
                            message("message-rich", seq = 11).copy(
                                seq = null,
                                author = memberProfile("unknown-author", MemberKind.Human),
                                timestampLabel = "",
                                body = "",
                                messageType = MessageType.Chat,
                                systemEventType = null,
                                metadata = emptyMap(),
                                parentMessageId = null,
                                updatedAtLabel = null
                            )
                        )
                )
            ),
            source = "sparse_refresh"
        )

        val cached = store.getMessagesForThread(ThreadId("thread-1")).single()

        assertEquals(10L, cached.seq)
        assertEquals("user-real", cached.author.id.value)
        assertEquals("Real User", cached.author.displayName)
        assertEquals("real-user", cached.author.handle)
        assertEquals("Original author", cached.author.description)
        assertEquals("2026-05-13T11:30:00Z", cached.timestampLabel)
        assertEquals("important body", cached.body)
        assertEquals(MessageType.System, cached.messageType)
        assertEquals("task:updated", cached.systemEventType)
        assertEquals("task-1", cached.metadata["taskId"])
        assertEquals("done", cached.metadata["to"])
        assertEquals("parent-1", cached.parentMessageId?.value)
        assertEquals("2026-05-13T11:31:00Z", cached.updatedAtLabel)
    }

    @Test
    fun threadProjectionEnrichesMessageAuthorsFromMemberSnapshots() {
        val store = createInMemoryStore()
        val writer = SlockLocalWriteThrough(store, clock = { 44L })
        store.upsertMemberSnapshot(
            memberProfile("agent-1", MemberKind.Agent).copy(
                displayName = "Android Developer",
                handle = "Android-Developer-2",
                avatarUrl = "pixel:diamond",
                gravatarHash = "abc123",
                avatar = AvatarReference.fromWireValue("pixel:diamond"),
                avatarLabel = "AD"
            ),
            syncedAtMs = 1L
        )
        assertEquals("AD", store.getMemberSnapshot(MemberId("agent-1"))?.avatarLabel)

        writer.writeThreadResult(
            DataResult.Content(
                ThreadSnapshot(
                    conversation = conversation("thread-1"),
                    messages =
                        listOf(
                            message("message-agent", seq = 10).copy(
                                author = memberProfile("agent-1", MemberKind.Agent)
                            )
                        )
                )
            ),
            source = "sparse_thread"
        )

        val cached = store.getMessagesForThread(ThreadId("thread-1")).single()

        assertEquals("Android Developer", cached.author.displayName)
        assertEquals("Android-Developer-2", cached.author.handle)
        assertEquals("pixel:diamond", cached.author.avatarUrl)
        assertEquals("abc123", cached.author.gravatarHash)
        assertEquals(AvatarReference.fromWireValue("pixel:diamond"), cached.author.avatar)
        assertEquals("AD", cached.author.avatarLabel)
    }

    @Test
    fun threadProjectionPreservesTaskAssigneeSnapshotsAcrossSparseUpdates() {
        val store = createInMemoryStore()
        val writer = SlockLocalWriteThrough(store, clock = { 44L })
        val richAssignee =
            memberProfile("agent-1", MemberKind.Agent).copy(
                displayName = "Android Developer",
                handle = "Android-Developer-2",
                avatarUrl = "pixel:diamond",
                avatar = AvatarReference.fromWireValue("pixel:diamond"),
                avatarLabel = "AD"
            )
        writer.writeThreadResult(
            DataResult.Content(
                ThreadSnapshot(
                    conversation = conversation("thread-1"),
                    messages =
                        listOf(
                            message("message-with-task", seq = 10).copy(
                                task =
                                    task("task-1").copy(
                                        messageId = MessageId("message-with-task"),
                                        assignee = richAssignee
                                    )
                            )
                        )
                )
            ),
            source = "initial_task"
        )

        writer.writeThreadResult(
            DataResult.Content(
                ThreadSnapshot(
                    conversation = conversation("thread-1"),
                    messages =
                        listOf(
                            message("message-with-task", seq = 10).copy(
                                task =
                                    task("task-1").copy(
                                        status = TaskStatus.Done,
                                        messageId = MessageId("message-with-task"),
                                        assignee = memberProfile("agent-1", MemberKind.Agent)
                                    )
                            )
                        )
                )
            ),
            source = "sparse_task_update"
        )

        val cached = store.getMessagesForThread(ThreadId("thread-1")).single()
        val assignee = cached.task?.assignee

        assertEquals(TaskStatus.Done, cached.task?.status)
        assertEquals("Android Developer", assignee?.displayName)
        assertEquals("Android-Developer-2", assignee?.handle)
        assertEquals("pixel:diamond", assignee?.avatarUrl)
        assertEquals(AvatarReference.fromWireValue("pixel:diamond"), assignee?.avatar)
        assertEquals("AD", assignee?.avatarLabel)
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
    fun writeThreadResultHydratesReplyThreadOriginFromFallbackParentMessage() {
        val store = createInMemoryStore()
        val writer = SlockLocalWriteThrough(store, clock = { 44L })
        val replyThreadId = ThreadId("reply-1")
        val parentMessage =
            message("parent-1", seq = 1).copy(
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                threadChannelId = ChannelId("reply-1")
            )
        val replyMessage =
            message("message-1", seq = 2).copy(
                threadId = replyThreadId,
                channelId = ChannelId("reply-1")
            )

        writer.writeThreadResult(
            DataResult.Content(
                ThreadSnapshot(
                    conversation = conversation("reply-1").copy(name = "reply-1"),
                    messages = listOf(replyMessage)
                )
            ),
            source = "reply_thread",
            fallbackThreadId = replyThreadId,
            fallbackParentMessage = parentMessage
        )

        val cached = store.getThreadTimeline(replyThreadId)

        assertEquals("parent-1", cached?.parentMessage?.id?.value)
        assertEquals("channel-1:parent-1", cached?.conversation?.threadId?.value)
        assertEquals("channel-1", cached?.conversation?.thread?.parentChannelId?.value)
        assertEquals("reply-1", cached?.conversation?.thread?.streamChannelId?.value)
    }

    @Test
    fun writeThreadResultPreservesReplyThreadOriginAcrossRefreshes() {
        val store = createInMemoryStore()
        val writer = SlockLocalWriteThrough(store, clock = { 44L })
        val replyThreadId = ThreadId("reply-1")
        val parentMessage =
            message("parent-1", seq = 1).copy(
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                threadChannelId = ChannelId("reply-1")
            )

        writer.writeThreadResult(
            DataResult.Content(
                ThreadSnapshot(
                    conversation = conversation("reply-1").copy(name = "reply-1"),
                    messages =
                        listOf(
                            message("message-1", seq = 2).copy(
                                threadId = replyThreadId,
                                channelId = ChannelId("reply-1")
                            )
                        )
                )
            ),
            source = "seed_reply_thread",
            fallbackThreadId = replyThreadId,
            fallbackParentMessage = parentMessage
        )

        writer.writeThreadResult(
            DataResult.Content(
                ThreadSnapshot(
                    conversation = conversation("reply-1").copy(name = "reply-1 updated"),
                    messages =
                        listOf(
                            message("message-2", seq = 3).copy(
                                threadId = replyThreadId,
                                channelId = ChannelId("reply-1")
                            )
                        )
                )
            ),
            source = "refresh_reply_thread",
            fallbackThreadId = replyThreadId
        )

        val cached = store.getThreadTimeline(replyThreadId)

        assertEquals("parent-1", cached?.parentMessage?.id?.value)
        assertEquals("channel-1:parent-1", cached?.conversation?.threadId?.value)
        assertEquals(
            listOf("message-1", "message-2"),
            cached?.messages?.map { it.id.value }
        )
    }

    @Test
    fun createdReplyThreadWriteBackStoresParentMetadataWithoutHomeChannelRow() {
        val store = createInMemoryStore()
        val writer = SlockLocalWriteThrough(store, clock = { 44L })
        val server = ServerSummary(id = ServerId("server-1"), name = "Slock")
        val currentMember = memberProfile("user-1", MemberKind.Human)
        val parentChannel =
            conversation("channel-1").copy(
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                name = "markdown"
            )
        val parentMessage =
            message("parent-1", seq = 1).copy(
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                threadChannelId = ChannelId("9b4930e4-4988-4284-a691-449965df7c82"),
                replyCount = 32
            )
        val replyMessage =
            message("reply-1", seq = 2).copy(
                threadId = ThreadId("9b4930e4-4988-4284-a691-449965df7c82"),
                channelId = ChannelId("9b4930e4-4988-4284-a691-449965df7c82")
            )

        writer.writeHomeResult(
            DataResult.Content(
                HomeSnapshot(
                    server = server,
                    currentMember = currentMember,
                    channels = listOf(parentChannel),
                    directMessages = emptyList(),
                    unreadTotal = 0
                )
            ),
            source = "seed_home"
        )
        writer.writeReplyThreadResult(
            DataResult.Content(
                ThreadSnapshot(
                    conversation =
                        conversation("9b4930e4-4988-4284-a691-449965df7c82").copy(
                            name = "9b4930e4-4988-4284-a691-449965df7c82"
                        ),
                    messages = listOf(replyMessage)
                )
            ),
            source = "open_created_reply_thread",
            fallbackThreadId = ThreadId("9b4930e4-4988-4284-a691-449965df7c82"),
            fallbackHeader = parentChannel,
            fallbackParentMessage = parentMessage,
            serverId = ServerId("server-1")
        )

        val home = writer.readHomeSnapshot(ServerId("server-1"), server, currentMember)
        val cachedParent =
            store.getMessagesForThread(ThreadId("channel-1"), ServerId("server-1"))
                .single { it.id == parentMessage.id }

        assertEquals(listOf("markdown"), home?.channels?.map { it.name })
        assertEquals("9b4930e4-4988-4284-a691-449965df7c82", cachedParent.threadChannelId?.value)
        assertEquals(32, cachedParent.replyCount)
    }

    @Test
    fun writeReplyThreadResultRequiresParentContext() {
        val method =
            SlockLocalWriteThrough::class.java.methods.single { method ->
                method.name == "writeReplyThreadResult"
            }

        assertEquals(
            listOf(
                "DataResult",
                "String",
                "ThreadId",
                "ConversationSummary",
                "Message",
                "ServerId"
            ),
            method.parameterTypes.map { it.simpleName }
        )
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
    fun readTaskSnapshotDeletesOnlyKnownLegacySampleTasks() {
        val store = createInMemoryStore()
        val writer = SlockLocalWriteThrough(store, clock = { 46L })
        val realTaskWithSampleText =
            task("real-task-1").copy(
                title = "Verify sample import",
                channelId = ChannelId("thread-1"),
                threadId = ThreadId("thread-1"),
                channelName = "sample-review"
            )
        store.upsertTaskReference(task("sample-task"), syncedAtMs = 1L, serverId = ServerId("server-1"))
        store.upsertTaskReference(realTaskWithSampleText, syncedAtMs = 2L, serverId = ServerId("server-1"))

        val snapshot = writer.readTaskSnapshot(ServerId("server-1"))

        assertEquals(listOf("real-task-1"), snapshot?.tasks?.map { it.id.value })
        assertEquals(listOf(realTaskWithSampleText), store.getTaskReferences(ServerId("server-1")))
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
            source = "test_initial",
            serverId = serverId
        )

        // Start observing the timeline
        val flow = store.observeThreadTimeline(threadId, serverId)

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
