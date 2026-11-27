package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.conversationSummary
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.systemMessagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFixtures.cachePayloadForTest
import build.raft.app.SharedThreadStoreTestFixtures.canonicalThreadDataSource
import build.raft.app.SharedThreadStoreTestFixtures.contentPayload
import build.raft.app.SharedThreadStoreTestFixtures.directMessagePayload
import build.raft.app.SharedThreadStoreTestFixtures.localPendingMessage
import build.raft.app.SharedThreadStoreTestFixtures.member
import build.raft.app.SharedThreadStoreTestFixtures.replyHintRepository
import build.raft.app.SharedThreadStoreTestFixtures.startLocalSendForCurrentOwner
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryComposeLocalDataStore
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.InboxItem
import build.raft.app.data.ThreadActionResult
import build.raft.app.data.ThreadMessagesPage
import build.raft.app.data.WireMessage
import build.raft.app.data.buildSharedThreadPayloadFromMessagesPage
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId
import build.raft.app.model.ThreadUpdated
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.SharedThreadIntent
import build.raft.app.thread.SharedThreadStore
import build.raft.app.thread.ThreadAccessResolution
import build.raft.app.thread.ThreadReplyPageTitle
import build.raft.app.thread.dispatch
import build.raft.app.thread.key
import build.raft.app.thread.threadHeaderDisplayTitle
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

class SharedThreadStoreRealtimeMergeTest {
    @Test
    fun startSkipsMismatchedReplyThreadMemoryCacheBeforeResolvedFetch() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val repository = replyHintRepository()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        dataSource.cachePayloadForTest(wrongReplyCachedPayload())
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val observedPayloads = mutableListOf<RaftThreadScreenPayload>()
        val observer = scope.launch {
            store.state.collect { state -> observedPayloads += state.payload }
        }

        store.accept(SharedThreadIntent.Start(replyRouteWithWrongHint()))

        val payload = store.state.value.payload
        assertFalse(observedPayloads.any { it.containsWrongReplyCacheContent() })
        assertEquals(emptyList(), repository.getThreadRequests)
        assertEquals(emptyList(), repository.createOrOpenRequests)
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
        assertEquals("thread-m1", payload.sourceThreadId)
        assertEquals("thread-m1", payload.streamChannelId)
        assertEquals("thread-m1", payload.composer.streamChannelId)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("m1", payload.parentMessageId)
        assertEquals("", payload.title)
        assertEquals(ThreadReplyPageTitle, threadHeaderDisplayTitle(payload.title, payload.surfaceKind))
        assertEquals("#general", payload.subtitle)
        assertEquals("parent body", payload.originMessage?.body)
        assertEquals(listOf("reply body"), payload.messages.map { it.body })
        observer.cancel()
        scope.cancel()
    }
    @Test
    fun startWithUnresolvedActivityReplyThreadCreatesStreamBeforeLoadingReplies() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            threadId = "thread-m1",
                            body = "reply body",
                            id = "reply-1",
                            messages = listOf(message(threadId = "thread-m1", id = "reply-1", seq = 2, body = "reply body")),
                            parentMessage = message(threadId = "channel-1", id = "m1", seq = 1, body = "parent body"),
                            conversation =
                                conversationSummary("thread-m1").copy(
                                    kind = ConversationKind.Thread,
                                    channelId = ChannelId("thread-m1"),
                                    name = "general",
                                    subtitle = "#general",
                                    thread =
                                        ThreadConversation(
                                            parentChannelId = ChannelId("channel-1"),
                                            parentMessageId = MessageId("m1"),
                                            streamChannelId = ChannelId("thread-m1")
                                        )
                                )
                        )
                    ),
                createThreadResult =
                    DataResult.Content(
                        ThreadActionResult(
                            threadChannelId = ChannelId("thread-m1"),
                            changed = false,
                            parentMessageId = MessageId("m1"),
                            replyCount = 2
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val request =
            InboxItem(
                key = "thread:m1",
                kind = InboxItemKind.Thread,
                channelId = ChannelId("thread-channel-compat"),
                channelName = "thread stream hint",
                parentChannelId = ChannelId("channel-1"),
                parentChannelName = "general",
                parentChannelKind = ConversationKind.Channel,
                parentMessageId = MessageId("m1"),
                parentMessagePreview = "parent body",
                unreadCount = 1,
                firstUnreadMessageId = MessageId("reply-1"),
                latestActivityMessageId = MessageId("reply-1")
            ).toHomeMessageOpenRequestForTest()
        val initialPayload =
            requireNotNull(
                buildAppShellThreadPayload(request.conversationIdentity, anchorMessageId = request.anchorMessageId)
            )
                .copy(
                    title = "stale launch title",
                    subtitle = "#stale",
                    originMessage =
                        RaftThreadMessageItem(
                            id = "m1",
                            authorId = "stale-user",
                            authorName = "Stale",
                            body = "stale launch parent"
                        ),
                    originPreview = "stale launch preview",
                    originStatus = "loaded"
                )

        store.accept(SharedThreadIntent.Start(initialPayload))

        val payload = store.state.value.payload
        assertEquals(null, initialPayload.sourceThreadId)
        assertEquals(null, initialPayload.streamChannelId)
        assertEquals(emptyList(), repository.createOrOpenRequests)
        assertEquals(emptyList(), repository.getThreadRequests)
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
        assertEquals(RaftThreadSurfaceKind.ReplyThread, payload.surfaceKind)
        assertEquals("thread-m1", payload.sourceThreadId)
        assertEquals("thread-m1", payload.streamChannelId)
        assertEquals("thread-m1", payload.composer.streamChannelId)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("m1", payload.parentMessageId)
        assertEquals("m1", payload.originParentMessageId)
        assertEquals("reply-1", payload.focusedMessageId)
        assertEquals(ThreadReplyPageTitle, threadHeaderDisplayTitle(payload.title, payload.surfaceKind))
        assertEquals("#general", payload.subtitle)
        assertEquals("parent body", payload.originMessage?.body)
        assertEquals(listOf("reply body"), payload.messages.map { it.body })
        scope.cancel()
    }
    @Test
    fun emptyInitialLoadStaysLoadingUntilCanonicalFetchCompletes() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val repository =
            FakeConversationRepository(
                threadDelayMillis = 100L,
                threadResult =
                    DataResult.Content(
                        threadSnapshot(messages = listOf(message(id = "m1", seq = 1, body = "late content")))
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope
            )

        store.dispatch(SharedThreadIntent.Start(basePayload()))

        delay(30L)
        assertEquals("loading", store.state.value.payload.status)
        assertEquals(emptyList(), store.state.value.payload.messages)
        assertEquals(null, store.state.value.payload.errorCode)

        withTimeout(10_000L) {
            while (store.state.value.payload.messages.singleOrNull()?.id != "m1") {
                delay(5L)
            }
        }
        assertEquals("content", store.state.value.payload.status)
        assertEquals(null, store.state.value.payload.errorCode)
        assertEquals("late content", store.state.value.payload.messages.single().body)
        scope.cancel()
    }
    @Test
    fun warmContentStaysVisibleDuringSlowRefresh() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Content(threadSnapshot(body = "cached content"))
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope
            )

        store.accept(SharedThreadIntent.Start(basePayload()))
        repository.threadDelayMillis = 100L
        repository.threadResult = DataResult.Content(threadSnapshot(body = "refreshed content"))
        store.dispatch(SharedThreadIntent.Refresh)

        delay(30L)
        assertEquals("content", store.state.value.payload.status)
        assertEquals("cached content", store.state.value.payload.messages.single().body)
        assertEquals(null, store.state.value.payload.errorCode)

        withTimeout(10_000L) {
            while (store.state.value.payload.messages.singleOrNull()?.body != "refreshed content") {
                delay(5L)
            }
        }
        assertEquals("content", store.state.value.payload.status)
        assertEquals(null, store.state.value.payload.errorCode)
        scope.cancel()
    }
    @Test
    fun restThreadFetchRendersPersistedTaskLifecycleSystemRow() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(
                    bodyText =
                        systemMessagesResponse(
                            body = "✅ artin moved #252 \"Implement Thread timeline system-message rendering\" to Done",
                            id = "sys-done-1",
                            seq = 44
                        )
                ),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)

        store.accept(SharedThreadIntent.Start(basePayload()))

        val systemRow = store.state.value.payload.messages.single()
        assertEquals("sys-done-1", systemRow.id)
        assertEquals("System", systemRow.messageType)
        assertEquals("system", systemRow.authorKind)
        assertEquals("✅ artin moved #252 \"Implement Thread timeline system-message rendering\" to Done", systemRow.body)
        scope.cancel()
    }
    @Test
    fun realtimeMergeWarmsFirstFrameCacheWithLiveStoreSnapshot() = runTest {
        // task #562 contract (KMP-专家/HanXin agreed): the first-frame cache is
        // a read-only snapshot of the LIVE STORE's merged state, written after
        // the reduce — one merge algorithm, no cache-side event logic. Active
        // conversations stay warm on re-entry; every open still refreshes.
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(
                    Message(
                        id = MessageId("m2"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        author = member("u2", "Stdrc"),
                        timestampLabel = "2026-05-19T16:01:00Z",
                        body = "realtime body"
                    )
                )
            )
        )

        val payload = store.state.value.payload
        assertEquals(listOf("cached body", "realtime body"), payload.messages.map { it.body })
        // Cache == what the user is looking at: the post-merge store snapshot.
        assertEquals(
            listOf("cached body", "realtime body"),
            dataSource.cachedPayload("channel-1")?.messages?.map { it.body }
        )
        // Durable local-store never rides the first-frame cache path.
        assertNull(localStore.readThread(ThreadId("channel-1")))
        scope.cancel()
    }
    @Test
    fun noPermissionRefreshInvalidatesFirstFrameCache() = runTest {
        // task #562 boundary: invalidation is reserved for unrecoverable
        // scenarios — the canonical fetch reporting permission loss drops the
        // candidate so a revoked thread can never seed a first frame again.
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(channelMessagesResponseCode = 403),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        assertNull(dataSource.cachedPayload("channel-1"))
        assertEquals(ThreadAccessResolution.NoPermission, store.state.value.accessResolution)
        assertEquals("no_permission", store.state.value.payload.status)
        assertEquals("thread_forbidden", store.state.value.payload.errorCode)
        assertTrue(store.state.value.payload.messages.isEmpty())
        scope.cancel()
    }
    @Test
    fun realtimeMessageNewMergesVisiblePayloadWhenMemoryCacheMissesEvent() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(
                    Message(
                        id = MessageId("m2"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        author = member("u2", "Stdrc"),
                        timestampLabel = "2026-05-19T16:01:00Z",
                        body = "realtime body"
                    )
                )
            )
        )

        assertEquals(listOf("cached body", "realtime body"), store.state.value.payload.messages.map { it.body })
        scope.cancel()
    }
    @Test
    fun localSendAdmissionClearsFocusedNewerContext() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi =
                    FakeThreadApi(
                        bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false)
                    ),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(focusedMessageId = "m10")))
        store.accept(SharedThreadIntent.SetPagingAvailability(canLoadMoreBefore = true, canLoadMoreAfter = true))

        store.startLocalSendForCurrentOwner(
            dataSource,
            localPendingMessage("local-prepare", "send body")
        )

        val payload = store.state.value.payload
        assertEquals(null, payload.focusedMessageId)
        assertEquals(false, payload.canLoadMoreAfter)
        assertEquals("idle", payload.newerMessagesStatus)
        scope.cancel()
    }
    @Test
    fun realtimeThreadUpdatedAppliesReplyMetadataToDirectMessageParent() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource = canonicalThreadDataSource(storeScope = backgroundScope)
        dataSource.cachePayloadForTest(
            buildSharedThreadPayloadFromMessagesPage(
                basePayload = directMessagePayload(),
                page =
                    ThreadMessagesPage(
                        messages =
                            listOf(
                                WireMessage(
                                    id = "dm-parent-1",
                                    channelId = "dm-1",
                                    senderType = "user",
                                    senderId = "u1",
                                    senderName = "Artin",
                                    messageType = "chat",
                                    seq = 1,
                                    content = "dm parent"
                                )
                            ),
                        canLoadMoreBefore = false,
                        canLoadMoreAfter = null
                    ),
                session = sampleSession()
            )
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(directMessagePayload()))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                ThreadUpdated(
                    parentMessageId = MessageId("dm-parent-1"),
                    threadChannelId = ChannelId("dm-thread-1"),
                    replyCount = 1,
                    lastReplyAt = "2026-05-19T16:01:00Z",
                    participantIds = listOf(MemberId("u1"), MemberId("agent-1")),
                    unreadCount = 1,
                    firstUnreadMessageId = MessageId("dm-reply-1")
                )
            )
        )

        val parent = store.state.value.payload.messages.single { it.id == "dm-parent-1" }
        assertEquals("dm-thread-1", parent.threadChannelId)
        assertEquals(1, parent.replyCount)
        assertEquals(1, parent.unreadReplyCount)
        assertEquals("dm-reply-1", parent.firstUnreadReplyMessageId)
        scope.cancel()
    }
    @Test
    fun irrelevantRealtimeMessageDoesNotChangeVisibleThread() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val logs = mutableListOf<String>()
        val localStore = InMemoryComposeLocalDataStore()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = logs::add)
        store.accept(SharedThreadIntent.Start(basePayload()))
        dataSource.cachePayloadForTest(store.state.value.payload)
        val before = store.state.value
        logs.clear()

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(
                    Message(
                        id = MessageId("other"),
                        threadId = ThreadId("other-channel"),
                        channelId = ChannelId("other-channel"),
                        author = member("u2", "Stdrc"),
                        timestampLabel = "2026-05-19T16:01:00Z",
                        body = "irrelevant"
                    )
                )
            )
        )

        assertTrue(before === store.state.value)
        assertEquals(listOf("cached body"), store.state.value.payload.messages.map { it.body })
        assertTrue(logs.any { it.contains("reason=no_projection") })
        assertFalse(logs.any { it.startsWith("thread_realtime_cache_result") })
        scope.cancel()
    }
    @Test
    fun realtimeMessageUpdatedPreservesExistingAuthorMetadata() = runTest {
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
                        author = member("u1", "u1"),
                        timestampLabel = "",
                        body = "edited body"
                    )
                )
            )
        )

        val message = store.state.value.payload.messages.single()
        assertEquals("edited body", message.body)
        assertEquals("Artin", message.authorName)
        scope.cancel()
    }
    @Test
    fun realtimeParentOriginUpdateDoesNotAppendToReplyThreadTimeline() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource = canonicalThreadDataSource(storeScope = backgroundScope)
        val route =
            basePayload().copy(
                instanceId = "shared-shell-thread-thread-m1",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = "thread-m1",
                streamChannelId = "thread-m1",
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                showModeTabs = false
            )
        dataSource.cachePayloadForTest(
            route.copy(
                status = "content",
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "reply-1",
                            authorId = "u2",
                            authorName = "Cindy",
                            body = "reply body"
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
                MessageUpdated(
                    message(
                        threadId = "thread-m1",
                        channelId = "channel-1",
                        id = "m1",
                        seq = 1,
                        body = "parent edited",
                        threadChannelId = "thread-m1",
                        replyCount = 1
                    )
                )
            )
        )

        val payload = store.state.value.payload
        assertEquals(listOf("reply-1"), payload.messages.map { it.id })
        assertEquals(listOf("reply body"), payload.messages.map { it.body })
        assertEquals("parent edited", payload.originMessage?.body)
        assertEquals("thread-m1", payload.originMessage?.threadChannelId)
        scope.cancel()
    }
    private fun replyRouteWithWrongHint(): RaftThreadScreenPayload =
        basePayload().copy(
            instanceId = "shared-shell-thread-wrong-thread",
            surfaceKind = RaftThreadSurfaceKind.ReplyThread,
            sourceThreadId = "wrong-thread",
            streamChannelId = "wrong-thread",
            parentChannelId = "channel-1",
            parentMessageId = "m1",
            originParentMessageId = "m1",
            title = ThreadReplyPageTitle,
            subtitle = "#general",
            showModeTabs = false
        )
    private fun wrongReplyCachedPayload(): RaftThreadScreenPayload =
        RaftThreadScreenPayload(
            instanceId = "shared-shell-thread-wrong-thread",
            surfaceKind = RaftThreadSurfaceKind.ReplyThread,
            sourceThreadId = "wrong-thread",
            streamChannelId = "wrong-thread",
            parentChannelId = "other-channel",
            parentMessageId = "other-message",
            originParentMessageId = "other-message",
            title = ThreadReplyPageTitle,
            subtitle = "#wrong",
            status = "content",
            messages =
                listOf(
                    RaftThreadMessageItem(
                        id = "wrong-reply",
                        authorId = "u1",
                        authorName = "Artin",
                        body = "wrong cached body"
                    )
                ),
            originMessage =
                RaftThreadMessageItem(
                    id = "other-message",
                    authorId = "u1",
                    authorName = "Artin",
                    body = "wrong parent"
                ),
            originStatus = "loaded"
        )
    private fun RaftThreadScreenPayload.containsWrongReplyCacheContent(): Boolean =
        title == "wrong" ||
            subtitle == "#wrong" ||
            originMessage?.body == "wrong parent" ||
            messages.any { message -> message.body == "wrong cached body" }
}
