package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.conversationSummary
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.pageResponse
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
import build.raft.app.SharedThreadStoreTestFixtures.wrongReplyThreadSnapshot
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.HomeSnapshot
import build.raft.app.data.InMemoryComposeLocalDataStore
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.InboxItem
import build.raft.app.data.ScreenError
import build.raft.app.data.ThreadActionResult
import build.raft.app.data.ThreadMessagesPage
import build.raft.app.data.WireMessage
import build.raft.app.data.buildSharedThreadPayloadFromMessagesPage
import build.raft.app.data.toHomeDirectMessageRow
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationSummary
import build.raft.app.model.InboxItemKind
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.MessageType
import build.raft.app.model.MessageUpdated
import build.raft.app.model.Presence
import build.raft.app.model.ServerId
import build.raft.app.model.ServerSummary
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId
import build.raft.app.model.ThreadUpdated
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
    fun startSkipsMismatchedReplyThreadPersistentCacheBeforeResolvedFetch() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeThread(wrongReplyThreadSnapshot())
        val repository = replyHintRepository()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(bodyText = messagesResponse("network body")),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository
            )
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
    fun startSkipsMismatchedReplyThreadCacheWhenResolutionFails() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeThread(wrongReplyThreadSnapshot())
        val repository =
            replyHintRepository(
                threadResult = DataResult.Error(ScreenError(code = "thread_reply_network", retryable = true)),
                createThreadResult =
                    DataResult.Error(ScreenError(code = "thread_reply_network", retryable = true))
            )
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
        assertEquals("error", payload.status)
        assertEquals("thread_reply_network", payload.errorCode)
        assertEquals(emptyList(), payload.messages)
        assertEquals(null, payload.originMessage)
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
    fun startWithUnresolvedEmptyReplyThreadKeepsFirstReplyPanelOpen() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Empty(EmptyReason.NoMessages),
                createThreadResult =
                    DataResult.Content(
                        ThreadActionResult(
                            threadChannelId = ChannelId("thread-m1"),
                            changed = false,
                            parentMessageId = MessageId("m1"),
                            replyCount = 0
                        )
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
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val initialPayload =
            basePayload().copy(
                instanceId = "shared-shell-thread-pending-m1",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = null,
                streamChannelId = null,
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                title = ThreadReplyPageTitle,
                showModeTabs = false,
                originMessage = contentPayload("parent body").messages.single(),
                originStatus = "loaded",
                messages = emptyList(),
                composer =
                    buildThreadSurfaceComposerPayload(
                        surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                        displayName = ThreadReplyPageTitle,
                        fallbackId = "m1",
                        enabled = true,
                        mentionChannelId = "channel-1",
                        parentMessageId = "m1",
                        streamChannelId = null
                    )
            )

        store.accept(SharedThreadIntent.Start(initialPayload))

        val payload = store.state.value.payload
        assertEquals("empty", payload.status)
        assertEquals("thread-m1", payload.sourceThreadId)
        assertEquals("thread-m1", payload.streamChannelId)
        assertEquals("thread-m1", payload.resolveThreadComposer().streamChannelId)
        assertEquals("channel-1", payload.parentChannelId)
        assertEquals("m1", payload.parentMessageId)
        assertEquals("m1", payload.originParentMessageId)
        assertEquals("parent body", payload.originMessage?.body)
        assertEquals("loaded", payload.originStatus)
        assertEquals(emptyList(), payload.messages)
        assertEquals(ThreadReplyEmptyTitle, payload.emptyTitle)
        assertEquals(ThreadReplyEmptyDetail, payload.emptyDetail)
        assertEquals(null, payload.errorCode)
        assertEquals(emptyList(), repository.createOrOpenRequests)
        assertEquals(emptyList(), repository.getThreadRequests)
        assertEquals(listOf(ChannelId("channel-1") to MessageId("m1")), repository.getReplyThreadRequests)
        scope.cancel()
    }
    @Test
    fun startWithZeroReplyTaskDoesNotWaitIndefinitelyForOriginContext() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val diagnostics = mutableListOf<String>()
        val repository =
            FakeConversationRepository(
                threadResult = DataResult.Empty(EmptyReason.NoMessages),
                messageContextDelayMillis = 5_000L,
                createThreadResult =
                    DataResult.Content(
                        ThreadActionResult(
                            threadChannelId = ChannelId("thread-task-600"),
                            changed = true,
                            parentMessageId = MessageId("task-message-600"),
                            replyCount = 0
                        )
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(bodyText = messagesResponse("unused")),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
                diagnosticsLogger = diagnostics::add
            )
        val store =
            SharedThreadStore(
                dataSource = dataSource,
                scope = scope,
                diagnosticsLogger = diagnostics::add
            )
        val initialPayload =
            basePayload().replyThreadLoadingPayload(
                parentMessageId = "task-message-600",
                parentChannelId = "channel-1",
                threadChannelId = null
            ).copy(originStatus = "loading")

        withTimeout(10_000L) {
            store.accept(SharedThreadIntent.Start(initialPayload))
        }

        val payload = store.state.value.payload
        assertEquals("empty", payload.status)
        assertEquals("thread-task-600", payload.sourceThreadId)
        assertEquals("thread-task-600", payload.streamChannelId)
        assertEquals("thread-task-600", payload.resolveThreadComposer().streamChannelId)
        assertEquals("loading", payload.originStatus)
        assertEquals(null, payload.originMessage)
        assertEquals(ThreadReplyEmptyTitle, payload.emptyTitle)
        assertEquals(ThreadReplyEmptyDetail, payload.emptyDetail)
        assertEquals(
            listOf(ChannelId("channel-1") to MessageId("task-message-600")),
            repository.getReplyThreadRequests
        )
        assertEquals(listOf(MessageId("task-message-600")), repository.getMessageContextRequests)
        assertTrue(diagnostics.any { it.startsWith("thread_parent_context_grace_expired ") })
        assertTrue(
            diagnostics.any {
                it.contains("thread_refresh_result") &&
                    it.contains("threadId=") &&
                    it.contains("result=content") &&
                    it.contains("originStatus=loading")
            }
        )
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
    fun refreshWithUnchangedSnapshotKeepsTimelineList() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            messages =
                                listOf(
                                    message(id = "m1", seq = 1, body = "first"),
                                    message(id = "m2", seq = 2, body = "second")
                                )
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
        store.accept(SharedThreadIntent.Start(basePayload()))
        val beforeMessages = store.state.value.payload.messages
        val observedStates = mutableListOf<SharedThreadState>()
        val observer = scope.launch {
            store.state.collect { state -> observedStates += state }
        }
        val countBeforeRefresh = observedStates.size

        repository.threadResult =
            DataResult.Content(
                threadSnapshot(
                    messages =
                        listOf(
                            message(id = "m1", seq = 1, body = "first"),
                            message(id = "m2", seq = 2, body = "second")
                        )
                )
            )
        store.accept(SharedThreadIntent.Refresh)

        assertTrue(observedStates.size >= countBeforeRefresh)
        assertTrue(beforeMessages === store.state.value.payload.messages)
        observer.cancel()
        scope.cancel()
    }
    @Test
    fun refreshSummaryPatchOnlyReplacesAffectedMessageRow() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            messages =
                                listOf(
                                    message(id = "m1", seq = 1, body = "first"),
                                    message(id = "m2", seq = 2, body = "second")
                                )
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
        store.accept(SharedThreadIntent.Start(basePayload()))
        val beforeMessages = store.state.value.payload.messages

        repository.threadResult =
            DataResult.Content(
                threadSnapshot(
                    messages =
                        listOf(
                            message(id = "m1", seq = 1, body = "first"),
                            message(
                                id = "m2",
                                seq = 2,
                                body = "second",
                                threadChannelId = "thread-m2",
                                replyCount = 2,
                                unreadReplyCount = 1
                            )
                        )
                )
            )
        store.accept(SharedThreadIntent.Refresh)

        val afterMessages = store.state.value.payload.messages
        assertEquals(listOf("m1", "m2"), afterMessages.map { it.id })
        assertTrue(beforeMessages !== afterMessages)
        assertTrue(beforeMessages[0] === afterMessages[0])
        assertTrue(beforeMessages[1] !== afterMessages[1])
        assertEquals("thread-m2", afterMessages[1].threadChannelId)
        assertEquals(2, afterMessages[1].replyCount)
        assertEquals(1, afterMessages[1].unreadReplyCount)
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
    fun realtimeForOtherThreadLeavesThisThreadsFirstFrameCacheIntact() = runTest {
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
                        id = MessageId("m-other"),
                        threadId = ThreadId("channel-other"),
                        channelId = ChannelId("channel-other"),
                        author = member("u2", "Stdrc"),
                        timestampLabel = "2026-05-19T16:01:00Z",
                        body = "unrelated realtime body"
                    )
                )
            )
        )

        // No projection for this thread → no merge, no invalidation.
        assertEquals(listOf("cached body"), store.state.value.payload.messages.map { it.body })
        assertEquals(
            "cached body",
            dataSource.cachedPayload("channel-1")?.messages?.single()?.body
        )
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
    fun realtimeMessageNewAcrossContextWindowNewerGapSyncsMissingWindow() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = pageResponse(Triple("m10", 10L, "current body"), Triple("m11", 11L, "gap body"))
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val gapCatchUps = store.recordGapRecoveryJobs()
        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.SetPagingAvailability(canLoadMoreBefore = true, canLoadMoreAfter = true))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(
                    message(id = "m12", seq = 12, body = "latest across gap")
                )
            )
        )
        gapCatchUps.joinAll()

        val payload = store.state.value.payload
        assertEquals(listOf("m10", "m11"), payload.messages.map { it.id })
        assertEquals(listOf("current body", "gap body"), payload.messages.map { it.body })
        assertEquals(1, api.catchUpCalls)
        assertEquals(false, payload.canLoadMoreAfter)
        assertEquals("idle", payload.newerMessagesStatus)
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
    fun realtimeMessageNewKeepsAvatarFieldsOutOfCachedSenderProfileMerge() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val localStore = InMemoryComposeLocalDataStore()
        localStore.writeHome(
            HomeSnapshot(
                server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
                currentMember = member("u1", "Artin"),
                channels = emptyList(),
                directMessages =
                    listOf(
                        ConversationSummary(
                            threadId = ThreadId("dm-agent-1"),
                            channelId = ChannelId("dm-agent-1"),
                            kind = ConversationKind.DirectMessage,
                            name = "Product-UX-Designer",
                            presenceMemberId = MemberId("agent-1")
                        ).toHomeDirectMessageRow()
                    ),
                unreadTotal = 0
            )
        )
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
                        author =
                            MemberProfile(
                                id = MemberId("agent-1"),
                                displayName = "Product-UX-Designer",
                                handle = "Product-UX-Designer",
                                kind = MemberKind.Agent,
                                presence = Presence.Offline
                            ),
                        timestampLabel = "2026-05-19T16:01:00Z",
                        body = "realtime agent body"
                    )
                )
            )
        )

        val message = store.state.value.payload.messages.single { it.id == "m2" }
        assertEquals("pixel:robot", message.avatarWireValue)
        assertNull(localStore.readThread(ThreadId("channel-1")))
        // task #562: cache snapshot carries the same resolved avatar fields.
        val cachedMessage = dataSource.cachedPayload("channel-1")?.messages?.single { it.id == "m2" }
        assertEquals("pixel:robot", cachedMessage?.avatarWireValue)
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
    fun realtimeMessageNewRendersTaskLifecycleSystemRow() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource = canonicalThreadDataSource(storeScope = backgroundScope)
        val cachedPayload =
            contentPayload("cached body").let { content ->
                content.copy(messages = content.messages.map { message -> message.copy(seq = 43L) })
            }
        dataSource.cachePayloadForTest(cachedPayload)
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(
                    message(
                        id = "sys-done-1",
                        seq = 44,
                        body = "✅ artin moved #252 \"Implement Thread timeline system-message rendering\" to Done",
                        messageType = MessageType.System,
                        authorKind = MemberKind.System
                    )
                )
            )
        )

        val messages = store.state.value.payload.messages
        assertEquals(listOf("m1", "sys-done-1"), messages.map { it.id })
        val systemRow = messages.last()
        assertEquals("System", systemRow.messageType)
        assertEquals("system", systemRow.authorKind)
        assertEquals("✅ artin moved #252 \"Implement Thread timeline system-message rendering\" to Done", systemRow.body)
        scope.cancel()
    }
    @Test
    fun realtimeLocalWriteFailureDoesNotRollbackVisiblePayload() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val backingStore = InMemoryComposeLocalDataStore()
        backingStore.writeThread(threadSnapshot(body = "cached body"))
        val logs = mutableListOf<String>()
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
        assertEquals(listOf("cached body"), backingStore.readThread(ThreadId("channel-1"))?.messages?.map { it.body })
        // task #562: the first-frame cache mirrors the visible merged state.
        assertEquals(listOf("cached body", "realtime body"), dataSource.cachedPayload("channel-1")?.messages?.map { it.body })
        assertFalse(logs.any { it.startsWith("thread_realtime_persist_failed") })
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
    fun payloadSelectorDoesNotEmitForMembersLoadStatusOnlyChanges() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        val payloadEvents = mutableListOf<RaftThreadScreenPayload>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            store.payloadFlow().collect { payload -> payloadEvents += payload }
        }
        assertEquals(emptyList(), payloadEvents.single().messages)
        assertEquals(listOf("cached body"), store.state.value.timelineSlice.messages.map { it.body })
        payloadEvents.clear()

        store.accept(SharedThreadIntent.LoadMembers)

        assertEquals(LoadMembersStatus.Loaded, store.state.value.membersLoadStatus)
        assertEquals(emptyList(), payloadEvents.toList())
        job.cancel()
        scope.cancel()
    }
    @Test
    fun timelineSelectorDoesNotEmitForMembersOnlyPayloadChanges() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        val timelineEvents = mutableListOf<ThreadTimelineSlice>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            store.timelineSliceFlow().collect { slice -> timelineEvents += slice }
        }
        assertEquals(listOf("cached body"), timelineEvents.single().messages.map { it.body })
        timelineEvents.clear()

        store.accept(
            SharedThreadIntent.ApplyMembersPatch(
                membersCount = 1,
                members = listOf(RaftThreadMemberItem(id = "u2", displayName = "Member 2")),
                memberCandidates = listOf(RaftThreadMemberItem(id = "u3", displayName = "Candidate 3")),
                canAddMembers = true
            )
        )

        assertEquals(1, store.state.value.payload.membersCount)
        assertEquals(listOf("u2"), store.state.value.payload.members.map { it.id })
        assertEquals(emptyList(), timelineEvents.toList())
        job.cancel()
        scope.cancel()
    }
    @Test
    fun timelineSelectorEmitsForMessageChanges() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = FakeThreadApi(failChannelMessages = true),
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository =
                    FakeConversationRepository(
                        threadResult =
                            DataResult.Content(
                                threadSnapshot(
                                    body = "cached body",
                                    conversation = conversationSummary().copy(joined = true)
                                )
                            )
                    )
            )
        dataSource.cachePayloadForTest(contentPayload("cached body"))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        val timelineEvents = mutableListOf<ThreadTimelineSlice>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            store.timelineSliceFlow().collect { slice -> timelineEvents += slice }
        }
        timelineEvents.clear()

        store.startLocalSendForCurrentOwner(
            dataSource,
            contentPayload("local send", id = "local-1").messages.single()
        )

        assertEquals(listOf("cached body", "local send"), timelineEvents.single().messages.map { it.body })
        job.cancel()
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
    fun realtimeMessageUpdatedCannotRewriteCreationTimeOrTimelineOrder() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource = canonicalThreadDataSource(storeScope = backgroundScope)
        val first =
            contentPayload("first", id = "m1").messages.single().copy(
                seq = 30,
                timestampLabel = "2026-07-28T19:30:00+08:00"
            )
        val second =
            first.copy(
                id = "m2",
                seq = 31,
                body = "second",
                timestampLabel = "2026-07-28T19:31:00+08:00"
            )
        dataSource.cachePayloadForTest(contentPayload("first").copy(messages = listOf(first, second)))
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageUpdated(
                    Message(
                        id = MessageId("m1"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        seq = 30,
                        author = member("u1", "Artin"),
                        timestampLabel = "2026-07-28T19:32:00+08:00",
                        updatedAtLabel = "2026-07-28T19:32:00+08:00",
                        body = "first updated"
                    )
                )
            )
        )

        assertEquals(listOf("m1", "m2"), store.state.value.payload.messages.map { it.id })
        assertEquals(
            listOf("2026-07-28T19:30:00+08:00", "2026-07-28T19:31:00+08:00"),
            store.state.value.payload.messages.map { it.timestampLabel }
        )
        assertEquals("first updated", store.state.value.payload.messages.first().body)
        scope.cancel()
    }
    @Test
    fun realtimeMessageUpdatedCannotReplaceCanonicalAuthorIdentity() = runTest {
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
                        author = member("u2", "u2"),
                        timestampLabel = "",
                        body = "edited body"
                    )
                )
            )
        )

        val message = store.state.value.payload.messages.single()
        assertEquals("edited body", message.body)
        assertEquals("u1", message.authorId)
        assertEquals("Artin", message.authorName)
        scope.cancel()
    }
    @Test
    fun realtimeMessageUpdatedCanHydrateUnknownAuthorIdentity() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val dataSource = canonicalThreadDataSource(storeScope = backgroundScope)
        val cachedPayload = contentPayload("cached body")
        dataSource.cachePayloadForTest(
            cachedPayload.copy(
                messages =
                    listOf(
                        cachedPayload.messages.single().copy(
                            authorId = "unknown",
                            authorName = "Unknown"
                        )
                    )
            )
        )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageUpdated(
                    Message(
                        id = MessageId("m1"),
                        threadId = ThreadId("channel-1"),
                        channelId = ChannelId("channel-1"),
                        author = member("u2", "Aiden"),
                        timestampLabel = "",
                        body = "edited body"
                    )
                )
            )
        )

        val message = store.state.value.payload.messages.single()
        assertEquals("u2", message.authorId)
        assertEquals("Aiden", message.authorName)
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
