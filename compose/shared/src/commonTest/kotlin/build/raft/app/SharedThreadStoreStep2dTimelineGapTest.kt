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
import build.raft.app.resources.thread_offline_older_unavailable
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
 * Phase 2d PR 2 (#proj-mobile:#240) / #proj-mobile:#388: bounded contiguous SQLite timeline reads,
 * online proven-segment local paging, offline contiguous segment-edge boundaries (zero middle gaps),
 * and anchored cold opens / offline jumps.
 */
class SharedThreadStoreStep2dTimelineGapTest {
    @Test
    fun systemMessageFoldsGroupContiguousSystemMessages() {
        val sysMessages =
            listOf(
                messageItem("s1", seq = 10L, messageType = "System", body = "Alice claimed #1 \"A\""),
                messageItem("s2", seq = 11L, messageType = "System", body = "Alice moved #1 \"A\" to Done"),
                messageItem("s3", seq = 40L, messageType = "System", body = "Bob claimed #2 \"B\""),
                messageItem("s4", seq = 41L, messageType = "System", body = "Bob moved #2 \"B\" to Done"),
            )
        val folded = threadSystemMessageFolds(sysMessages)
        assertEquals(
            listOf("s1", "s2", "s3", "s4"),
            (folded[0] as ThreadSystemMessageFold.Summary).memberIds,
        )
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
    fun offlineLoadOlderPagesContiguousSegmentOnlyStopsAtOuterBoundaryAndPersistsOnOnlineReconnect() = runTest {
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val offlineError = DataResult.Error(ScreenError(code = "offline", retryable = true))
        val extender = RecordingPageExtender()
        val repo =
            SharedThreadStoreTestFakes.FakeConversationRepository(
                threadResult = offlineError,
                olderResult = DataResult.Content(repoSnapshot((41L..60L).toList())),
            )
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
                    is ThreadDurableTimelineAnchor.Before ->
                        when (anchor.beforeSeq) {
                            91L ->
                                timelinePage(
                                    seqs = 61L..90L,
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
        assertTrue(store.state.value.payload.durableLastKnown)
        assertTrue(store.state.value.payload.mainLoadFailed)
        assertTrue(store.state.value.payload.canLoadMoreBefore)

        // Offline LoadOlder serves contiguous [61..90] from disk, then closes paging cleanly at the
        // segment edge (hasOlderStored=false, reachesOldest=false) with the outer offline boundary row.
        store.accept(SharedThreadIntent.LoadOlder)
        val atEdge = store.state.value.payload
        assertEquals((61L..120L).toList(), atEdge.messages.mapNotNull { it.seq })
        assertFalse(atEdge.canLoadMoreBefore)
        assertFalse(atEdge.durableReachedOldest)
        assertEquals("idle", atEdge.olderMessagesStatus)
        assertEquals(
            Res.string.thread_offline_older_unavailable.desc(),
            threadOlderMessagesStatusSpec(atEdge)?.label,
        )

        // Connectivity returns -> Refresh clears durableLastKnown & restores online paging;
        // online LoadOlder at segment edge fetches [41..60] from server and extends durable storage.
        repo.threadResult = DataResult.Content(repoSnapshot((91L..120L).toList()))
        store.accept(SharedThreadIntent.Refresh)
        val reconnected = store.state.value.payload
        assertFalse(reconnected.durableLastKnown)
        assertFalse(reconnected.mainLoadFailed)
        assertTrue(reconnected.canLoadMoreBefore)
        assertEquals((61L..120L).toList(), reconnected.messages.mapNotNull { it.seq })

        store.accept(SharedThreadIntent.LoadOlder)
        runCurrent()
        assertEquals(listOf(ThreadDurablePageDirection.Older to 61L), extender.calls)
        assertEquals((41L..120L).toList(), store.state.value.payload.messages.mapNotNull { it.seq })
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
                                hasOlderStored = false,
                                hasNewerStored = true,
                                reachesOldest = false,
                                reachesHead = false,
                            )
                        } else {
                            null
                        }
                    is ThreadDurableTimelineAnchor.After ->
                        if (anchor.afterSeq == 25L) {
                            timelinePage(
                                seqs = 26L..35L,
                                hasNewerStored = false,
                                reachesHead = false,
                                insideProvenSegment = true,
                            )
                        } else {
                            timelinePage(
                                seqs = emptyList(),
                                hasNewerStored = false,
                                reachesHead = false,
                                insideProvenSegment = false,
                            )
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
        assertFalse(anchored.canLoadMoreBefore, "offline anchored open with hasOlderStored=false closes older paging cleanly")
        assertTrue(anchored.canLoadMoreAfter, "more contiguous newer rows remain inside segment")
        assertEquals("Cached subtitle", anchored.subtitle)
        assertEquals(ThreadAccessResolution.Resolved, anchoredStore.state.value.accessResolution)
        assertTrue(anchoredStore.state.value.writeActionsEnabled, "durable anchored overlay preserves verified cached write access")

        // Offline LoadNewer pages strictly inside the same contiguous segment [26..35] without
        // polluting memory cache, and closes canLoadMoreAfter cleanly at the segment edge.
        anchoredStore.accept(SharedThreadIntent.LoadNewer)
        val afterOfflineNewer = anchoredStore.state.value.payload
        assertTrue(afterOfflineNewer.durableLastKnown)
        assertEquals((15L..35L).toList(), afterOfflineNewer.messages.mapNotNull { it.seq })
        assertFalse(afterOfflineNewer.canLoadMoreAfter, "offline LoadNewer closes cleanly at contiguous segment edge")
        assertEquals("idle", afterOfflineNewer.newerMessagesStatus)
        assertEquals(listOf("v2-120"), ds.cachedPayload("channel-1", serverId = "server-1")?.messages?.map { it.id })

        // Realtime events arriving on a durable stand-in MUST never overwrite the memory cache.
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

        // HIGH-5: LoadNewer cancellation on online durable stand-in (finally resets newerMessagesStatus="idle")
        // must never overwrite the memory cache while the online refresh is still in flight.
        val cancelRefreshStarted = CompletableDeferred<Unit>()
        val releaseCancelRefresh = CompletableDeferred<Unit>()
        api.failChannelMessages = false
        api.channelMessagesStarted = cancelRefreshStarted
        api.releaseChannelMessages = releaseCancelRefresh
        api.afterMessagesStarted = CompletableDeferred()
        api.releaseAfterMessages = CompletableDeferred()
        val cancelStore = SharedThreadStore(ds, scope = scope)
        val cancelStartJob = launch { cancelStore.accept(SharedThreadIntent.Start(basePayload().copy(focusedMessageId = "v2-20"))) }
        cancelRefreshStarted.await()
        // Advance to segment edge [15..35] while online refresh is in flight, then cancel network LoadNewer.
        cancelStore.accept(SharedThreadIntent.LoadNewer)
        assertEquals((15L..35L).toList(), cancelStore.state.value.payload.messages.mapNotNull { it.seq })
        val cancelJob = launch { cancelStore.accept(SharedThreadIntent.LoadNewer) }
        api.afterMessagesStarted!!.await()
        cancelJob.cancelAndJoin()
        assertEquals("idle", cancelStore.state.value.payload.newerMessagesStatus)
        assertEquals(
            listOf("v2-120"),
            ds.cachedPayload("channel-1", serverId = "server-1")?.messages?.map { it.id },
            "cancelled LoadNewer on durable stand-in must not pollute memory cache",
        )
        releaseCancelRefresh.complete(Unit)
        cancelStartJob.join()
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
