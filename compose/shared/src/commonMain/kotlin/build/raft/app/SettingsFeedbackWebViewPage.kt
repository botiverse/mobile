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
 * Whether `feedback:submit` cards open the Feedback WebView on [viewingServerId] (and a done card
 * shows "View in Settings"). One rule with Settings > Feedback ([settingsFeedbackUsesWebPage], no
 * server flag): only where Feedback is the web page (Compose desktop). On Android and iOS (artin,
 * 1.22.0) the card is as in 1.21 with the flag off: no in-App entry, its button is "Open in web".
 * An unknown viewing server keeps it off too, since the card entry is sent as operated from it.
 */
internal fun raftFeedbackCardEntryEnabled(
    viewingServerId: String?,
    feedbackWeb: Boolean = settingsFeedbackUsesWebPage(),
): Boolean = feedbackWeb && !viewingServerId.isNullOrBlank()

/**
 * The entry for tapping [card] (message [messageId]) on [viewingServerId], or null when the card
 * stays read-only: not a `feedback:submit` card, already executed, missing ids, or the card entry
 * is off on this platform ([raftFeedbackCardEntryEnabled]).
 */
internal fun raftFeedbackCardEntryOrNull(
    messageId: String?,
    card: ThreadActionCard?,
    viewingServerId: String?,
    feedbackWeb: Boolean = settingsFeedbackUsesWebPage(),
): RaftFeedbackCardEntry? {
    if (!raftFeedbackCardEntryEnabled(viewingServerId, feedbackWeb)) return null
    if (card == null || !card.isFeedbackSubmitAction() || card.isExecuted) return null
    val id = messageId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val server = viewingServerId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return RaftFeedbackCardEntry(actionCardMessageId = id, serverId = server)
}

/**
 * Whether a done `feedback:submit` card's "View in Settings" can open its ticket on
 * [viewingServerId]: the native ticket page wherever Feedback is native (Android, iOS; 1.23, no
 * server needed), else the web page the card entry opens ([raftFeedbackCardEntryEnabled]).
 */
internal fun raftFeedbackTicketPageAvailable(
    viewingServerId: String?,
    feedbackWeb: Boolean = settingsFeedbackUsesWebPage(),
): Boolean = !feedbackWeb || raftFeedbackCardEntryEnabled(viewingServerId, feedbackWeb)

/** Whether a prepared `feedback:submit` card's button opens the Feedback page. */
internal fun ThreadActionCard.opensFeedbackCardEntry(): Boolean = isFeedbackSubmitAction() && !isExecuted

/**
 * `<web>/feedback/?embed=…&shell=host[&actionCard=<id>&server=<id>][&ticket=<id>]`, on the current
 * environment's web origin. Both card params are added together or not at all. [ticketId] (a UUID,
 * [feedbackTicketIdOrNull]) asks the page to open on that ticket. The parameter is named exactly
 * `ticket` (agreed with the web for Kirby's S0.3 follow-up). As of slock bb82a341b the standalone
 * page (`packages/web/src/feedback/standalone/main.tsx`) does not read it yet and opens the list,
 * which is the S0.3 fallback.
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
