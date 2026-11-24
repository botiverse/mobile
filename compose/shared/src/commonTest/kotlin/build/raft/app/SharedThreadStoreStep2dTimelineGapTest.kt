package build.raft.app

import build.raft.app.data.ChannelMessageWindow
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RawMessagePage
import build.raft.app.data.RawMessagePageWitness
import build.raft.app.data.ScreenError
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadMessagesPage
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.data.ThreadSnapshot
import build.raft.app.data.WireMessage
import build.raft.app.data.buildSharedThreadPayloadFromMessagesPage
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.MessageNew
import build.raft.app.model.Presence
import build.raft.app.model.ThreadConversation
import build.raft.app.model.ThreadId
import build.raft.app.network.sync.CanonicalConversationContextV2
import build.raft.app.network.sync.CanonicalMessageEnvelopeV2
import build.raft.app.network.sync.CanonicalMessageNodeV2
import build.raft.app.network.sync.CanonicalMessageV2TimelineGap
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.MessageRefV2
import build.raft.app.network.sync.PersistedCursor
import build.raft.app.network.sync.ReplyMembershipV2
import build.raft.app.network.sync.ReplyScopeRefV2
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SyncScopeId
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.ThreadDurableLastKnownFrame
import build.raft.app.network.sync.ThreadDurableLastKnownReader
import build.raft.app.network.sync.ThreadDurablePageDirection
import build.raft.app.network.sync.ThreadDurablePageExtender
import build.raft.app.network.sync.ThreadDurableReplyParent
import build.raft.app.network.sync.ThreadDurableTimelineAnchor
import build.raft.app.network.sync.ThreadDurableTimelinePage
import build.raft.app.network.sync.TopLevelScopeRefV2
import build.raft.app.resources.Res
import build.raft.app.resources.desc
import build.raft.app.resources.thread_offline_gap_unavailable
import build.raft.app.resources.thread_offline_gap_unavailable_replies
import build.raft.app.resources.thread_offline_older_unavailable
import build.raft.app.resources.thread_retry_gap
import build.raft.app.resources.thread_retry_gap_replies
import build.raft.app.resources.thread_retry_older
import build.raft.app.resources.thread_state_loading_gap
import build.raft.app.resources.thread_state_loading_gap_replies
import build.raft.app.resources.thread_state_loading_older
import build.raft.app.resources.thread_state_loading_older_replies
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 2d PR 2 (#proj-mobile:#240): bounded SQLite timeline reads, online proven-segment local
 * paging, offline island crossing with derived inline timeline gaps, Q5 gap filling, and anchored
 * cold opens / offline jumps.
 */
class SharedThreadStoreStep2dTimelineGapTest {
    @Test
    fun gapRowSpecUsesDedicatedInlineGapCopyAndCollapsedGraceDelayAcrossAllStates() {
        val idle = ThreadTimelineGap(10L, 30L, status = "idle")
        val loading = ThreadTimelineGap(10L, 30L, status = "loading")
        val error = ThreadTimelineGap(10L, 30L, status = "error")

        val olderLabels =
            setOf(
                Res.string.thread_state_loading_older.desc(),
                Res.string.thread_state_loading_older_replies.desc(),
                Res.string.thread_retry_older.desc(),
                Res.string.thread_offline_older_unavailable.desc(),
            )
        // Exhaustive guard: no combination ever reuses top-of-list "older" strings.
        for (gap in listOf(idle, loading, error)) {
            for (durable in listOf(false, true)) {
                for (failed in listOf(false, true)) {
                    for (reply in listOf(false, true)) {
                        for (userInitiated in listOf(false, true)) {
                            val spec = threadTimelineGapRowSpec(gap, durable, failed, reply, userInitiated)
                            assertFalse(
                                spec.label in olderLabels,
                                "inline gap must never reuse top-of-list older copy: $spec",
                            )
                        }
                    }
                }
            }
        }

        // Stand-in while initial refresh is in flight (durableLastKnown=true, mainLoadFailed=false):
        // collapses (reserveSpaceBeforeReveal=false) for the longer inline grace delay (2s).
        assertEquals(
            ThreadTimelineGapRowSpec.Loading(
                label = Res.string.thread_state_loading_gap.desc(),
                revealDelayMs = ThreadStandInInlineGapRevealDelayMs,
                reserveSpaceBeforeReveal = false,
            ),
            threadTimelineGapRowSpec(idle, durableLastKnown = true, mainLoadFailed = false, replyThread = false),
        )
        assertEquals(
            ThreadTimelineGapRowSpec.Loading(
                label = Res.string.thread_state_loading_gap_replies.desc(),
                revealDelayMs = ThreadStandInInlineGapRevealDelayMs,
                reserveSpaceBeforeReveal = false,
            ),
            threadTimelineGapRowSpec(idle, durableLastKnown = true, mainLoadFailed = false, replyThread = true),
        )

        // Online auto-fill: idle and auto-started loading share the same 600ms collapsed delay so
        // transitioning idle -> loading does not bypass or restart the grace timer.
        assertEquals(
            ThreadTimelineGapRowSpec.Loading(
                label = Res.string.thread_state_loading_gap.desc(),
                revealDelayMs = ThreadStandInLoadingRevealDelayMs,
                reserveSpaceBeforeReveal = false,
            ),
            threadTimelineGapRowSpec(idle, durableLastKnown = false, mainLoadFailed = false, replyThread = false),
        )
        assertEquals(
            ThreadTimelineGapRowSpec.Loading(
                label = Res.string.thread_state_loading_gap.desc(),
                revealDelayMs = ThreadStandInLoadingRevealDelayMs,
                reserveSpaceBeforeReveal = false,
            ),
            threadTimelineGapRowSpec(loading, durableLastKnown = false, mainLoadFailed = false, replyThread = false, userInitiatedFill = false),
        )
        // Explicit user retry while online reveals immediately (0 ms).
        assertEquals(
            ThreadTimelineGapRowSpec.Loading(
                label = Res.string.thread_state_loading_gap_replies.desc(),
                revealDelayMs = 0L,
                reserveSpaceBeforeReveal = false,
            ),
            threadTimelineGapRowSpec(loading, durableLastKnown = false, mainLoadFailed = false, replyThread = true, userInitiatedFill = true),
        )
        // Active fill on a durable stand-in or after mainLoadFailed reveals immediately (0 ms).
        assertEquals(
            ThreadTimelineGapRowSpec.Loading(
                label = Res.string.thread_state_loading_gap_replies.desc(),
                revealDelayMs = 0L,
                reserveSpaceBeforeReveal = false,
            ),
            threadTimelineGapRowSpec(loading, durableLastKnown = true, mainLoadFailed = true, replyThread = true),
        )
        assertEquals(
            ThreadTimelineGapRowSpec.Loading(
                label = Res.string.thread_state_loading_gap.desc(),
                revealDelayMs = 0L,
                reserveSpaceBeforeReveal = false,
            ),
            threadTimelineGapRowSpec(loading, durableLastKnown = true, mainLoadFailed = false, replyThread = false),
        )
        assertEquals(
            ThreadTimelineGapRowSpec.Loading(
                label = Res.string.thread_state_loading_gap.desc(),
                revealDelayMs = 0L,
                reserveSpaceBeforeReveal = false,
            ),
            threadTimelineGapRowSpec(loading, durableLastKnown = false, mainLoadFailed = true, replyThread = false),
        )

        // Error state: Retry when online or when main load did not fail; Offline when durable & failed.
        assertEquals(
            ThreadTimelineGapRowSpec.Retry(Res.string.thread_retry_gap.desc()),
            threadTimelineGapRowSpec(error, durableLastKnown = false, mainLoadFailed = false, replyThread = false),
        )
        assertEquals(
            ThreadTimelineGapRowSpec.Retry(Res.string.thread_retry_gap_replies.desc()),
            threadTimelineGapRowSpec(error, durableLastKnown = false, mainLoadFailed = true, replyThread = true),
        )
        assertEquals(
            ThreadTimelineGapRowSpec.Retry(Res.string.thread_retry_gap_replies.desc()),
            threadTimelineGapRowSpec(error, durableLastKnown = true, mainLoadFailed = false, replyThread = true),
        )
        assertEquals(
            ThreadTimelineGapRowSpec.Offline(Res.string.thread_offline_gap_unavailable.desc()),
            threadTimelineGapRowSpec(error, durableLastKnown = true, mainLoadFailed = true, replyThread = false),
        )
        assertEquals(
            ThreadTimelineGapRowSpec.Offline(Res.string.thread_offline_gap_unavailable_replies.desc()),
            threadTimelineGapRowSpec(idle, durableLastKnown = true, mainLoadFailed = true, replyThread = true),
        )
        assertEquals(
            ThreadTimelineGapRowSpec.Offline(Res.string.thread_offline_gap_unavailable.desc()),
            threadTimelineGapRowSpec(idle, durableLastKnown = false, mainLoadFailed = true, replyThread = false),
        )
    }

    @Test
    fun gapKeyParsingReconciliationMultiGapSpilloverAndPendingSendAttachmentHold() {
        assertEquals(10L to 25L, parseThreadTimelineGapKey("10:25"))
        assertEquals(0L to 2L, parseThreadTimelineGapKey(" 0:2 "))
        assertNull(parseThreadTimelineGapKey(null))
        assertNull(parseThreadTimelineGapKey(""))
        assertNull(parseThreadTimelineGapKey("10:11"))
        assertNull(parseThreadTimelineGapKey("-1:5"))
        assertNull(parseThreadTimelineGapKey("bad"))

        val msgs =
            listOf(
                messageItem("m-10", seq = 10L),
                messageItem("local-pending", seq = null),
                messageItem("m-30", seq = 30L),
                messageItem("m-50", seq = 50L),
            )
        val initialGaps =
            reconcileThreadTimelineGaps(
                existingGaps = listOf(ThreadTimelineGap(10L, 30L, status = "error")),
                addedGaps = listOf(ThreadTimelineGap(10L, 30L, status = "idle"), ThreadTimelineGap(30L, 50L, status = "idle")),
                messages = msgs,
            )
        assertEquals(
            listOf(
                ThreadTimelineGap(10L, 30L, status = "error"),
                ThreadTimelineGap(30L, 50L, status = "idle"),
            ),
            initialGaps,
        )
        assertNull(threadTimelineGapAboveMessage(msgs, 0, initialGaps))
        assertNull(threadTimelineGapAboveMessage(msgs, 1, initialGaps), "local pending send never carries a gap")
        assertEquals(ThreadTimelineGap(10L, 30L, status = "error"), threadTimelineGapAboveMessage(msgs, 2, initialGaps))
        assertEquals(ThreadTimelineGap(30L, 50L, status = "idle"), threadTimelineGapAboveMessage(msgs, 3, initialGaps))

        // Full page shrinks gap (10, 80) -> (10, 50); short page bridges and removes it.
        val fullFetched = (50L..79L).map { seq -> messageItem("m-$seq", seq = seq) }
        val mergedAfterFirst = (listOf(messageItem("m-10", seq = 10L)) + fullFetched + listOf(messageItem("m-80", seq = 80L)))
        val shrunk =
            reconcileThreadTimelineGapsAfterFill(
                existingGaps = listOf(ThreadTimelineGap(10L, 80L, status = "loading")),
                filledAfterSeq = 10L,
                filledBeforeSeq = 80L,
                fetchedPageMessages = fullFetched,
                requestedPageSize = 30,
                mergedMessages = mergedAfterFirst,
            )
        assertEquals(listOf(ThreadTimelineGap(10L, 50L, status = "idle")), shrunk)

        val shortFetched = (20L..25L).map { seq -> messageItem("m-$seq", seq = seq) }
        val mergedAfterSecond = (listOf(messageItem("m-10", seq = 10L)) + shortFetched + fullFetched + listOf(messageItem("m-80", seq = 80L)))
        val bridged =
            reconcileThreadTimelineGapsAfterFill(
                existingGaps = shrunk,
                filledAfterSeq = 10L,
                filledBeforeSeq = 50L,
                fetchedPageMessages = shortFetched,
                requestedPageSize = 30,
                mergedMessages = mergedAfterSecond,
            )
        assertTrue(bridged.isEmpty(), "short page proves no more messages remain in the gap: $bridged")

        // Multi-gap spillover (HIGH-2): islands [1..10], [40..45], [100..120], [140..150].
        // Filling (45, 100) returns a full 30-message page [25..38, 40..45, 50..59] that spills past
        // 40 into (10, 40): (45, 100) is bridged, (10, 40) MUST shrink to (10, 25) instead of being
        // silently dropped, and (120, 140) strictly above 100 stays untouched.
        val spilloverFetched =
            ((25L..38L) + (40L..45L) + (50L..59L)).map { seq -> messageItem("m-$seq", seq = seq) }
        assertEquals(30, spilloverFetched.size)
        val spilloverMerged =
            ((1L..10L) + (25L..38L) + (40L..45L) + (50L..59L) + (100L..120L) + (140L..150L))
                .map { seq -> messageItem("m-$seq", seq = seq) }
        val afterSpillover =
            reconcileThreadTimelineGapsAfterFill(
                existingGaps =
                    listOf(
                        ThreadTimelineGap(10L, 40L, status = "idle"),
                        ThreadTimelineGap(45L, 100L, status = "loading"),
                        ThreadTimelineGap(120L, 140L, status = "error"),
                    ),
                filledAfterSeq = 45L,
                filledBeforeSeq = 100L,
                fetchedPageMessages = spilloverFetched,
                requestedPageSize = 30,
                mergedMessages = spilloverMerged,
            )
        assertEquals(
            listOf(
                ThreadTimelineGap(10L, 25L, status = "idle"),
                ThreadTimelineGap(120L, 140L, status = "error"),
            ),
            afterSpillover,
        )
    }

    @Test
    fun systemMessageFoldsNeverMergeAcrossATimelineGap() {
        val sysMessages =
            listOf(
                messageItem("s1", seq = 10L, messageType = "System", body = "Alice claimed #1 \"A\""),
                messageItem("s2", seq = 11L, messageType = "System", body = "Alice moved #1 \"A\" to Done"),
                messageItem("s3", seq = 40L, messageType = "System", body = "Bob claimed #2 \"B\""),
                messageItem("s4", seq = 41L, messageType = "System", body = "Bob moved #2 \"B\" to Done"),
            )
        val mergedWithoutGap = threadSystemMessageFolds(sysMessages, emptyList())
        assertEquals(
            listOf("s1", "s2", "s3", "s4"),
            (mergedWithoutGap[0] as ThreadSystemMessageFold.Summary).memberIds,
        )

        val splitAcrossGap = threadSystemMessageFolds(sysMessages, listOf(ThreadTimelineGap(11L, 40L)))
        assertEquals(listOf("s1", "s2"), (splitAcrossGap[0] as ThreadSystemMessageFold.Summary).memberIds)
        assertEquals(listOf("s3", "s4"), (splitAcrossGap[2] as ThreadSystemMessageFold.Summary).memberIds)
    }

    @Test
    fun onlinePagingServesInsideProvenLocalSegmentsAndNeverClosesCanLoadMoreAfterFromStaleHeadAtVerify() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val dbDispatcher = CountingDispatcher(backgroundScope.testDispatcher())
        val api =
            SharedThreadStoreTest.FakeThreadApi(
                bodyText = messagesJson((61..80).map { it.toLong() }),
                olderBodyText = messagesJson((21..30).map { it.toLong() }),
                newerBodyText = messagesJson((111..120).map { it.toLong() }),
            )
        val reader =
            TimelineFakeReader { anchor ->
                when (anchor) {
                    is ThreadDurableTimelineAnchor.Before -> {
                        assertFalse(anchor.crossGaps, "online LoadOlder must never cross gaps")
                        when (anchor.beforeSeq) {
                            61L ->
                                timelinePage(
                                    seqs = 41L..60L,
                                    hasOlderStored = false,
                                    reachesOldest = false,
                                    insideProvenSegment = true,
                                )
                            else ->
                                timelinePage(
                                    seqs = emptyList(),
                                    hasOlderStored = false,
                                    reachesOldest = false,
                                    insideProvenSegment = false,
                                )
                        }
                    }
                    is ThreadDurableTimelineAnchor.After -> {
                        assertFalse(anchor.crossGaps, "online LoadNewer must never cross gaps")
                        when (anchor.afterSeq) {
                            80L ->
                                // Even if SQLite persisted `reachesHead = true` (yesterday's headAtVerify),
                                // online LoadNewer MUST keep `canLoadMoreAfter = true` so server messages
                                // posted since then are fetched at the upper edge (HIGH-1).
                                timelinePage(
                                    seqs = 81L..100L,
                                    hasNewerStored = false,
                                    reachesHead = true,
                                    insideProvenSegment = true,
                                )
                            else ->
                                // Empty local read with `reachesHead = true` must NOT shortcut-close online paging.
                                timelinePage(
                                    seqs = emptyList(),
                                    hasNewerStored = false,
                                    reachesHead = true,
                                    insideProvenSegment = true,
                                )
                        }
                    }
                    else -> null
                }
            }
        val store = SharedThreadStore(dataSource(backgroundScope, api, reader, durableDbDispatcher = dbDispatcher), scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.SetPagingAvailability(canLoadMoreBefore = true, canLoadMoreAfter = true))
        assertFalse(store.state.value.payload.durableLastKnown)

        // Older inside proven segment: served from disk, zero network beforeSeq calls.
        store.accept(SharedThreadIntent.LoadOlder)
        runCurrent()
        assertTrue(api.beforeSeqs.isEmpty(), "proven segment served locally: ${api.beforeSeqs}")
        assertEquals((41L..80L).toList(), store.state.value.payload.messages.mapNotNull { it.seq })
        assertTrue(store.state.value.payload.canLoadMoreBefore)
        assertFalse(store.state.value.payload.durableLastKnown)
        assertEquals(1, reader.touchedScopes.size, "local segment read touched user access once")
        assertTrue(dbDispatcher.dispatchCount > 0, "touchUserAccess ran on durableDbDispatcher")

        // Next older hits segment edge: falls back to network and does NOT touch user access again.
        store.accept(SharedThreadIntent.LoadOlder)
        runCurrent()
        assertEquals(listOf(41L), api.beforeSeqs)
        assertEquals((21L..30L) + (41L..80L), store.state.value.payload.messages.mapNotNull { it.seq })
        assertEquals(1, reader.touchedScopes.size, "segment-edge miss must not touch user access")

        // Newer inside proven segment with stale reachesHead=true: served locally, keeps canLoadMoreAfter=true!
        store.accept(SharedThreadIntent.LoadNewer)
        runCurrent()
        assertTrue(api.afterSeqs.isEmpty(), "proven newer segment served locally: ${api.afterSeqs}")
        assertEquals((21L..30L) + (41L..100L), store.state.value.payload.messages.mapNotNull { it.seq })
        assertTrue(store.state.value.payload.canLoadMoreAfter, "online LoadNewer must not close on stale headAtVerify")
        assertEquals(2, reader.touchedScopes.size)

        // Next newer gets empty local read with reachesHead=true: MUST fall back to network for seq 111..120!
        store.accept(SharedThreadIntent.LoadNewer)
        runCurrent()
        assertEquals(listOf(100L), api.afterSeqs)
        assertEquals((21L..30L) + (41L..100L) + (111L..120L), store.state.value.payload.messages.mapNotNull { it.seq })
        assertEquals(2, reader.touchedScopes.size, "empty fallback must not touch user access")
        scopeJob.cancelAndJoin()
    }

    @Test
    fun touchUserAccessRunsOffMainAndNeverFiresOnRejectedReads() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val dbDispatcher = CountingDispatcher(backgroundScope.testDispatcher())
        val api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true)

        // Case 1: surface mismatch (reader returns scopeKind="thread" for top-level "channel" route) -> 0 touches.
        val mismatchReader =
            TimelineFakeReader {
                timelinePage(seqs = 1L..5L, scopeKind = "thread")
            }
        val mismatchStore =
            SharedThreadStore(dataSource(backgroundScope, api, mismatchReader, durableDbDispatcher = dbDispatcher), scope = scope)
        mismatchStore.accept(SharedThreadIntent.Start(basePayload()))
        runCurrent()
        assertTrue(mismatchReader.touchedScopes.isEmpty(), "surface mismatch must not touch user access")

        // Case 2: anchored open where durable frame lacks anchor -> anchorPending (not adopted on Frame 1) -> 0 touches.
        val anchorMissReader =
            TimelineFakeReader { anchor ->
                when (anchor) {
                    is ThreadDurableTimelineAnchor.AroundMessageId -> null
                    is ThreadDurableTimelineAnchor.Newest -> timelinePage(seqs = 91L..100L)
                    else -> null
                }
            }
        val anchorMissStore =
            SharedThreadStore(dataSource(backgroundScope, api, anchorMissReader, durableDbDispatcher = dbDispatcher), scope = scope)
        anchorMissStore.accept(SharedThreadIntent.Start(basePayload().copy(focusedMessageId = "v2-20")))
        runCurrent()
        assertTrue(anchorMissReader.touchedScopes.isEmpty(), "anchor-pending fallback must not eagerly touch user access")

        // Case 3: owner changes while durable read is suspended -> rejected, 0 touches.
        var activeIdentity = ownerA
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val racingReader =
            object : ThreadDurableLastKnownReader {
                val touched = mutableListOf<SyncScopeKey>()
                override suspend fun read(partition: DurablePartitionKey, channelId: SyncScopeId): ThreadDurableLastKnownFrame? = null
                override suspend fun readTimeline(
                    partition: DurablePartitionKey,
                    channelId: SyncScopeId,
                    anchor: ThreadDurableTimelineAnchor,
                    touchUserAccess: Boolean,
                ): ThreadDurableTimelinePage? {
                    readStarted.complete(Unit)
                    releaseRead.await()
                    return timelinePage(seqs = 1L..5L)
                }
                override suspend fun touchUserAccess(scope: SyncScopeKey) {
                    touched += scope
                }
            }
        val racingDs =
            dataSource(
                storeScope = backgroundScope,
                api = api,
                reader = racingReader,
                identityProvider = { activeIdentity },
                durableDbDispatcher = dbDispatcher,
            )
        val racingStore = SharedThreadStore(racingDs, scope = scope)
        val startJob = launch { racingStore.accept(SharedThreadIntent.Start(basePayload())) }
        readStarted.await()
        activeIdentity = ownerB
        assertNotNull(racingDs.readSession())
        releaseRead.complete(Unit)
        startJob.join()
        runCurrent()
        assertTrue(racingReader.touched.isEmpty(), "owner-changed rejected read must not touch user access")
        assertFalse(racingStore.state.value.payload.durableLastKnown)
        scopeJob.cancelAndJoin()
    }

    @Test
    fun offlineLoadOlderCrossesIntoLowerIslandAndFillTimelineGapShrinksBridgesAndRecordsErrors() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true)
        val reader =
            TimelineFakeReader { anchor ->
                when (anchor) {
                    is ThreadDurableTimelineAnchor.Newest ->
                        timelinePage(
                            seqs = 91L..120L,
                            hasOlderStored = true,
                            reachesOldest = false,
                            reachesHead = true,
                            insideProvenSegment = true,
                        )
                    is ThreadDurableTimelineAnchor.Before -> {
                        assertTrue(anchor.crossGaps, "offline LoadOlder crosses into lower islands")
                        assertEquals(91L, anchor.beforeSeq)
                        timelinePage(
                            seqs = 11L..40L,
                            gaps = listOf(CanonicalMessageV2TimelineGap(afterSeq = 40L, beforeSeq = 91L)),
                            hasOlderStored = false,
                            reachesOldest = true,
                            insideProvenSegment = false,
                        )
                    }
                    else -> null
                }
            }
        val store = SharedThreadStore(dataSource(backgroundScope, api, reader), scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        assertTrue(store.state.value.payload.durableLastKnown)
        assertTrue(store.state.value.payload.mainLoadFailed)
        assertTrue(store.state.value.payload.timelineGaps.isEmpty())

        // Offline LoadOlder crosses into lower stored island 11..40 and emits derived gap (40, 91).
        store.accept(SharedThreadIntent.LoadOlder)
        val crossed = store.state.value.payload
        assertEquals((11L..40L) + (91L..120L), crossed.messages.mapNotNull { it.seq })
        assertEquals(listOf(ThreadTimelineGap(40L, 91L, status = "idle")), crossed.timelineGaps)
        assertEquals(crossed.timelineGaps, store.state.value.timelineSlice.timelineGaps)
        assertFalse(crossed.canLoadMoreBefore)
        assertTrue(crossed.durableReachedOldest)

        // Filling the gap while still offline marks the gap error without losing it.
        store.accept(SharedThreadIntent.FillTimelineGap(afterSeq = 40L, beforeSeq = 91L))
        assertEquals(listOf(ThreadTimelineGap(40L, 91L, status = "error")), store.state.value.payload.timelineGaps)

        // Connectivity returns: first full page (30 messages 61..90) shrinks gap to (40, 61).
        api.failChannelMessages = false
        api.olderBodyText = messagesJson((61..90).map { it.toLong() })
        store.accept(SharedThreadIntent.FillTimelineGap(afterSeq = 40L, beforeSeq = 91L))
        assertEquals(listOf(91L), api.beforeSeqs)
        assertEquals(listOf(ThreadTimelineGap(40L, 61L, status = "idle")), store.state.value.payload.timelineGaps)
        assertEquals((11L..40L) + (61L..120L), store.state.value.payload.messages.mapNotNull { it.seq })

        // Second page (short page 45..60) proves the remainder and bridges the gap completely.
        api.olderBodyText = messagesJson((45..60).map { it.toLong() })
        store.accept(SharedThreadIntent.FillTimelineGap(afterSeq = 40L, beforeSeq = 61L))
        assertEquals(listOf(91L, 61L), api.beforeSeqs)
        assertTrue(store.state.value.payload.timelineGaps.isEmpty())
        assertEquals((11L..40L) + (45L..120L), store.state.value.payload.messages.mapNotNull { it.seq })
        scopeJob.cancelAndJoin()
    }

    @Test
    fun fillTimelineGapPersistsAdjacentPageHandlesEmptyDuplicateAndStaleTokenDrop() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val offlineError = DataResult.Error(ScreenError(code = "offline", retryable = true))
        val extender = RecordingPageExtender()
        val repo =
            SharedThreadStoreTestFakes.FakeConversationRepository(
                threadResult = offlineError,
                olderResult = DataResult.Content(repoSnapshot((61L..90L).toList())),
            )
        val reader =
            TimelineFakeReader { anchor ->
                when (anchor) {
                    is ThreadDurableTimelineAnchor.Newest ->
                        timelinePage(seqs = 91L..120L, hasOlderStored = true, reachesHead = true)
                    is ThreadDurableTimelineAnchor.Before ->
                        timelinePage(
                            seqs = 11L..40L,
                            gaps = listOf(CanonicalMessageV2TimelineGap(40L, 91L)),
                            reachesOldest = true,
                            insideProvenSegment = false,
                        )
                    else -> null
                }
            }
        val store =
            SharedThreadStore(
                dataSource(
                    storeScope = backgroundScope,
                    api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true),
                    reader = reader,
                    conversationRepository = repo,
                    durablePageExtender = extender,
                ),
                scope = scope,
            )
        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.LoadOlder)
        assertEquals(listOf(ThreadTimelineGap(40L, 91L, status = "idle")), store.state.value.payload.timelineGaps)

        // 1. Positive fill invokes durablePageExtender.extendWithAdjacentPage with direction=Older, anchorSeq=91L.
        store.accept(SharedThreadIntent.FillTimelineGap(afterSeq = 40L, beforeSeq = 91L))
        runCurrent()
        assertEquals(listOf(ThreadDurablePageDirection.Older to 91L), extender.calls)
        assertEquals(listOf(ThreadTimelineGap(40L, 61L, status = "idle")), store.state.value.payload.timelineGaps)

        // 2. DataResult.Empty removes the gap cleanly.
        repo.olderResult = DataResult.Empty(EmptyReason.NoMessages)
        store.accept(SharedThreadIntent.FillTimelineGap(afterSeq = 40L, beforeSeq = 61L))
        runCurrent()
        assertTrue(store.state.value.payload.timelineGaps.isEmpty())

        // 3. Duplicate FillTimelineGap while loading is ignored + stale-token drop prevents splicing old results.
        val fillStarted = CompletableDeferred<Unit>()
        val releaseFill = CompletableDeferred<Unit>()
        val suspendingRepo =
            object : build.raft.app.data.ConversationRepository by repo {
                var olderCalls = 0
                override suspend fun loadOlderMessages(
                    threadId: ThreadId,
                    beforeSeq: Long,
                    limit: Int,
                ): DataResult<ThreadSnapshot> {
                    olderCalls += 1
                    fillStarted.complete(Unit)
                    releaseFill.await()
                    return DataResult.Content(repoSnapshot((70L..80L).toList()))
                }
            }
        val staleStore =
            SharedThreadStore(
                dataSource(
                    storeScope = backgroundScope,
                    api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true),
                    reader = reader,
                    conversationRepository = suspendingRepo,
                ),
                scope = scope,
            )
        staleStore.accept(SharedThreadIntent.Start(basePayload()))
        staleStore.accept(SharedThreadIntent.LoadOlder)
        assertEquals(listOf(ThreadTimelineGap(40L, 91L, status = "idle")), staleStore.state.value.payload.timelineGaps)

        val fillJob = launch { staleStore.accept(SharedThreadIntent.FillTimelineGap(afterSeq = 40L, beforeSeq = 91L)) }
        fillStarted.await()
        assertEquals("loading", staleStore.state.value.payload.timelineGaps.single().status)
        // Duplicate while loading must be ignored.
        staleStore.accept(SharedThreadIntent.FillTimelineGap(afterSeq = 40L, beforeSeq = 91L))
        assertEquals(1, suspendingRepo.olderCalls, "duplicate FillTimelineGap while loading must be ignored")

        // Advance stale token via Refresh (returning fresh 100..120) while gap fill is still in flight.
        repo.threadResult = DataResult.Content(repoSnapshot((100L..120L).toList()))
        staleStore.accept(SharedThreadIntent.Refresh)
        assertEquals((100L..120L).toList(), staleStore.state.value.payload.messages.mapNotNull { it.seq })
        assertTrue(staleStore.state.value.payload.timelineGaps.isEmpty())

        // Release stale gap fill: staleTokenStillCurrent must drop it so 70..80 are never spliced in.
        releaseFill.complete(Unit)
        fillJob.join()
        assertEquals((100L..120L).toList(), staleStore.state.value.payload.messages.mapNotNull { it.seq })
        assertTrue(staleStore.state.value.payload.timelineGaps.isEmpty())
        scopeJob.cancelAndJoin()
    }

    @Test
    fun anchoredColdOpenOfflineJumpAndCacheNonPollutionHoldAcrossCancelAndRealtime() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true)
        val reader =
            TimelineFakeReader { anchor ->
                when (anchor) {
                    is ThreadDurableTimelineAnchor.Newest ->
                        timelinePage(
                            seqs = 91L..120L,
                            hasOlderStored = true,
                            reachesOldest = false,
                            reachesHead = true,
                        )
                    is ThreadDurableTimelineAnchor.AroundMessageId ->
                        if (anchor.messageId == "v2-20") {
                            timelinePage(
                                seqs = 15L..25L,
                                hasOlderStored = true,
                                hasNewerStored = true,
                                reachesOldest = false,
                                reachesHead = false,
                            )
                        } else {
                            null
                        }
                    is ThreadDurableTimelineAnchor.After ->
                        if (anchor.crossGaps && anchor.afterSeq == 25L) {
                            timelinePage(
                                seqs = 50L..60L,
                                gaps = listOf(CanonicalMessageV2TimelineGap(afterSeq = 25L, beforeSeq = 50L)),
                                hasNewerStored = false,
                                reachesHead = false,
                            )
                        } else {
                            null
                        }
                    else -> null
                }
            }
        val payloadStore = SharedThreadPayloadStore(scope = backgroundScope)
        val ds = dataSource(backgroundScope, api, reader, payloadStore = payloadStore)
        // Warm memory cache with writable newest messages lacking v2-20 and mark resolved access:
        // proves anchored open reads SQLite island, overlays it onto cached metadata, and keeps
        // verified resolved write access without flashing the composer (#265 Phase 2).
        assertNotNull(ds.readSession())
        val resolvedProvenance = assertNotNull(ds.captureReplyCacheProducerContext()?.provenance)
        ds.rememberResolvedThreadAccess("channel-1", resolvedProvenance, resolved = true)
        val cachedPayloadSeed =
            buildSharedThreadPayloadFromMessagesPage(
                basePayload = basePayload().copy(joined = true, canSend = true, channelArchived = false, subtitle = "Cached subtitle"),
                page =
                    ThreadMessagesPage(
                        messages =
                            listOf(
                                WireMessage(
                                    id = "v2-120",
                                    channelId = "channel-1",
                                    senderType = "user",
                                    senderId = "u-a",
                                    senderName = "Owner A",
                                    messageType = "chat",
                                    content = "body 120",
                                    seq = 120L,
                                )
                            ),
                        canLoadMoreBefore = true,
                        canLoadMoreAfter = false,
                    ),
                session = ownerA.session,
            )
        assertTrue(ds.cachePayload(cachedPayloadSeed, serverId = "server-1"))

        val anchoredStore = SharedThreadStore(ds, scope = scope)
        anchoredStore.accept(SharedThreadIntent.Start(basePayload().copy(focusedMessageId = "v2-20")))
        val anchored = anchoredStore.state.value.payload
        assertTrue(anchored.durableLastKnown)
        assertEquals((15L..25L).toList(), anchored.messages.mapNotNull { it.seq })
        assertTrue(anchored.canLoadMoreAfter)
        assertEquals("Cached subtitle", anchored.subtitle)
        assertEquals(ThreadAccessResolution.Resolved, anchoredStore.state.value.accessResolution)
        assertTrue(anchoredStore.state.value.writeActionsEnabled, "durable anchored overlay preserves verified cached write access")

        // Offline LoadNewer crosses into upper stored island without polluting memory cache.
        anchoredStore.accept(SharedThreadIntent.LoadNewer)
        val afterOfflineNewer = anchoredStore.state.value.payload
        assertTrue(afterOfflineNewer.durableLastKnown)
        assertEquals((15L..25L) + (50L..60L), afterOfflineNewer.messages.mapNotNull { it.seq })
        assertEquals(listOf(ThreadTimelineGap(25L, 50L, status = "idle")), afterOfflineNewer.timelineGaps)
        assertEquals(listOf("v2-120"), ds.cachedPayload("channel-1", serverId = "server-1")?.messages?.map { it.id })

        // HIGH-5: LoadNewer cancellation on durable stand-in (finally resets newerMessagesStatus="idle")
        // AND realtime events arriving on durable stand-in MUST never overwrite the memory cache.
        api.failChannelMessages = false
        api.afterMessagesStarted = CompletableDeferred()
        api.releaseAfterMessages = CompletableDeferred()
        val cancelJob = launch { anchoredStore.accept(SharedThreadIntent.LoadNewer) }
        api.afterMessagesStarted!!.await()
        cancelJob.cancelAndJoin()
        assertEquals("idle", anchoredStore.state.value.payload.newerMessagesStatus)
        assertEquals(
            listOf("v2-120"),
            ds.cachedPayload("channel-1", serverId = "server-1")?.messages?.map { it.id },
            "cancelled LoadNewer on durable stand-in must not pollute memory cache",
        )

        anchoredStore.accept(
            SharedThreadIntent.ApplyRealtimeEvent(
                MessageNew(
                    message =
                        Message(
                            id = MessageId("rt-61"),
                            threadId = ThreadId("channel-1"),
                            channelId = ChannelId("channel-1"),
                            author =
                                MemberProfile(
                                    id = MemberId("u-a"),
                                    displayName = "Owner A",
                                    handle = "owner-a",
                                    kind = MemberKind.Human,
                                    presence = Presence.Active,
                                ),
                            seq = 61L,
                            timestampLabel = "2026-05-19T16:10:00Z",
                            body = "live 61",
                        ),
                )
            )
        )
        assertEquals(
            listOf("v2-120"),
            ds.cachedPayload("channel-1", serverId = "server-1")?.messages?.map { it.id },
            "realtime fold on durable stand-in must not pollute memory cache",
        )
        scopeJob.cancelAndJoin()
    }

    @Test
    fun adoptDurableIslandForJumpIsolatesOwnerSwitchPoisonedConversationAndPreservesReplyOrigin() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true)
        val reader =
            TimelineFakeReader { anchor ->
                when (anchor) {
                    is ThreadDurableTimelineAnchor.Newest -> timelinePage(seqs = 91L..120L, hasOlderStored = true, reachesHead = true)
                    is ThreadDurableTimelineAnchor.AroundMessageId ->
                        if (anchor.messageId == "v2-20") timelinePage(seqs = 15L..25L, hasOlderStored = true, hasNewerStored = true) else null
                    else -> null
                }
            }

        // 1. Offline unreadThreadPill = true jump adopts stored island.
        val pillStore = SharedThreadStore(dataSource(backgroundScope, api, reader), scope = scope)
        pillStore.accept(SharedThreadIntent.Start(basePayload()))
        pillStore.accept(SharedThreadIntent.JumpToMessage(messageId = "v2-20", unreadThreadPill = true))
        assertTrue(pillStore.state.value.payload.messages.any { it.id == "v2-20" })
        assertTrue(pillStore.state.value.payload.durableLastKnown)

        // 2. Poisoned / stale conversation MUST NOT adopt a durable island on jump (HIGH-4).
        val poisonedPayloadStore = SharedThreadPayloadStore(scope = backgroundScope)
        val poisonedDs = dataSource(backgroundScope, api, reader, payloadStore = poisonedPayloadStore)
        val poisonedStore = SharedThreadStore(poisonedDs, scope = scope)
        poisonedStore.accept(SharedThreadIntent.Start(basePayload()))
        assertEquals((91L..120L).toList(), poisonedStore.state.value.payload.messages.mapNotNull { it.seq })
        val ownerScopeId = assertNotNull(poisonedDs.captureReplyCacheProducerContext()?.provenance?.threadPayloadOwnerScopeId)
        poisonedPayloadStore.markConversationStale("server-1", "channel-1", ownerScopeId, "realtime_poisoned:test")
        poisonedStore.accept(SharedThreadIntent.JumpToMessage(messageId = "v2-20", unreadThreadPill = false))
        assertTrue(
            poisonedStore.state.value.payload.messages.none { it.id == "v2-20" },
            "poisoned conversation must not adopt durable island on jump",
        )

        // 3. Owner switch while jump durable read is suspended MUST NOT commit Account A's island (HIGH-4).
        var activeIdentity = ownerA
        val jumpReadStarted = CompletableDeferred<Unit>()
        val releaseJumpRead = CompletableDeferred<Unit>()
        val switchingReader =
            object : ThreadDurableLastKnownReader {
                override suspend fun read(partition: DurablePartitionKey, channelId: SyncScopeId): ThreadDurableLastKnownFrame? = null
                override suspend fun readTimeline(
                    partition: DurablePartitionKey,
                    channelId: SyncScopeId,
                    anchor: ThreadDurableTimelineAnchor,
                    touchUserAccess: Boolean,
                ): ThreadDurableTimelinePage? =
                    when (anchor) {
                        is ThreadDurableTimelineAnchor.Newest -> timelinePage(seqs = 91L..120L)
                        is ThreadDurableTimelineAnchor.AroundMessageId -> {
                            jumpReadStarted.complete(Unit)
                            releaseJumpRead.await()
                            timelinePage(seqs = 15L..25L)
                        }
                        else -> null
                    }
            }
        val switchingDs = dataSource(backgroundScope, api, switchingReader, identityProvider = { activeIdentity })
        val switchingStore = SharedThreadStore(switchingDs, scope = scope)
        switchingStore.accept(SharedThreadIntent.Start(basePayload()))
        val jumpJob = launch { switchingStore.accept(SharedThreadIntent.JumpToMessage(messageId = "v2-20", unreadThreadPill = false)) }
        jumpReadStarted.await()
        activeIdentity = ownerB
        assertNotNull(switchingDs.readSession())
        releaseJumpRead.complete(Unit)
        jumpJob.join()
        assertTrue(
            switchingStore.state.value.payload.messages.none { it.id == "v2-20" },
            "account switch during jump must not commit Account A's island",
        )

        // 4. ReplyThread jump preserves originMessage / originStatus (HIGH-4).
        val replyParent = ThreadDurableReplyParent(parentChannelId = "channel-1", rootMessageId = "root-1")
        val replyReader =
            object : ThreadDurableLastKnownReader {
                override suspend fun read(partition: DurablePartitionKey, channelId: SyncScopeId): ThreadDurableLastKnownFrame? = null
                override suspend fun readReplyThreadTimeline(
                    partition: DurablePartitionKey,
                    threadChannelId: SyncScopeId,
                    replyParent: ThreadDurableReplyParent,
                    anchor: ThreadDurableTimelineAnchor,
                    touchUserAccess: Boolean,
                ): ThreadDurableTimelinePage? =
                    when (anchor) {
                        is ThreadDurableTimelineAnchor.Newest ->
                            timelinePage(seqs = 91L..100L, scopeKind = "thread", scopeId = "thread-1", replyParent = replyParent)
                        is ThreadDurableTimelineAnchor.AroundMessageId ->
                            if (anchor.messageId == "v2-20") {
                                timelinePage(seqs = 15L..25L, scopeKind = "thread", scopeId = "thread-1", replyParent = replyParent)
                            } else {
                                null
                            }
                        else -> null
                    }
            }
        val originDomainMsg =
            SharedThreadStoreTest.message(
                threadId = "channel-1",
                channelId = "channel-1",
                id = "root-1",
                seq = 1L,
                body = "Original root",
            )
        val replyRepo =
            SharedThreadStoreTestFakes.FakeConversationRepository(
                threadResult =
                    DataResult.Content(
                        SharedThreadStoreTest.threadSnapshot(
                            threadId = "thread-1",
                            messages =
                                (91L..100L).map { seq ->
                                    SharedThreadStoreTest.message(
                                        threadId = "thread-1",
                                        channelId = "thread-1",
                                        id = "v2-$seq",
                                        seq = seq,
                                        body = "body $seq",
                                    )
                                },
                            parentMessage = originDomainMsg,
                            conversation =
                                ConversationSummary(
                                    threadId = ThreadId("thread-1"),
                                    channelId = ChannelId("thread-1"),
                                    kind = ConversationKind.Thread,
                                    name = "Thread",
                                    joined = true,
                                    permissions = ConversationPermissions(canRead = true, canSend = true),
                                    thread =
                                        ThreadConversation(
                                            parentChannelId = ChannelId("channel-1"),
                                            parentMessageId = MessageId("root-1"),
                                            streamChannelId = ChannelId("thread-1"),
                                        ),
                                ),
                        )
                    ),
                messageContextResult = DataResult.Error(ScreenError(code = "offline", retryable = true)),
            )
        // Route payload itself omits originMessage (loading); Start loads it from replyRepo so
        // focusRoute/withReplies has originMessage=null and ONLY currentPayload preservation keeps it.
        val replyRoute =
            RaftThreadScreenPayload(
                instanceId = "shared-shell-reply-thread-1",
                title = "Thread",
                sourceThreadId = "thread-1",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentChannelId = "channel-1",
                parentMessageId = "root-1",
                originParentMessageId = "root-1",
                originMessage = null,
                originStatus = "loading",
                status = "loading",
                canSend = true,
            )
        val replyStore = SharedThreadStore(dataSource(backgroundScope, api, replyReader, conversationRepository = replyRepo), scope = scope)
        replyStore.accept(SharedThreadIntent.Start(replyRoute))
        assertEquals("root-1", replyStore.state.value.payload.originMessage?.id)
        assertEquals("Original root", replyStore.state.value.payload.originMessage?.body)
        assertEquals("loaded", replyStore.state.value.payload.originStatus)
        replyRepo.threadResult = DataResult.Error(ScreenError(code = "offline", retryable = true))
        replyStore.accept(SharedThreadIntent.JumpToMessage(messageId = "v2-20", unreadThreadPill = false))
        assertTrue(replyStore.state.value.payload.messages.any { it.id == "v2-20" })
        assertTrue(replyStore.state.value.payload.durableLastKnown)
        assertEquals("root-1", replyStore.state.value.payload.originMessage?.id, "ReplyThread jump must preserve originMessage")
        assertEquals("Original root", replyStore.state.value.payload.originMessage?.body)
        assertEquals("loaded", replyStore.state.value.payload.originStatus)

        // 5. Online jump paints stored island immediately, then context refresh transitions to durableLastKnown=false.
        val contextStarted = CompletableDeferred<Unit>()
        val releaseContext = CompletableDeferred<Unit>()
        val onlineRepo =
            SharedThreadStoreTestFakes.FakeConversationRepository(
                threadResult = DataResult.Content(repoSnapshot((91L..120L).toList())),
                messageContextResult = DataResult.Content(repoSnapshot((15L..25L).toList())),
            ).apply {
                messageContextStartedById[MessageId("v2-20")] = contextStarted
                releaseMessageContextById[MessageId("v2-20")] = releaseContext
            }
        val onlineJumpStore =
            SharedThreadStore(
                dataSource(backgroundScope, SharedThreadStoreTest.FakeThreadApi(), reader, conversationRepository = onlineRepo),
                scope = scope,
            )
        onlineJumpStore.accept(SharedThreadIntent.Start(basePayload()))
        assertFalse(onlineJumpStore.state.value.payload.durableLastKnown)
        val onlineJumpJob = launch { onlineJumpStore.accept(SharedThreadIntent.JumpToMessage(messageId = "v2-20", unreadThreadPill = false)) }
        contextStarted.await()
        // Before context network returns, the stored island is already painted on screen!
        assertTrue(onlineJumpStore.state.value.payload.durableLastKnown)
        assertEquals((15L..25L).toList(), onlineJumpStore.state.value.payload.messages.mapNotNull { it.seq })
        releaseContext.complete(Unit)
        onlineJumpJob.join()
        assertFalse(onlineJumpStore.state.value.payload.durableLastKnown, "online context refresh clears durableLastKnown")
        assertTrue(onlineJumpStore.state.value.payload.timelineGaps.isEmpty())
        scopeJob.cancelAndJoin()
    }

    @Test
    fun onlineProvenReachedOldestClosesPagingWithZeroNetworkAndReplyThreadReadsBoundedTimeline() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val api =
            SharedThreadStoreTest.FakeThreadApi(
                bodyText = messagesJson((1..10).map { it.toLong() }),
                olderBodyText = messagesJson(emptyList()),
            )
        val reader =
            TimelineFakeReader { anchor ->
                when (anchor) {
                    is ThreadDurableTimelineAnchor.Before ->
                        timelinePage(
                            seqs = emptyList(),
                            hasOlderStored = false,
                            reachesOldest = true,
                            insideProvenSegment = true,
                        )
                    else -> null
                }
            }
        val store = SharedThreadStore(dataSource(backgroundScope, api, reader), scope = scope)
        store.accept(SharedThreadIntent.Start(basePayload()))
        store.accept(SharedThreadIntent.SetPagingAvailability(canLoadMoreBefore = true, canLoadMoreAfter = false))

        store.accept(SharedThreadIntent.LoadOlder)
        runCurrent()
        assertTrue(api.beforeSeqs.isEmpty(), "proven_reached_oldest closes paging with 0 network calls")
        assertFalse(store.state.value.payload.canLoadMoreBefore)
        assertTrue(store.state.value.payload.durableReachedOldest)
        assertEquals(1, reader.touchedScopes.size)

        // ReplyThread bounded read: called with replyParent when present, refused without replyParent.
        var replyTimelineCalls = 0
        val replyReader =
            object : ThreadDurableLastKnownReader {
                override suspend fun read(partition: DurablePartitionKey, channelId: SyncScopeId): ThreadDurableLastKnownFrame? = null
                override suspend fun readReplyThreadTimeline(
                    partition: DurablePartitionKey,
                    threadChannelId: SyncScopeId,
                    replyParent: ThreadDurableReplyParent,
                    anchor: ThreadDurableTimelineAnchor,
                    touchUserAccess: Boolean,
                ): ThreadDurableTimelinePage? {
                    replyTimelineCalls += 1
                    return timelinePage(
                        seqs = 5L..10L,
                        scopeKind = "thread",
                        scopeId = threadChannelId,
                        replyParent = replyParent,
                    )
                }
            }
        val ds = dataSource(backgroundScope, SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true), replyReader)
        val context = assertNotNull(ds.captureReplyCacheProducerContext())
        val validReply =
            RaftThreadScreenPayload(
                instanceId = "reply-valid",
                sourceThreadId = "thread-1",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentChannelId = "channel-1",
                parentMessageId = "root-1",
            )
        val resultWithParent = ds.durableLastKnownResultIfCurrent(validReply, validReply.resolveThreadRouteIdentity(), context)
        assertNotNull(resultWithParent)
        assertEquals((5L..10L).toList(), resultWithParent.payload.messages.mapNotNull { it.seq })
        assertEquals(1, replyTimelineCalls)

        val missingParentReply = validReply.copy(parentChannelId = null, parentMessageId = null)
        assertNull(ds.durableLastKnownResultIfCurrent(missingParentReply, missingParentReply.resolveThreadRouteIdentity(), context))
        assertEquals(1, replyTimelineCalls, "reply route without replyParent must never call readReplyThreadTimeline")
        scopeJob.cancelAndJoin()
    }

    private class CountingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        var dispatchCount: Int = 0
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatchCount += 1
            delegate.dispatch(context, block)
        }
    }

    private class RecordingPageExtender : ThreadDurablePageExtender {
        val calls = mutableListOf<Pair<ThreadDurablePageDirection, Long>>()
        override suspend fun extendWithAdjacentPage(
            partition: DurablePartitionKey,
            channelId: SyncScopeId,
            direction: ThreadDurablePageDirection,
            anchorSeq: Long,
            rawMessages: JsonArray,
            requestedLimit: Int,
            replyParent: ThreadDurableReplyParent?,
            requestHeadEpoch: Long?,
            messageWindow: ChannelMessageWindow?,
            historyLimited: Boolean,
            revAtRequest: Long?,
            replyCountByParentId: Map<String, Int>?,
            stillCurrent: () -> Boolean,
        ) {
            calls += direction to anchorSeq
        }
    }

    private class TimelineFakeReader(
        private val onReadTimeline: (ThreadDurableTimelineAnchor) -> ThreadDurableTimelinePage?,
    ) : ThreadDurableLastKnownReader {
        val touchedScopes = mutableListOf<SyncScopeKey>()

        override suspend fun read(partition: DurablePartitionKey, channelId: SyncScopeId): ThreadDurableLastKnownFrame? = null

        override suspend fun readTimeline(
            partition: DurablePartitionKey,
            channelId: SyncScopeId,
            anchor: ThreadDurableTimelineAnchor,
            touchUserAccess: Boolean,
        ): ThreadDurableTimelinePage? =
            onReadTimeline(anchor)?.takeIf { it.scope.partition == partition && it.scope.scopeId == channelId }

        override suspend fun touchUserAccess(scope: SyncScopeKey) {
            touchedScopes += scope
        }
    }

    private fun timelinePage(
        seqs: Iterable<Long>,
        gaps: List<CanonicalMessageV2TimelineGap> = emptyList(),
        hasOlderStored: Boolean = false,
        hasNewerStored: Boolean = false,
        reachesOldest: Boolean = false,
        reachesHead: Boolean = false,
        insideProvenSegment: Boolean = true,
        scopeKind: String = "channel",
        scopeId: String = "channel-1",
        replyParent: ThreadDurableReplyParent? = null,
    ): ThreadDurableTimelinePage {
        val sorted = seqs.toList().sorted()
        return ThreadDurableTimelinePage(
            scope = SyncScopeKey(partition(ownerA), scopeId, scopeKind),
            envelopes = sorted.map { seq -> envelope(seq, scopeId = scopeId, replyParent = replyParent) },
            gaps = gaps,
            cursor =
                PersistedCursor(
                    latestSeq = sorted.maxOrNull(),
                    oldestLoadedSeq = sorted.minOrNull(),
                    newestLoadedSeq = sorted.maxOrNull(),
                    hasLoadedOldest = reachesOldest,
                    hasLoadedNewest = reachesHead,
                    epoch = "epoch-v2",
                    parentScopeKind = if (replyParent != null) "channel" else null,
                    parentScopeId = replyParent?.parentChannelId,
                    rootMessageId = replyParent?.rootMessageId,
                ),
            segments = emptyList(),
            hasOlderStored = hasOlderStored,
            hasNewerStored = hasNewerStored,
            reachesOldest = reachesOldest,
            reachesHead = reachesHead,
            insideProvenSegment = insideProvenSegment,
        )
    }

    private fun envelope(
        seq: Long,
        scopeId: String = "channel-1",
        replyParent: ThreadDurableReplyParent? = null,
    ): CanonicalMessageEnvelopeV2 =
        CanonicalMessageEnvelopeV2(
            fact =
                CanonicalMessageNodeV2(
                    id = "v2-$seq",
                    seq = seq,
                    senderType = "user",
                    senderId = "u-a",
                    content = "body $seq",
                    messageType = "chat",
                    createdAt = "2026-05-19T16:${((seq / 60) % 60).toString().padStart(2, '0')}:${(seq % 60).toString().padStart(2, '0')}Z",
                    senderName = "Owner A",
                ),
            context =
                if (replyParent != null) {
                    CanonicalConversationContextV2.Thread(
                        replyScope = ReplyScopeRefV2("server-1", scopeId),
                        parentTopLevelScope = TopLevelScopeRefV2.Channel("server-1", replyParent.parentChannelId),
                        rootMessageRef = MessageRefV2("server-1", replyParent.rootMessageId),
                    )
                } else {
                    CanonicalConversationContextV2.TopLevel(TopLevelScopeRefV2.Channel("server-1", scopeId))
                },
            replyMembership =
                replyParent?.let {
                    ReplyMembershipV2(
                        root = MessageRefV2("server-1", it.rootMessageId),
                        parentScope = ReplyScopeRefV2("server-1", scopeId),
                    )
                },
        )

    private fun repoSnapshot(seqs: List<Long>): ThreadSnapshot {
        val channelId = ChannelId("channel-1")
        val raw =
            JsonArray(
                seqs.map { seq ->
                    buildJsonObject {
                        put("id", "v2-$seq")
                        put("seq", seq)
                        put("channelId", "channel-1")
                        put("senderType", "user")
                        put("senderId", "u-a")
                        put("senderName", "Owner A")
                        put("messageType", "chat")
                        put("content", "body $seq")
                        put("createdAt", "2026-05-19T16:00:${(seq % 60).toString().padStart(2, '0')}Z")
                    }
                }
            )
        return ThreadSnapshot(
            conversation =
                ConversationSummary(
                    threadId = ThreadId("channel-1"),
                    channelId = channelId,
                    kind = ConversationKind.Channel,
                    name = "general",
                    joined = true,
                    permissions = ConversationPermissions(canRead = true, canSend = true),
                ),
            messages =
                seqs.map { seq ->
                    Message(
                        id = MessageId("v2-$seq"),
                        threadId = ThreadId("channel-1"),
                        channelId = channelId,
                        author =
                            MemberProfile(
                                id = MemberId("u-a"),
                                displayName = "Owner A",
                                handle = "owner-a",
                                kind = MemberKind.Human,
                                presence = Presence.Active,
                            ),
                        seq = seq,
                        timestampLabel = "2026-05-19T16:00:00Z",
                        body = "body $seq",
                    )
                },
            canLoadMoreBefore = true,
            canLoadMoreAfter = false,
            rawMessagePage = RawMessagePageWitness(RawMessagePage(messages = raw, requestedLimit = 30)),
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
                  "createdAt": "2026-05-19T16:${((seq / 60) % 60).toString().padStart(2, '0')}:${(seq % 60).toString().padStart(2, '0')}Z",
                  "senderId": "u-a",
                  "senderName": "Owner A",
                  "senderType": "user",
                  "messageType": "chat"
                }
                """.trimIndent()
            }
        return """{"historyLimited": $historyLimited, "messages": [$items]}"""
    }

    private fun messageItem(
        id: String,
        seq: Long?,
        messageType: String = "Chat",
        body: String = "body $id",
    ): RaftThreadMessageItem =
        RaftThreadMessageItem(
            id = id,
            authorId = "u-a",
            authorName = "Owner A",
            seq = seq,
            messageType = messageType,
            body = body,
        )

    private fun dataSource(
        storeScope: CoroutineScope,
        api: SharedThreadStoreTest.FakeThreadApi,
        reader: ThreadDurableLastKnownReader,
        conversationRepository: build.raft.app.data.ConversationRepository? = null,
        durablePageExtender: ThreadDurablePageExtender? = null,
        payloadStore: SharedThreadPayloadStore = SharedThreadPayloadStore(scope = storeScope),
        identityProvider: () -> ThreadReplyCacheIdentitySnapshot = { ownerA },
        durableDbDispatcher: CoroutineDispatcher = storeScope.testDispatcher(),
    ): SharedThreadDataSource =
        SharedThreadDataSource(
            threadParseDispatcher = storeScope.testDispatcher(),
            durableDbDispatcher = durableDbDispatcher,
            messageStore = SharedThreadMessageStore(scope = storeScope),
            payloadStore = payloadStore,
            conversationRepository = conversationRepository,
            durablePageExtender = durablePageExtender,
            networkApi = api,
            sessionStore = InMemoryRaftAuthSessionStore(ownerA.session),
            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            replyCacheIdentityAuthority = { identityProvider() },
            durableLastKnownReader = reader,
        )

    private fun partition(identity: ThreadReplyCacheIdentitySnapshot): DurablePartitionKey =
        DurablePartitionKey(
            serverId = identity.serverId,
            principalId = identity.principalId,
            consumerId = SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID,
            environmentId = identity.environmentId,
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

    private val ownerB =
        ThreadReplyCacheIdentitySnapshot(
            session =
                StoredAuthSession(
                    user = StoredAuthUser(id = "u-b", email = "u-b@example.test", displayName = "Owner B"),
                    accessToken = "access-u-b",
                    refreshToken = "refresh-u-b",
                    selectedServerId = "server-1",
                ),
            environmentId = TestThreadEnvironmentId,
            principalId = "u-b",
            serverId = "server-1",
            authGeneration = 2L,
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
