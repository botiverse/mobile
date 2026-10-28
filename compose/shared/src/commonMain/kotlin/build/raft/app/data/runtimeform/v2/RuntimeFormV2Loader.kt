package build.raft.app.data.runtimeform.v2

import build.raft.app.data.AgentSummary
import build.raft.app.data.DataResult
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.ScreenError
import build.raft.app.data.RaftAgentsApi
import build.raft.app.data.RaftAgentsApiClient
import build.raft.app.data.RaftComputersApi
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
 * names. Any failure is returned as an error; the caller shows it with a Retry (there is no v1 form).
 */
internal class RuntimeFormV2Loader(
    private val computers: RaftComputersApi,
    private val agents: RaftAgentsApi? = null,
    private val logger: (String) -> Unit = {}
) {
    suspend fun loadCreate(serverId: ServerId, machineId: String, runtimeId: String): DataResult<RuntimeFormV2Loaded> =
        loadCreateUnlogged(serverId, machineId, runtimeId)
            .also { log("create", serverId, machineId, "runtimeId=$runtimeId", it) }

    suspend fun loadEdit(serverId: ServerId, machineId: String, agentId: MemberId): DataResult<RuntimeFormV2Loaded> =
        loadEditUnlogged(serverId, machineId, agentId)
            .also { log("edit", serverId, machineId, "agentId=${agentId.value}", it) }

    private suspend fun loadCreateUnlogged(serverId: ServerId, machineId: String, runtimeId: String): DataResult<RuntimeFormV2Loaded> {
        if (machineId.isBlank() || runtimeId.isBlank()) return failure("runtime_form_v2_ref_missing")
        return refetchedOnceWhenStale {
            val response = guarded { computers.runtimeFormV2(serverId, machineId, runtimeId) }
                ?: return@refetchedOnceWhenStale failure("runtime_form_v2_request_failed")
            loadFrom(response, serverId, machineId)
        }
    }

    private suspend fun loadEditUnlogged(serverId: ServerId, machineId: String, agentId: MemberId): DataResult<RuntimeFormV2Loaded> {
        val transport = agents ?: return failure("runtime_form_v2_edit_unavailable")
        if (machineId.isBlank()) return failure("runtime_form_v2_ref_missing")
        return refetchedOnceWhenStale {
            val response = guarded { transport.agentRuntimeFormV2(agentId) }
                ?: return@refetchedOnceWhenStale failure("runtime_form_v2_request_failed")
            loadFrom(response, serverId, machineId)
        }
    }

    /**
     * The form went stale between fetching it and its sources (a server flag flip mid-load): fetch the
     * whole form once more. Options are never merged across form builds.
     */
    private suspend fun refetchedOnceWhenStale(load: suspend () -> DataResult<RuntimeFormV2Loaded>): DataResult<RuntimeFormV2Loaded> {
        val first = load()
        return if (first.isFormRevisionStale()) load() else first
    }

    /**
     * Re-requests one option source (the "re-probe" action next to a select, which is also the
     * `option_source.status` retry): sent with `?refresh=1` so the server probes again instead of
     * answering from its cache; a source without the capability ignores it. The form and every
     * other source stay as they are; a failure leaves the caller's current options in place, except
     * [RUNTIME_FORM_V2_FORM_REVISION_STALE], after which the caller must reload the whole form.
     */
    suspend fun reloadSource(
        serverId: ServerId,
        machineId: String,
        runtimeId: String,
        sourceId: String,
        formRevision: String? = null
    ): DataResult<RuntimeFormV2Source> {
        val result =
            if (machineId.isBlank() || runtimeId.isBlank()) failure("runtime_form_v2_ref_missing")
            else fetchSource(serverId, machineId, runtimeId, sourceId, refresh = true, formRevision = formRevision)
        log("source_refresh", serverId, machineId, "runtimeId=$runtimeId sourceId=$sourceId", result)
        return result
    }

    /** One line per v2 load or re-probe, like v1's `create_agent_form_load`, so a device run can tell what happened. */
    private fun log(kind: String, serverId: ServerId, machineId: String, detail: String, result: DataResult<*>) {
        val outcome = when (result) {
            is DataResult.Content -> "content"
            is DataResult.Empty -> "empty"
            is DataResult.Error -> "error:${result.error.code}"
            is DataResult.NoPermission -> "no_permission:${result.blocker.code}"
        }
        logger("runtime_form_v2_load kind=$kind serverId=${serverId.value} machineId=$machineId $detail result=$outcome")
    }

    /** The agent as the server now stores it, read back after a v2 save (the client never maps values to a config). */
    suspend fun agent(agentId: MemberId): DataResult<AgentSummary> {
        val transport = agents ?: return failure("runtime_form_v2_edit_unavailable")
        return RaftAgentsApiClient(transport).agent(agentId)
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
        for ((sourceId, key) in form.optionSources) {
            when (val source = fetchSource(serverId, machineId, form.runtimeId, sourceId, formRevision = form.revision)) {
                is DataResult.Content -> sources[sourceId] = source.data
                // One source that fails to load (a non-2xx such as an offline 409, or a body this
                // client cannot read) marks only its own field unavailable and retryable; the rest of
                // the form still renders instead of the whole form failing.
                is DataResult.Error ->
                    // Auth / permission answers are real blockers for the whole form, not a gap in one field;
                    // a stale form revision means the whole form, not this source, must be refetched.
                    if (source.error.code in RUNTIME_FORM_V2_FATAL_SOURCE_CODES || source.isFormRevisionStale()) return source
                    else sources[sourceId] = unavailableRuntimeFormV2Source(key)
                is DataResult.Empty -> sources[sourceId] = unavailableRuntimeFormV2Source(key)
                is DataResult.NoPermission -> return source
            }
        }
        return DataResult.Content(RuntimeFormV2Loaded(form, sources))
    }

    private suspend fun fetchSource(
        serverId: ServerId,
        machineId: String,
        runtimeId: String,
        sourceId: String,
        refresh: Boolean = false,
        formRevision: String? = null
    ): DataResult<RuntimeFormV2Source> {
        val sourceResponse =
            guarded { computers.runtimeFormV2OptionSource(serverId, machineId, runtimeId, sourceId, refresh, formRevision) }
                ?: return failure("runtime_form_v2_request_failed")
        if (sourceResponse.code == 409 && runtimeFormV2ErrorCode(sourceResponse.bodyText ?: sourceResponse.errorBodyText) == "form_revision_stale") {
            return failure(RUNTIME_FORM_V2_FORM_REVISION_STALE)
        }
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

/**
 * An option source answered 409 `form_revision_stale` (`provider.saved_connection`): the server's form
 * build moved on, so the whole form must be refetched.
 */
internal const val RUNTIME_FORM_V2_FORM_REVISION_STALE = "runtime_form_v2_form_revision_stale"

internal fun DataResult<*>.isFormRevisionStale(): Boolean =
    this is DataResult.Error && error.code == RUNTIME_FORM_V2_FORM_REVISION_STALE

/** The typed `code` of a server error body, if any. */
private fun runtimeFormV2ErrorCode(body: String?): String? =
    (body?.takeIf { it.isNotBlank() }?.let { runCatching { runtimeFormV2ErrorJson.parseToJsonElement(it) }.getOrNull() } as? JsonObject)
        ?.get("code").stringOrNull()

private val RUNTIME_FORM_V2_FATAL_SOURCE_CODES = setOf("runtime_form_v2_http_401", "runtime_form_v2_http_403")

/** Stand-in for an option source that failed to load: no options, `unavailable`, retryable. */
internal fun unavailableRuntimeFormV2Source(key: RuntimeFormV2OptionSourceKey): RuntimeFormV2Source =
    RuntimeFormV2Source(
        sourceId = key.sourceId,
        kind = key.kind,
        pointer = "/${key.key}",
        dependsOn = key.dependsOn?.let { "/$it" },
        options = if (key.kind == RuntimeFormV2SourceKind.Select) emptyList() else null,
        optionsByValue = if (key.kind == RuntimeFormV2SourceKind.DependentSelect) emptyMap() else null,
        status = "unavailable",
        reason = null,
        retryable = true
    )
