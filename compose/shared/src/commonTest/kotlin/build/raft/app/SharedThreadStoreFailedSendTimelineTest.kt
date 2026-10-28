package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.SharedThreadStoreTest.Companion.message
import build.raft.app.SharedThreadStoreTest.Companion.messagesResponse
import build.raft.app.SharedThreadStoreTest.Companion.pageResponse
import build.raft.app.SharedThreadStoreTest.Companion.sampleSession
import build.raft.app.SharedThreadStoreTest.FakeThreadApi
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.ThreadMessagesPage
import build.raft.app.data.WireMessage
import build.raft.app.model.MessageNew
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A failed outgoing message stays on the timeline (with Retry) but is local-only: it must never act
 * as the server anchor of the newest edge, so newer realtime messages, gap catch-up pages and
 * re-entry loads keep landing and the list keeps following the tail (task #78).
 */
class SharedThreadStoreFailedSendTimelineTest {
    private fun failedLocal(id: String = "${ThreadTransientLocalSendIdPrefix}failed-1"): RaftThreadMessageItem =
        RaftThreadMessageItem(
            id = id,
            authorId = "u1",
            authorName = "Artin",
            isOwn = true,
            body = "failed body",
            threadChannelId = "channel-1",
            deliveryState = ThreadMessageDeliveryFailed,
            pendingSendErrorCode = "thread_send_network",
            pendingSendContext =
                RaftThreadPendingSendContext(
                    channelId = "channel-1",
                    body = "failed body",
                    asTask = false,
                    attachmentIds = emptyList(),
                    attachments = emptyList(),
                    mentionChannelId = "channel-1",
                    streamChannelId = "channel-1",
                    memberScopedMentions = false,
                    randomId = "random-failed-1",
                ),
        )

    private suspend fun TestScopeStore(
        api: FakeThreadApi,
        scope: kotlinx.coroutines.CoroutineScope,
    ): Pair<SharedThreadStore, GapRecoveryJobs> {
        val (store, jobs, _) = storeWithFailedSend(api, scope)
        return store to jobs
    }

    private suspend fun storeWithFailedSend(
        api: FakeThreadApi,
        scope: kotlinx.coroutines.CoroutineScope,
    ): Triple<SharedThreadStore, GapRecoveryJobs, SharedThreadDataSource> {
        val dataSource =
            testSharedThreadDataSource(
                networkApi = api,
                storeScope = scope,
                sessionStore = InMemoryRaftAuthSessionStore(sampleSession()),
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            )
        val store = SharedThreadStore(dataSource = dataSource, scope = scope)
        val jobs = store.recordGapRecoveryJobs()
        store.accept(SharedThreadIntent.Start(basePayload().copy(canLoadMoreAfter = false)))
        val provenance = requireNotNull(dataSource.currentReplyCacheProvenance())
        val route = store.state.value.payload.resolveThreadRouteIdentity()
        val local = failedLocal()
        assertTrue(
            store.admitLocalSend(
                message = local.copy(deliveryState = ThreadMessageDeliverySending, pendingSendErrorCode = null),
                expectedProvenance = provenance,
                expectedRoute = route,
            ),
        )
        store.accept(
            SharedThreadIntent.ApplyLocalSendFailure(
                messageId = local.id,
                errorCode = "thread_send_network",
                errorMessage = null,
                expectedProvenance = provenance,
                expectedRoute = route,
            ),
        )
        return Triple(store, jobs, dataSource)
    }

    private fun api(): FakeThreadApi =
        FakeThreadApi(
            bodyText = messagesResponse("current body", id = "m10", seq = 10, historyLimited = false),
            newerBodyText = pageResponse(Triple("m10", 10L, "current body"), Triple("m11", 11L, "gap body")),
        )

    @Test
    fun adjacentRealtimeMessageShowsAboveTheFailedSend() = runTest {
        val (store, _) = TestScopeStore(api(), backgroundScope)

        store.accept(SharedThreadIntent.ApplyRealtimeEvent(MessageNew(message(id = "m11", seq = 11, body = "incoming"))))

        val ids = store.state.value.payload.messages.map { it.id }
        assertEquals(listOf("m10", "m11", "${ThreadTransientLocalSendIdPrefix}failed-1"), ids)
    }

    @Test
    fun gapRealtimeMessageCatchesUpAndKeepsTheFailedSendLast() = runTest {
        val (store, jobs) = TestScopeStore(api(), backgroundScope)

        store.accept(SharedThreadIntent.ApplyRealtimeEvent(MessageNew(message(id = "m12", seq = 12, body = "latest"))))
        jobs.joinAll()

        val payload = store.state.value.payload
        assertEquals(listOf("m10", "m11", "${ThreadTransientLocalSendIdPrefix}failed-1"), payload.messages.map { it.id })
        assertEquals(false, payload.canLoadMoreAfter)
        assertEquals(ThreadMessageDeliveryFailed, payload.messages.last().deliveryState)
    }

    @Test
    fun retryAfterNewMessagesLandsTheSentMessageAfterThemAndDropsTheFailedRow() = runTest {
        val (store, _, dataSource) = storeWithFailedSend(api(), backgroundScope)
        store.accept(SharedThreadIntent.ApplyRealtimeEvent(MessageNew(message(id = "m11", seq = 11, body = "incoming"))))
        val localId = "${ThreadTransientLocalSendIdPrefix}failed-1"
        val provenance = requireNotNull(dataSource.currentReplyCacheProvenance())
        val route = store.state.value.payload.resolveThreadRouteIdentity()
        val failed = store.state.value.payload.messages.first { it.id == localId }
        assertTrue(
            store.admitLocalSend(
                message = failed.copy(deliveryState = ThreadMessageDeliverySending, pendingSendErrorCode = null),
                expectedProvenance = provenance,
                expectedRoute = route,
            ),
        )

        store.accept(
            SharedThreadIntent.ReplaceLocalSendWithSentPage(
                messageId = localId,
                page =
                    ThreadMessagesPage(
                        messages =
                            listOf(
                                WireMessage(
                                    id = "server-retry",
                                    channelId = "channel-1",
                                    senderType = "user",
                                    senderId = "u1",
                                    senderName = "Artin",
                                    messageType = "chat",
                                    content = "failed body",
                                    createdAt = "2026-05-19T16:02:00Z",
                                    seq = 12,
                                    randomId = "random-failed-1",
                                ),
                            ),
                        canLoadMoreBefore = null,
                        canLoadMoreAfter = null,
                    ),
                expectedProvenance = provenance,
                expectedRoute = route,
            ),
        )

        assertEquals(listOf("m10", "m11", "server-retry"), store.state.value.payload.messages.map { it.id })
    }

    private fun sentPage(id: String, randomId: String?): ThreadMessagesPage =
        ThreadMessagesPage(
            messages =
                listOf(
                    WireMessage(
                        id = id,
                        channelId = "channel-1",
                        senderType = "user",
                        senderId = "u1",
                        senderName = "Artin",
                        messageType = "chat",
                        content = "failed body",
                        createdAt = "2026-05-19T16:02:00Z",
                        seq = 12,
                        randomId = randomId,
                    ),
                ),
            canLoadMoreBefore = null,
            canLoadMoreAfter = null,
        )

    /**
     * Feedback 03e9a2f6 (1.16.1): the drainer adopted an orphaned "sending" row on page entry,
     * flipped it to failed (`thread_send_adopted_stale`, projected onto the open page) and then
     * resent it with the same randomId, which succeeded. The success replacement removed the row,
     * but the failed-row retention put it straight back beside the delivered message.
     */
    @Test
    fun adoptedStaleRowThatTheDrainResendDeliversResolvesToTheDeliveredMessage() = runTest {
        val (store, _, dataSource) = storeWithFailedSend(api(), backgroundScope)
        val localId = "${ThreadTransientLocalSendIdPrefix}failed-1"
        val provenance = requireNotNull(dataSource.currentReplyCacheProvenance())
        val route = store.state.value.payload.resolveThreadRouteIdentity()
        // The drainer's adoption projection: the row is failed (not re-admitted as sending) when
        // the resend's success lands.
        store.accept(
            SharedThreadIntent.ApplyLocalSendFailure(
                messageId = localId,
                errorCode = "thread_send_adopted_stale",
                errorMessage = null,
                expectedProvenance = provenance,
                expectedRoute = route,
            ),
        )
        assertEquals(
            ThreadMessageDeliveryFailed,
            store.state.value.payload.messages.single { it.id == localId }.deliveryState,
        )

        store.accept(
            SharedThreadIntent.ReplaceLocalSendWithSentPage(
                messageId = localId,
                page = sentPage(id = "server-drained", randomId = "random-failed-1"),
                expectedProvenance = provenance,
                expectedRoute = route,
            ),
        )

        val messages = store.state.value.payload.messages
        assertEquals(listOf("m10", "server-drained"), messages.map { it.id })
        assertTrue(messages.none { it.deliveryState == ThreadMessageDeliveryFailed })
    }

    @Test
    fun drainSuccessWithoutRandomIdEchoStillRetiresTheFailedRow() = runTest {
        val (store, _, dataSource) = storeWithFailedSend(api(), backgroundScope)
        val localId = "${ThreadTransientLocalSendIdPrefix}failed-1"

        store.accept(
            SharedThreadIntent.ReplaceLocalSendWithSentPage(
                messageId = localId,
                page = sentPage(id = "server-legacy", randomId = null),
                expectedProvenance = requireNotNull(dataSource.currentReplyCacheProvenance()),
                expectedRoute = store.state.value.payload.resolveThreadRouteIdentity(),
            ),
        )

        // The HTTP success for this exact localId is the proof; no content heuristic is needed.
        assertEquals(listOf("m10", "server-legacy"), store.state.value.payload.messages.map { it.id })
    }

    @Test
    fun realtimeEchoWithTheSameRandomIdRetiresTheFailedRow() = runTest {
        val (store, _) = TestScopeStore(api(), backgroundScope)

        store.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(message(id = "m11", seq = 11, body = "failed body").copy(randomId = "random-failed-1")),
            ),
        )

        assertEquals(listOf("m10", "m11"), store.state.value.payload.messages.map { it.id })
    }

    @Test
    fun stableMergeNeverKeepsAFailedShadowBesideItsDeliveredEcho() {
        val failed = failedLocal()
        val previous = slicePayload(server("m10", 10), failed)
        val echo = server("m11", 11).copy(authorId = "u1", isOwn = true, body = "failed body", randomId = "random-failed-1")

        val merged = slicePayload(server("m10", 10), echo).preserveStableTimelineFrom(previous)
        assertEquals(listOf("m10", "m11"), merged.messages.map { it.id })

        // Same echo already in the timeline next to the failed shadow (gap catch-up shape).
        val sideBySide = slicePayload(server("m10", 10), echo, failed).preserveStableTimelineFrom(previous)
        assertEquals(listOf("m10", "m11"), sideBySide.messages.map { it.id })
    }

    @Test
    fun stableMergeKeepsAFailedShadowWithoutExactRandomIdProof() {
        val failed = failedLocal()
        val previous = slicePayload(server("m10", 10), failed)
        // Same author + body but no random-id echo (or a different one): still a retryable failure.
        val lookalike = server("m11", 11).copy(authorId = "u1", isOwn = true, body = "failed body")
        val otherSend = lookalike.copy(id = "m12", seq = 12, randomId = "random-other")

        val merged = slicePayload(server("m10", 10), lookalike, otherSend).preserveStableTimelineFrom(previous)

        assertEquals(listOf("m10", "m11", "m12", failed.id), merged.messages.map { it.id })
        assertEquals(ThreadMessageDeliveryFailed, merged.messages.last().deliveryState)
    }

    @Test
    fun gapCatchUpAnchorAndNewestServerSeqIgnoreLocalOnlyItems() = runTest {
        val (store, _) = TestScopeStore(api(), backgroundScope)
        val payload = store.state.value.payload

        assertEquals("m10", payload.gapCatchUpAnchorMessageId())
        assertEquals(10L, payload.newestServerSeqOrNull())
        assertNull(payload.copy(messages = payload.messages.filter { it.pendingSendContext != null }).gapCatchUpAnchorMessageId())
    }

    private fun server(id: String, seq: Long): RaftThreadMessageItem =
        RaftThreadMessageItem(id = id, authorId = "u2", authorName = "Bot", body = id, seq = seq)

    private fun slicePayload(vararg messages: RaftThreadMessageItem): RaftThreadScreenPayload =
        RaftThreadScreenPayload(
            instanceId = "i",
            sourceThreadId = "channel-1",
            status = "content",
            messages = messages.toList(),
        )

    private fun slice(vararg messages: RaftThreadMessageItem): ThreadTimelineSlice =
        RaftThreadScreenPayload(
            instanceId = "i",
            sourceThreadId = "channel-1",
            status = "content",
            messages = messages.toList(),
        ).toThreadTimelineSlice()

    @Test
    fun readMarkerKeyAdvancesPastAFailedSendPinnedAtTheTail() {
        val failed = failedLocal()
        val before = slice(server("m10", 10), failed).visibleReadMarkerKey("channel-1")
        val after = slice(server("m10", 10), server("m11", 11), failed).visibleReadMarkerKey("channel-1")

        assertEquals("channel-1:10:m10", before)
        assertEquals("channel-1:11:m11", after)
        // The rendered tail may be the failed row itself: it still resolves to the newest server row.
        assertEquals(after, slice(server("m10", 10), server("m11", 11), failed).visibleReadMarkerKey("channel-1", 2))
        // A timeline of only local rows keeps its old tail behavior.
        assertEquals("channel-1:no-seq:${failed.id}", slice(failed).visibleReadMarkerKey("channel-1"))
    }

    @Test
    fun newMessagesBelowCountsArrivalsLandingAboveAFailedSend() {
        val failedId = failedLocal().id
        fun rows(vararg ids: String) = (ids.toList() + failedId).map { ThreadJumpArrival(it, countable = it != failedId) }
        var state = reduceThreadNewMessagesBelow(ThreadNewMessagesBelow(), rows("m9", "m10"), awayFromBottom = true, canLoadMoreAfter = false)
        assertEquals(ThreadNewMessagesBelow(count = 0, lastMessageId = "m10"), state)

        state = reduceThreadNewMessagesBelow(state, rows("m9", "m10", "m11", "m12"), awayFromBottom = true, canLoadMoreAfter = false)

        assertEquals(ThreadNewMessagesBelow(count = 2, lastMessageId = "m12"), state)
    }

    @Test
    fun serverMessageAboveATrailingFailedSendIsAnAppendMutation() {
        val failedId = failedLocal().id
        val local = setOf(failedId)

        assertEquals(
            LazyViewportMutation.Append,
            classifyThreadViewportMutation(listOf("m9", "m10", failedId), listOf("m9", "m10", "m11", failedId), local),
        )
        assertEquals(
            LazyViewportMutation.Prepend,
            classifyThreadViewportMutation(listOf("m10", failedId), listOf("m8", "m9", "m10", failedId), local),
        )
        assertEquals(
            LazyViewportMutation.HeightChange,
            classifyThreadViewportMutation(listOf("m9", "m10", failedId), listOf("m9", "x10", failedId), local),
        )
    }
}
