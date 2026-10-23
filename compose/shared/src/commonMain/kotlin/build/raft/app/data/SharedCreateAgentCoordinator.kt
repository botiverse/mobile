package build.raft.app.data

import build.raft.app.SharedCreateAgentMachineOption
import build.raft.app.data.runtimeform.v2.RuntimeFormV2SubmitBody
import build.raft.app.SharedCreateAgentRuntimeModelCatalog
import build.raft.app.SharedCreateAgentRuntimeModelOption
import build.raft.app.model.RUNTIME_MODELS
import build.raft.app.model.RuntimeConfig
import build.raft.app.model.RuntimeModelConfig
import build.raft.app.model.ServerId
import build.raft.app.model.canonicalizeRuntimeModelPresentation

/**
 * KMP-side coordinator for the create-agent flow shared between the Members
 * page and the Thread action-card surface.
 *
 * Per @artin 2026-06-17 #members专修 msg=f1db7897: "应该抽象一个统一的". The
 * previous shape had:
 *   - `SharedMembersDataSource.createAgent` for the Members page agents "+"
 *   - `SharedThreadStore.commitAgentCreateActionCardInternal` for Thread
 *     action cards
 * Both built the same `CreateAgentBody` and called `agentsApi.createAgent`
 * with the same shape. This coordinator is the single point that owns the
 * create-agent body construction + API call + result mapping; both surfaces
 * now go through it.
 *
 * Input shape (`CreateAgentRequest`) is a data-layer-defined struct, NOT
 * the UI `SharedCreateAgentDialogSubmit`. Callers convert the dialog submit
 * to a `CreateAgentRequest` at the UI/store boundary; this keeps the data
 * layer free of UI types.
 *
 * The coordinator is `internal` because it is a pure KMP implementation
 * detail. The host (native) does not wire this directly — Composable code
 * builds it via `SharedCreateAgentCoordinator(agentsApiClient, computersApiClient)`.
 */
internal data class CreateAgentRequest(
    val name: String,
    val description: String?,
    val machineId: String?,
    val runtimeConfig: RuntimeConfig?,
    val formDefinitionRef: SharedRuntimeFormDefinitionRef? = null,
    val external: Boolean,
    val avatarUrl: String? = null,
    /** Runtime form v2 create: replaces runtimeConfig/formDefinitionRef on the wire. */
    val runtimeFormV2: RuntimeFormV2SubmitBody? = null
)

internal class SharedCreateAgentCoordinator(
    private val agentsApiClient: RaftAgentsApiClient,
    private val computersApiClient: RaftComputersApiClient,
    private val logger: (String) -> Unit = {}
) {
    suspend fun createAgent(request: CreateAgentRequest): DataResult<AgentSummary> {
        val v2 = request.runtimeFormV2
        val body =
            if (v2 != null) {
                // The server assembles the runtimeConfig from the field values.
                buildCreateAgentV2Body(
                    CreateAgentBody(
                        name = request.name,
                        description = request.description,
                        runtime = v2.runtimeId,
                        machineId = request.machineId,
                        avatarUrl = request.avatarUrl
                    ),
                    v2
                )
            } else {
                buildCreateAgentBody(
                    CreateAgentBody(
                        name = request.name,
                        description = request.description,
                        model = request.runtimeConfig?.model?.toModelIdOrNull(),
                        runtime = request.runtimeConfig?.runtime,
                        reasoningEffort = request.runtimeConfig?.reasoningEffort,
                        machineId = request.machineId,
                        envVars = request.runtimeConfig?.envVars,
                        avatarUrl = request.avatarUrl,
                        runtimeConfig = request.runtimeConfig,
                        formDefinitionRef = request.formDefinitionRef,
                        external = request.external.takeIf { it }
                    )
                )
            }
        return when (val result = agentsApiClient.createAgent(body)) {
            is DataResult.Content -> DataResult.Content(result.data)
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> {
                logger("create_agent_failed code=${result.error.code}")
                result
            }
            is DataResult.NoPermission -> {
                logger("create_agent_no_permission")
                result
            }
        }
    }

    suspend fun loadMachines(serverId: String): DataResult<List<SharedCreateAgentMachineOption>> {
        return when (val result = computersApiClient.machines(ServerId(serverId))) {
            is DataResult.Content -> {
                val opts = result.data.machines.map { m ->
                    SharedCreateAgentMachineOption(
                        id = m.id,
                        name = m.name,
                        status = when (m.status) {
                            ComputerStatus.Online -> "online"
                            ComputerStatus.Offline -> "offline"
                            ComputerStatus.Unknown -> "unknown"
                        },
                        runtimes = m.runtimes,
                        attachedByCurrentUser = m.attachedByCurrentUser,
                        creatorId = m.creator?.id,
                        usageFactsKnown = true
                    )
                }
                DataResult.Content(opts)
            }
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> {
                logger("load_machines_failed code=${result.error.code}")
                result
            }
            is DataResult.NoPermission -> {
                logger("load_machines_no_permission")
                result
            }
        }
    }

    suspend fun loadRuntimeModels(
        serverId: String,
        machineId: String,
        runtime: String
    ): DataResult<SharedCreateAgentRuntimeModelCatalog> {
        val normalizedServerId = serverId.trim()
        val normalizedMachineId = machineId.trim()
        val normalizedRuntime = runtime.trim()
        if (normalizedServerId.isBlank() || normalizedMachineId.isBlank() || normalizedRuntime.isBlank()) {
            return DataResult.Empty(EmptyReason.NoSettingsAvailable)
        }
        return when (
            val result =
                computersApiClient.runtimeModels(
                    ServerId(normalizedServerId),
                    normalizedMachineId,
                    normalizedRuntime
                )
        ) {
            is DataResult.Content -> {
                val fallbackById = RUNTIME_MODELS[normalizedRuntime].orEmpty().associateBy { it.id }
                val presentation =
                    canonicalizeRuntimeModelPresentation(
                        runtimeId = normalizedRuntime,
                        models = result.data.models,
                        machineDefault = result.data.defaultModel
                    )
                val options =
                    presentation.models.map { model ->
                        val fallback = fallbackById[model.id]
                        SharedCreateAgentRuntimeModelOption(
                            id = model.id,
                            label = model.label.takeIf { it.isNotBlank() } ?: fallback?.label ?: model.id,
                            verified = model.verified ?: fallback?.verified,
                            supportedReasoningEfforts =
                                model.supportedReasoningEfforts.takeIf { it.isNotEmpty() }
                                    ?: fallback?.supportedReasoningEfforts.orEmpty(),
                            defaultReasoningEffort =
                                model.defaultReasoningEffort ?: fallback?.defaultReasoningEffort
                        )
                    }
                DataResult.Content(
                    SharedCreateAgentRuntimeModelCatalog(
                        models = options,
                        defaultModel = presentation.defaultModel
                    )
                )
            }
            is DataResult.Empty -> DataResult.Empty(result.reason)
            is DataResult.Error -> {
                logger("load_runtime_models_failed machineId=$normalizedMachineId runtime=$normalizedRuntime code=${result.error.code}")
                result
            }
            is DataResult.NoPermission -> {
                logger("load_runtime_models_no_permission machineId=$normalizedMachineId runtime=$normalizedRuntime")
                result
            }
        }
    }
}

internal const val AGENT_CREATE_NAME_TAKEN = "agent_create_name_taken"

/**
 * task #193 honest-states fallback: an unmapped create error must always show
 * a real, non-empty reason. Preference order: the server's raw error text,
 * then the localized generic sentence. Machine codes stay in diagnostics and
 * never leak into visible fallback copy.
 */
internal fun createAgentFallbackErrorText(error: ScreenError, genericPrefix: String): String =
    error.serverMessage?.takeIf { it.isNotBlank() } ?: genericPrefix
internal const val AGENT_CREATE_FORM_REFRESH_REQUIRED = "agent_create_form_refresh_required"
internal const val AGENT_CREATE_FORM_REFRESH_FAILED = "agent_create_form_refresh_failed"

internal val CREATE_AGENT_RUNTIME_FORM_REJECTION_CODES =
    setOf(
        "form_definition_ref_required",
        "stale_form_schema",
        "unsupported_form_protocol",
        "unknown_form_runtime",
        "unknown_form_ref_field"
    )

internal suspend fun <T> DataResult<T>.recoverRejectedCreateAgentForm(
    formStore: SharedCreateAgentFormStore?,
    serverId: String?,
    machineId: String?
): DataResult<T> {
    val error = (this as? DataResult.Error)?.error ?: return this
    if (error.code !in CREATE_AGENT_RUNTIME_FORM_REJECTION_CODES) return this
    val cleanServerId = serverId?.trim().orEmpty()
    val cleanMachineId = machineId?.trim().orEmpty()
    if (formStore == null || cleanServerId.isBlank() || cleanMachineId.isBlank()) {
        return DataResult.Error(ScreenError(AGENT_CREATE_FORM_REFRESH_FAILED, retryable = true))
    }
    // Refresh the contract, but never replay a create mutation or retain its write-only API key.
    return when (formStore.loadSupportedBuiltInForm(cleanServerId, cleanMachineId, force = true)) {
        is DataResult.Content ->
            DataResult.Error(ScreenError(AGENT_CREATE_FORM_REFRESH_REQUIRED, retryable = true))
        else ->
            DataResult.Error(ScreenError(AGENT_CREATE_FORM_REFRESH_FAILED, retryable = true))
    }
}

private fun RuntimeModelConfig.toModelIdOrNull(): String? =
    when (this) {
        is RuntimeModelConfig.Preset -> id
        is RuntimeModelConfig.Custom -> name
    }
