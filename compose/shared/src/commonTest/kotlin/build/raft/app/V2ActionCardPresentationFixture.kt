package build.raft.app

import build.raft.app.data.ThreadActionCard
import build.raft.app.data.actionCardFromMessageMetadata
import build.raft.app.network.sync.CanonicalActionCardActionV2
import build.raft.app.network.sync.CanonicalActionMetadataV2
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The server presentation object of a card, including `card` (action-card.v1), as the producer sends it. */
internal val V2ActionCardPresentation: JsonObject = Json.parseToJsonElement(
    """{"title":"Create #launch","confirmLabel":"Create channel","warning":"Everyone in the server can join",""" +
        """"genericApprovalAllowed":true,"riskLevel":"normal",""" +
        """"displayItems":[{"key":"Name","value":"launch"},{"key":"Token","value":"[redacted]","redacted":true}],""" +
        """"card":{"icon":"hash","title":{"key":"card.channelCreate.title"},"spec":{"root":"main","elements":{}}}}""",
).jsonObject

/** A durable V2 action card that carries every field the REST card has. */
internal fun v2ActionCardWithPresentation(confirmationVersion: Int = 3): CanonicalActionMetadataV2.ActionCard =
    CanonicalActionMetadataV2.ActionCard(
        version = 1,
        action = CanonicalActionCardActionV2(type = "channel:create", name = "launch"),
        sourceServerId = "server-1",
        targetServerName = "Raft HQ",
        confirmationVersion = confirmationVersion,
        presentation = V2ActionCardPresentation,
    )

/** The thread item's card shows the server presentation, the generic approval and the credential. */
internal fun assertRestoredActionCardPresentation(
    metadata: Map<String, String>,
    confirmationVersion: Int = 3,
    sourceServerId: String = "server-1",
): ThreadActionCard {
    val card = assertNotNull(actionCardFromMessageMetadata(metadata), "the item still has its card: $metadata")
    assertEquals("channel:create", card.type)
    assertEquals("Create #launch", card.presentationTitle)
    assertEquals("Create channel", card.presentationConfirmLabel)
    assertEquals("Everyone in the server can join", card.presentationWarning)
    assertTrue(card.genericApprovalAllowed, "generic approval stays on")
    assertEquals(listOf("Name" to "launch", "Token" to "Hidden"), card.metadataItems.map { it.key to it.value })
    assertEquals(sourceServerId, card.sourceServerId)
    assertEquals("Raft HQ", card.targetServerName)
    assertEquals(confirmationVersion, card.confirmationVersion)
    return card
}
