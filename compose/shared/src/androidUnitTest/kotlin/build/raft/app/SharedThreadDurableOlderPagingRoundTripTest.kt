package build.raft.app

import build.raft.app.data.loadOnEnter
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.data.CanonicalMessageV2EnteredScopeLoader
import build.raft.app.data.CanonicalMessageV2RestSnapshotFetcher
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RecordingNetworkClient
import build.raft.app.data.RaftSqlDatabase
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.ThreadReplyCacheIdentitySnapshot
import build.raft.app.network.sync.CanonicalMessageV2BaselineCoordinator
import build.raft.app.network.sync.CanonicalMessageV2DurableLastKnownReader
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SqlDelightCanonicalMessageV2Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Task #168 step 2b on the real SQLite schema: the window the enter snapshot committed is paged
 * older offline through the real V2 reader, and the server flag still gates every read.
 */
class SharedThreadDurableOlderPagingRoundTripTest {
    @Test
    fun offlineStandInPagesThroughTheCommittedWindowAndTheFlagGatesIt() = withFreshDatabase { database ->
        runBlocking {
            var flagOn = true
            val sqlStore = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L })
            val engine = CanonicalMessageV2GenerationEngine(
                coordinator = CanonicalMessageV2BaselineCoordinator(),
                store = sqlStore,
                snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
            )
            val logs = mutableListOf<String>()
            val api = SharedThreadStoreTest.FakeThreadApi(failChannelMessages = true)
            val dataSource =
                SharedThreadDataSource(
                    networkApi = api,
                    sessionStore = InMemoryRaftAuthSessionStore(owner.session),
                    cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                    replyCacheIdentityAuthority = { owner },
                    durableLastKnownReader = CanonicalMessageV2DurableLastKnownReader(sqlStore, isEnabledForServer = { flagOn }),
                    diagnosticsLogger = logs::add,
                )
            val partition = assertNotNull(
                dataSource.captureReplyCacheProducerContext()
                    ?.provenance
                    ?.durableMessagePartitionOrNull(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID),
            )
            // The real enter loader commits 70 messages (seq 1..70) as the window; 70 < 200 ⇒ it
            // reaches the channel's start.
            CanonicalMessageV2EnteredScopeLoader(
                canary = CanonicalMessageV2ProductionCanary(isEnabledForServer = { true }, generationEngine = engine),
                generationEngine = engine,
                fetcher = CanonicalMessageV2RestSnapshotFetcher(RecordingNetworkClient(page((1..70).toList()))),
                isEnabledForServer = { true },
            ).loadOnEnter(partition, "channel-1") { true }

            // Same single-threaded confinement as production (see SharedThreadStoreDurableOlderPagingTest).
            val scopeJob = SupervisorJob()
            val scope = CoroutineScope(coroutineContext + scopeJob)
            val store = SharedThreadStore(dataSource = dataSource, scope = scope)
            store.accept(SharedThreadIntent.Start(basePayload()))
            val standIn = store.state.value.payload
            assertTrue(standIn.durableLastKnown)
            assertEquals((41..70).map { "hello m$it" }, standIn.messages.map { it.body })
            assertTrue(standIn.canLoadMoreBefore)

            store.accept(SharedThreadIntent.LoadOlder)
            assertEquals((11..70).map { "hello m$it" }, store.state.value.payload.messages.map { it.body })
            assertTrue(store.state.value.payload.canLoadMoreBefore)
            assertEquals(1, api.channelMessageCalls, "served from disk, not the network")

            // Flag revoked: the next older load reads nothing from disk and goes to the network.
            flagOn = false
            store.accept(SharedThreadIntent.LoadOlder)
            assertEquals(60, store.state.value.payload.messages.size, "no rows from disk with the flag off")
            assertEquals(2, api.channelMessageCalls)
            assertTrue(logs.any { "thread_durable_older_page" in it && "reason=no_frame" in it }, logs.toString())

            // Twin: flag back on ⇒ the last 10 come from disk and paging closes at the start.
            flagOn = true
            store.accept(SharedThreadIntent.LoadOlder)
            val last = store.state.value.payload
            assertEquals((1..70).map { "hello m$it" }, last.messages.map { it.body })
            assertFalse(last.canLoadMoreBefore)
            assertEquals(2, api.channelMessageCalls)
            scopeJob.cancelAndJoin()
        }
    }

    private val owner =
        ThreadReplyCacheIdentitySnapshot(
            session = StoredAuthSession(
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

    /** A full-v6 REST messages page for channel-1, one node per seq. */
    private fun page(seqs: List<Int>): String =
        seqs.joinToString(",", prefix = """{"messages":[""", postfix = """],"historyLimited":false}""") { seq ->
            val time = "${(seq / 60).toString().padStart(2, '0')}:${(seq % 60).toString().padStart(2, '0')}"
            """{"id":"m$seq","seq":$seq,"channelId":"channel-1","senderType":"user","senderId":"u-a",""" +
                """"content":"hello m$seq","messageType":"chat","randomId":null,"threadId":null,""" +
                """"createdAt":"2026-07-17T00:$time.000Z","senderName":"Owner A","senderDescription":null,""" +
                """"senderMembershipStatus":null,"mentions":[],"attachments":[],"reactions":[],"commentRef":null,"actionMetadata":null}"""
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
}
