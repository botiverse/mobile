package build.raft.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.data.CHANNEL_MESSAGE_WINDOW_DOMAIN
import build.raft.app.data.CHANNEL_MESSAGE_WINDOW_SCHEMA_VERSION
import build.raft.app.data.CanonicalMessageV2AdjacentPageWriter
import build.raft.app.data.CanonicalMessageV2RestSnapshotFetcher
import build.raft.app.data.ChannelMessageWindow
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RaftSqlDatabase
import build.raft.app.data.RawMessagePage
import build.raft.app.data.RawMessagePageWitness
import build.raft.app.data.RecordingNetworkClient
import build.raft.app.data.ScreenError
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.data.ThreadSnapshot
import build.raft.app.data.adjacentPageNode
import build.raft.app.data.adjacentPageOf
import build.raft.app.data.canonicalV2TestPage
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.ConversationSummary
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.Message
import build.raft.app.model.MessageId
import build.raft.app.model.Presence
import build.raft.app.model.ThreadId
import build.raft.app.network.sync.CanonicalConversationContextV2
import build.raft.app.network.sync.CanonicalMessageEnvelopeV2
import build.raft.app.network.sync.CanonicalMessageNodeV2
import build.raft.app.network.sync.CanonicalMessageV2BaselineCoordinator
import build.raft.app.network.sync.CanonicalMessageV2Coverage
import build.raft.app.network.sync.CanonicalMessageV2DurableLastKnownReader
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2SegmentSource
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SqlDelightCanonicalMessageV2Store
import build.raft.app.network.sync.SyncScopeKey
import build.raft.app.network.sync.TopLevelScopeRefV2
import build.raft.app.resources.Res
import build.raft.app.resources.desc
import build.raft.app.resources.thread_offline_older_unavailable
import build.raft.app.resources.thread_state_beginning_conversation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 2d PR 2 (#proj-mobile:#240) / #proj-mobile:#388 real-SQLite round trip:
 * disjoint segments `[1..10]` and `[51..80]` -> offline open stays strictly inside `[51..80]` and
 * stops cleanly at the contiguous segment edge with the outer "Older messages are not available
 * offline" boundary row (NEVER bridging across the gap into `[1..10]`) -> reconnect `Refresh`
 * restores online paging -> online `LoadOlder` fetches `[21..50]` then `[11..20]` and persists via
 * [CanonicalMessageV2AdjacentPageWriter] so SQLite segments coalesce into `[1..80]` -> a subsequent
 * cold offline open walks `80 -> 1` cleanly all the way to "Beginning of conversation".
 */
class SharedThreadStep2dGapRoundTripTest {
    @Test
    fun offlineDisjointSegmentsStopAtOuterBoundaryAndOnlinePagingCoalescesSqliteForNextColdOfflineOpen() = withFreshDatabase { database ->
        runBlocking {
            val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
            val sqlStore = SqlDelightCanonicalMessageV2Store(database = database, now = { 1_000L })
            val engine =
                CanonicalMessageV2GenerationEngine(
                    coordinator = CanonicalMessageV2BaselineCoordinator(),
                    store = sqlStore,
                    snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
                )
            val canary = CanonicalMessageV2ProductionCanary(generationEngine = engine)
            val reader = CanonicalMessageV2DurableLastKnownReader(sqlStore)
            val writer =
                CanonicalMessageV2AdjacentPageWriter(
                    canary = canary,
                    generationEngine = engine,
                    fetcher = CanonicalMessageV2RestSnapshotFetcher(RecordingNetworkClient(canonicalV2TestPage()), logs::add),
                    logger = logs::add,
                )
            val partition = partition()
            val scopeKey = SyncScopeKey(partition, "channel-1", "channel")

            // Seed two disjoint segments: lower island [1..10] (reaches oldest) and upper head [51..80].
            sqlStore.applyCoverage(
                scope = scopeKey,
                envelopes = (1L..10L).map(::envelope),
                coverage =
                    CanonicalMessageV2Coverage(
                        lowSeq = 1L,
                        highSeq = 10L,
                        reachesOldest = true,
                        headAtVerify = false,
                        source = CanonicalMessageV2SegmentSource.Older,
                    ),
                revAtRequest = 0L,
                userDriven = true,
            )
            sqlStore.applyCoverage(
                scope = scopeKey,
                envelopes = (51L..80L).map(::envelope),
                coverage =
                    CanonicalMessageV2Coverage(
                        lowSeq = 51L,
                        highSeq = 80L,
                        reachesOldest = false,
                        headAtVerify = true,
                        source = CanonicalMessageV2SegmentSource.Latest,
                    ),
                revAtRequest = 0L,
                userDriven = true,
            )
            assertEquals(2, sqlStore.readSegments(scopeKey).size)

            // Phase 1: Cold offline open paints strictly contiguous head [51..80]; never bridges
            // across the unverified gap into [1..10], closes older paging cleanly with the single
            // outer "Older messages are not available offline" boundary row.
            val repo =
                SharedThreadStoreTestFakes.FakeConversationRepository(
                    threadResult = DataResult.Error(ScreenError(code = "offline", retryable = true)),
                    olderResult = DataResult.Error(ScreenError(code = "offline", retryable = true)),
                )
            val phase1Job = SupervisorJob()
            val phase1Scope = CoroutineScope(coroutineContext + phase1Job)
            val phase1Store =
                SharedThreadStore(
                    dataSource =
                        SharedThreadDataSource(
                            networkApi = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true),
                            sessionStore = InMemoryRaftAuthSessionStore(owner.session),
                            conversationRepository = repo,
                            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                            replyCacheIdentityAuthority = { owner },
                            durableLastKnownReader = reader,
                            durablePageExtender = writer,
                            durableDbDispatcher = Dispatchers.Unconfined,
                            diagnosticsLogger = logs::add,
                        ),
                    scope = phase1Scope,
                )
            phase1Store.accept(SharedThreadIntent.Start(basePayload()))
            val offlineStart = phase1Store.state.value.payload
            assertTrue(offlineStart.durableLastKnown)
            assertTrue(offlineStart.mainLoadFailed)
            assertEquals((51L..80L).toList(), offlineStart.messages.mapNotNull { it.seq })
            assertFalse(offlineStart.canLoadMoreBefore)
            assertFalse(offlineStart.durableReachedOldest)
            assertEquals("idle", offlineStart.olderMessagesStatus)
            assertEquals(
                Res.string.thread_offline_older_unavailable.desc(),
                threadOlderMessagesStatusSpec(offlineStart)?.label,
            )

            // Offline LoadOlder stays strictly on [51..80] without bridging [1..10].
            phase1Store.accept(SharedThreadIntent.LoadOlder)
            assertEquals((51L..80L).toList(), phase1Store.state.value.payload.messages.mapNotNull { it.seq })
            assertFalse(phase1Store.state.value.payload.canLoadMoreBefore)
            assertFalse(phase1Store.state.value.payload.durableReachedOldest)

            // Phase 2: Connectivity returns. Refresh restores online older paging; scrolling older
            // fetches [21..50] then [11..20] sequentially from the server and persists them to SQLite,
            // coalescing [1..10] + [11..20] + [21..50] + [51..80] into ONE contiguous segment [1..80]!
            repo.threadResult = DataResult.Content(olderSnapshot(seqs = (51L..80L).toList(), coveredAfterSeq = 50L, coveredThroughSeq = 80L))
            phase1Store.accept(SharedThreadIntent.Refresh)
            assertFalse(phase1Store.state.value.payload.durableLastKnown)
            assertTrue(phase1Store.state.value.payload.canLoadMoreBefore)

            repo.olderResult = DataResult.Content(olderSnapshot(seqs = (21L..50L).toList(), coveredAfterSeq = 20L, coveredThroughSeq = 50L))
            phase1Store.accept(SharedThreadIntent.LoadOlder)
            assertEquals((21L..80L).toList(), phase1Store.state.value.payload.messages.mapNotNull { it.seq })
            awaitLogCount(logs, "canonical_v2_adjacent_page", expected = 1)

            repo.olderResult = DataResult.Content(olderSnapshot(seqs = (11L..20L).toList(), coveredAfterSeq = 10L, coveredThroughSeq = 20L))
            phase1Store.accept(SharedThreadIntent.LoadOlder)
            assertEquals((11L..80L).toList(), phase1Store.state.value.payload.messages.mapNotNull { it.seq })
            awaitLogCount(logs, "canonical_v2_adjacent_page", expected = 2)
            phase1Job.cancelAndJoin()

            val coalescedSegments = sqlStore.readSegments(scopeKey)
            assertEquals(1, coalescedSegments.size, "segments must coalesce after online older paging bridges them: $coalescedSegments ($logs)")
            assertEquals(1L, coalescedSegments.single().lowSeq)
            assertEquals(80L, coalescedSegments.single().highSeq)
            assertTrue(coalescedSegments.single().reachesOldest)
            assertTrue(coalescedSegments.single().headAtVerify)

            // Phase 3: Fresh cold offline open over the same SQLite DB walks 80 -> 1 seamlessly.
            val phase3Job = SupervisorJob()
            val phase3Scope = CoroutineScope(coroutineContext + phase3Job)
            val phase3Store =
                SharedThreadStore(
                    dataSource =
                        SharedThreadDataSource(
                            networkApi = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true),
                            sessionStore = InMemoryRaftAuthSessionStore(owner.session),
                            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                            replyCacheIdentityAuthority = { owner },
                            durableLastKnownReader = CanonicalMessageV2DurableLastKnownReader(sqlStore),
                            durableDbDispatcher = Dispatchers.Unconfined,
                        ),
                    scope = phase3Scope,
                )
            phase3Store.accept(SharedThreadIntent.Start(basePayload()))
            assertEquals((51L..80L).toList(), phase3Store.state.value.payload.messages.mapNotNull { it.seq })
            assertTrue(phase3Store.state.value.payload.canLoadMoreBefore)

            phase3Store.accept(SharedThreadIntent.LoadOlder)
            assertEquals((21L..80L).toList(), phase3Store.state.value.payload.messages.mapNotNull { it.seq })
            assertTrue(phase3Store.state.value.payload.canLoadMoreBefore)

            phase3Store.accept(SharedThreadIntent.LoadOlder)
            val finalOffline = phase3Store.state.value.payload
            assertEquals((1L..80L).toList(), finalOffline.messages.mapNotNull { it.seq })
            assertFalse(finalOffline.canLoadMoreBefore)
            assertTrue(finalOffline.durableReachedOldest)
            assertEquals(
                Res.string.thread_state_beginning_conversation.desc(),
                threadOlderMessagesStatusSpec(finalOffline)?.label,
            )
            phase3Job.cancelAndJoin()
        }
    }

    private suspend fun awaitLogCount(logs: List<String>, needle: String, expected: Int) {
        withTimeout(5_000L) {
            while (logs.count { needle in it } < expected) {
                delay(10L)
            }
        }
    }

    private fun olderSnapshot(seqs: List<Long>, coveredAfterSeq: Long, coveredThroughSeq: Long): ThreadSnapshot {
        val channelId = ChannelId("channel-1")
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
                        id = MessageId("m$seq"),
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
                        timestampLabel = "2026-07-17T00:00:00.000Z",
                        body = "hello m$seq",
                    )
                },
            canLoadMoreBefore = true,
            canLoadMoreAfter = false,
            rawMessagePage =
                RawMessagePageWitness(
                    RawMessagePage(
                        messages = adjacentPageOf(*seqs.map { adjacentPageNode("m$it", it) }.toTypedArray()),
                        requestedLimit = 30,
                        messageWindow =
                            ChannelMessageWindow(
                                schemaVersion = CHANNEL_MESSAGE_WINDOW_SCHEMA_VERSION,
                                domain = CHANNEL_MESSAGE_WINDOW_DOMAIN,
                                serverId = "server-1",
                                scopeId = "channel-1",
                                coveredAfterSeq = coveredAfterSeq,
                                coveredThroughSeq = coveredThroughSeq,
                                completeThroughLatest = false,
                            ),
                    )
                ),
        )
    }

    private fun envelope(seq: Long): CanonicalMessageEnvelopeV2 =
        CanonicalMessageEnvelopeV2(
            fact =
                CanonicalMessageNodeV2(
                    id = "m$seq",
                    seq = seq,
                    senderType = "user",
                    senderId = "u-a",
                    content = "hello m$seq",
                    messageType = "chat",
                    createdAt = "2026-07-17T00:${((seq / 60) % 60).toString().padStart(2, '0')}:${(seq % 60).toString().padStart(2, '0')}.000Z",
                    senderName = "Owner A",
                ),
            context = CanonicalConversationContextV2.TopLevel(TopLevelScopeRefV2.Channel("server-1", "channel-1")),
        )

    private fun partition(): DurablePartitionKey =
        DurablePartitionKey(
            serverId = "server-1",
            principalId = "u-a",
            consumerId = SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID,
            environmentId = TestThreadEnvironmentId,
        )

    private val owner =
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
