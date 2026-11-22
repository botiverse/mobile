package build.raft.app.network.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import build.raft.app.V2ActionCardPresentation
import build.raft.app.assertRestoredActionCardPresentation
import build.raft.app.data.RaftSqlDatabase
import build.raft.app.v2ActionCardWithPresentation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun aDurableCardRestoresWithItsPresentationAndANarrowerCopyNeverTakesItAway() = withFreshDatabase { database ->
        val store = SqlDelightCanonicalMessageV2Store(database = database, now = { 1L })
        val reader = CanonicalMessageV2DurableLastKnownReader(store)
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
        val reader = CanonicalMessageV2DurableLastKnownReader(store)
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

    private fun card(confirmationVersion: Int?) =
        v2ActionCardWithPresentation().copy(sourceServerId = "server-a", confirmationVersion = confirmationVersion)

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
