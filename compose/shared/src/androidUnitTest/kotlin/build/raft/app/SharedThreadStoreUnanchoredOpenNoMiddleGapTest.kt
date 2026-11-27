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
import build.raft.app.resolveThreadRouteIdentity
import build.raft.app.resources.Res
import build.raft.app.resources.desc
import build.raft.app.resources.thread_offline_older_unavailable
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadPendingSendContext
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.SharedThreadDataSource
import build.raft.app.thread.SharedThreadIntent
import build.raft.app.thread.SharedThreadMessageStore
import build.raft.app.thread.SharedThreadPayloadStore
import build.raft.app.thread.SharedThreadStore
import build.raft.app.thread.ThreadMessageDeliverySending
import build.raft.app.thread.ThreadTransientLocalSendIdPrefix
import build.raft.app.thread.resolveThreadRouteIdentity
import build.raft.app.thread.threadOlderMessagesStatusSpec
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
 * for single-contiguous-slice timelines (#proj-mobile:#358 & #proj-mobile:#388):
 * - Normal unanchored open shows only the contiguous head segment up to `head.highSeq` (plus local
 *   pending sends), never splicing unproven loose realtime facts above head or lower islands.
 * - Online refresh appends/updates newer messages cleanly at the bottom and absorbs loose facts
 *   once `Latest` coverage commits.
 * - Both unanchored and anchored offline opens NEVER bridge across unverified SQLite gaps; they
 *   stop cleanly at their contiguous segment edge with the single outer offline boundary row.
 */
class SharedThreadStoreUnanchoredOpenNoMiddleGapTest {
    @Test
    fun unanchoredAndAnchoredOpensNeverBridgeIslandsOnlineOrOfflineAndPreservePendingSendsAndRefresh() =
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
                    assertTrue(firstFrame.canLoadMoreBefore)
                    assertFalse(firstFrame.canLoadMoreAfter)

                    // 2. Network refresh completes: newer messages [21..25, 120] arrive cleanly at bottom
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
                } finally {
                    ThreadPendingSendInFlightRegistry.end("rand-pending-1")
                }

                // 3. Anchored offline open on loose fact 120 stays strictly on [120] and closes both
                //    outer boundaries cleanly without ever bridging into [10..20] or [1..5].
                val offlineApi = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true)
                val anchoredStore = SharedThreadStore(dataSource(backgroundScope, offlineApi, reader, journal), scope = storeScope)
                anchoredStore.accept(SharedThreadIntent.Start(basePayload().copy(focusedMessageId = "v2-120")))
                val anchoredLoose = anchoredStore.state.value.payload
                assertTrue(anchoredLoose.durableLastKnown)
                assertTrue(anchoredLoose.mainLoadFailed)
                assertEquals(
                    listOf(120L),
                    anchoredLoose.messages.mapNotNull { it.seq },
                    "anchored open on loose fact 120 must show only gap-free loose anchor",
                )
                assertFalse(anchoredLoose.canLoadMoreBefore)
                assertFalse(anchoredLoose.canLoadMoreAfter)
                assertFalse(anchoredLoose.durableReachedOldest)
                assertEquals(
                    Res.string.thread_offline_older_unavailable.desc(),
                    threadOlderMessagesStatusSpec(anchoredLoose)?.label,
                )

                // Explicit offline LoadOlder from loose anchor 120 never crosses into lower islands.
                anchoredStore.accept(SharedThreadIntent.LoadOlder)
                assertEquals(listOf(120L), anchoredStore.state.value.payload.messages.mapNotNull { it.seq })

                // Anchored offline open inside head [10..20] also stays strictly inside head [10..20]
                // and stops cleanly at both segment edges with the single outer offline boundary row.
                val anchoredInSegmentStore = SharedThreadStore(dataSource(backgroundScope, offlineApi, reader, journal), scope = storeScope)
                anchoredInSegmentStore.accept(SharedThreadIntent.Start(basePayload().copy(focusedMessageId = "v2-15")))
                val anchoredInSeg = anchoredInSegmentStore.state.value.payload
                assertTrue(anchoredInSeg.durableLastKnown)
                assertEquals((10L..20L).toList(), anchoredInSeg.messages.mapNotNull { it.seq })
                assertFalse(anchoredInSeg.canLoadMoreBefore)
                assertFalse(anchoredInSeg.canLoadMoreAfter)
                assertFalse(anchoredInSeg.durableReachedOldest)
                assertEquals(
                    Res.string.thread_offline_older_unavailable.desc(),
                    threadOlderMessagesStatusSpec(anchoredInSeg)?.label,
                )

                // 4. Unanchored offline open + LoadOlder stays strictly on head [10..20] and NEVER
                //    crosses into lower island [1..5].
                val offlineStore = SharedThreadStore(dataSource(backgroundScope, offlineApi, reader, journal), scope = storeScope)
                offlineStore.accept(SharedThreadIntent.Start(basePayload()))
                val offlineInitial = offlineStore.state.value.payload
                assertEquals((10L..20L).toList(), offlineInitial.messages.mapNotNull { it.seq })
                assertFalse(offlineInitial.canLoadMoreBefore)
                assertFalse(offlineInitial.durableReachedOldest)
                assertEquals(
                    Res.string.thread_offline_older_unavailable.desc(),
                    threadOlderMessagesStatusSpec(offlineInitial)?.label,
                )
                offlineStore.accept(SharedThreadIntent.LoadOlder)
                assertEquals((10L..20L).toList(), offlineStore.state.value.payload.messages.mapNotNull { it.seq })

                // 5. After Latest coverage commits [21..120], loose fact 120 is absorbed into head; subsequent offline
                //    open shows [10..25, 120] seamlessly as one contiguous slice!
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
                assertFalse(reopenedOffline.state.value.payload.canLoadMoreBefore)
                assertFalse(reopenedOffline.state.value.payload.durableReachedOldest)

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
