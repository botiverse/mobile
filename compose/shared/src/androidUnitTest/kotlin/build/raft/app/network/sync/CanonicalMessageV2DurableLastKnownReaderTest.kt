package build.raft.app.network.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.data.RaftSqlDatabase
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task #1123 read entry against the real SQLite schema: the reader returns exactly what the V2
 * writer committed for the caller's own partition, and nothing in every other case.
 */
class CanonicalMessageV2DurableLastKnownReaderTest {
    @Test
    fun readsBackTheCommittedTopLevelBaselineForTheSamePartition() = withFreshDatabase { database ->
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L })
        val scope = SyncScopeKey(partitionA, "channel-a", "channel")
        val cursor = PersistedCursor(3, 1, 3, hasLoadedOldest = true, epoch = "epoch-v2")
        val logs = mutableListOf<String>()
        val reader = CanonicalMessageV2DurableLastKnownReader(store, isEnabledForServer = { it == "server-a" }, logger = logs::add)

        runBlocking {
            store.applySnapshot(CanonicalMessageV2SnapshotFrame(scope, listOf(envelope(1), envelope(3)), cursor))
            val frame = assertNotNull(reader.read(partitionA, "channel-a"))
            assertEquals(scope, frame.scope)
            assertEquals(listOf("message-1", "message-3"), frame.envelopes.map { it.fact.id })
            assertEquals(cursor, frame.cursor)
        }
        assertTrue(logs.isEmpty(), logs.toString())
    }

    @Test
    fun anotherPrincipalOnTheSameServerReadsNothing() = withFreshDatabase { database ->
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L })
        val reader = CanonicalMessageV2DurableLastKnownReader(store, isEnabledForServer = { true })
        runBlocking {
            store.applySnapshot(CanonicalMessageV2SnapshotFrame(SyncScopeKey(partitionA, "channel-a", "channel"), listOf(envelope(1)), cursor()))
            // Positive control: the writer's own partition sees the frame.
            assertNotNull(reader.read(partitionA, "channel-a"))
            assertNull(reader.read(partitionA.copy(principalId = "principal-b"), "channel-a"))
            assertNull(reader.read(partitionA.copy(environmentId = "env-other"), "channel-a"))
            assertNull(reader.read(partitionA, "channel-b"))
        }
    }

    @Test
    fun serverFlagOffReadsNothingEvenWithACommittedBaseline() = withFreshDatabase { database ->
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L })
        runBlocking {
            store.applySnapshot(CanonicalMessageV2SnapshotFrame(SyncScopeKey(partitionA, "channel-a", "channel"), listOf(envelope(1)), cursor()))
            assertNotNull(CanonicalMessageV2DurableLastKnownReader(store, isEnabledForServer = { true }).read(partitionA, "channel-a"))
            assertNull(CanonicalMessageV2DurableLastKnownReader(store, isEnabledForServer = { false }).read(partitionA, "channel-a"))
        }
    }

    @Test
    fun emptyBaselineReadsAnEmptyFrameAndReplyThreadBaselineReadsNothing() = withFreshDatabase { database ->
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L })
        val reader = CanonicalMessageV2DurableLastKnownReader(store, isEnabledForServer = { true })
        runBlocking {
            // Negative control: no baseline at all is still no frame.
            assertNull(reader.read(partitionA, "channel-a"))
            // Task #1137: a committed baseline with zero facts is the answer "empty channel", not a miss.
            store.applySnapshot(CanonicalMessageV2SnapshotFrame(SyncScopeKey(partitionA, "channel-a", "channel"), emptyList(), cursor(latest = null)))
            val empty = assertNotNull(reader.read(partitionA, "channel-a"))
            assertEquals("channel", empty.scope.scopeKind)
            assertTrue(empty.envelopes.isEmpty())
            assertNull(empty.cursor.latestSeq)

            // Reply-thread scopes are keyed by the thread id and are not this reader's surface.
            val threadScope = SyncScopeKey(partitionA, "thread-a", "thread")
            val reply = CanonicalMessageEnvelopeV2(
                fact = fact(7),
                context = CanonicalConversationContextV2.Thread(
                    replyScope = ReplyScopeRefV2("server-a", "thread-a"),
                    parentTopLevelScope = TopLevelScopeRefV2.Channel("server-a", "channel-a"),
                    rootMessageRef = MessageRefV2("server-a", "message-root"),
                ),
                replyMembership = ReplyMembershipV2(
                    root = MessageRefV2("server-a", "message-root"),
                    parentScope = ReplyScopeRefV2("server-a", "thread-a"),
                ),
            )
            store.applySnapshot(
                CanonicalMessageV2SnapshotFrame(
                    threadScope,
                    listOf(reply),
                    cursor().copy(parentScopeKind = "channel", parentScopeId = "channel-a", rootMessageId = "message-root"),
                ),
            )
            assertEquals(listOf("thread"), store.baselinedScopeKinds(partitionA, "thread-a"))
            assertNull(reader.read(partitionA, "thread-a"))
        }
    }

    @Test
    fun twoTopLevelKindsUnderOneIdAreAmbiguousAndReadNothing() = withFreshDatabase { database ->
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L })
        val logs = mutableListOf<String>()
        val reader = CanonicalMessageV2DurableLastKnownReader(store, isEnabledForServer = { true }, logger = logs::add)
        runBlocking {
            store.applySnapshot(CanonicalMessageV2SnapshotFrame(SyncScopeKey(partitionA, "channel-a", "channel"), listOf(envelope(1)), cursor()))
            // Positive control: one committed kind reads back.
            assertNotNull(reader.read(partitionA, "channel-a"))
            assertTrue(logs.isEmpty(), logs.toString())

            // The current writer never commits two top-level kinds for one id; if it ever did, the
            // reader must refuse to guess which one the route means.
            val privateEnvelope = CanonicalMessageEnvelopeV2(
                fact = fact(2),
                context = CanonicalConversationContextV2.TopLevel(TopLevelScopeRefV2.Private("server-a", "channel-a")),
            )
            store.applySnapshot(CanonicalMessageV2SnapshotFrame(SyncScopeKey(partitionA, "channel-a", "private"), listOf(privateEnvelope), cursor(latest = 2)))
            assertEquals(setOf("channel", "private"), store.baselinedScopeKinds(partitionA, "channel-a").toSet())
            assertNull(reader.read(partitionA, "channel-a"))
        }
        val ambiguous = assertNotNull(logs.singleOrNull(), logs.toString())
        assertTrue("outcome=ambiguous_scope_kind" in ambiguous, ambiguous)
        assertTrue("scope=channel-a" in ambiguous, ambiguous)
    }

    @Test
    fun poisonedScopeReadsNothingAndLogsInsteadOfThrowing() = withFreshDatabase { database ->
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L })
        val scope = SyncScopeKey(partitionA, "channel-a", "channel")
        val logs = mutableListOf<String>()
        val reader = CanonicalMessageV2DurableLastKnownReader(store, isEnabledForServer = { true }, logger = logs::add)
        runBlocking {
            store.applySnapshot(CanonicalMessageV2SnapshotFrame(scope, listOf(envelope(1)), cursor()))
            database.queries.updateCanonicalFact(
                seq = 1,
                createdAt = "2026-07-16T00:00:00.000Z",
                canonicalFactJson = "malformed",
                id = "message-1",
                environmentId = scope.environmentId,
                serverId = scope.serverId,
                principalId = scope.principalId,
                consumerId = scope.consumerId,
                scopeKind = scope.scopeKind,
                channelId = scope.scopeId,
            )
            assertFailsWith<CanonicalMessageV2ScopePoisoned> { store.restore(scope) }
            assertNull(reader.read(partitionA, "channel-a"))
        }
        assertEquals(1, logs.size, logs.toString())
        assertTrue(logs.single().contains("outcome=poisoned"), logs.single())
    }

    @Test
    fun readerRejectsV1PartitionAndUntrimmedChannelId() = withFreshDatabase { database ->
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L })
        val reader = CanonicalMessageV2DurableLastKnownReader(store, isEnabledForServer = { true })
        runBlocking {
            assertFailsWith<IllegalArgumentException> {
                reader.read(partitionA.copy(consumerId = SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID), "channel-a")
            }
            assertNull(reader.read(partitionA, " channel-a"))
            assertNull(reader.read(partitionA, ""))
        }
    }

    private val partitionA = DurablePartitionKey("server-a", "principal-a", SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID, "env-test")

    private fun cursor(latest: Long? = 1) = PersistedCursor(latest, latest, latest, hasLoadedOldest = true, epoch = "epoch-v2")

    private fun fact(seq: Long) = CanonicalMessageNodeV2(
        id = "message-$seq",
        seq = seq,
        senderType = "user",
        senderId = "human-a",
        content = "body $seq",
        messageType = "chat",
        createdAt = "2026-07-16T00:00:00.000Z",
        senderName = "Human",
    )

    private fun envelope(seq: Long) = CanonicalMessageEnvelopeV2(
        fact = fact(seq),
        context = CanonicalConversationContextV2.TopLevel(TopLevelScopeRefV2.Channel("server-a", "channel-a")),
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
