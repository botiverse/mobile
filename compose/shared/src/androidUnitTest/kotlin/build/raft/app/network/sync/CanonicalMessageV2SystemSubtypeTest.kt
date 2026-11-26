package build.raft.app.network.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.canonical.message.CanonicalMessageRow
import build.raft.app.data.RaftSqlDatabase
import build.raft.app.data.WORKER_ENDED_RECORD_SUBTYPE
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanonicalMessageV2SystemSubtypeTest {
    private val partitionA = DurablePartitionKey("server-a", "principal-a", SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID, "env-test")
    private val scope = SyncScopeKey(partitionA, "channel-a", "channel")

    private fun cursor(latest: Long) = PersistedCursor(latest, 1, latest, hasLoadedOldest = true, epoch = "epoch-v2")

    private fun fact(seq: Long, systemSubtype: String? = null) = CanonicalMessageNodeV2(
        id = "message-$seq",
        seq = seq,
        senderType = "user",
        senderId = "system",
        content = "Worker w3 of @Sage finished (ran 12m): Done.",
        messageType = "system",
        createdAt = "2026-11-26T08:00:00.000Z",
        senderName = "System",
        systemSubtype = systemSubtype,
    )

    private fun envelope(fact: CanonicalMessageNodeV2) = CanonicalMessageEnvelopeV2(
        fact = fact,
        context = CanonicalConversationContextV2.TopLevel(TopLevelScopeRefV2.Channel("server-a", "channel-a")),
    )

    @Test
    fun systemSubtypeRoundTripsAndNullOmitsKey() {
        val withSubtypeBytes = encodeCanonicalMessageV2(envelope(fact(1, WORKER_ENDED_RECORD_SUBTYPE)), "server-a")
        assertTrue(withSubtypeBytes.contains(""""systemSubtype":"worker.ended""""))
        val decoded = assertIs<CanonicalMessageV2DecodeResult.Accepted>(decodeCanonicalMessageV2(withSubtypeBytes, "server-a"))
        assertEquals(WORKER_ENDED_RECORD_SUBTYPE, decoded.envelope.fact.systemSubtype)

        // Backward-compatible omission when null.
        val withoutSubtypeBytes = encodeCanonicalMessageV2(envelope(fact(2, null)), "server-a")
        assertFalse(withoutSubtypeBytes.contains(""""systemSubtype""""), "null systemSubtype must be omitted from canonical bytes")
        val decodedLegacy = assertIs<CanonicalMessageV2DecodeResult.Accepted>(decodeCanonicalMessageV2(withoutSubtypeBytes, "server-a"))
        assertNull(decodedLegacy.envelope.fact.systemSubtype)
    }

    @Test
    fun nullBlankUntrimmedOrNonStringSystemSubtypeIsRejected() {
        val baseBytes = encodeCanonicalMessageV2(envelope(fact(1, null)), "server-a")
        listOf("null", "\"\"", "\"   \"", "\" worker.ended\"", "\"worker.ended \"", "42", "true").forEach { value ->
            val tampered = baseBytes.replace(""","replyCount":0}""", ""","replyCount":0,"systemSubtype":$value}""")
            assertEquals(
                CanonicalMessageV2DecodeResult.Rejected(CANONICAL_MESSAGE_V2_EXACT_KEYS),
                decodeCanonicalMessageV2(tampered, "server-a"),
                "systemSubtype=$value",
            )
        }
        assertEquals(
            CANONICAL_MESSAGE_V2_EXACT_KEYS,
            validateCanonicalMessageV2(envelope(fact(1, "  ")), "server-a"),
        )
    }

    @Test
    fun systemSubtypeAndDeletedAtCoexistInCanonicalFact() {
        val both = fact(1, WORKER_ENDED_RECORD_SUBTYPE).copy(deletedAt = "2026-11-26T09:00:00.000Z")
        val bytes = encodeCanonicalMessageV2(envelope(both), "server-a")
        val decoded = assertIs<CanonicalMessageV2DecodeResult.Accepted>(decodeCanonicalMessageV2(bytes, "server-a"))
        assertEquals(WORKER_ENDED_RECORD_SUBTYPE, decoded.envelope.fact.systemSubtype)
        assertEquals("2026-11-26T09:00:00.000Z", decoded.envelope.fact.deletedAt)
    }

    @Test
    fun wireDecoderAndV1CodecAndRowProjectionPreserveSystemSubtype() {
        val raw =
            Json.parseToJsonElement(
                """
                {
                  "id": "m-ended",
                  "seq": 9,
                  "channelId": "channel-a",
                  "senderType": "user",
                  "senderId": "system",
                  "senderName": "System",
                  "messageType": "system",
                  "systemSubtype": "worker.ended",
                  "content": "Worker w1 of @Sage finished (ran 5m): All set.",
                  "createdAt": "2026-11-26T08:00:00.000Z",
                  "randomId": null,
                  "threadId": null,
                  "mentions": [],
                  "attachments": [],
                  "reactions": [],
                  "conversationContext": { "channelType": "channel" }
                }
                """.trimIndent(),
            ).jsonObject
        val carry = assertNotNull(decodeCanonicalMessageWire(raw, ProductionFace.MessageNew))
        assertEquals(WORKER_ENDED_RECORD_SUBTYPE, carry.row.systemSubtype)

        // V1 persisted BLOB round-trip.
        val v1Bytes = encodeCanonicalFact(carry.row)
        assertTrue(v1Bytes.contains(""""systemSubtype":"worker.ended""""))
        val restoredRow: CanonicalMessageRow = assertNotNull(decodeCanonicalFact(v1Bytes))
        assertEquals(WORKER_ENDED_RECORD_SUBTYPE, restoredRow.systemSubtype)

        // App Message projection preserves systemSubtype.
        val appMessage = assertNotNull(restoredRow.toAppMessage())
        assertEquals(WORKER_ENDED_RECORD_SUBTYPE, appMessage.systemSubtype)

        // Omitted when null in V1 BLOB.
        val v1NullBytes = encodeCanonicalFact(carry.row.copy(systemSubtype = null))
        assertFalse(v1NullBytes.contains(""""systemSubtype""""))
        assertNull(assertNotNull(decodeCanonicalFact(v1NullBytes)).systemSubtype)
    }

    @Test
    fun durableStorePreservesSystemSubtypeOneWayAndProjectsToThreadItem() = withFreshDatabase { database ->
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L })
        val reader = CanonicalMessageV2DurableLastKnownReader(store)
        runBlocking {
            store.applySnapshot(
                CanonicalMessageV2SnapshotFrame(scope, listOf(envelope(fact(1, WORKER_ENDED_RECORD_SUBTYPE))), cursor(1)),
            )
            // Narrower incremental update omitting systemSubtype must not clear the stored subtype.
            store.applyIncremental(
                CanonicalMessageV2IncrementalFrame(scope, listOf(envelope(fact(1, null).copy(replyCount = 1))), cursor(1)),
            )
            val stored = assertNotNull(reader.read(partitionA, "channel-a")).envelopes.single()
            assertEquals(WORKER_ENDED_RECORD_SUBTYPE, stored.fact.systemSubtype)
            assertEquals(1, stored.fact.replyCount)
            val threadItem = assertNotNull(stored.toThreadMessageItem(currentUserId = null))
            assertEquals(WORKER_ENDED_RECORD_SUBTYPE, threadItem.systemSubtype)
        }
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
