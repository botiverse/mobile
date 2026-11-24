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
    fun `the read answer names who the card waits for and the consent asked and the unreviewed App`() {
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
    fun `the read declares card execute with the link card capabilities and presents the view's tickets`() {
        val view = AppLinkCardView("6f1c1f86-5a83-4b1e-9b1a-0d2f0e0c0a01").apply { tickets[MessageCardActingPage] = "t-1" }
        val body = messageCardReadRequestBody(view, "ja")
        assertEquals(
            APP_LINK_CARD_READ_CAPABILITIES + listOf("card.execute.v1", "card.form.v1", "card.form.secret.v1"),
            body.capabilities,
        )
        assertEquals(1, body.capabilities.count { it == "execute.toast.v1" })
        assertEquals(true, "unreviewed_app.v1" in body.capabilities)
        assertEquals(mapOf(MessageCardActingPage to "t-1"), body.tickets)
        assertNull(body.urls)
        assertEquals("ja", body.locale)
    }

    @Test
    fun `acting buttons parse TextInput and SecretInput forms and reject SecretInput on undo buttons`() {
        val validActions =
            """{"approve":{"kind":"execute","action_id":"approve","confirm":"prepare","form":{"fields":[""" +
                """{"type":"TextInput","id":"name","label":"Name","placeholder":"deploy-key","required":true,"maxLength":20,"default":"prod"},""" +
                """{"type":"SecretInput","id":"secret_val","label":"Secret","placeholder":"sk-...","required":true}]}},""" +
                """"deny":{"kind":"execute","action_id":"deny","undoWindowMs":3000,"form":{"fields":[""" +
                """{"type":"TextInput","id":"reason","label":"Reason","maxLength":100}]}}}"""
        val card = assertNotNull(readMessageCardFromMetadata(metadataOf(messageCardActingMetadataJson(actions = validActions))))
        val acting = assertNotNull(card.acting)
        val approve = assertNotNull(acting.actions["approve"])
        assertEquals(
            RaftMessageCardForm(
                listOf(
                    RaftMessageCardFormField.TextInput(
                        id = "name",
                        label = "Name",
                        placeholder = "deploy-key",
                        required = true,
                        maxLength = 20,
                        defaultValue = "prod",
                    ),
                    RaftMessageCardFormField.SecretInput(
                        id = "secret_val",
                        label = "Secret",
                        placeholder = "sk-...",
                        required = true,
                    ),
                ),
            ),
            approve.form,
        )
        val deny = assertNotNull(acting.actions["deny"])
        assertEquals(
            RaftMessageCardForm(
                listOf(RaftMessageCardFormField.TextInput(id = "reason", label = "Reason", maxLength = 100)),
            ),
            deny.form,
        )

        // SecretInput on undoWindowMs, duplicate field ids, default on SecretInput, multiline label, reserved id, or >4 fields are refused.
        val invalidCases =
            listOf(
                """{"deny":{"kind":"execute","action_id":"deny","undoWindowMs":3000,"form":{"fields":[{"type":"SecretInput","id":"val","label":"Secret"}]}}}""",
                """{"approve":{"kind":"execute","action_id":"approve","confirm":"prepare","form":{"fields":[{"type":"SecretInput","id":"val","label":"Secret","default":"nope"}]}}}""",
                """{"approve":{"kind":"execute","action_id":"approve","confirm":"prepare","form":{"fields":[{"type":"TextInput","id":"a","label":"A"},{"type":"TextInput","id":"a","label":"B"}]}}}""",
                """{"approve":{"kind":"execute","action_id":"approve","confirm":"prepare","form":{"fields":[{"type":"TextInput","id":"__proto__","label":"Bad"}]}}}""",
                """{"approve":{"kind":"execute","action_id":"approve","confirm":"prepare","form":{"fields":[{"type":"TextInput","id":"a","label":"Line1\nLine2"}]}}}""",
                """{"approve":{"kind":"execute","action_id":"approve","confirm":"prepare","form":{"fields":[{"type":"TextInput","id":"a","label":"A","maxLength":3,"default":"abcd"}]}}}""",
                """{"approve":{"kind":"execute","action_id":"approve","confirm":"prepare","form":{"fields":[]}}}""",
                """{"approve":{"kind":"execute","action_id":"approve","confirm":"prepare","form":{"fields":[""" +
                    """{"type":"TextInput","id":"a","label":"1"},{"type":"TextInput","id":"b","label":"2"},""" +
                    """{"type":"TextInput","id":"c","label":"3"},{"type":"TextInput","id":"d","label":"4"},""" +
                    """{"type":"TextInput","id":"e","label":"5"}]}}}""",
            )
        for (bad in invalidCases) {
            val parsed = assertNotNull(readMessageCardFromMetadata(metadataOf(messageCardActingMetadataJson(actions = bad))), bad)
            assertNull(parsed.acting, "invalid form must be dropped: $bad")
        }
    }

    @Test
    fun `read answer keeps valid encryption key pair and handle exp while dropping malformed ones cleanly`() {
        val validKey =
            """{"kty":"EC","crv":"P-256","use":"enc","alg":"ECDH-ES","kid":"test-2026-11-a",""" +
                """"x":"_dsSyV6Maib6vDmb1MLPu-31qcVvNkbxUpWAfDnOGcA","y":"kIEVAwl4P1JF6_ondOiBCJDqXAD0wAM-xXM8OvfmmMM"}"""
        val good =
            assertNotNull(
                parseMessageCardReadAnswer(
                    messageCardReadBody(
                        """{"card_revision":"3","app":{"id":"app-1","name":"Vault","icon":null,"encryption_key":$validKey,"client_key":"app_test_vault"},""" +
                            """"actions":[{"element_id":"approve","action_id":"approve","url":"$MessageCardActingPage","ticket":"t","handle":"h-1","handle_exp":1794700800}]}""",
                    ),
                ),
            )
        assertEquals("test-2026-11-a", good.app?.encryptionKey?.kid)
        assertEquals("app_test_vault", good.app?.clientKey)
        assertEquals(1794700800L, good.handles.single().handleExp)
        val sealTo = assertNotNull(messageCardSealTo(good, good.handles.single()))
        assertEquals("3", sealTo.revision)
        assertEquals("app_test_vault", sealTo.clientKey)
        assertEquals(1794700800L, sealTo.handleExp)

        // Malformed encryption_key or handle_exp drops only the sealing fields; the answer and non-secret handle stand.
        val bad =
            assertNotNull(
                parseMessageCardReadAnswer(
                    messageCardReadBody(
                        """{"card_revision":"3","app":{"id":"app-1","name":"Vault","icon":null,"encryption_key":{"kty":"EC","crv":"P-256","use":"enc","alg":"RSA-OAEP","kid":"k","x":"_dsSyV6Maib6vDmb1MLPu-31qcVvNkbxUpWAfDnOGcA","y":"kIEVAwl4P1JF6_ondOiBCJDqXAD0wAM-xXM8OvfmmMM"},"client_key":"app_test_vault"},""" +
                            """"actions":[{"element_id":"approve","action_id":"approve","url":"$MessageCardActingPage","ticket":"t","handle":"h-1","handle_exp":"soon"}]}""",
                    ),
                ),
            )
        assertEquals(1, bad.handles.size)
        assertNull(bad.app?.encryptionKey)
        assertNull(bad.app?.clientKey)
        assertNull(bad.handles.single().handleExp)
        assertNull(messageCardSealTo(bad, bad.handles.single()))
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
