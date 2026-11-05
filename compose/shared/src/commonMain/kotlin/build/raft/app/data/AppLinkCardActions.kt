package build.raft.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Pressing a block card's buttons (RFC 076 sections 6.2 and 6.3; botiverse/slock
 * `packages/server/src/routes/linkCards.ts`, `services/linkCardActionService.ts`,
 * `services/linkCardDescription.ts`):
 *  - `POST /api/link-cards/prepare` `{ handle, claimed: { business_revision } }` answers
 *    `{ prepared_action_id, prepared_digest, description }` for a button with `confirm: "prepare"`;
 *    Raft's confirmation shows the description.
 *  - `POST /api/link-cards/execute` `{ client_request_id, handle, prepared_action_id?, prepared_digest? }`
 *    answers `{ operation_id, state, toast? }`; the card is read again afterwards.
 * The handle is the one the read gave for the button's action id ([AppLinkResult.Card.handles]).
 */

private val appLinkActionJson = Json { explicitNulls = false; ignoreUnknownKeys = true }

@Serializable
internal data class AppLinkPrepareClaim(@SerialName("business_revision") val businessRevision: String)

@Serializable
internal data class AppLinkPrepareRequestBody(val handle: String, val claimed: AppLinkPrepareClaim) {
    fun toJson(): String = appLinkActionJson.encodeToString(serializer(), this)
}

@Serializable
internal data class AppLinkExecuteRequestBody(
    @SerialName("client_request_id") val clientRequestId: String,
    val handle: String,
    @SerialName("prepared_action_id") val preparedActionId: String? = null,
    @SerialName("prepared_digest") val preparedDigest: String? = null,
) {
    fun toJson(): String = appLinkActionJson.encodeToString(serializer(), this)
}

/** The App's description of what a `confirm: "prepare"` button is about to do, as Raft's confirmation shows it. */
data class AppLinkActionDescription(
    val title: String,
    val body: String? = null,
    val rows: List<Pair<String, String>> = emptyList(),
    val finePrint: String? = null,
    val confirmLabel: String,
    val cancelLabel: String,
)

data class AppLinkPrepared(val preparedActionId: String, val preparedDigest: String, val description: AppLinkActionDescription)

/** What a prepare or an execute came to. */
sealed interface AppLinkActionResult<out T> {
    data class Ok<T>(val value: T) : AppLinkActionResult<T>

    /**
     * Refused or failed: [message] is the server's own words (it never carries the App's), [outOfDate]
     * for 410 (the card or its confirmation is stale: read it again), [code] the server's code.
     */
    data class Failed(val message: String?, val code: String?, val outOfDate: Boolean) : AppLinkActionResult<Nothing>
}

/** An execute that Raft accepted: the operation and its state (`sent`, `accepted`, `done`, `refused`...) with Raft's fixed hint. */
data class AppLinkExecuted(val operationId: String, val state: String, val hint: String?) {
    /** The App has not finished: read the card again soon. */
    val isOpen: Boolean get() = state !in AppLinkTerminalStates
}

private val AppLinkTerminalStates = setOf("done", "refused", "failed_not_sent", "resolved_manually")

/** A `client_request_id` (16-128 of `A-Za-z0-9._~:-`) for one press: the same id repeats the same intent. */
internal fun appLinkClientRequestId(random: String): String = "mobile-$random".take(128)

internal fun parseAppLinkPrepareAnswer(status: Int, bodyText: String?): AppLinkActionResult<AppLinkPrepared> {
    val obj = answerObject(bodyText)
    if (status !in 200..299) return failed(status, obj)
    val unreadable = AppLinkActionResult.Failed(null, null, outOfDate = false)
    if (obj == null) return unreadable
    val description = obj["description"] as? JsonObject ?: return unreadable
    val id = obj.string("prepared_action_id") ?: return unreadable
    val digest = obj.string("prepared_digest") ?: return unreadable
    val title = description.string("title") ?: return unreadable
    val confirm = description.string("confirm_label") ?: return unreadable
    val cancel = description.string("cancel_label") ?: return unreadable
    val rows =
        (description["rows"] as? JsonArray).orEmpty().mapNotNull { row ->
            val entry = row as? JsonObject ?: return@mapNotNull null
            val label = entry.string("label") ?: return@mapNotNull null
            val value = entry.string("value") ?: return@mapNotNull null
            label to value
        }
    return AppLinkActionResult.Ok(
        AppLinkPrepared(id, digest, AppLinkActionDescription(title, description.string("body"), rows, description.string("fine_print"), confirm, cancel)),
    )
}

internal fun parseAppLinkExecuteAnswer(status: Int, bodyText: String?): AppLinkActionResult<AppLinkExecuted> {
    val obj = answerObject(bodyText)
    if (status !in 200..299) return failed(status, obj)
    val unreadable = AppLinkActionResult.Failed(null, null, outOfDate = false)
    if (obj == null) return unreadable
    val operation = obj.string("operation_id") ?: return unreadable
    val state = obj.string("state") ?: return unreadable
    return AppLinkActionResult.Ok(AppLinkExecuted(operation, state, (obj["toast"] as? JsonObject)?.string("text")))
}

private fun failed(status: Int, obj: JsonObject?): AppLinkActionResult.Failed =
    AppLinkActionResult.Failed(obj?.string("error"), obj?.string("code"), outOfDate = status == 410)

private fun answerObject(bodyText: String?): JsonObject? =
    bodyText?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonObject

private fun JsonObject.string(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

