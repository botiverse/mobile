package build.raft.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RaftSqlDatabase
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadPendingSendInFlightRegistry
import build.raft.app.data.ThreadPendingSendJournal
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.network.sync.CanonicalConversationContextV2
import build.raft.app.network.sync.CanonicalMessageEnvelopeV2
import build.raft.app.network.sync.CanonicalMessageNodeV2
import build.raft.app.network.sync.CanonicalMessageV2Coverage
import build.raft.app.network.sync.CanonicalMessageV2DurableLastKnownReader
import build.raft.app.network.sync.CanonicalMessageV2SegmentSource
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SqlDelightCanonicalMessageV2Store
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.TopLevelScopeRefV2
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end SQLite + [CanonicalMessageV2DurableLastKnownReader] + [SharedThreadStore] verification
 * for first-principles unanchored opens (#proj-mobile:#358):
 * - Normal unanchored open shows only the contiguous head segment up to `head.highSeq` (plus local
 *   pending sends), never splicing unproven loose realtime facts above head or lower islands with
 *   mid-timeline gaps.
 * - Online refresh appends/updates newer messages cleanly at the bottom and absorbs loose facts
 *   once `Latest` coverage commits.
 * - Explicit offline `LoadOlder` (`Before(crossGaps=true)`) and anchored `AroundMessageId` opens
 *   remain intact.
 */
class SharedThreadStoreUnanchoredOpenNoMiddleGapTest {
    @Test
    fun unanchoredOpenNeverSplicesLooseRealtimeFactsOrLowerIslandsAndPreservesPendingSendsRefreshOfflineOlderAndAnchoredOpen() =
        withFreshDatabase { database ->
            runTest {
                val scopeJob = SupervisorJob()
                val storeScope = CoroutineScope(coroutineContext + scopeJob)
                val v2Store = SqlDelightCanonicalMessageV2Store(database = database, now = { 100L })
                val reader = CanonicalMessageV2DurableLastKnownReader(v2Store)
                val journal = ThreadPendingSendJournal(database = database, nowMillis = { 200L })
                val partition =
                    DurablePartitionKey(
                        serverId = "server-1",
                        principalId = "u-a",
                        consumerId = SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID,
                        environmentId = TestThreadEnvironmentId,
                    )
                val scopeKey = SyncScopeKey(partition = partition, scopeId = "channel-1", scopeKind = "channel")

                // Lower island [1..5] (reachesOldest=true), contiguous head [10..20], and loose realtime fact 120 above head.
                v2Store.applyCoverage(
                    scope = scopeKey,
                    envelopes = (1L..5L).map(::envelope),
                    coverage =
                        CanonicalMessageV2Coverage(
                            lowSeq = 1L,
                            highSeq = 5L,
                            reachesOldest = true,
                            headAtVerify = false,
                            source = CanonicalMessageV2SegmentSource.Context,
                        ),
                    revAtRequest = 0L,
                    userDriven = false,
                )
                v2Store.applyCoverage(
                    scope = scopeKey,
                    envelopes = (10L..20L).map(::envelope),
                    coverage =
                        CanonicalMessageV2Coverage(
                            lowSeq = 10L,
                            highSeq = 20L,
                            reachesOldest = false,
                            headAtVerify = true,
                            source = CanonicalMessageV2SegmentSource.Latest,
                        ),
                    revAtRequest = 0L,
                    userDriven = true,
                )
                v2Store.applyCoverage(
                    scope = scopeKey,
                    envelopes = listOf(envelope(120L)),
                    coverage = null,
                    revAtRequest = 0L,
                    userDriven = false,
                )

                val refreshStarted = CompletableDeferred<Unit>()
                val releaseRefresh = CompletableDeferred<Unit>()
                val api =
                    SharedThreadStoreTest.FakeThreadApi(
                        bodyText = messagesJson((10L..25L).toList() + listOf(120L)),
                    ).apply {
                        channelMessagesStarted = refreshStarted
                        releaseChannelMessages = releaseRefresh
                    }
                val ds = dataSource(backgroundScope, api, reader, journal)
                val producerContext = assertNotNull(ds.captureReplyCacheProducerContext())
                val route = basePayload().resolveThreadRouteIdentity()
                val pendingSend =
                    RaftThreadMessageItem(
                        id = "${ThreadTransientLocalSendIdPrefix}pending-1",
                        authorId = "u-a",
                        authorName = "Owner A",
                        isOwn = true,
                        seq = null,
                        timestampLabel = "2026-05-19T16:10:00Z",
                        createdAtEpochMillis = 1_779_207_000_000L,
                        body = "still sending",
                        deliveryState = ThreadMessageDeliverySending,
                        pendingSendContext =
                            RaftThreadPendingSendContext(
                                channelId = "channel-1",
                                body = "still sending",
                                randomId = "rand-pending-1",
                            ),
                    )
                assertTrue(journal.persistSending(producerContext.provenance, route, pendingSend))

                // 1. Unanchored Start while network refresh is in flight: Frame 1 shows contiguous head [10..20]
                //    + local pending send with ZERO inline timeline gaps!
                //    Mark rand-pending-1 in-flight so ThreadPendingSendDrainer does not adopt & retry it as stale.
                ThreadPendingSendInFlightRegistry.begin("rand-pending-1")
                try {
                    val threadStore = SharedThreadStore(ds, scope = storeScope)
                    val startJob = launch { threadStore.accept(SharedThreadIntent.Start(basePayload())) }
                    refreshStarted.await()

                    val firstFrame = threadStore.state.value.payload
                    assertTrue(firstFrame.durableLastKnown)
                    assertEquals((10L..20L).toList(), firstFrame.messages.mapNotNull { it.seq })
                    assertTrue(
                        firstFrame.messages.any {
                            it.id == pendingSend.id &&
                                it.seq == null &&
                                it.deliveryState == ThreadMessageDeliverySending
                        },
                        "local pending send must remain attached and sending on Frame 1",
                    )
                    assertEquals(0, api.sendMessageCalls)
                    assertTrue(firstFrame.timelineGaps.isEmpty(), "unanchored Frame 1 must never emit inline gaps: ${firstFrame.timelineGaps}")
                    assertTrue(firstFrame.canLoadMoreBefore)
                    assertFalse(firstFrame.canLoadMoreAfter)

                    // 2. Network refresh completes: newer messages [21..25, 120] arrive cleanly at bottom with zero gaps
                    //    and local pending send stays preserved.
                    releaseRefresh.complete(Unit)
                    startJob.join()
                    val afterRefresh = threadStore.state.value.payload
                    assertFalse(afterRefresh.durableLastKnown)
                    assertEquals((10L..25L).toList() + listOf(120L), afterRefresh.messages.mapNotNull { it.seq })
                    assertTrue(
                        afterRefresh.messages.any {
                            it.id == pendingSend.id && it.deliveryState == ThreadMessageDeliverySending
                        },
                    )
                    assertTrue(afterRefresh.timelineGaps.isEmpty())
                } finally {
                    ThreadPendingSendInFlightRegistry.end("rand-pending-1")
                }

                // 3. Anchored open BEFORE Latest coverage commits still locates loose fact 120 on disk.
                val offlineApi = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true)
                val anchoredStore = SharedThreadStore(dataSource(backgroundScope, offlineApi, reader, journal), scope = storeScope)
                anchoredStore.accept(SharedThreadIntent.Start(basePayload().copy(focusedMessageId = "v2-120")))
                assertTrue(anchoredStore.state.value.payload.durableLastKnown)
                assertTrue(
                    anchoredStore.state.value.payload.messages.any { it.id == "v2-120" },
                    "anchored open must still locate loose fact 120 on disk",
                )

                // 4. Offline open + explicit LoadOlder still crosses into lower island [1..5] with derived gap (5, 10).
                val offlineStore = SharedThreadStore(dataSource(backgroundScope, offlineApi, reader, journal), scope = storeScope)
                offlineStore.accept(SharedThreadIntent.Start(basePayload()))
                assertEquals((10L..20L).toList(), offlineStore.state.value.payload.messages.mapNotNull { it.seq })
                assertTrue(offlineStore.state.value.payload.timelineGaps.isEmpty())
                offlineStore.accept(SharedThreadIntent.LoadOlder)
                assertEquals((1L..5L).toList() + (10L..20L).toList(), offlineStore.state.value.payload.messages.mapNotNull { it.seq })
                assertEquals(listOf(ThreadTimelineGap(afterSeq = 5L, beforeSeq = 10L, status = "idle")), offlineStore.state.value.payload.timelineGaps)

                // 5. After Latest coverage commits [21..120], loose fact 120 is absorbed into head; subsequent offline
                //    open shows [10..25, 120] seamlessly with zero gaps!
                v2Store.applyCoverage(
                    scope = scopeKey,
                    envelopes = (21L..25L).map(::envelope) + listOf(envelope(120L)),
                    coverage =
                        CanonicalMessageV2Coverage(
                            lowSeq = 21L,
                            highSeq = 120L,
                            reachesOldest = false,
                            headAtVerify = true,
                            source = CanonicalMessageV2SegmentSource.Latest,
                        ),
                    revAtRequest = 0L,
                    userDriven = true,
                )
                val reopenedOffline = SharedThreadStore(dataSource(backgroundScope, offlineApi, reader, journal), scope = storeScope)
                reopenedOffline.accept(SharedThreadIntent.Start(basePayload()))
                assertEquals((10L..25L).toList() + listOf(120L), reopenedOffline.state.value.payload.messages.mapNotNull { it.seq })
                assertTrue(reopenedOffline.state.value.payload.timelineGaps.isEmpty())

                // 6. Unproven empty head (reachesOldest=false, 0 facts inside head, loose fact above it) must NOT
                //    fall back to legacy reader.read() and render a false empty channel or loose fact.
                val unprovenEmptyScope = SyncScopeKey(partition = partition, scopeId = "channel-unproven-empty", scopeKind = "channel")
                v2Store.applyCoverage(
                    scope = unprovenEmptyScope,
                    envelopes = emptyList(),
                    coverage =
                        CanonicalMessageV2Coverage(
                            lowSeq = 101L,
                            highSeq = 150L,
                            reachesOldest = false,
                            headAtVerify = true,
                            source = CanonicalMessageV2SegmentSource.Latest,
                        ),
                    revAtRequest = 0L,
                    userDriven = true,
                )
                v2Store.applyCoverage(
                    scope = unprovenEmptyScope,
                    envelopes =
                        listOf(
                            CanonicalMessageEnvelopeV2(
                                fact =
                                    CanonicalMessageNodeV2(
                                        id = "v2-empty-loose-200",
                                        seq = 200L,
                                        senderType = "user",
                                        senderId = "u-a",
                                        content = "loose above unproven empty head",
                                        messageType = "chat",
                                        createdAt = "2026-05-19T16:15:00Z",
                                        senderName = "Owner A",
                                    ),
                                context = CanonicalConversationContextV2.TopLevel(TopLevelScopeRefV2.Channel("server-1", "channel-unproven-empty")),
                            )
                        ),
                    coverage = null,
                    revAtRequest = 0L,
                    userDriven = false,
                )
                val unprovenRoute = basePayload().copy(instanceId = "unproven-empty", sourceThreadId = "channel-unproven-empty")
                assertNull(
                    ds.durableLastKnownResultIfCurrent(
                        routePayload = unprovenRoute,
                        routeIdentity = unprovenRoute.resolveThreadRouteIdentity(),
                        context = producerContext,
                    ),
                    "supportsTimelineReads=true must not fall back to legacy reader.read() on deliberate null",
                )

                scopeJob.cancelAndJoin()
            }
        }

    private fun envelope(seq: Long): CanonicalMessageEnvelopeV2 =
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
            context = CanonicalConversationContextV2.TopLevel(TopLevelScopeRefV2.Channel("server-1", "channel-1")),
        )

    private fun messagesJson(seqs: List<Long>): String {
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
        return """{"historyLimited": false, "messages": [$items]}"""
    }

    private fun dataSource(
        storeScope: CoroutineScope,
        api: SharedThreadStoreTest.FakeThreadApi,
        reader: CanonicalMessageV2DurableLastKnownReader,
        journal: ThreadPendingSendJournal,
    ): SharedThreadDataSource =
        SharedThreadDataSource(
            threadParseDispatcher = storeScope.testDispatcher(),
            durableDbDispatcher = storeScope.testDispatcher(),
            messageStore = SharedThreadMessageStore(scope = storeScope),
            payloadStore = SharedThreadPayloadStore(scope = storeScope),
            pendingSendJournal = journal,
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

    private fun withFreshDatabase(block: (RaftSqlDatabase) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            RaftSqlDatabase.Schema.create(driver).value
            block(RaftSqlDatabase(driver))
        } finally {
            driver.close()
        }
    }
}
