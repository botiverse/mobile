package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.MessageUpdated
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RFC 082 card messages: the card read, its fallback rules (payloads: [MessageCardFixtures], slock 1e9266662). */
class MessageCardTest {
    private fun parse(json: String): RaftAppLinkCardV2? = parseRaftMessageCard(Json.parseToJsonElement(json))

    private fun metadataOf(actionMetadata: String): Map<String, String> =
        Json { ignoreUnknownKeys = true }.decodeFromString(WireActionCardMetadata.serializer(), actionMetadata).toMessageItemMetadata()

    @Test
    fun `the TestFlight card reads as a link card v2 tree, as sent and as stored`() {
        for (json in listOf(messageCardBuildInputJson, messageCardBuildStoredJson)) {
            val card = assertNotNull(parse(json), json)
            assertEquals("root", card.root)
            assertEquals(listOf("title", "meta", "links"), card.elements.getValue("root").childIds)
            assertEquals(RaftLinkCardV2Element.Text("Raft App 1.19.0 (11900001)"), card.elements["title"])
            assertEquals(RaftLinkCardV2Element.Text("Internal, Botiverse", RaftLinkCardTextTone.Muted), card.elements["groups"])
            assertEquals(RaftLinkCardV2Element.Badge("In review", CardBadgeTone.Review), card.elements["state"])
            assertEquals(CardRelativeTime("2026-11-03T12:03:00Z"), card.elements["since"])
            assertEquals(
                listOf("links" to "https://appstoreconnect.apple.com/apps/1"),
                card.messageCardButtons().map { (id, button) -> id to (button.action as RaftLinkCardButtonAction.Navigate).url },
            )
        }
    }

    @Test
    fun `a card the shared schema refuses is not drawn`() {
        val cases =
            mapOf(
                "no version" to messageCardBuildStoredJson.replace(""""version":1,""", ""),
                "version 2" to messageCardBuildStoredJson.replace(""""version":1""", """"version":2"""),
                "a key a card has no place for" to messageCardBuildStoredJson.replace(""""version":1,""", """"version":1,"title":"x","""),
                "an element type this client does not know" to
                    messageCardBuildStoredJson.replace(""""type":"Badge"""", """"type":"Image""""),
                "a button that calls back" to
                    messageCardBuildStoredJson.replace(
                        """{"kind":"navigate","url":"https://appstoreconnect.apple.com/apps/1"}""",
                        """{"kind":"execute","action_id":"approve","confirm":"prepare"}""",
                    ),
                "a button to a non-https URL" to messageCardBuildStoredJson.replace("https://appstoreconnect", "http://appstoreconnect"),
                "a root that is not an element" to messageCardBuildStoredJson.replace(""""root":"root"""", """"root":"missing""""),
                "an element no one reaches" to
                    messageCardBuildStoredJson.replace(""""children":["title","meta","links"]""", """"children":["title","meta"]"""),
                "five buttons" to fiveButtonCard(),
                "depth 5" to deepCard(levels = 6),
            )
        for ((case, json) in cases) assertNull(parse(json), case)
        assertNotNull(parse(deepCard(levels = 5)), "depth 4 is allowed")
    }

    @Test
    fun `a card message projects its card, revision and time onto the message`() {
        val metadata = metadataOf(messageCardMetadataJson(messageCardUpdateTestCardJson("Ready for testing"), 2, "2026-11-03T12:10:00.000Z"))
        val card = assertNotNull(readMessageCardFromMetadata(metadata))
        assertEquals(2, card.revision)
        assertEquals("2026-11-03T12:10:00.000Z", card.updatedAt)
        assertTrue(card.showsUpdated, "revision 2 says when it was updated")
        assertEquals(RaftLinkCardV2Element.Badge("Ready for testing"), card.card.elements["state"])
        // Raft's own action card is not a card message, and a card message is not an action card.
        assertNull(actionCardFromMessageMetadata(metadata))

        val first = assertNotNull(readMessageCardFromMetadata(metadataOf(messageCardMetadataJson(messageCardBuildStoredJson, 1, "2026-11-03T12:00:00.000Z"))))
        assertEquals(false, first.showsUpdated, "a card never replaced shows no Updated line")
    }

    @Test
    fun `the fallback text is drawn when the flag is off or the card does not read`() {
        val valid = metadataOf(messageCardMetadataJson(messageCardBuildStoredJson, 3, "2026-11-03T12:10:00.000Z"))
        assertNotNull(messageCardToRender(valid, cardsEnabled = true))
        assertNull(messageCardToRender(valid, cardsEnabled = false), "message_cards off: the text")

        val textOnly =
            mapOf(
                "an unknown element" to messageCardMetadataJson(messageCardBuildStoredJson.replace(""""type":"Badge"""", """"type":"Chart""""), 2, "2026-11-03T12:10:00.000Z"),
                "no card" to """{"kind":"message-card","revision":2,"updatedAt":"2026-11-03T12:10:00.000Z"}""",
                "revision 0" to messageCardMetadataJson(messageCardBuildStoredJson, 0, "2026-11-03T12:10:00.000Z"),
                "a card that is not an object" to """{"kind":"message-card","card":"Raft App","revision":1,"updatedAt":"2026-11-03T12:10:00.000Z"}""",
                "another kind" to """{"kind":"message-card-v2","card":$messageCardBuildStoredJson,"revision":1}""",
            )
        for ((case, actionMetadata) in textOnly) assertNull(messageCardToRender(metadataOf(actionMetadata), cardsEnabled = true), case)
    }

    @Test
    fun `an older revision never replaces a newer one`() {
        val r2 = metadataOf(messageCardMetadataJson(messageCardUpdateTestCardJson("Testing"), 2, "2026-11-03T12:10:00.000Z"))
        val r3 = metadataOf(messageCardMetadataJson(messageCardUpdateTestCardJson("Ready for testing"), 3, "2026-11-03T12:11:00.000Z"))
        assertTrue(isOlderMessageCardRevision(held = r3, incoming = r2))
        assertEquals(false, isOlderMessageCardRevision(held = r2, incoming = r3))
        assertEquals(false, isOlderMessageCardRevision(held = emptyMap(), incoming = r2), "a plain message held: the update applies")
    }

    @Test
    fun `the message list row and the message updated payload carry the card and the fallback`() {
        val row = Json.parseToJsonElement(messageCardRowJson()).jsonObject
        val fromRow = assertNotNull(parseSharedMessageRow(row))
        val fromSocket = assertIs<MessageUpdated>(RealtimeEventMapper(diagnosticsLogger = {}).map(RealtimeSocketEvents.MessageUpdated, messageCardRowJson())).message
        for (message in listOf(fromRow, fromSocket)) {
            assertEquals("m1", message.id.value)
            assertEquals("Raft App 1.19.0 (11900001) · Ready for testing", message.body)
            assertEquals(2, readMessageCardFromMetadata(message.metadata)?.revision)
        }
        // Each revision is its own realtime frame (the update bumps the row's updatedAt).
        assertEquals("2026-11-03T12:10:00.000Z", fromSocket.updatedAtLabel)
    }

    private fun fiveButtonCard(): String {
        val ids = (0 until 5).map { "b$it" }
        val buttons =
            ids.joinToString(",") {
                """"$it":{"type":"Button","props":{"label":"$it","action":{"kind":"navigate","url":"https://example.com/$it"}}}"""
            }
        return """{"version":1,"root":"root","elements":{"root":{"type":"Stack","props":{},"children":[${ids.joinToString(",") { "\"$it\"" }}]},$buttons}}"""
    }

    /** A chain of Stacks [levels] deep ending in a Text (the root is depth 0). */
    private fun deepCard(levels: Int): String {
        val elements =
            (0 until levels).joinToString(",") { i ->
                if (i == levels - 1) {
                    """"s$i":{"type":"Text","props":{"text":"leaf"}}"""
                } else {
                    """"s$i":{"type":"Stack","props":{},"children":["s${i + 1}"]}"""
                }
            }
        return """{"version":1,"root":"s0","elements":{$elements}}"""
    }
}
