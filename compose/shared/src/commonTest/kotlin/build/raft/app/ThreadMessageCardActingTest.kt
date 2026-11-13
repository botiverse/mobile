package build.raft.app

import build.raft.app.data.AppLinkPromptApp
import build.raft.app.data.AppLinkResult
import build.raft.app.data.MessageCardActingApp
import build.raft.app.data.MessageCardActingPage
import build.raft.app.data.RaftMessageCardApp
import build.raft.app.data.WireActionCardMetadata
import build.raft.app.data.messageCardActingMetadataJson
import build.raft.app.data.messageCardReadBody
import build.raft.app.data.parseMessageCardReadAnswer
import build.raft.app.data.readMessageCardFromMetadata
import build.raft.app.data.toMessageItemMetadata
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What a card message's acting buttons draw for each viewer (RFC 086 §4.2; botiverse/slock #14723):
 * the person the card is for presses them; anyone else sees them disabled with "Waiting for {name}";
 * a viewer who never agreed to the App presses to be asked; one who declined sees why. Never the
 * reason stored for older clients.
 */
class ThreadMessageCardActingTest {
    private val card =
        assertNotNull(
            readMessageCardFromMetadata(
                Json { ignoreUnknownKeys = true }.decodeFromString(WireActionCardMetadata.serializer(), messageCardActingMetadataJson()).toMessageItemMetadata(),
            ),
        )

    private fun live(messageCard: String, prompts: String = "[]") = assertNotNull(parseMessageCardReadAnswer(messageCardReadBody(messageCard, prompts)))

    @Test
    fun theCardsPersonPressesEachActingButtonWithItsHandle() {
        val live =
            live(
                """{"card_revision":"1","app":$MessageCardActingApp,"actions":[""" +
                    """{"element_id":"approve","action_id":"approve","url":"$MessageCardActingPage","ticket":"t","handle":"h-approve"},""" +
                    """{"element_id":"deny","action_id":"deny","url":"$MessageCardActingPage","ticket":"t","handle":"h-deny"}]}""",
            ).forCard(card)
        assertEquals("h-approve", assertIs<RaftMessageCardActingState.Pressable>(messageCardActingState(live, "approve", MessageCardActingPage)).handle.handle)
        assertEquals("h-deny", assertIs<RaftMessageCardActingState.Pressable>(messageCardActingState(live, "deny", MessageCardActingPage)).handle.handle)
        assertNull(messageCardActingNote(live))
        // The press is RFC 076's: handles by action id, the card's revision as the prepare's business_revision.
        val press = messageCardPressCard(card, live)
        assertEquals(mapOf("approve" to "h-approve", "deny" to "h-deny"), press.handles)
        assertEquals("1", press.block?.businessRevision)
        assertEquals("Vault", press.app.name)
    }

    @Test
    fun everyoneElseSeesTheButtonsDisabledWaitingForTheCardsPerson() {
        val live = live("""{"card_revision":"1","actions":[],"waiting_for":{"id":"user-alice","name":"Alice"}}""").forCard(card)
        for (id in listOf("approve", "deny")) assertEquals(RaftMessageCardActingState.Disabled, messageCardActingState(live, id, MessageCardActingPage))
        assertEquals(RaftMessageCardActingNote.WaitingFor("Alice"), messageCardActingNote(live))
    }

    @Test
    fun consentNeededAsksOnThePressAndDeclinedSaysWhy() {
        val needed =
            live(
                """{"card_revision":"1","actions":[],"consent":"needed","app":$MessageCardActingApp}""",
                prompts = """[{"url":"$MessageCardActingPage","app":$MessageCardActingApp}]""",
            ).forCard(card)
        val ask = assertIs<RaftMessageCardActingState.AskConsent>(messageCardActingState(needed, "approve", MessageCardActingPage))
        assertEquals(AppLinkResult.Consent(MessageCardActingPage, AppLinkPromptApp("client-vault", "Vault", null)), ask.prompt)
        assertNull(messageCardActingNote(needed), "the buttons draw enabled, nothing to explain")

        val declined = live("""{"card_revision":"1","actions":[],"consent":"declined","app":$MessageCardActingApp}""").forCard(card)
        assertEquals(RaftMessageCardActingState.Disabled, messageCardActingState(declined, "approve", MessageCardActingPage))
        assertEquals(RaftMessageCardActingNote.Declined(RaftMessageCardApp("client-vault", "Vault", null)), messageCardActingNote(declined))
    }

    @Test
    fun beforeAReadForThisRevisionTheButtonsAreDisabledWithoutAReason() {
        assertEquals(RaftMessageCardActingState.Disabled, messageCardActingState(null, "approve", MessageCardActingPage))
        assertNull(messageCardActingNote(null))
        // An answer for an older revision of the card is not this card's.
        assertNull(live("""{"card_revision":"0","actions":[],"waiting_for":{"id":"user-alice","name":"Alice"}}""").forCard(card))
    }
}
