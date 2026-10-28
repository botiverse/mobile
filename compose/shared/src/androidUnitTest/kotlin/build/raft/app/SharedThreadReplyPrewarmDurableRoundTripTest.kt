package build.raft.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.data.ActivityReplyThreadPrewarmKey
import build.raft.app.data.ActivityReplyThreadPrewarmMarker
import build.raft.app.data.CanonicalMessageV2EnteredScopeLoader
import build.raft.app.data.CanonicalMessageV2RestSnapshotFetcher
import build.raft.app.data.DataResult
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.PathNetworkClient
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.RaftSqlDatabase
import build.raft.app.data.RawMessagePage
import build.raft.app.data.RawMessagePageWitness
import build.raft.app.data.ScreenError
import build.raft.app.data.SelectedServerStore
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.replyNode
import build.raft.app.data.replyPage
import build.raft.app.data.replyPageArray
import build.raft.app.network.sync.CanonicalMessageV2BaselineCoordinator
import build.raft.app.network.sync.CanonicalMessageV2DurableLastKnownReader
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SqlDelightCanonicalMessageV2Store
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task #14b on the real SQLite schema: the reply page an Activity prewarm fetches is persisted as the
 * thread's durable V2 window, so the thread opens from disk (`thread_durable_last_known`) after the
 * in-memory prewarm was invalidated by a freshness generation bump. Twin: a prewarm whose fetch fails
 * writes nothing.
 */
class SharedThreadReplyPrewarmDurableRoundTripTest {
    @Test
    fun prewarmedReplyPageOpensFromDiskAfterTheMemoryPrewarmIsInvalidated() = withFreshDatabase { database ->
        runBlocking {
            val rig = Rig(database, replyResult = DataResult.Content(prewarmSnapshot()))

            assertIs<DataResult.Content<Unit>>(rig.dataSource.prewarmReplyThread(marker))
            // Phase 3b: the prewarm read the durable head epoch BEFORE its reply request and handed that to the write.
            assertEquals(listOf<Long?>(0L), rig.loader.prewarmRequestEpochs.toList())
            assertTrue(
                rig.logs.any {
                    "canonical_v2_prewarm_page server=server-1 scope=thread-1 scopeKind=thread outcome=committed " +
                        "reason=window_created facts=3" in it
                },
                rig.logs.toString(),
            )
            assertEquals(
                listOf("channels/thread-1", "channels/channel-1"),
                rig.network.paths,
                "the parent is confirmed by the server; the page itself is the prewarm's, not refetched",
            )
            assertNotNull(rig.dataSource.cachedPayload("thread-1"), "the in-memory prewarm landed too")

            // A new reply arrives: the freshness generation moves and the in-memory prewarm is gone.
            rig.payloadStore.freshnessLedger.advanceAllOwners("server-1", "thread-1")
            assertNull(rig.dataSource.cachedPayload("thread-1"))

            val context = assertNotNull(rig.dataSource.captureReplyCacheProducerContext())
            val standIn = assertNotNull(
                rig.dataSource.durableLastKnownPayloadIfCurrent(replyRoute(), replyRoute().resolveThreadRouteIdentity(), context),
            )
            assertTrue(standIn.durableLastKnown)
            assertEquals(listOf("hello r20", "hello r30", "hello r40"), standIn.messages.map { it.body })
            assertFalse(standIn.canLoadMoreBefore, "3 replies < the 30 the prewarm asked for: the thread starts here")
            assertTrue(rig.logs.any { "thread_durable_last_known threadId=thread-1 outcome=frame scopeKind=thread" in it }, rig.logs.toString())
        }
    }

    @Test
    fun failedPrewarmWritesNothing() = withFreshDatabase { database ->
        // Twin of the test above: identical wiring and stored nothing, but the reply fetch fails.
        runBlocking {
            val rig = Rig(
                database,
                replyResult = DataResult.Error(ScreenError(code = "thread_network_reply", retryable = true)),
            )
            assertIs<DataResult.Error>(rig.dataSource.prewarmReplyThread(marker))
            assertTrue(rig.logs.none { "canonical_v2_prewarm_page" in it }, rig.logs.toString())
            assertTrue(rig.network.paths.isEmpty(), rig.network.paths.toString())
            val context = assertNotNull(rig.dataSource.captureReplyCacheProducerContext())
            assertNull(rig.dataSource.durableLastKnownPayloadIfCurrent(replyRoute(), replyRoute().resolveThreadRouteIdentity(), context))
        }
    }

    @Test
    fun prewarmWhoseSnapshotCarriesNoRawPageWritesNothing() = withFreshDatabase { database ->
        // Twin of the positive: identical successful prewarm, but the repository did not capture the page
        // (e.g. it joined an access flight started without capture). Nothing is invented from the projection.
        runBlocking {
            val rig = Rig(database, replyResult = DataResult.Content(prewarmSnapshot().copy(rawMessagePage = RawMessagePageWitness.None)))
            assertIs<DataResult.Content<Unit>>(rig.dataSource.prewarmReplyThread(marker))
            assertTrue(rig.logs.any { "scope=thread-1 scopeKind=thread outcome=skipped reason=no_raw_page facts=0" in it }, rig.logs.toString())
            assertTrue(rig.network.paths.isEmpty())
            val context = assertNotNull(rig.dataSource.captureReplyCacheProducerContext())
            assertNull(rig.dataSource.durableLastKnownPayloadIfCurrent(replyRoute(), replyRoute().resolveThreadRouteIdentity(), context))
        }
    }

    private class Rig(database: RaftSqlDatabase, replyResult: DataResult<build.raft.app.data.ThreadSnapshot>) {
        // Thread-safe: the reply fetch, the parent-context lookup (an `async` sibling that may still be logging
        // after the prewarm returns) and the durable writer all log from different threads. A plain ArrayList
        // lost lines or threw ArrayIndexOutOfBounds inside a logger under concurrent `add`.
        val logs: MutableList<String> = CopyOnWriteArrayList()
        val network = PathNetworkClient(
            mutableMapOf(
                "channels/thread-1" to """{"id":"thread-1","type":"thread","parentChannelId":"channel-1","parentMessageId":"root-1"}""",
                "channels/channel-1" to """{"id":"channel-1","type":"channel"}""",
                "messages/channel/thread-1" to replyPage(replyNode(20), replyNode(30), replyNode(40)),
            ),
        )
        private val sqlStore = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L })
        private val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = sqlStore,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        val payloadStore = SharedThreadPayloadStore(prewarmStartIntervalMillis = 0L)
        val repository = ReplyThreadAccessConversationRepositoryRecorder(replyResult = replyResult)

        /** Phase 3b: the real loader; its head epoch moves with every reply-page request (a read after it sees 1). */
        val loader =
            HeadEpochRecordingLoader(
                CanonicalMessageV2EnteredScopeLoader(
                    canary = CanonicalMessageV2ProductionCanary(isEnabledForServer = { true }, generationEngine = engine),
                    generationEngine = engine,
                    fetcher = CanonicalMessageV2RestSnapshotFetcher(network, logs::add),
                    isEnabledForServer = { true },
                    logger = logs::add,
                ),
            ) { synchronized(repository.replyRequests) { repository.replyRequests.size.toLong() } }
        private val sessionStore = InMemoryRaftAuthSessionStore(session)
        val dataSource =
            testSharedThreadDataSource(
                networkApi = SharedThreadStoreTest.FakeThreadApi(),
                sessionStore = sessionStore,
                cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                selectedServerStore = SelectedServerStore(RaftShellStoreScope.create("thread-prewarm-durable", "server-1")),
                conversationRepository = repository,
                payloadStore = payloadStore,
                durableLastKnownReader = CanonicalMessageV2DurableLastKnownReader(sqlStore, isEnabledForServer = { true }, logger = logs::add),
                enteredScopeSnapshotLoader = loader,
                diagnosticsLogger = logs::add,
            )
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

        val marker = ActivityReplyThreadPrewarmMarker(
            key = ActivityReplyThreadPrewarmKey(
                serverId = "server-1",
                parentChannelId = "channel-1",
                parentMessageId = "root-1",
                threadChannelId = "thread-1",
            ),
        )

        /** What the real repository returns for the prewarm: the projection plus the raw page it came from. */
        fun prewarmSnapshot() =
            replyThreadAccessSnapshot(
                parentMessageIdValue = "root-1",
                streamChannelIdValue = "thread-1",
                joined = true,
                canSend = true,
            ).copy(rawMessagePage = RawMessagePageWitness(RawMessagePage(replyPageArray(20, 30, 40), requestedLimit = 30)))

        fun replyRoute(): RaftThreadScreenPayload =
            RaftThreadScreenPayload(
                instanceId = "shared-shell-thread-thread-1",
                title = "Thread",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                sourceThreadId = "thread-1",
                streamChannelId = "thread-1",
                parentChannelId = "channel-1",
                parentMessageId = "root-1",
                originParentMessageId = "root-1",
                status = "loading",
                canSend = true,
            )
    }
}
