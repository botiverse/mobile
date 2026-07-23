package build.raft.app.data

import build.raft.app.model.MemberId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val agentsApiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
}

/**
 * Typed Agents boundary.
 *
 * Server contracts:
 *  - `packages/server/src/services/agentService.ts` (Agent record shape, with
 *    canonical `name` + nullable `displayName`).
 *  - `packages/server/src/routes/agents.ts` (per-endpoint payload shapes).
 *
 * Field names are exact, camelCase, matching the server payload. No snake_case
 * fallbacks, no speculative aliases.
 */
internal interface SlockAgentsApi {
    suspend fun agents(): HomeNetworkResponse
    suspend fun agent(agentId: MemberId): HomeNetworkResponse
    suspend fun createAgent(body: String): HomeNetworkResponse
    suspend fun updateAgent(agentId: MemberId, body: String): HomeNetworkResponse
    suspend fun startAgent(agentId: MemberId): HomeNetworkResponse
    suspend fun stopAgent(agentId: MemberId): HomeNetworkResponse
    suspend fun resetAgent(agentId: MemberId, body: String): HomeNetworkResponse
    suspend fun deleteAgent(agentId: MemberId): HomeNetworkResponse
    suspend fun assignMachine(agentId: MemberId, body: String): HomeNetworkResponse
    suspend fun agentDirectMessages(agentId: MemberId): HomeNetworkResponse
    suspend fun agentActivityLog(agentId: MemberId, limit: Int = 50): HomeNetworkResponse
    suspend fun agentWorkspaceFiles(agentId: MemberId, dirPath: String? = null): HomeNetworkResponse
    suspend fun readAgentWorkspaceFile(agentId: MemberId, path: String): HomeNetworkResponse
    suspend fun agentSkills(agentId: MemberId): HomeNetworkResponse
    // #89 React parity: agent permissions (scope grants) per React
    // `AgentScopesPanel.tsx`. Mirrors `GET / PUT /api/agents/:id/scopes`.
    // `updateAgentScopes` body is `{ scopes: AgentGrantableScope[] }`;
    // `resetAgentScopes` sends `{ mode: "default" }` and returns the
    // server's updated `AgentScopeSet` (granted list empty + mode "default").
    suspend fun agentScopes(agentId: MemberId): HomeNetworkResponse
    suspend fun updateAgentScopes(agentId: MemberId, body: String): HomeNetworkResponse
    suspend fun resetAgentScopes(agentId: MemberId): HomeNetworkResponse
    suspend fun issueBootstrapToken(agentId: MemberId, body: String): HomeNetworkResponse
}

internal class SlockAgentsNetworkApi(
    private val networkClient: SlockNetworkClient
) : SlockAgentsApi {
    override suspend fun agents(): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "agents"
        )

    override suspend fun agent(agentId: MemberId): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "agents/${agentId.value}"
        )

    override suspend fun createAgent(body: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "agents",
            method = "POST",
            bodyText = body
        )

    override suspend fun updateAgent(agentId: MemberId, body: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}",
            method = "PATCH",
            bodyText = body
        )

    override suspend fun startAgent(agentId: MemberId): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}/start",
            method = "POST"
        )

    override suspend fun stopAgent(agentId: MemberId): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}/stop",
            method = "POST"
        )

    override suspend fun resetAgent(agentId: MemberId, body: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}/reset",
            method = "POST",
            bodyText = body
        )

    override suspend fun deleteAgent(agentId: MemberId): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}",
            method = "DELETE"
        )

    override suspend fun assignMachine(agentId: MemberId, body: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}/assign-machine",
            method = "POST",
            bodyText = body
        )

    override suspend fun agentDirectMessages(agentId: MemberId): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "agents/${agentId.value}/agent-dms"
        )

    override suspend fun agentActivityLog(agentId: MemberId, limit: Int): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "agents/${agentId.value}/activity-log",
            query = listOf("limit" to limit.toString())
        )

    override suspend fun agentWorkspaceFiles(agentId: MemberId, dirPath: String?): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "agents/${agentId.value}/workspace-files",
            query = listOfNotNull(dirPath?.let { "dirPath" to it })
        )

    override suspend fun readAgentWorkspaceFile(agentId: MemberId, path: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "agents/${agentId.value}/workspace-files/read",
            query = listOf("path" to path)
        )

    override suspend fun agentSkills(agentId: MemberId): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Visible,
            path = "agents/${agentId.value}/skills"
        )

    // #89 React parity: agent scope grants endpoints.
    override suspend fun agentScopes(agentId: MemberId): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}/scopes"
        )

    override suspend fun updateAgentScopes(agentId: MemberId, body: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}/scopes",
            method = "PUT",
            bodyText = body
        )

    override suspend fun resetAgentScopes(agentId: MemberId): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}/scopes",
            method = "PUT",
            bodyText = """{"mode":"default"}"""
        )

    override suspend fun issueBootstrapToken(agentId: MemberId, body: String): HomeNetworkResponse =
        networkClient.request(
            priority = DataRequestPriority.Foreground,
            path = "agents/${agentId.value}/bootstrap-tokens",
            method = "POST",
            bodyText = body
        )
}

internal class SlockAgentsApiClient(
    private val transport: SlockAgentsApi,
    private val diagnosticsLogger: (String) -> Unit = {}
) {
    suspend fun agents(): DataResult<AgentsPage> =
        transport.agents().mapAgentsBody(
            "agents_list_contract_mismatch",
            ListSerializer(AgentWireDto.serializer()),
            diagnosticsLogger = diagnosticsLogger
        ) { wire -> AgentsPage(agents = wire.mapNotNull { it.toSummary() }) }

    suspend fun agent(agentId: MemberId): DataResult<AgentSummary> =
        transport.agent(agentId).mapAgentsBody(
            "agent_detail_contract_mismatch",
            AgentWireDto.serializer(),
            diagnosticsLogger = diagnosticsLogger
        ) { it.toSummary() }

    /** Public/profile-only projection; intentionally does not load private agent satellites. */
    suspend fun publicAgentDetail(agentId: MemberId): DataResult<AgentDetailSnapshot> =
        agent(agentId).map { summary ->
            AgentDetailSnapshot(
                agentId = agentId,
                summary = summary.toAgentDetailSummary(),
                directMessages = emptyList(),
                reminders = emptyList(),
                activity = emptyList()
            )
        }

    suspend fun createAgent(body: String): DataResult<AgentSummary> =
        transport.createAgent(body).mapAgentsBody(
            "agent_create_contract_mismatch",
            AgentWireDto.serializer(),
            diagnosticsLogger = diagnosticsLogger
        ) { it.toSummary() }

    suspend fun updateAgent(agentId: MemberId, body: String): DataResult<AgentSummary> =
        transport.updateAgent(agentId, body).mapAgentsBody(
            "agent_update_contract_mismatch",
            AgentWireDto.serializer(),
            diagnosticsLogger = diagnosticsLogger
        ) { it.toSummary() }

    suspend fun startAgent(agentId: MemberId): DataResult<AgentCommandResult> =
        transport.startAgent(agentId).mapAgentsCommand("agent_start_contract_mismatch", diagnosticsLogger)

    suspend fun stopAgent(agentId: MemberId): DataResult<AgentCommandResult> =
        transport.stopAgent(agentId).mapAgentsCommand("agent_stop_contract_mismatch", diagnosticsLogger)

    suspend fun resetAgent(agentId: MemberId, body: String): DataResult<AgentCommandResult> =
        transport.resetAgent(agentId, body).mapAgentsCommand("agent_reset_contract_mismatch", diagnosticsLogger)

    suspend fun deleteAgent(agentId: MemberId): DataResult<AgentCommandResult> =
        transport.deleteAgent(agentId).mapAgentsCommand("agent_delete_contract_mismatch", diagnosticsLogger)

    // #89 React parity: agent scope grants typed accessors. Mirror React
    // `AgentScopesPanel.tsx` load / save / reset flows.
    suspend fun agentScopes(agentId: MemberId): DataResult<AgentScopeSet> =
        transport.agentScopes(agentId).mapAgentsBody(
            "agent_scopes_contract_mismatch",
            AgentScopesWireDto.serializer(),
            diagnosticsLogger = diagnosticsLogger
        ) { wire -> wire.toScopeSet(agentId) }

    suspend fun updateAgentScopes(agentId: MemberId, scopes: List<String>): DataResult<AgentScopeSet> {
        val body = agentsApiJson.encodeToString(AgentScopesUpdateDto.serializer(), AgentScopesUpdateDto(scopes = scopes))
        return transport.updateAgentScopes(agentId, body).mapAgentsBody(
            "agent_scopes_update_contract_mismatch",
            AgentScopesWireDto.serializer(),
            diagnosticsLogger = diagnosticsLogger
        ) { wire -> wire.toScopeSet(agentId) }
    }

    suspend fun resetAgentScopes(agentId: MemberId): DataResult<AgentScopeSet> =
        transport.resetAgentScopes(agentId).mapAgentsBody(
            "agent_scopes_reset_contract_mismatch",
            AgentScopesWireDto.serializer(),
            diagnosticsLogger = diagnosticsLogger
        ) { wire -> wire.toScopeSet(agentId) }

    suspend fun assignMachine(agentId: MemberId, body: String): DataResult<AgentCommandResult> =
        transport.assignMachine(agentId, body).mapAgentsCommand("agent_assign_machine_contract_mismatch", diagnosticsLogger)

    suspend fun agentDirectMessages(agentId: MemberId): DataResult<AgentDmPage> =
        transport.agentDirectMessages(agentId).mapAgentsBody(
            "agent_dms_contract_mismatch",
            ListSerializer(AgentDmWireDto.serializer()),
            diagnosticsLogger = diagnosticsLogger
        ) { wire -> AgentDmPage(itemCount = wire.size) }

    suspend fun agentActivityLog(agentId: MemberId, limit: Int = 50): DataResult<AgentActivityPage> =
        transport.agentActivityLog(agentId, limit).mapAgentsBody(
            "agent_activity_log_contract_mismatch",
            ListSerializer(AgentActivityWireDto.serializer()),
            diagnosticsLogger = diagnosticsLogger
        ) { wire -> AgentActivityPage(entryCount = wire.size) }

    suspend fun agentWorkspaceFiles(agentId: MemberId, dirPath: String? = null): DataResult<AgentWorkspaceFilePage> =
        transport.agentWorkspaceFiles(agentId, dirPath).mapAgentsBody(
            "agent_workspace_files_contract_mismatch",
            AgentWorkspaceFilesResponseDto.serializer(),
            diagnosticsLogger = diagnosticsLogger
        ) { AgentWorkspaceFilePage(fileCount = it.files.size) }

    suspend fun readAgentWorkspaceFile(agentId: MemberId, path: String): DataResult<AgentWorkspaceFileRead> =
        transport.readAgentWorkspaceFile(agentId, path).mapAgentsBody(
            "agent_workspace_file_read_contract_mismatch",
            AgentWorkspaceFileReadDto.serializer(),
            diagnosticsLogger = diagnosticsLogger
        ) { wire ->
            val resolvedPath = wire.path.takeIf { it.isNotBlank() } ?: return@mapAgentsBody null
            AgentWorkspaceFileRead(
                path = resolvedPath,
                content = wire.content ?: "",
                binary = wire.binary ?: false,
                size = wire.size,
            )
        }

    suspend fun agentSkills(agentId: MemberId): DataResult<AgentSkillsSummary> =
        transport.agentSkills(agentId).mapAgentsBody(
            "agent_skills_contract_mismatch",
            AgentSkillsResponseDto.serializer(),
            diagnosticsLogger = diagnosticsLogger
        ) { AgentSkillsSummary(globalCount = it.global.size, workspaceCount = it.workspace.size) }

    suspend fun issueBootstrapToken(agentId: MemberId, body: String): DataResult<AgentBootstrapTokenSummary> =
        transport.issueBootstrapToken(agentId, body).mapAgentsBody(
            "agent_bootstrap_token_contract_mismatch",
            AgentBootstrapTokenResponseDto.serializer(),
            diagnosticsLogger = diagnosticsLogger
        ) { wire ->
            val tokenId = wire.tokenId?.takeIf { it.isNotBlank() } ?: return@mapAgentsBody null
            val prefix = wire.tokenPrefix?.takeIf { it.isNotBlank() } ?: return@mapAgentsBody null
            AgentBootstrapTokenSummary(
                tokenId = tokenId,
                tokenPrefix = prefix,
                bootstrapTokenPresent = !wire.bootstrapToken.isNullOrBlank(),
                scopeCount = wire.scopes.size,
            )
        }
}

internal data class AgentsPage(val agents: List<AgentSummary>) {
    val agentCount: Int
        get() = agents.size
}
internal data class AgentSummary(
    val id: String,
    val name: String,
    val displayName: String?,
    val description: String?,
    val serverRole: String?,
    val status: String?,
    val model: String?,
    val runtimeModelKind: String?,
    val runtime: String?,
    val reasoning: String?,
    val executionMode: String?,
    val machineId: String?,
    val machineName: String?,
    val daemonVersion: String?,
    val avatarUrl: String?,
    val creatorId: String?,
    val creatorType: String?,
    val creator: AgentCreatorSummary?,
    val createdAt: String?,
    val envVarNames: List<String>,
    val envVars: Map<String, String>,
    val createdAgents: List<AgentCreatedSummary>,
    val activity: String?,
    val activityDetail: String?,
    val deletedAt: String?
)

internal fun AgentSummary.toAgentDetailSummary(): AgentDetailSummary =
    AgentDetailSummary(
        id = id,
        name = name,
        displayName = displayName,
        description = description,
        serverRole = serverRole,
        status = status,
        runtime = runtime,
        model = model,
        runtimeModelKind = runtimeModelKind,
        reasoning = reasoning,
        executionMode = executionMode,
        machineId = machineId,
        machineName = machineName,
        daemonVersion = daemonVersion,
        avatarUrl = avatarUrl,
        creatorId = creatorId,
        creatorType = creatorType,
        creator = creator,
        createdAt = createdAt,
        envVarNames = envVarNames,
        envVars = envVars,
        createdAgents = createdAgents,
        activity = activity,
        activityDetail = activityDetail,
        deletedAt = deletedAt
    )
internal data class AgentCommandResult(val ok: Boolean)
internal data class AgentDmPage(val itemCount: Int)
internal data class AgentActivityPage(val entryCount: Int)
internal data class AgentWorkspaceFilePage(val fileCount: Int)
internal data class AgentWorkspaceFileRead(val path: String, val content: String, val binary: Boolean, val size: Long?)
internal data class AgentSkillsSummary(val globalCount: Int, val workspaceCount: Int)
internal data class AgentBootstrapTokenSummary(
    val tokenId: String,
    val tokenPrefix: String,
    val bootstrapTokenPresent: Boolean,
    val scopeCount: Int
)

private suspend inline fun <Wire, T> HomeNetworkResponse.mapAgentsBody(
    contractCode: String,
    serializer: KSerializer<Wire>,
    diagnosticsLogger: (String) -> Unit = {},
    crossinline transform: (Wire) -> T?,
): DataResult<T> {
    if (!isSuccessful) {
        return toAgentsDataError()
    }
    val body = bodyText?.takeIf { it.isNotBlank() }
        ?: return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    val value = try {
        parseResponseBodyWithTrace(
            source = contractCode.removeSuffix("_contract_mismatch"),
            markerPrefix = "agents",
            body = body,
            code = code,
            hasBody = true,
            diagnosticsLogger = diagnosticsLogger,
            parser = {
                val wire = agentsApiJson.decodeFromString(serializer, body)
                transform(wire) ?: throw AgentsContractMismatchException()
            }
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: SerializationException) {
        return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    } catch (_: AgentsContractMismatchException) {
        return DataResult.Error(ScreenError(code = contractCode, retryable = false))
    }
    return DataResult.Content(value)
}

private class AgentsContractMismatchException : IllegalStateException()

private fun <T> HomeNetworkResponse.toAgentsDataError(): DataResult<T> =
    when (code) {
        401 -> DataResult.NoPermission(PermissionBlocker(code = "auth_required", requiredCapability = "auth_session"))
        403 -> DataResult.NoPermission(PermissionBlocker(code = "server_forbidden", requiredCapability = "server_access"))
        in 500..599 -> DataResult.Error(ScreenError(code = "server_unavailable", retryable = true))
        else -> DataResult.Error(ScreenError(code = "http_$code", retryable = code != 404))
    }

private suspend fun HomeNetworkResponse.mapAgentsCommand(
    contractCode: String,
    diagnosticsLogger: (String) -> Unit = {}
): DataResult<AgentCommandResult> =
    mapAgentsBody(contractCode, AgentCommandResponseDto.serializer(), diagnosticsLogger) { wire ->
        if (!wire.ok) null else AgentCommandResult(ok = true)
    }

// ── Wire DTOs ──
// Source of truth: packages/server/src/services/agentService.ts and the
// routes/agents.ts handlers. `name` is required; `displayName` may be null but
// is not used as a fallback for `name` — the server always emits `name`.

@Serializable
private data class AgentWireDto(
    val id: String,
    val name: String,
    val displayName: String? = null,
    val description: String? = null,
    val role: String? = null,
    val status: String? = null,
    val model: String? = null,
    val runtime: String? = null,
    val runtimeConfig: AgentRuntimeConfigWireDto? = null,
    val reasoningEffort: String? = null,
    val executionMode: String? = null,
    val machineId: String? = null,
    val avatarUrl: String? = null,
    val creatorId: String? = null,
    val creatorType: String? = null,
    val creator: AgentCreatorWireDto? = null,
    val createdAt: String? = null,
    val daemonVersion: String? = null,
    val envVars: Map<String, JsonElement> = emptyMap(),
    val createdAgents: List<AgentCreatedWireDto> = emptyList(),
    val activity: String? = null,
    val activityDetail: String? = null,
    val deletedAt: String? = null,
    val runtimeProfile: AgentRuntimeProfileDto? = null,
)

@Serializable
private data class AgentCreatorWireDto(
    val type: String,
    val id: String,
    val name: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
)

private fun AgentWireDto.toSummary(): AgentSummary? {
    if (id.isBlank() || name.isBlank()) return null
    val currentProfile = runtimeProfile?.current
    val configuredModel = runtimeConfig?.model?.valueOrNull()
    return AgentSummary(
        id = id,
        name = name,
        displayName = displayName,
        description = description,
        serverRole = role?.trim()?.lowercase()?.takeIf { it.isNotBlank() },
        status = status,
        model = configuredModel ?: model ?: currentProfile?.model,
        runtimeModelKind = runtimeConfig?.model?.normalizedKindOrNull(),
        runtime = runtimeConfig?.runtime?.takeIf { it.isNotBlank() } ?: runtime ?: currentProfile?.runtime,
        reasoning = reasoningEffort ?: currentProfile?.reasoningEffort,
        executionMode = executionMode ?: currentProfile?.executionMode,
        machineId = machineId ?: currentProfile?.machineId,
        machineName = currentProfile?.machineName,
        daemonVersion = daemonVersion ?: currentProfile?.daemonVersion,
        avatarUrl = avatarUrl,
        creatorId = creatorId,
        creatorType = creatorType,
        creator = creator?.toDomain(),
        createdAt = createdAt,
        envVarNames = envVars.keys.filter { it.isNotBlank() }.sorted(),
        envVars = envVars.toAgentEnvVars(),
        createdAgents = createdAgents.mapNotNull { it.toDomain() },
        activity = activity,
        activityDetail = activityDetail,
        deletedAt = deletedAt
    )
}

@Serializable
private data class AgentRuntimeConfigWireDto(
    val runtime: String? = null,
    val model: AgentRuntimeModelWireDto? = null
)

@Serializable
private data class AgentRuntimeModelWireDto(
    val kind: String? = null,
    val id: String? = null,
    val name: String? = null
) {
    fun normalizedKindOrNull(): String? =
        kind?.trim()?.lowercase()?.takeIf { it == "preset" || it == "custom" }

    fun valueOrNull(): String? =
        when (normalizedKindOrNull()) {
            "preset" -> id?.trim()?.takeIf { it.isNotBlank() }
            "custom" -> name?.trim()?.takeIf { it.isNotBlank() }
            else -> null
        }
}

private fun Map<String, JsonElement>.toAgentEnvVars(): Map<String, String> =
    entries
        .mapNotNull { (key, value) ->
            val cleanKey = key.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            cleanKey to ((value as? JsonPrimitive)?.contentOrNull ?: value.toString())
        }
        .sortedBy { it.first }
        .toMap()

private fun AgentCreatorWireDto.toDomain(): AgentCreatorSummary? {
    if (id.isBlank() || name.isBlank()) return null
    return AgentCreatorSummary(
        type = type,
        id = id,
        name = name,
        displayName = displayName,
        avatarUrl = avatarUrl
    )
}

@Serializable
private data class AgentCreatedWireDto(
    val id: String,
    val name: String? = null,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val runtime: String? = null,
    val status: String? = null
) {
    fun toDomain(): AgentCreatedSummary? {
        val resolvedName = displayName?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() }
        if (id.isBlank() || resolvedName.isNullOrBlank()) return null
        return AgentCreatedSummary(
            id = id,
            name = name ?: resolvedName,
            displayName = displayName,
            avatarUrl = avatarUrl,
            runtime = runtime,
            status = status
        )
    }
}

@Serializable
private data class AgentRuntimeProfileDto(
    val current: AgentRuntimeProfileCurrentDto? = null
)

@Serializable
private data class AgentRuntimeProfileCurrentDto(
    val machineId: String? = null,
    val machineName: String? = null,
    val runtime: String? = null,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val executionMode: String? = null,
    val daemonVersion: String? = null
)

@Serializable
private data class AgentCommandResponseDto(val ok: Boolean)

@Serializable
private data class AgentScopesWireDto(
    val agentId: String,
    val granted: List<String>,
    val mode: String = "default",
    val revision: Long = 0L,
    val updatedAt: String? = null
)

@Serializable
private data class AgentScopesUpdateDto(
    val scopes: List<String> = emptyList()
)

private fun AgentScopesWireDto.toScopeSet(agentId: MemberId): AgentScopeSet =
    AgentScopeSet(
        agentId = agentId,
        granted = granted,
        mode = mode,
        revision = revision,
        updatedAt = updatedAt
    )

@Serializable
private data class AgentDmWireDto(val id: String)

@Serializable
private data class AgentActivityWireDto(val id: String)

@Serializable
private data class AgentWorkspaceFilesResponseDto(
    val files: List<AgentWorkspaceFileEntryDto>,
)

@Serializable
private data class AgentWorkspaceFileEntryDto(val path: String)

@Serializable
private data class AgentWorkspaceFileReadDto(
    val path: String,
    val content: String? = null,
    val binary: Boolean? = null,
    val size: Long? = null,
)

@Serializable
private data class AgentSkillsResponseDto(
    val global: List<AgentSkillEntryDto>,
    val workspace: List<AgentSkillEntryDto>,
)

@Serializable
private data class AgentSkillEntryDto(val name: String? = null)

@Serializable
private data class AgentScopesResponseDto(
    val agentId: String? = null,
    val granted: List<String>,
    val revision: Long? = null,
)

@Serializable
private data class AgentBootstrapTokenResponseDto(
    val tokenId: String? = null,
    val tokenPrefix: String? = null,
    val bootstrapToken: String? = null,
    val scopes: List<String> = emptyList(),
)

// ── Request bodies ──

@Serializable
internal data class CreateAgentBody(
    val name: String,
    val description: String? = null,
    val model: String? = null,
    val runtime: String? = null,
    val reasoningEffort: String? = null,
    val machineId: String? = null,
    val envVars: Map<String, String>? = null,
    val avatarUrl: String? = null,
    val onboarding: Boolean? = null,
    /**
     * Full RuntimeConfig object (mirrors `slock-ai/shared` `RuntimeConfig`).
     * When provided, the server picks up `model` / `runtime` / `mode` / `provider` /
     * `command` from this nested object instead of the legacy top-level fields above.
     * The legacy fields are kept so the Thread action card path
     * (which doesn't collect runtimeConfig yet) keeps working unchanged.
     */
    val runtimeConfig: build.raft.app.model.RuntimeConfig? = null,
    val formDefinitionRef: SharedRuntimeFormDefinitionRef? = null,
    /**
     * Indicates this create call comes from the external-agent flow (no Computer).
     * Mirrors React `CreateAgentDialog` `external` flag.
     */
    val external: Boolean? = null
)

@Serializable
internal data class UpdateAgentBody(
    val displayName: String? = null,
    val description: String? = null,
    val avatarUrl: String? = null,
    val model: String? = null,
    val runtime: String? = null,
    val reasoningEffort: String? = null,
    val envVars: Map<String, String>? = null,
    val restartMode: String? = null
)

@Serializable
internal data class ResetAgentBody(val mode: String)

@Serializable
internal data class AssignAgentMachineBody(val machineId: String?)

@Serializable
internal data class UpdateAgentScopesBody(val scopes: List<String>? = null, val mode: String? = null)

@Serializable
internal data class IssueAgentBootstrapTokenBody(val scopes: List<String>? = null, val ttlMs: Long? = null)

internal fun buildCreateAgentBody(body: CreateAgentBody): String = agentsApiJson.encodeToString(body)
internal fun buildUpdateAgentBody(body: UpdateAgentBody): String = agentsApiJson.encodeToString(body)
internal fun buildResetAgentBody(mode: String): String = agentsApiJson.encodeToString(ResetAgentBody(mode))
internal fun buildAssignAgentMachineBody(machineId: String?): String =
    agentsApiJson.encodeToString(AssignAgentMachineBody(machineId = machineId))
internal fun buildUpdateAgentScopesBody(body: UpdateAgentScopesBody): String = agentsApiJson.encodeToString(body)
internal fun buildIssueAgentBootstrapTokenBody(body: IssueAgentBootstrapTokenBody): String =
    agentsApiJson.encodeToString(body)
