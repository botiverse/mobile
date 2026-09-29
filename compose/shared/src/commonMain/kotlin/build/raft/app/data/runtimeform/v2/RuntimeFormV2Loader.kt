package build.raft.app.data.runtimeform.v2

import build.raft.app.data.AgentSummary
import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.ScreenError
import build.raft.app.data.SlockAgentsApi
import build.raft.app.data.SlockAgentsApiClient
import build.raft.app.data.SlockComputersApi
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What a create/edit form needs to use runtime form v2; handed out only while the flag is on. */
internal data class RuntimeFormV2Access(
    val loader: RuntimeFormV2Loader,
    val serverId: ServerId
)

/** A v2 form together with every option source it names, ready to render. */
internal data class RuntimeFormV2Loaded(
    val form: ParsedRuntimeFormV2,
    val sources: RuntimeFormV2Sources
)

/** The runtime-form part of a v2 create (POST) or edit (PATCH) request. */
data class RuntimeFormV2SubmitBody(
    val runtimeId: String,
    val formValues: JsonObject
) {
    internal fun formDefinitionRefJson(): JsonObject =
        buildJsonObject {
            put("protocolVersion", RUNTIME_FORM_V2_PROTOCOL_VERSION)
            put("runtimeId", runtimeId)
        }
}

/** Adds `formDefinitionRef {protocolVersion:2, runtimeId}` and `formValues` to a request body. */
internal fun JsonObject.withRuntimeFormV2(submit: RuntimeFormV2SubmitBody): JsonObject =
    JsonObject(
        this + mapOf(
            "formDefinitionRef" to submit.formDefinitionRefJson(),
            "formValues" to submit.formValues
        )
    )

private val runtimeFormV2ErrorJson = Json { ignoreUnknownKeys = true }

/**
 * Loads v2 forms: create from `…/machines/:machineId/runtime-forms/v2/:runtimeId`,
 * edit from `GET /agents/:id/runtime-form`, then every option source the form
 * names. Any failure is returned as an error so the caller can fall back to v1.
 */
internal class RuntimeFormV2Loader(
    private val computers: SlockComputersApi,
    private val agents: SlockAgentsApi? = null
) {
    suspend fun loadCreate(serverId: ServerId, machineId: String, runtimeId: String): DataResult<RuntimeFormV2Loaded> {
        if (machineId.isBlank() || runtimeId.isBlank()) return failure("runtime_form_v2_ref_missing")
        val response = guarded { computers.runtimeFormV2(serverId, machineId, runtimeId) }
            ?: return failure("runtime_form_v2_request_failed")
        return loadFrom(response, serverId, machineId)
    }

    suspend fun loadEdit(serverId: ServerId, machineId: String, agentId: MemberId): DataResult<RuntimeFormV2Loaded> {
        val transport = agents ?: return failure("runtime_form_v2_edit_unavailable")
        if (machineId.isBlank()) return failure("runtime_form_v2_ref_missing")
        val response = guarded { transport.agentRuntimeFormV2(agentId) }
            ?: return failure("runtime_form_v2_request_failed")
        return loadFrom(response, serverId, machineId)
    }

    /**
     * Re-requests one option source (the "re-probe" action next to a select). The form and every
     * other source stay as they are; a failure leaves the caller's current options in place.
     */
    suspend fun reloadSource(
        serverId: ServerId,
        machineId: String,
        runtimeId: String,
        sourceId: String
    ): DataResult<RuntimeFormV2Source> {
        if (machineId.isBlank() || runtimeId.isBlank()) return failure("runtime_form_v2_ref_missing")
        return fetchSource(serverId, machineId, runtimeId, sourceId)
    }

    /** The agent as the server now stores it, read back after a v2 save (the client never maps values to a config). */
    suspend fun agent(agentId: MemberId): DataResult<AgentSummary> {
        val transport = agents ?: return failure("runtime_form_v2_edit_unavailable")
        return SlockAgentsApiClient(transport).agent(agentId)
    }

    private suspend fun loadFrom(
        response: HomeNetworkResponse,
        serverId: ServerId,
        machineId: String
    ): DataResult<RuntimeFormV2Loaded> {
        if (!response.isSuccessful) return response.httpFailure()
        val form = response.bodyText?.let(::parseRuntimeFormV2)
            ?: return failure("runtime_form_v2_contract_mismatch")
        val sources = linkedMapOf<String, RuntimeFormV2Source>()
        for (sourceId in form.optionSources.keys) {
            when (val source = fetchSource(serverId, machineId, form.runtimeId, sourceId)) {
                is DataResult.Content -> sources[sourceId] = source.data
                is DataResult.Error -> return source
                is DataResult.NoPermission -> return source
                is DataResult.Empty -> return source
            }
        }
        return DataResult.Content(RuntimeFormV2Loaded(form, sources))
    }

    private suspend fun fetchSource(
        serverId: ServerId,
        machineId: String,
        runtimeId: String,
        sourceId: String
    ): DataResult<RuntimeFormV2Source> {
        val sourceResponse =
            guarded { computers.runtimeFormV2OptionSource(serverId, machineId, runtimeId, sourceId) }
                ?: return failure("runtime_form_v2_request_failed")
        if (!sourceResponse.isSuccessful) return sourceResponse.httpFailure()
        val source = sourceResponse.bodyText
            ?.let { runCatching { runtimeFormV2ErrorJson.parseToJsonElement(it) }.getOrNull() }
            ?.let(::parseRuntimeFormV2Source)
            ?: return failure("runtime_form_v2_option_source_contract_mismatch")
        return DataResult.Content(source)
    }

    private suspend fun guarded(block: suspend () -> HomeNetworkResponse): HomeNetworkResponse? =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }

    private fun failure(code: String): DataResult<Nothing> =
        DataResult.Error(ScreenError(code = code, retryable = true))

    private fun HomeNetworkResponse.httpFailure(): DataResult<Nothing> =
        DataResult.Error(
            ScreenError(
                code = "runtime_form_v2_http_$code",
                retryable = code != 404,
                serverMessage = runtimeFormV2ServerError(bodyText ?: errorBodyText)?.message
            )
        )
}

/** A server error body: its `error` text and the fields its `/formValues/<field>` issues point at. */
internal data class RuntimeFormV2ServerError(
    val message: String?,
    val fieldKeys: List<String>
)

internal fun runtimeFormV2ServerError(body: String?): RuntimeFormV2ServerError? {
    val root = body?.takeIf { it.isNotBlank() }
        ?.let { runCatching { runtimeFormV2ErrorJson.parseToJsonElement(it) }.getOrNull() } as? JsonObject
        ?: return null
    val fieldKeys = (root["issues"] as? JsonArray).orEmpty()
        .mapNotNull { issue -> runtimeFormV2FieldForPointer((issue as? JsonObject)?.get("pointer").stringOrNull()) }
        .distinct()
    return RuntimeFormV2ServerError(root["error"].stringOrNull(), fieldKeys)
}
