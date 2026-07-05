package build.raft.app.data

import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.MemberPresenceUpdated
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadId
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadUpdated
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.TaskUpdated
import build.raft.app.model.threadIdentity
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComposeLocalWriteThroughTest {
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
        assertEquals("thread-channel-1", cached.conversation.name)
        assertNull(cached.conversation.subtitle)
        assertEquals(parent.id, cached.parentMessage?.id)
    }

    @Test
    fun writeThreadResultRejectsMismatchedFallbackMetadata() = runBlocking {
        val logs = mutableListOf<String>()
        val store = InMemoryComposeLocalDataStore()
        val writer = ComposeLocalWriteThrough(store, logger = logs::add)
        val parent =
            message(
                id = "parent-1",
                threadId = "channel-1",
                channelId = "channel-1",
                threadChannelId = "thread-channel-1",
                replyCount = 2
            )
        val reply = message("reply-1", "thread-channel-1", "thread-channel-1", seq = 2)
        val threadId = threadIdentity(ChannelId("channel-1"), MessageId("parent-1"))
        val snapshot =
            ThreadSnapshot(
                conversation =
                    ConversationSummary(
                        threadId = threadId,
                        channelId = ChannelId("channel-1"),
                        kind = ConversationKind.Thread,
                        thread =
                            ThreadConversation(
                                parentChannelId = ChannelId("channel-1"),
                                parentMessageId = MessageId("parent-1"),
                                streamChannelId = ChannelId("thread-channel-1")
                            ),
                        name = "Thread"
                    ),
                messages = listOf(reply),
                parentMessage = parent,
                lastSeq = 2
            )
        val wrongHeader =
            ConversationSummary(
                threadId = ThreadId("channel-wrong"),
                channelId = ChannelId("channel-wrong"),
                kind = ConversationKind.Channel,
                name = "wrong-channel",
                subtitle = "Wrong subtitle",
                lastMessagePreview = "wrong preview",
                unreadCount = 9,
                unreadCountKnown = true
            )
        val wrongParent =
            message(
                id = "parent-wrong",
                threadId = "channel-wrong",
                channelId = "channel-wrong",
                threadChannelId = "wrong-thread",
                body = "wrong parent"
            )

        writer.writeThreadResult(
            DataResult.Content(snapshot),
            source = "test_wrong_fallback",
            fallbackThreadId = ThreadId("wrong-thread"),
            fallbackHeader = wrongHeader,
            fallbackParentMessage = wrongParent
        )

        val cached = store.readThread(threadId)
        assertNotNull(cached)
        assertEquals("Thread", cached.conversation.name)
        assertNull(cached.conversation.subtitle)
        assertNull(cached.conversation.lastMessagePreview)
        assertEquals(0, cached.conversation.unreadCount)
        assertEquals(false, cached.conversation.unreadCountKnown)
        assertEquals("parent-1", cached.parentMessage?.id?.value)
        assertEquals("parent-1", cached.messages.first().id.value)
        assertNull(store.readThread(ThreadId("wrong-thread")))
        assertTrue(logs.any { it.startsWith("write_thread_fallback_skip source=test_wrong_fallback reason=header_unproven") })
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
        assertEquals(1, logs.count { it.startsWith("store_merge_patch domain=thread source=test_thread") })
        assertEquals(1, logs.count { it.startsWith("store_merge_skip domain=thread source=test_thread reason=unchanged") })
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
        assertTrue(logs.any { it.contains("store_merge_patch domain=thread source=test_thread") && it.contains("timelineChanged=false") && it.contains("changedMessageIds=[m1]") })
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
        assertTrue(logs.any { it.contains("store_merge_patch domain=thread source=test_pagination") && it.contains("timelineChanged=true") && it.contains("changedMessageIds=[m2]") })
    }

    @Test
    fun realtimeMessageUpdatesCachedThreadWithoutTouchingHomeRows() = runBlocking {
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
        // Realtime home-row merges moved into SharedHomeStore (ac50c376) and the
        // durable home snapshot blob was removed (fb845a19): the local
        // write-through no longer patches cached home previews or unread counts.
        val homeChannel = store.readHome(serverId)?.channels?.single()
        assertNull(homeChannel?.lastMessagePreview)
        assertEquals(0, homeChannel?.unreadCount)
    }

    @Test
    fun syncResumeMergesThreadMessagesWithoutTouchingHomeUnread() = runBlocking {
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
            build.raft.app.model.SyncResumeResponse(
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

        val cachedThread = store.readThread(ThreadId("channel-1"))
        assertEquals(listOf("old", "new-1", "new-2", "new-3"), cachedThread?.messages?.map { it.id.value })
        assertEquals(4, cachedThread?.lastSeq)
        // Sync-resume no longer projects previews or unread counts onto cached
        // home rows: realtime home merges live in SharedHomeStore (ac50c376) and
        // the durable home snapshot blob was removed (fb845a19).
        val cached = store.readHome(serverId)
        val homeChannel = cached?.channels?.single()
        assertNull(homeChannel?.lastMessagePreview)
        assertEquals(0, homeChannel?.unreadCount)
        assertEquals(0, cached?.unreadTotal)
        assertNull(homeChannel?.firstUnreadMessageId)
    }

    @Test
    fun activeConversationDoesNotIncrementHomeUnread() = runBlocking {
        val store = InMemoryComposeLocalDataStore()
        val writer = ComposeLocalWriteThrough(store)
        val channel = channel()
        val otherMember = member.copy(id = MemberId("member-2"), displayName = "Cindy", handle = "cindy")
        store.writeHome(home(channels = listOf(channel)))

        val wrote =
            writer.writeRealtimeEvent(
                serverId,
                MessageNew(message("new", "channel-1", "channel-1", seq = 2, body = "visible", author = otherMember)),
                activeConversationId = "channel-1"
            )

        // The write-through no longer projects realtime previews onto cached
        // home rows (ac50c376 moved home merges into SharedHomeStore, fb845a19
        // removed the durable home snapshot blob), and with no cached thread
        // snapshot there is nothing to merge at all.
        assertEquals(false, wrote)
        val cached = store.readHome(serverId)
        val homeChannel = cached?.channels?.single()
        assertNull(homeChannel?.lastMessagePreview)
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
        assertTrue(logs.any { it.contains("store_merge_patch domain=thread source=realtime_message") && it.contains("changedMessageIds=[m1]") })
    }

    @Test
    fun threadMetadataDoesNotPatchCachedParentMessageFooterFacts() = runBlocking {
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
        assertEquals(false, wrote)
        assertEquals(0, cached?.parentMessage?.replyCount)
        assertEquals(0, cachedMessages[0].replyCount)
        assertEquals(0, cachedMessages[0].unreadReplyCount)
        assertTrue(cachedMessages[1] === reply)
        assertTrue(logs.none { it.contains("store_merge_patch domain=thread source=thread_metadata") })
    }

    @Test
    fun realtimeTaskEventDoesNotPatchThreadSnapshotTaskFacts() = runBlocking {
        val logs = mutableListOf<String>()
        val store = InMemoryComposeLocalDataStore()
        val writer = ComposeLocalWriteThrough(store, logger = logs::add)
        val existingTask = task("task-1", "Old one", TaskStatus.Todo)
        store.writeThread(
            ThreadSnapshot(
                conversation = channel(),
                messages = listOf(message("m1", "channel-1", "channel-1", seq = 1)),
                tasks = listOf(existingTask),
                lastSeq = 1
            )
        )

        val wrote =
            writer.writeRealtimeEvent(
                serverId,
                TaskUpdated(existingTask.copy(title = "Updated one", status = TaskStatus.Done)),
                activeConversationId = "channel-1"
            )

        val cachedTasks = store.readThread(ThreadId("channel-1"))?.tasks.orEmpty()
        assertEquals(false, wrote)
        assertEquals(listOf("task-1"), cachedTasks.map { it.id.value })
        assertEquals("Old one", cachedTasks.single().title)
        assertEquals(TaskStatus.Todo, cachedTasks.single().status)
        assertTrue(logs.none { it.contains("store_merge_patch domain=thread source=realtime_task") })
    }

    @Test
    fun realtimeBatchMergesSameThreadMessagesButNotThreadMetadataOrTasks() = runBlocking {
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
        assertEquals(0, cached?.messages?.first()?.replyCount)
        assertEquals(0, cached?.messages?.first()?.unreadReplyCount)
        assertEquals("Old task", cached?.tasks?.single()?.title)
        assertEquals(TaskStatus.Todo, cached?.tasks?.single()?.status)
        assertEquals(1, logs.count { it.contains("store_merge_patch domain=thread source=realtime_message") })
        assertEquals(0, logs.count { it.contains("store_merge_patch domain=thread source=thread_metadata") })
        assertEquals(0, logs.count { it.contains("store_merge_patch domain=thread source=realtime_task") })
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
        assertEquals(MemberId("agent-1"), cached?.presenceMemberId)
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
                            presenceMemberId = MemberId("agent-1")
                        ),
                        ConversationSummary(
                            threadId = ThreadId("dm-2"),
                            channelId = ChannelId("dm-2"),
                            kind = ConversationKind.DirectMessage,
                            name = "Agent Two",
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
        assertEquals(MemberId("agent-1"), cached[0].presenceMemberId)
        assertEquals(MemberId("agent-2"), cached[1].presenceMemberId)
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
        // Mention counts are no longer cleared by the local unread clear: they
        // are owned by the activity persist slice since b3971efd ("fix(activity):
        // persist cached counts through mvi").
        assertEquals(1, channel?.mentionCount)
        assertEquals(null, channel?.firstUnreadMessageId)
        assertEquals(0, cached?.unreadTotal)
    }

    @Test
    fun clearServerUnreadClearsUnreadSnapshotWithoutTouchingHomeRows() = runBlocking {
        val store = InMemoryComposeLocalDataStore()
        val writer = ComposeLocalWriteThrough(store)
        store.writeUnread(
            ChannelUnreadSnapshot(
                server = server,
                unreadCounts =
                    mapOf(
                        ChannelId("channel-1") to 3,
                        ChannelId("dm-1") to 2
                    ),
                totalUnreadCount = 5
            )
        )
        store.writeHome(
            home(
                channels =
                    listOf(
                        channel("channel-1").copy(
                            unreadCount = 3,
                            unreadCountKnown = true,
                            mentionCount = 1,
                            mentionCountKnown = true,
                            firstUnreadMessageId = MessageId("message-1")
                        )
                    ),
                directMessages =
                    listOf(
                        ConversationSummary(
                            threadId = ThreadId("dm-1"),
                            channelId = ChannelId("dm-1"),
                            kind = ConversationKind.DirectMessage,
                            name = "DM",
                            unreadCount = 2,
                            unreadCountKnown = true,
                            mentionCount = 1,
                            mentionCountKnown = true,
                            firstUnreadMessageId = MessageId("message-2")
                        )
                    )
            ).copy(unreadTotal = 5)
        )

        writer.clearServerUnread(serverId, "test_mark_all")

        val unread = store.readUnread(serverId)
        val cached = store.readHome(serverId)
        assertEquals(emptyMap(), unread?.unreadCounts)
        assertEquals(0, unread?.totalUnreadCount)
        // Cached home rows are no longer patched by the write-through: the home
        // snapshot blob cache was removed in fb845a19 ("fix(home): remove home
        // snapshot blob cache"); home rows converge through SharedHomeStore.
        assertEquals(5, cached?.unreadTotal)
        val cachedChannel = cached?.channels?.single()
        assertEquals(3, cachedChannel?.unreadCount)
        assertEquals(1, cachedChannel?.mentionCount)
        assertEquals(MessageId("message-1"), cachedChannel?.firstUnreadMessageId)
        val cachedDm = cached?.directMessages?.single()
        assertEquals(2, cachedDm?.unreadCount)
        assertEquals(MessageId("message-2"), cachedDm?.firstUnreadMessageId)
    }

    @Test
    fun writeUnreadCountsStoresUnreadSnapshotWithoutTouchingHomeRows() = runBlocking {
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

        // The unread snapshot is stored, but cached home rows are left untouched:
        // home-row unread projection moved out of the write-through when the home
        // snapshot blob cache was removed (fb845a19).
        val unread = store.readUnread(serverId)
        assertEquals(mapOf(ChannelId("channel-1") to 3), unread?.unreadCounts)
        assertEquals(3, unread?.totalUnreadCount)
        val cached = store.readHome(serverId)
        val after = cached?.channels.orEmpty()
        assertEquals(0, after[0].unreadCount)
        assertEquals(0, after[1].unreadCount)
        assertEquals(0, cached?.unreadTotal)
        assertTrue(before[0] === after[0])
        assertTrue(before[1] === after[1])
    }

    @Test
    fun heartbeatDoesNotRewriteSnapshots() = runBlocking {
        val logs = mutableListOf<String>()
        val store = InMemoryComposeLocalDataStore()
        val writer = ComposeLocalWriteThrough(store, logger = logs::add)

        writer.writeRealtimeEvent(serverId, build.raft.app.model.Heartbeat(seq = 42))

        assertNull(store.readHome(serverId))
        assertTrue(logs.any { it == "write_realtime_skip type=heartbeat seq=42" })
        assertTrue(logs.any { it.startsWith("store_merge domain=realtime source=events") && it.contains("wrote=false") })
    }

    private fun home(
        channels: List<ConversationSummary> = listOf(channel()),
        directMessages: List<ConversationSummary> = emptyList()
    ): HomeSnapshot =
        HomeSnapshot(
            server = server,
            currentMember = member,
            channels = channels.map { it.toHomeChannelRow() },
            directMessages = directMessages.map { it.toHomeDirectMessageRow() },
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
