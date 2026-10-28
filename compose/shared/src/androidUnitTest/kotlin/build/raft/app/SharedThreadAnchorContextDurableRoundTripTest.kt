package build.raft.app

import build.raft.app.data.loadOnEnter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.data.CanonicalMessageV2AdjacentPageWriter
import build.raft.app.data.CanonicalMessageV2EnteredScopeLoader
import build.raft.app.data.CanonicalMessageV2RestSnapshotFetcher
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RaftSqlDatabase
import build.raft.app.data.RawMessagePage
import build.raft.app.data.RawMessagePageWitness
import build.raft.app.data.RecordingNetworkClient
import build.raft.app.data.ScreenError
import build.raft.app.data.SelectedServerStore
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
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
import build.raft.app.network.sync.CanonicalMessageV2BaselineCoordinator
import build.raft.app.network.sync.CanonicalMessageV2DurableLastKnownReader
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.DurablePartitionKey
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SqlDelightCanonicalMessageV2Store
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Task #14 part C end to end on the real SQLite schema: an anchored open fetches `messages/context/{anchor}`;
 * that page is persisted into the durable V2 window (it overlaps the window the enter snapshot committed), so
 * a later OFFLINE open's durable stand-in already holds the anchor and the page around it. Twins: the writer's
 * flag off, and an identity change before the write — both write nothing (the offline open shows only the
 * window the enter committed).
 */
class SharedThreadAnchorContextDurableRoundTripTest {
    @Test
    fun anchorContextPagePersistedOnlineOpensOfflineAroundTheAnchor() = withFreshDatabase { database ->
        runBlocking {
            val online = Phase(database, "ctx-online-1", contextResult = DataResult.Content(contextSnapshot()))
            online.seedLatestPage()
            online.open()
            online.awaitLog("canonical_v2_context_page")
            assertTrue(
                online.logs.any {
                    it == "canonical_v2_context_page server=server-1 scope=channel-1 anchor=m30 outcome=committed " +
                        "reason=window_extended_older facts=3"
                },
                online.logs.toString(),
            )
            online.close()

            val offline = Phase(database, "ctx-offline-1", contextResult = contextUnavailable, enterReachable = false)
            val payload = offline.open()
            assertTrue(payload.durableLastKnown)
            assertEquals("m30", payload.focusedMessageId)
            // The enter committed 50..80; the context page added 20..40 below it, contiguous through m50.
            assertEquals(listOf("m20", "m30", "m40", "m50", "m60", "m70", "m80"), payload.messages.map { it.id })
            offline.close()
        }
    }

    @Test
    fun writerFlagOffLeavesTheOfflineOpenOnTheNewestPage() = withFreshDatabase { database ->
        // Twin of the test above: identical online open and context page, the writer's server flag is off.
        runBlocking {
            val online = Phase(database, "ctx-online-2", contextResult = DataResult.Content(contextSnapshot()), writerFlag = false)
            online.seedLatestPage()
            online.open()
            online.awaitLog("canonical_v2_context_page")
            assertTrue(
                online.logs.any {
                    it == "canonical_v2_context_page server=server-1 scope=channel-1 anchor=m30 outcome=skipped reason=flag_off facts=0"
                },
                online.logs.toString(),
            )
            online.close()

            val offline = Phase(database, "ctx-offline-2", contextResult = contextUnavailable, enterReachable = false)
            val payload = offline.open()
            assertTrue(payload.durableLastKnown)
            assertEquals(listOf("m50", "m60", "m70", "m80"), payload.messages.map { it.id })
            offline.close()
        }
    }

    /** One process lifetime over the shared database: its own engine, canary, payload memory and store. */
    private class Phase(
        database: RaftSqlDatabase,
        name: String,
        contextResult: DataResult<ThreadSnapshot>,
        writerFlag: Boolean = true,
        enterReachable: Boolean = true,
    ) {
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        private val sqlStore = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L })
        private val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = sqlStore,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        private val canary = CanonicalMessageV2ProductionCanary(isEnabledForServer = { true }, generationEngine = engine)
        val reader = CanonicalMessageV2DurableLastKnownReader(sqlStore, isEnabledForServer = { true })
        val sessionStore = InMemoryRaftAuthSessionStore(session)
        val repository =
            ReplyThreadAccessConversationRepositoryRecorder(
                replyResult = DataResult.Error(ScreenError(code = "unused", retryable = false)),
                parentResult = contextResult,
            )
        private val enterBody = """{"messages":${page(50, 60, 70, 80)},"historyLimited":false}"""
        val enterNetwork =
            RecordingNetworkClient(enterBody, channelIds = if (enterReachable) setOf("channel-1") else emptySet())
        private val loader = CanonicalMessageV2EnteredScopeLoader(
            canary = canary,
            generationEngine = engine,
            fetcher = CanonicalMessageV2RestSnapshotFetcher(enterNetwork, logs::add),
            isEnabledForServer = { true },
            logger = logs::add,
        )

        /**
         * Message storage Phase 2b: a focused (anchored) open fetches only the context page, so the channel's latest
         * page — the window the context page joins — is written by an earlier ordinary open; this plays that write.
         */
        suspend fun seedLatestPage() {
            loader.loadOnEnter(partitionOf(session), "channel-1") { true }
        }

        val dataSource =
            testSharedThreadDataSource(
                networkApi = SharedThreadStoreTest.FakeThreadApi(),
                sessionStore = sessionStore,
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                selectedServerStore = SelectedServerStore(RaftShellStoreScope.create(name, "server-1")),
                conversationRepository = repository,
                payloadStore = SharedThreadPayloadStore(prewarmStartIntervalMillis = 0L),
                durableLastKnownReader = reader,
                enteredScopeSnapshotLoader = loader,
                durablePageExtender = CanonicalMessageV2AdjacentPageWriter(
                    canary = canary,
                    generationEngine = engine,
                    fetcher = CanonicalMessageV2RestSnapshotFetcher(RecordingNetworkClient(canonicalV2TestPage()), logs::add),
                    isEnabledForServer = { writerFlag },
                    logger = logs::add,
                ),
                diagnosticsLogger = logs::add,
            )
        private val scopeJob = SupervisorJob()
        private val store = SharedThreadStore(dataSource = dataSource, scope = CoroutineScope(scopeJob + Dispatchers.Unconfined))

        suspend fun open(routePayload: RaftThreadScreenPayload = route()): RaftThreadScreenPayload {
            store.accept(SharedThreadIntent.Start(routePayload))
            return store.state.value.payload
        }

        suspend fun awaitLog(fragment: String) {
            withTimeout(5_000L) {
                while (synchronized(logs) { logs.none { fragment in it } }) delay(10L)
            }
        }

        suspend fun awaitPayload(predicate: (RaftThreadScreenPayload) -> Boolean) {
            withTimeout(5_000L) {
                while (!predicate(store.state.value.payload)) delay(10L)
            }
        }

        suspend fun close() = scopeJob.cancelAndJoin()
    }

    private fun withFreshDatabase(block: (RaftSqlDatabase) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            RaftSqlDatabase.Schema.create(driver).value
            block(RaftSqlDatabase(driver))
        } finally {
            driver.close()
        }
    }

    private companion object {
        val session = StoredAuthSession(
            user = StoredAuthUser(id = "u-a", email = "u-a@example.test", displayName = "Owner A"),
            accessToken = "access-u-a",
            refreshToken = "refresh-u-a",
            selectedServerId = "server-1",
        )
        val otherSession = StoredAuthSession(
            user = StoredAuthUser(id = "u-b", email = "u-b@example.test", displayName = "Owner B"),
            accessToken = "access-u-b",
            refreshToken = "refresh-u-b",
            selectedServerId = "server-1",
        )
        val contextUnavailable: DataResult<ThreadSnapshot> =
            DataResult.Error(ScreenError(code = "thread_focused_context", retryable = true))

        fun partitionOf(owner: StoredAuthSession) = DurablePartitionKey(
            serverId = "server-1",
            principalId = owner.user.id,
            consumerId = SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID,
            environmentId = TestThreadEnvironmentId,
        )

        fun page(vararg seqs: Long) = adjacentPageOf(*seqs.map { adjacentPageNode("m$it", it) }.toTypedArray())

        fun route() = RaftThreadScreenPayload(
            instanceId = "shared-shell-thread-channel-1",
            title = "general",
            sourceThreadId = "channel-1",
            status = "loading",
            canSend = true,
            focusedMessageId = "m30",
        )

        /** The context page around m30 the repository returns: the projection plus the raw page it came from. */
        fun contextSnapshot(): ThreadSnapshot {
            val channelId = ChannelId("channel-1")
            val seqs = listOf(20L, 30L, 40L, 50L)
            return ThreadSnapshot(
                conversation = ConversationSummary(
                    threadId = ThreadId("channel-1"),
                    channelId = channelId,
                    kind = ConversationKind.Channel,
                    name = "general",
                    joined = true,
                    permissions = ConversationPermissions(canRead = true, canSend = true),
                ),
                messages = seqs.map { seq ->
                    Message(
                        id = MessageId("m$seq"),
                        threadId = ThreadId("channel-1"),
                        channelId = channelId,
                        author = MemberProfile(
                            id = MemberId("user-1"),
                            displayName = "Alice",
                            handle = "alice",
                            kind = MemberKind.Human,
                            presence = Presence.Active,
                        ),
                        seq = seq,
                        timestampLabel = "2026-07-17T00:00:00Z",
                        body = "hello m$seq",
                    )
                },
                canLoadMoreBefore = true,
                canLoadMoreAfter = true,
                rawMessagePage = RawMessagePageWitness(RawMessagePage(page(*seqs.toLongArray()), requestedLimit = 0)),
            )
        }
    }
}
