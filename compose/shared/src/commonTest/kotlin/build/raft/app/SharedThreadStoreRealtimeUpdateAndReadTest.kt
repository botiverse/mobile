package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.replyConversation
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFixtures.cachePayloadForTest
import build.raft.app.SharedThreadStoreTestFixtures.canonicalThreadDataSource
import build.raft.app.SharedThreadStoreTestFixtures.contentPayload
import build.raft.app.SharedThreadStoreTestFixtures.member
import build.raft.app.data.ConversationReadOnSight
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.MessageMutationSummary
import build.raft.app.data.MessageReactionSummary
import build.raft.app.data.PermissionBlocker
import build.raft.app.model.ChannelId
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageReaction
import build.raft.app.model.MessageUpdated
import build.raft.app.model.ThreadId
import build.raft.app.model.ThreadUpdated
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadReactionItem
import build.raft.app.thread.SharedThreadIntent
import build.raft.app.thread.SharedThreadStore
import build.raft.app.thread.ThreadAccessResolution
import build.raft.app.thread.dispatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class SharedThreadStoreRealtimeUpdateAndReadTest {
    @Test
    fun replyCountGapCatchUpPageBringsRepliesStrandedBelowTheVisibleTail() = runTest {
        // task #296: replies stranded below the visible tail seq (backgrounded
        // receipt, initial short load) must come back, otherwise the thread shows
        // "N replies" but only the messages that arrived via realtime. Since task
        // #168 step 1 the catch-up IS the channel's latest page, so one request
        // brings them (no `messageSync` delta, no second tail refetch).
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val allReplies =
            (1..4).map { index ->
                message(
                    threadId = "thread-m1",
                    channelId = "thread-m1",
                    id = "reply-$index",
                    seq = index.toLong(),
                    body = "reply $index"
                )
            }
        // The tail fallback calls `getReplyThread` (the reply-thread resolver),
        // which the fake routes through `threadResultsById[thread-m1]` — first
        // call returns the single newest reply, second returns all four.
        var replyThreadFetchCount = 0
        val repository =
            FakeConversationRepository(
                // Delta sync has nothing newer than the visible tail.
                newerResult = DataResult.Empty(EmptyReason.NoMessages),
                threadResultsById =
                    mapOf(
                        ThreadId("thread-m1") to
                            DataResult.Content(
                                threadSnapshot(
                                    threadId = "thread-m1",
                                    messages = allReplies.takeLast(1),
                                    conversation =
                                        replyConversation()
                                )
                            )
                    ),
                threadResultProvider = { _, _, _ ->
                    replyThreadFetchCount += 1
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            messages = allReplies,
                            conversation = replyConversation()
                        )
                    )
                }
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        val route =
            basePayload().copy(
                instanceId = "shared-shell-thread-thread-m1",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = "thread-m1",
                streamChannelId = "thread-m1",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                parentSurfaceKind = RaftThreadSurfaceKind.Channel,
                parentChannelKind = RaftThreadSurfaceKind.Channel,
                originParentMessageId = "m1",
                showModeTabs = false
            )
        dataSource.cachePayloadForTest(
            route.copy(
                status = "content",
                messages = listOf(
                    RaftThreadMessageItem(
                        id = "reply-4",
                        authorId = "u2",
                        authorName = "Cindy",
                        body = "reply 4",
                        seq = 4
                    )
                ),
                originMessage =
                    RaftThreadMessageItem(
                        id = "m1",
                        authorId = "u1",
                        authorName = "Artin",
                        body = "parent body",
                        threadChannelId = "thread-m1",
                        replyCount = 1
                    ),
                originStatus = "loaded"
            )
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(route))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                ThreadUpdated(
                    parentMessageId = MessageId("m1"),
                    threadChannelId = ChannelId("thread-m1"),
                    replyCount = 4,
                    lastReplyAt = "2026-08-02T00:00:00Z",
                    participantIds = emptyList()
                )
            )
        )

        val payload = store.state.value.payload
        assertEquals(
            listOf("reply-1", "reply-2", "reply-3", "reply-4"),
            payload.messages.map { it.id }
        )
        assertEquals("content", payload.status)
        // No `/messages/sync` delta; the latest page (getThread on this fake) produced the replies.
        assertEquals(0, repository.syncNewerRequests.size)
        assertTrue(repository.getThreadRequests.size >= 1)
        scope.cancel()
    }
    @Test
    fun realtimeMessageUpdatedAppliesReactionPayloadIncludingEmptyRemoval() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource = canonicalThreadDataSource(storeScope = backgroundScope)
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageUpdated(
                    Message(
                        id = MessageId("m1"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        author = member("u2", "Cindy"),
                        timestampLabel = "",
                        body = "cached body",
                        reactions =
                            listOf(
                                MessageReaction(
                                    emoji = "👀",
                                    count = 1,
                                    reactorIds = listOf("u1"),
                                    reactorNames = listOf("Artin")
                                )
                            )
                    )
                )
            )
        )

        assertEquals(
            listOf(
                RaftThreadReactionItem(
                    "👀",
                    count = 1,
                    selected = true,
                    reactorIds = listOf("u1"),
                    reactorNames = listOf("Artin")
                )
            ),
            store.state.value.payload.messages.single().reactions
        )

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageUpdated(
                    Message(
                        id = MessageId("m1"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        author = member("u2", "Cindy"),
                        timestampLabel = "",
                        body = "cached body",
                        reactions = emptyList()
                    )
                )
            )
        )

        assertEquals(emptyList(), store.state.value.payload.messages.single().reactions)
        scope.cancel()
    }
    @Test
    fun realtimeMessageUpdatedDoesNotRevertJustAcknowledgedLocalReactionSelection() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource = canonicalThreadDataSource(storeScope = backgroundScope)
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(SharedThreadIntent.ApplyMessageReactionSelection("m1", "🔥", selected = true))
        store.accept(
            SharedThreadIntent.ApplyMessageMutation(
                MessageMutationSummary(
                    messageId = "m1",
                    reactions =
                        listOf(
                            MessageReactionSummary(
                                emoji = "🔥",
                                count = 1,
                                reactorIds = listOf("u1"),
                                reactorNames = listOf("Artin")
                            )
                        )
                )
            )
        )

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageUpdated(
                    Message(
                        id = MessageId("m1"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        author = member("u2", "Cindy"),
                        timestampLabel = "",
                        body = "server body edited",
                        reactions = emptyList()
                    )
                )
            )
        )

        val shieldedMessage = store.state.value.payload.messages.single()
        assertEquals("server body edited", shieldedMessage.body)
        assertEquals(
            listOf(RaftThreadReactionItem("🔥", count = 1, selected = true, reactorIds = listOf("u1"), reactorNames = listOf("Artin"))),
            shieldedMessage.reactions
        )

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageUpdated(
                    Message(
                        id = MessageId("m1"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        author = member("u2", "Cindy"),
                        timestampLabel = "",
                        body = "server body edited again",
                        reactions = emptyList()
                    )
                )
            )
        )

        val freshRemoval = store.state.value.payload.messages.single()
        assertEquals("server body edited again", freshRemoval.body)
        assertEquals(emptyList(), freshRemoval.reactions)
        scope.cancel()
    }
    @Test
    fun realtimeMessageUpdatedKeepsVisibleReactionOrder() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource = canonicalThreadDataSource(storeScope = backgroundScope)
        val initialMessage =
            contentPayload("cached body").messages.single().copy(
                reactions =
                    listOf(
                        RaftThreadReactionItem("👍", count = 1, selected = true),
                        RaftThreadReactionItem("🔥", count = 1, selected = false)
                    )
            )
        dataSource.cachePayloadForTest(contentPayload("cached body").copy(messages = listOf(initialMessage)))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageUpdated(
                    Message(
                        id = MessageId("m1"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        author = member("u2", "Cindy"),
                        timestampLabel = "",
                        body = "cached body",
                        reactions =
                            listOf(
                                MessageReaction(
                                    emoji = "🔥",
                                    count = 2,
                                    reactorIds = listOf("u2", "u3"),
                                    reactorNames = listOf("Cindy", "Artin")
                                ),
                                MessageReaction(
                                    emoji = "👍",
                                    count = 1,
                                    reactorIds = listOf("u1"),
                                    reactorNames = listOf("Artin")
                                )
                            )
                    )
                )
            )
        )

        val reactions = store.state.value.payload.messages.single().reactions
        assertEquals(listOf("👍", "🔥"), reactions.map { it.emoji })
        assertEquals(2, reactions.single { it.emoji == "🔥" }.count)
        assertEquals(listOf("u2", "u3"), reactions.single { it.emoji == "🔥" }.reactorIds)
        scope.cancel()
    }
    @Test
    fun realtimeThreadUpdatedCanDecreaseAuthoritativeReplyCount() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource = canonicalThreadDataSource(storeScope = backgroundScope)
        dataSource.cachePayloadForTest(
            contentPayload("parent").copy(
                messages =
                    listOf(
                        contentPayload("parent").messages.single().copy(
                            replyCount = 5,
                            unreadReplyCount = 3,
                            firstUnreadReplyMessageId = "reply-old"
                        )
                    )
            )
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                ThreadUpdated(
                    parentMessageId = MessageId("m1"),
                    threadChannelId = ChannelId("reply-thread"),
                    replyCount = 1,
                    lastReplyAt = "2026-05-19T16:13:00Z",
                    participantIds = emptyList(),
                    unreadCount = 0,
                    firstUnreadMessageId = null
                )
            )
        )

        val message = store.state.value.payload.messages.single()
        assertEquals(1, message.replyCount)
        assertEquals(0, message.unreadReplyCount)
        assertNull(message.firstUnreadReplyMessageId)
        scope.cancel()
    }
    @Test
    fun cachedTimelineNeverMarksReadWhenCanonicalAccessIsDenied() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api = FakeThreadApi()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository =
                    FakeConversationRepository(
                        threadResult = DataResult.NoPermission(PermissionBlocker(code = "thread_forbidden"))
                    )
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, readCoalesceTimeSource = testScheduler.timeSource)

        store.accept(SharedThreadIntent.Start(basePayload()))
        assertEquals(ThreadAccessResolution.NoPermission, store.state.value.accessResolution)
        assertFalse(store.state.value.writeActionsEnabled)
        store.accept(SharedThreadIntent.MarkVisibleRead())

        assertTrue(api.readAllChannels.isEmpty())
        assertTrue(api.readSeqRequests.isEmpty())
        scope.cancel()
    }
    @Test
    fun visibleReadUsesLatestSeqWhenAvailable() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api = FakeThreadApi(failChannelMessages = true)
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository =
                    FakeConversationRepository(
                        threadResult = DataResult.Content(threadSnapshot(body = "cached body", seq = 10))
                    ),
            )
        val payload =
            contentPayload("cached body").let { content ->
                content.copy(messages = content.messages.map { message -> message.copy(seq = 10L) })
            }
        dataSource.cachePayloadForTest(payload)
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, readCoalesceTimeSource = testScheduler.timeSource)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(SharedThreadIntent.MarkVisibleRead())

        assertEquals(listOf("channel-1" to 10L), api.readSeqRequests)
        assertTrue(api.readAllChannels.isEmpty())
        scope.cancel()
    }
    @Test
    fun deferredReadIsDroppedWhenANewerMessageArrivedUnseen() = runTest {
        // Review of task #19: the deferred retry marks only the marker the user was shown. A message that
        // arrives during the window without a new visible-read dispatch (page backgrounded) stays unread.
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api = FakeThreadApi(failChannelMessages = true).also { it.failReadAll = false }
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository =
                    FakeConversationRepository(
                        threadResult = DataResult.Content(threadSnapshot(body = "cached body", seq = 0))
                    ),
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, readCoalesceTimeSource = testScheduler.timeSource)
        store.accept(SharedThreadIntent.Start(basePayload()))
        fun newMessage(id: String, seq: Long) =
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(
                    Message(
                        id = MessageId(id),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        seq = seq,
                        author = member("u2", "Stdrc"),
                        timestampLabel = "2026-05-19T16:01:00Z",
                        body = "body $id"
                    )
                )
            )

        store.accept(SharedThreadIntent.MarkVisibleRead())
        store.accept(newMessage("m2", 11L))
        store.accept(SharedThreadIntent.MarkVisibleRead()) // shown m2 -> deferred
        store.accept(newMessage("m3", 12L)) // arrives unseen: no further dispatch
        delay(2_500)

        assertEquals(listOf("channel-1"), api.readAllChannels)
        assertTrue(api.readSeqRequests.isEmpty(), api.readSeqRequests.toString())
        scope.cancel()
    }
    private fun leftConversationNewMessage(id: String, seq: Long) =
        SharedThreadIntent.ApplyRealtimeEvent(
            MessageNew(
                Message(
                    id = MessageId(id),
                    threadId = ThreadId("channel-1"),
                    channelId = ChannelId("channel-1"),
                    seq = seq,
                    author = member("u2", "Stdrc"),
                    timestampLabel = "2026-05-19T16:01:00Z",
                    body = "body $id"
                )
            )
        )

    @Test
    fun deferredReadDoesNotMarkAConversationTheUserLeft() = runTest {
        // fb-4440a18c (artin, 1.22.0): a message arrives, the page shows it and asks for a read inside the
        // coalesce window (deferred), then the person leaves. The deferred retry used to fire after the
        // pop (03:52:09.610 pop, 03:52:09.612 thread_read_start): a conversation no longer shown must not
        // be marked read, neither by that retry nor by messages that arrive afterwards.
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api = FakeThreadApi(failChannelMessages = true).also { it.failReadAll = false }
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository =
                    FakeConversationRepository(
                        threadResult = DataResult.Content(threadSnapshot(body = "cached body", seq = 0))
                    ),
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, readCoalesceTimeSource = testScheduler.timeSource)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(SharedThreadIntent.MarkVisibleRead())
        store.accept(leftConversationNewMessage("m2", 11L))
        store.accept(SharedThreadIntent.MarkVisibleRead()) // shown m2 inside the window -> deferred
        store.dispatch(SharedThreadIntent.VisibleReadHidden) // the person leaves
        store.accept(leftConversationNewMessage("m3", 12L)) // arrives after leaving
        delay(5_000)

        assertEquals(listOf("channel-1"), api.readAllChannels)
        assertTrue(api.readSeqRequests.isEmpty(), "left conversation marked read: ${api.readSeqRequests}")

        // Coming back shows the conversation again: its next visible read goes out as before.
        store.accept(SharedThreadIntent.MarkVisibleRead())
        assertEquals(listOf("channel-1" to 12L), api.readSeqRequests)
        scope.cancel()
    }

    @Test
    fun theConversationIsReadOnSightOnlyWhileThePageReadsItsLiveTail() = runTest {
        // activity:inplace_check thread_reply unread=0/N: Activity reads a live message on arrival only
        // while a page reads its conversation on sight. Shown at the tail: yes. Scrolled up from the tail
        // (a live append is not read, task #232), left / covered (fb-4440a18c), or the page gone: no.
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api = FakeThreadApi(failChannelMessages = true).also { it.failReadAll = false }
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository =
                    FakeConversationRepository(
                        threadResult = DataResult.Content(threadSnapshot(body = "cached body", seq = 0))
                    ),
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val readOnSight = ConversationReadOnSight()
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope,
                readCoalesceTimeSource = testScheduler.timeSource,
                readOnSight = readOnSight
            )
        store.accept(SharedThreadIntent.Start(basePayload()))
        assertFalse(readOnSight.isReading("channel-1"), "not read before the page shows it")

        store.accept(SharedThreadIntent.MarkVisibleRead())
        assertTrue(readOnSight.isReading("channel-1"), "shown at the tail")

        store.accept(SharedThreadIntent.VisibleReadHidden)
        assertFalse(readOnSight.isReading("channel-1"), "left or covered")

        store.accept(leftConversationNewMessage("m2", 11L))
        delay(5_000)
        store.accept(SharedThreadIntent.MarkVisibleRead(visibleTailIndex = 0))
        assertFalse(readOnSight.isReading("channel-1"), "shown, but scrolled up from the live tail")

        store.accept(SharedThreadIntent.MarkVisibleRead(visibleTailIndex = 1))
        assertTrue(readOnSight.isReading("channel-1"), "back at the tail")

        store.clear()
        assertFalse(readOnSight.isReading("channel-1"), "the page is gone")
        scope.cancel()
    }

    @Test
    fun applyReplyThreadReadClearsParentUnreadProjectionAndCache() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api = FakeThreadApi(failChannelMessages = true)
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val initial =
            basePayload().copy(
                status = "content",
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "m1",
                            authorId = "u1",
                            authorName = "Artin",
                            body = "parent",
                            threadChannelId = "thread-old",
                            replyCount = 2,
                            unreadReplyCount = 1,
                            firstUnreadReplyMessageId = "reply-1"
                        )
                    )
            )
        dataSource.cachePayloadForTest(initial)
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(SharedThreadIntent.ApplyReplyThreadRead(parentMessageId = "m1", threadChannelId = "thread-new"))

        val message = store.state.value.payload.messages.single()
        assertEquals(2, message.replyCount)
        assertEquals(0, message.unreadReplyCount)
        assertNull(message.firstUnreadReplyMessageId)
        assertEquals("thread-new", message.threadChannelId)
        val cached = dataSource.cachedPayload("channel-1")?.messages?.single()
        assertEquals(0, cached?.unreadReplyCount)
        assertNull(cached?.firstUnreadReplyMessageId)
        scope.cancel()
    }
}
