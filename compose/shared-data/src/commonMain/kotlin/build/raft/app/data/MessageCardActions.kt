package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

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
@RaftCoreInternalApi const val MESSAGE_CARD_EXECUTE_CAPABILITY = "card.execute.v1"

/** The capability a client declares when it draws forms (`TextInput`) on acting buttons (RFC 086 §5.1). */
@RaftCoreInternalApi const val MESSAGE_CARD_FORM_CAPABILITY = "card.form.v1"

/** The capability a client declares when it draws `SecretInput` fields and seals values to the App (RFC 086 §5.1, §5.3). */
@RaftCoreInternalApi const val MESSAGE_CARD_SECRET_INPUT_CAPABILITY = "card.form.secret.v1"

/**
 * `execute.toast.v1` (RFC 076 §6.3, slock `EXECUTE_TOAST_CAPABILITY`): execute's answer may carry the
 * App's own toast (`app_result: { toast }`), which execute learns from the handle this read bound.
 */
@RaftCoreInternalApi const val EXECUTE_TOAST_CAPABILITY = "execute.toast.v1"

/**
 * What a card message read declares: the link card read's list (`unreviewed_app.v1` and `execute.toast.v1`
 * included) plus `card.execute.v1`, `card.form.v1`, and `card.form.secret.v1`, in the web's exact order.
 */
@RaftCoreInternalApi val MESSAGE_CARD_READ_CAPABILITIES: List<String> =
    APP_LINK_CARD_READ_CAPABILITIES +
        listOf(
            MESSAGE_CARD_EXECUTE_CAPABILITY,
            MESSAGE_CARD_FORM_CAPABILITY,
            MESSAGE_CARD_SECRET_INPUT_CAPABILITY,
        )

/** The one stored text of an acting button (`MESSAGE_CARD_ACTING_BUTTON_DISABLED_REASON`), for older clients only. */
@RaftCoreInternalApi const val MESSAGE_CARD_ACTING_BUTTON_DISABLED_REASON = "Update Raft to use this button"

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
@RaftCoreInternalApi fun readMessageCardActingButtons(card: RaftAppLinkCardV2, actionsJson: String?, forUserId: String?): RaftMessageCardActingButtons? {
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
 * `messageCardExecuteActionSchema` (strict):
 * - `{ kind: "execute", action_id, confirm: "prepare", form? }` (form may include SecretInput)
 * - `{ kind: "execute", action_id, undoWindowMs, form? }` (1000-10000; form may only have TextInput)
 */
private fun readMessageCardExecuteAction(raw: JsonElement): RaftLinkCardButtonAction.Execute? {
    val obj = raw as? JsonObject ?: return null
    if (obj["kind"].cardString() != "execute") return null
    val actionId = obj["action_id"].cardString()?.takeIf(MESSAGE_CARD_ACTION_ID::matches) ?: return null
    val hasConfirm = "confirm" in obj
    val hasUndo = "undoWindowMs" in obj
    if (hasConfirm == hasUndo) return null
    return if (hasConfirm) {
        if (!obj.hasOnlyKeys(setOf("kind", "action_id", "confirm", "form"))) return null
        if (obj["confirm"].cardString() != "prepare") return null
        val form = if ("form" in obj) readMessageCardForm(obj["form"], allowSecret = true) ?: return null else null
        RaftLinkCardButtonAction.Execute(actionId, confirm = true, form = form)
    } else {
        if (!obj.hasOnlyKeys(setOf("kind", "action_id", "undoWindowMs", "form"))) return null
        val window = obj["undoWindowMs"].cardSafeInteger()?.takeIf { it in 1000..10000 } ?: return null
        val form = if ("form" in obj) readMessageCardForm(obj["form"], allowSecret = false) ?: return null else null
        RaftLinkCardButtonAction.Execute(actionId, confirm = false, undoWindowMs = window, form = form)
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
    /** Handle expiry in Unix seconds (`handle_exp`), present on SecretInput buttons for JWE `exp` binding (RFC 086 §5.3). */
    val handleExp: Long? = null,
)

/**
 * The App acting (`message_card.app`): [reviewed] false for an App acting on its own Server's
 * confirmation (RFC 076 §17); [encryptionKey] and [clientKey] are present together when the App
 * has a valid card encryption key for sealing SecretInput values (RFC 086 §5.2).
 */
data class RaftMessageCardApp(
    val id: String,
    val name: String,
    val icon: String?,
    val reviewed: Boolean? = null,
    val encryptionKey: RaftAppCardEncryptionKey? = null,
    val clientKey: String? = null,
) {
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
    /** When this read answered (epoch ms): its [handles] are signed for at most 10 minutes. */
    val readAtMillis: Long? = null,
) {
    /** The handle for the acting button [elementId], or null. */
    fun handleFor(elementId: String): RaftMessageCardHandle? = handles.firstOrNull { it.elementId == elementId }
}

/**
 * Builds the [RaftLinkCardSealTo] target for [handle] from [live] (`messageCardSealTo`), or null
 * when any required sealing material (`app.encryption_key`, `app.client_key`, `handle.handle_exp`)
 * is absent.
 */
@RaftCoreInternalApi fun messageCardSealTo(live: RaftMessageCardLive?, handle: RaftMessageCardHandle): RaftLinkCardSealTo? {
    val app = live?.app ?: return null
    val key = app.encryptionKey ?: return null
    val clientKey = app.clientKey ?: return null
    val handleExp = handle.handleExp ?: return null
    return RaftLinkCardSealTo(
        key = key,
        clientKey = clientKey,
        handleExp = handleExp,
        revision = live.cardRevision,
    )
}

private val MESSAGE_CARD_HANDLE_REQUIRED_KEYS = setOf("element_id", "action_id", "url", "ticket", "handle")

/**
 * Reads the batched read's answer for a card message, or null when the body is not a JSON object
 * or carries no readable `message_card` (an older server reads the message as a link's). Tolerant of
 * keys added later; each handle entry requires its 5 fields, and an unreadable `handle_exp` or
 * `app.encryption_key` drops only the sealing fields so non-secret buttons still stand. `consent`
 * other than the two words reads as none.
 */
@RaftCoreInternalApi fun parseMessageCardReadAnswer(
    bodyText: String,
    resolveIcon: (String) -> String? = { it },
): RaftMessageCardLive? {
    val body = runCatching { Json.parseToJsonElement(bodyText) }.getOrNull() as? JsonObject ?: return null
    val card = body["message_card"] as? JsonObject ?: return null
    val cardRevision = card["card_revision"].cardString()?.takeIf { it.length in 1..512 } ?: return null
    val handles =
        (card["actions"] as? JsonArray).orEmpty().take(MESSAGE_CARD_MAX_BUTTONS).mapNotNull { raw ->
            val entry = raw as? JsonObject ?: return@mapNotNull null
            if (!entry.keys.containsAll(MESSAGE_CARD_HANDLE_REQUIRED_KEYS)) return@mapNotNull null
            val handleExp = entry["handle_exp"]?.cardSafeInteger()?.takeIf { it > 0L }
            RaftMessageCardHandle(
                elementId = entry["element_id"].cardString()?.takeIf(CARD_ELEMENT_ID::matches) ?: return@mapNotNull null,
                actionId = entry["action_id"].cardString()?.takeIf(MESSAGE_CARD_ACTION_ID::matches) ?: return@mapNotNull null,
                url = entry["url"].cardString()?.takeIf { it.length in 1..APP_LINK_PREVIEW_URL_MAX_LENGTH } ?: return@mapNotNull null,
                ticket = entry["ticket"].cardString()?.takeIf { it.length in 1..4096 } ?: return@mapNotNull null,
                handle = entry["handle"].cardString()?.takeIf { it.length in 1..4096 } ?: return@mapNotNull null,
                handleExp = handleExp,
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

/**
 * `message_card.app`: `{ id, name, icon, reviewed?, encryption_key?, client_key? }`.
 * `reviewed` present with any value reads `false` (confirm first). `encryption_key` and `client_key`
 * are kept only when both validate; a malformed key drops both without dropping the app.
 */
private fun readMessageCardApp(raw: JsonElement?): RaftMessageCardApp? {
    val obj = raw as? JsonObject ?: return null
    val id = obj["id"].cardString()?.takeIf { it.length in 1..200 } ?: return null
    val name = obj["name"].cardString()?.takeIf { it.length <= 300 } ?: return null
    val icon =
        when (val value = obj["icon"]) {
            null, is JsonNull -> null
            else -> value.cardString()?.takeIf { it.length <= APP_LINK_PREVIEW_URL_MAX_LENGTH }
        }
    val parsedKey = readAppCardEncryptionKey(obj["encryption_key"])
    val parsedClientKey = obj["client_key"].cardString()?.takeIf { it.length in 1..200 }
    val keepSealPair = parsedKey != null && parsedClientKey != null
    return RaftMessageCardApp(
        id = id,
        name = name,
        icon = icon,
        reviewed = if ("reviewed" in obj) false else null,
        encryptionKey = if (keepSealPair) parsedKey else null,
        clientKey = if (keepSealPair) parsedClientKey else null,
    )
}

/** The body of a card message's read: the view's id and tickets, the declared capabilities with `card.execute.v1`. */
@RaftCoreInternalApi fun messageCardReadRequestBody(view: AppLinkCardView, locale: String): AppLinkCardReadRequestBody =
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
@RaftCoreInternalApi fun messageNotificationText(body: String, metadata: Map<String, String>): String {
    if (MessageCardMetadataActions !in metadata) return body
    if (readMessageCardFromMetadata(metadata)?.acting == null) return body
    return body.replace(MESSAGE_CARD_ACTING_BUTTON_FALLBACK_SUFFIX, "")
}
