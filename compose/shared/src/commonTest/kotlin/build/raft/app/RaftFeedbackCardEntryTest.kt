package build.raft.app

import build.raft.app.compat.json.JSONObject
import build.raft.app.data.ActionCardSpecIntent
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.raftAppActionCardCapabilities
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.WireActionCardMetadata
import build.raft.app.data.actionCardFromMessageMetadata
import build.raft.app.data.isSupportedCommitAction
import build.raft.app.data.toMessageMetadata
import build.raft.app.data.toThreadActionCard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * The App's entry for `feedback:submit` cards (the native form on the card's draft, as the web page's
 * card flow): slock `docs/development/feedback-native-entry.md` ("Opened from a feedback card") and `packages/shared/src/actionCards.ts` at botiverse/slock
 * af05e7a04a5f6c64bdb5e9364dffdd242a013972.
 */
class RaftFeedbackCardEntryTest {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val cardMessageId = "0b6f3c8e-5d1a-4c2e-9f7a-1e2d3c4b5a69"
    private val viewingServerId = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
    private val cardServerId = "1f0e2d3c-4b5a-4968-8776-a5b4c3d2e1f0"

    /** Canonical prepared card metadata (ActionCardMetadata with the feedback:submit action). */
    private fun preparedFeedbackCard(state: String = "prepared"): ThreadActionCard =
        assertNotNull(
            json.decodeFromString(
                WireActionCardMetadata.serializer(),
                """
                {
                  "kind":"action-card",
                  "sourceServerId":"$cardServerId",
                  "targetServerName":"Agents HQ",
                  "action":{
                    "type":"feedback:submit",
                    "feedbackType":"idea",
                    "message":"Add dark mode",
                    "draftHint":"You asked for this."
                  },
                  "presentation":{
                    "title":"Submit feedback",
                    "confirmLabel":"Submit Feedback",
                    "genericApprovalAllowed":false,
                    "displayItems":[
                      {"key":"feedbackType","label":"Feedback type","value":"idea"},
                      {"key":"message","label":"Message","value":"Add dark mode"}
                    ],
                    "riskLevel":"normal"
                  },
                  "state":"$state",
                  "confirmationVersion":1
                }
                """.trimIndent(),
            ).toThreadActionCard()
        )

    @Test
    fun aPreparedFeedbackCardOpensTheNativeFormOnItsDraft() {
        // Every platform (1.24), no server flag: once the viewing server is known, tapping a prepared
        // card opens the native feedback form (not the web page), with the card's identity only.
        val card = preparedFeedbackCard()
        assertTrue(raftFeedbackCardEntryEnabled(viewingServerId))
        assertTrue(card.opensFeedbackCardEntry())
        val entry = assertNotNull(raftFeedbackCardEntryOrNull(cardMessageId, card, viewingServerId))
        assertEquals(RaftFeedbackCardEntry(actionCardMessageId = cardMessageId, serverId = viewingServerId), entry)

        val scope = RaftShellStoreScope.create(shellInstanceId = "shell-feedback", initialServerId = viewingServerId)
        val (pageName, pageData) = settingsFeedbackCardPageRequest(scope, entry)
        assertEquals(SETTINGS_HANDS_FEEDBACK_PAGE, pageName)
        assertEquals(SETTINGS_HANDS_FEEDBACK_PAGE, pageData.optString("pageName"))
        assertEquals(entry, settingsFeedbackCardEntryFromPageData(pageData))
        // Identity only: the draft is read by the page, never carried in the route.
        assertFalse(pageData.toString().contains("Add dark mode"))
        assertFalse(pageData.optBoolean(SETTINGS_HANDS_FEEDBACK_RETURN_TO_TICKETS, false))

        // An unknown viewing server keeps the button off (the draft is read as operated from it).
        assertFalse(raftFeedbackCardEntryEnabled(null))
        assertFalse(raftFeedbackCardEntryEnabled(" "))
    }

    @Test
    fun theFeedbackFormIntentIsDeclaredOnceTheViewingServerIsKnown() {
        assertTrue(
            ActionCardSpecIntent.FeedbackForm.capability in
                raftAppActionCardCapabilities(feedbackFormAvailable = raftFeedbackCardEntryEnabled(viewingServerId))
        )
        assertFalse(
            ActionCardSpecIntent.FeedbackForm.capability in
                raftAppActionCardCapabilities(feedbackFormAvailable = raftFeedbackCardEntryEnabled(null))
        )
    }

    @Test
    fun feedbackCardIsNeverAnInlineCommitButOpensTheFormWhilePrepared() {
        val card = preparedFeedbackCard()

        assertFalse(card.isSupportedCommitAction())
        assertFalse(preparedFeedbackCard(state = "executed").opensFeedbackCardEntry())
        assertFalse(card.copy(type = "channel:create").opensFeedbackCardEntry())
    }

    @Test
    fun entryRequiresCardTypeOpenStateAndBothIds() {
        val card = preparedFeedbackCard()
        assertNull(raftFeedbackCardEntryOrNull(cardMessageId, preparedFeedbackCard(state = "executed"), viewingServerId))
        assertNull(raftFeedbackCardEntryOrNull(cardMessageId, card.copy(type = "agent:create"), viewingServerId))
        assertNull(raftFeedbackCardEntryOrNull(cardMessageId, null, viewingServerId))
        listOf(null, "", "  ").forEach { blank ->
            assertNull(raftFeedbackCardEntryOrNull(blank, card, viewingServerId), "messageId=$blank")
            assertNull(raftFeedbackCardEntryOrNull(cardMessageId, card, blank), "server=$blank")
        }
    }

    @Test
    fun entryUsesTheViewingServerNotTheCardsOwnServer() {
        // A joint channel: the card was prepared on another server than the one it is viewed in.
        val card = preparedFeedbackCard()
        assertEquals(cardServerId, card.sourceServerId)
        assertEquals(cardServerId, actionCardFromMessageMetadata(card.toMessageMetadata())?.sourceServerId)

        val entry = assertNotNull(raftFeedbackCardEntryOrNull(cardMessageId, card, viewingServerId))

        assertEquals(viewingServerId, entry.serverId)
        val pageData = settingsHandsFeedbackPageData(RaftShellStoreScope.create(shellInstanceId = "shell-joint"), cardEntry = entry)
        assertEquals(viewingServerId, pageData.optString(SETTINGS_FEEDBACK_ACTION_CARD_SERVER_ID))
        assertFalse(pageData.toString().contains(cardServerId))
    }

    @Test
    fun aHalfCardEntryOpensTheOrdinaryForm() {
        val scope = RaftShellStoreScope.create(shellInstanceId = "shell-feedback")
        assertNull(settingsFeedbackCardEntryFromPageData(settingsHandsFeedbackPageData(scope)))
        assertNull(settingsFeedbackCardEntryFromPageData(JSONObject().put(SETTINGS_FEEDBACK_ACTION_CARD_ID, cardMessageId)))
        assertNull(
            settingsFeedbackCardEntryFromPageData(JSONObject().put(SETTINGS_FEEDBACK_ACTION_CARD_SERVER_ID, viewingServerId))
        )
    }

    @Test
    fun everySettingsFeedbackEntryOpensTheNativeTicketList() {
        // The Settings row, rail Help > Feedback, a done card's "View in Settings" and an agent
        // issue's "View Ticket" all go through openSettingsFeedbackPage: the native ticket list on
        // every platform (Compose desktop included), never the Settings web page.
        val scope = RaftShellStoreScope.create(shellInstanceId = "shell-feedback", initialServerId = viewingServerId)
        for (ticketId in listOf(null, cardMessageId)) {
            val (pageName, pageData) = settingsFeedbackPageRequest(scope, ticketId)
            assertEquals(SETTINGS_FEEDBACK_TICKETS_PAGE, pageName, "$ticketId")
            assertEquals(SETTINGS_FEEDBACK_TICKETS_PAGE, pageData.optString("pageName"), "$ticketId")
            assertNull(settingsWebViewSurfaceFromPageData(pageData), "$ticketId")
            assertEquals(ticketId.orEmpty(), pageData.optString(SETTINGS_FEEDBACK_TICKETS_TICKET_ID))
        }
        assertFalse(SettingsWebViewSurface.entries.any { it.diagnosticsName == "feedback" })
    }

    @Test
    fun theNativeTicketPageCarriesOnlyAUuidTicket() {
        val scope = RaftShellStoreScope.create(shellInstanceId = "shell-feedback", initialServerId = viewingServerId)
        val onTicket = settingsFeedbackTicketsPageData(scope, ticketId = " $cardMessageId ")
        assertEquals(SETTINGS_FEEDBACK_TICKETS_PAGE, onTicket.optString("pageName"))
        assertEquals(cardMessageId, onTicket.optString(SETTINGS_FEEDBACK_TICKETS_TICKET_ID))
        assertEquals("", settingsFeedbackTicketsPageData(scope, ticketId = "not-a-ticket").optString(SETTINGS_FEEDBACK_TICKETS_TICKET_ID))
        assertEquals("", settingsFeedbackTicketsPageData(scope).optString(SETTINGS_FEEDBACK_TICKETS_TICKET_ID))
    }
}
