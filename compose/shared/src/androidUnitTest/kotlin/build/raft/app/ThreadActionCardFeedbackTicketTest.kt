package build.raft.app

import build.raft.app.data.ACTION_CARD_OPEN_IN_WEB_LABEL
import build.raft.app.data.ActionCardSpecAction
import build.raft.app.data.ActionCardSpecButton
import build.raft.app.data.ActionCardSpecIntent
import build.raft.app.data.CardStack
import build.raft.app.data.RAFT_APP_ACTION_CARD_CAPABILITIES
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.WireActionCardMetadata
import build.raft.app.data.actionCardFromMessageMetadata
import build.raft.app.data.parseActionCardSpec
import build.raft.app.data.raftAppActionCardCapabilities
import build.raft.app.data.toMessageMetadata
import build.raft.app.data.toThreadActionCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * S0.3 (botiverse/slock #11381 at 1c807448): a done `feedback:submit` card's "View in Settings"
 * (`open feedback_ticket`), on the App. Cards are the vendored v4 vector's; the metadata is the
 * server's done feedback card (`actionCards.feedbackSubmit.api.test.ts`: `executedByUserId`,
 * `result: { kind: "product-feedback", ticketId, reference, source }`).
 */
class ThreadActionCardFeedbackTicketTest {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val vectorCards: Map<String, JsonObject> by lazy {
        val text = checkNotNull(javaClass.getResourceAsStream("/actioncard/actionCard.v1.vector.json")) { "missing vector" }.readBytes().decodeToString()
        Json.parseToJsonElement(text).jsonObject.getValue("cards").jsonArray.associate {
            it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject
        }
    }

    private fun vectorCard(name: String): JsonElement = vectorCards.getValue(name).getValue("card")

    private val sender = "5f2c1b7e-8a4d-4c3b-9e6f-0a1b2c3d4e5f"
    private val ticketId = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"

    /** A done feedback card as the server sends it; [presentationCard] is `presentation.card`. */
    private fun doneFeedbackCard(
        presentationCard: JsonElement = vectorCard("result_open_feedback_ticket"),
        source: String? = "android",
        ticket: String? = ticketId,
        executedBy: String? = sender,
        type: String = "feedback:submit",
        state: String = "executed",
    ): ThreadActionCard {
        val result =
            buildString {
                append("""{"kind":"product-feedback","ticketId":${ticket?.let { "\"$it\"" } ?: "null"},"reference":"FB-7"""")
                source?.let { append(""","source":"$it"""") }
                append("}")
            }
        return assertNotNull(
            json.decodeFromString(
                WireActionCardMetadata.serializer(),
                """
                {"kind":"action-card","state":"$state","sourceServerId":"7c9e6679-7425-40de-944b-e07fc1f90ae7",
                 "action":{"type":"$type","feedbackType":"idea","message":"Add dark mode"},
                 "executedAt":"2026-10-26T16:00:00.000Z",${executedBy?.let { "\"executedByUserId\":\"$it\"," }.orEmpty()}"executedByUserName":"Ada",
                 "result":$result,
                 "presentation":{"title":"Submit feedback","card":$presentationCard},
                 "confirmationVersion":1}
                """.trimIndent(),
            ).toThreadActionCard(),
        )
    }

    private fun ThreadActionCard.render(visible: Boolean, capabilities: List<String> = RAFT_APP_ACTION_CARD_CAPABILITIES) =
        assertNotNull(actionCardSpecToRender(specEnabled = true, capabilities = capabilities, feedbackTicketVisible = visible))

    @Test
    fun theAppDeclaresTheIntentOnlyForFeedbackCardsAndKeepsTheButtonThere() = runTest {
        assertTrue(ActionCardSpecIntent.FeedbackTicket.capability in RAFT_APP_ACTION_CARD_CAPABILITIES)
        // Declared whether or not the Feedback page is on: who sees the button is the visibility rule, not strip.
        assertTrue(ActionCardSpecIntent.FeedbackTicket.capability in raftAppActionCardCapabilities(feedbackFormAvailable = false))
        val done = doneFeedbackCard()
        assertTrue(ActionCardSpecIntent.FeedbackTicket.capability in done.actionCardSpecCapabilitiesFor(RAFT_APP_ACTION_CARD_CAPABILITIES))

        val kept = done.render(visible = true).result!!.element("openTicket") as ActionCardSpecButton
        assertEquals(ActionCardSpecAction.Open(ActionCardSpecIntent.FeedbackTicket), kept.action)
        assertEquals("View in Settings", kept.label.text.getValue("en"))

        // On another card type there is no native path: strip makes it "Open in web", with that label.
        val other = done.copy(type = "channel:create")
        assertFalse(ActionCardSpecIntent.FeedbackTicket.capability in other.actionCardSpecCapabilitiesFor(RAFT_APP_ACTION_CARD_CAPABILITIES))
        val stripped = other.render(visible = true).result!!.element("openTicket") as ActionCardSpecButton
        assertEquals(ActionCardSpecAction.OpenInWeb to ACTION_CARD_OPEN_IN_WEB_LABEL, stripped.action to stripped.label)
        // A reader without the capability (the vector's no_intents) strips it to "Open in web" too.
        val noIntents = done.render(visible = true, capabilities = RAFT_APP_ACTION_CARD_CAPABILITIES.filterNot { it.startsWith("intent.") })
        assertEquals(ActionCardSpecAction.OpenInWeb, (noIntents.result!!.element("openTicket") as ActionCardSpecButton).action)
    }

    @Test
    fun theResultAllowsOnlyFeedbackTicketAndTheBodyRefusesIt() = runTest {
        assertNotNull(parseActionCardSpec(vectorCard("result_open_feedback_ticket")))
        assertNull(parseActionCardSpec(vectorCard("spec_open_feedback_ticket")), "feedback_ticket in the body")
        assertNull(parseActionCardSpec(vectorCard("result_open_other_intent")), "feedback_form in a result")
        // Leniency 7 (AppFather's pin): a body feedback_ticket with a key this App does not know is a marker,
        // which the tree rules accept; it is "Open in web", never a native open from the body.
        val lenient = assertNotNull(parseActionCardSpec(vectorCard("read_unknown_prop_spec_feedback_ticket")))
        assertEquals(
            ActionCardSpecAction.Unsupported("open", "feedback_ticket"),
            (lenient.spec.element("openTicket") as ActionCardSpecButton).action,
        )
        val rendered = doneFeedbackCard(presentationCard = vectorCard("read_unknown_prop_spec_feedback_ticket"), state = "prepared")
            .render(visible = true)
        assertEquals(ActionCardSpecAction.OpenInWeb, (rendered.spec.element("openTicket") as ActionCardSpecButton).action)
    }

    @Test
    fun onlyTheSenderOnTheAppTheTicketWasFiledIntoSeesTheButton() = runTest {
        val done = doneFeedbackCard()
        // The wire fields survive the thread's message-metadata projection, which rebuilds the card on every read.
        val projected = assertNotNull(actionCardFromMessageMetadata(done.toMessageMetadata()))
        assertEquals(Triple(sender, ticketId, "android"), Triple(projected.executedByUserId, projected.resultTicketId, projected.resultSource))
        assertTrue(projected.showsFeedbackTicket(viewerUserId = sender, clientOs = "android", feedbackPageAvailable = true))

        data class Case(val label: String, val card: ThreadActionCard, val viewer: String?, val clientOs: String, val page: Boolean)
        val hidden =
            listOf(
                Case("another viewer", done, "0e7a9c1d-2b3f-4a5b-8c6d-7e8f9a0b1c2d", "android", true),
                Case("no viewer", done, null, "android", true),
                Case("filed into ios, viewed on android", done.copy(resultSource = "ios"), sender, "android", true),
                Case("filed into web (multi-app off)", doneFeedbackCard(source = "web"), sender, "ios", true),
                Case("a card done before S0.3: no source", doneFeedbackCard(source = null), sender, "android", true),
                Case("no executedByUserId (the canonical V2 read)", doneFeedbackCard(executedBy = null), sender, "android", true),
                Case("Feedback page off for the viewing server", done, sender, "android", false),
                Case("not done", doneFeedbackCard(state = "prepared"), sender, "android", true),
                Case("not a feedback card", done.copy(type = "channel:create"), sender, "android", true),
            )
        for (case in hidden) {
            assertFalse(case.card.showsFeedbackTicket(case.viewer, case.clientOs, case.page), case.label)
        }
        assertTrue(doneFeedbackCard(source = "ios").showsFeedbackTicket(sender, "ios", true), "same app on iOS")

        // Hidden means removed (the summary alone), not "Open in web".
        val result = assertNotNull(done.render(visible = false).result)
        assertNull(result.element("openTicket"))
        assertEquals(listOf("summary"), (result.element("root") as CardStack).children)
        assertEquals(listOf("summary", "openTicket"), (done.render(visible = true).result!!.element("root") as CardStack).children)
    }

    @Test
    fun aTapOpensSettingsFeedbackOnTheTicket() = runTest {
        val done = doneFeedbackCard()
        val spec = done.render(visible = true)
        // A done card: the button still works (it views, it does not change the card).
        assertEquals(ThreadActionCardSpecPress.OpenFeedbackTicket(ticketId), threadActionCardSpecPress(done, spec, "openTicket", viewerServerId = "s-1"))
        assertFalse(ActionCardSpecAction.Open(ActionCardSpecIntent.FeedbackTicket).changesCard)
        val button = spec.result!!.element("openTicket") as ActionCardSpecButton
        assertEquals(
            RaftCardButtonState.Enabled,
            actionCardFrameButtonState(RaftActionCardFrameState(status = RaftActionCardStatus.Executed, readOnly = true), "openTicket", button),
        )
        // No ticket, or one that is not a UUID: the feedback list.
        for (raw in listOf(null, "FB-7")) {
            val card = doneFeedbackCard(ticket = raw)
            assertEquals(ThreadActionCardSpecPress.OpenFeedbackTicket(null), threadActionCardSpecPress(card, card.render(visible = true), "openTicket"), "$raw")
        }

        // The route carries the ticket id only, and the page asks the web for that ticket.
        val page =
            settingsWebViewPageData(
                surface = SettingsWebViewSurface.Feedback,
                storeScope = RaftShellStoreScope.create(shellInstanceId = "shell-feedback", initialServerId = "s-1"),
                feedbackTicketId = ticketId,
            )
        assertEquals(ticketId, settingsFeedbackTicketIdFromPageData(page))
        assertNull(settingsFeedbackCardEntryFromPageData(page))
        assertEquals(
            "https://web.example.test/feedback/?embed=raft-settings-v1&shell=host&ticket=$ticketId",
            settingsFeedbackWebViewSpec(ticketId = settingsFeedbackTicketIdFromPageData(page)).buildWebUrl("https://web.example.test", null, "en"),
        )
        val list = settingsWebViewPageData(SettingsWebViewSurface.Feedback, RaftShellStoreScope.create(shellInstanceId = "shell-feedback"), feedbackTicketId = "FB-7")
        assertNull(settingsFeedbackTicketIdFromPageData(list))
        assertEquals("https://web.example.test/feedback/?embed=raft-settings-v1&shell=host", settingsFeedbackWebUrl("https://web.example.test", null, "FB-7"))
    }
}
