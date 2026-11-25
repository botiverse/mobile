package build.raft.app

import build.raft.app.data.AuthenticatedRouteOwner
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.TaskConversationDetailsViewModel
import build.raft.app.data.TaskDetailLoad
import build.raft.app.data.TaskEarlierDiscussion
import build.raft.app.data.TaskSourceMessagePreview
import build.raft.app.data.ThreadApiResult
import build.raft.app.data.ThreadAttachmentOpenTarget
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.model.ChannelId
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.Presence
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import build.raft.app.model.TaskSummary
import build.raft.app.model.ThreadId
import build.raft.app.network.sync.CanonicalConversationContextV2
import build.raft.app.network.sync.CanonicalMessageEnvelopeV2
import build.raft.app.network.sync.CanonicalMessageNodeV2
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.PersistedCursor
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SyncScopeId
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.ThreadDurableLastKnownFrame
import build.raft.app.network.sync.ThreadDurableLastKnownReader
import build.raft.app.network.sync.ThreadDurableTimelineAnchor
import build.raft.app.network.sync.ThreadDurableTimelinePage
import build.raft.app.network.sync.TopLevelScopeRefV2
import build.raft.app.resources.Res
import build.raft.app.resources.desc
import build.raft.app.resources.thread_offline_older_unavailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for #proj-mobile:#386 & #proj-mobile:#388:
 * - Online stand-in paging (`durableLastKnown && !mainLoadFailed`) never bridges gaps and seamlessly
 *   fetches from the server at segment edges for both `LoadOlder` and `LoadNewer`.
 * - Offline stand-in paging (`durableLastKnown && mainLoadFailed`) stays strictly inside its proven
 *   contiguous segment, stops cleanly at the segment edge with the single outer
 *   "Older messages are not available offline" boundary row (zero network spam, zero stuck spinner),
 *   and restores online older paging cleanly once connectivity returns via `Refresh`.
 * - Inline audio preview caches resolved URLs in `RaftFilePreviewAttachmentUrlCache`, invalidates
 *   on forced retry, and settles exceptions to `Error` (never stuck `Loading`).
 * - `TaskConversationDetailsViewModel` guards `SourceStarted` / `EarlierStarted` on `channelId != null`
 *   so missing `channelId` never sticks `source` or `earlierDiscussion` in `Loading` forever.
 */
class ThreadDurableGapContractTest {
    @Test
    fun onlineStandInLoadOlderAndLoadNewerFetchAtSegmentEdgeWithoutBridgingGaps() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val observedAnchors = mutableListOf<String>()
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val api =
            SharedThreadStoreTest.FakeThreadApi(
                bodyText = messagesJson((15L..25L).toList()),
                olderBodyText = messagesJson((10L..14L).toList()),
                newerBodyText = messagesJson((26L..30L).toList()),
            ).apply {
                channelMessagesStarted = refreshStarted
                releaseChannelMessages = releaseRefresh
            }
        val reader =
            object : ThreadDurableLastKnownReader {
                override val supportsTimelineReads: Boolean = true
                override suspend fun read(partition: DurablePartitionKey, channelId: SyncScopeId): ThreadDurableLastKnownFrame? = null
                override suspend fun readTimeline(
                    partition: DurablePartitionKey,
                    channelId: SyncScopeId,
                    anchor: ThreadDurableTimelineAnchor,
                    touchUserAccess: Boolean,
                ): ThreadDurableTimelinePage? =
                    when (anchor) {
                        is ThreadDurableTimelineAnchor.AroundMessageId ->
                            timelinePage(
                                seqs = 15L..25L,
                                hasOlderStored = false,
                                hasNewerStored = false,
                                insideProvenSegment = true,
                            )
                        is ThreadDurableTimelineAnchor.Before -> {
                            observedAnchors += "before:${anchor.beforeSeq}"
                            timelinePage(seqs = emptyList(), hasOlderStored = false, insideProvenSegment = false)
                        }
                        is ThreadDurableTimelineAnchor.After -> {
                            observedAnchors += "after:${anchor.afterSeq}"
                            timelinePage(seqs = emptyList(), hasNewerStored = false, insideProvenSegment = false)
                        }
                        else -> null
                    }
            }
        val store = SharedThreadStore(dataSource(backgroundScope, api, reader), scope = scope)
        val startJob = launch { store.accept(SharedThreadIntent.Start(basePayload().copy(focusedMessageId = "v2-20"))) }
        refreshStarted.await()

        val firstFrame = store.state.value.payload
        assertTrue(firstFrame.durableLastKnown)
        assertFalse(firstFrame.mainLoadFailed)
        assertTrue(firstFrame.canLoadMoreBefore)
        assertTrue(firstFrame.canLoadMoreAfter)

        // Both LoadOlder and LoadNewer while online refresh is in flight hit the segment edge
        // and fall through to network seamlessly without bridging gaps.
        store.accept(SharedThreadIntent.LoadOlder)
        assertEquals(listOf(15L), api.beforeSeqs)
        assertEquals((10L..25L).toList(), store.state.value.payload.messages.mapNotNull { it.seq })

        store.accept(SharedThreadIntent.LoadNewer)
        assertEquals(listOf(25L), api.afterSeqs)
        assertEquals((10L..30L).toList(), store.state.value.payload.messages.mapNotNull { it.seq })

        assertEquals(listOf("before:15", "after:25"), observedAnchors)

        releaseRefresh.complete(Unit)
        startJob.join()
        scopeJob.cancelAndJoin()
    }

    @Test
    fun offlineStandInStopsCleanlyAtContiguousSegmentEdgeWithOuterBoundaryAndRecoversOnReconnect() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true)
        val reader =
            object : ThreadDurableLastKnownReader {
                override val supportsTimelineReads: Boolean = true
                override suspend fun read(partition: DurablePartitionKey, channelId: SyncScopeId): ThreadDurableLastKnownFrame? = null
                override suspend fun readTimeline(
                    partition: DurablePartitionKey,
                    channelId: SyncScopeId,
                    anchor: ThreadDurableTimelineAnchor,
                    touchUserAccess: Boolean,
                ): ThreadDurableTimelinePage? =
                    when (anchor) {
                        is ThreadDurableTimelineAnchor.Newest ->
                            timelinePage(
                                seqs = 20L..25L,
                                hasOlderStored = true,
                                reachesOldest = false,
                                reachesHead = true,
                                insideProvenSegment = true,
                            )
                        is ThreadDurableTimelineAnchor.Before ->
                            // Simulates reaching segment edge when older row lookup discovers no contiguous rows.
                            timelinePage(
                                seqs = emptyList(),
                                hasOlderStored = false,
                                reachesOldest = false,
                                insideProvenSegment = false,
                            )
                        else -> null
                    }
            }
        val store = SharedThreadStore(dataSource(backgroundScope, api, reader), scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        assertTrue(store.state.value.payload.durableLastKnown)
        assertTrue(store.state.value.payload.mainLoadFailed)
        assertTrue(store.state.value.payload.canLoadMoreBefore)
        assertEquals(1, api.channelMessageCalls)

        // Offline LoadOlder at segment edge closes canLoadMoreBefore cleanly without bridging gaps
        // or calling the network, rendering the single outer offline boundary row.
        store.accept(SharedThreadIntent.LoadOlder)
        val stopped = store.state.value.payload
        assertEquals((20L..25L).toList(), stopped.messages.mapNotNull { it.seq })
        assertFalse(stopped.canLoadMoreBefore)
        assertFalse(stopped.durableReachedOldest)
        assertEquals("idle", stopped.olderMessagesStatus)
        assertEquals(1, api.channelMessageCalls, "offline segment edge must not hit network")
        assertTrue(api.beforeSeqs.isEmpty())
        val outerSpec = assertNotNull(threadOlderMessagesStatusSpec(stopped))
        assertEquals(Res.string.thread_offline_older_unavailable.desc(), outerSpec.label)
        assertFalse(outerSpec.loading)

        // Connectivity returns: Refresh replaces the stand-in and restores normal online older paging.
        api.failChannelMessages = false
        api.bodyText = messagesJson((20L..49L).toList(), historyLimited = true)
        api.olderBodyText = messagesJson((10L..19L).toList(), historyLimited = false)
        store.accept(SharedThreadIntent.Refresh)
        val reconnected = store.state.value.payload
        assertFalse(reconnected.durableLastKnown)
        assertFalse(reconnected.mainLoadFailed)
        assertTrue(reconnected.canLoadMoreBefore)

        store.accept(SharedThreadIntent.LoadOlder)
        assertEquals(listOf(20L), api.beforeSeqs)
        assertEquals((10L..49L).toList(), store.state.value.payload.messages.mapNotNull { it.seq })
        scopeJob.cancelAndJoin()
    }

    @Test
    fun inlineAudioPreviewCachesUrlInvalidatesOnForceAndSettlesExceptionToError() = runTest {
        val owner = AuthenticatedRouteOwner("https://api.raft.build", "user-1", "server-1")
        val urlCache = RaftFilePreviewAttachmentUrlCache()
        val key = RaftFilePreviewAttachmentUrlCacheKey(owner, "aud-1", "inline")
        val states = mutableListOf<ThreadInlineAudioPreviewUiState>()
        var fetchCount = 0

        // 1. Thrown exception settles cleanly to Error (never stuck in Loading).
        runThreadInlineAudioPreviewFetch(
            attachmentId = "aud-1",
            force = false,
            ownerProvider = { owner },
            urlCache = urlCache,
            fetch = {
                fetchCount += 1
                throw IllegalStateException("socket reset")
            },
            onState = { states += it },
        )
        assertEquals(
            listOf(ThreadInlineAudioPreviewStatus.Loading, ThreadInlineAudioPreviewStatus.Error),
            states.map { it.status },
        )
        assertNull(urlCache.get(key))

        // 2. Successful fetch writes url + future expiresAt into shared urlCache.
        states.clear()
        val futureExpiresAt = "2099-01-01T00:00:00Z"
        runThreadInlineAudioPreviewFetch(
            attachmentId = "aud-1",
            force = false,
            ownerProvider = { owner },
            urlCache = urlCache,
            fetch = {
                fetchCount += 1
                ThreadApiResult.Success(ThreadAttachmentOpenTarget("https://r2.example/aud-1.mp3?sig=1", futureExpiresAt))
            },
            onState = { states += it },
        )
        assertEquals(ThreadInlineAudioPreviewStatus.Ready, states.last().status)
        assertEquals("https://r2.example/aud-1.mp3?sig=1", urlCache.get(key))
        assertEquals(2, fetchCount)

        // 3. Non-forced re-request serves directly from shared urlCache with 0 network calls.
        states.clear()
        runThreadInlineAudioPreviewFetch(
            attachmentId = "aud-1",
            force = false,
            ownerProvider = { owner },
            urlCache = urlCache,
            fetch = {
                fetchCount += 1
                error("warm cache must not hit network")
            },
            onState = { states += it },
        )
        assertEquals(1, states.size)
        assertEquals(ThreadInlineAudioPreviewStatus.Ready, states.single().status)
        assertEquals("https://r2.example/aud-1.mp3?sig=1", states.single().url)
        assertEquals(2, fetchCount)

        // 4. Forced retry invalidates stale cached URL and fetches fresh URL.
        states.clear()
        runThreadInlineAudioPreviewFetch(
            attachmentId = "aud-1",
            force = true,
            ownerProvider = { owner },
            urlCache = urlCache,
            fetch = {
                fetchCount += 1
                ThreadApiResult.Success(ThreadAttachmentOpenTarget("https://r2.example/aud-1.mp3?sig=2", futureExpiresAt))
            },
            onState = { states += it },
        )
        assertEquals(3, fetchCount)
        assertEquals("https://r2.example/aud-1.mp3?sig=2", urlCache.get(key))
        assertEquals("https://r2.example/aud-1.mp3?sig=2", states.last().url)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun taskConversationDetailsViewModelNeverSticksLoadingWhenChannelIdIsNullAndLoadsOnceChannelArrives() = runTest {
        var sourceCalls = 0
        var earlierCalls = 0
        val viewModel =
            TaskConversationDetailsViewModel(
                loadHistory = { DataResult.Empty(EmptyReason.NoTasks) },
                loadSource = { _, messageId ->
                    sourceCalls += 1
                    DataResult.Content(
                        TaskSourceMessagePreview(
                            messageId = messageId,
                            author = null,
                            firstLine = "Origin message",
                            createdAt = "2026-11-25T10:00:00Z",
                        )
                    )
                },
                loadEarlierDiscussion = { _, hostMessageId ->
                    earlierCalls += 1
                    DataResult.Content(
                        TaskEarlierDiscussion(
                            hostMessageId = hostMessageId,
                            replyCount = 3,
                            threadChannelId = ChannelId("thread-1"),
                        )
                    )
                },
                scopeOverride = this,
            )

        val taskWithoutChannel =
            TaskSummary(
                id = TaskId("task-1"),
                title = "Audit loading gaps",
                status = TaskStatus.InProgress,
                channelId = null,
                updatedAtLabel = "2026-11-25T10:00:00Z",
                hostMessageId = MessageId("host-1"),
                sourceMessageId = MessageId("src-1"),
                sourceRecorded = true,
            )
        viewModel.onTask(taskWithoutChannel)
        advanceUntilIdle()

        assertEquals(0, sourceCalls)
        assertEquals(0, earlierCalls)
        assertIs<TaskDetailLoad.Idle>(viewModel.state.value.source)
        assertIs<TaskDetailLoad.Idle>(viewModel.state.value.earlierDiscussion)
        assertNull(viewModel.state.value.sourceMessageId)
        assertNull(viewModel.state.value.hostMessageId)

        // Once channelId becomes known on a subsequent update, both source and earlierDiscussion load cleanly.
        viewModel.onTask(taskWithoutChannel.copy(channelId = ChannelId("ch-1")))
        advanceUntilIdle()

        assertEquals(1, sourceCalls)
        assertEquals(1, earlierCalls)
        assertEquals(MessageId("src-1"), assertIs<TaskDetailLoad.Loaded<TaskSourceMessagePreview>>(viewModel.state.value.source).value.messageId)
        assertEquals(3, assertIs<TaskDetailLoad.Loaded<TaskEarlierDiscussion>>(viewModel.state.value.earlierDiscussion).value.replyCount)
    }

    private fun timelinePage(
        seqs: Iterable<Long>,
        hasOlderStored: Boolean = false,
        hasNewerStored: Boolean = false,
        reachesOldest: Boolean = false,
        reachesHead: Boolean = false,
        insideProvenSegment: Boolean = true,
    ): ThreadDurableTimelinePage {
        val sorted = seqs.toList().sorted()
        val partition =
            DurablePartitionKey(
                serverId = ownerA.serverId,
                principalId = ownerA.principalId,
                consumerId = SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID,
                environmentId = ownerA.environmentId,
            )
        return ThreadDurableTimelinePage(
            scope = SyncScopeKey(partition, "channel-1", "channel"),
            envelopes =
                sorted.map { seq ->
                    CanonicalMessageEnvelopeV2(
                        fact =
                            CanonicalMessageNodeV2(
                                id = "v2-$seq",
                                seq = seq,
                                senderType = "user",
                                senderId = "u-a",
                                content = "body $seq",
                                messageType = "chat",
                                createdAt = "2026-05-19T16:00:${(seq % 60).toString().padStart(2, '0')}Z",
                                senderName = "Owner A",
                            ),
                        context = CanonicalConversationContextV2.TopLevel(TopLevelScopeRefV2.Channel("server-1", "channel-1")),
                    )
                },
            cursor =
                PersistedCursor(
                    latestSeq = sorted.maxOrNull(),
                    oldestLoadedSeq = sorted.minOrNull(),
                    newestLoadedSeq = sorted.maxOrNull(),
                    hasLoadedOldest = reachesOldest,
                    hasLoadedNewest = reachesHead,
                    epoch = "epoch-v2",
                ),
            segments = emptyList(),
            hasOlderStored = hasOlderStored,
            hasNewerStored = hasNewerStored,
            reachesOldest = reachesOldest,
            reachesHead = reachesHead,
            insideProvenSegment = insideProvenSegment,
        )
    }

    private fun messagesJson(seqs: List<Long>, historyLimited: Boolean = false): String {
        val items =
            seqs.joinToString(",") { seq ->
                """
                {
                  "id": "v2-$seq",
                  "seq": $seq,
                  "channelId": "channel-1",
                  "content": "body $seq",
                  "createdAt": "2026-05-19T16:00:${(seq % 60).toString().padStart(2, '0')}Z",
                  "senderId": "u-a",
                  "senderName": "Owner A",
                  "senderType": "user",
                  "messageType": "chat"
                }
                """.trimIndent()
            }
        return """{"historyLimited": $historyLimited, "messages": [$items]}"""
    }

    private fun dataSource(
        storeScope: CoroutineScope,
        api: SharedThreadStoreTest.FakeThreadApi,
        reader: ThreadDurableLastKnownReader,
    ): SharedThreadDataSource =
        SharedThreadDataSource(
            threadParseDispatcher = storeScope.testDispatcher(),
            durableDbDispatcher = storeScope.testDispatcher(),
            messageStore = SharedThreadMessageStore(scope = storeScope),
            payloadStore = SharedThreadPayloadStore(scope = storeScope),
            networkApi = api,
            sessionStore = InMemoryRaftAuthSessionStore(ownerA.session),
            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            replyCacheIdentityAuthority = { ownerA },
            durableLastKnownReader = reader,
        )

    private val ownerA =
        ThreadReplyCacheIdentitySnapshot(
            session =
                StoredAuthSession(
                    user = StoredAuthUser(id = "u-a", email = "u-a@example.test", displayName = "Owner A"),
                    accessToken = "access-u-a",
                    refreshToken = "refresh-u-a",
                    selectedServerId = "server-1",
                ),
            environmentId = TestThreadEnvironmentId,
            principalId = "u-a",
            serverId = "server-1",
            authGeneration = 1L,
            selectionGeneration = 1L,
        )

    private fun basePayload(): RaftThreadScreenPayload =
        RaftThreadScreenPayload(
            instanceId = "shared-shell-thread-channel-1",
            title = "general",
            sourceThreadId = "channel-1",
            status = "loading",
            canSend = true,
        )
}
