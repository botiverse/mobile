package build.raft.app.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * RFC 086 App-sent cards, step 1 (botiverse/slock #14723 @ 3a036f809943760a0fee9b6250784a3964b2b720;
 * payloads: [messageCardActingStoredJson] and the read answers of `messageCardActions.api.test.ts`):
 * the stored `actions` and `for` beside the card, the card message read's answer, and the
 * notification text without the reason stored for older clients.
 */
class MessageCardActionsTest {
    private fun metadataOf(actionMetadata: String): Map<String, String> =
        Json { ignoreUnknownKeys = true }.decodeFromString(WireActionCardMetadata.serializer(), actionMetadata).toMessageItemMetadata()

    @Test
    fun `the stored acting buttons and for read beside the unchanged card`() {
        val card = assertNotNull(readMessageCardFromMetadata(metadataOf(messageCardActingMetadataJson())))
        val acting = assertNotNull(card.acting)
        assertEquals(
            mapOf(
                "approve" to RaftLinkCardButtonAction.Execute("approve", confirm = true),
                "deny" to RaftLinkCardButtonAction.Execute("deny", confirm = false, undoWindowMs = 5000),
            ),
            acting.actions,
        )
        assertEquals("user-alice", acting.forUserId)
        // The card itself is the stored one: every acting button is still the navigate Button older clients draw.
        val approve = card.card.elements.getValue("approve") as RaftLinkCardV2Element.Button
        assertEquals(RaftLinkCardButtonAction.Navigate(MessageCardActingPage), approve.action)
        assertEquals(MESSAGE_CARD_ACTING_BUTTON_DISABLED_REASON, approve.disabledReason)
        // Without `for`, anyone the read gives a handle may press.
        assertNull(assertNotNull(readMessageCardFromMetadata(metadataOf(messageCardActingMetadataJson(forUserId = null)))).acting?.forUserId)
    }

    @Test
    fun `acting buttons are read leniently and a card without any stays a navigate card`() {
        val cases =
            listOf(
                // A key naming no Button, and one naming a Stack, are left out; the rest stays.
                """{"approve":{"kind":"execute","action_id":"approve","confirm":"prepare"},"nope":{"kind":"execute","action_id":"x","confirm":"prepare"},"row":{"kind":"execute","action_id":"y","confirm":"prepare"}}""" to setOf("approve"),
                // An action outside RFC 076's two shapes is left out, never guessed.
                """{"approve":{"kind":"execute","action_id":"approve","confirm":"yes"},"deny":{"kind":"execute","action_id":"deny","undoWindowMs":500}}""" to emptySet(),
                """{"approve":{"kind":"execute","action_id":"Approve","confirm":"prepare"},"deny":{"kind":"execute","action_id":"deny","undoWindowMs":5000,"extra":1}}""" to emptySet(),
            )
        for ((actions, ids) in cases) {
            val card = assertNotNull(readMessageCardFromMetadata(metadataOf(messageCardActingMetadataJson(actions = actions))), actions)
            assertEquals(ids, card.acting?.actions?.keys.orEmpty(), actions)
        }
        assertNull(readMessageCardFromMetadata(metadataOf(messageCardMetadataJson(messageCardActingStoredJson, 1, "2026-11-13T09:00:00.000Z")))?.acting)
    }

    @Test
    fun `the read answer for the person the card is for carries a handle per acting button`() {
        val live =
            assertNotNull(
                parseMessageCardReadAnswer(
                    messageCardReadBody(
                        """{"card_revision":"1","app":$MessageCardActingApp,"actions":[""" +
                            """{"element_id":"approve","action_id":"approve","url":"$MessageCardActingPage","ticket":"t-1","handle":"h-approve"},""" +
                            """{"element_id":"deny","action_id":"deny","url":"$MessageCardActingPage","ticket":"t-1","handle":"h-deny"}]}""",
                    ),
                ),
            )
        assertEquals("1", live.cardRevision)
        assertEquals(listOf("approve" to "h-approve", "deny" to "h-deny"), live.handles.map { it.elementId to it.handle })
        assertEquals(RaftMessageCardApp("client-vault", "Vault", null), live.app)
        assertNull(live.waitingFor)
        assertNull(live.consent)
    }

    @Test
    fun `the read answer names who the card waits for, the consent asked and the unreviewed App`() {
        val waiting = assertNotNull(parseMessageCardReadAnswer(messageCardReadBody("""{"card_revision":"1","actions":[],"waiting_for":{"id":"user-alice","name":"Alice"}}""")))
        assertEquals(RaftMessageCardWaitingFor("user-alice", "Alice"), waiting.waitingFor)
        assertEquals(emptyList(), waiting.handles)

        val needed =
            assertNotNull(
                parseMessageCardReadAnswer(
                    messageCardReadBody(
                        """{"card_revision":"1","actions":[],"consent":"needed","app":$MessageCardActingApp}""",
                        prompts = """[{"url":"$MessageCardActingPage","app":$MessageCardActingApp}]""",
                    ),
                ),
            )
        assertEquals(RaftMessageCardConsent.Needed, needed.consent)
        assertEquals(AppLinkResult.Consent(MessageCardActingPage, AppLinkPromptApp("client-vault", "Vault", null)), needed.prompt)

        val declined = assertNotNull(parseMessageCardReadAnswer(messageCardReadBody("""{"card_revision":"1","actions":[],"consent":"declined","app":$MessageCardActingApp}""")))
        assertEquals(RaftMessageCardConsent.Declined, declined.consent)
        assertNull(declined.prompt)

        // An own-Server App (`unreviewed_app.v1`) comes with reviewed: false; a key added later (step 2) is no reason to drop it.
        val unreviewed =
            assertNotNull(
                parseMessageCardReadAnswer(
                    messageCardReadBody(
                        """{"card_revision":"2","actions":[],"app":{"id":"client-vault","name":"Vault","icon":null,"reviewed":false,"encryption_key":{}},"later":1}""",
                    ),
                ),
            )
        assertEquals(false, unreviewed.app?.reviewed)
        assertEquals(true, unreviewed.app?.toAppLinkApp()?.unreviewed)
    }

    @Test
    fun `an answer without message_card is not a card read, and a handle entry it can't read is left out`() {
        assertNull(parseMessageCardReadAnswer("""{"items":[],"prompts":[]}"""))
        assertNull(parseMessageCardReadAnswer("not json"))
        val live =
            assertNotNull(
                parseMessageCardReadAnswer(
                    messageCardReadBody(
                        """{"card_revision":"1","actions":[{"element_id":"approve","action_id":"approve","url":"$MessageCardActingPage","ticket":"t","handle":"h","guess":1},""" +
                            """{"element_id":"deny","action_id":"deny","url":"$MessageCardActingPage","ticket":"t","handle":"h-deny"}],"consent":"maybe"}""",
                    ),
                ),
            )
        assertEquals(listOf("deny"), live.handles.map { it.elementId })
        assertNull(live.consent)
    }

    @Test
    fun `the read declares card execute with the link card capabilities and presents the view's tickets`() {
        val view = AppLinkCardView("6f1c1f86-5a83-4b1e-9b1a-0d2f0e0c0a01").apply { tickets[MessageCardActingPage] = "t-1" }
        val body = messageCardReadRequestBody(view, "ja")
        assertEquals(APP_LINK_CARD_READ_CAPABILITIES + "card.execute.v1", body.capabilities)
        assertEquals(true, "unreviewed_app.v1" in body.capabilities)
        assertEquals(mapOf(MessageCardActingPage to "t-1"), body.tickets)
        assertNull(body.urls)
        assertEquals("ja", body.locale)
    }

    @Test
    fun `a notification of a card drawn with acting buttons never shows the reason stored for older clients`() {
        val acting = metadataOf(messageCardActingMetadataJson())
        val stripped = messageNotificationText(messageCardActingFallback, acting)
        assertEquals("**Access request: deploy key**\n\nbuilder-bot asks to use the deploy key until 18:00 UTC.\n\nApprove · Deny", stripped)
        // Any other message (a plain card, an ordinary message) keeps its text as it is.
        val plain = metadataOf(messageCardMetadataJson(messageCardActingStoredJson, 1, "2026-11-13T09:00:00.000Z"))
        assertEquals(messageCardActingFallback, messageNotificationText(messageCardActingFallback, plain))
        assertEquals(messageCardActingFallback, messageNotificationText(messageCardActingFallback, emptyMap()))
    }
}
