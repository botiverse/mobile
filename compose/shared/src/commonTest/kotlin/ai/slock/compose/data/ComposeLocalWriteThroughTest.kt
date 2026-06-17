package ai.slock.compose.data

import ai.slock.compose.model.ChannelId
import ai.slock.compose.model.ConversationKind
import ai.slock.compose.model.ConversationSummary
import ai.slock.compose.model.MemberId
import ai.slock.compose.model.MemberKind
import ai.slock.compose.model.MemberProfile
import ai.slock.compose.model.Message
import ai.slock.compose.model.MessageId
import ai.slock.compose.model.MessageNew
import ai.slock.compose.model.MessageUpdated
import ai.slock.compose.model.MemberPresenceUpdated
import ai.slock.compose.model.Presence
import ai.slock.compose.model.ServerId
import ai.slock.compose.model.ServerSummary
import ai.slock.compose.model.ThreadId
import ai.slock.compose.model.ThreadUpdated
import ai.slock.compose.model.TaskId
import ai.slock.compose.model.TaskStatus
import ai.slock.compose.model.TaskSummary
import ai.slock.compose.model.TaskUpdated
import ai.slock.compose.model.threadIdentity
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComposeLocalWriteThroughTest {
    @Test
    fun writeHomeResultPreservesStoredDirectMessagePresence() = runBlocking {
        val store = InMemoryComposeLocalDataStore()
        val writer = ComposeLocalWriteThrough(store)
        val dm = ConversationSummary(
            threadId = ThreadId("dm-1"),
            channelId = ChannelId("dm-1"),
            kind = ConversationKind.DirectMessage,
            name = "Agent",
            presence = Presence.Active,
            presenceMemberId = MemberId("agent-1")
        )
        store.writeHome(home(directMessages = listOf(dm)))

        writer.writeHomeResult(
            DataResult.Content(
                home(
                    directMessages =
                        listOf(
                            dm.copy(
                                presence = null,
                                presenceMemberId = null,
                                lastMessagePreview = "new"
                            )
                        )
                )
            ),
            source = "test"
        )

        val cached = store.readHome(serverId)?.directMessages?.single()
        assertEquals(Presence.Active, cached?.presence)
        assertEquals(MemberId("agent-1"), cached?.presenceMemberId)
        assertEquals("new", cached?.lastMessagePreview)
    }

    @Test
    fun duplicateHomeSnapshotWriteIsSkipped() = runBlocking {
        val logs = mutableListOf<String>()
        val store = InMemoryComposeLocalDataStore()
        val writer = ComposeLocalWriteThrough(store, logger = logs::add)
        val snapshot = home(channels = listOf(channel(), channel("channel-2")))

        writer.writeHomeResult(DataResult.Content(snapshot), source = "test_home")
        writer.writeHomeResult(DataResult.Content(snapshot), source = "test_home")

        assertEquals(listOf("channel-1", "channel-2"), store.readHome(serverId)?.channels?.map { it.threadId.value })
        assertEquals(1, logs.count { it.startsWith("write_home source=test_home") })
        assertEquals(1, logs.count { it.startsWith("write_home_skip source=test_home reason=unchanged") })
    }

    @Test
    fun writeReplyThreadResultRequiresAndStoresParentContext() = runBlocking {
        val store = InMemoryComposeLocalDataStore()
        val writer = ComposeLocalWriteThrough(store)
        val parent = message(
            id = "parent-1",
            threadId = "channel-1",
            channelId = "channel-1",
            threadChannelId = "thread-channel-1",
            replyCount = 4
        )
        val rawThread =
            ConversationSummary(
                threadId = ThreadId("thread-channel-1"),
                channelId = ChannelId("thread-channel-1"),
                kind = ConversationKind.Channel,
                name = "thread-channel-1"
            )
        val fallbackHeader =
            ConversationSummary(
                threadId = ThreadId("channel-1"),
                channelId = ChannelId("channel-1"),
                kind = ConversationKind.Channel,
                name = "general"
            )

        writer.writeReplyThreadResult(
            DataResult.Content(
                ThreadSnapshot(
                    conversation = rawThread,
                    messages = listOf(message("reply-1", "thread-channel-1", "thread-channel-1", seq = 2)),
                    parentMessage = null
                )
            ),
            source = "created_reply_thread",
            fallbackThreadId = ThreadId("thread-channel-1"),
            fallbackHeader = fallbackHeader,
            fallbackParentMessage = parent
        )

        val cached = store.readThread(threadIdentity(ChannelId("channel-1"), MessageId("parent-1")))
        assertNotNull(cached)
        assertEquals(ConversationKind.Thread, cached.conversation.kind)
        assertEquals(ChannelId("channel-1"), cached.conversation.channelId)
        assertEquals(ChannelId("thread-channel-1"), cached.conversation.thread?.streamChannelId)
        assertEquals(parent.id, cached.parentMessage?.id)
    }

    @Test
    fun duplicateThreadSnapshotWriteIsSkipped() = runBlocking {
        val logs = mutableListOf<String>()
        val store = InMemoryComposeLocalDataStore()
        val writer = ComposeLocalWriteThrough(store, logger = logs::add)
        val snapshot =
            ThreadSnapshot(
                conversation = channel(),
                messages = listOf(message("m1", "channel-1", "channel-1", seq = 1)),
                lastSeq = 1
            )

        writer.writeThreadResult(DataResult.Content(snapshot), source = "test_thread")
        writer.writeThreadResult(DataResult.Content(snapshot), source = "test_thread")

        assertEquals(snapshot.messages.single().id, store.readThread(ThreadId("channel-1"))?.messages?.single()?.id)
        assertEquals(1, logs.count { it.startsWith("thread_patch source=test_thread") })
        assertEquals(1, logs.count { it.startsWith("write_thread_skip source=test_thread reason=unchanged") })
    }

    @Test
    fun threadSnapshotWriteReducesToChangedMessagePatchBeforeStoreWrite() = runBlocking {
        val logs = mutableListOf<String>()
        val store = InMemoryComposeLocalDataStore()
        val writer = ComposeLocalWriteThrough(store, logger = logs::add)
        val firstMessage = message("m1", "channel-1", "channel-1", seq = 1, body = "old body")
        val secondMessage = message("m2", "channel-1", "channel-1", seq = 2, body = "same body")
        store.writeThread(
            ThreadSnapshot(
                conversation = channel(),
                messages = listOf(firstMessage, secondMessage),
                lastSeq = 2
            )
        )
        val incoming =
            ThreadSnapshot(
                conversation = channel(),
                messages =
                    listOf(
                        firstMessage.copy(body = "edited body"),
                        secondMessage.copy()
                    ),
                lastSeq = 2
            )

        writer.writeThreadResult(DataResult.Content(incoming), source = "test_thread")

        val cachedMessages = store.readThread(ThreadId("channel-1"))?.messages.orEmpty()
        assertEquals("edited body", cachedMessages[0].body)
        assertTrue(cachedMessages[0] !== firstMessage)
        assertTrue(cachedMessages[1] === secondMessage)
        assertTrue(logs.any { it.contains("thread_patch source=test_thread") && it.contains("timelineChanged=false") && it.contains("changedMessageIds=[m1]") })
    }

    @Test
    fun paginationAppendKeepsExistingMessageEntityReferences() = runBlocking {
        val logs = mutableListOf<String>()
        val store = InMemoryComposeLocalDataStore()
        val writer = ComposeLocalWriteThrough(store, logger = logs::add)
        val firstMessage = message("m1", "channel-1", "channel-1", seq = 1)
        val secondMessage = message("m2", "channel-1", "channel-1", seq = 2)
        store.writeThread(
            ThreadSnapshot(
                conversation = channel(),
                messages = listOf(firstMessage),
                lastSeq = 1
            )
        )

        writer.writeThreadResult(
            DataResult.Content(
                ThreadSnapshot(
                    conversation = channel(),
                    messages = listOf(firstMessage.copy(), secondMessage),
                    lastSeq = 2
                )
            ),
            source = "test_pagination"
        )

        val cachedMessages = store.readThread(ThreadId("channel-1"))?.messages.orEmpty()
        assertEquals(listOf("m1", "m2"), cachedMessages.map { it.id.value })
        assertTrue(cachedMessages[0] === firstMessage)
        assertTrue(cachedMessages[1] === secondMessage)
        assertTrue(logs.any { it.contains("thread_patch source=test_pagination") && it.contains("timelineChanged=true") && it.contains("changedMessageIds=[m2]") })
    }

    @Test
    fun realtimeMessageUpdatesCachedThreadAndHomePreview() = runBlocking {
        val store = InMemoryComposeLocalDataStore()
        val writer = ComposeLocalWriteThrough(store)
        val channel = channel()
        val otherMember = member.copy(id = MemberId("member-2"), displayName = "Cindy", handle = "cindy")
        store.writeHome(home(channels = listOf(channel)))
        store.writeThread(
            ThreadSnapshot(
                conversation = channel,
                messages = listOf(message("old", "channel-1", "channel-1", seq = 1))
            )
        )

        writer.writeRealtimeEvent(
            serverId,
            MessageNew(message("new", "channel-1", "channel-1", seq = 2, body = "new body", author = otherMember))
        )

        val thread = store.readThread(ThreadId("channel-1"))
        assertEquals(listOf("old", "new"), thread?.messages?.map { it.id.value })
        val homeChannel = store.readHome(serverId)?.channels?.single()
        assertEquals("new body", homeChannel?.lastMessagePreview)
        assertEquals(1, homeChannel?.unreadCount)
    }

    @Test
    fun syncResumeCountsOnlyNewNonSelfMessagesForHomeUnread() = runBlocking {
        val store = InMemoryComposeLocalDataStore()
        val writer = ComposeLocalWriteThrough(store)
        val channel = channel().copy(lastMessageId = MessageId("old"))
        val otherMember = member.copy(id = MemberId("member-2"), displayName = "Cindy", handle = "cindy")
        store.writeHome(home(channels = listOf(channel)))
        store.writeThread(
            ThreadSnapshot(
                conversation = channel,
                messages = listOf(message("old", "channel-1", "channel-1", seq = 1)),
                lastSeq = 1
            )
        )

        writer.writeRealtimeEvent(
            serverId,
            ai.slock.compose.model.SyncResumeResponse(
                messages =
                    listOf(
                        message("old", "channel-1", "channel-1", seq = 1, body = "duplicate", author = otherMember),
                        message("new-1", "channel-1", "channel-1", seq = 2, body = "first", author = otherMember),
                        message("new-2", "channel-1", "channel-1", seq = 3, body = "self", author = member),
                        message("new-3", "channel-1", "channel-1", seq = 4, body = "latest", author = otherMember)
                    ),
                currentSeq = 4,
                hasMore = false
            )
        )

        val cached = store.readHome(serverId)
        val homeChannel = cached?.channels?.single()
        assertEquals("latest", homeChannel?.lastMessagePreview)
        assertEquals(2, homeChannel?.unreadCount)
        assertEquals(2, cached?.unreadTotal)
        assertEquals("new-1", homeChannel?.firstUnreadMessageId?.value)
    }

    @Test
    fun activeConversationDoesNotIncrementHomeUnread() = runBlocking {
        val store = InMemoryComposeLocalDataStore()
        val writer = ComposeLocalWriteThrough(store)
        val channel = channel()
        val otherMember = member.copy(id = MemberId("member-2"), displayName = "Cindy", handle = "cindy")
        store.writeHome(home(channels = listOf(channel)))

        writer.writeRealtimeEvent(
            serverId,
            MessageNew(message("new", "channel-1", "channel-1", seq = 2, body = "visible", author = otherMember)),
            activeConversationId = "channel-1"
        )

        val cached = store.readHome(serverId)
        val homeChannel = cached?.channels?.single()
        assertEquals("visible", homeChannel?.lastMessagePreview)
        assertEquals(0, homeChannel?.unreadCount)
        assertEquals(false, homeChannel?.unreadCountKnown)
        assertEquals(0, cached?.unreadTotal)
    }

    @Test
    fun realtimeMessageUpdateUsesSameThreadPatchReducer() = runBlocking {
        val logs = mutableListOf<String>()
        val store = InMemoryComposeLocalDataStore()
        val writer = ComposeLocalWriteThrough(store, logger = logs::add)
        val firstMessage = message("m1", "channel-1", "channel-1", seq = 1, body = "old body")
        val secondMessage = message("m2", "channel-1", "channel-1", seq = 2, body = "same body")
        store.writeHome(home(channels = listOf(channel())))
        store.writeThread(
            ThreadSnapshot(
                conversation = channel(),
                messages = listOf(firstMessage, secondMessage),
                lastSeq = 2
            )
        )

        val wrote =
            writer.writeRealtimeEvent(
                serverId,
                MessageUpdated(firstMessage.copy(body = "edited body"))
            )

        val cachedMessages = store.readThread(ThreadId("channel-1"))?.messages.orEmpty()
        assertEquals(true, wrote)
        assertEquals("edited body", cachedMessages[0].body)
        assertTrue(cachedMessages[1] === secondMessage)
        assertTrue(logs.any { it.contains("thread_patch source=realtime_message") && it.contains("changedMessageIds=[m1]") })
    }

    @Test
    fun threadMetadataPatchesCachedParentMessageWithoutReplacingTimeline() = runBlocking {
        val logs = mutableListOf<String>()
        val store = InMemoryComposeLocalDataStore()
        val writer = ComposeLocalWriteThrough(store, logger = logs::add)
        val parent = message("parent-1", "channel-1", "channel-1", seq = 1, body = "parent")
        val reply = message("reply-1", "thread-parent-1", "thread-parent-1", seq = 2, body = "reply")
        val threadId = threadIdentity(ChannelId("channel-1"), MessageId("parent-1"))
        store.writeHome(home(channels = listOf(channel().copy(lastMessageId = MessageId("parent-1")))))
        store.writeThread(
            ThreadSnapshot(
                conversation =
                    channel("thread-parent-1").copy(
                        threadId = threadId,
                        channelId = ChannelId("channel-1"),
                        kind = ConversationKind.Thread
                    ),
                messages = listOf(parent, reply),
                parentMessage = parent,
                lastSeq = 2
            )
        )

        val wrote =
            writer.writeRealtimeEvent(
                serverId,
                ThreadUpdated(
                    parentMessageId = MessageId("parent-1"),
                    threadChannelId = ChannelId("thread-parent-1"),
                    replyCount = 4,
                    lastReplyAt = "12:05",
                    participantIds = emptyList(),
                    unreadCount = 2,
                    firstUnreadMessageId = MessageId("reply-1")
                )
            )

        val cached = store.readThread(threadId)
        val cachedMessages = cached?.messages.orEmpty()
        assertEquals(true, wrote)
        assertEquals(4, cached?.parentMessage?.replyCount)
        assertEquals(4, cachedMessages[0].replyCount)
        assertTrue(cachedMessages[1] === reply)
        assertTrue(logs.any { it.contains("thread_patch source=thread_metadata") && it.contains("timelineChanged=false") && it.contains("changedMessageIds=[parent-1]") })
    }

    @Test
    fun realtimeTaskPatchKeepsExistingTaskReferences() = runBlocking {
        val logs = mutableListOf<String>()
        val store = InMemoryComposeLocalDataStore()
        val writer = ComposeLocalWriteThrough(store, logger = logs::add)
        val taskOne =
            task(
                id = "task-1",
                title = "Old one",
                status = TaskStatus.Todo
            )
        val taskTwo =
            task(
                id = "task-2",
                title = "Same two",
                status = TaskStatus.Todo
            )
        store.writeThread(
            ThreadSnapshot(
                conversation = channel(),
                messages = listOf(message("m1", "channel-1", "channel-1", seq = 1)),
                tasks = listOf(taskOne, taskTwo),
                lastSeq = 1
            )
        )

        writer.writeRealtimeEvent(
            serverId,
            ai.slock.compose.model.TaskUpdated(taskOne.copy(title = "Updated one", status = TaskStatus.Done))
        )

        val cachedTasks = store.readThread(ThreadId("channel-1"))?.tasks.orEmpty()
        assertEquals("Updated one", cachedTasks[0].title)
        assertEquals(TaskStatus.Done, cachedTasks[0].status)
        assertTrue(cachedTasks[0] !== taskOne)
        assertTrue(cachedTasks[1] === taskTwo)
        assertTrue(logs.any { it.contains("thread_patch source=realtime_task") && it.contains("changedTaskIds=[task-1]") })
    }

    @Test
    fun realtimeBatchMergesSameThreadMessagesMetadataAndTasks() = runBlocking {
        val logs = mutableListOf<String>()
        val store = InMemoryComposeLocalDataStore()
        val writer = ComposeLocalWriteThrough(store, logger = logs::add)
        val parent = message("parent-1", "channel-1", "channel-1", seq = 1, body = "parent")
        val existingTask = task("task-1", "Old task", TaskStatus.Todo)
        store.writeHome(home(channels = listOf(channel().copy(lastMessageId = MessageId("parent-1")))))
        store.writeThread(
            ThreadSnapshot(
                conversation = channel(),
                messages = listOf(parent),
                tasks = listOf(existingTask),
                lastSeq = 1
            )
        )

        writer.writeRealtimeEvents(
            serverId,
            listOf(
                MessageNew(message("m2", "channel-1", "channel-1", seq = 2, body = "second")),
                MessageNew(message("m3", "channel-1", "channel-1", seq = 3, body = "third")),
                ThreadUpdated(
                    parentMessageId = MessageId("parent-1"),
                    threadChannelId = ChannelId("thread-parent-1"),
                    replyCount = 2,
                    lastReplyAt = "12:05",
                    participantIds = emptyList(),
                    unreadCount = 1
                ),
                ThreadUpdated(
                    parentMessageId = MessageId("parent-1"),
                    threadChannelId = ChannelId("thread-parent-1"),
                    replyCount = 3,
                    lastReplyAt = "12:06",
                    participantIds = emptyList(),
                    unreadCount = 2
                ),
                TaskUpdated(existingTask.copy(title = "Updated task", status = TaskStatus.InReview))
            )
        )

        val cached = store.readThread(ThreadId("channel-1"))
        assertEquals(listOf("parent-1", "m2", "m3"), cached?.messages?.map { it.id.value })
        assertEquals(3, cached?.lastSeq)
        assertEquals(3, cached?.messages?.first()?.replyCount)
        assertEquals(2, cached?.messages?.first()?.unreadReplyCount)
        assertEquals("Updated task", cached?.tasks?.single()?.title)
        assertEquals(TaskStatus.InReview, cached?.tasks?.single()?.status)
        assertEquals(1, logs.count { it.contains("thread_patch source=realtime_message") })
        assertEquals(1, logs.count { it.contains("thread_patch source=thread_metadata") })
        assertEquals(1, logs.count { it.contains("thread_patch source=realtime_task") })
    }

    @Test
    fun realtimePresenceDoesNotMutateCachedDirectMessage() = runBlocking {
        val store = InMemoryComposeLocalDataStore()
        val writer = ComposeLocalWriteThrough(store)
        val dm =
            ConversationSummary(
                threadId = ThreadId("dm-1"),
                channelId = ChannelId("dm-1"),
                kind = ConversationKind.DirectMessage,
                name = "Agent",
                presence = Presence.Offline,
                presenceMemberId = MemberId("agent-1")
            )
        store.writeHome(home(directMessages = listOf(dm)))

        writer.writeRealtimeEvent(
            serverId,
            MemberPresenceUpdated(
                memberId = MemberId("agent-1"),
                presence = Presence.Active,
                kind = MemberKind.Agent
            )
        )

        val cached = store.readHome(serverId)?.directMessages?.single()
        assertEquals(Presence.Offline, cached?.presence)
    }

    @Test
    fun realtimePresenceBatchSkipsHomeCacheWrites() = runBlocking {
        val logs = mutableListOf<String>()
        val store = InMemoryComposeLocalDataStore()
        val writer = ComposeLocalWriteThrough(store, logger = logs::add)
        store.writeHome(
            home(
                directMessages =
                    listOf(
                        ConversationSummary(
                            threadId = ThreadId("dm-1"),
                            channelId = ChannelId("dm-1"),
                            kind = ConversationKind.DirectMessage,
                            name = "Agent One",
                            presence = Presence.Offline,
                            presenceMemberId = MemberId("agent-1")
                        ),
                        ConversationSummary(
                            threadId = ThreadId("dm-2"),
                            channelId = ChannelId("dm-2"),
                            kind = ConversationKind.DirectMessage,
                            name = "Agent Two",
                            presence = Presence.Offline,
                            presenceMemberId = MemberId("agent-2")
                        )
                    )
            )
        )

        writer.writeRealtimeEvents(
            serverId,
            listOf(
                MemberPresenceUpdated(
                    memberId = MemberId("agent-1"),
                    presence = Presence.Active,
                    kind = MemberKind.Agent
                ),
                MemberPresenceUpdated(
                    memberId = MemberId("agent-2"),
                    presence = Presence.Away,
                    kind = MemberKind.Agent
                )
            )
        )

        val cached = store.readHome(serverId)?.directMessages.orEmpty()
        assertEquals(Presence.Offline, cached[0].presence)
        assertEquals(Presence.Offline, cached[1].presence)
        assertEquals(emptyList(), logs.filter { it.startsWith("write_home_realtime source=presence_batch") })
    }

    @Test
    fun clearConversationUnreadUpdatesCachedHomeRow() = runBlocking {
        val store = InMemoryComposeLocalDataStore()
        val writer = ComposeLocalWriteThrough(store)
        store.writeHome(
            home(
                channels =
                    listOf(
                        channel().copy(
                            unreadCount = 3,
                            unreadCountKnown = true,
                            mentionCount = 1,
                            mentionCountKnown = true,
                            firstUnreadMessageId = MessageId("message-1")
                        )
                    )
            ).copy(unreadTotal = 3)
        )

        writer.clearConversationUnread(ChannelId("channel-1"), "test", serverId)

        val cached = store.readHome(serverId)
        val channel = cached?.channels?.single()
        assertEquals(0, channel?.unreadCount)
        assertEquals(true, channel?.unreadCountKnown)
        assertEquals(0, channel?.mentionCount)
        assertEquals(null, channel?.firstUnreadMessageId)
        assertEquals(0, cached?.unreadTotal)
    }

    @Test
    fun writeUnreadCountsOnlyReplacesMatchingHomeRows() = runBlocking {
        val store = InMemoryComposeLocalDataStore()
        val writer = ComposeLocalWriteThrough(store)
        store.writeHome(
            home(
                channels =
                    listOf(
                        channel("channel-1").copy(unreadCount = 0, unreadCountKnown = true),
                        channel("channel-2").copy(unreadCount = 0, unreadCountKnown = true)
                    )
            )
        )
        val before = store.readHome(serverId)?.channels.orEmpty()

        writer.writeUnreadCounts(
            DataResult.Content(
                ChannelUnreadSnapshot(
                    server = server,
                    unreadCounts = mapOf(ChannelId("channel-1") to 3),
                    totalUnreadCount = 3
                )
            ),
            source = "test_unread"
        )

        val cached = store.readHome(serverId)
        val after = cached?.channels.orEmpty()
        assertEquals(3, after[0].unreadCount)
        assertEquals(0, after[1].unreadCount)
        assertEquals(3, cached?.unreadTotal)
        assertTrue(before[0] !== after[0])
        assertTrue(before[1] === after[1])
    }

    @Test
    fun heartbeatDoesNotRewriteSnapshots() = runBlocking {
        val logs = mutableListOf<String>()
        val store = InMemoryComposeLocalDataStore()
        val writer = ComposeLocalWriteThrough(store, logger = logs::add)

        writer.writeRealtimeEvent(serverId, ai.slock.compose.model.Heartbeat(seq = 42))

        assertNull(store.readHome(serverId))
        assertEquals(listOf("write_realtime_skip type=heartbeat seq=42"), logs)
    }

    private fun home(
        channels: List<ConversationSummary> = listOf(channel()),
        directMessages: List<ConversationSummary> = emptyList()
    ): HomeSnapshot =
        HomeSnapshot(
            server = server,
            currentMember = member,
            channels = channels,
            directMessages = directMessages,
            unreadTotal = 0
        )

    private fun channel(id: String = "channel-1"): ConversationSummary =
        ConversationSummary(
            threadId = ThreadId(id),
            channelId = ChannelId(id),
            kind = ConversationKind.Channel,
            name = if (id == "channel-1") "general" else id
        )

    private fun message(
        id: String,
        threadId: String,
        channelId: String,
        threadChannelId: String? = null,
        seq: Long? = null,
        body: String = id,
        replyCount: Int = 0,
        author: MemberProfile = member
    ): Message =
        Message(
            id = MessageId(id),
            threadId = ThreadId(threadId),
            channelId = ChannelId(channelId),
            threadChannelId = threadChannelId?.let(::ChannelId),
            seq = seq,
            author = author,
            timestampLabel = "12:00",
            body = body,
            replyCount = replyCount
        )

    private fun task(
        id: String,
        title: String,
        status: TaskStatus,
        threadId: String = "channel-1"
    ): TaskSummary =
        TaskSummary(
            id = TaskId(id),
            title = title,
            status = status,
            taskNumber = id.substringAfter("task-").toIntOrNull(),
            threadId = ThreadId(threadId),
            updatedAtLabel = "12:00"
        )

    private companion object {
        val serverId = ServerId("server-1")
        val server = ServerSummary(id = serverId, name = "Slock")
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
