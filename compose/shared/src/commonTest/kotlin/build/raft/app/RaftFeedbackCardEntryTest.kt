package build.raft.app

import build.raft.app.compat.json.JSONObject
import build.raft.app.data.RaftShellStoreScope
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
 * Mobile entry for `feedback:submit` cards: slock `docs/development/feedback-native-entry.md`
 * ("Opened from a feedback card") and `packages/shared/src/actionCards.ts` at botiverse/slock
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
    fun feedbackCardIsNeverAnInlineCommitButOpensTheFormOnlyWhileTheFlagIsOn() {
        val card = preparedFeedbackCard()

        assertFalse(card.isSupportedCommitAction())
        assertTrue(card.opensFeedbackCardEntry(productFeedbackMultiAppEnabled = true))
        assertFalse(card.opensFeedbackCardEntry(productFeedbackMultiAppEnabled = false))
        assertFalse(preparedFeedbackCard(state = "executed").opensFeedbackCardEntry(productFeedbackMultiAppEnabled = true))
        assertFalse(
            card.copy(type = "channel:create").opensFeedbackCardEntry(productFeedbackMultiAppEnabled = true)
        )
    }

    @Test
    fun entryRequiresFlagCardTypeOpenStateAndBothIds() {
        val card = preparedFeedbackCard()
        assertEquals(
            RaftFeedbackCardEntry(actionCardMessageId = cardMessageId, serverId = viewingServerId),
            raftFeedbackCardEntryOrNull(cardMessageId, card, viewingServerId, productFeedbackMultiAppEnabled = true),
        )

        assertNull(raftFeedbackCardEntryOrNull(cardMessageId, card, viewingServerId, productFeedbackMultiAppEnabled = false))
        assertNull(
            raftFeedbackCardEntryOrNull(
                cardMessageId,
                preparedFeedbackCard(state = "executed"),
                viewingServerId,
                productFeedbackMultiAppEnabled = true,
            )
        )
        assertNull(
            raftFeedbackCardEntryOrNull(cardMessageId, card.copy(type = "agent:create"), viewingServerId, true)
        )
        assertNull(raftFeedbackCardEntryOrNull(cardMessageId, null, viewingServerId, true))
        listOf(null, "", "  ").forEach { blank ->
            assertNull(raftFeedbackCardEntryOrNull(blank, card, viewingServerId, true), "messageId=$blank")
            assertNull(raftFeedbackCardEntryOrNull(cardMessageId, card, blank, true), "server=$blank")
        }
    }

    @Test
    fun entryUsesTheViewingServerNotTheCardsOwnServer() {
        // A joint channel: the card was prepared on another server than the one it is viewed in.
        val card = preparedFeedbackCard()
        assertEquals(cardServerId, card.sourceServerId)
        assertEquals(cardServerId, actionCardFromMessageMetadata(card.toMessageMetadata())?.sourceServerId)

        val entry = assertNotNull(raftFeedbackCardEntryOrNull(cardMessageId, card, viewingServerId, true))

        assertEquals(viewingServerId, entry.serverId)
        val url = settingsFeedbackWebUrl("https://web.example.test", entry)
        assertTrue(url.contains("server=$viewingServerId"), url)
        assertFalse(url.contains(cardServerId), url)
    }

    @Test
    fun webUrlCarriesBothCardParamsOnTheEnvironmentWebOrigin() {
        val entry = RaftFeedbackCardEntry(actionCardMessageId = cardMessageId, serverId = viewingServerId)

        assertEquals(
            "https://staging.web.example.test/feedback/?embed=raft-settings-v1&shell=host" +
                "&actionCard=$cardMessageId&server=$viewingServerId",
            settingsFeedbackWebUrl(" https://staging.web.example.test/ ", entry),
        )
        assertEquals(
            "https://staging.web.example.test/feedback/?embed=raft-settings-v1&shell=host",
            settingsFeedbackWebUrl("https://staging.web.example.test", null),
        )
        assertEquals(
            settingsFeedbackWebUrl("http://10.0.2.2:5173", entry),
            settingsFeedbackWebViewSpec(entry).buildWebUrl("http://10.0.2.2:5173", null, "en"),
        )
    }

    @Test
    fun webUrlPercentEncodesCardParams() {
        val url =
            settingsFeedbackWebUrl(
                "https://web.example.test",
                RaftFeedbackCardEntry(actionCardMessageId = "a&b=c d", serverId = "s/1?#é"),
            )

        assertTrue(url.endsWith("&actionCard=a%26b%3Dc%20d&server=s%2F1%3F%23%C3%A9"), url)
    }

    @Test
    fun feedbackRouteCarriesOnlyTheCardIdentityAndRoundTrips() {
        val entry = RaftFeedbackCardEntry(actionCardMessageId = cardMessageId, serverId = viewingServerId)
        val pageData =
            settingsWebViewPageData(
                surface = SettingsWebViewSurface.Feedback,
                storeScope = RaftShellStoreScope.create(shellInstanceId = "shell-feedback", initialServerId = viewingServerId),
                feedbackCardEntry = entry,
            )

        assertEquals(SettingsWebViewSurface.Feedback, settingsWebViewSurfaceFromPageData(pageData))
        assertEquals(entry, settingsFeedbackCardEntryFromPageData(pageData))
        assertFalse(pageData.toString().contains("Add dark mode"))

        // Another surface never carries a card, and a half entry opens the ordinary page.
        val billing =
            settingsWebViewPageData(
                surface = SettingsWebViewSurface.Billing,
                storeScope = RaftShellStoreScope.create(shellInstanceId = "shell-billing"),
                feedbackCardEntry = entry,
            )
        assertNull(settingsFeedbackCardEntryFromPageData(billing))
        assertNull(settingsFeedbackCardEntryFromPageData(JSONObject().put(SETTINGS_FEEDBACK_ACTION_CARD_ID, cardMessageId)))
        assertNull(
            settingsFeedbackCardEntryFromPageData(JSONObject().put(SETTINGS_FEEDBACK_ACTION_CARD_SERVER_ID, viewingServerId))
        )
    }
}
