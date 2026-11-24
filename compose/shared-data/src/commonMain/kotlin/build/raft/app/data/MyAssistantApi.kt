package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

import build.raft.app.model.ServerId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * "My assistant" (RFC 075 v4): the signed-in person's own personal assistant in one server.
 *
 * Server contract (botiverse/slock `packages/server/src/routes/agents.ts` `myAssistantRouter`,
 * mounted at `/api/servers/:id/assistant` in `routes/servers.ts`;
 * `services/assistantService.ts` `MyAssistant` / `getMyAssistant` / `isMyAssistantAvailable`):
 *  - `GET servers/:id/assistant` →
 *    `{ enabled: boolean, assistant: null | { id, name, agentId, dmChannelId: string | null,
 *       readsDms, sendAsOwner, ownerNotes } }`.
 *  - `enabled` is false when the server's `personal_assistant` flag is off or the hosted runtime
 *    is unavailable. `dmChannelId` is null until the owner's DM with the assistant exists.
 *  - 403 `assistant_not_enabled`, or 404 from a server that predates the route: not available.
 *  - `PATCH servers/:id/assistant` `{ ownerNotes: string | null }` (RFC 077, #10762): the owner's
 *    extra instructions; the server trims them, empty or null clears them, and longer than
 *    [ASSISTANT_OWNER_NOTES_MAX_LENGTH] after trimming is 400 `invalid_owner_notes`. It answers
 *    with the same view as the `GET`.
 *  - `PATCH servers/:id/assistant` `{ readsDms: boolean }` or `{ sendAsOwner: boolean }` (RFC 077):
 *    the owner's two switches, one per request (web assistantClient `updateAssistantSetting`); the
 *    same view back.
 *  - `POST servers/:id/assistant` `{}` (web assistantClient `createAssistant`): 201 with the same
 *    view as the `GET`. The server names every assistant Ottie and ignores a `name`. 409
 *    `assistant_exists` carries the existing assistant's view beside `error` and `code` (the
 *    client adopts it); 403 `assistant_not_enabled`; 404 / any other 409: read the assistant again.
 *    A 400 whose `error` says "… limit reached (n/m on X plan)." is the agent or seat limit (web
 *    `isCapacityLimitError`, the message is the contract). Since botiverse/slock #12143 an
 *    assistant takes no agent seat and the server no longer sends it, but web still handles it.
 *  - `DELETE servers/:id/assistant` (web assistantClient `deleteAssistant`): archives the person's
 *    assistant (`archiveAgentAsUser(..., "keep")`) and answers `{ enabled, assistant: null }`
 *    (`routes/myAssistant.api.test.ts` asserts `{ enabled: true, assistant: null }`). 404
 *    `assistant_not_found` when there is none; 403 for a guest (`assistant_not_enabled`) or a fenced
 *    refusal (`forbidden`); an `AgentArchiveError` answers its own status and code (404 / 409). Web
 *    handles the refusals as for create: 403 not available, 404 / 409 read the assistant again.
 *
 * Web reads it in `packages/web/src/assistant/assistantClient.ts` (`useMyAssistant`): only while
 * the `personal_assistant` flag is on, never for a guest; any failure of the first read fails
 * closed (not enabled); a refetch keeps what it had on a transient failure (not 403 / 404).
 *
 * The owner's settings are modelled as web `AssistantOwnerSettings` reads them: `readsDms` (the
 * otter panel's meta line, web AssistantOtterPanel `metaId`), `sendAsOwner`, and `ownerNotes` (the
 * owner's section of the assistant's profile, web AssistantOwnerSection). Field names are exact
 * camelCase.
 */
private val myAssistantJson = Json {
    ignoreUnknownKeys = true
}

@Serializable
private data class WireMyAssistantRecord(
    val id: String,
    val name: String,
    val agentId: String,
    val dmChannelId: String? = null,
    /** Absent from a server that predates RFC 077: null, as web's `readsDms: boolean | null`. */
    val readsDms: Boolean? = null,
    val sendAsOwner: Boolean? = null,
    /** Null both when cleared and when absent; [parseSharedMyAssistantResponse] tells them apart. */
    val ownerNotes: String? = null,
)

@Serializable
private data class WireMyAssistantResponse(
    val enabled: Boolean = false,
    val assistant: WireMyAssistantRecord? = null,
)

/** The person's assistant (web `AssistantRecord`). */
data class MyAssistantRecord(
    /** Equals [agentId] in v4. */
    val id: String,
    val name: String,
    /** The assistant's agent in this server, the one its owner DMs. */
    val agentId: String,
    /** The owner's DM with that agent; null while it is still being set up. */
    val dmChannelId: String?,
    /**
     * Whether it reads the owner's DMs (RFC 077 `assistant.readsDms`); null when the server does
     * not say (web `AssistantOwnerSettings.readsDms`).
     */
    val readsDms: Boolean? = null,
    /** Whether it may send messages as its owner (RFC 077 `sendAsOwner`); null when not reported. */
    val sendAsOwner: Boolean? = null,
    /**
     * The owner's extra instructions as saved, `""` for none; null when the server does not report
     * them, and then the owner's section offers no editor (web `ownerNotesOf`).
     */
    val ownerNotes: String? = null,
)

/** One answer of `GET servers/:id/assistant` (web `MyAssistantResponse`, without the settings). */
data class MyAssistantAnswer(
    val enabled: Boolean,
    val assistant: MyAssistantRecord?,
) {
    companion object {
        /** Not available here (web `NOT_ENABLED`). */
        val NotEnabled: MyAssistantAnswer = MyAssistantAnswer(enabled = false, assistant = null)
    }
}

/**
 * Parses a `GET servers/:id/assistant` body. Null for a body that is not this shape (the caller
 * fails closed, as web does for any unreadable answer).
 */
fun parseSharedMyAssistantResponse(body: String): MyAssistantAnswer? {
    val wire =
        try {
            myAssistantJson.decodeFromString(WireMyAssistantResponse.serializer(), body)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
    val record = wire.assistant
    if (record != null && (record.id.isBlank() || record.agentId.isBlank())) return null
    // Web `ownerNotesOf`: a missing field hides the editor, a null one is no notes.
    val reportsOwnerNotes =
        record != null &&
            runCatching { myAssistantJson.parseToJsonElement(body).jsonObject["assistant"]?.jsonObject?.containsKey("ownerNotes") }
                .getOrNull() == true
    return MyAssistantAnswer(
        enabled = wire.enabled,
        assistant =
            record?.let {
                MyAssistantRecord(
                    id = it.id,
                    name = it.name,
                    agentId = it.agentId,
                    dmChannelId = it.dmChannelId?.takeIf(String::isNotBlank),
                    readsDms = it.readsDms,
                    sendAsOwner = it.sendAsOwner,
                    ownerNotes = if (reportsOwnerNotes) it.ownerNotes.orEmpty() else null,
                )
            },
    )
}

/**
 * The longest owner's notes Raft keeps, in UTF-16 code units after trimming (botiverse/slock
 * `packages/shared/src/assistantOwnerNotes.ts` `ASSISTANT_OWNER_NOTES_MAX_LENGTH`).
 */
const val ASSISTANT_OWNER_NOTES_MAX_LENGTH: Int = 4000

/** Web AssistantInstructionsDialog `COUNTER_FROM`: the counter shows from 90% of the limit on. */
const val ASSISTANT_OWNER_NOTES_COUNTER_FROM: Int = ASSISTANT_OWNER_NOTES_MAX_LENGTH * 9 / 10

/** The draft is over the limit once trimmed (web `tooLong`). */
fun assistantOwnerNotesTooLong(draft: String): Boolean = draft.trim().length > ASSISTANT_OWNER_NOTES_MAX_LENGTH

/** The counter under the field shows (web `showCounter`). */
fun assistantOwnerNotesShowsCounter(draft: String): Boolean = draft.trim().length >= ASSISTANT_OWNER_NOTES_COUNTER_FROM

/** Save is enabled: the trimmed draft differs from the saved notes and fits (web `dirty && !tooLong`). */
fun assistantOwnerNotesCanSave(draft: String, savedNotes: String): Boolean =
    draft.trim() != savedNotes.trim() && !assistantOwnerNotesTooLong(draft)

/** The `PATCH` body: the trimmed notes, null to clear them (web `{ ownerNotes: trimmed || null }`). */
fun encodeAssistantOwnerNotesPatchBody(notes: String): String {
    val trimmed = notes.trim()
    return buildJsonObject { put("ownerNotes", if (trimmed.isEmpty()) JsonNull else JsonPrimitive(trimmed)) }.toString()
}

/** The owner's switches (web `AssistantOwnerSetting`); [wireName] is the `PATCH` body's field. */
enum class AssistantOwnerSetting(val wireName: String) {
    /** Read my direct messages. */
    ReadsDms("readsDms"),

    /** Send messages as me. */
    SendAsOwner("sendAsOwner"),
}

/** The switch's value as the server reported it; null when it does not (the row is hidden). */
fun MyAssistantRecord.ownerSetting(setting: AssistantOwnerSetting): Boolean? =
    when (setting) {
        AssistantOwnerSetting.ReadsDms -> readsDms
        AssistantOwnerSetting.SendAsOwner -> sendAsOwner
    }

/** This record with [setting] at [value] (web assistantClient `patchSetting`). */
fun MyAssistantRecord.withOwnerSetting(setting: AssistantOwnerSetting, value: Boolean?): MyAssistantRecord =
    when (setting) {
        AssistantOwnerSetting.ReadsDms -> copy(readsDms = value)
        AssistantOwnerSetting.SendAsOwner -> copy(sendAsOwner = value)
    }

/** The `PATCH` body for one switch: `{ readsDms: value }` or `{ sendAsOwner: value }` (web `{ [setting]: value }`). */
fun encodeAssistantOwnerSettingPatchBody(setting: AssistantOwnerSetting, value: Boolean): String =
    buildJsonObject { put(setting.wireName, JsonPrimitive(value)) }.toString()

/** What one read of the route gave. */
sealed interface MyAssistantFetchResult {
    data class Answer(val answer: MyAssistantAnswer) : MyAssistantFetchResult

    /** 403 `assistant_not_enabled`, or 404 from a server without the route. */
    data object NotAvailable : MyAssistantFetchResult

    /** Offline, 5xx, an unreadable body: transient. [code] is the HTTP status, null for transport. */
    data class Failed(val code: Int?) : MyAssistantFetchResult
}

/** What one save of the owner's notes gave. */
sealed interface MyAssistantOwnerNotesSaveResult {
    /** Saved; [answer] is the server's view after the save, null when it could not be read. */
    data class Saved(val answer: MyAssistantAnswer?) : MyAssistantOwnerNotesSaveResult

    /** 400 `invalid_owner_notes`: longer than the limit. [traceId] is the response's `X-Trace-Id`. */
    data class TooLong(val traceId: String? = null) : MyAssistantOwnerNotesSaveResult

    /** 403 / 404: the assistant is gone or no longer available here. */
    data class NotAvailable(val code: Int, val traceId: String? = null) : MyAssistantOwnerNotesSaveResult

    /**
     * Anything else; nothing changed. [code] is the HTTP status, null for transport; [traceId] the
     * response's `X-Trace-Id`, null when there was no response.
     */
    data class Failed(val code: Int?, val traceId: String? = null) : MyAssistantOwnerNotesSaveResult
}

/** What one save of an owner's switch gave. */
sealed interface MyAssistantSettingSaveResult {
    /** Saved; [answer] is the server's view after the save, null when it could not be read. */
    data class Saved(val answer: MyAssistantAnswer?) : MyAssistantSettingSaveResult

    /** 403 / 404: the server no longer lets this person change it. */
    data class NotAvailable(val code: Int, val traceId: String? = null) : MyAssistantSettingSaveResult

    /** Anything else; nothing changed. [code] is the HTTP status, null for transport. */
    data class Failed(val code: Int?, val traceId: String? = null) : MyAssistantSettingSaveResult
}

/**
 * The `POST servers/:id/assistant` body. Web sends `{}`: every assistant is Ottie, so there is
 * nothing to choose (an older client's `name` is ignored by the server).
 */
@Serializable
class MyAssistantCreateRequest

/** The `POST` body: `{}` ([MyAssistantCreateRequest]). */
fun encodeMyAssistantCreateBody(): String =
    myAssistantJson.encodeToString(MyAssistantCreateRequest.serializer(), MyAssistantCreateRequest())

/** What one `POST servers/:id/assistant` gave (web `createAssistant` + `handleMutationError`). */
sealed interface MyAssistantCreateResult {
    /** 201: created; [answer] is the server's view, null when it could not be read. */
    data class Created(val answer: MyAssistantAnswer?) : MyAssistantCreateResult

    /** 409 `assistant_exists` with the existing assistant: adopt it (web stores it `enabled: true`). */
    data class Exists(val answer: MyAssistantAnswer) : MyAssistantCreateResult

    /** 400 at the agent or seat limit: [message] is the server's, shown as it is (web `AssistantCapacityError`). */
    data class CapacityLimit(val message: String, val traceId: String? = null) : MyAssistantCreateResult

    /** 403 `assistant_not_enabled` (or a revoked membership): not available here any more. */
    data class NotAvailable(val code: Int, val traceId: String? = null) : MyAssistantCreateResult

    /** 404, or a 409 without the assistant: what the client holds is stale, read it again (web `refresh`). */
    data class Stale(val code: Int, val traceId: String? = null) : MyAssistantCreateResult

    /** Anything else: nothing was created. [code] is the HTTP status, null for transport. */
    data class Failed(val code: Int?, val traceId: String? = null) : MyAssistantCreateResult
}

/** What one `DELETE servers/:id/assistant` gave (web `deleteAssistant` + `handleMutationError`). */
sealed interface MyAssistantDeleteResult {
    /** 2xx: deleted; [answer] is the server's view (`assistant: null`), null when it could not be read. */
    data class Deleted(val answer: MyAssistantAnswer?) : MyAssistantDeleteResult

    /** 403 (`assistant_not_enabled`, `forbidden`): not available here any more (web stores `NOT_ENABLED`). */
    data class NotAvailable(val code: Int, val traceId: String? = null) : MyAssistantDeleteResult

    /** 404 `assistant_not_found` or a 409: what the client holds is stale, read it again (web `refresh`). */
    data class Stale(val code: Int, val traceId: String? = null) : MyAssistantDeleteResult

    /** Anything else: nothing was deleted. [code] is the HTTP status, null for transport. */
    data class Failed(val code: Int?, val traceId: String? = null) : MyAssistantDeleteResult
}

interface RaftMyAssistantApi {
    suspend fun fetch(serverId: ServerId): MyAssistantFetchResult

    /** `POST servers/:id/assistant` `{}`: creates the person's assistant (Ottie). */
    suspend fun create(serverId: ServerId): MyAssistantCreateResult

    /** `DELETE servers/:id/assistant`: deletes (archives) the person's assistant. */
    suspend fun delete(serverId: ServerId): MyAssistantDeleteResult

    /** `PATCH servers/:id/assistant` `{ ownerNotes }` with [notes] trimmed, empty clears them. */
    suspend fun updateOwnerNotes(serverId: ServerId, notes: String): MyAssistantOwnerNotesSaveResult

    /** `PATCH servers/:id/assistant` `{ [setting]: value }`: one of the owner's switches. */
    suspend fun updateSetting(serverId: ServerId, setting: AssistantOwnerSetting, value: Boolean): MyAssistantSettingSaveResult
}

@RaftCoreInternalApi class RaftMyAssistantNetworkApi(
    private val networkClient: RaftNetworkClient,
) : RaftMyAssistantApi {
    override suspend fun fetch(serverId: ServerId): MyAssistantFetchResult {
        val response =
            runCatchingPreservingCancellation {
                networkClient.request(
                    // Warm-up read behind the composer's otter; nothing waits on it.
                    priority = DataRequestPriority.Background,
                    path = "servers/${serverId.value}/assistant",
                    serverIdOverride = serverId,
                )
            }.getOrElse { return MyAssistantFetchResult.Failed(code = null) }
        return myAssistantFetchResultOf(response.code, response.bodyText)
    }

    override suspend fun create(serverId: ServerId): MyAssistantCreateResult {
        val response =
            runCatchingPreservingCancellation {
                networkClient.request(
                    // The person pressed Create and waits on it.
                    priority = DataRequestPriority.Foreground,
                    path = "servers/${serverId.value}/assistant",
                    method = "POST",
                    serverIdOverride = serverId,
                    bodyText = encodeMyAssistantCreateBody(),
                )
            }.getOrElse { return MyAssistantCreateResult.Failed(code = null) }
        return myAssistantCreateResultOf(response.code, response.bodyText ?: response.errorBodyText, response.traceId)
    }

    override suspend fun delete(serverId: ServerId): MyAssistantDeleteResult {
        val response =
            runCatchingPreservingCancellation {
                networkClient.request(
                    // The person confirmed Delete and waits on it.
                    priority = DataRequestPriority.Foreground,
                    path = "servers/${serverId.value}/assistant",
                    method = "DELETE",
                    serverIdOverride = serverId,
                )
            }.getOrElse { return MyAssistantDeleteResult.Failed(code = null) }
        return myAssistantDeleteResultOf(response.code, response.bodyText ?: response.errorBodyText, response.traceId)
    }

    override suspend fun updateOwnerNotes(serverId: ServerId, notes: String): MyAssistantOwnerNotesSaveResult {
        val response =
            runCatchingPreservingCancellation {
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "servers/${serverId.value}/assistant",
                    method = "PATCH",
                    serverIdOverride = serverId,
                    bodyText = encodeAssistantOwnerNotesPatchBody(notes),
                )
            }.getOrElse { return MyAssistantOwnerNotesSaveResult.Failed(code = null) }
        return myAssistantOwnerNotesSaveResultOf(response.code, response.bodyText ?: response.errorBodyText, response.traceId)
    }

    override suspend fun updateSetting(
        serverId: ServerId,
        setting: AssistantOwnerSetting,
        value: Boolean,
    ): MyAssistantSettingSaveResult {
        val response =
            runCatchingPreservingCancellation {
                networkClient.request(
                    priority = DataRequestPriority.Foreground,
                    path = "servers/${serverId.value}/assistant",
                    method = "PATCH",
                    serverIdOverride = serverId,
                    bodyText = encodeAssistantOwnerSettingPatchBody(setting, value),
                )
            }.getOrElse { return MyAssistantSettingSaveResult.Failed(code = null) }
        return myAssistantSettingSaveResultOf(response.code, response.bodyText, response.traceId)
    }
}

@RaftCoreInternalApi fun myAssistantSettingSaveResultOf(
    code: Int,
    bodyText: String?,
    traceId: String? = null,
): MyAssistantSettingSaveResult =
    when {
        code in 200..299 -> MyAssistantSettingSaveResult.Saved(bodyText?.let(::parseSharedMyAssistantResponse))
        code == 403 || code == 404 -> MyAssistantSettingSaveResult.NotAvailable(code, traceId)
        else -> MyAssistantSettingSaveResult.Failed(code, traceId)
    }

@RaftCoreInternalApi fun myAssistantOwnerNotesSaveResultOf(
    code: Int,
    bodyText: String?,
    traceId: String? = null,
): MyAssistantOwnerNotesSaveResult =
    when {
        code in 200..299 -> MyAssistantOwnerNotesSaveResult.Saved(bodyText?.let(::parseSharedMyAssistantResponse))
        code == 400 && myAssistantErrorCode(bodyText) == "invalid_owner_notes" -> MyAssistantOwnerNotesSaveResult.TooLong(traceId)
        code == 403 || code == 404 -> MyAssistantOwnerNotesSaveResult.NotAvailable(code, traceId)
        else -> MyAssistantOwnerNotesSaveResult.Failed(code, traceId)
    }

/**
 * Web `createAssistant`'s answers: 2xx created; 400 whose `error` is the limit's message
 * (`isCapacityLimitError`: it contains "limit reached"); 403 not available; 409
 * `assistant_exists` with an assistant adopted (`enabled: true`); 404 or another 409 stale.
 */
@RaftCoreInternalApi fun myAssistantCreateResultOf(
    code: Int,
    bodyText: String?,
    traceId: String? = null,
): MyAssistantCreateResult {
    if (code in 200..299) return MyAssistantCreateResult.Created(bodyText?.let(::parseSharedMyAssistantResponse))
    val message = myAssistantErrorField(bodyText, "error")
    return when {
        code == 400 && message != null && myAssistantIsCapacityLimitMessage(message) ->
            MyAssistantCreateResult.CapacityLimit(message, traceId)
        code == 403 -> MyAssistantCreateResult.NotAvailable(code, traceId)
        code == 409 && myAssistantErrorCode(bodyText) == "assistant_exists" ->
            bodyText
                ?.let(::parseSharedMyAssistantResponse)
                ?.takeIf { it.assistant != null }
                ?.let { MyAssistantCreateResult.Exists(it.copy(enabled = true)) }
                ?: MyAssistantCreateResult.Stale(code, traceId)
        code == 404 || code == 409 -> MyAssistantCreateResult.Stale(code, traceId)
        else -> MyAssistantCreateResult.Failed(code, traceId)
    }
}

/**
 * Web `deleteAssistant`'s answers: 2xx deleted (the view, `assistant: null`); 403 not available;
 * 404 / 409 stale; anything else failed. (Web would also read a 400 limit message, which a delete
 * never sends.)
 */
@RaftCoreInternalApi fun myAssistantDeleteResultOf(
    code: Int,
    bodyText: String?,
    traceId: String? = null,
): MyAssistantDeleteResult =
    when {
        code in 200..299 -> MyAssistantDeleteResult.Deleted(bodyText?.let(::parseSharedMyAssistantResponse))
        code == 403 -> MyAssistantDeleteResult.NotAvailable(code, traceId)
        code == 404 || code == 409 -> MyAssistantDeleteResult.Stale(code, traceId)
        else -> MyAssistantDeleteResult.Failed(code, traceId)
    }

/** Web `isCapacityLimitError`: the server's `formatBillingCapacityLimitMessage` ("Seat limit reached (1/1 on Pro plan)."). */
@RaftCoreInternalApi fun myAssistantIsCapacityLimitMessage(message: String): Boolean = message.contains("limit reached")

/** The error body's `code`, else its `error` (web assistantClient `errorOf`). */
private fun myAssistantErrorCode(bodyText: String?): String? =
    myAssistantErrorField(bodyText, "code") ?: myAssistantErrorField(bodyText, "error")

/** One string field of an error body, trimmed; null when absent, empty or not a string. */
private fun myAssistantErrorField(bodyText: String?, name: String): String? {
    val body =
        bodyText?.takeIf(String::isNotBlank)?.let { text ->
            runCatching { myAssistantJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
        } ?: return null
    return (body[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
}

@RaftCoreInternalApi fun myAssistantFetchResultOf(code: Int, bodyText: String?): MyAssistantFetchResult =
    when {
        code == 403 || code == 404 -> MyAssistantFetchResult.NotAvailable
        code !in 200..299 || bodyText == null -> MyAssistantFetchResult.Failed(code)
        else ->
            parseSharedMyAssistantResponse(bodyText)
                ?.let(MyAssistantFetchResult::Answer)
                ?: MyAssistantFetchResult.Failed(code)
    }
