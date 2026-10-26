package build.raft.app

import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.Pager
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.isFeedbackSubmitAction

/** pageData keys for a Feedback WebView opened from a `feedback:submit` card: identity only. */
internal const val SETTINGS_FEEDBACK_ACTION_CARD_ID = "settingsFeedbackActionCardId"
internal const val SETTINGS_FEEDBACK_ACTION_CARD_SERVER_ID = "settingsFeedbackActionCardServerId"

/** pageData key for a Feedback WebView opened on one ticket (a done feedback card's "View in Settings", S0.3): identity only. */
internal const val SETTINGS_FEEDBACK_TICKET_ID = "settingsFeedbackTicketId"

/** The web feedback page's query parameter for the ticket to open on (S0.3, the web reads exactly this name). */
internal const val SETTINGS_FEEDBACK_TICKET_QUERY = "ticket"

/**
 * The card a Feedback WebView is opened for (slock `docs/development/feedback-native-entry.md`,
 * "Opened from a feedback card"): the card's message id and the server the person is viewing the
 * card in. In a joint channel that viewing server can differ from the card's own server
 * ([ThreadActionCard.sourceServerId]); the page reads and sends the card as operated from the
 * viewing server, so the card's server is never used here.
 */
internal data class RaftFeedbackCardEntry(
    val actionCardMessageId: String,
    val serverId: String,
)

/**
 * The entry for tapping [card] (message [messageId]) on [viewingServerId], or null when the card
 * stays read-only: not a `feedback:submit` card, already executed, missing ids, or the viewing
 * server does not have `product_feedback_multi_app` on.
 */
internal fun raftFeedbackCardEntryOrNull(
    messageId: String?,
    card: ThreadActionCard?,
    viewingServerId: String?,
    productFeedbackMultiAppEnabled: Boolean,
): RaftFeedbackCardEntry? {
    if (!productFeedbackMultiAppEnabled) return null
    if (card == null || !card.isFeedbackSubmitAction() || card.isExecuted) return null
    val id = messageId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val server = viewingServerId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return RaftFeedbackCardEntry(actionCardMessageId = id, serverId = server)
}

/** Whether a prepared `feedback:submit` card's button opens the Feedback page. */
internal fun ThreadActionCard.opensFeedbackCardEntry(productFeedbackMultiAppEnabled: Boolean): Boolean =
    productFeedbackMultiAppEnabled && isFeedbackSubmitAction() && !isExecuted

/**
 * `<web>/feedback/?embed=…&shell=host[&actionCard=<id>&server=<id>][&ticket=<id>]`, on the current
 * environment's web origin. Both card params are added together or not at all. [ticketId] (a UUID,
 * [feedbackTicketIdOrNull]) asks the page to open on that ticket. The parameter is named exactly
 * `ticket` (agreed with the web: slock's standalone feedback page reads `ticket` and opens its
 * workspace on it, Kirby's S0.3 follow-up); a page that does not read it yet opens the list, which
 * is the S0.3 fallback.
 */
internal fun settingsFeedbackWebUrl(webBaseUrl: String?, entry: RaftFeedbackCardEntry?, ticketId: String? = null): String =
    sharedSettingsHostShellWebUrl(
        webBaseUrl,
        "feedback/",
        entry?.let {
            listOf(
                "actionCard" to it.actionCardMessageId.feedbackQueryEncode(),
                "server" to it.serverId.feedbackQueryEncode(),
            )
        }.orEmpty() + listOfNotNull(feedbackTicketIdOrNull(ticketId)?.let { SETTINGS_FEEDBACK_TICKET_QUERY to it.feedbackQueryEncode() }),
    )

internal fun JSONObject.putSettingsFeedbackCardEntry(entry: RaftFeedbackCardEntry?): JSONObject =
    apply {
        if (entry == null) return@apply
        put(SETTINGS_FEEDBACK_ACTION_CARD_ID, entry.actionCardMessageId)
        put(SETTINGS_FEEDBACK_ACTION_CARD_SERVER_ID, entry.serverId)
    }

internal fun JSONObject.putSettingsFeedbackTicketId(ticketId: String?): JSONObject =
    apply { feedbackTicketIdOrNull(ticketId)?.let { put(SETTINGS_FEEDBACK_TICKET_ID, it) } }

/** The ticket a Feedback WebView opens on, or null (the list): only a UUID. */
internal fun settingsFeedbackTicketIdFromPageData(params: JSONObject): String? =
    feedbackTicketIdOrNull(params.optString(SETTINGS_FEEDBACK_TICKET_ID))

/** Both ids or no card: a half entry opens the ordinary Feedback page. */
internal fun settingsFeedbackCardEntryFromPageData(params: JSONObject): RaftFeedbackCardEntry? {
    val id = params.optString(SETTINGS_FEEDBACK_ACTION_CARD_ID).trim().takeIf { it.isNotEmpty() } ?: return null
    val server = params.optString(SETTINGS_FEEDBACK_ACTION_CARD_SERVER_ID).trim().takeIf { it.isNotEmpty() } ?: return null
    return RaftFeedbackCardEntry(actionCardMessageId = id, serverId = server)
}

internal fun Pager.openSettingsFeedbackWebViewPage(
    storeScope: build.raft.app.data.RaftShellStoreScope,
    cardEntry: RaftFeedbackCardEntry? = null,
    ticketId: String? = null,
) {
    openSettingsWebViewPage(SettingsWebViewSurface.Feedback, storeScope, feedbackCardEntry = cardEntry, feedbackTicketId = ticketId)
}

internal fun settingsFeedbackWebViewSpec(cardEntry: RaftFeedbackCardEntry? = null, ticketId: String? = null): StandaloneSettingsWebViewSpec =
    StandaloneSettingsWebViewSpec(
        surface = SettingsWebViewSurface.Feedback,
        hostEventsEnabled = true,
        allowNavigation = false,
        buildWebUrl = { webBaseUrl, _, _ -> settingsFeedbackWebUrl(webBaseUrl, cardEntry, ticketId) },
    )

private fun String.feedbackQueryEncode(): String = buildString {
    encodeToByteArray().forEach { byte ->
        val value = byte.toInt() and 0xFF
        val char = value.toChar()
        if (
            char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
            char == '-' || char == '.' || char == '_' || char == '~'
        ) {
            append(char)
        } else {
            append('%')
            append(value.toString(16).uppercase().padStart(2, '0'))
        }
    }
}
