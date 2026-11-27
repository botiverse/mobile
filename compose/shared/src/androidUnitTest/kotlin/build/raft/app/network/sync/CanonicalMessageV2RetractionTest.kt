package build.raft.app.network.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.data.RaftSqlDatabase
import build.raft.app.thread.isRetracted
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RFC 077 retraction in the canonical message contract (mirror of slock 704d69f4): `deletedAt` is an
 * additive optional `$.fact` key, present only for a retracted message, and one-way in every merge.
 */
class CanonicalMessageV2RetractionTest {
    private val retractedAt = "2026-10-20T08:15:30.123Z"

    @Test
    fun aNullEmptyOrMalformedDeletedAtIsNeverCanonical() {
        val liveBytes = encodeCanonicalMessageV2(envelope(fact(1)), "server-a")
        listOf("null", "\"\"", "\" $retractedAt\"", "\"not-a-time\"", "0").forEach { value ->
            val tampered = liveBytes.replace(""","replyCount":0}""", ""","replyCount":0,"deletedAt":$value}""")
            assertEquals(
                CanonicalMessageV2DecodeResult.Rejected(CANONICAL_MESSAGE_V2_EXACT_KEYS),
                decodeCanonicalMessageV2(tampered, "server-a"),
                "deletedAt=$value",
            )
        }
        assertNull(validateCanonicalMessageV2(envelope(fact(1).copy(deletedAt = retractedAt)), "server-a"))
        assertEquals(CANONICAL_MESSAGE_V2_EXACT_KEYS, validateCanonicalMessageV2(envelope(fact(1).copy(deletedAt = "")), "server-a"))
    }

    @Test
    fun aDurableRetractedFrameRestoresAsAFlaggedThreadItemWithoutTakenBackContent() = withFreshDatabase { database ->
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L })
        val reader = CanonicalMessageV2DurableLastKnownReader(store)
        val retracted = fact(1).copy(
            deletedAt = retractedAt,
            content = "This message was retracted.",
            // Stored bytes carrying content is not expected from the server, but must still not render.
            attachments = listOf(CanonicalAttachmentV2("a1", "secret.pdf", "application/pdf", 10)),
            reactions = listOf(ReactionFact("👀", 2)),
        )
        runBlocking {
            store.applySnapshot(CanonicalMessageV2SnapshotFrame(scope, listOf(envelope(retracted), envelope(fact(2))), cursor(2)))
            val frame = assertNotNull(reader.read(partitionA, "channel-a"))
            val items = frame.envelopes.map { assertNotNull(it.toThreadMessageItem(currentUserId = null)) }
            val item = items.single { it.id == "message-1" }
            assertTrue(item.isRetracted)
            assertEquals(retractedAt, item.deletedAt)
            assertTrue(item.attachments.isEmpty())
            assertTrue(item.reactions.isEmpty())
            assertNull(item.forwardBundle)
            // Positive control: the live neighbour keeps everything.
            assertFalse(items.single { it.id == "message-2" }.isRetracted)
        }
    }

    @Test
    fun anOlderFrameWithoutDeletedAtNeverUnRetractsAStoredFact() = withFreshDatabase { database ->
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L })
        val reader = CanonicalMessageV2DurableLastKnownReader(store)
        val placeholder = "This message was retracted."
        runBlocking {
            store.applySnapshot(
                CanonicalMessageV2SnapshotFrame(scope, listOf(envelope(fact(1).copy(deletedAt = retractedAt, content = placeholder))), cursor(1)),
            )
            // A stale catch-up / page copy from before the retraction.
            val stale = fact(1).copy(
                content = "old words",
                attachments = listOf(CanonicalAttachmentV2("a1", "old.png", "image/png", 10)),
                reactions = listOf(ReactionFact("🔥", 1)),
                replyCount = 2,
            )
            store.applyIncremental(CanonicalMessageV2IncrementalFrame(scope, listOf(envelope(stale)), cursor(1)))
            val kept = assertNotNull(reader.read(partitionA, "channel-a")).envelopes.single().fact
            assertEquals(retractedAt, kept.deletedAt)
            assertEquals(placeholder, kept.content)
            assertTrue(kept.attachments.isEmpty())
            assertTrue(kept.reactions.isEmpty())
            assertEquals(2, kept.replyCount, "non-retraction facts still flow")

            // A fresh snapshot without the key does not clear it either.
            store.applySnapshot(CanonicalMessageV2SnapshotFrame(scope, listOf(envelope(stale)), cursor(1)))
            assertEquals(retractedAt, assertNotNull(reader.read(partitionA, "channel-a")).envelopes.single().fact.deletedAt)
        }
    }

    @Test
    fun aRetractionFrameRetractsAStoredLiveFact() = withFreshDatabase { database ->
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L })
        val reader = CanonicalMessageV2DurableLastKnownReader(store)
        runBlocking {
            store.applySnapshot(CanonicalMessageV2SnapshotFrame(scope, listOf(envelope(fact(1))), cursor(1)))
            assertNull(assertNotNull(reader.read(partitionA, "channel-a")).envelopes.single().fact.deletedAt)
            store.applyIncremental(
                CanonicalMessageV2IncrementalFrame(scope, listOf(envelope(fact(1).copy(deletedAt = retractedAt))), cursor(1)),
            )
            assertEquals(retractedAt, assertNotNull(reader.read(partitionA, "channel-a")).envelopes.single().fact.deletedAt)
        }
    }

    private val partitionA = DurablePartitionKey("server-a", "principal-a", SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID, "env-test")
    private val scope = SyncScopeKey(partitionA, "channel-a", "channel")

    private fun cursor(latest: Long) = PersistedCursor(latest, 1, latest, hasLoadedOldest = true, epoch = "epoch-v2")

    private fun fact(seq: Long) = CanonicalMessageNodeV2(
        id = "message-$seq",
        seq = seq,
        senderType = "agent",
        senderId = "agent-a",
        content = "body $seq",
        messageType = "chat",
        createdAt = "2026-10-20T08:00:00.000Z",
        senderName = "Friday",
    )

    private fun envelope(fact: CanonicalMessageNodeV2) = CanonicalMessageEnvelopeV2(
        fact = fact,
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
