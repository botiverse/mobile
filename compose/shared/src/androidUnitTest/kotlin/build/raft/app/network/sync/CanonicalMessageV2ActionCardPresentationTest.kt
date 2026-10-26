package build.raft.app.network.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.V2ActionCardPresentation
import build.raft.app.assertRestoredActionCardPresentation
import build.raft.app.canonical.message.MessageAggregateField
import build.raft.app.data.RaftSqlDatabase
import build.raft.app.v2ActionCardWithPresentation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task #42: an action card in the durable V2 fact keeps what the REST card has: the server presentation
 * (title, labels, warning, display items, generic approval, `card`), sourceServerId, targetServerName and
 * confirmationVersion. They are additive optional keys (App manifest v8), one-way in the store.
 */
class CanonicalMessageV2ActionCardPresentationTest {
    @Test
    fun aCurrentServerCardConvertsIntoAFullV2FactWithItsPresentation() {
        val carry = assertNotNull(decodeCanonicalMessageWire(wire(currentServerCard), ProductionFace.EnrichedUpdated))
        val conversion = CanonicalMessageFoldInput(CanonicalMessageFoldKind.Updated, carry.row, MessageAggregateField.entries.toList())
            .toCanonicalMessageV2Conversion(partitionA, replyCount = 0)
        val card = assertIs<CanonicalActionMetadataV2.ActionCard>(
            assertIs<CanonicalMessageV2EnvelopeConversion.Converted>(conversion, conversion.toString()).envelope.fact.actionMetadata,
        )
        assertEquals("server-a", card.sourceServerId)
        assertEquals("Raft HQ", card.targetServerName)
        assertEquals(3, card.confirmationVersion)
        assertEquals(V2ActionCardPresentation, card.presentation)
        assertNotNull(card.presentation!!["card"], "presentation.card (action-card.v1) is kept")

        // The user pointer id and the conversion bookkeeping are admitted but never stored.
        val bytes = encodeCanonicalMessageV2(conversion.envelope, "server-a")
        listOf("executedByUserId", "conversionJobId", "conversionEpoch", "reconfirmedAt", "user-secret", "job-1").forEach {
            assertFalse(it in bytes, "$it in $bytes")
        }
        // Twin: an unknown root key still refuses the card as not full V2.
        val unknown = JsonObject(currentServerCard + ("somethingNew" to Json.parseToJsonElement("1")))
        val refused = CanonicalMessageFoldInput(
            CanonicalMessageFoldKind.Updated,
            assertNotNull(decodeCanonicalMessageWire(wire(unknown), ProductionFace.EnrichedUpdated)).row,
            MessageAggregateField.entries.toList(),
        ).toCanonicalMessageV2Conversion(partitionA, replyCount = 0)
        assertIs<CanonicalMessageV2EnvelopeConversion.NotFull>(refused)
    }

    @Test
    fun codecRoundTripsTheOptionalKeysAndKeepsABareCardsBytes() {
        val bare = envelope(1, v2ActionCardWithPresentation().copy(sourceServerId = null, targetServerName = null, confirmationVersion = null, presentation = null))
        val bareBytes = encodeCanonicalMessageV2(bare, "server-a")
        listOf("sourceServerId", "targetServerName", "confirmationVersion", "presentation").forEach {
            assertFalse("\"$it\"" in bareBytes, "a card without $it never carries the key (not even as null)")
        }
        assertEquals(bare, assertIs<CanonicalMessageV2DecodeResult.Accepted>(decodeCanonicalMessageV2(bareBytes, "server-a")).envelope)

        val full = envelope(1, v2ActionCardWithPresentation().copy(sourceServerId = "server-b"))
        val bytes = encodeCanonicalMessageV2(full, "server-a")
        val decoded = assertIs<CanonicalMessageV2DecodeResult.Accepted>(decodeCanonicalMessageV2(bytes, "server-a"))
        assertEquals(full, decoded.envelope, "a joint card's own server is not an embedded cross-server ref")
        assertEquals(bytes, encodeCanonicalMessageV2(decoded.envelope, "server-a"))

        listOf(
            "\"presentation\":null", "\"presentation\":\"x\"", "\"confirmationVersion\":null", "\"sourceServerId\":null",
            "\"sourceServerId\":\" a\"", "\"confirmationVersion\":-1", "\"unknownKey\":1",
        ).forEach { extra ->
            val tampered = bareBytes.replace("\"kind\":\"action-card\",", "\"kind\":\"action-card\",$extra,")
            assertIs<CanonicalMessageV2DecodeResult.Rejected>(decodeCanonicalMessageV2(tampered, "server-a"), extra)
        }
    }

    @Test
    fun aDurableCardRestoresWithItsPresentationAndANarrowerCopyNeverTakesItAway() = withFreshDatabase { database ->
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L })
        val reader = CanonicalMessageV2DurableLastKnownReader(store, isEnabledForServer = { true })
        suspend fun storedCard() = assertIs<CanonicalActionMetadataV2.ActionCard>(
            assertNotNull(reader.read(partitionA, "channel-a")).envelopes.single().fact.actionMetadata,
        )
        runBlocking {
            store.applySnapshot(CanonicalMessageV2SnapshotFrame(scope, listOf(envelope(1, card(confirmationVersion = 3))), cursor(1)))
            val restored = assertNotNull(reader.read(partitionA, "channel-a")).envelopes.single()
            assertRestoredActionCardPresentation(
                assertNotNull(restored.toThreadMessageItem(currentUserId = null)).metadata,
                sourceServerId = "server-a",
            )
            assertEquals(V2ActionCardPresentation, (restored.fact.actionMetadata as CanonicalActionMetadataV2.ActionCard).presentation)

            // A copy without the optional keys (and an older credential) keeps the stored ones; state still flows.
            val narrower = card(confirmationVersion = 2).copy(
                state = "executed",
                sourceServerId = null,
                targetServerName = null,
                presentation = null,
            )
            store.applyIncremental(CanonicalMessageV2IncrementalFrame(scope, listOf(envelope(1, narrower)), cursor(1)))
            storedCard().let {
                assertEquals("executed", it.state)
                assertEquals(3, it.confirmationVersion, "the credential never goes down")
                assertEquals("server-a", it.sourceServerId)
                assertEquals("Raft HQ", it.targetServerName)
                assertEquals(V2ActionCardPresentation, it.presentation)
            }
            // A snapshot without them does not clear them either.
            store.applySnapshot(CanonicalMessageV2SnapshotFrame(scope, listOf(envelope(1, narrower.copy(confirmationVersion = null))), cursor(1)))
            assertEquals(3, storedCard().confirmationVersion)
            assertEquals(V2ActionCardPresentation, storedCard().presentation)

            // A newer credential and a rebuilt presentation do replace them.
            val rebuilt = JsonObject(V2ActionCardPresentation + ("title" to Json.parseToJsonElement("\"Created #launch\"")))
            store.applyIncremental(
                CanonicalMessageV2IncrementalFrame(scope, listOf(envelope(1, card(confirmationVersion = 4).copy(presentation = rebuilt))), cursor(1)),
            )
            assertEquals(4, storedCard().confirmationVersion)
            assertEquals(rebuilt, storedCard().presentation)
        }
    }

    @Test
    fun aCardStoredWithoutTheKeysStillDecodesAndRendersTheLegacyFallback() = withFreshDatabase { database ->
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L })
        val reader = CanonicalMessageV2DurableLastKnownReader(store, isEnabledForServer = { true })
        runBlocking {
            val bare = card(confirmationVersion = null).copy(sourceServerId = null, targetServerName = null, presentation = null)
            store.applySnapshot(CanonicalMessageV2SnapshotFrame(scope, listOf(envelope(1, bare)), cursor(1)))
            val item = assertNotNull(assertNotNull(reader.read(partitionA, "channel-a")).envelopes.single().toThreadMessageItem(null))
            val metadata = item.metadata
            assertEquals("action-card", metadata["actionCard.kind"])
            assertNull(metadata["actionCard.presentationTitle"])
            assertTrue(metadata["actionCard.genericApprovalAllowed"] != "true")
        }
    }

    private val currentServerCard: JsonObject = Json.parseToJsonElement(
        """{"kind":"action-card","sourceServerId":"server-a","targetServerName":"Raft HQ",""" +
            """"action":{"type":"channel:create","name":"launch"},"presentation":$V2ActionCardPresentation,""" +
            """"state":"prepared","executedAt":null,"executedByUserId":"user-secret","executedByUserName":null,""" +
            """"result":null,"confirmationVersion":3,"conversionJobId":"job-1","conversionEpoch":"e1","reconfirmedAt":null}""",
    ).jsonObject

    private fun card(confirmationVersion: Int?) =
        v2ActionCardWithPresentation().copy(sourceServerId = "server-a", confirmationVersion = confirmationVersion)

    private fun wire(actionMetadata: JsonObject): JsonObject = Json.parseToJsonElement(
        """{"id":"message-1","channelId":"channel-a","seq":1,"senderType":"agent","senderId":"agent-a",""" +
            """"content":"Create #launch?","messageType":"chat","randomId":null,"threadId":null,""" +
            """"createdAt":"2026-10-20T08:00:00.000Z","senderName":"Friday","senderDescription":null,""" +
            """"senderMembershipStatus":null,"mentions":[],"attachments":[],"reactions":[],"commentRef":null,""" +
            """"actionMetadata":$actionMetadata,"conversationContext":{"channelType":"channel"}}""",
    ).jsonObject

    private val partitionA = DurablePartitionKey("server-a", "principal-a", SYNC_CORE_MESSAGES_V2_DURABLE_CONSUMER_ID, "env-test")
    private val scope = SyncScopeKey(partitionA, "channel-a", "channel")

    private fun cursor(latest: Long) = PersistedCursor(latest, 1, latest, hasLoadedOldest = true, epoch = "epoch-v2")

    private fun envelope(seq: Long, actionMetadata: CanonicalActionMetadataV2) = CanonicalMessageEnvelopeV2(
        fact = CanonicalMessageNodeV2(
            id = "message-$seq",
            seq = seq,
            senderType = "agent",
            senderId = "agent-a",
            content = "Create #launch?",
            messageType = "chat",
            createdAt = "2026-10-20T08:00:00.000Z",
            senderName = "Friday",
            actionMetadata = actionMetadata,
        ),
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
