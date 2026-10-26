package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.emptyMessagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.pageRangeResponse
import build.raft.app.SharedThreadStoreTest.Companion.pageResponse
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.Companion.systemMessagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.threadSnapshot
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.SharedThreadStoreTestFakes.FakeConversationRepository
import build.raft.app.SharedThreadStoreTestFixtures.replyPayload
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.InMemoryComposeLocalDataStore
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.InboxItem
import build.raft.app.data.ThreadReplySummariesSnapshot
import build.raft.app.model.ChannelId
import build.raft.app.model.InboxItemKind
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.ThreadId
import build.raft.app.model.ThreadUpdated
import build.raft.app.network.sync.ReplyPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

class SharedThreadStoreRecoveryTerminalTest {
    @Test
    fun cancelledRecoveryReleasesOwnershipAndRestoresIdleForSuccessor() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val oldStarted = CompletableDeferred<Unit>()
        val oldRelease = CompletableDeferred<Unit>()
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
            )
        api.catchUpBodyTexts.addLast(pageRangeResponse(10L..12L))
        api.catchUpBodyTexts.addLast(pageRangeResponse(10L..13L))
        api.catchUpStartedSignals.addLast(oldStarted)
        api.catchUpReleases.addLast(oldRelease)
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val gapCatchUps = store.recordGapRecoveryJobs()
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        // The realtime sink only registers the gap; the catch-up runs on the store scope, so cancelling
        // that job (store teardown) is what cancels the recovery.
        val trigger = launch {
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageNew(message(id = "m12", seq = 12, body = "cancelled gap")),
                ),
            )
        }
        oldStarted.await()
        trigger.join()
        gapCatchUps[0].cancel()
        gapCatchUps.join(0)

        assertEquals("idle", store.state.value.payload.newerMessagesStatus)
        assertEquals("idle", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(message(id = "m13", seq = 13, body = "successor gap")),
            ),
        )
        gapCatchUps.joinAll()
        assertEquals(2, api.catchUpCalls)
        assertEquals(listOf(10L, 11L, 12L, 13L), store.state.value.payload.messages.mapNotNull { it.seq })
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)
        assertEquals(
            listOf(10L, 11L, 12L, 13L),
            dataSource.cachedPayload("channel-1")?.messages?.mapNotNull { it.seq },
        )
        scope.cancel()
    }
    @Test
    fun clearInvalidatesParkedRecoveryAndRestoresVisibleAndCachedIdle() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = pageRangeResponse(10L..12L),
            )
        api.catchUpStarted = CompletableDeferred()
        api.releaseCatchUp = CompletableDeferred()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        val recovery = launch {
            store.accept(
                SharedThreadIntent.ApplyRealtimeEvent(
                    MessageNew(message(id = "m12", seq = 12, body = "cleared gap")),
                ),
            )
        }
        api.catchUpStarted?.await()
        assertEquals("loading", store.state.value.payload.newerMessagesStatus)
        assertEquals("loading", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)

        store.clear()
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)
        assertEquals("idle", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)

        api.releaseCatchUp?.complete(Unit)
        recovery.join()

        assertEquals(
            listOf(10L),
            store.state.value.payload.messages.mapNotNull { it.seq },
            "a response owned by the generation invalidated by clear must perform zero payload mutation",
        )
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)
        assertEquals(listOf(10L), dataSource.cachedPayload("channel-1")?.messages?.mapNotNull { it.seq })
        assertEquals("idle", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)
        scope.cancel()
    }
    @Test
    fun emptyRecoveryResponseTerminatesIdleAndUpdatesCache() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = emptyMessagesResponse(),
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val gapCatchUps = store.recordGapRecoveryJobs()
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(message(id = "m12", seq = 12, body = "empty recovery")),
            ),
        )
        gapCatchUps.joinAll()

        assertEquals(1, api.catchUpCalls)
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)
        assertEquals("idle", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)
        scope.cancel()
    }
    @Test
    fun errorRecoveryResponseReleasesStateForSuccessfulRetry() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
            )
        api.catchUpBodyTexts.addLast("{}")
        api.catchUpResponseCodes.addLast(500)
        api.catchUpBodyTexts.addLast(pageRangeResponse(10L..13L))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val gapCatchUps = store.recordGapRecoveryJobs()
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(message(id = "m12", seq = 12, body = "failing recovery")),
            ),
        )
        gapCatchUps.joinAll()
        assertEquals("error", store.state.value.payload.newerMessagesStatus)
        assertEquals("error", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(message(id = "m13", seq = 13, body = "retry recovery")),
            ),
        )
        gapCatchUps.joinAll()
        assertEquals(2, api.catchUpCalls)
        assertEquals(listOf(10L, 11L, 12L, 13L), store.state.value.payload.messages.mapNotNull { it.seq })
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)
        assertEquals("idle", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)
        scope.cancel()
    }
    @Test
    fun failedResumeCatchUpIsAskedAgainOnItsOwn() = runTest {
        // Feedback 4409cbc5: the resume catch-up failed and nothing asked again, so the thread kept its
        // cached messages until the user left. A retryable failure now schedules a bounded retry.
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val logs = mutableListOf<String>()
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
            )
        api.catchUpBodyTexts.addLast("{}")
        api.catchUpResponseCodes.addLast(500)
        api.catchUpBodyTexts.addLast(pageRangeResponse(10L..13L))
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = logs::add)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        store.accept(SharedThreadIntent.RefreshLatestAfterVisibleMaxSeq)
        assertEquals(1, api.catchUpCalls)
        assertEquals("error", store.state.value.payload.newerMessagesStatus)
        assertTrue(logs.any { "thread_resume_catchup_retry_scheduled" in it && "attempt=1" in it }, logs.toString())

        withTimeout(resumeCatchUpRetryDelayMs(0) + 5_000L) {
            while (store.state.value.payload.newerMessagesStatus != "idle") delay(50)
        }
        assertEquals(2, api.catchUpCalls)
        assertEquals(listOf(10L, 11L, 12L, 13L), store.state.value.payload.messages.mapNotNull { it.seq })
        scope.cancel()
    }
    @Test
    fun resumeCatchUpRetriesAreBounded() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L), (0 until RESUME_CATCH_UP_MAX_RETRIES).map(::resumeCatchUpRetryDelayMs))
    }
    @Test
    fun allKnownCatchUpPageTerminatesWithoutLooping() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = pageResponse(Triple("m10", 10L, "current body")),
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val gapCatchUps = store.recordGapRecoveryJobs()
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(message(id = "m12", seq = 12, body = "no progress recovery")),
            ),
        )
        gapCatchUps.joinAll()

        assertEquals(1, api.catchUpCalls)
        assertEquals("idle", store.state.value.payload.newerMessagesStatus)
        assertEquals("idle", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)
        scope.cancel()
    }
    @Test
    fun noPermissionRecoveryResponseTerminatesWithoutWritingRetiredOwnerCache() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val auth = InMemoryRaftAuthSessionStore(sampleSession())
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
            )
        val dataSource = testSharedThreadDataSource(storeScope = backgroundScope, networkApi = api, sessionStore = auth, cacheEnvironmentIdProvider = { TestThreadEnvironmentId })
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))
        val cacheContext = requireNotNull(dataSource.captureReplyCacheProducerContext())
        auth.clearSession()

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(message(id = "m12", seq = 12, body = "no permission recovery")),
            ),
        )

        assertEquals(0, api.catchUpCalls)
        assertEquals("error", store.state.value.payload.newerMessagesStatus)
        assertEquals(
            "idle",
            dataSource.cachedPayload(
                threadId = "channel-1",
                ownerScopeId = requireNotNull(cacheContext.provenance.threadPayloadOwnerScopeId),
                serverId = cacheContext.provenance.serverId,
                principalId = cacheContext.provenance.principalId
            )?.newerMessagesStatus
        )
        scope.cancel()
    }
    @Test
    fun replyThreadUpdatedReplyCountGapSyncsFromVisiblePayloadTail() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val localStore = InMemoryComposeLocalDataStore()
        val api =
            FakeThreadApi(
                bodyText =
                    messagesResponse(
                        "current body",
                        id = "m10",
                        seq = 10,
                        historyLimited = false,
                        channelId = "thread-m1"
                    ),
                newerBodyText =
                    pageResponse(
                        Triple("m10", 10L, "current body"),
                        Triple("m11", 11L, "recovered body"),
                        channelId = "thread-m1"
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val route = replyPayload(parentMessageId = "m1", threadId = "thread-m1").copy(canLoadMoreAfter = false)
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val gapCatchUps = store.recordGapRecoveryJobs()
        store.accept(SharedThreadIntent.Start(route))

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                ThreadUpdated(
                    threadChannelId = ChannelId("thread-m1"),
                    parentMessageId = MessageId("m1"),
                    replyCount = 2,
                    lastReplyAt = "2026-05-19T16:02:00Z",
                    participantIds = emptyList()
                ),
                activeReplyParentMessageId = MessageId("m1")
            )
        )
        gapCatchUps.joinAll()

        val payload = store.state.value.payload
        assertEquals(listOf("current body", "recovered body"), payload.messages.map { it.body })
        assertEquals(1, api.catchUpCalls)
        assertEquals(false, payload.canLoadMoreAfter)
        assertEquals("idle", payload.newerMessagesStatus)
        assertNull(localStore.readThread(ThreadId("thread-m1")))
        assertEquals(
            listOf("current body", "recovered body"),
            dataSource.cachedPayload("thread-m1")?.messages?.map { it.body }
        )
        scope.cancel()
    }
    @Test
    fun realtimeGapRecoverySkipsWhenVisiblePayloadAlreadyCaughtUp() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = pageResponse(Triple("m10", 10L, "current body"), Triple("m12", 12L, "unused"))
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        store.accept(SharedThreadIntent.RecoverRealtimeGap(serverSeq = 10))

        assertEquals(0, api.catchUpCalls)
        assertEquals(listOf("current body"), store.state.value.payload.messages.map { it.body })
        scope.cancel()
    }
    @Test
    fun foregroundRefreshRebaselineDropsInFlightRealtimeGapResult() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = pageResponse(Triple("m10", 10L, "current body"), Triple("m11", 11L, "stale gap body"))
            )
        api.catchUpStarted = CompletableDeferred()
        api.releaseCatchUp = CompletableDeferred()
        val logs = mutableListOf<String>()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = logs::add)
        val gapCatchUps = store.recordGapRecoveryJobs()
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        val gapJob = launch {
            store.accept(SharedThreadIntent.RecoverRealtimeGap(serverSeq = 90))
        }
        api.catchUpStarted?.await()
        api.bodyText = messagesResponse("foreground body", id = "m20", seq = 20, historyLimited = false)
        store.accept(SharedThreadIntent.Refresh)
        api.releaseCatchUp?.complete(Unit)
        gapJob.join()
        gapCatchUps.joinAll()

        val payload = store.state.value.payload
        assertEquals(listOf("foreground body"), payload.messages.map { it.body })
        assertEquals(1, api.catchUpCalls)
        assertEquals("idle", payload.newerMessagesStatus)
        assertEquals(listOf("foreground body"), dataSource.cachedPayload("channel-1")?.messages?.map { it.body })
        assertEquals("idle", dataSource.cachedPayload("channel-1")?.newerMessagesStatus)
        assertTrue(logs.any { it.startsWith("thread_realtime_gap_stale_drop") && it.contains("reason=stale_token") })
        scope.cancel()
    }
    @Test
    fun loadNewerMessagesMergesSystemRowsFromChannelAfter() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText =
                    systemMessagesResponse(
                        body = "released #252 \"Implement Thread timeline system-message rendering\"",
                        id = "sys-release-1",
                        seq = 12
                    )
            )
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId }
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = true)))

        store.accept(SharedThreadIntent.LoadNewer)

        val messages = store.state.value.payload.messages
        assertEquals(listOf("m10", "sys-release-1"), messages.map { it.id })
        val systemRow = messages.last()
        assertEquals("System", systemRow.messageType)
        assertEquals("system", systemRow.authorKind)
        assertEquals("released #252 \"Implement Thread timeline system-message rendering\"", systemRow.body)
        assertEquals(listOf(10L), api.afterSeqs)
        assertEquals(0, api.catchUpCalls)
        scope.cancel()
    }
    @Test
    fun realtimeGapRecoveryWithNewArrivalsBackfillsSecondaryTimeline() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = pageResponse(Triple("m10", 10L, "current body"), Triple("m12", 12L, "gap body"))
            )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            body = "current body",
                            messages =
                                listOf(
                                    message(
                                        threadId = "channel-1",
                                        id = "m10",
                                        seq = 10,
                                        body = "current body",
                                        threadChannelId = "thread-m10",
                                        replyCount = 0
                                    )
                                )
                        )
                    ),
                threadReplySummariesResult =
                    DataResult.Content(
                        ThreadReplySummariesSnapshot(
                            threads =
                                listOf(
                                    InboxItem(
                                        key = "thread:m10",
                                        kind = InboxItemKind.Thread,
                                        channelId = ChannelId("thread-m10"),
                                        channelName = "reply thread",
                                        threadChannelId = ChannelId("thread-m10"),
                                        parentChannelId = ChannelId("channel-1"),
                                        parentMessageId = MessageId("m10"),
                                        replyCount = 7,
                                        unreadCount = 2,
                                        firstUnreadMessageId = MessageId("reply-6"),
                                        latestReplies =
                                            listOf(
                                                ReplyPreview(
                                                    messageId = "reply-6",
                                                    seq = 6,
                                                    content = "first unread",
                                                    senderId = "u2",
                                                    senderType = "human",
                                                    senderName = "cindy",
                                                    senderAvatarUrl = null,
                                                    createdAt = "2026-05-19T16:06:00Z",
                                                    senderDisplayName = "Cindy",
                                                )
                                            )
                                    )
                                ),
                            followedThreadKeys = setOf("thread:m10")
                        )
                    )
            )
        val logs = mutableListOf<String>()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = logs::add)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))
        store.accept(SharedThreadIntent.FirstContentReady)
        delay(1L)

        val summariesBefore = repository.getThreadReplySummariesRequests.size
        repository.latestResult = DataResult.Content(threadSnapshot(messages = catchUpPageWithAnchorM10("gap body")))

        store.accept(SharedThreadIntent.RecoverRealtimeGap(serverSeq = 90))

        try {
            withTimeout(5_000) {
                while (repository.latestRequests.size != 1) delay(10)
            }
        } catch (e: TimeoutCancellationException) {
            fail(
                "timed out waiting for the catch-up page, " +
                    "requests=${repository.latestRequests} logs=$logs"
            )
        }
        val payload = store.state.value.payload
        assertEquals(listOf("current body", "gap body"), payload.messages.map { it.body })
        assertEquals(listOf(ThreadId("channel-1")), repository.latestRequests)
        assertEquals(emptyList(), repository.syncNewerRequests)
        withTimeout(5_000) {
            while (
                logs.none { it.startsWith("thread_secondary_backfill") && it.contains("trigger=resume_gap") }
            ) {
                delay(10)
            }
        }
        withTimeout(5_000) {
            while (repository.getThreadReplySummariesRequests.size <= summariesBefore) delay(10)
        }
        scope.cancel()
    }
    @Test
    fun resumeWithNoNewTopLevelMessagesStillBackfillsReplySummaries() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = pageResponse(Triple("m10", 10L, "current body"), Triple("m12", 12L, "gap body"))
            )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            body = "current body",
                            messages =
                                listOf(
                                    message(
                                        threadId = "channel-1",
                                        id = "m10",
                                        seq = 10,
                                        body = "current body",
                                        threadChannelId = "thread-m10",
                                        replyCount = 0
                                    )
                                )
                        )
                    ),
                newerResult = DataResult.Empty(EmptyReason.NoMessages),
                threadReplySummariesResult =
                    DataResult.Content(
                        ThreadReplySummariesSnapshot(
                            threads =
                                listOf(
                                    InboxItem(
                                        key = "thread:m10",
                                        kind = InboxItemKind.Thread,
                                        channelId = ChannelId("thread-m10"),
                                        channelName = "reply thread",
                                        threadChannelId = ChannelId("thread-m10"),
                                        parentChannelId = ChannelId("channel-1"),
                                        parentMessageId = MessageId("m10"),
                                        replyCount = 7,
                                        unreadCount = 2,
                                        firstUnreadMessageId = MessageId("reply-6"),
                                        latestReplies =
                                            listOf(
                                                ReplyPreview(
                                                    messageId = "reply-6",
                                                    seq = 6,
                                                    content = "first unread",
                                                    senderId = "u2",
                                                    senderType = "human",
                                                    senderName = "cindy",
                                                    senderAvatarUrl = null,
                                                    createdAt = "2026-05-19T16:06:00Z",
                                                    senderDisplayName = "Cindy",
                                                )
                                            )
                                    )
                                ),
                            followedThreadKeys = setOf("thread:m10")
                        )
                    )
            )
        val logs = mutableListOf<String>()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = logs::add)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))
        store.accept(SharedThreadIntent.FirstContentReady)
        delay(1L)

        val summariesBefore = repository.getThreadReplySummariesRequests.size

        repository.latestResult = DataResult.Content(threadSnapshot(messages = catchUpPageWithAnchorM10(null)))
        store.accept(SharedThreadIntent.RefreshLatestAfterVisibleMaxSeq)

        withTimeout(5_000) {
            while (repository.latestRequests.size != 1) delay(10)
        }
        assertEquals(listOf("current body"), store.state.value.payload.messages.map { it.body })
        // ad1df6b7: replies posted while backgrounded are not top-level messages, so the catch-up adds nothing,
        // yet the loaded parents' reply summaries must still be re-read.
        withTimeout(5_000) {
            while (logs.none { it.startsWith("thread_secondary_backfill") && it.contains("trigger=resume") }) {
                delay(10)
            }
        }
        withTimeout(5_000) {
            while (repository.getThreadReplySummariesRequests.size <= summariesBefore) delay(10)
        }
        assertEquals(7, store.state.value.payload.messages.single { it.id == "m10" }.replyCount)
        scope.cancel()
    }
    @Test
    fun foregroundGapWithNoNewMessagesDoesNotBackfillReplySummaries() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = pageResponse(Triple("m10", 10L, "current body"), Triple("m12", 12L, "gap body"))
            )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            body = "current body",
                            messages =
                                listOf(
                                    message(
                                        threadId = "channel-1",
                                        id = "m10",
                                        seq = 10,
                                        body = "current body",
                                        threadChannelId = "thread-m10",
                                        replyCount = 0
                                    )
                                )
                        )
                    ),
                newerResult = DataResult.Empty(EmptyReason.NoMessages),
                threadReplySummariesResult =
                    DataResult.Content(
                        ThreadReplySummariesSnapshot(
                            threads =
                                listOf(
                                    InboxItem(
                                        key = "thread:m10",
                                        kind = InboxItemKind.Thread,
                                        channelId = ChannelId("thread-m10"),
                                        channelName = "reply thread",
                                        threadChannelId = ChannelId("thread-m10"),
                                        parentChannelId = ChannelId("channel-1"),
                                        parentMessageId = MessageId("m10"),
                                        replyCount = 7,
                                        unreadCount = 2,
                                        firstUnreadMessageId = MessageId("reply-6"),
                                        latestReplies =
                                            listOf(
                                                ReplyPreview(
                                                    messageId = "reply-6",
                                                    seq = 6,
                                                    content = "first unread",
                                                    senderId = "u2",
                                                    senderType = "human",
                                                    senderName = "cindy",
                                                    senderAvatarUrl = null,
                                                    createdAt = "2026-05-19T16:06:00Z",
                                                    senderDisplayName = "Cindy",
                                                )
                                            )
                                    )
                                ),
                            followedThreadKeys = setOf("thread:m10")
                        )
                    )
            )
        val logs = mutableListOf<String>()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = logs::add)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))
        store.accept(SharedThreadIntent.FirstContentReady)
        delay(1L)

        val summariesBefore = repository.getThreadReplySummariesRequests.size

        repository.latestResult = DataResult.Content(threadSnapshot(messages = catchUpPageWithAnchorM10(null)))
        store.accept(SharedThreadIntent.RecoverRealtimeGap(serverSeq = 90))

        withTimeout(5_000) {
            while (repository.latestRequests.size != 1) delay(10)
        }
        assertEquals(listOf("current body"), store.state.value.payload.messages.map { it.body })
        // Twin: an in-foreground realtime gap that merges nothing keeps skipping the backfill (the socket
        // was up, so ThreadUpdated already carried any reply-count change).
        delay(50)
        assertTrue(
            logs.none { it.startsWith("thread_secondary_backfill") },
            "expected no secondary backfill for an empty foreground gap, got: $logs"
        )
        assertEquals(summariesBefore, repository.getThreadReplySummariesRequests.size)
        scope.cancel()
    }
    @Test
    fun realtimeGapRecoveryStaleDropSkipsSecondaryBackfill() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val api =
            FakeThreadApi(
                bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
                newerBodyText = pageResponse(Triple("m10", 10L, "current body"), Triple("m11", 11L, "stale gap body"))
            )
        val repository =
            FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        threadSnapshot(
                            body = "foreground body",
                            messages =
                                listOf(
                                    message(
                                        threadId = "channel-1",
                                        id = "m20",
                                        seq = 20,
                                        body = "foreground body",
                                        threadChannelId = "thread-m20",
                                        replyCount = 0
                                    )
                                )
                        )
                    ),
                threadReplySummariesResult =
                    DataResult.Content(
                        ThreadReplySummariesSnapshot(
                            threads = emptyList(),
                            followedThreadKeys = emptySet()
                        )
                    )
            )
        repository.latestStarted = CompletableDeferred()
        repository.releaseLatest = CompletableDeferred()
        val logs = mutableListOf<String>()
        val dataSource =
            testSharedThreadDataSource(
                storeScope = backgroundScope,
                networkApi = api,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                conversationRepository = repository,
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope, diagnosticsLogger = logs::add)
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))

        val gapJob = launch {
            store.accept(SharedThreadIntent.RecoverRealtimeGap(serverSeq = 90))
        }
        repository.latestStarted?.await()
        api.bodyText = messagesResponse("foreground body", id = "m20", seq = 20, historyLimited = false)
        store.accept(SharedThreadIntent.Refresh)
        val summariesBefore = repository.getThreadReplySummariesRequests.size
        repository.releaseLatest?.complete(Unit)
        withTimeout(10_000) { gapJob.join() }
        delay(1L)

        val summariesAfter = repository.getThreadReplySummariesRequests.size
        assertTrue(logs.any { it.startsWith("thread_realtime_gap_stale_drop") && it.contains("reason=stale_token") })
        assertTrue(
            logs.none { it.startsWith("thread_secondary_backfill") && it.contains("trigger=resume_gap") },
            "expected no secondary backfill after stale drop, got: $logs"
        )
        assertEquals(summariesBefore, summariesAfter)
        scope.cancel()
    }
    /** Task #168 step 1: a latest page holding the anchor m10 (with its reply thread) plus an optional m12. */
    private fun catchUpPageWithAnchorM10(newBody: String?): List<Message> =
        listOfNotNull(
            message(
                threadId = "channel-1",
                id = "m10",
                seq = 10,
                body = "current body",
                threadChannelId = "thread-m10",
                replyCount = 0
            ),
            newBody?.let { body -> message(threadId = "channel-1", id = "m12", seq = 12, body = body) },
        )
}
