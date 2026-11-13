package build.raft.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/*
 * App-sent cards, step 1 (RFC 086 §4): an App's bot sends a card message (RFC 082) whose buttons
 * act through the App's action endpoint exactly as link card buttons do (RFC 076), optionally for
 * one named person (`for`).
 *
 * Contract: botiverse/slock #14723 @ 3a036f809943760a0fee9b6250784a3964b2b720 (RFC 086 step 1)
 *  - `packages/shared/src/messageCardActions.ts`: `MESSAGE_CARD_EXECUTE_CAPABILITY`,
 *    `MESSAGE_CARD_ACTING_BUTTON_DISABLED_REASON`, `messageCardActingButtons` (read leniently),
 *    `messageCardForUser`, `MessageCardReadAnswer`.
 *  - `packages/shared/src/messageCard.ts`: `MessageCardMetadata` gains `actions` and `for`
 *    (`action_metadata = { kind: "message-card", card, revision, updatedAt, actions?, for? }`).
 *  - `packages/server/src/routes/messages.ts` (`POST /:messageId/link-cards/read` answers a card
 *    message `{ items: [], prompts, message_card }`) and `services/messageCardActionRead.ts`.
 *  - `packages/shared/src/messageCard.v1.vector.json` case `acting_buttons_as_stored`.
 *
 * The stored card is unchanged for every client: an acting button is an ordinary navigate Button
 * to the App's fallback page with the `disabledReason` Raft writes ("Update Raft to use this
 * button"), which an older client draws disabled. This client draws it from `actions` instead and
 * asks the batched read, declaring `card.execute.v1`, for this viewer's handles. Everything after
 * the handle is RFC 076 ([build.raft.app.ThreadAppLinkCardPress]).
 */

/** The capability a client declares on the batched read when it draws a card message's acting buttons (RFC 086 §4.4). */
internal const val MESSAGE_CARD_EXECUTE_CAPABILITY = "card.execute.v1"

/** What a card message read declares: the link card read's list (`unreviewed_app.v1` included) and `card.execute.v1`. */
internal val MESSAGE_CARD_READ_CAPABILITIES: List<String> = APP_LINK_CARD_READ_CAPABILITIES + MESSAGE_CARD_EXECUTE_CAPABILITY

/** The one stored text of an acting button (`MESSAGE_CARD_ACTING_BUTTON_DISABLED_REASON`), for older clients only. */
internal const val MESSAGE_CARD_ACTING_BUTTON_DISABLED_REASON = "Update Raft to use this button"

private val MESSAGE_CARD_ACTION_ID = Regex("^[a-z][a-z0-9_]{0,31}$")

/**
 * A card's acting buttons (RFC 086): [actions] by Button element id, in the order of `actions`,
 * and [forUserId], the one person who may press them (null: anyone the read gives a handle).
 */
data class RaftMessageCardActingButtons(
    val actions: Map<String, RaftLinkCardButtonAction.Execute>,
    val forUserId: String? = null,
)

/**
 * The acting buttons of a stored card message (`messageCardActingButtons`): `actions` read leniently
 * against [card], an entry whose key names no Button or whose action doesn't validate is left out,
 * never guessed. Null when none is left.
 */
internal fun readMessageCardActingButtons(card: RaftAppLinkCardV2, actionsJson: String?, forUserId: String?): RaftMessageCardActingButtons? {
    val raw = actionsJson?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return null
    val actions = LinkedHashMap<String, RaftLinkCardButtonAction.Execute>()
    for ((elementId, value) in raw) {
        if (card.elements[elementId] !is RaftLinkCardV2Element.Button) continue
        actions[elementId] = readMessageCardExecuteAction(value) ?: continue
    }
    if (actions.isEmpty()) return null
    return RaftMessageCardActingButtons(actions, forUserId?.takeIf { it.isNotEmpty() })
}

/**
 * `linkCardExecuteActionSchema` (strict): `{ kind: "execute", action_id, confirm: "prepare" }` or
 * `{ kind: "execute", action_id, undoWindowMs }` (an integer, 1000-10000).
 */
private fun readMessageCardExecuteAction(raw: JsonElement): RaftLinkCardButtonAction.Execute? {
    val obj = raw as? JsonObject ?: return null
    if (obj["kind"].cardString() != "execute") return null
    val actionId = obj["action_id"].cardString()?.takeIf(MESSAGE_CARD_ACTION_ID::matches) ?: return null
    return when (obj.keys) {
        setOf("kind", "action_id", "confirm") ->
            if (obj["confirm"].cardString() == "prepare") RaftLinkCardButtonAction.Execute(actionId, confirm = true) else null
        setOf("kind", "action_id", "undoWindowMs") -> {
            val window = obj["undoWindowMs"].cardSafeInteger()?.takeIf { it in 1000..10000 } ?: return null
            RaftLinkCardButtonAction.Execute(actionId, confirm = false, undoWindowMs = window)
        }
        else -> null
    }
}

/** The viewer's choice for the App, when it is what stands between them and the buttons (`consent`). */
enum class RaftMessageCardConsent {
    /** Never agreed: the buttons draw enabled; the first press asks (the read's prompt), then reads again. */
    Needed,

    /** Declined this App: no handles; the buttons say why and how to change it. */
    Declined,
}

/** One acting button this viewer may press now (`message_card.actions[]`). */
data class RaftMessageCardHandle(
    val elementId: String,
    val actionId: String,
    /** The button's fallback page: the key its [ticket] is presented under on the next read. */
    val url: String,
    val ticket: String,
    val handle: String,
)

/** The App acting (`message_card.app`): [reviewed] false for an App acting on its own Server's confirmation (RFC 076 §17). */
data class RaftMessageCardApp(val id: String, val name: String, val icon: String?, val reviewed: Boolean? = null) {
    fun toAppLinkApp(): AppLinkApp = AppLinkApp(name = name, icon = icon, reviewed = reviewed)

    fun toPromptApp(): AppLinkPromptApp = AppLinkPromptApp(id = id, name = name, icon = icon)
}

/** The person a card is for, as the read names them to anyone else (`waiting_for`). */
data class RaftMessageCardWaitingFor(val id: String, val name: String)

/**
 * A card message read's answer for this viewer (`MessageCardReadAnswer` + the read's `prompts`):
 * the handles of the buttons they may press now and, when none, why: the card is for someone else
 * ([waitingFor]), or their choice for the App ([consent], with the App's first-view [prompt]).
 */
data class RaftMessageCardLive(
    val cardRevision: String,
    val handles: List<RaftMessageCardHandle> = emptyList(),
    val app: RaftMessageCardApp? = null,
    val waitingFor: RaftMessageCardWaitingFor? = null,
    val consent: RaftMessageCardConsent? = null,
    /** `consent: "needed"`: the App's first-view prompt (`prompts[0]`, the link card read's shape). */
    val prompt: AppLinkResult.Consent? = null,
) {
    /** The handle for the acting button [elementId], or null. */
    fun handleFor(elementId: String): RaftMessageCardHandle? = handles.firstOrNull { it.elementId == elementId }
}

/**
 * Reads the batched read's answer for a card message, or null when the body is not a JSON object
 * or carries no readable `message_card` (an older server reads the message as a link's). Tolerant of
 * keys added later (step 2 adds `app.encryption_key`); each handle entry is strict, and one this
 * client can't read is left out (its button stays disabled). `consent` other than the two words
 * reads as none.
 */
internal fun parseMessageCardReadAnswer(
    bodyText: String,
    resolveIcon: (String) -> String? = { it },
): RaftMessageCardLive? {
    val body = runCatching { Json.parseToJsonElement(bodyText) }.getOrNull() as? JsonObject ?: return null
    val card = body["message_card"] as? JsonObject ?: return null
    val cardRevision = card["card_revision"].cardString()?.takeIf { it.length in 1..512 } ?: return null
    val handles =
        (card["actions"] as? JsonArray).orEmpty().take(MESSAGE_CARD_MAX_BUTTONS).mapNotNull { raw ->
            val entry = raw as? JsonObject ?: return@mapNotNull null
            if (entry.keys != setOf("element_id", "action_id", "url", "ticket", "handle")) return@mapNotNull null
            RaftMessageCardHandle(
                elementId = entry["element_id"].cardString()?.takeIf(CARD_ELEMENT_ID::matches) ?: return@mapNotNull null,
                actionId = entry["action_id"].cardString()?.takeIf(MESSAGE_CARD_ACTION_ID::matches) ?: return@mapNotNull null,
                url = entry["url"].cardString()?.takeIf { it.length in 1..APP_LINK_PREVIEW_URL_MAX_LENGTH } ?: return@mapNotNull null,
                ticket = entry["ticket"].cardString()?.takeIf { it.length in 1..4096 } ?: return@mapNotNull null,
                handle = entry["handle"].cardString()?.takeIf { it.length in 1..4096 } ?: return@mapNotNull null,
            )
        }.distinctBy { it.elementId }
    val app = readMessageCardApp(card["app"])?.let { it.copy(icon = it.icon?.let(resolveIcon)) }
    val waitingFor =
        (card["waiting_for"] as? JsonObject)?.let { obj ->
            val id = obj["id"].cardString()?.takeIf { it.isNotEmpty() } ?: return@let null
            RaftMessageCardWaitingFor(id = id, name = obj["name"].cardString().orEmpty())
        }
    val consent =
        when (card["consent"].cardString()) {
            "needed" -> RaftMessageCardConsent.Needed
            "declined" -> RaftMessageCardConsent.Declined
            else -> null
        }
    val prompt =
        if (consent == RaftMessageCardConsent.Needed) {
            val prompts = (body["prompts"] as? JsonArray)?.toList().orEmpty()
            appLinkResultsWithPrompts(emptyMap(), prompts, resolveIcon).values.filterIsInstance<AppLinkResult.Consent>().firstOrNull()
        } else {
            null
        }
    return RaftMessageCardLive(
        cardRevision = cardRevision,
        handles = handles,
        app = app,
        waitingFor = waitingFor,
        consent = consent,
        prompt = prompt,
    )
}

/** `message_card.app`: `{ id, name, icon, reviewed? }`; `reviewed` present with any value reads `false` (confirm first). */
private fun readMessageCardApp(raw: JsonElement?): RaftMessageCardApp? {
    val obj = raw as? JsonObject ?: return null
    val id = obj["id"].cardString()?.takeIf { it.length in 1..200 } ?: return null
    val name = obj["name"].cardString()?.takeIf { it.length <= 300 } ?: return null
    val icon =
        when (val value = obj["icon"]) {
            null, is JsonNull -> null
            else -> value.cardString()?.takeIf { it.length <= APP_LINK_PREVIEW_URL_MAX_LENGTH }
        }
    return RaftMessageCardApp(id = id, name = name, icon = icon, reviewed = if ("reviewed" in obj) false else null)
}

/** The body of a card message's read: the view's id and tickets, the declared capabilities with `card.execute.v1`. */
internal fun messageCardReadRequestBody(view: AppLinkCardView, locale: String): AppLinkCardReadRequestBody =
    AppLinkCardReadRequestBody(
        viewInstanceId = view.id,
        capabilities = MESSAGE_CARD_READ_CAPABILITIES,
        locale = locale,
        tickets = view.tickets.toMap().takeIf { it.isNotEmpty() },
    )

/** How the stored fallback writes an acting button's reason (`label (disabled: reason)`, `renderMessageCardMarkdown`). */
private const val MESSAGE_CARD_ACTING_BUTTON_FALLBACK_SUFFIX = " (disabled: $MESSAGE_CARD_ACTING_BUTTON_DISABLED_REASON)"

/**
 * The text a notification shows for a message (RFC 086): for a card message this client draws with
 * acting buttons, its stored fallback without the reason written for older clients
 * ("Approve (disabled: Update Raft to use this button)" reads "Approve"); any other message's text
 * as it is.
 */
internal fun messageNotificationText(body: String, metadata: Map<String, String>): String {
    if (MessageCardMetadataActions !in metadata) return body
    if (readMessageCardFromMetadata(metadata)?.acting == null) return body
    return body.replace(MESSAGE_CARD_ACTING_BUTTON_FALLBACK_SUFFIX, "")
}
