package build.raft.app.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.network.sync.CanonicalMessageV2BaselineCoordinator
import build.raft.app.network.sync.CanonicalMessageV2DurableLastKnownReader
import build.raft.app.network.sync.CanonicalMessageV2GenerationEngine
import build.raft.app.network.sync.CanonicalMessageV2ProductionCanary
import build.raft.app.network.sync.CanonicalMessageV2SnapshotCommitObserver
import build.raft.app.network.sync.SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID
import build.raft.app.network.sync.SqlDelightCanonicalMessageV2Store
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Task #1124 ⇄ #1123 on the real SQLite schema: what the enter-snapshot loader commits for one
 * channel is exactly what the offline reader hands back for the same partition — and nothing for
 * any other partition.
 */
class CanonicalMessageV2EnteredScopeRoundTripTest {
    @Test
    fun enteredChannelPageIsReadableOfflineForTheSamePartitionOnly() = withFreshDatabase { database ->
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L })
        val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        val loader = CanonicalMessageV2EnteredScopeLoader(
            canary = CanonicalMessageV2ProductionCanary(isEnabledForServer = { true }, generationEngine = engine),
            generationEngine = engine,
            fetcher = CanonicalMessageV2RestSnapshotFetcher(
                RecordingNetworkClient(
                    canonicalV2TestThreadParentPage(),
                    threadSummariesBody = canonicalV2TestThreadSummaries(replyCount = 4),
                ),
            ),
            isEnabledForServer = { true },
        )
        val reader = CanonicalMessageV2DurableLastKnownReader(store, isEnabledForServer = { true })

        runBlocking {
            assertNull(reader.read(partition, "channel-1"), "nothing on disk before the first entry")
            loader.loadOnEnter(partition, "channel-1") { true }

            val frame = assertNotNull(reader.read(partition, "channel-1"))
            assertEquals("channel", frame.scope.scopeKind)
            assertEquals(listOf("message-1"), frame.envelopes.map { it.fact.id })
            assertEquals(4, frame.envelopes.single().fact.replyCount)
            assertEquals(7L, frame.cursor.latestSeq)
            assertEquals(true, frame.cursor.hasLoadedNewest)

            assertNull(reader.read(partition.copy(principalId = "user-2"), "channel-1"))
            assertNull(reader.read(partition.copy(environmentId = "env-other"), "channel-1"))
            assertNull(reader.read(partition, "channel-2"))
        }
    }

    @Test
    fun directMessageKindResolvedFromTheServerIsWhatTheReaderSees() = withFreshDatabase { database ->
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L })
        val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        val loader = CanonicalMessageV2EnteredScopeLoader(
            canary = CanonicalMessageV2ProductionCanary(isEnabledForServer = { true }, generationEngine = engine),
            generationEngine = engine,
            fetcher = CanonicalMessageV2RestSnapshotFetcher(
                RecordingNetworkClient(canonicalV2TestPage(channelId = "dm-1"), channelIds = setOf("dm-1"), channelType = "dm"),
            ),
            isEnabledForServer = { true },
        )
        runBlocking {
            loader.loadOnEnter(partition, "dm-1") { true }
            val frame = assertNotNull(CanonicalMessageV2DurableLastKnownReader(store, { true }).read(partition, "dm-1"))
            assertEquals("dm", frame.scope.scopeKind)
            assertEquals(listOf("message-1"), frame.envelopes.map { it.fact.id })
        }
    }

    @Test
    fun emptyChannelIsReadableOfflineAsEmptyAndALaterPageReplacesIt() = withFreshDatabase { database ->
        // Task #1137 on the real schema: an empty page is committed, read back as an empty frame, and a
        // later entry with messages rebaselines it.
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L })
        val engine = CanonicalMessageV2GenerationEngine(
            coordinator = CanonicalMessageV2BaselineCoordinator(),
            store = store,
            snapshotCommitObserver = CanonicalMessageV2SnapshotCommitObserver {},
        )
        fun loaderFor(pageBody: String) = CanonicalMessageV2EnteredScopeLoader(
            canary = CanonicalMessageV2ProductionCanary(isEnabledForServer = { true }, generationEngine = engine),
            generationEngine = engine,
            fetcher = CanonicalMessageV2RestSnapshotFetcher(RecordingNetworkClient(pageBody)),
            isEnabledForServer = { true },
        )
        val reader = CanonicalMessageV2DurableLastKnownReader(store, isEnabledForServer = { true })

        runBlocking {
            loaderFor("""{"messages":[],"historyLimited":false}""").loadOnEnter(partition, "channel-1") { true }
            val empty = assertNotNull(reader.read(partition, "channel-1"), "an empty channel is a frame, not a miss")
            assertEquals("channel", empty.scope.scopeKind)
            assertEquals(emptyList(), empty.envelopes)
            assertNull(empty.cursor.latestSeq)
            assertEquals(true, empty.cursor.hasLoadedOldest)
            assertEquals(true, empty.cursor.hasLoadedNewest)
            assertNull(reader.read(partition.copy(principalId = "user-2"), "channel-1"), "still partition-strict")

            loaderFor(canonicalV2TestPage()).loadOnEnter(partition, "channel-1") { true }
            val filled = assertNotNull(reader.read(partition, "channel-1"))
            assertEquals(listOf("message-1"), filled.envelopes.map { it.fact.id })
            assertEquals(7L, filled.cursor.latestSeq)

            // And the reverse: a channel whose messages are all gone reads empty again, not the old facts.
            loaderFor("""{"messages":[],"historyLimited":false}""").loadOnEnter(partition, "channel-1") { true }
            assertEquals(emptyList(), assertNotNull(reader.read(partition, "channel-1")).envelopes)
        }
    }

    private val partition = canonicalV2TestGrant().durablePartition(SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID)

    private fun withFreshDatabase(block: (SlockSqlDatabase) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            SlockSqlDatabase.Schema.create(driver).value
            block(SlockSqlDatabase(driver))
        } finally {
            driver.close()
        }
    }
}
